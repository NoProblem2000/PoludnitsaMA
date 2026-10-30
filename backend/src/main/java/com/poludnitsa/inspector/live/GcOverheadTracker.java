package com.poludnitsa.inspector.live;

import java.util.ArrayDeque;

/**
 * Rolling window of cumulative stop-the-world GC time and count. Keeps one sample at or before the window start as
 * the baseline, so the window always spans the full period once enough samples exist.
 */
final class GcOverheadTracker {

    private final long windowMs;
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();

    GcOverheadTracker(long windowMs) {
        this.windowMs = windowMs;
    }

    void add(long timestampMs, long cumulativePauseMs, long cumulativeCollections) {
        long[] last = samples.peekLast();
        if (last != null && (cumulativePauseMs < last[1] || cumulativeCollections < last[2])) {
            // Counters went backwards: not the same JVM run any more.
            samples.clear();
        }
        samples.addLast(new long[] {timestampMs, cumulativePauseMs, cumulativeCollections});
        long cutoff = timestampMs - windowMs;
        while (samples.size() > 1) {
            long[] first = samples.pollFirst();
            long[] next = samples.peekFirst();
            if (next[0] > cutoff) {
                samples.addFirst(first);
                break;
            }
        }
    }

    /** Share of wall time spent in pauses over the window, 0..100. */
    double overheadPercent() {
        if (samples.size() < 2) {
            return 0;
        }
        long[] first = samples.peekFirst();
        long[] last = samples.peekLast();
        long elapsed = last[0] - first[0];
        if (elapsed <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, 100.0 * (last[1] - first[1]) / elapsed));
    }

    /** Collections per minute over the window, extrapolated while the window is still filling. */
    double collectionsPerMinute() {
        if (samples.size() < 2) {
            return 0;
        }
        long[] first = samples.peekFirst();
        long[] last = samples.peekLast();
        long elapsed = last[0] - first[0];
        return elapsed <= 0 ? 0 : (last[2] - first[2]) * 60_000.0 / elapsed;
    }
}
