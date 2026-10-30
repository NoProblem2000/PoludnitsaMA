package com.poludnitsa.inspector.api.dto;

/** Lifecycle of a snapshot inside the current inspector session. */
public enum SnapshotStatus {
    /** Target JVM is writing the .hprof file (a stop-the-world pause on the target). */
    DUMPING,
    /** NetBeans profiler lib is parsing the dump and building on-disk indexes. */
    INDEXING,
    /** Dominator tree, retained sizes and GC root paths are being computed. */
    ANALYZING,
    READY,
    FAILED
}
