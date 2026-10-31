package com.poludnitsa.inspector.api.dto;

/** Which references a shortest-path search to a GC root may follow. */
public enum PathMode {
    /** only strong references: the path that really keeps the object alive (default) */
    STRONG_ONLY,
    /** also {@code referent} fields of weak, soft, phantom and final references, marked by {@link ReferenceKind} */
    ALL_REFERENCES
}
