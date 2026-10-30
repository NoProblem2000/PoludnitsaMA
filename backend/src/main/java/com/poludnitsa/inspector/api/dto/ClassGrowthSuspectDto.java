package com.poludnitsa.inspector.api.dto;

/**
 * A class whose footprint grows across consecutive {@code GC.class_histogram} samples of the target JVM.
 *
 * @param instanceCount        instances in the latest histogram
 * @param shallowBytes         total shallow bytes of all instances in the latest histogram
 * @param deltaInstancesPerMin regression slope of the instance count
 * @param deltaBytesPerMin     regression slope of shallow bytes
 * @param leakProbabilityScore 0..100, combines slope, monotonicity and survival across old-generation GCs
 */
public record ClassGrowthSuspectDto(
        String className,
        PackageCategory packageCategory,
        long instanceCount,
        long shallowBytes,
        double deltaInstancesPerMin,
        double deltaBytesPerMin,
        int leakProbabilityScore
) {
    public ClassGrowthSuspectDto {
        if (leakProbabilityScore < 0 || leakProbabilityScore > 100) {
            throw new IllegalArgumentException("leakProbabilityScore must be within 0..100: " + leakProbabilityScore);
        }
    }
}
