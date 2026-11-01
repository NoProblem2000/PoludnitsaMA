package com.poludnitsa.inspector.it;

import io.undertow.server.Connectors;
import org.hibernate.event.internal.DefaultFlushEventListener;
import org.postgresql.jdbc.PgPreparedStatement;

/**
 * Child JVM with a known profile: one thread burns CPU in business code, a request thread goes Undertow → resource
 * → Hibernate → PostgreSQL driver and waits there, and two threads contend for one monitor.
 */
public final class CpuTarget {

    static volatile double sink;
    static final Object LEDGER = new Object();

    /** Business code that burns CPU. */
    public static final class Billing {
        static double calculate(int rounds) {
            double total = 0;
            for (int i = 1; i < rounds; i++) {
                total += Math.sqrt(i) * Math.sin(i);
            }
            return total;
        }
    }

    /** A JAX-RS-like resource whose request runs a Hibernate flush with one query. */
    public static final class OrderResource {
        private final DefaultFlushEventListener flush = new DefaultFlushEventListener();
        private final PgPreparedStatement statement = new PgPreparedStatement();

        void handle() {
            flush.onFlush(() -> sink += statement.executeQuery(25));
        }
    }

    /** Holds the ledger lock while "posting": the other thread blocks on it. */
    public static final class Ledger {
        static void post() {
            synchronized (LEDGER) {
                long end = System.nanoTime() + 40_000_000L;
                while (System.nanoTime() < end) {
                    sink += Math.cbrt(sink + 1);
                }
            }
        }
    }

    private CpuTarget() {
    }

    public static void main(String[] args) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10 * 60_000L;
        start("cpu-burner", () -> {
            while (System.currentTimeMillis() < deadline) {
                sink += Billing.calculate(200_000);
            }
        });
        OrderResource resource = new OrderResource();
        start("default task-1", () -> {
            while (System.currentTimeMillis() < deadline) {
                Connectors.executeRootHandler(resource::handle);
            }
        });
        for (int i = 1; i <= 2; i++) {
            start("ledger-" + i, () -> {
                while (System.currentTimeMillis() < deadline) {
                    Ledger.post();
                    // outside the lock: the other thread acquires it, after blocking for one posting
                    pause(2);
                }
            });
        }
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
