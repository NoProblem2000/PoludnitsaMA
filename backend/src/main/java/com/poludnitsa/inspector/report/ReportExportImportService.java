package com.poludnitsa.inspector.report;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poludnitsa.inspector.api.dto.DiffPairDto;
import com.poludnitsa.inspector.api.dto.ImportedReportDto;
import com.poludnitsa.inspector.api.dto.GcAnalysisDto;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.MetricsPointDto;
import com.poludnitsa.inspector.api.dto.OqlHistoryEntryDto;
import com.poludnitsa.inspector.api.dto.ProfileReportDto;
import com.poludnitsa.inspector.api.dto.ReportExportRequestDto;
import com.poludnitsa.inspector.api.dto.SnapshotDiffDto;
import com.poludnitsa.inspector.api.dto.SnapshotStatus;
import com.poludnitsa.inspector.api.dto.ThreadLiveDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesAnalysisDto;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.gc.GcLogParserAndAnalyzerService;
import com.poludnitsa.inspector.live.LiveMemoryCollectorService;
import com.poludnitsa.inspector.oql.OqlExecutionService;
import com.poludnitsa.inspector.profiler.CpuAndTimelineProfilerService;
import com.poludnitsa.inspector.snapshot.SnapshotDiffService;
import com.poludnitsa.inspector.snapshot.SnapshotRecord;
import com.poludnitsa.inspector.snapshot.SnapshotStore;
import com.poludnitsa.inspector.threads.ThreadContentionAndSeriesService;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * File-based reports instead of a database: a {@link SessionReportBundle} of process metadata, chart history,
 * snapshots (dominators, classes, groups), diffs and findings, written as gzipped JSON or standalone HTML and
 * re-imported from JSON on any machine.
 */
@Service
public class ReportExportImportService {

    /** 1: memory only (steps 1–8); 2: plus CPU profiles, thread series / contention, GC analyses, OQL history. */
    public static final int FORMAT_VERSION = 2;
    private static final int REPORT_PROFILES = 5;
    static final String GENERATOR = "JVM Memory & Leak Inspector 0.1.0";
    private static final long MAX_IMPORT_BYTES = 512L << 20;
    private static final int MAX_IMPORTED_REPORTS = 10;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private static final Logger log = LoggerFactory.getLogger(ReportExportImportService.class);

    private final SnapshotStore store;
    private final SnapshotDiffService diffs;
    private final LiveMemoryCollectorService collector;
    private final JvmDiscoveryService discovery;
    private final ObjectMapper mapper;
    private final CpuAndTimelineProfilerService profiler;
    private final ThreadContentionAndSeriesService threads;
    private final GcLogParserAndAnalyzerService gc;
    private final OqlExecutionService oql;
    private final HtmlReportRenderer renderer = new HtmlReportRenderer();
    private final Map<String, SessionReportBundle> imported = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SessionReportBundle> eldest) {
                    return size() > MAX_IMPORTED_REPORTS;
                }
            });

    /** Memory-only reports (no diagnostics services), e.g. in tests. */
    public ReportExportImportService(SnapshotStore store, SnapshotDiffService diffs,
                                     LiveMemoryCollectorService collector, JvmDiscoveryService discovery,
                                     ObjectMapper mapper) {
        this(store, diffs, collector, discovery, mapper, null, null, null, null);
    }

    @Autowired
    public ReportExportImportService(SnapshotStore store, SnapshotDiffService diffs,
                                     LiveMemoryCollectorService collector, JvmDiscoveryService discovery,
                                     ObjectMapper mapper, CpuAndTimelineProfilerService profiler,
                                     ThreadContentionAndSeriesService threads, GcLogParserAndAnalyzerService gc,
                                     OqlExecutionService oql) {
        this.store = store;
        this.diffs = diffs;
        this.collector = collector;
        this.discovery = discovery;
        this.mapper = mapper;
        this.profiler = profiler;
        this.threads = threads;
        this.gc = gc;
        this.oql = oql;
    }

    // ---------------------------------------------------------------- export

    public SessionReportBundle buildBundle(ReportExportRequestDto request) {
        List<SnapshotRecord> records = selectSnapshots(request);
        Long pid = request.pid();
        if (pid == null && !records.isEmpty()
                && records.stream().map(SnapshotRecord::pid).distinct().count() == 1) {
            pid = records.get(0).pid();
        }
        JvmProcessDto process = pid == null ? null : describe(pid);
        List<MetricsPointDto> history = pid != null && request.metricsHistoryIncluded()
                ? collector.history(pid) : List.of();

        List<SessionReportBundle.SnapshotReport> snapshots = records.stream()
                .map(r -> new SessionReportBundle.SnapshotReport(r.toSummary(), r.data()))
                .toList();
        List<SnapshotDiffDto> comparisons = new ArrayList<>();
        if (!request.diffs().isEmpty()) {
            for (DiffPairDto pair : request.diffs()) {
                comparisons.add(diffs.diff(pair.baselineId(), pair.currentId(), 500, true));
            }
        } else {
            records.stream()
                    .collect(Collectors.groupingBy(SnapshotRecord::pid, LinkedHashMap::new,
                            Collectors.toList()))
                    .values().stream()
                    .filter(group -> group.size() >= 2)
                    .forEach(group -> {
                        List<SnapshotRecord> chronological = group.stream()
                                .sorted(SnapshotStore.CHRONOLOGICAL).toList();
                        comparisons.add(diffs.diff(chronological.get(0).id(),
                                chronological.get(chronological.size() - 1).id(), 300, false));
                    });
        }

        Map<String, InspectionFindingDto> findings = new LinkedHashMap<>();
        records.stream()
                .sorted(SnapshotStore.CHRONOLOGICAL.reversed())
                .flatMap(r -> r.data().findings().stream())
                .forEach(f -> findings.putIfAbsent(f.id(), f));

        // diagnostics of the same process (and every uploaded file): profiles, threads, GC, OQL
        boolean diagnostics = request.diagnosticsIncluded();
        List<ProfileReportDto> profiles = diagnostics && profiler != null
                ? profiler.reportProfiles(pid, REPORT_PROFILES) : List.of();
        List<ThreadSeriesAnalysisDto> series = diagnostics && threads != null ? threads.readyAnalyses(pid) : List.of();
        ThreadLiveDto live = diagnostics && threads != null && pid != null ? threads.liveIfRunning(pid) : null;
        List<GcAnalysisDto> gcAnalyses = diagnostics && gc != null ? gc.readyAnalyses(pid) : List.of();
        List<OqlHistoryEntryDto> queries = new ArrayList<>();
        if (diagnostics && oql != null) {
            Set<String> included = records.stream().map(SnapshotRecord::id).collect(Collectors.toSet());
            oql.history(null).stream().filter(q -> included.contains(q.snapshotId())).forEach(queries::add);
        }

        Instant now = Instant.now();
        String title = request.title() != null && !request.title().isBlank() ? request.title()
                : "Отчёт по JVM" + (process != null ? " — " + process.displayName() + " (PID "
                + process.pid() + ")" : "");
        return new SessionReportBundle(FORMAT_VERSION, "rep-" + FILE_TIME.format(now) + "-"
                + UUID.randomUUID().toString().substring(0, 8), title, now, GENERATOR, hostName(), process, history,
                snapshots, comparisons, new ArrayList<>(findings.values()), profiles, series, live, gcAnalyses,
                queries);
    }

    /** Gzipped JSON; {@code out} is not closed. */
    public void writeJson(SessionReportBundle bundle, OutputStream out) throws IOException {
        GZIPOutputStream gzip = new GZIPOutputStream(out, 64 * 1024);
        mapper.writer().without(JsonGenerator.Feature.AUTO_CLOSE_TARGET).writeValue(gzip, bundle);
        gzip.finish();
    }

    /** Standalone HTML; {@code out} is not closed. */
    public void writeHtml(SessionReportBundle bundle, OutputStream out) throws IOException {
        out.write(renderer.render(bundle).getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static String fileName(SessionReportBundle bundle, String extension) {
        return "jvm-memory-report-" + FILE_TIME.format(bundle.generatedAt()) + extension;
    }

    /** Reads a report file ({@code .json.gz} / {@code .json}, e.g. opened offline in the UI) without importing it. */
    public SessionReportBundle readBundle(InputStream input) {
        return read(input);
    }

    // ---------------------------------------------------------------- import

    /** Reads a {@code .json.gz} or {@code .json} report and registers its snapshots in this session. */
    public ImportedReportDto importReport(InputStream input) {
        SessionReportBundle bundle = read(input);
        Map<String, String> ids = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (SessionReportBundle.SnapshotReport snapshot : bundle.snapshots()) {
            if (snapshot.summary() == null || snapshot.data() == null
                    || snapshot.summary().status() != SnapshotStatus.READY) {
                warnings.add("Пропущен неполный снапшот "
                        + (snapshot.summary() == null ? "?" : snapshot.summary().snapshotId()));
                continue;
            }
            SnapshotRecord record = store.importSnapshot(snapshot.summary(), snapshot.data());
            ids.put(snapshot.summary().snapshotId(), record.id());
        }
        String reportId = "imp-" + UUID.randomUUID().toString().substring(0, 12);
        imported.put(reportId, bundle);
        log.info("Imported report '{}' ({} snapshots, {} findings)", bundle.title(), ids.size(),
                bundle.findings().size());
        return describeImported(reportId, bundle, ids, warnings);
    }

    public List<ImportedReportDto> importedReports() {
        synchronized (imported) {
            List<ImportedReportDto> result = new ArrayList<>();
            imported.forEach((id, bundle) -> result.add(describeImported(id, bundle, Map.of(), List.of())));
            return result;
        }
    }

    public SessionReportBundle importedReport(String reportId) {
        SessionReportBundle bundle = imported.get(reportId);
        if (bundle == null) {
            throw new InspectorException(404, "REPORT_NOT_FOUND", "Imported report " + reportId + " does not exist");
        }
        return bundle;
    }

    // ---------------------------------------------------------------- helpers

    private SessionReportBundle read(InputStream input) {
        SessionReportBundle bundle;
        try (InputStream in = open(input)) {
            bundle = mapper.readValue(in, SessionReportBundle.class);
        } catch (SizeLimitException e) {
            throw new OperationRejectedException(413, "REPORT_TOO_LARGE",
                    "Report is larger than " + (MAX_IMPORT_BYTES >> 20) + " MiB after decompression");
        } catch (JsonProcessingException e) {
            throw new OperationRejectedException(400, "REPORT_INVALID",
                    "Not a JVM Memory & Leak Inspector report: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new InspectorException(400, "REPORT_UNREADABLE", "Cannot read report: " + e.getMessage(), e);
        }
        if (bundle == null || bundle.formatVersion() < 1 || bundle.formatVersion() > FORMAT_VERSION) {
            throw new OperationRejectedException(400, "REPORT_VERSION_UNSUPPORTED",
                    "Unsupported report format version " + (bundle == null ? "?" : bundle.formatVersion()));
        }
        return bundle;
    }

    private List<SnapshotRecord> selectSnapshots(ReportExportRequestDto request) {
        List<SnapshotRecord> records = new ArrayList<>();
        if (!request.snapshotIds().isEmpty()) {
            Set<String> unique = new LinkedHashSet<>(request.snapshotIds());
            for (String id : unique) {
                SnapshotRecord record = store.get(id);
                if (record.status() != SnapshotStatus.READY || record.data() == null) {
                    throw new OperationRejectedException(409, "SNAPSHOT_NOT_READY",
                            "Snapshot " + id + " is " + record.status());
                }
                records.add(record);
            }
            return records;
        }
        store.list().stream()
                .filter(summary -> summary.status() == SnapshotStatus.READY)
                .filter(summary -> request.pid() == null || summary.sourcePid() == request.pid())
                .map(summary -> store.find(summary.snapshotId()).orElse(null))
                .filter(Objects::nonNull)
                .filter(record -> record.data() != null)
                .forEach(records::add);
        return records;
    }

    private JvmProcessDto describe(long pid) {
        try {
            return discovery.describe(pid);
        } catch (InspectorException e) {
            return null;
        }
    }

    private static ImportedReportDto describeImported(String id, SessionReportBundle bundle, Map<String, String> ids,
                                                      List<String> warnings) {
        return new ImportedReportDto(id, bundle.title(), bundle.generatedAt(), bundle.hostName(),
                bundle.process() == null ? null : bundle.process().displayName(), ids, bundle.findings().size(),
                bundle.metricsHistory().size(), warnings);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    /** Transparent gunzip plus a hard limit against decompression bombs. */
    private static InputStream open(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input, 64 * 1024);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        InputStream decoded = first == 0x1F && second == 0x8B ? new GZIPInputStream(buffered, 64 * 1024) : buffered;
        return new LimitedInputStream(decoded, MAX_IMPORT_BYTES);
    }

    private static final class SizeLimitException extends IOException {
        SizeLimitException() {
            super("size limit exceeded");
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long read;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(long n) throws SizeLimitException {
            read += n;
            if (read > limit) {
                throw new SizeLimitException();
            }
        }
    }
}
