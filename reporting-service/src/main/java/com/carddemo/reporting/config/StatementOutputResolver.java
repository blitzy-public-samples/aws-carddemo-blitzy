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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * :purpose: Resolve the filesystem destination of the statement-generation job's
 *  two output documents (the ``CBSTM03A`` 80-column ``STMTFILE`` and the
 *  100-column ``HTMLFILE``) from configuration instead of a hard-coded relative
 *  directory, and prove that the destination is genuinely writable before a
 *  report is accepted. It exists because the legacy program's submission fails
 *  visibly when the submission medium cannot be written (``CORPT00C`` reports
 *  ``'Unable to Write TDQ (JOBS)...'``), whereas a hard-coded ``output/``
 *  directory silently disagreed with a deployment whose root filesystem is
 *  read-only.
 * :output: A {@link Component} exposing {@link #resolveText(String)},
 *  {@link #resolveHtml(String)} and {@link #verifyWritable()}. The resolvers
 *  return an absolute {@link Path} inside the configured output root, creating
 *  the root when absent; ``verifyWritable`` reports whether the root exists (or
 *  can be created) and accepts writes.
 * :note: The root comes from ``carddemo.reporting.output-dir``; when the property
 *  is blank a ``<java.io.tmpdir>/carddemo-reporting/output`` default is used, the
 *  same convention the sibling batch-service applies to its own report output, so
 *  a container with a read-only root filesystem and a writable ``/tmp`` mount
 *  needs no extra configuration.
 */
@Component
public class StatementOutputResolver {

    /** :purpose: Logger for output-destination resolution and writability probes. */
    private static final Logger LOGGER = LoggerFactory.getLogger(StatementOutputResolver.class);

    /** :purpose: Default file name of the plain-text statement document (``STMTFILE``). */
    public static final String DEFAULT_TEXT_FILE = "statements.txt";

    /** :purpose: Default file name of the HTML statement document (``HTMLFILE``). */
    public static final String DEFAULT_HTML_FILE = "statements.html";

    /** :purpose: Sub-directory leaf used when no output directory is configured. */
    private static final String DEFAULT_LEAF = "output";

    /** :purpose: Parent directory name used when no output directory is configured. */
    private static final String DEFAULT_PARENT = "carddemo-reporting";

    /** :purpose: Absolute, normalized root directory every statement document is written under. */
    private final Path outputRoot;

    /**
     * :purpose: Construct the resolver from the configured output directory,
     *  falling back to a temporary-directory default when the property is blank.
     * :param outputDir: the configured output root (``carddemo.reporting.output-dir``).
     */
    public StatementOutputResolver(@Value("${carddemo.reporting.output-dir:}") String outputDir) {
        this.outputRoot = Paths.get(defaultIfBlank(outputDir)).toAbsolutePath().normalize();
        LOGGER.info("Statement output root resolved to {}", this.outputRoot);
    }

    /**
     * :purpose: Resolve the plain-text statement document, honouring an explicit
     *  job-parameter path when the launcher supplied one.
     * :param requested: the ``stmtFile`` job-parameter value, or ``null``/blank to
     *  use the configured root and default file name.
     * :returns: the absolute path of the plain-text statement document.
     */
    public Path resolveText(String requested) {
        return resolve(requested, DEFAULT_TEXT_FILE);
    }

    /**
     * :purpose: Resolve the HTML statement document, honouring an explicit
     *  job-parameter path when the launcher supplied one.
     * :param requested: the ``htmlFile`` job-parameter value, or ``null``/blank to
     *  use the configured root and default file name.
     * :returns: the absolute path of the HTML statement document.
     */
    public Path resolveHtml(String requested) {
        return resolve(requested, DEFAULT_HTML_FILE);
    }

    /**
     * :purpose: Report the configured output root so callers can log or pass it as
     *  a job parameter.
     * :returns: the absolute output root directory.
     */
    public Path outputRoot() {
        return outputRoot;
    }

    /**
     * :purpose: Prove the output destination is usable before a report request is
     *  accepted: create the root when absent and confirm the process may write to
     *  it. This is the target equivalent of the legacy check that the submission
     *  medium (TDQ ``'JOBS'``) could be written.
     * :returns: ``true`` when the root exists (or was created) and is writable.
     */
    public boolean verifyWritable() {
        try {
            Files.createDirectories(outputRoot);
        } catch (IOException e) {
            LOGGER.error("Statement output root {} cannot be created", outputRoot, e);
            return false;
        }
        if (!Files.isWritable(outputRoot)) {
            LOGGER.error("Statement output root {} is not writable", outputRoot);
            return false;
        }
        return true;
    }

    /**
     * :purpose: Resolve a requested path against the output root, creating the
     *  parent directory so the flat-file writers can open the document.
     * :param requested: the requested path, or ``null``/blank for the default name.
     * :param defaultFileName: the default document name inside the output root.
     * :returns: the absolute path of the document to write.
     */
    private Path resolve(String requested, String defaultFileName) {
        Path candidate = (requested == null || requested.isBlank())
                ? outputRoot.resolve(defaultFileName)
                : Paths.get(requested);
        Path resolved = (candidate.isAbsolute() ? candidate : outputRoot.resolve(candidate)).normalize();
        Path parent = resolved.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                // Surface as an unchecked failure so the step fails loudly rather
                // than the writer reporting an opaque "Unable to create file".
                throw new java.io.UncheckedIOException(
                        "Failed to prepare statement output directory " + parent, e);
            }
        }
        return resolved;
    }

    /**
     * :purpose: Provide the temporary-directory default when no output directory
     *  is configured.
     * :param configured: the configured value, possibly ``null`` or blank.
     * :returns: the configured value when present, otherwise
     *  ``<java.io.tmpdir>/carddemo-reporting/output``.
     */
    private static String defaultIfBlank(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), DEFAULT_PARENT, DEFAULT_LEAF).toString();
    }
}
