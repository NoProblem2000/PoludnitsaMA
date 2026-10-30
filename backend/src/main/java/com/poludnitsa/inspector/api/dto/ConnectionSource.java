package com.poludnitsa.inspector.api.dto;

/** How the inspector reaches a target JVM. */
public enum ConnectionSource {
    /** Attach API by PID, then the target's local JMX agent ({@code startLocalManagementAgent()}). */
    LOCAL_ATTACH,
    /** JMX RMI connector given as {@code host:port} or a full {@code service:jmx:} URL. */
    REMOTE_JMX
}
