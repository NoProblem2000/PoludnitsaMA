package com.poludnitsa.inspector.snapshot;

import com.poludnitsa.inspector.api.dto.ProcessType;
import com.poludnitsa.inspector.api.dto.SnapshotKind;
import com.poludnitsa.inspector.api.dto.SnapshotStatus;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/** Mutable state of one snapshot while it is being taken and analysed; immutable data once READY. */
public final class SnapshotRecord {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** Breaks ties between snapshots created within the same clock tick. */
    private final long sequence = SEQUENCE.incrementAndGet();
    private final String id;
    private final SnapshotKind kind;
    private final long pid;
    private final String displayName;
    private final ProcessType processType;
    private final Instant createdAt;
    private final boolean liveObjectsOnly;

    private SnapshotStatus status;
    private String progressMessage;
    private IntSupplier progressSource;
    private int progressPercent;
    private long hprofFileSizeBytes;
    private boolean rawDumpDeleted;
    private Long dumpDurationMs;
    private Long indexingDurationMs;
    private Long analysisDurationMs;
    private String errorMessage;
    private SnapshotData data;

    SnapshotRecord(String id, SnapshotKind kind, long pid, String displayName, ProcessType processType,
                   Instant createdAt, boolean liveObjectsOnly) {
        this.id = id;
        this.kind = kind;
        this.pid = pid;
        this.displayName = displayName;
        this.processType = processType;
        this.createdAt = createdAt;
        this.liveObjectsOnly = liveObjectsOnly;
        this.status = kind == SnapshotKind.DEEP ? SnapshotStatus.DUMPING : SnapshotStatus.ANALYZING;
        this.progressMessage = "Queued";
    }

    static SnapshotRecord restored(SnapshotSummaryDto summary, SnapshotData data) {
        SnapshotRecord record = new SnapshotRecord(summary.snapshotId(), summary.kind(), summary.sourcePid(),
                summary.sourceDisplayName(), summary.sourceProcessType(), summary.createdAt(),
                summary.liveObjectsOnly());
        record.status = SnapshotStatus.READY;
        record.progressMessage = "Restored from summary";
        record.progressPercent = 100;
        record.hprofFileSizeBytes = summary.hprofFileSizeBytes();
        record.rawDumpDeleted = summary.rawDumpDeleted();
        record.dumpDurationMs = summary.dumpDurationMs();
        record.indexingDurationMs = summary.indexingDurationMs();
        record.analysisDurationMs = summary.analysisDurationMs();
        record.data = data;
        return record;
    }

    public String id() {
        return id;
    }

    public SnapshotKind kind() {
        return kind;
    }

    public long pid() {
        return pid;
    }

    public Instant createdAt() {
        return createdAt;
    }

    long sequence() {
        return sequence;
    }

    public boolean liveObjectsOnly() {
        return liveObjectsOnly;
    }

    public synchronized SnapshotStatus status() {
        return status;
    }

    public synchronized boolean isFinished() {
        return status == SnapshotStatus.READY || status == SnapshotStatus.FAILED;
    }

    public synchronized SnapshotData data() {
        return data;
    }

    synchronized void phase(SnapshotStatus newStatus, String message) {
        status = newStatus;
        progressMessage = message;
        progressPercent = 0;
    }

    /** Live progress of the current phase (NetBeans reports it on the analysing thread). */
    synchronized void progressSource(IntSupplier source) {
        progressSource = source;
    }

    synchronized void dumped(long durationMs, long fileSizeBytes) {
        dumpDurationMs = durationMs;
        hprofFileSizeBytes = fileSizeBytes;
    }

    synchronized void indexed(long durationMs) {
        indexingDurationMs = durationMs;
    }

    synchronized void analysed(Long durationMs, SnapshotData result) {
        analysisDurationMs = durationMs;
        data = result;
    }

    synchronized void rawDumpDeleted(boolean deleted) {
        rawDumpDeleted = deleted;
    }

    synchronized void ready() {
        status = SnapshotStatus.READY;
        progressMessage = "Ready";
        progressPercent = 100;
        progressSource = null;
    }

    synchronized void fail(String message) {
        status = SnapshotStatus.FAILED;
        progressMessage = "Failed";
        errorMessage = message;
        progressSource = null;
        data = null;
    }

    public synchronized SnapshotSummaryDto toSummary() {
        int percent = progressPercent;
        if (progressSource != null) {
            percent = Math.max(0, Math.min(100, progressSource.getAsInt()));
        }
        SnapshotData current = data;
        return new SnapshotSummaryDto(id, kind, pid, displayName, processType, createdAt, liveObjectsOnly, status,
                percent, progressMessage, hprofFileSizeBytes, rawDumpDeleted, dumpDurationMs, indexingDurationMs,
                analysisDurationMs,
                current == null ? 0 : current.totalInstances(),
                current == null ? 0 : current.totalClasses(),
                current == null ? 0 : current.totalHeapBytes(),
                current == null ? 0 : current.gcRootCount(),
                current != null && current.retainedSizesAvailable(),
                errorMessage);
    }
}
