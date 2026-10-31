package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Per-class statistics of a snapshot.
 *
 * @param retainedBytes   sum of retained sizes of the class's instances; {@code null} for histogram snapshots
 * @param definitionCount how many class loaders define a class of this name ({@code > 1} hints at a redeploy leak)
 * @param sample          the largest instance with its GC root path; only for the top classes by retained size
 * @param retentionPaths  how sampled instances are held, most common shape first; only for the top classes of deep
 *                        snapshots
 */
public record ClassStatsDto(
        String className,
        PackageCategory packageCategory,
        MemoryGroup memoryGroup,
        long instances,
        long shallowBytes,
        Long retainedBytes,
        int definitionCount,
        ClassSampleDto sample,
        List<RetentionPathDto> retentionPaths
) {
    public ClassStatsDto {
        retentionPaths = retentionPaths == null ? List.of() : List.copyOf(retentionPaths);
    }

    public ClassStatsDto(String className, PackageCategory packageCategory, MemoryGroup memoryGroup, long instances,
                         long shallowBytes, Long retainedBytes, int definitionCount, ClassSampleDto sample) {
        this(className, packageCategory, memoryGroup, instances, shallowBytes, retainedBytes, definitionCount, sample,
                List.of());
    }
}
