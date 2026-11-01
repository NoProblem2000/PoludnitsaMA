package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A frame of a thread stack.
 *
 * @param fileName {@code null} when unknown
 * @param lineNumber -1 when unknown, -2 for native methods
 * @param locks lock lines of this frame as in jstack: "waiting to lock java.lang.Object@1b6d3586", "locked …"
 */
public record StackFrameDto(
        String className,
        String methodName,
        String fileName,
        int lineNumber,
        PackageCategory packageCategory,
        List<String> locks
) {
    public StackFrameDto {
        locks = locks == null ? List.of() : List.copyOf(locks);
    }
}
