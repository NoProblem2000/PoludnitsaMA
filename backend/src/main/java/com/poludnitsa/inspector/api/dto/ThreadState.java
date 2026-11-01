package com.poludnitsa.inspector.api.dto;

/** State of a thread lane in a timeline column, by the time it spent in it. */
public enum ThreadState {
    RUNNABLE,
    BLOCKED,
    WAITING,
    IO
}
