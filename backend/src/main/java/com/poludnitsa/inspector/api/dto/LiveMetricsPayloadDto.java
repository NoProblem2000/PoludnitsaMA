package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * One sample streamed over {@code /ws/live}. Every value is read from the target JVM through its
 * {@code MBeanServerConnection}, never from the inspector's own JVM.
 *
 * @param timestampEpochMs          time the sample was taken
 * @param heap                      {@code MemoryMXBean#getHeapMemoryUsage()}
 * @param nonHeap                   {@code MemoryMXBean#getNonHeapMemoryUsage()}
 * @param pools                     all memory pools, heap and non-heap
 * @param gcCollectors              one entry per {@code GarbageCollectorMXBean}
 * @param gcOverheadPercent         share of wall time spent in stop-the-world GC pauses over the last minute, 0..100
 * @param gcCollectionsPerMin       stop-the-world collections over the last minute
 * @param allocationRateBytesPerSec from {@code ThreadMXBean#getTotalThreadAllocatedBytes()}; {@code null} when the
 *                                  target does not support it or before the second sample
 * @param histogramTimestampEpochMs time of the class histogram behind {@code topGrowingClasses}, {@code null} if none yet
 * @param histogramDurationMs       duration of the last {@code GC.class_histogram -all}; it is a safepoint heap walk,
 *                                  so this is the pause the radar costs the target
 * @param histogramIntervalMs       current leak radar interval (grows automatically when histograms are slow);
 *                                  {@code null} when the target has no {@code DiagnosticCommand} MBean
 * @param topGrowingClasses         ordered by {@code leakProbabilityScore} descending
 * @param jfrAllocation             optional JFR allocation profile, {@code null} when not running
 */
public record LiveMetricsPayloadDto(
        long pid,
        long timestampEpochMs,
        MemoryUsageDto heap,
        MemoryUsageDto nonHeap,
        List<MemoryPoolDto> pools,
        List<GcCollectorMetricsDto> gcCollectors,
        double gcOverheadPercent,
        double gcCollectionsPerMin,
        Double allocationRateBytesPerSec,
        Long histogramTimestampEpochMs,
        Long histogramDurationMs,
        Long histogramIntervalMs,
        List<ClassGrowthSuspectDto> topGrowingClasses,
        JfrAllocationStatsDto jfrAllocation
) {
    public LiveMetricsPayloadDto {
        pools = pools == null ? List.of() : List.copyOf(pools);
        gcCollectors = gcCollectors == null ? List.of() : List.copyOf(gcCollectors);
        topGrowingClasses = topGrowingClasses == null ? List.of() : List.copyOf(topGrowingClasses);
    }
}
