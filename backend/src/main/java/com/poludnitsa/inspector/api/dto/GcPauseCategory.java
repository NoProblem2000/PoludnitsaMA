package com.poludnitsa.inspector.api.dto;

/** Kind of a stop-the-world GC pause, for the scatter plot. */
public enum GcPauseCategory {
    YOUNG,
    MIXED,
    FULL,
    HUMONGOUS,
    REMARK,
    CLEANUP,
    CONCURRENT_PAUSE,
    OTHER
}
