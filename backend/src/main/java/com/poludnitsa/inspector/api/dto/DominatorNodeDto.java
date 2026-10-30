package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Node of the dominator tree. Children are fetched lazily by {@code objectId}.
 *
 * @param objectId      HPROF object id
 * @param shallowSize   bytes of the object itself
 * @param retainedSize  bytes freed if this object became unreachable
 * @param percentOfHeap {@code retainedSize / totalHeapBytes * 100}
 * @param gcRootPath    shortest path to a GC root; empty when not requested (resolved on demand)
 * @param childCount    number of directly dominated objects
 */
public record DominatorNodeDto(
        long objectId,
        String className,
        PackageCategory packageCategory,
        long shallowSize,
        long retainedSize,
        double percentOfHeap,
        List<GcRootPathStepDto> gcRootPath,
        int childCount
) {
    public DominatorNodeDto {
        gcRootPath = gcRootPath == null ? List.of() : List.copyOf(gcRootPath);
    }
}
