package com.poludnitsa.inspector.api.dto;

/**
 * One hop of a reference chain. Paths are ordered from the GC root (index 0) to the inspected object (last).
 *
 * @param className     class of the object; for a class object holding static fields: {@code class com.acme.Foo}
 * @param referenceName field name, {@code static NAME} or array index ({@code [42]}) through which this object
 *                      references the <em>next</em> step of the path; {@code null} for the last step
 * @param gcRoot        {@code true} only for the first step, when it is a GC root
 * @param gcRootKind    NetBeans {@code GCRoot#getKind()} ("sticky class", "thread object", "Java frame", ...), for
 *                      Java frames with the frame location; {@code null} when {@code gcRoot == false}
 */
public record GcRootPathStepDto(
        long objectId,
        String className,
        String referenceName,
        boolean gcRoot,
        String gcRootKind
) {
}
