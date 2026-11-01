package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.api.dto.ButterflyDto;
import com.poludnitsa.inspector.api.dto.DiffFlameGraphDto;
import com.poludnitsa.inspector.api.dto.FlameGraphDto;
import com.poludnitsa.inspector.api.dto.HotspotDto;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.ProfileMetric;
import com.poludnitsa.inspector.api.dto.ProfileRequestDto;
import com.poludnitsa.inspector.api.dto.ProfileSource;
import com.poludnitsa.inspector.api.dto.ProfileStatus;
import com.poludnitsa.inspector.api.dto.ProfileSummaryDto;
import com.poludnitsa.inspector.api.dto.ProfilerCapabilitiesDto;
import com.poludnitsa.inspector.api.dto.SubsystemBreakdownDto;
import com.poludnitsa.inspector.api.dto.TimelineDto;
import com.poludnitsa.inspector.common.DaemonThreadFactory;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * CPU, wall-clock and allocation profiles of a target with their event timeline, as in dotTrace.
 *
 * <p>Two sources: JFR inside the target, driven through its {@code FlightRecorderMXBean} ({@link JfrProfileRecorder};
 * all metrics at once), or async-profiler attached by {@code asprof} ({@link AsyncProfilerBridge}; one of cpu / wall
 * / alloc, Linux and macOS). Either way a recording file is transferred, parsed into a {@link ProfileData} and
 * deleted; every query (flame graph, diff, hotspots, butterfly, subsystems) runs on that model for any time window
 * and thread, so the UI can cross-filter by a brushed interval.
 */
@Service
public class CpuAndTimelineProfilerService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CpuAndTimelineProfilerService.class);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final JmxConnectionManager connections;
    private final JvmDiscoveryService discovery;
    private final InspectorProperties.Profiler props;
    private final JfrProfileRecorder jfr;
    private final AsyncProfilerBridge asyncProfiler;
    private final Map<String, ProfileRecord> profiles = new LinkedHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(new DaemonThreadFactory("cpu-profile"));

    @Autowired
    public CpuAndTimelineProfilerService(JmxConnectionManager connections, JvmDiscoveryService discovery,
                                         InspectorProperties properties) {
        this(connections, discovery, properties, new AsyncProfilerBridge(properties.profiler()));
    }

    CpuAndTimelineProfilerService(JmxConnectionManager connections, JvmDiscoveryService discovery,
                                  InspectorProperties properties, AsyncProfilerBridge asyncProfiler) {
        this.connections = connections;
        this.discovery = discovery;
        this.props = properties.profiler();
        this.jfr = new JfrProfileRecorder(connections, props);
        this.asyncProfiler = asyncProfiler;
    }

    public ProfilerCapabilitiesDto capabilities() {
        return asyncProfiler.capabilities();
    }

    // ---------------------------------------------------------------- lifecycle

    public ProfileSummaryDto start(ProfileRequestDto request) {
        long pid = request.pid();
        if (pid == connections.selfPid()) {
            throw new OperationRejectedException(400, "SELF_MONITORING_FORBIDDEN",
                    "PID " + pid + " is the inspector itself; it never profiles its own JVM");
        }
        Duration duration = Duration.ofSeconds(request.durationSeconds());
        if (duration.compareTo(props.maxDuration()) > 0) {
            throw new OperationRejectedException(400, "PROFILE_TOO_LONG",
                    "At most " + props.maxDuration().toSeconds() + " s per profile (inspector.profiler.max-duration)");
        }
        ProfileSource source = request.effectiveSource();
        if (source == ProfileSource.ASYNC_PROFILER && !asyncProfiler.capabilities().asyncProfilerAvailable()) {
            throw new OperationRejectedException(409, "ASYNC_PROFILER_UNAVAILABLE",
                    asyncProfiler.capabilities().asyncProfilerReason());
        }
        JvmProcessDto process = describe(pid);
        double interval = source == ProfileSource.JFR ? props.cpuSamplingInterval().toNanos() / 1e6
                : props.asyncProfilerInterval().toNanos() / 1e6;
        ProfileRecord record;
        synchronized (profiles) {
            boolean running = profiles.values().stream().anyMatch(p -> p.pid == pid && !p.finished());
            if (running) {
                throw new OperationRejectedException(409, "PROFILE_IN_PROGRESS",
                        "A profile of PID " + pid + " is being recorded; wait for it or cancel it");
            }
            String id = "cpu-" + ID_TIME.format(Instant.now()) + "-" + pid + "-"
                    + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
            record = new ProfileRecord(id, pid, process == null ? "PID " + pid : process.displayName(), source,
                    request.effectiveMode(), Instant.now(), duration.toMillis(),
                    source == ProfileSource.JFR && request.tracingEnabled(), interval);
            profiles.put(id, record);
            evict();
        }
        ProfileRecord started = record;
        record.task(executor.submit(() -> run(started, duration)));
        return record.toSummary();
    }

    private void run(ProfileRecord record, Duration duration) {
        Path file = null;
        try {
            Files.createDirectories(props.resolvedWorkDir());
            file = props.resolvedWorkDir().resolve(record.id + ".jfr");
            Instant recordingStart;
            if (record.source == ProfileSource.JFR) {
                long recordingId = jfr.start(record.pid, record.id, duration, record.tracing);
                recordingStart = Instant.now();
                try {
                    record.phase(ProfileStatus.RECORDING, "Recording " + duration.toSeconds() + " s on the target (JFR)");
                    Thread.sleep(duration.toMillis());
                    record.phase(ProfileStatus.PARSING, "Transferring the recording from the target");
                    jfr.stopAndTransfer(record.pid, recordingId, file);
                } catch (InterruptedException e) {
                    jfr.close(record.pid, recordingId);
                    Thread.currentThread().interrupt();
                    record.fail("Cancelled");
                    return;
                }
            } else {
                recordingStart = Instant.now();
                record.phase(ProfileStatus.RECORDING, "async-profiler " + record.mode.name().toLowerCase()
                        + " for " + duration.toSeconds() + " s");
                asyncProfiler.profile(record.pid, record.mode, duration, file);
            }
            record.phase(ProfileStatus.PARSING, "Parsing " + Files.size(file) / 1024 + " KiB of events");
            double cpuUs = record.samplingIntervalMs * 1000;
            ProfileData data = JfrProfileParser.parse(file, recordingStart, duration, new JfrProfileParser.Options(
                    cpuUs, props.nativeSamplingInterval().toNanos() / 1000.0,
                    props.asyncProfilerInterval().toNanos() / 1000.0, props.maxSamples()));
            record.ready(data);
            log.info("Profile {} of PID {}: {} samples, {} events, {} threads", record.id, record.pid,
                    data.sampleCount(), data.events().size(), data.threads().size());
        } catch (InspectorException e) {
            record.fail(e.getMessage());
        } catch (IOException | UncheckedIOException e) {
            record.fail("I/O error: " + e.getMessage());
            log.warn("Profile {} failed", record.id, e);
        } catch (RuntimeException e) {
            record.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Profile {} failed", record.id, e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.debug("Could not delete {}: {}", file, e.toString());
                }
            }
        }
    }

    public List<ProfileSummaryDto> list() {
        synchronized (profiles) {
            List<ProfileSummaryDto> result = new ArrayList<>();
            profiles.values().forEach(p -> result.add(p.toSummary()));
            result.sort(Comparator.comparing(ProfileSummaryDto::startedAt).reversed());
            return result;
        }
    }

    public ProfileSummaryDto get(String id) {
        return record(id).toSummary();
    }

    /** Cancels a running profile or forgets a finished one. */
    public void delete(String id) {
        ProfileRecord record;
        synchronized (profiles) {
            record = profiles.remove(id);
        }
        if (record == null) {
            throw notFound(id);
        }
        if (record.task() != null && !record.finished()) {
            record.task().cancel(true);
        }
    }

    // ---------------------------------------------------------------- queries

    public TimelineDto timeline(String id, Integer buckets) {
        return TimelineBuilder.build(id, ready(id), buckets == null ? props.timelineBuckets() : buckets,
                props.timelineThreads());
    }

    public FlameGraphDto flame(String id, ProfileMetric metric, Double fromMs, Double toMs, Integer thread) {
        ProfileData data = ready(id);
        Window window = window(data, fromMs, toMs, thread);
        CallTreeEngine.Node root = CallTreeEngine.flameTree(data, metric, window.from, window.to, window.thread);
        CallTreeEngine.Budget budget = new CallTreeEngine.Budget(root.total, props.flameMinShare(),
                props.flameMaxNodes());
        return new FlameGraphDto(id, metric, CallTreeEngine.unit(metric), window.from, window.to,
                window.thread < 0 ? null : data.threads().get(window.thread), root.total, root.totalSamples,
                CallTreeEngine.toDto(data, root, root.total, metric != ProfileMetric.ALLOC, budget), budget.pruned);
    }

    public DiffFlameGraphDto diff(String baselineId, String targetId, ProfileMetric metric) {
        if (baselineId.equals(targetId)) {
            throw new OperationRejectedException(400, "SAME_PROFILE", "Choose two different profiles");
        }
        ProfileData baseline = ready(baselineId);
        ProfileData target = ready(targetId);
        CallTreeEngine.Node baseRoot = CallTreeEngine.flameTree(baseline, metric, 0, Double.MAX_VALUE, -1);
        CallTreeEngine.Node targetRoot = CallTreeEngine.flameTree(target, metric, 0, Double.MAX_VALUE, -1);
        return new DiffFlameGraphDto(baselineId, targetId, metric, CallTreeEngine.unit(metric), baseRoot.total,
                targetRoot.total, CallTreeEngine.diff(baseline, baseRoot, target, targetRoot, props.flameMinShare(),
                props.flameMaxNodes()));
    }

    public List<HotspotDto> hotspots(String id, ProfileMetric metric, Double fromMs, Double toMs, Integer thread,
                                     Integer limit) {
        ProfileData data = ready(id);
        Window window = window(data, fromMs, toMs, thread);
        return CallTreeEngine.hotspots(data, metric, window.from, window.to, window.thread, limit == null ? 100 : limit);
    }

    public ButterflyDto butterfly(String id, String className, String methodName, ProfileMetric metric, Double fromMs,
                                  Double toMs, Integer thread) {
        ProfileData data = ready(id);
        Window window = window(data, fromMs, toMs, thread);
        return CallTreeEngine.butterfly(id, data, metric, className, methodName, window.from, window.to,
                window.thread, props.flameMinShare(), props.flameMaxNodes());
    }

    public SubsystemBreakdownDto subsystems(String id, ProfileMetric metric, Double fromMs, Double toMs,
                                            Integer thread) {
        ProfileData data = ready(id);
        Window window = window(data, fromMs, toMs, thread);
        return SubsystemBreakdownBuilder.build(id, data, metric, window.from, window.to, window.thread);
    }

    // ---------------------------------------------------------------- helpers

    private record Window(double from, double to, int thread) {
    }

    private static Window window(ProfileData data, Double fromMs, Double toMs, Integer thread) {
        double from = fromMs == null ? 0 : Math.max(0, fromMs);
        double to = toMs == null ? Double.MAX_VALUE : toMs;
        if (to <= from) {
            throw new OperationRejectedException(400, "EMPTY_WINDOW", "The time window is empty (to <= from)");
        }
        int t = thread == null ? -1 : thread;
        if (t >= data.threads().size()) {
            throw new OperationRejectedException(400, "NO_SUCH_THREAD", "Thread " + t + " is not in the profile");
        }
        return new Window(from, to, t);
    }

    private ProfileRecord record(String id) {
        synchronized (profiles) {
            ProfileRecord record = profiles.get(id);
            if (record == null) {
                throw notFound(id);
            }
            return record;
        }
    }

    private ProfileData ready(String id) {
        ProfileRecord record = record(id);
        ProfileData data = record.data();
        if (data == null) {
            throw new OperationRejectedException(409, "PROFILE_NOT_READY", "Profile " + id + " is " + record.status());
        }
        return data;
    }

    private void evict() {
        while (profiles.size() > Math.max(1, props.maxProfiles())) {
            String oldest = profiles.values().stream().filter(ProfileRecord::finished)
                    .min(Comparator.comparing(p -> p.startedAt)).map(p -> p.id).orElse(null);
            if (oldest == null) {
                return;
            }
            profiles.remove(oldest);
        }
    }

    private JvmProcessDto describe(long pid) {
        try {
            return discovery.describe(pid);
        } catch (InspectorException e) {
            return null;
        }
    }

    private static InspectorException notFound(String id) {
        return new InspectorException(404, "PROFILE_NOT_FOUND", "Profile " + id + " does not exist");
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
