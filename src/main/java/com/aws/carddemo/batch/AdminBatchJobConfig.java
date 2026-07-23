package com.aws.carddemo.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration translating the mainframe admin batch driver {@code CBADMCDJ}.
 *
 * <p>Origin: {@code legacy/jcl/CBADMCDJ.jcl} (source branch {@code app/jcl/CBADMCDJ.jcl}).
 * Implements AAP &sect;0.4.1 (JCL job &rarr; Spring Batch {@link Job}) and &sect;0.6.3 (CICS
 * enable/disable &amp; CSD install &rarr; not applicable under managed Spring).</p>
 *
 * <p>On z/OS, {@code CBADMCDJ} executed the CICS utility {@code DFHCSDUP} to install CICS System
 * Definition (CSD) resources &mdash; the {@code CARDDEMO} group of mapsets, programs, and
 * transactions (for example {@code DEFINE PROGRAM(COSGN00C) ... TRANSID(CC00)} and
 * {@code DEFINE TRANSACTION(CCDM) PROGRAM(COADM00C)}). The job carried <strong>no business or data
 * logic</strong>: it only declared which programs, transactions, and files exist inside the CICS
 * region.</p>
 *
 * <p>Under Spring Boot there is no CICS region and no CSD. Those resource definitions are realized
 * declaratively by {@code config/SecurityConfig} (URL/method routing plus {@code ROLE_ADMIN} /
 * {@code ROLE_USER} authorities) and by the controller, service, and repository beans discovered
 * through component scanning. There is therefore <strong>no runtime work</strong> for this job to
 * perform. It is retained as a minimal, documented <em>no-op</em> {@link Tasklet} so the
 * {@code CBADMCDJ.jcl} &rarr; {@code AdminBatchJobConfig} mapping is preserved 1:1 in the
 * traceability matrix (Explainability rule &mdash; every JCL job maps to a Java artifact, with no
 * silent drops). The full rationale, and the rejected alternative of omitting the job entirely, are
 * recorded in {@code docs/decision-log.md}; per the Explainability rule the rationale lives in the
 * decision log rather than in code comments.</p>
 *
 * <p>Wiring note (AAP binding constraint): this class deliberately does <strong>not</strong> use
 * {@code @EnableBatchProcessing}. It relies on Spring Boot's Batch auto-configuration, which
 * supplies the {@link JobRepository} and {@link PlatformTransactionManager} injected through the
 * constructor. No repositories are injected, so the job performs no data access.</p>
 */
@Configuration
public class AdminBatchJobConfig {

    /** Logger used by the no-op tasklet to record why the CICS CSD install is not applicable. */
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminBatchJobConfig.class);

    /** Auto-configured Spring Batch job repository used to build the step and the job. */
    private final JobRepository jobRepository;

    /** Auto-configured transaction manager bounding the (no-op) tasklet's execution. */
    private final PlatformTransactionManager transactionManager;

    /**
     * Creates the configuration with the collaborators supplied by Spring Boot's Batch
     * auto-configuration.
     *
     * @param jobRepository      the auto-configured Spring Batch {@link JobRepository}
     * @param transactionManager the auto-configured {@link PlatformTransactionManager}
     */
    public AdminBatchJobConfig(JobRepository jobRepository,
            PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
    }

    /**
     * The single no-op step body. It performs no business logic and no data access; it simply logs
     * why the legacy CICS {@code DFHCSDUP} CSD install has no runtime equivalent under Spring and
     * then signals completion.
     *
     * @return a {@link Tasklet} that logs an informational message and returns
     *         {@link RepeatStatus#FINISHED}
     */
    @Bean
    public Tasklet adminBatchTasklet() {
        return (contribution, chunkContext) -> {
            LOGGER.info("CBADMCDJ (CICS DFHCSDUP CSD install) is not applicable under Spring; "
                    + "resource definitions are realized by SecurityConfig and bean wiring.");
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The single step of {@link #adminBatchJob()}, wrapping the no-op {@link #adminBatchTasklet()}
     * within the auto-configured transaction boundary.
     *
     * @return the {@code adminBatchStep} {@link Step}
     */
    @Bean
    public Step adminBatchStep() {
        return new StepBuilder("adminBatchStep", jobRepository)
                .tasklet(adminBatchTasklet(), transactionManager)
                .build();
    }

    /**
     * The Spring Batch {@link Job} corresponding to legacy JCL job {@code CBADMCDJ}. It consists of
     * the single no-op {@link #adminBatchStep()} and completes immediately with batch status
     * {@code COMPLETED}.
     *
     * @return the {@code adminBatchJob} {@link Job}
     */
    @Bean
    public Job adminBatchJob() {
        return new JobBuilder("adminBatchJob", jobRepository)
                .start(adminBatchStep())
                .build();
    }
}
