package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Change of one class between a baseline and a current snapshot.
 *
 * @param deltaRetainedBytes {@code null} unless both snapshots are deep
 * @param newClass           the class had no instances in the baseline
 * @param survivalScore      0..100: how strongly the growth looks like objects created between the two snapshots
 *                           that are still held; gated by absolute growth, weighted by relative growth and by how
 *                           reliable the snapshots are (live-only dumps contain reachable objects only)
 * @param retentionPaths     for grown classes of a deep current snapshot: how its instances are held there, i.e.
 *                           through which roots the delta is retained; empty otherwise
 */
public record ClassDiffDto(
        String className,
        PackageCategory packageCategory,
        MemoryGroup memoryGroup,
        long instancesBefore,
        long instancesAfter,
        long deltaInstances,
        long shallowBytesBefore,
        long shallowBytesAfter,
        long deltaShallowBytes,
        Long retainedBytesBefore,
        Long retainedBytesAfter,
        Long deltaRetainedBytes,
        boolean newClass,
        int survivalScore,
        List<RetentionPathDto> retentionPaths
) {
    public ClassDiffDto {
        retentionPaths = retentionPaths == null ? List.of() : List.copyOf(retentionPaths);
    }

    public ClassDiffDto(String className, PackageCategory packageCategory, MemoryGroup memoryGroup,
                        long instancesBefore, long instancesAfter, long deltaInstances, long shallowBytesBefore,
                        long shallowBytesAfter, long deltaShallowBytes, Long retainedBytesBefore,
                        Long retainedBytesAfter, Long deltaRetainedBytes, boolean newClass, int survivalScore) {
        this(className, packageCategory, memoryGroup, instancesBefore, instancesAfter, deltaInstances,
                shallowBytesBefore, shallowBytesAfter, deltaShallowBytes, retainedBytesBefore, retainedBytesAfter,
                deltaRetainedBytes, newClass, survivalScore, List.of());
    }
}
