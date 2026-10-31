package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Who allocates a class in a target, from the JFR recording of the inspector.
 *
 * @param weightBytes  sampled bytes of the class in the window
 * @param windowMs     time covered by the aggregated events
 * @param hotspots     first application frame, by weight
 * @param stacks       full stacks, by weight
 * @param eventTypes   JFR events the data comes from
 */
public record AllocationStacksDto(
        long pid,
        String className,
        long weightBytes,
        long samples,
        long windowMs,
        List<AllocationHotspotDto> hotspots,
        List<AllocationStackDto> stacks,
        List<String> eventTypes
) {
    public AllocationStacksDto {
        hotspots = hotspots == null ? List.of() : List.copyOf(hotspots);
        stacks = stacks == null ? List.of() : List.copyOf(stacks);
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
    }
}
