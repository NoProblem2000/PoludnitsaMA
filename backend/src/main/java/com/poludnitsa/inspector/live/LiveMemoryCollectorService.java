package com.poludnitsa.inspector.live;

import com.poludnitsa.inspector.api.dto.ActiveProcessDto;
import com.poludnitsa.inspector.api.dto.ClassHistogramDto;
import com.poludnitsa.inspector.api.dto.ClassHistogramEntryDto;
import com.poludnitsa.inspector.api.dto.ConnectionState;
import com.poludnitsa.inspector.api.dto.GcTriggerResultDto;
import com.poludnitsa.inspector.api.dto.JfrAllocationStatsDto;
import com.poludnitsa.inspector.api.dto.LiveMetricsPayloadDto;
import com.poludnitsa.inspector.api.dto.MetricsPointDto;
import com.poludnitsa.inspector.common.DaemonThreadFactory;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.common.PackageCategorizer;
import com.poludnitsa.inspector.common.TargetNotFoundException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.jfr.JfrAllocationSampler;
import com.poludnitsa.inspector.jmx.JmxAttributes;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.jmx.MBeanNames;
import com.poludnitsa.inspector.jmx.TargetMetadata;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.JMException;
import javax.management.JMRuntimeException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.openmbean.CompositeData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Live monitoring of target JVMs. A target is monitored while it has subscribers or is the selected (active) PID,
 * plus {@code idleGrace} so a page reload does not restart sampling. All data comes from the target's
 * {@code MBeanServerConnection}.
 */
@Service
public class LiveMemoryCollectorService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LiveMemoryCollectorService.class);
    private static final int MAX_HISTOGRAM_ROWS = 10_000;

    private final JmxConnectionManager connections;
    private final InspectorProperties props;
    private final ConcurrentHashMap<Long, LiveTargetMonitor> monitors = new ConcurrentHashMap<>();
    private final AtomicReference<Long> selectedPid = new AtomicReference<>();
    private final ScheduledExecutorService sampler;
    private final ExecutorService histogramExecutor;
    private final ScheduledExecutorService jfrPoller;

    public LiveMemoryCollectorService(JmxConnectionManager connections, InspectorProperties props) {
        this.connections = connections;
        this.props = props;
        this.sampler = Executors.newScheduledThreadPool(Math.max(1, props.live().samplerThreads()),
                new DaemonThreadFactory("live-sampler"));
        this.histogramExecutor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64),
                new DaemonThreadFactory("leak-radar"));
        this.jfrPoller = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("jfr-poll"));
    }

    /** Starts monitoring {@code pid} if needed and registers the listener; it immediately gets status and latest. */
    public Subscription subscribe(long pid, LiveMetricsListener listener) {
        LiveTargetMonitor monitor = monitor(pid);
        monitor.addListener(listener);
        return () -> monitor.removeListener(listener);
    }

    public ActiveProcessDto select(long pid) {
        LiveTargetMonitor monitor = monitor(pid);
        monitor.setSelected(true);
        Long previous = selectedPid.getAndSet(pid);
        if (previous != null && previous != pid) {
            LiveTargetMonitor previousMonitor = monitors.get(previous);
            if (previousMonitor != null) {
                previousMonitor.setSelected(false);
            }
        }
        return new ActiveProcessDto(pid);
    }

    public ActiveProcessDto clearSelection() {
        Long previous = selectedPid.getAndSet(null);
        if (previous != null) {
            LiveTargetMonitor monitor = monitors.get(previous);
            if (monitor != null) {
                monitor.setSelected(false);
            }
        }
        return new ActiveProcessDto(null);
    }

    public ActiveProcessDto activeProcess() {
        return new ActiveProcessDto(selectedPid.get());
    }

    public Optional<LiveMetricsPayloadDto> latest(long pid) {
        LiveTargetMonitor monitor = monitors.get(pid);
        return monitor == null ? Optional.empty() : Optional.ofNullable(monitor.latest());
    }

    /** Compact metric history of a monitored PID, oldest first; empty when not monitored. */
    public List<MetricsPointDto> history(long pid) {
        LiveTargetMonitor monitor = monitors.get(pid);
        return monitor == null ? List.of() : monitor.history();
    }

    /** Full {@code GC.class_histogram -all} result (no forced Full GC); the sample also feeds the radar. */
    public HistogramFetcher.Result captureHistogram(long pid) {
        return monitor(pid).histogramNow();
    }

    /** Takes a {@code GC.class_histogram -all} right now (no forced Full GC); the sample also feeds the radar. */
    public ClassHistogramDto histogram(long pid, int limit) {
        HistogramFetcher.Result result = captureHistogram(pid);
        ClassHistogram histogram = result.histogram();
        int rows = Math.max(1, Math.min(limit, MAX_HISTOGRAM_ROWS));
        List<ClassHistogramEntryDto> entries = new ArrayList<>(Math.min(rows, histogram.entries().size()));
        for (HistogramEntry entry : histogram.entries()) {
            if (entries.size() == rows) {
                break;
            }
            entries.add(new ClassHistogramEntryDto(entries.size() + 1, entry.className(),
                    PackageCategorizer.categorize(entry.className()), entry.instances(), entry.bytes(),
                    entry.module(), entry.definitionCount()));
        }
        return new ClassHistogramDto(pid, histogram.timestampEpochMs(), result.durationMs(),
                histogram.totalInstances(), histogram.totalBytes(), histogram.entries().size(), entries);
    }

    /** {@code MemoryMXBean.gc()} on the target. The caller must have confirmed the warning. */
    public GcTriggerResultDto triggerGc(long pid) {
        return connections.call(pid, (connection, metadata) -> {
            boolean disabled = vmFlag(connection, metadata, "DisableExplicitGC");
            boolean concurrent = vmFlag(connection, metadata, "ExplicitGCInvokesConcurrent");
            long before = JmxAttributes.heapUsed(connection);
            long started = System.nanoTime();
            connection.invoke(MBeanNames.MEMORY, "gc", null, null);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            long after = JmxAttributes.heapUsed(connection);
            String warning;
            if (disabled) {
                warning = "-XX:+DisableExplicitGC is set: the target ignored the request.";
            } else if (concurrent) {
                warning = "-XX:+ExplicitGCInvokesConcurrent is set: a concurrent cycle ran instead of a Full GC.";
            } else {
                warning = "A stop-the-world Full GC ran on the target for about " + durationMs + " ms. Pauses longer"
                        + " than the Hazelcast client heartbeat timeout drop its cluster connection.";
            }
            log.warn("Explicit GC on PID {}: {} ms, heap {} -> {} bytes", pid, durationMs, before, after);
            return new GcTriggerResultDto(pid, durationMs, before, after, disabled, concurrent, warning);
        });
    }

    public JfrAllocationStatsDto startJfr(long pid) {
        LiveTargetMonitor monitor = monitor(pid);
        synchronized (monitor) {
            JfrAllocationSampler existing = monitor.jfr();
            if (existing != null && existing.isActive()) {
                return existing.snapshot();
            }
            if (existing != null) {
                monitor.detachJfr().close();
            }
            JfrAllocationSampler jfrSampler = new JfrAllocationSampler(pid, connections, props.jfr());
            jfrSampler.start();
            long interval = props.jfr().pollInterval().toMillis();
            ScheduledFuture<?> schedule = jfrPoller.scheduleWithFixedDelay(jfrSampler::poll, interval, interval,
                    TimeUnit.MILLISECONDS);
            jfrSampler.attachSchedule(schedule);
            monitor.setJfr(jfrSampler);
            return jfrSampler.snapshot();
        }
    }

    public JfrAllocationStatsDto stopJfr(long pid) {
        LiveTargetMonitor monitor = monitors.get(pid);
        JfrAllocationSampler jfrSampler = monitor == null ? null : monitor.detachJfr();
        if (jfrSampler == null) {
            throw new OperationRejectedException(404, "JFR_NOT_RUNNING", "No JFR allocation profile runs on PID " + pid);
        }
        jfrSampler.close();
        return jfrSampler.snapshot();
    }

    public Optional<JfrAllocationStatsDto> jfrStatus(long pid) {
        LiveTargetMonitor monitor = monitors.get(pid);
        JfrAllocationSampler jfrSampler = monitor == null ? null : monitor.jfr();
        return jfrSampler == null ? Optional.empty() : Optional.of(jfrSampler.snapshot());
    }

    /** Stops monitoring, e.g. before a remote target is unregistered; subscribers get the final status. */
    public void stopMonitoring(long pid, ConnectionState finalState, String reason) {
        LiveTargetMonitor monitor = monitors.remove(pid);
        if (monitor != null) {
            monitor.publishStatus(finalState, reason);
            monitor.stop();
        }
        selectedPid.compareAndSet(pid, null);
    }

    @Override
    public void destroy() {
        // Close JFR recordings on the targets before the connections go away.
        monitors.values().forEach(LiveTargetMonitor::stop);
        monitors.clear();
        sampler.shutdownNow();
        histogramExecutor.shutdownNow();
        jfrPoller.shutdownNow();
    }

    private LiveTargetMonitor monitor(long pid) {
        LiveTargetMonitor existing = monitors.get(pid);
        if (existing != null) {
            return existing;
        }
        if (pid == connections.selfPid()) {
            throw new OperationRejectedException(400, "SELF_MONITORING_FORBIDDEN",
                    "PID " + pid + " is the inspector itself; it never monitors its own JVM");
        }
        if (!connections.isKnown(pid)) {
            throw new TargetNotFoundException(pid);
        }
        return monitors.computeIfAbsent(pid, p -> {
            LiveTargetMonitor monitor = new LiveTargetMonitor(p, connections, props.live(), histogramExecutor,
                    this::onTerminated, this::onIdle);
            monitor.start(sampler);
            log.info("Live monitoring of PID {} started", p);
            return monitor;
        });
    }

    private void onTerminated(long pid) {
        LiveTargetMonitor monitor = monitors.remove(pid);
        if (monitor != null) {
            monitor.stop();
        }
        selectedPid.compareAndSet(pid, null);
        log.info("Live monitoring of PID {} stopped: process exited", pid);
    }

    private void onIdle(long pid) {
        LiveTargetMonitor monitor = monitors.remove(pid);
        if (monitor != null) {
            monitor.stop();
            log.info("Live monitoring of PID {} stopped: no subscribers", pid);
        }
    }

    private static boolean vmFlag(MBeanServerConnection connection, TargetMetadata metadata, String flag)
            throws IOException, JMException {
        if (!metadata.hotSpotDiagnosticAvailable()) {
            return false;
        }
        try {
            Object option = connection.invoke(MBeanNames.HOTSPOT_DIAGNOSTIC, "getVMOption", new Object[] {flag},
                    new String[] {String.class.getName()});
            return option instanceof CompositeData data && "true".equalsIgnoreCase(String.valueOf(data.get("value")));
        } catch (MBeanException | JMRuntimeException e) {
            // Unknown flag on this JVM.
            return false;
        }
    }
}
