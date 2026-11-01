package com.poludnitsa.inspector.api.dto;

/**
 * What the inspector can profile with on this host.
 *
 * @param asyncProfilerPath   the {@code asprof} found, or {@code null}
 * @param asyncProfilerReason why async-profiler cannot be used, {@code null} when it can
 */
public record ProfilerCapabilitiesDto(
        boolean jfrAvailable,
        boolean asyncProfilerAvailable,
        String asyncProfilerPath,
        String asyncProfilerReason,
        String os
) {
}
