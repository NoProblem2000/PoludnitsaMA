package com.poludnitsa.inspector.api.dto;

/**
 * How one rule ran on a snapshot.
 *
 * @param applicable {@code false} when the heap has none of the classes the rule looks at (e.g. no Hazelcast)
 * @param truncated  the rule hit its time or instance budget and looked at part of the heap only
 * @param error      the rule failed; other rules are not affected
 */
public record InspectionRuleRunDto(
        String ruleId,
        String title,
        FindingCategory category,
        long durationMs,
        int findings,
        boolean applicable,
        boolean truncated,
        String error
) {
}
