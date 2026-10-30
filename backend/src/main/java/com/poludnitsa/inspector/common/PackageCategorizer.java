package com.poludnitsa.inspector.common;

import com.poludnitsa.inspector.api.dto.PackageCategory;
import java.util.List;
import java.util.Set;

/** Maps a source-like class name ({@link JvmTypeNames#toJavaName}) to its {@link PackageCategory}. */
public final class PackageCategorizer {

    private static final Set<String> PRIMITIVES =
            Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double");

    private static final List<String> JDK = List.of(
            "java.", "javax.management.", "javax.naming.", "javax.net.", "javax.security.", "javax.crypto.",
            "javax.xml.", "javax.script.", "javax.sql.", "javax.tools.", "javax.lang.", "javax.annotation.processing.",
            "jdk.", "sun.", "com.sun.crypto.", "com.sun.jndi.", "com.sun.management.", "com.sun.net.",
            "com.sun.org.apache.", "com.sun.proxy.", "com.sun.tools.", "com.sun.jmx.", "org.w3c.", "org.xml.",
            "org.ietf.");

    private static final List<String> WILDFLY = List.of(
            "org.jboss.", "org.wildfly.", "io.undertow.", "org.hibernate.", "org.infinispan.", "org.jgroups.",
            "io.smallrye.", "org.eclipse.microprofile.", "org.apache.activemq.artemis.", "org.jberet.",
            "org.apache.cxf.", "org.eclipse.yasson.", "org.glassfish.expressly.", "com.sun.faces.",
            "org.picketbox.", "org.xnio.", "org.apache.santuario.", "org.keycloak.");

    private static final List<String> THIRD_PARTY = List.of(
            "org.springframework.", "org.apache.", "com.fasterxml.", "io.netty.", "com.google.", "org.slf4j.",
            "ch.qos.", "io.micrometer.", "org.yaml.", "kotlin.", "kotlinx.", "scala.", "reactor.", "net.bytebuddy.",
            "org.objectweb.", "com.zaxxer.", "org.postgresql.", "oracle.", "com.mysql.", "org.mariadb.", "org.h2.",
            "org.eclipse.", "org.glassfish.", "io.grpc.", "io.opentelemetry.", "org.bouncycastle.", "com.squareup.",
            "okhttp3.", "okio.", "org.json.", "org.joda.", "org.aspectj.", "io.prometheus.", "org.quartz.",
            "com.github.benmanes.", "io.vertx.", "io.reactivex.", "com.ctc.wstx.", "org.codehaus.", "org.antlr.",
            "javassist.", "org.javassist.", "org.mapstruct.", "lombok.", "io.swagger.", "com.thoughtworks.");

    private PackageCategorizer() {
    }

    public static PackageCategory categorize(String className) {
        if (className == null || className.isEmpty()) {
            return PackageCategory.PRIMITIVE_OR_ARRAY;
        }
        String element = className;
        while (element.endsWith("[]")) {
            element = element.substring(0, element.length() - 2);
        }
        if (PRIMITIVES.contains(element) || element.indexOf('.') < 0) {
            return PackageCategory.PRIMITIVE_OR_ARRAY;
        }
        if (element.startsWith("com.hazelcast.")) {
            return PackageCategory.HAZELCAST;
        }
        if (element.startsWith("jakarta.")) {
            return PackageCategory.JAKARTA_EE;
        }
        if (startsWithAny(element, WILDFLY)) {
            return PackageCategory.WILDFLY;
        }
        if (startsWithAny(element, JDK)) {
            return PackageCategory.JDK;
        }
        if (startsWithAny(element, THIRD_PARTY)) {
            return PackageCategory.THIRD_PARTY;
        }
        return PackageCategory.APPLICATION;
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
