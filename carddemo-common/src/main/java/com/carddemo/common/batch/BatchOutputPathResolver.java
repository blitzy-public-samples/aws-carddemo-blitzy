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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * :purpose: Resolve a job-parameter file name supplied to a data-management batch job to a
 *     concrete filesystem path that is confined to a configured, allowlisted root directory.
 *     It exists so that the report, dump, combine, and feed-reader writers/readers never open
 *     an attacker-influenced absolute path, ``..`` traversal, or symlink that escapes the
 *     batch working area (CWE-22). Two independent roots are managed: an output root for files
 *     the jobs write (reports, record dumps, the combined transaction file) and an input root
 *     for the daily-transaction feed the jobs read.
 * :output: A shared resolver exposing {@link #resolveOutput(String)} and {@link
 *     #resolveInput(String)}. Each returns a canonical {@link Path} proven to reside within
 *     the matching real (symlink-resolved) root; the output variant additionally creates the
 *     parent directory. Any request that is blank, escapes the root, or traverses a symlink
 *     out of the root raises {@link IllegalArgumentException} (or {@link UncheckedIOException}
 *     on an underlying I/O failure), so a rejected path is never opened.
 * :note: Roots come from ``carddemo.batch.output-dir`` and ``carddemo.batch.input-dir``;
 *     when a property is blank a workstation default under the JVM temporary directory is
 *     used. Both roots are created, canonicalized and (for the output root) proven writable
 *     once at construction, so a container whose batch directory is missing or read-only
 *     aborts startup with a precise message instead of failing every job mid-step with a bare
 *     ``java.io.IOException: No such file or directory``.
 * :note: This type lives in ``carddemo-common`` because BOTH batch-capable services need
 *     it - ``batch-service`` for its nine data-management jobs and ``reporting-service`` for
 *     the ``CBSTM03A`` statement writer - and a second copy would be free to drift away from
 *     this one's containment rules. It carries no stereotype annotation: the library is not
 *     component-scanned, so each batch-capable service declares the bean explicitly and the
 *     other seven services never create a batch working directory.
 */
public class BatchOutputPathResolver {

    /**
     * Longest accepted batch file name. A legacy data-set name is at most 44
     * characters; this leaves room for the generation qualifier and an extension
     * while still bounding what a caller can put on a file system.
     */
    private static final int MAX_FILE_NAME_LENGTH = 64;

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
    public BatchOutputPathResolver(String outputDir, String inputDir) {
        this.outputRoot = canonicalizeRoot(defaultIfBlank(outputDir, "output"), true);
        this.inputRoot = canonicalizeRoot(defaultIfBlank(inputDir, "input"), false);
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
     * :purpose: Resolve a requested file name to a PER-RUN generation of it, reproducing the
     *     generation-data-group allocation the legacy job streams used for their deliverables.
     * :param requested: the configured base file name (for example ``dalyrejs.txt``).
     * :param generation: the run's generation number; the job execution id is used, so the
     *     name is unique for all time, ordered by run, and directly resolvable in
     *     ``BATCH_JOB_EXECUTION``.
     * :returns: the canonical {@link Path} of that generation, confined to the output root.
     * :throws IllegalArgumentException: when the request is blank, escapes the root, or
     *     resolves through a symlink that leaves the root.
     * :note: The generation is inserted BEFORE the extension - ``dalyrejs.txt`` with
     *     generation 33 becomes ``dalyrejs.G0033V00.txt`` - so the name carries the legacy
     *     ``Gnnnnvnn`` generation shape (``AWS.M2.CARDDEMO.DALYREJS.G0001V00``) while keeping the
     *     extension the repository's ``.gitignore`` matches. A run's own generation is still
     *     removed when its step fails, exactly as ``DISP=(NEW,CATLG,DELETE)`` deletes the
     *     generation it created on an abend; what can no longer happen is one run destroying
     *     ANOTHER run's generation [app/jcl/POSTTRAN.jcl].
     * :note: A negative or zero generation is not special-cased: Spring Batch execution ids
     *     start at 1, and formatting is width-4 minimum without truncation, so a five-digit id
     *     widens the field rather than wrapping onto an earlier generation.
     */
    public Path resolveOutputGeneration(String requested, long generation) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("Batch file path must not be blank");
        }
        return resolve(outputRoot, generationName(requested, generation), true);
    }

    /**
     * :purpose: Insert a ``Gnnnnv00`` generation qualifier before the extension of a file
     *  name, leaving any directory prefix untouched.
     * :param requested: the base file name, possibly carrying a relative directory prefix.
     * :param generation: the generation number.
     * :returns: the generation-qualified name.
     */
    private static String generationName(String requested, long generation) {
        String qualifier = String.format(".G%04dV00", generation);
        int lastSeparator = Math.max(requested.lastIndexOf('/'), requested.lastIndexOf('\\'));
        int dot = requested.lastIndexOf('.');
        if (dot <= lastSeparator + 1) {
            // No extension on the file-name component (a leading dot is part of the name).
            return requested + qualifier;
        }
        return requested.substring(0, dot) + qualifier + requested.substring(dot);
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
        requireSafeName(requested);

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
     * :purpose: Reject a requested batch file name that is not drawn from the safe character
     *     set, before it is ever resolved against a root or opened.
     * :param requested: the raw requested file name.
     * :raises IllegalArgumentException: when the value contains a character outside the
     *     allow-list, or is longer than the accepted maximum.
     * :note: The path guards below already prevent traversal and symlink escape, so a name
     *     like ``$(touch /tmp/pwned)`` or ``;id #.txt`` created a file with that literal name
     *     INSIDE the batch root and no command ever ran. It is still refused here: such a name is
     *     a deliverable that a downstream consumer must handle, and every consumer of these files
     *     is a shell script, an operator command line or a JCL-equivalent scheduler step. A
     *     literal ``$(...)``/backtick/``;``/newline in the name is a command-injection primitive
     *     the moment it is interpolated by any of them (CWE-78 at the consuming hop), and quoting
     *     is not something this service can enforce on its consumers.
     * :note: The allow-list is deliberately narrower than "not dangerous": letters, digits,
     *     dot, dash and underscore is the whole vocabulary a legacy data-set name could hold, so
     *     nothing an operator could legitimately have asked the mainframe for is refused. The
     *     generation-qualified names the writers derive (``dalyrejs.G0033V00.txt``) are inside it.
     * :note: Applied per PATH ELEMENT, not to the whole string, so the existing contract is
     *     preserved exactly: an absolute path that already lies inside the root stays acceptable,
     *     and a ``.``/``..`` element is passed through to the containment check so a traversal
     *     attempt is still reported as one.
     */
    private static void requireSafeName(String requested) {
        for (Path element : Paths.get(requested)) {
            String name = element.toString();
            // '.' and '..' are left to the containment check below, which reports a
            // traversal attempt in the terms an operator needs ("escapes the allowed
            // directory") rather than as a character-set violation.
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            if (name.length() > MAX_FILE_NAME_LENGTH) {
                throw new IllegalArgumentException("Batch file name element must be at most "
                        + MAX_FILE_NAME_LENGTH + " characters: " + name);
            }
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                boolean allowed = (c >= 'a' && c <= 'z')
                        || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9')
                        || c == '.' || c == '-' || c == '_';
                if (!allowed) {
                    throw new IllegalArgumentException(
                            "Batch file name may contain only letters, digits, '.', '-' and '_': "
                                    + requested);
                }
            }
        }
    }

    /**
     * :purpose: Create (if absent), canonicalize and - for the output root -
     *  writability-check a root directory, so a misconfigured or unmounted batch
     *  directory is reported once at startup rather than by every job that tries
     *  to open a file under it.
     * :param dir: the configured or defaulted root directory path.
     * :param requireWritable: assert the directory is writable (output root only;
     *  the input root is legitimately mounted read-only).
     * :returns: the real (symlink-resolved) {@link Path} of the created root.
     * :raises IllegalStateException: when the output root exists but is not writable.
     */
    private static Path canonicalizeRoot(String dir, boolean requireWritable) {
        try {
            Path root = Paths.get(dir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Path real = root.toRealPath();
            if (requireWritable && !Files.isWritable(real)) {
                throw new IllegalStateException(
                        "Batch output directory is not writable: " + real
                                + " (set carddemo.batch.output-dir / CARDDEMO_BATCH_OUTPUT_DIR to a"
                                + " writable path and mount it into the container)");
            }
            return real;
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
