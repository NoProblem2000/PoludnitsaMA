package org.jboss.modules;

/** Test double of jboss-modules {@code ModuleClassLoader}: a real class loader with a {@code module} field. */
public class ModuleClassLoader extends ClassLoader {

    private final Module module;

    public ModuleClassLoader(Module module, ClassLoader parent) {
        super(parent);
        this.module = module;
    }

    public Class<?> define(String name, byte[] bytes) {
        return defineClass(name, bytes, 0, bytes.length);
    }

    public Module getModule() {
        return module;
    }
}
