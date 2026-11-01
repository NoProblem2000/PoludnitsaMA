package com.poludnitsa.inspector.it;

import java.util.ArrayList;
import java.util.List;

/**
 * Child JVM with known thread trouble: two threads in a monitor deadlock, a WildFly-like request thread spinning in
 * an endless loop, two request threads contending for one monitor, and an allocator that keeps the GC busy.
 */
public final class ThreadsTarget {

    static volatile double sink;

    /** Lock order inversion: each transfer locks its source, then its target. */
    public static final class Account {
        void transfer(Account to) {
            synchronized (this) {
                pause(200);
                synchronized (to) {
                    sink++;
                }
            }
        }
    }

    /** A loop that never ends: the "stuck RUNNABLE" thread. */
    public static final class Spinner {
        static void spin() {
            long i = 0;
            while (true) {
                sink += Math.sqrt(++i);
            }
        }
    }

    /** A hot monitor: held 30 ms at a time by two request threads. */
    public static final class Inventory {
        static final Inventory STOCK = new Inventory();

        synchronized void reserve() {
            long end = System.nanoTime() + 30_000_000L;
            while (System.nanoTime() < end) {
                sink += Math.cbrt(sink + 1);
            }
        }
    }

    private ThreadsTarget() {
    }

    public static void main(String[] args) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10 * 60_000L;
        Account a = new Account();
        Account b = new Account();
        start("deadlock-A", () -> a.transfer(b));
        start("deadlock-B", () -> b.transfer(a));
        start("default task-1", Spinner::spin);
        for (int i = 2; i <= 3; i++) {
            start("default task-" + i, () -> {
                while (System.currentTimeMillis() < deadline) {
                    Inventory.STOCK.reserve();
                    pause(5);
                }
            });
        }
        start("allocator", () -> {
            List<byte[]> keep = new ArrayList<>();
            int n = 0;
            while (System.currentTimeMillis() < deadline) {
                keep.add(new byte[16 * 1024]);
                if (keep.size() > 2_000) {
                    keep.clear();
                }
                if (++n % 100 == 0) {
                    pause(1);
                }
            }
        });
        Thread.sleep(500);
        System.out.println("READY");
        System.out.flush();
        Thread.sleep(deadline - System.currentTimeMillis());
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void start(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
    }
}
