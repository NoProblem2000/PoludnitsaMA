package com.poludnitsa.inspector.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Record the GC and safepoints of a live target through JFR for a while.
 */
public record GcLiveRequestDto(
        @Positive long pid,
        @NotNull @Min(5) @Max(1_800) Integer durationSeconds
) {
}
