package com.poludnitsa.inspector.api.dto;

import java.time.Instant;

/**
 * Snapshot registered in the current session: a quick class histogram or an analysed heap dump.
 *
 * @param liveObjectsOnly         {@code true} when dumped after a Full GC ({@code dumpHeap(path, true)});
 *                                always {@code false} for histograms ({@code -all})
 * @param progressPercent         0..100 within the current phase
 * @param progressMessage         human-readable current phase
 * @param hprofFileSizeBytes      size of the raw dump, {@code 0} for histograms
 * @param rawDumpDeleted          the .hprof and the NetBeans index directory have been removed from disk
 * @param dumpDurationMs          duration of {@code dumpHeap} (or of the histogram) on the target: the pause it cost
 * @param indexingDurationMs      {@code null} until indexing finished or for histograms
 * @param analysisDurationMs      {@code null} until the analysis finished or for histograms
 * @param totalHeapBytes          sum of shallow sizes of all objects in the snapshot
 * @param gcRootCount             {@code 0} for histograms
 * @param retainedSizesAvailable  {@code true} for analysed deep snapshots
 * @param errorMessage            set only when {@link SnapshotStatus#FAILED}
 */
public record SnapshotSummaryDto(
        String snapshotId,
        SnapshotKind kind,
        long sourcePid,
        String sourceDisplayName,
        ProcessType sourceProcessType,
        Instant createdAt,
        boolean liveObjectsOnly,
        SnapshotStatus status,
        int progressPercent,
        String progressMessage,
        long hprofFileSizeBytes,
        boolean rawDumpDeleted,
        Long dumpDurationMs,
        Long indexingDurationMs,
        Long analysisDurationMs,
        long totalInstances,
        int totalClasses,
        long totalHeapBytes,
        int gcRootCount,
        boolean retainedSizesAvailable,
        String errorMessage
) {
}
