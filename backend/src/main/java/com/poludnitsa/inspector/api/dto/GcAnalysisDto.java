package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A parsed GC log (Unified Logging of JDK 11+: G1, Parallel, Serial, ZGC, Shenandoah) or live JFR recording.
 *
 * @param pauses chronological; very long logs are thinned, keeping the longest pauses
 * @param safepoints {@code null} without safepoint data
 * @param truncated more pauses than inspector.gc-log.max-events: the rest was not analysed
 */
public record GcAnalysisDto(
        GcAnalysisSummaryDto summary,
        GcKpiDto kpi,
        List<GcPauseDto> pauses,
        List<GcRateSampleDto> rates,
        List<GcCauseStatDto> causes,
        SafepointSummaryDto safepoints,
        List<GcFindingDto> findings,
        int concurrentCycles,
        long parsedLines,
        long unparsedLines,
        boolean truncated
) {
    public GcAnalysisDto {
        pauses = pauses == null ? List.of() : List.copyOf(pauses);
        rates = rates == null ? List.of() : List.copyOf(rates);
        causes = causes == null ? List.of() : List.copyOf(causes);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
