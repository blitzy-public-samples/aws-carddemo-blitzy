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

package com.carddemo.batch.batch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * :purpose: Resolve a job-parameter file name supplied to a data-management
 *  batch job to a concrete filesystem path that is confined to a configured,
 *  allowlisted root directory. It exists so that the report, dump, combine, and
 *  feed-reader writers/readers never open an attacker-influenced absolute path,
 *  ``..`` traversal, or symlink that escapes the batch working area (CWE-22).
 *  Two independent roots are managed: an output root for files the jobs write
 *  (reports, record dumps, the combined transaction file) and an input root for
 *  the daily-transaction feed the jobs read.
 * :output: A {@link Component} exposing {@link #resolveOutput(String)} and
 *  {@link #resolveInput(String)}. Each returns a canonical {@link Path} proven
 *  to reside within the matching real (symlink-resolved) root; the output
 *  variant additionally creates the parent directory. Any request that is blank,
 *  escapes the root, or traverses a symlink out of the root raises
 *  {@link IllegalArgumentException} (or {@link UncheckedIOException} on an
 *  underlying I/O failure), so a rejected path is never opened.
 * :note: Roots come from ``carddemo.batch.output-dir`` and
 *  ``carddemo.batch.input-dir``; when a property is blank a workstation default
 *  under the JVM temporary directory is used. Both roots are created and
 *  canonicalized once at construction.
 */
@Component
public class BatchOutputPathResolver {

    /** Canonical (symlink-resolved) root that every written file must reside within. */
    private final Path outputRoot;

    /** Canonical (symlink-resolved) root that every read feed file must reside within. */
    private final Path inputRoot;

    /**
     * :purpose: Create the resolver, materialize both roots on disk, and
     *  canonicalize them so later containment checks compare real paths.
     * :param outputDir: configured output root (``carddemo.batch.output-dir``);
     *  when blank, ``<java.io.tmpdir>/carddemo-batch/output`` is used.
     * :param inputDir: configured input root (``carddemo.batch.input-dir``);
     *  when blank, ``<java.io.tmpdir>/carddemo-batch/input`` is used.
     */
    public BatchOutputPathResolver(
            @Value("${carddemo.batch.output-dir:}") String outputDir,
            @Value("${carddemo.batch.input-dir:}") String inputDir) {
        this.outputRoot = canonicalizeRoot(defaultIfBlank(outputDir, "output"));
        this.inputRoot = canonicalizeRoot(defaultIfBlank(inputDir, "input"));
    }

    /**
     * :purpose: Resolve a requested file name to a path confined to the output
     *  root, creating the parent directory so the caller can open the file for
     *  writing.
     * :param requested: the ``outputFile`` / ``reportFile`` job-parameter value;
     *  a relative name is resolved under the output root and an absolute path is
     *  accepted only when it already resides within the output root.
     * :returns: the canonical {@link Path}, guaranteed to be within the real
     *  output root.
     * :throws IllegalArgumentException: when the request is blank, escapes the
     *  root, or resolves through a symlink that leaves the root.
     */
    public Path resolveOutput(String requested) {
        return resolve(outputRoot, requested, true);
    }

    /**
     * :purpose: Resolve a requested file name to a path confined to the input
     *  root for reading the daily-transaction feed.
     * :param requested: the ``inputFile`` job-parameter value; a relative name is
     *  resolved under the input root and an absolute path is accepted only when
     *  it already resides within the input root.
     * :returns: the canonical {@link Path}, guaranteed to be within the real
     *  input root.
     * :throws IllegalArgumentException: when the request is blank, escapes the
     *  root, or resolves through a symlink that leaves the root.
     */
    public Path resolveInput(String requested) {
        return resolve(inputRoot, requested, false);
    }

    /**
     * :purpose: Perform the shared containment check: normalize the request
     *  against the given root, reject any result outside the root, then
     *  re-validate against the real (symlink-resolved) parent so a symlink cannot
     *  redirect the path out of the root.
     * :param root: the canonical root the result must reside within.
     * :param requested: the requested file name or path.
     * :param createParent: create the parent directory (output) versus leaving it
     *  untouched (input).
     * :returns: the canonical resolved {@link Path}.
     */
    private Path resolve(Path root, String requested, boolean createParent) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("Batch file path must not be blank");
        }

        Path candidate = Paths.get(requested);
        Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();

        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Batch file path escapes the allowed directory: " + requested);
        }

        Path fileName = resolved.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException(
                    "Batch file path must name a file, not a directory: " + requested);
        }

        Path parent = resolved.getParent();
        try {
            if (createParent) {
                Files.createDirectories(parent);
            }
            // Canonicalize the parent (following symlinks) and re-check
            // containment so a symlinked directory cannot escape the root.
            if (Files.exists(parent)) {
                Path realParent = parent.toRealPath();
                if (!realParent.startsWith(root)) {
                    throw new IllegalArgumentException(
                            "Batch file path resolves outside the allowed directory: " + requested);
                }
                return realParent.resolve(fileName);
            }
            return resolved;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to resolve batch file path: " + requested, e);
        }
    }

    /**
     * :purpose: Create (if absent) and canonicalize a root directory.
     * :param dir: the configured or defaulted root directory path.
     * :returns: the real (symlink-resolved) {@link Path} of the created root.
     */
    private static Path canonicalizeRoot(String dir) {
        try {
            Path root = Paths.get(dir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            return root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to prepare batch root directory: " + dir, e);
        }
    }

    /**
     * :purpose: Provide a workstation default under the JVM temporary directory
     *  when a root property is left blank.
     * :param configured: the configured value (possibly blank or null).
     * :param leaf: the sub-directory leaf (``output`` or ``input``).
     * :returns: the configured value when present, otherwise
     *  ``<java.io.tmpdir>/carddemo-batch/<leaf>``.
     */
    private static String defaultIfBlank(String configured, String leaf) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "carddemo-batch", leaf).toString();
    }
}
