package com.carddemo.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration for the <strong>{@code statementGenerationJob}</strong> &mdash; the
 * Java/PostgreSQL replacement for the legacy mainframe statement-creation flow
 * {@code app/jcl/CREASTMT.JCL} driving {@code app/cbl/CBSTM03A.CBL}
 * (CardDemo_v1.0-15-g27d6c6f-68).
 *
 * <h2>Legacy mainframe behavior (CREASTMT.JCL)</h2>
 * The original JCL created one statement per card present in the cross-reference (XREF) file using
 * four functional EXEC steps (the {@code STEP030 IEFBR14} report-cleanup step is folded into the
 * emission step's output management and is not modeled as a distinct bean):
 * <ol>
 *   <li><b>DELDEF01</b> ({@code PGM=IDCAMS}) &mdash; {@code DELETE}d and re-{@code DEFINE}d a
 *       temporary VSAM KSDS staging cluster
 *       ({@code AWS.M2.CARDDEMO.TRXFL.VSAM.KSDS}, {@code KEYS(32 0)}, {@code RECORDSIZE(350 350)})
 *       used to hold transactions re-keyed by card number.</li>
 *   <li><b>STEP010</b> ({@code PGM=SORT}) &mdash; DFSORT of the {@code TRANSACT} master with
 *       {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}: primary key {@code TRAN-CARD-NUM} (offset 263,
 *       length 16) ascending, secondary key {@code TRAN-ID} (offset 1, length 16) ascending, with
 *       {@code OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50)} moving the card number to the front
 *       of each output record.</li>
 *   <li><b>STEP020</b> ({@code PGM=IDCAMS}, {@code COND=(0,NE)}) &mdash; {@code REPRO}'d the sorted
 *       sequential output into the temporary staging KSDS.</li>
 *   <li><b>STEP040</b> ({@code PGM=CBSTM03A}, {@code COND=(0,NE)}) &mdash; read the staging KSDS
 *       ({@code TRNXFILE}), the cross-reference ({@code XREFFILE}), the account ({@code ACCTFILE})
 *       and the customer ({@code CUSTFILE}) files and emitted, for each card/account, a plain-text
 *       statement ({@code STMTFILE}, RECFM=FB LRECL=80) and an HTML statement ({@code HTMLFILE},
 *       RECFM=FB LRECL=100).</li>
 * </ol>
 *
 * <h2>Modernized PostgreSQL behavior</h2>
 * In the relational target the staging cluster, the explicit DFSORT and the IDCAMS REPRO load all
 * become unnecessary: the {@code transactions} table is already indexed and can be read in
 * {@code (cardNum, tranId)} order directly at query time. The actual statement emission
 * (the {@code CBSTM03A} mainline) is ported into {@link StatementGenerationTasklet}, which streams
 * transactions through {@code StatementIoSubroutine} (the {@code CBSTM03B} I/O subroutine port)
 * using {@code Sort.by("cardNum", "tranId")} &mdash; exactly the STEP010 sort sequence &mdash; and
 * renders HTML via {@code StatementHtmlBuilder} (the byte-for-byte {@code CBSTM03A} HTML port).
 *
 * <p>This job therefore defines <strong>four chained {@link Step} beans</strong> that preserve the
 * original JCL step count (so operators and auditors comparing execution logs still see four steps),
 * where the first three are fast no-op tasklets that simply log their legacy purpose and the fourth
 * performs the real work:</p>
 * <table border="1">
 *   <caption>CREASTMT.JCL step &rarr; Spring Batch step mapping</caption>
 *   <tr><th>JCL step</th><th>Spring Batch step bean</th><th>Behavior</th></tr>
 *   <tr><td>DELDEF01</td><td>{@link #statementPurgeStagingStep()}</td>
 *       <td>No-op &mdash; PostgreSQL needs no VSAM staging cluster.</td></tr>
 *   <tr><td>STEP010</td><td>{@link #statementSortStep()}</td>
 *       <td>No-op &mdash; the {@code (cardNum, tranId)} sort is performed by the JPA query inside
 *           {@link StatementGenerationTasklet}.</td></tr>
 *   <tr><td>STEP020</td><td>{@link #statementLoadStagingStep()}</td>
 *       <td>No-op &mdash; no staging-cluster load is needed.</td></tr>
 *   <tr><td>STEP040</td><td>{@link #statementEmissionStep()}</td>
 *       <td>Real work &mdash; delegates to {@link StatementGenerationTasklet} ({@code CBSTM03A}).</td></tr>
 * </table>
 *
 * <h2>Position in the critical batch sequence (PR-12)</h2>
 * This is the <strong>fourth and final</strong> job in the mandated sequence
 * {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT}. It remains independently
 * runnable: Spring Boot batch auto-configuration registers this {@link Job} with the
 * {@code JobRegistry} by its {@link #JOB_NAME}, so it is launchable on demand through
 * {@code BatchAdminController} ({@code POST /api/admin/jobs/statementGenerationJob/launch}).
 *
 * <h2>Job parameters</h2>
 * The job optionally accepts an <strong>{@code outputDir}</strong> string parameter naming the
 * directory the HTML and plain-text statement files are written to (default {@code ./statements}).
 * It is consumed by {@link StatementGenerationTasklet} and is declared <em>non-identifying</em> by
 * callers so the job can be re-run with the same {@code outputDir} value:
 * <pre>{@code
 * JobParameters params = new JobParametersBuilder()
 *         .addString("outputDir", "/tmp/statements", false) // non-identifying
 *         .toJobParameters();
 * jobLauncher.run(statementGenerationJob, params);
 * }</pre>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-09</b> (HTML byte-for-byte): the statement HTML structure is reproduced
 *       character-by-character; this fidelity is delegated to
 *       {@link StatementGenerationTasklet} &rarr; {@code StatementHtmlBuilder}.</li>
 *   <li><b>PR-12</b> (critical batch sequence): this is the fourth job in the
 *       {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT} sequence and remains
 *       independently runnable.</li>
 *   <li><b>PR-23</b> (lock ordering): the read-only access sequence
 *       CUSTOMER &rarr; ACCOUNT &rarr; CARD &rarr; TRANSACTION is preserved by
 *       {@code StatementIoSubroutine} inside the emission tasklet.</li>
 *   <li><b>PR-24</b> (unit-of-work boundary): every step is bracketed by a transaction managed by
 *       the injected {@link PlatformTransactionManager}, mirroring the implicit CICS
 *       {@code SYNCPOINT}.</li>
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
 * @see StatementGenerationTasklet
 * @see org.springframework.batch.core.step.tasklet.Tasklet
 * @see org.springframework.batch.core.Job
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class StatementGenerationJobConfig {

    /**
     * Logical name of the statement-generation {@link Job} bean. Registered with the Spring Batch
     * {@code JobRegistry} by Spring Boot auto-configuration so it can be launched by name through
     * the {@code BatchAdminController} ({@code POST /api/admin/jobs/statementGenerationJob/launch})
     * and identified within the {@code POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT}
     * critical batch sequence (PR-12).
     */
    public static final String JOB_NAME = "statementGenerationJob";

    /** Step name for the DELDEF01 (IDCAMS DELETE/DEFINE) no-op staging-purge step. */
    private static final String STEP_PURGE_STAGING = "statementPurgeStagingStep";

    /** Step name for the STEP010 (DFSORT) no-op sort step. */
    private static final String STEP_SORT = "statementSortStep";

    /** Step name for the STEP020 (IDCAMS REPRO) no-op staging-load step. */
    private static final String STEP_LOAD_STAGING = "statementLoadStagingStep";

    /** Step name for the STEP040 ({@code EXEC PGM=CBSTM03A}) statement-emission step. */
    private static final String STEP_EMISSION = "statementEmissionStep";

    /** Spring Batch metadata repository (auto-configured by Spring Boot 3.2). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing each step's unit of work &mdash; the {@code SYNCPOINT}
     * equivalent (PR-24).
     */
    private final PlatformTransactionManager transactionManager;

    /**
     * The {@code CBSTM03A} statement-emission port. Wired as the tasklet of
     * {@link #statementEmissionStep()} (CREASTMT.JCL STEP040); performs the real statement-generation
     * work (HTML + plain text). Injected by type as a {@code @Component} (PR-29).
     */
    private final StatementGenerationTasklet statementGenerationTasklet;

    /**
     * CREASTMT.JCL <strong>DELDEF01</strong> &mdash; IDCAMS {@code DELETE}/{@code DEFINE} of the
     * temporary VSAM staging cluster.
     *
     * <p>No-op in PostgreSQL: there is no staging cluster to (re)create because statement queries
     * read the production {@code transactions} table directly. The step is retained purely to
     * preserve the original four-step execution-log shape (audit parity).</p>
     *
     * @return the {@code statementPurgeStagingStep} bean (logs and finishes immediately)
     */
    @Bean
    public Step statementPurgeStagingStep() {
        return new StepBuilder(STEP_PURGE_STAGING, jobRepository)
                .tasklet(noOpTasklet("STEP-DELDEF01: no-op (no VSAM staging cluster in PostgreSQL)"),
                        transactionManager)
                .build();
    }

    /**
     * CREASTMT.JCL <strong>STEP010</strong> &mdash; DFSORT
     * {@code FIELDS=(263,16,CH,A,1,16,CH,A)} on the {@code TRANSACT} master (sort by
     * {@code TRAN-CARD-NUM} then {@code TRAN-ID}).
     *
     * <p>No-op in PostgreSQL: the equivalent ordering is produced at query time by
     * {@link StatementGenerationTasklet} via Spring Data {@code Sort.by("cardNum", "tranId")} (the
     * {@code STEP010} primary/secondary sort keys), so no standalone sort pass is required. The step
     * is retained for execution-log parity.</p>
     *
     * @return the {@code statementSortStep} bean (logs and finishes immediately)
     */
    @Bean
    public Step statementSortStep() {
        return new StepBuilder(STEP_SORT, jobRepository)
                .tasklet(noOpTasklet(
                                "STEP010: no-op (sorting performed by JPA query in StatementGenerationTasklet)"),
                        transactionManager)
                .build();
    }

    /**
     * CREASTMT.JCL <strong>STEP020</strong> &mdash; IDCAMS {@code REPRO} loading the sorted
     * sequential output into the temporary staging VSAM KSDS.
     *
     * <p>No-op in PostgreSQL: with no staging cluster there is nothing to load; the emission step
     * reads the production tables directly. The step is retained for execution-log parity.</p>
     *
     * @return the {@code statementLoadStagingStep} bean (logs and finishes immediately)
     */
    @Bean
    public Step statementLoadStagingStep() {
        return new StepBuilder(STEP_LOAD_STAGING, jobRepository)
                .tasklet(noOpTasklet("STEP020: no-op (no staging cluster load needed)"),
                        transactionManager)
                .build();
    }

    /**
     * CREASTMT.JCL <strong>STEP040</strong> &mdash; {@code EXEC PGM=CBSTM03A}.
     *
     * <p>This is the real work of the job: it delegates to {@link StatementGenerationTasklet}, which
     * walks the cross-reference / customer / account / transaction data and emits one HTML statement
     * and one plain-text statement per {@code (customer, account)}, preserving the {@code CBSTM03A}
     * HTML structure byte-for-byte (PR-09). The tasklet's own
     * {@code @Transactional(readOnly = true)} boundary combines with this step's
     * {@link PlatformTransactionManager} binding to provide the read-only unit-of-work semantics of
     * the original program (PR-24).</p>
     *
     * @return the {@code statementEmissionStep} bean backed by {@link #statementGenerationTasklet}
     */
    @Bean
    public Step statementEmissionStep() {
        return new StepBuilder(STEP_EMISSION, jobRepository)
                .tasklet(statementGenerationTasklet, transactionManager)
                .build();
    }

    /**
     * Statement-generation {@link Job} ({@link #JOB_NAME}). Chains the four steps in the exact
     * execution order of the original {@code CREASTMT.JCL}:
     * <ol>
     *   <li>{@link #statementPurgeStagingStep()} (DELDEF01 &mdash; no-op)</li>
     *   <li>{@link #statementSortStep()} (STEP010 &mdash; no-op; sort happens at query time)</li>
     *   <li>{@link #statementLoadStagingStep()} (STEP020 &mdash; no-op)</li>
     *   <li>{@link #statementEmissionStep()} (STEP040 &mdash; real work via
     *       {@link StatementGenerationTasklet})</li>
     * </ol>
     *
     * <p>Because each step persists its own {@code BATCH_STEP_EXECUTION} metadata, the job is
     * restartable from the failed step via standard {@code JobOperator} semantics. Spring Boot
     * auto-configuration registers this job with the {@code JobRegistry}, making it the fourth,
     * independently launchable job of the critical batch sequence (PR-12).</p>
     *
     * @return the {@code statementGenerationJob} bean chaining all four steps
     */
    @Bean
    public Job statementGenerationJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(statementPurgeStagingStep())
                .next(statementSortStep())
                .next(statementLoadStagingStep())
                .next(statementEmissionStep())
                .build();
    }

    /**
     * Builds a no-op {@link Tasklet} that logs the supplied message and finishes immediately.
     *
     * <p>Used for the three legacy infrastructure steps (DELDEF01 / STEP010 / STEP020) whose VSAM
     * staging, DFSORT and IDCAMS REPRO semantics have no PostgreSQL analog. Logging at INFO keeps the
     * step visible in the execution log (audit parity with the original JCL) while contributing
     * effectively zero runtime cost.</p>
     *
     * @param message the legacy-purpose message logged when the tasklet runs (never {@code null})
     * @return a {@link Tasklet} that logs {@code message} and returns {@link RepeatStatus#FINISHED}
     */
    private Tasklet noOpTasklet(String message) {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            log.info(message);
            return RepeatStatus.FINISHED;
        };
    }
}
