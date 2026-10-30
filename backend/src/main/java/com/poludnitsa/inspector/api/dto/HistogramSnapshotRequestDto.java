package com.poludnitsa.inspector.api.dto;

import jakarta.validation.constraints.Positive;

/** Request to store a quick {@code GC.class_histogram -all} of the target as a snapshot. */
public record HistogramSnapshotRequestDto(
        @Positive long pid
) {
}
