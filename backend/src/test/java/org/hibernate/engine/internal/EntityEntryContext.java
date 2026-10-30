package org.hibernate.engine.internal;

/** Test double of Hibernate {@code EntityEntryContext}: the managed entity counter. */
public final class EntityEntryContext {

    private transient int count;

    void increment() {
        count++;
    }
}
