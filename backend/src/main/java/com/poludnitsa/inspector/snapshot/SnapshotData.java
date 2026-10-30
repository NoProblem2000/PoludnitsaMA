package com.poludnitsa.inspector.snapshot;

import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.DominatorNodeDto;
import com.poludnitsa.inspector.api.dto.MemoryGroupStatsDto;
import java.util.List;

/**
 * Analytical result of a snapshot. This is all that remains of a heap dump once the raw .hprof is deleted, so it
 * is kept self-contained (no references into the NetBeans heap) and is what gets persisted and exported.
 *
 * @param classes    every class with instances, ordered by retained bytes (deep) or shallow bytes (histogram)
 * @param dominators objects with the largest retained size, descending; empty for histograms
 */
public record SnapshotData(
        long totalInstances,
        int totalClasses,
        long totalHeapBytes,
        int gcRootCount,
        boolean retainedSizesAvailable,
        List<ClassStatsDto> classes,
        List<DominatorNodeDto> dominators,
        List<MemoryGroupStatsDto> groups
) {
    public SnapshotData {
        classes = List.copyOf(classes);
        dominators = List.copyOf(dominators);
        groups = List.copyOf(groups);
    }
}
