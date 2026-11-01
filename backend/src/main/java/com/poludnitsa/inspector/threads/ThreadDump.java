package com.poludnitsa.inspector.threads;

import java.time.Instant;
import java.util.List;

/**
 * All Java threads at one moment.
 *
 * @param capturedAt {@code null} when a dump file carries no timestamp
 * @param jvmInfo    "Full thread dump …" header of a jstack text, {@code null} for a live dump
 */
public record ThreadDump(int index, Instant capturedAt, String jvmInfo, List<ThreadEntry> threads) {

    public ThreadDump {
        threads = threads == null ? List.of() : List.copyOf(threads);
    }
}
