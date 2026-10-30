package com.poludnitsa.inspector.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DumpFileCleanerTest {

    @TempDir
    Path workDir;

    @Test
    void deletesDumpAndNetBeansIndexDirectory() throws Exception {
        DumpFileCleaner cleaner = new DumpFileCleaner(SnapshotTestData.properties(workDir));
        Path dir = cleaner.createWorkDir("20261030-101500-4711-abcd");
        Files.write(dir.resolve("heap.hprof"), new byte[1024]);
        Path index = Files.createDirectories(dir.resolve("heap.hprof.nbcache"));
        Files.write(index.resolve("NBProfiler123.map"), new byte[64]);
        Files.write(index.resolve("NBProfiler.nphd"), new byte[64]);

        assertThat(cleaner.pending()).contains(dir);
        assertThat(cleaner.deleteNow(dir)).isTrue();

        assertThat(dir).doesNotExist();
        assertThat(cleaner.pending()).isEmpty();
    }

    @Test
    void leftoversOfAKilledInspectorAreRemovedAtStart() throws Exception {
        Path leftover = Files.createDirectories(workDir.resolve("work").resolve("20261029-000000-1-ffff"));
        Files.write(leftover.resolve("heap.hprof"), new byte[128]);

        DumpFileCleaner cleaner = new DumpFileCleaner(SnapshotTestData.properties(workDir));

        assertThat(leftover).doesNotExist();
        assertThat(cleaner.workRoot()).exists();
    }

    @Test
    void shutdownDeletesDirectoriesStillInUse() throws Exception {
        DumpFileCleaner cleaner = new DumpFileCleaner(SnapshotTestData.properties(workDir));
        Path dir = cleaner.createWorkDir("20261030-101500-4711-0001");
        Files.write(dir.resolve("heap.hprof"), new byte[16]);

        cleaner.destroy();

        assertThat(dir).doesNotExist();
    }

    @Test
    void rejectsIdsEscapingTheWorkRoot() {
        DumpFileCleaner cleaner = new DumpFileCleaner(SnapshotTestData.properties(workDir));

        assertThatThrownBy(() -> cleaner.createWorkDir("../outside")).isInstanceOf(IllegalArgumentException.class);
    }
}
