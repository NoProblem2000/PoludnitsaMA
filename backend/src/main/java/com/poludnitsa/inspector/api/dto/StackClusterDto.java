package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Threads with an identical stack and state in one dump.
 */
public record StackClusterDto(
        int clusterId,
        int dumpIndex,
        JvmThreadState state,
        int threadCount,
        List<String> threads,
        String topFrame,
        String lockName,
        int stackId
) {
    public StackClusterDto {
        threads = threads == null ? List.of() : List.copyOf(threads);
    }
}
