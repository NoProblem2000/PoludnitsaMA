package com.poludnitsa.inspector.api.dto;

/**
 * Request to call {@code MemoryMXBean.gc()} on the target.
 *
 * @param confirm must be {@code true}: an explicit GC on a production WildFly is a Full GC (stop-the-world, seconds on
 *                large heaps) unless {@code -XX:+ExplicitGCInvokesConcurrent} is set, and long pauses can drop the
 *                Hazelcast client connection on heartbeat timeout
 */
public record GcTriggerRequestDto(
        boolean confirm
) {
}
