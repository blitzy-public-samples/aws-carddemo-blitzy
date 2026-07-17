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

import java.util.Map;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.data.RepositoryItemReader;
import org.springframework.batch.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import com.aws.carddemo.batch.writer.TransactionBackupItemWriter;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Spring Batch {@link Configuration} that re-platforms the legacy transaction-file backup job
 * {@code legacy/jcl/TRANBKP.jcl} (source {@code app/jcl/TRANBKP.jcl}), which drives the generic
 * IDCAMS procedure {@code legacy/proc/REPROC.prc} with the control member
 * {@code legacy/ctl/REPROCT.ctl}.
 *
 * <h2>Legacy behavior (TRANBKP.jcl &rarr; REPROC.prc &rarr; REPROCT.ctl)</h2>
 * <p>The mainframe job performs three steps against the transaction master VSAM cluster
 * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}:</p>
 * <ol>
 *   <li><strong>{@code STEP05R} ({@code EXEC PROC=REPROC}).</strong> Runs {@code PGM=IDCAMS} with
 *       {@code SYSIN} bound to {@code REPROCT}, whose single command is
 *       {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)}. It copies the key-sequenced transaction
 *       dataset into a brand-new generation-data-group member
 *       {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(+1)} declared {@code DCB=(LRECL=350,RECFM=FB)} &mdash;
 *       i.e. a fixed-block backup of the entire transaction master.</li>
 *   <li><strong>{@code STEP05} ({@code IDCAMS DELETE}).</strong> Deletes the transaction cluster
 *       and its alternate index (guarded by {@code IF MAXCC LE 08 THEN SET MAXCC = 0}).</li>
 *   <li><strong>{@code STEP10} ({@code IDCAMS DEFINE CLUSTER}, {@code COND=(4,LT)}).</strong>
 *       Recreates the empty cluster with {@code KEYS(16 0)} (16-character key at offset 0) and
 *       {@code RECORDSIZE(350 350)}.</li>
 * </ol>
 *
 * <h2>What this job reproduces &mdash; the REPRO export only</h2>
 * <p>This configuration reproduces <strong>{@code STEP05R}</strong>: it streams the entire
 * {@code transaction} table (the relational form of {@code TRANSACT.VSAM.KSDS}, copybook
 * {@code legacy/cpy/CVTRA05Y.cpy}, {@code TRAN-RECORD}, {@code RECLN = 350}) in ascending
 * primary-key ({@code tranId}) order &mdash; mirroring an IDCAMS REPRO of a key-sequenced dataset
 * &mdash; and serializes each row to a byte-exact 350-byte fixed-width record through
 * {@link TransactionBackupItemWriter}. That writer emits the records into a <em>timestamped</em>
 * backup file whose name is the relational analog of the GDG {@code (+1)} "new generation": each run
 * produces a fresh, immutable file rather than overwriting the previous one. The
 * {@code LRECL=350 RECFM=FB} external file contract is preserved byte/semantically
 * (Technical Specification &sect;0.7.2 hotspot M2); the fixed-width layout, offsets, and
 * zoned-decimal overpunch encoding are owned by the writer and {@code common/util/FixedWidthCodec}.</p>
 *
 * <h2>Documented deviation &mdash; VSAM storage management is NOT replicated</h2>
 * <p>The IDCAMS storage-management steps {@code STEP05} ({@code DELETE ... CLUSTER} /
 * {@code DELETE ... ALTERNATEINDEX}) and {@code STEP10} ({@code DEFINE CLUSTER ...}) are
 * <strong>intentionally not reproduced</strong>. On the mainframe they exist only to reclaim and
 * re-initialize VSAM storage after the backup copy; in the relational target a backup export never
 * drops and recreates the {@code transaction} table &mdash; that would be destructive and has no
 * relational equivalent. This is a deliberate, documented deviation recorded in
 * {@code docs/decision-log.md} (decision D14: GDG/VSAM backup semantics &rarr; scheduled database
 * export; {@code DELETE}/{@code DEFINE} not carried over). It is documented rather than silently
 * dropped; this class only references that decision and neither owns nor edits the decision log.</p>
 *
 * <h2>Restartability &mdash; deliberate full regeneration</h2>
 * <p>Because the backup mirrors a complete REPRO copy, it is a <strong>full regeneration on every
 * execution</strong>: {@link TransactionBackupItemWriter} opens a new, freshly timestamped file at
 * the start of the step and is documented as non-restartable. To keep that contract self-consistent,
 * the reader built here sets {@code saveState(false)} (see
 * {@link #transactionBackupItemReader(TransactionRepository)}): should the step be restarted, the
 * reader re-reads the table from the beginning so the newly opened file always receives the
 * <em>complete</em> transaction master. This is the one intentional departure from the read-only
 * print siblings (for example {@code XrefPrintJob}), whose resumable scans use
 * {@code saveState(true)}.</p>
 *
 * <h2>Spring Batch wiring and infrastructure contract</h2>
 * <ul>
 *   <li>Consistent with every other CardDemo batch configuration, this class does
 *       <strong>not</strong> declare {@code @EnableBatchProcessing} and does <strong>not</strong>
 *       redeclare any batch infrastructure bean: the {@link JobRepository} and the batch
 *       {@link PlatformTransactionManager} are supplied by Spring Boot's batch auto-configuration
 *       and injected as method parameters (see {@code com.aws.carddemo.config.BatchConfig} for the
 *       full rationale).</li>
 *   <li>The job never runs at startup because {@code application.yml} sets
 *       {@code spring.batch.job.enabled=false}; it is launched explicitly (by {@link Job}-bean
 *       injection with a {@code JobLauncher}, or by name through the CI/CD workflow
 *       {@code .github/workflows/ci.yml} &mdash; the modern equivalent of the mainframe JCL
 *       scheduler; AAP &sect;0.4.4).</li>
 *   <li>The {@link CorrelationIdJobListener} (same package) is registered on the job so every log
 *       line emitted during the run carries the observability correlation id
 *       (Technical Specification &sect;0.9.5).</li>
 *   <li>{@link TransactionBackupItemWriter} also implements
 *       {@link org.springframework.batch.core.StepExecutionListener}; when it is set as the step's
 *       writer, Spring Batch automatically registers its {@code beforeStep}/{@code afterStep}
 *       callbacks (which open and close the backup file), so it is deliberately <em>not</em>
 *       registered a second time as a step listener here.</li>
 * </ul>
 *
 * <p><strong>Bean-name note.</strong> The configuration bean is explicitly named
 * {@code transactionBackupJobConfig} so it does not collide with the {@code transactionBackupJob}
 * {@link Job} bean declared by {@link #transactionBackupJob(JobRepository, Step,
 * CorrelationIdJobListener)} (the default configuration-class bean name would otherwise be
 * {@code transactionBackupJob}).</p>
 *
 * @see TransactionBackupItemWriter
 * @see Transaction
 * @see TransactionRepository
 * @see CorrelationIdJobListener
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Configuration("transactionBackupJobConfig")
public class TransactionBackupJob {

    /**
     * Chunk (commit-interval) size for the step and page size for the reader.
     *
     * <p>The two are kept identical so each transactional chunk corresponds to exactly one reader
     * page, bounding memory while streaming the full {@code transaction} table. The value matches
     * the page size used by the sibling batch readers for consistency and remains correct for an
     * arbitrarily large table.</p>
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * JPA/entity property name of the transaction primary key ({@code TRAN-ID}, column
     * {@code tran_id}). Sorting the reader ascending on this property reproduces the VSAM
     * key-sequenced ({@code RECORD KEY}) order that an IDCAMS REPRO of {@code TRANSACT.VSAM.KSDS}
     * would emit.
     */
    private static final String TRAN_ID_PROPERTY = "tranId";

    /**
     * {@link org.springframework.data.repository.PagingAndSortingRepository} method invoked by the
     * reader. {@code findAll} is inherited by {@link TransactionRepository} from
     * {@code JpaRepository}; the {@link RepositoryItemReader} appends a {@code Pageable} carrying the
     * ascending {@link #TRAN_ID_PROPERTY} sort, so the {@code findAll(Pageable)} overload is invoked.
     */
    private static final String READER_METHOD_NAME = "findAll";

    /**
     * Stable name assigned to the reader; also its {@code ExecutionContext} state-key prefix. A
     * fixed name is required by {@link RepositoryItemReaderBuilder} even though this reader does not
     * persist paging state (see the {@code saveState(false)} rationale in
     * {@link #transactionBackupItemReader(TransactionRepository)}).
     */
    private static final String READER_NAME = "transactionBackupItemReader";

    /**
     * Defines the {@code transactionBackupJob} batch job: a single-step job that exports the entire
     * transaction master to a timestamped 350-byte fixed-width backup file, reproducing the
     * {@code STEP05R} REPRO export of {@code TRANBKP.jcl}.
     *
     * <p>The bean name is exactly {@code transactionBackupJob} (from the method name), which is the
     * identifier used to launch the job by name. The {@code correlationIdJobListener} is attached so
     * the correlation id propagates across the batch boundary for the whole execution. A clean pass
     * completes with {@code COMPLETED} status (return code {@code 0}); any I/O failure while reading
     * or writing propagates, fails the step, and yields a {@code FAILED} job with a non-zero return
     * code &mdash; the batch return-code-8 analog for a backup/copy failure.</p>
     *
     * @param jobRepository            the auto-configured Spring Batch {@link JobRepository};
     *                                 never {@code null}
     * @param transactionBackupStep    the single {@link Step} of this job (see
     *                                 {@link #transactionBackupStep(JobRepository,
     *                                 PlatformTransactionManager, TransactionRepository,
     *                                 TransactionBackupItemWriter)}); never {@code null}
     * @param correlationIdJobListener the cross-cutting listener that publishes the correlation id
     *                                 into the logging context for the run; never {@code null}
     * @return the configured {@link Job}; never {@code null}
     */
    @Bean
    public Job transactionBackupJob(JobRepository jobRepository,
                                    Step transactionBackupStep,
                                    CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder("transactionBackupJob", jobRepository)
                .listener(correlationIdJobListener)
                .start(transactionBackupStep)
                .build();
    }

    /**
     * Defines the single chunk-oriented {@link Step} that streams every {@link Transaction} in
     * ascending primary-key order and writes it to the fixed-width backup file.
     *
     * <p>The step is {@code <Transaction, Transaction>}: items flow from the reader straight to the
     * writer with no {@code ItemProcessor}, mirroring an IDCAMS REPRO that copies each record
     * verbatim. The reader is constructed inline (see
     * {@link #transactionBackupItemReader(TransactionRepository)}); the framework registers it as an
     * {@code ItemStream} and manages its open/update/close lifecycle per execution. The injected
     * {@link TransactionBackupItemWriter} is a singleton {@code @Component} that also implements
     * {@link org.springframework.batch.core.StepExecutionListener}: setting it via
     * {@code .writer(...)} causes Spring Batch to auto-register its {@code beforeStep}/{@code afterStep}
     * callbacks, which open the timestamped backup file at the start of the step and flush/close it at
     * the end. It is therefore intentionally not registered again as a step listener (that would open
     * and close the file twice).</p>
     *
     * @param jobRepository               the auto-configured Spring Batch {@link JobRepository};
     *                                    never {@code null}
     * @param transactionManager          the auto-configured batch {@link PlatformTransactionManager}
     *                                    that governs each chunk's transaction; never {@code null}
     * @param transactionRepository       the Spring Data repository backing the reader;
     *                                    never {@code null}
     * @param transactionBackupItemWriter the fixed-width 350-byte backup writer (also the step's
     *                                    open/close lifecycle listener); never {@code null}
     * @return the configured {@link Step}; never {@code null}
     */
    @Bean
    public Step transactionBackupStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      TransactionRepository transactionRepository,
                                      TransactionBackupItemWriter transactionBackupItemWriter) {
        return new StepBuilder("transactionBackupStep", jobRepository)
                .<Transaction, Transaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionBackupItemReader(transactionRepository))
                .writer(transactionBackupItemWriter)
                .build();
    }

    /**
     * Builds the read-only {@link RepositoryItemReader} that streams the {@code transaction} table
     * in ascending {@code tran_id} order, reproducing the key-sequenced order an IDCAMS REPRO of
     * {@code TRANSACT.VSAM.KSDS} would produce.
     *
     * <p>The reader is backed by {@link TransactionRepository} and drives its inherited
     * {@code findAll(Pageable)} method: on each page the {@link RepositoryItemReader} constructs a
     * {@code Pageable} carrying the ascending {@link #TRAN_ID_PROPERTY} sort, so rows are returned in
     * the exact primary-key order the legacy KSDS browse produced. Paging with a page size of
     * {@value #CHUNK_SIZE} keeps memory bounded regardless of table size.</p>
     *
     * <p><strong>{@code saveState(false)} is deliberate.</strong> {@link TransactionBackupItemWriter}
     * performs a full regeneration on every run (it opens a new, freshly timestamped file per step
     * and is documented as non-restartable). If this reader persisted its paging position, a restart
     * would resume mid-table and the newly opened backup file would omit the earlier records. By not
     * saving state, a restart re-reads the table from the beginning, guaranteeing the backup file is
     * always the <em>complete</em> transaction master &mdash; keeping the reader and writer contracts
     * consistent.</p>
     *
     * @param transactionRepository the repository to page over; never {@code null}
     * @return a fully configured, read-only {@link RepositoryItemReader} over {@link Transaction}
     */
    private RepositoryItemReader<Transaction> transactionBackupItemReader(
            TransactionRepository transactionRepository) {
        return new RepositoryItemReaderBuilder<Transaction>()
                .name(READER_NAME)
                .repository(transactionRepository)
                .methodName(READER_METHOD_NAME)
                .sorts(Map.of(TRAN_ID_PROPERTY, Sort.Direction.ASC))
                .pageSize(CHUNK_SIZE)
                .saveState(false)
                .build();
    }
}
