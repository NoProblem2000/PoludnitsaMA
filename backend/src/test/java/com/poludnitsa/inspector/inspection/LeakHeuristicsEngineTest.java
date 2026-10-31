package com.poludnitsa.inspector.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.InspectionRuleRunDto;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.heap.GcRootPathResolver;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.netbeans.lib.profiler.heap.Heap;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class LeakHeuristicsEngineTest {

    private final Heap heap = mock(Heap.class);

    @Test
    void failingRuleDoesNotStopTheOthers() {
        LeakHeuristicsEngine engine = new LeakHeuristicsEngine(List.of(
                rule("a.broken", FindingCategory.JVM_CORE, true, context -> {
                    throw new IllegalStateException("boom");
                }),
                rule("b.works", FindingCategory.JVM_CORE, true, context -> List.of(
                        finding(context, "b.works", Severity.WARNING, 10, null, null),
                        finding(context, "b.works", Severity.CRITICAL, 5, null, null))),
                rule("c.absent", FindingCategory.HAZELCAST_CLIENT, false, context -> List.of())),
                InspectorProperties.defaults());

        LeakHeuristicsEngine.InspectionOutcome outcome = engine.inspect(heap, 1000, resolver(), message -> {
        });

        assertThat(outcome.findings()).extracting(InspectionFindingDto::severity)
                .containsExactly(Severity.CRITICAL, Severity.WARNING);
        Map<String, InspectionRuleRunDto> runs = outcome.runs().stream()
                .collect(java.util.stream.Collectors.toMap(InspectionRuleRunDto::ruleId, Function.identity()));
        assertThat(runs.get("a.broken").error()).contains("boom");
        assertThat(runs.get("b.works").findings()).isEqualTo(2);
        assertThat(runs.get("c.absent").applicable()).isFalse();
    }

    @Test
    void disabledRulesAreSkipped() {
        InspectorProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "inspector.inspections.disabled-rules[0]", "b.works")))
                .bindOrCreate("inspector", InspectorProperties.class);
        LeakHeuristicsEngine engine = new LeakHeuristicsEngine(List.of(
                rule("b.works", FindingCategory.JVM_CORE, true,
                        context -> List.of(finding(context, "b.works", Severity.INFO, 1, null, null)))), properties);

        assertThat(engine.inspect(heap, 1000, resolver(), message -> {
        }).runs()).isEmpty();
        assertThat(engine.describeRules()).singleElement().satisfies(rule -> assertThat(rule.enabled()).isFalse());
    }

    @Test
    void genericFindingOnTheSameFieldAsASpecificOneIsDropped() {
        LeakHeuristicsEngine engine = new LeakHeuristicsEngine(List.of(
                rule("jvm.generic", FindingCategory.JVM_CORE, true, context -> List.of(
                        finding(context, "jvm.generic", Severity.CRITICAL, 100, "io.undertow.Manager", "sessions"),
                        finding(context, "jvm.generic", Severity.WARNING, 50, "com.acme.Cache", "entries"))),
                rule("undertow.specific", FindingCategory.JAKARTA_WILDFLY, true, context -> List.of(
                        finding(context, "undertow.specific", Severity.CRITICAL, 100, "io.undertow.Manager",
                                "sessions")))),
                InspectorProperties.defaults());

        List<InspectionFindingDto> findings = engine.inspect(heap, 1000, resolver(), message -> {
        }).findings();

        assertThat(findings).extracting(InspectionFindingDto::ruleId)
                .containsExactly("undertow.specific", "jvm.generic");
        assertThat(findings.get(1).fieldName()).isEqualTo("entries");
    }

    @Test
    void defaultRulesAreAllRegistered() {
        assertThat(LeakHeuristicsEngine.defaultRules()).extracting(LeakInspectionRule::id).containsExactlyInAnyOrder(
                "wildfly.classloader-leak", "wildfly.threadlocal-pool-leak", "cdi.scope-mismatch",
                "hibernate.persistence-context-bloat", "undertow.http-session-bloat", "hazelcast.serialized-heapdata",
                "hazelcast.orphaned-listeners", "jvm.unbounded-collection", "jvm.duplicate-strings",
                "jvm.empty-sparse-collections", "jvm.boxed-primitives");
    }

    private GcRootPathResolver resolver() {
        return new GcRootPathResolver(heap, 8);
    }

    private static InspectionFindingDto finding(InspectionContext context, String ruleId, Severity severity,
                                                long wasted, String suspect, String field) {
        LeakInspectionRule owner = rule(ruleId, ruleId.startsWith("jvm") || ruleId.startsWith("b")
                ? FindingCategory.JVM_CORE : FindingCategory.JAKARTA_WILDFLY, true, c -> List.of());
        return context.finding(owner, severity + ":" + wasted).severity(severity).title(ruleId).wasted(wasted)
                .suspect(suspect, field).why("why").fix("fix").build();
    }

    private static LeakInspectionRule rule(String id, FindingCategory category, boolean applicable,
                                           Function<InspectionContext, List<InspectionFindingDto>> body) {
        return new LeakInspectionRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String title() {
                return id;
            }

            @Override
            public FindingCategory category() {
                return category;
            }

            @Override
            public boolean isApplicable(InspectionContext context) {
                return applicable;
            }

            @Override
            public List<InspectionFindingDto> inspect(InspectionContext context) {
                return body.apply(context);
            }
        };
    }
}
