package com.aws.carddemo.util.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Independent, source-derived adversarial unit suite for the centralized safe-path resolver
 * (review findings&nbsp;#18 and&nbsp;#27).
 *
 * <p>{@link BatchFilePathResolver} is the single choke point that hardens every batch file path
 * derived from a job parameter: it canonicalizes {@code ..} segments and symlinks, requires the real
 * path to lie within an allowed root, refuses symlinked output targets, and deliberately renders only
 * the file name in diagnostics so directory layout is never leaked (CWE-532). Before this suite the
 * resolver was only ever constructed for <em>happy-path</em> resolution inside the batch job tests;
 * these tests pin the negative/adversarial behaviour directly &mdash; traversal escape, absolute
 * escape, symlink escape, missing/irregular file, and message non-leakage.</p>
 *
 * <p><strong>Origin / parity:</strong> net-new hardening with no COBOL ancestor (z/OS dataset names
 * are not filesystem paths); it protects the migrated batch file I/O that replaces the JCL DD
 * allocations of the legacy jobs retained read-only under {@code legacy/**}.</p>
 */
@DisplayName("BatchFilePathResolver: safe-root containment and traversal rejection (#18)")
class BatchFilePathResolverTest {

    @TempDir
    Path allowedRoot;

    /** A sibling directory of {@link #allowedRoot} that is deliberately NOT an allowed root. */
    @TempDir
    Path outsideRoot;

    private BatchFilePathResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BatchFilePathResolver(List.of(allowedRoot.toString()));
    }

    private Path writeFile(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(file, "data");
        return file;
    }

    // ---- resolveInputFile: acceptance (positive controls) ----------------------------------

    @Test
    @DisplayName("accepts a regular file inside an allowed root")
    void acceptsRegularFileInsideRoot() throws IOException {
        Path input = writeFile(allowedRoot, "feed.dat");

        Path resolved = resolver.resolveInputFile(input.toString());

        assertThat(resolved).startsWith(allowedRoot.toRealPath());
        assertThat(Files.isRegularFile(resolved)).isTrue();
    }

    @Test
    @DisplayName("accepts a path with '..' segments that normalize back inside the root")
    void acceptsDotDotThatStaysInsideRoot() throws IOException {
        Path input = writeFile(allowedRoot.resolve("sub"), "feed.dat");
        // allowedRoot/sub/../sub/feed.dat -> normalizes to allowedRoot/sub/feed.dat (still inside).
        Path raw = allowedRoot.resolve("sub").resolve("..").resolve("sub").resolve("feed.dat");

        assertThat(resolver.resolveInputFile(raw.toString())).isEqualTo(input.toRealPath());
    }

    // ---- resolveInputFile: rejection (adversarial) -----------------------------------------

    @Test
    @DisplayName("rejects an absolute path pointing outside every allowed root")
    void rejectsAbsolutePathOutsideRoot() throws IOException {
        Path secret = writeFile(outsideRoot, "secret.txt");

        assertThatThrownBy(() -> resolver.resolveInputFile(secret.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside every allowed root");
    }

    @Test
    @DisplayName("rejects a '..' traversal that escapes the allowed root")
    void rejectsDotDotTraversalEscapingRoot() throws IOException {
        writeFile(outsideRoot, "secret.txt");
        // allowedRoot/../<outsideRoot>/secret.txt -> canonicalizes to the sibling outside the root.
        Path raw = allowedRoot.resolve("..").resolve(outsideRoot.getFileName()).resolve("secret.txt");

        assertThatThrownBy(() -> resolver.resolveInputFile(raw.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside every allowed root");
    }

    @Test
    @DisplayName("rejects a symlink whose real target escapes the allowed root")
    void rejectsSymlinkEscapingRoot() throws IOException {
        Path secret = writeFile(outsideRoot, "secret.txt");
        Path link = allowedRoot.resolve("link.dat");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException unsupported) {
            abort("symbolic links are not supported on this filesystem");
        }

        assertThatThrownBy(() -> resolver.resolveInputFile(link.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside every allowed root");
    }

    @Test
    @DisplayName("rejects a blank input path")
    void rejectsBlankInputPath() {
        assertThatThrownBy(() -> resolver.resolveInputFile("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");
    }

    @Test
    @DisplayName("rejects a non-existent file inside the root")
    void rejectsNonExistentFileInsideRoot() {
        Path ghost = allowedRoot.resolve("ghost.dat");

        assertThatThrownBy(() -> resolver.resolveInputFile(ghost.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an existing regular file");
    }

    @Test
    @DisplayName("rejects a directory (only regular files are valid inputs)")
    void rejectsDirectoryAsInput() {
        assertThatThrownBy(() -> resolver.resolveInputFile(allowedRoot.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an existing regular file");
    }

    @Test
    @DisplayName("the rejection message leaks only the file name, never the directory layout (CWE-532)")
    void rejectionMessageDoesNotLeakDirectoryLayout() throws IOException {
        Path secret = writeFile(outsideRoot, "secret.txt");

        Throwable thrown = catchThrowable(() -> resolver.resolveInputFile(secret.toString()));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        assertThat(thrown.getMessage())
                .contains("secret.txt")
                .doesNotContain(outsideRoot.toString());
    }

    // ---- constructor validation ------------------------------------------------------------

    @Test
    @DisplayName("constructor rejects null, empty, or all-blank allowed-root lists")
    void constructorRejectsInvalidRoots() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BatchFilePathResolver(null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BatchFilePathResolver(List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BatchFilePathResolver(List.of("", "   ")))
                .withMessageContaining("allowed root");
    }

    // ---- resolveOutputTarget ---------------------------------------------------------------

    @Test
    @DisplayName("accepts a not-yet-existent output target inside the root and creates its parent")
    void acceptsNewOutputTargetInsideRoot() throws IOException {
        Path raw = allowedRoot.resolve("out").resolve("report.txt");

        Path resolved = resolver.resolveOutputTarget(raw.toString());

        assertThat(Files.isDirectory(allowedRoot.resolve("out"))).isTrue();
        // The output target does not exist yet (only its parent is created), so use the lexical
        // Path.startsWith rather than AssertJ's startsWith, which would call toRealPath on the leaf.
        assertThat(resolved.startsWith(allowedRoot.toRealPath())).isTrue();
        assertThat(resolved.getFileName().toString()).isEqualTo("report.txt");
    }

    @Test
    @DisplayName("rejects an output target whose parent escapes the allowed root")
    void rejectsOutputTargetEscapingRoot() {
        Path raw = allowedRoot.resolve("..").resolve(outsideRoot.getFileName()).resolve("out.dat");

        assertThatThrownBy(() -> resolver.resolveOutputTarget(raw.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside every allowed root");
    }

    @Test
    @DisplayName("rejects an output target that already exists as a symbolic link")
    void rejectsExistingSymlinkOutputTarget() throws IOException {
        Path real = writeFile(allowedRoot, "real-output.dat");
        Path link = allowedRoot.resolve("output-link.dat");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException unsupported) {
            abort("symbolic links are not supported on this filesystem");
        }

        assertThatThrownBy(() -> resolver.resolveOutputTarget(link.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be a symbolic link");
    }
}
