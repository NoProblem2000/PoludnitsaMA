package com.poludnitsa.inspector.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poludnitsa.inspector.api.dto.GcRootPathDto;
import com.poludnitsa.inspector.api.dto.GcRootPathStepDto;
import com.poludnitsa.inspector.api.dto.OqlQueryRequestDto;
import com.poludnitsa.inspector.api.dto.OqlResultDto;
import com.poludnitsa.inspector.api.dto.OqlValueDto;
import com.poludnitsa.inspector.api.dto.OqlValueType;
import com.poludnitsa.inspector.api.dto.PathMode;
import com.poludnitsa.inspector.api.dto.ReferenceKind;
import com.poludnitsa.inspector.api.dto.SnapshotStatus;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.discovery.ProcessClassifier;
import com.poludnitsa.inspector.inspection.LeakHeuristicsEngine;
import com.poludnitsa.inspector.it.scenario.ScenarioTarget;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.oql.OqlExecutionService;
import com.poludnitsa.inspector.snapshot.DumpFileCleaner;
import com.poludnitsa.inspector.snapshot.ObjectGraphAnalyzer;
import com.poludnitsa.inspector.snapshot.OpenHeapRegistry;
import com.poludnitsa.inspector.snapshot.SafeHeapDumpService;
import com.poludnitsa.inspector.snapshot.SnapshotStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * A deep snapshot kept open for queries: OQL against the real dump of a child JVM, the sandbox of its script engine,
 * GC root paths with weak / soft references, and closing the dump.
 */
class HeapQueryIntegrationTest {

    private static final String CUSTOMER = ScenarioTarget.class.getName() + "$Customer";

    @TempDir
    static Path workDir;

    private static TargetJvm target;
    private static JmxConnectionManager connections;
    private static JvmDiscoveryService discovery;
    private static DumpFileCleaner cleaner;
    private static SnapshotStore store;
    private static SafeHeapDumpService dumps;
    private static OpenHeapRegistry registry;
    private static OqlExecutionService oql;
    private static String snapshotId;

    @BeforeAll
    static void start() throws Exception {
        InspectorProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "inspector.snapshots.work-dir", workDir.toString(),
                "inspector.inspections.enabled", "false")))
                .bindOrCreate("inspector", InspectorProperties.class);
        connections = new JmxConnectionManager(properties);
        discovery = new JvmDiscoveryService(connections, new ProcessClassifier(), properties);
        cleaner = new DumpFileCleaner(properties);
        store = new SnapshotStore(properties, new ObjectMapper().findAndRegisterModules());
        registry = new OpenHeapRegistry(cleaner, properties);
        dumps = new SafeHeapDumpService(connections, discovery,
                new ObjectGraphAnalyzer(properties, LeakHeuristicsEngine.withDefaultRules(properties)), store, cleaner,
                registry, properties);
        oql = new OqlExecutionService(registry, properties);
        target = TargetJvm.start(ScenarioTarget.class, 256);
        // Safe dump: no Full GC, so the softly held payload is certainly still there.
        snapshotId = awaitReady(dumps.startDeepSnapshot(target.pid(), false, true)).snapshotId();
    }

    @AfterAll
    static void stop() {
        registry.destroy();
        oql.destroy();
        dumps.destroy();
        discovery.destroy();
        connections.destroy();
        cleaner.destroy();
        if (target != null) {
            target.close();
        }
    }

    @Test
    void keptDumpStaysOnDiskAndIsMarkedOpen() {
        SnapshotSummaryDto summary = store.get(snapshotId).toSummary();
        assertThat(summary.heapOpen()).isTrue();
        assertThat(summary.heapExpiresAt()).isAfter(summary.createdAt());
        assertThat(summary.rawDumpDeleted()).isFalse();
        assertThat(Files.isRegularFile(cleaner.workRoot().resolve(snapshotId).resolve("heap.hprof"))).isTrue();
    }

    @Test
    void selectsInstancesWithLimitAndScriptObjectsAsColumns() {
        OqlResultDto instances = oql.execute(snapshotId,
                new OqlQueryRequestDto("select c from " + CUSTOMER + " c", 5, null));
        assertThat(instances.columns()).containsExactly("value");
        assertThat(instances.rowCount()).isEqualTo(5);
        assertThat(instances.truncated()).isTrue();
        OqlValueDto first = instances.rows().get(0).get(0);
        assertThat(first.type()).isEqualTo(OqlValueType.INSTANCE);
        assertThat(first.className()).isEqualTo(CUSTOMER);
        assertThat(first.objectId()).isPositive();
        assertThat(first.retainedSize()).isPositive();

        OqlResultDto objects = oql.execute(snapshotId, new OqlQueryRequestDto(
                "select { id: objectid(c), tags: c.tags, size: c.tags.size } from " + CUSTOMER + " c", 3, null));
        assertThat(objects.columns()).containsExactly("id", "tags", "size");
        assertThat(objects.rows()).hasSize(3);
        // hat.js objectid() returns the id as a string
        assertThat(objects.rows().get(0).get(0).type()).isIn(OqlValueType.STRING, OqlValueType.NUMBER);
        assertThat(objects.rows().get(0).get(1).type()).isEqualTo(OqlValueType.INSTANCE);
        assertThat(objects.rows().get(0).get(1).className()).isEqualTo("java.util.ArrayList");
        assertThat(objects.rows().get(0).get(2).text()).isEqualTo("0");

        OqlResultDto strings = oql.execute(snapshotId, new OqlQueryRequestDto(
                "select s from java.lang.String s where s.toString() == 'ORDER_STATUS_PENDING'", 2, null));
        assertThat(strings.rows()).isNotEmpty();
        assertThat(strings.rows().get(0).get(0).text()).isEqualTo("\"ORDER_STATUS_PENDING\"");
    }

    @Test
    void scriptsCannotReachTheJavaPlatform() {
        for (String query : new String[] {
                "select Java.type('java.lang.Runtime').getRuntime()",
                "select java.lang.System.getProperty('user.home')",
                "select load({ script: \"Java.type('java.lang.ProcessBuilder')\", name: 'x' })"}) {
            assertThatThrownBy(() -> oql.execute(snapshotId, new OqlQueryRequestDto(query, 1, null)))
                    .as(query)
                    .isInstanceOf(OperationRejectedException.class)
                    .hasMessageContaining("not accessible");
        }
        // A filtered class looks like an empty package: harmless, and unusable.
        assertThatThrownBy(() -> oql.execute(snapshotId,
                new OqlQueryRequestDto("select new Packages.java.io.File('x').exists()", 1, null)))
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo("OQL_ERROR"));
        OqlResultDto pkg = oql.execute(snapshotId, new OqlQueryRequestDto("select String(Packages.java.io.File)", 1,
                null));
        assertThat(pkg.rows().get(0).get(0).text()).startsWith("[JavaPackage");
        OqlResultDto engine = oql.execute(snapshotId, new OqlQueryRequestDto(
                "select typeof engine + '/' + typeof this['eng' + 'ine'] + '/' + typeof context", 1, null));
        assertThat(engine.rows().get(0).get(0).text()).isEqualTo("undefined/undefined/undefined");
    }

    @Test
    void syntaxErrorsAreReportedAsBadRequests() {
        assertThatThrownBy(() -> oql.execute(snapshotId, new OqlQueryRequestDto("select x from no.such.Clazz x", 1,
                null)))
                .isInstanceOfSatisfying(OperationRejectedException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("OQL_ERROR");
                })
                .hasMessageContaining("no.such.Clazz");
    }

    @Test
    void softlyHeldPayloadHasNoStrongPath() {
        long payload = numberOf(oql.execute(snapshotId, new OqlQueryRequestDto(
                "select objectid(a) from byte[] a where a.length == " + ScenarioTarget.SoftCache.PAYLOAD_BYTES, 1,
                null)));

        GcRootPathDto strong = oql.path(snapshotId, payload, PathMode.STRONG_ONLY);
        assertThat(strong.strongPathExists()).isFalse();
        assertThat(strong.steps()).extracting(GcRootPathStepDto::referenceKind).contains(ReferenceKind.SOFT);
        assertThat(strong.text()).contains("referent (soft)").contains("softly reachable only");
        assertThat(strong.steps().get(0).gcRoot()).isTrue();

        GcRootPathDto all = oql.path(snapshotId, payload, PathMode.ALL_REFERENCES);
        assertThat(all.strongPathExists()).isFalse();
        assertThat(all.steps()).extracting(GcRootPathStepDto::referenceKind).contains(ReferenceKind.SOFT);
    }

    @Test
    void customerHasAStrongPathInBothModes() {
        long customer = numberOf(oql.execute(snapshotId,
                new OqlQueryRequestDto("select objectid(c) from " + CUSTOMER + " c", 1, null)));

        GcRootPathDto strong = oql.path(snapshotId, customer, PathMode.STRONG_ONLY);
        assertThat(strong.strongPathExists()).isTrue();
        assertThat(strong.text()).contains("static ALL in " + ScenarioTarget.class.getName() + "$CustomerRegistry");
        assertThat(strong.steps()).extracting(GcRootPathStepDto::referenceKind)
                .allMatch(kind -> kind == null || kind == ReferenceKind.STRONG);

        GcRootPathDto all = oql.path(snapshotId, customer, PathMode.ALL_REFERENCES);
        assertThat(all.strongPathExists()).isTrue();
        assertThat(all.steps().size()).isLessThanOrEqualTo(strong.steps().size());
    }

    @Test
    void closingDeletesTheDump() throws Exception {
        // a second, short-lived open dump, so the shared one stays available to the other tests
        String second = awaitReady(dumps.startDeepSnapshot(target.pid(), false, true)).snapshotId();
        // max-open-heaps is 1: the first dump was closed to make room
        assertThat(store.get(snapshotId).heapOpen()).isFalse();
        assertThat(registry.close(second, "test")).isTrue();
        SnapshotSummaryDto summary = store.get(second).toSummary();
        assertThat(summary.heapOpen()).isFalse();
        assertThat(summary.rawDumpDeleted()).isTrue();
        assertThat(Files.exists(cleaner.workRoot().resolve(second))).isFalse();
        assertThatThrownBy(() -> oql.execute(second, new OqlQueryRequestDto("select heap", 1, null)))
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo("HEAP_NOT_OPEN"));
        // reopen the shared snapshot for tests that may run after this one
        snapshotId = awaitReady(dumps.startDeepSnapshot(target.pid(), false, true)).snapshotId();
    }

    private static long numberOf(OqlResultDto result) {
        assertThat(result.rows()).isNotEmpty();
        OqlValueDto cell = result.rows().get(0).get(0);
        assertThat(cell.type()).isIn(OqlValueType.STRING, OqlValueType.NUMBER);
        return Long.parseLong(cell.text());
    }

    private static SnapshotSummaryDto awaitReady(SnapshotSummaryDto started) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (System.nanoTime() < deadline) {
            SnapshotSummaryDto current = store.get(started.snapshotId()).toSummary();
            if (current.status() == SnapshotStatus.READY) {
                return current;
            }
            if (current.status() == SnapshotStatus.FAILED) {
                throw new AssertionError("Snapshot failed: " + current.errorMessage());
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Snapshot not ready in time");
    }
}
