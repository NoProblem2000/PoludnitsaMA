package com.poludnitsa.inspector.api.dto;

/**
 * Safepoints by VM operation.
 */
public record SafepointOperationStatDto(
        String operation,
        int count,
        double totalMs,
        double maxTotalMs,
        double maxTtspMs
) {
}
