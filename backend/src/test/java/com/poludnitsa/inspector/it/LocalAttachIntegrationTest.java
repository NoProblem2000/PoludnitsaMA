package com.poludnitsa.inspector.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.poludnitsa.inspector.api.dto.ClassGrowthSuspectDto;
import com.poludnitsa.inspector.api.dto.ClassHistogramDto;
import com.poludnitsa.inspector.api.dto.ConnectionState;
import com.poludnitsa.inspector.api.dto.GcCollectorMetricsDto;
import com.poludnitsa.inspector.api.dto.GcTriggerResultDto;
import com.poludnitsa.inspector.api.dto.JfrAllocationStatsDto;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.LiveMetricsPayloadDto;
import com.poludnitsa.inspector.api.dto.MemoryPoolDto;
import com.poludnitsa.inspector.api.dto.MemoryPoolKind;
import com.poludnitsa.inspector.api.dto.ProcessType;
import com.poludnitsa.inspector.api.dto.TargetStatusDto;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.discovery.ProcessClassifier;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.jmx.MBeanNames;
import com.poludnitsa.inspector.jmx.TargetConnection;
import com.poludnitsa.inspector.live.LiveMemoryCollectorService;
import com.poludnitsa.inspector.live.LiveMetricsListener;
import com.poludnitsa.inspector.live.Subscription;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.management.JMX;
import jdk.management.jfr.FlightRecorderMXBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * End-to-end against a real child JVM: Attach API, {@code startLocalManagementAgent()}, platform MXBeans over the
 * resulting {@code MBeanServerConnection}, {@code GC.class_histogram -all}, the leak radar and remote JFR.
 */
class LocalAttachIntegrationTest {

    private static TargetJvm target;
    private static InspectorProperties properties;
    private static JmxConnectionManager connections;
    private static LiveMemoryCollectorService collector;

    @BeforeAll
    static void startTarget() throws Exception {
        target = TargetJvm.start();
        properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "inspector.live.histogram-interval", "2s",
                "inspector.live.histogram-min-gap", "500ms",
                "inspector.live.track-min-bytes", "1KB",
                "inspector.jfr.poll-interval", "2s")))
                .bindOrCreate("inspector", InspectorProperties.class);
        connections = new JmxConnectionManager(properties);
        collector = new LiveMemoryCollectorService(connections, properties);
    }

    @AfterAll
    static void stopTarget() throws Exception {
        collector.destroy();
        connections.destroy();
        target.close();
    }

    @Test
    void attachStartsLocalAgentAndReadsMetadata() {
        TargetConnection connection = connections.connection(target.pid());

        assertThat(connection.metadata().pid()).isEqualTo(target.pid());
        assertThat(connection.metadata().collectors()).extracting(c -> c.name())
                .contains("G1 Young Generation", "G1 Old Generation");
        assertThat(connection.metadata().pools()).extracting(p -> p.kind())
                .contains(MemoryPoolKind.EDEN, MemoryPoolKind.SURVIVOR, MemoryPoolKind.OLD, MemoryPoolKind.METASPACE);
        assertThat(connection.metadata().diagnosticCommandAvailable()).isTrue();
        assertThat(connection.metadata().flightRecorderAvailable()).isTrue();
        assertThat(connections.state(target.pid())).isEqualTo(ConnectionState.CONNECTED);
    }

    @Test
    void neverAttachesToItself() {
        assertThatThrownBy(() -> connections.connection(ProcessHandle.current().pid()))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("inspector itself");
    }

    @Test
    void discoveryClassifiesTargetAndHidesInspector() {
        JvmDiscoveryService discovery = new JvmDiscoveryService(connections, new ProcessClassifier(), properties);

        List<JvmProcessDto> processes = discovery.listProcesses();

        assertThat(processes).extracting(JvmProcessDto::pid).doesNotContain(ProcessHandle.current().pid());
        JvmProcessDto child = processes.stream().filter(p -> p.pid() == target.pid()).findFirst().orElseThrow();
        assertThat(child.processType()).isEqualTo(ProcessType.STANDARD_JVM);
        assertThat(child.displayName()).isEqualTo("LeakyTarget");
        assertThat(child.attachable()).isTrue();
        assertThat(child.jvmFlags()).contains("-Xmx256m");
        discovery.destroy();
    }

    @Test
    void onDemandHistogramDoesNotForceFullGc() {
        long oldCollectionsBefore = oldCollections();

        ClassHistogramDto histogram = collector.histogram(target.pid(), 200);

        assertThat(histogram.entries()).anySatisfy(entry -> {
            assertThat(entry.className()).isEqualTo(LeakyTarget.LeakedEntry.class.getName());
            assertThat(entry.instances()).isPositive();
        });
        assertThat(histogram.entries()).anySatisfy(entry -> assertThat(entry.className()).isEqualTo("byte[]"));
        assertThat(histogram.totalBytes()).isPositive();
        assertThat(oldCollections()).isEqualTo(oldCollectionsBefore);
    }

    @Test
    void liveSamplesPoolsGcAndFindsTheLeak() {
        List<LiveMetricsPayloadDto> payloads = new CopyOnWriteArrayList<>();
        List<TargetStatusDto> statuses = new CopyOnWriteArrayList<>();
        Subscription subscription = collector.subscribe(target.pid(), new LiveMetricsListener() {
            @Override
            public void onMetrics(LiveMetricsPayloadDto payload) {
                payloads.add(payload);
            }

            @Override
            public void onStatus(TargetStatusDto status) {
                statuses.add(status);
            }
        });
        try {
            String leakClass = LeakyTarget.LeakedEntry.class.getName();
            awaitTrue(Duration.ofSeconds(60), () -> !payloads.isEmpty() && payloads.get(payloads.size() - 1)
                    .topGrowingClasses().stream().anyMatch(s -> s.className().equals(leakClass)));

            LiveMetricsPayloadDto latest = payloads.get(payloads.size() - 1);
            assertThat(statuses).extracting(TargetStatusDto::state).contains(ConnectionState.CONNECTED);
            assertThat(latest.heap().used()).isPositive();
            assertThat(latest.pools()).extracting(MemoryPoolDto::kind)
                    .contains(MemoryPoolKind.EDEN, MemoryPoolKind.OLD, MemoryPoolKind.METASPACE);
            assertThat(latest.gcCollectors()).extracting(GcCollectorMetricsDto::name).contains("G1 Young Generation");
            assertThat(latest.gcCollectors().stream().mapToLong(GcCollectorMetricsDto::collectionCount).sum())
                    .isPositive();
            assertThat(latest.allocationRateBytesPerSec()).isNotNull().isPositive();
            assertThat(latest.histogramTimestampEpochMs()).isNotNull();
            assertThat(latest.histogramIntervalMs()).isNotNull();
            ClassGrowthSuspectDto leak = latest.topGrowingClasses().stream()
                    .filter(s -> s.className().equals(leakClass)).findFirst().orElseThrow();
            assertThat(leak.leakProbabilityScore()).isPositive();
            assertThat(leak.deltaBytesPerMin()).isPositive();
        } finally {
            subscription.close();
        }
    }

    @Test
    void jfrAllocationProfileRunsInsideTargetAndIsClosed() {
        JfrAllocationStatsDto started = collector.startJfr(target.pid());
        assertThat(started.active()).isTrue();
        String recordingName = started.recordingName();
        assertThat(recordingNames()).contains(recordingName);

        awaitTrue(Duration.ofSeconds(45), () -> collector.jfrStatus(target.pid())
                .map(s -> s.allocationRateBytesPerSec() != null && !s.topAllocatingClasses().isEmpty())
                .orElse(false));
        JfrAllocationStatsDto running = collector.jfrStatus(target.pid()).orElseThrow();
        assertThat(running.error()).isNull();
        assertThat(running.allocationRateBytesPerSec()).isPositive();
        assertThat(running.topAllocatingClasses()).extracting(c -> c.className()).contains("byte[]");

        JfrAllocationStatsDto stopped = collector.stopJfr(target.pid());
        assertThat(stopped.active()).isFalse();
        assertThat(recordingNames()).doesNotContain(recordingName);
    }

    @Test
    void explicitGcReportsDuration() {
        GcTriggerResultDto result = collector.triggerGc(target.pid());

        assertThat(result.durationMs()).isNotNegative();
        assertThat(result.explicitGcDisabled()).isFalse();
        assertThat(result.warning()).contains("Full GC");
    }

    private static long oldCollections() {
        return connections.call(target.pid(), (connection, metadata) -> (Long) connection.getAttribute(
                MBeanNames.of("java.lang:type=GarbageCollector,name=G1 Old Generation"), "CollectionCount"));
    }

    private static List<String> recordingNames() {
        return connections.call(target.pid(), (connection, metadata) ->
                JMX.newMXBeanProxy(connection, MBeanNames.FLIGHT_RECORDER, FlightRecorderMXBean.class)
                        .getRecordings().stream().map(r -> r.getName()).toList());
    }

    private static void awaitTrue(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
