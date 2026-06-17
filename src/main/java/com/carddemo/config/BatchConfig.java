package com.carddemo.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;

/**
 * Spring Batch <strong>infrastructure</strong> configuration for the CardDemo monolith.
 *
 * <p><strong>Migration role.</strong> The legacy AWS CardDemo system ran its back-office work as
 * JCL-scheduled COBOL batch programs. In the Spring Boot 3.2.x / Java&nbsp;17 port those programs
 * become Spring Batch&nbsp;5 chunk-oriented jobs (reader &rarr; processor &rarr; writer) that live in
 * the sibling {@code com.carddemo.batch} package (AAP&nbsp;&sect;0.3.2). This class is the
 * <strong>infrastructure</strong> seam for that batch subsystem: it is the
 * {@code com.carddemo.config} home that AAP&nbsp;&sect;0.3.1 lists as
 * "{@code BatchConfig.java (JobLauncher, JobRepository)}" and AAP&nbsp;&sect;0.4.1.5 enumerates among
 * the security/DTO/batch/config artifacts. It deliberately defines <em>no</em> {@code Job},
 * {@code Step}, {@code ItemReader}, {@code ItemProcessor} or {@code ItemWriter} &mdash; those belong
 * exclusively to the {@code batch/} package under the strict Controller&nbsp;&rarr;&nbsp;Service&nbsp;&rarr;&nbsp;Repository
 * layering of AAP&nbsp;&sect;0.3.2.</p>
 *
 * <h2>Batch-enablement decision &mdash; OPTION&nbsp;A (Spring Boot auto-configuration)</h2>
 *
 * <p>The <strong>shared</strong> Spring Batch infrastructure &mdash; the {@code JobRepository}, the
 * default {@code JobLauncher} (bean name {@code jobLauncher}), the {@code JobExplorer} and the
 * supporting {@code PlatformTransactionManager} &mdash; is provided entirely by
 * <strong>Spring Boot's {@code BatchAutoConfiguration}</strong>. Boot auto-creates those beans against
 * the application {@code DataSource} and the auto-configured
 * {@code DataSourceTransactionManager}/{@code JdbcTransactionManager}. Two keys in the already-authored
 * {@code src/main/resources/application.yml} (a {@code depends_on} of this file) drive the runtime
 * behaviour, and <strong>both are Spring Boot batch auto-configuration features that take effect ONLY
 * while that auto-configuration is active</strong>:</p>
 * <ul>
 *   <li>{@code spring.batch.jdbc.initialize-schema: always} &mdash; runs the Spring Batch DDL so the
 *       {@code BATCH_*} metadata tables ({@code BATCH_JOB_INSTANCE}, {@code BATCH_JOB_EXECUTION},
 *       {@code BATCH_STEP_EXECUTION}, &hellip;) exist. Without these tables an on-demand
 *       {@code JobLauncher.run(...)} fails with a "table not found" error.</li>
 *   <li>{@code spring.batch.job.enabled: false} &mdash; suppresses Boot's
 *       {@code JobLauncherApplicationRunner} so <strong>no job auto-runs at startup</strong> (the
 *       runner would otherwise launch every {@code Job} bean on boot, because its guard is
 *       {@code matchIfMissing = true}). The five CardDemo jobs are instead launched
 *       <strong>on demand</strong> through a {@code JobLauncher} (e.g. from {@code ReportService} and
 *       the account/customer refresh endpoints).</li>
 * </ul>
 *
 * <h2>The one bean this class adds &mdash; an asynchronous report submitter</h2>
 *
 * <p>Beyond the auto-configured infrastructure, this class contributes exactly <strong>one</strong>
 * bean: {@link #reportJobSubmitter(JobRepository, TaskExecutor) reportJobSubmitter}, a
 * {@link ReportJobSubmitter} that encapsulates a {@link TaskExecutorJobLauncher}. That launcher reuses
 * the <em>auto-configured</em> {@link JobRepository} but runs jobs on the bounded asynchronous
 * {@link TaskExecutor} declared by {@link AsyncConfig#taskExecutor()} (bean
 * {@value AsyncConfig#TASK_EXECUTOR_BEAN_NAME}).</p>
 *
 * <p><strong>Why this is necessary (AAP&nbsp;&sect;0.3.1 / &sect;0.3.2 &mdash; fire-and-forget report
 * submission).</strong> The legacy online report program {@code app/cbl/CORPT00C.cbl} handed a JCL
 * job to the CICS internal reader ({@code SUBMIT-JOB-TO-INTRDR}) and returned to the operator
 * <em>immediately</em>, never blocking on the report's completion. Boot's default {@code jobLauncher}
 * cannot reproduce that: under Spring Batch&nbsp;5 the launcher created by
 * {@code DefaultBatchConfiguration} uses a {@code SyncTaskExecutor}, so
 * {@code JobLauncher.run(...)} executes the job <em>inline on the caller's thread</em> and only
 * returns once the job has finished (a {@code COMPLETED} execution). Backing a launcher with the
 * asynchronous {@code taskExecutor} instead makes {@code run(...)} return promptly with a
 * non-terminal ({@code STARTING}/{@code STARTED}) {@code JobExecution} whose id is already persisted
 * &mdash; the faithful Java analog of {@code CORPT00C}'s submit-and-return semantics.
 * {@code com.carddemo.service.ReportService} consumes the {@link ReportJobSubmitter} port to perform
 * exactly that submission.</p>
 *
 * <h2>Why a submitter <em>port</em> rather than a second {@code JobLauncher} bean</h2>
 *
 * <p>The asynchronous {@link TaskExecutorJobLauncher} is built and held <em>privately</em> by the
 * factory method below and is wrapped in a {@link ReportJobSubmitter}; it is <strong>not</strong>
 * registered as a {@link JobLauncher} bean. Consequently the application context still contains
 * <strong>exactly one</strong> {@link JobLauncher} bean &mdash; Boot's auto-configured, synchronous
 * {@code jobLauncher}. This single-{@code JobLauncher}-bean invariant is deliberate and load-bearing:</p>
 * <ul>
 *   <li><strong>By-type test wiring stays unambiguous.</strong> {@code spring-batch-test}'s
 *       {@code @SpringBatchTest} populates {@code JobLauncherTestUtils} by <em>type</em> (its
 *       {@code BatchTestContextCustomizer} registers the helper with no explicit autowiring, and the
 *       launcher is resolved through a by-type dependency descriptor that performs <em>no</em>
 *       bean-name fallback). A second, non-{@code @Primary} {@link JobLauncher} bean would make that
 *       resolution ambiguous and &mdash; because by-type autowiring is lenient rather than fatal
 *       &mdash; silently inject {@code null}, making every {@code @SpringBatchTest} job test
 *       (the refresh, posting, interest, and statement job tests of AAP&nbsp;&sect;0.2.1.3) fail with a
 *       {@code NullPointerException}.</li>
 *   <li><strong>The async launcher must not become {@code @Primary} either.</strong> Marking it
 *       primary would instead hand {@code JobLauncherTestUtils} the asynchronous launcher and break the
 *       deterministic {@code COMPLETED} assertions those job tests rely on. Hiding the async launcher
 *       behind the {@link ReportJobSubmitter} port avoids both failure modes at the root.</li>
 *   <li><strong>Option&nbsp;A is preserved.</strong> The factory reuses the auto-configured
 *       {@code JobRepository}, so the {@code application.yml}-driven {@code BATCH_*} schema
 *       initialization and the {@code spring.batch.job.enabled=false} auto-run suppression are
 *       untouched. No auto-configured bean is replaced or suppressed.</li>
 * </ul>
 *
 * <h2>Why {@code @EnableBatchProcessing} is intentionally ABSENT</h2>
 *
 * <p>Neither this class nor {@code com.carddemo.CardDemoApplication} carries
 * {@code @EnableBatchProcessing} &mdash; and that omission is deliberate and load-bearing. Under
 * Spring Boot&nbsp;3.2 / Spring Batch&nbsp;5, {@code BatchAutoConfiguration} is annotated
 * {@code @ConditionalOnMissingBean(DefaultBatchConfiguration.class)} and <strong>backs off entirely
 * the moment {@code @EnableBatchProcessing} appears anywhere</strong> in the context. Because the two
 * {@code application.yml} keys above are auto-configuration features, adding
 * {@code @EnableBatchProcessing} would silently disable them: the {@code BATCH_*} schema would never
 * be created and on-demand job launches would break. This class is a plain {@code @Configuration}
 * (it is <em>not</em> a {@code DefaultBatchConfiguration}), so contributing the additive
 * {@code reportJobSubmitter} above does not trigger that back-off. Keeping the annotation absent is the
 * single most important constraint on this file, kept in lock-step with {@code CardDemoApplication},
 * which carries {@code @SpringBootApplication} only.</p>
 *
 * <h2>How the five jobs consume this infrastructure</h2>
 *
 * <p>The job-configuration classes in the {@code com.carddemo.batch} package inject the
 * <strong>auto-configured</strong> {@code JobRepository} and {@code PlatformTransactionManager}
 * directly into their {@code JobBuilder}/{@code StepBuilder}; this class supplies no collaborators to
 * them. Each job re-expresses a legacy JCL-scheduled COBOL batch program (REFERENCE only &mdash; the
 * COBOL is never modified):</p>
 * <ul>
 *   <li>{@code TransactionPostingJobConfig} &lArr; {@code app/cbl/CBTRN02C.cbl} &mdash; post the daily
 *       transaction file (reject-code superset 100/101/102/103/109; 430-byte DALYREJS reject record).</li>
 *   <li>{@code InterestCalculationJobConfig} &lArr; {@code app/cbl/CBACT04C.cbl} &mdash; per-category
 *       interest = balance &times; rate &divide; 1200, {@code RoundingMode.HALF_UP}.</li>
 *   <li>{@code StatementCreationJobConfig} &lArr; {@code app/cbl/CBSTM03A.CBL} &mdash; account
 *       statements in both plain-text and HTML formats; also hosts the {@code transactionReportJob}
 *       launched asynchronously through {@link #reportJobSubmitter(JobRepository, TaskExecutor)}.</li>
 *   <li>{@code AccountRefreshJobConfig} &lArr; {@code CBACT01C}/{@code CBACT02C}/{@code CBACT03C}
 *       (VSAM dump/print utilities reinterpreted as an account-master refresh).</li>
 *   <li>{@code CustomerRefreshJobConfig} &lArr; {@code app/cbl/CBCUS01C.cbl} (customer-master refresh).</li>
 * </ul>
 *
 * <h2>Rejected alternative &mdash; OPTION&nbsp;B</h2>
 *
 * <p>A fallback design (Option&nbsp;B) would annotate a configuration class with
 * {@code @EnableBatchProcessing} and then hand-roll the {@code DataSource}-backed
 * {@code JobRepository}/{@code JobLauncher} plus a {@code BatchDataSourceScriptDatabaseInitializer} to
 * compensate for the lost auto-configuration. <strong>Option&nbsp;B is explicitly rejected here</strong>:
 * {@code CardDemoApplication} and {@code application.yml} are already committed to Option&nbsp;A, so
 * Option&nbsp;B would be inconsistent and would re-introduce the back-off problem described above. The
 * asynchronous {@code reportJobSubmitter} achieves fire-and-forget submission <em>within</em>
 * Option&nbsp;A, without {@code @EnableBatchProcessing} and without replacing any auto-configured bean.</p>
 *
 * <h2>Design notes &mdash; what must NOT be added here</h2>
 *
 * <p>To preserve Option&nbsp;A, the following must never be introduced in this class:</p>
 * <ul>
 *   <li>{@code @EnableBatchProcessing} &mdash; breaks {@code BATCH_*} schema initialization and
 *       on-demand launch (see above).</li>
 *   <li>A {@code JobRepository}, {@code JobExplorer}, {@code JobRegistry}, or a replacement / additional
 *       {@code JobLauncher} {@code @Bean} &mdash; these are owned by Boot's auto-configuration, and a
 *       second {@code JobLauncher} bean would break by-type test wiring (see the submitter-port section
 *       above). The async launcher is therefore encapsulated inside {@link ReportJobSubmitter}, which
 *       <em>reuses</em> the auto-configured {@code JobRepository} rather than redefining it.</li>
 *   <li>Any {@code Job} / {@code Step} / {@code ItemReader} / {@code ItemProcessor} /
 *       {@code ItemWriter} &mdash; these live in {@code com.carddemo.batch}.</li>
 *   <li>A {@code BatchDataSourceScriptDatabaseInitializer} &mdash; Option&nbsp;B compensation, rejected.</li>
 * </ul>
 *
 * <p>This class depends only on the auto-configured {@link JobRepository} and the
 * {@link AsyncConfig#taskExecutor()} bean (both infrastructure tiers), so it can never participate in a
 * cyclic dependency with the controller/service/repository layers. It is component-scanned
 * automatically because {@code com.carddemo.config} is a sub-package of the
 * {@code @SpringBootApplication} base package {@code com.carddemo}.</p>
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP&nbsp;&sect;0.3.1 &mdash; {@code config/BatchConfig.java (JobLauncher, JobRepository)}.</li>
 *   <li>AAP&nbsp;&sect;0.3.2 &mdash; chunk-oriented Spring Batch reader/processor/writer triplets and
 *       <em>asynchronous job submission</em> (the report fire-and-forget analog of {@code CORPT00C}).</li>
 *   <li>AAP&nbsp;&sect;0.4.1.5 &mdash; security/DTO/mapper/batch/config artifact inventory.</li>
 *   <li>AAP&nbsp;&sect;0.2.2 &mdash; single monolith; no microservice split, no remote/partitioned batch.</li>
 * </ul>
 *
 * @see AsyncConfig#taskExecutor()
 * @see ReportJobSubmitter
 * @see com.carddemo.service.ReportService
 * @see <a href="file:app/cbl/CORPT00C.cbl">CORPT00C.cbl</a>
 * @see <a href="file:app/cbl/CBTRN02C.cbl">CBTRN02C.cbl</a>
 * @see <a href="file:app/cbl/CBACT04C.cbl">CBACT04C.cbl</a>
 * @see <a href="file:app/cbl/CBSTM03A.CBL">CBSTM03A.CBL</a>
 */
@Configuration
public class BatchConfig {

    /**
     * Bean name of the asynchronous report submitter. {@code ReportService} consumes the
     * {@link ReportJobSubmitter} by type; this constant documents the canonical bean name and is used
     * by the {@code reportJobSubmitter} factory method's {@code @Bean(name = ...)} declaration.
     */
    public static final String REPORT_JOB_SUBMITTER_BEAN_NAME = "reportJobSubmitter";

    /**
     * The asynchronous report-submission port &mdash; a {@link ReportJobSubmitter} wrapping a
     * {@link TaskExecutorJobLauncher} &mdash; used for online, fire-and-forget job submission,
     * principally the {@code transactionReportJob} launched by
     * {@code com.carddemo.service.ReportService}, reproducing the submit-and-return behaviour of the
     * legacy {@code CORPT00C} ({@code SUBMIT-JOB-TO-INTRDR}).
     *
     * <p>The wrapped {@link TaskExecutorJobLauncher} is wired with:</p>
     * <ul>
     *   <li>the <strong>auto-configured</strong> {@link JobRepository} (so this launcher shares the
     *       same {@code BATCH_*} metadata store as every other job launch &mdash; no parallel
     *       repository, no Option&nbsp;B compensation); and</li>
     *   <li>the bounded asynchronous {@link TaskExecutor} {@value AsyncConfig#TASK_EXECUTOR_BEAN_NAME}
     *       from {@link AsyncConfig}, resolved by {@link Qualifier}.</li>
     * </ul>
     *
     * <p>Because the executor is asynchronous, the launcher's
     * {@link JobLauncher#run(org.springframework.batch.core.Job, org.springframework.batch.core.JobParameters)
     * run(...)} schedules the job on a worker thread and returns immediately with a
     * {@code STARTING}/{@code STARTED} {@code JobExecution} whose id is already persisted &mdash; exactly
     * the reference the report API echoes back to the caller. This contrasts with Spring Boot's default
     * {@code jobLauncher}, which uses a {@code SyncTaskExecutor} and therefore blocks the caller until
     * the job reaches a terminal status.</p>
     *
     * <p><strong>The launcher is intentionally not exposed as a {@link JobLauncher} bean.</strong> It is
     * a local object wrapped by the returned {@link ReportJobSubmitter}, which keeps the context's only
     * {@link JobLauncher} bean the synchronous, auto-configured {@code jobLauncher} &mdash; see the
     * "submitter port" section in the class Javadoc for why this matters to {@code @SpringBatchTest}'s
     * by-type {@code JobLauncherTestUtils} wiring. Because the launcher is not a bean, Spring will
     * <em>not</em> invoke its {@link TaskExecutorJobLauncher#afterPropertiesSet() afterPropertiesSet()};
     * this method therefore invokes it explicitly so the launcher is fully initialized (it validates
     * that the {@code JobRepository} is present) before it is handed to the submitter.</p>
     *
     * @param jobRepository the auto-configured Spring Batch {@link JobRepository}; must not be {@code null}
     * @param taskExecutor  the bounded asynchronous {@link TaskExecutor} declared by {@link AsyncConfig}
     * @return an asynchronous {@link ReportJobSubmitter} that submits report jobs without blocking the caller
     * @throws Exception if {@link TaskExecutorJobLauncher#afterPropertiesSet()} fails (e.g. a missing
     *                   {@code JobRepository}) &mdash; a fatal context-startup misconfiguration
     */
    @Bean(name = REPORT_JOB_SUBMITTER_BEAN_NAME)
    public ReportJobSubmitter reportJobSubmitter(
            JobRepository jobRepository,
            @Qualifier(AsyncConfig.TASK_EXECUTOR_BEAN_NAME) TaskExecutor taskExecutor) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        // The asynchronous executor is what makes run(...) return before the job completes. Without
        // this call TaskExecutorJobLauncher defaults to a SyncTaskExecutor (the very behaviour that
        // made report submission block), so setting it is the load-bearing line of this method.
        launcher.setTaskExecutor(taskExecutor);
        // Required: the launcher is NOT a Spring bean, so its InitializingBean callback is not invoked
        // by the container. Initialize it explicitly before wrapping it in the submitter port.
        launcher.afterPropertiesSet();
        return new ReportJobSubmitter(launcher);
    }
}
