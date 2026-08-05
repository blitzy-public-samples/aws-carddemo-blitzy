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
package com.carddemo.common.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * :purpose: Pins the build-context hygiene invariant for every container image the project
 *     builds: each image's context must be governed by a ``.dockerignore``, and Docker
 *     resolves that file relative to the CONTEXT root rather than to the module directory.
 *     Nine images build from a module context and are governed by their own
 *     ``<module>/.dockerignore``; reporting-service alone builds from the repository root,
 *     because its Dockerfile compiles the Maven reactor in-container and therefore needs the
 *     aggregator ``pom.xml``, every module ``pom.xml`` and the ``carddemo-common`` sibling —
 *     so its governing file is the repository-root ``.dockerignore``. A module-level
 *     ``reporting-service/.dockerignore`` would never be read for that build, and an
 *     allowlist copied from a sibling (``*`` then ``!target/*.jar``) would additionally
 *     contradict how the image is produced.
 * :output: A Surefire unit test that reads ``docker-compose.yml`` and the committed ignore
 *     files and asserts the mapping, so a future edit that adds an image without an ignore
 *     file, or that moves reporting-service to a module context, fails here instead of
 *     silently shipping an unfiltered context.
 * :note: Rationale for the root-context arrangement is in docs/decision-log.md
 *     (build-context hygiene), which also records why this class lives in ``packaging``
 *     rather than in a ``build`` package.
 */
class DockerBuildContextHygieneTest {

    /** :purpose: Compose file naming every image the project builds. */
    private static final String COMPOSE_FILE = "docker-compose.yml";

    /** :purpose: The ignore file Docker reads, relative to a build context root. */
    private static final String IGNORE_FILE = ".dockerignore";

    /** :purpose: The one service whose build context is the repository root. */
    private static final String ROOT_CONTEXT_SERVICE = "reporting-service";

    /** :purpose: Exclusions the root context must carry: host output, history, secrets. */
    private static final Set<String> REQUIRED_ROOT_EXCLUSIONS =
            Set.of("**/target", "**/node_modules", ".git", ".env");

    /**
     * :purpose: Build inputs ``reporting-service/Dockerfile`` copies from the root context.
     *     None of them may be excluded, or the in-container reactor build cannot resolve.
     */
    private static final List<String> ROOT_CONTEXT_BUILD_INPUTS =
            List.of("pom.xml", "carddemo-common", "reporting-service");

    /** :purpose: Matches ``  <name>:`` at the service-declaration indent of the compose file. */
    private static final Pattern SERVICE_LINE = Pattern.compile("^ {2}([A-Za-z0-9._-]+):\\s*$");

    /** :purpose: Matches a ``context:`` entry inside a service's ``build:`` block. */
    private static final Pattern CONTEXT_LINE = Pattern.compile("^\\s*context:\\s*(\\S+)\\s*$");

    /** :purpose: Matches a ``dockerfile:`` entry inside a service's ``build:`` block. */
    private static final Pattern DOCKERFILE_LINE = Pattern.compile("^\\s*dockerfile:\\s*(\\S+)\\s*$");

    /**
     * :purpose: One image-producing compose service.
     * :param service: the compose service name.
     * :param context: the build context, exactly as declared.
     * :param dockerfile: the explicit Dockerfile path, or ``null`` when the default applies.
     */
    private record BuildEntry(String service, String context, String dockerfile) {

        /**
         * :purpose: Whether this image builds from the repository root rather than a module.
         * :returns: ``true`` when the declared context is the repository root.
         */
        boolean isRootContext() {
            return ".".equals(context) || "./".equals(context);
        }
    }

    /**
     * :purpose: Locate the repository root by walking up from the module's working directory
     *     until the compose file is found, so the test runs from the reactor root or from the
     *     module directory alike.
     * :returns: the repository root.
     */
    private static Path repositoryRoot() {
        Path candidate = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(COMPOSE_FILE))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Unable to locate " + COMPOSE_FILE + " above " + System.getProperty("user.dir"));
    }

    /**
     * :purpose: Read every line of a committed repository file.
     * :param relativePath: the path relative to the repository root.
     * :returns: the file's lines, without line terminators.
     */
    private static List<String> lines(String relativePath) {
        try {
            return Files.readAllLines(repositoryRoot().resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("unreadable committed file: " + relativePath, ex);
        }
    }

    /**
     * :purpose: Extract every image-producing service from the compose file together with its
     *     declared context and Dockerfile. The compose file is read as text rather than through
     *     a YAML binding so the test needs no additional dependency and so a value carrying
     *     ``${VAR:?}`` interpolation is observed exactly as written.
     * :returns: one entry per service that declares a ``build:`` block, in file order.
     */
    private static List<BuildEntry> buildEntries() {
        List<BuildEntry> entries = new ArrayList<>();
        String service = null;
        boolean inBuild = false;
        String context = null;
        String dockerfile = null;
        for (String line : lines(COMPOSE_FILE)) {
            Matcher serviceMatcher = SERVICE_LINE.matcher(line);
            if (serviceMatcher.matches()) {
                if (service != null && context != null) {
                    entries.add(new BuildEntry(service, context, dockerfile));
                }
                service = serviceMatcher.group(1);
                inBuild = false;
                context = null;
                dockerfile = null;
                continue;
            }
            if (line.strip().equals("build:")) {
                inBuild = true;
                continue;
            }
            if (!inBuild) {
                continue;
            }
            Matcher contextMatcher = CONTEXT_LINE.matcher(line);
            if (contextMatcher.matches()) {
                context = contextMatcher.group(1);
                continue;
            }
            Matcher dockerfileMatcher = DOCKERFILE_LINE.matcher(line);
            if (dockerfileMatcher.matches()) {
                dockerfile = dockerfileMatcher.group(1);
            }
        }
        if (service != null && context != null) {
            entries.add(new BuildEntry(service, context, dockerfile));
        }
        return entries;
    }

    /**
     * :purpose: The exclusion patterns a committed ignore file declares, with comment and
     *     blank lines dropped.
     * :param relativePath: the ignore file, relative to the repository root.
     * :returns: the declared patterns in file order, including any leading ``!`` re-admission.
     */
    private static List<String> ignorePatterns(String relativePath) {
        List<String> patterns = new ArrayList<>();
        for (String line : lines(relativePath)) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                patterns.add(stripped);
            }
        }
        return patterns;
    }

    @Test
    @DisplayName("every image the project builds has a .dockerignore governing its own context")
    void everyBuildContextIsGovernedByAnIgnoreFile() {
        List<BuildEntry> entries = buildEntries();
        assertThat(entries)
                .as("compose declares at least the ten committed images")
                .hasSizeGreaterThanOrEqualTo(10);

        Map<String, String> governingFile = new LinkedHashMap<>();
        for (BuildEntry entry : entries) {
            String ignorePath = entry.isRootContext()
                    ? IGNORE_FILE
                    : entry.context().replaceFirst("^\\./", "") + "/" + IGNORE_FILE;
            assertThat(repositoryRoot().resolve(ignorePath))
                    .as("%s builds from context '%s', so Docker reads '%s'",
                            entry.service(), entry.context(), ignorePath)
                    .isRegularFile();
            governingFile.put(entry.service(), ignorePath);
        }

        // reporting-service is deliberately the ONLY image mapped to the root ignore file.
        assertThat(governingFile)
                .containsEntry(ROOT_CONTEXT_SERVICE, IGNORE_FILE);
        assertThat(governingFile.values().stream().filter(IGNORE_FILE::equals).count())
                .as("exactly one root-context image")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("reporting-service builds from the repository root, so the root .dockerignore governs it")
    void reportingServiceIsARootContextBuild() {
        BuildEntry reporting = buildEntries().stream()
                .filter(entry -> ROOT_CONTEXT_SERVICE.equals(entry.service()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(ROOT_CONTEXT_SERVICE + " declares no build"));

        assertThat(reporting.isRootContext())
                .as("its Dockerfile copies the aggregator pom.xml and the carddemo-common sibling, "
                        + "neither of which a module context can see")
                .isTrue();
        assertThat(reporting.dockerfile()).isEqualTo(ROOT_CONTEXT_SERVICE + "/Dockerfile");

        // A module-level file would never be read for a root-context build; asserting its
        // absence keeps a well-meant but inert copy of a sibling's allowlist from appearing.
        assertThat(repositoryRoot().resolve(ROOT_CONTEXT_SERVICE).resolve(IGNORE_FILE))
                .as("a module-level ignore file here would be dead configuration")
                .doesNotExist();
    }

    @Test
    @DisplayName("the root .dockerignore filters host output, history and secrets")
    void rootIgnoreFileFiltersTheContext() {
        List<String> patterns = ignorePatterns(IGNORE_FILE);

        assertThat(patterns).containsAll(REQUIRED_ROOT_EXCLUSIONS);
        assertThat(patterns)
                .as("the retained legacy sources are reference material, never a build input")
                .contains("app", "diagrams", "samples");
    }

    @Test
    @DisplayName("the root .dockerignore admits every input the reporting-service build copies")
    void rootIgnoreFileAdmitsTheReactorBuildInputs() {
        List<String> patterns = ignorePatterns(IGNORE_FILE);

        // Every root pattern is a plain path, a **/ prefix form or a *.ext suffix form, so an
        // exact-token comparison is sufficient to prove a build input is not excluded.
        for (String input : ROOT_CONTEXT_BUILD_INPUTS) {
            assertThat(patterns)
                    .as("'%s' is copied by %s/Dockerfile and must stay in the context",
                            input, ROOT_CONTEXT_SERVICE)
                    .doesNotContain(input, "/" + input, input + "/", "**/" + input);
        }
        assertThat(patterns)
                .as("a blanket exclusion would strip the reactor sources")
                .doesNotContain("*", "**", "src", "**/src", "**/pom.xml");
    }

    @Test
    @DisplayName("each module-context ignore file excludes everything but the repackaged boot jar")
    void moduleIgnoreFilesAdmitOnlyTheBootJar() {
        for (BuildEntry entry : buildEntries()) {
            if (entry.isRootContext() || "frontend".equals(entry.service())) {
                continue;
            }
            String ignorePath = entry.context().replaceFirst("^\\./", "") + "/" + IGNORE_FILE;
            assertThat(ignorePatterns(ignorePath))
                    .as("%s admits only target/*.jar, which is the single input its Dockerfile copies",
                            entry.service())
                    .containsExactly("*", "!target", "target/*", "!target/*.jar");
        }
    }
}
