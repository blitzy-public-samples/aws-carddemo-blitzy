package com.carddemo.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link BatchOutputPathResolver}.
 *
 * <p>These tests pin the CP4 path-traversal contract shared by statement and report batch jobs:
 * callers may request only relative subdirectories under the configured base output directory; null
 * or blank input resolves to the base itself; absolute paths and parent-directory traversal are
 * rejected before any filesystem writes occur.</p>
 */
@DisplayName("BatchOutputPathResolver")
class BatchOutputPathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("null and blank requested paths resolve to the normalized base directory")
    void resolvesNullAndBlankToBaseDirectory() {
        BatchOutputPathResolver resolver = new BatchOutputPathResolver(tempDir.toString());
        Path expectedBase = tempDir.toAbsolutePath().normalize();

        assertThat(resolver.resolve(null)).isEqualTo(expectedBase);
        assertThat(resolver.resolve("   ")).isEqualTo(expectedBase);
        assertThat(resolver.getBaseDir()).isEqualTo(expectedBase);
    }

    @Test
    @DisplayName("relative requested paths are normalized beneath the configured base directory")
    void resolvesRelativePathsUnderBaseDirectory() {
        BatchOutputPathResolver resolver = new BatchOutputPathResolver(tempDir.toString());

        Path resolved = resolver.resolve("statements/2022-07");

        assertThat(resolved).isEqualTo(tempDir.resolve("statements/2022-07").toAbsolutePath().normalize());
        assertThat(resolved.startsWith(resolver.getBaseDir())).isTrue();
    }

    @Test
    @DisplayName("absolute requested paths are rejected")
    void rejectsAbsolutePaths() {
        BatchOutputPathResolver resolver = new BatchOutputPathResolver(tempDir.toString());
        Path absoluteRequest = tempDir.resolve("outside").toAbsolutePath();

        assertThatThrownBy(() -> resolver.resolve(absoluteRequest.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute paths are not permitted");
    }

    @Test
    @DisplayName("parent-directory traversal is rejected even if normalization would stay inside base")
    void rejectsParentTraversalSegments() {
        BatchOutputPathResolver resolver = new BatchOutputPathResolver(tempDir.toString());

        assertThatThrownBy(() -> resolver.resolve("../etc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent-directory traversal");

        assertThatThrownBy(() -> resolver.resolve("reports/../statements"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent-directory traversal");
    }
}