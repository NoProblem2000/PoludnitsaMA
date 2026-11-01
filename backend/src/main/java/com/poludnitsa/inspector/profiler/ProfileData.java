package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.api.dto.ProfileMetric;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed CPU / wall / allocation profile: interned frames and stacks, samples and duration events on one time axis
 * (milliseconds from {@link #startEpochMs}). Immutable once built; nothing references the recording file.
 */
public final class ProfileData {

    /** A method; frames are aggregated per method, not per line. */
    public record Frame(String className, String methodName, PackageCategory category) {
        public String key() {
            return className + '.' + methodName;
        }
    }

    public enum SampleKind {
        /** jdk.ExecutionSample (or async-profiler cpu): running Java code */
        CPU,
        /** jdk.NativeMethodSample: in native code, usually a blocking call */
        NATIVE,
        /** a native sample inside a duration event of the same thread: already counted by the event */
        NATIVE_COVERED,
        /** async-profiler wall-clock sample (profiler.WallClockSample) */
        WALL,
        /** allocation event; weight in bytes */
        ALLOC
    }

    /** @param weight microseconds for time samples, bytes for allocations */
    public record Sample(double timeMs, int thread, int stack, SampleKind kind, long weight) {
    }

    public enum EventKind {
        PARK("jdk.ThreadPark", "Ожидание (park)"),
        MONITOR_ENTER("jdk.JavaMonitorEnter", "Блокировка монитора"),
        MONITOR_WAIT("jdk.JavaMonitorWait", "Object.wait()"),
        SLEEP("jdk.ThreadSleep", "Thread.sleep()"),
        SOCKET_READ("jdk.SocketRead", "Чтение сокета"),
        SOCKET_WRITE("jdk.SocketWrite", "Запись в сокет"),
        FILE_READ("jdk.FileRead", "Чтение файла"),
        FILE_WRITE("jdk.FileWrite", "Запись файла"),
        EXCEPTION("jdk.JavaExceptionThrow", "Исключения");

        public final String jfrType;
        public final String label;

        EventKind(String jfrType, String label) {
            this.jfrType = jfrType;
            this.label = label;
        }

        public boolean isIo() {
            return this == SOCKET_READ || this == SOCKET_WRITE || this == FILE_READ || this == FILE_WRITE;
        }

        public boolean isWait() {
            return this == PARK || this == MONITOR_WAIT || this == SLEEP;
        }

        /** Events whose duration is wall-clock time spent by the thread. */
        public boolean hasDuration() {
            return this != EXCEPTION;
        }
    }

    public record TraceEvent(EventKind kind, double startMs, double durationMs, int thread, int stack, String detail) {
        public double endMs() {
            return startMs + durationMs;
        }
    }

    /** jdk.CPULoad: fractions 0..1. */
    public record CpuLoad(double timeMs, double jvm, double machine) {
    }

    public record GcPause(double startMs, double durationMs, String name) {
    }

    final long startEpochMs;
    final double durationMs;
    final List<Frame> frames;
    /** frame ids, leaf (top of stack) first */
    final List<int[]> stacks;
    final List<String> threads;
    final List<Sample> samples;
    final List<TraceEvent> events;
    final List<CpuLoad> cpuLoad;
    final List<GcPause> gcPauses;
    final Map<String, Long> exceptions;
    final double cpuIntervalUs;
    final boolean truncated;

    ProfileData(long startEpochMs, double durationMs, List<Frame> frames, List<int[]> stacks, List<String> threads,
                List<Sample> samples, List<TraceEvent> events, List<CpuLoad> cpuLoad, List<GcPause> gcPauses,
                Map<String, Long> exceptions, double cpuIntervalUs, boolean truncated) {
        this.startEpochMs = startEpochMs;
        this.durationMs = durationMs;
        this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
        this.stacks = Collections.unmodifiableList(new ArrayList<>(stacks));
        this.threads = Collections.unmodifiableList(new ArrayList<>(threads));
        this.samples = Collections.unmodifiableList(new ArrayList<>(samples));
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.cpuLoad = List.copyOf(cpuLoad);
        this.gcPauses = List.copyOf(gcPauses);
        this.exceptions = Map.copyOf(exceptions);
        this.cpuIntervalUs = cpuIntervalUs;
        this.truncated = truncated;
    }

    public long startEpochMs() {
        return startEpochMs;
    }

    public double durationMs() {
        return durationMs;
    }

    public Frame frame(int id) {
        return frames.get(id);
    }

    public int frameCount() {
        return frames.size();
    }

    public int[] stack(int id) {
        return stacks.get(id);
    }

    public List<String> threads() {
        return threads;
    }

    public List<Sample> samples() {
        return samples;
    }

    public List<TraceEvent> events() {
        return events;
    }

    public List<CpuLoad> cpuLoad() {
        return cpuLoad;
    }

    public List<GcPause> gcPauses() {
        return gcPauses;
    }

    public Map<String, Long> exceptions() {
        return exceptions;
    }

    public double cpuIntervalUs() {
        return cpuIntervalUs;
    }

    public boolean truncated() {
        return truncated;
    }

    public long sampleCount() {
        return samples.size();
    }

    public Set<ProfileMetric> metrics() {
        Set<ProfileMetric> metrics = EnumSet.noneOf(ProfileMetric.class);
        for (Sample sample : samples) {
            switch (sample.kind()) {
                case CPU -> {
                    metrics.add(ProfileMetric.CPU);
                    metrics.add(ProfileMetric.WALL);
                }
                case NATIVE, NATIVE_COVERED, WALL -> metrics.add(ProfileMetric.WALL);
                case ALLOC -> metrics.add(ProfileMetric.ALLOC);
            }
            if (metrics.size() == ProfileMetric.values().length) {
                break;
            }
        }
        if (events.stream().anyMatch(e -> e.kind().hasDuration())) {
            metrics.add(ProfileMetric.WALL);
        }
        return metrics;
    }

    /** Weighted stacks of a metric in a window, optionally of one thread. */
    @FunctionalInterface
    public interface StackVisitor {
        /**
         * @param sample a sample (counted in samples) or a duration event (time only)
         * @param event  the kind of the duration event, {@code null} for samples
         */
        void visit(int stack, long weight, boolean sample, int thread, EventKind event);
    }

    /**
     * @param fromMs window start (inclusive), {@code toMs} end (exclusive); duration events are clipped to it
     * @param thread a thread index or {@code -1} for all
     */
    public void forEachStack(ProfileMetric metric, double fromMs, double toMs, int thread, StackVisitor visitor) {
        for (Sample sample : samples) {
            if (sample.timeMs() < fromMs || sample.timeMs() >= toMs || (thread >= 0 && sample.thread() != thread)
                    || sample.stack() < 0) {
                continue;
            }
            boolean counted = switch (metric) {
                case CPU -> sample.kind() == SampleKind.CPU;
                case WALL -> sample.kind() == SampleKind.CPU || sample.kind() == SampleKind.NATIVE
                        || sample.kind() == SampleKind.WALL;
                case ALLOC -> sample.kind() == SampleKind.ALLOC;
            };
            if (counted) {
                visitor.visit(sample.stack(), sample.weight(), true, sample.thread(), null);
            }
        }
        if (metric != ProfileMetric.WALL) {
            return;
        }
        for (TraceEvent event : events) {
            if (!event.kind().hasDuration() || event.stack() < 0 || (thread >= 0 && event.thread() != thread)) {
                continue;
            }
            double overlap = Math.min(event.endMs(), toMs) - Math.max(event.startMs(), fromMs);
            if (overlap > 0) {
                visitor.visit(event.stack(), Math.round(overlap * 1000), false, event.thread(), event.kind());
            }
        }
    }
}
