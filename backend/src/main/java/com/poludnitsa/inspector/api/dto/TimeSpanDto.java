package com.poludnitsa.inspector.api.dto;

/** An interval of a profile, in milliseconds from its start. */
public record TimeSpanDto(
        double startMs,
        double durationMs,
        String label
) {
}
