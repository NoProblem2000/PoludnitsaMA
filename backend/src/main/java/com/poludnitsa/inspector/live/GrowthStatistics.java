package com.poludnitsa.inspector.live;

import com.poludnitsa.inspector.api.dto.GrowthWindowDto;

/** Least-squares trend and monotonicity of one class over one window of post-GC samples. */
final class GrowthStatistics {

    /**
     * @param fittedStartBytes value of the fitted line at the first sample
     * @param fittedGrowth     growth along the fitted line over the window; unlike last-minus-first it is not fooled
     *                         by a series that merely oscillates and happens to end high
     */
    record WindowStats(
            int windowMinutes,
            int samples,
            double bytesSlopePerMin,
            double instancesSlopePerMin,
            double monotonicity,
            double rSquared,
            double fittedStartBytes,
            double fittedGrowth
    ) {
        GrowthWindowDto toDto() {
            return new GrowthWindowDto(windowMinutes, samples, bytesSlopePerMin, instancesSlopePerMin, monotonicity,
                    rSquared);
        }
    }

    private GrowthStatistics() {
    }

    /** Uses the first {@code n} elements of the arrays; times are epoch millis in ascending order. */
    static WindowStats compute(int windowMinutes, long[] timesMs, long[] bytes, long[] instances, int n) {
        if (n == 0) {
            return new WindowStats(windowMinutes, 0, 0, 0, 0, 0, 0, 0);
        }
        if (n == 1) {
            return new WindowStats(windowMinutes, 1, 0, 0, 0, 0, bytes[0], 0);
        }
        double meanT = 0;
        double meanB = 0;
        double meanI = 0;
        for (int i = 0; i < n; i++) {
            meanT += minutes(timesMs, i);
            meanB += bytes[i];
            meanI += instances[i];
        }
        meanT /= n;
        meanB /= n;
        meanI /= n;
        double sxx = 0;
        double sxb = 0;
        double sxi = 0;
        double sbb = 0;
        int increasing = 0;
        for (int i = 0; i < n; i++) {
            double dt = minutes(timesMs, i) - meanT;
            double db = bytes[i] - meanB;
            sxx += dt * dt;
            sxb += dt * db;
            sxi += dt * (instances[i] - meanI);
            sbb += db * db;
            if (i > 0 && bytes[i] > bytes[i - 1] && instances[i] >= instances[i - 1]) {
                increasing++;
            }
        }
        double bytesSlope = sxx == 0 ? 0 : sxb / sxx;
        double instancesSlope = sxx == 0 ? 0 : sxi / sxx;
        double rSquared = sxx == 0 || sbb == 0 ? 0 : (sxb * sxb) / (sxx * sbb);
        double span = minutes(timesMs, n - 1);
        return new WindowStats(windowMinutes, n, bytesSlope, instancesSlope, increasing / (double) (n - 1),
                Math.min(1, rSquared), meanB - bytesSlope * meanT, bytesSlope * span);
    }

    private static double minutes(long[] timesMs, int i) {
        return (timesMs[i] - timesMs[0]) / 60_000.0;
    }
}
