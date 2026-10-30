package com.poludnitsa.inspector.config;

import com.poludnitsa.inspector.api.ws.LiveMetricsWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveMetricsWebSocketHandler liveMetricsHandler;

    public WebSocketConfig(LiveMetricsWebSocketHandler liveMetricsHandler) {
        this.liveMetricsHandler = liveMetricsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Origins are enforced by LocalOriginFilter for every request, including the handshake.
        registry.addHandler(liveMetricsHandler, "/ws/live").setAllowedOriginPatterns("*");
    }
}
