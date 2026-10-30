package com.poludnitsa.inspector.snapshot;

import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.DominatorNodeDto;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.InspectionRuleRunDto;
import com.poludnitsa.inspector.api.dto.MemoryGroupStatsDto;
import java.util.List;

/**
 * Analytical result of a snapshot. This is all that remains of a heap dump once the raw .hprof is deleted, so it
 * is kept self-contained (no references into the NetBeans heap) and is what gets persisted and exported.
 *
 * @param classes     every class with instances, ordered by retained bytes (deep) or shallow bytes (histogram)
 * @param dominators  objects with the largest retained size, descending; empty for histograms
 * @param findings    leak inspection results, most severe first; empty for histograms
 * @param inspections how each inspection rule ran
 */
public record SnapshotData(
        long totalInstances,
        int totalClasses,
        long totalHeapBytes,
        int gcRootCount,
        boolean retainedSizesAvailable,
        List<ClassStatsDto> classes,
        List<DominatorNodeDto> dominators,
        List<MemoryGroupStatsDto> groups,
        List<InspectionFindingDto> findings,
        List<InspectionRuleRunDto> inspections
) {
    public SnapshotData {
        classes = List.copyOf(classes);
        dominators = List.copyOf(dominators);
        groups = List.copyOf(groups);
        // Summaries written before inspections existed have no such fields.
        findings = findings == null ? List.of() : List.copyOf(findings);
        inspections = inspections == null ? List.of() : List.copyOf(inspections);
    }
}
