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

import com.aws.carddemo.batch.processor.StatementProcessor;
import com.aws.carddemo.batch.writer.StatementItemWriter;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.repository.CardXrefRepository;

/**
 * Spring Batch {@link Configuration} that re-platforms the legacy statement-creation program
 * {@code legacy/cbl/CBSTM03A.CBL} (source {@code app/cbl/CBSTM03A.CBL}, 924 lines) together with its
 * called generic file-I/O subprogram {@code legacy/cbl/CBSTM03B.CBL} (source
 * {@code app/cbl/CBSTM03B.CBL}, 230 lines), orchestrated on the mainframe by
 * {@code legacy/jcl/CREASTMT.JCL}. The job produces, for <em>every</em> card in the cross-reference,
 * one account statement rendered in <strong>both</strong> a plain-text form and an HTML form (AAP
 * &sect;0.4.4, &sect;0.5.4, &sect;0.5.7).
 *
 * <h2>&#9888; CRITICAL bean-name contract (cross-folder coordination)</h2>
 * <p>The statement {@link Job} {@code @Bean} declared here is named <strong>exactly
 * {@code statementGenerationJob}</strong> (the {@link #statementGenerationJob(JobRepository, Step,
 * CorrelationIdJobListener)} method name is the bean name, and the {@link JobBuilder} name matches).
 * That name is the identifier by which the job is launched &mdash; whether by bean/name injection
 * through a {@code JobLauncher} or by name through the CI/CD workflow {@code .github/workflows/ci.yml}
 * (the modern equivalent of the mainframe JCL scheduler). Renaming the bean would break every such
 * launch site, so the name is fixed.</p>
 *
 * <h2>Legacy behavior reproduced (CBSTM03A {@code 1000-MAINLINE}, L316-L329)</h2>
 * <p>The COBOL driver walks the card cross-reference sequentially
 * ({@code PERFORM 1000-XREFFILE-GET-NEXT}) and, for each card that is read before end-of-file,
 * performs the following in order:</p>
 * <ol>
 *   <li>{@code 2000-CUSTFILE-GET} &mdash; keyed read of the customer master by {@code XREF-CUST-ID};</li>
 *   <li>{@code 3000-ACCTFILE-GET} &mdash; keyed read of the account master by {@code XREF-ACCT-ID};</li>
 *   <li>{@code 5000-CREATE-STATEMENT} &mdash; emit the statement header (name, address lines,
 *       {@code Account ID}, {@code Current Balance} formatted {@code PIC 9(9).99-}, {@code FICO Score},
 *       and the {@code TRANSACTION SUMMARY} banner);</li>
 *   <li>{@code MOVE ZERO TO WS-TOTAL-AMT} then {@code 4000-TRNXFILE-GET} &mdash; reset the running
 *       total and read the card's transactions, emitting one detail line per transaction and finally
 *       the accumulated total.</li>
 * </ol>
 * <p>The two output files are fixed-width: {@code STMT-FILE} carries {@code FD-STMTFILE-REC PIC X(80)}
 * (plain text, 80-byte records) and {@code HTML-FILE} carries {@code FD-HTMLFILE-REC PIC X(100)}
 * (HTML, 100-byte records) (CBSTM03A L44-L47; {@code CREASTMT} STEP040 {@code LRECL=80}/{@code LRECL=100}).</p>
 *
 * <h2>Target construct mapping</h2>
 * <p>That single sequential program is decomposed here into the canonical chunk-oriented
 * reader &rarr; processor &rarr; writer triad, one <em>statement unit per cross-reference row</em>:</p>
 * <ul>
 *   <li><strong>Reader &mdash; the cross-reference browse ({@code 1000-XREFFILE-GET-NEXT}).</strong>
 *       An inline {@link RepositoryItemReader} over {@link CardXrefRepository} streams the
 *       {@code card_xref} table (the relational form of {@code CARDXREF.VSAM.KSDS}) in ascending
 *       {@code xref_card_num} order, exactly preserving the {@code CBSTM03B} {@code XREF-FILE}
 *       {@code ACCESS MODE IS SEQUENTIAL} / {@code RECORD KEY IS FD-XREF-CARD-NUM} browse
 *       (CBSTM03B L37-L41). Each emitted {@link CardXref} is one card's statement request.</li>
 *   <li><strong>Processor &mdash; {@link StatementProcessor}.</strong> For each {@link CardXref} it
 *       resolves the {@code Customer} and {@code Account} and reads the card's transactions
 *       (ordered by {@code (cardNum, tranId)} ascending, the {@code CREASTMT} STEP010 sort key),
 *       then assembles a {@link StatementProcessor.StatementDocument} holding the formatted 80-byte
 *       text lines and 100-byte HTML lines &mdash; including the {@code Current Balance}
 *       {@code 9(9).99-} display formatting (trailing sign) and the accumulated total. All monetary
 *       arithmetic is performed there in {@link java.math.BigDecimal} at scale 2; this configuration
 *       class holds no monetary state.</li>
 *   <li><strong>Writer &mdash; {@link StatementItemWriter}.</strong> It emits each document's text
 *       lines as 80-byte records to the statement output and its HTML lines as 100-byte records to
 *       the HTML output, preserving the exact record widths via {@code common/util/FixedWidthCodec}. Output
 *       destinations are resolved from configuration (no hardcoded absolute paths).</li>
 * </ul>
 *
 * <h2>{@code CALL CBSTM03B} &rarr; injected {@code StatementFileService}</h2>
 * <p>The COBOL {@code CALL 'CBSTM03B'} (invoked 13&times;) &mdash; a generic dispatcher whose
 * {@code EVALUATE LK-M03B-DD} routed to sequential reads of {@code TRNXFILE}/{@code XREFFILE} and
 * keyed reads of {@code CUSTFILE}/{@code ACCTFILE} (CBSTM03B L28-L53) &mdash; is re-expressed as the
 * {@code batch/reader/StatementFileService} Spring bean, which the {@link StatementProcessor} injects
 * and calls. This configuration therefore contains <strong>no</strong> inline VSAM-style file-access
 * or dispatch logic: the file-access concern lives in that injected service, exactly as the
 * migration mandates (AAP &sect;0.5.7). The reader's use of {@link CardXrefRepository} is the ordinary
 * chunk-reader entry point for the cross-reference browse, not a re-implementation of the dispatcher.</p>
 *
 * <h2>Documented AAP deviation &mdash; intermediate VSAM lifecycle</h2>
 * <p>{@code CREASTMT.JCL} sorts the transaction master into an intermediate {@code TRXFL} VSAM
 * cluster (STEP010 {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}) via an IDCAMS
 * {@code DELETE}/{@code DEFINE}. That intermediate-cluster lifecycle is a mainframe file-management
 * artifact with no relational analog and is intentionally <strong>not</strong> replicated; the
 * equivalent {@code (cardNum, tranId)} ordering is instead produced by the set-based, sorted
 * repository query the processor drives. This is recorded in {@code docs/decision-log.md}; this class
 * only wires the components and neither owns nor edits that decision.</p>
 *
 * <h2>Spring Batch wiring and infrastructure contract</h2>
 * <ul>
 *   <li>Consistent with every other CardDemo batch configuration, this class does <strong>not</strong>
 *       declare {@code @EnableBatchProcessing} and does <strong>not</strong> redeclare any batch
 *       infrastructure bean: the {@link JobRepository} and the batch
 *       {@link PlatformTransactionManager} are supplied by Spring Boot's batch auto-configuration and
 *       injected as method parameters (see {@code com.aws.carddemo.config.BatchConfig} for the full
 *       rationale).</li>
 *   <li>The job never runs at startup because {@code application.yml} sets
 *       {@code spring.batch.job.enabled=false}; it is launched explicitly (by bean/name injection with
 *       a {@code JobLauncher}) or by name through the CI/CD workflow (AAP &sect;0.4.4).</li>
 *   <li>The {@link CorrelationIdJobListener} (same package) is registered on the job so every log line
 *       emitted during the run carries the observability correlation id (Technical Specification
 *       &sect;0.9.5).</li>
 *   <li>{@link StatementItemWriter} also implements
 *       {@link org.springframework.batch.core.StepExecutionListener}; setting it as the step's writer
 *       makes Spring Batch automatically register its {@code beforeStep}/{@code afterStep} callbacks
 *       (which open both output files &mdash; reproducing {@code OPEN OUTPUT STMT-FILE HTML-FILE} &mdash;
 *       and finalize/close them &mdash; reproducing {@code CLOSE STMT-FILE HTML-FILE} &mdash; mapping the
 *       outcome to batch return code 0 or 8). It is therefore intentionally <em>not</em> registered a
 *       second time as a step listener here (that would open and close the files twice).</li>
 * </ul>
 *
 * <p><strong>Bean-name note.</strong> The configuration bean is explicitly named
 * {@code statementGenerationJobConfig} so it does not collide with the {@code statementGenerationJob}
 * {@link Job} bean declared by {@link #statementGenerationJob(JobRepository, Step,
 * CorrelationIdJobListener)} (the default configuration-class bean name would otherwise decapitalize
 * to {@code statementGenerationJob} and clash with the {@link Job} bean).</p>
 *
 * @see StatementProcessor
 * @see StatementItemWriter
 * @see CorrelationIdJobListener
 * @see CardXref
 * @see CardXrefRepository
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Configuration("statementGenerationJobConfig")
public class StatementGenerationJob {

    /**
     * Chunk (commit-interval) size for the step and page size for the cross-reference reader.
     *
     * <p>The two are kept identical so each transactional chunk corresponds to exactly one reader
     * page, bounding memory while streaming the full cross-reference table. The value comfortably
     * exceeds the seeded {@code card_xref} row count, so a typical run completes in a single page and
     * a single commit, while remaining correct for an arbitrarily large table. It is aligned with the
     * sibling batch jobs ({@code XrefPrintJob}, {@code TransactionReportJob}) for consistency.</p>
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * JPA/entity property name of the cross-reference primary key ({@code XREF-CARD-NUM}, column
     * {@code xref_card_num}). Sorting the reader ascending on this property reproduces the VSAM
     * {@code RECORD KEY IS FD-XREF-CARD-NUM} sequential browse of {@code CBSTM03B} (L37-L41), which
     * {@code CBSTM03A 1000-XREFFILE-GET-NEXT} drives one record at a time.
     */
    private static final String XREF_CARD_NUM_PROPERTY = "xrefCardNum";

    /**
     * {@link org.springframework.data.repository.PagingAndSortingRepository} method invoked by the
     * reader. {@code findAll} is inherited by {@link CardXrefRepository} from {@code JpaRepository};
     * the {@link RepositoryItemReader} appends a {@code Pageable} carrying the ascending
     * {@link #XREF_CARD_NUM_PROPERTY} sort, so the {@code findAll(Pageable)} overload is invoked and
     * rows are returned in the exact primary-key order the legacy KSDS browse produced.
     */
    private static final String READER_METHOD_NAME = "findAll";

    /**
     * Name assigned to the reader; also its {@code ExecutionContext} state-key prefix. A stable,
     * unique name is required because the reader saves its paging state for restartability.
     */
    private static final String READER_NAME = "statementCrossReferenceItemReader";

    /**
     * Defines the {@code statementGenerationJob} batch job: a single-step job that iterates the card
     * cross-reference and produces the dual (text + HTML) account statements, reproducing
     * {@code CBSTM03A} + {@code CBSTM03B} (triggered on the mainframe by {@code CREASTMT.JCL}).
     *
     * <p>The bean name is exactly {@code statementGenerationJob} (from the method name), the
     * identifier used to launch the job. The {@code correlationIdJobListener} is attached so the
     * correlation id propagates across the batch boundary for the whole execution. A clean pass
     * completes with {@code COMPLETED} status (return code {@code 0}); a missing customer or account
     * (the {@code CBSTM03A} {@code 2000-CUSTFILE-GET} / {@code 3000-ACCTFILE-GET} abend paths, mapped
     * by {@link StatementProcessor} to a {@code FileStatusException}) or any statement-file I/O
     * failure propagates, fails the step, and yields a {@code FAILED} job with a non-zero return code
     * (the batch return-code-8 analog).</p>
     *
     * @param jobRepository            the auto-configured Spring Batch {@link JobRepository};
     *                                 never {@code null}
     * @param statementGenerationStep  the single {@link Step} of this job (see
     *                                 {@link #statementGenerationStep(JobRepository,
     *                                 PlatformTransactionManager, CardXrefRepository,
     *                                 StatementProcessor, StatementItemWriter)}); never {@code null}
     * @param correlationIdJobListener the cross-cutting listener that publishes the correlation id
     *                                 into the logging context for the run; never {@code null}
     * @return the configured {@link Job}; never {@code null}
     */
    @Bean
    public Job statementGenerationJob(JobRepository jobRepository,
                                      Step statementGenerationStep,
                                      CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder("statementGenerationJob", jobRepository)
                .listener(correlationIdJobListener)
                .start(statementGenerationStep)
                .build();
    }

    /**
     * Defines the single chunk-oriented {@link Step} that streams each {@link CardXref} in ascending
     * card-number order, transforms it into a {@link StatementProcessor.StatementDocument}, and feeds
     * the dual-output statement writer.
     *
     * <p>The step is {@code <CardXref, StatementProcessor.StatementDocument>}: the reader emits ordered
     * cross-reference rows ({@code 1000-XREFFILE-GET-NEXT}), the processor resolves the customer,
     * account, and transactions and assembles one statement document per card
     * ({@code 2000}/{@code 3000}/{@code 5000}/{@code 4000} paragraphs), and the writer emits the
     * fixed-width 80-byte text and 100-byte HTML records.</p>
     *
     * <p>The reader is constructed inline (see
     * {@link #statementCrossReferenceItemReader(CardXrefRepository)}); the framework registers it as an
     * {@code ItemStream} and manages its open/update/close lifecycle per execution. The injected
     * {@code statementProcessor} and {@code statementItemWriter} are singleton {@code @Component}
     * beans. Because {@link StatementItemWriter} additionally implements
     * {@link org.springframework.batch.core.StepExecutionListener}, setting it via {@code .writer(...)}
     * causes Spring Batch to auto-register its {@code beforeStep}/{@code afterStep} callbacks (which
     * open and finalize/close both output files); it is therefore intentionally not registered again
     * as a step listener here (that would open and close the files twice).</p>
     *
     * @param jobRepository       the auto-configured Spring Batch {@link JobRepository};
     *                            never {@code null}
     * @param transactionManager  the auto-configured batch {@link PlatformTransactionManager} that
     *                            governs each chunk's transaction; never {@code null}
     * @param cardXrefRepository  the Spring Data repository backing the cross-reference reader;
     *                            never {@code null}
     * @param statementProcessor  the processor that resolves customer/account/transactions and
     *                            assembles the dual-format statement document; never {@code null}
     * @param statementItemWriter the writer that emits the 80-byte text and 100-byte HTML statement
     *                            records (and owns the step's file open/close lifecycle);
     *                            never {@code null}
     * @return the configured {@link Step}; never {@code null}
     */
    @Bean
    public Step statementGenerationStep(JobRepository jobRepository,
                                        PlatformTransactionManager transactionManager,
                                        CardXrefRepository cardXrefRepository,
                                        StatementProcessor statementProcessor,
                                        StatementItemWriter statementItemWriter) {
        return new StepBuilder("statementGenerationStep", jobRepository)
                .<CardXref, StatementProcessor.StatementDocument>chunk(CHUNK_SIZE, transactionManager)
                .reader(statementCrossReferenceItemReader(cardXrefRepository))
                .processor(statementProcessor)
                .writer(statementItemWriter)
                .build();
    }

    /**
     * Builds the read-only {@link RepositoryItemReader} that streams the {@code card_xref} table in
     * ascending {@code xref_card_num} order, reproducing the sequential VSAM {@code RECORD KEY} browse
     * of {@code CBSTM03B} {@code XREF-FILE} that {@code CBSTM03A}'s {@code 1000-XREFFILE-GET-NEXT}
     * consumes one record at a time.
     *
     * <p>The reader is backed by {@link CardXrefRepository} and drives its inherited
     * {@code findAll(Pageable)} method: on each page the {@link RepositoryItemReader} constructs a
     * {@code Pageable} carrying the ascending {@link #XREF_CARD_NUM_PROPERTY} sort, so rows are
     * returned in the exact primary-key order the legacy KSDS browse produced. Paging keeps memory
     * bounded, and {@code saveState} is enabled so the reader's position is persisted for
     * restartability. This is the ordinary chunk-reader entry point for the cross-reference browse;
     * all subsequent per-card file access (customer, account, transactions) is delegated by the
     * {@link StatementProcessor} to the injected {@code StatementFileService} (the {@code CBSTM03B}
     * replacement), so this class contains no inline file-dispatch logic.</p>
     *
     * @param cardXrefRepository the repository to page over; never {@code null}
     * @return a fully configured, read-only {@link RepositoryItemReader} over {@link CardXref}
     */
    private RepositoryItemReader<CardXref> statementCrossReferenceItemReader(CardXrefRepository cardXrefRepository) {
        return new RepositoryItemReaderBuilder<CardXref>()
                .name(READER_NAME)
                .repository(cardXrefRepository)
                .methodName(READER_METHOD_NAME)
                .pageSize(CHUNK_SIZE)
                .sorts(Map.of(XREF_CARD_NUM_PROPERTY, Sort.Direction.ASC))
                .saveState(true)
                .build();
    }
}
