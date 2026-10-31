package com.poludnitsa.inspector.api.dto;

public enum ClassLoaderKind {
    /** classes without a loader instance (java.base and other boot modules) */
    BOOTSTRAP,
    /** {@code jdk.internal.loader.ClassLoaders$PlatformClassLoader} */
    PLATFORM,
    /** {@code jdk.internal.loader.ClassLoaders$AppClassLoader}: the class path */
    APPLICATION,
    /** {@code org.jboss.modules.ModuleClassLoader}: a WildFly module or deployment */
    JBOSS_MODULE,
    OTHER
}
