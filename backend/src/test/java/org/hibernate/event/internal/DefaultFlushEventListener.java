package org.hibernate.event.internal;

/** Test double: a Hibernate flush that runs the statements it needs. */
public final class DefaultFlushEventListener {

    public void onFlush(Runnable statements) {
        statements.run();
    }
}
