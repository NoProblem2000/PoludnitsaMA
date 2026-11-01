package com.poludnitsa.inspector.api.dto;

/**
 * A stop-the-world pause.
 *
 * @param gcId -1 when the log has none
 * @param uptimeMs from the JVM start (log) or the recording start (JFR)
 * @param name e.g. "Pause Young (Normal)"
 */
public record GcPauseDto(
        int gcId,
        double uptimeMs,
        GcPauseCategory category,
        String name,
        String cause,
        double pauseMs,
        Double heapBeforeMb,
        Double heapAfterMb,
        Double heapTotalMb
) {
}
