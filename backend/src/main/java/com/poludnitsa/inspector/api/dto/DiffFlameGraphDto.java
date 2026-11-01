package com.poludnitsa.inspector.api.dto;

/** The call trees of two profiles merged; widths follow the target, colours the change of share. */
public record DiffFlameGraphDto(
        String baselineId,
        String targetId,
        ProfileMetric metric,
        String unit,
        long baselineTotal,
        long targetTotal,
        DiffFlameNodeDto root
) {
}
