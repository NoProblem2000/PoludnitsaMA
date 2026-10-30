package com.poludnitsa.inspector.api.dto;

import jakarta.validation.constraints.Positive;

/**
 * Request to dump and analyse the heap of a target JVM.
 *
 * <p>Writing the dump always happens at a safepoint: the target is paused for the whole write, roughly
 * proportional to the used heap.
 *
 * @param liveObjectsOnly {@code false} (default): no Full GC before the dump, so the pause is only the write;
 *                        unreachable objects are included. {@code true}: a Full GC runs first, the dump holds only
 *                        reachable objects (smaller, exact for diffs) but the pause is longer and may drop Hazelcast
 *                        client connections on heartbeat timeout.
 */
public record HeapDumpRequestDto(
        @Positive long pid,
        boolean liveObjectsOnly
) {
}
