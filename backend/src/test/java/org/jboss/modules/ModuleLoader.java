package org.jboss.modules;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Test double of jboss-modules {@code ModuleLoader}: {@code moduleMap} of {@code FutureModule}s. */
public class ModuleLoader {

    private final ConcurrentMap<String, FutureModule> moduleMap = new ConcurrentHashMap<>();

    public void register(Module module) {
        moduleMap.put(module.getName(), new FutureModule(module));
    }

    public void unregister(Module module) {
        moduleMap.remove(module.getName());
    }

    static final class FutureModule {
        volatile Object module;

        FutureModule(Module module) {
            this.module = module;
        }
    }
}
