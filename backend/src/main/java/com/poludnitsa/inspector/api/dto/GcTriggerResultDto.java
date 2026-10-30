package com.poludnitsa.inspector.api.dto;

/**
 * Result of {@code MemoryMXBean.gc()} on the target.
 *
 * @param durationMs              wall time of the JMX call (for a Full GC this is roughly the pause)
 * @param explicitGcDisabled      {@code -XX:+DisableExplicitGC} is set, so the call was a no-op
 * @param explicitGcConcurrent    {@code -XX:+ExplicitGCInvokesConcurrent} is set, so a concurrent cycle ran instead of
 *                                a Full GC
 * @param warning                 what the call meant for the target
 */
public record GcTriggerResultDto(
        long pid,
        long durationMs,
        long heapUsedBefore,
        long heapUsedAfter,
        boolean explicitGcDisabled,
        boolean explicitGcConcurrent,
        String warning
) {
}
