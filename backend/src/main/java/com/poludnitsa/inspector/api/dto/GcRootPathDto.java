package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A shortest path to a GC root searched on demand in an open heap dump.
 *
 * @param steps            GC root first; empty when no path was found within the search limits
 * @param text             object first, as in the snapshot data
 * @param rootKind         kind of the root reached, {@code null} if none
 * @param strongPathExists a path made only of strong references exists: the object is really kept alive. With
 *                         {@link PathMode#ALL_REFERENCES} the shortest path may still go through a weak reference.
 * @param visitedObjects   objects the search looked at
 * @param truncated        the search stopped at its limits before finding a root
 */
public record GcRootPathDto(
        long objectId,
        PathMode mode,
        List<GcRootPathStepDto> steps,
        String text,
        String rootKind,
        boolean strongPathExists,
        int visitedObjects,
        boolean truncated
) {
    public GcRootPathDto {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
