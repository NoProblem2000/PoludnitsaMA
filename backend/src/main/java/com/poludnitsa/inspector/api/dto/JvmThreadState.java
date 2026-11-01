package com.poludnitsa.inspector.api.dto;

/** java.lang.Thread.State of a thread in a dump. */
public enum JvmThreadState {
    NEW,
    RUNNABLE,
    BLOCKED,
    WAITING,
    TIMED_WAITING,
    TERMINATED
}
