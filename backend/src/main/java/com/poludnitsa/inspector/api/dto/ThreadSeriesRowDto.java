package com.poludnitsa.inspector.api.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A thread across the dumps (one row of the matrix).
 *
 * @param idle      JVM housekeeping, or a pool / selector / accept thread that only waits for work
 * @param clusterId cluster of identical stacks in the last dump, {@code null} if none
 * @param cells     one per dump, {@code null} where the thread does not exist
 */
public record ThreadSeriesRowDto(
        String name,
        Long threadId,
        boolean daemon,
        boolean serverThread,
        boolean idle,
        boolean stuck,
        Integer clusterId,
        List<ThreadCellDto> cells
) {
    public ThreadSeriesRowDto {
        // a copy that may hold nulls
        cells = cells == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(cells));
    }
}
