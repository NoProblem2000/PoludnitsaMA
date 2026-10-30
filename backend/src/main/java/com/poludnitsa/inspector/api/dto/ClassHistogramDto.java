package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Result of an on-demand {@code GC.class_histogram -all} (no forced Full GC) on the target JVM.
 *
 * @param durationMs     duration of the safepoint heap walk on the target
 * @param totalInstances includes unreachable objects not collected yet ({@code -all})
 * @param totalBytes     includes unreachable objects not collected yet ({@code -all})
 * @param classCount     distinct class names in the histogram
 * @param entries        top entries by bytes, limited by the request
 */
public record ClassHistogramDto(
        long pid,
        long timestampEpochMs,
        long durationMs,
        long totalInstances,
        long totalBytes,
        int classCount,
        List<ClassHistogramEntryDto> entries
) {
    public ClassHistogramDto {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
