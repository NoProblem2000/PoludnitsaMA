package com.poludnitsa.inspector.api.dto;

/** Type of a server-to-client message on {@code /ws/live}. */
public enum LiveStreamMessageType {
    SUBSCRIBED,
    UNSUBSCRIBED,
    METRICS,
    STATUS,
    ERROR,
    PONG
}
