package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Safepoint statistics (-Xlog:safepoint or jdk.Safepoint* JFR events).
 *
 * @param slowest by total time
 */
public record SafepointSummaryDto(
        int count,
        double totalMs,
        double maxTtspMs,
        double avgTtspMs,
        double ttspWarningMs,
        int slowTtspCount,
        List<SafepointOperationStatDto> operations,
        List<SafepointEventDto> slowest
) {
    public SafepointSummaryDto {
        operations = operations == null ? List.of() : List.copyOf(operations);
        slowest = slowest == null ? List.of() : List.copyOf(slowest);
    }
}
