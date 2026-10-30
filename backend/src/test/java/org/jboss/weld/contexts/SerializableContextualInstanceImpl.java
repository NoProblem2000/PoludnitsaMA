package org.jboss.weld.contexts;

/** Test double of Weld {@code SerializableContextualInstanceImpl}: contextual, instance, creational context. */
public class SerializableContextualInstanceImpl {

    private final Object contextual;
    private final Object instance;
    private final CreationalContextImpl creationalContext;

    public SerializableContextualInstanceImpl(Object contextual, Object instance,
                                              CreationalContextImpl creationalContext) {
        this.contextual = contextual;
        this.instance = instance;
        this.creationalContext = creationalContext;
    }

    public Object instance() {
        return instance;
    }

    public Object contextual() {
        return contextual;
    }

    public CreationalContextImpl creationalContext() {
        return creationalContext;
    }
}
