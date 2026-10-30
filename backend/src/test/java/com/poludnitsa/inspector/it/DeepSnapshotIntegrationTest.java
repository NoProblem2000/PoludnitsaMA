package com.poludnitsa.inspector.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poludnitsa.inspector.api.dto.ClassDiffDto;
import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.DiskSpaceCheckDto;
import com.poludnitsa.inspector.api.dto.DominatorNodeDto;
import com.poludnitsa.inspector.api.dto.MemoryGroup;
import com.poludnitsa.inspector.api.dto.MemoryGroupStatsDto;
import com.poludnitsa.inspector.api.dto.SnapshotDiffDto;
import com.poludnitsa.inspector.api.dto.SnapshotKind;
import com.poludnitsa.inspector.api.dto.SnapshotStatus;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import com.poludnitsa.inspector.common.InsufficientDiskSpaceException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.discovery.ProcessClassifier;
import com.poludnitsa.inspector.inspection.LeakHeuristicsEngine;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.live.LiveMemoryCollectorService;
import com.poludnitsa.inspector.snapshot.DumpFileCleaner;
import com.poludnitsa.inspector.snapshot.HistogramSnapshotService;
import com.poludnitsa.inspector.snapshot.ObjectGraphAnalyzer;
import com.poludnitsa.inspector.snapshot.SafeHeapDumpService;
import com.poludnitsa.inspector.snapshot.SnapshotData;
import com.poludnitsa.inspector.snapshot.SnapshotDiffService;
import com.poludnitsa.inspector.snapshot.SnapshotStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Real heap dumps of a child JVM: disk guard, NetBeans analysis, GC root paths, cleanup and diffs. */
class DeepSnapshotIntegrationTest {

    private static final String LEAKED_ENTRY = LeakyTarget.LeakedEntry.class.getName();
    private static final String REGISTRY = LeakyTarget.Registry.class.getName();

    @TempDir
    static Path workDir;

    private static TargetJvm target;
    private static InspectorProperties properties;
    private static JmxConnectionManager connections;
    private static JvmDiscoveryService discovery;
    private static LiveMemoryCollectorService collector;
    private static DumpFileCleaner cleaner;
    private static SnapshotStore store;
    private static SafeHeapDumpService dumps;
    private static HistogramSnapshotService histograms;
    private static SnapshotDiffService diffs;

    @BeforeAll
    static void start() throws Exception {
        target = TargetJvm.start();
        properties = properties(Map.of());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        connections = new JmxConnectionManager(properties);
        discovery = new JvmDiscoveryService(connections, new ProcessClassifier(), properties);
        collector = new LiveMemoryCollectorService(connections, properties);
        cleaner = new DumpFileCleaner(properties);
        store = new SnapshotStore(properties, mapper);
        dumps = new SafeHeapDumpService(connections, discovery, new ObjectGraphAnalyzer(properties, LeakHeuristicsEngine.withDefaultRules(properties)), store, cleaner,
                properties);
        histograms = new HistogramSnapshotService(collector, discovery, store);
        diffs = new SnapshotDiffService(store);
        // Let the cache grow to a few MB.
        Thread.sleep(3_000);
    }

    @AfterAll
    static void stop() {
        dumps.destroy();
        collector.destroy();
        discovery.destroy();
        connections.destroy();
        cleaner.destroy();
        target.close();
    }

    @Test
    void deepSnapshotFindsStaticCacheAndDeletesRawDump() throws Exception {
        DiskSpaceCheckDto check = dumps.checkDiskSpace(target.pid());
        assertThat(check.sufficient()).isTrue();
        assertThat(check.requiredBytes()).isEqualTo((long) Math.ceil(2.5 * check.heapUsedBytes()));

        SnapshotSummaryDto summary = awaitReady(dumps.startDeepSnapshot(target.pid(), false));
        SnapshotData data = store.get(summary.snapshotId()).data();

        assertThat(summary.kind()).isEqualTo(SnapshotKind.DEEP);
        assertThat(summary.rawDumpDeleted()).isTrue();
        assertThat(summary.hprofFileSizeBytes()).isPositive();
        assertThat(summary.retainedSizesAvailable()).isTrue();
        assertThat(summary.totalHeapBytes()).isPositive();
        assertThat(summary.gcRootCount()).isPositive();

        ClassStatsDto entries = data.classes().stream().filter(c -> c.className().equals(LEAKED_ENTRY))
                .findFirst().orElseThrow();
        assertThat(entries.instances()).isPositive();
        assertThat(entries.retainedBytes()).isGreaterThanOrEqualTo(entries.shallowBytes());
        assertThat(entries.memoryGroup()).isEqualTo(MemoryGroup.USER_BUSINESS_CODE);
        assertThat(entries.sample()).isNotNull();
        assertThat(entries.sample().gcRootPathText())
                .startsWith("LeakyTarget$LeakedEntry <- HashMap$Node.value <- HashMap$Node[")
                .contains("HashMap.table <- static CACHE in " + REGISTRY)
                .contains("[GC Root: ");
        assertThat(entries.sample().gcRootPath().get(0).gcRoot()).isTrue();

        List<DominatorNodeDto> dominators = data.dominators();
        assertThat(dominators).isNotEmpty().hasSizeLessThanOrEqualTo(200);
        assertThat(dominators).isSortedAccordingTo(
                Comparator.comparingLong(DominatorNodeDto::retainedSize).reversed());
        assertThat(dominators).anySatisfy(d -> {
            assertThat(d.className()).isEqualTo("java.util.HashMap");
            assertThat(d.gcRootPathText()).startsWith("HashMap <- static CACHE in " + REGISTRY);
            assertThat(d.retainedSize()).isGreaterThan(entries.shallowBytes());
        });

        assertThat(data.groups()).extracting(MemoryGroupStatsDto::group).contains(MemoryGroup.USER_BUSINESS_CODE);
        assertThat(data.groups().stream().mapToLong(MemoryGroupStatsDto::shallowBytes).sum())
                .isEqualTo(data.classes().stream().mapToLong(ClassStatsDto::shallowBytes).sum());

        assertWorkDirEmpty();
        assertThat(workDir.resolve("summaries").resolve(summary.snapshotId() + ".json.gz")).exists();
    }

    @Test
    void diffOfTwoLiveOnlyDumpsShowsSurvivingLeak() throws Exception {
        SnapshotSummaryDto first = awaitReady(dumps.startDeepSnapshot(target.pid(), true));
        Thread.sleep(3_000);
        SnapshotSummaryDto second = awaitReady(dumps.startDeepSnapshot(target.pid(), true));

        SnapshotDiffDto diff = diffs.diff(first.snapshotId(), second.snapshotId(), 20, false);

        assertThat(diff.retainedDeltaAvailable()).isTrue();
        assertThat(diff.samePid()).isTrue();
        assertThat(diff.chronological()).isTrue();
        ClassDiffDto leak = diff.classes().stream().filter(c -> c.className().equals(LEAKED_ENTRY))
                .findFirst().orElseThrow();
        assertThat(leak.deltaInstances()).isPositive();
        assertThat(leak.deltaRetainedBytes()).isPositive();
        assertThat(leak.survivalScore()).isPositive();
        assertWorkDirEmpty();
    }

    @Test
    void histogramSnapshotsCanBeCompared() throws Exception {
        SnapshotSummaryDto first = histograms.capture(target.pid());
        Thread.sleep(2_000);
        SnapshotSummaryDto second = histograms.capture(target.pid());

        SnapshotDiffDto diff = diffs.diff(first.snapshotId(), second.snapshotId(), 5000, false);

        assertThat(first.kind()).isEqualTo(SnapshotKind.LIVE_HISTOGRAM);
        assertThat(first.status()).isEqualTo(SnapshotStatus.READY);
        assertThat(diff.retainedDeltaAvailable()).isFalse();
        assertThat(diff.classes()).isSortedAccordingTo(
                Comparator.comparingInt(ClassDiffDto::survivalScore).reversed());
        ClassDiffDto leak = diff.classes().stream().filter(c -> c.className().equals(LEAKED_ENTRY))
                .findFirst().orElseThrow();
        assertThat(leak.deltaInstances()).isPositive();
        assertThat(leak.deltaRetainedBytes()).isNull();
        // Tiny classes that merely appeared (JMX internals of the monitoring itself) rank below the leak.
        assertThat(diff.classes().stream()
                .filter(c -> c.newClass() && c.deltaShallowBytes() < 4096)
                .mapToInt(ClassDiffDto::survivalScore).max().orElse(0))
                .isLessThanOrEqualTo(leak.survivalScore());
    }

    @Test
    void insufficientDiskSpaceIsRejectedBeforeAnyDump() {
        InspectorProperties greedy = properties(Map.of("inspector.snapshots.disk-space-factor", "1e12"));
        SafeHeapDumpService guarded = new SafeHeapDumpService(connections, discovery,
                new ObjectGraphAnalyzer(greedy, LeakHeuristicsEngine.withDefaultRules(greedy)), store, cleaner, greedy);
        int snapshotsBefore = store.list().size();

        assertThatThrownBy(() -> guarded.startDeepSnapshot(target.pid(), false))
                .isInstanceOf(InsufficientDiskSpaceException.class)
                .hasMessageContaining("required").hasMessageContaining("available");
        // The permit was released: the second attempt fails the same way instead of "in progress".
        assertThatThrownBy(() -> guarded.startDeepSnapshot(target.pid(), false))
                .isInstanceOf(InsufficientDiskSpaceException.class);
        assertThat(store.list()).hasSize(snapshotsBefore);
        guarded.destroy();
    }

    @Test
    void secondDeepSnapshotIsRejectedWhileOneRuns() throws Exception {
        SnapshotSummaryDto running = dumps.startDeepSnapshot(target.pid(), false);

        assertThatThrownBy(() -> dumps.startDeepSnapshot(target.pid(), false))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("one deep snapshot runs at a time");
        awaitReady(running);
    }

    private static SnapshotSummaryDto awaitReady(SnapshotSummaryDto started) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            SnapshotSummaryDto current = store.get(started.snapshotId()).toSummary();
            if (current.status() == SnapshotStatus.READY) {
                return current;
            }
            if (current.status() == SnapshotStatus.FAILED) {
                throw new AssertionError("Snapshot failed: " + current.errorMessage());
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Snapshot " + started.snapshotId() + " not ready in time");
    }

    private static void assertWorkDirEmpty() throws Exception {
        try (Stream<Path> leftovers = Files.list(cleaner.workRoot())) {
            assertThat(leftovers).isEmpty();
        }
        assertThat(cleaner.pending()).isEmpty();
    }

    private static InspectorProperties properties(Map<String, String> extra) {
        Map<String, String> values = new java.util.HashMap<>(extra);
        values.put("inspector.snapshots.work-dir", workDir.toString());
        values.put("inspector.live.histogram-min-gap", "500ms");
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("inspector",
                InspectorProperties.class);
    }
}
