package com.poludnitsa.inspector.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.poludnitsa.inspector.api.dto.ClassGrowthSuspectDto;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;

class LeakRadarTest {

    private static final long MB = 1 << 20;
    private static final long INTERVAL_MS = 10_000;

    private final LeakRadar radar = new LeakRadar(new LeakRadar.Settings(200, 100, 1, 1, 10, 60));

    @Test
    void steadyGrowthSurvivingOldCollectionsIsLeakCandidate() {
        feed(90, sample -> 4 * MB + sample * MB, sample -> true, sample -> sample % 5 == 0);

        ClassGrowthSuspectDto suspect = suspect("com.acme.Leak").orElseThrow();

        assertThat(suspect.leakProbabilityScore()).isGreaterThanOrEqualTo(90);
        assertThat(suspect.leakCandidate()).isTrue();
        assertThat(suspect.oldGcSurvivalStreak()).isGreaterThanOrEqualTo(3);
        assertThat(suspect.packageCategory()).isEqualTo(PackageCategory.APPLICATION);
        assertThat(suspect.deltaBytesPerMin()).isCloseTo(6.0 * MB, org.assertj.core.data.Offset.offset(1.0));
        assertThat(suspect.windows()).extracting(w -> w.windowMinutes()).containsExactly(1, 5, 15);
    }

    @Test
    void growthWithoutOldCollectionsIsCappedBelowCertainty() {
        feed(90, sample -> 4 * MB + sample * MB, sample -> true, sample -> false);

        ClassGrowthSuspectDto suspect = suspect("com.acme.Leak").orElseThrow();

        assertThat(suspect.leakProbabilityScore()).isBetween(60, 80);
        assertThat(suspect.oldGcSurvivalStreak()).isZero();
    }

    @Test
    void oscillatingChurnScoresLow() {
        feed(90, sample -> sample % 2 == 0 ? 5 * MB : 10 * MB, sample -> true, sample -> sample % 5 == 0);

        Optional<ClassGrowthSuspectDto> suspect = suspect("com.acme.Leak");

        assertThat(suspect.map(ClassGrowthSuspectDto::leakProbabilityScore).orElse(0)).isLessThan(30);
    }

    @Test
    void stableClassIsNotReported() {
        feed(30, sample -> 8 * MB, sample -> true, sample -> sample % 5 == 0);

        assertThat(suspect("com.acme.Leak")).isEmpty();
    }

    @Test
    void growthBetweenCollectionsOnlyIsIgnored() {
        // Grows while garbage accumulates, back to 1 MB after every GC (one GC per three samples).
        feed(90, sample -> (1 + sample % 3) * MB, sample -> sample % 3 == 0, sample -> sample % 15 == 0);

        assertThat(suspect("com.acme.Leak")).isEmpty();
    }

    @Test
    void untrackedSmallClassesAreSkipped() {
        LeakRadar strict = new LeakRadar(new LeakRadar.Settings(200, 100, 64 * 1024, 1000, 10, 60));
        long gc = 0;
        for (int sample = 0; sample < 20; sample++) {
            ClassHistogram histogram = new ClassHistogram(sample * INTERVAL_MS,
                    List.of(new HistogramEntry("com.acme.Tiny", 10 + sample, 160L + sample * 16, null, 1)), 0, 0);
            strict.ingest(histogram, new LeakRadar.GcContext(++gc, 0, null), 100 * MB);
        }

        assertThat(strict.trackedClasses()).isZero();
        assertThat(strict.suspects()).isEmpty();
    }

    private void feed(int samples, LongUnaryOperator bytes, java.util.function.LongPredicate gcBefore,
                      java.util.function.LongPredicate oldGcBefore) {
        long collections = 0;
        long oldCollections = 0;
        for (long sample = 0; sample < samples; sample++) {
            if (gcBefore.test(sample)) {
                collections++;
            }
            if (oldGcBefore.test(sample)) {
                oldCollections++;
            }
            long value = bytes.applyAsLong(sample);
            List<HistogramEntry> entries = new ArrayList<>();
            entries.add(new HistogramEntry("com.acme.Leak", value / 64, value, null, 1));
            entries.add(new HistogramEntry("java.lang.String", 1000, 64_000, "java.base@17", 1));
            radar.ingest(new ClassHistogram(sample * INTERVAL_MS, entries, 0, 0),
                    new LeakRadar.GcContext(collections, oldCollections, null), 512 * MB);
        }
    }

    private Optional<ClassGrowthSuspectDto> suspect(String className) {
        return radar.suspects().stream().filter(s -> s.className().equals(className)).findFirst();
    }
}
