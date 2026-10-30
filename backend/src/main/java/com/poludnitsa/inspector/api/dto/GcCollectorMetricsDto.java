package com.poludnitsa.inspector.api.dto;

/**
 * Counters of one {@code GarbageCollectorMXBean} of the target JVM.
 *
 * @param stopTheWorld     {@code true} when {@code collectionTime} of this bean is pause time ("G1 Young Generation",
 *                         "ZGC Pauses", ...); {@code false} for concurrent-cycle beans ("ZGC Cycles", "Shenandoah Cycles").
 *                         Only stop-the-world beans contribute to GC overhead.
 * @param collectionCount  cumulative collections since JVM start
 * @param collectionTimeMs cumulative collection time since JVM start
 * @param countDelta       collections since the previous sample
 * @param timeDeltaMs      collection time since the previous sample
 * @param lastPauseMs      duration of the last collection ({@code LastGcInfo.duration}); for concurrent-cycle beans this
 *                         is the cycle duration. {@code null} if no collection happened yet
 */
public record GcCollectorMetricsDto(
        String name,
        GcGeneration generation,
        boolean stopTheWorld,
        long collectionCount,
        long collectionTimeMs,
        long countDelta,
        long timeDeltaMs,
        Long lastPauseMs
) {
}
