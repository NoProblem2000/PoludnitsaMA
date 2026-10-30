package com.poludnitsa.inspector.api.dto;

/**
 * Shallow memory per {@link MemoryGroup}. Shallow sizes partition the heap exactly, so the groups add up to 100%;
 * retained sizes overlap between classes and are therefore reported per class and per object only.
 */
public record MemoryGroupStatsDto(
        MemoryGroup group,
        int classCount,
        long instances,
        long shallowBytes,
        double percentOfHeap
) {
}
