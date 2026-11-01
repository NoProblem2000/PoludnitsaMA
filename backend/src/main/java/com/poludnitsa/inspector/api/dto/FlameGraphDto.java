package com.poludnitsa.inspector.api.dto;

/**
 * A call tree for a metric, a time window and optionally one thread.
 *
 * @param unit        {@code "us"} (time) or {@code "bytes"}
 * @param fromMs      window start from the profile start; {@code toMs} is its end
 * @param root        synthetic root whose children are the bottom frames (thread entry points)
 * @param prunedNodes frames cut because they were below {@code inspector.profiler.flame-min-share}
 */
public record FlameGraphDto(
        String profileId,
        ProfileMetric metric,
        String unit,
        double fromMs,
        double toMs,
        String threadName,
        long totalValue,
        long totalSamples,
        FlameNodeDto root,
        int prunedNodes
) {
}
