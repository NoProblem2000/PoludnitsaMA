package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A frame of a differential flame graph: its share of the total of each profile.
 *
 * @param deltaPercent {@code targetPercent - baselinePercent}: positive is a regression (more time), negative an
 *                     improvement
 */
public record DiffFlameNodeDto(
        String className,
        String methodName,
        PackageCategory packageCategory,
        long baselineValue,
        long targetValue,
        double baselinePercent,
        double targetPercent,
        double deltaPercent,
        List<DiffFlameNodeDto> children
) {
    public DiffFlameNodeDto {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
