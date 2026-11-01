package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Where the time of a window goes: business code, JDBC / PostgreSQL, Hibernate, HTTP (Undertow / servlets), I/O,
 * locks and GC.
 *
 * @param traced     duration events of the window by type
 * @param exceptions thrown exceptions by class (JFR: errors always, all exceptions in tracing mode)
 */
public record SubsystemBreakdownDto(
        String profileId,
        ProfileMetric metric,
        String unit,
        double fromMs,
        double toMs,
        long totalValue,
        List<SubsystemShareDto> subsystems,
        List<TracedEventStatDto> traced,
        List<ExceptionCountDto> exceptions
) {
}
