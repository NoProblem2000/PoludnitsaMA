package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * The largest (by retained size) instance found for a class, with its path to a GC root. Computed while the heap
 * dump is open, because the raw dump is deleted after the analysis.
 */
public record ClassSampleDto(
        long objectId,
        long retainedSize,
        List<GcRootPathStepDto> gcRootPath,
        String gcRootPathText
) {
    public ClassSampleDto {
        gcRootPath = gcRootPath == null ? List.of() : List.copyOf(gcRootPath);
    }
}
