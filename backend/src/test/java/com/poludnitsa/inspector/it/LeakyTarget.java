package com.poludnitsa.inspector.it;

import java.util.ArrayList;
import java.util.List;

/**
 * Child JVM used as the monitored target in integration tests: retains {@link LeakedEntry} objects at a steady rate
 * and churns garbage so that young collections happen every few hundred milliseconds.
 */
public final class LeakyTarget {

    /** Retained forever; the leak the radar must find. */
    public static final class LeakedEntry {
        final byte[] payload = new byte[256];
    }

    /** About 300 KB/s of retained growth for five minutes, well within -Xmx256m. */
    private static final int MAX_RETAINED = 300_000;
    private static volatile Object blackhole;

    private LeakyTarget() {
    }

    public static void main(String[] args) throws InterruptedException {
        List<LeakedEntry> leak = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10 * 60_000L;
        System.out.println("READY");
        System.out.flush();
        while (System.currentTimeMillis() < deadline) {
            if (leak.size() < MAX_RETAINED) {
                for (int i = 0; i < 25; i++) {
                    leak.add(new LeakedEntry());
                }
            }
            for (int i = 0; i < 2_000; i++) {
                blackhole = new byte[1024];
            }
            Thread.sleep(20);
        }
    }
}
