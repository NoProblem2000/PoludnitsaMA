package com.poludnitsa.inspector.api.dto;

/**
 * A thread in one dump of the matrix.
 *
 * @param stackId index into the stacks of the analysis
 * @param sameAsPrevious identical state, stack and lock as in the previous dump
 * @param cpuDeltaMs CPU since the previous dump, {@code null} when unknown
 */
public record ThreadCellDto(
        JvmThreadState state,
        int stackId,
        boolean sameAsPrevious,
        String topFrame,
        String lockName,
        Double cpuDeltaMs
) {
}
