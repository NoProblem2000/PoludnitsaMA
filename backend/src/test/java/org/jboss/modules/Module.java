package org.jboss.modules;

/** Test double with the field layout of jboss-modules 2.x {@code Module} (only what the inspector reads). */
public final class Module {

    private final String name;

    public Module(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
