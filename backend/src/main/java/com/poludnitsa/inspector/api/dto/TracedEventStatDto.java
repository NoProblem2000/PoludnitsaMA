package com.poludnitsa.inspector.api.dto;

/**
 * Precisely timed events of one kind in the window (JFR events with a duration).
 *
 * @param type      JFR event type, e.g. {@code jdk.SocketRead}
 * @param topDetail the most frequent detail (address, file, monitor class, exception class)
 */
public record TracedEventStatDto(
        String type,
        String label,
        long count,
        double totalMs,
        double maxMs,
        String topDetail
) {
}
