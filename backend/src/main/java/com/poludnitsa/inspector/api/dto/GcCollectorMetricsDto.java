package com.poludnitsa.inspector.api.dto;

/**
 * Counters of one {@code GarbageCollectorMXBean} of the target JVM.
 *
 * @param collectionCount  cumulative collections since JVM start
 * @param collectionTimeMs cumulative collection time since JVM start
 * @param countDelta       collections since the previous sample
 * @param timeDeltaMs      collection time since the previous sample
 * @param lastPauseMs      duration of the last collection from {@code com.sun.management.GcInfo}; {@code null} if none yet
 */
public record GcCollectorMetricsDto(
        String name,
        GcGeneration generation,
        long collectionCount,
        long collectionTimeMs,
        long countDelta,
        long timeDeltaMs,
        Long lastPauseMs
) {
}
