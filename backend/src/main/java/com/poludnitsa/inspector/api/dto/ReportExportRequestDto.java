package com.poludnitsa.inspector.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What goes into an exported report.
 *
 * @param pid                   process the report is about; {@code null}: derived from the snapshots
 * @param snapshotIds           snapshots to include; empty: every finished snapshot (of {@code pid}, if given)
 * @param diffs                 comparisons to include; empty: oldest vs newest snapshot per process
 * @param includeMetricsHistory include the live chart history of {@code pid} (default {@code true})
 * @param title                 report title; {@code null}: generated
 */
public record ReportExportRequestDto(
        @Positive Long pid,
        @Size(max = 100) List<@Pattern(regexp = "^[A-Za-z0-9-]{1,64}$") String> snapshotIds,
        @Size(max = 20) List<@Valid DiffPairDto> diffs,
        Boolean includeMetricsHistory,
        @Size(max = 200) String title
) {
    public ReportExportRequestDto {
        snapshotIds = snapshotIds == null ? List.of() : List.copyOf(snapshotIds);
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
    }

    public boolean metricsHistoryIncluded() {
        return includeMetricsHistory == null || includeMetricsHistory;
    }
}
