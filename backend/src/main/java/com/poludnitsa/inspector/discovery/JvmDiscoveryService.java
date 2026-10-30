package com.poludnitsa.inspector.discovery;

import com.poludnitsa.inspector.api.dto.ConnectionSource;
import com.poludnitsa.inspector.api.dto.ConnectionState;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.ProcessType;
import com.poludnitsa.inspector.api.dto.RemoteJmxConnectRequestDto;
import com.poludnitsa.inspector.common.DaemonThreadFactory;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.common.TargetNotFoundException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.jmx.AttachErrorHints;
import com.poludnitsa.inspector.jmx.JmxAttributes;
import com.poludnitsa.inspector.jmx.JmxConnectionManager;
import com.poludnitsa.inspector.jmx.MBeanNames;
import com.poludnitsa.inspector.jmx.TargetEndpoint;
import com.poludnitsa.inspector.jmx.TargetMetadata;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.Manifest;
import javax.management.MBeanServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Finds Java processes on this host ({@code VirtualMachine.list()}) and registered remote JMX endpoints, classifies
 * them and exposes their JMX connections. Process facts are read through the attach channel without starting any
 * agent; the JMX agent is started only when a process is explicitly connected or monitored.
 */
@Service
public class JvmDiscoveryService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JvmDiscoveryService.class);

    private static final Comparator<JvmProcessDto> ORDER = Comparator
            .comparing(JvmProcessDto::processType)
            .thenComparing(JvmProcessDto::displayName, String.CASE_INSENSITIVE_ORDER)
            .thenComparingLong(JvmProcessDto::pid);

    private final JmxConnectionManager connections;
    private final ProcessClassifier classifier;
    private final InspectorProperties.Jmx props;
    private final ConcurrentHashMap<Long, ProbeResult> probes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<ProbeResult>> probesInFlight = new ConcurrentHashMap<>();
    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(4, new DaemonThreadFactory("jvm-probe"));

    /** Facts about one process; for local processes valid while the process with that start instant lives. */
    record ProbeResult(
            long pid,
            Instant startInstant,
            ProcessType type,
            String displayName,
            List<String> jvmFlags,
            String jvmVersion,
            boolean attachable,
            String attachError,
            long probedAtEpochMs
    ) {
    }

    private record LiveFacts(MemoryUsage heap, TargetMetadata metadata) {
    }

    public JvmDiscoveryService(JmxConnectionManager connections, ProcessClassifier classifier,
                               InspectorProperties properties) {
        this.connections = connections;
        this.classifier = classifier;
        this.props = properties.jmx();
    }

    /** All local JVMs (except the inspector) and all registered remote targets. */
    public List<JvmProcessDto> listProcesses() {
        Set<Long> remotePids = new HashSet<>(connections.remotePids());
        List<JvmProcessDto> result = new ArrayList<>();
        if (JmxConnectionManager.attachSupported()) {
            Map<LocalJvmProbe.LocalJvm, CompletableFuture<ProbeResult>> pending = new LinkedHashMap<>();
            for (LocalJvmProbe.LocalJvm jvm : LocalJvmProbe.list()) {
                if (jvm.pid() != connections.selfPid() && !remotePids.contains(jvm.pid())) {
                    pending.put(jvm, probeAsync(jvm));
                }
            }
            long deadline = System.nanoTime() + props.connectTimeout().toNanos();
            pending.forEach((jvm, future) -> result.add(toDto(await(jvm, future, deadline))));
        } else {
            log.warn("jdk.attach is not available; only remote JMX targets are listed");
        }
        for (long pid : remotePids) {
            result.add(describe(pid));
        }
        result.sort(ORDER);
        return result;
    }

    public JvmProcessDto describe(long pid) {
        if (pid == connections.selfPid()) {
            throw new OperationRejectedException(400, "SELF_MONITORING_FORBIDDEN",
                    "PID " + pid + " is the inspector itself");
        }
        if (connections.remoteEndpoint(pid).isPresent()) {
            ProbeResult cached = probes.get(pid);
            return toDto(cached != null ? cached : probeRemote(pid));
        }
        if (!connections.isKnown(pid)) {
            throw new TargetNotFoundException(pid);
        }
        LocalJvmProbe.LocalJvm jvm = new LocalJvmProbe.LocalJvm(pid, "");
        return toDto(await(jvm, probeAsync(jvm), System.nanoTime() + props.connectTimeout().toNanos()));
    }

    /** Opens (or re-opens) the JMX connection right away, ignoring reconnect backoff. */
    public JvmProcessDto connect(long pid) {
        connections.connection(pid, true);
        ProbeResult probe = probes.get(pid);
        if (probe != null && !probe.attachable()) {
            probes.remove(pid, probe);
        }
        return describe(pid);
    }

    public JvmProcessDto connectRemote(RemoteJmxConnectRequestDto request) {
        TargetEndpoint.RemoteJmx endpoint = TargetEndpoint.RemoteJmx.of(
                request.hostPort(), request.serviceUrl(), request.username(), request.password());
        long pid = connections.registerRemote(endpoint);
        return toDto(probeRemote(pid));
    }

    public void disconnectRemote(long pid) {
        if (connections.remoteEndpoint(pid).isEmpty()) {
            throw new TargetNotFoundException(pid);
        }
        connections.unregister(pid);
        probes.remove(pid);
    }

    /** The target's MBean server; connects if needed. */
    public MBeanServerConnection mbeanServer(long pid) {
        return connections.connection(pid).mbeanServer();
    }

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void pruneExitedProcesses() {
        connections.pruneTerminated();
        probes.values().removeIf(probe -> probe.startInstant() != null
                ? !new TargetEndpoint.LocalProcess(probe.pid(), probe.startInstant()).isSameProcessAlive()
                : connections.remoteEndpoint(probe.pid()).isEmpty() && !connections.isKnown(probe.pid()));
    }

    @Override
    public void destroy() {
        probeExecutor.shutdownNow();
    }

    private CompletableFuture<ProbeResult> probeAsync(LocalJvmProbe.LocalJvm jvm) {
        long pid = jvm.pid();
        Instant startInstant = ProcessHandle.of(pid).flatMap(h -> h.info().startInstant()).orElse(null);
        ProbeResult cached = probes.get(pid);
        if (cached != null && isFresh(cached, startInstant)) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<ProbeResult> created = new CompletableFuture<>();
        CompletableFuture<ProbeResult> existing = probesInFlight.putIfAbsent(pid, created);
        if (existing != null) {
            return existing;
        }
        probeExecutor.execute(() -> {
            try {
                ProbeResult result = probeLocal(jvm, startInstant);
                probes.put(pid, result);
                created.complete(result);
            } catch (Throwable t) {
                created.completeExceptionally(t);
            } finally {
                probesInFlight.remove(pid, created);
            }
        });
        return created;
    }

    private boolean isFresh(ProbeResult probe, Instant startInstant) {
        if (!Objects.equals(probe.startInstant(), startInstant)) {
            return false;
        }
        long age = System.currentTimeMillis() - probe.probedAtEpochMs();
        return age < (probe.attachable() ? props.probeCacheTtl() : props.failedProbeRetry()).toMillis();
    }

    private ProbeResult await(LocalJvmProbe.LocalJvm jvm, CompletableFuture<ProbeResult> future, long deadlineNanos) {
        try {
            long remaining = Math.max(0, deadlineNanos - System.nanoTime());
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return provisional(jvm, "Attach did not answer within " + props.connectTimeout().toSeconds()
                    + " s; probing continues in the background");
        } catch (ExecutionException e) {
            return provisional(jvm, AttachErrorHints.describe(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return provisional(jvm, "Interrupted while probing");
        }
    }

    private ProbeResult provisional(LocalJvmProbe.LocalJvm jvm, String error) {
        ProcessClassifier.Classification classification =
                classifier.classify(new ProcessFacts(null, jvm.command(), List.of(), null));
        return new ProbeResult(jvm.pid(), null, classification.type(), classification.displayName(), List.of(),
                null, false, error, System.currentTimeMillis());
    }

    private ProbeResult probeLocal(LocalJvmProbe.LocalJvm jvm, Instant startInstant) {
        String command = jvm.command();
        try {
            LocalJvmProbe.AttachedProperties attached = LocalJvmProbe.read(jvm.pid());
            Properties system = attached.systemProperties();
            if (command == null || command.isBlank()) {
                command = attached.agentProperties().getProperty("sun.java.command", "");
            }
            List<String> jvmArgs = splitArguments(attached.agentProperties().getProperty("sun.jvm.args", ""));
            ProcessFacts facts = withManifest(new ProcessFacts(system, command, jvmArgs, null),
                    system.getProperty("user.dir"));
            ProcessClassifier.Classification classification = classifier.classify(facts);
            return new ProbeResult(jvm.pid(), startInstant, classification.type(), classification.displayName(),
                    jvmArgs, system.getProperty("java.vm.version"), true, null, System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("Attach probe of PID {} failed", jvm.pid(), e);
            ProcessFacts facts = withManifest(new ProcessFacts(null, command, List.of(), null), null);
            ProcessClassifier.Classification classification = classifier.classify(facts);
            return new ProbeResult(jvm.pid(), startInstant, classification.type(), classification.displayName(),
                    List.of(), null, false, AttachErrorHints.describe(e), System.currentTimeMillis());
        }
    }

    private ProbeResult probeRemote(long pid) {
        ProbeResult result = connections.call(pid, (connection, metadata) -> {
            Properties system = JmxAttributes.systemProperties(connection);
            ProcessFacts facts = new ProcessFacts(system, system.getProperty("sun.java.command", ""),
                    metadata.inputArguments(), null);
            ProcessClassifier.Classification classification = classifier.classify(facts);
            return new ProbeResult(pid, null, classification.type(), classification.displayName(),
                    metadata.inputArguments(), metadata.vmVersion(), true, null, System.currentTimeMillis());
        });
        probes.put(pid, result);
        return result;
    }

    private static ProcessFacts withManifest(ProcessFacts facts, String workingDir) {
        if (!facts.launchedFromJar()) {
            return facts;
        }
        Manifest manifest = ManifestReader.read(facts.launchTarget(), workingDir);
        return manifest == null ? facts
                : new ProcessFacts(facts.systemProperties(), facts.command(), facts.jvmArguments(), manifest);
    }

    private static List<String> splitArguments(String arguments) {
        String trimmed = arguments.trim();
        return trimmed.isEmpty() ? List.of() : Arrays.asList(trimmed.split("\\s+"));
    }

    private JvmProcessDto toDto(ProbeResult probe) {
        long pid = probe.pid();
        Optional<TargetEndpoint.RemoteJmx> remote = connections.remoteEndpoint(pid);
        ConnectionState state = connections.state(pid);
        Long heapUsed = null;
        Long heapMax = null;
        List<String> flags = probe.jvmFlags();
        String version = probe.jvmVersion();
        if (connections.peek(pid).isPresent()) {
            try {
                LiveFacts live = connections.call(pid, (connection, metadata) -> new LiveFacts(
                        JmxAttributes.memoryUsage(connection.getAttribute(MBeanNames.MEMORY, "HeapMemoryUsage")),
                        metadata));
                if (live.heap() != null) {
                    heapUsed = live.heap().getUsed();
                    heapMax = live.heap().getMax() >= 0 ? live.heap().getMax() : null;
                }
                flags = live.metadata().inputArguments();
                version = version != null ? version : live.metadata().vmVersion();
            } catch (InspectorException e) {
                log.debug("Heap read for PID {} failed: {}", pid, e.getMessage());
            }
            state = connections.state(pid);
        }
        String error = probe.attachError();
        if (error == null && (state == ConnectionState.FAILED || state == ConnectionState.RECONNECTING)) {
            error = connections.lastError(pid).orElse(null);
        }
        return new JvmProcessDto(pid, probe.displayName(), probe.type(), heapUsed, heapMax, flags, version,
                probe.attachable(), error,
                remote.isPresent() ? ConnectionSource.REMOTE_JMX : ConnectionSource.LOCAL_ATTACH,
                state, remote.map(TargetEndpoint.RemoteJmx::serviceUrl).orElse(null));
    }
}
