/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.common.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * :purpose: Verify the shared batch path resolver on both of the behaviours the
 *  CardDemo batch tier depends on: it materializes a usable, writable output root
 *  at construction (QA Issue 21, the defect that failed every statement run with
 *  ``No such file or directory``), and it confines every resolved file to that
 *  root (QA MJ-08 / CWE-22).
 * :output: Assertions over {@link BatchOutputPathResolver#resolveOutput(String)}
 *  and {@link BatchOutputPathResolver#resolveInput(String)}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BatchOutputPathResolverTest {

    /**
     * :purpose: A relative file name resolves inside the configured output root and
     *  its parent directory is created, so a flat-file writer can open it. This is
     *  the exact path the statement and report writers take.
     * :param tempDir: JUnit-managed directory standing in for the mounted batch root.
     */
    @Test
    @DisplayName("a relative name resolves inside the output root and the directory is created")
    void relativeNameResolvesInsideOutputRoot(@TempDir Path tempDir) throws Exception {
        Path outputRoot = tempDir.resolve("output");
        Path inputRoot = tempDir.resolve("input");
        BatchOutputPathResolver resolver =
                new BatchOutputPathResolver(outputRoot.toString(), inputRoot.toString());

        Path resolved = resolver.resolveOutput("statements.txt");

        // Compared as text rather than with AssertJ's startsWith(Path), which
        // canonicalizes the actual value and therefore requires the file to exist;
        // the resolver deliberately creates only the parent directory.
        assertThat(resolved.toString()).startsWith(outputRoot.toRealPath().toString());
        assertThat(resolved.getFileName()).hasToString("statements.txt");
        assertThat(resolved.getParent()).isDirectory();
    }

    /**
     * :purpose: Both roots are created while the resolver is constructed, so a
     *  container started with an unmounted batch directory surfaces the problem at
     *  startup rather than in the middle of a job.
     * :param tempDir: JUnit-managed directory standing in for the mounted batch root.
     */
    @Test
    @DisplayName("both roots are created eagerly at construction")
    void bothRootsAreCreatedAtConstruction(@TempDir Path tempDir) {
        Path outputRoot = tempDir.resolve("nested/output");
        Path inputRoot = tempDir.resolve("nested/input");
        assertThat(outputRoot).doesNotExist();

        new BatchOutputPathResolver(outputRoot.toString(), inputRoot.toString());

        assertThat(outputRoot).isDirectory();
        assertThat(inputRoot).isDirectory();
    }

    /**
     * :purpose: A blank configuration falls back to the JVM temporary directory, so
     *  the jobs stay runnable on a workstation and in a container whose only
     *  writable mount is ``/tmp``.
     */
    @Test
    @DisplayName("a blank configuration falls back under the JVM temporary directory")
    void blankConfigurationFallsBackToTempDirectory() {
        BatchOutputPathResolver resolver = new BatchOutputPathResolver("", "");

        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();

        assertThat(resolver.resolveOutput("report.txt").toString())
                .contains("carddemo-batch")
                .startsWith(tmpRoot.toString());
        assertThat(resolver.resolveInput("dailytran.txt").toString())
                .contains("carddemo-batch")
                .startsWith(tmpRoot.toString());
    }

    /**
     * :purpose: A traversal escape is rejected rather than opened (CWE-22).
     * :param tempDir: JUnit-managed directory standing in for the mounted batch root.
     */
    @Test
    @DisplayName("a '..' traversal escaping the root is rejected")
    void traversalEscapeIsRejected(@TempDir Path tempDir) {
        BatchOutputPathResolver resolver = new BatchOutputPathResolver(
                tempDir.resolve("output").toString(), tempDir.resolve("input").toString());

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> resolver.resolveOutput("../escaped.txt"))
                .withMessageContaining("escapes the allowed directory");
    }

    /**
     * :purpose: An absolute path outside the root is rejected; one already inside it
     *  is accepted, so an operator may name a full path as long as it stays confined.
     * :param tempDir: JUnit-managed directory standing in for the mounted batch root.
     */
    @Test
    @DisplayName("an absolute path is accepted only when it already lies inside the root")
    void absolutePathIsConfinedToTheRoot(@TempDir Path tempDir) throws Exception {
        Path outputRoot = tempDir.resolve("output");
        BatchOutputPathResolver resolver =
                new BatchOutputPathResolver(outputRoot.toString(), tempDir.resolve("input").toString());
        Path realOutputRoot = outputRoot.toRealPath();

        assertThat(resolver.resolveOutput(realOutputRoot.resolve("inside.txt").toString()))
                .isEqualTo(realOutputRoot.resolve("inside.txt"));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> resolver.resolveOutput(tempDir.resolve("outside.txt").toString()))
                .withMessageContaining("escapes the allowed directory");
    }

    /**
     * :purpose: A blank request names no file and is rejected. Before the launch
     *  surface supplied its defaults, six of the nine ``batch-service`` jobs passed
     *  no file parameter at all and reached exactly this guard, failing at step
     *  start (QA Issue 14/21).
     * :param tempDir: JUnit-managed directory standing in for the mounted batch root.
     */
    @Test
    @DisplayName("a blank or null file name is rejected")
    void blankRequestIsRejected(@TempDir Path tempDir) {
        BatchOutputPathResolver resolver = new BatchOutputPathResolver(
                tempDir.resolve("output").toString(), tempDir.resolve("input").toString());

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> resolver.resolveOutput("   "))
                .withMessageContaining("must not be blank");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> resolver.resolveInput(null))
                .withMessageContaining("must not be blank");
    }

    /**
     * :purpose: A non-writable output root aborts construction with a message that
     *  names the directory and the property to set, so the operator is told exactly
     *  what to mount instead of discovering it from a failed job.
     * :param tempDir: JUnit-managed directory standing in for the mounted batch root.
     */
    @Test
    @DisplayName("a non-writable output root aborts construction with an actionable message")
    void nonWritableOutputRootAbortsConstruction(@TempDir Path tempDir) throws Exception {
        Path outputRoot = Files.createDirectory(tempDir.resolve("readonly-output"));
        Path inputRoot = tempDir.resolve("input");
        boolean madeReadOnly = outputRoot.toFile().setWritable(false, false);
        // Running as root defeats the permission bit; the containment behaviour above
        // is what matters in that case, so only assert when the bit actually took.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                madeReadOnly && !Files.isWritable(outputRoot),
                "filesystem permissions are not enforced for this user");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new BatchOutputPathResolver(outputRoot.toString(), inputRoot.toString()))
                .withMessageContaining("Batch output directory is not writable")
                .withMessageContaining("CARDDEMO_BATCH_OUTPUT_DIR");

        outputRoot.toFile().setWritable(true, false);
    }

    /**
     * :purpose: The input root may legitimately be mounted read-only (the feed file
     *  is delivered by an upstream process), so it must NOT be writability-checked.
     * :param tempDir: JUnit-managed directory standing in for the mounted batch root.
     */
    @Test
    @DisplayName("a read-only input root is accepted")
    void readOnlyInputRootIsAccepted(@TempDir Path tempDir) throws Exception {
        Path inputRoot = Files.createDirectory(tempDir.resolve("readonly-input"));
        Path feed = Files.createFile(inputRoot.resolve("dailytran.txt"));
        inputRoot.toFile().setWritable(false, false);

        BatchOutputPathResolver resolver = new BatchOutputPathResolver(
                tempDir.resolve("output").toString(), inputRoot.toString());

        assertThat(resolver.resolveInput("dailytran.txt")).isEqualTo(feed.toRealPath());

        inputRoot.toFile().setWritable(true, false);
    }
}
