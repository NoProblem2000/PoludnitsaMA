package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * State and results of the optional JFR allocation profile that runs inside the target JVM.
 *
 * @param active                    the recording is running on the target
 * @param recordingName             name of the recording on the target ({@code jcmd <pid> JFR.check} shows it)
 * @param startedAtEpochMs          when the recording was started
 * @param lastUpdatedEpochMs        time of the newest event processed, {@code null} before the first poll
 * @param allocationRateBytesPerSec from {@code jdk.ThreadAllocationStatistics} deltas, {@code null} until two polls
 * @param topAllocatingClasses      rolling 30 s view, ordered by rate descending
 * @param error                     last polling error, {@code null} when healthy
 */
public record JfrAllocationStatsDto(
        boolean active,
        String recordingName,
        long startedAtEpochMs,
        Long lastUpdatedEpochMs,
        Double allocationRateBytesPerSec,
        List<AllocatingClassDto> topAllocatingClasses,
        String error
) {
    public JfrAllocationStatsDto {
        topAllocatingClasses = topAllocatingClasses == null ? List.of() : List.copyOf(topAllocatingClasses);
    }
}
