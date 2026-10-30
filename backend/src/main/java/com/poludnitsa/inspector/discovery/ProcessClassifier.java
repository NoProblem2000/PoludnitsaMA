package com.poludnitsa.inspector.discovery;

import com.poludnitsa.inspector.api.dto.ProcessType;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Detects WildFly, standalone Hazelcast members and Spring Boot applications. Checks run from the most to the least
 * specific marker, because WildFly and Spring Boot services often carry Hazelcast client properties too.
 */
@Component
public class ProcessClassifier {

    public record Classification(ProcessType type, String displayName) {
    }

    private static final List<String> WILDFLY_COMMAND_MARKERS = List.of(
            "jboss-modules.jar", "org.jboss.modules.Main", "org.jboss.Main", "org.jboss.as.standalone",
            "org.jboss.as.server", "org.jboss.as.host-controller", "org.jboss.as.process-controller");

    private static final List<String> HAZELCAST_MAIN_CLASSES = List.of(
            "com.hazelcast.core.server.HazelcastMemberStarter",
            "com.hazelcast.commandline.HazelcastServerCommandLine",
            "com.hazelcast.commandline.HazelcastCommandLine");

    /** Domain-mode process names: {@code -D[Server:server-one]}, {@code -D[Host Controller]}, ... */
    private static final Pattern DOMAIN_PROCESS = Pattern.compile("^-D\\[(.+)]$");

    public Classification classify(ProcessFacts facts) {
        if (isWildFly(facts)) {
            return new Classification(ProcessType.WILDFLY, wildFlyName(facts));
        }
        String mainClass = facts.mainClass();
        if (mainClass.startsWith("com.hazelcast.") || containsAny(facts.command(), HAZELCAST_MAIN_CLASSES)) {
            return new Classification(ProcessType.HAZELCAST, hazelcastName(facts));
        }
        if (isSpringBoot(facts)) {
            return new Classification(ProcessType.SPRING_BOOT, springBootName(facts));
        }
        if (hasHazelcastMemberProperties(facts)) {
            return new Classification(ProcessType.HAZELCAST, hazelcastName(facts));
        }
        return new Classification(ProcessType.STANDARD_JVM, plainName(facts));
    }

    private static boolean isWildFly(ProcessFacts facts) {
        return facts.property("jboss.home.dir") != null
                || containsAny(facts.command(), WILDFLY_COMMAND_MARKERS)
                || facts.jvmArguments().stream().anyMatch(arg -> arg.startsWith("-Djboss.home.dir="));
    }

    private static boolean isSpringBoot(ProcessFacts facts) {
        if (facts.mainClass().startsWith("org.springframework.boot.loader.")) {
            return true;
        }
        if (facts.manifestAttribute("Spring-Boot-Version") != null || facts.manifestAttribute("Start-Class") != null) {
            return true;
        }
        String classPath = facts.property("java.class.path");
        return classPath != null && classPath.contains("spring-boot-") && classPath.contains(".jar");
    }

    /** {@code hazelcast.*} member settings; {@code hazelcast.client.*} only means the JVM is a client. */
    private static boolean hasHazelcastMemberProperties(ProcessFacts facts) {
        boolean memberProperty = facts.systemProperties().stringPropertyNames().stream()
                .anyMatch(key -> key.startsWith("hazelcast.") && !key.startsWith("hazelcast.client."));
        String classPath = facts.property("java.class.path");
        boolean hazelcastOnClassPath = (classPath != null && classPath.toLowerCase(Locale.ROOT).contains("hazelcast"))
                || facts.command().toLowerCase(Locale.ROOT).contains("hazelcast");
        return memberProperty && hazelcastOnClassPath;
    }

    private static String wildFlyName(ProcessFacts facts) {
        for (String argument : facts.jvmArguments()) {
            Matcher matcher = DOMAIN_PROCESS.matcher(argument);
            if (matcher.matches()) {
                return "WildFly " + matcher.group(1);
            }
        }
        String server = firstNonBlank(facts.property("jboss.server.name"), facts.property("jboss.node.name"));
        String home = facts.property("jboss.home.dir");
        StringBuilder name = new StringBuilder("WildFly");
        if (server != null) {
            name.append(' ').append(server);
        }
        if (home != null) {
            name.append(" (").append(fileName(home)).append(')');
        }
        return name.toString();
    }

    private static String hazelcastName(ProcessFacts facts) {
        String config = firstNonBlank(facts.property("hazelcast.config"), facts.property("hazelcast.config.file"));
        return config != null ? "Hazelcast member (" + fileName(config) + ")" : "Hazelcast member";
    }

    private static String springBootName(ProcessFacts facts) {
        String startClass = facts.manifestAttribute("Start-Class");
        String application = facts.property("spring.application.name");
        if (application != null) {
            return "Spring Boot " + application;
        }
        if (facts.launchedFromJar()) {
            return "Spring Boot " + fileName(facts.launchTarget());
        }
        return "Spring Boot " + simpleName(startClass != null ? startClass.trim() : facts.mainClass());
    }

    private static String plainName(ProcessFacts facts) {
        if (facts.launchedFromJar()) {
            return fileName(facts.launchTarget());
        }
        String main = facts.mainClass();
        return main.isEmpty() ? "java" : simpleName(main);
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static String fileName(String path) {
        try {
            Path fileName = Path.of(path).getFileName();
            return fileName == null ? path : fileName.toString();
        } catch (RuntimeException e) {
            return path;
        }
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
