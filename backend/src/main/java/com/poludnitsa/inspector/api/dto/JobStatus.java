package com.poludnitsa.inspector.api.dto;

/** State of a background capture (thread dump series, live GC recording). */
public enum JobStatus {
    RUNNING,
    READY,
    FAILED
}
