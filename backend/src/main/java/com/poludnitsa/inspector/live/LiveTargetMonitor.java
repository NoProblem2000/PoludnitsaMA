package com.poludnitsa.inspector.live;

import com.poludnitsa.inspector.api.dto.ConnectionState;
import com.poludnitsa.inspector.api.dto.GcCollectorMetricsDto;
import com.poludnitsa.inspector.api.dto.JfrAllocationStatsDto;
import com.poludnitsa.inspector.api.dto.LiveMetricsPayloadDto;
import com.poludnitsa.inspector.api.dto.MemoryPoolDto;
import com.poludnitsa.inspector.api.dto.MemoryUsageDto;
import com.poludnitsa.inspector.api.dto.MetricsPointDto;
import com.poludnitsa.inspector.api.dto.TargetStatusDto;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.common.TargetTerminatedException;
import com.poludnitsa.inspector.common.TargetUnavailableException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.jfr.JfrAllocationSampler;
import com.poludnitsa.inspector.jmx.JmxAttributes;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.jmx.MBeanNames;
import com.poludnitsa.inspector.jmx.TargetMetadata;
import java.io.IOException;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.JMException;
import javax.management.MBeanServerConnection;
import javax.management.RuntimeMBeanException;
import javax.management.openmbean.CompositeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples one target JVM: memory, pools and collectors every {@code sampleInterval}, the leak radar histogram every
 * {@code histogramInterval} on a separate executor (a slow heap walk never delays the 1 s samples).
 *
 * <p>The sampling task runs with a fixed delay, so at most one sample per target is in flight.
 */
final class LiveTargetMonitor {

    private static final Logger log = LoggerFactory.getLogger(LiveTargetMonitor.class);
    private static final String[] MEMORY_ATTRIBUTES = {"HeapMemoryUsage", "NonHeapMemoryUsage"};
    private static final String[] POOL_ATTRIBUTES = {"Usage", "CollectionUsage"};
    private static final String[] COLLECTOR_ATTRIBUTES = {"CollectionCount", "CollectionTime"};

    private final long pid;
    private final JmxConnectionManager connections;
    private final InspectorProperties.Live props;
    private final ExecutorService histogramExecutor;
    private final LongConsumer onTerminated;
    private final LongConsumer onIdle;
    private final LeakRadar radar;
    private final GcOverheadTracker overhead = new GcOverheadTracker(60_000);
    private final Set<LiveMetricsListener> listeners = new CopyOnWriteArraySet<>();
    private final AtomicBoolean histogramInFlight = new AtomicBoolean();
    private final ArrayDeque<MetricsPointDto> history = new ArrayDeque<>();

    // Touched by the sampling task only.
    private final Map<String, long[]> previousCollectorCounters = new HashMap<>();
    private final Map<String, Long> lastPauseMs = new HashMap<>();
    private long previousAllocatedBytes = -1;
    private long previousSampleAt;
    private long idleSince;

    private volatile boolean allocationCounterSupported = true;
    private volatile boolean histogramsUnavailable;
    private volatile long histogramIntervalMs;
    private volatile long lastHistogramStartedAt;
    private volatile Long lastHistogramAt;
    private volatile Long lastHistogramDurationMs;
    private volatile LiveMetricsPayloadDto latest;
    private volatile TargetStatusDto status;
    private volatile JfrAllocationSampler jfr;
    private volatile boolean selected;
    private volatile boolean stopped;
    private volatile ScheduledFuture<?> future;

    LiveTargetMonitor(long pid, JmxConnectionManager connections, InspectorProperties.Live props,
                      ExecutorService histogramExecutor, LongConsumer onTerminated, LongConsumer onIdle) {
        this.pid = pid;
        this.connections = connections;
        this.props = props;
        this.histogramExecutor = histogramExecutor;
        this.onTerminated = onTerminated;
        this.onIdle = onIdle;
        this.radar = new LeakRadar(LeakRadar.Settings.from(props));
        this.histogramIntervalMs = props.histogramInterval().toMillis();
        this.status = new TargetStatusDto(pid, ConnectionState.CONNECTING, null, System.currentTimeMillis());
    }

    void start(ScheduledExecutorService scheduler) {
        future = scheduler.scheduleWithFixedDelay(this::tick, 0, props.sampleInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    void stop() {
        stopped = true;
        ScheduledFuture<?> scheduled = future;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        JfrAllocationSampler sampler = jfr;
        jfr = null;
        if (sampler != null) {
            sampler.close();
        }
    }

    long pid() {
        return pid;
    }

    LiveMetricsPayloadDto latest() {
        return latest;
    }

    TargetStatusDto status() {
        return status;
    }

    /** Compact history, oldest first, at most {@code historySize} points. */
    List<MetricsPointDto> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    private void remember(MetricsPointDto point) {
        synchronized (history) {
            history.addLast(point);
            while (history.size() > Math.max(1, props.historySize())) {
                history.removeFirst();
            }
        }
    }

    void setSelected(boolean selected) {
        this.selected = selected;
    }

    JfrAllocationSampler jfr() {
        return jfr;
    }

    void setJfr(JfrAllocationSampler sampler) {
        this.jfr = sampler;
    }

    JfrAllocationSampler detachJfr() {
        JfrAllocationSampler sampler = jfr;
        jfr = null;
        return sampler;
    }

    void addListener(LiveMetricsListener listener) {
        listeners.add(listener);
        deliverStatus(listener, status);
        LiveMetricsPayloadDto current = latest;
        if (current != null) {
            deliverMetrics(listener, current);
        }
    }

    void removeListener(LiveMetricsListener listener) {
        listeners.remove(listener);
    }

    void publishStatus(ConnectionState state, String message) {
        TargetStatusDto previous = status;
        if (previous != null && previous.state() == state && Objects.equals(previous.message(), message)) {
            return;
        }
        TargetStatusDto next = new TargetStatusDto(pid, state, message, System.currentTimeMillis());
        status = next;
        for (LiveMetricsListener listener : listeners) {
            deliverStatus(listener, next);
        }
    }

    /** On-demand histogram; also feeds the radar. Rate limited by {@code histogramMinGap}. */
    HistogramFetcher.Result histogramNow() {
        long now = System.currentTimeMillis();
        long gap = props.histogramMinGap().toMillis();
        if (now - lastHistogramStartedAt < gap) {
            throw new OperationRejectedException(429, "HISTOGRAM_RATE_LIMITED",
                    "A class histogram was taken less than " + gap + " ms ago; every histogram is a safepoint pause");
        }
        if (!histogramInFlight.compareAndSet(false, true)) {
            throw new OperationRejectedException(429, "HISTOGRAM_IN_PROGRESS",
                    "A class histogram of PID " + pid + " is running");
        }
        try {
            lastHistogramStartedAt = now;
            return runHistogram();
        } finally {
            histogramInFlight.set(false);
        }
    }

    void tick() {
        if (stopped) {
            return;
        }
        long now = System.currentTimeMillis();
        if (listeners.isEmpty() && !selected) {
            if (idleSince == 0) {
                idleSince = now;
            } else if (now - idleSince >= props.idleGrace().toMillis()) {
                onIdle.accept(pid);
                return;
            }
        } else {
            idleSince = 0;
        }
        try {
            RawSample raw = connections.call(pid, this::readRaw);
            LiveMetricsPayloadDto payload = buildPayload(raw);
            latest = payload;
            remember(MetricsPointDto.of(payload));
            publishStatus(ConnectionState.CONNECTED, null);
            for (LiveMetricsListener listener : listeners) {
                deliverMetrics(listener, payload);
            }
            if (!raw.diagnosticCommandAvailable()) {
                histogramsUnavailable = true;
            }
            maybeScheduleHistogram(now);
        } catch (TargetTerminatedException e) {
            publishStatus(ConnectionState.TERMINATED, e.getMessage());
            onTerminated.accept(pid);
        } catch (TargetUnavailableException e) {
            ConnectionState state = connections.state(pid) == ConnectionState.FAILED
                    ? ConnectionState.FAILED
                    : ConnectionState.RECONNECTING;
            publishStatus(state, connections.lastError(pid).orElse(e.getMessage()));
        } catch (InspectorException e) {
            log.warn("Sampling PID {} failed: {}", pid, e.getMessage());
        } catch (RuntimeException e) {
            // Never let the scheduled task die: an exception would cancel all further samples.
            log.error("Unexpected failure while sampling PID {}", pid, e);
        }
    }

    private RawSample readRaw(MBeanServerConnection connection, TargetMetadata metadata)
            throws IOException, JMException {
        long timestamp = System.currentTimeMillis();
        AttributeList memory = connection.getAttributes(MBeanNames.MEMORY, MEMORY_ATTRIBUTES);

        List<PoolSample> pools = new ArrayList<>(metadata.pools().size());
        for (TargetMetadata.PoolInfo pool : metadata.pools()) {
            AttributeList attributes = connection.getAttributes(pool.objectName(), POOL_ATTRIBUTES);
            pools.add(new PoolSample(pool,
                    JmxAttributes.memoryUsage(JmxAttributes.get(attributes, "Usage")),
                    JmxAttributes.memoryUsage(JmxAttributes.get(attributes, "CollectionUsage"))));
        }

        List<CollectorSample> collectors = new ArrayList<>(metadata.collectors().size());
        for (TargetMetadata.CollectorInfo collector : metadata.collectors()) {
            AttributeList attributes = connection.getAttributes(collector.objectName(), COLLECTOR_ATTRIBUTES);
            long count = JmxAttributes.longValue(JmxAttributes.get(attributes, "CollectionCount"), 0);
            long time = JmxAttributes.longValue(JmxAttributes.get(attributes, "CollectionTime"), 0);
            long[] previous = previousCollectorCounters.get(collector.name());
            if (count > 0 && (previous == null || previous[0] != count)) {
                // LastGcInfo is a large composite; read it only when a collection happened.
                Object info = connection.getAttribute(collector.objectName(), "LastGcInfo");
                if (info instanceof CompositeData data && data.containsKey("duration")) {
                    lastPauseMs.put(collector.name(), JmxAttributes.longValue(data.get("duration"), 0));
                }
            }
            collectors.add(new CollectorSample(collector, Math.max(count, 0), Math.max(time, 0)));
        }

        Long allocated = null;
        if (allocationCounterSupported && metadata.totalAllocatedBytesAvailable()) {
            try {
                long value = JmxAttributes.longValue(
                        connection.getAttribute(MBeanNames.THREADING, "TotalThreadAllocatedBytes"), -1);
                allocated = value >= 0 ? value : null;
            } catch (AttributeNotFoundException | RuntimeMBeanException e) {
                allocationCounterSupported = false;
            }
        }
        return new RawSample(timestamp,
                JmxAttributes.memoryUsage(JmxAttributes.get(memory, "HeapMemoryUsage")),
                JmxAttributes.memoryUsage(JmxAttributes.get(memory, "NonHeapMemoryUsage")),
                pools, collectors, allocated, metadata.diagnosticCommandAvailable());
    }

    private LiveMetricsPayloadDto buildPayload(RawSample raw) {
        long elapsedMs = previousSampleAt > 0 ? raw.timestamp() - previousSampleAt : 0;

        List<GcCollectorMetricsDto> collectors = new ArrayList<>(raw.collectors().size());
        long stopTheWorldTime = 0;
        long stopTheWorldCount = 0;
        for (CollectorSample sample : raw.collectors()) {
            TargetMetadata.CollectorInfo info = sample.info();
            long[] previous = previousCollectorCounters.put(info.name(), new long[] {sample.count(), sample.timeMs()});
            long countDelta = previous == null ? 0 : Math.max(0, sample.count() - previous[0]);
            long timeDelta = previous == null ? 0 : Math.max(0, sample.timeMs() - previous[1]);
            if (info.stopTheWorld()) {
                stopTheWorldTime += sample.timeMs();
                stopTheWorldCount += sample.count();
            }
            collectors.add(new GcCollectorMetricsDto(info.name(), info.generation(), info.stopTheWorld(),
                    sample.count(), sample.timeMs(), countDelta, timeDelta, lastPauseMs.get(info.name())));
        }
        overhead.add(raw.timestamp(), stopTheWorldTime, stopTheWorldCount);

        Double allocationRate = null;
        if (raw.allocatedBytes() != null && previousAllocatedBytes >= 0 && elapsedMs > 0
                && raw.allocatedBytes() >= previousAllocatedBytes) {
            allocationRate = (raw.allocatedBytes() - previousAllocatedBytes) * 1000.0 / elapsedMs;
        }
        previousAllocatedBytes = raw.allocatedBytes() == null ? -1 : raw.allocatedBytes();
        previousSampleAt = raw.timestamp();

        List<MemoryPoolDto> pools = new ArrayList<>(raw.pools().size());
        for (PoolSample sample : raw.pools()) {
            MemoryUsage usage = sample.usage();
            if (usage == null) {
                continue;
            }
            TargetMetadata.PoolInfo info = sample.info();
            pools.add(new MemoryPoolDto(info.name(), info.type(), info.kind(), usage.getUsed(), usage.getCommitted(),
                    usage.getMax(), sample.collectionUsage() == null ? null : sample.collectionUsage().getUsed()));
        }

        JfrAllocationSampler sampler = jfr;
        JfrAllocationStatsDto jfrStats = sampler == null ? null : sampler.snapshot();
        return new LiveMetricsPayloadDto(
                pid,
                raw.timestamp(),
                toDto(raw.heap()),
                toDto(raw.nonHeap()),
                pools,
                collectors,
                overhead.overheadPercent(),
                overhead.collectionsPerMinute(),
                allocationRate,
                lastHistogramAt,
                lastHistogramDurationMs,
                histogramsUnavailable ? null : histogramIntervalMs,
                radar.suspects(),
                jfrStats);
    }

    private void maybeScheduleHistogram(long now) {
        if (histogramsUnavailable || stopped || now - lastHistogramStartedAt < histogramIntervalMs) {
            return;
        }
        if (!histogramInFlight.compareAndSet(false, true)) {
            return;
        }
        lastHistogramStartedAt = now;
        try {
            histogramExecutor.execute(() -> {
                try {
                    if (!stopped) {
                        runHistogram();
                    }
                } catch (OperationRejectedException e) {
                    if ("DIAGNOSTIC_COMMAND_UNAVAILABLE".equals(e.code())) {
                        histogramsUnavailable = true;
                    }
                    log.info("Leak radar for PID {}: {}", pid, e.getMessage());
                } catch (InspectorException e) {
                    log.debug("Leak radar histogram of PID {} failed: {}", pid, e.getMessage());
                } catch (RuntimeException e) {
                    log.warn("Leak radar histogram of PID {} failed", pid, e);
                } finally {
                    histogramInFlight.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            histogramInFlight.set(false);
        }
    }

    private HistogramFetcher.Result runHistogram() {
        HistogramFetcher.Result result = connections.call(pid, props.histogramTimeout(), HistogramFetcher::fetch);
        radar.ingest(result.histogram(), result.gcContext(), result.heapUsedBytes());
        lastHistogramAt = result.histogram().timestampEpochMs();
        lastHistogramDurationMs = result.durationMs();
        adaptInterval(result.durationMs());
        return result;
    }

    /** Doubles the radar interval while histograms exceed the pause budget, and halves it back once they are cheap. */
    private void adaptInterval(long durationMs) {
        long budget = props.histogramPauseBudget().toMillis();
        long base = props.histogramInterval().toMillis();
        long max = Math.max(base, props.histogramMaxInterval().toMillis());
        long current = histogramIntervalMs;
        long next = current;
        if (durationMs > budget) {
            next = Math.min(max, current * 2);
        } else if (durationMs < budget / 4 && current > base) {
            next = Math.max(base, current / 2);
        }
        if (next != current) {
            histogramIntervalMs = next;
            log.info("Leak radar interval for PID {} is now {} ms (last histogram took {} ms)", pid, next, durationMs);
        }
    }

    private void deliverMetrics(LiveMetricsListener listener, LiveMetricsPayloadDto payload) {
        try {
            listener.onMetrics(payload);
        } catch (RuntimeException e) {
            log.debug("Dropping listener of PID {}: {}", pid, e.toString());
            listeners.remove(listener);
        }
    }

    private void deliverStatus(LiveMetricsListener listener, TargetStatusDto targetStatus) {
        try {
            listener.onStatus(targetStatus);
        } catch (RuntimeException e) {
            log.debug("Dropping listener of PID {}: {}", pid, e.toString());
            listeners.remove(listener);
        }
    }

    private static MemoryUsageDto toDto(MemoryUsage usage) {
        return usage == null
                ? new MemoryUsageDto(-1, -1, -1, -1)
                : new MemoryUsageDto(usage.getInit(), usage.getUsed(), usage.getCommitted(), usage.getMax());
    }

    private record PoolSample(TargetMetadata.PoolInfo info, MemoryUsage usage, MemoryUsage collectionUsage) {
    }

    private record CollectorSample(TargetMetadata.CollectorInfo info, long count, long timeMs) {
    }

    private record RawSample(
            long timestamp,
            MemoryUsage heap,
            MemoryUsage nonHeap,
            List<PoolSample> pools,
            List<CollectorSample> collectors,
            Long allocatedBytes,
            boolean diagnosticCommandAvailable
    ) {
    }
}
