package com.poludnitsa.inspector.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * A recorded profile.
 *
 * @param mode               the metric async-profiler sampled; JFR profiles carry all metrics
 * @param metrics            metrics with data in this profile
 * @param durationMs         requested recording length
 * @param recordedMs         time covered by the parsed events
 * @param samplingIntervalMs CPU sampling interval: each sample stands for this much time
 * @param truncated          more samples than {@code inspector.profiler.max-samples}: the rest was dropped
 */
public record ProfileSummaryDto(
        String profileId,
        long pid,
        String displayName,
        ProfileSource source,
        ProfileMetric mode,
        ProfileStatus status,
        Instant startedAt,
        long durationMs,
        long recordedMs,
        String progressMessage,
        String errorMessage,
        long sampleCount,
        long eventCount,
        int threadCount,
        double samplingIntervalMs,
        boolean tracing,
        List<ProfileMetric> metrics,
        boolean truncated
) {
    public ProfileSummaryDto {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}
