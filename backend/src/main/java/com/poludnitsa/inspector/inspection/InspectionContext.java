package com.poludnitsa.inspector.inspection;

import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.heap.GcRootPathResolver;
import com.poludnitsa.inspector.heap.HeapReader;
import org.netbeans.lib.profiler.heap.Heap;

/** Everything a rule needs: the heap, readers, GC root paths, thresholds and its time budget. */
public final class InspectionContext {

    private final Heap heap;
    private final HeapReader reader;
    private final GcRootPathResolver paths;
    private final long totalHeapBytes;
    private final InspectorProperties.Inspections settings;
    private long deadlineNanos = Long.MAX_VALUE;
    private boolean truncated;

    public InspectionContext(Heap heap, GcRootPathResolver paths, long totalHeapBytes,
                             InspectorProperties.Inspections settings) {
        this.heap = heap;
        this.reader = new HeapReader(heap);
        this.paths = paths;
        this.totalHeapBytes = Math.max(1, totalHeapBytes);
        this.settings = settings;
    }

    public Heap heap() {
        return heap;
    }

    public HeapReader reader() {
        return reader;
    }

    public GcRootPathResolver paths() {
        return paths;
    }

    public long totalHeapBytes() {
        return totalHeapBytes;
    }

    public InspectorProperties.Inspections settings() {
        return settings;
    }

    public double shareOfHeap(long bytes) {
        return (double) bytes / totalHeapBytes;
    }

    /** CRITICAL from {@code criticalShare} of the heap, WARNING from {@code warningShare}, INFO below. */
    public Severity severityByShare(long bytes, double warningShare, double criticalShare) {
        double share = shareOfHeap(bytes);
        if (share >= criticalShare) {
            return Severity.CRITICAL;
        }
        return share >= warningShare ? Severity.WARNING : Severity.INFO;
    }

    /** {@code true} once the rule's time budget is used up; the run is then marked truncated. */
    public boolean outOfBudget() {
        if (System.nanoTime() > deadlineNanos) {
            truncated = true;
        }
        return truncated;
    }

    /** Marks the run as partial for another reason (e.g. instance cap reached). */
    public void markTruncated() {
        truncated = true;
    }

    public FindingBuilder finding(LeakInspectionRule rule, String key) {
        return new FindingBuilder(rule, key, paths);
    }

    void startRule(long budgetNanos) {
        deadlineNanos = System.nanoTime() + budgetNanos;
        truncated = false;
    }

    boolean truncated() {
        return truncated;
    }
}
