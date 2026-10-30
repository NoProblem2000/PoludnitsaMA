package com.poludnitsa.inspector.common;

/** The target exists but no JMX connection is available right now (connect failed, lost, or backing off). */
public class TargetUnavailableException extends InspectorException {

    public TargetUnavailableException(long pid, String message, Throwable cause) {
        super(503, "TARGET_UNAVAILABLE", "PID " + pid + ": " + message, cause);
    }
}
