package com.poludnitsa.inspector.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.poludnitsa.inspector.api.dto.GcAnalysisDto;
import com.poludnitsa.inspector.api.dto.GcAnalysisSummaryDto;
import com.poludnitsa.inspector.api.dto.GcLiveRequestDto;
import com.poludnitsa.inspector.api.dto.JobStatus;
import com.poludnitsa.inspector.api.dto.JvmThreadState;
import com.poludnitsa.inspector.api.dto.LockKind;
import com.poludnitsa.inspector.api.dto.MonitorContentionDto;
import com.poludnitsa.inspector.api.dto.StuckKind;
import com.poludnitsa.inspector.api.dto.StuckThreadDto;
import com.poludnitsa.inspector.api.dto.ThreadLiveDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesAnalysisDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesRequestDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesSummaryDto;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.discovery.ProcessClassifier;
import com.poludnitsa.inspector.gc.GcLogParserAndAnalyzerService;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.jmx.MBeanNames;
import com.poludnitsa.inspector.report.HtmlReportRenderer;
import com.poludnitsa.inspector.report.ReportExportImportService;
import com.poludnitsa.inspector.report.SessionReportBundle;
import com.poludnitsa.inspector.threads.ThreadContentionAndSeriesService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Threads &amp; GC against a real child JVM, over JMX only: the deadlock, the contended monitor, the endless loop of a
 * request thread, a series of dumps, a live GC recording through the target's FlightRecorderMXBean and its own
 * {@code -Xlog:gc*,safepoint*} file.
 */
class ThreadsAndGcIntegrationTest {

    @TempDir
    static Path workDir;

    private static Path gcLog;
    private static TargetJvm target;
    private static JmxConnectionManager connections;
    private static JvmDiscoveryService discovery;
    private static ThreadContentionAndSeriesService threads;
    private static GcLogParserAndAnalyzerService gc;

    @BeforeAll
    static void start() throws Exception {
        InspectorProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "inspector.gc-log.work-dir", workDir.resolve("gc").toString(),
                "inspector.threads.sample-interval", "200ms",
                "inspector.threads.deadlock-check-interval", "400ms")))
                .bindOrCreate("inspector", InspectorProperties.class);
        connections = new JmxConnectionManager(properties);
        discovery = new JvmDiscoveryService(connections, new ProcessClassifier(), properties);
        threads = new ThreadContentionAndSeriesService(connections, discovery, properties);
        gc = new GcLogParserAndAnalyzerService(connections, discovery, properties);
        gcLog = workDir.resolve("target-gc.log");
        target = TargetJvm.start(ThreadsTarget.class, 96,
                List.of("-Xlog:gc*,safepoint*:file=" + gcLog.toString().replace('\\', '/') + ":time,uptime,level,tags"));
    }

    @AfterAll
    static void stop() {
        threads.destroy();
        gc.destroy();
        discovery.destroy();
        connections.destroy();
        if (target != null) {
            target.close();
        }
    }

    @Test
    void liveContentionAndDeadlockThroughThreadMxBean() throws Exception {
        assertThat(flags().get("ThreadContentionMonitoringEnabled")).isFalse();
        threads.live(target.pid());
        ThreadLiveDto live = await(() -> {
            ThreadLiveDto current = threads.live(target.pid());
            boolean ready = !current.deadlocks().isEmpty() && current.contention().stream()
                    .anyMatch(c -> c.lockClass().endsWith("Inventory") && c.blockedTimeMs() > 0);
            return ready && current.samples() >= 8 ? current : null;
        });

        assertThat(live.contentionMonitoring()).isTrue();
        assertThat(live.cpuTimeMonitoring()).isTrue();
        assertThat(flags().get("ThreadContentionMonitoringEnabled")).isTrue();
        assertThat(live.states().get(JvmThreadState.BLOCKED)).isGreaterThanOrEqualTo(2);
        assertThat(live.deadlocks()).hasSize(1);
        assertThat(live.deadlocks().get(0).kind()).isEqualTo(LockKind.MONITOR);
        assertThat(live.deadlocks().get(0).cycle()).extracting(n -> n.threadName())
                .containsExactlyInAnyOrder("deadlock-A", "deadlock-B");
        assertThat(live.deadlocks().get(0).cycle()).allSatisfy(n -> {
            assertThat(n.waitingForLockClass()).endsWith("Account");
            assertThat(n.stack().get(0).methodName()).isEqualTo("transfer");
        });
        MonitorContentionDto inventory = live.contention().stream()
                .filter(c -> c.lockClass().endsWith("Inventory")).findFirst().orElseThrow();
        assertThat(inventory.timeMeasured()).isTrue();
        assertThat(inventory.lockKind()).isEqualTo(LockKind.MONITOR);
        assertThat(inventory.serverThreads()).isPositive();
        assertThat(inventory.blockedAt()).contains("Inventory.reserve");
        assertThat(inventory.topOwners()).anyMatch(o -> o.startsWith("default task-"));
        assertThat(live.threads()).anyMatch(t -> t.name().equals("default task-1") && t.cpuMs() != null);

        threads.resetLive(target.pid());
        assertThat(threads.liveIfRunning(target.pid())).isNull();
        assertThat(flags().get("ThreadContentionMonitoringEnabled")).isFalse();
        assertThatThrownBy(() -> threads.live(connections.selfPid()))
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo("SELF_MONITORING_FORBIDDEN"));
    }

    @Test
    void seriesOfDumpsFindsTheEndlessLoopAndTheDeadlock() throws Exception {
        ThreadSeriesSummaryDto started = threads.startSeries(new ThreadSeriesRequestDto(target.pid(), 4, 500));
        assertThat(started.status()).isEqualTo(JobStatus.RUNNING);
        assertThatThrownBy(() -> threads.startSeries(new ThreadSeriesRequestDto(target.pid(), 3, 500)))
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo("SERIES_IN_PROGRESS"));
        ThreadSeriesSummaryDto done = await(() -> {
            ThreadSeriesSummaryDto s = threads.summary(started.seriesId());
            return s.status() == JobStatus.RUNNING ? null : s;
        });
        assertThat(done.status()).as(done.errorMessage()).isEqualTo(JobStatus.READY);
        ThreadSeriesAnalysisDto analysis = threads.analysis(started.seriesId());

        assertThat(analysis.dumps()).hasSize(4);
        assertThat(done.dumpsTaken()).isEqualTo(4);
        StuckThreadDto loop = analysis.stuck().stream().filter(s -> s.name().equals("default task-1")).findFirst()
                .orElseThrow(() -> new AssertionError("loop not stuck: " + analysis.stuck()));
        assertThat(loop.kind()).isEqualTo(StuckKind.CPU_LOOP);
        assertThat(loop.stack().get(0).className()).endsWith("Spinner");
        assertThat(analysis.stuck()).anyMatch(s -> s.name().startsWith("deadlock-") && s.kind() == StuckKind.LOCK_WAIT);
        assertThat(analysis.deadlocks()).hasSize(1);
        assertThat(analysis.contentionTimed()).isTrue();
        assertThat(analysis.contention()).anyMatch(c -> c.lockClass().endsWith("Inventory"));
        assertThat(analysis.rows()).anyMatch(r -> r.name().equals("default task-1") && r.stuck()
                && r.cells().stream().filter(c -> c != null && c.sameAsPrevious()).count() >= 2);
        assertThat(threads.readyAnalyses(target.pid())).extracting(a -> a.summary().seriesId())
                .contains(started.seriesId());

        assertSurvivesJsonRoundTrip(bundle(List.of(analysis), List.of()));
        String html = new HtmlReportRenderer().render(bundle(List.of(analysis), List.of()));
        assertThat(html).contains("Серия thread dump-ов", "DEADLOCK", "Матрица состояний", "default task-1")
                .doesNotContain("<script");
    }

    @Test
    void liveGcRecordingAndTheTargetsOwnGcLog() throws Exception {
        GcAnalysisSummaryDto started = gc.startLive(new GcLiveRequestDto(target.pid(), 5));
        GcAnalysisSummaryDto done = await(() -> {
            GcAnalysisSummaryDto s = gc.summary(started.analysisId());
            return s.status() == JobStatus.RUNNING ? null : s;
        });
        assertThat(done.status()).as(done.errorMessage()).isEqualTo(JobStatus.READY);
        GcAnalysisDto live = gc.analysis(started.analysisId());
        assertThat(live.summary().collector()).isEqualTo("G1");
        assertThat(live.summary().jvmVersion()).isNotBlank();
        assertThat(live.kpi().pauseCount()).isPositive();
        assertThat(live.kpi().throughputPercent()).isBetween(50.0, 100.0);
        // 5 s recording; a slow CI runner stops it late
        assertThat(live.summary().coveredMs()).isBetween(3_000.0, 20_000.0);
        assertThat(live.pauses()).allSatisfy(p -> assertThat(p.name()).startsWith("Pause"));
        assertThat(live.pauses()).anyMatch(p -> p.heapBeforeMb() != null && p.heapAfterMb() != null);
        assertThat(live.kpi().allocationRateMbPerSec()).isPositive();
        assertThat(live.safepoints().count()).isPositive();
        assertThat(live.causes()).isNotEmpty();
        try (var files = Files.list(workDir.resolve("gc"))) {
            assertThat(files.filter(p -> p.toString().endsWith(".jfr"))).isEmpty();
        }

        GcAnalysisSummaryDto uploaded;
        try (InputStream in = new ByteArrayInputStream(Files.readAllBytes(gcLog))) {
            uploaded = gc.upload("target-gc.log", in);
        }
        GcAnalysisDto file = gc.analysis(uploaded.analysisId());
        assertThat(file.summary().collector()).isEqualTo("G1");
        assertThat(file.kpi().pauseCount()).isPositive();
        assertThat(file.safepoints().count()).isPositive();
        assertThat(file.unparsedLines()).isZero();
        assertThat(gc.readyAnalyses(target.pid())).hasSizeGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> gc.upload("x.txt", new ByteArrayInputStream("no gc here".getBytes())))
                .isInstanceOfSatisfying(OperationRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo("GC_LOG_INVALID"));

        assertSurvivesJsonRoundTrip(bundle(List.of(), List.of(live, file)));
        String html = new HtmlReportRenderer().render(bundle(List.of(), List.of(live, file)));
        assertThat(html).contains("GC &amp; Safepoints", "Throughput", "Самые медленные safepoint-ы", "<circle");
    }

    // ---------------------------------------------------------------- helpers

    private static SessionReportBundle bundle(List<ThreadSeriesAnalysisDto> series, List<GcAnalysisDto> gcs) {
        return new SessionReportBundle(2, "rep-test", "Тест", Instant.now(), "test", "host", null, List.of(),
                List.of(), List.of(), List.of(), List.of(), series, null, gcs, List.of());
    }

    /** A v2 report written to .json.gz and read back (as the UI opens it offline) loses no diagnostics field. */
    private static void assertSurvivesJsonRoundTrip(SessionReportBundle bundle) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        ReportExportImportService reports = new ReportExportImportService(null, null, null, null, mapper);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reports.writeJson(bundle, out);

        SessionReportBundle read = reports.readBundle(new ByteArrayInputStream(out.toByteArray()));

        assertThat((Object) mapper.valueToTree(read)).isEqualTo(mapper.valueToTree(bundle));
        assertThat(read.threadSeries()).hasSameSizeAs(bundle.threadSeries());
        assertThat(read.gcAnalyses()).hasSameSizeAs(bundle.gcAnalyses());
    }

    private static Map<String, Boolean> flags() {
        return connections.call(target.pid(), (connection, metadata) -> Map.of("ThreadContentionMonitoringEnabled",
                (Boolean) connection.getAttribute(MBeanNames.THREADING, "ThreadContentionMonitoringEnabled")));
    }

    private static <T> T await(Supplier<T> check) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            T value = check.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("timed out");
    }
}
