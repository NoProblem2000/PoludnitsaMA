package com.poludnitsa.inspector.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;

/**
 * Manual connection to a JVM started with an open JMX port
 * ({@code -Dcom.sun.management.jmxremote.port=9010 ...}). Exactly one of {@code hostPort} and {@code serviceUrl} is set.
 *
 * @param hostPort   {@code host:port} of the RMI registry, e.g. {@code 127.0.0.1:9010} or {@code [::1]:9010}
 * @param serviceUrl full URL, e.g. {@code service:jmx:rmi:///jndi/rmi://127.0.0.1:9010/jmxrmi}
 * @param username   optional JMX user ({@code jmxremote.password})
 * @param password   optional JMX password; kept in memory only and never returned by the API
 */
public record RemoteJmxConnectRequestDto(
        @Pattern(regexp = "^(\\[[0-9A-Fa-f:.]+]|[^\\s:\\[\\]]+):\\d{1,5}$", message = "must be host:port")
        String hostPort,
        @Pattern(regexp = "^service:jmx:\\S+$", message = "must start with service:jmx:")
        String serviceUrl,
        String username,
        String password
) {
    @AssertTrue(message = "exactly one of hostPort and serviceUrl must be set")
    public boolean isExactlyOneEndpoint() {
        return (hostPort == null || hostPort.isBlank()) != (serviceUrl == null || serviceUrl.isBlank());
    }

    @Override
    public String toString() {
        return "RemoteJmxConnectRequestDto[hostPort=" + hostPort + ", serviceUrl=" + serviceUrl
                + ", username=" + username + ", password=" + (password == null ? "null" : "***") + "]";
    }
}
