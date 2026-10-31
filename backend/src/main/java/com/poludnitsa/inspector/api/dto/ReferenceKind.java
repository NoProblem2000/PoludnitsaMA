package com.poludnitsa.inspector.api.dto;

/**
 * How one object of a GC root path references the next one. Everything but {@link #STRONG} is the {@code referent}
 * of a {@code java.lang.ref.Reference}: it does not keep the object alive on its own.
 */
public enum ReferenceKind {
    STRONG,
    /** {@code WeakReference.referent}: cleared by the next GC that finds no strong path */
    WEAK,
    /** {@code SoftReference.referent}: cleared only under memory pressure, before an OutOfMemoryError */
    SOFT,
    /** {@code PhantomReference.referent}: the object is already finalized, only post-mortem cleanup */
    PHANTOM,
    /** {@code FinalReference.referent} ({@code java.lang.ref.Finalizer}): waits for {@code finalize()} */
    FINALIZER
}
