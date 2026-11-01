package com.poludnitsa.inspector.gc;

import com.poludnitsa.inspector.api.dto.GcPauseCategory;
import java.io.BufferedReader;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming parser of Java 11+ Unified Logging GC logs ({@code -Xlog:gc*,safepoint*}) of G1, Parallel, Serial, ZGC
 * (single and generational) and Shenandoah. One line at a time: the file is never held in memory, and at most
 * {@code maxEvents} events are kept.
 *
 * <p>Decorations are any of {@code [time]}, {@code [uptime]} ({@code 1.234s}), {@code [uptimemillis]},
 * {@code [uptimenanos]}, {@code [level]} and {@code [tags]}; a pause line is logged when the pause ends, so its start
 * is the uptime minus its duration.
 */
public final class UnifiedGcLogParser {

    private static final String NUM = "(\\d+(?:[.,]\\d+)?)";
    private static final String SIZE = NUM + "([BKMGT])";
    private static final Pattern GC_ID = Pattern.compile("^GC\\((\\d+)\\)\\s+(.*)$");
    private static final Pattern SIZED_PAUSE = Pattern.compile(
            "^(?:[yYoO]: )?Pause (.+?) " + SIZE + "->" + SIZE + "\\(" + SIZE + "\\) " + NUM + "ms$");
    private static final Pattern PAUSE = Pattern.compile("^(?:[yYoO]: )?Pause (.+?) " + NUM + "ms$");
    private static final Pattern Z_COLLECTION_START = Pattern.compile(
            "^(?:Garbage|Major|Minor) Collection \\((?!.*%\\))(.+)\\)$");
    private static final Pattern Z_COLLECTION = Pattern.compile(
            "^(Garbage|Major|Minor) Collection \\((.+)\\) " + SIZE + "\\((\\d+)%\\)->" + SIZE + "\\((\\d+)%\\).*$");
    private static final Pattern SHENANDOAH_CLEANUP = Pattern.compile(
            "^Concurrent cleanup " + SIZE + "->" + SIZE + "\\(" + SIZE + "\\) " + NUM + "ms$");
    private static final Pattern CONCURRENT_CYCLE = Pattern.compile("^Concurrent (?:(?:Mark|Undo) )?Cycle " + NUM + "ms$");
    private static final Pattern G1_REGIONS = Pattern.compile("^(Eden|Survivor|Old|Humongous) regions: (\\d+)->(\\d+)");
    private static final Pattern OLD_GEN = Pattern.compile(
            "^(?:ParOldGen|PSOldGen|Tenured): " + SIZE + "(?:\\(" + SIZE + "\\))?->" + SIZE);
    private static final Pattern REGION_SIZE = Pattern.compile("^Heap [Rr]egion [Ss]ize: " + SIZE);
    private static final Pattern USING = Pattern.compile("^Using (\\D.*)$");
    private static final Pattern VERSION = Pattern.compile("^Version: (\\S+)");
    private static final Pattern SAFEPOINT = Pattern.compile("^Safepoint \"([^\"]+)\", Time since last: \\d+ ns, "
            + "Reaching safepoint: (\\d+) ns, (?:Cleanup: \\d+ ns, )?At safepoint: (\\d+) ns, "
            + "(?:Leaving safepoint: \\d+ ns, )?Total: (\\d+) ns");
    private static final Pattern SAFEPOINT_11_ENTER = Pattern.compile("^Entering safepoint region: (.+)$");
    private static final Pattern SAFEPOINT_11_TOTAL = Pattern.compile(
            "^Total time for which application threads were stopped: " + NUM + " seconds, Stopping threads took: "
                    + NUM + " seconds");
    private static final Pattern ALLOCATION_STALL = Pattern.compile("Allocation Stall \\((.+)\\) " + NUM + "ms");
    private static final Pattern TRIGGER = Pattern.compile("^Trigger(?: \\(\\w+\\))?: (.+)$");
    private static final Pattern UPTIME_S = Pattern.compile("^" + NUM + "s$");
    private static final Pattern UPTIME_MS = Pattern.compile("^(\\d+)ms$");
    private static final Pattern UPTIME_NS = Pattern.compile("^(\\d+)ns$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final int maxEvents;
    private final GcLogData data = new GcLogData();
    private final Map<Integer, double[]> pendingOld = new HashMap<>();
    private final Map<Integer, double[]> pendingRegions = new HashMap<>();
    private final Map<Integer, String> cycleCauses = new HashMap<>();
    private String pendingSafepoint;
    private String pendingTrigger;
    private Long firstTimeMs;
    private double uptimeMs;
    private boolean hasUptime;

    private UnifiedGcLogParser(int maxEvents) {
        this.maxEvents = maxEvents;
    }

    public static GcLogData parse(BufferedReader reader, int maxEvents) throws IOException {
        UnifiedGcLogParser parser = new UnifiedGcLogParser(maxEvents);
        String line;
        while ((line = reader.readLine()) != null) {
            parser.line(line);
            if (parser.data.truncated) {
                break;
            }
        }
        return parser.finish();
    }

    private void line(String raw) {
        if (raw.isBlank()) {
            return;
        }
        int i = 0;
        boolean decorated = false;
        hasUptime = false;
        while (i < raw.length() && raw.charAt(i) == '[') {
            int end = raw.indexOf(']', i);
            if (end < 0) {
                break;
            }
            decoration(raw.substring(i + 1, end).trim());
            decorated = true;
            i = end + 1;
            while (i < raw.length() && raw.charAt(i) == ' ') {
                i++;
            }
        }
        if (!decorated) {
            data.unparsedLines++;
            return;
        }
        data.parsedLines++;
        if (hasUptime) {
            data.seen(uptimeMs);
        }
        message(raw.substring(i).trim());
    }

    private void decoration(String token) {
        Matcher m;
        if ((m = UPTIME_S.matcher(token)).matches()) {
            uptime(number(m.group(1)) * 1000);
        } else if ((m = UPTIME_MS.matcher(token)).matches()) {
            uptime(Double.parseDouble(m.group(1)));
        } else if ((m = UPTIME_NS.matcher(token)).matches()) {
            uptime(Double.parseDouble(m.group(1)) / 1e6);
        } else if (token.length() > 19 && Character.isDigit(token.charAt(0)) && token.charAt(4) == '-'
                && token.charAt(10) == 'T') {
            try {
                long epoch = OffsetDateTime.parse(token, TIME).toInstant().toEpochMilli();
                if (firstTimeMs == null) {
                    firstTimeMs = epoch;
                }
                if (!hasUptime) {
                    // only [time]: the uptime is relative to the first line
                    uptimeMs = epoch - firstTimeMs;
                    hasUptime = true;
                }
            } catch (DateTimeParseException ignored) {
                // another date format: no time
            }
        }
    }

    private void uptime(double ms) {
        uptimeMs = ms;
        hasUptime = true;
    }

    private void message(String message) {
        Matcher m = GC_ID.matcher(message);
        if (m.matches()) {
            gcMessage(Integer.parseInt(m.group(1)), m.group(2));
            return;
        }
        if ((m = SAFEPOINT.matcher(message)).find()) {
            double total = Double.parseDouble(m.group(4)) / 1e6;
            safepoint(m.group(1), Double.parseDouble(m.group(2)) / 1e6, Double.parseDouble(m.group(3)) / 1e6, total);
        } else if ((m = SAFEPOINT_11_ENTER.matcher(message)).matches()) {
            pendingSafepoint = m.group(1).trim();
        } else if ((m = SAFEPOINT_11_TOTAL.matcher(message)).find()) {
            double total = number(m.group(1)) * 1000;
            double ttsp = number(m.group(2)) * 1000;
            safepoint(pendingSafepoint != null ? pendingSafepoint : "unknown", ttsp, Math.max(0, total - ttsp), total);
            pendingSafepoint = null;
        } else if ((m = USING.matcher(message)).matches() && data.collector == null) {
            data.collector = collectorName(m.group(1));
        } else if ((m = VERSION.matcher(message)).find() && data.jvmVersion == null) {
            data.jvmVersion = m.group(1);
        } else if ((m = REGION_SIZE.matcher(message)).find()) {
            data.regionSizeMb = mb(m.group(1), m.group(2));
        } else if ((m = TRIGGER.matcher(message)).matches()) {
            pendingTrigger = trigger(m.group(1));
        } else if ((m = ALLOCATION_STALL.matcher(message)).find()) {
            data.allocationStalls++;
            data.allocationStallMs += number(m.group(2));
        } else if (message.contains("To-space exhausted")) {
            data.evacuationFailedGcIds.add(-1 - data.evacuationFailedGcIds.size());
        }
    }

    private void gcMessage(int gcId, String message) {
        Matcher m;
        if ((m = SIZED_PAUSE.matcher(message)).matches()) {
            if (!room()) {
                return;
            }
            GcLogData.Pause pause = data.pause(gcId, uptimeMs, m.group(1), number(m.group(8)));
            pause.heapBeforeMb = mb(m.group(2), m.group(3));
            pause.heapAfterMb = mb(m.group(4), m.group(5));
            pause.heapTotalMb = mb(m.group(6), m.group(7));
            attachOld(pause);
        } else if ((m = PAUSE.matcher(message)).matches()) {
            if (room()) {
                GcLogData.Pause pause = data.pause(gcId, uptimeMs, m.group(1), number(m.group(2)));
                String cycle = cycleCauses.get(gcId);
                if (cycle != null && (pause.cause == null || pause.category == GcPauseCategory.CONCURRENT_PAUSE)) {
                    pause.cause = cycle;
                }
            }
        } else if ((m = Z_COLLECTION_START.matcher(message)).matches()) {
            // logged when a ZGC cycle starts: the cause of its pauses, even when the log ends before the cycle
            cycleCauses.put(gcId, m.group(1).trim());
        } else if ((m = Z_COLLECTION.matcher(message)).matches()) {
            if (data.collector == null || "ZGC".equals(data.collector)) {
                data.collector = "Garbage".equals(m.group(1)) ? "ZGC" : "ZGC (generational)";
            }
            double before = mb(m.group(3), m.group(4));
            int percent = Integer.parseInt(m.group(5));
            Double total = percent > 0 ? before * 100 / percent : null;
            if (room()) {
                data.collections.add(new GcLogData.Collection(gcId, uptimeMs, m.group(2).trim(), before,
                        mb(m.group(6), m.group(7)), total));
            }
            data.concurrentCycles++;
        } else if ((m = SHENANDOAH_CLEANUP.matcher(message)).matches()) {
            if (data.collector == null) {
                data.collector = "Shenandoah";
            }
            if (room()) {
                data.collections.add(new GcLogData.Collection(gcId, uptimeMs,
                        pendingTrigger != null ? pendingTrigger : "Concurrent cycle", mb(m.group(1), m.group(2)),
                        mb(m.group(3), m.group(4)), mb(m.group(5), m.group(6))));
            }
            pendingTrigger = null;
            data.concurrentCycles++;
        } else if (CONCURRENT_CYCLE.matcher(message).matches()) {
            data.concurrentCycles++;
        } else if ((m = G1_REGIONS.matcher(message)).find()) {
            if ("Old".equals(m.group(1)) || "Humongous".equals(m.group(1))) {
                double[] regions = pendingRegions.computeIfAbsent(gcId, k -> new double[2]);
                regions[0] += Integer.parseInt(m.group(2));
                regions[1] += Integer.parseInt(m.group(3));
            }
        } else if ((m = OLD_GEN.matcher(message)).find()) {
            pendingOld.put(gcId, new double[] {mb(m.group(1), m.group(2)), mb(m.group(5), m.group(6))});
        } else if (message.startsWith("To-space exhausted") || message.contains("Evacuation Failure")) {
            data.evacuationFailedGcIds.add(gcId);
        } else if ((m = ALLOCATION_STALL.matcher(message)).find()) {
            data.allocationStalls++;
            data.allocationStallMs += number(m.group(2));
        } else if ((m = USING.matcher(message)).matches() && message.startsWith("Using ") && data.collector == null
                && !Character.isDigit(m.group(1).charAt(0))) {
            data.collector = collectorName(m.group(1));
        }
    }

    /** Old generation before / after of the pause, from the region or generation lines logged before it. */
    private void attachOld(GcLogData.Pause pause) {
        double[] old = pendingOld.remove(pause.gcId);
        if (old != null) {
            pause.oldBeforeMb = old[0];
            pause.oldAfterMb = old[1];
        }
        double[] regions = pendingRegions.remove(pause.gcId);
        if (regions != null && data.regionSizeMb != null) {
            pause.oldBeforeMb = regions[0] * data.regionSizeMb;
            pause.oldAfterMb = regions[1] * data.regionSizeMb;
        }
    }

    private void safepoint(String operation, double ttspMs, double operationMs, double totalMs) {
        if (room()) {
            data.safepoints.add(new GcLogData.Safepoint(Math.max(0, uptimeMs - totalMs), operation, ttspMs, operationMs,
                    totalMs));
        }
    }

    private boolean room() {
        if (data.pauses.size() + data.collections.size() + data.safepoints.size() >= maxEvents) {
            data.truncated = true;
            return false;
        }
        return true;
    }

    private GcLogData finish() {
        if (data.collector == null) {
            boolean g1 = data.pauses.stream().anyMatch(p -> p.cause != null && p.cause.startsWith("G1"));
            boolean shenandoah = data.pauses.stream().anyMatch(p -> p.name.startsWith("Pause Init Mark")
                    || p.name.startsWith("Pause Final Mark"));
            boolean z = data.pauses.stream().anyMatch(p -> p.name.startsWith("Pause Mark Start"));
            data.collector = g1 ? "G1" : shenandoah ? "Shenandoah" : z ? "ZGC" : null;
        }
        return data;
    }

    static String collectorName(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("g1")) {
            return "G1";
        }
        if (t.contains("parallel")) {
            return "Parallel";
        }
        if (t.contains("serial")) {
            return "Serial";
        }
        if (t.contains("z garbage") || t.equals("zgc")) {
            return "ZGC";
        }
        if (t.contains("shenandoah")) {
            return "Shenandoah";
        }
        return null;
    }

    /** "Learning 1 of 5. Free (89M) is below initial threshold (89M)" → "Learning N of N". */
    private static String trigger(String text) {
        String first = text.split("\\.\\s", 2)[0];
        String cleaned = first.replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\d+(?:[.,]\\d+)?%?", "N").trim();
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    static double number(String text) {
        return Double.parseDouble(text.replace(',', '.'));
    }

    static double mb(String value, String unit) {
        double v = number(value);
        return switch (unit) {
            case "B" -> v / (1024 * 1024);
            case "K" -> v / 1024;
            case "G" -> v * 1024;
            case "T" -> v * 1024 * 1024;
            default -> v;
        };
    }
}
