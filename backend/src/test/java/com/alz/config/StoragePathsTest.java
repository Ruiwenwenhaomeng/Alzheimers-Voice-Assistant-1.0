package com.alz.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoragePathsTest {

    @TempDir
    Path tempDirectory;

    @Test
    void resolvesSimpleFilenamesInsideConfiguredDirectories() {
        StoragePaths paths = createPaths();

        assertEquals(tempDirectory.resolve("audio/sample.wav").toAbsolutePath().normalize(),
                paths.resolveAudio("sample.wav"));
    }

    @Test
    void rejectsTraversalAndNestedPaths() {
        StoragePaths paths = createPaths();

        assertThrows(IllegalArgumentException.class, () -> paths.resolveAudio("../secret.wav"));
        assertThrows(IllegalArgumentException.class, () -> paths.resolveAudio("nested/sample.wav"));
        assertThrows(IllegalArgumentException.class, () -> paths.resolveAudio(tempDirectory.resolve("x.wav").toString()));
    }

    private StoragePaths createPaths() {
        return new StoragePaths(
                tempDirectory.resolve("audio").toString(),
                tempDirectory.resolve("pdf").toString(),
                tempDirectory.resolve("admin").toString());
    }
}
