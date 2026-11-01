package com.poludnitsa.inspector.api.dto;

/** Where diagnostic data came from: the live target over JMX / JFR, or an uploaded file. */
public enum DiagnosticSource {
    LIVE,
    FILE
}
