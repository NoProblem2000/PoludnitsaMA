package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.api.dto.ExceptionCountDto;
import com.poludnitsa.inspector.api.dto.HotspotDto;
import com.poludnitsa.inspector.api.dto.ProfileMetric;
import com.poludnitsa.inspector.api.dto.Subsystem;
import com.poludnitsa.inspector.api.dto.SubsystemBreakdownDto;
import com.poludnitsa.inspector.api.dto.SubsystemShareDto;
import com.poludnitsa.inspector.api.dto.TracedEventStatDto;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Time of a window by subsystem (see {@link SubsystemClassifier}), with the heaviest methods of each, plus the
 * precisely timed (traced) events and the thrown exceptions of the window. GC pauses count as GC in wall-clock time:
 * every thread stood still during them.
 */
public final class SubsystemBreakdownBuilder {

    private static final int TOP_METHODS = 5;

    private SubsystemBreakdownBuilder() {
    }

    public static SubsystemBreakdownDto build(String profileId, ProfileData data, ProfileMetric metric, double fromMs,
                                              double toMs, int thread) {
        Map<Subsystem, long[]> totals = new EnumMap<>(Subsystem.class);         // value, samples
        Map<Subsystem, Map<Integer, long[]>> methods = new EnumMap<>(Subsystem.class); // frame -> self, samples
        long[] grand = {0};
        data.forEachStack(metric, fromMs, toMs, thread, (stack, weight, sample, t, kind) -> {
            Subsystem subsystem = kind == null ? SubsystemClassifier.classifySample(data, stack)
                    : SubsystemClassifier.classifyEvent(data, kind, stack);
            long[] total = totals.computeIfAbsent(subsystem, s -> new long[2]);
            total[0] += weight;
            if (sample) {
                total[1]++;
            }
            long[] method = methods.computeIfAbsent(subsystem, s -> new HashMap<>())
                    .computeIfAbsent(data.stack(stack)[0], f -> new long[2]);
            method[0] += weight;
            if (sample) {
                method[1]++;
            }
            grand[0] += weight;
        });
        if (metric == ProfileMetric.WALL && thread < 0) {
            long gc = 0;
            for (ProfileData.GcPause pause : data.gcPauses()) {
                double overlap = Math.min(pause.startMs() + pause.durationMs(), toMs) - Math.max(pause.startMs(), fromMs);
                gc += overlap > 0 ? Math.round(overlap * 1000) : 0;
            }
            if (gc > 0) {
                totals.computeIfAbsent(Subsystem.GC, s -> new long[2])[0] += gc;
                grand[0] += gc;
            }
        }

        long total = grand[0];
        List<SubsystemShareDto> shares = new ArrayList<>();
        totals.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).forEach(e -> {
            List<HotspotDto> top = methods.getOrDefault(e.getKey(), Map.of()).entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(TOP_METHODS)
                    .map(m -> {
                        ProfileData.Frame frame = data.frame(m.getKey());
                        long[] v = m.getValue();
                        return new HotspotDto(frame.className(), frame.methodName(), frame.category(), e.getKey(),
                                v[1], v[1], v[0], v[0], CallTreeEngine.percent(v[0], total),
                                CallTreeEngine.percent(v[0], total));
                    })
                    .toList();
            shares.add(new SubsystemShareDto(e.getKey(), SubsystemClassifier.label(e.getKey()), e.getValue()[0],
                    e.getValue()[1], CallTreeEngine.percent(e.getValue()[0], total), top));
        });
        return new SubsystemBreakdownDto(profileId, metric, CallTreeEngine.unit(metric), fromMs, toMs, total, shares,
                traced(data, fromMs, toMs, thread), exceptions(data, fromMs, toMs, thread));
    }

    static List<TracedEventStatDto> traced(ProfileData data, double fromMs, double toMs, int thread) {
        Map<ProfileData.EventKind, double[]> stats = new EnumMap<>(ProfileData.EventKind.class); // count, total, max
        Map<ProfileData.EventKind, Map<String, Integer>> details = new EnumMap<>(ProfileData.EventKind.class);
        for (ProfileData.TraceEvent event : data.events()) {
            if (event.endMs() < fromMs || event.startMs() >= toMs || (thread >= 0 && event.thread() != thread)) {
                continue;
            }
            double overlap = event.kind().hasDuration()
                    ? Math.min(event.endMs(), toMs) - Math.max(event.startMs(), fromMs) : 0;
            double[] s = stats.computeIfAbsent(event.kind(), k -> new double[3]);
            s[0]++;
            s[1] += Math.max(0, overlap);
            s[2] = Math.max(s[2], event.durationMs());
            if (event.detail() != null) {
                details.computeIfAbsent(event.kind(), k -> new HashMap<>()).merge(event.detail(), 1, Integer::sum);
            }
        }
        List<TracedEventStatDto> result = new ArrayList<>();
        stats.forEach((kind, s) -> {
            String top = details.getOrDefault(kind, Map.of()).entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            result.add(new TracedEventStatDto(kind.jfrType, kind.label, (long) s[0], round(s[1]), round(s[2]), top));
        });
        result.sort((a, b) -> Double.compare(b.totalMs(), a.totalMs()));
        return result;
    }

    static List<ExceptionCountDto> exceptions(ProfileData data, double fromMs, double toMs, int thread) {
        Map<String, Long> counts = new HashMap<>();
        for (ProfileData.TraceEvent event : data.events()) {
            if (event.kind() == ProfileData.EventKind.EXCEPTION && event.startMs() >= fromMs && event.startMs() < toMs
                    && (thread < 0 || event.thread() == thread)) {
                counts.merge(event.detail() == null ? "?" : event.detail(), 1L, Long::sum);
            }
        }
        return counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(20)
                .map(e -> new ExceptionCountDto(e.getKey(), e.getValue())).toList();
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
