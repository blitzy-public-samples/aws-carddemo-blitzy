package com.aws.carddemo.config;

import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch tuning for the CardDemo migration (JCL/JES2 -&gt; Spring Batch).
 *
 * <p>This {@code @Configuration} class is the single Java home for cross-job <em>batch tuning</em>
 * in the AWS CardDemo COBOL&rarr;Java migration. It centralizes shared chunk-size (commit-interval)
 * constants and an optional, reusable job-execution logging listener. It deliberately owns
 * <strong>no</strong> orchestration: it declares no {@code Job}, {@code Step}, {@code JobRepository},
 * {@code JobLauncher}, {@code JobExplorer}, {@code JobRegistry} or {@code JobOperator} bean. The 13
 * business/utility batch jobs in the sibling package {@code com.aws.carddemo.batch} each define
 * their own {@code Job}/{@code Step} beans and constructor-inject the auto-configured
 * {@code JobRepository} and {@link org.springframework.transaction.PlatformTransactionManager};
 * this class provides only shared tuning that those jobs may reference by value.</p>
 *
 * <p><strong>WARNING &mdash; do NOT add {@code @EnableBatchProcessing} to this class (or any class).</strong>
 * Under Spring Boot 3.x, {@code BatchAutoConfiguration} supplies a <em>persistent, restartable,
 * JDBC-backed</em> {@code JobRepository} (plus {@code JobLauncher}, {@code JobExplorer} and
 * {@code JobRegistry}) wired to the application {@code DataSource} &mdash; but only while
 * {@code @EnableBatchProcessing} is <em>absent</em>. Adding that annotation causes Boot's batch
 * auto-configuration to back off, and current Spring Batch 5 then substitutes a non-persistent
 * {@code ResourcelessJobRepository} (in-memory), which destroys job restartability and the
 * {@code BATCH_*} metadata tables. That would be a functional regression versus the mainframe's
 * JES2/checkpoint-restart behavior, so the annotation is intentionally omitted here exactly as it
 * is in {@code com.aws.carddemo.CardDemoApplication}. The rationale is recorded in
 * {@code docs/decision-log.md}; do not "helpfully" re-add it.</p>
 *
 * <p><strong>Origin (lineage):</strong> {@code legacy/jcl/**} batch jobs (e.g.,
 * {@code POSTTRAN.jcl}, {@code INTCALC.jcl}) and {@code legacy/proc/**} &mdash; the mainframe step
 * topology and commit semantics are realized as chunk-oriented Spring Batch steps in package
 * {@code com.aws.carddemo.batch}. No JCL logic is reproduced in this class; the citation is context
 * only. For example, {@code POSTTRAN.jcl} runs {@code CBTRN02C} to post the daily-transaction feed
 * while mutating the account and transaction-category-balance records and writing rejects, and
 * {@code INTCALC.jcl} runs {@code CBACT04C} to compute monthly interest with a {@code PARM} date;
 * their record-processing shapes motivate the two commit-interval constants below.</p>
 *
 * <p><strong>Required batch policy (owned by the resources agent in {@code application.yml}; this
 * class cannot set it):</strong></p>
 * <ul>
 *   <li><strong>{@code spring.batch.job.enabled=false}</strong> &mdash; CRITICAL. Prevents Boot's
 *       {@code JobLauncherApplicationRunner} from auto-running every {@code Job} bean at application
 *       startup. CardDemo is an online application; batch jobs are launched on demand (for example,
 *       {@code ReportSubmitService} launches {@code transactionReportJob} from the {@code /report}
 *       screen). Without this flag, all 12 jobs would fire on boot.</li>
 *   <li><strong>{@code spring.batch.jdbc.initialize-schema=never}</strong> (on every profile).
 *       Flyway is the single source of truth for all DDL in this application, so the {@code BATCH_*}
 *       metadata tables and sequences are created by the versioned migration
 *       {@code db/migration/V0__spring_batch_metadata.sql} (copied verbatim from
 *       {@code spring-batch-core}'s own {@code schema-postgresql.sql}) rather than by Boot's schema
 *       initializer. The previous {@code embedded} setting behaved as {@code never} on PostgreSQL
 *       and left these tables absent, so every {@code Job} failed on first execution and could not
 *       restart (review finding F2). The chosen value and its rationale are recorded in
 *       {@code docs/decision-log.md}.</li>
 * </ul>
 *
 * <p>This bean is stateless and thread-safe: it exposes immutable {@code int} constants and a
 * stateless listener whose only side effect is INFO-level logging of job-level metadata.</p>
 */
@Configuration
public class BatchConfig {

    /**
     * Default chunk size (commit interval) for read-only "print" and "report" chunk steps.
     *
     * <p>These jobs mirror the COBOL record-at-a-time read loops (e.g., the account, card, xref and
     * customer print jobs and the transaction report). Because each processed record is independent
     * and no shared running total is mutated mid-step, a moderate commit interval of {@value}
     * balances throughput against memory and transaction-log pressure without affecting observable
     * results. This is the default a job should adopt unless it has accumulating semantics (see
     * {@link #ACCUMULATING_CHUNK_SIZE}). Jobs reference this value by value; the choice is recorded
     * in {@code docs/decision-log.md}.</p>
     */
    public static final int DEFAULT_CHUNK_SIZE = 100;

    /**
     * Commit interval of {@value} for jobs whose processor mutates shared running totals or
     * dependent records for each input item.
     *
     * <p>The canonical example is daily transaction posting ({@code legacy/jcl/POSTTRAN.jcl} &rarr;
     * {@code CBTRN02C}), whose processor updates the {@code Account} and
     * {@code TransactionCategoryBalance} records and writes rejects (e.g., an over-limit
     * transaction) as it consumes each daily-transaction record. Committing one record at a time
     * preserves COBOL's record-by-record commit visibility and the exact reject-on-error semantics,
     * so a mid-file failure leaves the same set of committed records as the mainframe job. The
     * interest calculator ({@code legacy/jcl/INTCALC.jcl} &rarr; {@code CBACT04C}) has the same
     * accumulating shape. The choice is recorded in {@code docs/decision-log.md}.</p>
     */
    public static final int ACCUMULATING_CHUNK_SIZE = 1;

    /**
     * Reusable, opt-in {@link JobExecutionListener} that logs structured job-level lifecycle
     * information (job name on start; job name, batch status, exit code and elapsed duration on
     * completion) at INFO level.
     *
     * <p>The listener is <em>not</em> auto-attached to any job. A job in
     * {@code com.aws.carddemo.batch} may register it explicitly with
     * {@code new JobBuilder(name, jobRepository).listener(batchJobLoggingListener)...}; if no job
     * registers it, it remains an unused bean at runtime, which is harmless and warning-free. It
     * supports the Observability rule by emitting correlation-friendly, structured lines that the
     * {@code logback-spring.xml} configuration (owned by the resources agent) enriches with MDC
     * correlation identifiers.</p>
     *
     * <p>The listener records only job-level metadata &mdash; never record content &mdash; so it is
     * safe to attach to the print/report jobs that stream account and customer data.</p>
     *
     * @return a stateless, thread-safe job-execution logging listener
     */
    @Bean
    public JobExecutionListener batchJobLoggingListener() {
        return new BatchJobLoggingListener();
    }

    /**
     * Stateless {@link JobExecutionListener} implementation used by {@link #batchJobLoggingListener()}.
     *
     * <p>Both {@code beforeJob} and {@code afterJob} are {@code default} methods on the interface, so
     * a lambda cannot express this listener; a concrete type is required. Every access to the
     * {@link JobExecution} is null-guarded so the listener never throws and never masks a job's own
     * exit status. It logs job-level metadata exclusively and holds no mutable state, making it safe
     * to share across concurrent job executions.</p>
     */
    private static final class BatchJobLoggingListener implements JobExecutionListener {

        /** Sentinel returned when the elapsed duration cannot be computed (missing timestamps). */
        private static final long DURATION_UNKNOWN = -1L;

        /** Placeholder logged when the job name is not resolvable from the execution. */
        private static final String UNKNOWN_JOB_NAME = "unknown";

        private static final Logger LOG = LoggerFactory.getLogger(BatchJobLoggingListener.class);

        @Override
        public void beforeJob(JobExecution jobExecution) {
            LOG.info("Batch job '{}' starting", jobName(jobExecution));
        }

        @Override
        public void afterJob(JobExecution jobExecution) {
            LOG.info("Batch job '{}' finished with status {} (exitCode={}, durationMs={})",
                    jobName(jobExecution),
                    jobExecution.getStatus(),
                    jobExecution.getExitStatus().getExitCode(),
                    durationMillis(jobExecution));
        }

        /**
         * Resolves the batch job name defensively.
         *
         * @param jobExecution the current execution
         * @return the job name, or {@value #UNKNOWN_JOB_NAME} when the instance is unavailable
         */
        private static String jobName(JobExecution jobExecution) {
            return jobExecution.getJobInstance() != null
                    ? jobExecution.getJobInstance().getJobName()
                    : UNKNOWN_JOB_NAME;
        }

        /**
         * Computes the wall-clock job duration in milliseconds.
         *
         * @param jobExecution the current execution
         * @return elapsed milliseconds between start and end, or {@value #DURATION_UNKNOWN} when
         *         either timestamp is absent
         */
        private static long durationMillis(JobExecution jobExecution) {
            LocalDateTime start = jobExecution.getStartTime();
            LocalDateTime end = jobExecution.getEndTime();
            if (start == null || end == null) {
                return DURATION_UNKNOWN;
            }
            return Duration.between(start, end).toMillis();
        }
    }
}
