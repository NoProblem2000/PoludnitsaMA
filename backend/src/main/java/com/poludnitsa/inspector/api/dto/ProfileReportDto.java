package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A CPU & Timeline profile as embedded in a report (no raw samples: no re-filtering).
 */
public record ProfileReportDto(
        ProfileSummaryDto summary,
        TimelineDto timeline,
        List<ProfileMetricReportDto> metrics
) {
    public ProfileReportDto {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }
}
