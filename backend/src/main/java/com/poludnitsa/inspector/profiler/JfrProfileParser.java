package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.common.JvmTypeNames;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Reads a recording file transferred from the target (JFR written by the target's Flight Recorder through its MXBean,
 * or the JFR output of async-profiler) into a {@link ProfileData}. Works on the file only: the inspector's own
 * Flight Recorder is never involved.
 *
 * <p>Event mapping:
 * <ul>
 *   <li>{@code jdk.ExecutionSample} (JFR and async-profiler cpu) → CPU samples, one sampling interval each;</li>
 *   <li>{@code jdk.NativeMethodSample} → native samples (wall-clock), {@code profiler.WallClockSample} (async-profiler
 *       wall) → wall samples;</li>
 *   <li>{@code jdk.ObjectAllocationSample} / {@code InNewTLAB} / {@code OutsideTLAB} → allocations in bytes;</li>
 *   <li>park, monitor enter / wait, sleep, socket and file read / write → duration events with stack;</li>
 *   <li>{@code jdk.GarbageCollection} → pauses, {@code jdk.CPULoad} → CPU series, exception throws → counts.</li>
 * </ul>
 */
public final class JfrProfileParser {

    /**
     * @param cpuIntervalUs    weight of one execution sample
     * @param nativeIntervalUs weight of one native sample
     * @param wallIntervalUs   weight of one async-profiler wall sample
     */
    public record Options(double cpuIntervalUs, double nativeIntervalUs, double wallIntervalUs, int maxRecords) {
    }

    private JfrProfileParser() {
    }

    public static ProfileData parse(Path file, Instant recordingStart, Duration requested, Options options)
            throws IOException {
        long startEpochMs = recordingStart.toEpochMilli();
        ProfileBuilder builder = new ProfileBuilder(options.maxRecords());
        long cpuWeight = Math.max(1, Math.round(options.cpuIntervalUs()));
        long nativeWeight = Math.max(1, Math.round(options.nativeIntervalUs()));
        long wallWeight = Math.max(1, Math.round(options.wallIntervalUs()));
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                double startMs = (event.getStartTime().toEpochMilli() - startEpochMs)
                        + (event.getStartTime().getNano() % 1_000_000) / 1e6;
                switch (type) {
                    case "jdk.ExecutionSample" -> sample(builder, event, "sampledThread", startMs,
                            ProfileData.SampleKind.CPU, cpuWeight);
                    case "jdk.NativeMethodSample" -> sample(builder, event, "sampledThread", startMs,
                            ProfileData.SampleKind.NATIVE, nativeWeight);
                    case "profiler.WallClockSample" -> sample(builder, event, "sampledThread", startMs,
                            ProfileData.SampleKind.WALL, wallWeight * Math.max(1, longField(event, "samples", 1)));
                    case "jdk.ObjectAllocationSample" -> sample(builder, event, null, startMs,
                            ProfileData.SampleKind.ALLOC, longField(event, "weight", 0));
                    case "jdk.ObjectAllocationInNewTLAB" -> sample(builder, event, null, startMs,
                            ProfileData.SampleKind.ALLOC, longField(event, "tlabSize", longField(event, "allocationSize", 0)));
                    case "jdk.ObjectAllocationOutsideTLAB" -> sample(builder, event, null, startMs,
                            ProfileData.SampleKind.ALLOC, longField(event, "allocationSize", 0));
                    case "jdk.ThreadPark" -> event(builder, event, ProfileData.EventKind.PARK, startMs,
                            className(event, "parkedClass"));
                    case "jdk.JavaMonitorEnter" -> event(builder, event, ProfileData.EventKind.MONITOR_ENTER, startMs,
                            className(event, "monitorClass"));
                    case "jdk.JavaMonitorWait" -> event(builder, event, ProfileData.EventKind.MONITOR_WAIT, startMs,
                            className(event, "monitorClass"));
                    case "jdk.ThreadSleep" -> event(builder, event, ProfileData.EventKind.SLEEP, startMs, null);
                    case "jdk.SocketRead" -> event(builder, event, ProfileData.EventKind.SOCKET_READ, startMs,
                            address(event));
                    case "jdk.SocketWrite" -> event(builder, event, ProfileData.EventKind.SOCKET_WRITE, startMs,
                            address(event));
                    case "jdk.FileRead" -> event(builder, event, ProfileData.EventKind.FILE_READ, startMs,
                            stringField(event, "path"));
                    case "jdk.FileWrite" -> event(builder, event, ProfileData.EventKind.FILE_WRITE, startMs,
                            stringField(event, "path"));
                    case "jdk.JavaExceptionThrow", "jdk.JavaErrorThrow" -> {
                        String thrown = className(event, "thrownClass");
                        builder.exception(thrown == null ? "?" : thrown);
                        event(builder, event, ProfileData.EventKind.EXCEPTION, startMs, thrown);
                    }
                    case "jdk.GarbageCollection" -> {
                        Duration pauses = durationField(event, "sumOfPauses");
                        if (pauses != null && !pauses.isZero()) {
                            builder.gcPause(startMs, pauses.toNanos() / 1e6, stringField(event, "name"));
                        }
                    }
                    case "jdk.CPULoad" -> builder.cpuLoad(startMs,
                            doubleField(event, "jvmUser") + doubleField(event, "jvmSystem"),
                            doubleField(event, "machineTotal"));
                    default -> {
                        // other events of the recording are not part of the profile
                    }
                }
            }
        }
        return builder.build(startEpochMs, requested.toMillis(), options.cpuIntervalUs());
    }

    private static void sample(ProfileBuilder builder, RecordedEvent event, String threadField, double timeMs,
                               ProfileData.SampleKind kind, long weight) {
        RecordedThread thread = threadField != null && event.hasField(threadField) ? event.getThread(threadField)
                : event.getThread();
        builder.sample(timeMs, thread(builder, thread), stack(builder, event.getStackTrace()), kind, weight);
    }

    private static void event(ProfileBuilder builder, RecordedEvent event, ProfileData.EventKind kind, double startMs,
                              String detail) {
        double durationMs = event.getDuration().toNanos() / 1e6;
        builder.event(kind, startMs, durationMs, thread(builder, event.getThread()),
                stack(builder, event.getStackTrace()), detail);
    }

    private static int thread(ProfileBuilder builder, RecordedThread thread) {
        if (thread == null) {
            return builder.thread(-1, "unknown");
        }
        long id = thread.getJavaThreadId() > 0 ? thread.getJavaThreadId() : -thread.getOSThreadId() - 2;
        return builder.thread(id, thread.getJavaName() != null ? thread.getJavaName() : thread.getOSName());
    }

    /** Leaf first; hidden frames (lambda forms, reflection stubs) are dropped. */
    private static int stack(ProfileBuilder builder, RecordedStackTrace stack) {
        if (stack == null) {
            return -1;
        }
        List<RecordedFrame> frames = stack.getFrames();
        int[] ids = new int[frames.size()];
        int count = 0;
        for (RecordedFrame frame : frames) {
            RecordedMethod method = frame.getMethod();
            if (method == null || method.getType() == null || method.isHidden()) {
                continue;
            }
            ids[count++] = builder.frame(JvmTypeNames.toJavaName(method.getType().getName()), method.getName());
        }
        return builder.stack(count == ids.length ? ids : Arrays.copyOf(ids, count));
    }

    private static String className(RecordedEvent event, String field) {
        if (!event.hasField(field)) {
            return null;
        }
        Object value = event.getValue(field);
        return value instanceof RecordedClass type ? JvmTypeNames.toJavaName(type.getName()) : null;
    }

    private static String address(RecordedEvent event) {
        String host = stringField(event, "host");
        String address = stringField(event, "address");
        long port = longField(event, "port", -1);
        String name = host != null && !host.isBlank() ? host : address;
        return name == null ? null : port >= 0 ? name + ":" + port : name;
    }

    private static String stringField(RecordedEvent event, String field) {
        return event.hasField(field) ? event.getString(field) : null;
    }

    private static long longField(RecordedEvent event, String field, long fallback) {
        if (!event.hasField(field)) {
            return fallback;
        }
        Object value = event.getValue(field);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static double doubleField(RecordedEvent event, String field) {
        if (!event.hasField(field)) {
            return 0;
        }
        Object value = event.getValue(field);
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static Duration durationField(RecordedEvent event, String field) {
        return event.hasField(field) ? event.getDuration(field) : null;
    }
}
