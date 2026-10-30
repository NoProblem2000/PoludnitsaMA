package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * One sample pushed to {@code /topic/metrics/{pid}}. Every value is read from the target JVM through
 * {@code MBeanServerConnection}, never from the inspector's own JVM.
 *
 * @param timestampEpochMs          time the sample was taken
 * @param heap                      {@code MemoryMXBean#getHeapMemoryUsage()}
 * @param nonHeap                   {@code MemoryMXBean#getNonHeapMemoryUsage()}
 * @param pools                     all memory pools, heap and non-heap (Metaspace, Compressed Class Space, Code Cache)
 * @param gcCollectors              one entry per {@code GarbageCollectorMXBean}
 * @param gcOverheadPercent         share of wall time spent in GC since the previous sample, 0..100
 * @param histogramTimestampEpochMs time of the class histogram behind {@code topGrowingClasses};
 *                                  histograms are sampled less often than pools, {@code null} if none yet
 * @param topGrowingClasses         ordered by {@code leakProbabilityScore} descending
 */
public record LiveMetricsPayloadDto(
        long pid,
        long timestampEpochMs,
        MemoryUsageDto heap,
        MemoryUsageDto nonHeap,
        List<MemoryPoolDto> pools,
        List<GcCollectorMetricsDto> gcCollectors,
        double gcOverheadPercent,
        Long histogramTimestampEpochMs,
        List<ClassGrowthSuspectDto> topGrowingClasses
) {
    public LiveMetricsPayloadDto {
        pools = pools == null ? List.of() : List.copyOf(pools);
        gcCollectors = gcCollectors == null ? List.of() : List.copyOf(gcCollectors);
        topGrowingClasses = topGrowingClasses == null ? List.of() : List.copyOf(topGrowingClasses);
    }
}
