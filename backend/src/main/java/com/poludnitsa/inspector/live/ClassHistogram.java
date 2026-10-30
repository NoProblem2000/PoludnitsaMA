package com.poludnitsa.inspector.live;

import java.util.List;

/**
 * Parsed {@code GC.class_histogram} output.
 *
 * @param entries        ordered by bytes descending
 * @param totalInstances from the {@code Total} line, or the sum of rows when it is missing
 * @param totalBytes     from the {@code Total} line, or the sum of rows when it is missing
 */
public record ClassHistogram(long timestampEpochMs, List<HistogramEntry> entries, long totalInstances, long totalBytes) {

    public ClassHistogram {
        entries = List.copyOf(entries);
    }
}
