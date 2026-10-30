package com.poludnitsa.inspector.snapshot;

import com.poludnitsa.inspector.config.InspectorProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Guarantees that raw heap dumps and NetBeans index files never outlive their analysis.
 *
 * <p>Every deep snapshot works in its own directory {@code <work-dir>/work/<snapshotId>/} holding {@code heap.hprof}
 * and the {@code heap.hprof.nbcache/} directory where the NetBeans profiler lib puts its {@code NBProfiler*} index
 * files. The directory is removed as a whole:
 * <ul>
 *   <li>right after the analysis (the caller's {@code finally});</li>
 *   <li>by a JVM shutdown hook for directories still in use when the inspector stops;</li>
 *   <li>at start-up for leftovers of a killed inspector ({@code kill -9} skips shutdown hooks);</li>
 *   <li>by a periodic retry for directories that could not be deleted yet.</li>
 * </ul>
 * The heap files are memory-mapped. On Linux a deleted file keeps its blocks until it is unmapped, and on Windows a
 * mapped file cannot be deleted at all, so deletion is followed by a GC <em>of the inspector's own JVM</em> to release
 * the mappings.
 */
@Component
public final class DumpFileCleaner implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DumpFileCleaner.class);
    private static final int DELETE_ATTEMPTS = 6;

    private final Path workRoot;
    private final Set<Path> active = ConcurrentHashMap.newKeySet();
    private final Set<Path> retry = ConcurrentHashMap.newKeySet();

    public DumpFileCleaner(InspectorProperties properties) {
        this.workRoot = properties.snapshots().resolvedWorkDir().resolve("work").toAbsolutePath().normalize();
        try {
            Files.createDirectories(workRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + workRoot, e);
        }
        sweepLeftovers();
        Runtime.getRuntime().addShutdownHook(new Thread(this::deleteEverything, "heap-dump-cleanup"));
    }

    public Path workRoot() {
        return workRoot;
    }

    /** Creates the private directory of one snapshot and tracks it until it is deleted. */
    public Path createWorkDir(String snapshotId) throws IOException {
        Path dir = workRoot.resolve(snapshotId).normalize();
        if (!dir.getParent().equals(workRoot)) {
            throw new IllegalArgumentException("Invalid snapshot id " + snapshotId);
        }
        active.add(dir);
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Deletes the directory with everything in it, retrying while the inspector still holds memory mappings.
     *
     * @return {@code true} when nothing is left; otherwise the directory is retried in the background
     */
    public boolean deleteNow(Path dir) {
        IOException last = null;
        for (int attempt = 0; attempt < DELETE_ATTEMPTS; attempt++) {
            try {
                deleteRecursively(dir);
                active.remove(dir);
                retry.remove(dir);
                releaseMappings();
                return true;
            } catch (IOException e) {
                last = e;
                releaseMappings();
                sleep(200L * (attempt + 1));
            }
        }
        active.remove(dir);
        retry.add(dir);
        log.warn("Could not delete {} yet ({}); retrying in the background", dir, last);
        return false;
    }

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void retryPending() {
        for (Path dir : retry) {
            try {
                deleteRecursively(dir);
                retry.remove(dir);
                log.info("Deleted {} on retry", dir);
            } catch (IOException e) {
                log.debug("Still cannot delete {}: {}", dir, e.toString());
            }
        }
        if (!retry.isEmpty()) {
            releaseMappings();
        }
    }

    /** Directories not deleted yet: in use or awaiting a retry. */
    public Set<Path> pending() {
        Set<Path> all = ConcurrentHashMap.newKeySet();
        all.addAll(active);
        all.addAll(retry);
        return all;
    }

    @Override
    public void destroy() {
        deleteEverything();
    }

    private void deleteEverything() {
        for (Path dir : pending()) {
            try {
                deleteRecursively(dir);
                active.remove(dir);
                retry.remove(dir);
            } catch (IOException e) {
                log.warn("Could not delete {} on shutdown: {}", dir, e.toString());
            }
        }
    }

    private void sweepLeftovers() {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(workRoot)) {
            for (Path child : children) {
                try {
                    deleteRecursively(child);
                    log.info("Deleted leftover heap dump files {}", child);
                } catch (IOException e) {
                    retry.add(child);
                    log.warn("Could not delete leftover {}: {}", child, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("Cannot list {}: {}", workRoot, e.toString());
        }
    }

    static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null && !(failure instanceof NoSuchFileException)) {
                    throw failure;
                }
                deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteIfExists(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (NoSuchFileException ignored) {
            // removed concurrently
        }
    }

    /** Lets the inspector's JVM unmap buffers and close descriptors of heap files nobody references any more. */
    private static void releaseMappings() {
        System.gc();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
