package com.poludnitsa.inspector.api.dto;

/**
 * A thread of the live target with its cumulative contention counters.
 *
 * @param serverThread WildFly worker / pool thread (inspector.inspections.pool-thread-prefixes)
 * @param cpuMs {@code null} when CPU time is not measured
 * @param cpuPercent of one core since the previous sample, {@code null} until known
 * @param blockedTimeMs since contention monitoring was enabled, {@code null} when it is off
 */
public record LiveThreadDto(
        long threadId,
        String name,
        JvmThreadState state,
        boolean daemon,
        boolean serverThread,
        Double cpuMs,
        Double cpuPercent,
        long blockedCount,
        Long blockedTimeMs,
        long waitedCount,
        Long waitedTimeMs,
        String lockName,
        String lockOwnerName,
        String topFrame
) {
}
