package com.poludnitsa.inspector.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Take a series of thread dumps from a target.
 */
public record ThreadSeriesRequestDto(
        @Positive long pid,
        @NotNull @Min(2) @Max(30) Integer dumps,
        @NotNull @Min(200) @Max(60_000) Integer intervalMs
) {
}
