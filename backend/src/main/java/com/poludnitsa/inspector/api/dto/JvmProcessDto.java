package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * A Java process discovered on the host.
 *
 * @param pid          OS process id
 * @param displayName  main class / jar or WildFly server name
 * @param processType  detected kind of process
 * @param heapUsed     bytes used in heap; {@code null} when the process is not attached yet
 * @param heapMax      max heap bytes ({@code -Xmx}); {@code null} when unknown or undefined
 * @param jvmFlags     JVM input arguments of the target process
 * @param jvmVersion   {@code java.vm.version} of the target, {@code null} if unknown
 * @param attachable   whether the Attach API / local JMX agent can be used for this process
 * @param attachError  reason and hint when {@code attachable == false}, otherwise {@code null}
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
        String attachError
) {
    public JvmProcessDto {
        jvmFlags = jvmFlags == null ? List.of() : List.copyOf(jvmFlags);
    }
}
