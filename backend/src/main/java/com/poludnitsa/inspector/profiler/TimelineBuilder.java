package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.api.dto.ThreadLaneDto;
import com.poludnitsa.inspector.api.dto.ThreadState;
import com.poludnitsa.inspector.api.dto.TimeSpanDto;
import com.poludnitsa.inspector.api.dto.TimelineDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * All lanes of a profile on one time axis: CPU load, GC pauses, lock contention, waiting, I/O and the states of the
 * busiest threads.
 *
 * <p>A thread lane column shows the state the thread spent most time in: RUNNABLE (execution samples x interval),
 * BLOCKED (monitor enter), WAITING (park, Object.wait, sleep) or IO (socket / file). Pool threads of WildFly
 * ({@code default task-*}, XNIO, EE managed) are preferred for the lanes, since that is where requests run.
 */
public final class TimelineBuilder {

    private static final List<String> SERVER_POOLS = List.of("default task-", "XNIO-", "EE-Managed", "ejb-client-",
            "Batch Thread", "ServerService Thread Pool");

    private TimelineBuilder() {
    }

    public static TimelineDto build(String profileId, ProfileData data, int buckets, int lanes) {
        int n = Math.max(10, buckets);
        double duration = Math.max(1, data.durationMs());
        double bucketMs = duration / n;

        double[] jvmSum = new double[n];
        double[] machineSum = new double[n];
        int[] loadCount = new int[n];
        for (ProfileData.CpuLoad load : data.cpuLoad()) {
            int b = bucket(load.timeMs(), bucketMs, n);
            jvmSum[b] += load.jvm();
            machineSum[b] += load.machine();
            loadCount[b]++;
        }
        List<Double> jvm = new ArrayList<>(n);
        List<Double> machine = new ArrayList<>(n);
        // CPU load is sampled once a second: carry the last value across short columns
        Double lastJvm = null;
        Double lastMachine = null;
        for (int b = 0; b < n; b++) {
            if (loadCount[b] > 0) {
                lastJvm = 100 * jvmSum[b] / loadCount[b];
                lastMachine = 100 * machineSum[b] / loadCount[b];
            }
            jvm.add(lastJvm);
            machine.add(lastMachine);
        }

        int[] samples = new int[n];
        int threads = data.threads().size();
        double[][][] state = new double[threads][4][];
        double[] busy = new double[threads];
        double interval = data.cpuIntervalUs() / 1000.0;
        for (ProfileData.Sample sample : data.samples()) {
            if (sample.kind() != ProfileData.SampleKind.CPU && sample.kind() != ProfileData.SampleKind.WALL) {
                continue;
            }
            int b = bucket(sample.timeMs(), bucketMs, n);
            if (sample.kind() == ProfileData.SampleKind.CPU) {
                samples[b]++;
            }
            double ms = sample.weight() / 1000.0;
            lane(state, sample.thread(), ThreadState.RUNNABLE, n)[b] += sample.kind() == ProfileData.SampleKind.CPU
                    ? interval : ms;
            busy[sample.thread()] += ms;
        }

        double[] gc = new double[n];
        List<TimeSpanDto> pauses = new ArrayList<>();
        for (ProfileData.GcPause pause : data.gcPauses()) {
            spread(gc, pause.startMs(), pause.durationMs(), bucketMs, n);
            pauses.add(new TimeSpanDto(pause.startMs(), pause.durationMs(), pause.name()));
        }

        double[] lock = new double[n];
        double[] wait = new double[n];
        double[] io = new double[n];
        for (ProfileData.TraceEvent event : data.events()) {
            if (!event.kind().hasDuration()) {
                continue;
            }
            ThreadState laneState;
            double[] total;
            if (event.kind() == ProfileData.EventKind.MONITOR_ENTER) {
                laneState = ThreadState.BLOCKED;
                total = lock;
            } else if (event.kind().isIo()) {
                laneState = ThreadState.IO;
                total = io;
            } else {
                laneState = ThreadState.WAITING;
                total = wait;
            }
            spread(total, event.startMs(), event.durationMs(), bucketMs, n);
            spread(lane(state, event.thread(), laneState, n), event.startMs(), event.durationMs(), bucketMs, n);
            busy[event.thread()] += event.durationMs();
        }

        List<ThreadLaneDto> threadLanes = new ArrayList<>();
        for (int t : laneOrder(data, busy, lanes)) {
            List<ThreadState> states = new ArrayList<>(n);
            double[] totals = new double[4];
            for (int b = 0; b < n; b++) {
                ThreadState best = null;
                double bestMs = 0;
                for (ThreadState s : ThreadState.values()) {
                    double[] values = state[t][s.ordinal()];
                    double ms = values == null ? 0 : values[b];
                    totals[s.ordinal()] += ms;
                    if (ms > bestMs) {
                        best = s;
                        bestMs = ms;
                    }
                }
                states.add(best);
            }
            threadLanes.add(new ThreadLaneDto(t, data.threads().get(t), states, totals[0], totals[1], totals[2],
                    totals[3]));
        }

        return new TimelineDto(profileId, data.startEpochMs(), duration, bucketMs, n, jvm, machine, boxed(samples),
                boxed(gc), boxed(lock), boxed(wait), boxed(io), pauses, threadLanes);
    }

    /** Busiest threads, WildFly pool threads first among equals; at most {@code lanes}. */
    private static List<Integer> laneOrder(ProfileData data, double[] busy, int lanes) {
        List<Integer> order = new ArrayList<>();
        for (int t = 0; t < busy.length; t++) {
            if (busy[t] > 0) {
                order.add(t);
            }
        }
        order.sort(Comparator.comparingDouble((Integer t) -> -(busy[t] * (serverPool(data.threads().get(t)) ? 4 : 1))));
        return order.subList(0, Math.min(Math.max(1, lanes), order.size()));
    }

    private static boolean serverPool(String name) {
        return SERVER_POOLS.stream().anyMatch(name::startsWith);
    }

    private static double[] lane(double[][][] state, int thread, ThreadState s, int n) {
        double[] values = state[thread][s.ordinal()];
        if (values == null) {
            values = new double[n];
            state[thread][s.ordinal()] = values;
        }
        return values;
    }

    /** Adds the part of [start, start + duration) that falls into each column. */
    static void spread(double[] columns, double startMs, double durationMs, double bucketMs, int n) {
        double end = startMs + durationMs;
        int first = bucket(startMs, bucketMs, n);
        int last = bucket(Math.max(startMs, end - 1e-9), bucketMs, n);
        for (int b = first; b <= last; b++) {
            double overlap = Math.min(end, (b + 1) * bucketMs) - Math.max(startMs, b * bucketMs);
            if (overlap > 0) {
                columns[b] += overlap;
            }
        }
    }

    static int bucket(double timeMs, double bucketMs, int n) {
        return (int) Math.max(0, Math.min(n - 1, Math.floor(timeMs / bucketMs)));
    }

    private static List<Integer> boxed(int[] values) {
        return Arrays.stream(values).boxed().toList();
    }

    private static List<Double> boxed(double[] values) {
        return Arrays.stream(values).map(v -> Math.round(v * 100) / 100.0).boxed().toList();
    }
}
