package com.poludnitsa.inspector.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poludnitsa.inspector.api.dto.ApiErrorDto;
import com.poludnitsa.inspector.api.dto.ConnectionState;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.LiveStreamMessageDto;
import com.poludnitsa.inspector.api.dto.LiveStreamMessageType;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveWebSocketIntegrationTest {

    private static TargetJvm target;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper mapper;

    @BeforeAll
    static void startTarget() throws Exception {
        target = TargetJvm.start();
    }

    @AfterAll
    static void stopTarget() throws Exception {
        target.close();
    }

    @Test
    void processListContainsTarget() {
        ResponseEntity<JvmProcessDto[]> response = rest.getForEntity("/api/processes", JvmProcessDto[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(JvmProcessDto::pid)
                .contains(target.pid())
                .doesNotContain(ProcessHandle.current().pid());
    }

    @Test
    void streamsMetricsOverWebSocket() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                received.add(message.getPayload());
            }
        }, null, URI.create("ws://127.0.0.1:" + port + "/ws/live")).get(10, TimeUnit.SECONDS);
        try {
            session.sendMessage(new TextMessage("{\"action\":\"subscribe\",\"pid\":" + target.pid() + "}"));

            List<LiveStreamMessageDto> messages = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (messages.stream().noneMatch(m -> m.type() == LiveStreamMessageType.METRICS)
                    && System.nanoTime() < deadline) {
                String payload = received.poll(1, TimeUnit.SECONDS);
                if (payload != null) {
                    messages.add(mapper.readValue(payload, LiveStreamMessageDto.class));
                }
            }

            assertThat(messages).extracting(LiveStreamMessageDto::type)
                    .contains(LiveStreamMessageType.SUBSCRIBED, LiveStreamMessageType.STATUS,
                            LiveStreamMessageType.METRICS);
            assertThat(messages).filteredOn(m -> m.type() == LiveStreamMessageType.STATUS)
                    .extracting(m -> m.status().state()).contains(ConnectionState.CONNECTED);
            LiveStreamMessageDto metrics = messages.stream()
                    .filter(m -> m.type() == LiveStreamMessageType.METRICS).findFirst().orElseThrow();
            assertThat(metrics.pid()).isEqualTo(target.pid());
            assertThat(metrics.metrics().heap().used()).isPositive();
        } finally {
            session.close();
        }
    }

    @Test
    void gcRequiresConfirmation() {
        ResponseEntity<ApiErrorDto> response = rest.exchange("/api/processes/" + target.pid() + "/gc",
                HttpMethod.POST, jsonEntity("{\"confirm\":false}"), ApiErrorDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("GC_CONFIRMATION_REQUIRED");
    }

    @Test
    void selfPidIsRejected() {
        ResponseEntity<ApiErrorDto> response = rest.exchange("/api/processes/active", HttpMethod.PUT,
                jsonEntity("{\"pid\":" + ProcessHandle.current().pid() + "}"), ApiErrorDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("SELF_MONITORING_FORBIDDEN");
    }

    private static HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
