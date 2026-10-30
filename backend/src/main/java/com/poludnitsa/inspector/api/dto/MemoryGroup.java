package com.poludnitsa.inspector.api.dto;

/** Coarse ownership of memory, used for the snapshot breakdown. */
public enum MemoryGroup {
    /** Classes of the inspected microservice. */
    USER_BUSINESS_CODE,
    /** {@code jakarta.*}, {@code org.jboss.*}, {@code org.wildfly.*}, {@code io.undertow.*}, {@code org.hibernate.*}, ... */
    JAKARTA_WILDFLY_INTERNAL,
    /** {@code com.hazelcast.*}. */
    HAZELCAST_CLIENT,
    /** JDK classes and primitive arrays ({@code byte[]} usually holds the payload of strings and buffers). */
    JDK_CORE,
    /** Other libraries: Spring, Jackson, Netty, JDBC drivers, ... */
    THIRD_PARTY_LIBRARY;

    public static MemoryGroup of(PackageCategory category) {
        return switch (category) {
            case APPLICATION -> USER_BUSINESS_CODE;
            case JAKARTA_EE, WILDFLY -> JAKARTA_WILDFLY_INTERNAL;
            case HAZELCAST -> HAZELCAST_CLIENT;
            case JDK, PRIMITIVE_OR_ARRAY -> JDK_CORE;
            case THIRD_PARTY -> THIRD_PARTY_LIBRARY;
        };
    }
}
