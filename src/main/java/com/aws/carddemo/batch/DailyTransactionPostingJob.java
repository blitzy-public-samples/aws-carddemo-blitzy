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

import com.aws.carddemo.batch.processor.DailyTransactionPostingProcessor;
import com.aws.carddemo.batch.processor.DailyTransactionPostingProcessor.PostingResult;
import com.aws.carddemo.batch.reader.DailyTransactionItemReader;
import com.aws.carddemo.batch.writer.DailyTransactionPostingWriter;
import com.aws.carddemo.domain.DailyTransaction;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration that re-platforms the legacy COBOL batch program
 * {@code CBTRN02C} ("Post the records from daily transaction file", source
 * {@code legacy/cbl/CBTRN02C.cbl}, formerly {@code app/cbl/CBTRN02C.cbl}), triggered by the JCL job
 * {@code POSTTRAN.jcl}, onto Spring Batch (AAP sections 0.4.4 and 0.5.4). It defines the
 * {@code dailyTransactionPostingJob} &mdash; the daily-transaction <strong>posting core</strong>.
 *
 * <p>This is the highest parity-risk job in the migration: it is Technical Specification
 * &sect;0.7.1 hotspot <strong>H4</strong> (batch posting reject-code semantics) and also touches
 * <strong>H3</strong> (monetary fidelity), <strong>H5</strong> (VSAM browse) and <strong>H6</strong>
 * (read-update-rewrite concurrency). Reject-path parity for reason codes 100/101/102/103 is a hard
 * acceptance criterion (AAP &sect;0.9.2, &sect;0.9.6).</p>
 *
 * <h2>Legacy behavior reproduced (CBTRN02C)</h2>
 * The mainframe program opens the sequential {@code DALYTRAN} file for input and the indexed
 * {@code XREF}, {@code ACCOUNT}, {@code TCATBALF} and {@code TRANSACT} files, then walks
 * {@code DALYTRAN} front-to-back until end-of-file (MAIN loop, L202-L219). For each record it
 * resets the fail reason to zero, validates the record ({@code 1500-VALIDATE-TRAN}, L370-L422), and
 * then either posts it ({@code 2000-POST-TRANSACTION}, L424-L444) when the reason is still zero, or
 * increments the reject count and writes an 80-byte-trailered reject record
 * ({@code 2500-WRITE-REJECT-REC}, L446-L465) otherwise. At end-of-file, {@code IF WS-REJECT-COUNT >
 * 0 MOVE 4 TO RETURN-CODE} (L229-L231).
 *
 * <h2>Frozen validation-order contract (do not reorder)</h2>
 * The reject-code order and short-circuit semantics are a frozen behavioral contract; a reordering
 * silently changes which code a record receives and fails the golden-file tests:
 * <ol>
 *   <li><strong>100</strong> {@code INVALID CARD NUMBER FOUND} &mdash; card cross-reference missing
 *       ({@code 1500-A-LOOKUP-XREF}, L385). This <em>short-circuits</em>: the account lookup is not
 *       performed ({@code 1500-VALIDATE-TRAN} only performs {@code 1500-B} while the reason is still
 *       zero, L372-L376).</li>
 *   <li><strong>101</strong> {@code ACCOUNT RECORD NOT FOUND} &mdash; account missing
 *       ({@code 1500-B-LOOKUP-ACCT}, L397); blocks the over-limit and expiration checks.</li>
 *   <li><strong>102</strong> {@code OVERLIMIT TRANSACTION} &mdash; assigned when
 *       {@code ACCT-CREDIT-LIMIT < (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)}
 *       (L403-L413), and <strong>103</strong> {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}
 *       &mdash; assigned when {@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)} (L414-L420). These
 *       are <strong>two independent {@code IF} statements, not an {@code else-if}</strong>: when both
 *       conditions hold, 103 is assigned last and therefore wins (<em>last-writer-wins</em>).</li>
 * </ol>
 * <p>That validation and candidate-construction logic lives in the injected
 * {@link DailyTransactionPostingProcessor}; the mutating paragraphs ({@code 2700-UPDATE-TCATBAL},
 * {@code 2800-UPDATE-ACCOUNT-REC}, {@code 2900-WRITE-TRANSACTION-FILE}) and the reject-file write
 * live in the injected {@link DailyTransactionPostingWriter}. This job class only wires them into a
 * single-step Spring Batch {@link Job}; it contains no business logic of its own.</p>
 *
 * <h2>Sequential (record-at-a-time) semantics &mdash; chunk size 1 (AAP H4/H6)</h2>
 * COBOL posts one record at a time: the over-limit check reads {@code ACCT-CURR-CYC-CREDIT} /
 * {@code ACCT-CURR-CYC-DEBIT}, and {@code 2800-UPDATE-ACCOUNT-REC} mutates those same fields, so a
 * later transaction for the same account must observe the earlier mutation. Spring Batch defers a
 * chunk's writes to the end of the chunk, so any chunk size greater than one would validate later
 * records against <em>stale</em> account state and diverge from COBOL. The step therefore uses a
 * {@linkplain #CHUNK_SIZE chunk size of exactly one}: each record's account mutation is committed
 * within its own chunk transaction before the next record is read and validated, giving guaranteed
 * balance parity plus per-record restartability. The processor and writer are written to assume this
 * chunk size. The {@code @Version} columns on {@code Account} and {@code TransactionCategoryBalance}
 * add optimistic-locking integrity to the read-update-rewrite cycle &mdash; a documented improvement
 * over the COBOL VSAM {@code REWRITE} (AAP H6, recorded in {@code docs/decision-log.md}).
 *
 * <h2>Return-code ownership (MAIN L229-L231)</h2>
 * The COBOL return code is owned by this batch flow, not by the web {@code GlobalExceptionHandler}:
 * a clean run is RC {@code 0}, at least one reject is RC {@code 4}
 * ({@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}), and an I/O error (COBOL status
 * {@code 109} on the account rewrite, a duplicate transaction, or a reject-file failure) is RC
 * {@code 8} (the {@code 9999-ABEND-PROGRAM} path). The return code is surfaced through the step
 * {@link org.springframework.batch.core.ExitStatus ExitStatus}: the writer &mdash; which is also the
 * step's {@link org.springframework.batch.core.StepExecutionListener} &mdash; maps the run outcome
 * in its {@code afterStep} to {@link org.springframework.batch.core.ExitStatus#COMPLETED} (RC 0),
 * {@code new ExitStatus("COMPLETED_WITH_REJECTS")} (RC 4), or
 * {@link org.springframework.batch.core.ExitStatus#FAILED} (RC 8). Because this is a single-step job,
 * that step exit status becomes the job exit status, which the launching CI/CD workflow (the modern
 * equivalent of the JCL scheduler) maps to the process return code 0/4/8. The
 * {@code "COMPLETED_WITH_REJECTS"} literal is a coordination contract with the writer and must not be
 * changed on one side alone.
 *
 * <h2>Listener registration (no double-registration)</h2>
 * The step's writer, {@link DailyTransactionPostingWriter}, implements
 * {@link org.springframework.batch.core.StepExecutionListener}; Spring Batch automatically registers
 * a step's reader/processor/writer that implement listener interfaces, so the writer's
 * {@code beforeStep} (open reject file, reset counters) and {@code afterStep} (close file, map the
 * return code) fire automatically. It is therefore <strong>deliberately not</strong> passed to
 * {@code StepBuilder.listener(...)} a second time here &mdash; double-registration would open and
 * close the reject file twice and double-run the return-code mapping. The
 * {@link CorrelationIdJobListener} (same package) is registered on the {@link JobBuilder} so every
 * batch log line carries a correlation id across the job/step boundary (Observability rule,
 * AAP &sect;0.9.5); because the step runs single-threaded, job-level registration is sufficient for
 * the id to be present on every log line.
 *
 * <h2>Infrastructure contract</h2>
 * <ul>
 *   <li>This class carries only {@link Configuration @Configuration} and deliberately does
 *       <strong>not</strong> declare {@code @EnableBatchProcessing}: declaring it anywhere makes
 *       Spring Boot's batch auto-configuration back off. The {@link JobRepository} and batch
 *       {@link PlatformTransactionManager} injected into the bean methods below are the
 *       auto-configured infrastructure beans and are never redeclared here (that responsibility, and
 *       its rationale, lives in {@code com.aws.carddemo.config.BatchConfig}).</li>
 *   <li>The configuration bean is explicitly named {@code "dailyTransactionPostingJobConfig"} so the
 *       default component name (the decapitalized class name {@code dailyTransactionPostingJob}) does
 *       not collide with the {@link Job} bean of the same name declared by
 *       {@link #dailyTransactionPostingJob(JobRepository, Step, CorrelationIdJobListener)}.</li>
 *   <li>The job never runs at application startup because {@code spring.batch.job.enabled=false} in
 *       {@code application.yml}; it is launched explicitly (via {@code JobLauncher}/{@code JobOperator}
 *       or the CI/CD workflow).</li>
 * </ul>
 *
 * @see DailyTransactionItemReader
 * @see DailyTransactionPostingProcessor
 * @see DailyTransactionPostingWriter
 * @see CorrelationIdJobListener
 * @see DailyTransaction
 */
@Configuration("dailyTransactionPostingJobConfig")
public class DailyTransactionPostingJob {

    /**
     * Job bean name. Exactly {@code "dailyTransactionPostingJob"} so it can be launched by name
     * through {@code JobOperator}/{@code JobRegistry} (the JCL-scheduling equivalent) and matches the
     * AAP-specified public API.
     */
    private static final String JOB_NAME = "dailyTransactionPostingJob";

    /** Step bean name for the single chunk-oriented validate&rarr;post/reject step. */
    private static final String STEP_NAME = "dailyTransactionPostingStep";

    /**
     * Chunk (commit-interval) size for the posting step. It is fixed at <strong>one</strong> so each
     * daily transaction is validated, posted (or rejected), and committed as its own unit before the
     * next record is read. This reproduces the COBOL record-at-a-time posting semantics exactly: the
     * over-limit validation of a later transaction observes the account-balance mutation made by an
     * earlier transaction for the same account. A larger chunk would defer those mutations and
     * validate later records against stale balances, diverging from CBTRN02C (AAP H4/H6). This value
     * MUST NOT be increased.
     */
    private static final int CHUNK_SIZE = 1;

    /**
     * Defines the {@code dailyTransactionPostingJob} batch job: a single-step job that posts the
     * daily-transaction staging table, reproducing CBTRN02C.
     *
     * <p>The {@link CorrelationIdJobListener} is registered so the job (and its single-threaded step)
     * log lines carry a correlation id end-to-end. The job's exit status is inherited from its one
     * step, whose writer maps the run outcome to the {@code COMPLETED} / {@code COMPLETED_WITH_REJECTS}
     * / {@code FAILED} exit status that carries the COBOL return code 0/4/8.</p>
     *
     * @param jobRepository                the auto-configured Spring Batch {@link JobRepository};
     *                                     never {@code null}
     * @param dailyTransactionPostingStep  the single validate&rarr;post/reject {@link Step} defined by
     *                                     {@link #dailyTransactionPostingStep(JobRepository, PlatformTransactionManager, DailyTransactionItemReader, DailyTransactionPostingProcessor, DailyTransactionPostingWriter)};
     *                                     never {@code null}
     * @param correlationIdJobListener     the cross-cutting correlation-id listener (same package);
     *                                     never {@code null}
     * @return the fully built daily-transaction posting {@link Job}
     */
    @Bean
    public Job dailyTransactionPostingJob(JobRepository jobRepository,
                                          Step dailyTransactionPostingStep,
                                          CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(correlationIdJobListener)
                .start(dailyTransactionPostingStep)
                .build();
    }

    /**
     * Defines the chunk-oriented validate&rarr;post/reject step. Each chunk holds exactly one
     * {@link DailyTransaction}: it is read in ascending staging-key order (the COBOL {@code DALYTRAN}
     * sequential-read analog), validated and turned into a
     * {@link DailyTransactionPostingProcessor.PostingResult} by the processor, and then either posted
     * (transaction-category-balance upsert, account balance/cycle rewrite, transaction insert) or
     * written to the 430-byte reject file by the writer.
     *
     * <p>The reader, processor and writer are the shared, singleton {@code @Component} beans from the
     * {@code batch/reader}, {@code batch/processor} and {@code batch/writer} packages; they are
     * injected here (rather than constructed inline) because the reader is also consumed by
     * {@code DailyTransactionValidateJob} and the processor/writer encapsulate the CBTRN02C validation
     * and persistence logic respectively. Spring Batch auto-registers the writer as this step's
     * {@link org.springframework.batch.core.StepExecutionListener} (it implements that interface), so
     * its {@code beforeStep}/{@code afterStep} callbacks &mdash; which open/close the reject file and
     * map the return code &mdash; run without being registered a second time here. Each chunk is
     * bounded by the auto-configured batch {@link PlatformTransactionManager}, so with a chunk size of
     * one every record's JPA writes are atomic per record.</p>
     *
     * @param jobRepository                     the auto-configured Spring Batch {@link JobRepository};
     *                                          never {@code null}
     * @param transactionManager                the auto-configured batch
     *                                          {@link PlatformTransactionManager} that bounds each
     *                                          per-record chunk; never {@code null}
     * @param dailyTransactionItemReader        the shared reader over the {@code daily_transaction}
     *                                          staging table (COBOL {@code DALYTRAN} sequential read);
     *                                          never {@code null}
     * @param dailyTransactionPostingProcessor  the read-only validation / candidate-construction
     *                                          processor (COBOL {@code 1500-VALIDATE-TRAN} and the
     *                                          field copy of {@code 2000-POST-TRANSACTION}); never
     *                                          {@code null}
     * @param dailyTransactionPostingWriter     the persistence / reject-file writer and step
     *                                          return-code listener (COBOL {@code 2700}/{@code 2800}/
     *                                          {@code 2900} and {@code 2500-WRITE-REJECT-REC}); never
     *                                          {@code null}
     * @return the configured single-chunk-size posting {@link Step}
     */
    @Bean
    public Step dailyTransactionPostingStep(JobRepository jobRepository,
                                            PlatformTransactionManager transactionManager,
                                            DailyTransactionItemReader dailyTransactionItemReader,
                                            DailyTransactionPostingProcessor dailyTransactionPostingProcessor,
                                            DailyTransactionPostingWriter dailyTransactionPostingWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, PostingResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(dailyTransactionItemReader)
                .processor(dailyTransactionPostingProcessor)
                .writer(dailyTransactionPostingWriter)
                .build();
    }
}
