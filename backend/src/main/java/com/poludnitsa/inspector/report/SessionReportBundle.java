package com.poludnitsa.inspector.report;

import com.poludnitsa.inspector.api.dto.GcAnalysisDto;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.MetricsPointDto;
import com.poludnitsa.inspector.api.dto.OqlHistoryEntryDto;
import com.poludnitsa.inspector.api.dto.ProfileReportDto;
import com.poludnitsa.inspector.api.dto.SnapshotDiffDto;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import com.poludnitsa.inspector.api.dto.ThreadLiveDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesAnalysisDto;
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
 * @param profiles       CPU &amp; Timeline profiles (format 2+)
 * @param threadSeries   thread dump series analyses (format 2+)
 * @param threadsLive    the live contention / deadlock picture at export time, if it was being watched (format 2+)
 * @param gcAnalyses     GC log / live GC analyses (format 2+)
 * @param oqlQueries     OQL queries run on the included snapshots, newest first (format 2+)
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
        List<InspectionFindingDto> findings,
        List<ProfileReportDto> profiles,
        List<ThreadSeriesAnalysisDto> threadSeries,
        ThreadLiveDto threadsLive,
        List<GcAnalysisDto> gcAnalyses,
        List<OqlHistoryEntryDto> oqlQueries
) {

    public record SnapshotReport(SnapshotSummaryDto summary, SnapshotData data) {
    }

    public SessionReportBundle {
        metricsHistory = metricsHistory == null ? List.of() : List.copyOf(metricsHistory);
        snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
        findings = findings == null ? List.of() : List.copyOf(findings);
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        threadSeries = threadSeries == null ? List.of() : List.copyOf(threadSeries);
        gcAnalyses = gcAnalyses == null ? List.of() : List.copyOf(gcAnalyses);
        oqlQueries = oqlQueries == null ? List.of() : List.copyOf(oqlQueries);
    }
}
