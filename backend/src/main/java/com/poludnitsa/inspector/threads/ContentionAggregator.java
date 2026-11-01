package com.poludnitsa.inspector.threads;

import com.poludnitsa.inspector.api.dto.LockKind;
import com.poludnitsa.inspector.api.dto.MonitorContentionDto;
import com.poludnitsa.inspector.api.dto.StackFrameDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitor contention hotspots from thread samples: for each lock class and the line that waits for it, how long and
 * how often threads blocked there, which threads and who held the lock.
 *
 * <p>With ThreadMXBean contention monitoring the blocked time is exact: the growth of each thread's
 * {@code blockedTime} (monitors) or {@code waitedTime} (parked on a synchronizer) since its previous sample. Short
 * blocks that start and end between two samples are credited to the lock the thread was last seen blocked on.
 * Without it (a jstack file) each sample counts as {@code estimateMs}.
 *
 * <p>Not thread-safe: one sampler feeds it.
 */
public final class ContentionAggregator {

    private record Key(String lockClass, LockKind kind, String blockedAt) {
    }

    private static final class Stat {
        final Key key;
        String applicationFrame;
        double timeMs;
        long count;
        long samples;
        final Map<String, Integer> threads = new LinkedHashMap<>();
        int serverThreads;
        final Map<String, Integer> owners = new LinkedHashMap<>();
        final Map<String, Integer> ownerFrames = new LinkedHashMap<>();

        Stat(Key key) {
            this.key = key;
        }
    }

    private record Previous(long blockedTime, long blockedCount, long waitedTime, Key key) {
    }

    private final List<String> poolPrefixes;
    private final double estimateMs;
    private final Map<Key, Stat> stats = new HashMap<>();
    private final Map<String, Previous> previous = new HashMap<>();
    private boolean timed;
    private long lastSample;

    public ContentionAggregator(List<String> poolPrefixes, double estimateMs) {
        this.poolPrefixes = poolPrefixes;
        this.estimateMs = estimateMs;
    }

    public void sample(List<ThreadEntry> threads) {
        long now = System.nanoTime();
        // a thread blocked since before contention monitoring was enabled has no running timer: still blocked on the
        // same lock as in the previous sample counts as blocked for the time between the two samples
        double elapsedMs = lastSample == 0 ? estimateMs : (now - lastSample) / 1e6;
        lastSample = now;
        Map<Long, ThreadEntry> byId = new HashMap<>();
        Map<String, ThreadEntry> byName = new HashMap<>();
        for (ThreadEntry thread : threads) {
            if (thread.id() >= 0) {
                byId.put(thread.id(), thread);
            }
            byName.putIfAbsent(thread.name(), thread);
        }
        for (ThreadEntry thread : threads) {
            Key key = key(thread);
            Previous before = previous.get(thread.key());
            boolean measured = thread.blockedTimeMs() >= 0 && before != null && before.blockedTime() >= 0;
            timed |= thread.blockedTimeMs() >= 0;
            long blockedDelta = measured ? Math.max(0, thread.blockedTimeMs() - before.blockedTime()) : 0;
            long countDelta = measured ? Math.max(0, thread.blockedCount() - before.blockedCount()) : 0;
            long waitedDelta = measured && thread.waitedTimeMs() >= 0 && before.waitedTime() >= 0
                    ? Math.max(0, thread.waitedTimeMs() - before.waitedTime()) : 0;
            if (key != null) {
                Stat stat = stats.computeIfAbsent(key, Stat::new);
                stat.samples++;
                boolean newEpisode = before == null || !key.equals(before.key());
                if (measured) {
                    double delta = key.kind() == LockKind.MONITOR ? blockedDelta : Math.min(waitedDelta, 60_000);
                    stat.timeMs += newEpisode ? delta : Math.max(delta, elapsedMs);
                    stat.count += key.kind() == LockKind.MONITOR ? Math.max(countDelta, newEpisode ? 1 : 0)
                            : newEpisode ? 1 : 0;
                } else {
                    stat.timeMs += estimateMs;
                    stat.count += newEpisode ? 1 : 0;
                }
                if (stat.threads.merge(thread.name(), 1, Integer::sum) == 1
                        && Frames.serverThread(thread.name(), poolPrefixes)) {
                    stat.serverThreads++;
                }
                if (stat.applicationFrame == null) {
                    StackFrameDto app = Frames.firstApplicationFrame(thread.frames());
                    stat.applicationFrame = app == null ? null : Frames.describe(app);
                }
                ThreadEntry owner = thread.lockOwnerId() >= 0 ? byId.get(thread.lockOwnerId())
                        : thread.lockOwnerName() == null ? null : byName.get(thread.lockOwnerName());
                String ownerName = owner != null ? owner.name() : thread.lockOwnerName();
                if (ownerName != null) {
                    stat.owners.merge(ownerName, 1, Integer::sum);
                }
                if (owner != null && !owner.frames().isEmpty()) {
                    StackFrameDto app = Frames.firstApplicationFrame(owner.frames());
                    stat.ownerFrames.merge(Frames.describe(app != null ? app : owner.frames().get(0)), 1, Integer::sum);
                }
            } else if (measured && blockedDelta > 0 && before.key() != null) {
                // blocked and released between two samples: the lock it was last seen blocked on
                Stat stat = stats.get(before.key());
                if (stat != null) {
                    stat.timeMs += blockedDelta;
                    stat.count += countDelta;
                }
            }
            previous.put(thread.key(), new Previous(thread.blockedTimeMs(), thread.blockedCount(),
                    thread.waitedTimeMs(), key != null ? key : before == null ? null : before.key()));
        }
    }

    /** Whether the blocked times are measured (contention monitoring on), not estimated. */
    public boolean timed() {
        return timed;
    }

    public List<MonitorContentionDto> result(int limit) {
        List<Stat> sorted = new ArrayList<>(stats.values());
        sorted.sort(Comparator.comparingDouble((Stat s) -> s.timeMs).thenComparingLong(s -> s.samples).reversed());
        List<MonitorContentionDto> result = new ArrayList<>();
        for (Stat stat : sorted.subList(0, Math.min(limit, sorted.size()))) {
            result.add(new MonitorContentionDto(stat.key.lockClass(), stat.key.kind(), stat.key.blockedAt(),
                    stat.applicationFrame, Math.round(stat.timeMs * 10) / 10.0, stat.count, stat.samples,
                    stat.threads.size(), stat.serverThreads, timed, top(stat.threads, 10), top(stat.owners, 5),
                    stat.ownerFrames.isEmpty() ? null : top(stat.ownerFrames, 1).get(0)));
        }
        return result;
    }

    private static Key key(ThreadEntry thread) {
        if (thread.lockKind() == null || thread.lockName() == null) {
            return null;
        }
        StackFrameDto frame = thread.lockKind() == LockKind.MONITOR
                ? thread.frames().isEmpty() ? null : thread.frames().get(0)
                : Frames.waitingFrame(thread.frames());
        return new Key(thread.lockClass(), thread.lockKind(), frame == null ? "?" : Frames.describe(frame));
    }

    private static List<String> top(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }
}
