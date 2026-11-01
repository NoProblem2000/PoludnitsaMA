package com.poludnitsa.inspector.threads;

import com.poludnitsa.inspector.api.dto.JvmThreadState;
import com.poludnitsa.inspector.api.dto.LockKind;
import com.poludnitsa.inspector.api.dto.StackFrameDto;
import java.util.List;

/**
 * A Java thread at one point in time, from {@code ThreadMXBean} ({@link ThreadMx}) or a jstack text
 * ({@link JstackParser}).
 *
 * @param id            thread id, -1 if unknown
 * @param lockName      identity ("class@hash") of what the thread waits for: a monitor it blocks on, an object it
 *                      waits on, a synchronizer it is parked on; {@code null} if none
 * @param lockKind      {@code MONITOR} when BLOCKED on a monitor, {@code SYNCHRONIZER} when parked on a lock owned
 *                      by another thread: the two kinds of contention; {@code null} otherwise
 * @param lockOwnerId   owner of {@code lockName}, -1 if none or unknown
 * @param heldLocks     identities of the monitors and synchronizers it holds
 * @param cpuMs         CPU time so far, {@code null} if unknown
 * @param blockedTimeMs cumulative time BLOCKED (ThreadMXBean contention monitoring), -1 if not measured
 * @param waitedTimeMs  cumulative time WAITING / TIMED_WAITING, -1 if not measured
 */
public record ThreadEntry(
        long id,
        String name,
        boolean daemon,
        JvmThreadState state,
        List<StackFrameDto> frames,
        String lockName,
        LockKind lockKind,
        long lockOwnerId,
        String lockOwnerName,
        List<String> heldLocks,
        Double cpuMs,
        long blockedCount,
        long blockedTimeMs,
        long waitedCount,
        long waitedTimeMs
) {
    public ThreadEntry {
        frames = frames == null ? List.of() : List.copyOf(frames);
        heldLocks = heldLocks == null ? List.of() : List.copyOf(heldLocks);
    }

    /** Class part of {@code lockName}. */
    public String lockClass() {
        return lockClassOf(lockName);
    }

    public static String lockClassOf(String identity) {
        if (identity == null) {
            return null;
        }
        int at = identity.lastIndexOf('@');
        return at < 0 ? identity : identity.substring(0, at);
    }

    ThreadEntry withOwner(long ownerId, String ownerName, LockKind kind) {
        return new ThreadEntry(id, name, daemon, state, frames, lockName, kind, ownerId, ownerName, heldLocks, cpuMs,
                blockedCount, blockedTimeMs, waitedCount, waitedTimeMs);
    }

    ThreadEntry withCpu(Double cpu) {
        return new ThreadEntry(id, name, daemon, state, frames, lockName, lockKind, lockOwnerId, lockOwnerName,
                heldLocks, cpu, blockedCount, blockedTimeMs, waitedCount, waitedTimeMs);
    }

    /** Key across dumps: the id when known (names repeat), else the name. */
    String key() {
        return id >= 0 ? id + "|" + name : name;
    }
}
