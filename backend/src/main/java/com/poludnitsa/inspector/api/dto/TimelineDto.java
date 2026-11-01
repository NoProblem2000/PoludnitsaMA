package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Everything on one time axis, in {@code buckets} columns of {@code bucketMs}.
 *
 * @param startEpochMs      wall-clock time of column 0
 * @param jvmCpuPercent     CPU used by the target JVM per column (jdk.CPULoad), {@code null} where unknown
 * @param machineCpuPercent CPU of the whole machine per column, {@code null} where unknown
 * @param samples           execution samples per column: a CPU estimate where jdk.CPULoad is missing
 * @param gcPauseMs         stop-the-world GC pause time per column
 * @param lockMs            time threads spent blocked on monitors per column
 * @param waitMs            time threads spent parked, waiting or sleeping per column
 * @param ioMs              time threads spent in socket / file I/O per column
 * @param gcPauses          each pause, for markers
 */
public record TimelineDto(
        String profileId,
        long startEpochMs,
        double durationMs,
        double bucketMs,
        int buckets,
        List<Double> jvmCpuPercent,
        List<Double> machineCpuPercent,
        List<Integer> samples,
        List<Double> gcPauseMs,
        List<Double> lockMs,
        List<Double> waitMs,
        List<Double> ioMs,
        List<TimeSpanDto> gcPauses,
        List<ThreadLaneDto> threads
) {
}
