package com.poludnitsa.inspector.api.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poludnitsa.inspector.api.dto.LiveMetricsPayloadDto;
import com.poludnitsa.inspector.api.dto.LiveStreamMessageDto;
import com.poludnitsa.inspector.api.dto.TargetStatusDto;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.live.LiveMemoryCollectorService;
import com.poludnitsa.inspector.live.LiveMetricsListener;
import com.poludnitsa.inspector.live.Subscription;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * {@code /ws/live}: streams {@link LiveMetricsPayloadDto} of one PID per connection.
 *
 * <p>Protocol (JSON text frames):
 * <ul>
 *   <li>client: {@code {"action":"subscribe","pid":4711}} (or {@code /ws/live?pid=4711}),
 *       {@code {"action":"unsubscribe"}}, {@code {"action":"ping"}}</li>
 *   <li>server: {@link LiveStreamMessageDto} with type SUBSCRIBED, METRICS, STATUS, UNSUBSCRIBED, ERROR or PONG</li>
 * </ul>
 * Sends go through {@link ConcurrentWebSocketSessionDecorator}: sampler threads never block on a slow browser, and
 * when the buffer is full the oldest pending samples are dropped.
 */
@Component
public class LiveMetricsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveMetricsWebSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 1 << 20;

    private final LiveMemoryCollectorService collector;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

    /** Client-to-server message. */
    record ClientMessage(String action, Long pid) {
    }

    public LiveMetricsWebSocketHandler(LiveMemoryCollectorService collector, ObjectMapper mapper) {
        this.collector = collector;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        ClientSession client = new ClientSession(new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS,
                BUFFER_SIZE_LIMIT, ConcurrentWebSocketSessionDecorator.OverflowStrategy.DROP));
        sessions.put(session.getId(), client);
        Long pid = pidFromQuery(session.getUri());
        if (pid != null) {
            client.subscribe(pid);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientSession client = sessions.get(session.getId());
        if (client == null) {
            return;
        }
        ClientMessage request;
        try {
            request = mapper.readValue(message.getPayload(), ClientMessage.class);
        } catch (JsonProcessingException e) {
            client.send(LiveStreamMessageDto.error(null, "Malformed message: " + e.getOriginalMessage()));
            return;
        }
        String action = request.action() == null ? "" : request.action().toLowerCase(Locale.ROOT);
        switch (action) {
            case "subscribe" -> {
                if (request.pid() == null || request.pid() <= 0) {
                    client.send(LiveStreamMessageDto.error(null, "subscribe requires a positive pid"));
                } else {
                    client.subscribe(request.pid());
                }
            }
            case "unsubscribe" -> client.unsubscribe(true);
            case "ping" -> client.send(LiveStreamMessageDto.pong());
            default -> client.send(LiveStreamMessageDto.error(null, "Unknown action: " + request.action()));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket {} transport error: {}", session.getId(), exception.toString());
        dispose(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        dispose(session.getId());
    }

    private void dispose(String sessionId) {
        ClientSession client = sessions.remove(sessionId);
        if (client != null) {
            client.unsubscribe(false);
        }
    }

    private static Long pidFromQuery(URI uri) {
        if (uri == null) {
            return null;
        }
        String value = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("pid");
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private final class ClientSession implements LiveMetricsListener {

        private final WebSocketSession session;
        private Subscription subscription;
        private Long pid;

        ClientSession(WebSocketSession session) {
            this.session = session;
        }

        synchronized void subscribe(long newPid) {
            unsubscribe(false);
            try {
                pid = newPid;
                send(LiveStreamMessageDto.subscribed(newPid));
                subscription = collector.subscribe(newPid, this);
            } catch (InspectorException e) {
                pid = null;
                send(LiveStreamMessageDto.error(newPid, e.getMessage()));
            }
        }

        synchronized void unsubscribe(boolean notify) {
            Subscription current = subscription;
            Long previousPid = pid;
            subscription = null;
            pid = null;
            if (current != null) {
                current.close();
            }
            if (notify) {
                send(LiveStreamMessageDto.unsubscribed(previousPid));
            }
        }

        @Override
        public void onMetrics(LiveMetricsPayloadDto payload) {
            send(LiveStreamMessageDto.metrics(payload));
        }

        @Override
        public void onStatus(TargetStatusDto status) {
            send(LiveStreamMessageDto.status(status));
        }

        void send(LiveStreamMessageDto message) {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(message)));
            } catch (IOException | RuntimeException e) {
                // Unsubscribing from here would re-enter the monitor; closing the session triggers cleanup instead.
                log.debug("Closing WebSocket {}: {}", session.getId(), e.toString());
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (IOException ignored) {
                    // already broken
                }
            }
        }
    }
}
