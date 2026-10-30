package com.poludnitsa.inspector.api.dto;

import java.time.Instant;

/**
 * Version and license of the running inspector, for the About dialog.
 *
 * @param name           product name
 * @param version        project version; from {@code META-INF/build-info.properties} (release builds), else the jar
 *                       manifest, else {@code "dev"}
 * @param buildTime      release builds only, else {@code null}
 * @param gitBranch      from {@code git.properties} (release builds only), else {@code null}
 * @param gitCommitId    abbreviated commit id, or {@code null}
 * @param gitCommitTime  commit time, or {@code null}
 * @param gitDirty       the build had uncommitted changes
 * @param license        SPDX id of the product license
 * @param licenseUrl     canonical license text
 * @param runtimeJava    Java version of the analyzer JVM itself
 */
public record AboutDto(
        String name,
        String version,
        Instant buildTime,
        String gitBranch,
        String gitCommitId,
        Instant gitCommitTime,
        boolean gitDirty,
        String license,
        String licenseUrl,
        String runtimeJava
) {
}
