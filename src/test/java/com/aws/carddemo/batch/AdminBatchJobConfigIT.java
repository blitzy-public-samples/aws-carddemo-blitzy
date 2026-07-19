package com.aws.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

/**
 * Failsafe integration / parity test for {@link AdminBatchJobConfig}, verifying that the migrated
 * admin-batch driver job runs to completion as an inert no-op with no data side effects.
 *
 * <p><strong>Origin:</strong> {@code legacy/jcl/CBADMCDJ.jcl} (source branch
 * {@code app/jcl/CBADMCDJ.jcl}). On z/OS that job ran {@code EXEC PGM=DFHCSDUP} &mdash; the CICS
 * System Definition (CSD) utility that <em>defines/updates CICS resource definitions</em> (the
 * {@code CARDDEMO} group of mapsets, programs, and transactions, e.g.
 * {@code DEFINE PROGRAM(COSGN00C) ... TRANSID(CC00)}). It carried <strong>no business or data
 * processing</strong>. Under Spring Boot there is no CICS region and no CSD: those resource
 * definitions are realized declaratively by {@code config/SecurityConfig} (URL/method routing plus
 * {@code ROLE_ADMIN} / {@code ROLE_USER} authorities) and by the component-scanned controller,
 * service, and repository beans. The job is therefore retained as a documented no-op
 * {@code Tasklet} so the {@code CBADMCDJ.jcl} &rarr; {@code AdminBatchJobConfig} mapping stays 1:1
 * in the traceability matrix (Explainability rule, AAP &sect;0.6.10). This test is the parity oracle
 * for that contract: the single step must run and complete cleanly while touching no business
 * table.</p>
 *
 * <p><strong>Context scope &mdash; focused batch slice.</strong> The test extends
 * {@link AbstractPostgresIntegrationTest} to reuse the shared, real PostgreSQL 18 database started
 * by Testcontainers; that database is required because Spring Boot auto-configures the JDBC-backed
 * {@code JobRepository} against it (this job persists no business data of its own, but its batch
 * metadata is written to genuine {@code BATCH_*} tables). Rather than performing the full
 * application component scan, the class-level {@link ContextConfiguration} pins the primary
 * configuration to the nested {@link BatchSliceConfig}, which enables Spring Boot
 * auto-configuration and {@link Import imports} only {@link AdminBatchJobConfig} &mdash; the single
 * configuration under test &mdash; alongside {@link BatchTestConfig}, which supplies the launcher
 * helper (see below). Because {@link BatchSliceConfig} is a plain {@code @Configuration} (a non-test
 * component), Spring Boot skips the package search for a primary {@code @SpringBootConfiguration}
 * and uses these two classes directly, so no application bean beyond {@link AdminBatchJobConfig} is
 * ever instantiated. This mirrors the established batch integration-test pattern in this
 * codebase (a minimal, hermetic slice per job) and is correct here because
 * {@code AdminBatchJobConfig} is a self-contained no-op driver with no repository, entity, web,
 * service, or security collaborators; loading those layers would add nothing to the parity contract
 * this oracle verifies. Spring Batch's JDBC infrastructure ({@code JobRepository},
 * {@link JobLauncher}, {@link org.springframework.transaction.PlatformTransactionManager}) is
 * supplied by Boot auto-configuration exactly as in production, where {@code config/BatchConfig}
 * likewise relies on auto-configuration and deliberately omits {@code @EnableBatchProcessing}.</p>
 *
 * <p><strong>Launcher wiring.</strong> {@code @SpringBatchTest} is deliberately <strong>not</strong>
 * used. The application declares twelve batch {@link Job} beans; the annotation's auto-registered
 * {@link JobLauncherTestUtils} autowires a single, unique {@link Job} and so is unsafe against the
 * multi-job application, and avoiding it keeps this harness uniform with the rest of the suite.
 * Instead the nested {@link BatchTestConfig} supplies exactly one {@link JobLauncherTestUtils},
 * bound explicitly to the {@code adminBatchJob} bean via {@link Qualifier}, so the target job is
 * unambiguous.</p>
 *
 * <p><strong>Hibernate schema validation scoped out.</strong> {@link TestPropertySource} sets
 * {@code spring.jpa.hibernate.ddl-auto=none}, overriding the {@code validate} default from
 * {@code application-test.yml}. Flyway still applies the real production migrations
 * ({@code V0}&ndash;{@code V3}) into the Testcontainers database, so the JDBC {@code JobRepository}
 * runs against the genuine {@code BATCH_*} schema. Because this slice performs no component scan and
 * maps no JPA entity, Hibernate's entity-to-table validation has nothing to check; turning it off
 * simply keeps the boot deterministic and this batch-only oracle decoupled from the domain-entity
 * mapping layer, without weakening any of the batch assertions below.</p>
 *
 * @see AdminBatchJobConfig
 */
@ContextConfiguration(classes = {
    AdminBatchJobConfigIT.BatchSliceConfig.class,
    AdminBatchJobConfigIT.BatchTestConfig.class
})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class AdminBatchJobConfigIT extends AbstractPostgresIntegrationTest {

    /** Bean name of the single {@code Step} declared by {@link AdminBatchJobConfig}. */
    private static final String STEP_NAME = "adminBatchStep";

    /**
     * The single {@link JobLauncherTestUtils} bound to {@code adminBatchJob} by
     * {@link BatchTestConfig}. Populated by Spring through field injection.
     */
    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * The admin-batch job reproducing {@code CBADMCDJ} runs to {@link BatchStatus#COMPLETED},
     * mirroring the clean end of the legacy {@code DFHCSDUP} step.
     *
     * @throws Exception if the launcher raises an error (surfaced as a test failure)
     */
    @Test
    void adminBatchJobCompletes() throws Exception {
        JobExecution execution = launchAdminBatchJob();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * The job's exit status is the standard {@code "COMPLETED"} exit code.
     *
     * @throws Exception if the launcher raises an error (surfaced as a test failure)
     */
    @Test
    void exitStatusIsCompleted() throws Exception {
        JobExecution execution = launchAdminBatchJob();

        assertThat(execution.getExitStatus().getExitCode())
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * The job has exactly one step ({@code adminBatchStep}) that completes without reading or
     * writing any item &mdash; proving the admin/CSD driver is an inert no-op that processes no data
     * and mutates no business table.
     *
     * @throws Exception if the launcher raises an error (surfaced as a test failure)
     */
    @Test
    void singleStep_noItemsProcessed() throws Exception {
        JobExecution execution = launchAdminBatchJob();

        Collection<StepExecution> stepExecutions = execution.getStepExecutions();
        assertThat(stepExecutions).hasSize(1);

        StepExecution step = stepExecutions.iterator().next();
        assertThat(step.getStepName()).isEqualTo(STEP_NAME);
        assertThat(step.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step.getReadCount()).isZero();
        assertThat(step.getWriteCount()).isZero();
    }

    /**
     * Launches {@code adminBatchJob} with only a unique {@code run.id} parameter. The job requires
     * no business parameters; the high-resolution timestamp guarantees a fresh {@code JobInstance}
     * per invocation so the three test methods never collide on a completed instance.
     *
     * @return the resulting {@link JobExecution}
     * @throws Exception if the launcher raises an error (surfaced as a test failure)
     */
    private JobExecution launchAdminBatchJob() throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", System.nanoTime())
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Minimal Spring Boot slice for this parity oracle: it enables auto-configuration (datasource,
     * JPA, and Spring Batch's JDBC {@code JobRepository}) and {@link Import imports} only
     * {@link AdminBatchJobConfig}, the configuration under test. It intentionally performs no
     * component scan, so the web, service, security, and persistence layers &mdash; none of which
     * the no-op admin driver depends on &mdash; are not loaded. Declared as a plain
     * {@link Configuration} (not {@code @SpringBootConfiguration}) and referenced explicitly by
     * {@link ContextConfiguration}, so Spring Boot uses it directly without scanning the package for
     * a primary configuration.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(AdminBatchJobConfig.class)
    static class BatchSliceConfig {
    }

    /**
     * Supplies the single {@link JobLauncherTestUtils} for this test, bound explicitly to the
     * {@code adminBatchJob} bean. Declared as a nested {@link TestConfiguration} and listed
     * explicitly by {@link ContextConfiguration} alongside {@link BatchSliceConfig} (explicit
     * {@code @ContextConfiguration(classes = ...)} suppresses the automatic detection of nested
     * configuration classes, so it is named directly); {@code @SpringBatchTest} is intentionally
     * avoided so that no launcher helper is auto-wired against an ambiguous {@link Job}.
     */
    @TestConfiguration
    static class BatchTestConfig {

        /**
         * Builds the launcher helper wired to the auto-configured {@link JobLauncher} and
         * {@link JobRepository} and to the {@code adminBatchJob} {@link Job} under test.
         *
         * @param jobLauncher   the Spring Boot auto-configured Spring Batch job launcher
         * @param jobRepository the Spring Boot auto-configured Spring Batch job repository
         * @param adminBatchJob the job under test, selected by bean name via {@link Qualifier}
         * @return a {@link JobLauncherTestUtils} that launches {@code adminBatchJob}
         */
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(JobLauncher jobLauncher,
                JobRepository jobRepository,
                @Qualifier("adminBatchJob") Job adminBatchJob) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            utils.setJob(adminBatchJob);
            return utils;
        }
    }
}
