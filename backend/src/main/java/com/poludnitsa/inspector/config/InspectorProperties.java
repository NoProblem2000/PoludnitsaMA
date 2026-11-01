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
        @DefaultValue Inspections inspections,
        @DefaultValue Oql oql,
        @DefaultValue Profiler profiler,
        @DefaultValue Threads threads,
        @DefaultValue GcLog gcLog,
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
     * @param historySize            compact metric points kept per target for charts and reports (1 per sample)
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
            @DefaultValue("4") int samplerThreads,
            @DefaultValue("3600") int historySize
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
     * @param tlabEvents               also record {@code jdk.ObjectAllocationInNewTLAB} and
     *                                 {@code jdk.ObjectAllocationOutsideTLAB} with stack traces: complete allocation
     *                                 call trees, but one event per TLAB refill (measurable overhead on busy servers);
     *                                 {@code jdk.ObjectAllocationSample} (throttled) always has stack traces
     * @param stackDepth               frames kept per allocation stack, from the allocating frame down
     * @param stackPolls               polls aggregated for allocation stacks (window = stackPolls x pollInterval)
     * @param maxStacksPerClass        distinct stacks kept per class and poll
     * @param workDir                  local directory for transferred JFR chunks, defaults to
     *                                 {@code ${java.io.tmpdir}/jvm-leak-inspector/jfr}
     */
    public record Jfr(
            @DefaultValue("10s") Duration pollInterval,
            @DefaultValue("100/s") String objectAllocationThrottle,
            @DefaultValue("3m") Duration maxAge,
            @DefaultValue("64MB") DataSize maxSize,
            @DefaultValue("15") int topClasses,
            @DefaultValue("false") boolean tlabEvents,
            @DefaultValue("12") int stackDepth,
            @DefaultValue("18") int stackPolls,
            @DefaultValue("200") int maxStacksPerClass,
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
     * @param retentionSamplesPerClass   instances per top class whose GC root paths are grouped into retention paths
     * @param retentionPathBudget        total time spent on retention paths per snapshot
     * @param maxClassLoaders            class loaders reported (largest first)
     */
    public record Snapshots(
            Path workDir,
            @DefaultValue("2.5") double diskSpaceFactor,
            @DefaultValue("30m") Duration dumpTimeout,
            @DefaultValue("200") int topDominators,
            @DefaultValue("50") int classesWithSamplePath,
            @DefaultValue("100000") int maxInstancesScannedPerClass,
            @DefaultValue("64") int maxPathDepth,
            @DefaultValue("20") int maxSnapshots,
            @DefaultValue("24") int retentionSamplesPerClass,
            @DefaultValue("30s") Duration retentionPathBudget,
            @DefaultValue("500") int maxClassLoaders
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
     * Thresholds of the leak inspections that run on every deep snapshot.
     *
     * @param enabled                      run inspections at all
     * @param disabledRules                rule ids to skip
     * @param ruleTimeBudget               a rule stops scanning after this and reports what it found (truncated)
     * @param maxInstancesPerRule          instances of one class a rule looks at
     * @param maxFindingsPerRule           findings kept per rule, most severe first
     * @param minReportedWaste             below this, informational findings are not reported
     * @param threadLocalMinBytes          ThreadLocal values above this in total are critical
     * @param poolThreadPrefixes           names of long-living server pool threads
     * @param collectionHeapShare          a collection retaining this share of the heap is reported
     * @param collectionMinElements        a collection with this many elements is reported
     * @param hibernateEntitiesWarning     managed entities per persistence context that warn
     * @param hibernateEntitiesCritical    managed entities per persistence context that are critical
     * @param httpSessionsWarning          in-memory HTTP sessions per deployment that warn
     * @param httpSessionFatBytes          average session size that counts as fat
     * @param listenersWarning             Hazelcast client listener registrations that warn
     * @param cdiDependentInstancesWarning dependent instances tracked by one CreationalContext that warn
     * @param cdiCollectionElementsWarning elements of a collection field of a long-living bean that warn
     * @param duplicateStringMinCopies     copies of the same text that make a duplicate group
     * @param maxStringsScanned            strings fingerprinted by the duplicate inspection
     */
    public record Inspections(
            @DefaultValue("true") boolean enabled,
            List<String> disabledRules,
            @DefaultValue("2m") Duration ruleTimeBudget,
            @DefaultValue("1000000") int maxInstancesPerRule,
            @DefaultValue("20") int maxFindingsPerRule,
            @DefaultValue("1MB") DataSize minReportedWaste,
            @DefaultValue("1MB") DataSize threadLocalMinBytes,
            @DefaultValue({"default task-", "XNIO-", "ejb-client-", "EE-Managed", "ServerService Thread Pool",
                    "Batch Thread", "Transaction Reaper"}) List<String> poolThreadPrefixes,
            @DefaultValue("0.05") double collectionHeapShare,
            @DefaultValue("50000") int collectionMinElements,
            @DefaultValue("1000") int hibernateEntitiesWarning,
            @DefaultValue("10000") int hibernateEntitiesCritical,
            @DefaultValue("10000") int httpSessionsWarning,
            @DefaultValue("512KB") DataSize httpSessionFatBytes,
            @DefaultValue("100") int listenersWarning,
            @DefaultValue("1000") int cdiDependentInstancesWarning,
            @DefaultValue("10000") int cdiCollectionElementsWarning,
            @DefaultValue("50") int duplicateStringMinCopies,
            @DefaultValue("2000000") int maxStringsScanned
    ) {
    }

    /**
     * Open heap dumps and OQL. A dump taken with {@code keepForQueries} stays on disk with its indexes until closed,
     * idle for {@code idleTimeout} or evicted by a newer one.
     *
     * @param enabled          allow keeping dumps open and running OQL at all
     * @param maxOpenHeaps     open dumps at the same time; opening one more closes the least recently used
     * @param idleTimeout      an open dump not queried for this long is closed and deleted
     * @param defaultRowLimit  rows returned when a query sets no limit
     * @param maxRowLimit      upper bound of the limit a query may ask for
     * @param queryTimeout     a running query is cancelled after this unless the request asks for less
     * @param maxQueryLength   longer queries are rejected
     * @param pathSearchLimit  objects an on-demand GC root path search may visit
     */
    public record Oql(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1") int maxOpenHeaps,
            @DefaultValue("30m") Duration idleTimeout,
            @DefaultValue("200") int defaultRowLimit,
            @DefaultValue("5000") int maxRowLimit,
            @DefaultValue("60s") Duration queryTimeout,
            @DefaultValue("10000") int maxQueryLength,
            @DefaultValue("1000000") int pathSearchLimit
    ) {
    }

    /**
     * CPU, wall-clock and allocation profiles of a target with its event timeline (JFR in the target, or
     * async-profiler on Linux / macOS).
     *
     * @param cpuSamplingInterval    {@code jdk.ExecutionSample#period}: one stack per running thread per period
     * @param nativeSamplingInterval {@code jdk.NativeMethodSample#period}: threads in native code
     * @param eventThreshold         minimum duration of park, monitor, sleep and socket / file events (sampling mode)
     * @param tracingThreshold       the same in tracing mode, which also records every thrown exception
     * @param maxDuration            longest recording accepted
     * @param maxProfiles            profiles kept in memory; the oldest finished ones are dropped
     * @param maxSamples             samples and events kept per profile; a longer recording is truncated
     * @param asyncProfilerPath      {@code asprof} binary or async-profiler home; empty: PATH, ASYNC_PROFILER_HOME,
     *                               /opt/async-profiler
     * @param asyncProfilerInterval  sampling interval of async-profiler (cpu and wall)
     * @param flameMinShare          frames below this share of the total are cut from flame graphs
     * @param flameMaxNodes          upper bound of nodes in one flame graph
     * @param timelineBuckets        columns of the timeline
     * @param timelineThreads        thread lanes shown (busiest first, WildFly pool threads preferred)
     * @param workDir                local directory for transferred recordings, defaults to
     *                               {@code ${java.io.tmpdir}/jvm-leak-inspector/profiles}
     */
    public record Profiler(
            @DefaultValue("10ms") Duration cpuSamplingInterval,
            @DefaultValue("20ms") Duration nativeSamplingInterval,
            @DefaultValue("10ms") Duration eventThreshold,
            @DefaultValue("1ms") Duration tracingThreshold,
            @DefaultValue("10m") Duration maxDuration,
            @DefaultValue("8") int maxProfiles,
            @DefaultValue("3000000") int maxSamples,
            String asyncProfilerPath,
            @DefaultValue("10ms") Duration asyncProfilerInterval,
            @DefaultValue("0.0005") double flameMinShare,
            @DefaultValue("20000") int flameMaxNodes,
            @DefaultValue("240") int timelineBuckets,
            @DefaultValue("12") int timelineThreads,
            Path workDir
    ) {
        public Path resolvedWorkDir() {
            return workDir != null ? workDir
                    : Path.of(System.getProperty("java.io.tmpdir"), "jvm-leak-inspector", "profiles");
        }
    }

    /**
     * Thread diagnostics: real-time contention / deadlock sampling and series of thread dumps.
     *
     * @param sampleInterval        period of the live contention sampler ({@code dumpAllThreads} without locks)
     * @param sampleDepth           stack frames per thread in those samples
     * @param deadlockCheckInterval {@code findDeadlockedThreads}, thread counts and CPU times this often
     * @param liveIdleTimeout       the live sampler of a target stops when the UI has not asked for this long
     * @param stuckMinDumps         consecutive dumps with the same stack that make a thread stuck
     * @param maxStackDepth         frames kept per stack in a series analysis
     * @param maxSeries             series kept in memory; the oldest finished one is dropped
     * @param maxClusters           clusters of identical stacks reported per series
     * @param liveThreads           threads listed in a live snapshot
     */
    public record Threads(
            @DefaultValue("500ms") Duration sampleInterval,
            @DefaultValue("32") int sampleDepth,
            @DefaultValue("2s") Duration deadlockCheckInterval,
            @DefaultValue("2m") Duration liveIdleTimeout,
            @DefaultValue("3") int stuckMinDumps,
            @DefaultValue("64") int maxStackDepth,
            @DefaultValue("10") int maxSeries,
            @DefaultValue("100") int maxClusters,
            @DefaultValue("200") int liveThreads
    ) {
    }

    /**
     * GC log analysis (Unified Logging files, JFR files, live JFR recordings of a target).
     *
     * @param maxEvents      pauses and safepoints analysed per log; the rest of a longer log is skipped
     * @param maxPausePoints pauses returned for the scatter plot (the longest are always kept)
     * @param rateWindows    windows of the allocation / promotion rate chart
     * @param ttspWarning    time to safepoint from which a safepoint is reported as slow to reach
     * @param maxAnalyses    analyses kept in memory; the oldest finished one is dropped
     * @param maxLiveDuration longest live recording accepted
     * @param workDir        where live recordings are stored while they are parsed
     */
    public record GcLog(
            @DefaultValue("500000") int maxEvents,
            @DefaultValue("20000") int maxPausePoints,
            @DefaultValue("200") int rateWindows,
            @DefaultValue("50ms") Duration ttspWarning,
            @DefaultValue("10") int maxAnalyses,
            @DefaultValue("30m") Duration maxLiveDuration,
            Path workDir
    ) {
        public Path resolvedWorkDir() {
            return workDir != null ? workDir
                    : Path.of(System.getProperty("java.io.tmpdir"), "jvm-leak-inspector", "gc");
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
