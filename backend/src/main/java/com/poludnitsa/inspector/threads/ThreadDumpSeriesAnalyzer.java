package com.poludnitsa.inspector.threads;

import com.poludnitsa.inspector.api.dto.DeadlockDto;
import com.poludnitsa.inspector.api.dto.JvmThreadState;
import com.poludnitsa.inspector.api.dto.LockKind;
import com.poludnitsa.inspector.api.dto.MonitorContentionDto;
import com.poludnitsa.inspector.api.dto.StackClusterDto;
import com.poludnitsa.inspector.api.dto.StackFrameDto;
import com.poludnitsa.inspector.api.dto.StuckKind;
import com.poludnitsa.inspector.api.dto.StuckThreadDto;
import com.poludnitsa.inspector.api.dto.ThreadCellDto;
import com.poludnitsa.inspector.api.dto.ThreadDumpMetaDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesAnalysisDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesRowDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesSummaryDto;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Compares a series of thread dumps thread by thread, as Samurai and fastThread do: a matrix of states with "same
 * stack as before" marks, stuck threads (identical stack and state RUNNABLE or BLOCKED in {@code stuckMinDumps}
 * consecutive dumps, idle selector / accept loops excluded), clusters of threads sharing a stack, deadlocks and
 * contention hotspots.
 */
public final class ThreadDumpSeriesAnalyzer {

    /** Thresholds of an analysis. */
    public record Settings(int stuckMinDumps, int maxStackDepth, List<String> poolPrefixes, int maxClusters) {
    }

    private record StackKey(List<String> frames) {
    }

    private record Cell(ThreadEntry thread, int stackId, boolean same, Double cpuDelta) {
    }

    private ThreadDumpSeriesAnalyzer() {
    }

    /**
     * @param contention hotspots measured while the dumps were taken (live series), or {@code null} to derive them
     *                   from the dumps
     */
    public static ThreadSeriesAnalysisDto analyze(ThreadSeriesSummaryDto summary, List<ThreadDump> dumps,
                                                  List<MonitorContentionDto> contention, boolean contentionTimed,
                                                  Settings settings) {
        int n = dumps.size();
        Instant first = n == 0 ? null : dumps.get(0).capturedAt();

        // distinct stacks, frames only (lock addresses differ from dump to dump)
        Map<StackKey, Integer> stackIds = new HashMap<>();
        List<List<StackFrameDto>> stacks = new ArrayList<>();
        Function<ThreadEntry, Integer> stackOf = thread -> {
            List<StackFrameDto> frames = thread.frames().subList(0, Math.min(settings.maxStackDepth(),
                    thread.frames().size()));
            List<String> key = new ArrayList<>(frames.size());
            frames.forEach(f -> key.add(Frames.describe(f)));
            return stackIds.computeIfAbsent(new StackKey(key), k -> {
                stacks.add(List.copyOf(frames));
                return stacks.size() - 1;
            });
        };

        // threads in order of first appearance
        Map<String, Cell[]> matrix = new LinkedHashMap<>();
        List<ThreadDumpMetaDto> metas = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            ThreadDump dump = dumps.get(d);
            Map<JvmThreadState, Integer> states = new EnumMap<>(JvmThreadState.class);
            for (ThreadEntry thread : dump.threads()) {
                states.merge(thread.state(), 1, Integer::sum);
                Cell[] row = matrix.computeIfAbsent(thread.key(), k -> new Cell[n]);
                int stack = stackOf.apply(thread);
                Cell before = d > 0 ? row[d - 1] : null;
                boolean same = before != null && before.stackId() == stack && before.thread().state() == thread.state()
                        && Objects.equals(before.thread().lockName(), thread.lockName());
                Double cpu = before != null && before.thread().cpuMs() != null && thread.cpuMs() != null
                        ? Math.max(0, thread.cpuMs() - before.thread().cpuMs()) : null;
                row[d] = new Cell(thread, stack, same, cpu);
            }
            Long offset = first != null && dump.capturedAt() != null
                    ? Duration.between(first, dump.capturedAt()).toMillis() : null;
            metas.add(new ThreadDumpMetaDto(d, dump.capturedAt(), offset, dump.threads().size(), states,
                    dump.jvmInfo()));
        }

        // stuck runs
        List<StuckThreadDto> stuck = new ArrayList<>();
        Set<String> stuckKeys = new HashSet<>();
        for (Map.Entry<String, Cell[]> entry : matrix.entrySet()) {
            StuckThreadDto run = longestRun(entry.getValue(), metas, settings);
            if (run != null) {
                stuck.add(run);
                stuckKeys.add(entry.getKey());
            }
        }
        stuck.sort(Comparator.comparingInt(StuckThreadDto::dumps).reversed()
                .thenComparing(StuckThreadDto::serverThread, Comparator.reverseOrder())
                .thenComparing(StuckThreadDto::name));

        // clusters of identical stacks in the last dump
        List<StackClusterDto> clusters = new ArrayList<>();
        Map<String, Integer> clusterOf = new HashMap<>();
        if (n > 0) {
            Map<String, List<ThreadEntry>> groups = new LinkedHashMap<>();
            Map<String, Integer> groupStack = new HashMap<>();
            for (ThreadEntry thread : dumps.get(n - 1).threads()) {
                if (thread.frames().isEmpty()) {
                    continue;
                }
                int stack = matrix.get(thread.key())[n - 1].stackId();
                String group = stack + "|" + thread.state();
                groups.computeIfAbsent(group, g -> new ArrayList<>()).add(thread);
                groupStack.put(group, stack);
            }
            List<Map.Entry<String, List<ThreadEntry>>> sorted = new ArrayList<>(groups.entrySet());
            sorted.removeIf(e -> e.getValue().size() < 2);
            sorted.sort(Comparator.comparingInt((Map.Entry<String, List<ThreadEntry>> e) -> e.getValue().size())
                    .reversed());
            for (Map.Entry<String, List<ThreadEntry>> group : sorted.subList(0, Math.min(settings.maxClusters(),
                    sorted.size()))) {
                int id = clusters.size() + 1;
                List<ThreadEntry> members = group.getValue();
                ThreadEntry sample = members.get(0);
                List<String> names = members.stream().map(ThreadEntry::name).sorted().limit(100).toList();
                members.forEach(t -> clusterOf.put(t.key(), id));
                clusters.add(new StackClusterDto(id, n - 1, sample.state(), members.size(), names,
                        Frames.top(sample.frames()), ThreadEntry.lockClassOf(sample.lockName()),
                        groupStack.get(group.getKey())));
            }
        }

        // rows: stuck first, then working threads, idle ones last
        List<ThreadSeriesRowDto> rows = new ArrayList<>();
        for (Map.Entry<String, Cell[]> entry : matrix.entrySet()) {
            Cell[] cells = entry.getValue();
            ThreadEntry any = null;
            for (Cell cell : cells) {
                if (cell != null) {
                    any = cell.thread();
                    break;
                }
            }
            if (any == null) {
                continue;
            }
            List<ThreadCellDto> dtoCells = new ArrayList<>(n);
            for (Cell cell : cells) {
                dtoCells.add(cell == null ? null : new ThreadCellDto(cell.thread().state(), cell.stackId(), cell.same(),
                        Frames.top(cell.thread().frames()), cell.thread().lockName(), cell.cpuDelta()));
            }
            rows.add(new ThreadSeriesRowDto(any.name(), any.id() >= 0 ? any.id() : null, any.daemon(),
                    Frames.serverThread(any.name(), settings.poolPrefixes()), idle(cells),
                    stuckKeys.contains(entry.getKey()), clusterOf.get(entry.getKey()), dtoCells));
        }
        rows.sort(Comparator.comparing(ThreadSeriesRowDto::stuck, Comparator.reverseOrder())
                .thenComparing(ThreadSeriesRowDto::idle)
                .thenComparing(ThreadSeriesRowDto::serverThread, Comparator.reverseOrder())
                .thenComparing(ThreadSeriesRowDto::name));

        // deadlocks of any dump, each cycle once
        List<DeadlockDto> deadlocks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ThreadDump dump : dumps) {
            for (DeadlockDto cycle : DeadlockDetector.cycles(dump.threads(), dump.capturedAt())) {
                String key = cycle.cycle().stream().map(c -> c.threadName()).sorted().toList().toString();
                if (seen.add(key)) {
                    deadlocks.add(cycle);
                }
            }
        }

        List<MonitorContentionDto> hotspots = contention;
        boolean timed = contentionTimed;
        if (hotspots == null) {
            ContentionAggregator aggregator = new ContentionAggregator(settings.poolPrefixes(),
                    summary.intervalMs() == null ? 1000 : summary.intervalMs());
            dumps.forEach(d -> aggregator.sample(d.threads()));
            hotspots = aggregator.result(50);
            timed = aggregator.timed();
        }

        ThreadSeriesSummaryDto finished = new ThreadSeriesSummaryDto(summary.seriesId(), summary.source(),
                summary.pid(), summary.name(), summary.status(), summary.startedAt(), summary.dumpsRequested(), n,
                summary.intervalMs(), summary.progressMessage(), summary.errorMessage(), matrix.size(), stuck.size(),
                deadlocks.size(), clusters.size());
        return new ThreadSeriesAnalysisDto(finished, metas, rows, stacks, stuck, clusters, deadlocks, hotspots, timed);
    }

    /** Median gap between the dump timestamps, {@code null} without them. */
    public static Long medianIntervalMs(List<ThreadDump> dumps) {
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < dumps.size(); i++) {
            Instant a = dumps.get(i - 1).capturedAt();
            Instant b = dumps.get(i).capturedAt();
            if (a != null && b != null) {
                gaps.add(Duration.between(a, b).toMillis());
            }
        }
        if (gaps.isEmpty()) {
            return null;
        }
        gaps.sort(null);
        return gaps.get(gaps.size() / 2);
    }

    private static boolean idle(Cell[] cells) {
        boolean seen = false;
        for (Cell cell : cells) {
            if (cell == null) {
                continue;
            }
            ThreadEntry thread = cell.thread();
            if (Frames.systemThread(thread.name())) {
                return true;
            }
            seen = true;
            boolean waitingForWork = switch (thread.state()) {
                case WAITING, TIMED_WAITING -> thread.lockKind() == null;
                case RUNNABLE -> Frames.idleRunnable(thread.frames());
                default -> false;
            };
            if (!waitingForWork) {
                return false;
            }
        }
        return seen;
    }

    private static StuckThreadDto longestRun(Cell[] cells, List<ThreadDumpMetaDto> metas, Settings settings) {
        int bestFrom = -1;
        int bestTo = -1;
        int from = -1;
        for (int d = 0; d < cells.length; d++) {
            Cell cell = cells[d];
            boolean candidate = cell != null && stuckState(cell.thread());
            if (!candidate) {
                from = -1;
                continue;
            }
            if (from < 0 || !cell.same()) {
                from = d;
            }
            if (d - from > bestTo - bestFrom) {
                bestFrom = from;
                bestTo = d;
            }
        }
        int length = bestTo - bestFrom + 1;
        if (bestFrom < 0 || length < settings.stuckMinDumps()) {
            return null;
        }
        ThreadEntry thread = cells[bestTo].thread();
        Double cpu = 0.0;
        for (int d = bestFrom + 1; d <= bestTo; d++) {
            Double delta = cells[d].cpuDelta();
            cpu = cpu == null || delta == null ? null : cpu + delta;
        }
        Long a = metas.get(bestFrom).offsetMs();
        Long b = metas.get(bestTo).offsetMs();
        Long duration = a != null && b != null ? b - a : null;
        StuckKind kind = kind(thread, cpu, duration);
        boolean server = Frames.serverThread(thread.name(), settings.poolPrefixes());
        return new StuckThreadDto(thread.name(), thread.id() >= 0 ? thread.id() : null, thread.state(), kind,
                reason(kind, thread, length, cpu, duration), bestFrom, bestTo, length, duration, cpu,
                thread.lockName(), server, thread.frames().subList(0, Math.min(settings.maxStackDepth(),
                thread.frames().size())));
    }

    private static boolean stuckState(ThreadEntry thread) {
        if (Frames.systemThread(thread.name())) {
            return false;
        }
        return switch (thread.state()) {
            case BLOCKED -> true;
            case RUNNABLE -> !Frames.idleRunnable(thread.frames());
            case WAITING, TIMED_WAITING -> thread.lockKind() == LockKind.SYNCHRONIZER;
            default -> false;
        };
    }

    private static StuckKind kind(ThreadEntry thread, Double cpuMs, Long durationMs) {
        if (thread.state() != JvmThreadState.RUNNABLE) {
            return StuckKind.LOCK_WAIT;
        }
        if (Frames.jdbc(thread.frames())) {
            return StuckKind.DB_QUERY;
        }
        if (Frames.readTop(thread.frames())) {
            return StuckKind.IO_WAIT;
        }
        if (cpuMs != null && durationMs != null && durationMs > 0 && cpuMs >= 0.3 * durationMs) {
            return StuckKind.CPU_LOOP;
        }
        return StuckKind.RUNNABLE;
    }

    private static String reason(StuckKind kind, ThreadEntry thread, int dumps, Double cpuMs, Long durationMs) {
        String span = dumps + " дампов подряд" + (durationMs != null ? " (" + durationMs / 1000.0 + " с)" : "");
        String cpu = cpuMs == null ? "" : String.format(java.util.Locale.ROOT, ", CPU за это время %.0f мс", cpuMs);
        return switch (kind) {
            case LOCK_WAIT -> "Ждёт один и тот же лок " + ThreadEntry.lockClassOf(thread.lockName())
                    + (thread.lockOwnerName() != null ? " (держит «" + thread.lockOwnerName() + "»)" : "") + " " + span;
            case DB_QUERY -> "Тяжёлый или зависший запрос к БД: один и тот же стек в JDBC-драйвере " + span + cpu
                    + ". Проверьте план запроса, блокировки в БД и socketTimeout / queryTimeout драйвера";
            case IO_WAIT -> "Ждёт ответа сокета или файла " + span + cpu
                    + ": удалённая сторона не отвечает, нужен read timeout";
            case CPU_LOOP -> "Крутится на CPU с одним и тем же стеком " + span + cpu
                    + ": бесконечный цикл или очень тяжёлое вычисление";
            case RUNNABLE -> "RUNNABLE с одинаковым стеком " + span + cpu;
        };
    }
}
