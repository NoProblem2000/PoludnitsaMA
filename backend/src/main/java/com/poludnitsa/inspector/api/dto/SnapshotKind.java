package com.poludnitsa.inspector.api.dto;

/** What a snapshot was taken from. */
public enum SnapshotKind {
    /**
     * {@code GC.class_histogram -all}: instances and shallow bytes per class, taken in milliseconds without a Full
     * GC. Counts include unreachable objects not collected yet; no retained sizes.
     */
    LIVE_HISTOGRAM,
    /** Heap dump analysed with retained sizes, dominators and GC root paths. */
    DEEP
}
