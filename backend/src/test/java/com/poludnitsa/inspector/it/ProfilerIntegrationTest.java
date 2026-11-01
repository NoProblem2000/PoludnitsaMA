package com.poludnitsa.inspector.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.poludnitsa.inspector.api.dto.ButterflyDto;
import com.poludnitsa.inspector.api.dto.FlameGraphDto;
import com.poludnitsa.inspector.api.dto.FlameNodeDto;
import com.poludnitsa.inspector.api.dto.HotspotDto;
import com.poludnitsa.inspector.api.dto.ProfileMetric;
import com.poludnitsa.inspector.api.dto.ProfileRequestDto;
import com.poludnitsa.inspector.api.dto.ProfileSource;
import com.poludnitsa.inspector.api.dto.ProfileStatus;
import com.poludnitsa.inspector.api.dto.ProfileSummaryDto;
import com.poludnitsa.inspector.api.dto.Subsystem;
import com.poludnitsa.inspector.api.dto.SubsystemBreakdownDto;
import com.poludnitsa.inspector.api.dto.SubsystemShareDto;
import com.poludnitsa.inspector.api.dto.ThreadLaneDto;
import com.poludnitsa.inspector.api.dto.ThreadState;
import com.poludnitsa.inspector.api.dto.TimelineDto;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.discovery.ProcessClassifier;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.profiler.CpuAndTimelineProfilerService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** A JFR profile of a child JVM with a known shape: CPU in business code, a request waiting in JDBC, a monitor. */
class ProfilerIntegrationTest {

    private static final String TARGET = CpuTarget.class.getName();

    @TempDir
    static Path workDir;

    private static TargetJvm target;
    private static JmxConnectionManager connections;
    private static JvmDiscoveryService discovery;
    private static CpuAndTimelineProfilerService profiler;
    private static ProfileSummaryDto profile;

    @BeforeAll
    static void record() throws Exception {
        InspectorProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "inspector.profiler.work-dir", workDir.toString())))
                .bindOrCreate("inspector", InspectorProperties.class);
        connections = new JmxConnectionManager(properties);
        discovery = new JvmDiscoveryService(connections, new ProcessClassifier(), properties);
        profiler = new CpuAndTimelineProfilerService(connections, discovery, properties);
        target = TargetJvm.start(CpuTarget.class, 128);
        Thread.sleep(1_000);
        ProfileSummaryDto started = profiler.start(new ProfileRequestDto(target.pid(), ProfileSource.JFR, null, 6,
                true));
        assertThat(started.status()).isEqualTo(ProfileStatus.RECORDING);
        assertThatThrownBy(() -> profiler.start(new ProfileRequestDto(target.pid(), null, null, 5, null)))
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo("PROFILE_IN_PROGRESS"));
        profile = await(started.profileId());
    }

    @AfterAll
    static void stop() {
        profiler.destroy();
        discovery.destroy();
        connections.destroy();
        if (target != null) {
            target.close();
        }
    }

    @Test
    void profileHasAllMetricsAndTheRecordingFileIsGone() throws Exception {
        assertThat(profile.metrics()).contains(ProfileMetric.CPU, ProfileMetric.WALL, ProfileMetric.ALLOC);
        assertThat(profile.sampleCount()).isPositive();
        assertThat(profile.eventCount()).isPositive();
        assertThat(profile.recordedMs()).isBetween(3_000L, 9_000L);
        try (Stream<Path> files = Files.list(workDir)) {
            assertThat(files.filter(p -> p.toString().endsWith(".jfr"))).isEmpty();
        }
    }

    @Test
    void cpuHotspotIsTheBusinessLoop() {
        // both busy threads run business code (Ledger spins while it holds the monitor)
        HotspotDto top = profiler.hotspots(profile.profileId(), ProfileMetric.CPU, null, null, null, 20).get(0);
        assertThat(top.subsystem()).isEqualTo(Subsystem.BUSINESS);
        HotspotDto billing = profiler.hotspots(profile.profileId(), ProfileMetric.CPU, null, null, null, 50).stream()
                .filter(h -> h.className().equals(TARGET + "$Billing")).findFirst().orElseThrow();
        assertThat(billing.totalPercent()).isGreaterThan(10);
        assertThat(billing.subsystem()).isEqualTo(Subsystem.BUSINESS);

        FlameGraphDto flame = profiler.flame(profile.profileId(), ProfileMetric.CPU, null, null, null);
        assertThat(flame.unit()).isEqualTo("us");
        assertThat(flame.root().percent()).isEqualTo(100.0);
        assertThat(find(flame.root(), TARGET + "$Billing", "calculate")).isNotNull();
    }

    @Test
    void wallClockSplitsJdbcLocksAndBusiness() {
        SubsystemBreakdownDto wall = profiler.subsystems(profile.profileId(), ProfileMetric.WALL, null, null, null);
        assertThat(wall.subsystems()).extracting(SubsystemShareDto::subsystem)
                .contains(Subsystem.JDBC, Subsystem.LOCKS, Subsystem.BUSINESS);
        SubsystemShareDto jdbc = wall.subsystems().stream().filter(s -> s.subsystem() == Subsystem.JDBC).findFirst()
                .orElseThrow();
        // the request thread sleeps 25 ms per query inside the driver: most of its time is JDBC wait
        assertThat(jdbc.value()).isGreaterThan(1_000_000);
        assertThat(jdbc.topMethods()).isNotEmpty();
        assertThat(wall.traced()).anyMatch(t -> t.type().equals("jdk.JavaMonitorEnter") && t.count() > 0)
                .anyMatch(t -> t.type().equals("jdk.ThreadSleep") && t.count() > 0);
        assertThat(wall.traced()).filteredOn(t -> t.type().equals("jdk.JavaMonitorEnter"))
                .allMatch(t -> Objects.equals(t.topDetail(), "java.lang.Object"));
    }

    @Test
    void butterflyShowsHibernateAsTheCallerOfTheQuery() {
        ButterflyDto butterfly = profiler.butterfly(profile.profileId(), "org.postgresql.jdbc.PgPreparedStatement",
                "executeQuery", ProfileMetric.WALL, null, null, null);
        assertThat(butterfly.totalValue()).isPositive();
        assertThat(butterfly.callers().children()).isNotEmpty();
        assertThat(find(butterfly.callers(), "org.hibernate.event.internal.DefaultFlushEventListener", "onFlush"))
                .isNotNull();
        assertThat(find(butterfly.callers(), "io.undertow.server.Connectors", "executeRootHandler")).isNotNull();
        assertThat(butterfly.callees().children()).isNotEmpty();
    }

    @Test
    void timelineShowsBlockedAndRunnableThreads() {
        TimelineDto timeline = profiler.timeline(profile.profileId(), 60);
        assertThat(timeline.buckets()).isEqualTo(60);
        assertThat(timeline.jvmCpuPercent()).anyMatch(Objects::nonNull);
        assertThat(timeline.lockMs().stream().mapToDouble(Double::doubleValue).sum()).isPositive();
        assertThat(timeline.threads()).anySatisfy(lane -> {
            assertThat(lane.name()).isEqualTo("cpu-burner");
            assertThat(lane.states()).contains(ThreadState.RUNNABLE);
        });
        assertThat(timeline.threads()).filteredOn(lane -> lane.name().startsWith("ledger-"))
                .anyMatch(lane -> lane.states().contains(ThreadState.BLOCKED));
        ThreadLaneDto request = timeline.threads().stream().filter(l -> l.name().equals("default task-1"))
                .findFirst().orElseThrow();
        assertThat(request.waitingMs()).isPositive();
    }

    @Test
    void windowAndThreadFiltersNarrowTheCallTree() {
        FlameGraphDto all = profiler.flame(profile.profileId(), ProfileMetric.CPU, null, null, null);
        FlameGraphDto half = profiler.flame(profile.profileId(), ProfileMetric.CPU, 0.0, profile.recordedMs() / 2.0, null);
        assertThat(half.totalValue()).isPositive().isLessThan(all.totalValue());

        TimelineDto timeline = profiler.timeline(profile.profileId(), 30);
        int burner = timeline.threads().stream().filter(l -> l.name().equals("cpu-burner")).findFirst().orElseThrow()
                .threadIndex();
        FlameGraphDto onlyBurner = profiler.flame(profile.profileId(), ProfileMetric.CPU, null, null, burner);
        assertThat(onlyBurner.threadName()).isEqualTo("cpu-burner");
        assertThat(find(onlyBurner.root(), "org.postgresql.jdbc.PgPreparedStatement", "executeQuery")).isNull();
    }

    private static FlameNodeDto find(FlameNodeDto node, String className, String method) {
        if (node.className().equals(className) && node.methodName().equals(method)) {
            return node;
        }
        for (FlameNodeDto child : node.children()) {
            FlameNodeDto found = find(child, className, method);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static ProfileSummaryDto await(String id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            ProfileSummaryDto current = profiler.get(id);
            if (current.status() == ProfileStatus.READY) {
                return current;
            }
            if (current.status() == ProfileStatus.FAILED) {
                throw new AssertionError("Profile failed: " + current.errorMessage());
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Profile not ready in time");
    }
}
