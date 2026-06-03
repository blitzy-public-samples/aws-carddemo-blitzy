package com.carddemo.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration for the <strong>{@code interestCalculationJob}</strong> &mdash; the
 * Java/PostgreSQL replacement for the legacy mainframe interest run {@code app/jcl/INTCALC.jcl}
 * driving {@code app/cbl/CBACT04C.cbl} (CardDemo_v1.0-15-g27d6c6f-68).
 *
 * <h2>Legacy mainframe behavior (INTCALC.jcl)</h2>
 * The original JCL had a single executable step:
 * <pre>{@code
 * //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'
 * }</pre>
 * {@code CBACT04C} ("interest calculator program") reads the transaction-category-balance file
 * ({@code TCATBALF}, keyed {@code account + type + category}) front to back, looks up the
 * disclosure-group interest rate for each row (with a {@code 'DEFAULT'} fallback), computes the
 * monthly interest, emits an interest transaction to the new {@code TRANSACT} sequential file, and
 * &mdash; on every account boundary &mdash; applies the accumulated interest to the account record
 * and zeroes the current-cycle credit/debit buckets. The 10-character {@code PARM='2022071800'}
 * ({@code PARM-DATE PIC X(10)}) is the prefix of every generated transaction ID.
 *
 * <h2>Modernized PostgreSQL behavior</h2>
 * This {@code @Configuration} is a <strong>thin wrapper</strong>: it assembles a single-{@link Step}
 * {@link Job} whose entire body is the {@link InterestCalculationTasklet} &mdash; the line-by-line
 * port of the {@code CBACT04C} {@code PROCEDURE DIVISION} mainline
 * [app/cbl/CBACT04C.cbl&nbsp;L188-L222]. All interest-calculation business logic and the
 * preservation rules below live in that tasklet; this class only wires it into a Spring Batch
 * {@link Step} and {@link Job} and registers the job by name.
 *
 * <table border="1">
 *   <caption>INTCALC.jcl &rarr; Spring Batch mapping</caption>
 *   <tr><th>JCL construct</th><th>Spring Batch equivalent</th></tr>
 *   <tr><td>{@code STEP15 EXEC PGM=CBACT04C}</td>
 *       <td>{@link #interestCalculationStep()} wrapping {@link InterestCalculationTasklet}</td></tr>
 *   <tr><td>{@code PARM='2022071800'} ({@code PARM-DATE PIC X(10)})</td>
 *       <td>{@code tranDate} job parameter (10 chars), consumed and length-validated by the
 *           tasklet &mdash; see &sect;Job&nbsp;parameters</td></tr>
 *   <tr><td>Whole job</td><td>{@link #interestCalculationJob()} (a single chained step)</td></tr>
 * </table>
 *
 * <h2>Position in the critical batch sequence (PR-12)</h2>
 * This is the <strong>second</strong> job in the mandated sequence
 * {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT}. It remains independently
 * runnable: Spring Boot batch auto-configuration registers this {@link Job} with the
 * {@code JobRegistry} by its {@link #JOB_NAME}, so it is launchable on demand through the
 * {@code BatchAdminController} ({@code POST /api/admin/jobs/interestCalculationJob/launch}).
 *
 * <h2>Job parameters</h2>
 * The job <strong>requires</strong> one parameter, {@code tranDate} &mdash; a 10-character string
 * equivalent to the JCL {@code PARM='2022071800'} ({@code CBACT04C PARM-DATE PIC X(10)}). It forms
 * the prefix of every generated interest-transaction ID (PR-10). The parameter is read and
 * validated (non-null and exactly 10 characters, otherwise {@link IllegalStateException}) by the
 * {@link InterestCalculationTasklet}; this configuration deliberately does not inspect job
 * parameters, keeping the wiring concern separate from the business concern.
 * <pre>{@code
 * JobParameters params = new JobParametersBuilder()
 *         .addString("tranDate", "2022071800")
 *         .toJobParameters();
 * jobLauncher.run(interestCalculationJob, params);
 * }</pre>
 *
 * <h2>Refactoring rules enforced (delegated to {@link InterestCalculationTasklet})</h2>
 * <ul>
 *   <li><b>PR-01</b> (interest formula preserved line-by-line):
 *       {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} &mdash; delegated.</li>
 *   <li><b>PR-02</b> (DISCGRP {@code 'DEFAULT'} fallback) &mdash; delegated.</li>
 *   <li><b>PR-08</b> (account REWRITE: add interest, zero cycle buckets) &mdash; delegated.</li>
 *   <li><b>PR-10</b> (16-char transaction ID = {@code tranDate}(10) + suffix(6)) &mdash; delegated.</li>
 *   <li><b>PR-11</b> (DB2 timestamp format {@code yyyy-MM-dd-HH.mm.ss.SSS'0000'}) &mdash; delegated.</li>
 *   <li><b>PR-12</b> (critical batch sequence): this is the second job in
 *       {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT} and remains independently
 *       runnable.</li>
 *   <li><b>PR-24</b> (unit-of-work boundary): the step is bound to the injected
 *       {@link PlatformTransactionManager}, mirroring the implicit CICS {@code SYNCPOINT}; the
 *       tasklet's {@code @Transactional(propagation = REQUIRES_NEW)} {@code execute} establishes the
 *       run's own transaction scope.</li>
 *   <li><b>PR-28</b> (Jakarta/Spring 6 namespace): only Spring Framework 6.1 / Jakarta EE 10
 *       annotations are used.</li>
 *   <li><b>PR-29</b> (constructor injection): all three collaborators are {@code final} and injected
 *       through the Lombok {@code @RequiredArgsConstructor}-generated constructor &mdash; no field
 *       injection.</li>
 * </ul>
 *
 * <p><strong>Auto-configuration boundary:</strong> {@code @EnableBatchProcessing} is intentionally
 * absent. Spring Boot 3.2 auto-configures the {@link JobRepository}, {@code JobLauncher},
 * {@code JobRegistry} (which makes this {@link Job} launchable by name) and the
 * {@link PlatformTransactionManager}; adding {@code @EnableBatchProcessing} would disable that
 * auto-configuration. See {@code BatchConfig} for details.</p>
 *
 * @see InterestCalculationTasklet
 * @see org.springframework.batch.core.step.tasklet.Tasklet
 * @see org.springframework.batch.core.Job
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class InterestCalculationJobConfig {

    /**
     * Logical name of the interest-calculation {@link Job} bean. Registered with the Spring Batch
     * {@code JobRegistry} by Spring Boot auto-configuration so it can be launched by name through
     * the {@code BatchAdminController} ({@code POST /api/admin/jobs/interestCalculationJob/launch})
     * and identified as the second job within the
     * {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT} critical batch sequence
     * (PR-12).
     */
    public static final String JOB_NAME = "interestCalculationJob";

    /** Step name for the INTCALC {@code STEP15} ({@code EXEC PGM=CBACT04C}) interest-calculation step. */
    private static final String STEP_NAME = "interestCalculationStep";

    /** Spring Batch metadata repository (auto-configured by Spring Boot 3.2). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing the step's unit of work &mdash; the {@code SYNCPOINT}
     * equivalent (PR-24).
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * The {@code CBACT04C} interest-calculation port. Wired as the tasklet of
     * {@link #interestCalculationStep()} (INTCALC.jcl {@code STEP15}); performs the real work
     * (PR-01 interest formula, PR-02 DEFAULT fallback, PR-08 account REWRITE, PR-10 transaction ID,
     * PR-11 DB2 timestamp) and validates the {@code tranDate} job parameter. Injected by type as a
     * {@code @Component} (PR-29).
     */
    private final InterestCalculationTasklet interestCalculationTasklet;

    /**
     * INTCALC.jcl <strong>STEP15</strong> &mdash; {@code EXEC PGM=CBACT04C}.
     *
     * <p>This is the entire work of the job: it delegates to {@link InterestCalculationTasklet},
     * which walks the transaction-category-balance rows grouped by account, resolves the
     * disclosure-group rate (with {@code 'DEFAULT'} fallback), computes monthly interest, emits one
     * interest transaction per non-zero-rate category row, and applies the accumulated interest to
     * each account while zeroing its cycle buckets. The tasklet's own
     * {@code @Transactional(propagation = REQUIRES_NEW)} boundary combines with this step's
     * {@link PlatformTransactionManager} binding to provide the unit-of-work semantics of the
     * original program (PR-24).</p>
     *
     * @return the {@code interestCalculationStep} bean backed by {@link #interestCalculationTasklet}
     */
    @Bean
    public Step interestCalculationStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(interestCalculationTasklet, transactionManager)
                .build();
    }

    /**
     * Interest-calculation {@link Job} ({@link #JOB_NAME}). Wraps the single
     * {@link #interestCalculationStep()} in a one-step job that reproduces the {@code INTCALC.jcl}
     * {@code STEP15 EXEC PGM=CBACT04C} execution.
     *
     * <p>Because the single step persists its own {@code BATCH_STEP_EXECUTION} metadata, the job is
     * restartable from the failed step via standard {@code JobOperator} semantics (a single
     * step + tasklet yields clean restart behavior). Spring Boot auto-configuration registers this
     * job with the {@code JobRegistry}, making it the second, independently launchable job of the
     * critical batch sequence (PR-12).</p>
     *
     * @return the {@code interestCalculationJob} bean wrapping {@link #interestCalculationStep()}
     */
    @Bean
    public Job interestCalculationJob() {
        log.info("Registering batch job '{}' (INTCALC -> CBACT04C); step='{}'; "
                        + "position 2/4 in POSTTRAN -> INTCALC -> COMBTRAN -> CREASTMT (PR-12); "
                        + "requires 'tranDate' job parameter (PARM='2022071800')",
                JOB_NAME, STEP_NAME);
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(interestCalculationStep())
                .build();
    }
}
