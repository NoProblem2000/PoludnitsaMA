package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A series of thread dumps compared thread by thread (as Samurai / fastThread do).
 *
 * @param rows non-idle threads first, stuck first
 * @param stacks distinct stacks, referenced by {@code stackId}
 * @param clusters largest first, at least two threads each
 * @param deadlocks cycles found in any dump
 * @param contentionTimed blocked time measured by ThreadMXBean contention monitoring, not estimated
 */
public record ThreadSeriesAnalysisDto(
        ThreadSeriesSummaryDto summary,
        List<ThreadDumpMetaDto> dumps,
        List<ThreadSeriesRowDto> rows,
        List<List<StackFrameDto>> stacks,
        List<StuckThreadDto> stuck,
        List<StackClusterDto> clusters,
        List<DeadlockDto> deadlocks,
        List<MonitorContentionDto> contention,
        boolean contentionTimed
) {
    public ThreadSeriesAnalysisDto {
        dumps = dumps == null ? List.of() : List.copyOf(dumps);
        rows = rows == null ? List.of() : List.copyOf(rows);
        stacks = stacks == null ? List.of() : List.copyOf(stacks);
        stuck = stuck == null ? List.of() : List.copyOf(stuck);
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
        deadlocks = deadlocks == null ? List.of() : List.copyOf(deadlocks);
        contention = contention == null ? List.of() : List.copyOf(contention);
    }
}
