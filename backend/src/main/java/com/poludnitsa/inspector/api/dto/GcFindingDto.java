package com.poludnitsa.inspector.api.dto;

/**
 * A conclusion drawn from the log, with advice.
 */
public record GcFindingDto(
        Severity severity,
        String code,
        String title,
        String detail,
        String advice
) {
}
