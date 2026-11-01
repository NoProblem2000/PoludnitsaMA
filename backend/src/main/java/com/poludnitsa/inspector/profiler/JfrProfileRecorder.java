package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.jmx.MBeanNames;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import javax.management.JMX;
import javax.management.MBeanServerConnection;
import jdk.management.jfr.FlightRecorderMXBean;

/**
 * A CPU / wall / allocation recording <b>inside the target</b>, through its {@code FlightRecorderMXBean} only: the
 * recording is created, started and stopped on the target, then its data is copied over JMX
 * ({@code openStream} / {@code readStream}) into a local file for {@link JfrProfileParser}.
 *
 * <p>Events: execution and native method samples, thread park, monitor enter / wait, sleep, socket and file I/O
 * (from {@code eventThreshold}, or {@code tracingThreshold} in tracing mode), GC, CPU load, allocation samples,
 * errors, and in tracing mode every thrown exception.
 */
public final class JfrProfileRecorder {

    static final String RECORDING_PREFIX = "jvm-leak-inspector-profile-";

    private final JmxConnectionManager connections;
    private final InspectorProperties.Profiler props;

    public JfrProfileRecorder(JmxConnectionManager connections, InspectorProperties.Profiler props) {
        this.connections = connections;
        this.props = props;
    }

    /** Creates and starts the recording; returns its id on the target. */
    public long start(long pid, String profileId, Duration duration, boolean tracing) {
        return start(pid, RECORDING_PREFIX + profileId, duration, settings(tracing));
    }

    /** Creates and starts a recording named {@code name} with explicit event settings (e.g. GC and safepoints). */
    public long start(long pid, String name, Duration duration, Map<String, String> settings) {
        return connections.call(pid, (connection, metadata) -> {
            if (!metadata.flightRecorderAvailable()) {
                throw new OperationRejectedException(409, "JFR_UNAVAILABLE",
                        "Target has no jdk.management.jfr:type=FlightRecorder MBean");
            }
            FlightRecorderMXBean recorder = recorder(connection);
            long id = recorder.newRecording();
            try {
                Map<String, String> options = new HashMap<>();
                options.put("name", name);
                // a safety net: the recording ends on the target even if the inspector goes away
                options.put("duration", (duration.toSeconds() + 60) + " s");
                options.put("maxSize", Long.toString(512L << 20));
                options.put("disk", "true");
                options.put("dumpOnExit", "false");
                recorder.setRecordingOptions(id, options);
                recorder.setRecordingSettings(id, settings);
                recorder.startRecording(id);
                return id;
            } catch (RuntimeException e) {
                closeQuietly(recorder, id);
                throw e;
            }
        });
    }

    /** Stops the recording, copies it into {@code file} and closes it on the target. */
    public void stopAndTransfer(long pid, long recordingId, Path file) {
        connections.call(pid, Duration.ofMinutes(10), (connection, metadata) -> {
            FlightRecorderMXBean recorder = recorder(connection);
            try {
                recorder.stopRecording(recordingId);
                long stream;
                try {
                    stream = recorder.openStream(recordingId, Map.of("blockSize", Integer.toString(1 << 20)));
                } catch (IOException e) {
                    throw remoteFailure(e, "openStream");
                }
                try (OutputStream out = Files.newOutputStream(file)) {
                    byte[] block;
                    while ((block = recorder.readStream(stream)) != null) {
                        out.write(block);
                    }
                } catch (IOException e) {
                    throw remoteFailure(e, "readStream");
                } finally {
                    try {
                        recorder.closeStream(stream);
                    } catch (IOException ignored) {
                        // closed with the recording
                    }
                }
            } finally {
                closeQuietly(recorder, recordingId);
            }
            return null;
        });
    }

    /** Best effort, e.g. when a profile is cancelled. */
    public void close(long pid, long recordingId) {
        try {
            connections.call(pid, (connection, metadata) -> {
                closeQuietly(recorder(connection), recordingId);
                return null;
            });
        } catch (RuntimeException ignored) {
            // the target may be gone; the recording has a duration of its own
        }
    }

    Map<String, String> settings(boolean tracing) {
        String threshold = millis(tracing ? props.tracingThreshold() : props.eventThreshold());
        Map<String, String> settings = new HashMap<>();
        enable(settings, "jdk.ExecutionSample");
        settings.put("jdk.ExecutionSample#period", millis(props.cpuSamplingInterval()));
        enable(settings, "jdk.NativeMethodSample");
        settings.put("jdk.NativeMethodSample#period", millis(props.nativeSamplingInterval()));
        for (String event : new String[] {"jdk.ThreadPark", "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait",
                "jdk.ThreadSleep", "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite"}) {
            enable(settings, event);
            settings.put(event + "#threshold", threshold);
            settings.put(event + "#stackTrace", "true");
        }
        enable(settings, "jdk.GarbageCollection");
        enable(settings, "jdk.CPULoad");
        settings.put("jdk.CPULoad#period", "1 s");
        enable(settings, "jdk.ObjectAllocationSample");
        settings.put("jdk.ObjectAllocationSample#throttle", "150/s");
        settings.put("jdk.ObjectAllocationSample#stackTrace", "true");
        enable(settings, "jdk.JavaErrorThrow");
        settings.put("jdk.JavaErrorThrow#stackTrace", "true");
        if (tracing) {
            enable(settings, "jdk.JavaExceptionThrow");
            settings.put("jdk.JavaExceptionThrow#stackTrace", "true");
        }
        enable(settings, "jdk.ThreadStart");
        enable(settings, "jdk.ThreadEnd");
        return settings;
    }

    private static void enable(Map<String, String> settings, String event) {
        settings.put(event + "#enabled", "true");
    }

    private static String millis(Duration duration) {
        return Math.max(0, duration.toMillis()) + " ms";
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

    /** Transport failures (RMI) may mark the connection broken; failures of the target's repository may not. */
    private static IOException remoteFailure(IOException e, String operation) {
        if (e instanceof RemoteException) {
            return e;
        }
        throw new UncheckedIOException("FlightRecorderMXBean." + operation + " failed on the target: " + e.getMessage(),
                e);
    }
}
