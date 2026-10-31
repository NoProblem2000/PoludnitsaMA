package com.poludnitsa.inspector.heap;

import com.poludnitsa.inspector.api.dto.GcRootPathStepDto;
import com.poludnitsa.inspector.api.dto.PathMode;
import com.poludnitsa.inspector.api.dto.ReferenceKind;
import com.poludnitsa.inspector.common.JvmTypeNames;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Shortest reference chain from a GC root to an object, naming every hop by the field, static field or array index
 * that holds the next object and by the kind of that reference.
 *
 * <ul>
 *   <li>{@link PathMode#STRONG_ONLY}: follows {@link Instance#getNearestGCRootPointer()}. The NetBeans lib computes
 *       nearest roots for the whole heap once (on-disk structures) and skips the {@code referent} of weak, soft,
 *       final and phantom references, so this is the path that really keeps the object alive. An object without
 *       such a path is not leaking: it is either garbage or held only through a reference object, and the
 *       resolver then searches the weak path to show which one.</li>
 *   <li>{@link PathMode#ALL_REFERENCES}: breadth-first search backwards over incoming references
 *       ({@link Instance#getReferences()}), referents included, bounded by depth and visited objects.</li>
 * </ul>
 *
 * <p>Text form, object first, as in dotMemory:
 * {@code OrderDto <- Object[142] <- ArrayList.elementData <- static CACHE in com.acme.OrderService [GC Root: sticky class]}.
 * HPROF has no field modifiers or declared types, so only {@code static} is shown.
 */
public final class GcRootPathResolver {

    private static final int MAX_REFERENCES_SCANNED = 100_000;
    /** incoming references looked at per object by the backward search (a shared object may have millions) */
    private static final int MAX_REFERRERS_PER_OBJECT = 10_000;
    private static final int DEFAULT_MAX_VISITED = 250_000;
    private static final String REFERENT = "referent";

    /**
     * @param strongPathExists the object is kept alive by strong references only
     * @param visited          objects looked at by a backward search, 0 when the precomputed pointers sufficed
     * @param truncated        the path was cut by {@code maxDepth}, or a backward search hit its limits
     */
    public record ResolvedPath(List<GcRootPathStepDto> steps, String text, String rootKind, boolean strongPathExists,
                               int visited, boolean truncated) {
    }

    private record Hop(Instance holder, String reference, ReferenceKind kind) {
    }

    private final Heap heap;
    private final int maxDepth;
    private final int maxVisited;

    public GcRootPathResolver(Heap heap, int maxDepth) {
        this(heap, maxDepth, DEFAULT_MAX_VISITED);
    }

    public GcRootPathResolver(Heap heap, int maxDepth, int maxVisited) {
        this.heap = heap;
        this.maxDepth = Math.max(2, maxDepth);
        this.maxVisited = Math.max(1_000, maxVisited);
    }

    public ResolvedPath resolve(Instance target) {
        return resolve(target, PathMode.STRONG_ONLY);
    }

    public ResolvedPath resolve(Instance target, PathMode mode) {
        StrongChain strong = strongChain(target);
        if (mode == PathMode.STRONG_ONLY && (strong.reachedRoot || strong.truncated)) {
            return fromStrongChain(target, strong);
        }
        // ALL_REFERENCES, or no strong path: the object may still be held through a reference object.
        Search search = searchBackwards(target);
        if (search.path != null) {
            return fromSearch(target, search, strong.reachedRoot);
        }
        if (mode == PathMode.ALL_REFERENCES && strong.reachedRoot) {
            // the search hit its limits; the strong path is still a valid answer
            ResolvedPath path = fromStrongChain(target, strong);
            return new ResolvedPath(path.steps(), path.text(), path.rootKind(), true, search.visited, true);
        }
        ResolvedPath unreachable = fromStrongChain(target, strong);
        return new ResolvedPath(unreachable.steps(), unreachable.text(), null, false, search.visited,
                search.truncated);
    }

    // ---------------------------------------------------------------- strong path (precomputed by NetBeans)

    private static final class StrongChain {
        final List<Instance> chain = new ArrayList<>();
        boolean reachedRoot;
        boolean truncated;
    }

    private StrongChain strongChain(Instance target) {
        StrongChain result = new StrongChain();
        Set<Long> seen = new HashSet<>();
        Instance current = target;
        while (current != null && seen.add(current.getInstanceId())) {
            result.chain.add(current);
            if (current.isGCRoot()) {
                result.reachedRoot = true;
                break;
            }
            if (result.chain.size() >= maxDepth) {
                break;
            }
            current = current.getNearestGCRootPointer();
        }
        result.truncated = !result.reachedRoot && current != null;
        return result;
    }

    private ResolvedPath fromStrongChain(Instance target, StrongChain strong) {
        List<Instance> chain = strong.chain;
        // hops[i] = how chain[i + 1] holds chain[i]
        List<Hop> hops = new ArrayList<>(chain.size());
        for (int i = 0; i + 1 < chain.size(); i++) {
            hops.add(hop(chain.get(i + 1), chain.get(i)));
        }
        String rootKind = strong.reachedRoot ? rootKind(chain.get(chain.size() - 1)) : null;
        String suffix;
        if (strong.reachedRoot) {
            suffix = " [GC Root: " + rootKind + "]";
        } else if (strong.truncated) {
            suffix = " <- ... [path longer than " + maxDepth + " hops]";
        } else {
            suffix = " [no GC root: unreachable, collected by the next GC]";
        }
        return new ResolvedPath(steps(chain, hops, strong.reachedRoot, rootKind), text(target, chain, hops, suffix),
                rootKind, strong.reachedRoot, 0, strong.truncated);
    }

    // ---------------------------------------------------------------- backward search (all references)

    private static final class Search {
        List<Instance> path;
        List<Hop> hops;
        int visited;
        boolean truncated;
    }

    /** Breadth-first from the target over incoming references until a GC root; shortest in hops. */
    private Search searchBackwards(Instance target) {
        Search search = new Search();
        // holder id -> the object it references on the way to the target, and how
        Map<Long, Parent> parents = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        visited.add(target.getInstanceId());
        List<Instance> level = List.of(target);
        Instance root = null;
        search:
        for (int depth = 0; depth < maxDepth && !level.isEmpty(); depth++) {
            List<Instance> next = new ArrayList<>();
            for (Instance current : level) {
                if (current.isGCRoot()) {
                    root = current;
                    break search;
                }
                int scanned = 0;
                for (Object reference : current.getReferences()) {
                    if (++scanned > MAX_REFERRERS_PER_OBJECT) {
                        search.truncated = true;
                        break;
                    }
                    Value value = (Value) reference;
                    Instance holder = value.getDefiningInstance();
                    if (holder == null || !visited.add(holder.getInstanceId())) {
                        continue;
                    }
                    if (visited.size() > maxVisited) {
                        search.truncated = true;
                        break search;
                    }
                    parents.put(holder.getInstanceId(), new Parent(current, describe(value)));
                    next.add(holder);
                }
            }
            level = next;
        }
        search.visited = visited.size();
        if (root == null) {
            search.truncated |= !level.isEmpty();
            return search;
        }
        // root first: rootFirst[k] holds rootFirst[k + 1] through hopsRootFirst[k]
        List<Instance> rootFirst = new ArrayList<>();
        List<Hop> hopsRootFirst = new ArrayList<>();
        for (Instance current = root; current != null; ) {
            rootFirst.add(current);
            Parent parent = parents.get(current.getInstanceId());
            if (parent == null) {
                break;
            }
            hopsRootFirst.add(new Hop(current, parent.edge().reference(), parent.edge().kind()));
            current = parent.child();
        }
        // object first, as the strong chain: hops[i] = how chain[i + 1] holds chain[i]
        Collections.reverse(rootFirst);
        Collections.reverse(hopsRootFirst);
        search.path = rootFirst;
        search.hops = hopsRootFirst;
        return search;
    }

    private record Edge(String reference, ReferenceKind kind) {
    }

    private record Parent(Instance child, Edge edge) {
    }

    private Edge describe(Value value) {
        if (value instanceof ArrayItemValue item) {
            return new Edge("[" + item.getIndex() + "]", ReferenceKind.STRONG);
        }
        if (value instanceof ObjectFieldValue field) {
            String name = field.getField().getName();
            if (field.getField().isStatic()) {
                return new Edge("static " + name, ReferenceKind.STRONG);
            }
            return new Edge(name, referenceKind(value.getDefiningInstance(), name));
        }
        return new Edge(null, ReferenceKind.STRONG);
    }

    private ResolvedPath fromSearch(Instance target, Search search, boolean strongPathExists) {
        List<Instance> chain = search.path;
        List<Hop> hops = search.hops;
        Instance root = chain.get(chain.size() - 1);
        String rootKind = rootKind(root);
        // a chain is as strong as its weakest link: strong > soft > weak > final > phantom
        ReferenceKind weakest = ReferenceKind.STRONG;
        for (Hop hop : hops) {
            if (weakness(hop.kind()) > weakness(weakest)) {
                weakest = hop.kind();
            }
        }
        String suffix = " [GC Root: " + rootKind + "]";
        if (!strongPathExists && weakest != ReferenceKind.STRONG) {
            suffix += switch (weakest) {
                case WEAK -> " [weakly reachable only: collected by the next GC]";
                case SOFT -> " [softly reachable only: cleared under memory pressure]";
                case PHANTOM -> " [phantom reachable only: already finalized]";
                case FINALIZER -> " [reachable only from the finalizer queue: freed after finalize()]";
                default -> "";
            };
        }
        return new ResolvedPath(steps(chain, hops, true, rootKind), text(target, chain, hops, suffix), rootKind,
                strongPathExists, search.visited, false);
    }

    private static int weakness(ReferenceKind kind) {
        return switch (kind) {
            case STRONG -> 0;
            case SOFT -> 1;
            case WEAK -> 2;
            case FINALIZER -> 3;
            case PHANTOM -> 4;
        };
    }

    // ---------------------------------------------------------------- shared rendering

    /** {@code chain} object first, {@code hops.get(i)} = how chain[i + 1] holds chain[i]; steps are root first. */
    private List<GcRootPathStepDto> steps(List<Instance> chain, List<Hop> hops, boolean reachedRoot, String rootKind) {
        List<GcRootPathStepDto> steps = new ArrayList<>(chain.size());
        for (int j = chain.size() - 1; j >= 0; j--) {
            Instance instance = chain.get(j);
            boolean isRoot = reachedRoot && j == chain.size() - 1;
            Hop hop = j > 0 ? hops.get(j - 1) : null;
            steps.add(new GcRootPathStepDto(instance.getInstanceId(), describeClass(instance),
                    hop == null ? null : hop.reference(), isRoot, isRoot ? rootKind : null,
                    hop == null ? null : hop.kind()));
        }
        return steps;
    }

    private String text(Instance target, List<Instance> chain, List<Hop> hops, String suffix) {
        StringBuilder text = new StringBuilder(simpleName(describeClass(target)));
        for (int i = 1; i < chain.size(); i++) {
            Hop hop = hops.get(i - 1);
            text.append(" <- ").append(hop(chain.get(i), hop.reference()));
            if (hop.kind() != ReferenceKind.STRONG) {
                text.append(" (").append(hop.kind().name().toLowerCase(Locale.ROOT)).append(")");
            }
        }
        return text.append(suffix).toString();
    }

    /** How {@code holder} references {@code child}: field, {@code static FIELD} or {@code [index]}, and its kind. */
    private Hop hop(Instance holder, Instance child) {
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
            Edge edge = describe(value);
            return new Hop(holder, edge.reference(), edge.kind());
        }
        return new Hop(holder, null, ReferenceKind.STRONG);
    }

    /**
     * The kind of {@code holder.field}: the {@code referent} of a {@code java.lang.ref.Reference} subclass is weak,
     * soft, phantom or final; everything else is strong.
     */
    public static ReferenceKind referenceKind(Instance holder, String field) {
        if (holder == null || !REFERENT.equals(field)) {
            return ReferenceKind.STRONG;
        }
        for (JavaClass c = holder.getJavaClass(); c != null; c = c.getSuperClass()) {
            switch (c.getName()) {
                case "java.lang.ref.WeakReference":
                    return ReferenceKind.WEAK;
                case "java.lang.ref.SoftReference":
                    return ReferenceKind.SOFT;
                case "java.lang.ref.PhantomReference":
                    return ReferenceKind.PHANTOM;
                case "java.lang.ref.FinalReference":
                    return ReferenceKind.FINALIZER;
                default:
                    break;
            }
        }
        return ReferenceKind.STRONG;
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
