package com.poludnitsa.inspector.gc;

import static org.assertj.core.api.Assertions.assertThat;

import com.poludnitsa.inspector.api.dto.DiagnosticSource;
import com.poludnitsa.inspector.api.dto.GcAnalysisDto;
import com.poludnitsa.inspector.api.dto.GcAnalysisSummaryDto;
import com.poludnitsa.inspector.api.dto.GcCauseStatDto;
import com.poludnitsa.inspector.api.dto.GcFindingDto;
import com.poludnitsa.inspector.api.dto.GcPauseCategory;
import com.poludnitsa.inspector.api.dto.GcPauseDto;
import com.poludnitsa.inspector.api.dto.JobStatus;
import com.poludnitsa.inspector.api.dto.Severity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Unified Logging parsing and the GC analysis: real {@code -Xlog:gc*,safepoint*} logs of JDK 21 for every
 * collector, plus JDK 11 / 17 formats (old safepoint lines, single-generation ZGC).
 */
class GcLogParserTest {

    private static final GcLogAnalyzer.Settings SETTINGS = new GcLogAnalyzer.Settings(20_000, 200, 50);

    @Test
    void g1Jdk21RealLog() throws IOException {
        GcLogData data = resource("jdk21-g1.log");
        GcAnalysisDto analysis = analyze(data);

        assertThat(data.collector()).isEqualTo("G1");
        assertThat(analysis.summary().jvmVersion()).startsWith("21.");
        assertThat(analysis.kpi().pauseCount()).isGreaterThan(20);
        assertThat(analysis.kpi().youngCount()).isPositive();
        assertThat(analysis.pauses()).extracting(GcPauseDto::category)
                .contains(GcPauseCategory.YOUNG, GcPauseCategory.HUMONGOUS, GcPauseCategory.REMARK);
        assertThat(analysis.pauses()).allSatisfy(p -> assertThat(p.pauseMs()).isPositive());
        assertThat(analysis.pauses()).filteredOn(p -> p.category() == GcPauseCategory.YOUNG)
                .allSatisfy(p -> assertThat(p.heapBeforeMb()).isNotNull());
        assertThat(analysis.kpi().throughputPercent()).isBetween(1.0, 100.0);
        assertThat(analysis.kpi().allocationRateMbPerSec()).isPositive();
        assertThat(analysis.kpi().promotionRateMbPerSec()).isNotNull();
        assertThat(analysis.kpi().maxPauseMs()).isGreaterThanOrEqualTo(analysis.kpi().avgPauseMs());
        assertThat(analysis.rates()).isNotEmpty();
        assertThat(analysis.concurrentCycles()).isPositive();
        GcCauseStatDto humongous = cause(analysis, "G1 Humongous Allocation");
        assertThat(humongous.severity()).isEqualTo(Severity.WARNING);
        assertThat(humongous.advice()).contains("byte[]", "DTO", "G1HeapRegionSize", "1 MB");
        assertThat(cause(analysis, "G1 Evacuation Pause").severity()).isEqualTo(Severity.INFO);
        assertThat(analysis.safepoints().count()).isPositive();
        assertThat(analysis.safepoints().operations()).extracting(o -> o.operation())
                .contains("G1CollectForAllocation");
        assertThat(analysis.safepoints().slowest()).isNotEmpty()
                .allSatisfy(s -> assertThat(s.totalMs()).isGreaterThanOrEqualTo(s.ttspMs()));
        assertThat(analysis.findings()).extracting(GcFindingDto::code).contains("HUMONGOUS");
        assertThat(analysis.unparsedLines()).isZero();
    }

    @Test
    void parallelJdk21RealLogHasPromotionFromParOldGen() throws IOException {
        GcAnalysisDto analysis = analyze(resource("jdk21-parallel.log"));

        assertThat(analysis.summary().collector()).isEqualTo("Parallel");
        assertThat(cause(analysis, "Allocation Failure").count()).isPositive();
        assertThat(analysis.kpi().promotionRateMbPerSec()).isPositive();
        assertThat(analysis.safepoints().operations()).extracting(o -> o.operation())
                .contains("ParallelGCFailedAllocation");
    }

    @Test
    void zgcJdk21RealLogs() throws IOException {
        GcLogData single = resource("jdk21-zgc.log");
        GcAnalysisDto analysis = analyze(single);
        assertThat(single.collector()).isEqualTo("ZGC");
        assertThat(single.collections()).isNotEmpty();
        assertThat(analysis.pauses()).isNotEmpty()
                .allSatisfy(p -> assertThat(p.category()).isEqualTo(GcPauseCategory.CONCURRENT_PAUSE))
                .allSatisfy(p -> assertThat(p.cause()).isNotNull());
        assertThat(analysis.kpi().allocationRateMbPerSec()).isPositive();
        assertThat(analysis.kpi().maxPauseMs()).isLessThan(5);

        GcLogData generational = resource("jdk21-zgc-generational.log");
        GcAnalysisDto genAnalysis = analyze(generational);
        assertThat(generational.collector()).isEqualTo("ZGC (generational)");
        assertThat(genAnalysis.pauses()).extracting(GcPauseDto::name).contains("Pause Mark Start");
        assertThat(genAnalysis.causes()).extracting(GcCauseStatDto::cause).contains("Warmup");
        assertThat(genAnalysis.causes()).extracting(GcCauseStatDto::cause).doesNotContain("Major", "Minor");
    }

    @Test
    void shenandoahJdk21RealLog() throws IOException {
        GcLogData data = resource("jdk21-shenandoah.log");
        GcAnalysisDto analysis = analyze(data);

        assertThat(data.collector()).isEqualTo("Shenandoah");
        assertThat(data.collections()).isNotEmpty();
        assertThat(analysis.pauses()).extracting(GcPauseDto::name)
                .contains("Pause Init Mark (unload classes)", "Pause Final Mark (unload classes)");
        assertThat(analysis.causes()).extracting(GcCauseStatDto::cause).anyMatch(c -> c.startsWith("Learning"));
        assertThat(analysis.kpi().allocationRateMbPerSec()).isPositive();
    }

    @Test
    void jdk11G1WithOldSafepointFormatToSpaceExhaustedAndExplicitGc() throws IOException {
        String log = """
                [2024-03-01T10:00:00.000+0000][0.010s][info][gc] Using G1
                [2024-03-01T10:00:00.002+0000][0.012s][info][gc,heap] Heap region size: 1M
                [0.500s][info][safepoint] Application time: 0.4800000 seconds
                [0.500s][info][safepoint] Entering safepoint region: G1CollectForAllocation
                [0.505s][info][gc,start] GC(0) Pause Young (Normal) (G1 Evacuation Pause)
                [0.505s][info][gc,heap] GC(0) Eden regions: 24->0(20)
                [0.505s][info][gc,heap] GC(0) Survivor regions: 0->3(3)
                [0.505s][info][gc,heap] GC(0) Old regions: 0->2
                [0.505s][info][gc,heap] GC(0) Humongous regions: 0->0
                [0.505s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->5M(256M) 4.500ms
                [0.505s][info][safepoint] Leaving safepoint region
                [0.505s][info][safepoint] Total time for which application threads were stopped: 0.0050000 seconds, Stopping threads took: 0.0003000 seconds
                [1.000s][info][gc] GC(1) To-space exhausted
                [1.010s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 250M->240M(256M) 80.000ms
                [1.500s][info][gc] GC(2) Pause Young (Concurrent Start) (Metadata GC Threshold) 100M->90M(256M) 3.000ms
                [2.000s][info][gc] GC(3) Pause Full (System.gc()) 120M->30M(256M) 150.000ms
                [2.200s][info][safepoint] Entering safepoint region: RevokeBias
                [2.400s][info][safepoint] Total time for which application threads were stopped: 0.2010000 seconds, Stopping threads took: 0.2000000 seconds
                [3.000s][info][gc] GC(4) Pause Young (Normal) (G1 Humongous Allocation) 60M->20M(256M) 2.000ms
                """;
        GcLogData data = parse(log);
        GcAnalysisDto analysis = analyze(data);

        assertThat(data.collector()).isEqualTo("G1");
        assertThat(analysis.kpi().pauseCount()).isEqualTo(5);
        assertThat(analysis.kpi().fullCount()).isEqualTo(1);
        assertThat(analysis.kpi().maxPauseMs()).isEqualTo(150.0);
        assertThat(analysis.kpi().totalPauseMs()).isEqualTo(239.5);
        assertThat(analysis.summary().coveredMs()).isEqualTo(2990.0);
        assertThat(analysis.kpi().throughputPercent()).isEqualTo(91.99);
        GcPauseDto first = analysis.pauses().get(0);
        assertThat(first.cause()).isEqualTo("G1 Evacuation Pause");
        assertThat(first.uptimeMs()).isEqualTo(500.5);
        assertThat(first.heapBeforeMb()).isEqualTo(24.0);
        assertThat(first.heapTotalMb()).isEqualTo(256.0);
        assertThat(analysis.pauses().get(3).cause()).isEqualTo("System.gc()");
        assertThat(analysis.pauses().get(4).category()).isEqualTo(GcPauseCategory.HUMONGOUS);
        assertThat(data.evacuationFailures()).isEqualTo(1);
        assertThat(cause(analysis, "System.gc()").advice()).contains("DisableExplicitGC");
        assertThat(cause(analysis, "Metadata GC Threshold").advice()).contains("Metaspace");

        assertThat(analysis.safepoints().count()).isEqualTo(2);
        assertThat(analysis.safepoints().slowTtspCount()).isEqualTo(1);
        assertThat(analysis.safepoints().maxTtspMs()).isEqualTo(200.0);
        assertThat(analysis.safepoints().slowest().get(0).operation()).isEqualTo("RevokeBias");
        assertThat(analysis.safepoints().slowest().get(0).slowTtsp()).isTrue();

        assertThat(analysis.findings()).extracting(GcFindingDto::code)
                .contains("LOW_THROUGHPUT", "SYSTEM_GC", "METADATA_THRESHOLD", "HUMONGOUS", "EVACUATION_FAILURE",
                        "LONG_TTSP")
                .doesNotContain("FULL_GC");
        assertThat(analysis.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void jdk17SingleGenerationZgcWithAllocationStall() throws IOException {
        String log = """
                [0.020s][info][gc,init] Version: 17.0.9+9 (release)
                [0.021s][info][gc,init] Using The Z Garbage Collector
                [1.000s][info][gc,start] GC(0) Garbage Collection (Warmup)
                [1.001s][info][gc,phases] GC(0) Pause Mark Start 0.020ms
                [1.010s][info][gc,phases] GC(0) Pause Mark End 0.030ms
                [1.020s][info][gc,phases] GC(0) Pause Relocate Start 0.010ms
                [1.030s][info][gc] GC(0) Garbage Collection (Warmup) 410M(10%)->200M(5%)
                [1.500s][info][gc] Allocation Stall (default task-7) 12.500ms
                [2.000s][info][gc] GC(1) Garbage Collection (Allocation Rate) 1000M(25%)->300M(8%)
                """;
        GcLogData data = parse(log);
        GcAnalysisDto analysis = analyze(data);

        assertThat(data.collector()).isEqualTo("ZGC");
        assertThat(analysis.summary().jvmVersion()).isEqualTo("17.0.9+9");
        assertThat(analysis.pauses()).hasSize(3).allSatisfy(p -> assertThat(p.cause()).isEqualTo("Warmup"));
        assertThat(data.collections()).hasSize(2);
        assertThat(data.collections().get(0).heapTotalMb()).isEqualTo(4100.0);
        // 800 MB allocated between the end of GC(0) and GC(1), 0.97 s apart
        assertThat(analysis.kpi().allocationRateMbPerSec()).isEqualTo(824.7);
        assertThat(analysis.causes()).extracting(GcCauseStatDto::cause).containsExactly("Warmup", "Allocation Rate");
        assertThat(analysis.findings()).extracting(GcFindingDto::code).contains("ALLOCATION_STALL");
    }

    @Test
    void shenandoahDegeneratedAndFullGcWithUptimeMillis() throws IOException {
        String log = """
                [10ms][info][gc] Using Shenandoah
                [1000ms][info][gc] Trigger: Free (12M) is below minimum threshold (13M)
                [1001ms][info][gc] GC(0) Pause Init Mark (unload classes) 0.200ms
                [1020ms][info][gc] GC(0) Pause Final Mark (unload classes) 0.300ms
                [1030ms][info][gc] GC(0) Concurrent cleanup 100M->40M(128M) 0.050ms
                [2000ms][info][gc] GC(1) Pause Degenerated GC (Mark) 120M->60M(128M) 25.000ms
                [3000ms][info][gc] GC(2) Pause Full 125M->50M(128M) 300.000ms
                """;
        GcAnalysisDto analysis = analyze(parse(log));

        assertThat(analysis.summary().collector()).isEqualTo("Shenandoah");
        assertThat(analysis.kpi().fullCount()).isEqualTo(2);
        assertThat(analysis.causes()).extracting(GcCauseStatDto::cause)
                .contains("Free is below minimum threshold");
        assertThat(analysis.findings()).extracting(GcFindingDto::code)
                .contains("DEGENERATED_GC", "FULL_GC", "LONG_PAUSE");
    }

    @Test
    void foreignTextHasNoEvents() throws IOException {
        GcLogData data = parse("2024-01-01 12:00:00 INFO started\n[GC (Allocation Failure) 1024K->512K(2048K), 0.001 secs]\n");
        GcAnalysisDto analysis = analyze(data);
        assertThat(data.pauses()).isEmpty();
        assertThat(analysis.findings()).extracting(GcFindingDto::code).containsExactly("NO_GC_EVENTS");
    }

    @Test
    void causeParsingKeepsNestedParentheses() {
        assertThat(GcLogData.causeOf("Young (Normal) (G1 Evacuation Pause)")).isEqualTo("G1 Evacuation Pause");
        assertThat(GcLogData.causeOf("Full (System.gc())")).isEqualTo("System.gc()");
        assertThat(GcLogData.causeOf("Young (Mixed) (G1 Evacuation Pause) (Evacuation Failure)"))
                .isEqualTo("G1 Evacuation Pause");
        assertThat(GcLogData.causeOf("Remark")).isNull();
        assertThat(GcLogData.categoryOf("Young (Mixed) (G1 Evacuation Pause)", "G1 Evacuation Pause", false))
                .isEqualTo(GcPauseCategory.MIXED);
        assertThat(GcLogData.categoryOf("Young (Prepare Mixed) (G1 Evacuation Pause)", "G1 Evacuation Pause", false))
                .isEqualTo(GcPauseCategory.YOUNG);
    }

    // ---------------------------------------------------------------- helpers

    static GcAnalysisDto analyze(GcLogData data) {
        GcAnalysisSummaryDto summary = new GcAnalysisSummaryDto("gc-test", DiagnosticSource.FILE, null, "test",
                JobStatus.READY, Instant.now(), null, null, null, null, null, 0, 0);
        return GcLogAnalyzer.analyze(summary, data, SETTINGS);
    }

    private static GcLogData parse(String text) throws IOException {
        return UnifiedGcLogParser.parse(new BufferedReader(new StringReader(text)), 100_000);
    }

    private static GcLogData resource(String name) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                GcLogParserTest.class.getResourceAsStream("/gclogs/" + name)), StandardCharsets.UTF_8))) {
            return UnifiedGcLogParser.parse(reader, 100_000);
        }
    }

    private static GcCauseStatDto cause(GcAnalysisDto analysis, String cause) {
        return analysis.causes().stream().filter(c -> c.cause().equals(cause)).findFirst()
                .orElseThrow(() -> new AssertionError(cause + " not in " + analysis.causes()));
    }
}
