package com.poludnitsa.inspector.jfr;

import com.poludnitsa.inspector.api.dto.JfrAllocationStatsDto;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.JvmTypeNames;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.common.TargetTerminatedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.jmx.MBeanNames;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import javax.management.JMX;
import javax.management.MBeanServerConnection;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import jdk.management.jfr.FlightRecorderMXBean;
import jdk.management.jfr.RecordingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight allocation profile that runs <b>inside the target JVM</b>, driven only through the target's
 * {@code jdk.management.jfr:type=FlightRecorder} MBean over its {@code MBeanServerConnection}.
 *
 * <p>The recording enables just {@code jdk.ThreadAllocationStatistics} (1 s period) and
 * {@code jdk.ObjectAllocationSample} (throttled, no stack traces), bounded by {@code maxAge}/{@code maxSize}. Each poll
 * clones the recording with {@code stop=true} (which rotates the target's chunk, so the clone holds data up to now),
 * streams the clone's chunks since the last processed event via {@code openStream/readStream} into a local file and
 * parses that file with {@link RecordingFile}. Nothing here touches the inspector's own Flight Recorder.
 */
public final class JfrAllocationSampler implements AutoCloseable {

    static final String RECORDING_PREFIX = "jvm-leak-inspector-alloc-";
    static final String THREAD_ALLOCATION = "jdk.ThreadAllocationStatistics";
    static final String ALLOCATION_SAMPLE = "jdk.ObjectAllocationSample";

    private static final Logger log = LoggerFactory.getLogger(JfrAllocationSampler.class);
    private static final long SELF_PID = ProcessHandle.current().pid();

    private final long pid;
    private final JmxConnectionManager connections;
    private final InspectorProperties.Jfr props;
    private final JfrAllocationAggregator aggregator;
    private final String recordingName = RECORDING_PREFIX + SELF_PID + "-" + UUID.randomUUID().toString().substring(0, 8);
    private final Object pollLock = new Object();
    private volatile Long recordingId;
    private volatile long startedAtEpochMs;
    private volatile boolean active;
    private volatile String error;
    private volatile ScheduledFuture<?> schedule;
    private Instant streamFrom;

    public JfrAllocationSampler(long pid, JmxConnectionManager connections, InspectorProperties.Jfr props) {
        this.pid = pid;
        this.connections = connections;
        this.props = props;
        this.aggregator = new JfrAllocationAggregator(props.topClasses());
    }

    /** Creates and starts the recording on the target. */
    public void start() {
        try {
            Files.createDirectories(props.resolvedWorkDir());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create JFR work directory " + props.resolvedWorkDir(), e);
        }
        long id = connections.call(pid, (connection, metadata) -> {
            if (!metadata.flightRecorderAvailable()) {
                throw new OperationRejectedException(409, "JFR_UNAVAILABLE",
                        "Target has no jdk.management.jfr:type=FlightRecorder MBean");
            }
            FlightRecorderMXBean recorder = recorder(connection);
            closeOrphanedRecordings(recorder);
            long created = recorder.newRecording();
            try {
                Map<String, String> options = new HashMap<>();
                options.put("name", recordingName);
                options.put("maxAge", props.maxAge().toSeconds() + " s");
                options.put("maxSize", Long.toString(props.maxSize().toBytes()));
                options.put("disk", "true");
                options.put("dumpOnExit", "false");
                recorder.setRecordingOptions(created, options);
                recorder.setRecordingSettings(created, settings());
                recorder.startRecording(created);
                return created;
            } catch (RuntimeException e) {
                closeQuietly(recorder, created);
                throw e;
            }
        });
        recordingId = id;
        startedAtEpochMs = System.currentTimeMillis();
        streamFrom = Instant.ofEpochMilli(startedAtEpochMs);
        active = true;
        log.info("Started JFR allocation recording '{}' (id {}) on PID {}", recordingName, id, pid);
    }

    public void attachSchedule(ScheduledFuture<?> future) {
        this.schedule = future;
    }

    public boolean isActive() {
        return active;
    }

    /** Pulls new data from the target; scheduled with a fixed delay. Never throws. */
    public void poll() {
        if (!active) {
            return;
        }
        synchronized (pollLock) {
            Long id = recordingId;
            if (id == null) {
                return;
            }
            Path file = null;
            try {
                Instant from = streamFrom;
                file = connections.call(pid, (connection, metadata) -> transfer(recorder(connection), id, from));
                if (file != null) {
                    parse(file);
                }
                aggregator.completePoll();
                Long last = aggregator.lastEventEpochMs();
                if (last != null) {
                    streamFrom = Instant.ofEpochMilli(last - 1000);
                }
                error = null;
            } catch (TargetTerminatedException e) {
                active = false;
                error = e.getMessage();
            } catch (InspectorException | IOException | UncheckedIOException e) {
                error = e.getMessage();
                log.debug("JFR poll of PID {} failed", pid, e);
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("JFR poll of PID {} failed", pid, e);
            } finally {
                deleteQuietly(file);
            }
        }
    }

    public JfrAllocationStatsDto snapshot() {
        return new JfrAllocationStatsDto(active, recordingName, startedAtEpochMs, aggregator.lastEventEpochMs(),
                aggregator.allocationRateBytesPerSec(), aggregator.topAllocatingClasses(), error);
    }

    /** Stops polling and closes the recording on the target (best effort if the target is gone). */
    @Override
    public void close() {
        active = false;
        ScheduledFuture<?> future = schedule;
        if (future != null) {
            future.cancel(false);
        }
        Long id = recordingId;
        recordingId = null;
        if (id == null) {
            return;
        }
        synchronized (pollLock) {
            try {
                connections.call(pid, (connection, metadata) -> {
                    recorder(connection).closeRecording(id);
                    return null;
                });
                log.info("Closed JFR allocation recording '{}' on PID {}", recordingName, pid);
            } catch (InspectorException e) {
                log.debug("Could not close JFR recording {} on PID {}: {}", id, pid, e.getMessage());
            }
        }
    }

    private Map<String, String> settings() {
        Map<String, String> settings = new HashMap<>();
        settings.put(THREAD_ALLOCATION + "#enabled", "true");
        settings.put(THREAD_ALLOCATION + "#period", "1 s");
        settings.put(ALLOCATION_SAMPLE + "#enabled", "true");
        settings.put(ALLOCATION_SAMPLE + "#throttle", props.objectAllocationThrottle());
        settings.put(ALLOCATION_SAMPLE + "#stackTrace", "false");
        return settings;
    }

    /** Recordings named by an inspector that no longer runs, or left over by this one, are closed. */
    private void closeOrphanedRecordings(FlightRecorderMXBean recorder) {
        List<RecordingInfo> recordings = recorder.getRecordings();
        for (RecordingInfo info : recordings) {
            String name = info.getName();
            if (name == null || !name.startsWith(RECORDING_PREFIX)) {
                continue;
            }
            long owner = ownerPid(name);
            if (owner == SELF_PID || owner < 0 || ProcessHandle.of(owner).map(h -> !h.isAlive()).orElse(true)) {
                closeQuietly(recorder, info.getId());
                log.info("Closed orphaned JFR recording '{}' on PID {}", name, pid);
            }
        }
    }

    static long ownerPid(String recordingName) {
        String rest = recordingName.substring(RECORDING_PREFIX.length());
        int dash = rest.indexOf('-');
        try {
            return Long.parseLong(dash < 0 ? rest : rest.substring(0, dash));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Path transfer(FlightRecorderMXBean recorder, long id, Instant from) throws IOException {
        long clone = recorder.cloneRecording(id, true);
        try {
            Map<String, String> options = new HashMap<>();
            options.put("blockSize", Integer.toString(1 << 20));
            if (from != null) {
                options.put("startTime", from.toString());
            }
            long stream;
            try {
                stream = recorder.openStream(clone, options);
            } catch (IOException e) {
                throw remoteFailure(e, "openStream");
            }
            Path file = Files.createTempFile(props.resolvedWorkDir(), "alloc-" + pid + "-", ".jfr");
            try (OutputStream out = Files.newOutputStream(file)) {
                byte[] block;
                while ((block = recorder.readStream(stream)) != null) {
                    out.write(block);
                }
            } catch (IOException | RuntimeException e) {
                deleteQuietly(file);
                if (e instanceof IOException io) {
                    throw remoteFailure(io, "readStream");
                }
                throw e;
            } finally {
                try {
                    recorder.closeStream(stream);
                } catch (IOException ignored) {
                    // stream is gone with the clone
                }
            }
            return Files.size(file) > 0 ? file : deleteAndReturnNull(file);
        } finally {
            try {
                recorder.closeRecording(clone);
            } catch (IOException | RuntimeException ignored) {
                // a leftover stopped clone is dropped by the target's maxAge handling
            }
        }
    }

    /**
     * IOExceptions declared by FlightRecorderMXBean come both from the transport and from the target's repository;
     * only transport failures (RMI) may mark the connection broken.
     */
    private static IOException remoteFailure(IOException e, String operation) {
        if (e instanceof RemoteException) {
            return e;
        }
        throw new UncheckedIOException("FlightRecorderMXBean." + operation + " failed on target: " + e.getMessage(), e);
    }

    private void parse(Path file) throws IOException {
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                long time = event.getEndTime().toEpochMilli();
                if (THREAD_ALLOCATION.equals(type)) {
                    RecordedThread thread = event.getThread("thread");
                    if (thread != null && thread.getJavaThreadId() > 0) {
                        aggregator.onThreadAllocation(thread.getJavaThreadId(), event.getLong("allocated"), time);
                    }
                } else if (ALLOCATION_SAMPLE.equals(type)) {
                    RecordedClass objectClass = event.getClass("objectClass");
                    if (objectClass != null) {
                        aggregator.onAllocationSample(JvmTypeNames.toJavaName(objectClass.getName()),
                                event.getLong("weight"), time);
                    }
                }
            }
        }
    }

    private static FlightRecorderMXBean recorder(MBeanServerConnection connection) {
        return JMX.newMXBeanProxy(connection, MBeanNames.FLIGHT_RECORDER, FlightRecorderMXBean.class);
    }

    private static void closeQuietly(FlightRecorderMXBean recorder, long id) {
        try {
            recorder.closeRecording(id);
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    private static Path deleteAndReturnNull(Path file) {
        deleteQuietly(file);
        return null;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // temp file in the work dir
        }
    }
}
