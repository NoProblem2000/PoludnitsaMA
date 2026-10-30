package com.poludnitsa.inspector.api.dto;

/**
 * @param pid active PID, {@code null} when none is selected
 */
public record ActiveProcessDto(
        Long pid
) {
}
