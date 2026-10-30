package com.poludnitsa.inspector.live;

/**
 * One class of a parsed histogram, aggregated over all class loaders that define a class with this name.
 *
 * @param className       source-like name ({@code byte[]}, {@code java.lang.String[]})
 * @param module          {@code java.base@17.0.9}, or {@code null} for the unnamed module
 * @param definitionCount number of histogram rows (one per defining class loader) merged into this entry
 */
public record HistogramEntry(String className, long instances, long bytes, String module, int definitionCount) {
}
