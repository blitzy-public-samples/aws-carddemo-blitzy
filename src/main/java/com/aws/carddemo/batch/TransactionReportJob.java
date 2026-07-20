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

import com.aws.carddemo.batch.processor.TransactionReportProcessor;
import com.aws.carddemo.batch.reader.TransactionReportItemReader;
import com.aws.carddemo.batch.writer.TransactionReportWriter;
import com.aws.carddemo.domain.Transaction;

/**
 * Spring Batch {@link Configuration} that re-platforms the legacy transaction-detail report program
 * {@code legacy/cbl/CBTRN03C.cbl} (source {@code app/cbl/CBTRN03C.cbl}), orchestrated on the
 * mainframe by {@code legacy/proc/TRANREPT.prc} / {@code legacy/jcl/TRANREPT.jcl}. The job produces a
 * paginated "Daily Transaction Report" for a processing-date range with per-page subtotals,
 * per-account (per-card control break) subtotals, and a grand total &mdash; the batch counterpart of
 * the online {@code CR00} report screen ({@code CORPT00C}) driven by {@code service/ReportService}
 * (AAP &sect;0.4.4, &sect;0.5.4).
 *
 * <h2>&#9888; CRITICAL bean-name contract (cross-folder coordination)</h2>
 * <p>{@code service/ReportService} launches this job through a {@code JobLauncher}, injecting the
 * report {@link Job} by qualifier {@code @Qualifier("transactionReportJob")}. The {@link Job}
 * {@code @Bean} declared here is therefore named <strong>exactly {@code transactionReportJob}</strong>
 * (the {@link #transactionReportJob(JobRepository, Step, CorrelationIdJobListener)} method name is the
 * bean name, and the {@link JobBuilder} name matches). Any rename breaks {@code ReportService}
 * autowiring and reintroduces the {@code NoSuchBeanDefinitionException} it is designed to prevent.</p>
 *
 * <h2>Legacy behavior reproduced (CBTRN03C)</h2>
 * <p>The COBOL program reads {@code TRANSACT-FILE} sequentially, filters each record to the inclusive
 * processing-date window ({@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND <= WS-END-DATE},
 * CBTRN03C L173-174), control-breaks on a change of card number ({@code WS-CURR-CARD-NUM NOT=
 * TRAN-CARD-NUM}, L181), resolves the account via the card cross-reference and the
 * type/category descriptions ({@code 1500-A/B/C-LOOKUP-*}), and writes headers, detail lines, page
 * totals every {@code WS-PAGE-SIZE} lines, per-account totals on each card change, and a grand total
 * at end-of-file ({@code 1100}/{@code 1110}/{@code 1120} paragraphs). That single sequential program
 * is decomposed here into the canonical chunk-oriented reader &rarr; processor &rarr; writer triad:</p>
 * <ul>
 *   <li><strong>Reader</strong> &mdash; {@link TransactionReportItemReader} (bean
 *       {@code transactionReportItemReader}, a {@code @StepScope}
 *       {@link org.springframework.batch.item.database.JpaPagingItemReader JpaPagingItemReader} of
 *       {@link Transaction}) streams the rows filtered by {@code SUBSTRING(procTs,1,10) BETWEEN
 *       :startDate AND :endDate} and ordered by {@code (cardNum, tranId)} so the downstream card
 *       control break is well-formed. The {@code startDate}/{@code endDate} bounds (the {@code
 *       DATEPARM} analog, {@code YYYY-MM-DD}) are late-bound from the <em>job parameters</em> that
 *       {@code ReportService} supplies from the CR00 screen.</li>
 *   <li><strong>Processor</strong> &mdash; {@link TransactionReportProcessor} (bean
 *       {@code transactionReportProcessor}) performs the per-record cross-reference / type / category
 *       lookups and emits one {@link TransactionReportProcessor.ReportLine} per in-range
 *       transaction.</li>
 *   <li><strong>Writer</strong> &mdash; {@link TransactionReportWriter} (bean
 *       {@code transactionReportWriter}) owns the report formatting, pagination, control-break, and
 *       totals state machine, emitting the fixed-width 133-byte {@code REPORT-FILE} records.</li>
 * </ul>
 * <p>All monetary aggregation is performed in {@link java.math.BigDecimal} by the writer; this
 * configuration class holds no monetary state.</p>
 *
 * <h2>Documented AAP deviation &mdash; read order</h2>
 * <p>The legacy KSDS is read in physical {@code TRAN-ID} order and the report control-breaks on card
 * as cards happen to appear; the set-based reader instead orders {@code (cardNum, tranId)} so the
 * break is deterministic. This changes read <em>order</em> only, never report <em>content</em>, and
 * is recorded in {@code docs/decision-log.md} as decision <strong>D40</strong>. This class only wires
 * the components; it neither owns nor edits that decision.</p>
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
 *       {@code spring.batch.job.enabled=false}; it is launched explicitly by {@code ReportService}
 *       (via {@link Job}-bean injection with a {@code JobLauncher}), or by name through the CI/CD
 *       workflow {@code .github/workflows/ci.yml} &mdash; the modern equivalent of the mainframe JCL
 *       scheduler (AAP &sect;0.4.4).</li>
 *   <li>The {@link CorrelationIdJobListener} (same package) is registered on the job so every log
 *       line emitted during the run carries the observability correlation id (Technical
 *       Specification &sect;0.9.5).</li>
 *   <li>{@link TransactionReportWriter} also implements
 *       {@link org.springframework.batch.core.StepExecutionListener}; when it is set as the step's
 *       writer, Spring Batch automatically registers its {@code beforeStep}/{@code afterStep}
 *       callbacks (which read the {@code startDate}/{@code endDate} job parameters, open the report
 *       file, and finalize/close it). It is therefore intentionally <em>not</em> registered a second
 *       time as a step listener here (that would open and close the file twice).</li>
 *   <li>No {@code JobParametersIncrementer} is declared: {@code ReportService} adds a unique
 *       {@code requestedAt} timestamp parameter to every launch, so each run already has a distinct
 *       {@link org.springframework.batch.core.JobInstance} identity.</li>
 * </ul>
 *
 * <p><strong>Bean-name note.</strong> The configuration bean is explicitly named
 * {@code transactionReportJobConfig} so it does not collide with the {@code transactionReportJob}
 * {@link Job} bean declared by {@link #transactionReportJob(JobRepository, Step,
 * CorrelationIdJobListener)} (the default configuration-class bean name would otherwise decapitalize
 * to {@code transactionReportJob} and clash with the {@link Job} bean).</p>
 *
 * @see TransactionReportItemReader
 * @see TransactionReportProcessor
 * @see TransactionReportWriter
 * @see CorrelationIdJobListener
 * @see Transaction
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Configuration("transactionReportJobConfig")
public class TransactionReportJob {

    /**
     * Chunk (commit-interval) size for the step. It matches the reader's JPA paging page size
     * ({@code 100}) so each transactional chunk corresponds to exactly one reader page, bounding
     * memory while streaming an arbitrarily large filtered result set. The value is aligned with the
     * sibling batch jobs for consistency.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Defines the {@code transactionReportJob} batch job: a single-step job that streams the
     * date-filtered transaction rows, resolves reference data, and writes the paginated transaction
     * detail report, reproducing {@code CBTRN03C} (triggered on the mainframe by {@code TRANREPT}).
     *
     * <p>The bean name is exactly {@code transactionReportJob} (from the method name), which is the
     * identifier {@code service/ReportService} resolves via {@code @Qualifier("transactionReportJob")}
     * to launch the report. The {@code correlationIdJobListener} is attached so the correlation id
     * propagates across the batch boundary for the whole execution. A clean pass completes with
     * {@code COMPLETED} status (return code {@code 0}); a missing cross-reference / type / category
     * (the {@code CBTRN03C} {@code INVALID KEY} &rarr; {@code 9999-ABEND-PROGRAM} paths) or any report
     * I/O failure propagates, fails the step, and yields a {@code FAILED} job with a non-zero return
     * code (the batch return-code-8 analog).</p>
     *
     * @param jobRepository            the auto-configured Spring Batch {@link JobRepository};
     *                                 never {@code null}
     * @param transactionReportStep    the single {@link Step} of this job (see
     *                                 {@link #transactionReportStep(JobRepository,
     *                                 PlatformTransactionManager, TransactionReportItemReader,
     *                                 TransactionReportProcessor, TransactionReportWriter)});
     *                                 never {@code null}
     * @param correlationIdJobListener the cross-cutting listener that publishes the correlation id
     *                                 into the logging context for the run; never {@code null}
     * @return the configured {@link Job}; never {@code null}
     */
    @Bean
    public Job transactionReportJob(JobRepository jobRepository,
                                    Step transactionReportStep,
                                    CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder("transactionReportJob", jobRepository)
                .listener(correlationIdJobListener)
                .start(transactionReportStep)
                .build();
    }

    /**
     * Defines the single chunk-oriented {@link Step} that streams each in-range {@link Transaction},
     * transforms it into a {@link TransactionReportProcessor.ReportLine}, and feeds the aggregating
     * report writer.
     *
     * <p>The step is {@code <Transaction, TransactionReportProcessor.ReportLine>}: the reader emits
     * ordered, date-filtered {@link Transaction} rows, the processor resolves reference data and
     * emits one report line per row, and the writer formats/paginates/totals them into the
     * fixed-width report file.</p>
     *
     * <p>The injected {@code transactionReportItemReader} is a {@code @StepScope} component; Spring
     * supplies a scoped proxy here and instantiates the real reader per step execution so its
     * {@code #{jobParameters['startDate']}} / {@code #{jobParameters['endDate']}} late bindings
     * resolve within the running step. The injected {@code transactionReportWriter} is a singleton
     * {@code @Component} that also implements
     * {@link org.springframework.batch.core.StepExecutionListener}: setting it via {@code .writer(...)}
     * causes Spring Batch to auto-register its {@code beforeStep}/{@code afterStep} callbacks (which
     * open the report file from the date job parameters and finalize/close it). It is therefore
     * intentionally not registered again as a step listener (that would open and close the file
     * twice).</p>
     *
     * @param jobRepository                the auto-configured Spring Batch {@link JobRepository};
     *                                     never {@code null}
     * @param transactionManager           the auto-configured batch {@link PlatformTransactionManager}
     *                                     that governs each chunk's transaction; never {@code null}
     * @param transactionReportItemReader  the {@code @StepScope} paging reader that streams the
     *                                     date-filtered transactions ordered by card; never {@code null}
     * @param transactionReportProcessor   the processor that resolves reference data and emits report
     *                                     lines; never {@code null}
     * @param transactionReportWriter      the aggregating report writer (also the step's open/close
     *                                     lifecycle listener); never {@code null}
     * @return the configured {@link Step}; never {@code null}
     */
    @Bean
    public Step transactionReportStep(JobRepository jobRepository,
                                      PlatformTransactionManager transactionManager,
                                      TransactionReportItemReader transactionReportItemReader,
                                      TransactionReportProcessor transactionReportProcessor,
                                      TransactionReportWriter transactionReportWriter) {
        return new StepBuilder("transactionReportStep", jobRepository)
                .<Transaction, TransactionReportProcessor.ReportLine>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionReportItemReader)
                .processor(transactionReportProcessor)
                .writer(transactionReportWriter)
                .build();
    }
}
