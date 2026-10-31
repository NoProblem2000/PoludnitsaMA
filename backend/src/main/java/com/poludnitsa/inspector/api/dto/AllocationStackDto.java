package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Allocations of one class through one stack trace.
 *
 * @param frames      top of the stack first (the allocating frame), cut at {@code inspector.jfr.stack-depth}
 * @param weightBytes bytes the samples stand for
 * @param share       of all sampled bytes of the class, 0..1
 */
public record AllocationStackDto(
        List<AllocationFrameDto> frames,
        long weightBytes,
        long samples,
        double share
) {
    public AllocationStackDto {
        frames = frames == null ? List.of() : List.copyOf(frames);
    }
}
