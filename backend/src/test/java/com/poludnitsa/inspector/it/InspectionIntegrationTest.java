package com.poludnitsa.inspector.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.HazelcastInstance;
import com.poludnitsa.inspector.api.dto.ClassLoaderKind;
import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.InspectionRuleRunDto;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.api.dto.SnapshotStatus;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.discovery.ProcessClassifier;
import com.poludnitsa.inspector.inspection.LeakHeuristicsEngine;
import com.poludnitsa.inspector.it.scenario.ScenarioTarget;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.snapshot.DumpFileCleaner;
import com.poludnitsa.inspector.snapshot.ObjectGraphAnalyzer;
import com.poludnitsa.inspector.snapshot.OpenHeapRegistry;
import com.poludnitsa.inspector.snapshot.SafeHeapDumpService;
import com.poludnitsa.inspector.snapshot.SnapshotData;
import com.poludnitsa.inspector.snapshot.SnapshotStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** All eleven inspections against real heap dumps of child JVMs that reproduce the leak patterns. */
class InspectionIntegrationTest {

    private static final String SCENARIO = ScenarioTarget.class.getName();

    @TempDir
    static Path workDir;

    private static JmxConnectionManager connections;
    private static JvmDiscoveryService discovery;
    private static DumpFileCleaner cleaner;
    private static SnapshotStore store;
    private static SafeHeapDumpService dumps;

    @BeforeAll
    static void start() {
        InspectorProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "inspector.snapshots.work-dir", workDir.toString())))
                .bindOrCreate("inspector", InspectorProperties.class);
        connections = new JmxConnectionManager(properties);
        discovery = new JvmDiscoveryService(connections, new ProcessClassifier(), properties);
        cleaner = new DumpFileCleaner(properties);
        store = new SnapshotStore(properties, new ObjectMapper().findAndRegisterModules());
        dumps = new SafeHeapDumpService(connections, discovery,
                new ObjectGraphAnalyzer(properties, LeakHeuristicsEngine.withDefaultRules(properties)), store, cleaner,
                new OpenHeapRegistry(cleaner, properties), properties);
    }

    @AfterAll
    static void stop() {
        dumps.destroy();
        discovery.destroy();
        connections.destroy();
        cleaner.destroy();
    }

    @Test
    void wildFlyJakartaAndJvmPatternsAreDiagnosed() throws Exception {
        SnapshotData data;
        try (TargetJvm target = TargetJvm.start(ScenarioTarget.class, 256)) {
            data = deepSnapshot(target.pid());
        }
        assertNoRuleFailed(data);

        InspectionFindingDto classLoader = finding(data, "wildfly.classloader-leak", f -> true);
        assertThat(classLoader.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(classLoader.title()).contains("deployment.orders.war");
        assertThat(classLoader.affectedInstances()).isPositive();
        assertThat(classLoader.evidence()).anyMatch(line -> line.contains("'default task-7'"));
        assertThat(classLoader.gcRootPathText()).contains("contextClassLoader");
        // The deployment's static state counts: DeployedPlugin.STATIC_STATE is 128 KiB.
        assertThat(classLoader.wastedMemoryBytes()).isGreaterThanOrEqualTo(128 * 1024);
        assertThat(data.findings()).filteredOn(f -> f.ruleId().equals("wildfly.classloader-leak")).hasSize(1);

        InspectionFindingDto threadLocal = finding(data, "wildfly.threadlocal-pool-leak", f -> true);
        assertThat(threadLocal.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(threadLocal.suspectClassName()).isEqualTo(SCENARIO + "$RequestContext");
        assertThat(threadLocal.fieldName()).isEqualTo("CURRENT");
        assertThat(threadLocal.affectedInstances()).isEqualTo(4);
        assertThat(threadLocal.wastedMemoryBytes()).isGreaterThanOrEqualTo(4L * 600 * 1024);
        assertThat(threadLocal.evidence()).anyMatch(line -> line.contains("default task-1"));

        InspectionFindingDto hibernate = finding(data, "hibernate.persistence-context-bloat", f -> true);
        assertThat(hibernate.affectedInstances()).isEqualTo(5_000);
        assertThat(hibernate.evidence()).anyMatch(line -> line.contains("$OrderEntity"));

        InspectionFindingDto sessions = finding(data, "undertow.http-session-bloat", f -> true);
        assertThat(sessions.affectedInstances()).isEqualTo(12_000);
        assertThat(sessions.title()).contains("orders.war");
        assertThat(sessions.evidence()).anyMatch(line -> line.contains("'cart'"));

        InspectionFindingDto dependents = finding(data, "cdi.scope-mismatch", f -> f.id().contains("dependent:"));
        assertThat(dependents.affectedInstances()).isEqualTo(5_000);
        assertThat(dependents.title()).contains("ReportService");
        InspectionFindingDto beanCache = finding(data, "cdi.scope-mismatch", f -> f.id().contains("collection:"));
        assertThat(beanCache.fieldName()).isEqualTo("cache");
        assertThat(beanCache.affectedInstances()).isEqualTo(60_000);

        InspectionFindingDto badKeys = finding(data, "jvm.unbounded-collection",
                f -> "PRICES".equals(f.fieldName()));
        assertThat(badKeys.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(badKeys.suspectClassName()).isEqualTo(SCENARIO + "$PriceRegistry");
        assertThat(badKeys.evidence()).anyMatch(line -> line.contains("hashCode() не переопределён"));

        // No false positives: class loader class lists, single-element holders, or generic duplicates of the
        // specific session-store / CDI-cache findings.
        assertThat(data.findings()).filteredOn(f -> f.ruleId().equals("jvm.unbounded-collection"))
                .noneMatch(f -> "classes".equals(f.fieldName()))
                .noneMatch(f -> "delegate".equals(f.fieldName()))
                .noneMatch(f -> "sessions".equals(f.fieldName()))
                .noneMatch(f -> "cache".equals(f.fieldName()));

        InspectionFindingDto strings = finding(data, "jvm.duplicate-strings", f -> f.id().endsWith("#strings"));
        assertThat(strings.evidence()).anyMatch(line -> line.contains("\"ORDER_STATUS_PENDING\"")
                && line.contains("AuditEntry.status"));
        assertThat(strings.wastedMemoryBytes()).isGreaterThan(1L << 20);

        // Eager empty collections, grouped by the owning field: ArrayList(50) and a cleared HashMap(64) per customer.
        InspectionFindingDto emptyTags = finding(data, "jvm.empty-sparse-collections", f -> "tags".equals(f.fieldName()));
        assertThat(emptyTags.suspectClassName()).isEqualTo(SCENARIO + "$Customer");
        assertThat(emptyTags.affectedInstances()).isEqualTo(80_000);
        assertThat(emptyTags.wastedMemoryBytes()).isGreaterThanOrEqualTo(80_000L * 200);
        assertThat(emptyTags.title()).contains("ArrayList");
        InspectionFindingDto emptyAttributes = finding(data, "jvm.empty-sparse-collections",
                f -> "attributes".equals(f.fieldName()));
        assertThat(emptyAttributes.title()).contains("HashMap");
        assertThat(emptyAttributes.affectedInstances()).isEqualTo(80_000);
        // %,d groups digits with a no-break space in the Russian locale
        assertThat(emptyAttributes.evidence()).anyMatch(line -> line.replaceAll("[\\s\\u00a0\\u202f]", "")
                .contains("пустых80000"));

        // Boxed Longs: a field of 80 000 customers and a List<Long> of 100 000 ids.
        InspectionFindingDto boxedField = finding(data, "jvm.boxed-primitives",
                f -> "loyaltyPoints".equals(f.fieldName()));
        assertThat(boxedField.suspectClassName()).isEqualTo(SCENARIO + "$Customer");
        assertThat(boxedField.title()).contains("Long");
        InspectionFindingDto boxedList = finding(data, "jvm.boxed-primitives", f -> "RECENT_IDS".equals(f.fieldName()));
        assertThat(boxedList.affectedInstances()).isEqualTo(100_000);
        assertThat(boxedList.wastedMemoryBytes()).isGreaterThanOrEqualTo(100_000L * 16);

        // Memory per class loader, with the WildFly module name.
        assertThat(data.classLoaders()).anySatisfy(loader -> {
            assertThat(loader.kind()).isEqualTo(ClassLoaderKind.JBOSS_MODULE);
            assertThat(loader.moduleName()).isEqualTo("deployment.orders.war");
            assertThat(loader.retainedBytes()).isNotNull();
        });
        assertThat(data.classLoaders()).anySatisfy(loader -> assertThat(loader.kind())
                .isEqualTo(ClassLoaderKind.APPLICATION));
        assertThat(data.classLoaders()).anySatisfy(loader -> assertThat(loader.kind())
                .isEqualTo(ClassLoaderKind.BOOTSTRAP));

        // Retention paths of a top class: all sampled customers hang on the registry list.
        ClassStatsDto customers = data.classes().stream()
                .filter(c -> c.className().equals(SCENARIO + "$Customer")).findFirst().orElseThrow();
        assertThat(customers.retentionPaths()).isNotEmpty();
        // the real chain: the class path loader lists the registry class, whose static list holds the customers
        assertThat(customers.retentionPaths().get(0).signature())
                .startsWith("ClassLoaders$AppClassLoader.classes")
                .contains("static ALL in ScenarioTarget$CustomerRegistry");
        assertThat(customers.retentionPaths().get(0).share()).isGreaterThan(0.9);

        assertThat(data.findings()).allSatisfy(f -> {
            assertThat(f.whyItHappens()).isNotBlank();
            assertThat(f.howToFixCodeSnippet()).isNotBlank();
        });
    }

    @Test
    void hazelcastClientPatternsAreDiagnosed() throws Exception {
        SnapshotData data;
        try (TargetJvm target = TargetJvm.start(HazelcastTarget.class, 512, HazelcastInstance.class)) {
            data = deepSnapshot(target.pid());
        }
        assertNoRuleFailed(data);

        InspectionFindingDto heapData = finding(data, "hazelcast.serialized-heapdata", f -> f.id().endsWith("#heapdata"));
        assertThat(heapData.evidence())
                .anyMatch(line -> line.contains("Java Serializable " + HazelcastTarget.OrderPayload.class.getName()))
                .anyMatch(line -> line.contains("IdentifiedDataSerializable factoryId=1000 classId=7"))
                .anyMatch(line -> line.contains("NearCache 'orders'"));
        assertThat(heapData.affectedInstances()).isGreaterThan(20_000);
        finding(data, "hazelcast.serialized-heapdata", f -> f.title().contains("NearCache 'orders'"));

        InspectionFindingDto registrations = finding(data, "hazelcast.orphaned-listeners",
                f -> f.id().contains("registrations:"));
        assertThat(registrations.affectedInstances()).isGreaterThanOrEqualTo(150);
        assertThat(registrations.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(registrations.evidence()).anyMatch(line -> line.contains("OrderService$1 × 150"));

        InspectionFindingDto capture = finding(data, "hazelcast.orphaned-listeners", f -> f.id().contains("capture:"));
        assertThat(capture.fieldName()).isEqualTo("this$0");
        assertThat(capture.wastedMemoryBytes()).isGreaterThanOrEqualTo(3L << 20);
    }

    private static SnapshotData deepSnapshot(long pid) throws InterruptedException {
        SnapshotSummaryDto started = dumps.startDeepSnapshot(pid, true);
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (System.nanoTime() < deadline) {
            SnapshotSummaryDto current = store.get(started.snapshotId()).toSummary();
            if (current.status() == SnapshotStatus.READY) {
                return store.get(started.snapshotId()).data();
            }
            if (current.status() == SnapshotStatus.FAILED) {
                throw new AssertionError("Snapshot failed: " + current.errorMessage());
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Snapshot not ready in time");
    }

    private static void assertNoRuleFailed(SnapshotData data) {
        assertThat(data.inspections()).hasSize(11);
        assertThat(data.inspections()).extracting(InspectionRuleRunDto::error).containsOnlyNulls();
    }

    private static InspectionFindingDto finding(SnapshotData data, String ruleId,
                                                Predicate<InspectionFindingDto> filter) {
        List<InspectionFindingDto> matches = data.findings().stream()
                .filter(f -> f.ruleId().equals(ruleId))
                .filter(filter)
                .toList();
        assertThat(matches).as("findings of %s among %s", ruleId,
                data.findings().stream().map(InspectionFindingDto::title).toList()).isNotEmpty();
        return matches.get(0);
    }
}
