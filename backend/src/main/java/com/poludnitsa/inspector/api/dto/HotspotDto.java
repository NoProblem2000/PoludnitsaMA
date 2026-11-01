package com.poludnitsa.inspector.api.dto;

/**
 * A method by its own (self) weight.
 *
 * @param subsystem where its self time is accounted
 */
public record HotspotDto(
        String className,
        String methodName,
        PackageCategory packageCategory,
        Subsystem subsystem,
        long selfSamples,
        long totalSamples,
        long selfValue,
        long totalValue,
        double selfPercent,
        double totalPercent
) {
}
