package com.poludnitsa.inspector.api.dto;

/** What a call tree weighs. */
public enum ProfileMetric {
    /** time on CPU: execution samples x sampling interval */
    CPU,
    /** wall-clock time: running, plus waiting in park / monitor / sleep / socket / file I/O, with its stack */
    WALL,
    /** allocated bytes */
    ALLOC
}
