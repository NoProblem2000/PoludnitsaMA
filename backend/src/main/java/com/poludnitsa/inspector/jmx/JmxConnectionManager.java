package com.poludnitsa.inspector.jmx;

import com.poludnitsa.inspector.api.dto.ConnectionState;
import com.poludnitsa.inspector.common.DaemonThreadFactory;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.common.TargetNotFoundException;
import com.poludnitsa.inspector.common.TargetOperationException;
import com.poludnitsa.inspector.common.TargetTerminatedException;
import com.poludnitsa.inspector.common.TargetUnavailableException;
import com.poludnitsa.inspector.config.InspectorProperties;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.JMRuntimeException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Owns every JMX connection to target JVMs: attach + {@code startLocalManagementAgent()} for local PIDs, RMI connectors
 * for remote endpoints, a cached connection per PID, failure detection and reconnects with exponential backoff.
 *
 * <p>The inspector's own JVM is never accepted as a target.
 */
@Component
public class JmxConnectionManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JmxConnectionManager.class);

    private final InspectorProperties.Jmx props;
    private final long selfPid = ProcessHandle.current().pid();
    private final long selfStartTime = ManagementFactory.getRuntimeMXBean().getStartTime();
    private final ConcurrentHashMap<Long, ManagedTarget> targets = new ConcurrentHashMap<>();
    private final ExecutorService connectExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory("jmx-connect"));
    private final ExecutorService callExecutor = Executors.newCachedThreadPool(new DaemonThreadFactory("jmx-call"));

    public JmxConnectionManager(InspectorProperties properties) {
        this.props = properties.jmx();
    }

    public static boolean attachSupported() {
        return ModuleLayer.boot().findModule("jdk.attach").isPresent();
    }

    public long selfPid() {
        return selfPid;
    }

    /** A live local process (other than the inspector) or a registered remote target. */
    public boolean isKnown(long pid) {
        ManagedTarget target = targets.get(pid);
        if (target != null) {
            return target.state != ConnectionState.TERMINATED;
        }
        return pid != selfPid && TargetEndpoint.LocalProcess.of(pid).isPresent();
    }

    public List<Long> remotePids() {
        return targets.values().stream()
                .filter(t -> t.endpoint instanceof TargetEndpoint.RemoteJmx)
                .map(t -> t.pid)
                .sorted()
                .toList();
    }

    public Optional<TargetEndpoint.RemoteJmx> remoteEndpoint(long pid) {
        ManagedTarget target = targets.get(pid);
        return target != null && target.endpoint instanceof TargetEndpoint.RemoteJmx remote
                ? Optional.of(remote)
                : Optional.empty();
    }

    public ConnectionState state(long pid) {
        ManagedTarget target = targets.get(pid);
        if (target != null) {
            return target.state;
        }
        return isKnown(pid) ? ConnectionState.DISCONNECTED : ConnectionState.TERMINATED;
    }

    public Optional<String> lastError(long pid) {
        ManagedTarget target = targets.get(pid);
        return target == null ? Optional.empty() : Optional.ofNullable(target.lastError);
    }

    /** The current healthy connection, without connecting. */
    public Optional<TargetConnection> peek(long pid) {
        ManagedTarget target = targets.get(pid);
        TargetConnection connection = target == null ? null : target.connection;
        return connection != null && !connection.isBroken() ? Optional.of(connection) : Optional.empty();
    }

    /** Returns a healthy connection, connecting or reconnecting if needed (subject to backoff). */
    public TargetConnection connection(long pid) {
        return connection(pid, false);
    }

    /**
     * @param ignoreBackoff {@code true} for explicit user actions ("Connect" button): retry right away
     */
    public TargetConnection connection(long pid, boolean ignoreBackoff) {
        ManagedTarget target = target(pid);
        TargetConnection current = target.connection;
        if (current != null && !current.isBroken()) {
            return current;
        }
        target.lock.lock();
        try {
            current = target.connection;
            if (current != null && !current.isBroken()) {
                return current;
            }
            if (current != null) {
                current.close();
                target.connection = null;
            }
            if (target.endpoint instanceof TargetEndpoint.LocalProcess local && !local.isSameProcessAlive()) {
                terminate(target);
                throw new TargetTerminatedException(pid);
            }
            long now = System.currentTimeMillis();
            if (!ignoreBackoff && now < target.nextAttemptAt) {
                throw new TargetUnavailableException(pid, "next connection attempt in "
                        + (target.nextAttemptAt - now) + " ms; last error: " + target.lastError, null);
            }
            target.state = target.everConnected ? ConnectionState.RECONNECTING : ConnectionState.CONNECTING;
            try {
                TargetConnection opened = open(target.endpoint, pid);
                target.connection = opened;
                target.state = ConnectionState.CONNECTED;
                target.everConnected = true;
                target.lastError = null;
                target.backoffMs = 0;
                target.nextAttemptAt = 0;
                log.info("Connected to PID {} via {}", pid, target.endpoint);
                return opened;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                target.state = target.everConnected ? ConnectionState.RECONNECTING : ConnectionState.FAILED;
                throw new TargetUnavailableException(pid, "interrupted while connecting", e);
            } catch (Exception e) {
                target.lastError = AttachErrorHints.describe(e);
                target.backoffMs = target.backoffMs == 0
                        ? props.reconnectInitialBackoff().toMillis()
                        : Math.min(props.reconnectMaxBackoff().toMillis(), target.backoffMs * 2);
                target.nextAttemptAt = System.currentTimeMillis() + target.backoffMs;
                target.state = target.everConnected ? ConnectionState.RECONNECTING : ConnectionState.FAILED;
                log.warn("Connection to PID {} failed (retry in {} ms): {}", pid, target.backoffMs, target.lastError);
                throw new TargetUnavailableException(pid, target.lastError, e);
            }
        } finally {
            target.lock.unlock();
        }
    }

    /** {@link #call(long, Duration, JmxCall)} with the default {@code inspector.jmx.call-timeout}. */
    public <T> T call(long pid, JmxCall<T> call) {
        return call(pid, props.callTimeout(), call);
    }

    /**
     * Runs {@code call} on a healthy connection within {@code timeout}. Communication failures and timeouts mark the
     * connection broken (the next call reconnects) and surface as {@link TargetUnavailableException} or, if the
     * process exited, {@link TargetTerminatedException}.
     *
     * <p>Timeouts are per call rather than a global RMI response timeout, so long operations such as a heap dump can
     * get minutes while a stuck target still cannot block a sampler thread.
     */
    public <T> T call(long pid, Duration timeout, JmxCall<T> call) {
        TargetConnection connection = connection(pid);
        Future<T> future = callExecutor.submit(() -> call.call(connection.mbeanServer(), connection.metadata()));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            connection.markBroken();
            throw new TargetUnavailableException(pid, "JMX call did not complete within " + timeout.toMillis()
                    + " ms (target stalled or overloaded)", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new TargetUnavailableException(pid, "interrupted while waiting for the target", e);
        } catch (ExecutionException e) {
            throw translate(pid, connection, e.getCause());
        }
    }

    private RuntimeException translate(long pid, TargetConnection connection, Throwable failure) {
        if (failure instanceof IOException io) {
            return connectionLost(pid, connection, io);
        }
        if (failure instanceof InstanceNotFoundException e) {
            refreshMetadataQuietly(pid, connection);
            return new TargetOperationException(pid, "MBean not found: " + e.getMessage(), e);
        }
        if (failure instanceof MBeanException e) {
            Throwable cause = e.getTargetException() != null ? e.getTargetException() : e;
            return new TargetOperationException(pid, cause.getClass().getSimpleName() + ": " + cause.getMessage(), e);
        }
        if (failure instanceof JMException || failure instanceof JMRuntimeException) {
            return new TargetOperationException(pid,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage(), failure);
        }
        if (failure instanceof UndeclaredThrowableException e) {
            // MXBean proxies wrap checked communication failures.
            Throwable cause = e.getUndeclaredThrowable();
            if (cause instanceof IOException io) {
                return connectionLost(pid, connection, io);
            }
            return new TargetOperationException(pid, String.valueOf(cause), cause);
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new TargetOperationException(pid, String.valueOf(failure), failure);
    }

    /** Connects to a remote JMX endpoint and registers it under the PID the target reports. */
    public long registerRemote(TargetEndpoint.RemoteJmx endpoint) {
        TargetConnection connection;
        try {
            connection = open(endpoint, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InspectorException(503, "REMOTE_CONNECT_FAILED", "Interrupted while connecting", e);
        } catch (Exception e) {
            throw new InspectorException(502, "REMOTE_CONNECT_FAILED",
                    endpoint.serviceUrl() + ": " + AttachErrorHints.describe(e), e);
        }
        TargetMetadata metadata = connection.metadata();
        long pid = metadata.pid();
        if (pid == selfPid && metadata.startTimeEpochMs() == selfStartTime) {
            connection.close();
            throw selfMonitoringRejected(pid);
        }
        ManagedTarget target = new ManagedTarget(pid, endpoint);
        target.connection = connection;
        target.state = ConnectionState.CONNECTED;
        target.everConnected = true;
        ManagedTarget previous = targets.putIfAbsent(pid, target);
        if (previous != null) {
            connection.close();
            throw new OperationRejectedException(409, "PID_CONFLICT",
                    "A target with PID " + pid + " is already registered via " + previous.endpoint);
        }
        log.info("Registered remote target PID {} via {}", pid, endpoint);
        return pid;
    }

    public void unregister(long pid) {
        ManagedTarget target = targets.remove(pid);
        if (target != null) {
            target.closeConnection();
            log.info("Unregistered PID {}", pid);
        }
    }

    /** Drops local targets whose process has exited. */
    public void pruneTerminated() {
        for (ManagedTarget target : targets.values()) {
            if (target.endpoint instanceof TargetEndpoint.LocalProcess local && !local.isSameProcessAlive()) {
                terminate(target);
            }
        }
    }

    @Override
    public void destroy() {
        targets.values().forEach(ManagedTarget::closeConnection);
        targets.clear();
        connectExecutor.shutdownNow();
        callExecutor.shutdownNow();
    }

    private ManagedTarget target(long pid) {
        ManagedTarget existing = targets.get(pid);
        if (existing != null) {
            if (existing.state == ConnectionState.TERMINATED) {
                throw new TargetTerminatedException(pid);
            }
            return existing;
        }
        if (pid == selfPid) {
            throw selfMonitoringRejected(pid);
        }
        if (!attachSupported()) {
            throw new OperationRejectedException(501, "ATTACH_UNAVAILABLE",
                    "The inspector runs without the jdk.attach module; start it with a full JDK");
        }
        TargetEndpoint.LocalProcess local = TargetEndpoint.LocalProcess.of(pid)
                .orElseThrow(() -> new TargetNotFoundException(pid));
        return targets.computeIfAbsent(pid, p -> new ManagedTarget(p, local));
    }

    private static OperationRejectedException selfMonitoringRejected(long pid) {
        return new OperationRejectedException(400, "SELF_MONITORING_FORBIDDEN",
                "PID " + pid + " is the inspector itself; it never monitors its own JVM");
    }

    private RuntimeException connectionLost(long pid, TargetConnection connection, IOException cause) {
        connection.markBroken();
        ManagedTarget target = targets.get(pid);
        if (target != null && target.endpoint instanceof TargetEndpoint.LocalProcess local
                && !local.isSameProcessAlive()) {
            terminate(target);
            return new TargetTerminatedException(pid);
        }
        if (target != null) {
            target.state = ConnectionState.RECONNECTING;
            target.lastError = AttachErrorHints.describe(cause);
        }
        return new TargetUnavailableException(pid, "connection lost: " + cause.getMessage(), cause);
    }

    private void refreshMetadataQuietly(long pid, TargetConnection connection) {
        try {
            connection.refreshMetadata();
        } catch (IOException | JMException e) {
            log.debug("Metadata refresh for PID {} failed", pid, e);
        }
    }

    private void terminate(ManagedTarget target) {
        target.state = ConnectionState.TERMINATED;
        target.closeConnection();
        targets.remove(target.pid, target);
        log.info("PID {} has exited", target.pid);
    }

    private TargetConnection open(TargetEndpoint endpoint, Long expectedPid) throws Exception {
        Future<TargetConnection> future = connectExecutor.submit(() -> doOpen(endpoint));
        TargetConnection connection;
        try {
            connection = future.get(props.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("attach/connect timed out after " + props.connectTimeout().toSeconds() + " s", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw new IOException(e.getCause());
        }
        if (expectedPid != null && connection.metadata().pid() != expectedPid) {
            connection.close();
            throw new IOException("connected JVM reports PID " + connection.metadata().pid()
                    + ", expected " + expectedPid);
        }
        return connection;
    }

    private TargetConnection doOpen(TargetEndpoint endpoint) throws Exception {
        Map<String, Object> environment = new HashMap<>();
        environment.put("jmx.remote.x.client.connection.check.period", props.connectionCheckPeriod().toMillis());
        JMXServiceURL url;
        if (endpoint instanceof TargetEndpoint.LocalProcess local) {
            url = new JMXServiceURL(LocalAttach.localConnectorAddress(local.pid()));
        } else {
            TargetEndpoint.RemoteJmx remote = (TargetEndpoint.RemoteJmx) endpoint;
            url = new JMXServiceURL(remote.serviceUrl());
            if (remote.username() != null) {
                environment.put(JMXConnector.CREDENTIALS, new String[] {remote.username(), remote.password()});
            }
        }
        JMXConnector connector = JMXConnectorFactory.connect(url, environment);
        try {
            MBeanServerConnection mbeanServer = connector.getMBeanServerConnection();
            TargetConnection connection = new TargetConnection(endpoint, connector, mbeanServer,
                    TargetMetadata.load(mbeanServer));
            if (Thread.currentThread().isInterrupted()) {
                // open() gave up on us; do not leak the connector.
                connection.close();
                throw new InterruptedException("connect cancelled");
            }
            return connection;
        } catch (Exception e) {
            try {
                connector.close();
            } catch (IOException ignored) {
                // reporting the original failure
            }
            throw e;
        }
    }

    private static final class ManagedTarget {
        final long pid;
        final TargetEndpoint endpoint;
        final ReentrantLock lock = new ReentrantLock();
        volatile TargetConnection connection;
        volatile ConnectionState state = ConnectionState.DISCONNECTED;
        volatile String lastError;
        volatile boolean everConnected;
        volatile long backoffMs;
        volatile long nextAttemptAt;

        ManagedTarget(long pid, TargetEndpoint endpoint) {
            this.pid = pid;
            this.endpoint = endpoint;
        }

        void closeConnection() {
            TargetConnection current = connection;
            connection = null;
            if (current != null) {
                current.close();
            }
        }
    }
}
