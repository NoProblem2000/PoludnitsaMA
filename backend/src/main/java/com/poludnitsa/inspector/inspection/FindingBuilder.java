package com.poludnitsa.inspector.inspection;

import com.poludnitsa.inspector.api.dto.GcRootPathStepDto;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.heap.GcRootPathResolver;
import java.util.ArrayList;
import java.util.List;
import org.netbeans.lib.profiler.heap.Instance;

/** Fluent construction of an {@link InspectionFindingDto}. */
public final class FindingBuilder {

    private static final int MAX_EVIDENCE = 40;

    private final LeakInspectionRule rule;
    private final String key;
    private final GcRootPathResolver paths;
    private final List<String> evidence = new ArrayList<>();
    private Severity severity = Severity.WARNING;
    private String title;
    private long wastedBytes;
    private long affected;
    private String suspectClass;
    private String field;
    private List<GcRootPathStepDto> path = List.of();
    private String pathText;
    private String why;
    private String fix;

    FindingBuilder(LeakInspectionRule rule, String key, GcRootPathResolver paths) {
        this.rule = rule;
        this.key = key;
        this.paths = paths;
    }

    public FindingBuilder severity(Severity value) {
        severity = value;
        return this;
    }

    public FindingBuilder title(String value) {
        title = value;
        return this;
    }

    public FindingBuilder wasted(long bytes) {
        wastedBytes = bytes;
        return this;
    }

    public FindingBuilder affected(long count) {
        affected = count;
        return this;
    }

    public FindingBuilder suspect(String className, String fieldName) {
        suspectClass = className;
        field = fieldName;
        return this;
    }

    /** Resolves the GC root path of the representative object. */
    public FindingBuilder pathOf(Instance instance) {
        if (instance != null) {
            GcRootPathResolver.ResolvedPath resolved = paths.resolve(instance);
            path = resolved.steps();
            pathText = resolved.text();
        }
        return this;
    }

    public FindingBuilder evidence(String line) {
        if (evidence.size() < MAX_EVIDENCE && line != null && !line.isBlank()) {
            evidence.add(line);
        }
        return this;
    }

    public FindingBuilder why(String text) {
        why = text;
        return this;
    }

    public FindingBuilder fix(String code) {
        fix = code;
        return this;
    }

    public InspectionFindingDto build() {
        return new InspectionFindingDto(rule.id() + "#" + key, rule.id(), severity, rule.category(), title,
                wastedBytes, affected, suspectClass, field, path, pathText, evidence, why, fix);
    }
}
