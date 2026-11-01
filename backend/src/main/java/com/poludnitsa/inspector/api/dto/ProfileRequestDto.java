package com.poludnitsa.inspector.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Start a CPU / timeline profile of a target.
 *
 * @param source          {@code null}: JFR
 * @param mode            what async-profiler samples (cpu, wall, alloc); JFR records all three at once, so it is
 *                        ignored there. {@code null}: CPU
 * @param durationSeconds recording length
 * @param tracing         record park / monitor / I/O events from 1 ms instead of 10 ms and every thrown exception
 *                        with its stack (JFR only; more overhead). {@code null}: {@code false}
 */
public record ProfileRequestDto(
        @Positive long pid,
        ProfileSource source,
        ProfileMetric mode,
        @NotNull @Min(1) @Max(3_600) Integer durationSeconds,
        Boolean tracing
) {
    public ProfileSource effectiveSource() {
        return source == null ? ProfileSource.JFR : source;
    }

    public ProfileMetric effectiveMode() {
        return mode == null ? ProfileMetric.CPU : mode;
    }

    public boolean tracingEnabled() {
        return Boolean.TRUE.equals(tracing);
    }
}
