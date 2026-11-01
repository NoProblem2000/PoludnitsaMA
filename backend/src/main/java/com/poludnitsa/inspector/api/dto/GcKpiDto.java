package com.poludnitsa.inspector.api.dto;

/**
 * Headline numbers of a GC log.
 *
 * @param throughputPercent time not paused by GC over the covered span
 * @param allocationRateMbPerSec heap growth between collections, {@code null} without heap sizes
 * @param promotionRateMbPerSec old generation growth in young collections, {@code null} when not logged
 * @param safepointTotalMs all safepoint time (GC and others), {@code null} without safepoint data
 */
public record GcKpiDto(
        double throughputPercent,
        double totalPauseMs,
        double maxPauseMs,
        double avgPauseMs,
        double p95PauseMs,
        double p99PauseMs,
        int pauseCount,
        int youngCount,
        int mixedCount,
        int fullCount,
        Double allocationRateMbPerSec,
        Double promotionRateMbPerSec,
        Double peakHeapMb,
        Double avgHeapAfterGcMb,
        Double safepointTotalMs
) {
}
