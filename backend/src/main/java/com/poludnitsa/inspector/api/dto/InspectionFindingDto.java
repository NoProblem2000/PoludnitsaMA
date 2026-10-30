package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Result of one inspection rule applied to a heap snapshot.
 *
 * @param id                  unique within a snapshot (rule id + suspect object id)
 * @param ruleId              stable rule identifier, e.g. {@code "jakarta.threadlocal-in-managed-pool"}
 * @param wastedMemoryBytes   retained bytes attributable to the problem
 * @param affectedInstances   number of instances matched by the rule
 * @param suspectClassName    class holding the leaking reference
 * @param fieldName           field of {@code suspectClassName} holding the reference, {@code null} if not applicable
 * @param gcRootPath          representative path from a GC root to the suspect
 * @param whyItHappens        root-cause explanation (plain text / Markdown)
 * @param howToFixCodeSnippet Java code illustrating the fix
 */
public record InspectionFindingDto(
        String id,
        String ruleId,
        Severity severity,
        FindingCategory category,
        String title,
        long wastedMemoryBytes,
        long affectedInstances,
        String suspectClassName,
        String fieldName,
        List<GcRootPathStepDto> gcRootPath,
        String whyItHappens,
        String howToFixCodeSnippet
) {
    public InspectionFindingDto {
        gcRootPath = gcRootPath == null ? List.of() : List.copyOf(gcRootPath);
    }
}
