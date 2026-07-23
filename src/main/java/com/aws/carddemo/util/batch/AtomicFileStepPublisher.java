package com.aws.carddemo.util.batch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ExecutionContext;

/**
 * A reusable {@link StepExecutionListener} that gives a chunk-oriented file writer step
 * <em>atomic, owner-only publication with rollback safety</em> (review findings&nbsp;#18 and&nbsp;#19).
 *
 * <p>Spring Batch's {@link org.springframework.batch.item.file.FlatFileItemWriter} streams records
 * directly to its target resource and tracks the committed byte position in the step
 * {@link ExecutionContext} for restartability. Writing straight to the final path, however, exposes
 * a partially written file to any consumer if the step fails, and offers no restrictive permissions
 * or symlink protection. This listener adds those guarantees without giving up chunk streaming or
 * restart:</p>
 *
 * <ol>
 *   <li>The writer bean calls {@link #prepare(String, StepExecution)} (or the named
 *       {@link #prepare(String, StepExecution, String)} overload) at step time. That resolves the
 *       final target through the {@link BatchFilePathResolver} (safe-root containment + symlink
 *       rejection), creates a <em>private owner-only ({@code 0700}) per-job-instance staging
 *       directory</em> beside the target
 *       ({@code <parent>/.carddemo-inprogress-<jobInstanceId>}) via
 *       {@link BatchFilePathResolver#createSecureStagingDirectory(Path, long)}, and returns a
 *       <em>deterministic</em> temporary path <em>inside that directory</em>
 *       ({@code <stagingDir>/<target>.<jobInstanceId>.inprogress}) that the writer targets instead of
 *       the final file. The determinism is what makes a restart resume the very same temp file that
 *       the failed execution was appending to.</li>
 *   <li><strong>Confidentiality of the in-progress file (QA finding&nbsp;P4-SEC-01).</strong> Spring
 *       Batch's {@code FlatFileItemWriter} creates the temp file itself, so its mode is fixed by the
 *       process umask ({@code 0644} under the usual {@code 0022}) for the whole duration of the step.
 *       Rather than fight that (which would break the writer's restart/append contract), the temp is
 *       created <em>within</em> the {@code 0700} staging directory: because that directory is not
 *       traversable by group or other, the enclosed temp is unreachable by any other principal from
 *       the instant it exists, closing the world-readable window race-free (the directory's mode is
 *       pinned at creation and, having no group/other bits, survives any umask). The staging directory
 *       is created <em>before</em> the writer opens the file, so Spring's own {@code mkdirs} never
 *       recreates it under the umask.</li>
 *   <li>On {@link #afterStep(StepExecution)} for a {@link BatchStatus#COMPLETED} step, every prepared
 *       temp file is set to owner-only ({@code 0600}) permissions, flushed to durable storage and
 *       <em>atomically renamed</em> onto its final target (an intra-filesystem rename, since the
 *       staging directory is a child of the target's parent). Each final file therefore only ever
 *       appears complete, and each now-empty staging directory is then removed.</li>
 *   <li>On a failed step the temp files (and their staging directories) are intentionally left in
 *       place so a restart can resume them; the final targets are never touched, so no partial output
 *       is published (rollback safety).</li>
 * </ol>
 *
 * <p>A single step may publish <em>more than one</em> output file (for example the statement job's
 * plain-text and HTML datasets): each writer calls {@link #prepare(String, StepExecution, String)}
 * with a distinct logical name, and {@link #afterStep(StepExecution)} publishes every registered
 * target. The convenience {@link #prepare(String, StepExecution)} overload uses a single default
 * name and suffices for single-file steps.</p>
 *
 * <p>The listener holds no per-step mutable state &mdash; the temp/target paths live in the step
 * {@link ExecutionContext} keyed by logical name &mdash; so a single instance is safely shared across
 * every writer step and across concurrent executions.</p>
 */
public final class AtomicFileStepPublisher implements StepExecutionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtomicFileStepPublisher.class);

    /** Common prefix for every step {@link ExecutionContext} key this listener owns. */
    private static final String KEY_PREFIX = "carddemo.atomicPublish.";

    /** Suffix (appended to {@code KEY_PREFIX + logicalName}) of the resolved final target path key. */
    private static final String SUFFIX_TARGET = ".target";

    /** Suffix (appended to {@code KEY_PREFIX + logicalName}) of the in-progress temporary path key. */
    private static final String SUFFIX_TEMP = ".temp";

    /**
     * Suffix (appended to {@code KEY_PREFIX + logicalName}) of the private staging-directory path key
     * (QA finding&nbsp;P4-SEC-01). Recorded per output so {@link #afterStep(StepExecution)} can remove
     * the (now-empty) staging directory after a successful publish.
     */
    private static final String SUFFIX_STAGE = ".stage";

    /** Logical name used by the single-file {@link #prepare(String, StepExecution)} convenience overload. */
    private static final String DEFAULT_NAME = "default";

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final BatchFilePathResolver resolver;

    /**
     * Creates the publisher.
     *
     * @param resolver the shared safe-path resolver used for containment/symlink checks and the
     *                 atomic move; must not be {@code null}
     */
    public AtomicFileStepPublisher(BatchFilePathResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    /**
     * Single-file convenience overload of {@link #prepare(String, StepExecution, String)} using the
     * {@value #DEFAULT_NAME} logical name; suitable for a step that publishes exactly one output file.
     *
     * @param rawTargetPath the raw job-parameter output path
     * @param stepExecution the running step execution (source of the job-instance id and the
     *                      execution context)
     * @return the in-progress temporary path for the writer's resource
     * @throws IllegalArgumentException if the target path fails safe-root/symlink validation
     */
    public Path prepare(String rawTargetPath, StepExecution stepExecution) {
        return prepare(rawTargetPath, stepExecution, DEFAULT_NAME);
    }

    /**
     * Resolves the safe final target for {@code rawTargetPath}, creates a private owner-only
     * ({@code 0700}) per-job-instance staging directory beside it (QA finding&nbsp;P4-SEC-01), records
     * the target, the deterministic in-progress temporary path <em>inside that staging directory</em>,
     * and the staging directory itself in the step {@link ExecutionContext} under the given logical
     * name, and returns the temporary path the writer should target. A step may call this multiple
     * times with distinct {@code logicalName}s to publish several output files atomically.
     *
     * @param rawTargetPath the raw job-parameter output path
     * @param stepExecution the running step execution (source of the job-instance id and the
     *                      execution context)
     * @param logicalName   a unique-within-step name identifying this output file
     * @return the in-progress temporary path for the writer's resource
     * @throws IllegalArgumentException if the target path fails safe-root/symlink validation
     * @throws NullPointerException     if {@code stepExecution} or {@code logicalName} is {@code null}
     */
    public Path prepare(String rawTargetPath, StepExecution stepExecution, String logicalName) {
        Objects.requireNonNull(stepExecution, "stepExecution must not be null");
        Objects.requireNonNull(logicalName, "logicalName must not be null");
        Path target = resolver.resolveOutputTarget(rawTargetPath);
        long instanceId = stepExecution.getJobExecution().getJobInstance().getInstanceId();
        // P4-SEC-01: place the in-progress temp inside a private 0700 per-instance staging directory
        // (created here, before the writer opens the file) so it is never world-readable while the
        // step streams into it. The filename keeps the deterministic <target>.<instanceId>.inprogress
        // shape so a restart of the same instance resumes the very same temp.
        Path stagingDir = resolver.createSecureStagingDirectory(target, instanceId);
        Path temp = stagingDir.resolve(target.getFileName() + "." + instanceId + ".inprogress");
        ExecutionContext ec = stepExecution.getExecutionContext();
        ec.putString(KEY_PREFIX + logicalName + SUFFIX_TARGET, target.toString());
        ec.putString(KEY_PREFIX + logicalName + SUFFIX_TEMP, temp.toString());
        ec.putString(KEY_PREFIX + logicalName + SUFFIX_STAGE, stagingDir.toString());
        return temp;
    }

    /**
     * Publishes every prepared in-progress temp file to its final target when the step completed
     * successfully, or leaves them untouched (for restart) otherwise.
     *
     * <p><strong>Finalization is part of the step contract (finding&nbsp;F-01).</strong> The atomic
     * rename performed here <em>is</em> the step's externally observable output; a batch step whose
     * chunk phase completed but whose output was never published has <em>not</em> succeeded. Spring
     * Batch, however, invokes this callback <em>after</em> it has already set the step's
     * {@link BatchStatus#COMPLETED}, and it merely logs any exception a listener throws from
     * {@code afterStep} &mdash; so a finalization failure that simply propagated would leave the step
     * (and its job) reporting {@code COMPLETED}/exit&nbsp;0 while the byte-exact external file
     * (e.g. the {@code DALYREJS} reject feed, a statement, a transaction backup, or a category-balance
     * report) was silently lost, orphaned as an {@code .inprogress} temp. To prevent that
     * false-success/data-loss defect, a publication failure is caught here and turned into a genuine
     * step failure: the throwable is recorded on the {@link StepExecution}, the status is forced to
     * {@link BatchStatus#FAILED}, and {@link ExitStatus#FAILED} is returned. Returning a failed status
     * (rather than re-throwing) is what actually flips the already-{@code COMPLETED} step to
     * {@code FAILED}; that failure then propagates to the {@code JobExecution}, so the CLI batch
     * process exits non-zero (RC&nbsp;8 via the return-code exit-code generator) and the job instance
     * is restartable.</p>
     *
     * <p>The {@code .inprogress} temp files are intentionally left in place on such a failure (see the
     * class contract, step&nbsp;3): a restart resumes/re-publishes them once the operator has cleared
     * the underlying cause (for example a target path that had been replaced by a directory), so no
     * output is dropped and none is published in a partial state.</p>
     *
     * @param stepExecution the finished step execution
     * @return the step's own exit status when publication succeeded (or the step had not completed);
     *         {@link ExitStatus#FAILED} if publishing a completed step's output failed
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            // A non-completed step leaves every temp file in place so a restart resumes it; no final
            // target is partially written (rollback safety).
            return stepExecution.getExitStatus();
        }
        ExecutionContext ec = stepExecution.getExecutionContext();
        // Staging directories to remove once every output has been published. Deduplicated because a
        // multi-output step whose targets share one parent shares a single staging directory.
        Set<Path> stagingDirs = new LinkedHashSet<>();
        try {
            for (String targetKey : registeredTargetKeys(ec)) {
                String base = targetKey.substring(0, targetKey.length() - SUFFIX_TARGET.length());
                String tempKey = base + SUFFIX_TEMP;
                if (!ec.containsKey(tempKey)) {
                    continue;
                }
                Path temp = Paths.get(ec.getString(tempKey));
                Path target = Paths.get(ec.getString(targetKey));
                restrictPermissions(temp);
                resolver.atomicPublish(temp, target);
                String stageKey = base + SUFFIX_STAGE;
                if (ec.containsKey(stageKey)) {
                    stagingDirs.add(Paths.get(ec.getString(stageKey)));
                }
            }
            // Every temp has been renamed out; remove each now-empty private staging directory. This
            // runs only on the success path - a failed step (handled below) leaves the staging
            // directory and its temp in place for a restart to resume/re-publish.
            for (Path stagingDir : stagingDirs) {
                resolver.deleteQuietly(stagingDir);
            }
        } catch (RuntimeException ex) {
            // Finalization failed (e.g. BatchFilePathResolver.atomicPublish threw UncheckedIOException
            // because Files.move could not rename onto the target). Fail the step instead of letting
            // the exception be swallowed by the listener contract (finding F-01). The temp file - and
            // its private staging directory (P4-SEC-01) - are left in place for a restart to re-publish
            // once the cause is cleared (no staging directory is removed on this failure path).
            LOGGER.error("Atomic publication of completed step [{}] output failed; failing the step so "
                    + "the false-COMPLETED / silent data-loss defect (finding F-01) cannot occur",
                    stepExecution.getStepName(), ex);
            stepExecution.addFailureException(ex);
            stepExecution.setStatus(BatchStatus.FAILED);
            return ExitStatus.FAILED.addExitDescription(ex);
        }
        return stepExecution.getExitStatus();
    }

    /**
     * Collects the execution-context keys this listener owns that hold a resolved target path, taking
     * a snapshot so publication does not iterate the live context view.
     *
     * @param ec the step execution context
     * @return the list of target-path keys previously recorded by {@link #prepare}
     */
    private static List<String> registeredTargetKeys(ExecutionContext ec) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Object> entry : ec.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(KEY_PREFIX) && key.endsWith(SUFFIX_TARGET)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Best-effort tightening of {@code file} to owner-only ({@code 0600}) permissions before it is
     * published, where the filesystem supports POSIX permissions.
     *
     * @param file the in-progress temp file
     */
    private static void restrictPermissions(Path file) {
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    && file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, OWNER_ONLY);
            }
        } catch (IOException | UnsupportedOperationException ex) {
            LOGGER.debug("Unable to set owner-only permissions on batch output temp file", ex);
        }
    }
}
