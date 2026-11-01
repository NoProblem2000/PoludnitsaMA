package com.poludnitsa.inspector.gc;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

/**
 * GC and safepoint events of a JFR file (recorded in the target through its {@code FlightRecorderMXBean}, or
 * uploaded) into {@link GcLogData}, so live data and log files share one analysis. The file is read event by event
 * with {@link RecordingFile}; only the GC / safepoint events are kept.
 */
public final class JfrGcParser {

    /** Event settings of a GC &amp; safepoint recording. */
    public static Map<String, String> settings() {
        Map<String, String> settings = new HashMap<>();
        for (String event : new String[] {"jdk.GarbageCollection", "jdk.G1GarbageCollection",
                "jdk.ParallelOldGarbageCollection", "jdk.YoungGarbageCollection", "jdk.OldGarbageCollection",
                "jdk.GCPhasePause", "jdk.GCHeapSummary", "jdk.G1HeapSummary", "jdk.PSHeapSummary",
                "jdk.SafepointBegin", "jdk.SafepointStateSynchronization", "jdk.SafepointEnd",
                "jdk.ExecuteVMOperation", "jdk.EvacuationFailed", "jdk.ZAllocationStall"}) {
            settings.put(event + "#enabled", "true");
            settings.put(event + "#threshold", "0 ms");
        }
        for (String event : new String[] {"jdk.GCConfiguration", "jdk.JVMInformation"}) {
            settings.put(event + "#enabled", "true");
            settings.put(event + "#period", "beginChunk");
        }
        return settings;
    }

    private static final class Gc {
        String name;
        String cause;
        String g1Type;
        long startMs;
        double sumOfPausesMs;
        final Map<String, Double> heap = new HashMap<>();
        final Map<String, Double> eden = new HashMap<>();
        final Map<String, Double> old = new HashMap<>();
        Double committedMb;
    }

    private record PhasePause(int gcId, long endMs, String name, double durationMs) {
    }

    private static final class Sp {
        long startMs;
        /** start of jdk.SafepointBegin, end of jdk.SafepointEnd; the begin event alone covers only reaching it */
        Instant begin;
        Instant end;
        double totalMs = -1;
        double ttspMs;
        String operation;
        double operationMs;
    }

    private JfrGcParser() {
    }

    /**
     * @param recordingStart start of the recording when known (live); uptimes are relative to the target JVM start
     *                       ({@code jdk.JVMInformation}), else to this instant, else to the first event
     * @param coveredMs      length of the observed window (live), or null for the span of the events
     */
    public static GcLogData parse(Path file, Instant recordingStart, Double coveredMs, int maxEvents)
            throws IOException {
        Map<Integer, Gc> gcs = new LinkedHashMap<>();
        List<PhasePause> phases = new ArrayList<>();
        Map<Long, Sp> safepoints = new LinkedHashMap<>();
        GcLogData data = new GcLogData();
        Long jvmStart = null;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        String young = null;
        String oldCollector = null;
        long events = 0;
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                long start = event.getStartTime().toEpochMilli();
                long end = event.getEndTime().toEpochMilli();
                switch (type) {
                    case "jdk.JVMInformation" -> {
                        if (event.hasField("jvmStartTime")) {
                            jvmStart = event.getInstant("jvmStartTime").toEpochMilli();
                        }
                        if (event.hasField("jvmVersion") && data.jvmVersion == null) {
                            data.jvmVersion = version(event.getString("jvmVersion"));
                        }
                        continue;
                    }
                    case "jdk.GCConfiguration" -> {
                        young = string(event, "youngCollector");
                        oldCollector = string(event, "oldCollector");
                        continue;
                    }
                    default -> {
                    }
                }
                if (++events > maxEvents * 4L) {
                    data.truncated = true;
                    break;
                }
                first = Math.min(first, start);
                last = Math.max(last, end);
                switch (type) {
                    case "jdk.GarbageCollection" -> {
                        Gc gc = gcs.computeIfAbsent(event.getInt("gcId"), k -> new Gc());
                        gc.name = string(event, "name");
                        gc.cause = string(event, "cause");
                        gc.startMs = start;
                        gc.sumOfPausesMs = millis(event.getDuration("sumOfPauses"));
                    }
                    case "jdk.G1GarbageCollection" ->
                            gcs.computeIfAbsent(event.getInt("gcId"), k -> new Gc()).g1Type = string(event, "type");
                    case "jdk.GCPhasePause" -> phases.add(new PhasePause(event.getInt("gcId"), end,
                            string(event, "name"), millis(event.getDuration())));
                    case "jdk.GCHeapSummary" -> {
                        Gc gc = gcs.computeIfAbsent(event.getInt("gcId"), k -> new Gc());
                        String when = string(event, "when");
                        gc.heap.put(when, event.getLong("heapUsed") / 1048576.0);
                        RecordedObject space = event.getValue("heapSpace");
                        if (space != null && space.hasField("committedSize")) {
                            gc.committedMb = space.getLong("committedSize") / 1048576.0;
                        }
                    }
                    case "jdk.G1HeapSummary" -> {
                        Gc gc = gcs.computeIfAbsent(event.getInt("gcId"), k -> new Gc());
                        gc.eden.put(string(event, "when"),
                                (event.getLong("edenUsedSize") + event.getLong("survivorUsedSize")) / 1048576.0);
                    }
                    case "jdk.PSHeapSummary" -> {
                        Gc gc = gcs.computeIfAbsent(event.getInt("gcId"), k -> new Gc());
                        RecordedObject oldSpace = event.getValue("oldObjectSpace");
                        if (oldSpace != null && oldSpace.hasField("used")) {
                            gc.old.put(string(event, "when"), oldSpace.getLong("used") / 1048576.0);
                        }
                    }
                    case "jdk.SafepointBegin" -> {
                        Sp sp = safepoints.computeIfAbsent(event.getLong("safepointId"), k -> new Sp());
                        sp.startMs = start;
                        sp.begin = event.getStartTime();
                        sp.totalMs = millis(event.getDuration());
                    }
                    case "jdk.SafepointEnd" ->
                            safepoints.computeIfAbsent(event.getLong("safepointId"), k -> new Sp()).end =
                                    event.getEndTime();
                    case "jdk.SafepointStateSynchronization" ->
                            safepoints.computeIfAbsent(event.getLong("safepointId"), k -> new Sp()).ttspMs =
                                    millis(event.getDuration());
                    case "jdk.ExecuteVMOperation" -> {
                        if (event.hasField("safepoint") && event.getBoolean("safepoint")
                                && event.hasField("safepointId")) {
                            Sp sp = safepoints.computeIfAbsent(event.getLong("safepointId"), k -> new Sp());
                            sp.operation = string(event, "operation");
                            sp.operationMs = millis(event.getDuration());
                        }
                    }
                    case "jdk.EvacuationFailed" -> data.evacuationFailedGcIds.add(event.getInt("gcId"));
                    case "jdk.ZAllocationStall" -> {
                        data.allocationStalls++;
                        data.allocationStallMs += millis(event.getDuration());
                    }
                    default -> {
                    }
                }
            }
        }
        data.collector = collector(young, oldCollector, gcs);
        long base = jvmStart != null ? jvmStart
                : recordingStart != null ? recordingStart.toEpochMilli() : first == Long.MAX_VALUE ? 0 : first;
        if (first != Long.MAX_VALUE) {
            data.seen(first - base);
            data.seen(last - base);
        }
        data.coveredMs = coveredMs;

        // pauses: the phase pauses of each GC, else its sum of pauses (collectors without phase events)
        Map<Integer, List<GcLogData.Pause>> byGc = new HashMap<>();
        for (PhasePause phase : phases) {
            Gc gc = gcs.get(phase.gcId());
            String name = pauseName(phase.name(), gc);
            GcLogData.Pause pause = data.pause(phase.gcId(), phase.endMs() - base, name, phase.durationMs());
            if (pause.cause == null && gc != null) {
                pause.cause = gc.cause;
                pause.category = GcLogData.categoryOf(name, pause.cause, GcLogData.concurrentCollector(data.collector));
            }
            byGc.computeIfAbsent(phase.gcId(), k -> new ArrayList<>()).add(pause);
        }
        for (Map.Entry<Integer, Gc> entry : gcs.entrySet()) {
            Gc gc = entry.getValue();
            if (gc.name == null) {
                continue;
            }
            if (concurrentCycle(gc.name)) {
                data.concurrentCycles++;
            }
            if (!byGc.containsKey(entry.getKey()) && gc.sumOfPausesMs > 0 && !concurrentCycle(gc.name)) {
                GcLogData.Pause pause = data.pause(entry.getKey(), gc.startMs - base + gc.sumOfPausesMs,
                        synthesizedName(gc), gc.sumOfPausesMs);
                byGc.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(pause);
            }
            Double before = gc.heap.get("Before GC");
            Double after = gc.heap.get("After GC");
            List<GcLogData.Pause> pauses = byGc.get(entry.getKey());
            if (pauses != null && before != null && after != null) {
                GcLogData.Pause main = pauses.stream().max((a, b) -> Double.compare(a.pauseMs, b.pauseMs)).get();
                main.heapBeforeMb = before;
                main.heapAfterMb = after;
                main.heapTotalMb = gc.committedMb;
                main.oldBeforeMb = old(gc, "Before GC");
                main.oldAfterMb = old(gc, "After GC");
            }
            if (GcLogData.concurrentCollector(data.collector) && before != null && after != null) {
                data.collections.add(new GcLogData.Collection(entry.getKey(), gc.startMs - base,
                        gc.cause == null ? "Concurrent cycle" : gc.cause, before, after, gc.committedMb));
                if (pauses != null) {
                    pauses.forEach(p -> {
                        if (p.cause == null || p.cause.equals(p.name)) {
                            p.cause = gc.cause;
                        }
                    });
                }
            }
        }
        for (Sp sp : safepoints.values()) {
            if (sp.begin != null && sp.end != null && sp.end.isAfter(sp.begin)) {
                sp.totalMs = millis(Duration.between(sp.begin, sp.end));
            }
            if (sp.totalMs >= 0) {
                sp.totalMs = Math.max(sp.totalMs, sp.ttspMs + sp.operationMs);
                data.safepoints.add(new GcLogData.Safepoint(sp.startMs - base,
                        sp.operation != null ? sp.operation : "unknown", sp.ttspMs,
                        sp.operationMs > 0 ? sp.operationMs : Math.max(0, sp.totalMs - sp.ttspMs), sp.totalMs));
            }
        }
        data.pauses.sort((a, b) -> Double.compare(a.uptimeMs, b.uptimeMs));
        data.safepoints.sort((a, b) -> Double.compare(a.uptimeMs(), b.uptimeMs()));
        data.parsedLines = events;
        return data;
    }

    private static Double old(Gc gc, String when) {
        Double old = gc.old.get(when);
        if (old != null) {
            return old;
        }
        Double heap = gc.heap.get(when);
        Double young = gc.eden.get(when);
        return heap != null && young != null ? Math.max(0, heap - young) : null;
    }

    /** "Pause Young (Normal) (G1 Evacuation Pause)" → "Young (Normal) (G1 Evacuation Pause)"; generic names rebuilt. */
    private static String pauseName(String phase, Gc gc) {
        String name = phase == null ? "" : phase.startsWith("Pause ") ? phase.substring(6) : phase;
        if (gc != null && gc.name != null && (name.isBlank() || name.equals("GC Pause") || !phase.startsWith("Pause ")
                || !name.contains("(") && (name.equals("Young") || name.equals("Full")))) {
            return synthesizedName(gc);
        }
        return name.isBlank() ? "GC" : name;
    }

    private static String synthesizedName(Gc gc) {
        String cause = gc.cause == null ? "" : " (" + gc.cause + ")";
        return switch (gc.name) {
            case "G1New" -> "Young" + (gc.g1Type == null ? "" : " (" + gc.g1Type + ")") + cause;
            case "ParallelScavenge", "DefNew", "ParNew" -> "Young" + cause;
            case "G1Full", "ParallelOld", "SerialOld", "PSMarkSweep" -> "Full" + cause;
            default -> gc.name + cause;
        };
    }

    private static boolean concurrentCycle(String name) {
        return name.equals("G1Old") || name.startsWith("Z") || name.startsWith("Shenandoah");
    }

    private static String collector(String young, String old, Map<Integer, Gc> gcs) {
        String hint = young != null ? young + " " + old : gcs.values().stream().map(g -> g.name)
                .filter(n -> n != null).findFirst().orElse(null);
        if (hint == null) {
            return null;
        }
        if (hint.startsWith("G1")) {
            return "G1";
        }
        if (hint.startsWith("ParallelScavenge") || hint.startsWith("ParallelOld")) {
            return "Parallel";
        }
        if (hint.startsWith("DefNew") || hint.startsWith("Serial")) {
            return "Serial";
        }
        if (hint.startsWith("Z")) {
            return hint.contains("Minor") || hint.contains("Major") || hint.contains("ZYoung") ? "ZGC (generational)"
                    : "ZGC";
        }
        if (hint.startsWith("Shenandoah")) {
            return "Shenandoah";
        }
        return hint.split(" ")[0];
    }

    /** "OpenJDK 64-Bit Server VM (21.0.1+12-LTS) for windows-amd64 …" → "21.0.1+12-LTS". */
    private static String version(String text) {
        if (text == null) {
            return null;
        }
        int open = text.indexOf('(');
        int close = text.indexOf(')', open + 1);
        return open >= 0 && close > open ? text.substring(open + 1, close) : text;
    }

    private static String string(RecordedEvent event, String field) {
        return event.hasField(field) ? event.getString(field) : null;
    }

    private static double millis(Duration duration) {
        return duration == null ? 0 : duration.toNanos() / 1e6;
    }
}
