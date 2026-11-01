package com.poludnitsa.inspector.gc;

import com.poludnitsa.inspector.api.dto.DiagnosticSource;
import com.poludnitsa.inspector.api.dto.GcAnalysisDto;
import com.poludnitsa.inspector.api.dto.GcAnalysisSummaryDto;
import com.poludnitsa.inspector.api.dto.GcLiveRequestDto;
import com.poludnitsa.inspector.api.dto.JobStatus;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.common.DaemonThreadFactory;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.profiler.JfrProfileRecorder;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * GC &amp; safepoint analysis of two sources, with one analysis ({@link GcLogAnalyzer}):
 *
 * <ul>
 *   <li><b>Uploaded files</b>: Java 11 / 17 / 21 Unified Logging ({@code -Xlog:gc*,safepoint*}, plain or gzip),
 *   parsed line by line ({@link UnifiedGcLogParser}), or a {@code .jfr} recording ({@link JfrGcParser}).</li>
 *   <li><b>A live target</b>: a GC / safepoint JFR recording created in the target through its
 *   {@code FlightRecorderMXBean} ({@link JfrProfileRecorder}), copied over JMX and parsed locally — never the
 *   inspector's own Flight Recorder.</li>
 * </ul>
 */
@Service
public class GcLogParserAndAnalyzerService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GcLogParserAndAnalyzerService.class);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    static final String RECORDING_PREFIX = "jvm-leak-inspector-gc-";

    private final JmxConnectionManager connections;
    private final JvmDiscoveryService discovery;
    private final InspectorProperties.GcLog props;
    private final JfrProfileRecorder jfr;
    private final Map<String, Analysis> analyses = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(new DaemonThreadFactory("gc-live"));

    public GcLogParserAndAnalyzerService(JmxConnectionManager connections, JvmDiscoveryService discovery,
                                         InspectorProperties properties) {
        this.connections = connections;
        this.discovery = discovery;
        this.props = properties.gcLog();
        this.jfr = new JfrProfileRecorder(connections, properties.profiler());
    }

    // ---------------------------------------------------------------- files

    /** Parses an uploaded GC log (plain / gzip Unified Logging) or JFR file. */
    public GcAnalysisSummaryDto upload(String fileName, InputStream input) {
        String name = fileName == null || fileName.isBlank() ? "gc.log" : fileName;
        Analysis analysis = new Analysis(newId(), DiagnosticSource.FILE, null, name, null);
        GcLogData data;
        try {
            BufferedInputStream in = new BufferedInputStream(input, 1 << 16);
            byte[] magic = peek(in, 4);
            if (magic.length == 4 && magic[0] == 'F' && magic[1] == 'L' && magic[2] == 'R' && magic[3] == 0) {
                data = parseJfrUpload(analysis.id, in);
            } else {
                InputStream text = magic.length >= 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b
                        ? new GZIPInputStream(in, 1 << 16) : in;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(text, StandardCharsets.UTF_8),
                        1 << 16)) {
                    data = UnifiedGcLogParser.parse(reader, props.maxEvents());
                }
            }
        } catch (IOException e) {
            throw new InspectorException(400, "GC_LOG_UNREADABLE", "Cannot read " + name + ": " + e.getMessage(), e);
        }
        if (data.pauses.isEmpty() && data.collections.isEmpty() && data.safepoints.isEmpty()) {
            throw new OperationRejectedException(400, "GC_LOG_INVALID", "No GC pause or safepoint found in " + name
                    + ": expected Java 11+ Unified Logging (-Xlog:gc*,safepoint*) or a JFR recording");
        }
        analysis.ready(GcLogAnalyzer.analyze(analysis.summary(), data, settings()));
        store(analysis);
        log.info("GC log {} analysed: {} pauses, collector {}", name, data.pauses.size(), data.collector);
        return analysis.summary();
    }

    private GcLogData parseJfrUpload(String id, InputStream in) throws IOException {
        Files.createDirectories(props.resolvedWorkDir());
        Path file = props.resolvedWorkDir().resolve(id + "-upload.jfr");
        try {
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            return JfrGcParser.parse(file, null, null, props.maxEvents());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static byte[] peek(BufferedInputStream in, int count) throws IOException {
        in.mark(count);
        byte[] buffer = in.readNBytes(count);
        in.reset();
        return buffer;
    }

    // ---------------------------------------------------------------- live

    /** Records GC and safepoint events in the target for {@code durationSeconds}, then analyses them. */
    public GcAnalysisSummaryDto startLive(GcLiveRequestDto request) {
        long pid = request.pid();
        if (pid == connections.selfPid()) {
            throw new OperationRejectedException(400, "SELF_MONITORING_FORBIDDEN",
                    "PID " + pid + " is the inspector itself; it never records its own JVM");
        }
        Duration duration = Duration.ofSeconds(request.durationSeconds());
        if (duration.compareTo(props.maxLiveDuration()) > 0) {
            throw new OperationRejectedException(400, "GC_RECORDING_TOO_LONG", "At most "
                    + props.maxLiveDuration().toSeconds() + " s per recording (inspector.gc-log.max-live-duration)");
        }
        JvmProcessDto process = describe(pid);
        Analysis analysis;
        synchronized (analyses) {
            boolean running = analyses.values().stream()
                    .anyMatch(a -> a.pid != null && a.pid == pid && a.status == JobStatus.RUNNING);
            if (running) {
                throw new OperationRejectedException(409, "GC_RECORDING_IN_PROGRESS",
                        "A GC recording of PID " + pid + " is running; stop it or wait for it");
            }
            analysis = new Analysis(newId(), DiagnosticSource.LIVE, pid,
                    process == null ? "PID " + pid : process.displayName(), duration.toSeconds());
            analyses.put(analysis.id, analysis);
            evict();
        }
        Analysis started = analysis;
        analysis.task = executor.submit(() -> run(started, duration));
        return analysis.summary();
    }

    private void run(Analysis analysis, Duration duration) {
        Path file = null;
        long recordingId = -1;
        try {
            Files.createDirectories(props.resolvedWorkDir());
            file = props.resolvedWorkDir().resolve(analysis.id + ".jfr");
            recordingId = jfr.start(analysis.pid, RECORDING_PREFIX + analysis.id, duration, JfrGcParser.settings());
            Instant recordingStart = Instant.now();
            long started = System.nanoTime();
            analysis.progress("Recording GC & safepoints on the target (JFR, " + duration.toSeconds() + " s)");
            analysis.stop.await(duration.toMillis(), TimeUnit.MILLISECONDS);
            double coveredMs = (System.nanoTime() - started) / 1e6;
            analysis.progress("Copying the recording from the target");
            jfr.stopAndTransfer(analysis.pid, recordingId, file);
            recordingId = -1;
            analysis.progress("Analysing");
            GcLogData data = JfrGcParser.parse(file, recordingStart, coveredMs, props.maxEvents());
            analysis.ready(GcLogAnalyzer.analyze(analysis.summary(), data, settings()));
            log.info("GC recording {} of PID {}: {} pauses, {} safepoints", analysis.id, analysis.pid,
                    data.pauses.size(), data.safepoints.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            analysis.fail("Cancelled");
        } catch (InspectorException e) {
            analysis.fail(e.getMessage());
        } catch (IOException | RuntimeException e) {
            analysis.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("GC recording {} failed", analysis.id, e);
        } finally {
            if (recordingId >= 0) {
                jfr.close(analysis.pid, recordingId);
            }
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // temp dir
                }
            }
        }
    }

    /** Ends a live recording now and analyses what was recorded. */
    public GcAnalysisSummaryDto stop(String id) {
        Analysis analysis = analysis0(id);
        analysis.stop.countDown();
        return analysis.summary();
    }

    // ---------------------------------------------------------------- queries

    public List<GcAnalysisSummaryDto> list() {
        synchronized (analyses) {
            List<GcAnalysisSummaryDto> result = new ArrayList<>();
            analyses.values().forEach(a -> result.add(a.summary()));
            result.sort(Comparator.comparing(GcAnalysisSummaryDto::startedAt).reversed());
            return result;
        }
    }

    public GcAnalysisSummaryDto summary(String id) {
        return analysis0(id).summary();
    }

    public GcAnalysisDto analysis(String id) {
        Analysis analysis = analysis0(id);
        GcAnalysisDto result = analysis.result;
        if (result == null) {
            throw new OperationRejectedException(409, "GC_ANALYSIS_NOT_READY",
                    "GC analysis " + id + " is " + analysis.status.name().toLowerCase());
        }
        return result;
    }

    /** Finished analyses, newest first, of {@code pid} (and every file) or of all when {@code pid} is null. */
    public List<GcAnalysisDto> readyAnalyses(Long pid) {
        synchronized (analyses) {
            List<GcAnalysisDto> result = new ArrayList<>();
            for (Analysis analysis : analyses.values()) {
                if (analysis.result != null && (pid == null || analysis.pid == null || analysis.pid.equals(pid))) {
                    result.add(analysis.result);
                }
            }
            result.sort(Comparator.comparing((GcAnalysisDto a) -> a.summary().startedAt()).reversed());
            return result;
        }
    }

    /** Cancels a running recording (closing it on the target) or forgets an analysis. */
    public void delete(String id) {
        Analysis analysis;
        synchronized (analyses) {
            analysis = analyses.remove(id);
        }
        if (analysis == null) {
            throw notFound(id);
        }
        if (analysis.task != null && analysis.status == JobStatus.RUNNING) {
            analysis.task.cancel(true);
        }
    }

    private Analysis analysis0(String id) {
        synchronized (analyses) {
            Analysis analysis = analyses.get(id);
            if (analysis == null) {
                throw notFound(id);
            }
            return analysis;
        }
    }

    private void store(Analysis analysis) {
        synchronized (analyses) {
            analyses.put(analysis.id, analysis);
            evict();
        }
    }

    private void evict() {
        while (analyses.size() > props.maxAnalyses()) {
            String oldest = analyses.values().stream().filter(a -> a.status != JobStatus.RUNNING)
                    .min(Comparator.comparing(a -> a.startedAt)).map(a -> a.id).orElse(null);
            if (oldest == null) {
                return;
            }
            analyses.remove(oldest);
        }
    }

    private GcLogAnalyzer.Settings settings() {
        return new GcLogAnalyzer.Settings(props.maxPausePoints(), props.rateWindows(),
                props.ttspWarning().toNanos() / 1e6);
    }

    private JvmProcessDto describe(long pid) {
        try {
            return discovery.describe(pid);
        } catch (InspectorException e) {
            return null;
        }
    }

    private static String newId() {
        return "gc-" + ID_TIME.format(Instant.now()) + "-"
                + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
    }

    private static InspectorException notFound(String id) {
        return new InspectorException(404, "GC_ANALYSIS_NOT_FOUND", "GC analysis " + id + " does not exist");
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    private static final class Analysis {
        final String id;
        final DiagnosticSource source;
        final Long pid;
        final String name;
        final Long durationSeconds;
        final Instant startedAt = Instant.now();
        final CountDownLatch stop = new CountDownLatch(1);
        volatile JobStatus status = JobStatus.RUNNING;
        volatile String progress = "Starting";
        volatile String error;
        volatile GcAnalysisDto result;
        Future<?> task;

        Analysis(String id, DiagnosticSource source, Long pid, String name, Long durationSeconds) {
            this.id = id;
            this.source = source;
            this.pid = pid;
            this.name = name;
            this.durationSeconds = durationSeconds;
        }

        void progress(String message) {
            progress = message;
        }

        void ready(GcAnalysisDto analysis) {
            GcAnalysisSummaryDto s = analysis.summary();
            result = new GcAnalysisDto(new GcAnalysisSummaryDto(id, source, pid, name, JobStatus.READY, startedAt,
                    durationSeconds, null, null, s.collector(), s.jvmVersion(), s.pauseCount(), s.coveredMs()),
                    analysis.kpi(), analysis.pauses(), analysis.rates(), analysis.causes(), analysis.safepoints(),
                    analysis.findings(), analysis.concurrentCycles(), analysis.parsedLines(), analysis.unparsedLines(),
                    analysis.truncated());
            progress = null;
            status = JobStatus.READY;
        }

        void fail(String message) {
            error = message;
            progress = null;
            status = JobStatus.FAILED;
        }

        GcAnalysisSummaryDto summary() {
            GcAnalysisDto r = result;
            if (r != null) {
                return r.summary();
            }
            return new GcAnalysisSummaryDto(id, source, pid, name, status, startedAt, durationSeconds, progress, error,
                    null, null, 0, 0);
        }
    }
}
