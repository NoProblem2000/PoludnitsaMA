package com.poludnitsa.inspector.snapshot;

import com.poludnitsa.inspector.api.dto.DiskSpaceCheckDto;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.ProcessType;
import com.poludnitsa.inspector.api.dto.SnapshotKind;
import com.poludnitsa.inspector.api.dto.SnapshotStatus;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import com.poludnitsa.inspector.common.DaemonThreadFactory;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.InsufficientDiskSpaceException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.common.TargetOperationException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.jmx.JmxAttributes;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.jmx.MBeanNames;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Takes a heap dump of a target JVM and turns it into a deep snapshot without ever endangering the server's disk.
 *
 * <ol>
 *   <li>Free space in the work directory must be at least {@code 2.5 x} the target's used heap, checked when the
 *       request arrives and again right before the dump.</li>
 *   <li>{@code HotSpotDiagnosticMXBean.dumpHeap(path, liveObjectsOnly)} runs on the target, through its
 *       {@code MBeanServerConnection}, into the snapshot's private directory.</li>
 *   <li>{@link ObjectGraphAnalyzer} builds the analytical snapshot.</li>
 *   <li>In {@code finally} the whole directory (dump + NetBeans indexes) is deleted; {@link DumpFileCleaner} covers
 *       shutdown and crashes.</li>
 * </ol>
 * One deep snapshot runs at a time: each one pauses the target and may need gigabytes of disk.
 */
@Service
public class SafeHeapDumpService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SafeHeapDumpService.class);
    private static final String DUMP_FILE = "heap.hprof";

    private final JmxConnectionManager connections;
    private final JvmDiscoveryService discovery;
    private final ObjectGraphAnalyzer analyzer;
    private final SnapshotStore store;
    private final DumpFileCleaner cleaner;
    private final InspectorProperties.Snapshots props;
    private final Semaphore oneAtATime = new Semaphore(1);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory("heap-dump"));

    public SafeHeapDumpService(JmxConnectionManager connections, JvmDiscoveryService discovery,
                               ObjectGraphAnalyzer analyzer, SnapshotStore store, DumpFileCleaner cleaner,
                               InspectorProperties properties) {
        this.connections = connections;
        this.discovery = discovery;
        this.analyzer = analyzer;
        this.store = store;
        this.cleaner = cleaner;
        this.props = properties.snapshots();
    }

    /** {@code usableSpace >= factor * targetUsedHeap} for the directory the dump would be written to. */
    public DiskSpaceCheckDto checkDiskSpace(long pid) {
        Path directory = cleaner.workRoot();
        long heapUsed = connections.call(pid, (connection, metadata) -> JmxAttributes.heapUsed(connection));
        long usable;
        try {
            usable = Files.getFileStore(directory).getUsableSpace();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read free space of " + directory, e);
        }
        double factor = props.effectiveDiskSpaceFactor();
        long required = (long) Math.ceil(factor * Math.max(0, heapUsed));
        return new DiskSpaceCheckDto(pid, directory.toString(), heapUsed, factor, required, usable,
                usable >= required);
    }

    /**
     * Validates the request synchronously (disk space, one at a time), then dumps and analyses in the background.
     * Poll the returned snapshot for progress.
     */
    public SnapshotSummaryDto startDeepSnapshot(long pid, boolean liveObjectsOnly) {
        if (!oneAtATime.tryAcquire()) {
            throw new OperationRejectedException(409, "SNAPSHOT_IN_PROGRESS",
                    "Another heap dump is being taken or analysed; one deep snapshot runs at a time");
        }
        boolean handedOver = false;
        try {
            DiskSpaceCheckDto check = checkDiskSpace(pid);
            if (!check.sufficient()) {
                throw new InsufficientDiskSpaceException(check);
            }
            JvmProcessDto process = describe(pid);
            SnapshotRecord record = store.create(SnapshotKind.DEEP, pid,
                    process == null ? "PID " + pid : process.displayName(),
                    process == null ? ProcessType.STANDARD_JVM : process.processType(), liveObjectsOnly);
            executor.execute(() -> run(record, liveObjectsOnly));
            handedOver = true;
            return record.toSummary();
        } catch (RejectedExecutionException e) {
            throw new OperationRejectedException(503, "SHUTTING_DOWN", "The inspector is shutting down");
        } finally {
            if (!handedOver) {
                oneAtATime.release();
            }
        }
    }

    /**
     * The final status (READY / FAILED) is published last, after the files are deleted and the permit is free:
     * once a client sees a finished snapshot, the disk is clean and the next dump can start.
     */
    private void run(SnapshotRecord record, boolean liveObjectsOnly) {
        long pid = record.pid();
        Path workDir = null;
        SnapshotData data = null;
        String failure = null;
        try {
            workDir = cleaner.createWorkDir(record.id());
            Path hprof = workDir.resolve(DUMP_FILE).toAbsolutePath();

            // Other processes may have filled the disk since the request was accepted.
            DiskSpaceCheckDto check = checkDiskSpace(pid);
            if (!check.sufficient()) {
                throw new InsufficientDiskSpaceException(check);
            }
            record.phase(SnapshotStatus.DUMPING, liveObjectsOnly
                    ? "Full GC + writing heap dump on the target (stop-the-world)"
                    : "Writing heap dump on the target without Full GC (stop-the-world while writing)");
            long started = System.nanoTime();
            dumpHeap(pid, hprof, liveObjectsOnly);
            long dumpMs = (System.nanoTime() - started) / 1_000_000;
            if (!Files.isRegularFile(hprof)) {
                throw new InspectorException(502, "DUMP_NOT_VISIBLE", "The target reported success but " + hprof
                        + " does not exist for the inspector. The target sees a different file system (remote host,"
                        + " container or systemd PrivateTmp); set inspector.snapshots.work-dir to a shared path.");
            }
            record.dumped(dumpMs, Files.size(hprof));
            log.info("Heap dump of PID {} written in {} ms: {} bytes (live={})", pid, dumpMs, Files.size(hprof),
                    liveObjectsOnly);

            data = analyzer.analyze(hprof, record);
            record.phase(SnapshotStatus.ANALYZING, "Deleting raw heap dump and indexes");
        } catch (InspectorException e) {
            failure = logFailure(record, e.getMessage(), e);
        } catch (IOException | UncheckedIOException e) {
            failure = logFailure(record, "I/O error: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            failure = logFailure(record, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (OutOfMemoryError e) {
            failure = logFailure(record, "The inspector ran out of memory while analysing the dump; start it with a"
                    + " larger -Xmx", e);
        } finally {
            try {
                if (workDir != null) {
                    record.rawDumpDeleted(cleaner.deleteNow(workDir));
                }
            } finally {
                oneAtATime.release();
                if (data != null && failure == null) {
                    store.complete(record);
                } else {
                    record.fail(failure != null ? failure : "Aborted");
                }
            }
        }
    }

    private void dumpHeap(long pid, Path hprof, boolean liveObjectsOnly) {
        connections.call(pid, props.dumpTimeout(), (connection, metadata) -> {
            if (!metadata.hotSpotDiagnosticAvailable()) {
                throw new OperationRejectedException(409, "HOTSPOT_DIAGNOSTIC_UNAVAILABLE",
                        "Target has no com.sun.management:type=HotSpotDiagnostic MBean (not a HotSpot JVM?)");
            }
            HotSpotDiagnosticMXBean diagnostic = ManagementFactory.newPlatformMXBeanProxy(connection,
                    MBeanNames.HOTSPOT_DIAGNOSTIC.getCanonicalName(), HotSpotDiagnosticMXBean.class);
            try {
                diagnostic.dumpHeap(hprof.toString(), liveObjectsOnly);
            } catch (IOException e) {
                if (e instanceof RemoteException) {
                    throw e;
                }
                // Raised by the target itself (disk full on its side, file exists, permissions).
                throw new TargetOperationException(pid, "dumpHeap failed on the target: " + e.getMessage(), e);
            }
            return null;
        });
    }

    private JvmProcessDto describe(long pid) {
        try {
            return discovery.describe(pid);
        } catch (InspectorException e) {
            return null;
        }
    }

    private static String logFailure(SnapshotRecord record, String message, Throwable cause) {
        log.warn("Deep snapshot {} of PID {} failed: {}", record.id(), record.pid(), message, cause);
        return message;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
