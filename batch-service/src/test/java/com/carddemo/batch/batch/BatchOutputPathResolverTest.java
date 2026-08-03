/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.batch.batch;

import com.carddemo.common.batch.BatchOutputPathResolver;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for :class:`BatchOutputPathResolver`.
 *
 * :purpose: Verify the path-confinement control that keeps every batch reader and
 *     writer inside its configured root (CWE-22): a relative name resolves under
 *     the root, an absolute path is accepted only when it already lies inside the
 *     root, and a blank name, a ``..`` traversal, an absolute path outside the
 *     root or a symlinked parent that leaves the root are all rejected before any
 *     file is opened. The output and input roots are independent, so a file may
 *     not be written through the input root or read through the output root.
 * :output: JUnit 5 / AssertJ assertions over real directories in a JUnit temporary
 *     directory; no Spring context and no database.
 */
class BatchOutputPathResolverTest {

    @TempDir
    private Path tempDir;

    private Path outputRoot;

    private Path inputRoot;

    private BatchOutputPathResolver resolver;

    @BeforeEach
    void createResolver() throws IOException {
        outputRoot = Files.createDirectories(tempDir.resolve("batch-output"));
        inputRoot = Files.createDirectories(tempDir.resolve("batch-input"));
        resolver = new BatchOutputPathResolver(outputRoot.toString(), inputRoot.toString());
    }

    @Nested
    @DisplayName("Accepted requests")
    class Accepted {

        @Test
        @DisplayName("a relative output name resolves under the output root")
        void relativeOutputNameResolvesUnderTheRoot() throws IOException {
            Path resolved = resolver.resolveOutput("acctdump.txt");

            assertThat(resolved).isAbsolute();
            assertThat(resolved.getParent()).isEqualTo(outputRoot.toRealPath());
            assertThat(resolved.getFileName()).hasToString("acctdump.txt");
        }

        @Test
        @DisplayName("a nested relative output name has its parent directory created")
        void nestedOutputNameCreatesItsParent() {
            Path resolved = resolver.resolveOutput("reports/2024/dalyrept.txt");

            assertThat(resolved.getParent()).exists().isDirectory();
            assertThat(resolved.toString()).startsWith(outputRoot.toString());
        }

        @Test
        @DisplayName("an absolute output path inside the root is accepted unchanged")
        void absolutePathInsideTheRootIsAccepted() {
            Path requested = outputRoot.resolve("combined.txt");

            assertThat(resolver.resolveOutput(requested.toString()))
                    .isEqualTo(requested.getParent().resolve("combined.txt"));
        }

        @Test
        @DisplayName("a relative input name resolves under the input root, without creating it")
        void relativeInputNameResolvesUnderTheInputRoot() {
            Path resolved = resolver.resolveInput("dailytran.txt");

            assertThat(resolved.toString()).startsWith(inputRoot.toString());
            assertThat(resolved).doesNotExist();
        }

        @Test
        @DisplayName("the two roots are independent: the same name resolves to different files")
        void theTwoRootsAreIndependent() {
            assertThat(resolver.resolveOutput("shared.txt"))
                    .isNotEqualTo(resolver.resolveInput("shared.txt"));
            assertThat(resolver.resolveOutput("shared.txt").toString()).startsWith(outputRoot.toString());
            assertThat(resolver.resolveInput("shared.txt").toString()).startsWith(inputRoot.toString());
        }

        @Test
        @DisplayName("a blank configured root defaults under the JVM temporary directory")
        void blankConfiguredRootFallsBackToTheTempDirectory() {
            BatchOutputPathResolver defaulted = new BatchOutputPathResolver("", "  ");

            assertThat(defaulted.resolveOutput("defaulted.txt").toString())
                    .contains("carddemo-batch");
            assertThat(defaulted.resolveInput("defaulted.txt").toString())
                    .contains("carddemo-batch");
        }
    }

    @Nested
    @DisplayName("Rejected requests")
    class Rejected {

        @ParameterizedTest
        @DisplayName("a blank request is rejected")
        @ValueSource(strings = {"", " ", "   "})
        void blankRequestIsRejected(String requested) {
            assertThatThrownBy(() -> resolver.resolveOutput(requested))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
            assertThatThrownBy(() -> resolver.resolveInput(requested))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("a null request is rejected")
        void nullRequestIsRejected() {
            assertThatThrownBy(() -> resolver.resolveOutput(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        }

        @ParameterizedTest
        @DisplayName("a relative traversal that escapes the root is rejected")
        @ValueSource(strings = {
                "../escaped.txt",
                "../../escaped.txt",
                "reports/../../escaped.txt",
                "./../escaped.txt"
        })
        void traversalIsRejected(String requested) {
            assertThatThrownBy(() -> resolver.resolveOutput(requested))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("escapes the allowed directory");
        }

        @Test
        @DisplayName("an absolute path outside the root is rejected")
        void absolutePathOutsideTheRootIsRejected() {
            Path outside = tempDir.resolve("outside.txt");

            assertThatThrownBy(() -> resolver.resolveOutput(outside.toString()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("escapes the allowed directory");
        }

        @Test
        @DisplayName("an output path may not be requested through the input root, and vice versa")
        void crossRootRequestsAreRejected() {
            assertThatThrownBy(() -> resolver.resolveOutput(inputRoot.resolve("x.txt").toString()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> resolver.resolveInput(outputRoot.resolve("x.txt").toString()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a symlinked sub-directory that leaves the root is rejected")
        void symlinkedParentLeavingTheRootIsRejected() throws IOException {
            Path escapeTarget = Files.createDirectories(tempDir.resolve("escape-target"));
            Path link = outputRoot.resolve("linked");
            try {
                Files.createSymbolicLink(link, escapeTarget);
            } catch (UnsupportedOperationException | IOException unsupported) {
                // Symlink creation is unavailable on this filesystem; the containment
                // rule is still covered by the traversal and absolute-path scenarios.
                return;
            }

            assertThatThrownBy(() -> resolver.resolveOutput("linked/escaped.txt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resolves outside the allowed directory");
        }
    }
}
