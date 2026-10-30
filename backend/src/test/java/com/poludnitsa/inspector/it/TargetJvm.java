package com.poludnitsa.inspector.it;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Starts {@link LeakyTarget} in a separate JVM so tests attach to a real foreign process, never to themselves. */
final class TargetJvm implements AutoCloseable {

    private final Process process;

    private TargetJvm(Process process) {
        this.process = process;
    }

    static TargetJvm start() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(LeakyTarget.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        Process process = new ProcessBuilder(java, "-Xmx256m", "-XX:+UseG1GC", "-cp", classes,
                LeakyTarget.class.getName())
                .redirectErrorStream(true)
                .start();
        BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        String line = CompletableFuture.supplyAsync(() -> {
            try {
                return output.readLine();
            } catch (IOException e) {
                return null;
            }
        }).get(30, TimeUnit.SECONDS);
        if (!"READY".equals(line)) {
            process.destroyForcibly();
            throw new IllegalStateException("Target JVM did not start: " + line);
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
