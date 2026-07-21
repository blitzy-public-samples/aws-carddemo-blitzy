package com.aws.carddemo.util.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * Unit test for {@link AtomicFileStepPublisher}, the shared step listener that atomically publishes a
 * batch step's in-progress temp file onto its final target on {@code COMPLETED}.
 *
 * <p><strong>Primary gate (QA finding&nbsp;F-01).</strong> These tests lock down that a failure of the
 * final atomic rename in {@link AtomicFileStepPublisher#afterStep(StepExecution)} <em>fails the step</em>
 * rather than being swallowed. Before the fix the publish loop had no error handling, so an
 * {@link UncheckedIOException} from {@code BatchFilePathResolver.atomicPublish} propagated out of the
 * {@code afterStep} listener callback &mdash; and because Spring Batch invokes that callback only
 * <em>after</em> it has already set {@link BatchStatus#COMPLETED} and merely logs a thrown listener
 * exception, the step (and its job) reported {@code COMPLETED}/exit&nbsp;0 while the byte-exact output
 * file was never published (orphaned as an {@code .inprogress} temp). The fix catches the failure and
 * returns {@link ExitStatus#FAILED} with the step forced to {@link BatchStatus#FAILED}.</p>
 *
 * <p>The tests drive the listener directly with a {@link MetaDataInstanceFactory}-built
 * {@link StepExecution} and a real {@link BatchFilePathResolver} rooted at a JUnit {@link TempDir}, so
 * they exercise the genuine filesystem publish path with no Spring context or database. The reliable,
 * deterministic finalization-failure trigger is a target path that already exists as a
 * <em>non-empty directory</em>: the atomic {@code Files.move} onto it raises
 * {@code DirectoryNotEmptyException}, which the resolver rethrows as {@code UncheckedIOException}.</p>
 *
 * @see AtomicFileStepPublisher
 * @see BatchFilePathResolver
 */
class AtomicFileStepPublisherTest {

    @TempDir
    Path root;

    private AtomicFileStepPublisher newPublisher() {
        return new AtomicFileStepPublisher(new BatchFilePathResolver(List.of(root.toString())));
    }

    private static StepExecution completedStep() {
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        stepExecution.setStatus(BatchStatus.COMPLETED);
        stepExecution.setExitStatus(ExitStatus.COMPLETED);
        return stepExecution;
    }

    /**
     * Success path: a completed step's temp file is atomically renamed onto its final target, the
     * target holds the exact bytes, the temp is gone, and the step keeps its {@code COMPLETED} status.
     */
    @Test
    @DisplayName("completed step publishes its temp atomically onto the target and removes the temp")
    void completedStepPublishesTempAndRemovesIt() throws IOException {
        AtomicFileStepPublisher publisher = newPublisher();
        StepExecution stepExecution = completedStep();

        Path target = root.resolve("reject.out");
        Path temp = publisher.prepare(target.toString(), stepExecution);
        Files.writeString(temp, "REJECTED-RECORD\n");

        ExitStatus result = publisher.afterStep(stepExecution);

        assertThat(result.getExitCode())
                .as("success path returns the step's own COMPLETED exit status")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(Files.exists(target)).as("final target published").isTrue();
        assertThat(Files.readString(target)).isEqualTo("REJECTED-RECORD\n");
        assertThat(Files.exists(temp)).as("in-progress temp removed after publication").isFalse();
        assertThat(stepExecution.getFailureExceptions()).isEmpty();
    }

    /**
     * The F-01 gate: when the atomic rename fails (target is a pre-existing non-empty directory), the
     * step is failed &mdash; {@code afterStep} returns {@link ExitStatus#FAILED}, the step status is
     * {@link BatchStatus#FAILED}, the cause is recorded on the step, and the {@code .inprogress} temp
     * is intentionally left in place for a restart to re-publish. Before the fix this returned
     * {@code COMPLETED} with the output silently lost.
     */
    @Test
    @DisplayName("F-01: publication failure fails the step, records the cause, and leaves the temp")
    void publicationFailureFailsStepAndLeavesTemp() throws IOException {
        AtomicFileStepPublisher publisher = newPublisher();
        StepExecution stepExecution = completedStep();

        // Target path already exists as a NON-EMPTY directory -> the atomic move onto it must fail.
        Path target = root.resolve("reject.out");
        Files.createDirectory(target);
        Files.writeString(target.resolve("blocker"), "x");

        Path temp = publisher.prepare(target.toString(), stepExecution);
        Files.writeString(temp, "REJECTED-RECORD\n");

        ExitStatus result = publisher.afterStep(stepExecution);

        assertThat(result.getExitCode())
                .as("finalization failure surfaces as ExitStatus.FAILED (not swallowed as COMPLETED)")
                .isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(result.getExitDescription())
                .as("the failure cause is attached to the exit description").isNotBlank();
        assertThat(stepExecution.getStatus())
                .as("the already-COMPLETED step is flipped to FAILED").isEqualTo(BatchStatus.FAILED);
        assertThat(stepExecution.getFailureExceptions())
                .as("the throwable is recorded on the step execution").isNotEmpty();
        assertThat(stepExecution.getFailureExceptions().get(0))
                .as("the recorded cause is the publish UncheckedIOException")
                .isInstanceOf(UncheckedIOException.class);
        assertThat(Files.exists(temp))
                .as("the in-progress temp is left in place for a restart to re-publish").isTrue();
        assertThat(Files.isDirectory(target))
                .as("the final target was never partially overwritten").isTrue();
    }

    /**
     * Rollback safety: a non-completed step publishes nothing, returns its own exit status, and leaves
     * every temp in place for a restart (the pre-existing behavior, guarded here against regression).
     */
    @Test
    @DisplayName("non-completed step publishes nothing and leaves its temp for restart")
    void nonCompletedStepLeavesTempUntouched() throws IOException {
        AtomicFileStepPublisher publisher = newPublisher();
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        stepExecution.setStatus(BatchStatus.FAILED);
        stepExecution.setExitStatus(ExitStatus.FAILED);

        Path target = root.resolve("reject.out");
        Path temp = publisher.prepare(target.toString(), stepExecution);
        Files.writeString(temp, "PARTIAL\n");

        ExitStatus result = publisher.afterStep(stepExecution);

        assertThat(result.getExitCode())
                .as("a non-completed step returns its own exit status unchanged")
                .isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(Files.exists(target)).as("nothing is published for a non-completed step").isFalse();
        assertThat(Files.exists(temp)).as("the temp is preserved for a restart").isTrue();
    }
}
