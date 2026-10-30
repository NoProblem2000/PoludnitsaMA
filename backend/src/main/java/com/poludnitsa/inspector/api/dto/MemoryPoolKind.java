package com.poludnitsa.inspector.api.dto;

/** Collector-independent role of a memory pool, resolved from the pool name. */
public enum MemoryPoolKind {
    /** "G1 Eden Space", "PS Eden Space", "Eden Space". */
    EDEN,
    /** "G1 Survivor Space", "PS Survivor Space", "Survivor Space". */
    SURVIVOR,
    /** "G1 Old Gen", "PS Old Gen", "Tenured Gen", "ZGC Old Generation". */
    OLD,
    METASPACE,
    COMPRESSED_CLASS_SPACE,
    /** "CodeHeap 'non-nmethods'", "CodeHeap 'profiled nmethods'", "CodeHeap 'non-profiled nmethods'", "CodeCache". */
    CODE_CACHE,
    /** Single-pool collectors ("ZHeap", "Shenandoah") and "ZGC Young Generation". */
    OTHER_HEAP,
    OTHER_NON_HEAP
}
