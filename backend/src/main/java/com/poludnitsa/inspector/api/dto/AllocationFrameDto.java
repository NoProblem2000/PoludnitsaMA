package com.poludnitsa.inspector.api.dto;

/**
 * A stack frame of an allocation.
 *
 * @param line        source line, {@code -1} when unknown (no debug info, native)
 * @param application the class is application code (not JDK, application server or a known library): the first
 *                    such frame from the top is where your code allocates
 */
public record AllocationFrameDto(
        String className,
        String method,
        int line,
        boolean application
) {
}
