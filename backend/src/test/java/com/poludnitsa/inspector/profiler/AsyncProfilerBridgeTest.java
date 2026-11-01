package com.poludnitsa.inspector.profiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.poludnitsa.inspector.api.dto.ProfileMetric;
import com.poludnitsa.inspector.api.dto.ProfilerCapabilitiesDto;
import com.poludnitsa.inspector.config.InspectorProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AsyncProfilerBridgeTest {

    @TempDir
    Path home;

    private static InspectorProperties.Profiler props(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("inspector", InspectorProperties.class)
                .profiler();
    }

    @Test
    void windowsHasNoAsyncProfiler() {
        AsyncProfilerBridge bridge = new AsyncProfilerBridge(props(Map.of()), name -> null, "Windows 11");
        ProfilerCapabilitiesDto capabilities = bridge.capabilities();
        assertThat(capabilities.jfrAvailable()).isTrue();
        assertThat(capabilities.asyncProfilerAvailable()).isFalse();
        assertThat(capabilities.asyncProfilerReason()).contains("Linux");
    }

    @Test
    void launcherIsFoundInTheConfiguredHomeAndBuildsAJfrCommand() throws Exception {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path launcher = Files.writeString(bin.resolve("asprof"), "#!/bin/sh\n");
        assertThat(launcher.toFile().setExecutable(true)).isTrue();
        AsyncProfilerBridge bridge = new AsyncProfilerBridge(
                props(Map.of("inspector.profiler.async-profiler-path", home.toString(),
                        "inspector.profiler.async-profiler-interval", "5ms")), name -> null, "Linux");

        ProfilerCapabilitiesDto capabilities = bridge.capabilities();
        assertThat(capabilities.asyncProfilerAvailable()).isTrue();
        assertThat(capabilities.asyncProfilerPath()).isEqualTo(launcher.toString());

        Path out = home.resolve("p.jfr");
        assertThat(bridge.command(launcher, 4711, ProfileMetric.WALL, Duration.ofSeconds(30), out)).containsExactly(
                launcher.toString(), "-e", "wall", "-d", "30", "-i", "5ms", "-o", "jfr", "-f", out.toString(), "4711");
        assertThat(bridge.command(launcher, 4711, ProfileMetric.ALLOC, Duration.ofSeconds(10), out))
                .contains("alloc").doesNotContain("-i");
    }

    @Test
    void missingLauncherIsExplained() {
        AsyncProfilerBridge bridge = new AsyncProfilerBridge(
                props(Map.of("inspector.profiler.async-profiler-path", home.resolve("missing").toString())),
                name -> null, "Linux");
        assertThat(bridge.capabilities().asyncProfilerAvailable()).isFalse();
        assertThat(bridge.capabilities().asyncProfilerReason()).contains("asprof");
    }
}
