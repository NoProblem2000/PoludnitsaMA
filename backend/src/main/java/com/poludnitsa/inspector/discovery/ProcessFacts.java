package com.poludnitsa.inspector.discovery;

import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Everything known about a JVM before (or without) a JMX connection.
 *
 * @param systemProperties target system properties; empty when the target could not be attached
 * @param command          {@code sun.java.command}: main class or jar followed by program arguments
 * @param jvmArguments     JVM input arguments ({@code sun.jvm.args} / {@code RuntimeMXBean#getInputArguments()})
 * @param manifest         manifest of the launched jar, {@code null} when not launched with {@code -jar} or unreadable
 */
public record ProcessFacts(
        Properties systemProperties,
        String command,
        List<String> jvmArguments,
        Manifest manifest
) {
    public ProcessFacts {
        systemProperties = systemProperties == null ? new Properties() : systemProperties;
        command = command == null ? "" : command.trim();
        jvmArguments = jvmArguments == null ? List.of() : List.copyOf(jvmArguments);
    }

    /** First token of the command: the main class, or the jar path for {@code java -jar}. */
    public String launchTarget() {
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    public boolean launchedFromJar() {
        return launchTarget().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    public String manifestAttribute(String name) {
        if (manifest == null) {
            return null;
        }
        Attributes attributes = manifest.getMainAttributes();
        return attributes == null ? null : attributes.getValue(name);
    }

    /** Main class from the manifest for jar launches, otherwise the first command token. */
    public String mainClass() {
        if (launchedFromJar()) {
            String fromManifest = manifestAttribute("Main-Class");
            return fromManifest != null ? fromManifest.trim() : "";
        }
        return launchTarget();
    }

    public String property(String key) {
        return systemProperties.getProperty(key);
    }
}
