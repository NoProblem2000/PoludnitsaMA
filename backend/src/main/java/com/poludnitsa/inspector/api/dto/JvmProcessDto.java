package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A Java process discovered on the host or registered through a remote JMX endpoint.
 *
 * @param pid              OS process id (for remote targets: {@code RuntimeMXBean#getPid()} of the target)
 * @param displayName      WildFly server name, Hazelcast member, Spring Boot jar or main class
 * @param processType      detected kind of process
 * @param heapUsed         bytes used in heap; {@code null} while not connected
 * @param heapMax          max heap bytes ({@code -Xmx}); {@code null} while not connected or when undefined
 * @param jvmFlags         JVM input arguments of the target process
 * @param jvmVersion       {@code java.vm.version} of the target, {@code null} if unknown
 * @param attachable       whether the inspector can open a JMX connection to this process
 * @param attachError      reason and remediation hint when {@code attachable == false} or the last connect failed
 * @param connectionSource attach by PID or remote JMX
 * @param connectionState  current state of the JMX connection
 * @param remoteEndpoint   JMX service URL for {@link ConnectionSource#REMOTE_JMX}, otherwise {@code null}
 * @param startTimeEpochMs when the process started (uptime in the UI), {@code null} if unknown
 */
public record JvmProcessDto(
        long pid,
        String displayName,
        ProcessType processType,
        Long heapUsed,
        Long heapMax,
        List<String> jvmFlags,
        String jvmVersion,
        boolean attachable,
        String attachError,
        ConnectionSource connectionSource,
        ConnectionState connectionState,
        String remoteEndpoint,
        Long startTimeEpochMs
) {
    public JvmProcessDto {
        jvmFlags = jvmFlags == null ? List.of() : List.copyOf(jvmFlags);
    }
}
