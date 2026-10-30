package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Comparison of two snapshots (current minus baseline), like dotMemory's "Compare snapshots".
 *
 * @param retainedDeltaAvailable both snapshots are deep, so retained deltas are set
 * @param samePid                both snapshots come from the same process
 * @param chronological          the baseline was taken before the current snapshot
 * @param newClasses             classes with instances only in the current snapshot
 * @param removedClasses         classes with instances only in the baseline
 * @param growingClasses         classes whose shallow size grew
 * @param classes                changed classes ordered by {@code survivalScore}, then by absolute growth
 */
public record SnapshotDiffDto(
        SnapshotSummaryDto baseline,
        SnapshotSummaryDto current,
        boolean retainedDeltaAvailable,
        boolean samePid,
        boolean chronological,
        long deltaTotalInstances,
        long deltaTotalBytes,
        int newClasses,
        int removedClasses,
        int growingClasses,
        List<ClassDiffDto> classes
) {
    public SnapshotDiffDto {
        classes = classes == null ? List.of() : List.copyOf(classes);
    }
}
