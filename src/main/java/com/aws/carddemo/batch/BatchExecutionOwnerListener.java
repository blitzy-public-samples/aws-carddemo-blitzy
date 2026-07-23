package com.aws.carddemo.batch;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.stereotype.Component;

/**
 * Stamps the identity of the JVM that owns a running batch {@link JobExecution} into its persisted
 * {@link ExecutionContext}, so that a later launch can tell whether a {@code STARTED}/{@code UNKNOWN}
 * execution left behind by an <em>abnormally terminated</em> run belongs to a process that is still
 * alive or to one that has died (review finding&nbsp;F-02).
 *
 * <p><strong>Why this exists (mainframe parity).</strong> On z/OS a JCL job that abends or is
 * cancelled leaves its JES2 job in a terminal state and its resources recoverable; the operator can
 * simply resubmit. The migrated Spring Batch equivalent does not get that for free: when the batch
 * JVM is killed with {@code SIGTERM}/{@code SIGKILL} mid-run, the {@code BATCH_JOB_EXECUTION} row is
 * left {@code STARTED} (or {@code UNKNOWN}) forever, and because Spring Batch refuses to start a new
 * execution for a {@link org.springframework.batch.core.JobInstance JobInstance} whose last execution
 * still looks like it is running, the <em>same</em> job can never be restarted &mdash; it fails at
 * launch with {@link org.springframework.batch.core.repository.JobExecutionAlreadyRunningException}.
 * That refusal is correct and must be preserved while a run is genuinely in flight (it is what stops
 * two JVMs from posting the daily-transaction feed twice), but it becomes a liveness trap after an
 * abnormal termination. {@link BatchExecutionRecovery} breaks that trap on the next launch, and it
 * can only do so safely because this listener records <em>who</em> was running the execution.</p>
 *
 * <p><strong>What is stamped.</strong> In {@link #beforeJob(JobExecution)} &mdash; which Spring Batch
 * invokes after the {@code JobExecution} row already exists and is marked {@code STARTED} &mdash; this
 * listener writes two entries into the job-level {@link ExecutionContext}:</p>
 * <ul>
 *   <li>{@link #OWNER_HOST_KEY} &mdash; the host name of this JVM ({@link #currentHost()}); and</li>
 *   <li>{@link #OWNER_PID_KEY} &mdash; the operating-system process id of this JVM
 *       ({@link #currentPid()}).</li>
 * </ul>
 * <p>It then calls {@link JobRepository#updateExecutionContext(JobExecution)} so the stamp is
 * <em>durably persisted immediately</em>, before any chunk is processed. This ordering is essential:
 * a {@code SIGKILL} runs no shutdown hook, so only state already committed to the
 * {@code BATCH_JOB_EXECUTION_CONTEXT} table survives the crash. Spring Batch's
 * {@code SimpleJobExplorer.findRunningJobExecutions(...)} reloads that persisted context on the next
 * launch, so {@link BatchExecutionRecovery} sees the stamp even though the JVM that wrote it is gone.</p>
 *
 * <p><strong>Scope and safety.</strong> The listener is attached to <em>every</em> batch job centrally
 * by {@link JobExecutionOwnerRegistrar} (a {@code BeanPostProcessor}), so no per-job wiring is needed
 * and no job can be forgotten. Stamping is harmless in every mode: for the online report job launched
 * asynchronously in the web tier it simply records the web JVM as owner (never reconciled, because
 * {@link BatchExecutionRecovery} only acts in CLI batch mode); for the integration-test slices it adds
 * two context entries that no assertion inspects. It records only JVM identity &mdash; never business
 * data &mdash; and never throws in a way that could mask a job's own outcome.</p>
 *
 * <p><strong>Origin (lineage):</strong> net-new operational infrastructure with no single COBOL
 * ancestor; it restores the JES2 abend-and-resubmit recoverability of the legacy batch tier
 * ({@code legacy/jcl/**}) under Spring Batch. Rationale is recorded in {@code docs/decision-log.md},
 * not in code comments (Explainability rule).</p>
 *
 * @see BatchExecutionRecovery
 * @see JobExecutionOwnerRegistrar
 */
@Component
public class BatchExecutionOwnerListener implements JobExecutionListener {

    /**
     * Job-execution-context key under which the owning JVM's host name is stored. Package-private so
     * {@link BatchExecutionRecovery} (same package) reads it back when deciding whether a stale
     * execution belongs to this host.
     */
    static final String OWNER_HOST_KEY = "carddemo.owner.host";

    /**
     * Job-execution-context key under which the owning JVM's OS process id is stored. Package-private
     * so {@link BatchExecutionRecovery} reads it back to probe process liveness.
     */
    static final String OWNER_PID_KEY = "carddemo.owner.pid";

    /** Fallback host name used only if the local host name cannot be resolved at all. */
    private static final String UNKNOWN_HOST = "unknown-host";

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchExecutionOwnerListener.class);

    /** JDBC-backed repository used to persist the owner stamp immediately (before the first chunk). */
    private final JobRepository jobRepository;

    /**
     * Creates the listener over the Spring Boot auto-configured, JDBC-backed job repository.
     *
     * @param jobRepository the durable job repository; must not be {@code null}
     */
    public BatchExecutionOwnerListener(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * Records this JVM's host name and process id in the job execution context and persists it
     * immediately, so an abnormal termination leaves behind a durable owner stamp that
     * {@link BatchExecutionRecovery} can use on the next launch.
     *
     * @param jobExecution the execution that is about to run (already {@code STARTED} and persisted)
     */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        ExecutionContext context = jobExecution.getExecutionContext();
        String host = currentHost();
        long pid = currentPid();
        context.putString(OWNER_HOST_KEY, host);
        context.putLong(OWNER_PID_KEY, pid);
        jobRepository.updateExecutionContext(jobExecution);
        LOGGER.debug("Stamped batch execution owner host={} pid={} for job '{}' (executionId={})",
                host, pid,
                jobExecution.getJobInstance() != null
                        ? jobExecution.getJobInstance().getJobName() : "unknown",
                jobExecution.getId());
    }

    /**
     * Resolves the host name of the JVM running this listener.
     *
     * <p>The value only needs to be <em>stable across a crash and the subsequent restart on the same
     * machine</em>, which {@link InetAddress#getLocalHost()} provides (the container/pod host name).
     * If resolution fails, the {@code HOSTNAME} environment variable is used, and finally a constant,
     * so this method never throws.</p>
     *
     * @return the current host name, or a deterministic fallback
     */
    static String currentHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            String env = System.getenv("HOSTNAME");
            return env != null && !env.isBlank() ? env : UNKNOWN_HOST;
        }
    }

    /**
     * Returns the operating-system process id of this JVM.
     *
     * @return this JVM's PID
     */
    static long currentPid() {
        return ProcessHandle.current().pid();
    }

    /**
     * Tests whether an operating-system process with the given id is currently alive on this host.
     *
     * <p>A pid that the OS no longer knows about resolves to an empty {@link ProcessHandle}, which is
     * reported as not alive. The check errs toward reporting {@code true} (alive) only when the OS
     * genuinely still has the process, so {@link BatchExecutionRecovery} never reconciles a live run.</p>
     *
     * @param pid the process id read from a stale execution's owner stamp
     * @return {@code true} if a process with that id is alive on this host, {@code false} otherwise
     */
    static boolean isProcessAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
