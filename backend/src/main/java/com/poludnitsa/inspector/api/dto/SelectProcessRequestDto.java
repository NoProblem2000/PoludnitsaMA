package com.poludnitsa.inspector.api.dto;

import jakarta.validation.constraints.Positive;

/** Selects the active PID; the active PID is monitored even without WebSocket subscribers. */
public record SelectProcessRequestDto(
        @Positive long pid
) {
}
