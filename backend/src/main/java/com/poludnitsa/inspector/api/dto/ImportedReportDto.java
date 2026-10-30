package com.poludnitsa.inspector.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A report file loaded into this session. Its snapshots are registered as regular (imported) snapshots, so all
 * snapshot endpoints, including diffs, work on them.
 *
 * @param snapshotIds ids of the snapshots in the file mapped to the ids they got here (they change on collision)
 */
public record ImportedReportDto(
        String reportId,
        String title,
        Instant generatedAt,
        String hostName,
        String processName,
        Map<String, String> snapshotIds,
        int findings,
        int metricsPoints,
        List<String> warnings
) {
    public ImportedReportDto {
        snapshotIds = snapshotIds == null ? Map.of() : Map.copyOf(snapshotIds);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
