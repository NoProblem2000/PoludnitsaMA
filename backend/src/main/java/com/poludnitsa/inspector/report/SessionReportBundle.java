package com.poludnitsa.inspector.report;

import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.MetricsPointDto;
import com.poludnitsa.inspector.api.dto.SnapshotDiffDto;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import com.poludnitsa.inspector.snapshot.SnapshotData;
import java.time.Instant;
import java.util.List;

/**
 * Self-contained report of an inspector session: exported as gzipped JSON (re-opened in the UI on any machine) and
 * rendered as standalone HTML. Contains no raw heap data.
 *
 * @param process        the process the report is about; {@code null} if it could not be described
 * @param metricsHistory live chart history of that process, oldest first
 * @param snapshots      snapshots with their full analysis (classes, dominators, groups, findings)
 * @param diffs          snapshot comparisons included in the report
 * @param findings       all findings of the included snapshots, newest snapshot first, most severe first
 */
public record SessionReportBundle(
        int formatVersion,
        String reportId,
        String title,
        Instant generatedAt,
        String generator,
        String hostName,
        JvmProcessDto process,
        List<MetricsPointDto> metricsHistory,
        List<SnapshotReport> snapshots,
        List<SnapshotDiffDto> diffs,
        List<InspectionFindingDto> findings
) {

    public record SnapshotReport(SnapshotSummaryDto summary, SnapshotData data) {
    }

    public SessionReportBundle {
        metricsHistory = metricsHistory == null ? List.of() : List.copyOf(metricsHistory);
        snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
