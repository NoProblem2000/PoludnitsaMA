package com.poludnitsa.inspector.api.dto;

/** Why a thread looks stuck in a series of dumps. */
public enum StuckKind {
    CPU_LOOP,
    DB_QUERY,
    IO_WAIT,
    LOCK_WAIT,
    RUNNABLE
}
