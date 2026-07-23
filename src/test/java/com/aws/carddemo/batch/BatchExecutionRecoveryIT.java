package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.aws.carddemo.AbstractPostgresIntegrationTest;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Testcontainers integration test for {@link BatchExecutionRecovery} and its collaborators
 * {@link BatchExecutionOwnerListener} and {@link JobExecutionOwnerRegistrar} &mdash; the
 * abnormal-termination recovery that restores the mainframe abend-and-resubmit contract
 * (review finding&nbsp;F-02).
 *
 * <p>These are the runtime parity oracles for the fix. They drive the real Spring Batch JDBC
 * metadata (created by the {@code V0__spring_batch_metadata.sql} Flyway migration in the shared
 * PostgreSQL container) exactly as a killed-and-relaunched batch process would, and assert both
 * halves of the contract:</p>
 * <ol>
 *   <li><strong>Recovery.</strong> A running execution orphaned by a <em>dead</em> process on this
 *       host is reconciled to {@link BatchStatus#FAILED} so the job instance becomes restartable
 *       ({@link #deadOwnerOnThisHostIsReconciledAndBecomesRestartable()}).</li>
 *   <li><strong>Non-regression of the concurrent-run guard.</strong> A running execution whose owner
 *       process is still <em>alive</em> is left untouched, so a second launch of the same job still
 *       (correctly) fails with {@link JobExecutionAlreadyRunningException}
 *       ({@link #liveOwnerOnThisHostIsNeverReconciled()}). Executions owned by another host
 *       ({@link #executionOwnedByAnotherHostIsNeverReconciled()}) or carrying no owner stamp
 *       ({@link #executionWithoutOwnerStampIsNeverReconciled()}) are likewise left untouched.</li>
 * </ol>
 * <p>A final test ({@link #ownerStampIsPersistedForARealJobRun()}) launches a real job through
 * {@link JobLauncherTestUtils} and confirms the {@link JobExecutionOwnerRegistrar} attached the
 * listener and the owner stamp was durably persisted &mdash; the wiring on which recovery depends.</p>
 *
 * <p><strong>Why the reconciler is invoked directly.</strong> {@link BatchExecutionRecovery}'s
 * automatic {@link org.springframework.boot.ApplicationRunner ApplicationRunner} path is gated on the
 * {@code spring.batch.job.name} property (CLI batch mode), which these tests deliberately do not set,
 * so it is a no-op here; the tests therefore call {@link BatchExecutionRecovery#reconcileStaleExecutions(String)}
 * directly to exercise the logic deterministically without spawning a second JVM.</p>
 *
 * <p><strong>Origin:</strong> net-new operational-recovery test with no COBOL ancestor; it validates
 * the restart behavior that the legacy JES2/JCL batch tier provided implicitly. Rationale is recorded
 * in {@code docs/decision-log.md} (Explainability rule).</p>
 *
 * @see BatchExecutionRecovery
 * @see BatchExecutionOwnerListener
 * @see JobExecutionOwnerRegistrar
 */
@SpringBootTest(classes = {
        BatchExecutionRecoveryIT.RecoverySliceConfig.class,
        BatchExecutionRecoveryIT.RecoveryHarnessConfig.class
}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "management.prometheus.metrics.export.enabled=false"
})
class BatchExecutionRecoveryIT extends AbstractPostgresIntegrationTest {

    /**
     * Focused Spring Boot slice pinned as the sole context configuration. {@link EnableAutoConfiguration}
     * brings up the container {@code DataSource}, Flyway (which applies {@code V0} to create the
     * {@code BATCH_*} metadata) and the auto-configured Spring Batch {@link JobRepository},
     * {@link JobExplorer} and {@link JobLauncher}. The three production recovery beans are imported
     * explicitly (they are {@code @Component}s not reached by this pinned slice's component scan), and a
     * trivial probe {@link Job} is provided so the owner-stamp wiring can be exercised end-to-end.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
            BatchExecutionOwnerListener.class,
            JobExecutionOwnerRegistrar.class,
            BatchExecutionRecovery.class
    })
    static class RecoverySliceConfig {

        /**
         * A trivial single-tasklet job used only to prove that {@link JobExecutionOwnerRegistrar}
         * attaches {@link BatchExecutionOwnerListener} to real job beans and that the stamp is
         * persisted. It performs no business work.
         *
         * @param jobRepository      the auto-configured job repository
         * @param transactionManager the auto-configured transaction manager
         * @return the probe job
         */
        @Bean
        Job recoveryProbeJob(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
            Step step = new StepBuilder("recoveryProbeStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> RepeatStatus.FINISHED, transactionManager)
                    .build();
            return new JobBuilder("recoveryProbeJob", jobRepository).start(step).build();
        }
    }

    /**
     * Supplies the single {@link JobLauncherTestUtils}, bound to {@code recoveryProbeJob}, following the
     * project's explicit-harness convention (rather than {@code @SpringBatchTest}).
     */
    @TestConfiguration
    static class RecoveryHarnessConfig {

        /**
         * Builds the {@link JobLauncherTestUtils} bound to the probe job.
         *
         * @param jobLauncher      the auto-configured shared launcher
         * @param jobRepository    the auto-configured shared repository
         * @param recoveryProbeJob the probe job resolved by qualifier
         * @return the configured test utility
         */
        @Bean
        JobLauncherTestUtils recoveryProbeJobLauncherTestUtils(
                JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier("recoveryProbeJob") Job recoveryProbeJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(recoveryProbeJob);
            return utils;
        }
    }

    /** Synthetic job name used for the seeded metadata scenarios (never a real bean; not launched). */
    private static final String STALE_JOB_NAME = "f02RecoveryStaleJob";

    @Autowired
    private BatchExecutionRecovery batchExecutionRecovery;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * A running execution left by a now-dead process on this host is reconciled to {@code FAILED},
     * unblocking a restart. Before reconciliation the trap is proven real (a second launch of the same
     * instance is refused); after reconciliation the same instance restarts.
     *
     * @throws Exception if seeding the batch metadata fails
     */
    @Test
    @DisplayName("Dead owner on this host: stale execution is reconciled to FAILED and the job restarts")
    void deadOwnerOnThisHostIsReconciledAndBecomesRestartable() throws Exception {
        long deadPid = spawnAndReapProcess();
        assumeFalse(BatchExecutionOwnerListener.isProcessAlive(deadPid),
                "spawned probe PID unexpectedly still alive (PID reuse); skipping");

        JobParameters params = uniqueParams();
        JobExecution stale = seedRunningExecution(
                params, BatchExecutionOwnerListener.currentHost(), deadPid);

        // The defect: while the stale execution looks running, the same instance cannot be relaunched.
        assertThatThrownBy(() -> jobRepository.createJobExecution(STALE_JOB_NAME, params))
                .isInstanceOf(JobExecutionAlreadyRunningException.class);

        int reconciled = batchExecutionRecovery.reconcileStaleExecutions(STALE_JOB_NAME);

        assertThat(reconciled).isEqualTo(1);
        JobExecution reloaded = jobExplorer.getJobExecution(stale.getId());
        assertThat(reloaded.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(reloaded.getEndTime()).isNotNull();
        assertThat(reloaded.getStepExecutions())
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo(BatchStatus.FAILED));

        // The fix: the instance is now restartable — a fresh execution for the same instance is created.
        JobExecution restart = jobRepository.createJobExecution(STALE_JOB_NAME, params);
        assertThat(restart).isNotNull();
        assertThat(restart.getId()).isNotEqualTo(stale.getId());
        assertThat(restart.getJobInstance().getInstanceId())
                .isEqualTo(stale.getJobInstance().getInstanceId());
    }

    /**
     * A running execution whose owner process is still alive is never reconciled, so Spring Batch's
     * concurrent-run guard is preserved: a second launch of the same instance still fails with
     * {@link JobExecutionAlreadyRunningException}. This is the critical non-regression assertion.
     *
     * @throws Exception if seeding the batch metadata fails
     */
    @Test
    @DisplayName("Live owner on this host: never reconciled; concurrent-run refusal preserved")
    void liveOwnerOnThisHostIsNeverReconciled() throws Exception {
        Process alive = new ProcessBuilder("sleep", "60").start();
        try {
            long alivePid = alive.pid();
            assumeFalse(!alive.isAlive(), "spawned keep-alive process is not running; skipping");

            JobParameters params = uniqueParams();
            JobExecution stale = seedRunningExecution(
                    params, BatchExecutionOwnerListener.currentHost(), alivePid);

            int reconciled = batchExecutionRecovery.reconcileStaleExecutions(STALE_JOB_NAME);

            assertThat(reconciled).isZero();
            assertThat(jobExplorer.getJobExecution(stale.getId()).getStatus())
                    .isEqualTo(BatchStatus.STARTED);
            // Concurrent-run guard still holds: the same instance cannot be relaunched.
            assertThatThrownBy(() -> jobRepository.createJobExecution(STALE_JOB_NAME, params))
                    .isInstanceOf(JobExecutionAlreadyRunningException.class);
        } finally {
            alive.destroyForcibly();
            alive.waitFor();
        }
    }

    /**
     * A running execution stamped with a <em>different</em> host is never reconciled here, because this
     * JVM cannot judge the liveness of a remote process.
     *
     * @throws Exception if seeding the batch metadata fails
     */
    @Test
    @DisplayName("Owner on another host: never reconciled")
    void executionOwnedByAnotherHostIsNeverReconciled() throws Exception {
        JobParameters params = uniqueParams();
        JobExecution stale = seedRunningExecution(
                params, "some-other-host-" + UUID.randomUUID(), 999_999L);

        int reconciled = batchExecutionRecovery.reconcileStaleExecutions(STALE_JOB_NAME);

        assertThat(reconciled).isZero();
        assertThat(jobExplorer.getJobExecution(stale.getId()).getStatus())
                .isEqualTo(BatchStatus.STARTED);
    }

    /**
     * A running execution with no owner stamp (e.g. started before this feature, or by another tool) is
     * never reconciled &mdash; the conservative default.
     *
     * @throws Exception if seeding the batch metadata fails
     */
    @Test
    @DisplayName("No owner stamp: never reconciled")
    void executionWithoutOwnerStampIsNeverReconciled() throws Exception {
        JobParameters params = uniqueParams();
        JobExecution stale = seedRunningExecution(params, null, -1L);

        int reconciled = batchExecutionRecovery.reconcileStaleExecutions(STALE_JOB_NAME);

        assertThat(reconciled).isZero();
        assertThat(jobExplorer.getJobExecution(stale.getId()).getStatus())
                .isEqualTo(BatchStatus.STARTED);
    }

    /**
     * Launching a real job proves the {@link JobExecutionOwnerRegistrar} attached the
     * {@link BatchExecutionOwnerListener} and that the owner stamp (host + PID) was durably persisted to
     * the execution context &mdash; the mechanism the startup reconciler relies on after a crash.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("Owner stamp (host + PID) is persisted for a real job run")
    void ownerStampIsPersistedForARealJobRun() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.launchJob(params);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        JobExecution reloaded = jobExplorer.getJobExecution(execution.getId());
        assertThat(reloaded.getExecutionContext().containsKey(BatchExecutionOwnerListener.OWNER_HOST_KEY))
                .as("owner host stamp persisted")
                .isTrue();
        assertThat(reloaded.getExecutionContext().containsKey(BatchExecutionOwnerListener.OWNER_PID_KEY))
                .as("owner PID stamp persisted")
                .isTrue();
        assertThat(reloaded.getExecutionContext()
                .getString(BatchExecutionOwnerListener.OWNER_HOST_KEY))
                .isEqualTo(BatchExecutionOwnerListener.currentHost());
        assertThat(reloaded.getExecutionContext()
                .getLong(BatchExecutionOwnerListener.OWNER_PID_KEY))
                .isEqualTo(BatchExecutionOwnerListener.currentPid());
    }

    /**
     * Seeds a persisted, {@code STARTED} job execution with a single {@code STARTED} step, optionally
     * stamped with an owner host and PID, mimicking the durable metadata a killed batch JVM leaves
     * behind.
     *
     * @param params    the identifying job parameters for the seeded instance
     * @param ownerHost the owner host to stamp, or {@code null} to seed no owner stamp
     * @param ownerPid  the owner PID to stamp (ignored when {@code ownerHost} is {@code null})
     * @return the seeded, persisted job execution
     * @throws Exception if the batch metadata cannot be created (e.g. the instance already exists)
     */
    private JobExecution seedRunningExecution(JobParameters params, String ownerHost, long ownerPid)
            throws Exception {
        JobExecution execution = jobRepository.createJobExecution(STALE_JOB_NAME, params);
        execution.setStartTime(LocalDateTime.now());
        execution.setStatus(BatchStatus.STARTED);
        if (ownerHost != null) {
            execution.getExecutionContext()
                    .putString(BatchExecutionOwnerListener.OWNER_HOST_KEY, ownerHost);
            execution.getExecutionContext()
                    .putLong(BatchExecutionOwnerListener.OWNER_PID_KEY, ownerPid);
        }
        jobRepository.update(execution);
        jobRepository.updateExecutionContext(execution);

        StepExecution step = new StepExecution("seededStep", execution);
        step.setStartTime(LocalDateTime.now());
        step.setStatus(BatchStatus.STARTED);
        jobRepository.add(step);
        step.setStatus(BatchStatus.STARTED);
        jobRepository.update(step);
        return execution;
    }

    /**
     * Builds a unique set of identifying job parameters so each test seeds a distinct job instance.
     *
     * @return fresh job parameters
     */
    private JobParameters uniqueParams() {
        return new JobParametersBuilder()
                .addString("seed", UUID.randomUUID().toString())
                .toJobParameters();
    }

    /**
     * Spawns a trivial child process, waits for it to exit, and returns its now-dead PID &mdash; a
     * genuinely non-alive process id on this host (as opposed to a guessed constant).
     *
     * @return the PID of a process that has exited
     * @throws Exception if the child process cannot be started or joined
     */
    private long spawnAndReapProcess() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "exit 0").start();
        long pid = process.pid();
        process.waitFor();
        return pid;
    }
}
