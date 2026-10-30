package com.poludnitsa.inspector.api.dto;

/** Lifecycle of a heap snapshot inside the current inspector session. */
public enum SnapshotStatus {
    /** Target JVM is writing the .hprof file. */
    DUMPING,
    /** NetBeans profiler lib is building on-disk indexes. */
    INDEXING,
    READY,
    FAILED
}
