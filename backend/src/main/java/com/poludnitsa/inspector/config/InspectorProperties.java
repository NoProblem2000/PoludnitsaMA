package com.poludnitsa.inspector.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/** All tunables of the inspector, bound from {@code inspector.*}. */
@ConfigurationProperties(prefix = "inspector")
public record InspectorProperties(
        @DefaultValue Live live,
        @DefaultValue Jmx jmx,
        @DefaultValue Jfr jfr,
        @DefaultValue Snapshots snapshots,
        @DefaultValue Web web
) {

    /** Properties with every default applied, for code constructed outside a Spring context. */
    public static InspectorProperties defaults() {
        return new Binder().bindOrCreate("inspector", InspectorProperties.class);
    }

    /**
     * @param sampleInterval         MemoryMXBean / pools / GC polling period
     * @param histogramInterval      base period of {@code GC.class_histogram -all} for the leak radar
     * @param histogramMaxInterval   upper bound when the interval is stretched because histograms are slow
     * @param histogramMinGap        minimum gap between two histograms, including on-demand ones
     * @param histogramPauseBudget   histograms slower than this double the interval (the heap walk is a safepoint)
     * @param histogramTimeout       JMX call timeout of one histogram (large heaps take seconds)
     * @param maxTrackedClasses      classes with a rolling series per target
     * @param trackMinBytes          a class starts being tracked once its shallow size reaches this ...
     * @param trackMinInstances      ... or its instance count reaches this
     * @param topSuspects            suspects included in every live payload
     * @param leakCandidateThreshold score from which a class is flagged as a leak candidate
     * @param idleGrace              a monitor without subscribers and not selected stops after this
     * @param samplerThreads         threads polling all monitored targets
     */
    public record Live(
            @DefaultValue("1s") Duration sampleInterval,
            @DefaultValue("10s") Duration histogramInterval,
            @DefaultValue("2m") Duration histogramMaxInterval,
            @DefaultValue("2s") Duration histogramMinGap,
            @DefaultValue("500ms") Duration histogramPauseBudget,
            @DefaultValue("2m") Duration histogramTimeout,
            @DefaultValue("3000") int maxTrackedClasses,
            @DefaultValue("32KB") DataSize trackMinBytes,
            @DefaultValue("500") long trackMinInstances,
            @DefaultValue("30") int topSuspects,
            @DefaultValue("60") int leakCandidateThreshold,
            @DefaultValue("60s") Duration idleGrace,
            @DefaultValue("4") int samplerThreads
    ) {
    }

    /**
     * @param connectTimeout          attach + JMX connect must finish within this
     * @param callTimeout             default timeout of one JMX call; a stalled target fails fast instead of
     *                                blocking a sampler thread
     * @param reconnectInitialBackoff first delay after a failed (re)connect
     * @param reconnectMaxBackoff     cap of the exponential backoff
     * @param connectionCheckPeriod   JMX client heartbeat; detects dead connections without traffic
     * @param probeCacheTtl           how long attach-based process facts are reused
     * @param failedProbeRetry        how soon a process that could not be attached is probed again
     */
    public record Jmx(
            @DefaultValue("15s") Duration connectTimeout,
            @DefaultValue("20s") Duration callTimeout,
            @DefaultValue("1s") Duration reconnectInitialBackoff,
            @DefaultValue("30s") Duration reconnectMaxBackoff,
            @DefaultValue("10s") Duration connectionCheckPeriod,
            @DefaultValue("10m") Duration probeCacheTtl,
            @DefaultValue("30s") Duration failedProbeRetry
    ) {
    }

    /**
     * @param pollInterval             how often JFR data is pulled from the target; every poll rotates the target's
     *                                 JFR chunk, so do not go below a few seconds
     * @param objectAllocationThrottle {@code jdk.ObjectAllocationSample#throttle}
     * @param maxAge                   {@code maxAge} of the recording on the target, bounds its disk repository
     * @param maxSize                  {@code maxSize} of the recording on the target
     * @param topClasses               allocating classes reported
     * @param workDir                  local directory for transferred JFR chunks, defaults to
     *                                 {@code ${java.io.tmpdir}/jvm-leak-inspector/jfr}
     */
    public record Jfr(
            @DefaultValue("10s") Duration pollInterval,
            @DefaultValue("100/s") String objectAllocationThrottle,
            @DefaultValue("3m") Duration maxAge,
            @DefaultValue("64MB") DataSize maxSize,
            @DefaultValue("15") int topClasses,
            Path workDir
    ) {
        public Path resolvedWorkDir() {
            return workDir != null ? workDir : Path.of(System.getProperty("java.io.tmpdir"), "jvm-leak-inspector", "jfr");
        }
    }

    /**
     * @param workDir                    root of snapshot files, defaults to
     *                                   {@code ${java.io.tmpdir}/jvm-leak-inspector/snapshots}. The target JVM writes
     *                                   the dump here, so it must see the same path (not under a systemd PrivateTmp).
     * @param diskSpaceFactor            free space required before a dump, as a multiple of the target's used heap;
     *                                   never below 2.5 (dump + NetBeans indexes)
     * @param dumpTimeout                maximum duration of {@code dumpHeap} on the target
     * @param topDominators              objects reported by retained size
     * @param classesWithSamplePath      top classes by retained size that get a representative GC root path
     * @param maxInstancesScannedPerClass instances checked per class when looking for that representative
     * @param maxPathDepth               hops followed towards a GC root before the path is reported as truncated
     * @param maxSnapshots               snapshots kept in the session; the oldest finished ones are dropped
     */
    public record Snapshots(
            Path workDir,
            @DefaultValue("2.5") double diskSpaceFactor,
            @DefaultValue("30m") Duration dumpTimeout,
            @DefaultValue("200") int topDominators,
            @DefaultValue("50") int classesWithSamplePath,
            @DefaultValue("100000") int maxInstancesScannedPerClass,
            @DefaultValue("64") int maxPathDepth,
            @DefaultValue("20") int maxSnapshots
    ) {
        public static final double MIN_DISK_SPACE_FACTOR = 2.5;

        public Path resolvedWorkDir() {
            return workDir != null
                    ? workDir
                    : Path.of(System.getProperty("java.io.tmpdir"), "jvm-leak-inspector", "snapshots");
        }

        public double effectiveDiskSpaceFactor() {
            return Math.max(MIN_DISK_SPACE_FACTOR, diskSpaceFactor);
        }
    }

    /**
     * @param allowedOriginHosts hosts allowed in the {@code Origin} header of REST and WebSocket requests; requests
     *                           from any other web page are rejected so a browser tab cannot drive the inspector
     */
    public record Web(
            @DefaultValue({"localhost", "127.0.0.1", "[::1]"}) List<String> allowedOriginHosts
    ) {
    }
}
