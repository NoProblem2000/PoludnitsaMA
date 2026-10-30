package com.poludnitsa.inspector.common;

public class TargetNotFoundException extends InspectorException {

    public TargetNotFoundException(long pid) {
        super(404, "TARGET_NOT_FOUND", "No Java process with PID " + pid + " is known on this host");
    }
}
