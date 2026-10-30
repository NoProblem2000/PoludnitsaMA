package com.poludnitsa.inspector.api.dto;

/**
 * One hop of a reference chain. Paths are ordered from the GC root (index 0) to the inspected object (last).
 *
 * @param referenceName field name, array index ({@code "[42]"}) or {@code null}, through which this object
 *                      references the <em>next</em> step of the path
 * @param gcRoot        {@code true} only for the first step
 * @param gcRootKind    NetBeans {@code GCRoot#getKind()} (e.g. "thread object", "JNI global", "sticky class"),
 *                      {@code null} when {@code gcRoot == false}
 */
public record GcRootPathStepDto(
        long objectId,
        String className,
        String referenceName,
        boolean gcRoot,
        String gcRootKind
) {
}
