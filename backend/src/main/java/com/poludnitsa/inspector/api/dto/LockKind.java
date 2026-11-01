package com.poludnitsa.inspector.api.dto;

/** A monitor ({@code synchronized}) or an ownable synchronizer (java.util.concurrent lock). */
public enum LockKind {
    MONITOR,
    SYNCHRONIZER
}
