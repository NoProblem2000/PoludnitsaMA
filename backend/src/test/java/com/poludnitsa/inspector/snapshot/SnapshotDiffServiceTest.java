package com.poludnitsa.inspector.snapshot;

import static com.poludnitsa.inspector.snapshot.SnapshotTestData.ready;
import static com.poludnitsa.inspector.snapshot.SnapshotTestData.stats;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.poludnitsa.inspector.api.dto.ClassDiffDto;
import com.poludnitsa.inspector.api.dto.ProcessType;
import com.poludnitsa.inspector.api.dto.SnapshotDiffDto;
import com.poludnitsa.inspector.api.dto.SnapshotKind;
import com.poludnitsa.inspector.common.OperationRejectedException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotDiffServiceTest {

    private static final long MB = 1 << 20;

    @TempDir
    Path workDir;

    private SnapshotStore store;
    private SnapshotDiffService diffs;

    @BeforeEach
    void setUp() {
        store = new SnapshotStore(SnapshotTestData.properties(workDir), SnapshotTestData.mapper());
        diffs = new SnapshotDiffService(store);
    }

    @Test
    void ranksSurvivingGrowthFirstAndUsesRetainedForDeepPairs() {
        SnapshotRecord before = ready(store, SnapshotKind.DEEP, true, List.of(
                stats("com.acme.OrderDto", 1_000, 32_000L, 2 * MB),
                stats("java.lang.String", 50_000, 1_200_000L, 4 * MB),
                stats("com.acme.Stable", 10, 320L, 320L)));
        SnapshotRecord after = ready(store, SnapshotKind.DEEP, true, List.of(
                stats("com.acme.OrderDto", 21_000, 672_000L, 42 * MB),
                stats("java.lang.String", 50_500, 1_212_000L, 4 * MB + 12_000),
                stats("com.acme.Stable", 10, 320L, 320L)));

        SnapshotDiffDto diff = diffs.diff(before.id(), after.id(), 10, false);

        assertThat(diff.retainedDeltaAvailable()).isTrue();
        assertThat(diff.samePid()).isTrue();
        assertThat(diff.chronological()).isTrue();
        assertThat(diff.classes()).extracting(ClassDiffDto::className)
                .containsExactly("com.acme.OrderDto", "java.lang.String");
        ClassDiffDto order = diff.classes().get(0);
        assertThat(order.deltaInstances()).isEqualTo(20_000);
        assertThat(order.deltaShallowBytes()).isEqualTo(640_000);
        assertThat(order.deltaRetainedBytes()).isEqualTo(40 * MB);
        assertThat(order.survivalScore()).isGreaterThanOrEqualTo(95);
        assertThat(diff.classes().get(1).survivalScore()).isLessThan(order.survivalScore());
        assertThat(diff.deltaTotalInstances()).isEqualTo(20_500);
        assertThat(diff.growingClasses()).isEqualTo(2);
    }

    @Test
    void histogramPairsHaveNoRetainedDeltaAndLowerConfidence() {
        SnapshotRecord before = ready(store, SnapshotKind.LIVE_HISTOGRAM, false,
                List.of(stats("com.acme.OrderDto", 1_000, 32_000L, null)));
        SnapshotRecord after = ready(store, SnapshotKind.LIVE_HISTOGRAM, false,
                List.of(stats("com.acme.OrderDto", 101_000, 3_232_000L, null)));

        SnapshotDiffDto diff = diffs.diff(before.id(), after.id(), 10, false);

        assertThat(diff.retainedDeltaAvailable()).isFalse();
        ClassDiffDto order = diff.classes().get(0);
        assertThat(order.deltaRetainedBytes()).isNull();
        assertThat(order.survivalScore()).isEqualTo(70);
    }

    @Test
    void newClassesAreFlaggedAndShrinkingOnlyOnRequest() {
        SnapshotRecord before = ready(store, SnapshotKind.DEEP, false,
                List.of(stats("com.acme.Session", 500, 64_000L, 3 * MB)));
        SnapshotRecord after = ready(store, SnapshotKind.DEEP, false,
                List.of(stats("com.acme.Session", 100, 12_800L, MB), stats("com.acme.Leak", 4_000, 2 * MB, 2 * MB)));

        SnapshotDiffDto growingOnly = diffs.diff(before.id(), after.id(), 10, false);
        SnapshotDiffDto all = diffs.diff(before.id(), after.id(), 10, true);

        assertThat(growingOnly.classes()).extracting(ClassDiffDto::className).containsExactly("com.acme.Leak");
        assertThat(growingOnly.classes().get(0).newClass()).isTrue();
        assertThat(growingOnly.newClasses()).isEqualTo(1);
        assertThat(all.classes()).extracting(ClassDiffDto::className).containsExactly("com.acme.Leak",
                "com.acme.Session");
        assertThat(all.classes().get(1).survivalScore()).isZero();
    }

    @Test
    void rejectsSameOrUnfinishedSnapshots() {
        SnapshotRecord ready = ready(store, SnapshotKind.LIVE_HISTOGRAM, false, List.of(stats("a.B", 1, 16L, null)));
        SnapshotRecord running = store.create(SnapshotKind.DEEP, 4711, "orders", ProcessType.WILDFLY, false);

        assertThatThrownBy(() -> diffs.diff(ready.id(), ready.id(), 10, false))
                .isInstanceOf(OperationRejectedException.class).hasMessageContaining("two different");
        assertThatThrownBy(() -> diffs.diff(ready.id(), running.id(), 10, false))
                .isInstanceOf(OperationRejectedException.class).hasMessageContaining("DUMPING");
    }

    @Test
    void scoreComponents() {
        assertThat(SnapshotDiffService.survivalScore(100, 0, 1000, 0, false, MB, 1.0)).isZero();
        assertThat(SnapshotDiffService.survivalScore(0, 10, 0, 10 * MB, true, MB, 1.0)).isEqualTo(100);
        assertThat(SnapshotDiffService.survivalScore(0, 10, 0, 10 * MB, true, MB, 0.7)).isEqualTo(70);
    }
}
