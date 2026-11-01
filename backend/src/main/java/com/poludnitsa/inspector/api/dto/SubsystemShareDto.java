package com.poludnitsa.inspector.api.dto;

import java.util.List;

/** Time (or bytes) of one subsystem with its heaviest methods. */
public record SubsystemShareDto(
        Subsystem subsystem,
        String label,
        long value,
        long samples,
        double percent,
        List<HotspotDto> topMethods
) {
    public SubsystemShareDto {
        topMethods = topMethods == null ? List.of() : List.copyOf(topMethods);
    }
}
