package com.poludnitsa.inspector.api.dto;

import java.time.Instant;

/**
 * Heap snapshot registered in the current session, either dumped from a target PID or uploaded as a .hprof file.
 *
 * @param sourcePid               {@code null} for uploaded files
 * @param sourceDisplayName       process name, or the file name for uploads
 * @param sourceProcessType       {@code null} for uploads
 * @param liveObjectsOnly         {@code true} when dumped after a Full GC ({@code dumpHeap(path, true)})
 * @param indexingProgressPercent 0..100
 * @param indexingDurationMs      {@code null} until indexing has finished
 * @param totalInstances          {@code 0} until {@link SnapshotStatus#READY}
 * @param totalHeapBytes          sum of shallow sizes of all instances
 * @param errorMessage            set only when {@link SnapshotStatus#FAILED}
 */
public record SnapshotSummaryDto(
        String snapshotId,
        Long sourcePid,
        String sourceDisplayName,
        ProcessType sourceProcessType,
        String hprofPath,
        long fileSizeBytes,
        Instant createdAt,
        boolean liveObjectsOnly,
        SnapshotStatus status,
        int indexingProgressPercent,
        Long indexingDurationMs,
        long totalInstances,
        int totalClasses,
        long totalHeapBytes,
        int gcRootCount,
        String errorMessage
) {
}
