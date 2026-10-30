package com.poludnitsa.inspector.heap;

/**
 * Open-addressing {@code long -> int} counter map on primitive arrays: about 12 bytes per entry instead of ~60 for a
 * {@code HashMap<Long, Integer>}, which matters when millions of strings are fingerprinted.
 */
public final class LongIntHashMap {

    private static final float LOAD_FACTOR = 0.6f;

    private long[] keys;
    private int[] values;
    private boolean[] used;
    private int size;
    private int threshold;

    public LongIntHashMap(int expected) {
        int capacity = Integer.highestOneBit(Math.max(16, (int) (expected / LOAD_FACTOR)) - 1) << 1;
        allocate(capacity);
    }

    /** Adds one to the counter of {@code key} and returns the new value. */
    public int increment(long key) {
        int slot = find(key);
        if (used[slot]) {
            return ++values[slot];
        }
        used[slot] = true;
        keys[slot] = key;
        values[slot] = 1;
        if (++size > threshold) {
            rehash();
        }
        return 1;
    }

    public int get(long key) {
        int slot = find(key);
        return used[slot] ? values[slot] : 0;
    }

    public int size() {
        return size;
    }

    private int find(long key) {
        int mask = keys.length - 1;
        int slot = (int) mix(key) & mask;
        while (used[slot] && keys[slot] != key) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private void rehash() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        boolean[] oldUsed = used;
        allocate(oldKeys.length * 2);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                int slot = find(oldKeys[i]);
                used[slot] = true;
                keys[slot] = oldKeys[i];
                values[slot] = oldValues[i];
            }
        }
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new int[capacity];
        used = new boolean[capacity];
        threshold = (int) (capacity * LOAD_FACTOR);
    }

    private static long mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 32);
    }
}
