package com.poludnitsa.inspector.jfr;

import com.poludnitsa.inspector.api.dto.AllocatingClassDto;
import com.poludnitsa.inspector.common.PackageCategorizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns JFR allocation events into rates.
 *
 * <ul>
 *   <li>Allocation rate: per-thread deltas of the cumulative {@code jdk.ThreadAllocationStatistics.allocated},
 *       divided by the event time covered since the previous poll. A thread's first event is its baseline, so
 *       allocations of threads that live shorter than one period are not counted.</li>
 *   <li>Top allocating classes: {@code jdk.ObjectAllocationSample.weight} summed per class over the last
 *       {@value #ROLLING_POLLS} polls.</li>
 * </ul>
 * Transferred chunks overlap between polls, so events at or before the last processed time are skipped.
 */
public final class JfrAllocationAggregator {

    static final int ROLLING_POLLS = 3;
    private static final int THREAD_IDLE_POLLS = 3;

    private final int topClasses;
    private final Map<Long, long[]> threads = new HashMap<>();
    private final ArrayDeque<PollWindow> windows = new ArrayDeque<>();

    private long pollAllocatedDelta;
    private Map<String, long[]> pollClasses = new HashMap<>();
    private long pollMinTime = Long.MAX_VALUE;
    private long pollMaxTime = Long.MIN_VALUE;
    private long pollMaxSampleTime = Long.MIN_VALUE;
    private long sampleWatermark = Long.MIN_VALUE;
    private long previousPollEnd = -1;
    private long pollCounter;

    private Double allocationRateBytesPerSec;
    private Long lastEventEpochMs;
    private List<AllocatingClassDto> top = List.of();

    private record PollWindow(Map<String, long[]> classes, long durationMs) {
    }

    public JfrAllocationAggregator(int topClasses) {
        this.topClasses = topClasses;
    }

    /** @param allocatedTotal cumulative bytes allocated by the thread since it started */
    public synchronized void onThreadAllocation(long threadId, long allocatedTotal, long eventTimeMs) {
        long[] state = threads.get(threadId);
        if (state == null) {
            threads.put(threadId, new long[] {allocatedTotal, eventTimeMs, pollCounter});
            track(eventTimeMs);
            return;
        }
        if (eventTimeMs <= state[1]) {
            return;
        }
        if (allocatedTotal >= state[0]) {
            pollAllocatedDelta += allocatedTotal - state[0];
        }
        state[0] = allocatedTotal;
        state[1] = eventTimeMs;
        state[2] = pollCounter;
        track(eventTimeMs);
    }

    /** @param weight bytes allocated that this sample stands for */
    public synchronized void onAllocationSample(String className, long weight, long eventTimeMs) {
        if (eventTimeMs <= sampleWatermark || weight <= 0) {
            return;
        }
        long[] accumulator = pollClasses.computeIfAbsent(className, name -> new long[2]);
        accumulator[0] += weight;
        accumulator[1]++;
        pollMaxSampleTime = Math.max(pollMaxSampleTime, eventTimeMs);
        track(eventTimeMs);
    }

    /** Closes the current poll and recomputes the published rates. */
    public synchronized void completePoll() {
        if (pollMaxTime != Long.MIN_VALUE) {
            if (previousPollEnd >= 0 && pollMaxTime > previousPollEnd) {
                allocationRateBytesPerSec = pollAllocatedDelta * 1000.0 / (pollMaxTime - previousPollEnd);
            }
            long duration = previousPollEnd >= 0 ? pollMaxTime - previousPollEnd : pollMaxTime - pollMinTime;
            windows.addLast(new PollWindow(pollClasses, Math.max(duration, 1)));
            while (windows.size() > ROLLING_POLLS) {
                windows.removeFirst();
            }
            top = computeTop();
            previousPollEnd = pollMaxTime;
            lastEventEpochMs = pollMaxTime;
            sampleWatermark = Math.max(sampleWatermark, pollMaxSampleTime);
        }
        long current = pollCounter;
        threads.values().removeIf(state -> current - state[2] > THREAD_IDLE_POLLS);
        pollCounter++;
        pollAllocatedDelta = 0;
        pollClasses = new HashMap<>();
        pollMinTime = Long.MAX_VALUE;
        pollMaxTime = Long.MIN_VALUE;
        pollMaxSampleTime = Long.MIN_VALUE;
    }

    public synchronized Double allocationRateBytesPerSec() {
        return allocationRateBytesPerSec;
    }

    public synchronized Long lastEventEpochMs() {
        return lastEventEpochMs;
    }

    public synchronized List<AllocatingClassDto> topAllocatingClasses() {
        return top;
    }

    private void track(long eventTimeMs) {
        pollMinTime = Math.min(pollMinTime, eventTimeMs);
        pollMaxTime = Math.max(pollMaxTime, eventTimeMs);
    }

    private List<AllocatingClassDto> computeTop() {
        Map<String, long[]> merged = new HashMap<>();
        long durationMs = 0;
        for (PollWindow window : windows) {
            durationMs += window.durationMs();
            window.classes().forEach((name, values) -> {
                long[] sum = merged.computeIfAbsent(name, n -> new long[2]);
                sum[0] += values[0];
                sum[1] += values[1];
            });
        }
        double seconds = durationMs / 1000.0;
        List<AllocatingClassDto> result = new ArrayList<>(merged.size());
        merged.forEach((name, values) -> result.add(new AllocatingClassDto(
                name, PackageCategorizer.categorize(name), values[0] / seconds, values[1])));
        result.sort((a, b) -> Double.compare(b.bytesPerSec(), a.bytesPerSec()));
        return List.copyOf(result.subList(0, Math.min(topClasses, result.size())));
    }
}
