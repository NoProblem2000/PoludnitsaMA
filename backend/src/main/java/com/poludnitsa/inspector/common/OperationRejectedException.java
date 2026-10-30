package com.poludnitsa.inspector.common;

/** The inspector refuses a request on purpose (missing confirmation, rate limit, unsupported feature, conflict). */
public class OperationRejectedException extends InspectorException {

    public OperationRejectedException(int status, String code, String message) {
        super(status, code, message);
    }
}
