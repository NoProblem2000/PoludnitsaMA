package org.jboss.weld.contexts.beanstore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test double of Weld {@code ConcurrentHashMapBeanStore}: bean id → contextual instance. */
public class ConcurrentHashMapBeanStore {

    protected final Map<String, Object> delegate = new ConcurrentHashMap<>();

    public void put(String id, Object contextualInstance) {
        delegate.put(id, contextualInstance);
    }
}
