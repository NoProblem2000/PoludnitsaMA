package com.poludnitsa.inspector.gc;

import com.poludnitsa.inspector.api.dto.GcPauseCategory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * GC events of one source (a Unified Logging file or a JFR recording of the target), in a form common to every
 * collector, before {@link GcLogAnalyzer} turns them into KPIs. Sizes are MB, times ms of JVM uptime.
 */
public final class GcLogData {

    /** A stop-the-world pause. {@code oldBeforeMb} / {@code oldAfterMb} feed the promotion rate. */
    public static final class Pause {
        int gcId;
        double uptimeMs;
        GcPauseCategory category;
        String name;
        String cause;
        double pauseMs;
        Double heapBeforeMb;
        Double heapAfterMb;
        Double heapTotalMb;
        Double oldBeforeMb;
        Double oldAfterMb;

        public int gcId() {
            return gcId;
        }

        public double uptimeMs() {
            return uptimeMs;
        }

        public GcPauseCategory category() {
            return category;
        }

        public String name() {
            return name;
        }

        public String cause() {
            return cause;
        }

        public double pauseMs() {
            return pauseMs;
        }

        public Double heapBeforeMb() {
            return heapBeforeMb;
        }

        public Double heapAfterMb() {
            return heapAfterMb;
        }
    }

    /** A collection of a concurrent collector (ZGC, Shenandoah): heap before / after, without a long pause. */
    public record Collection(int gcId, double uptimeMs, String cause, double heapBeforeMb, double heapAfterMb,
                             Double heapTotalMb) {
    }

    /** A safepoint: time to safepoint (TTSP) and the VM operation itself. */
    public record Safepoint(double uptimeMs, String operation, double ttspMs, double operationMs, double totalMs) {
    }

    String collector;
    String jvmVersion;
    Double regionSizeMb;
    Double firstUptimeMs;
    Double lastUptimeMs;
    /** Explicit length of the observed window (a live recording); else last − first uptime. */
    Double coveredMs;
    final List<Pause> pauses = new ArrayList<>();
    final List<Collection> collections = new ArrayList<>();
    final List<Safepoint> safepoints = new ArrayList<>();
    int concurrentCycles;
    /** GC ids with an evacuation failure / to-space exhausted (logged as a line, or in the pause name). */
    final Set<Integer> evacuationFailedGcIds = new HashSet<>();
    int allocationStalls;
    double allocationStallMs;
    int degenerated;
    long parsedLines;
    long unparsedLines;
    boolean truncated;

    public String collector() {
        return collector;
    }

    public List<Pause> pauses() {
        return pauses;
    }

    public List<Collection> collections() {
        return collections;
    }

    public List<Safepoint> safepoints() {
        return safepoints;
    }

    public int evacuationFailures() {
        return evacuationFailedGcIds.size();
    }

    public int allocationStalls() {
        return allocationStalls;
    }

    void seen(double uptimeMs) {
        if (firstUptimeMs == null || uptimeMs < firstUptimeMs) {
            firstUptimeMs = uptimeMs;
        }
        if (lastUptimeMs == null || uptimeMs > lastUptimeMs) {
            lastUptimeMs = uptimeMs;
        }
    }

    double covered() {
        if (coveredMs != null) {
            return coveredMs;
        }
        return firstUptimeMs == null ? 0 : lastUptimeMs - firstUptimeMs;
    }

    // ---------------------------------------------------------------- pause names

    /** G1 young pause kinds, printed in parentheses before the cause: "Pause Young (Normal) (G1 Evacuation Pause)". */
    private static final Set<String> PAUSE_KINDS = Set.of("normal", "concurrent start", "prepare mixed", "mixed",
            "initial mark", "evacuation failure", "unload classes", "process weakrefs", "update refs",
            "outside of cycle", "mark", "evacuation", "unload classes, update refs", "major", "minor");

    /**
     * The cause of a pause name ("Young (Normal) (G1 Evacuation Pause)" → "G1 Evacuation Pause"): the last top-level
     * parenthesised group that is not a pause kind; parentheses nest ("Full (System.gc())").
     */
    static String causeOf(String name) {
        List<String> groups = groups(name);
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (!PAUSE_KINDS.contains(groups.get(i).toLowerCase(Locale.ROOT))) {
                return groups.get(i);
            }
        }
        return null;
    }

    static List<String> groups(String text) {
        List<String> groups = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                if (depth++ == 0) {
                    start = i + 1;
                }
            } else if (c == ')' && depth > 0 && --depth == 0) {
                groups.add(text.substring(start, i).trim());
            }
        }
        return groups;
    }

    static GcPauseCategory categoryOf(String name, String cause, boolean concurrentCollector) {
        String n = name.toLowerCase(Locale.ROOT);
        String c = cause == null ? "" : cause.toLowerCase(Locale.ROOT);
        if (n.startsWith("full") || n.contains("degenerated")) {
            return GcPauseCategory.FULL;
        }
        if (c.contains("humongous")) {
            return GcPauseCategory.HUMONGOUS;
        }
        if (n.startsWith("young (mixed)") || n.startsWith("mixed")) {
            return GcPauseCategory.MIXED;
        }
        if (n.startsWith("young") || n.startsWith("initial mark")) {
            return GcPauseCategory.YOUNG;
        }
        if (n.startsWith("remark")) {
            return GcPauseCategory.REMARK;
        }
        if (n.startsWith("cleanup")) {
            return GcPauseCategory.CLEANUP;
        }
        if (concurrentCollector || n.startsWith("mark") || n.startsWith("relocate") || n.startsWith("init ")
                || n.startsWith("final ")) {
            return GcPauseCategory.CONCURRENT_PAUSE;
        }
        return GcPauseCategory.OTHER;
    }

    static boolean concurrentCollector(String collector) {
        if (collector == null) {
            return false;
        }
        String c = collector.toLowerCase(Locale.ROOT);
        return c.startsWith("z") || c.contains("shenandoah");
    }

    Pause pause(int gcId, double endUptimeMs, String name, double pauseMs) {
        Pause pause = new Pause();
        pause.gcId = gcId;
        pause.pauseMs = pauseMs;
        pause.uptimeMs = Math.max(0, endUptimeMs - pauseMs);
        String trimmed = name.trim();
        pause.name = "Pause " + trimmed;
        pause.cause = causeOf(trimmed);
        if (trimmed.contains("Evacuation Failure")) {
            evacuationFailedGcIds.add(gcId);
        }
        if (trimmed.contains("Degenerated")) {
            degenerated++;
        }
        pause.category = categoryOf(trimmed, pause.cause, concurrentCollector(collector));
        pauses.add(pause);
        return pause;
    }
}
