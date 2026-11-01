package com.poludnitsa.inspector.api.dto;

/**
 * Collections by cause.
 *
 * @param percentOfPauses of all pause time
 * @param advice what to check, {@code null} for ordinary causes
 */
public record GcCauseStatDto(
        String cause,
        int count,
        double totalPauseMs,
        double maxPauseMs,
        double percentOfPauses,
        Severity severity,
        String advice
) {
}
