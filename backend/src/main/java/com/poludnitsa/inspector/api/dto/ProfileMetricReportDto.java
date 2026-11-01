package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A metric of a CPU profile as embedded in a report.
 */
public record ProfileMetricReportDto(
        ProfileMetric metric,
        FlameGraphDto flame,
        List<HotspotDto> hotspots,
        SubsystemBreakdownDto subsystems
) {
    public ProfileMetricReportDto {
        hotspots = hotspots == null ? List.of() : List.copyOf(hotspots);
    }
}
