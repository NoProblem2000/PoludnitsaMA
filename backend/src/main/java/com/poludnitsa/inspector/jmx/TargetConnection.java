package com.poludnitsa.inspector.jmx;

import java.io.IOException;
import java.time.Instant;
import javax.management.JMException;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.remote.JMXConnectionNotification;
import javax.management.remote.JMXConnector;

/** One open JMX connection to a target. Marked broken on connector failure; the manager then reconnects. */
public final class TargetConnection implements AutoCloseable {

    private final TargetEndpoint endpoint;
    private final JMXConnector connector;
    private final MBeanServerConnection mbeanServer;
    private final Instant connectedAt = Instant.now();
    private final NotificationListener connectionListener = this::onConnectionNotification;
    private volatile TargetMetadata metadata;
    private volatile boolean broken;

    TargetConnection(TargetEndpoint endpoint, JMXConnector connector, MBeanServerConnection mbeanServer,
                     TargetMetadata metadata) {
        this.endpoint = endpoint;
        this.connector = connector;
        this.mbeanServer = mbeanServer;
        this.metadata = metadata;
        connector.addConnectionNotificationListener(connectionListener, null, null);
    }

    public TargetEndpoint endpoint() {
        return endpoint;
    }

    public MBeanServerConnection mbeanServer() {
        return mbeanServer;
    }

    public TargetMetadata metadata() {
        return metadata;
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    public boolean isBroken() {
        return broken;
    }

    void markBroken() {
        broken = true;
    }

    void refreshMetadata() throws IOException, JMException {
        metadata = TargetMetadata.load(mbeanServer);
    }

    private void onConnectionNotification(Notification notification, Object handback) {
        String type = notification.getType();
        if (JMXConnectionNotification.FAILED.equals(type) || JMXConnectionNotification.CLOSED.equals(type)) {
            broken = true;
        }
    }

    @Override
    public void close() {
        broken = true;
        try {
            connector.removeConnectionNotificationListener(connectionListener);
        } catch (ListenerNotFoundException ignored) {
            // already removed
        }
        try {
            connector.close();
        } catch (IOException ignored) {
            // the connection is being discarded anyway
        }
    }
}
