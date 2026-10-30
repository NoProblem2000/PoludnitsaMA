package com.poludnitsa.inspector.heap;

import com.poludnitsa.inspector.api.dto.GcRootPathStepDto;
import com.poludnitsa.inspector.common.JvmTypeNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.netbeans.lib.profiler.heap.ArrayItemValue;
import org.netbeans.lib.profiler.heap.GCRoot;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.lib.profiler.heap.JavaFrameGCRoot;
import org.netbeans.lib.profiler.heap.ObjectFieldValue;
import org.netbeans.lib.profiler.heap.ThreadObjectGCRoot;
import org.netbeans.lib.profiler.heap.Value;

/**
 * Shortest reference chain from a GC root to an object, following {@link Instance#getNearestGCRootPointer()} (the
 * NetBeans lib computes nearest roots for the whole heap once, with on-disk structures) and naming every hop by the
 * field, static field or array index that holds the next object.
 *
 * <p>Text form, object first, as in dotMemory:
 * {@code OrderDto <- Object[142] <- ArrayList.elementData <- static CACHE in com.acme.OrderService [GC Root: sticky class]}.
 * HPROF has no field modifiers or declared types, so only {@code static} is shown.
 */
public final class GcRootPathResolver {

    private static final int MAX_REFERENCES_SCANNED = 100_000;

    public record ResolvedPath(List<GcRootPathStepDto> steps, String text, String rootKind) {
    }

    private final Heap heap;
    private final int maxDepth;

    public GcRootPathResolver(Heap heap, int maxDepth) {
        this.heap = heap;
        this.maxDepth = Math.max(2, maxDepth);
    }

    public ResolvedPath resolve(Instance target) {
        List<Instance> chain = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Instance current = target;
        boolean reachedRoot = false;
        while (current != null && seen.add(current.getInstanceId())) {
            chain.add(current);
            if (current.isGCRoot()) {
                reachedRoot = true;
                break;
            }
            if (chain.size() >= maxDepth) {
                break;
            }
            current = current.getNearestGCRootPointer();
        }
        boolean truncated = !reachedRoot && current != null;

        // references[i] = how chain[i + 1] holds chain[i]
        String[] references = new String[chain.size()];
        for (int i = 0; i + 1 < chain.size(); i++) {
            references[i] = referenceName(chain.get(i + 1), chain.get(i));
        }
        Instance last = chain.get(chain.size() - 1);
        String rootKind = reachedRoot ? rootKind(last) : null;

        List<GcRootPathStepDto> steps = new ArrayList<>(chain.size());
        for (int j = chain.size() - 1; j >= 0; j--) {
            Instance instance = chain.get(j);
            boolean isRoot = reachedRoot && j == chain.size() - 1;
            steps.add(new GcRootPathStepDto(instance.getInstanceId(), describeClass(instance),
                    j > 0 ? references[j - 1] : null, isRoot, isRoot ? rootKind : null));
        }

        StringBuilder text = new StringBuilder(simpleName(describeClass(target)));
        for (int i = 1; i < chain.size(); i++) {
            text.append(" <- ").append(hop(chain.get(i), references[i - 1]));
        }
        if (reachedRoot) {
            text.append(" [GC Root: ").append(rootKind).append(']');
        } else if (truncated) {
            text.append(" <- ... [path longer than ").append(maxDepth).append(" hops]");
        } else {
            text.append(" [no GC root: unreachable, collected by the next GC]");
        }
        return new ResolvedPath(steps, text.toString(), rootKind);
    }

    /** How {@code holder} references {@code child}: {@code field}, {@code static FIELD} or {@code [index]}. */
    private static String referenceName(Instance holder, Instance child) {
        long holderId = holder.getInstanceId();
        int scanned = 0;
        for (Object reference : child.getReferences()) {
            if (++scanned > MAX_REFERENCES_SCANNED) {
                break;
            }
            Value value = (Value) reference;
            Instance defining = value.getDefiningInstance();
            if (defining == null || defining.getInstanceId() != holderId) {
                continue;
            }
            if (value instanceof ArrayItemValue item) {
                return "[" + item.getIndex() + "]";
            }
            if (value instanceof ObjectFieldValue field) {
                return (field.getField().isStatic() ? "static " : "") + field.getField().getName();
            }
        }
        return null;
    }

    /** {@code HashMap$Node.value}, {@code Object[142]}, {@code static CACHE in com.acme.OrderService}. */
    private String hop(Instance holder, String reference) {
        JavaClass represented = representedClass(holder);
        if (represented != null) {
            String field = reference == null ? "static field" : reference;
            return field + " in " + JvmTypeNames.toJavaName(represented.getName());
        }
        String holderName = simpleName(describeClass(holder));
        if (reference == null) {
            return holderName;
        }
        if (reference.startsWith("[")) {
            String element = holderName.endsWith("[]") ? holderName.substring(0, holderName.length() - 2) : holderName;
            return element + reference;
        }
        return holderName + "." + reference;
    }

    private String describeClass(Instance instance) {
        JavaClass represented = representedClass(instance);
        if (represented != null) {
            return "class " + JvmTypeNames.toJavaName(represented.getName());
        }
        JavaClass javaClass = instance.getJavaClass();
        return javaClass == null ? "?" : JvmTypeNames.toJavaName(javaClass.getName());
    }

    /** Class dumps act as the holder of static fields; their id is the class id. */
    private JavaClass representedClass(Instance instance) {
        JavaClass javaClass = instance.getJavaClass();
        if (javaClass == null || !"java.lang.Class".equals(javaClass.getName())) {
            return null;
        }
        return heap.getJavaClassByID(instance.getInstanceId());
    }

    private String rootKind(Instance root) {
        GCRoot gcRoot = heap.getGCRoot(root);
        if (gcRoot == null) {
            return GCRoot.UNKNOWN;
        }
        String kind = gcRoot.getKind();
        if (gcRoot instanceof JavaFrameGCRoot frame) {
            ThreadObjectGCRoot thread = frame.getThreadGCRoot();
            StackTraceElement[] stack = thread == null ? null : thread.getStackTrace();
            int index = frame.getFrameNumber();
            if (stack != null && index >= 0 && index < stack.length) {
                return kind + " " + stack[index];
            }
        }
        return kind;
    }

    public static String simpleName(String className) {
        if (className.startsWith("class ")) {
            return className;
        }
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
