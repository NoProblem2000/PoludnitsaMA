package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Where threads block: a lock class and the line that waits for it.
 *
 * @param blockedAt frame that waits for the lock: "class.method(File.java:42)"
 * @param applicationFrame first application frame of the blocked stacks, {@code null} if none
 * @param blockedTimeMs time spent blocked here (ThreadMXBean contention monitoring, or samples x interval when {@code timeMeasured} is false)
 * @param blockedCount times threads entered the blocked state here
 * @param samples samples or dumps that saw a thread blocked here
 * @param threads distinct threads blocked here
 * @param serverThreads of them WildFly worker / pool threads
 * @param topThreads blocked threads, most blocked first
 * @param topOwners threads seen holding the lock
 * @param ownerFrame what the most frequent owner was executing, {@code null} if unknown
 */
public record MonitorContentionDto(
        String lockClass,
        LockKind lockKind,
        String blockedAt,
        String applicationFrame,
        double blockedTimeMs,
        long blockedCount,
        long samples,
        int threads,
        int serverThreads,
        boolean timeMeasured,
        List<String> topThreads,
        List<String> topOwners,
        String ownerFrame
) {
    public MonitorContentionDto {
        topThreads = topThreads == null ? List.of() : List.copyOf(topThreads);
        topOwners = topOwners == null ? List.of() : List.copyOf(topOwners);
    }
}
