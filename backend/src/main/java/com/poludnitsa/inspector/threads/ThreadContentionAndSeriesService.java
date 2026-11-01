package com.poludnitsa.inspector.threads;

import com.poludnitsa.inspector.api.dto.DeadlockDto;
import com.poludnitsa.inspector.api.dto.DiagnosticSource;
import com.poludnitsa.inspector.api.dto.JobStatus;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.JvmThreadState;
import com.poludnitsa.inspector.api.dto.LiveThreadDto;
import com.poludnitsa.inspector.api.dto.MonitorContentionDto;
import com.poludnitsa.inspector.api.dto.ThreadLiveDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesAnalysisDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesRequestDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesSummaryDto;
import com.poludnitsa.inspector.common.DaemonThreadFactory;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.common.TargetTerminatedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Thread diagnostics of a target, through its {@code ThreadMXBean} over JMX only.
 *
 * <ul>
 *   <li><b>Real-time contention and deadlocks</b>: while the UI watches a target, a sampler enables contention and
 *   CPU-time monitoring on it, takes {@code dumpAllThreads} every {@code inspector.threads.sample-interval} and
 *   credits the growth of each thread's blocked time to the lock class and line it blocks on
 *   ({@link ContentionAggregator}); deadlocks come from {@code findDeadlockedThreads} /
 *   {@code findMonitorDeadlockedThreads}. The monitoring flags are restored when nobody uses them any more.</li>
 *   <li><b>Series of thread dumps</b> (live, or a jstack / jcmd text): compared thread by thread by
 *   {@link ThreadDumpSeriesAnalyzer} for stuck threads, stack clusters and deadlocks.</li>
 * </ul>
 */
@Service
public class ThreadContentionAndSeriesService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ThreadContentionAndSeriesService.class);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final long MAX_UPLOAD_CHARS = 256L << 20;

    private final JmxConnectionManager connections;
    private final JvmDiscoveryService discovery;
    private final InspectorProperties.Threads props;
    private final List<String> poolPrefixes;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
            new DaemonThreadFactory("thread-sampler"));
    private final ExecutorService jobs = Executors.newCachedThreadPool(new DaemonThreadFactory("thread-series"));
    private final Map<Long, LiveMonitor> monitors = new ConcurrentHashMap<>();
    /** last picture of a sampler that stopped because the UI left the screen; still goes into reports */
    private final Map<Long, ThreadLiveDto> lastLive = new ConcurrentHashMap<>();
    private final Map<String, SeriesRecord> series = new LinkedHashMap<>();
    private final Map<Long, Integer> flagUsers = new HashMap<>();
    private final Map<Long, ThreadMx.Flags> originalFlags = new HashMap<>();

    public ThreadContentionAndSeriesService(JmxConnectionManager connections, JvmDiscoveryService discovery,
                                            InspectorProperties properties) {
        this.connections = connections;
        this.discovery = discovery;
        this.props = properties.threads();
        this.poolPrefixes = properties.inspections().poolThreadPrefixes();
    }

    // ---------------------------------------------------------------- real time

    /** The live picture of a target; starts its sampler on the first call (and keeps it while the UI polls). */
    public ThreadLiveDto live(long pid) {
        rejectSelf(pid);
        LiveMonitor monitor = monitors.get(pid);
        if (monitor == null) {
            LiveMonitor created = new LiveMonitor(pid);
            monitor = monitors.putIfAbsent(pid, created);
            if (monitor == null) {
                monitor = created;
                try {
                    created.start();
                } catch (RuntimeException e) {
                    monitors.remove(pid, created);
                    created.stop();
                    throw e;
                }
            }
        }
        return monitor.snapshot();
    }

    /**
     * The live snapshot when a sampler runs for {@code pid}, else the last one of a sampler stopped for idleness,
     * without starting one (reports).
     */
    public ThreadLiveDto liveIfRunning(long pid) {
        LiveMonitor monitor = monitors.get(pid);
        return monitor == null ? lastLive.get(pid) : monitor.snapshot();
    }

    /** Stops the sampler of a target and forgets its contention statistics. */
    public void resetLive(long pid) {
        lastLive.remove(pid);
        LiveMonitor monitor = monitors.remove(pid);
        if (monitor != null) {
            monitor.stop();
        }
    }

    private final class LiveMonitor {
        final long pid;
        final Instant since = Instant.now();
        final ContentionAggregator aggregator = new ContentionAggregator(poolPrefixes,
                props.sampleInterval().toMillis());
        volatile long lastAccess = System.nanoTime();
        ScheduledFuture<?> task;
        List<ThreadEntry> latest = List.of();
        Instant latestAt;
        long samples;
        List<DeadlockDto> deadlocks = List.of();
        long lastSlowCheck;
        Map<Long, Double> cpu = Map.of();
        Map<Long, Double> cpuPercent = Map.of();
        long cpuAt;
        int threadCount;
        int daemonCount;
        int peakCount;
        ThreadMx.Flags flags;
        String error;
        boolean released;

        LiveMonitor(long pid) {
            this.pid = pid;
        }

        void start() {
            acquireFlags(pid);
            tick();
            if (error != null && samples == 0) {
                throw new OperationRejectedException(409, "THREAD_SAMPLING_FAILED", error);
            }
            long period = Math.max(50, props.sampleInterval().toMillis());
            task = scheduler.scheduleWithFixedDelay(this::tickSafely, period, period, TimeUnit.MILLISECONDS);
        }

        void stop() {
            if (task != null) {
                task.cancel(false);
            }
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
            }
            releaseFlags(pid);
        }

        void tickSafely() {
            if (System.nanoTime() - lastAccess > props.liveIdleTimeout().toNanos()) {
                log.debug("Live thread sampler of PID {} idle, stopping", pid);
                if (samples > 0) {
                    lastLive.put(pid, snapshot());
                }
                monitors.remove(pid, this);
                stop();
                return;
            }
            try {
                tick();
            } catch (RuntimeException e) {
                log.debug("Thread sample of PID {} failed: {}", pid, e.toString());
            }
        }

        void tick() {
            try {
                boolean slow = System.nanoTime() - lastSlowCheck >= props.deadlockCheckInterval().toNanos()
                        || samples == 0;
                connections.call(pid, (connection, metadata) -> {
                    List<ThreadEntry> dump = ThreadMx.dumpAll(connection, false, props.sampleDepth());
                    Instant now = Instant.now();
                    List<DeadlockDto> found = null;
                    Map<Long, Double> times = null;
                    int count = 0;
                    int daemons = 0;
                    int peak = 0;
                    ThreadMx.Flags current = null;
                    if (slow) {
                        long[] ids = ThreadMx.deadlocked(connection);
                        found = ids.length == 0 ? List.of()
                                : DeadlockDetector.cycles(ThreadMx.info(connection, ids), now);
                        long[] all = dump.stream().mapToLong(ThreadEntry::id).toArray();
                        times = ThreadMx.cpuMs(connection, all);
                        count = ThreadMx.intAttribute(connection, "ThreadCount");
                        daemons = ThreadMx.intAttribute(connection, "DaemonThreadCount");
                        peak = ThreadMx.intAttribute(connection, "PeakThreadCount");
                        current = ThreadMx.flags(connection);
                    }
                    record(dump, now, found, times, count, daemons, peak, current);
                    return null;
                });
                error = null;
            } catch (TargetTerminatedException e) {
                error = e.getMessage();
                lastLive.remove(pid);
                monitors.remove(pid, this);
                stop();
            } catch (InspectorException e) {
                error = e.getMessage();
            }
        }

        synchronized void record(List<ThreadEntry> dump, Instant now, List<DeadlockDto> found, Map<Long, Double> times,
                                 int count, int daemons, int peak, ThreadMx.Flags current) {
            aggregator.sample(dump);
            latest = dump;
            latestAt = now;
            samples++;
            if (found != null) {
                lastSlowCheck = System.nanoTime();
                deadlocks = found;
                threadCount = count;
                daemonCount = daemons;
                peakCount = peak;
                flags = current;
                long at = System.nanoTime();
                if (!cpu.isEmpty() && cpuAt > 0) {
                    double elapsed = (at - cpuAt) / 1e6;
                    Map<Long, Double> percent = new HashMap<>();
                    times.forEach((id, ms) -> {
                        Double before = cpu.get(id);
                        if (before != null && elapsed > 0) {
                            percent.put(id, Math.max(0, (ms - before) / elapsed * 100));
                        }
                    });
                    cpuPercent = percent;
                }
                cpu = times;
                cpuAt = at;
            }
        }

        synchronized ThreadLiveDto snapshot() {
            lastAccess = System.nanoTime();
            if (samples == 0) {
                throw new OperationRejectedException(409, "THREAD_SAMPLING_FAILED",
                        error != null ? error : "No thread sample of PID " + pid + " yet");
            }
            Map<JvmThreadState, Integer> states = new EnumMap<>(JvmThreadState.class);
            List<LiveThreadDto> threads = new ArrayList<>();
            for (ThreadEntry thread : latest) {
                states.merge(thread.state(), 1, Integer::sum);
                threads.add(new LiveThreadDto(thread.id(), thread.name(), thread.state(), thread.daemon(),
                        Frames.serverThread(thread.name(), poolPrefixes), cpu.get(thread.id()),
                        cpuPercent.get(thread.id()), thread.blockedCount(),
                        thread.blockedTimeMs() >= 0 ? thread.blockedTimeMs() : null, thread.waitedCount(),
                        thread.waitedTimeMs() >= 0 ? thread.waitedTimeMs() : null, thread.lockName(),
                        thread.lockOwnerName(), Frames.top(thread.frames())));
            }
            threads.sort(Comparator.comparing((LiveThreadDto t) -> t.blockedTimeMs() == null ? 0 : t.blockedTimeMs())
                    .thenComparing(t -> t.cpuPercent() == null ? 0 : t.cpuPercent())
                    .thenComparing(t -> t.cpuMs() == null ? 0 : t.cpuMs()).reversed());
            List<MonitorContentionDto> contention = aggregator.result(50);
            return new ThreadLiveDto(pid, latestAt, since, samples, props.sampleInterval().toMillis(),
                    threadCount > 0 ? threadCount : latest.size(), daemonCount, peakCount, states,
                    flags != null && (flags.contention() || flags.contentionSupported() && aggregator.timed()),
                    flags != null && (flags.cpu() || !cpu.isEmpty()), deadlocks, contention,
                    threads.subList(0, Math.min(props.liveThreads(), threads.size())));
        }
    }

    // ---------------------------------------------------------------- series

    public ThreadSeriesSummaryDto startSeries(ThreadSeriesRequestDto request) {
        long pid = request.pid();
        rejectSelf(pid);
        JvmProcessDto process = describe(pid);
        SeriesRecord record;
        synchronized (series) {
            boolean running = series.values().stream()
                    .anyMatch(s -> s.pid != null && s.pid == pid && s.status == JobStatus.RUNNING);
            if (running) {
                throw new OperationRejectedException(409, "SERIES_IN_PROGRESS",
                        "A thread dump series of PID " + pid + " is being taken; wait for it or cancel it");
            }
            record = new SeriesRecord(newId(), DiagnosticSource.LIVE, pid,
                    process == null ? "PID " + pid : process.displayName(), request.dumps(),
                    (long) request.intervalMs());
            series.put(record.id, record);
            evict();
        }
        SeriesRecord started = record;
        record.task = jobs.submit(() -> run(started, request.dumps(), request.intervalMs()));
        return record.summary();
    }

    private void run(SeriesRecord record, int count, long intervalMs) {
        long pid = record.pid;
        acquireFlags(pid);
        try {
            ContentionAggregator aggregator = new ContentionAggregator(poolPrefixes,
                    Math.min(intervalMs, props.sampleInterval().toMillis()));
            List<ThreadDump> dumps = new ArrayList<>();
            long sampleMs = Math.max(50, Math.min(intervalMs, props.sampleInterval().toMillis()));
            for (int d = 0; d < count; d++) {
                long started = System.nanoTime();
                record.progress("Thread dump " + (d + 1) + " / " + count);
                int index = d;
                ThreadDump dump = connections.call(pid, (connection, metadata) -> {
                    List<ThreadEntry> threads = ThreadMx.dumpAll(connection, true, 0);
                    long[] ids = threads.stream().mapToLong(ThreadEntry::id).toArray();
                    Map<Long, Double> cpu = ThreadMx.cpuMs(connection, ids);
                    List<ThreadEntry> withCpu = new ArrayList<>(threads.size());
                    threads.forEach(t -> withCpu.add(t.withCpu(cpu.get(t.id()))));
                    return new ThreadDump(index, Instant.now(), null, withCpu);
                });
                dumps.add(dump);
                aggregator.sample(dump.threads());
                record.taken(dumps.size());
                if (d == count - 1) {
                    break;
                }
                // contention samples between the dumps
                long next = started + TimeUnit.MILLISECONDS.toNanos(intervalMs);
                while (true) {
                    long remaining = next - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    Thread.sleep(Math.max(1, Math.min(sampleMs, TimeUnit.NANOSECONDS.toMillis(remaining))));
                    if (next - System.nanoTime() > TimeUnit.MILLISECONDS.toNanos(sampleMs / 2)) {
                        aggregator.sample(connections.call(pid, (connection, metadata) ->
                                ThreadMx.dumpAll(connection, false, props.sampleDepth())));
                    }
                }
            }
            record.progress("Analysing " + dumps.size() + " dumps");
            ThreadSeriesAnalysisDto analysis = ThreadDumpSeriesAnalyzer.analyze(record.summary(), dumps,
                    aggregator.result(50), aggregator.timed(), settings());
            record.ready(analysis);
            log.info("Thread dump series {} of PID {}: {} dumps, {} stuck, {} deadlocks", record.id, pid,
                    dumps.size(), analysis.stuck().size(), analysis.deadlocks().size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record.fail("Cancelled");
        } catch (InspectorException e) {
            record.fail(e.getMessage());
        } catch (RuntimeException e) {
            record.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Thread dump series {} failed", record.id, e);
        } finally {
            releaseFlags(pid);
        }
    }

    /** A jstack / jcmd Thread.print / kill -3 text with one or more dumps. */
    public ThreadSeriesSummaryDto upload(String fileName, InputStream input) {
        List<ThreadDump> dumps;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new LimitedInputStream(input,
                MAX_UPLOAD_CHARS), StandardCharsets.UTF_8), 1 << 16)) {
            dumps = JstackParser.parse(reader);
        } catch (LimitedInputStream.LimitExceededException e) {
            throw new OperationRejectedException(413, "THREAD_DUMP_TOO_LARGE",
                    "Thread dump files above " + (MAX_UPLOAD_CHARS >> 20) + " MiB are not accepted");
        } catch (IOException e) {
            throw new InspectorException(400, "THREAD_DUMP_UNREADABLE", "Cannot read the file: " + e.getMessage(), e);
        }
        if (dumps.isEmpty()) {
            throw new OperationRejectedException(400, "THREAD_DUMP_INVALID",
                    "No thread dump found: expected jstack / jcmd Thread.print output (\"name\" … java.lang.Thread.State:)");
        }
        String name = fileName == null || fileName.isBlank() ? "thread dump" : fileName;
        SeriesRecord record = new SeriesRecord(newId(), DiagnosticSource.FILE, null, name, dumps.size(),
                ThreadDumpSeriesAnalyzer.medianIntervalMs(dumps));
        record.taken(dumps.size());
        record.ready(ThreadDumpSeriesAnalyzer.analyze(record.summary(), dumps, null, false, settings()));
        synchronized (series) {
            series.put(record.id, record);
            evict();
        }
        return record.summary();
    }

    public List<ThreadSeriesSummaryDto> list() {
        synchronized (series) {
            List<ThreadSeriesSummaryDto> result = new ArrayList<>();
            series.values().forEach(s -> result.add(s.summary()));
            result.sort(Comparator.comparing(ThreadSeriesSummaryDto::startedAt).reversed());
            return result;
        }
    }

    public ThreadSeriesSummaryDto summary(String id) {
        return record(id).summary();
    }

    public ThreadSeriesAnalysisDto analysis(String id) {
        SeriesRecord record = record(id);
        ThreadSeriesAnalysisDto analysis = record.analysis;
        if (analysis == null) {
            throw new OperationRejectedException(409, "SERIES_NOT_READY",
                    "Series " + id + " is " + record.status.name().toLowerCase());
        }
        return analysis;
    }

    /** Finished analyses, newest first, of {@code pid} (and every file) or of all when {@code pid} is null. */
    public List<ThreadSeriesAnalysisDto> readyAnalyses(Long pid) {
        synchronized (series) {
            List<ThreadSeriesAnalysisDto> result = new ArrayList<>();
            for (SeriesRecord record : series.values()) {
                if (record.analysis != null && (pid == null || record.pid == null || record.pid.equals(pid))) {
                    result.add(record.analysis);
                }
            }
            result.sort(Comparator.comparing((ThreadSeriesAnalysisDto a) -> a.summary().startedAt()).reversed());
            return result;
        }
    }

    /** Cancels a running series or forgets a finished one. */
    public void delete(String id) {
        SeriesRecord record;
        synchronized (series) {
            record = series.remove(id);
        }
        if (record == null) {
            throw notFound(id);
        }
        if (record.task != null && record.status == JobStatus.RUNNING) {
            record.task.cancel(true);
        }
    }

    private SeriesRecord record(String id) {
        synchronized (series) {
            SeriesRecord record = series.get(id);
            if (record == null) {
                throw notFound(id);
            }
            return record;
        }
    }

    private void evict() {
        while (series.size() > props.maxSeries()) {
            String oldest = series.values().stream().filter(s -> s.status != JobStatus.RUNNING)
                    .min(Comparator.comparing(s -> s.startedAt)).map(s -> s.id).orElse(null);
            if (oldest == null) {
                return;
            }
            series.remove(oldest);
        }
    }

    private ThreadDumpSeriesAnalyzer.Settings settings() {
        return new ThreadDumpSeriesAnalyzer.Settings(props.stuckMinDumps(), props.maxStackDepth(), poolPrefixes,
                props.maxClusters());
    }

    private static final class SeriesRecord {
        final String id;
        final DiagnosticSource source;
        final Long pid;
        final String name;
        final Instant startedAt = Instant.now();
        final int requested;
        final Long intervalMs;
        volatile JobStatus status = JobStatus.RUNNING;
        volatile String progress = "Starting";
        volatile String error;
        volatile int taken;
        volatile ThreadSeriesAnalysisDto analysis;
        Future<?> task;

        SeriesRecord(String id, DiagnosticSource source, Long pid, String name, int requested, Long intervalMs) {
            this.id = id;
            this.source = source;
            this.pid = pid;
            this.name = name;
            this.requested = requested;
            this.intervalMs = intervalMs;
        }

        void progress(String message) {
            progress = message;
        }

        void taken(int count) {
            taken = count;
        }

        void ready(ThreadSeriesAnalysisDto result) {
            progress = null;
            status = JobStatus.READY;
            // the counts come from the analysis, the job fields from this record
            ThreadSeriesSummaryDto s = result.summary();
            analysis = new ThreadSeriesAnalysisDto(new ThreadSeriesSummaryDto(id, source, pid, name, JobStatus.READY,
                    startedAt, requested, taken, intervalMs, null, null, s.threadCount(), s.stuckCount(),
                    s.deadlockCount(), s.clusterCount()), result.dumps(), result.rows(), result.stacks(),
                    result.stuck(), result.clusters(), result.deadlocks(), result.contention(),
                    result.contentionTimed());
        }

        void fail(String message) {
            error = message;
            progress = null;
            status = JobStatus.FAILED;
        }

        ThreadSeriesSummaryDto summary() {
            ThreadSeriesAnalysisDto a = analysis;
            if (a != null) {
                return a.summary();
            }
            return new ThreadSeriesSummaryDto(id, source, pid, name, status, startedAt, requested, taken, intervalMs,
                    progress, error, 0, 0, 0, 0);
        }
    }

    // ---------------------------------------------------------------- monitoring flags

    /** Enables contention / CPU monitoring for the first user of a target and remembers what it was. */
    private void acquireFlags(long pid) {
        synchronized (flagUsers) {
            int users = flagUsers.merge(pid, 1, Integer::sum);
            if (users > 1) {
                return;
            }
            try {
                originalFlags.put(pid, connections.call(pid, (connection, metadata) -> ThreadMx.enable(connection)));
            } catch (InspectorException e) {
                log.info("Cannot enable thread contention monitoring on PID {}: {}", pid, e.getMessage());
            }
        }
    }

    private void releaseFlags(long pid) {
        ThreadMx.Flags original;
        synchronized (flagUsers) {
            Integer users = flagUsers.get(pid);
            if (users == null) {
                return;
            }
            if (users > 1) {
                flagUsers.put(pid, users - 1);
                return;
            }
            flagUsers.remove(pid);
            original = originalFlags.remove(pid);
        }
        if (original != null) {
            try {
                connections.call(pid, (connection, metadata) -> {
                    ThreadMx.restore(connection, original);
                    return null;
                });
            } catch (RuntimeException e) {
                log.debug("Could not restore thread monitoring flags of PID {}: {}", pid, e.toString());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private void rejectSelf(long pid) {
        if (pid == connections.selfPid()) {
            throw new OperationRejectedException(400, "SELF_MONITORING_FORBIDDEN",
                    "PID " + pid + " is the inspector itself; it never inspects its own JVM");
        }
    }

    private JvmProcessDto describe(long pid) {
        try {
            return discovery.describe(pid);
        } catch (InspectorException e) {
            return null;
        }
    }

    private static String newId() {
        return "thr-" + ID_TIME.format(Instant.now()) + "-"
                + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
    }

    private static InspectorException notFound(String id) {
        return new InspectorException(404, "SERIES_NOT_FOUND", "Thread dump series " + id + " does not exist");
    }

    @Override
    public void destroy() {
        monitors.values().forEach(LiveMonitor::stop);
        monitors.clear();
        scheduler.shutdownNow();
        jobs.shutdownNow();
    }

    /** Fails a read beyond {@code limit} bytes, so an endless upload cannot fill the heap. */
    static final class LimitedInputStream extends java.io.FilterInputStream {
        static final class LimitExceededException extends IOException {
            LimitExceededException() {
                super("limit exceeded");
            }
        }

        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && --remaining < 0) {
                throw new LimitExceededException();
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0 && (remaining -= n) < 0) {
                throw new LimitExceededException();
            }
            return n;
        }
    }
}
