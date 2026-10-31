package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * One way instances of a class are held: sampled instances whose GC root paths share the same shape (classes and
 * fields from the root, array indexes ignored).
 *
 * @param signature        the shape, root first: {@code Thread.threadLocals → ThreadLocalMap.table → ...}
 * @param steps            the full path of one representative instance
 * @param sampledInstances sampled instances with this shape
 * @param share            {@code sampledInstances / all sampled instances} of the class, 0..1
 */
public record RetentionPathDto(
        String signature,
        List<GcRootPathStepDto> steps,
        int sampledInstances,
        double share
) {
    public RetentionPathDto {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
