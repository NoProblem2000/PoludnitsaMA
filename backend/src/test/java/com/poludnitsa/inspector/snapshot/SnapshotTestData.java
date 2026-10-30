package com.poludnitsa.inspector.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.MemoryGroup;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.api.dto.ProcessType;
import com.poludnitsa.inspector.api.dto.SnapshotKind;
import com.poludnitsa.inspector.common.PackageCategorizer;
import com.poludnitsa.inspector.config.InspectorProperties;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

final class SnapshotTestData {

    private SnapshotTestData() {
    }

    /** Properties with the work dir set; {@code extra} are key/value pairs. */
    static InspectorProperties properties(Path workDir, String... extra) {
        Map<String, String> values = new HashMap<>();
        values.put("inspector.snapshots.work-dir", workDir.toString());
        for (int i = 0; i < extra.length; i += 2) {
            values.put(extra[i], extra[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("inspector",
                InspectorProperties.class);
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    static ClassStatsDto stats(String name, long instances, long shallow, Long retained) {
        PackageCategory category = PackageCategorizer.categorize(name);
        return new ClassStatsDto(name, category, MemoryGroup.of(category), instances, shallow, retained, 1, null);
    }

    static SnapshotRecord ready(SnapshotStore store, SnapshotKind kind, boolean live, List<ClassStatsDto> classes) {
        SnapshotRecord record = store.create(kind, 4711, "orders", ProcessType.WILDFLY, live);
        long total = classes.stream().mapToLong(ClassStatsDto::shallowBytes).sum();
        long instances = classes.stream().mapToLong(ClassStatsDto::instances).sum();
        record.analysed(10L, new SnapshotData(instances, classes.size(), total, kind == SnapshotKind.DEEP ? 5 : 0,
                kind == SnapshotKind.DEEP, classes, List.of(), SnapshotDataFactory.groups(classes, total)));
        store.complete(record);
        return record;
    }
}
