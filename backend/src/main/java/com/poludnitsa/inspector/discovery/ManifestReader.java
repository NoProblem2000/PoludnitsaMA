package com.poludnitsa.inspector.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/** Reads the manifest of a jar launched with {@code java -jar} on this host. */
final class ManifestReader {

    private ManifestReader() {
    }

    /**
     * @param jarPath    path as written in {@code sun.java.command}, possibly relative
     * @param workingDir {@code user.dir} of the target, used to resolve relative paths
     */
    static Manifest read(String jarPath, String workingDir) {
        try {
            Path path = Path.of(jarPath);
            if (!path.isAbsolute() && workingDir != null) {
                path = Path.of(workingDir).resolve(path);
            }
            if (!Files.isRegularFile(path)) {
                return null;
            }
            try (JarFile jar = new JarFile(path.toFile(), false)) {
                return jar.getManifest();
            }
        } catch (IOException | InvalidPathException | SecurityException e) {
            return null;
        }
    }
}
