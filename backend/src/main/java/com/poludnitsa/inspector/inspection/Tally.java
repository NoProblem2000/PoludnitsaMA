package com.poludnitsa.inspector.inspection;

import com.poludnitsa.inspector.common.Bytes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Counts and bytes per key, for "top N" evidence lines. */
public final class Tally {

    public record Entry(String key, long count, long bytes) {

        public String format() {
            return bytes > 0
                    ? String.format("%s — %,d шт., %s", key, count, Bytes.format(bytes))
                    : String.format("%s — %,d шт.", key, count);
        }
    }

    private final Map<String, long[]> values = new HashMap<>();

    public void add(String key, long bytes) {
        long[] value = values.computeIfAbsent(key == null ? "?" : key, k -> new long[2]);
        value[0]++;
        value[1] += Math.max(0, bytes);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int distinct() {
        return values.size();
    }

    /** By bytes, then by count, descending. */
    public List<Entry> top(int limit) {
        List<Entry> entries = new ArrayList<>(values.size());
        values.forEach((key, value) -> entries.add(new Entry(key, value[0], value[1])));
        entries.sort((a, b) -> a.bytes() != b.bytes() ? Long.compare(b.bytes(), a.bytes())
                : a.count() != b.count() ? Long.compare(b.count(), a.count()) : a.key().compareTo(b.key()));
        return entries.subList(0, Math.min(limit, entries.size()));
    }

    /** The most frequent key. */
    public Entry mostFrequent() {
        Entry best = null;
        for (Map.Entry<String, long[]> e : values.entrySet()) {
            if (best == null || e.getValue()[0] > best.count()) {
                best = new Entry(e.getKey(), e.getValue()[0], e.getValue()[1]);
            }
        }
        return best;
    }
}
