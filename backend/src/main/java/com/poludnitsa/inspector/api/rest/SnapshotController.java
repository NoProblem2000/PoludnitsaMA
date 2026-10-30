package com.poludnitsa.inspector.api.rest;

import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.DiskSpaceCheckDto;
import com.poludnitsa.inspector.api.dto.DominatorNodeDto;
import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.InspectionRuleRunDto;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.api.dto.HeapDumpRequestDto;
import com.poludnitsa.inspector.api.dto.HistogramSnapshotRequestDto;
import com.poludnitsa.inspector.api.dto.MemoryGroup;
import com.poludnitsa.inspector.api.dto.MemoryGroupStatsDto;
import com.poludnitsa.inspector.api.dto.PageDto;
import com.poludnitsa.inspector.api.dto.SnapshotDiffDto;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.snapshot.HistogramSnapshotService;
import com.poludnitsa.inspector.snapshot.SafeHeapDumpService;
import com.poludnitsa.inspector.snapshot.SnapshotData;
import com.poludnitsa.inspector.snapshot.SnapshotDiffService;
import com.poludnitsa.inspector.snapshot.SnapshotRecord;
import com.poludnitsa.inspector.snapshot.SnapshotStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Deep snapshots (heap dump + analysis), quick histogram snapshots, their content and diffs. */
@RestController
@RequestMapping("/api/snapshots")
@Validated
public class SnapshotController {

    private static final String ID = "^[A-Za-z0-9-]{1,64}$";

    public enum ClassSort { RETAINED, SHALLOW, INSTANCES, NAME }

    private final SafeHeapDumpService dumps;
    private final HistogramSnapshotService histograms;
    private final SnapshotStore store;
    private final SnapshotDiffService diffs;

    public SnapshotController(SafeHeapDumpService dumps, HistogramSnapshotService histograms, SnapshotStore store,
                              SnapshotDiffService diffs) {
        this.dumps = dumps;
        this.histograms = histograms;
        this.store = store;
        this.diffs = diffs;
    }

    @GetMapping
    public List<SnapshotSummaryDto> list() {
        return store.list();
    }

    /** Lets the UI show "needs X, Y free" before the operator confirms a dump. */
    @GetMapping("/disk-check")
    public DiskSpaceCheckDto diskCheck(@RequestParam @Positive long pid) {
        return dumps.checkDiskSpace(pid);
    }

    /** Starts dump + analysis; 202 with the snapshot to poll. 507 when the disk is too small, 409 if one runs. */
    @PostMapping("/deep")
    public ResponseEntity<SnapshotSummaryDto> deep(@Valid @RequestBody HeapDumpRequestDto request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(dumps.startDeepSnapshot(request.pid(), request.liveObjectsOnly()));
    }

    @PostMapping("/histogram")
    public ResponseEntity<SnapshotSummaryDto> histogram(@Valid @RequestBody HistogramSnapshotRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(histograms.capture(request.pid()));
    }

    /** Current minus baseline, ranked by anomalous survival. */
    @GetMapping("/diff")
    public SnapshotDiffDto diff(@RequestParam @Pattern(regexp = ID) String baseline,
                                @RequestParam @Pattern(regexp = ID) String current,
                                @RequestParam(defaultValue = "200") @Min(1) @Max(5000) int limit,
                                @RequestParam(defaultValue = "false") boolean includeShrinking) {
        return diffs.diff(baseline, current, limit, includeShrinking);
    }

    @GetMapping("/{id}")
    public SnapshotSummaryDto get(@PathVariable @Pattern(regexp = ID) String id) {
        return store.get(id).toSummary();
    }

    @GetMapping("/{id}/classes")
    public PageDto<ClassStatsDto> classes(@PathVariable @Pattern(regexp = ID) String id,
                                          @RequestParam(defaultValue = "RETAINED") ClassSort sort,
                                          @RequestParam(required = false) MemoryGroup group,
                                          @RequestParam(required = false) String search,
                                          @RequestParam(defaultValue = "0") @Min(0) int offset,
                                          @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        SnapshotData data = readyData(id);
        Stream<ClassStatsDto> stream = data.classes().stream();
        if (group != null) {
            stream = stream.filter(c -> c.memoryGroup() == group);
        }
        if (search != null && !search.isBlank()) {
            String needle = search.toLowerCase(Locale.ROOT);
            stream = stream.filter(c -> c.className().toLowerCase(Locale.ROOT).contains(needle));
        }
        List<ClassStatsDto> filtered = stream.sorted(comparator(sort, data.retainedSizesAvailable())).toList();
        int from = Math.min(offset, filtered.size());
        int to = Math.min(from + limit, filtered.size());
        return new PageDto<>(filtered.subList(from, to), filtered.size(), offset, limit);
    }

    @GetMapping("/{id}/dominators")
    public List<DominatorNodeDto> dominators(@PathVariable @Pattern(regexp = ID) String id) {
        return readyData(id).dominators();
    }

    @GetMapping("/{id}/groups")
    public List<MemoryGroupStatsDto> groups(@PathVariable @Pattern(regexp = ID) String id) {
        return readyData(id).groups();
    }

    /** Leak inspection findings, most severe first. */
    @GetMapping("/{id}/findings")
    public List<InspectionFindingDto> findings(@PathVariable @Pattern(regexp = ID) String id,
                                               @RequestParam(required = false) Severity severity,
                                               @RequestParam(required = false) FindingCategory category) {
        return readyData(id).findings().stream()
                .filter(f -> severity == null || f.severity() == severity)
                .filter(f -> category == null || f.category() == category)
                .toList();
    }

    /** How each inspection rule ran (duration, truncated, errors). */
    @GetMapping("/{id}/inspections")
    public List<InspectionRuleRunDto> inspections(@PathVariable @Pattern(regexp = ID) String id) {
        return readyData(id).inspections();
    }

    /** The analysed snapshot as gzipped JSON, for offline review or attaching to a ticket. */
    @GetMapping("/{id}/export")
    public ResponseEntity<StreamingResponseBody> export(@PathVariable @Pattern(regexp = ID) String id) {
        SnapshotRecord record = store.get(id);
        readyData(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/gzip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"snapshot-" + id + ".json.gz\"")
                .body(out -> store.export(record, out));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Pattern(regexp = ID) String id) {
        store.delete(id);
        return ResponseEntity.noContent().build();
    }

    private SnapshotData readyData(String id) {
        SnapshotRecord record = store.get(id);
        SnapshotData data = record.data();
        if (data == null || !record.isFinished()) {
            throw new OperationRejectedException(409, "SNAPSHOT_NOT_READY",
                    "Snapshot " + id + " is " + record.status());
        }
        return data;
    }

    private static Comparator<ClassStatsDto> comparator(ClassSort sort, boolean retainedAvailable) {
        Comparator<ClassStatsDto> byName = Comparator.comparing(ClassStatsDto::className);
        return switch (sort == ClassSort.RETAINED && !retainedAvailable ? ClassSort.SHALLOW : sort) {
            case RETAINED -> Comparator.comparingLong((ClassStatsDto c) -> c.retainedBytes() == null ? 0
                    : c.retainedBytes()).reversed().thenComparing(byName);
            case SHALLOW -> Comparator.comparingLong(ClassStatsDto::shallowBytes).reversed().thenComparing(byName);
            case INSTANCES -> Comparator.comparingLong(ClassStatsDto::instances).reversed().thenComparing(byName);
            case NAME -> byName;
        };
    }
}
