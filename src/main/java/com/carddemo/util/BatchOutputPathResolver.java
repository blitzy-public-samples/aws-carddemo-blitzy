package com.carddemo.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves and validates filesystem output locations supplied to batch jobs as
 * {@code JobParameters}, confining every resolved {@link Path} to a single,
 * pre-configured base directory.
 *
 * <h2>Why this exists (CP4 security finding)</h2>
 * <p>Several batch components ({@code StatementGenerationTasklet},
 * {@code CategoryBalanceReportJobConfig}) historically accepted an
 * {@code outputDir} job parameter and passed it <em>directly</em> to
 * {@link java.nio.file.Paths#get(String, String...)} before creating directories
 * and writing files. Because {@code JobParameters} are caller-controlled (any
 * client able to launch a job through {@code BatchAdminController} can set them),
 * an attacker could direct writes outside the intended output area — e.g.
 * {@code outputDir=/etc} or {@code outputDir=../../var/www} — a classic path
 * traversal vulnerability.</p>
 *
 * <h2>Contract</h2>
 * <p>A single configured <b>base directory</b> (property
 * {@code carddemo.batch.output.base-dir}, default {@value #DEFAULT_BASE_DIR})
 * defines the only filesystem subtree batch jobs may write to. The
 * caller-supplied value is treated strictly as a <b>relative sub-path</b> beneath
 * that base. The resolver:</p>
 * <ol>
 *   <li>returns the base directory itself when the requested sub-path is
 *       {@code null} or blank (the secure default);</li>
 *   <li>rejects any <b>absolute</b> requested path (e.g. {@code /etc/passwd},
 *       {@code C:\\Windows}) with {@link IllegalArgumentException};</li>
 *   <li>rejects any requested path that contains a {@code ..} (parent) segment
 *       with {@link IllegalArgumentException};</li>
 *   <li>resolves the sub-path against the base, {@linkplain Path#normalize()
 *       normalizes} the result, and performs a final defensive
 *       {@link Path#startsWith(Path)} containment check — rejecting anything that
 *       escapes the base even after normalization.</li>
 * </ol>
 *
 * <p>The base directory is itself normalized to an absolute path at construction
 * time, so the containment check is performed between two absolute, normalized
 * paths and is not sensitive to the process working directory at call time.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>Instances are immutable after construction (the base directory is a single
 * {@code final} field) and therefore safe to share as a Spring singleton across
 * concurrent batch executions.</p>
 *
 * <p>PR-29 compliance: the configured base directory is supplied via constructor
 * injection ({@code @Value} on the constructor parameter) rather than field
 * injection.</p>
 */
@Component
public class BatchOutputPathResolver {

    /**
     * Default base output directory used when {@code carddemo.batch.output.base-dir}
     * is not set. Relative to the process working directory; normalized to an
     * absolute path at construction time.
     */
    public static final String DEFAULT_BASE_DIR = "./output";

    /**
     * The single approved output subtree. Absolute and normalized; all resolved
     * paths are guaranteed to live at or beneath this directory.
     */
    private final Path baseDir;

    /**
     * Creates the resolver, normalizing the configured base directory to an
     * absolute path. A blank or absent configuration value falls back to
     * {@link #DEFAULT_BASE_DIR}.
     *
     * @param configuredBaseDir value of {@code carddemo.batch.output.base-dir}
     *                          (may be {@code null}/blank, in which case the
     *                          default is used)
     */
    public BatchOutputPathResolver(
            @Value("${carddemo.batch.output.base-dir:./output}") String configuredBaseDir) {
        String effective = (configuredBaseDir == null || configuredBaseDir.isBlank())
                ? DEFAULT_BASE_DIR
                : configuredBaseDir.trim();
        this.baseDir = Paths.get(effective).toAbsolutePath().normalize();
    }

    /**
     * Returns the absolute, normalized base directory that bounds every resolved
     * path. Useful for callers that want to write directly to the root of the
     * approved subtree.
     *
     * @return the approved base output directory (never {@code null})
     */
    public Path getBaseDir() {
        return baseDir;
    }

    /**
     * Resolves a caller-supplied output location to a safe absolute {@link Path}
     * confined within {@link #getBaseDir()}.
     *
     * @param requestedSubpath a relative sub-path beneath the base directory;
     *                         {@code null} or blank yields the base directory
     *                         itself
     * @return an absolute, normalized path at or beneath the base directory
     * @throws IllegalArgumentException if the requested path is absolute, contains
     *                                  a {@code ..} parent segment, or otherwise
     *                                  escapes the configured base directory
     */
    public Path resolve(String requestedSubpath) {
        if (requestedSubpath == null || requestedSubpath.isBlank()) {
            return baseDir;
        }

        String trimmed = requestedSubpath.trim();
        Path requested = Paths.get(trimmed);

        // 1. Reject absolute paths outright (e.g. /etc, C:\Windows). An absolute
        //    request can never be a sub-path of the base and is always an attempt
        //    to escape the approved subtree.
        if (requested.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Rejected output path: absolute paths are not permitted; "
                            + "the outputDir job parameter must be a relative sub-path "
                            + "beneath the configured base directory [" + baseDir + "].");
        }

        // 2. Reject any explicit parent-directory ("..") segment. This is a fast,
        //    intent-clear rejection that complements the normalized containment
        //    check below.
        for (Path segment : requested) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException(
                        "Rejected output path: parent-directory traversal ('..') is "
                                + "not permitted in the outputDir job parameter.");
            }
        }

        // 3. Resolve against the base and normalize, then perform a defensive
        //    containment check. normalize() collapses any residual '.'/'..'
        //    sequences; startsWith guarantees the result cannot escape the base
        //    even via symlink-free path arithmetic.
        Path resolved = baseDir.resolve(requested).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException(
                    "Rejected output path: resolved location escapes the configured "
                            + "base directory [" + baseDir + "].");
        }

        return resolved;
    }
}
