package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.common.PackageCategorizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Collects frames, stacks, samples and events while a recording is parsed, interning frames and stacks. */
final class ProfileBuilder {

    private final List<ProfileData.Frame> frames = new ArrayList<>();
    private final Map<String, Integer> frameIds = new HashMap<>();
    private final List<int[]> stacks = new ArrayList<>();
    private final Map<StackKey, Integer> stackIds = new HashMap<>();
    private final List<String> threads = new ArrayList<>();
    private final Map<Long, Integer> threadIds = new HashMap<>();
    private final List<ProfileData.Sample> samples = new ArrayList<>();
    private final List<ProfileData.TraceEvent> events = new ArrayList<>();
    private final List<ProfileData.CpuLoad> cpuLoad = new ArrayList<>();
    private final List<ProfileData.GcPause> gcPauses = new ArrayList<>();
    private final Map<String, Long> exceptions = new HashMap<>();
    private final int maxRecords;
    private boolean truncated;
    private double maxTimeMs;

    private record StackKey(int[] frames, int hash) {
        StackKey(int[] frames) {
            this(frames, Arrays.hashCode(frames));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StackKey key && Arrays.equals(frames, key.frames);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    ProfileBuilder(int maxRecords) {
        this.maxRecords = Math.max(1_000, maxRecords);
    }

    int frame(String className, String methodName) {
        String key = className + '.' + methodName;
        Integer id = frameIds.get(key);
        if (id == null) {
            id = frames.size();
            frames.add(new ProfileData.Frame(className, methodName, PackageCategorizer.categorize(className)));
            frameIds.put(key, id);
        }
        return id;
    }

    /** @param frameIds leaf first; {@code -1} when there is no stack */
    int stack(int[] frameIds) {
        if (frameIds == null || frameIds.length == 0) {
            return -1;
        }
        StackKey key = new StackKey(frameIds);
        Integer id = stackIds.get(key);
        if (id == null) {
            id = stacks.size();
            stacks.add(frameIds);
            stackIds.put(key, id);
        }
        return id;
    }

    int thread(long id, String name) {
        Integer index = threadIds.get(id);
        if (index == null) {
            index = threads.size();
            threads.add(name == null || name.isBlank() ? "thread-" + id : name);
            threadIds.put(id, index);
        } else if (name != null && !name.isBlank() && threads.get(index).startsWith("thread-")) {
            threads.set(index, name);
        }
        return index;
    }

    boolean full() {
        if (samples.size() + events.size() >= maxRecords) {
            truncated = true;
        }
        return truncated;
    }

    void sample(double timeMs, int thread, int stack, ProfileData.SampleKind kind, long weight) {
        if (!full() && weight > 0) {
            samples.add(new ProfileData.Sample(timeMs, thread, stack, kind, weight));
            maxTimeMs = Math.max(maxTimeMs, timeMs);
        }
    }

    void event(ProfileData.EventKind kind, double startMs, double durationMs, int thread, int stack, String detail) {
        if (!full()) {
            events.add(new ProfileData.TraceEvent(kind, startMs, Math.max(0, durationMs), thread, stack, detail));
            maxTimeMs = Math.max(maxTimeMs, startMs + Math.max(0, durationMs));
        }
    }

    void cpuLoad(double timeMs, double jvm, double machine) {
        cpuLoad.add(new ProfileData.CpuLoad(timeMs, jvm, machine));
        maxTimeMs = Math.max(maxTimeMs, timeMs);
    }

    void gcPause(double startMs, double durationMs, String name) {
        gcPauses.add(new ProfileData.GcPause(startMs, durationMs, name));
        maxTimeMs = Math.max(maxTimeMs, startMs + durationMs);
    }

    void exception(String className) {
        exceptions.merge(className, 1L, Long::sum);
    }

    /**
     * Marks native samples that fall inside a duration event of the same thread: the event already accounts for that
     * wall-clock time (a socket read is both a jdk.SocketRead and a series of native samples).
     */
    ProfileData build(long startEpochMs, double requestedDurationMs, double cpuIntervalUs) {
        Map<Integer, List<ProfileData.TraceEvent>> byThread = new HashMap<>();
        for (ProfileData.TraceEvent event : events) {
            if (event.kind().hasDuration()) {
                byThread.computeIfAbsent(event.thread(), t -> new ArrayList<>()).add(event);
            }
        }
        byThread.values().forEach(list -> list.sort(Comparator.comparingDouble(ProfileData.TraceEvent::startMs)));
        for (int i = 0; i < samples.size(); i++) {
            ProfileData.Sample sample = samples.get(i);
            if (sample.kind() == ProfileData.SampleKind.NATIVE
                    && covered(byThread.get(sample.thread()), sample.timeMs())) {
                samples.set(i, new ProfileData.Sample(sample.timeMs(), sample.thread(), sample.stack(),
                        ProfileData.SampleKind.NATIVE_COVERED, sample.weight()));
            }
        }
        samples.sort(Comparator.comparingDouble(ProfileData.Sample::timeMs));
        events.sort(Comparator.comparingDouble(ProfileData.TraceEvent::startMs));
        cpuLoad.sort(Comparator.comparingDouble(ProfileData.CpuLoad::timeMs));
        gcPauses.sort(Comparator.comparingDouble(ProfileData.GcPause::startMs));
        double duration = Math.max(requestedDurationMs > 0 ? Math.min(requestedDurationMs, maxTimeMs + 1) : 0,
                maxTimeMs + 1);
        return new ProfileData(startEpochMs, duration, frames, stacks, threads, samples, events, cpuLoad, gcPauses,
                exceptions, cpuIntervalUs, truncated);
    }

    /** Whether {@code timeMs} lies inside one of the sorted intervals (binary search on start). */
    private static boolean covered(List<ProfileData.TraceEvent> events, double timeMs) {
        if (events == null || events.isEmpty()) {
            return false;
        }
        int low = 0;
        int high = events.size() - 1;
        int candidate = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (events.get(mid).startMs() <= timeMs) {
                candidate = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        // events of one thread do not overlap, but look back a few in case of nesting (park inside a monitor wait)
        for (int i = candidate; i >= 0 && i > candidate - 4; i--) {
            if (events.get(i).endMs() >= timeMs) {
                return true;
            }
        }
        return false;
    }
}
