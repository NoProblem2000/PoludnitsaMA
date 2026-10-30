package com.poludnitsa.inspector.api.dto;

/**
 * Compact point of the live metrics history (one per sample), for charts and reports. Bytes; {@code -1} = unknown.
 *
 * @param oldGenUsedAfterGc old-generation usage after the last collection: the clearest leak trend line
 */
public record MetricsPointDto(
        long timestampEpochMs,
        long heapUsed,
        long heapCommitted,
        long heapMax,
        long nonHeapUsed,
        long metaspaceUsed,
        long oldGenUsed,
        long oldGenUsedAfterGc,
        double gcOverheadPercent,
        double gcCollectionsPerMin,
        Double allocationRateBytesPerSec
) {

    public static MetricsPointDto of(LiveMetricsPayloadDto payload) {
        long metaspace = -1;
        long oldUsed = -1;
        long oldAfterGc = -1;
        for (MemoryPoolDto pool : payload.pools()) {
            if (pool.kind() == MemoryPoolKind.METASPACE) {
                metaspace = pool.used();
            } else if (pool.kind() == MemoryPoolKind.OLD && oldUsed < 0) {
                oldUsed = pool.used();
                oldAfterGc = pool.usedAfterLastGc() == null ? -1 : pool.usedAfterLastGc();
            }
        }
        return new MetricsPointDto(payload.timestampEpochMs(), payload.heap().used(), payload.heap().committed(),
                payload.heap().max(), payload.nonHeap().used(), metaspace, oldUsed, oldAfterGc,
                payload.gcOverheadPercent(), payload.gcCollectionsPerMin(), payload.allocationRateBytesPerSec());
    }
}
