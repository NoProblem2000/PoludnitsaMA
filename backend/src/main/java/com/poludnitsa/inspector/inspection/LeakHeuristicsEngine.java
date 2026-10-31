package com.poludnitsa.inspector.inspection;

import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.InspectionRuleDto;
import com.poludnitsa.inspector.api.dto.InspectionRuleRunDto;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.heap.GcRootPathResolver;
import com.poludnitsa.inspector.inspection.rules.hazelcast.HazelcastSerializedHeapDataInspector;
import com.poludnitsa.inspector.inspection.rules.hazelcast.OrphanedHazelcastListenerInspector;
import com.poludnitsa.inspector.inspection.rules.jakarta.CdiScopeMismatchInspector;
import com.poludnitsa.inspector.inspection.rules.jakarta.HibernatePersistenceContextBloatInspector;
import com.poludnitsa.inspector.inspection.rules.jakarta.ThreadLocalLeakInspector;
import com.poludnitsa.inspector.inspection.rules.jakarta.UndertowHttpSessionBloatInspector;
import com.poludnitsa.inspector.inspection.rules.jakarta.WildFlyClassLoaderLeakInspector;
import com.poludnitsa.inspector.inspection.rules.jvm.BoxedPrimitivesOverheadInspector;
import com.poludnitsa.inspector.inspection.rules.jvm.DuplicateStringsAndByteArraysInspector;
import com.poludnitsa.inspector.inspection.rules.jvm.EmptyAndSparseCollectionsInspector;
import com.poludnitsa.inspector.inspection.rules.jvm.UnboundedCollectionAndCacheInspector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.netbeans.lib.profiler.heap.Heap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link LeakInspectionRule} against an open heap. A failing or slow rule never breaks the analysis:
 * exceptions are recorded in its {@link InspectionRuleRunDto}, and each rule gets its own time budget.
 */
@Component
public class LeakHeuristicsEngine {

    private static final Logger log = LoggerFactory.getLogger(LeakHeuristicsEngine.class);

    /** Findings ordered by severity, then by wasted memory. */
    public record InspectionOutcome(List<InspectionFindingDto> findings, List<InspectionRuleRunDto> runs) {
    }

    private final List<LeakInspectionRule> rules;
    private final InspectorProperties.Inspections settings;

    public LeakHeuristicsEngine(List<LeakInspectionRule> rules, InspectorProperties properties) {
        this.rules = rules.stream()
                .sorted(Comparator.comparing(LeakInspectionRule::category).thenComparing(LeakInspectionRule::id))
                .toList();
        this.settings = properties.inspections();
    }

    /** The built-in rules, for use outside a Spring context. */
    public static List<LeakInspectionRule> defaultRules() {
        return List.of(
                new WildFlyClassLoaderLeakInspector(),
                new ThreadLocalLeakInspector(),
                new CdiScopeMismatchInspector(),
                new HibernatePersistenceContextBloatInspector(),
                new UndertowHttpSessionBloatInspector(),
                new HazelcastSerializedHeapDataInspector(),
                new OrphanedHazelcastListenerInspector(),
                new UnboundedCollectionAndCacheInspector(),
                new DuplicateStringsAndByteArraysInspector(),
                new EmptyAndSparseCollectionsInspector(),
                new BoxedPrimitivesOverheadInspector());
    }

    public static LeakHeuristicsEngine withDefaultRules(InspectorProperties properties) {
        return new LeakHeuristicsEngine(defaultRules(), properties);
    }

    public List<InspectionRuleDto> describeRules() {
        return rules.stream()
                .map(rule -> new InspectionRuleDto(rule.id(), rule.title(), rule.category(), isEnabled(rule)))
                .toList();
    }

    /** @param progress receives a short message per rule */
    public InspectionOutcome inspect(Heap heap, long totalHeapBytes, GcRootPathResolver paths,
                                     Consumer<String> progress) {
        List<InspectionFindingDto> findings = new ArrayList<>();
        List<InspectionRuleRunDto> runs = new ArrayList<>();
        if (!settings.enabled()) {
            return new InspectionOutcome(findings, runs);
        }
        InspectionContext context = new InspectionContext(heap, paths, totalHeapBytes, settings);
        for (LeakInspectionRule rule : rules) {
            if (!isEnabled(rule)) {
                continue;
            }
            progress.accept("Inspection: " + rule.title());
            context.startRule(settings.ruleTimeBudget().toNanos());
            long started = System.nanoTime();
            List<InspectionFindingDto> ruleFindings = List.of();
            boolean applicable = false;
            String error = null;
            try {
                applicable = rule.isApplicable(context);
                if (applicable) {
                    ruleFindings = rule.inspect(context).stream()
                            .sorted(ORDER)
                            .limit(settings.maxFindingsPerRule())
                            .toList();
                }
            } catch (RuntimeException | StackOverflowError e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("Inspection {} failed", rule.id(), e);
            }
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            findings.addAll(ruleFindings);
            runs.add(new InspectionRuleRunDto(rule.id(), rule.title(), rule.category(), durationMs,
                    ruleFindings.size(), applicable, context.truncated(), error));
            log.debug("Inspection {}: {} findings in {} ms", rule.id(), ruleFindings.size(), durationMs);
        }
        findings.sort(ORDER);
        return new InspectionOutcome(withoutGenericDuplicates(findings), runs);
    }

    /**
     * A generic JVM finding about the same class and field as a framework-specific one (e.g. a big map that is
     * really Undertow's session store or a CDI bean's cache) adds nothing: the specific diagnosis explains it better.
     */
    static List<InspectionFindingDto> withoutGenericDuplicates(List<InspectionFindingDto> findings) {
        Set<String> specific = new HashSet<>();
        for (InspectionFindingDto finding : findings) {
            if (finding.category() != FindingCategory.JVM_CORE && finding.fieldName() != null) {
                specific.add(finding.suspectClassName() + "#" + finding.fieldName());
            }
        }
        return findings.stream()
                .filter(f -> f.category() != FindingCategory.JVM_CORE || f.fieldName() == null
                        || !specific.contains(f.suspectClassName() + "#" + f.fieldName()))
                .toList();
    }

    private boolean isEnabled(LeakInspectionRule rule) {
        List<String> disabled = settings.disabledRules();
        return disabled == null || !Set.copyOf(disabled).contains(rule.id());
    }

    private static final Comparator<InspectionFindingDto> ORDER = Comparator
            .comparing(InspectionFindingDto::severity).reversed()
            .thenComparing(Comparator.comparingLong(InspectionFindingDto::wastedMemoryBytes).reversed())
            .thenComparing(InspectionFindingDto::id);
}
