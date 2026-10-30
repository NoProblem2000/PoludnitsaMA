package com.poludnitsa.inspector.common;

public class TargetTerminatedException extends InspectorException {

    public TargetTerminatedException(long pid) {
        super(410, "TARGET_TERMINATED", "Process " + pid + " has exited");
    }
}
