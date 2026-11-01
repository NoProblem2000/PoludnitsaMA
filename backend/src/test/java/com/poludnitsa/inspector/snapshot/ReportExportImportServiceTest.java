package com.poludnitsa.inspector.snapshot;

import static com.poludnitsa.inspector.snapshot.SnapshotTestData.stats;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.ImportedReportDto;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.ProcessType;
import com.poludnitsa.inspector.api.dto.ReportExportRequestDto;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.api.dto.SnapshotKind;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.discovery.ProcessClassifier;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.live.LiveMemoryCollectorService;
import com.poludnitsa.inspector.report.ReportExportImportService;
import com.poludnitsa.inspector.report.SessionReportBundle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportExportImportServiceTest {

    private static final String HOSTILE = "<script>alert('x')</script>";

    @TempDir
    Path workDir;

    private JmxConnectionManager connections;
    private LiveMemoryCollectorService collector;
    private JvmDiscoveryService discovery;
    private SnapshotStore store;
    private ReportExportImportService reports;

    @BeforeEach
    void setUp() {
        InspectorProperties properties = SnapshotTestData.properties(workDir);
        ObjectMapper mapper = SnapshotTestData.mapper();
        connections = new JmxConnectionManager(properties);
        collector = new LiveMemoryCollectorService(connections, properties);
        discovery = new JvmDiscoveryService(connections, new ProcessClassifier(), properties);
        store = new SnapshotStore(properties, mapper);
        reports = new ReportExportImportService(store, new SnapshotDiffService(store), collector, discovery, mapper);
    }

    @AfterEach
    void tearDown() {
        collector.destroy();
        discovery.destroy();
        connections.destroy();
    }

    @Test
    void bundleContainsSnapshotsAutomaticDiffAndFindings() {
        SnapshotRecord first = deep(List.of(stats("com.acme.Order", 100, 3_200L, 8_000L)), List.of());
        SnapshotRecord second = deep(List.of(stats("com.acme.Order", 9_000, 288_000L, 900_000L)),
                List.of(finding("leak-1", Severity.CRITICAL)));

        SessionReportBundle bundle = reports.buildBundle(new ReportExportRequestDto(null, List.of(), List.of(),
                true, null, null));

        assertThat(bundle.formatVersion()).isEqualTo(ReportExportImportService.FORMAT_VERSION);
        assertThat(bundle.snapshots()).extracting(s -> s.summary().snapshotId())
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(bundle.diffs()).singleElement().satisfies(diff -> {
            assertThat(diff.baseline().snapshotId()).isEqualTo(first.id());
            assertThat(diff.current().snapshotId()).isEqualTo(second.id());
            assertThat(diff.classes().get(0).className()).isEqualTo("com.acme.Order");
        });
        assertThat(bundle.findings()).extracting(InspectionFindingDto::id).containsExactly("leak-1");
    }

    @Test
    void htmlIsStandaloneAndEscapesHeapContent() throws Exception {
        deep(List.of(stats("com.acme." + HOSTILE, 1, 16L, 16L)), List.of(finding("leak-1", Severity.CRITICAL)));
        SessionReportBundle bundle = reports.buildBundle(new ReportExportRequestDto(null, List.of(), List.of(),
                false, "Отчёт <b>тест</b>", null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        reports.writeHtml(bundle, out);

        String html = out.toString(StandardCharsets.UTF_8);
        assertThat(html).startsWith("<!doctype html>")
                .contains("#1b1d23", "#00e5ff", "#ff3d71")
                .contains("&lt;script&gt;", "Отчёт &lt;b&gt;тест&lt;/b&gt;", "Как исправить")
                .doesNotContain(HOSTILE)
                .doesNotContain("<script", "src=\"http", "href=\"http");
    }

    @Test
    void jsonRoundTripRegistersSnapshotsUnderNewIds() throws Exception {
        SnapshotRecord original = deep(List.of(stats("com.acme.Order", 10, 320L, 640L)),
                List.of(finding("leak-1", Severity.WARNING)));
        SessionReportBundle bundle = reports.buildBundle(new ReportExportRequestDto(null, List.of(original.id()),
                List.of(), false, null, null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reports.writeJson(bundle, out);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(new String(gzip.readAllBytes(), StandardCharsets.UTF_8)).contains("\"formatVersion\":2");
        }

        ImportedReportDto imported = reports.importReport(new ByteArrayInputStream(out.toByteArray()));

        String newId = imported.snapshotIds().get(original.id());
        assertThat(newId).isNotNull().isNotEqualTo(original.id());
        SnapshotRecord copy = store.get(newId);
        assertThat(copy.toSummary().imported()).isTrue();
        assertThat(copy.data().findings()).extracting(InspectionFindingDto::id).containsExactly("leak-1");
        assertThat(imported.findings()).isEqualTo(1);
        assertThat(reports.importedReport(imported.reportId()).title()).isEqualTo(bundle.title());
        assertThat(reports.importedReports()).hasSize(1);
    }

    @Test
    void plainJsonIsAcceptedAndGarbageRejected() throws Exception {
        deep(List.of(stats("com.acme.Order", 10, 320L, 640L)), List.of());
        SessionReportBundle bundle = reports.buildBundle(new ReportExportRequestDto(null, List.of(), List.of(),
                false, null, null));
        byte[] json = SnapshotTestData.mapper().writeValueAsBytes(bundle);

        assertThat(reports.importReport(new ByteArrayInputStream(json)).snapshotIds()).hasSize(1);
        assertThatThrownBy(() -> reports.importReport(new ByteArrayInputStream("not json".getBytes())))
                .isInstanceOf(InspectorException.class).hasMessageContaining("Not a JVM Memory");
        assertThatThrownBy(() -> reports.importReport(new ByteArrayInputStream(
                "{\"formatVersion\":99}".getBytes()))).hasMessageContaining("Unsupported report format version 99");
    }

    private SnapshotRecord deep(List<ClassStatsDto> classes, List<InspectionFindingDto> findings) {
        SnapshotRecord record = store.create(SnapshotKind.DEEP, 4711, "orders", ProcessType.WILDFLY, true);
        long total = classes.stream().mapToLong(ClassStatsDto::shallowBytes).sum();
        record.analysed(10L, new SnapshotData(10, classes.size(), total, 3, true, classes, List.of(),
                SnapshotDataFactory.groups(classes, total), findings, List.of()));
        store.complete(record);
        return record;
    }

    private static InspectionFindingDto finding(String id, Severity severity) {
        return new InspectionFindingDto(id, "jvm.test", severity, FindingCategory.JVM_CORE, "Title " + HOSTILE,
                1 << 20, 1, "com.acme.Order", "cache", List.of(), "Order <- static CACHE in com.acme.Cache",
                List.of("evidence " + HOSTILE), "why " + HOSTILE, "map.clear(); // " + HOSTILE);
    }
}
