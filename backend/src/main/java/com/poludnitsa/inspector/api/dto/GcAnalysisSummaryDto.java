package com.poludnitsa.inspector.api.dto;

import java.time.Instant;

/**
 * A GC log or live GC recording.
 *
 * @param pid {@code null} for a file
 * @param name file name or process
 * @param durationSeconds requested recording length (live)
 * @param collector G1, Parallel, Serial, ZGC, Shenandoah or {@code null}
 * @param coveredMs time span of the log
 */
public record GcAnalysisSummaryDto(
        String analysisId,
        DiagnosticSource source,
        Long pid,
        String name,
        JobStatus status,
        Instant startedAt,
        Long durationSeconds,
        String progressMessage,
        String errorMessage,
        String collector,
        String jvmVersion,
        int pauseCount,
        double coveredMs
) {
}
