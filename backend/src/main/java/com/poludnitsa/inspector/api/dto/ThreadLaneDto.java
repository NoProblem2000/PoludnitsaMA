package com.poludnitsa.inspector.api.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One thread on the timeline.
 *
 * @param threadIndex index for the {@code thread} filter of the call tree queries
 * @param states      dominant state per column, {@code null} where the thread did nothing visible
 */
public record ThreadLaneDto(
        int threadIndex,
        String name,
        List<ThreadState> states,
        double cpuMs,
        double blockedMs,
        double waitingMs,
        double ioMs
) {
    public ThreadLaneDto {
        // a copy that may hold nulls
        states = states == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(states));
    }
}
