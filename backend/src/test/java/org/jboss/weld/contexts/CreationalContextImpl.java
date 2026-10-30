package org.jboss.weld.contexts;

import java.util.ArrayList;
import java.util.List;

/** Test double of Weld {@code CreationalContextImpl}: the list of dependent instances to destroy. */
public class CreationalContextImpl {

    private final Object contextual;
    private final List<Object> dependentInstances = new ArrayList<>();

    public CreationalContextImpl(Object contextual) {
        this.contextual = contextual;
    }

    public void addDependentInstance(Object contextualInstance) {
        dependentInstances.add(contextualInstance);
    }

    public Object contextual() {
        return contextual;
    }
}
