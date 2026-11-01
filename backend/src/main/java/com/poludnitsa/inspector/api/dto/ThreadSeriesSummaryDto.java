package com.poludnitsa.inspector.api.dto;

import java.time.Instant;

/**
 * A thread dump series: taken live or read from a jstack / jcmd Thread.print file.
 *
 * @param pid {@code null} for a file
 * @param intervalMs between dumps; for a file, the median gap of the dump timestamps
 * @param threadCount distinct threads in the series
 */
public record ThreadSeriesSummaryDto(
        String seriesId,
        DiagnosticSource source,
        Long pid,
        String name,
        JobStatus status,
        Instant startedAt,
        int dumpsRequested,
        int dumpsTaken,
        Long intervalMs,
        String progressMessage,
        String errorMessage,
        int threadCount,
        int stuckCount,
        int deadlockCount,
        int clusterCount
) {
}
