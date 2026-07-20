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

import com.aws.carddemo.batch.processor.DailyTransactionValidateProcessor;
import com.aws.carddemo.batch.reader.DailyTransactionItemReader;
import com.aws.carddemo.domain.DailyTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration that re-platforms the legacy COBOL batch program
 * {@code CBTRN01C} ("Read the records from daily transaction file", source
 * {@code legacy/cbl/CBTRN01C.cbl}, formerly {@code app/cbl/CBTRN01C.cbl}) onto Spring
 * Batch (AAP sections 0.4.4 and 0.5.4). It defines the {@code dailyTransactionValidateJob}
 * &mdash; the lightweight, <strong>read-only</strong> verification pass over the incoming
 * daily-transaction batch.
 *
 * <h2>Legacy behavior reproduced (CBTRN01C)</h2>
 * The mainframe program opens the sequential {@code DALYTRAN} file for input (plus the
 * {@code CUSTOMER}, {@code XREF}, {@code CARD}, {@code ACCOUNT} and {@code TRANSACT} files
 * for verification context), then walks {@code DALYTRAN} front-to-back until end-of-file
 * ({@code MAIN-PARA} loop, CBTRN01C L164-186). For every record it:
 * <ol>
 *   <li>looks up the card cross-reference by card number
 *       ({@code 2000-LOOKUP-XREF}, L227-239); on {@code INVALID KEY} it {@code DISPLAY}s that
 *       the card could not be verified and simply skips the record; and</li>
 *   <li>only when the cross-reference read succeeded ({@code IF WS-XREF-READ-STATUS = 0}),
 *       reads the owning account by the cross-referenced account id
 *       ({@code 3000-READ-ACCOUNT}, L241-250); a missing account is {@code DISPLAY}ed as
 *       {@code 'ACCOUNT ... NOT FOUND'}.</li>
 * </ol>
 * The program performs <strong>no writes, no balance updates and produces no reject file</strong>:
 * its sole purpose is to report referential-integrity anomalies of the incoming batch. A clean
 * pass is COBOL return code {@code 0}, and &mdash; crucially &mdash; a missing cross-reference or
 * account is a normal, non-fatal {@code INVALID KEY} that is merely reported, so the program still
 * ends with return code {@code 0} even in the presence of such anomalies. The legacy
 * {@code Z-ABEND-PROGRAM} path (return code {@code 8} via {@code CEE3ABD}) exists only for genuine
 * file-system I/O failures (open/read/close status other than {@code '00'}/{@code '10'}), which in
 * the Spring Batch model are surfaced by the reader/framework, never by the validation logic.
 *
 * <p>This job is deliberately the simplest of the daily-transaction jobs. It must not be confused
 * with {@code DailyTransactionPostingJob} (CBTRN02C), which <em>does</em> post balances and write
 * rejects with reason codes 100/101/102/103; no posting or reject semantics belong here.</p>
 *
 * <h2>Target design</h2>
 * <ul>
 *   <li><strong>Set-based sequential read.</strong> The COBOL sequential read of {@code DALYTRAN}
 *       becomes a chunk-oriented step driven by the shared {@link DailyTransactionItemReader}
 *       (bean {@code dailyTransactionItemReader}), which pages the {@code daily_transaction} staging
 *       table ordered by its surrogate identity key ascending &mdash; the faithful analog of the
 *       physical/sequential file order.</li>
 *   <li><strong>Per-record verification.</strong> The {@code 2000-LOOKUP-XREF} /
 *       {@code 3000-READ-ACCOUNT} logic is reproduced by the shared
 *       {@link DailyTransactionValidateProcessor} (bean {@code dailyTransactionValidateProcessor}),
 *       which performs the read-only cross-reference and account lookups, logs any anomaly
 *       (mirroring the COBOL {@code DISPLAY}), and returns each record unchanged without ever
 *       throwing &mdash; so a missing cross-reference or account never aborts the job.</li>
 *   <li><strong>No persistence.</strong> The step's writer is an intentional no-op /
 *       logging {@link ItemWriter} (see {@link #dailyTransactionValidateWriter()}). Nothing is
 *       saved, updated or rejected, preserving the read-only nature of CBTRN01C. A clean pass
 *       completes with {@code COMPLETED} {@code BatchStatus} (the analog of return code {@code 0}),
 *       including when the processor has logged anomalies.</li>
 * </ul>
 *
 * <h2>Infrastructure contract</h2>
 * <ul>
 *   <li>This class carries only {@link Configuration @Configuration} and deliberately does
 *       <strong>not</strong> declare {@code @EnableBatchProcessing}: declaring it anywhere would
 *       make Spring Boot's batch auto-configuration back off. The {@link JobRepository} and batch
 *       {@link PlatformTransactionManager} injected into the bean methods below are the
 *       auto-configured infrastructure beans and are never redeclared here (that responsibility,
 *       and its rationale, lives in {@code com.aws.carddemo.config.BatchConfig}).</li>
 *   <li>The configuration bean is explicitly named {@code "dailyTransactionValidateJobConfig"} so
 *       that the default component name (the decapitalized class name
 *       {@code dailyTransactionValidateJob}) does not collide with the {@code Job} bean of the same
 *       name declared by {@link #dailyTransactionValidateJob(JobRepository, Step, CorrelationIdJobListener)}.</li>
 *   <li>The job never runs at application startup because {@code spring.batch.job.enabled=false} in
 *       {@code application.yml}; it is launched explicitly (via {@code JobLauncher}/{@code JobOperator}
 *       or the CI/CD workflow, the modern equivalent of the JCL scheduler per AAP 0.4.4).</li>
 *   <li>The {@link CorrelationIdJobListener} (same package) is registered on the job so every batch
 *       log line carries a correlation id across the job/step boundary (Observability rule,
 *       AAP 0.9.5).</li>
 * </ul>
 *
 * @see DailyTransactionItemReader
 * @see DailyTransactionValidateProcessor
 * @see CorrelationIdJobListener
 * @see DailyTransaction
 */
@Configuration("dailyTransactionValidateJobConfig")
public class DailyTransactionValidateJob {

    /**
     * SLF4J logger used by the no-op / logging writer to emit a quiet per-chunk trace. It carries
     * the batch correlation id via the {@code MDC} populated by {@link CorrelationIdJobListener}.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(DailyTransactionValidateJob.class);

    /**
     * Job bean name. Exactly {@code "dailyTransactionValidateJob"} so it can be launched by name
     * through {@code JobOperator}/{@code JobRegistry} (the JCL-scheduling equivalent) and matches
     * the AAP-specified public API.
     */
    private static final String JOB_NAME = "dailyTransactionValidateJob";

    /** Step bean name for the single chunk-oriented read&rarr;verify step. */
    private static final String STEP_NAME = "dailyTransactionValidateStep";

    /**
     * Chunk (commit-interval) size for the verification step. The step performs no writes, so this
     * governs only how often Spring Batch checkpoints its metadata while streaming; a page of
     * {@code 100} records per commit keeps memory bounded for an arbitrarily large daily-transaction
     * input and is kept equal to the reader's page size so one page fills one chunk.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Defines the {@code dailyTransactionValidateJob} batch job: a single-step job that reads the
     * daily-transaction staging table in sequential order and verifies each record's card
     * cross-reference and account, reproducing CBTRN01C.
     *
     * <p>The {@link CorrelationIdJobListener} is registered so the job (and its step) log lines
     * carry a correlation id end-to-end. Because the step is single-threaded, registering the
     * listener on the job is sufficient for the correlation id to be present on every batch log
     * line.</p>
     *
     * @param jobRepository                 the auto-configured Spring Batch {@link JobRepository};
     *                                      never {@code null}
     * @param dailyTransactionValidateStep  the single read&rarr;verify {@link Step} defined by
     *                                      {@link #dailyTransactionValidateStep(JobRepository, PlatformTransactionManager, DailyTransactionItemReader, DailyTransactionValidateProcessor)};
     *                                      never {@code null}
     * @param correlationIdJobListener      the cross-cutting correlation-id listener (same package);
     *                                      never {@code null}
     * @return the fully built, read-only daily-transaction validate {@link Job}
     */
    @Bean
    public Job dailyTransactionValidateJob(JobRepository jobRepository,
                                           Step dailyTransactionValidateStep,
                                           CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(correlationIdJobListener)
                .start(dailyTransactionValidateStep)
                .build();
    }

    /**
     * Defines the chunk-oriented read&rarr;verify step. Each chunk of {@link #CHUNK_SIZE}
     * {@link DailyTransaction} records is read in ascending staging-key order, verified against the
     * card cross-reference and account, and passed through unchanged; nothing is written back,
     * preserving the read-only nature of CBTRN01C.
     *
     * <p>The reader and processor are the shared, singleton {@code @Component} beans from the
     * {@code batch/reader} and {@code batch/processor} packages; they are injected here (rather than
     * constructed inline) because the reader is also consumed by {@code DailyTransactionPostingJob}
     * and the processor encapsulates the CBTRN01C verification logic. The writer is a local no-op /
     * logging writer because this job persists nothing.</p>
     *
     * @param jobRepository                       the auto-configured Spring Batch
     *                                            {@link JobRepository}; never {@code null}
     * @param transactionManager                  the auto-configured batch
     *                                            {@link PlatformTransactionManager} that bounds each
     *                                            chunk; never {@code null}
     * @param dailyTransactionItemReader          the shared reader over the {@code daily_transaction}
     *                                            staging table (COBOL {@code DALYTRAN} sequential
     *                                            read); never {@code null}
     * @param dailyTransactionValidateProcessor   the shared read-only verification processor
     *                                            (COBOL {@code 2000-LOOKUP-XREF} /
     *                                            {@code 3000-READ-ACCOUNT}); never {@code null}
     * @return the configured, read-only {@link Step}
     */
    @Bean
    public Step dailyTransactionValidateStep(JobRepository jobRepository,
                                             PlatformTransactionManager transactionManager,
                                             DailyTransactionItemReader dailyTransactionItemReader,
                                             DailyTransactionValidateProcessor dailyTransactionValidateProcessor) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, DailyTransaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(dailyTransactionItemReader)
                .processor(dailyTransactionValidateProcessor)
                .writer(dailyTransactionValidateWriter())
                .build();
    }

    /**
     * Builds the inline, no-op / logging {@link ItemWriter} for the verification step.
     *
     * <p>CBTRN01C writes nothing, so this writer persists nothing: it invokes no repository
     * {@code save}/{@code update} and never mutates a record. It only emits a quiet {@code DEBUG}
     * trace of how many records were verified in the chunk, so normal runs stay silent while the
     * pass remains observable. Keeping a typed {@code ItemWriter<DailyTransaction>} (rather than an
     * untyped lambda) preserves the step's read&rarr;verify generic contract and a warning-free
     * build.</p>
     *
     * @return an {@link ItemWriter} that logs the per-chunk verified count and persists nothing
     */
    private ItemWriter<DailyTransaction> dailyTransactionValidateWriter() {
        return chunk -> LOGGER.debug(
                "Verified {} daily-transaction record(s) in this chunk "
                        + "(read-only verification pass; nothing persisted)",
                chunk.size());
    }
}
