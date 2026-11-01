package com.poludnitsa.inspector.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * A wait-for cycle: every thread waits for a lock held by the next one.
 *
 * @param kind MONITOR when every lock is a monitor, else SYNCHRONIZER
 */
public record DeadlockDto(
        Instant detectedAt,
        LockKind kind,
        List<DeadlockNodeDto> cycle
) {
    public DeadlockDto {
        cycle = cycle == null ? List.of() : List.copyOf(cycle);
    }
}
