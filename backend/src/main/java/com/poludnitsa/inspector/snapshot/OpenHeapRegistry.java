package com.poludnitsa.inspector.snapshot;

import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.netbeans.lib.profiler.heap.Heap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Heap dumps kept open after their analysis, for OQL and on-demand GC root paths.
 *
 * <p>An open dump keeps its {@code .hprof} and NetBeans indexes on disk and its mapped buffers in the inspector's
 * address space, so the registry is strict about closing: on request, after {@code inspector.oql.idle-timeout}
 * without use, when more than {@code max-open-heaps} are open (least recently used first) and at shutdown. Closing
 * drops the heap and deletes the snapshot's directory through {@link DumpFileCleaner}.
 *
 * <p>The NetBeans heap is not thread-safe: every use goes through {@link #withHeap}, one caller at a time per dump.
 */
@Component
public class OpenHeapRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OpenHeapRegistry.class);
    private static final Duration BUSY_WAIT = Duration.ofSeconds(5);

    /** One open dump. Per-dump state of the users (an OQL engine) lives in {@link #attachment}. */
    public static final class OpenHeap {
        private final SnapshotRecord record;
        /** dropped on close: while referenced, its memory-mapped files cannot be deleted on Windows */
        private volatile Heap heap;
        private final Path workDir;
        private final Instant openedAt = Instant.now();
        private final ReentrantLock lock = new ReentrantLock();
        private volatile Instant lastUsed = openedAt;
        private volatile Object attachment;
        private volatile boolean closed;

        OpenHeap(SnapshotRecord record, Heap heap, Path workDir) {
            this.record = record;
            this.heap = heap;
            this.workDir = workDir;
        }

        public String snapshotId() {
            return record.id();
        }

        public Heap heap() {
            Heap current = heap;
            if (current == null) {
                throw notOpen(record.id());
            }
            return current;
        }

        public Instant openedAt() {
            return openedAt;
        }

        /** Per-dump object of a user of the registry (created under the dump's lock). */
        @SuppressWarnings("unchecked")
        public <T> T attachment(Class<T> type, Function<Heap, T> create) {
            Object current = attachment;
            if (!type.isInstance(current)) {
                current = create.apply(heap());
                attachment = current;
            }
            return (T) current;
        }

        public Object attachment() {
            return attachment;
        }
    }

    private final DumpFileCleaner cleaner;
    private final InspectorProperties.Oql props;
    private final Map<String, OpenHeap> open = new LinkedHashMap<>();

    public OpenHeapRegistry(DumpFileCleaner cleaner, InspectorProperties properties) {
        this.cleaner = cleaner;
        this.props = properties.oql();
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** Takes over an analysed heap and its directory; the caller must not delete them. */
    public void open(SnapshotRecord record, Heap heap, Path workDir) {
        List<OpenHeap> evicted = new ArrayList<>();
        OpenHeap entry = new OpenHeap(record, heap, workDir);
        synchronized (open) {
            open.put(record.id(), entry);
            while (open.size() > Math.max(1, props.maxOpenHeaps())) {
                OpenHeap oldest = open.values().stream().filter(o -> o != entry)
                        .min(Comparator.comparing(o -> o.lastUsed)).orElseThrow();
                open.remove(oldest.snapshotId());
                evicted.add(oldest);
            }
        }
        record.heapOpened(Instant.now().plus(props.idleTimeout()));
        log.info("Heap dump of snapshot {} kept open for queries ({} idle timeout)", record.id(), props.idleTimeout());
        evicted.forEach(o -> release(o, "more than " + props.maxOpenHeaps() + " open"));
    }

    public boolean isOpen(String snapshotId) {
        synchronized (open) {
            return open.containsKey(snapshotId);
        }
    }

    /**
     * Runs {@code action} with exclusive use of the open dump and moves its idle deadline forward.
     *
     * @throws OperationRejectedException 409 when the dump is not open, or busy for longer than a few seconds
     */
    public <T> T withHeap(String snapshotId, Function<OpenHeap, T> action) {
        OpenHeap entry;
        synchronized (open) {
            entry = open.get(snapshotId);
        }
        if (entry == null) {
            throw notOpen(snapshotId);
        }
        boolean locked;
        try {
            locked = entry.lock.tryLock(BUSY_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationRejectedException(503, "INTERRUPTED", "Interrupted while waiting for the heap dump");
        }
        if (!locked) {
            throw new OperationRejectedException(409, "HEAP_BUSY",
                    "Another query is running on snapshot " + snapshotId + "; try again when it finished");
        }
        try {
            if (entry.closed) {
                throw notOpen(snapshotId);
            }
            touch(entry);
            return action.apply(entry);
        } finally {
            touch(entry);
            entry.lock.unlock();
        }
    }

    /** Closes the dump and deletes its files; {@code false} if it was not open. */
    public boolean close(String snapshotId, String reason) {
        OpenHeap entry;
        synchronized (open) {
            entry = open.remove(snapshotId);
        }
        if (entry == null) {
            return false;
        }
        release(entry, reason);
        return true;
    }

    /** Closes dumps idle for longer than {@code inspector.oql.idle-timeout}. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void closeIdle() {
        Instant cutoff = Instant.now().minus(props.idleTimeout());
        List<String> idle = new ArrayList<>();
        synchronized (open) {
            open.values().stream().filter(o -> o.lastUsed.isBefore(cutoff) && !o.lock.isLocked())
                    .forEach(o -> idle.add(o.snapshotId()));
        }
        idle.forEach(id -> close(id, "idle for " + props.idleTimeout()));
    }

    private void touch(OpenHeap entry) {
        entry.lastUsed = Instant.now();
        entry.record.heapOpened(entry.lastUsed.plus(props.idleTimeout()));
    }

    /** Waits for a running user (a query is cancelled by its own timeout), then drops the heap and the files. */
    private void release(OpenHeap entry, String reason) {
        boolean locked = false;
        try {
            locked = entry.lock.tryLock(props.queryTimeout().toMillis() + BUSY_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            entry.closed = true;
            entry.attachment = null;
            entry.heap = null;
            entry.record.heapClosed();
            entry.record.rawDumpDeleted(cleaner.deleteNow(entry.workDir));
            log.info("Closed open heap dump of snapshot {} ({})", entry.snapshotId(), reason);
        } finally {
            if (locked) {
                entry.lock.unlock();
            }
        }
    }

    public Optional<Instant> openedAt(String snapshotId) {
        synchronized (open) {
            return Optional.ofNullable(open.get(snapshotId)).map(OpenHeap::openedAt);
        }
    }

    private static OperationRejectedException notOpen(String snapshotId) {
        return new OperationRejectedException(409, "HEAP_NOT_OPEN", "The heap dump of snapshot " + snapshotId
                + " is not open. Take a Deep Heap Dump with \"keep open for OQL\" to query it; analysed dumps are"
                + " deleted otherwise.");
    }

    @Override
    public void destroy() {
        List<String> ids;
        synchronized (open) {
            ids = new ArrayList<>(open.keySet());
        }
        ids.forEach(id -> close(id, "shutdown"));
    }
}
