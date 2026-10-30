package com.poludnitsa.inspector.live;

import com.poludnitsa.inspector.api.dto.ClassGrowthSuspectDto;
import com.poludnitsa.inspector.common.PackageCategorizer;
import com.poludnitsa.inspector.config.InspectorProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Live leak radar of one target: rolling per-class series of {@code GC.class_histogram -all} samples and their
 * survival trend across garbage collections.
 *
 * <p>{@code -all} histograms also count unreachable objects. Objects are only freed by a GC, so between two
 * collections a class can only grow; the first sample after each GC is the best available estimate of its live set.
 * Trends are therefore computed from post-GC samples only, over rolling 1, 5 and 15 minute windows. Samples taken
 * after an old-generation reclamation (old/full GC, or a drop of the old pool's post-GC usage, i.e. a G1 mixed
 * collection) additionally feed the survival streak: a class that is larger after each of them survives exactly the
 * collections that should have removed it.
 *
 * <p>Memory is bounded: at most {@code maxTrackedClasses} series of {@code capacity} samples each.
 */
public final class LeakRadar {

    /** GC counters read right after a histogram. */
    public record GcContext(long totalCollections, long oldCollections, Long oldPoolUsedAfterGc) {
    }

    public record Settings(
            int capacity,
            int maxTrackedClasses,
            long trackMinBytes,
            long trackMinInstances,
            int topSuspects,
            int candidateThreshold
    ) {
        public static Settings from(InspectorProperties.Live live) {
            long perWindow = Math.max(1, MAX_WINDOW_MS / Math.max(1, live.histogramInterval().toMillis()));
            int capacity = (int) Math.min(4096, Math.max(32, perWindow * 2 + 1));
            return new Settings(capacity, live.maxTrackedClasses(), live.trackMinBytes().toBytes(),
                    live.trackMinInstances(), live.topSuspects(), live.leakCandidateThreshold());
        }
    }

    static final int[] WINDOW_MINUTES = {1, 5, 15};
    private static final long MAX_WINDOW_MS = 15 * 60_000L;
    private static final int MAX_ADMISSIONS_WHEN_FULL = 64;

    private final Settings settings;
    private final long[] times;
    private final boolean[] postGc;
    private final boolean[] oldReclamation;
    private final Map<String, ClassSeries> series = new HashMap<>();
    private final long[] scratchTimes;
    private final long[] scratchBytes;
    private final long[] scratchInstances;
    private long nextSeq;
    private GcContext lastGc;
    private long heapUsedBytes;
    private volatile List<ClassGrowthSuspectDto> suspects = List.of();

    public LeakRadar(Settings settings) {
        this.settings = settings;
        this.times = new long[settings.capacity()];
        this.postGc = new boolean[settings.capacity()];
        this.oldReclamation = new boolean[settings.capacity()];
        this.scratchTimes = new long[settings.capacity()];
        this.scratchBytes = new long[settings.capacity()];
        this.scratchInstances = new long[settings.capacity()];
    }

    /** Current suspects, ordered by score descending; safe to call from any thread. */
    public List<ClassGrowthSuspectDto> suspects() {
        return suspects;
    }

    public synchronized int trackedClasses() {
        return series.size();
    }

    public synchronized void ingest(ClassHistogram histogram, GcContext gc, long heapUsed) {
        long seq = nextSeq++;
        int slot = slot(seq);
        boolean afterGc = lastGc == null || gc.totalCollections() != lastGc.totalCollections();
        boolean afterOldReclamation = lastGc != null && (gc.oldCollections() != lastGc.oldCollections()
                || shrank(lastGc.oldPoolUsedAfterGc(), gc.oldPoolUsedAfterGc()));
        lastGc = gc;
        times[slot] = histogram.timestampEpochMs();
        postGc[slot] = afterGc;
        oldReclamation[slot] = afterOldReclamation;
        heapUsedBytes = heapUsed > 0 ? heapUsed : histogram.totalBytes();

        int admissionsWhenFull = 0;
        for (HistogramEntry entry : histogram.entries()) {
            ClassSeries classSeries = series.get(entry.className());
            if (classSeries == null) {
                if (entry.bytes() < settings.trackMinBytes() && entry.instances() < settings.trackMinInstances()) {
                    continue;
                }
                if (series.size() >= settings.maxTrackedClasses()
                        && (admissionsWhenFull++ >= MAX_ADMISSIONS_WHEN_FULL || !evictSmallerThan(entry.bytes()))) {
                    continue;
                }
                classSeries = new ClassSeries(entry.className(), seq, settings.capacity());
                series.put(entry.className(), classSeries);
            }
            classSeries.record(seq, slot, entry.instances(), entry.bytes());
        }

        long oldestSeq = seq - settings.capacity() + 1;
        Iterator<ClassSeries> iterator = series.values().iterator();
        while (iterator.hasNext()) {
            ClassSeries classSeries = iterator.next();
            if (classSeries.lastRecordedSeq != seq) {
                // Absent from the histogram: no instances left.
                classSeries.record(seq, slot, 0, 0);
            }
            if (classSeries.lastNonZeroSeq < oldestSeq) {
                iterator.remove();
            }
        }
        suspects = computeSuspects(seq);
    }

    private List<ClassGrowthSuspectDto> computeSuspects(long latestSeq) {
        long latestTime = times[slot(latestSeq)];
        long firstValidSeq = Math.max(0, latestSeq - settings.capacity() + 1);
        List<Scored> scored = new ArrayList<>();
        for (ClassSeries classSeries : series.values()) {
            long from = Math.max(firstValidSeq, classSeries.firstSeq);
            List<GrowthStatistics.WindowStats> windows = new ArrayList<>(WINDOW_MINUTES.length);
            GrowthStatistics.WindowStats primary = null;
            for (int minutes : WINDOW_MINUTES) {
                int n = collectPostGc(classSeries, from, latestSeq, latestTime - minutes * 60_000L);
                GrowthStatistics.WindowStats stats =
                        GrowthStatistics.compute(minutes, scratchTimes, scratchBytes, scratchInstances, n);
                windows.add(stats);
                if (stats.samples() >= 3) {
                    primary = stats;
                }
            }
            int[] survival = oldSurvival(classSeries, from, latestSeq, latestTime - MAX_WINDOW_MS);
            int score = LeakScorer.score(primary, windows, survival[0], survival[1], heapUsedBytes);
            if (score > 0) {
                scored.add(new Scored(classSeries, primary, windows, survival[0], score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(Comparator.comparingDouble((Scored s) -> s.primary().bytesSlopePerMin()).reversed()));
        List<ClassGrowthSuspectDto> result = new ArrayList<>(Math.min(scored.size(), settings.topSuspects()));
        for (Scored s : scored.subList(0, Math.min(scored.size(), settings.topSuspects()))) {
            result.add(new ClassGrowthSuspectDto(
                    s.series().name,
                    PackageCategorizer.categorize(s.series().name),
                    s.series().latestInstances,
                    s.series().latestBytes,
                    s.primary().instancesSlopePerMin(),
                    s.primary().bytesSlopePerMin(),
                    s.score(),
                    s.score() >= settings.candidateThreshold(),
                    s.survivalStreak(),
                    s.windows().stream().map(GrowthStatistics.WindowStats::toDto).toList()));
        }
        return List.copyOf(result);
    }

    private int collectPostGc(ClassSeries classSeries, long fromSeq, long toSeq, long cutoffTime) {
        int n = 0;
        for (long seq = fromSeq; seq <= toSeq; seq++) {
            int slot = slot(seq);
            if (postGc[slot] && times[slot] >= cutoffTime) {
                scratchTimes[n] = times[slot];
                scratchBytes[n] = classSeries.bytes[slot];
                scratchInstances[n] = classSeries.instances[slot];
                n++;
            }
        }
        return n;
    }

    /** {@code [streak, points]}: trailing growth streak across old-generation reclamations and how many there were. */
    private int[] oldSurvival(ClassSeries classSeries, long fromSeq, long toSeq, long cutoffTime) {
        int points = 0;
        int streak = 0;
        long previousBytes = 0;
        long previousInstances = 0;
        for (long seq = fromSeq; seq <= toSeq; seq++) {
            int slot = slot(seq);
            if (!oldReclamation[slot] || times[slot] < cutoffTime) {
                continue;
            }
            long bytes = classSeries.bytes[slot];
            long instances = classSeries.instances[slot];
            if (points > 0) {
                streak = bytes > previousBytes && instances >= previousInstances ? streak + 1 : 0;
            }
            points++;
            previousBytes = bytes;
            previousInstances = instances;
        }
        return new int[] {streak, points};
    }

    private boolean evictSmallerThan(long bytes) {
        ClassSeries smallest = null;
        for (ClassSeries classSeries : series.values()) {
            if (smallest == null || classSeries.latestBytes < smallest.latestBytes) {
                smallest = classSeries;
            }
        }
        if (smallest != null && smallest.latestBytes < bytes) {
            series.remove(smallest.name);
            return true;
        }
        return false;
    }

    private static boolean shrank(Long previous, Long current) {
        return previous != null && current != null && current < previous;
    }

    private int slot(long seq) {
        return (int) (seq % settings.capacity());
    }

    private record Scored(ClassSeries series, GrowthStatistics.WindowStats primary,
                          List<GrowthStatistics.WindowStats> windows, int survivalStreak, int score) {
    }

    private static final class ClassSeries {
        final String name;
        final long firstSeq;
        final long[] instances;
        final long[] bytes;
        long lastRecordedSeq = -1;
        long lastNonZeroSeq;
        long latestInstances;
        long latestBytes;

        ClassSeries(String name, long firstSeq, int capacity) {
            this.name = name;
            this.firstSeq = firstSeq;
            this.lastNonZeroSeq = firstSeq;
            this.instances = new long[capacity];
            this.bytes = new long[capacity];
        }

        void record(long seq, int slot, long instanceCount, long byteCount) {
            instances[slot] = instanceCount;
            bytes[slot] = byteCount;
            lastRecordedSeq = seq;
            latestInstances = instanceCount;
            latestBytes = byteCount;
            if (byteCount > 0) {
                lastNonZeroSeq = seq;
            }
        }
    }
}
