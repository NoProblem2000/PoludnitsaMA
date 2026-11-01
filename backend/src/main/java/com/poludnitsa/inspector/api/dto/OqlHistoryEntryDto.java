package com.poludnitsa.inspector.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * An executed OQL query with a preview of its result, for reports.
 *
 * @param preview first rows as text
 */
public record OqlHistoryEntryDto(
        String snapshotId,
        String query,
        Instant executedAt,
        int rowCount,
        long durationMs,
        boolean truncated,
        List<String> columns,
        List<List<String>> preview
) {
    public OqlHistoryEntryDto {
        columns = columns == null ? List.of() : List.copyOf(columns);
        preview = preview == null ? List.of() : List.copyOf(preview);
    }
}
