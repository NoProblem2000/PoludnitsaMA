package com.poludnitsa.inspector.inspection;

import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import java.util.List;

/**
 * One heuristic over an open heap dump. Rules are Spring beans, so a new one is added by declaring a
 * {@code @Component} implementing this interface.
 *
 * <p>Rules run while the dump is still on disk (it is deleted after the analysis), must be read-only and must check
 * {@link InspectionContext#outOfBudget()} in their loops.
 */
public interface LeakInspectionRule {

    /** Stable identifier, e.g. {@code "wildfly.classloader-leak"}. */
    String id();

    /** Short human-readable name. */
    String title();

    FindingCategory category();

    /**
     * Whether the heap contains anything this rule looks at; inapplicable rules are skipped and reported as such.
     */
    boolean isApplicable(InspectionContext context);

    List<InspectionFindingDto> inspect(InspectionContext context);
}
