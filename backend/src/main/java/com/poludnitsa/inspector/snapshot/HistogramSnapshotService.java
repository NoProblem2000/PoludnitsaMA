package com.poludnitsa.inspector.snapshot;

import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.ProcessType;
import com.poludnitsa.inspector.api.dto.SnapshotKind;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.live.HistogramFetcher;
import com.poludnitsa.inspector.live.LiveMemoryCollectorService;
import org.springframework.stereotype.Service;

/** Stores a quick {@code GC.class_histogram -all} as a snapshot: no dump, no Full GC, milliseconds of pause. */
@Service
public class HistogramSnapshotService {

    private final LiveMemoryCollectorService collector;
    private final JvmDiscoveryService discovery;
    private final SnapshotStore store;

    public HistogramSnapshotService(LiveMemoryCollectorService collector, JvmDiscoveryService discovery,
                                    SnapshotStore store) {
        this.collector = collector;
        this.discovery = discovery;
        this.store = store;
    }

    public SnapshotSummaryDto capture(long pid) {
        HistogramFetcher.Result result = collector.captureHistogram(pid);
        JvmProcessDto process = describe(pid);
        SnapshotRecord record = store.create(SnapshotKind.LIVE_HISTOGRAM, pid,
                process == null ? "PID " + pid : process.displayName(),
                process == null ? ProcessType.STANDARD_JVM : process.processType(), false);
        record.dumped(result.durationMs(), 0);
        record.analysed(null, SnapshotDataFactory.fromHistogram(result.histogram()));
        store.complete(record);
        return record.toSummary();
    }

    private JvmProcessDto describe(long pid) {
        try {
            return discovery.describe(pid);
        } catch (InspectorException e) {
            return null;
        }
    }
}
