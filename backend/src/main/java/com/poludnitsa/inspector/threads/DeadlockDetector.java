package com.poludnitsa.inspector.threads;

import com.poludnitsa.inspector.api.dto.DeadlockDto;
import com.poludnitsa.inspector.api.dto.DeadlockNodeDto;
import com.poludnitsa.inspector.api.dto.LockKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wait-for cycles of a set of threads: a thread blocked on a monitor, or parked on a synchronizer, points to the
 * thread that owns it; a cycle is a deadlock. Works the same on a live dump (owners from {@code ThreadInfo}) and a
 * jstack text (owners from the "- locked" lines).
 */
public final class DeadlockDetector {

    private static final int STACK_FRAMES = 12;

    private DeadlockDetector() {
    }

    public static List<DeadlockDto> cycles(List<ThreadEntry> threads, Instant at) {
        Map<String, ThreadEntry> byKey = new LinkedHashMap<>();
        Map<Long, ThreadEntry> byId = new HashMap<>();
        Map<String, ThreadEntry> byName = new HashMap<>();
        for (ThreadEntry thread : threads) {
            byKey.put(thread.key(), thread);
            if (thread.id() >= 0) {
                byId.put(thread.id(), thread);
            }
            byName.putIfAbsent(thread.name(), thread);
        }
        List<DeadlockDto> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (ThreadEntry start : threads) {
            if (done.contains(start.key())) {
                continue;
            }
            List<ThreadEntry> path = new ArrayList<>();
            Map<String, Integer> position = new HashMap<>();
            ThreadEntry current = start;
            while (current != null && !done.contains(current.key())) {
                Integer at0 = position.get(current.key());
                if (at0 != null) {
                    List<ThreadEntry> cycle = path.subList(at0, path.size());
                    Set<String> names = new TreeSet<>();
                    cycle.forEach(t -> names.add(t.key()));
                    if (seen.add(String.join(",", names))) {
                        result.add(toDto(new ArrayList<>(cycle), byId, byName, at));
                    }
                    break;
                }
                position.put(current.key(), path.size());
                path.add(current);
                current = owner(current, byId, byName);
            }
            path.forEach(t -> done.add(t.key()));
        }
        return result;
    }

    private static ThreadEntry owner(ThreadEntry thread, Map<Long, ThreadEntry> byId, Map<String, ThreadEntry> byName) {
        if (thread.lockKind() == null || thread.lockName() == null) {
            return null;
        }
        if (thread.lockOwnerId() >= 0) {
            ThreadEntry owner = byId.get(thread.lockOwnerId());
            if (owner != null) {
                return owner;
            }
        }
        return thread.lockOwnerName() == null ? null : byName.get(thread.lockOwnerName());
    }

    private static DeadlockDto toDto(List<ThreadEntry> cycle, Map<Long, ThreadEntry> byId,
                                     Map<String, ThreadEntry> byName, Instant at) {
        List<DeadlockNodeDto> nodes = new ArrayList<>();
        boolean monitors = true;
        for (ThreadEntry thread : cycle) {
            ThreadEntry owner = owner(thread, byId, byName);
            monitors &= thread.lockKind() == LockKind.MONITOR;
            nodes.add(new DeadlockNodeDto(thread.id(), thread.name(), thread.state(), thread.lockName(),
                    thread.lockClass(), thread.lockKind(), owner == null ? -1 : owner.id(),
                    owner == null ? thread.lockOwnerName() : owner.name(), thread.heldLocks(),
                    thread.frames().subList(0, Math.min(STACK_FRAMES, thread.frames().size()))));
        }
        return new DeadlockDto(at, monitors ? LockKind.MONITOR : LockKind.SYNCHRONIZER, nodes);
    }
}
