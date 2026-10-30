package com.poludnitsa.inspector.live;

import java.util.List;

/**
 * Leak probability 0..100 of one class, from its post-GC growth.
 *
 * <ul>
 *   <li><b>M</b> monotonicity: share of post-GC steps that grew (weight 0.25)</li>
 *   <li><b>R</b> R² of the linear fit: steady growth vs noise (0.15)</li>
 *   <li><b>G</b> relative fitted growth, saturating: +25% gives 0.63, +100% gives 0.98 (0.15)</li>
 *   <li><b>S</b> significance: absolute fitted growth vs max(1 MiB, 0.5% of used heap) (0.15)</li>
 *   <li><b>O</b> survival: consecutive old-generation reclamations after which the class was larger (0.30)</li>
 * </ul>
 * Without at least two old-generation reclamations in the window, O is unknown and the score is capped at 80: growth
 * that never met an old GC may still be garbage waiting to be collected. The result is scaled by sample confidence
 * and by how many of the 1/5/15 min windows agree on growth.
 */
final class LeakScorer {

    private LeakScorer() {
    }

    static int score(GrowthStatistics.WindowStats primary, List<GrowthStatistics.WindowStats> windows,
                     int oldSurvivalStreak, int oldReclamationPoints, long heapUsedBytes) {
        if (primary == null || primary.samples() < 3 || primary.bytesSlopePerMin() <= 0) {
            return 0;
        }
        double growth = primary.fittedGrowth();
        if (growth <= 0) {
            return 0;
        }
        double m = primary.monotonicity();
        double r = primary.rSquared();
        double relative = growth / Math.max(primary.fittedStartBytes(), 1.0);
        double g = 1 - Math.exp(-relative / 0.25);
        double significanceBytes = Math.max(1 << 20, heapUsedBytes * 0.005);
        double s = Math.min(1.0, growth / significanceBytes);

        double trend = 0.25 * m + 0.15 * r + 0.15 * g + 0.15 * s;
        double base;
        if (oldReclamationPoints >= 2) {
            double o = Math.min(1.0, oldSurvivalStreak / 3.0);
            base = trend + 0.30 * o;
            if (oldSurvivalStreak == 0) {
                // It shrank at the last old-generation reclamation: most likely cache churn, not a leak.
                base *= 0.6;
            }
        } else {
            base = 0.8 * trend / 0.70;
        }

        int considered = 0;
        int growing = 0;
        for (GrowthStatistics.WindowStats window : windows) {
            if (window.samples() >= 3) {
                considered++;
                if (window.bytesSlopePerMin() > 0) {
                    growing++;
                }
            }
        }
        double agreement = considered == 0 ? 0 : growing / (double) considered;
        double confidence = Math.sqrt(Math.min(1.0, primary.samples() / 8.0));
        long result = Math.round(100 * base * confidence * (0.5 + 0.5 * agreement));
        return (int) Math.max(0, Math.min(100, result));
    }
}
