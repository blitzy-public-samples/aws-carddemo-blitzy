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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * :purpose: Validates the two Explainability deliverables -- ``docs/decision-log.md`` and
 *     ``docs/traceability-matrix.md`` -- against the repository they describe, so a stale
 *     locator or an unmapped module cannot reach a release unnoticed.
 * :returns: nothing; each test fails with the offending locators or paths named.
 *
 * Three invariants are enforced. Every bracketed source locator must name a file that
 * exists, spelled with the case the filesystem uses, and any line range it cites must lie
 * inside that file. Every tracked Java, TypeScript and SQL file under the modern tree must
 * appear in the traceability matrix. And the matrix must state coverage in both directions.
 *
 * The locator grammar is the one the log's own legend defines: ``path`` or ``path:Lnn`` or
 * ``path:Lnn-Lmm``, inside square brackets, several separated by ``;`` or ``,``.
 */
class GovernanceDocumentationTest {

    /** File whose presence marks the repository root. */
    private static final String COMPOSE_FILE = "docker-compose.yml";

    /** The decision log. */
    private static final String DECISION_LOG = "docs/decision-log.md";

    /** The bidirectional traceability matrix. */
    private static final String TRACEABILITY_MATRIX = "docs/traceability-matrix.md";

    /**
     * Matches one locator: a repository-relative path optionally followed by a line or line
     * range. The path alternation is anchored on the directories the legend uses, so ordinary
     * prose containing a slash is not mistaken for a locator.
     */
    private static final Pattern LOCATOR = Pattern.compile(
            "\\b((?:app|db|docs|k8s|observability|perf|frontend|carddemo-common"
                    + "|[a-z]+-service|api-gateway)/[A-Za-z0-9_./-]+\\.[A-Za-z0-9]+)"
                    + "(?::L(\\d+)(?:\\s*-\\s*L?(\\d+))?)?");

    /**
     * Directories that hold generated or vendored content rather than authored source.
     * ``blitzy`` is the gitignored tree that automated review and QA runs write their working
     * evidence into; it is untracked, so it is not part of the delivery the matrix covers.
     */
    private static final Set<String> EXCLUDED_SEGMENTS =
            Set.of("target", "node_modules", "dist", "build", ".git", "coverage", "blitzy");

    /**
     * :purpose: Every bracketed locator in either governance document must resolve to a real
     *     file, with the case the filesystem uses, and any cited line range must exist.
     */
    @Test
    @DisplayName("every source locator in the decision log and traceability matrix is valid")
    void everyLocatorResolvesToAnExistingFileAndLineRange() {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();

        for (String document : List.of(DECISION_LOG, TRACEABILITY_MATRIX)) {
            List<String> lines = lines(root, document);
            for (int i = 0; i < lines.size(); i++) {
                for (String bracketed : bracketedSpans(lines.get(i))) {
                    Matcher matcher = LOCATOR.matcher(bracketed);
                    while (matcher.find()) {
                        checkLocator(root, document, i + 1, matcher, violations);
                    }
                }
            }
        }

        assertThat(violations)
                .describedAs(
                        "Governance documents cite source locators that do not resolve. A locator "
                                + "must name an existing file with the case the filesystem uses, and "
                                + "any line range must lie inside it.")
                .isEmpty();
    }

    /**
     * :purpose: Every authored source file in the modern tree must appear in the traceability
     *     matrix, so the document's 100% reverse-coverage claim is true rather than asserted.
     */
    @Test
    @DisplayName("every modern source file appears in the traceability matrix")
    void everyModernSourceFileIsTraced() {
        Path root = repositoryRoot();
        String matrix = read(root, TRACEABILITY_MATRIX);
        List<String> untraced = new ArrayList<>();

        for (Path file : modernSourceFiles(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String fileName = file.getFileName().toString();
            String stem = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            // A file counts as traced when the matrix names its path, its file name, or its
            // stem: rows legitimately refer to `AccountService` rather than to the .java path.
            if (!matrix.contains(relative) && !matrix.contains(fileName)
                    && !matrix.contains(stem)) {
                untraced.add(relative);
            }
        }

        assertThat(untraced)
                .describedAs(
                        "The traceability matrix claims 100%% reverse coverage, so every authored "
                                + "source file must appear in it by path, file name or type name.")
                .isEmpty();
    }

    /**
     * :purpose: The matrix must state coverage in both directions, which is what makes it
     *     bidirectional rather than a one-way index.
     */
    @Test
    @DisplayName("the traceability matrix records both directions")
    void matrixRecordsBothDirections() {
        String matrix = read(repositoryRoot(), TRACEABILITY_MATRIX);
        assertThat(matrix).contains("source → target");
        assertThat(matrix).contains("target → source");
    }

    /**
     * :purpose: Validate one matched locator, appending a description of any fault found.
     * :param root: the repository root.
     * :param document: the document the locator was read from.
     * :param lineNumber: the document line the locator appears on.
     * :param matcher: the matched locator, group 1 the path and groups 2-3 the line range.
     * :param violations: collector the fault description is appended to.
     */
    private static void checkLocator(
            Path root, String document, int lineNumber, Matcher matcher, List<String> violations) {
        String relative = matcher.group(1);
        if (relative.contains("/.../")) {
            // `module/.../Type.java` is the documents' own abbreviation for an elided package
            // path. It names a type rather than a path, so there is no path to resolve.
            return;
        }
        Path target = root.resolve(relative);
        String where = document + ":" + lineNumber + " -> " + matcher.group(0);

        if (!Files.isRegularFile(target)) {
            String actualCase = caseInsensitiveMatch(root, relative);
            violations.add(actualCase == null
                    ? where + " (no such file)"
                    : where + " (wrong case; the file is " + actualCase + ")");
            return;
        }
        if (matcher.group(2) == null) {
            return;
        }
        int fileLines = lines(root, relative).size();
        int start = Integer.parseInt(matcher.group(2));
        int end = matcher.group(3) == null ? start : Integer.parseInt(matcher.group(3));
        if (start < 1 || end > fileLines) {
            violations.add(where + " (cites L" + start + "-L" + end + " but the file has "
                    + fileLines + " lines)");
        }
    }

    /**
     * :purpose: Find the file a locator meant when only its case is wrong, so the failure
     *     message can name the correction rather than merely reporting an absence.
     * :param root: the repository root.
     * :param relative: the repository-relative path as written in the document.
     * :returns: the real repository-relative path, or ``null`` when no such file exists.
     */
    private static String caseInsensitiveMatch(Path root, String relative) {
        Path parent = root.resolve(relative).getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return null;
        }
        String wanted = Paths.get(relative).getFileName().toString().toLowerCase(Locale.ROOT);
        try (Stream<Path> entries = Files.list(parent)) {
            return entries
                    .filter(candidate -> candidate.getFileName().toString()
                            .toLowerCase(Locale.ROOT).equals(wanted))
                    .findFirst()
                    .map(candidate -> root.relativize(candidate).toString().replace('\\', '/'))
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * :purpose: Extract the square-bracketed spans of a line, which is where the legend places
     *     locators, so a path mentioned in ordinary prose is not validated as one.
     * :param line: one document line.
     * :returns: the contents of each bracketed span on the line.
     */
    private static List<String> bracketedSpans(String line) {
        List<String> spans = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = line.indexOf('[', from);
            if (open < 0) {
                return spans;
            }
            int close = line.indexOf(']', open + 1);
            if (close < 0) {
                return spans;
            }
            spans.add(line.substring(open + 1, close));
            from = close + 1;
        }
    }

    /**
     * :purpose: Collect the authored source files whose traceability the Explainability rule
     *     requires: Java, TypeScript and SQL under the modern tree.
     * :param root: the repository root.
     * :returns: the source files, in a stable order.
     */
    private static Set<Path> modernSourceFiles(Path root) {
        Set<Path> files = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".java") || name.endsWith(".ts")
                                || name.endsWith(".tsx") || name.endsWith(".sql");
                    })
                    .filter(path -> {
                        for (Path segment : root.relativize(path)) {
                            if (EXCLUDED_SEGMENTS.contains(segment.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
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
     * :purpose: Read a committed repository file as one string.
     * :param root: the repository root.
     * :param relativePath: the path relative to the repository root.
     * :returns: the file's full contents.
     */
    private static String read(Path root, String relativePath) {
        try {
            return Files.readString(root.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * :purpose: Read every line of a committed repository file.
     * :param root: the repository root.
     * :param relativePath: the path relative to the repository root.
     * :returns: the file's lines, without line terminators.
     */
    private static List<String> lines(Path root, String relativePath) {
        try {
            return Files.readAllLines(root.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
