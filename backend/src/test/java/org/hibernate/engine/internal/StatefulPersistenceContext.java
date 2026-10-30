package org.hibernate.engine.internal;

import java.util.HashMap;
import java.util.IdentityHashMap;

/** Test double with the fields of Hibernate 6 {@code StatefulPersistenceContext} that the inspector reads. */
public class StatefulPersistenceContext {

    private final Object session;
    private final EntityEntryContext entityEntryContext = new EntityEntryContext();
    private final HashMap<Object, Object> entitiesByKey = new HashMap<>();
    private final IdentityHashMap<Object, Object> collectionEntries = new IdentityHashMap<>();

    public StatefulPersistenceContext(Object session) {
        this.session = session;
    }

    public void addEntity(Object key, Object entity) {
        entitiesByKey.put(key, entity);
        entityEntryContext.increment();
    }

    public Object session() {
        return session;
    }

    public int collections() {
        return collectionEntries.size();
    }
}
