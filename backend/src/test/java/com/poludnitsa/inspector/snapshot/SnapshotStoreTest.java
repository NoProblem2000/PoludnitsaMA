package com.poludnitsa.inspector.snapshot;

import static com.poludnitsa.inspector.snapshot.SnapshotTestData.ready;
import static com.poludnitsa.inspector.snapshot.SnapshotTestData.stats;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.poludnitsa.inspector.api.dto.MemoryGroup;
import com.poludnitsa.inspector.api.dto.MemoryGroupStatsDto;
import com.poludnitsa.inspector.api.dto.ProcessType;
import com.poludnitsa.inspector.api.dto.SnapshotKind;
import com.poludnitsa.inspector.api.dto.SnapshotStatus;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotStoreTest {

    @TempDir
    Path workDir;

    @Test
    void finishedSnapshotsSurviveRestart() {
        SnapshotStore store = new SnapshotStore(SnapshotTestData.properties(workDir), SnapshotTestData.mapper());
        SnapshotRecord record = ready(store, SnapshotKind.DEEP, true, List.of(
                stats("com.acme.OrderDto", 10, 320L, 4096L), stats("byte[]", 5, 1000L, 1000L)));

        SnapshotStore restarted = new SnapshotStore(SnapshotTestData.properties(workDir), SnapshotTestData.mapper());

        SnapshotRecord restored = restarted.get(record.id());
        assertThat(restored.status()).isEqualTo(SnapshotStatus.READY);
        assertThat(restored.toSummary().kind()).isEqualTo(SnapshotKind.DEEP);
        assertThat(restored.toSummary().liveObjectsOnly()).isTrue();
        assertThat(restored.data()).isEqualTo(record.data());
    }

    @Test
    void groupsPartitionShallowBytes() {
        SnapshotStore store = new SnapshotStore(SnapshotTestData.properties(workDir), SnapshotTestData.mapper());
        SnapshotRecord record = ready(store, SnapshotKind.LIVE_HISTOGRAM, false, List.of(
                stats("com.acme.OrderDto", 10, 300L, null),
                stats("io.undertow.server.HttpServerExchange", 2, 200L, null),
                stats("com.hazelcast.client.impl.ClientMessage", 4, 400L, null),
                stats("byte[]", 1, 100L, null)));

        List<MemoryGroupStatsDto> groups = record.data().groups();

        assertThat(groups).extracting(MemoryGroupStatsDto::group).containsExactly(MemoryGroup.values());
        assertThat(groups.stream().mapToLong(MemoryGroupStatsDto::shallowBytes).sum()).isEqualTo(1000);
        assertThat(groups.get(0).percentOfHeap()).isEqualTo(30.0);
        assertThat(groups.stream().filter(g -> g.group() == MemoryGroup.JDK_CORE).findFirst().orElseThrow()
                .shallowBytes()).isEqualTo(100);
    }

    @Test
    void oldestFinishedSnapshotsAreEvicted() {
        SnapshotStore store = new SnapshotStore(
                SnapshotTestData.properties(workDir, "inspector.snapshots.max-snapshots", "2"),
                SnapshotTestData.mapper());
        SnapshotRecord first = ready(store, SnapshotKind.LIVE_HISTOGRAM, false, List.of(stats("a.A", 1, 16L, null)));
        ready(store, SnapshotKind.LIVE_HISTOGRAM, false, List.of(stats("a.A", 1, 16L, null)));
        ready(store, SnapshotKind.LIVE_HISTOGRAM, false, List.of(stats("a.A", 1, 16L, null)));

        assertThat(store.list()).hasSize(2);
        assertThat(store.find(first.id())).isEmpty();
        assertThat(workDir.resolve("summaries").resolve(first.id() + ".json.gz")).doesNotExist();
    }

    @Test
    void runningSnapshotCannotBeDeletedOrExported() {
        SnapshotStore store = new SnapshotStore(SnapshotTestData.properties(workDir), SnapshotTestData.mapper());
        SnapshotRecord running = store.create(SnapshotKind.DEEP, 1, "x", ProcessType.STANDARD_JVM, false);

        assertThatThrownBy(() -> store.delete(running.id())).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> store.export(running, new ByteArrayOutputStream()))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> store.get("missing")).isInstanceOf(InspectorException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void exportIsGzippedJsonAndDeleteRemovesSummary() throws Exception {
        SnapshotStore store = new SnapshotStore(SnapshotTestData.properties(workDir), SnapshotTestData.mapper());
        SnapshotRecord record = ready(store, SnapshotKind.LIVE_HISTOGRAM, false,
                List.of(stats("com.acme.OrderDto", 10, 320L, null)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        store.export(record, out);

        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(json).contains("\"formatVersion\":1", "com.acme.OrderDto", record.id());
        }
        Path summary = workDir.resolve("summaries").resolve(record.id() + ".json.gz");
        assertThat(Files.exists(summary)).isTrue();
        store.delete(record.id());
        assertThat(summary).doesNotExist();
        assertThat(store.find(record.id())).isEmpty();
    }
}
