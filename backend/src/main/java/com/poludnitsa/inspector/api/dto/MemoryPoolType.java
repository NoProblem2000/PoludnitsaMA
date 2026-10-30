package com.poludnitsa.inspector.api.dto;

/** Mirrors {@link java.lang.management.MemoryType}. Metaspace and Code Cache are {@link #NON_HEAP}. */
public enum MemoryPoolType {
    HEAP,
    NON_HEAP
}
