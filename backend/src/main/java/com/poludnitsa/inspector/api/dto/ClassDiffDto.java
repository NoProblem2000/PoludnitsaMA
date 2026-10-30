package com.poludnitsa.inspector.api.dto;

/**
 * Change of one class between a baseline and a current snapshot.
 *
 * @param deltaRetainedBytes {@code null} unless both snapshots are deep
 * @param newClass           the class had no instances in the baseline
 * @param survivalScore      0..100: how strongly the growth looks like objects created between the two snapshots
 *                           that are still held; gated by absolute growth, weighted by relative growth and by how
 *                           reliable the snapshots are (live-only dumps contain reachable objects only)
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
        int survivalScore
) {
}
