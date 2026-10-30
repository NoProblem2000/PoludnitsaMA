package com.poludnitsa.inspector.it;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Starts a target main class in a separate JVM so tests attach to a real foreign process, never to themselves. */
final class TargetJvm implements AutoCloseable {

    private final Process process;

    private TargetJvm(Process process) {
        this.process = process;
    }

    static TargetJvm start() throws Exception {
        return start(LeakyTarget.class, 256);
    }

    /**
     * @param extraClasspath classes whose jars or directories go on the child's class path besides test classes
     */
    static TargetJvm start(Class<?> mainClass, int heapMb, Class<?>... extraClasspath) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> classpath = new ArrayList<>();
        classpath.add(location(mainClass));
        for (Class<?> type : extraClasspath) {
            classpath.add(location(type));
        }
        Process process = new ProcessBuilder(java, "-Xmx" + heapMb + "m", "-XX:+UseG1GC",
                "-Dhazelcast.phone.home.enabled=false", "-Dhazelcast.logging.type=none",
                "-cp", String.join(File.pathSeparator, classpath), mainClass.getName())
                .redirectErrorStream(true)
                .start();
        BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        List<String> lines = new ArrayList<>();
        String ready = CompletableFuture.supplyAsync(() -> {
            try {
                String line;
                while ((line = output.readLine()) != null) {
                    if (line.equals("READY")) {
                        return line;
                    }
                    lines.add(line);
                }
                return null;
            } catch (IOException e) {
                return null;
            }
        }).get(120, TimeUnit.SECONDS);
        if (ready == null) {
            process.destroyForcibly();
            throw new IllegalStateException("Target JVM did not start: " + String.join("\n", lines));
        }
        // Keep draining output so the child never blocks on a full pipe.
        Thread drain = new Thread(() -> {
            try {
                while (output.readLine() != null) {
                    // discard
                }
            } catch (IOException ignored) {
                // child exited
            }
        }, "target-jvm-output");
        drain.setDaemon(true);
        drain.start();
        return new TargetJvm(process);
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    long pid() {
        return process.pid();
    }

    @Override
    public void close() {
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
