package com.poludnitsa.inspector.api.dto;

/**
 * Per-class statistics of a snapshot, aggregated over all class loaders defining a class with this name.
 *
 * @param retainedBytes   memory retained by all instances of the class as a set
 *                        ({@code JavaClass#getRetainedSizeByClass()}); {@code null} for histograms
 * @param definitionCount class loaders defining this name; above 1 for application classes hints at a class loader
 *                        leak after redeploy
 * @param sample          largest instance with its GC root path, only for the top classes by retained size
 */
public record ClassStatsDto(
        String className,
        PackageCategory packageCategory,
        MemoryGroup memoryGroup,
        long instances,
        long shallowBytes,
        Long retainedBytes,
        int definitionCount,
        ClassSampleDto sample
) {
}
