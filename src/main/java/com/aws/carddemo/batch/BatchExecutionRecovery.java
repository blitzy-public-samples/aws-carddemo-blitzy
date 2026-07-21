package com.aws.carddemo.batch;

import java.time.LocalDateTime;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Recovers batch job instances that were left un-restartable by an <em>abnormal termination</em> of a
 * prior batch JVM, restoring the mainframe's abend-and-resubmit contract (review finding&nbsp;F-02).
 *
 * <p><strong>The defect.</strong> When a CardDemo batch job is launched as a process
 * ({@code --spring.main.web-application-type=none --spring.batch.job.enabled=true
 * --spring.batch.job.name=<job> ...}) and that process is killed mid-run ({@code SIGTERM} or
 * {@code SIGKILL}, a crash, or {@code kill -9}), Spring Batch never gets to finalize the run: the
 * {@code BATCH_JOB_EXECUTION} row is left {@code STARTED} (or {@code UNKNOWN}) permanently. On the next
 * launch of the <em>same</em> job with the <em>same</em> parameters, Spring Batch sees a last execution
 * that still looks like it is running and refuses to start with
 * {@link org.springframework.batch.core.repository.JobExecutionAlreadyRunningException} &mdash; the job
 * can never run again without manual metadata surgery. On z/OS the equivalent abended job could simply
 * be resubmitted; this class restores that behavior.</p>
 *
 * <p><strong>The fix &mdash; startup reconciliation (authoritative).</strong> As an
 * {@link ApplicationRunner} ordered at {@link Ordered#HIGHEST_PRECEDENCE} it runs <em>before</em> Spring
 * Boot's {@code JobLauncherApplicationRunner} (which launches the requested job at the default order).
 * When the application is started in CLI batch mode (i.e. {@code spring.batch.job.name} is set), it asks
 * {@link JobExplorer#findRunningJobExecutions(String)} for every execution of that job still marked
 * running and, for each one it can prove was orphaned by a dead process on this host, transitions it
 * (and its still-running step executions) to {@link BatchStatus#FAILED}. A {@code FAILED} last execution
 * makes the {@code JobInstance} restartable, so the immediately-following launch proceeds. Because this
 * runs on the <em>next</em> launch regardless of how the previous JVM died, it covers {@code SIGKILL}
 * (which runs no shutdown hook) just as well as {@code SIGTERM}; the {@code SIGKILL} case strictly
 * dominates, so handling it handles both.</p>
 *
 * <p><strong>Preserving the concurrent-run guard (must NOT regress).</strong> Spring Batch's refusal to
 * start a second execution while one is genuinely running is a correctness guarantee &mdash; it is what
 * stops two JVMs from double-posting the daily-transaction feed. Reconciliation must therefore never
 * touch a live run. The discriminator is the owner stamp written by {@link BatchExecutionOwnerListener}
 * (host + PID, persisted before the first chunk): an execution is reconciled <strong>only</strong> when
 * <em>all</em> of the following hold &mdash;</p>
 * <ol>
 *   <li>the owner host and PID are present in its execution context (a run started before this feature,
 *       or by another tool, is left untouched);</li>
 *   <li>the owner host equals this JVM's host (a run owned by a <em>different</em> machine cannot have
 *       its PID liveness judged here, so it is left untouched);</li>
 *   <li>the owner PID is not this JVM's own PID (defensive); and</li>
 *   <li>no live operating-system process with the owner PID exists on this host
 *       ({@link BatchExecutionOwnerListener#isProcessAlive(long)}).</li>
 * </ol>
 * <p>If the owner process is still alive, the execution is left exactly as-is and the next launch still
 * (correctly) fails with {@code JobExecutionAlreadyRunningException}. The check errs toward <em>not</em>
 * reconciling, so a genuinely concurrent run is never disturbed; in the residual PID-reuse case the
 * instance stays protected and an operator can recover it explicitly (see {@code docs/onboarding.md}).</p>
 *
 * <p><strong>Graceful-shutdown marker (defense-in-depth).</strong> {@link #destroy()} additionally makes
 * a best-effort attempt, on a clean context close in CLI batch mode, to mark this JVM's own still-running
 * executions {@link BatchStatus#STOPPED} (restartable). It never throws and is not required for
 * correctness &mdash; the startup reconciler is authoritative &mdash; but it leaves tidier metadata after
 * an orderly {@code SIGTERM}.</p>
 *
 * <p><strong>Mode gating.</strong> Every action is a no-op unless {@code spring.batch.job.name} is set,
 * so the online web application (which launches the report job asynchronously and sets no job name) and
 * the integration-test slices (which drive jobs through {@code JobLauncherTestUtils}) are completely
 * unaffected. Tests exercise the reconciliation logic by calling {@link #reconcileStaleExecutions(String)}
 * directly.</p>
 *
 * <p><strong>Origin (lineage):</strong> net-new operational infrastructure with no COBOL ancestor;
 * it restores the abend-and-resubmit recoverability of the legacy JES2/JCL batch tier
 * ({@code legacy/jcl/**}). Rationale is recorded in {@code docs/decision-log.md} (Explainability rule).</p>
 *
 * @see BatchExecutionOwnerListener
 * @see JobExecutionOwnerRegistrar
 */
@Component
public class BatchExecutionRecovery implements ApplicationRunner, DisposableBean, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchExecutionRecovery.class);

    /** Prefix of the exit description recorded on an execution failed by reconciliation. */
    private static final String RECONCILED_EXIT_DESCRIPTION =
            "Reconciled by BatchExecutionRecovery (finding F-02): owning process no longer alive";

    private final JobExplorer jobExplorer;

    private final JobRepository jobRepository;

    /**
     * The job name supplied on the command line via {@code --spring.batch.job.name}. Blank (the default)
     * in the online web application and in the integration-test slices, which disables every automatic
     * action of this bean.
     */
    private final String configuredJobName;

    /**
     * Creates the recovery runner over the Spring Boot auto-configured batch infrastructure.
     *
     * @param jobExplorer       explorer used to find running executions and reload their persisted
     *                          execution context; must not be {@code null}
     * @param jobRepository     repository used to persist the reconciled {@code FAILED}/{@code STOPPED}
     *                          state; must not be {@code null}
     * @param configuredJobName the {@code spring.batch.job.name} property, or blank when absent
     */
    public BatchExecutionRecovery(
            JobExplorer jobExplorer,
            JobRepository jobRepository,
            @Value("${spring.batch.job.name:}") String configuredJobName) {
        this.jobExplorer = jobExplorer;
        this.jobRepository = jobRepository;
        this.configuredJobName = configuredJobName;
    }

    /**
     * Runs first among application runners: in CLI batch mode, reconciles any stale execution of the
     * job about to be launched so the launch can proceed.
     *
     * @param args the incoming application arguments (unused; the job name is bound from configuration)
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!isBatchCliMode()) {
            return;
        }
        int reconciled = reconcileStaleExecutions(configuredJobName);
        if (reconciled > 0) {
            LOGGER.warn("Batch startup recovery reconciled {} stale execution(s) of job '{}' left by an "
                    + "abnormally terminated run; the job is now restartable", reconciled, configuredJobName);
        }
    }

    /**
     * Reconciles every stale, orphaned running execution of the named job to {@link BatchStatus#FAILED},
     * leaving live and non-attributable executions untouched. Public and side-effect-scoped so that
     * integration tests can drive it directly without a CLI launch.
     *
     * @param jobName the batch job name whose stale executions should be reconciled
     * @return the number of executions transitioned to {@code FAILED}
     */
    public int reconcileStaleExecutions(String jobName) {
        Set<JobExecution> running = jobExplorer.findRunningJobExecutions(jobName);
        int reconciled = 0;
        for (JobExecution execution : running) {
            if (isReconcilable(execution)) {
                markExecutionFailed(execution);
                reconciled++;
            }
        }
        return reconciled;
    }

    /**
     * Decides whether a running execution was orphaned by a dead process on this host and may therefore
     * be safely failed. See the class Javadoc for the full four-part rule; the method returns
     * {@code false} (leave untouched) for missing owner stamp, a different host, this JVM's own PID, or a
     * still-live owner process.
     *
     * @param execution a currently-running execution returned by the explorer (context already reloaded)
     * @return {@code true} if the execution is a reconcilable orphan, {@code false} otherwise
     */
    private boolean isReconcilable(JobExecution execution) {
        ExecutionContext context = execution.getExecutionContext();
        if (!context.containsKey(BatchExecutionOwnerListener.OWNER_HOST_KEY)
                || !context.containsKey(BatchExecutionOwnerListener.OWNER_PID_KEY)) {
            LOGGER.debug("Leaving execution {} untouched: no owner stamp present", execution.getId());
            return false;
        }
        String ownerHost = context.getString(BatchExecutionOwnerListener.OWNER_HOST_KEY, "");
        long ownerPid = context.getLong(BatchExecutionOwnerListener.OWNER_PID_KEY, -1L);
        if (!BatchExecutionOwnerListener.currentHost().equals(ownerHost)) {
            LOGGER.debug("Leaving execution {} untouched: owned by another host '{}'",
                    execution.getId(), ownerHost);
            return false;
        }
        if (ownerPid == BatchExecutionOwnerListener.currentPid()) {
            LOGGER.debug("Leaving execution {} untouched: owned by this JVM", execution.getId());
            return false;
        }
        if (BatchExecutionOwnerListener.isProcessAlive(ownerPid)) {
            LOGGER.debug("Leaving execution {} untouched: owner PID {} still alive",
                    execution.getId(), ownerPid);
            return false;
        }
        return true;
    }

    /**
     * Transitions an orphaned execution and its still-running step executions to {@code FAILED} and
     * persists the change, making the enclosing {@code JobInstance} restartable.
     *
     * @param execution the orphaned execution to fail
     */
    private void markExecutionFailed(JobExecution execution) {
        LocalDateTime now = LocalDateTime.now();
        for (StepExecution step : execution.getStepExecutions()) {
            BatchStatus status = step.getStatus();
            if (status != null && (status.isRunning() || status == BatchStatus.UNKNOWN)) {
                step.setStatus(BatchStatus.FAILED);
                step.setExitStatus(ExitStatus.FAILED.addExitDescription(RECONCILED_EXIT_DESCRIPTION));
                step.setEndTime(now);
                step.setLastUpdated(now);
                jobRepository.update(step);
            }
        }
        execution.setStatus(BatchStatus.FAILED);
        execution.setExitStatus(ExitStatus.FAILED.addExitDescription(RECONCILED_EXIT_DESCRIPTION));
        execution.setEndTime(now);
        execution.setLastUpdated(now);
        jobRepository.update(execution);
        LOGGER.warn("Reconciled stale batch execution {} of job '{}' to FAILED (owner process dead)",
                execution.getId(),
                execution.getJobInstance() != null
                        ? execution.getJobInstance().getJobName() : configuredJobName);
    }

    /**
     * Best-effort graceful-shutdown marker (defense-in-depth). On a clean context close in CLI batch
     * mode, marks this JVM's own still-running executions {@link BatchStatus#STOPPED} so an orderly
     * {@code SIGTERM} leaves restartable, tidy metadata. Never throws; the startup reconciler remains the
     * authoritative recovery path for hard kills.
     */
    @Override
    public void destroy() {
        if (!isBatchCliMode()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            long selfPid = BatchExecutionOwnerListener.currentPid();
            String selfHost = BatchExecutionOwnerListener.currentHost();
            for (JobExecution execution : jobExplorer.findRunningJobExecutions(configuredJobName)) {
                ExecutionContext context = execution.getExecutionContext();
                boolean ownedBySelf = selfHost.equals(
                                context.getString(BatchExecutionOwnerListener.OWNER_HOST_KEY, ""))
                        && context.getLong(BatchExecutionOwnerListener.OWNER_PID_KEY, -1L) == selfPid;
                if (ownedBySelf) {
                    execution.setStatus(BatchStatus.STOPPED);
                    execution.setExitStatus(ExitStatus.STOPPED.addExitDescription(
                            "Marked STOPPED on graceful shutdown by BatchExecutionRecovery (finding F-02)"));
                    execution.setEndTime(now);
                    execution.setLastUpdated(now);
                    jobRepository.update(execution);
                    LOGGER.info("Marked this JVM's running execution {} STOPPED on graceful shutdown",
                            execution.getId());
                }
            }
        } catch (RuntimeException ex) {
            LOGGER.debug("Graceful-shutdown STOPPED marking skipped ({}); startup reconciliation will "
                    + "recover any stale execution on the next launch", ex.toString());
        }
    }

    /**
     * Runs before Spring Boot's job launcher so a stale execution is cleared before the new launch.
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Whether the application was started to run a specific batch job from the command line.
     *
     * @return {@code true} if {@code spring.batch.job.name} is set (non-blank)
     */
    private boolean isBatchCliMode() {
        return configuredJobName != null && !configuredJobName.isBlank();
    }
}
