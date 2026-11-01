package com.poludnitsa.inspector.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Real-time thread state of a target: counts, deadlocks and contention since sampling started.
 *
 * @param samples thread samples taken since {@code samplingSince}
 * @param contentionMonitoring ThreadMXBean contention monitoring is enabled on the target
 * @param deadlocks from findDeadlockedThreads / findMonitorDeadlockedThreads
 * @param threads most blocked and busiest first
 */
public record ThreadLiveDto(
        long pid,
        Instant capturedAt,
        Instant samplingSince,
        long samples,
        long sampleIntervalMs,
        int threadCount,
        int daemonCount,
        int peakThreadCount,
        Map<JvmThreadState, Integer> states,
        boolean contentionMonitoring,
        boolean cpuTimeMonitoring,
        List<DeadlockDto> deadlocks,
        List<MonitorContentionDto> contention,
        List<LiveThreadDto> threads
) {
    public ThreadLiveDto {
        deadlocks = deadlocks == null ? List.of() : List.copyOf(deadlocks);
        contention = contention == null ? List.of() : List.copyOf(contention);
        threads = threads == null ? List.of() : List.copyOf(threads);
        states = states == null ? Map.of() : Map.copyOf(states);
    }
}
