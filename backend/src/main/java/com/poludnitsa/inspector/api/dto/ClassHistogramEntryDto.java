package com.poludnitsa.inspector.api.dto;

/**
 * One aggregated row of {@code GC.class_histogram}.
 *
 * @param rank            1-based position by shallow bytes
 * @param module          module of the class, e.g. {@code java.base@17.0.9}; {@code null} for the unnamed module
 * @param definitionCount how many classes with this name are loaded by different class loaders. Values above 1 for
 *                        application classes in WildFly usually mean a class loader leak after redeploy.
 */
public record ClassHistogramEntryDto(
        int rank,
        String className,
        PackageCategory packageCategory,
        long instances,
        long bytes,
        String module,
        int definitionCount
) {
}
