package com.poludnitsa.inspector.api.dto;

import jakarta.validation.constraints.Positive;

/**
 * Request to dump the heap of a target JVM.
 *
 * @param liveObjectsOnly {@code false}: no Full GC and a short pause, safe for Hazelcast client heartbeats (default);
 *                        {@code true}: Full GC first, so a smaller and more precise dump but a longer
 *                        stop-the-world pause that may drop Hazelcast connections on timeout
 */
public record HeapDumpRequestDto(
        @Positive long pid,
        boolean liveObjectsOnly
) {
}
