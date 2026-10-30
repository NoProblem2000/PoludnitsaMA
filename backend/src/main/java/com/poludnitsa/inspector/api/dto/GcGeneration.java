package com.poludnitsa.inspector.api.dto;

/** Generation a {@code GarbageCollectorMXBean} works on, resolved from the collector name. */
public enum GcGeneration {
    /** e.g. "G1 Young Generation", "PS Scavenge", "Copy", "ZGC Minor Cycles". */
    YOUNG,
    /** e.g. "G1 Old Generation", "PS MarkSweep", "MarkSweepCompact", "ZGC Major Cycles". */
    OLD,
    /** Concurrent cycles / pauses, e.g. "G1 Concurrent GC", "ZGC Pauses", "Shenandoah Pauses". */
    CONCURRENT,
    UNKNOWN
}
