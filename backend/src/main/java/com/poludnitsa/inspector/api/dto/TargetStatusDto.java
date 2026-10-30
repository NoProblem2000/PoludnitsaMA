package com.poludnitsa.inspector.api.dto;

/**
 * Connection status of a monitored target, pushed over {@code /ws/live} whenever it changes.
 *
 * @param message reason for non-{@link ConnectionState#CONNECTED} states, {@code null} otherwise
 */
public record TargetStatusDto(
        long pid,
        ConnectionState state,
        String message,
        long timestampEpochMs
) {
}
