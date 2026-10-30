package com.poludnitsa.inspector.api.dto;

/** Coarse origin of a class, derived from its package name. Drives colouring and inspection routing in the UI. */
public enum PackageCategory {
    /** {@code java.*}, {@code jdk.*}, {@code sun.*}, {@code com.sun.*}. */
    JDK,
    /** {@code jakarta.*} APIs. */
    JAKARTA_EE,
    /** WildFly / JBoss runtime: {@code org.jboss.*}, {@code org.wildfly.*}, {@code io.undertow.*}, {@code org.hibernate.*}, {@code org.infinispan.*}. */
    WILDFLY,
    /** {@code com.hazelcast.*}. */
    HAZELCAST,
    /** Other libraries recognised as third party. */
    THIRD_PARTY,
    /** Application code of the inspected microservice. */
    APPLICATION,
    /** Primitive arrays and classes without a package. */
    PRIMITIVE_OR_ARRAY
}
