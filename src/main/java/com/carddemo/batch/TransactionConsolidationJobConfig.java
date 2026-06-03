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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration for the <strong>{@code transactionConsolidationJob}</strong> —
 * the Java/PostgreSQL replacement for the legacy JCL job {@code app/jcl/COMBTRAN.jcl}.
 *
 * <h2>Legacy mainframe behavior (COMBTRAN.jcl)</h2>
 * The original COMBTRAN job consolidated transactions in two chained steps:
 * <ol>
 *   <li><b>STEP05R</b> ({@code PGM=SORT}) — concatenated the current transaction master
 *       backup ({@code TRANSACT.BKUP(0)}) and the system-generated transactions
 *       ({@code SYSTRAN(0)}), then sorted the combined stream by {@code TRAN-ID} ascending
 *       ({@code SORT FIELDS=(TRAN-ID,A)}; key at position 1, length 16, character) into a
 *       new generation {@code TRANSACT.COMBINED(+1)}.</li>
 *   <li><b>STEP10</b> ({@code PGM=IDCAMS}) — {@code REPRO}'d the sorted combined file into the
 *       transaction master VSAM KSDS ({@code TRANSACT.VSAM.KSDS}).</li>
 * </ol>
 *
 * <h2>Modernized PostgreSQL behavior</h2>
 * In the relational target, the DFSORT + IDCAMS REPRO pipeline collapses into a single
 * idempotent SQL UPSERT executed inside one Spring Batch tasklet. Approved transactions are
 * already written directly into the {@code transactions} master table by the upstream
 * {@code POSTTRAN} job ({@link TransactionPostingJobConfig}); this job therefore acts as a
 * de-duplicating safety net that merges any <em>accepted</em> {@code processed = TRUE} rows still
 * residing only in the {@code daily_transactions} staging table into {@code transactions}. Rows
 * that {@code POSTTRAN} <em>rejected</em> (validation codes 100/101/102/103) are explicitly
 * excluded — they are also flagged {@code processed = TRUE} but live solely in
 * {@code rejected_transactions} (the DALYREJS equivalent), and per PR-03 they must never enter the
 * master {@code transactions} table that statement generation ({@code CREASTMT}) reads. This
 * mirrors {@code COMBTRAN.jcl}, which merges only the already-posted master backup and the
 * system-generated transactions and never reads the daily/reject stream. The explicit
 * {@code SORT FIELDS=(TRAN-ID,A)} of STEP05R is satisfied implicitly by the
 * {@code transactions} primary-key B-tree index on {@code tran_id}, which governs the read
 * order consumed by downstream statement generation ({@code CREASTMT}).
 *
 * <h2>Position in the critical batch sequence (PR-12)</h2>
 * This is the <strong>third</strong> job in the mandated sequence
 * {@code POSTTRAN → INTCALC → COMBTRAN → CREASTMT}. It remains independently runnable via the
 * {@code BatchAdminController} and registers itself with the {@code JobRegistry} through the
 * standard Spring Boot batch auto-configuration.
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-03</b> — preserves reject segregation: transactions rejected by {@code POSTTRAN}
 *       (validation codes 100/101/102/103) remain solely in {@code rejected_transactions} and are
 *       never consolidated into the master {@code transactions} table (enforced by the second
 *       {@code NOT EXISTS} guard in {@link #CONSOLIDATION_UPSERT_SQL}).</li>
 *   <li><b>PR-12</b> — preserves the critical batch sequence ordering; independently runnable.</li>
 *   <li><b>PR-22</b> — {@code ON CONFLICT (tran_id) DO NOTHING} guarantees no data loss: existing
 *       master rows written by {@code POSTTRAN} are never clobbered, and reruns are idempotent.</li>
 *   <li><b>PR-23</b> — single-table UPSERT; no cross-entity lock-ordering concerns arise.</li>
 *   <li><b>PR-24</b> — the tasklet executes within a single transaction managed by the injected
 *       {@link PlatformTransactionManager}, mirroring the implicit CICS {@code SYNCPOINT}
 *       unit-of-work boundary.</li>
 *   <li><b>PR-28</b> — uses Jakarta EE 10 / Spring 6 annotations exclusively.</li>
 *   <li><b>PR-29</b> — constructor injection only, via Lombok {@code @RequiredArgsConstructor}
 *       over {@code final} fields (no field injection).</li>
 * </ul>
 *
 * <p>Column names referenced by {@link #CONSOLIDATION_UPSERT_SQL} mirror the authoritative
 * Flyway migration {@code src/main/resources/db/migration/V1__schema.sql} ({@code transactions}
 * and {@code daily_transactions} tables).
 *
 * @see TransactionPostingJobConfig
 * @see org.springframework.batch.core.step.tasklet.Tasklet
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class TransactionConsolidationJobConfig {

    /**
     * Logical name of the consolidation {@link Job} bean. Used by the {@code JobRegistry} and the
     * {@code BatchAdminController} to launch this job by name as part of the
     * {@code POSTTRAN → INTCALC → COMBTRAN → CREASTMT} sequence (PR-12).
     */
    public static final String JOB_NAME = "transactionConsolidationJob";

    /** Logical name of the single tasklet-based consolidation {@link Step}. */
    private static final String STEP_NAME = "transactionConsolidationStep";

    /**
     * Idempotent SQL UPSERT that replaces the COMBTRAN {@code SORT} + {@code REPRO} pipeline.
     *
     * <p>Semantics:
     * <ul>
     *   <li>Selects only {@code processed = TRUE} rows from the {@code daily_transactions} staging
     *       table — i.e. rows the upstream {@code POSTTRAN} job has already accounted for. This
     *       guarantees the {@code NOT NULL} business columns required by {@code transactions}
     *       ({@code type_cd}, {@code cat_cd}, {@code card_num}, {@code amount}) are populated.
     *       <strong>Note:</strong> {@code POSTTRAN} sets {@code processed = TRUE} for
     *       <em>both</em> accepted rows (written to {@code transactions}) <em>and</em> rejected
     *       rows (written only to {@code rejected_transactions}); the {@code processed} flag alone
     *       therefore does <em>not</em> distinguish accepted from rejected, which is why the two
     *       {@code NOT EXISTS} guards below are both required.</li>
     *   <li>The first {@code NOT EXISTS} correlated sub-query (against {@code transactions}) skips
     *       staging rows already present in the master table, so the common case (POSTTRAN already
     *       inserted the accepted rows) performs zero writes.</li>
     *   <li>The second {@code NOT EXISTS} correlated sub-query (against {@code rejected_transactions})
     *       excludes any staging row whose {@code tran_id} was rejected by {@code POSTTRAN} (codes
     *       100/101/102/103). Rejected transactions must reside <em>only</em> in the
     *       {@code rejected_transactions} sink (the DALYREJS equivalent — a separate store, PR-03)
     *       and must never contaminate the master {@code transactions} table consumed by statement
     *       generation ({@code CREASTMT}). This faithfully preserves the original
     *       {@code COMBTRAN.jcl} contract: STEP05R sorts only {@code TRANSACT.BKUP(0)} (already-posted
     *       master) and {@code SYSTRAN(0)} (system-generated) — it never reads the daily/reject
     *       stream, so a rejected transaction can never enter the master.</li>
     *   <li>{@code COALESCE(proc_timestamp, orig_timestamp)} guarantees a non-null processing
     *       timestamp even when the staged {@code DALYTRAN-PROC-TS} was never set.</li>
     *   <li>{@code ON CONFLICT (tran_id) DO NOTHING} is the final safety net: it absorbs both
     *       duplicate {@code tran_id} values within the staging set and any concurrent insert race,
     *       preserving existing master rows (PR-22).</li>
     * </ul>
     *
     * <p>Columns omitted from the {@code INSERT} list ({@code version}, {@code created_date},
     * {@code last_modified_date}, {@code created_by}, {@code last_modified_by}) are supplied by
     * their schema-level {@code DEFAULT}s or are nullable, so they are intentionally not projected.
     */
    private static final String CONSOLIDATION_UPSERT_SQL = """
            INSERT INTO transactions (
                tran_id, type_cd, cat_cd, source, description,
                amount, merchant_id, merchant_name, merchant_city, merchant_zip,
                card_num, orig_timestamp, proc_timestamp
            )
            SELECT
                dt.tran_id, dt.type_cd, dt.cat_cd, dt.source, dt.description,
                dt.amount, dt.merchant_id, dt.merchant_name, dt.merchant_city, dt.merchant_zip,
                dt.card_num, dt.orig_timestamp, COALESCE(dt.proc_timestamp, dt.orig_timestamp)
            FROM daily_transactions dt
            WHERE dt.processed = TRUE
              AND NOT EXISTS (
                  SELECT 1 FROM transactions t WHERE t.tran_id = dt.tran_id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM rejected_transactions r WHERE r.tran_id = dt.tran_id
              )
            ON CONFLICT (tran_id) DO NOTHING
            """;

    /** Spring Batch metadata repository (auto-configured by Spring Boot). */
    private final JobRepository jobRepository;

    /**
     * Transaction manager bracketing the tasklet's unit of work — the {@code SYNCPOINT}
     * equivalent (PR-24).
     */
    private final PlatformTransactionManager transactionManager;

    /** Executes the raw consolidation UPSERT (the COMBTRAN SORT + REPRO replacement). */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Defines the consolidation {@link Tasklet} that merges processed {@code daily_transactions}
     * into the {@code transactions} master table via {@link #CONSOLIDATION_UPSERT_SQL}.
     *
     * <p>The number of rows actually inserted is logged and recorded on the
     * {@link StepContribution} write-count so it surfaces in the Spring Batch
     * {@code BATCH_STEP_EXECUTION} metadata, giving operators visibility equivalent to the
     * IDCAMS {@code SYSPRINT} record count from the original {@code COMBTRAN} STEP10.
     *
     * @return a single-shot tasklet that performs the idempotent consolidation UPSERT
     */
    @Bean
    public Tasklet transactionConsolidationTasklet() {
        return (StepContribution contribution, ChunkContext chunkContext) -> {
            log.info("TransactionConsolidationJob ({}) starting — merging processed "
                    + "daily_transactions into the transactions master table", JOB_NAME);

            int rowsAffected = jdbcTemplate.update(CONSOLIDATION_UPSERT_SQL);

            log.info("TransactionConsolidationJob merged {} row(s) into the transactions table",
                    rowsAffected);
            contribution.incrementWriteCount(rowsAffected);

            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Builds the single tasklet-based {@link Step} for the consolidation job.
     *
     * <p>Binding the {@link #transactionManager} to the step ensures the {@code UPSERT} runs inside
     * one transaction boundary (PR-24).
     *
     * @return the {@code transactionConsolidationStep} bean
     */
    @Bean
    public Step transactionConsolidationStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(transactionConsolidationTasklet(), transactionManager)
                .build();
    }

    /**
     * Builds the {@link Job} bean ({@link #JOB_NAME}) consisting of the single consolidation step.
     *
     * <p>The job is registered with the Spring Batch {@code JobRegistry} by Spring Boot
     * auto-configuration, making it launchable by name through the {@code BatchAdminController} and
     * runnable as the third stage of the {@code POSTTRAN → INTCALC → COMBTRAN → CREASTMT}
     * sequence (PR-12).
     *
     * @return the {@code transactionConsolidationJob} bean
     */
    @Bean
    public Job transactionConsolidationJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionConsolidationStep())
                .build();
    }
}
