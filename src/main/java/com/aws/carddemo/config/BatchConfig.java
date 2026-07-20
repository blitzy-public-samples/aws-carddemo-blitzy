package com.aws.carddemo.config;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.aws.carddemo.util.batch.AtomicFileStepPublisher;
import com.aws.carddemo.util.batch.BatchFilePathResolver;

/**
 * Spring Batch tuning for the CardDemo migration (JCL/JES2 -&gt; Spring Batch).
 *
 * <p>This {@code @Configuration} class is the single Java home for cross-job <em>batch tuning</em>
 * in the AWS CardDemo COBOL&rarr;Java migration. It centralizes shared chunk-size (commit-interval)
 * constants, an optional reusable job-execution logging listener, the hardened batch file-path
 * resolver, the atomic file-publication step listener, and the two {@code JobLauncher} beans
 * described below. It deliberately owns <strong>no</strong> {@code Job}, {@code Step},
 * {@code JobRepository}, {@code JobExplorer}, {@code JobRegistry} or {@code JobOperator} bean. The 12
 * business/utility batch jobs in the sibling package {@code com.aws.carddemo.batch} each define
 * their own {@code Job}/{@code Step} beans and constructor-inject the auto-configured
 * {@code JobRepository} and {@link org.springframework.transaction.PlatformTransactionManager};
 * this class provides only shared tuning and launch infrastructure that those jobs and the online
 * report-submission service may reference.</p>
 *
 * <p><strong>Asynchronous report launcher (review finding&nbsp;#34).</strong> The online transaction
 * report ({@code CORPT00C} / {@code CR00}) faithfully migrates {@code EXEC CICS WRITEQ TD
 * QUEUE('JOBS')}, which enqueues a job to the JES2 internal reader and <em>returns immediately</em>;
 * the batch job then runs asynchronously. To preserve that enqueue-and-return semantics the report
 * service must launch on a bounded background executor rather than block the HTTP worker thread, so
 * this class declares {@link #reportJobLauncher(JobRepository) reportJobLauncher} &mdash; a
 * {@link TaskExecutorJobLauncher} backed by a bounded {@link ThreadPoolTaskExecutor} (an
 * {@link ThreadPoolExecutor.AbortPolicy AbortPolicy} surfaces saturation as a
 * {@link org.springframework.core.task.TaskRejectedException}).</p>
 *
 * <p><strong>Coexistence with Boot's auto-configured launcher.</strong> Spring Boot's
 * {@code BatchAutoConfiguration} (via its {@code SpringBootBatchConfiguration}) always supplies a bean
 * named {@code jobLauncher} &mdash; a {@link TaskExecutorJobLauncher} whose executor defaults to a
 * <em>synchronous</em> {@code SyncTaskExecutor}. That auto-configured launcher does <strong>not</strong>
 * back off merely because another {@code JobLauncher} bean exists: {@code SpringBootBatchConfiguration}
 * is gated on {@code @ConditionalOnMissingBean(DefaultBatchConfiguration.class)}, <em>not</em> on the
 * presence of a {@code JobLauncher}. This class therefore deliberately does <strong>not</strong>
 * declare a competing bean named {@code jobLauncher}; an earlier revision did, and because bean-
 * definition overriding is disabled by default it collided with Boot's definition and raised a
 * {@code BeanDefinitionOverrideException} at context start (review finding&nbsp;#34). Instead the
 * application relies on Boot's synchronous {@code jobLauncher} for
 * {@code JobLauncherApplicationRunner} and for the batch integration tests' {@code JobLauncherTestUtils},
 * and declares only the additional {@code reportJobLauncher}. Because a second {@code JobLauncher} bean
 * would otherwise make plain by-type injection ambiguous, {@code reportJobLauncher} is declared with
 * {@code @Bean(defaultCandidate = false)}: it is excluded from default by-type autowiring &mdash; so
 * {@code JobLauncherTestUtils} unambiguously resolves Boot's synchronous launcher &mdash; yet remains
 * injectable through the explicit {@code @Qualifier("reportJobLauncher")} that
 * {@code ReportSubmitService} uses. Both launchers share the auto-configured JDBC-backed
 * {@code JobRepository}, so job executions remain durable and restartable (the rationale is recorded in
 * {@code docs/decision-log.md}).</p>
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
     * Core pool size of the bounded executor backing {@link #reportJobLauncher(JobRepository)}. One
     * always-available worker is sufficient for the low-frequency online report submission
     * ({@code CORPT00C} / {@code CR00}); additional concurrent submissions queue (see
     * {@link #REPORT_LAUNCHER_QUEUE_CAPACITY}) before a second worker is created.
     */
    private static final int REPORT_LAUNCHER_CORE_POOL = 1;

    /**
     * Maximum pool size of the bounded executor backing {@link #reportJobLauncher(JobRepository)}. The
     * pool never grows beyond {@value}; once {@link #REPORT_LAUNCHER_MAX_POOL} workers are busy and the
     * queue is full, further submissions are rejected with a
     * {@link org.springframework.core.task.TaskRejectedException} (the COBOL {@code WHEN OTHER}
     * "Unable to Write TDQ" branch), bounding the online tier's batch-launch fan-out.
     */
    private static final int REPORT_LAUNCHER_MAX_POOL = 2;

    /**
     * Bounded work-queue capacity of the executor backing {@link #reportJobLauncher(JobRepository)}. A
     * finite queue (rather than an unbounded {@code LinkedBlockingQueue}) is what makes the launch
     * boundary <em>bounded</em>: it caps the number of accepted-but-not-yet-started report launches so
     * a burst of submissions cannot exhaust memory or threads.
     */
    private static final int REPORT_LAUNCHER_QUEUE_CAPACITY = 25;

    /**
     * Seconds the {@link #reportJobLauncher(JobRepository)} executor waits, on application shutdown,
     * for in-flight report jobs to finish before forcing termination. This lets an accepted launch
     * complete and durably record its {@link org.springframework.batch.core.JobExecution} outcome
     * rather than being abandoned mid-run.
     */
    private static final int REPORT_LAUNCHER_AWAIT_SECONDS = 30;

    /** Thread-name prefix for the {@link #reportJobLauncher(JobRepository)} executor workers. */
    private static final String REPORT_LAUNCHER_THREAD_PREFIX = "cr00-report-";

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
     * Centralized, hardened resolver for every batch file path derived from a job parameter
     * (review finding&nbsp;#18). All batch readers and writers resolve their input/output paths
     * through this bean instead of building a {@link org.springframework.core.io.FileSystemResource}
     * from a raw job-parameter string, gaining safe-root containment, symlink rejection, owner-only
     * ({@code 0600}) temporary files, {@code fsync} and atomic publication with rollback cleanup.
     *
     * <p>The set of allowed root directories is bound from the {@code carddemo.batch.allowed-paths}
     * property (a comma-separated list). When the property is absent or blank &mdash; the default in
     * this local-validation deployment &mdash; the allowed roots are the JVM temporary directory
     * (where GDG-style default outputs and integration-test scratch files live) and the module
     * working directory (where {@code target/reports} and test resources live). Any path resolving
     * outside every allowed root is rejected, so a traversal ({@code ../../etc/passwd}) or an
     * absolute path such as {@code /etc/passwd} cannot be read or written by a batch job.</p>
     *
     * @param allowedPaths the configured allowed root directories (comma-separated); may be empty to
     *                     select the safe local defaults
     * @return the shared, thread-safe path resolver
     */
    @Bean
    public BatchFilePathResolver batchFilePathResolver(
            @Value("${carddemo.batch.allowed-paths:}") List<String> allowedPaths) {
        boolean blank = allowedPaths == null || allowedPaths.isEmpty()
                || allowedPaths.stream().allMatch(p -> p == null || p.isBlank());
        List<String> roots = blank
                ? List.of(System.getProperty("java.io.tmpdir"), System.getProperty("user.dir"))
                : allowedPaths;
        return new BatchFilePathResolver(roots);
    }

    /**
     * Reusable {@link org.springframework.batch.core.StepExecutionListener} that gives chunk-oriented
     * file-writer steps atomic, owner-only ({@code 0600}) publication with restart-safe resume and
     * rollback cleanup (review findings&nbsp;#18 and&nbsp;#19). A writer step targets a deterministic
     * in-progress temp file and registers this listener; on successful completion the temp file is
     * atomically renamed onto the final target, and on failure it is left for a restart to resume so
     * the final target is never partially written.
     *
     * @param batchFilePathResolver the shared safe-path resolver
     * @return the shared, thread-safe atomic-publication step listener
     */
    @Bean
    public AtomicFileStepPublisher atomicFileStepPublisher(BatchFilePathResolver batchFilePathResolver) {
        return new AtomicFileStepPublisher(batchFilePathResolver);
    }

    /**
     * Bounded, asynchronous {@link JobLauncher} used by the online transaction-report submission
     * ({@code ReportSubmitService}, CICS transaction {@code CR00} / program {@code CORPT00C}) &mdash;
     * review finding&nbsp;#34.
     *
     * <p>The COBOL {@code WIRTE-JOBSUB-TDQ} paragraph performs {@code EXEC CICS WRITEQ TD
     * QUEUE('JOBS')}, enqueuing the job to the JES2 internal reader and returning to the terminal
     * immediately; the batch job then runs asynchronously under JES2. Launching the migrated
     * {@code transactionReportJob} on this {@link TaskExecutorJobLauncher} (backed by a bounded
     * {@link ThreadPoolTaskExecutor}) reproduces that semantics: {@link JobLauncher#run} performs the
     * synchronous pre-flight (parameter validation and {@code JobExecution} creation, so a launch
     * failure still surfaces synchronously) and then hands {@code job.execute(...)} to a background
     * worker, returning an {@code STARTING}/{@code STARTED} execution without blocking the HTTP worker
     * thread. The {@code JobExecution} is persisted in the JDBC-backed {@link JobRepository}, so the
     * submission is <em>durable</em> and its later completion or failure is queryable via
     * {@code JobExplorer}.</p>
     *
     * <p>The executor is <em>bounded</em>: {@value #REPORT_LAUNCHER_CORE_POOL} core &rarr;
     * {@value #REPORT_LAUNCHER_MAX_POOL} max threads with a {@value #REPORT_LAUNCHER_QUEUE_CAPACITY}-slot
     * queue and an {@link ThreadPoolExecutor.AbortPolicy AbortPolicy}. When the pool and queue are
     * saturated, submission is rejected with a
     * {@link org.springframework.core.task.TaskRejectedException}, which the report service maps to the
     * COBOL {@code EVALUATE WS-RESP-CD WHEN OTHER} "Unable to Write TDQ (JOBS)..." error line &mdash;
     * so a flood of submissions degrades gracefully instead of exhausting resources. On shutdown the
     * executor waits up to {@value #REPORT_LAUNCHER_AWAIT_SECONDS}s for in-flight jobs to finish and
     * durably record their outcome.</p>
     *
     * <p>Declared with {@code @Bean(defaultCandidate = false)} so it is excluded from plain by-type
     * {@code JobLauncher} autowiring. That keeps Boot's synchronous auto-configured {@code jobLauncher}
     * the unambiguous by-type choice for {@code JobLauncherApplicationRunner} and the batch integration
     * tests' {@code JobLauncherTestUtils}, while this launcher remains injectable through the explicit
     * {@code @Qualifier("reportJobLauncher")} used by {@code ReportSubmitService}.</p>
     *
     * @param jobRepository the Spring Boot auto-configured, JDBC-backed job repository; must not be
     *                      {@code null}
     * @return a bounded asynchronous {@link TaskExecutorJobLauncher} over the shared job repository
     * @throws Exception if the launcher cannot be initialized
     */
    @Bean(defaultCandidate = false)
    public JobLauncher reportJobLauncher(JobRepository jobRepository) throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(REPORT_LAUNCHER_THREAD_PREFIX);
        executor.setCorePoolSize(REPORT_LAUNCHER_CORE_POOL);
        executor.setMaxPoolSize(REPORT_LAUNCHER_MAX_POOL);
        executor.setQueueCapacity(REPORT_LAUNCHER_QUEUE_CAPACITY);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(REPORT_LAUNCHER_AWAIT_SECONDS);
        executor.initialize();

        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(executor);
        launcher.afterPropertiesSet();
        return launcher;
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
