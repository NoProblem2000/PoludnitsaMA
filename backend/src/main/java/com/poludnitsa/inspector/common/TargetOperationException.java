package com.poludnitsa.inspector.common;

/** The connection works but an MBean operation or attribute read failed on the target. */
public class TargetOperationException extends InspectorException {

    public TargetOperationException(long pid, String message, Throwable cause) {
        super(502, "TARGET_OPERATION_FAILED", "PID " + pid + ": " + message, cause);
    }
}
