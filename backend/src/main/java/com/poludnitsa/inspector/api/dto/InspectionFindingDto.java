package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Diagnosis produced by one inspection rule: what leaks, why, who holds it and how to fix it.
 *
 * @param id                  unique within a snapshot ({@code ruleId#key})
 * @param ruleId              stable rule identifier, e.g. {@code "wildfly.threadlocal-pool-leak"}
 * @param wastedMemoryBytes   retained bytes attributable to the problem
 * @param affectedInstances   instances, threads, sessions or entries matched by the rule
 * @param suspectClassName    class responsible for holding the memory
 * @param fieldName           field of {@code suspectClassName} that holds it, {@code null} if not applicable
 * @param gcRootPath          representative path from a GC root to the suspect, GC root first
 * @param gcRootPathText      the same path as text, object first
 * @param evidence            facts found in the heap that support the diagnosis (threads, sizes, top types, ...)
 * @param whyItHappens        root-cause explanation in Russian
 * @param howToFixCodeSnippet Java code / configuration illustrating the fix
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
        String gcRootPathText,
        List<String> evidence,
        String whyItHappens,
        String howToFixCodeSnippet
) {
    public InspectionFindingDto {
        gcRootPath = gcRootPath == null ? List.of() : List.copyOf(gcRootPath);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
