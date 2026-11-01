package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A thread with the same stack and state (RUNNABLE or BLOCKED) in consecutive dumps.
 *
 * @param dumps length of the run
 * @param durationMs between the first and the last dump of the run, when timestamps are known
 * @param cpuDeltaMs CPU over the run, {@code null} when unknown
 */
public record StuckThreadDto(
        String name,
        Long threadId,
        JvmThreadState state,
        StuckKind kind,
        String reason,
        int fromDump,
        int toDump,
        int dumps,
        Long durationMs,
        Double cpuDeltaMs,
        String lockName,
        boolean serverThread,
        List<StackFrameDto> stack
) {
    public StuckThreadDto {
        stack = stack == null ? List.of() : List.copyOf(stack);
    }
}
