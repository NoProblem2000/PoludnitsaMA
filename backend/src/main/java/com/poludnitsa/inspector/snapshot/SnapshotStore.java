package com.poludnitsa.inspector.snapshot;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poludnitsa.inspector.api.dto.ProcessType;
import com.poludnitsa.inspector.api.dto.SnapshotKind;
import com.poludnitsa.inspector.api.dto.SnapshotStatus;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Snapshots of the session, in memory, plus one gzipped JSON summary per finished snapshot under
 * {@code <work-dir>/summaries}. Summaries are small (class statistics, dominators, paths) and are reloaded at start,
 * so an analysis survives an inspector restart although the raw dump is long gone.
 */
@Component
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final String SUFFIX = ".json.gz";
    private static final int FORMAT_VERSION = 1;
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    /** Creation order; the sequence breaks ties between snapshots created within one clock tick. */
    public static final Comparator<SnapshotRecord> CHRONOLOGICAL =
            Comparator.comparing(SnapshotRecord::createdAt).thenComparingLong(SnapshotRecord::sequence);

    /** File format of a persisted or exported snapshot. */
    public record PersistedSnapshot(int formatVersion, SnapshotSummaryDto summary, SnapshotData data) {
    }

    private final Path summariesDir;
    private final int maxSnapshots;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, SnapshotRecord> records = new ConcurrentHashMap<>();

    public SnapshotStore(InspectorProperties properties, ObjectMapper mapper) {
        this.summariesDir = properties.snapshots().resolvedWorkDir().resolve("summaries");
        this.maxSnapshots = Math.max(1, properties.snapshots().maxSnapshots());
        this.mapper = mapper;
        try {
            Files.createDirectories(summariesDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + summariesDir, e);
        }
        loadPersisted();
    }

    public SnapshotRecord create(SnapshotKind kind, long pid, String displayName, ProcessType processType,
                                 boolean liveObjectsOnly) {
        Instant now = Instant.now();
        while (true) {
            String id = ID_TIME.format(now) + "-" + pid + "-"
                    + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
            SnapshotRecord record = new SnapshotRecord(id, kind, pid, displayName, processType, now, liveObjectsOnly);
            if (records.putIfAbsent(id, record) == null) {
                evictOldest();
                return record;
            }
        }
    }

    /**
     * Registers a snapshot read from a report file. Keeps its id when it is valid and free, otherwise assigns a new
     * one, so imported snapshots never overwrite local ones.
     */
    public SnapshotRecord importSnapshot(SnapshotSummaryDto summary, SnapshotData data) {
        String id = summary.snapshotId();
        while (id == null || !ID.matcher(id).matches() || records.containsKey(id)) {
            id = "imp-" + ID_TIME.format(Instant.now()) + "-"
                    + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
        }
        SnapshotRecord record = SnapshotRecord.restored(summary, data, id, true);
        records.put(id, record);
        try {
            persist(record);
        } catch (IOException e) {
            log.warn("Could not persist imported snapshot {}: {}", id, e.toString());
        }
        evictOldest();
        return record;
    }

    public Optional<SnapshotRecord> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(records.get(id));
    }

    public SnapshotRecord get(String id) {
        return find(id).orElseThrow(() -> new InspectorException(404, "SNAPSHOT_NOT_FOUND",
                "Snapshot " + id + " does not exist"));
    }

    /** Newest first. */
    public List<SnapshotSummaryDto> list() {
        return records.values().stream()
                .sorted(CHRONOLOGICAL.reversed())
                .map(SnapshotRecord::toSummary)
                .toList();
    }

    /** Marks the snapshot READY and persists its summary. */
    public void complete(SnapshotRecord record) {
        record.ready();
        try {
            persist(record);
        } catch (IOException e) {
            log.warn("Could not persist summary of snapshot {}: {}", record.id(), e.toString());
        }
    }

    public void delete(String id) {
        SnapshotRecord record = get(id);
        if (!record.isFinished()) {
            throw new OperationRejectedException(409, "SNAPSHOT_IN_PROGRESS",
                    "Snapshot " + id + " is still " + record.status() + "; it can be deleted once finished");
        }
        records.remove(id, record);
        deleteSummaryFile(id);
    }

    /** Writes the snapshot as gzipped JSON; the stream is not closed. */
    public void export(SnapshotRecord record, OutputStream out) throws IOException {
        if (record.status() != SnapshotStatus.READY) {
            throw new OperationRejectedException(409, "SNAPSHOT_NOT_READY", "Snapshot " + record.id() + " is "
                    + record.status());
        }
        GZIPOutputStream gzip = new GZIPOutputStream(out, 64 * 1024);
        mapper.writer().without(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
                .writeValue(gzip, new PersistedSnapshot(FORMAT_VERSION, record.toSummary(), record.data()));
        gzip.finish();
    }

    private void persist(SnapshotRecord record) throws IOException {
        Path target = summariesDir.resolve(record.id() + SUFFIX);
        Path temporary = summariesDir.resolve(record.id() + SUFFIX + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary);
             GZIPOutputStream gzip = new GZIPOutputStream(out, 64 * 1024)) {
            mapper.writeValue(gzip, new PersistedSnapshot(FORMAT_VERSION, record.toSummary(), record.data()));
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void loadPersisted() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(summariesDir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(SUFFIX + ".tmp")) {
                    Files.deleteIfExists(file);
                    continue;
                }
                if (!name.endsWith(SUFFIX) || !ID.matcher(name.substring(0, name.length() - SUFFIX.length()))
                        .matches()) {
                    continue;
                }
                try (InputStream in = new GZIPInputStream(Files.newInputStream(file), 64 * 1024)) {
                    PersistedSnapshot persisted = mapper.readValue(in, PersistedSnapshot.class);
                    SnapshotRecord record = SnapshotRecord.restored(persisted.summary(), persisted.data());
                    records.put(record.id(), record);
                } catch (IOException | RuntimeException e) {
                    log.warn("Skipping unreadable snapshot summary {}: {}", file, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("Cannot list {}: {}", summariesDir, e.toString());
        }
        evictOldest();
        if (!records.isEmpty()) {
            log.info("Restored {} snapshot summaries from {}", records.size(), summariesDir);
        }
    }

    private void evictOldest() {
        while (records.size() > maxSnapshots) {
            Optional<SnapshotRecord> oldest = records.values().stream()
                    .filter(SnapshotRecord::isFinished)
                    .min(CHRONOLOGICAL);
            if (oldest.isEmpty()) {
                return;
            }
            records.remove(oldest.get().id());
            deleteSummaryFile(oldest.get().id());
            log.info("Evicted snapshot {} (limit {})", oldest.get().id(), maxSnapshots);
        }
    }

    private void deleteSummaryFile(String id) {
        if (!ID.matcher(id).matches()) {
            return;
        }
        try {
            Files.deleteIfExists(summariesDir.resolve(id + SUFFIX));
        } catch (IOException e) {
            log.warn("Could not delete summary of snapshot {}: {}", id, e.toString());
        }
    }
}
