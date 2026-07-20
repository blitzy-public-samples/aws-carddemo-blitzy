/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.batch.reader.CombinedTransactionItemReader;
import com.aws.carddemo.batch.writer.TransactionJpaItemWriter;
import com.aws.carddemo.domain.Transaction;

/**
 * Spring Batch {@link Configuration} that re-platforms the legacy transaction-combine job
 * {@code legacy/jcl/COMBTRAN.jcl} (source {@code app/jcl/COMBTRAN.jcl}, <em>"Sort current
 * transaction file and system generated transactions"</em>). It concatenates the prior transaction
 * backup with the newly generated system transactions (for example the interest postings produced
 * by {@code InterestCalculationJob}), sorts the combined set by transaction id ascending, and loads
 * the result into the transaction master (AAP &sect;0.4.4 "SORT &rarr; Java comparator/ORDER BY",
 * &sect;0.5.4).
 *
 * <h2>Legacy behavior reproduced (COMBTRAN.jcl)</h2>
 * <ol>
 *   <li><strong>{@code STEP05R} ({@code EXEC PGM=SORT}).</strong> {@code SORTIN} is the DD
 *       concatenation of {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} <em>followed by</em>
 *       {@code AWS.M2.CARDDEMO.SYSTRAN(0)} &mdash; the current transaction backup generation first,
 *       the system-generated transaction file second. Both are 350-byte fixed records
 *       ({@code LRECL=350, RECFM=FB}; copybook {@code legacy/cpy/CVTRA05Y.cpy}, {@code TRAN-RECORD}).
 *       {@code SYMNAMES} declares {@code TRAN-ID,1,16,CH} and {@code SYSIN} declares
 *       {@code SORT FIELDS=(TRAN-ID,A)}: the sort key is the 16-character transaction id at record
 *       position 1, ascending. The output is the new generation {@code TRANSACT.COMBINED(+1)}.</li>
 *   <li><strong>{@code STEP10} ({@code EXEC PGM=IDCAMS}).</strong> Loads the sorted combined file
 *       into the key-sequenced transaction master with {@code REPRO INFILE(TRANSACT)
 *       OUTFILE(TRANVSAM)}, where {@code TRANVSAM} is {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}.</li>
 * </ol>
 *
 * <h2>Target construct mapping (JCL &rarr; Spring Batch)</h2>
 * <ul>
 *   <li><strong>Concatenated {@code SORTIN} + {@code SORT FIELDS=(TRAN-ID,A)} &rarr; the reader.</strong>
 *       The {@code STEP05R} concatenate-and-sort is reproduced by
 *       {@link CombinedTransactionItemReader}, which reads the backup dataset <em>before</em> the
 *       system dataset (preserving the {@code SORTIN} concatenation order), decodes each 350-byte
 *       {@code TRAN-RECORD} through {@code common/util/FixedWidthCodec}, and emits every record
 *       ordered by {@link Transaction#getTranId()} ascending. The parity-critical sort key
 *       ({@code TRAN-ID} at position&nbsp;1, length&nbsp;16, ascending, lexicographic) is therefore
 *       owned by the reader; this configuration must not reorder its output.</li>
 *   <li><strong>{@code STEP10} {@code REPRO} &rarr; the writer.</strong> The IDCAMS load of the
 *       combined file into {@code TRANSACT.VSAM.KSDS} is reproduced by
 *       {@link TransactionJpaItemWriter}, which loads each {@link Transaction} into the relational
 *       transaction master (PostgreSQL table {@code transaction}) with REPRO-without-{@code REPLACE}
 *       semantics: a record whose {@code tran_id} is new is inserted, and a record whose key already
 *       exists is <em>rejected</em> (the {@code IDC1440I} skip) rather than overwritten, leaving the
 *       existing row and any pre-existing "stale" rows untouched. {@code STEP10} performs no
 *       {@code DELETE}/{@code DEFINE} and specifies no {@code REPLACE}, so the master is merged into,
 *       never rebuilt; when any record is rejected the step ends with condition code {@code 4}.</li>
 *   <li><strong>Two JCL steps &rarr; one chunk step.</strong> Because the reader already yields the
 *       fully combined and sorted stream, the sort ({@code STEP05R}) and the load ({@code STEP10})
 *       collapse into a single chunk-oriented {@link Step} whose reader feeds the writer directly.
 *       There is no {@code ItemProcessor}: COMBTRAN carries no business logic and mutates no field,
 *       so items flow reader&rarr;writer verbatim, mirroring the IDCAMS {@code REPRO} copy (this is
 *       the same reader&rarr;writer, no-processor shape used by the sibling REPRO job
 *       {@link TransactionBackupJob}).</li>
 * </ul>
 *
 * <h2>Ordering guarantee</h2>
 * <p>The combined output is ordered strictly by {@code tranId} ascending, matching
 * {@code SORT FIELDS=(TRAN-ID,A)}. That ordering is produced once, by the reader's stable sort over
 * the backup-then-system concatenation, and is preserved end-to-end: the chunk step neither
 * reorders nor filters, and {@link TransactionJpaItemWriter} persists each chunk in the exact order
 * it is received.</p>
 *
 * <h2>Infrastructure contract</h2>
 * <p>Consistent with every other CardDemo batch configuration, this class does <strong>not</strong>
 * declare {@code @EnableBatchProcessing} and does <strong>not</strong> redeclare any batch
 * infrastructure bean: the {@link JobRepository} and the batch {@link PlatformTransactionManager}
 * are supplied by Spring Boot's batch auto-configuration and injected as method parameters (see
 * {@code com.aws.carddemo.config.BatchConfig} for the full rationale). The job never runs at
 * startup because {@code application.yml} sets {@code spring.batch.job.enabled=false}; it is
 * launched explicitly (by {@link Job}-bean injection with a {@code JobLauncher}, or by name through
 * the CI/CD workflow {@code .github/workflows/ci.yml} &mdash; the modern equivalent of the mainframe
 * JCL scheduler; AAP &sect;0.4.4). The {@link CorrelationIdJobListener} (same package) is registered
 * on the job so every log line emitted during the run carries the observability correlation id
 * (Technical Specification &sect;0.9.5).</p>
 *
 * <p><strong>Return codes.</strong> The three-value {@code 0}/{@code 4}/{@code 8} contract of the
 * mainframe {@code SORT}/{@code IDCAMS} steps is preserved by {@link TransactionJpaItemWriter}
 * (which is auto-registered as the step's {@code StepExecutionListener}): a clean pass completes with
 * {@code COMPLETED} (RC&nbsp;0); a pass in which at least one record was rejected as a duplicate key
 * (the {@code IDC1440I} skip of a no-{@code REPLACE} {@code REPRO}) completes
 * {@code COMPLETED_WITH_REJECTS} (RC&nbsp;4) while still loading every non-duplicate record; and any
 * exception raised while reading, sorting, or loading propagates, fails the step, and yields a
 * {@code FAILED} job (RC&nbsp;8). The job-level exit status is translated to the process exit code by
 * {@code com.aws.carddemo.config.BatchExitCodeGenerator}.</p>
 *
 * <p><strong>Bean-name note.</strong> The configuration bean is explicitly named
 * {@code transactionCombineJobConfig} so it does not collide with the {@code transactionCombineJob}
 * {@link Job} bean declared by {@link #transactionCombineJob(JobRepository, Step,
 * CorrelationIdJobListener)} (the default configuration-class bean name would otherwise be
 * {@code transactionCombineJob}). This mirrors the naming used by the sibling batch configurations
 * (for example {@code transactionBackupJobConfig}, {@code xrefPrintJobConfig}).</p>
 *
 * @see CombinedTransactionItemReader
 * @see TransactionJpaItemWriter
 * @see Transaction
 * @see CorrelationIdJobListener
 * @see TransactionBackupJob
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Configuration("transactionCombineJobConfig")
public class TransactionCombineJob {

    /**
     * Chunk (commit-interval) size for the combine step: <strong>one record per chunk</strong>.
     *
     * <p>The load reproduces a record-at-a-time IDCAMS {@code REPRO} without {@code REPLACE} (see
     * {@link TransactionJpaItemWriter}): for each record the writer probes the master with
     * {@code existsById} and either inserts it or rejects it as a duplicate ({@code IDC1440I}). A
     * commit interval of {@code 1} is required for correctness, not merely for granularity: it
     * guarantees each inserted record is committed before the next record's {@code existsById} probe,
     * so a duplicate {@code tranId} that appears twice <em>within the same combined input</em> is
     * detected on its second occurrence rather than slipping through an unflushed persistence context.
     * The final {@code tranId}-ascending ordering is unaffected by the chunk size &mdash; it is fixed
     * once by {@link CombinedTransactionItemReader}'s single global sort of the concatenated inputs
     * (the combined volume is small: the seed data holds a few hundred transactions plus any backup
     * generations).</p>
     */
    private static final int CHUNK_SIZE = 1;

    /**
     * Defines the {@code transactionCombineJob} batch job: a single-step job that combines the
     * transaction backup with the system-generated transactions, sorts them by transaction id
     * ascending, and loads them into the transaction master &mdash; reproducing
     * {@code legacy/jcl/COMBTRAN.jcl}.
     *
     * <p>The bean name is exactly {@code transactionCombineJob} (from the method name), which is the
     * identifier used to launch the job by name. The {@code correlationIdJobListener} is attached so
     * the correlation id propagates across the batch boundary for the whole execution.</p>
     *
     * @param jobRepository            the auto-configured Spring Batch {@link JobRepository};
     *                                 never {@code null}
     * @param transactionCombineStep   the single {@link Step} of this job (see
     *                                 {@link #transactionCombineStep(JobRepository,
     *                                 PlatformTransactionManager, CombinedTransactionItemReader,
     *                                 TransactionJpaItemWriter)}); never {@code null}
     * @param correlationIdJobListener the cross-cutting listener that publishes the correlation id
     *                                 into the logging context for the run; never {@code null}
     * @return the configured {@link Job}; never {@code null}
     */
    @Bean
    public Job transactionCombineJob(JobRepository jobRepository,
                                     Step transactionCombineStep,
                                     CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder("transactionCombineJob", jobRepository)
                .listener(correlationIdJobListener)
                .start(transactionCombineStep)
                .build();
    }

    /**
     * Defines the single chunk-oriented {@link Step} that streams the combined, {@code tranId}-sorted
     * transaction set from the reader straight into the transaction master through the writer.
     *
     * <p>The step is {@code <Transaction, Transaction>}: items flow from the reader directly to the
     * writer with no {@code ItemProcessor}, mirroring the IDCAMS {@code REPRO} of {@code STEP10}
     * that loads each record verbatim (COMBTRAN performs no business mutation). The injected
     * {@link CombinedTransactionItemReader} is a {@code @StepScope} {@code @Component}: because its
     * two input locations are late-bound job parameters resolved with SpEL, the framework supplies a
     * step-scoped proxy that binds those parameters when the step runs, and Spring Batch registers
     * the proxy as an {@code ItemStream} so its {@code open}/{@code update}/{@code close} lifecycle
     * is managed per execution. The injected {@link TransactionJpaItemWriter} is a singleton
     * {@code @Component} that persists each chunk (the {@code REPRO} load). The chunk commit is
     * governed by the auto-configured batch {@link PlatformTransactionManager}.</p>
     *
     * <p>The reader has already applied the {@code SORT FIELDS=(TRAN-ID,A)} ordering over the
     * backup-then-system concatenation, so this step must not (and does not) reorder items: the
     * ascending {@code tranId} sequence is preserved exactly as it reaches the writer.</p>
     *
     * @param jobRepository                 the auto-configured Spring Batch {@link JobRepository};
     *                                      never {@code null}
     * @param transactionManager            the auto-configured batch
     *                                      {@link PlatformTransactionManager} that governs each
     *                                      chunk's transaction; never {@code null}
     * @param combinedTransactionItemReader the step-scoped reader that concatenates the backup and
     *                                      system transaction inputs and emits them in ascending
     *                                      {@code tranId} order ({@code STEP05R}); never {@code null}
     * @param transactionJpaItemWriter      the writer that loads each {@link Transaction} into the
     *                                      {@code transaction} master &mdash; inserting new keys and
     *                                      rejecting duplicates ({@code STEP10} {@code REPRO} without
     *                                      {@code REPLACE}); never {@code null}
     * @return the configured {@link Step}; never {@code null}
     */
    @Bean
    public Step transactionCombineStep(JobRepository jobRepository,
                                       PlatformTransactionManager transactionManager,
                                       CombinedTransactionItemReader combinedTransactionItemReader,
                                       TransactionJpaItemWriter transactionJpaItemWriter) {
        return new StepBuilder("transactionCombineStep", jobRepository)
                .<Transaction, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(combinedTransactionItemReader)
                .writer(transactionJpaItemWriter)
                .build();
    }
}
