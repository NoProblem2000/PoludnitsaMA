package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A class whose footprint keeps growing across post-GC {@code GC.class_histogram -all} samples of the target JVM.
 *
 * @param instanceCount        instances in the latest histogram
 * @param shallowBytes         total shallow bytes of all instances in the latest histogram
 * @param deltaInstancesPerMin instance-count slope of the longest window with enough samples
 * @param deltaBytesPerMin     shallow-bytes slope of the longest window with enough samples
 * @param leakProbabilityScore 0..100, combines monotonicity, fit, relative and absolute growth, agreement of the
 *                             1/5/15 min windows and survival across old-generation collections
 * @param leakCandidate        {@code leakProbabilityScore} reached the configured candidate threshold
 * @param oldGcSurvivalStreak  consecutive old-generation reclamations (full/old/mixed GC) after which the class was
 *                             larger than before
 * @param windows              per-window trends, ordered 1, 5, 15 minutes
 */
public record ClassGrowthSuspectDto(
        String className,
        PackageCategory packageCategory,
        long instanceCount,
        long shallowBytes,
        double deltaInstancesPerMin,
        double deltaBytesPerMin,
        int leakProbabilityScore,
        boolean leakCandidate,
        int oldGcSurvivalStreak,
        List<GrowthWindowDto> windows
) {
    public ClassGrowthSuspectDto {
        if (leakProbabilityScore < 0 || leakProbabilityScore > 100) {
            throw new IllegalArgumentException("leakProbabilityScore must be within 0..100: " + leakProbabilityScore);
        }
        windows = windows == null ? List.of() : List.copyOf(windows);
    }
}
