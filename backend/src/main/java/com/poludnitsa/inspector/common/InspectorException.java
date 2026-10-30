package com.poludnitsa.inspector.common;

/** Base of all expected failures; carries the HTTP status and a machine-readable code for {@code ApiErrorDto}. */
public class InspectorException extends RuntimeException {

    private final int status;
    private final String code;

    public InspectorException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public InspectorException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
