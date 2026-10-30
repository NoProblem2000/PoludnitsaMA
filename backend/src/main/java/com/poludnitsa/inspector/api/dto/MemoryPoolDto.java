package com.poludnitsa.inspector.api.dto;

/**
 * Snapshot of one {@code MemoryPoolMXBean} of the target JVM (Eden, Survivor, Old Gen, Metaspace, ...).
 *
 * @param max             {@code -1} when undefined
 * @param usedAfterLastGc used bytes of {@code MemoryPoolMXBean#getCollectionUsage()}: the most reliable leak signal
 *                        for old-generation pools; {@code null} for pools without collection usage (e.g. Metaspace)
 */
public record MemoryPoolDto(
        String name,
        MemoryPoolType type,
        long used,
        long committed,
        long max,
        Long usedAfterLastGc
) {
}
