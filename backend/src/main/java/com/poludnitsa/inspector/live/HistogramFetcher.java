package com.poludnitsa.inspector.live;

import com.poludnitsa.inspector.api.dto.GcGeneration;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.jmx.JmxAttributes;
import com.poludnitsa.inspector.jmx.MBeanNames;
import com.poludnitsa.inspector.jmx.TargetMetadata;
import java.io.IOException;
import java.lang.management.MemoryUsage;
import javax.management.JMException;
import javax.management.MBeanServerConnection;

/**
 * Runs {@code GC.class_histogram -all} on the target through {@code DiagnosticCommandMBean} and captures the GC
 * context right after it.
 *
 * <p>{@code -all} inspects all objects including unreachable ones and therefore does <b>not</b> request a Full GC.
 * Without it HotSpot runs a Full GC first. The heap walk itself still happens at a safepoint (parallel on Java 16+),
 * so its duration is measured and reported.
 */
public final class HistogramFetcher {

    static final String OPERATION = "gcClassHistogram";
    static final Object[] ARGUMENTS = {new String[] {"-all"}};
    static final String[] SIGNATURE = {String[].class.getName()};

    public record Result(ClassHistogram histogram, LeakRadar.GcContext gcContext, long durationMs, long heapUsedBytes) {
    }

    private HistogramFetcher() {
    }

    public static Result fetch(MBeanServerConnection connection, TargetMetadata metadata)
            throws IOException, JMException {
        if (!metadata.diagnosticCommandAvailable()) {
            throw new OperationRejectedException(409, "DIAGNOSTIC_COMMAND_UNAVAILABLE",
                    "Target has no com.sun.management:type=DiagnosticCommand MBean (not a HotSpot JVM?)");
        }
        long timestamp = System.currentTimeMillis();
        long started = System.nanoTime();
        Object output = connection.invoke(MBeanNames.DIAGNOSTIC_COMMAND, OPERATION, ARGUMENTS, SIGNATURE);
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        ClassHistogram histogram = ClassHistogramParser.parse(output == null ? null : output.toString(), timestamp);
        return new Result(histogram, readGcContext(connection, metadata), durationMs,
                JmxAttributes.heapUsed(connection));
    }

    static LeakRadar.GcContext readGcContext(MBeanServerConnection connection, TargetMetadata metadata)
            throws IOException, JMException {
        long total = 0;
        long old = 0;
        for (TargetMetadata.CollectorInfo collector : metadata.collectors()) {
            long count = JmxAttributes.longValue(connection.getAttribute(collector.objectName(), "CollectionCount"), 0);
            if (count > 0) {
                total += count;
                if (collector.generation() == GcGeneration.OLD) {
                    old += count;
                }
            }
        }
        Long oldUsedAfterGc = null;
        TargetMetadata.PoolInfo oldPool = metadata.oldGenerationPool().orElse(null);
        if (oldPool != null) {
            MemoryUsage usage = JmxAttributes.memoryUsage(
                    connection.getAttribute(oldPool.objectName(), "CollectionUsage"));
            oldUsedAfterGc = usage == null ? null : usage.getUsed();
        }
        return new LeakRadar.GcContext(total, old, oldUsedAfterGc);
    }
}
