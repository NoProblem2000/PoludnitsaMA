package com.poludnitsa.inspector.snapshot;

import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.MemoryGroup;
import com.poludnitsa.inspector.api.dto.MemoryGroupStatsDto;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.common.PackageCategorizer;
import com.poludnitsa.inspector.live.ClassHistogram;
import com.poludnitsa.inspector.live.HistogramEntry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Builds {@link SnapshotData} parts shared by histogram and deep snapshots. */
final class SnapshotDataFactory {

    private SnapshotDataFactory() {
    }

    static SnapshotData fromHistogram(ClassHistogram histogram) {
        List<ClassStatsDto> classes = new ArrayList<>(histogram.entries().size());
        for (HistogramEntry entry : histogram.entries()) {
            PackageCategory category = PackageCategorizer.categorize(entry.className());
            classes.add(new ClassStatsDto(entry.className(), category, MemoryGroup.of(category), entry.instances(),
                    entry.bytes(), null, entry.definitionCount(), null));
        }
        return new SnapshotData(histogram.totalInstances(), classes.size(), histogram.totalBytes(), 0, false,
                classes, List.of(), groups(classes, histogram.totalBytes()), List.of(), List.of());
    }

    /** One entry per {@link MemoryGroup}, in declaration order, including empty groups. */
    static List<MemoryGroupStatsDto> groups(List<ClassStatsDto> classes, long totalHeapBytes) {
        Map<MemoryGroup, long[]> sums = new EnumMap<>(MemoryGroup.class);
        for (MemoryGroup group : MemoryGroup.values()) {
            sums.put(group, new long[3]);
        }
        for (ClassStatsDto stats : classes) {
            long[] sum = sums.get(stats.memoryGroup());
            sum[0]++;
            sum[1] += stats.instances();
            sum[2] += stats.shallowBytes();
        }
        List<MemoryGroupStatsDto> result = new ArrayList<>(sums.size());
        sums.forEach((group, sum) -> result.add(new MemoryGroupStatsDto(group, (int) sum[0], sum[1], sum[2],
                totalHeapBytes > 0 ? 100.0 * sum[2] / totalHeapBytes : 0)));
        return result;
    }
}
