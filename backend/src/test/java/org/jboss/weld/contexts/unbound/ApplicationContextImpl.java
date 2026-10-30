package org.jboss.weld.contexts.unbound;

import org.jboss.weld.contexts.beanstore.ConcurrentHashMapBeanStore;

/** Test double of Weld's application context with its bean store. */
public class ApplicationContextImpl {

    private final ConcurrentHashMapBeanStore beanStore = new ConcurrentHashMapBeanStore();

    public ConcurrentHashMapBeanStore beanStore() {
        return beanStore;
    }
}
