package com.poludnitsa.inspector.api.dto;

/**
 * The first application frame of allocation stacks: the line of code that creates the objects.
 *
 * @param share of all sampled bytes of the class, 0..1
 */
public record AllocationHotspotDto(
        AllocationFrameDto frame,
        long weightBytes,
        long samples,
        double share
) {
}
