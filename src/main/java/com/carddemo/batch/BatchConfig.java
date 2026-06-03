package com.carddemo.batch;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.support.JobRegistryBeanPostProcessor;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.SimpleJobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Central Spring Batch infrastructure configuration for the CardDemo modernization.
 *
 * <p>This {@code @Configuration} class is the foundational batch-infrastructure bean that the
 * CardDemo Spring Batch subsystem builds upon. It supplies the supporting beans required to launch
 * batch jobs <em>asynchronously</em> (fire-and-forget) from the online tier — replacing the legacy
 * CICS {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} pattern used by {@code CORPT00C} to enqueue report
 * jobs (see {@code app/jcl/POSTTRAN.jcl} and the {@code *JobConfig} classes such as
 * {@code TransactionPostingJobConfig}, which run {@code CBTRN02C}-style posting workloads).</p>
 *
 * <h2>Spring Boot 3.2 auto-configuration boundary — read before editing</h2>
 *
 * <p>In Spring Boot 3.x, {@code spring-boot-starter-batch} auto-configures the entire Spring Batch
 * core via {@code BatchAutoConfiguration}. Its nested
 * {@code BatchAutoConfiguration$SpringBootBatchConfiguration} <em>extends</em>
 * {@link org.springframework.batch.core.configuration.support.DefaultBatchConfiguration}, which
 * declares <strong>unconditional</strong> {@code @Bean} methods for the following batch
 * infrastructure beans. They are therefore <strong>already present in the application context</strong>
 * and MUST NOT be redefined here:</p>
 *
 * <table border="1">
 *   <caption>Batch infrastructure bean ownership</caption>
 *   <tr><th>Bean</th><th>Owner</th><th>Notes</th></tr>
 *   <tr><td>{@code jobRepository}</td><td>Spring Boot auto-config</td><td>Backed by the DataSource + {@code BATCH_*} tables</td></tr>
 *   <tr><td>{@code jobLauncher} (synchronous, primary)</td><td>Spring Boot auto-config</td><td>Default launcher for synchronous runs</td></tr>
 *   <tr><td>{@code jobExplorer}</td><td>Spring Boot auto-config</td><td>Read-only execution metadata access</td></tr>
 *   <tr><td>{@code jobRegistry}</td><td>Spring Boot auto-config</td><td>Name-based job lookup (used by {@code BatchAdminController})</td></tr>
 *   <tr><td>{@code jobRegistryBeanPostProcessor}</td><td>Spring Boot auto-config</td><td>Auto-registers every {@code @Bean Job} into the registry</td></tr>
 *   <tr><td>{@code jobOperator}</td><td>Spring Boot auto-config</td><td>High-level start/stop/restart/abandon operations</td></tr>
 *   <tr><td>{@code asyncJobTaskExecutor}</td><td><strong>this class</strong></td><td>Thread pool for {@code @Async} launches</td></tr>
 *   <tr><td>{@code asyncJobLauncher}</td><td><strong>this class</strong></td><td>Non-blocking launcher for REST-triggered jobs (HTTP 202)</td></tr>
 * </table>
 *
 * <p><strong>Why {@code @EnableBatchProcessing} is forbidden here:</strong> per the Spring Boot 3.0
 * release notes, {@code @EnableBatchProcessing} is no longer required and, if added, <em>disables</em>
 * {@code BatchAutoConfiguration} and forces fully manual setup (DataSource wiring, transaction
 * manager, table prefix, isolation level). This class deliberately omits it so auto-configuration
 * remains active.</p>
 *
 * <p><strong>Why three schema members are NOT annotated {@code @Bean}:</strong> the file contract
 * lists {@link #jobRegistry()}, {@link #jobRegistryBeanPostProcessor(JobRegistry)} and
 * {@link #jobOperator(JobLauncher, JobRepository, JobRegistry, JobExplorer)} as exposed members.
 * Because Spring Boot 3.2.12 <em>already</em> registers these beans unconditionally (see table
 * above), declaring competing {@code @Bean} methods of the same names makes the container fail at
 * startup with
 * {@link org.springframework.beans.factory.support.BeanDefinitionOverrideException} — bean-definition
 * overriding is disabled by default in Spring Boot, and {@code @ConditionalOnMissingBean} does not
 * help because user {@code @Configuration} beans register before the deferred auto-configuration.
 * They are therefore provided as fully-implemented, documented <em>factory methods</em> (not beans):
 * they construct exactly what {@code DefaultBatchConfiguration} would, are available for programmatic
 * or test bootstrapping in non-auto-configured contexts, and keep the public contract intact without
 * destabilizing the application context. Runtime consumers
 * ({@code BatchAdminController}, {@code ReportService}) must autowire the auto-configured
 * {@link JobRegistry} / {@link JobOperator} <em>by type</em>.</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><strong>PR-25</strong> (single monolith): all batch infrastructure lives in one Spring context.</li>
 *   <li><strong>PR-26</strong> (no new external services): async launches use a local in-JVM thread
 *       pool — no SQS/Kafka/queue infrastructure.</li>
 *   <li><strong>PR-28</strong> (Jakarta namespace): {@link jakarta.annotation.PostConstruct} and the
 *       Spring Framework 6.1 / Jakarta EE 10 baseline are used throughout.</li>
 *   <li><strong>PR-29</strong> (constructor injection): dependencies are injected through the
 *       Lombok {@code @RequiredArgsConstructor}-generated constructor over {@code final} fields —
 *       no field injection.</li>
 *   <li><strong>PR-30</strong> (single-phase delivery): this bean is part of the one-phase
 *       deliverable.</li>
 * </ul>
 *
 * @see org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration
 * @see org.springframework.batch.core.configuration.support.DefaultBatchConfiguration
 */
@Configuration
@EnableAsync
@RequiredArgsConstructor
@Slf4j
public class BatchConfig {

    // NOTE: DO NOT add @EnableBatchProcessing — Spring Boot 3.x auto-configures Spring Batch.
    // Adding it DISABLES BatchAutoConfiguration and forces manual JobRepository/JobLauncher setup.

    /**
     * Bean name of the dedicated async batch {@link TaskExecutor}. Exposed as a constant so async
     * launch sites (e.g. {@code ReportService}) can reference it with
     * {@code @Async(BatchConfig.ASYNC_JOB_TASK_EXECUTOR)} or {@code @Qualifier(...)} without
     * string drift.
     */
    public static final String ASYNC_JOB_TASK_EXECUTOR = "asyncJobTaskExecutor";

    /**
     * Bean name of the async (non-primary) {@link JobLauncher}. Inject it with
     * {@code @Qualifier(BatchConfig.ASYNC_JOB_LAUNCHER)} to launch jobs without blocking the calling
     * (HTTP request) thread.
     */
    public static final String ASYNC_JOB_LAUNCHER = "asyncJobLauncher";

    /**
     * Maximum number of batch jobs that may be launched concurrently through the async launcher.
     * Bounds resource usage for fire-and-forget submissions originating from the REST tier.
     */
    private static final int ASYNC_CONCURRENCY_LIMIT = 5;

    /**
     * Auto-configured {@link JobRepository} (backed by the application DataSource and the
     * {@code BATCH_*} metadata tables). Injected for the async launcher wiring and for the
     * startup observability log below.
     */
    private final JobRepository jobRepository;

    /**
     * Auto-configured {@link JobExplorer} providing read-only access to job/step execution metadata.
     * Injected to document the dependency surface and for the startup observability log.
     */
    private final JobExplorer jobExplorer;

    /**
     * Auto-configured (synchronous, primary) {@link JobLauncher}. Resolved by name to the
     * Spring Boot {@code jobLauncher} bean (not the {@link #asyncJobLauncher} defined by this class).
     * Injected to document the dependency surface and for the startup observability log.
     */
    private final JobLauncher jobLauncher;

    /**
     * Emits a single, structured startup line recording which batch-infrastructure implementations
     * are active. This (a) confirms the Spring Boot auto-configuration boundary at runtime,
     * (b) gives operators a quick signal that the batch subsystem initialized, and (c) makes the
     * constructor-injected dependencies first-class (no unused fields). Logged at INFO because it is
     * a one-time, low-volume operational event.
     */
    @PostConstruct
    void logBatchInfrastructure() {
        log.info("CardDemo batch infrastructure initialized: jobRepository={}, jobExplorer={}, "
                        + "synchronousJobLauncher={}; async launcher bean='{}' (executor bean='{}', "
                        + "concurrencyLimit={}). Spring Boot 3.2 auto-configures jobRepository, "
                        + "jobLauncher, jobExplorer, jobRegistry, jobRegistryBeanPostProcessor and "
                        + "jobOperator; @EnableBatchProcessing is intentionally NOT present.",
                jobRepository.getClass().getSimpleName(),
                jobExplorer.getClass().getSimpleName(),
                jobLauncher.getClass().getSimpleName(),
                ASYNC_JOB_LAUNCHER,
                ASYNC_JOB_TASK_EXECUTOR,
                ASYNC_CONCURRENCY_LIMIT);
    }

    /**
     * Async {@link TaskExecutor} used by {@code @Async} methods (e.g. {@code ReportService}) for
     * fire-and-forget batch submission, replacing the CICS {@code WRITEQ TD QUEUE('JOBS')} semantics.
     *
     * <p>Implemented with {@link SimpleAsyncTaskExecutor}: each submission runs on a fresh thread
     * named {@code carddemo-batch-async-N}, with the total number of in-flight tasks bounded by a
     * concurrency limit of {@value #ASYNC_CONCURRENCY_LIMIT}. This satisfies PR-26 (no external
     * messaging infrastructure) using a purely local thread pool.</p>
     *
     * @return the dedicated async batch task executor
     */
    @Bean(name = ASYNC_JOB_TASK_EXECUTOR)
    public TaskExecutor asyncJobTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("carddemo-batch-async-");
        // Bound concurrent fire-and-forget launches so a burst of report requests cannot exhaust
        // resources; further submissions block until a slot frees up.
        executor.setConcurrencyLimit(ASYNC_CONCURRENCY_LIMIT);
        log.debug("Created async batch TaskExecutor '{}' with concurrencyLimit={} and threadNamePrefix='carddemo-batch-async-'",
                ASYNC_JOB_TASK_EXECUTOR, ASYNC_CONCURRENCY_LIMIT);
        return executor;
    }

    /**
     * Additional, <strong>non-primary</strong> {@link JobLauncher} that runs jobs on the
     * {@link #asyncJobTaskExecutor()} thread pool. Use this (never the primary, synchronous launcher)
     * when launching jobs from REST endpoints so the controller can return {@code 202 Accepted}
     * immediately while the batch executes in the background.
     *
     * <p>The primary, synchronous {@link JobLauncher} is auto-configured by Spring Boot; this bean is
     * <em>in addition</em> to it and is distinguished purely by its bean name
     * ({@value #ASYNC_JOB_LAUNCHER}). It is deliberately NOT annotated {@code @Primary} so the
     * auto-configured synchronous launcher remains the default for by-type injection. Inject this one
     * explicitly:</p>
     * <pre>{@code @Qualifier(BatchConfig.ASYNC_JOB_LAUNCHER) JobLauncher asyncJobLauncher}</pre>
     *
     * @param jobRepository        the auto-configured {@link JobRepository} (same instance held by
     *                             {@link #jobRepository}); taken as a parameter to honor the declared
     *                             member signature and let Spring resolve it
     * @param asyncJobTaskExecutor the async executor declared by {@link #asyncJobTaskExecutor()},
     *                             selected explicitly via {@link Qualifier} because Spring Boot may
     *                             also expose an {@code applicationTaskExecutor}
     * @return a fully-initialized async {@link JobLauncher}
     * @throws Exception if {@link TaskExecutorJobLauncher#afterPropertiesSet()} fails validation
     */
    @Bean(name = ASYNC_JOB_LAUNCHER)
    public JobLauncher asyncJobLauncher(JobRepository jobRepository,
                                        @Qualifier(ASYNC_JOB_TASK_EXECUTOR) TaskExecutor asyncJobTaskExecutor)
            throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(asyncJobTaskExecutor);
        // TaskExecutorJobLauncher is an InitializingBean; because we construct it manually we must
        // invoke afterPropertiesSet() ourselves to validate the required collaborators are wired.
        launcher.afterPropertiesSet();
        log.debug("Created async JobLauncher '{}' backed by TaskExecutor '{}'",
                ASYNC_JOB_LAUNCHER, ASYNC_JOB_TASK_EXECUTOR);
        return launcher;
    }

    // ---------------------------------------------------------------------------------------------
    // Auto-configured batch infrastructure — provided here as documented FACTORY METHODS only.
    //
    // The three members below are part of this file's public contract, but Spring Boot 3.2.12
    // ALREADY registers equivalent beans unconditionally (via
    // BatchAutoConfiguration$SpringBootBatchConfiguration extends DefaultBatchConfiguration).
    // They are therefore intentionally NOT annotated @Bean: doing so throws
    // BeanDefinitionOverrideException at startup (overriding is disabled by default, and
    // @ConditionalOnMissingBean cannot help because user configs register before deferred
    // auto-config). Each method returns exactly what DefaultBatchConfiguration would build, so it
    // remains usable for programmatic/test bootstrapping in a non-auto-configured context. Runtime
    // consumers must autowire the auto-configured JobRegistry/JobOperator BY TYPE.
    // ---------------------------------------------------------------------------------------------

    /**
     * Factory for the canonical {@link JobRegistry} (a {@link MapJobRegistry}) — the registry of all
     * {@link org.springframework.batch.core.Job} beans discoverable by name, e.g. by
     * {@code BatchAdminController} for {@code POST /api/admin/jobs/{jobName}/launch}.
     *
     * <p><strong>Intentionally NOT annotated {@code @Bean}.</strong> Spring Boot 3.2 auto-configures
     * a {@code jobRegistry} bean; declaring a competing {@code @Bean} of the same name throws
     * {@link org.springframework.beans.factory.support.BeanDefinitionOverrideException} at startup.
     * Autowire the auto-configured {@link JobRegistry} by type instead of calling this method.</p>
     *
     * @return a new, empty {@link MapJobRegistry} instance
     */
    public JobRegistry jobRegistry() {
        return new MapJobRegistry();
    }

    /**
     * Factory for a {@link JobRegistryBeanPostProcessor} bound to the supplied {@link JobRegistry}.
     * The post-processor scans the context for {@code @Bean Job} instances and registers each one
     * with the registry at startup, enabling name-based lookup such as
     * {@code jobRegistry.getJob("transactionPostingJob")}.
     *
     * <p><strong>Intentionally NOT annotated {@code @Bean}.</strong> Spring Boot 3.2 auto-configures
     * an equivalent {@code jobRegistryBeanPostProcessor}; declaring a competing {@code @Bean} of the
     * same name throws
     * {@link org.springframework.beans.factory.support.BeanDefinitionOverrideException} at startup.
     * Rely on the auto-configured post-processor to populate the auto-configured registry.</p>
     *
     * @param jobRegistry the registry the post-processor should populate
     * @return a fully-initialized {@link JobRegistryBeanPostProcessor}
     * @throws Exception if {@link JobRegistryBeanPostProcessor#afterPropertiesSet()} fails validation
     */
    public JobRegistryBeanPostProcessor jobRegistryBeanPostProcessor(JobRegistry jobRegistry) throws Exception {
        JobRegistryBeanPostProcessor postProcessor = new JobRegistryBeanPostProcessor();
        postProcessor.setJobRegistry(jobRegistry);
        postProcessor.afterPropertiesSet();
        return postProcessor;
    }

    /**
     * Factory for a {@link JobOperator} (a {@link SimpleJobOperator}) — the high-level operations API
     * for listing executions, restarting failed jobs, and abandoning stuck ones, as surfaced by
     * {@code BatchAdminController}.
     *
     * <p><strong>Intentionally NOT annotated {@code @Bean}.</strong> Spring Boot 3.2 auto-configures
     * a {@code jobOperator} bean; declaring a competing {@code @Bean} of the same name throws
     * {@link org.springframework.beans.factory.support.BeanDefinitionOverrideException} at startup.
     * Autowire the auto-configured {@link JobOperator} by type instead of calling this method.</p>
     *
     * <p>Note: {@link SimpleJobOperator#setJobRegistry} accepts a
     * {@link org.springframework.batch.core.configuration.ListableJobLocator}; {@link JobRegistry}
     * extends that interface, so the supplied registry is wired directly.</p>
     *
     * @param jobLauncher   the launcher used to start/restart jobs
     * @param jobRepository the repository holding execution metadata
     * @param jobRegistry   the registry used to resolve jobs by name
     * @param jobExplorer   the explorer used to read execution state
     * @return a fully-initialized {@link SimpleJobOperator}
     * @throws Exception if {@link SimpleJobOperator#afterPropertiesSet()} fails validation
     */
    public JobOperator jobOperator(JobLauncher jobLauncher,
                                   JobRepository jobRepository,
                                   JobRegistry jobRegistry,
                                   JobExplorer jobExplorer) throws Exception {
        SimpleJobOperator operator = new SimpleJobOperator();
        operator.setJobLauncher(jobLauncher);
        operator.setJobRepository(jobRepository);
        operator.setJobRegistry(jobRegistry);
        operator.setJobExplorer(jobExplorer);
        operator.afterPropertiesSet();
        return operator;
    }
}
