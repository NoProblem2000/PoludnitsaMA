package com.poludnitsa.inspector.heap;

import com.poludnitsa.inspector.api.dto.GcRootPathStepDto;
import com.poludnitsa.inspector.api.dto.ReferenceKind;
import com.poludnitsa.inspector.api.dto.RetentionPathDto;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;

/**
 * How the instances of a class are held: GC root paths of a spread sample of instances, grouped by shape. The shape
 * is the sequence of classes and fields from the root with array indexes dropped, so 10 000 orders in one cache are
 * one shape ({@code static CACHE in OrderService → HashMap.table → HashMap$Node[] → HashMap$Node.value}) and orders
 * still referenced by a request thread are another.
 */
public final class RetentionPathSampler {

    private static final int MAX_SHAPES = 3;

    private final GcRootPathResolver paths;
    private final int samples;

    /** @param paths a resolver with a small search limit: sampled objects may be garbage */
    public RetentionPathSampler(GcRootPathResolver paths, int samples) {
        this.paths = paths;
        this.samples = Math.max(1, samples);
    }

    /** @param deadlineNanos {@link System#nanoTime()} after which sampling stops with what it has */
    public List<RetentionPathDto> sample(List<JavaClass> definitions, long deadlineNanos) {
        long total = 0;
        for (JavaClass definition : definitions) {
            total += definition.getInstancesCount();
        }
        if (total == 0) {
            return List.of();
        }
        // every n-th instance: instances of one class are spread over the heap in allocation order
        long stride = Math.max(1, total / samples);
        Map<String, Shape> shapes = new LinkedHashMap<>();
        int sampled = 0;
        long index = 0;
        outer:
        for (JavaClass definition : definitions) {
            Iterator<?> instances = definition.getInstancesIterator();
            while (instances.hasNext()) {
                Instance instance = (Instance) instances.next();
                if (index++ % stride != 0) {
                    continue;
                }
                if (sampled >= samples || System.nanoTime() > deadlineNanos) {
                    break outer;
                }
                GcRootPathResolver.ResolvedPath path = paths.resolve(instance);
                sampled++;
                if (path.steps().isEmpty() || !path.steps().get(0).gcRoot()) {
                    continue;
                }
                String signature = signature(path.steps());
                shapes.computeIfAbsent(signature, s -> new Shape(path.steps())).count++;
            }
        }
        if (sampled == 0) {
            return List.of();
        }
        int all = sampled;
        return shapes.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().count, a.getValue().count))
                .limit(MAX_SHAPES)
                .map(e -> new RetentionPathDto(e.getKey(), e.getValue().steps, e.getValue().count,
                        (double) e.getValue().count / all))
                .toList();
    }

    private static final class Shape {
        final List<GcRootPathStepDto> steps;
        int count;

        Shape(List<GcRootPathStepDto> steps) {
            this.steps = steps;
        }
    }

    /** Root first, without the inspected object itself: {@code Thread.threadLocals → ThreadLocalMap.table → ...}. */
    static String signature(List<GcRootPathStepDto> steps) {
        List<String> parts = new ArrayList<>(steps.size());
        for (int i = 0; i + 1 < steps.size(); i++) {
            GcRootPathStepDto step = steps.get(i);
            String holder = GcRootPathResolver.simpleName(step.className());
            String reference = step.referenceName();
            String part;
            if (step.className().startsWith("class ") && reference != null && reference.startsWith("static ")) {
                String owner = step.className().substring("class ".length());
                part = reference + " in " + GcRootPathResolver.simpleName(owner);
            } else if (reference == null) {
                part = holder;
            } else if (reference.startsWith("[")) {
                part = holder.endsWith("[]") ? holder : holder + "[]";
            } else {
                part = holder + "." + reference;
            }
            if (step.referenceKind() != null && step.referenceKind() != ReferenceKind.STRONG) {
                part += " (" + step.referenceKind().name().toLowerCase(Locale.ROOT) + ")";
            }
            parts.add(part);
        }
        return parts.isEmpty() ? GcRootPathResolver.simpleName(steps.get(0).className()) : String.join(" → ", parts);
    }
}
