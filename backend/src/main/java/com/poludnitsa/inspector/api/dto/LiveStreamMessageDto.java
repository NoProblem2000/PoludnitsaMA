package com.poludnitsa.inspector.api.dto;

/**
 * Envelope of every server-to-client message on {@code /ws/live}. Exactly one of {@code metrics}, {@code status} and
 * {@code error} is set, depending on {@code type}.
 *
 * <p>Client-to-server messages are {@code {"action":"subscribe","pid":4711}}, {@code {"action":"unsubscribe"}} and
 * {@code {"action":"ping"}}.
 */
public record LiveStreamMessageDto(
        LiveStreamMessageType type,
        Long pid,
        LiveMetricsPayloadDto metrics,
        TargetStatusDto status,
        String error
) {
    public static LiveStreamMessageDto subscribed(long pid) {
        return new LiveStreamMessageDto(LiveStreamMessageType.SUBSCRIBED, pid, null, null, null);
    }

    public static LiveStreamMessageDto unsubscribed(Long pid) {
        return new LiveStreamMessageDto(LiveStreamMessageType.UNSUBSCRIBED, pid, null, null, null);
    }

    public static LiveStreamMessageDto metrics(LiveMetricsPayloadDto metrics) {
        return new LiveStreamMessageDto(LiveStreamMessageType.METRICS, metrics.pid(), metrics, null, null);
    }

    public static LiveStreamMessageDto status(TargetStatusDto status) {
        return new LiveStreamMessageDto(LiveStreamMessageType.STATUS, status.pid(), null, status, null);
    }

    public static LiveStreamMessageDto error(Long pid, String error) {
        return new LiveStreamMessageDto(LiveStreamMessageType.ERROR, pid, null, null, error);
    }

    public static LiveStreamMessageDto pong() {
        return new LiveStreamMessageDto(LiveStreamMessageType.PONG, null, null, null, null);
    }
}
