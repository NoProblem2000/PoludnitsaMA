package com.poludnitsa.inspector.api.dto;

/** Kind of target JVM, detected from system properties, the launch command and the jar manifest. */
public enum ProcessType {
    /** {@code jboss.home.dir}, {@code jboss-modules.jar}, {@code org.jboss.Main} / {@code org.jboss.as.*}. */
    WILDFLY,
    /** Standalone Hazelcast member: {@code com.hazelcast.*} main class or member {@code hazelcast.*} properties. */
    HAZELCAST,
    /** Spring Boot launcher, manifest {@code Start-Class} or {@code spring-boot-*.jar} on the class path. */
    SPRING_BOOT,
    STANDARD_JVM
}
