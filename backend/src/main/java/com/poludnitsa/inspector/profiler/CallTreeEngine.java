package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.api.dto.ButterflyDto;
import com.poludnitsa.inspector.api.dto.DiffFlameNodeDto;
import com.poludnitsa.inspector.api.dto.FlameNodeDto;
import com.poludnitsa.inspector.api.dto.HotspotDto;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.api.dto.ProfileMetric;
import com.poludnitsa.inspector.api.dto.Subsystem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Call trees of a profile window: flame graph, differential flame graph, hotspots and the butterfly view.
 *
 * <p>Stacks are stored leaf first; the flame graph grows from the bottom frames (thread entry points) to the leaves,
 * so a frame's width is its total weight and the bare top of a frame its self weight. Recursive calls stay separate
 * nodes in the flame graph (as in every flame graph); hotspots and the butterfly count a method once per stack.
 */
public final class CallTreeEngine {

    static final String ROOT = "all";

    private CallTreeEngine() {
    }

    /** Mutable tree node keyed by frame id. */
    static final class Node {
        final int frame;
        long self;
        long total;
        long selfSamples;
        long totalSamples;
        final Map<Integer, Node> children = new HashMap<>();

        Node(int frame) {
            this.frame = frame;
        }

        Node child(int frameId) {
            return children.computeIfAbsent(frameId, Node::new);
        }

        void add(long weight, boolean sample) {
            total += weight;
            if (sample) {
                totalSamples++;
            }
        }
    }

    /** The window's flame tree, bottom frames under a synthetic root. */
    static Node flameTree(ProfileData data, ProfileMetric metric, double fromMs, double toMs, int thread) {
        Node root = new Node(-1);
        data.forEachStack(metric, fromMs, toMs, thread, (stack, weight, sample, t, kind) -> {
            int[] frames = data.stack(stack);
            root.add(weight, sample);
            Node node = root;
            for (int i = frames.length - 1; i >= 0; i--) {
                node = node.child(frames[i]);
                node.add(weight, sample);
            }
            node.self += weight;
            if (sample) {
                node.selfSamples++;
            }
        });
        return root;
    }

    /** Pruning state of one conversion. */
    static final class Budget {
        int nodes;
        int pruned;
        final long minValue;
        final int maxNodes;

        Budget(long total, double minShare, int maxNodes) {
            this.minValue = Math.max(1, (long) Math.ceil(total * minShare));
            this.maxNodes = maxNodes;
        }
    }

    static FlameNodeDto toDto(ProfileData data, Node node, long grandTotal, boolean timeMetric, Budget budget) {
        List<Node> children = new ArrayList<>(node.children.values());
        children.sort(Comparator.comparingLong((Node n) -> n.total).reversed());
        List<FlameNodeDto> dtos = new ArrayList<>();
        for (Node child : children) {
            if (child.total < budget.minValue || budget.nodes >= budget.maxNodes) {
                budget.pruned++;
                continue;
            }
            budget.nodes++;
            dtos.add(toDto(data, child, grandTotal, timeMetric, budget));
        }
        String className = node.frame < 0 ? "" : data.frame(node.frame).className();
        String method = node.frame < 0 ? ROOT : data.frame(node.frame).methodName();
        PackageCategory category = node.frame < 0 ? PackageCategory.JDK : data.frame(node.frame).category();
        return new FlameNodeDto(className, method, category, node.selfSamples, node.totalSamples, node.self,
                node.total, timeMetric ? node.total / 1000.0 : null,
                grandTotal == 0 ? 0 : 100.0 * node.total / grandTotal, dtos);
    }

    // ---------------------------------------------------------------- hotspots

    public static List<HotspotDto> hotspots(ProfileData data, ProfileMetric metric, double fromMs, double toMs,
                                            int thread, int limit) {
        Map<Integer, long[]> stats = new HashMap<>();  // frame -> {self, total, selfSamples, totalSamples}
        Map<Integer, EnumMap<Subsystem, Long>> subsystems = new HashMap<>();
        long[] grand = {0};
        Set<Integer> seen = new HashSet<>();
        data.forEachStack(metric, fromMs, toMs, thread, (stack, weight, sample, t, kind) -> {
            int[] frames = data.stack(stack);
            grand[0] += weight;
            seen.clear();
            for (int frame : frames) {
                if (seen.add(frame)) {
                    long[] s = stats.computeIfAbsent(frame, f -> new long[4]);
                    s[1] += weight;
                    if (sample) {
                        s[3]++;
                    }
                }
            }
            long[] leaf = stats.get(frames[0]);
            leaf[0] += weight;
            if (sample) {
                leaf[2]++;
            }
            subsystems.computeIfAbsent(frames[0], f -> new EnumMap<>(Subsystem.class))
                    .merge(kind == null ? SubsystemClassifier.classifySample(data, stack)
                            : SubsystemClassifier.classifyEvent(data, kind, stack), weight, Long::sum);
        });
        long total = grand[0];
        return stats.entrySet().stream()
                .filter(e -> e.getValue()[0] > 0)
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(Math.max(1, limit))
                .map(e -> {
                    ProfileData.Frame frame = data.frame(e.getKey());
                    long[] s = e.getValue();
                    Subsystem subsystem = subsystems.getOrDefault(e.getKey(), new EnumMap<>(Subsystem.class))
                            .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                            .orElse(Subsystem.JDK);
                    return new HotspotDto(frame.className(), frame.methodName(), frame.category(), subsystem, s[2],
                            s[3], s[0], s[1], percent(s[0], total), percent(s[1], total));
                })
                .toList();
    }

    // ---------------------------------------------------------------- butterfly

    public static ButterflyDto butterfly(String profileId, ProfileData data, ProfileMetric metric, String className,
                                         String methodName, double fromMs, double toMs, int thread, double minShare,
                                         int maxNodes) {
        Set<Integer> targets = new HashSet<>();
        for (int id = 0; id < data.frameCount(); id++) {
            ProfileData.Frame frame = data.frame(id);
            if (frame.className().equals(className) && frame.methodName().equals(methodName)) {
                targets.add(id);
            }
        }
        Node callers = new Node(targets.isEmpty() ? -1 : targets.iterator().next());
        Node callees = new Node(callers.frame);
        long[] grand = {0};
        data.forEachStack(metric, fromMs, toMs, thread, (stack, weight, sample, t, kind) -> {
            int[] frames = data.stack(stack);
            grand[0] += weight;
            int leafMost = -1;
            int rootMost = -1;
            for (int i = 0; i < frames.length; i++) {
                if (targets.contains(frames[i])) {
                    if (leafMost < 0) {
                        leafMost = i;
                    }
                    rootMost = i;
                }
            }
            if (leafMost < 0) {
                return;
            }
            // callers: from the method down the stack towards the thread entry
            callers.add(weight, sample);
            Node up = callers;
            for (int i = leafMost + 1; i < frames.length; i++) {
                up = up.child(frames[i]);
                up.add(weight, sample);
            }
            // callees: from the outermost call of the method up to the leaf
            callees.add(weight, sample);
            Node down = callees;
            for (int i = rootMost - 1; i >= 0; i--) {
                down = down.child(frames[i]);
                down.add(weight, sample);
            }
            down.self += weight;
            if (sample) {
                down.selfSamples++;
            }
        });
        boolean time = metric != ProfileMetric.ALLOC;
        long total = callers.total;
        // self of the method: stacks in which nothing is above it
        callers.self = callees.self;
        callers.selfSamples = callees.selfSamples;
        FlameNodeDto callersDto = toDto(data, callers, total, time, new Budget(total, minShare, maxNodes));
        FlameNodeDto calleesDto = toDto(data, callees, total, time, new Budget(total, minShare, maxNodes));
        return new ButterflyDto(profileId, metric, unit(metric), className, methodName, total, callees.self,
                percent(total, grand[0]), callersDto, calleesDto);
    }

    // ---------------------------------------------------------------- differential flame graph

    /** Children of two trees matched by method (frame ids differ between profiles). */
    public static DiffFlameNodeDto diff(ProfileData baseline, Node baseRoot, ProfileData target, Node targetRoot,
                                        double minShare, int maxNodes) {
        long baseTotal = Math.max(1, baseRoot.total);
        long targetTotal = Math.max(1, targetRoot.total);
        int[] budget = {maxNodes};
        return diffNode(baseline, baseRoot, target, targetRoot, baseTotal, targetTotal, minShare, budget);
    }

    private static DiffFlameNodeDto diffNode(ProfileData baseline, Node base, ProfileData target, Node current,
                                             long baseTotal, long targetTotal, double minShare, int[] budget) {
        Map<String, Node[]> pairs = new LinkedHashMap<>();
        if (base != null) {
            base.children.values().forEach(n -> pairs.computeIfAbsent(baseline.frame(n.frame).key(),
                    k -> new Node[2])[0] = n);
        }
        if (current != null) {
            current.children.values().forEach(n -> pairs.computeIfAbsent(target.frame(n.frame).key(),
                    k -> new Node[2])[1] = n);
        }
        List<DiffFlameNodeDto> children = new ArrayList<>();
        pairs.values().stream()
                .sorted(Comparator.comparingDouble((Node[] p) -> -Math.max(
                        p[1] == null ? 0 : (double) p[1].total / targetTotal,
                        p[0] == null ? 0 : (double) p[0].total / baseTotal)))
                .forEach(pair -> {
                    double share = Math.max(pair[0] == null ? 0 : (double) pair[0].total / baseTotal,
                            pair[1] == null ? 0 : (double) pair[1].total / targetTotal);
                    if (share >= minShare && budget[0]-- > 0) {
                        children.add(diffNode(baseline, pair[0], target, pair[1], baseTotal, targetTotal, minShare,
                                budget));
                    }
                });
        Node any = current != null ? current : base;
        ProfileData source = current != null ? target : baseline;
        String className = any == null || any.frame < 0 ? "" : source.frame(any.frame).className();
        String method = any == null || any.frame < 0 ? ROOT : source.frame(any.frame).methodName();
        PackageCategory category = any == null || any.frame < 0 ? PackageCategory.JDK : source.frame(any.frame).category();
        long baseValue = base == null ? 0 : base.total;
        long targetValue = current == null ? 0 : current.total;
        double basePercent = 100.0 * baseValue / baseTotal;
        double targetPercent = 100.0 * targetValue / targetTotal;
        return new DiffFlameNodeDto(className, method, category, baseValue, targetValue, basePercent, targetPercent,
                targetPercent - basePercent, children);
    }

    static String unit(ProfileMetric metric) {
        return metric == ProfileMetric.ALLOC ? "bytes" : "us";
    }

    static double percent(long value, long total) {
        return total == 0 ? 0 : 100.0 * value / total;
    }
}
