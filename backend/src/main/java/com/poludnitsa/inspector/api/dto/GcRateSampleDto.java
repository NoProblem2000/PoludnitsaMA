package com.poludnitsa.inspector.api.dto;

/**
 * Allocation and promotion rate of one window of the log.
 *
 * @param uptimeMs window start
 * @param pauseMs pause time in the window
 */
public record GcRateSampleDto(
        double uptimeMs,
        double allocationMbPerSec,
        Double promotionMbPerSec,
        double pauseMs
) {
}
