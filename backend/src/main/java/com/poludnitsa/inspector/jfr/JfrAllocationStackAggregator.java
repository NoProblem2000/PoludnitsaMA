package com.poludnitsa.inspector.jfr;

import com.poludnitsa.inspector.api.dto.AllocationFrameDto;
import com.poludnitsa.inspector.api.dto.AllocationHotspotDto;
import com.poludnitsa.inspector.api.dto.AllocationStackDto;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Allocation call trees per class from JFR allocation events with stack traces, over the last {@code polls} polls.
 *
 * <p>Events are weighted by the bytes they stand for ({@code weight} of {@code jdk.ObjectAllocationSample}, the TLAB
 * size of {@code jdk.ObjectAllocationInNewTLAB}, the allocation size of {@code jdk.ObjectAllocationOutsideTLAB}).
 * A hotspot is the first application frame from the top of a stack (the {@code OrderService.process():142} below
 * {@code ArrayList.grow} and {@code HashMap.resize}); stacks without one fall back to the first non-JDK frame, then
 * to the allocating frame. Transferred chunks overlap between polls, so events at or before the last processed
 * time are skipped.
 */
public final class JfrAllocationStackAggregator {

    private final int polls;
    private final int maxStacksPerClass;
    private final ArrayDeque<Window> windows = new ArrayDeque<>();
    private Map<String, Map<String, Accumulator>> current = new HashMap<>();
    private long currentMin = Long.MAX_VALUE;
    private long currentMax = Long.MIN_VALUE;
    private long watermark = Long.MIN_VALUE;

    private static final class Accumulator {
        final List<AllocationFrameDto> frames;
        long weight;
        long samples;

        Accumulator(List<AllocationFrameDto> frames) {
            this.frames = frames;
        }
    }

    private record Window(Map<String, Map<String, Accumulator>> classes, long durationMs) {
    }

    /** One class's stacks merged over the window. */
    public record ClassStacks(long weightBytes, long samples, long windowMs, List<AllocationHotspotDto> hotspots,
                              List<AllocationStackDto> stacks) {
    }

    public JfrAllocationStackAggregator(int polls, int maxStacksPerClass) {
        this.polls = Math.max(1, polls);
        this.maxStacksPerClass = Math.max(1, maxStacksPerClass);
    }

    /** @param frames top first, already cut to the configured depth */
    public synchronized void onAllocation(String className, List<AllocationFrameDto> frames, long weight,
                                          long eventTimeMs) {
        if (eventTimeMs <= watermark || weight <= 0 || frames.isEmpty()) {
            return;
        }
        Map<String, Accumulator> stacks = current.computeIfAbsent(className, name -> new HashMap<>());
        String key = key(frames);
        Accumulator accumulator = stacks.get(key);
        if (accumulator == null) {
            if (stacks.size() >= maxStacksPerClass) {
                return;
            }
            accumulator = new Accumulator(List.copyOf(frames));
            stacks.put(key, accumulator);
        }
        accumulator.weight += weight;
        accumulator.samples++;
        currentMin = Math.min(currentMin, eventTimeMs);
        currentMax = Math.max(currentMax, eventTimeMs);
    }

    public synchronized void completePoll() {
        if (currentMax != Long.MIN_VALUE) {
            windows.addLast(new Window(current, Math.max(1, currentMax - currentMin)));
            while (windows.size() > polls) {
                windows.removeFirst();
            }
            watermark = Math.max(watermark, currentMax);
        }
        current = new HashMap<>();
        currentMin = Long.MAX_VALUE;
        currentMax = Long.MIN_VALUE;
    }

    public synchronized ClassStacks stacks(String className, int limit) {
        Map<String, Accumulator> merged = new LinkedHashMap<>();
        long windowMs = 0;
        for (Window window : windows) {
            windowMs += window.durationMs();
            Map<String, Accumulator> stacks = window.classes().get(className);
            if (stacks == null) {
                continue;
            }
            stacks.forEach((key, value) -> {
                Accumulator sum = merged.computeIfAbsent(key, k -> new Accumulator(value.frames));
                sum.weight += value.weight;
                sum.samples += value.samples;
            });
        }
        long total = merged.values().stream().mapToLong(a -> a.weight).sum();
        long samples = merged.values().stream().mapToLong(a -> a.samples).sum();
        if (total == 0) {
            return new ClassStacks(0, 0, windowMs, List.of(), List.of());
        }
        Map<String, long[]> hotspotSums = new LinkedHashMap<>();
        Map<String, AllocationFrameDto> hotspotFrames = new HashMap<>();
        for (Accumulator stack : merged.values()) {
            AllocationFrameDto hotspot = hotspot(stack.frames);
            String key = frameKey(hotspot);
            hotspotFrames.putIfAbsent(key, hotspot);
            long[] sum = hotspotSums.computeIfAbsent(key, k -> new long[2]);
            sum[0] += stack.weight;
            sum[1] += stack.samples;
        }
        List<AllocationHotspotDto> hotspots = hotspotSums.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(limit)
                .map(e -> new AllocationHotspotDto(hotspotFrames.get(e.getKey()), e.getValue()[0], e.getValue()[1],
                        (double) e.getValue()[0] / total))
                .toList();
        List<AllocationStackDto> stacks = merged.values().stream()
                .sorted((a, b) -> Long.compare(b.weight, a.weight))
                .limit(limit)
                .map(a -> new AllocationStackDto(a.frames, a.weight, a.samples, (double) a.weight / total))
                .toList();
        return new ClassStacks(total, samples, windowMs, hotspots, stacks);
    }

    /** First application frame, else first frame outside the JDK, else the allocating frame. */
    static AllocationFrameDto hotspot(List<AllocationFrameDto> frames) {
        for (AllocationFrameDto frame : frames) {
            if (frame.application()) {
                return frame;
            }
        }
        for (AllocationFrameDto frame : frames) {
            if (!JfrFrames.isJdk(frame.className())) {
                return frame;
            }
        }
        return frames.get(0);
    }

    private static String key(List<AllocationFrameDto> frames) {
        StringBuilder key = new StringBuilder();
        for (AllocationFrameDto frame : frames) {
            key.append(frameKey(frame)).append('|');
        }
        return key.toString();
    }

    private static String frameKey(AllocationFrameDto frame) {
        return frame.className() + '#' + frame.method() + ':' + frame.line();
    }
}
