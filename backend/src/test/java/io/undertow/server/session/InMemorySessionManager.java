package io.undertow.server.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Test double with the fields of Undertow {@code InMemorySessionManager} / {@code SessionImpl} that are read. */
public class InMemorySessionManager {

    private final ConcurrentMap<String, SessionImpl> sessions = new ConcurrentHashMap<>();
    private final String deploymentName;
    private volatile int defaultSessionTimeout = 30 * 60;

    public InMemorySessionManager(String deploymentName) {
        this.deploymentName = deploymentName;
    }

    public SessionImpl createSession(String id) {
        SessionImpl session = new SessionImpl(defaultSessionTimeout);
        sessions.put(id, session);
        return session;
    }

    public String deploymentName() {
        return deploymentName;
    }

    public static final class SessionImpl {
        final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
        volatile int maxInactiveInterval;
        final long creationTime = System.currentTimeMillis();

        SessionImpl(int timeout) {
            this.maxInactiveInterval = timeout;
        }

        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }
    }
}
