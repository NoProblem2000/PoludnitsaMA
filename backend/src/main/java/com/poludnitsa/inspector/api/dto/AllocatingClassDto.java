package com.poludnitsa.inspector.api.dto;

/**
 * Class allocated at a high rate, estimated from {@code jdk.ObjectAllocationSample} weights.
 *
 * @param bytesPerSec estimated allocation rate of this class
 * @param samples     number of JFR samples behind the estimate
 */
public record AllocatingClassDto(
        String className,
        PackageCategory packageCategory,
        double bytesPerSec,
        long samples
) {
}
