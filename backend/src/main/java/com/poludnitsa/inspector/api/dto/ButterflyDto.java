package com.poludnitsa.inspector.api.dto;

/**
 * A method seen from both sides, as in dotTrace.
 *
 * @param callers inverted tree rooted at the method: its children are the callers, their children the callers of
 *                those; values are the weight of the method through each path
 * @param callees tree rooted at the method: what it calls, merged over all its calls (recursion counted once)
 */
public record ButterflyDto(
        String profileId,
        ProfileMetric metric,
        String unit,
        String className,
        String methodName,
        long totalValue,
        long selfValue,
        double percent,
        FlameNodeDto callers,
        FlameNodeDto callees
) {
}
