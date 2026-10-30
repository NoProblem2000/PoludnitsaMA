package com.poludnitsa.inspector.snapshot;

import com.poludnitsa.inspector.api.dto.ClassDiffDto;
import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.MemoryGroup;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.api.dto.SnapshotDiffDto;
import com.poludnitsa.inspector.api.dto.SnapshotKind;
import com.poludnitsa.inspector.api.dto.SnapshotStatus;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.common.PackageCategorizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Compares two snapshots class by class (histograms, deep snapshots or one of each), current minus baseline.
 *
 * <p>Object identities do not survive between dumps (HPROF ids are addresses that GC compaction changes), so
 * "objects created after the baseline and still held" is estimated per class: growth of retained bytes (deep pairs)
 * or shallow bytes, growth of the instance count, and how trustworthy the pair is. Two live-only dumps contain only
 * reachable objects, so their growth is survival by definition; dumps with unreachable objects and {@code -all}
 * histograms also count garbage that the next GC may still remove.
 */
@Service
public class SnapshotDiffService {

    private static final long MIB = 1 << 20;

    private final SnapshotStore store;

    public SnapshotDiffService(SnapshotStore store) {
        this.store = store;
    }

    /**
     * @param limit            classes returned, after ranking
     * @param includeShrinking also return classes that shrank or disappeared
     */
    public SnapshotDiffDto diff(String baselineId, String currentId, int limit, boolean includeShrinking) {
        if (baselineId.equals(currentId)) {
            throw new OperationRejectedException(400, "SAME_SNAPSHOT", "Choose two different snapshots");
        }
        SnapshotRecord baseline = store.get(baselineId);
        SnapshotRecord current = store.get(currentId);
        SnapshotData before = readyData(baseline);
        SnapshotData after = readyData(current);

        boolean retained = before.retainedSizesAvailable() && after.retainedSizesAvailable();
        double confidence = confidence(baseline, current);
        long significanceBytes = Math.max(MIB, (long) (after.totalHeapBytes() * 0.005));
        Map<String, ClassStatsDto> beforeByName = index(before);
        Map<String, ClassStatsDto> afterByName = index(after);
        Set<String> names = new LinkedHashSet<>(afterByName.keySet());
        names.addAll(beforeByName.keySet());

        int newClasses = 0;
        int removedClasses = 0;
        int growingClasses = 0;
        List<ClassDiffDto> changed = new ArrayList<>();
        for (String name : names) {
            ClassStatsDto x = beforeByName.get(name);
            ClassStatsDto y = afterByName.get(name);
            long instancesBefore = x == null ? 0 : x.instances();
            long instancesAfter = y == null ? 0 : y.instances();
            long shallowBefore = x == null ? 0 : x.shallowBytes();
            long shallowAfter = y == null ? 0 : y.shallowBytes();
            Long retainedBefore = retained ? retainedOf(x) : null;
            Long retainedAfter = retained ? retainedOf(y) : null;
            Long deltaRetained = retained ? retainedAfter - retainedBefore : null;
            long deltaInstances = instancesAfter - instancesBefore;
            long deltaShallow = shallowAfter - shallowBefore;
            if (deltaInstances == 0 && deltaShallow == 0 && (deltaRetained == null || deltaRetained == 0)) {
                continue;
            }
            boolean isNew = instancesBefore == 0 && instancesAfter > 0;
            if (isNew) {
                newClasses++;
            }
            if (instancesAfter == 0 && instancesBefore > 0) {
                removedClasses++;
            }
            if (deltaShallow > 0) {
                growingClasses++;
            }
            boolean grew = deltaShallow > 0 || deltaInstances > 0 || (deltaRetained != null && deltaRetained > 0);
            if (!grew && !includeShrinking) {
                continue;
            }
            long bytesBefore = retained ? retainedBefore : shallowBefore;
            long deltaBytes = retained ? deltaRetained : deltaShallow;
            int score = survivalScore(instancesBefore, deltaInstances, bytesBefore, deltaBytes, isNew,
                    significanceBytes, confidence);
            PackageCategory category = PackageCategorizer.categorize(name);
            changed.add(new ClassDiffDto(name, category, MemoryGroup.of(category), instancesBefore, instancesAfter,
                    deltaInstances, shallowBefore, shallowAfter, deltaShallow, retainedBefore, retainedAfter,
                    deltaRetained, isNew, score));
        }
        changed.sort(Comparator.comparingInt(ClassDiffDto::survivalScore).reversed()
                .thenComparing(Comparator.comparingLong((ClassDiffDto d) -> Math.abs(
                        d.deltaRetainedBytes() != null ? d.deltaRetainedBytes() : d.deltaShallowBytes())).reversed())
                .thenComparing(ClassDiffDto::className));
        List<ClassDiffDto> top = changed.subList(0, Math.min(Math.max(1, limit), changed.size()));
        return new SnapshotDiffDto(baseline.toSummary(), current.toSummary(), retained,
                baseline.pid() == current.pid(), !baseline.createdAt().isAfter(current.createdAt()),
                after.totalInstances() - before.totalInstances(), after.totalHeapBytes() - before.totalHeapBytes(),
                newClasses, removedClasses, growingClasses, top);
    }

    /**
     * 0..100 = {@code 100 * confidence * sqrt(S) * (0.5 + 0.3 R + 0.2 I)}.
     * <ul>
     *   <li>S: absolute byte growth against max(1 MiB, 0.5% of the current heap), capped at 1. It gates the score:
     *       a brand-new class of a few hundred bytes (JMX internals, lambdas) must not outrank a real leak.</li>
     *   <li>R, I: relative byte and instance growth, saturating (+25% gives 0.63); new classes count as saturated.</li>
     * </ul>
     */
    static int survivalScore(long instancesBefore, long deltaInstances, long bytesBefore, long deltaBytes,
                             boolean newClass, long significanceBytes, double confidence) {
        if (deltaBytes <= 0) {
            return 0;
        }
        double significance = Math.min(1.0, deltaBytes / (double) significanceBytes);
        double relativeBytes = newClass ? 10 : deltaBytes / (double) Math.max(bytesBefore, 1);
        double relativeInstances = newClass ? 10
                : deltaInstances > 0 ? deltaInstances / (double) Math.max(instancesBefore, 1) : 0;
        double r = 1 - Math.exp(-relativeBytes / 0.25);
        double i = 1 - Math.exp(-relativeInstances / 0.25);
        long score = Math.round(100 * confidence * Math.sqrt(significance) * (0.5 + 0.3 * r + 0.2 * i));
        return (int) Math.max(0, Math.min(100, score));
    }

    /** Live-only deep pairs hold reachable objects only; anything with garbage in it is less certain. */
    static double confidence(SnapshotRecord baseline, SnapshotRecord current) {
        boolean bothDeep = baseline.kind() == SnapshotKind.DEEP && current.kind() == SnapshotKind.DEEP;
        if (bothDeep && baseline.liveObjectsOnly() && current.liveObjectsOnly()) {
            return 1.0;
        }
        return bothDeep ? 0.85 : 0.7;
    }

    private static SnapshotData readyData(SnapshotRecord record) {
        SnapshotData data = record.data();
        if (record.status() != SnapshotStatus.READY || data == null) {
            throw new OperationRejectedException(409, "SNAPSHOT_NOT_READY",
                    "Snapshot " + record.id() + " is " + record.status());
        }
        return data;
    }

    private static Map<String, ClassStatsDto> index(SnapshotData data) {
        Map<String, ClassStatsDto> byName = new HashMap<>(data.classes().size() * 2);
        for (ClassStatsDto stats : data.classes()) {
            byName.put(stats.className(), stats);
        }
        return byName;
    }

    private static long retainedOf(ClassStatsDto stats) {
        return stats == null || stats.retainedBytes() == null ? 0 : stats.retainedBytes();
    }
}
