package com.poludnitsa.inspector.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poludnitsa.inspector.api.dto.ApiErrorDto;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests whose {@code Origin} header names a host other than the local ones, including WebSocket
 * handshakes. The inspector can dump heaps and trigger GCs on production processes, so a random web page open in the
 * operator's browser must not be able to drive it through {@code http://127.0.0.1:8080}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalOriginFilter extends OncePerRequestFilter {

    private final Set<String> allowedHosts;
    private final ObjectMapper mapper;

    public LocalOriginFilter(InspectorProperties properties, ObjectMapper mapper) {
        this.allowedHosts = properties.web().allowedOriginHosts().stream()
                .map(LocalOriginFilter::normalizeHost)
                .collect(Collectors.toUnmodifiableSet());
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin == null || isAllowed(origin)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiErrorDto(Instant.now(), 403, "ORIGIN_FORBIDDEN",
                "Requests from origin " + origin + " are not allowed", request.getRequestURI()));
    }

    boolean isAllowed(String origin) {
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return false;
            }
            return allowedHosts.contains(normalizeHost(uri.getHost()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String normalizeHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.startsWith("[") && lower.endsWith("]") ? lower.substring(1, lower.length() - 1) : lower;
    }
}
