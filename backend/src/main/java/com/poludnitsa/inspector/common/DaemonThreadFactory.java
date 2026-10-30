package com.poludnitsa.inspector.common;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Named daemon threads, so inspector background work never blocks JVM shutdown. */
public final class DaemonThreadFactory implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    public DaemonThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, prefix + "-" + counter.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
}
