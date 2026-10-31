package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Result of an OQL query.
 *
 * @param columns    one column {@code "value"} for plain results; the property names when the query selects script
 *                   objects ({@code select { name: ..., size: ... } from ...})
 * @param rows       one list of cells per result, in column order
 * @param truncated  more results existed than {@code limit}
 * @param durationMs time of the query in the inspector
 */
public record OqlResultDto(
        String snapshotId,
        String query,
        List<String> columns,
        List<List<OqlValueDto>> rows,
        int rowCount,
        boolean truncated,
        long durationMs
) {
}
