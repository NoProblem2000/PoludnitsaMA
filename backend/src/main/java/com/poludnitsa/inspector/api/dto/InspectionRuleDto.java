package com.poludnitsa.inspector.api.dto;

/** A registered inspection rule. */
public record InspectionRuleDto(
        String ruleId,
        String title,
        FindingCategory category,
        boolean enabled
) {
}
