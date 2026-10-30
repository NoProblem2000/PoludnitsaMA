package com.poludnitsa.inspector.api.dto;

import java.time.Instant;

/**
 * Uniform error body for REST endpoints.
 *
 * @param code machine-readable code, e.g. {@code "INSUFFICIENT_DISK_SPACE"}, {@code "TARGET_NOT_ATTACHABLE"}
 */
public record ApiErrorDto(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path
) {
}
