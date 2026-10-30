package com.poludnitsa.inspector.jmx;

import com.poludnitsa.inspector.api.dto.GcGeneration;
import java.util.Locale;

/**
 * Classifies {@code GarbageCollectorMXBean}s by name. Known names on Java 17+:
 * <ul>
 *   <li>G1: "G1 Young Generation", "G1 Old Generation", "G1 Concurrent GC" (JDK 20+, Remark/Cleanup pauses)</li>
 *   <li>Parallel: "PS Scavenge", "PS MarkSweep"; Serial: "Copy", "MarkSweepCompact"</li>
 *   <li>ZGC: "ZGC Cycles", "ZGC Pauses"; generational ZGC: "ZGC Minor/Major Cycles", "ZGC Minor/Major Pauses"</li>
 *   <li>Shenandoah: "Shenandoah Cycles", "Shenandoah Pauses"</li>
 * </ul>
 */
public final class GcCollectorClassifier {

    private GcCollectorClassifier() {
    }

    public static GcGeneration generation(String collectorName) {
        String n = collectorName.toLowerCase(Locale.ROOT);
        if (n.contains("young") || n.contains("minor") || n.contains("scavenge") || n.equals("copy")
                || n.contains("parnew")) {
            return GcGeneration.YOUNG;
        }
        if (n.contains("old") || n.contains("major") || n.contains("marksweep") || n.contains("full")) {
            return GcGeneration.OLD;
        }
        if (n.contains("concurrent")) {
            return GcGeneration.CONCURRENT;
        }
        if (n.startsWith("zgc") || n.startsWith("shenandoah")) {
            // Single-generation ZGC / Shenandoah: every cycle covers the whole heap.
            return GcGeneration.OLD;
        }
        return GcGeneration.UNKNOWN;
    }

    /** {@code false} for beans whose collection time is the duration of concurrent cycles, not of pauses. */
    public static boolean stopTheWorld(String collectorName) {
        return !collectorName.toLowerCase(Locale.ROOT).contains("cycles");
    }
}
