package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * One of the objects with the largest retained size in a deep snapshot.
 *
 * @param objectId        HPROF object id
 * @param shallowSize     bytes of the object itself
 * @param retainedSize    bytes freed if this object became unreachable (it and everything only it keeps alive)
 * @param percentOfHeap   {@code retainedSize / totalHeapBytes * 100}
 * @param gcRootPath      shortest reference chain, GC root first
 * @param gcRootPathText  the same chain object first, e.g.
 *                        {@code OrderDto <- Object[142] <- ArrayList.elementData <- static CACHE in com.acme.OrderService
 *                        [GC Root: sticky class]}
 * @param gcRootKind      kind of the GC root the path ends at, {@code null} if no root was reached
 */
public record DominatorNodeDto(
        long objectId,
        String className,
        PackageCategory packageCategory,
        MemoryGroup memoryGroup,
        long shallowSize,
        long retainedSize,
        double percentOfHeap,
        List<GcRootPathStepDto> gcRootPath,
        String gcRootPathText,
        String gcRootKind
) {
    public DominatorNodeDto {
        gcRootPath = gcRootPath == null ? List.of() : List.copyOf(gcRootPath);
    }
}
