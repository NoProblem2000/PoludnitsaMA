package com.poludnitsa.inspector.api.dto;

/**
 * Growth trend of a class over one rolling window, computed only from post-GC histogram samples
 * (the first histogram after each collection, which approximates the live set best).
 *
 * @param windowMinutes        1, 5 or 15
 * @param samples              post-GC samples inside the window; trends need at least 3
 * @param bytesSlopePerMin     least-squares slope of shallow bytes
 * @param instancesSlopePerMin least-squares slope of instance count
 * @param monotonicity         share of consecutive samples where bytes grew and instances did not shrink, 0..1
 * @param rSquared             goodness of the linear fit of bytes, 0..1
 */
public record GrowthWindowDto(
        int windowMinutes,
        int samples,
        double bytesSlopePerMin,
        double instancesSlopePerMin,
        double monotonicity,
        double rSquared
) {
}
