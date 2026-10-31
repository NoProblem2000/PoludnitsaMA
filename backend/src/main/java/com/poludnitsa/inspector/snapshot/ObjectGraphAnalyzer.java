package com.poludnitsa.inspector.snapshot;

import com.poludnitsa.inspector.api.dto.ClassLoaderStatsDto;
import com.poludnitsa.inspector.api.dto.ClassSampleDto;
import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.DominatorNodeDto;
import com.poludnitsa.inspector.api.dto.MemoryGroup;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.api.dto.RetentionPathDto;
import com.poludnitsa.inspector.api.dto.SnapshotStatus;
import com.poludnitsa.inspector.common.JvmTypeNames;
import com.poludnitsa.inspector.common.PackageCategorizer;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.heap.ClassLoaderGrouping;
import com.poludnitsa.inspector.heap.GcRootPathResolver;
import com.poludnitsa.inspector.heap.HeapReader;
import com.poludnitsa.inspector.heap.RetentionPathSampler;
import com.poludnitsa.inspector.inspection.LeakHeuristicsEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.swing.BoundedRangeModel;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.HeapFactory;
import org.netbeans.lib.profiler.heap.HeapProgress;
import org.netbeans.lib.profiler.heap.HeapSummary;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Analyses one .hprof file with the NetBeans profiler lib.
 *
 * <p>{@link HeapFactory#createHeap(java.io.File)} never loads the dump into the inspector's heap: it memory-maps the
 * file and builds its indexes as files in {@code <dump>.nbcache/}. On top of that it computes the dominator tree
 * (retained sizes), the top objects and classes by retained size, and shortest GC root paths for them.
 *
 * <p>The returned {@link SnapshotData} holds no reference into the NetBeans heap. The {@link Analysis} also carries the
 * open heap: dropped by the caller, it and its mapped buffers become garbage and the files can be deleted; kept, it
 * answers OQL queries and on-demand GC root paths ({@link OpenHeapRegistry}).
 */
@Component
public class ObjectGraphAnalyzer {

    /** The analysis and the heap it was computed on (still open: drop it to release the files). */
    public record Analysis(SnapshotData data, Heap heap) {
    }

    private static final Logger log = LoggerFactory.getLogger(ObjectGraphAnalyzer.class);
    /** search limit for sampled retention paths: sampled objects of a dump without Full GC may be garbage */
    private static final int SAMPLE_SEARCH_LIMIT = 20_000;

    private final InspectorProperties.Snapshots props;
    private final LeakHeuristicsEngine engine;

    public ObjectGraphAnalyzer(InspectorProperties properties, LeakHeuristicsEngine engine) {
        this.props = properties.snapshots();
        this.engine = engine;
    }

    /** Runs on the calling thread; {@code record} receives phases and live progress. */
    public Analysis analyze(Path hprof, SnapshotRecord record) throws IOException {
        // NetBeans reports progress through a model bound to the thread that does the work.
        BoundedRangeModel progress = HeapProgress.getProgress();
        record.progressSource(() -> percent(progress));
        try {
            long started = System.nanoTime();
            record.phase(SnapshotStatus.INDEXING, "Checking heap dump consistency");
            HprofSanitizer.Result repair = HprofSanitizer.repairDanglingStickyClassRoots(hprof);
            if (repair.danglingRepaired() > 0) {
                log.info("Repaired {} of {} sticky-class GC roots without class dump in {}",
                        repair.danglingRepaired(), repair.stickyClassRoots(), hprof);
            } else if (!repair.scanned()) {
                log.warn("Unknown HPROF record in {}; analysing it unmodified", hprof);
            }
            record.phase(SnapshotStatus.INDEXING, "Indexing heap dump (on-disk NetBeans indexes)");
            Heap heap = HeapFactory.createHeap(hprof.toFile());
            HeapSummary summary = heap.getSummary();
            long totalBytes = summary.getTotalLiveBytes();
            long totalInstances = summary.getTotalLiveInstances();
            int gcRoots = heap.getGCRoots().size();
            record.indexed(millisSince(started));

            long analysisStarted = System.nanoTime();
            record.phase(SnapshotStatus.ANALYZING, "Computing dominator tree and retained sizes");
            List<Instance> biggest = new ArrayList<>();
            for (Object instance : heap.getBiggestObjectsByRetainedSize(props.topDominators())) {
                biggest.add((Instance) instance);
            }

            record.phase(SnapshotStatus.ANALYZING, "Aggregating retained size by class");
            Map<String, ClassAccumulator> classes = aggregateClasses(heap);

            record.phase(SnapshotStatus.ANALYZING, "Resolving shortest paths to GC roots");
            GcRootPathResolver paths = new GcRootPathResolver(heap, props.maxPathDepth());
            List<DominatorNodeDto> dominators = new ArrayList<>(biggest.size());
            Map<String, Instance> biggestByClass = new HashMap<>();
            for (Instance instance : biggest) {
                DominatorNodeDto node = dominator(instance, paths, totalBytes);
                dominators.add(node);
                biggestByClass.putIfAbsent(node.className(), instance);
            }

            List<ClassAccumulator> ranked = new ArrayList<>(classes.values());
            ranked.sort(Comparator.comparingLong((ClassAccumulator c) -> c.retained).reversed()
                    .thenComparing(c -> c.name));
            int withSample = Math.min(props.classesWithSamplePath(), ranked.size());
            for (int i = 0; i < withSample; i++) {
                ClassAccumulator accumulator = ranked.get(i);
                Instance representative = biggestByClass.get(accumulator.name);
                if (representative == null) {
                    representative = largestInstance(accumulator);
                }
                if (representative != null) {
                    GcRootPathResolver.ResolvedPath path = paths.resolve(representative);
                    accumulator.sample = new ClassSampleDto(representative.getInstanceId(),
                            representative.getRetainedSize(), path.steps(), path.text());
                }
            }

            record.phase(SnapshotStatus.ANALYZING, "Sampling retention paths of the top classes");
            RetentionPathSampler sampler = new RetentionPathSampler(
                    new GcRootPathResolver(heap, props.maxPathDepth(), SAMPLE_SEARCH_LIMIT),
                    props.retentionSamplesPerClass());
            long retentionDeadline = System.nanoTime() + props.retentionPathBudget().toNanos();
            for (int i = 0; i < withSample && System.nanoTime() < retentionDeadline; i++) {
                ClassAccumulator accumulator = ranked.get(i);
                accumulator.retentionPaths = sampler.sample(accumulator.definitions, retentionDeadline);
            }

            List<ClassStatsDto> classStats = new ArrayList<>(ranked.size());
            for (ClassAccumulator accumulator : ranked) {
                classStats.add(accumulator.toDto());
            }

            record.phase(SnapshotStatus.ANALYZING, "Grouping memory by class loader and JBoss module");
            List<ClassLoaderStatsDto> classLoaders = ClassLoaderGrouping.group(heap, new HeapReader(heap),
                    props.maxClassLoaders());

            // Inspections need the open heap: after this method the raw dump is usually deleted.
            LeakHeuristicsEngine.InspectionOutcome inspections = engine.inspect(heap, totalBytes, paths,
                    message -> record.phase(SnapshotStatus.ANALYZING, message));

            SnapshotData data = new SnapshotData(totalInstances, classStats.size(), totalBytes, gcRoots, true,
                    classStats, dominators, SnapshotDataFactory.groups(classStats, totalBytes),
                    inspections.findings(), inspections.runs(), classLoaders);
            record.analysed(millisSince(analysisStarted), data);
            log.info("Analysed {}: {} objects, {} classes, {} bytes, {} GC roots, {} class loaders, {} findings",
                    hprof, totalInstances, classStats.size(), totalBytes, gcRoots, classLoaders.size(),
                    inspections.findings().size());
            return new Analysis(data, heap);
        } finally {
            record.progressSource(null);
        }
    }

    private static Map<String, ClassAccumulator> aggregateClasses(Heap heap) {
        Map<String, ClassAccumulator> byName = new HashMap<>();
        for (Object element : heap.getAllClasses()) {
            JavaClass javaClass = (JavaClass) element;
            int count = javaClass.getInstancesCount();
            if (count == 0) {
                continue;
            }
            String name = JvmTypeNames.toJavaName(javaClass.getName());
            ClassAccumulator accumulator = byName.computeIfAbsent(name, ClassAccumulator::new);
            accumulator.instances += count;
            accumulator.shallow += javaClass.getAllInstancesSize();
            accumulator.retained += javaClass.getRetainedSizeByClass();
            accumulator.definitions.add(javaClass);
        }
        return byName;
    }

    private Instance largestInstance(ClassAccumulator accumulator) {
        Instance largest = null;
        long largestSize = -1;
        int scanned = 0;
        for (JavaClass definition : accumulator.definitions) {
            Iterator<?> instances = definition.getInstancesIterator();
            while (instances.hasNext() && scanned < props.maxInstancesScannedPerClass()) {
                Instance instance = (Instance) instances.next();
                scanned++;
                long size = instance.getRetainedSize();
                if (size > largestSize) {
                    largest = instance;
                    largestSize = size;
                }
            }
        }
        return largest;
    }

    private static DominatorNodeDto dominator(Instance instance, GcRootPathResolver paths, long totalBytes) {
        GcRootPathResolver.ResolvedPath path = paths.resolve(instance);
        String className = path.steps().isEmpty()
                ? JvmTypeNames.toJavaName(instance.getJavaClass().getName())
                : path.steps().get(path.steps().size() - 1).className();
        String categorized = className.startsWith("class ") ? "java.lang.Class" : className;
        PackageCategory category = PackageCategorizer.categorize(categorized);
        long retained = instance.getRetainedSize();
        String owner = categorized;
        PackageCategory ownerCategory = category;
        for (int i = path.steps().size() - 1; i >= 0; i--) {
            String name = path.steps().get(i).className();
            String bare = name.startsWith("class ") ? name.substring("class ".length()) : name;
            PackageCategory candidate = PackageCategorizer.categorize(bare);
            if (candidate != PackageCategory.JDK && candidate != PackageCategory.PRIMITIVE_OR_ARRAY) {
                owner = bare;
                ownerCategory = candidate;
                break;
            }
        }
        return new DominatorNodeDto(instance.getInstanceId(), className, category, MemoryGroup.of(category),
                instance.getSize(), retained, totalBytes > 0 ? 100.0 * retained / totalBytes : 0,
                path.steps(), path.text(), path.rootKind(), owner, MemoryGroup.of(ownerCategory));
    }

    private static int percent(BoundedRangeModel model) {
        int range = model.getMaximum() - model.getMinimum();
        return range <= 0 ? 0 : (int) (100L * (model.getValue() - model.getMinimum()) / range);
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static final class ClassAccumulator {
        final String name;
        final List<JavaClass> definitions = new ArrayList<>(1);
        long instances;
        long shallow;
        long retained;
        ClassSampleDto sample;
        List<RetentionPathDto> retentionPaths = List.of();

        ClassAccumulator(String name) {
            this.name = name;
        }

        ClassStatsDto toDto() {
            PackageCategory category = PackageCategorizer.categorize(name);
            return new ClassStatsDto(name, category, MemoryGroup.of(category), instances, shallow, retained,
                    definitions.size(), sample, retentionPaths);
        }
    }
}
