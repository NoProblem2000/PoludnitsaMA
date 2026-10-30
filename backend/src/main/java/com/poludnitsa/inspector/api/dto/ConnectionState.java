package com.poludnitsa.inspector.api.dto;

/** State of the JMX connection to a target JVM. */
public enum ConnectionState {
    /** Not connected yet; listing a process never connects implicitly. */
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    /** Connection was lost; reconnect attempts run with exponential backoff. */
    RECONNECTING,
    /** First connection attempt failed; see the attach error for the reason. */
    FAILED,
    /** The target process has exited. */
    TERMINATED
}
