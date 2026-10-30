package org.hibernate.internal;

import org.hibernate.engine.internal.StatefulPersistenceContext;

/** Test double of Hibernate {@code SessionImpl} owning its persistence context. */
public final class SessionImpl {

    private final StatefulPersistenceContext persistenceContext = new StatefulPersistenceContext(this);

    public StatefulPersistenceContext getPersistenceContext() {
        return persistenceContext;
    }
}
