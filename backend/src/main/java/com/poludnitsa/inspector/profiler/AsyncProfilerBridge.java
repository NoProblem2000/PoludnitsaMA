package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.api.dto.ProfileMetric;
import com.poludnitsa.inspector.api.dto.ProfilerCapabilitiesDto;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * async-profiler (https://github.com/async-profiler/async-profiler) attached to a target by its {@code asprof}
 * launcher: {@code asprof -e cpu|wall|alloc -d <s> -i <interval> -o jfr -f <file> <pid>}. The JFR output is read by
 * the same {@link JfrProfileParser} as Flight Recorder recordings ({@code jdk.ExecutionSample} for cpu,
 * {@code profiler.WallClockSample} for wall, TLAB allocation events for alloc).
 *
 * <p>Linux and macOS only. {@code asprof} attaches like jcmd, so the inspector must run as the user of the target;
 * for the {@code cpu} engine on Linux, {@code perf_events} needs {@code kernel.perf_event_paranoid <= 1} or
 * async-profiler falls back to {@code itimer}.
 */
public final class AsyncProfilerBridge {

    static final String LAUNCHER = "asprof";

    private final InspectorProperties.Profiler props;
    private final Function<String, String> env;
    private final String os;

    public AsyncProfilerBridge(InspectorProperties.Profiler props) {
        this(props, System::getenv, System.getProperty("os.name", ""));
    }

    AsyncProfilerBridge(InspectorProperties.Profiler props, Function<String, String> env, String os) {
        this.props = props;
        this.env = env;
        this.os = os;
    }

    public ProfilerCapabilitiesDto capabilities() {
        String lower = os.toLowerCase(Locale.ROOT);
        if (lower.contains("win")) {
            return new ProfilerCapabilitiesDto(true, false, null,
                    "async-profiler работает только на Linux и macOS; на Windows доступен JFR", os);
        }
        Optional<Path> launcher = locate();
        return launcher.map(path -> new ProfilerCapabilitiesDto(true, true, path.toString(), null, os))
                .orElseGet(() -> new ProfilerCapabilitiesDto(true, false, null,
                        "asprof не найден: укажите inspector.profiler.async-profiler-path, ASYNC_PROFILER_HOME или"
                                + " установите async-profiler в /opt/async-profiler", os));
    }

    /** The launcher: configured path (binary or home), ASYNC_PROFILER_HOME, /opt/async-profiler, then PATH. */
    Optional<Path> locate() {
        List<Path> candidates = new ArrayList<>();
        if (props.asyncProfilerPath() != null && !props.asyncProfilerPath().isBlank()) {
            Path configured = Path.of(props.asyncProfilerPath());
            candidates.add(configured);
            candidates.add(configured.resolve("bin").resolve(LAUNCHER));
        }
        String home = env.apply("ASYNC_PROFILER_HOME");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, "bin", LAUNCHER));
        }
        candidates.add(Path.of("/opt/async-profiler/bin", LAUNCHER));
        String path = env.apply("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    candidates.add(Path.of(dir, LAUNCHER));
                }
            }
        }
        return candidates.stream().filter(p -> Files.isRegularFile(p) && Files.isExecutable(p)).findFirst();
    }

    /** {@code asprof -e <event> -d <seconds> -i <interval> -o jfr -f <file> <pid>}. */
    List<String> command(Path launcher, long pid, ProfileMetric mode, Duration duration, Path output) {
        String event = switch (mode) {
            case CPU -> "cpu";
            case WALL -> "wall";
            case ALLOC -> "alloc";
        };
        List<String> command = new ArrayList<>(List.of(launcher.toString(), "-e", event,
                "-d", Long.toString(Math.max(1, duration.toSeconds()))));
        if (mode != ProfileMetric.ALLOC) {
            command.add("-i");
            command.add(Math.max(1, props.asyncProfilerInterval().toMillis()) + "ms");
        }
        command.addAll(List.of("-o", "jfr", "-f", output.toString(), Long.toString(pid)));
        return command;
    }

    /** Runs a profile to completion (blocks for about {@code duration}). */
    public void profile(long pid, ProfileMetric mode, Duration duration, Path output) throws IOException {
        ProfilerCapabilitiesDto capabilities = capabilities();
        if (!capabilities.asyncProfilerAvailable()) {
            throw new OperationRejectedException(409, "ASYNC_PROFILER_UNAVAILABLE", capabilities.asyncProfilerReason());
        }
        Path launcher = Path.of(capabilities.asyncProfilerPath());
        ProcessBuilder builder = new ProcessBuilder(command(launcher, pid, mode, duration, output))
                .redirectErrorStream(true);
        builder.environment().putAll(Map.of("LC_ALL", "C"));
        Process process = builder.start();
        byte[] log;
        try {
            if (!process.waitFor(duration.toSeconds() + 60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new OperationRejectedException(504, "ASYNC_PROFILER_TIMEOUT",
                        "asprof did not finish " + duration.toSeconds() + " s after the requested duration");
            }
            log = process.getInputStream().readAllBytes();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new OperationRejectedException(503, "INTERRUPTED", "Interrupted while profiling");
        }
        String text = new String(log, StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new OperationRejectedException(502, "ASYNC_PROFILER_FAILED", "asprof exited with "
                    + process.exitValue() + (text.isEmpty() ? "" : ": " + text.substring(0, Math.min(800, text.length()))));
        }
    }
}
