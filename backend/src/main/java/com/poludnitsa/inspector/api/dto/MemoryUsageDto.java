package com.poludnitsa.inspector.api.dto;

/**
 * Mirrors {@link java.lang.management.MemoryUsage}. All values are bytes; {@code -1} means undefined.
 */
public record MemoryUsageDto(
        long init,
        long used,
        long committed,
        long max
) {
}
