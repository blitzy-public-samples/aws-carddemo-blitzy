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
package com.carddemo.reporting.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Unit tests for {@link StatementOutputResolver}, the component that
 *  makes the statement-generation output destination configuration rather than a
 *  hard-coded relative directory and proves it is writable before a report
 *  request is accepted.
 * :output: Verifies the configured root is honoured, the temporary-directory
 *  default applies when the property is blank, the default document names match
 *  the legacy ``STMTFILE``/``HTMLFILE`` documents, an explicit job-parameter path
 *  wins, the parent directory is created so the flat-file writers can open the
 *  documents, and an unusable root is reported as not writable.
 */
class StatementOutputResolverTest {

    /** :purpose: Writable statement output root for the scenario. */
    @TempDir
    Path outputDir;

    /**
     * :purpose: The configured root and the legacy document names determine the
     *  resolved paths.
     */
    @Test
    @DisplayName("configured root yields statements.txt and statements.html inside it")
    void configuredRootResolvesDefaultDocuments() {
        StatementOutputResolver resolver = new StatementOutputResolver(outputDir.toString());

        assertThat(resolver.resolveText(null)).isEqualTo(outputDir.resolve("statements.txt"));
        assertThat(resolver.resolveHtml(null)).isEqualTo(outputDir.resolve("statements.html"));
        assertThat(resolver.outputRoot()).isEqualTo(outputDir);
    }

    /**
     * :purpose: A blank property resolves under the JVM temporary directory, which
     *  is the writable mount of the hardened container image, so no extra
     *  configuration is required for the job to run.
     */
    @Test
    @DisplayName("blank output-dir falls back to <java.io.tmpdir>/carddemo-reporting/output")
    void blankConfigurationFallsBackToTempDirectory() {
        StatementOutputResolver resolver = new StatementOutputResolver("  ");

        Path expected = Paths.get(System.getProperty("java.io.tmpdir"), "carddemo-reporting", "output")
                .toAbsolutePath().normalize();
        assertThat(resolver.outputRoot()).isEqualTo(expected);
    }

    /**
     * :purpose: An explicit job-parameter path is honoured, and a relative one is
     *  resolved beneath the configured root.
     */
    @Test
    @DisplayName("explicit job-parameter paths are honoured, relative ones resolve under the root")
    void explicitPathsAreHonoured() {
        StatementOutputResolver resolver = new StatementOutputResolver(outputDir.toString());
        Path absolute = outputDir.resolve("custom").resolve("run.txt");

        assertThat(resolver.resolveText(absolute.toString())).isEqualTo(absolute);
        assertThat(resolver.resolveHtml("nested/run.html"))
                .isEqualTo(outputDir.resolve("nested").resolve("run.html"));
    }

    /**
     * :purpose: The parent directory is created during resolution so the flat-file
     *  writers never fail with an "Unable to create file" stream exception.
     */
    @Test
    @DisplayName("resolution creates the parent directory")
    void resolutionCreatesParentDirectory() {
        StatementOutputResolver resolver = new StatementOutputResolver(
                outputDir.resolve("deep").resolve("tree").toString());

        Path resolved = resolver.resolveText(null);

        assertThat(Files.isDirectory(resolved.getParent())).isTrue();
    }

    /**
     * :purpose: A writable root is reported as writable, which is the pre-flight
     *  condition for accepting a report request.
     */
    @Test
    @DisplayName("a writable root passes the writability probe")
    void writableRootPassesProbe() {
        assertThat(new StatementOutputResolver(outputDir.toString()).verifyWritable()).isTrue();
    }

    /**
     * :purpose: A root that cannot be created is reported as not writable so the
     *  request is refused with the legacy failure message instead of launching a
     *  run that is certain to fail.
     */
    @Test
    @DisplayName("an uncreatable root fails the writability probe")
    void uncreatableRootFailsProbe() {
        StatementOutputResolver resolver =
                new StatementOutputResolver("/proc/carddemo-statements-must-not-exist");

        assertThat(resolver.verifyWritable()).isFalse();
    }
}
