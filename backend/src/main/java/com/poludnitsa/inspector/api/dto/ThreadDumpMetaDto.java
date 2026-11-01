package com.poludnitsa.inspector.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * One dump of a series.
 *
 * @param capturedAt {@code null} if the file has no timestamp
 * @param offsetMs from the first dump
 * @param jvmInfo the "Full thread dump …" header, if any
 */
public record ThreadDumpMetaDto(
        int index,
        Instant capturedAt,
        Long offsetMs,
        int threadCount,
        Map<JvmThreadState, Integer> states,
        String jvmInfo
) {
    public ThreadDumpMetaDto {
        states = states == null ? Map.of() : Map.copyOf(states);
    }
}
