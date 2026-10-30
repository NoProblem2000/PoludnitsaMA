package com.poludnitsa.inspector.jmx;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** How a target JVM is reached. */
public sealed interface TargetEndpoint permits TargetEndpoint.LocalProcess, TargetEndpoint.RemoteJmx {

    /**
     * A process on this host, reached through the Attach API. The start instant guards against PID reuse:
     * a new process with the same PID is a different target.
     */
    record LocalProcess(long pid, Instant startInstant) implements TargetEndpoint {

        public static Optional<LocalProcess> of(long pid) {
            return ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .map(handle -> new LocalProcess(pid, handle.info().startInstant().orElse(null)));
        }

        public boolean isSameProcessAlive() {
            return ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .map(handle -> Objects.equals(handle.info().startInstant().orElse(null), startInstant))
                    .orElse(false);
        }
    }

    /** A JVM with an open JMX connector. The password is kept in memory only. */
    record RemoteJmx(String serviceUrl, String username, String password) implements TargetEndpoint {

        public static RemoteJmx of(String hostPort, String serviceUrl, String username, String password) {
            String url = hostPort != null && !hostPort.isBlank()
                    ? "service:jmx:rmi:///jndi/rmi://" + hostPort.trim() + "/jmxrmi"
                    : serviceUrl.trim();
            String user = username == null || username.isBlank() ? null : username;
            return new RemoteJmx(url, user, user == null ? null : password);
        }

        @Override
        public String toString() {
            return "RemoteJmx[" + serviceUrl + (username != null ? ", user=" + username : "") + "]";
        }
    }
}
