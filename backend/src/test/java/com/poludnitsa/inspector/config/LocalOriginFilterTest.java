package com.poludnitsa.inspector.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalOriginFilterTest {

    private final LocalOriginFilter filter = new LocalOriginFilter(InspectorProperties.defaults(),
            new ObjectMapper().registerModule(new JavaTimeModule()));

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:8080", "http://[::1]:5173", "https://LOCALHOST"})
    void localOriginsPass(String origin) {
        assertThat(filter.isAllowed(origin)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "http://localhost.evil.example", "null", "file:///tmp/x.html",
            "http://127.0.0.2"})
    void foreignOriginsAreRejected(String origin) {
        assertThat(filter.isAllowed(origin)).isFalse();
    }

    @Test
    void rejectedRequestNeverReachesTheController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/processes/4711/gc");
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ORIGIN_FORBIDDEN");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void requestWithoutOriginPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/processes");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }
}
