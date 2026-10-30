package com.poludnitsa.inspector.api.rest;

import com.poludnitsa.inspector.api.dto.AboutDto;
import com.poludnitsa.inspector.common.InspectorException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Version, build and license of the inspector. {@link BuildProperties} and {@link GitProperties} exist only in jars
 * built with {@code -Prelease} (Spring Boot reads {@code META-INF/build-info.properties} and {@code git.properties});
 * development builds fall back to the manifest version.
 */
@RestController
@RequestMapping("/api/about")
public class AboutController {

    static final String NAME = "JVM Memory & Leak Inspector";
    static final String LICENSE = "Apache-2.0";
    static final String LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";

    private final Optional<BuildProperties> build;
    private final Optional<GitProperties> git;

    public AboutController(ObjectProvider<BuildProperties> build, ObjectProvider<GitProperties> git) {
        this.build = Optional.ofNullable(build.getIfAvailable());
        this.git = Optional.ofNullable(git.getIfAvailable());
    }

    @GetMapping
    public AboutDto about() {
        String version = build.map(BuildProperties::getVersion)
                .or(() -> Optional.ofNullable(AboutController.class.getPackage().getImplementationVersion()))
                .orElse("dev");
        return new AboutDto(NAME, version,
                build.map(BuildProperties::getTime).orElse(null),
                git.map(GitProperties::getBranch).orElse(null),
                git.map(GitProperties::getShortCommitId).orElse(null),
                git.map(GitProperties::getCommitTime).orElse(null),
                git.map(g -> Boolean.parseBoolean(g.get("dirty"))).orElse(false),
                LICENSE, LICENSE_URL, Runtime.version().toString());
    }

    /** Full license text shipped in the jar. */
    @GetMapping(path = "/license", produces = MediaType.TEXT_PLAIN_VALUE)
    public String license() {
        return legalFile("LICENSE");
    }

    /** Attribution notices of the product and its bundled libraries. */
    @GetMapping(path = "/notice", produces = MediaType.TEXT_PLAIN_VALUE)
    public String notice() {
        return legalFile("NOTICE");
    }

    private static String legalFile(String name) {
        try (InputStream in = AboutController.class.getClassLoader().getResourceAsStream("META-INF/" + name)) {
            if (in == null) {
                throw new InspectorException(404, "LEGAL_FILE_MISSING",
                        "META-INF/" + name + " is not in this build; see " + LICENSE_URL);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InspectorException(500, "LEGAL_FILE_UNREADABLE", "Cannot read META-INF/" + name, e);
        }
    }
}
