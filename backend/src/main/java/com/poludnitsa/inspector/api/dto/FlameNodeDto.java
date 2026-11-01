package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A frame of a call tree (flame graph). Values are in the unit of the metric: microseconds for CPU / WALL, bytes
 * for ALLOC.
 *
 * @param selfValue   weight of the stacks ending in this frame
 * @param totalValue  weight of the stacks passing through it
 * @param totalTimeMs {@code totalValue} in milliseconds for time metrics, {@code null} for ALLOC
 * @param percent     {@code totalValue} of the total of the tree, 0..100
 * @param children    by {@code totalValue}, descending; small ones are cut (see {@code prunedNodes} of the graph)
 */
public record FlameNodeDto(
        String className,
        String methodName,
        PackageCategory packageCategory,
        long selfSamples,
        long totalSamples,
        long selfValue,
        long totalValue,
        Double totalTimeMs,
        double percent,
        List<FlameNodeDto> children
) {
    public FlameNodeDto {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
