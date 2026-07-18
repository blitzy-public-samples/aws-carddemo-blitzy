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

import com.aws.carddemo.batch.processor.InterestCalculationProcessor;
import com.aws.carddemo.batch.reader.TransactionCategoryBalanceItemReader;
import com.aws.carddemo.batch.writer.InterestTransactionWriter;
import com.aws.carddemo.domain.TransactionCategoryBalance;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch job configuration that re-platforms the legacy COBOL batch program
 * {@code CBACT04C} &mdash; the CardDemo <em>interest calculator</em> &mdash; onto Spring Batch
 * (source {@code legacy/cbl/CBACT04C.cbl}, formerly {@code app/cbl/CBACT04C.cbl}; JCL trigger
 * {@code legacy/jcl/INTCALC.jcl}, formerly {@code app/jcl/INTCALC.jcl},
 * {@code EXEC PGM=CBACT04C,PARM='2022071800'}). It defines the {@code interestCalculationJob} and
 * its single chunk-oriented {@code interestCalculationStep} (AAP sections 0.4.4 and 0.5.4).
 *
 * <p>This job is the single highest monetary-fidelity risk in the migration (AAP High-risk hotspot
 * <strong>H3</strong>): the monthly-interest computation applies the project-wide {@code BigDecimal}
 * scale-2 {@link java.math.RoundingMode#HALF_UP} standard mandated by AAP &sect;0.4.2. That standard
 * <em>intentionally diverges</em> from the legacy {@code COMPUTE}, which carries no {@code ROUNDED}
 * phrase and therefore truncates; the divergence is a documented deviation (decision log D31), not a
 * defect, so golden-file assertions target the HALF_UP result rather than the truncated COBOL value.
 * The parity-critical arithmetic itself lives in the {@link InterestCalculationProcessor} (the
 * formula and the disclosure-group {@code DEFAULT} fallback) and the per-account roll-up lives in the
 * {@link InterestTransactionWriter} (the control-break account update); this class is the
 * <em>orchestration</em> that wires those verified components into a runnable job.</p>
 *
 * <h2>Legacy behavior reproduced (CBACT04C)</h2>
 * The mainframe program walks the transaction-category-balance file ({@code TCATBAL}, VSAM KSDS)
 * front-to-back in ascending record-key order {@code (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD,
 * TRANCAT-CD)} (main loop, CBACT04C L188-222). For each category balance it looks up the account's
 * disclosure-group interest rate ({@code 1200-GET-INTEREST-RATE}, with a {@code 'DEFAULT'}-group
 * retry when the account's own group has no rate row), and &mdash; when the rate is non-zero &mdash;
 * computes a monthly interest amount ({@code 1300-COMPUTE-INTEREST}, L464-465), writes a
 * system-generated interest transaction (type {@code 01}, category {@code 05}, source
 * {@code System}; {@code 1300-B-WRITE-TX}, L473-515), and accumulates a per-account interest total.
 * On an <em>account control break</em> the accumulated total is added to the account balance and the
 * cycle credit/debit are zeroed ({@code 1050-UPDATE-ACCOUNT}, L350-370), and the account is
 * rewritten; the same finalize is applied to the final account after the file is exhausted.
 *
 * <h2>Target design &mdash; chunk-oriented step (Strategy B)</h2>
 * The step is chunk-oriented over {@code <}{@link TransactionCategoryBalance}{@code ,}
 * {@link InterestCalculationProcessor.InterestResult}{@code >} and is assembled from the three
 * verified, package-private-to-the-layer collaborators (all injected as shared beans rather than
 * constructed inline, because each encapsulates parity-critical logic):
 * <ul>
 *   <li><strong>Reader</strong> {@link TransactionCategoryBalanceItemReader}
 *       (bean {@code transactionCategoryBalanceItemReader}) &mdash; streams every {@code TCATBAL}
 *       row ordered by {@code (id.acctId, id.typeCd, id.catCd)} ascending, the set-based analog of
 *       the CBACT04C sequential VSAM browse. The leading account-id key guarantees that all rows for
 *       one account arrive contiguously, which is the precondition the account control break relies
 *       on.</li>
 *   <li><strong>Processor</strong> {@link InterestCalculationProcessor}
 *       (bean {@code interestCalculationProcessor}, {@code @StepScope}) &mdash; performs the per-row
 *       transform: the disclosure-group rate lookup with the {@code DEFAULT} fallback, the zero-rate
 *       guard, and the exact monthly-interest formula
 *       {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} at scale&nbsp;2 with
 *       {@link java.math.RoundingMode#HALF_UP}. It emits an {@code InterestResult} carrying the built
 *       interest {@link com.aws.carddemo.domain.Transaction}, the computed interest, and the account
 *       id; a filtered ({@code null}) result means a zero or absent rate.</li>
 *   <li><strong>Writer</strong> {@link InterestTransactionWriter}
 *       (bean {@code interestTransactionWriter}) &mdash; performs the output side: it accumulates the
 *       per-account interest total, drives the account control break, applies
 *       {@code 1050-UPDATE-ACCOUNT} (add total to {@code ACCT-CURR-BAL}, zero the cycle credit/debit,
 *       rewrite via the account repository under the entity's {@code @Version} optimistic lock),
 *       finalizes the last account, and serializes each interest transaction to the 350-byte
 *       fixed-width {@code SYSTRAN} file.</li>
 * </ul>
 *
 * <h3>Chunk size is intentionally {@code 1}</h3>
 * The commit interval is {@value #CHUNK_SIZE}. A chunk of one preserves the strict per-record
 * semantics of the COBOL read loop: each category balance is processed and written before the next
 * is read, so the control-break account update in the writer is applied at exactly the right point
 * in the ordered stream. (The writer maintains its control-break state across chunks as well, so
 * correctness does not depend on the chunk boundary; the size of one simply keeps the mapping to the
 * legacy record-at-a-time loop exact and obvious.)
 *
 * <h3>Why the writer's {@link org.springframework.batch.core.StepExecutionListener} is not
 * registered explicitly</h3>
 * {@link InterestTransactionWriter} is both an {@code ItemWriter} <em>and</em> a
 * {@code StepExecutionListener} (it opens the {@code SYSTRAN} file and resets control-break state in
 * {@code beforeStep}, and finalizes the last account, closes the file, and maps the batch return
 * code in {@code afterStep}). Spring Batch's {@code SimpleStepBuilder.build()} calls
 * {@code registerAsStreamsAndListeners(reader, processor, writer)}, which automatically registers any
 * of the reader/processor/writer as a {@code StepExecutionListener} (and as an {@code ItemStream})
 * when it implements the corresponding interface. Passing the writer through
 * {@link org.springframework.batch.core.step.builder.SimpleStepBuilder#writer(org.springframework.batch.item.ItemWriter)
 * writer(...)} therefore already wires its {@code beforeStep}/{@code afterStep}; registering it again
 * with an explicit {@code .listener(...)} would invoke those callbacks twice. For the same reason the
 * reader's {@code ItemStream} save-state lifecycle ({@code open}/{@code update}/{@code close}) is
 * managed automatically. This rationale is documented here (per the project's explainability rule)
 * rather than inline.
 *
 * <h2>Interest formula and the documented rounding deviation</h2>
 * The parity-critical statement {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}
 * (CBACT04C {@code 1300-COMPUTE-INTEREST}, L464-465) is reproduced in the processor as
 * {@code tranCatBal.multiply(intRate).divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP)}.
 * The COBOL {@code COMPUTE} carries no {@code ROUNDED} phrase and therefore truncates to scale&nbsp;2,
 * whereas the target intentionally applies {@link java.math.RoundingMode#HALF_UP}, the project-wide
 * monetary rounding standard (AAP &sect;0.4.2, &sect;0.7.1 H3). This is a deliberate, documented
 * deviation recorded in {@code docs/decision-log.md} as decision <strong>D31</strong>; it is noted
 * here so the choice is visible from the job that owns the interest calculation. Consequently the
 * interest result is <em>not</em> bit-for-bit identical to the truncating COBOL {@code COMPUTE} in the
 * boundary case where the third decimal digit is exactly 5 (for example a raw {@code 0.005} rounds to
 * {@code 0.01} here versus {@code 0.00} under COBOL truncation). All monetary values are
 * {@link java.math.BigDecimal} at scale&nbsp;2; {@code double}/{@code float} are never used.
 *
 * <h2>Run parameter</h2>
 * CBACT04C receives {@code PARM-DATE PIC X(10)} (the {@code INTCALC.jcl} {@code PARM='2022071800'})
 * via {@code PROCEDURE DIVISION USING}. In the target this is the {@code parmDate} job parameter
 * (ten characters, {@code CCYYMMDD} + two trailing digits), late-bound by the {@code @StepScope}
 * processor via SpEL ({@code #{jobParameters['parmDate']}}); it seeds the high-order ten characters
 * of every generated interest {@code TRAN-ID}. Because the parameter is late-bound only when the step
 * runs, this job declares a {@link JobParametersValidator} ({@link #parmDateValidator()}) that
 * requires a non-blank {@code parmDate} at launch, so a caller who omits it fails fast with a clear
 * {@link JobParametersInvalidException} instead of an opaque in-step binding error or a malformed
 * generated {@code TRAN-ID}. The launcher (an explicit {@code JobLauncher}/{@code JobOperator} or the
 * CI/CD workflow) supplies the parameter; the fail-fast contract is recorded in the decision log
 * (D33).
 *
 * <h2>Return codes</h2>
 * The interest job has <strong>no reject path</strong> (unlike transaction posting). A clean run
 * completes with {@code COMPLETED} {@link org.springframework.batch.core.BatchStatus} (the analog of
 * COBOL return code {@code 0}); an unrecoverable I/O failure fails the step and job (the analog of
 * return code {@code 8}). The writer performs the final {@code ExitStatus}/return-code mapping in its
 * {@code afterStep}.
 *
 * <h2>Infrastructure contract</h2>
 * <ul>
 *   <li>This class carries only {@link Configuration @Configuration} and deliberately does
 *       <strong>not</strong> declare {@code @EnableBatchProcessing}: declaring it anywhere would make
 *       Spring Boot's batch auto-configuration back off. The {@link JobRepository} and batch
 *       {@link PlatformTransactionManager} injected into the bean methods below are the
 *       auto-configured infrastructure beans and are never redeclared here.</li>
 *   <li>The configuration bean is explicitly named {@code "interestCalculationJobConfig"} so that the
 *       default component name (the decapitalized class name {@code interestCalculationJob}) does not
 *       collide with the {@link Job} bean of the same name declared by
 *       {@link #interestCalculationJob(JobRepository, Step, CorrelationIdJobListener)}.</li>
 *   <li>The job never runs at application startup because {@code spring.batch.job.enabled=false} in
 *       {@code application.yml}; it is launched explicitly (via {@code JobLauncher}/{@code JobOperator}
 *       or the CI/CD workflow, the modern equivalent of the JCL scheduler per AAP 0.4.4). Nothing in
 *       this configuration triggers auto-execution.</li>
 *   <li>The {@link CorrelationIdJobListener} (same package) is registered on the job so every batch
 *       log line carries a correlation id across the job/step boundary (Observability rule,
 *       AAP 0.9.5). Because the step is single-threaded (chunk size {@value #CHUNK_SIZE}), registering
 *       the listener on the job is sufficient for the correlation id to be present on every batch log
 *       line.</li>
 *   <li>Constructor/parameter injection only; no field injection. This configuration needs no
 *       injected collaborators of its own &mdash; the batch components are supplied as {@code @Bean}
 *       method parameters and resolved from the application context by type (and, for the {@link Step}
 *       parameter, by name, since several {@code Step} beans exist).</li>
 * </ul>
 *
 * @see InterestCalculationProcessor
 * @see TransactionCategoryBalanceItemReader
 * @see InterestTransactionWriter
 * @see CorrelationIdJobListener
 * @see TransactionCategoryBalance
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@Configuration("interestCalculationJobConfig")
public class InterestCalculationJob {

    /**
     * Job bean name. It is exactly {@code "interestCalculationJob"} so the job can be launched by name
     * through {@code JobLauncher}/{@code JobOperator}/{@code JobRegistry} (the JCL-scheduling
     * equivalent) and matches the AAP-specified public API.
     */
    private static final String JOB_NAME = "interestCalculationJob";

    /** Step bean name for the single chunk-oriented compute&rarr;post interest step. */
    private static final String STEP_NAME = "interestCalculationStep";

    /**
     * Chunk (commit-interval) size for the interest step. It is intentionally {@code 1} so that each
     * transaction-category-balance row is processed and written before the next is read, reproducing
     * the strict record-at-a-time semantics of the CBACT04C main loop and applying the control-break
     * account update at exactly the right point in the account-ordered stream.
     */
    private static final int CHUNK_SIZE = 1;

    /**
     * Job parameter name for the CBACT04C run date ({@code PARM-DATE PIC X(10)}). Late-bound by the
     * {@code @StepScope} {@link InterestCalculationProcessor} and required by {@link #parmDateValidator()}.
     */
    private static final String PARAM_PARM_DATE = "parmDate";

    /**
     * Defines the {@code interestCalculationJob}: a single-step job that computes and posts monthly
     * interest per account, reproducing CBACT04C.
     *
     * <p>The {@link CorrelationIdJobListener} is registered so the job (and its step) log lines carry
     * a correlation id end-to-end (Observability rule). The job has a single start step and no reject
     * path; the writer maps the terminal {@code ExitStatus}/return code.</p>
     *
     * @param jobRepository            the auto-configured Spring Batch {@link JobRepository};
     *                                 never {@code null}
     * @param interestCalculationStep  the single compute&rarr;post interest {@link Step} defined by
     *                                 {@link #interestCalculationStep(JobRepository, PlatformTransactionManager, TransactionCategoryBalanceItemReader, InterestCalculationProcessor, InterestTransactionWriter)};
     *                                 resolved by bean name; never {@code null}
     * @param correlationIdJobListener the cross-cutting correlation-id listener (same package);
     *                                 never {@code null}
     * @return the fully built interest-calculation {@link Job}; never {@code null}
     */
    @Bean
    public Job interestCalculationJob(JobRepository jobRepository,
                                      Step interestCalculationStep,
                                      CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(parmDateValidator())
                .listener(correlationIdJobListener)
                .start(interestCalculationStep)
                .build();
    }

    /**
     * Builds the {@link JobParametersValidator} that requires a non-blank {@code parmDate} parameter.
     *
     * <p>CBACT04C receives its run date as {@code PARM-DATE PIC X(10)} via {@code PROCEDURE DIVISION
     * USING} (the {@code INTCALC.jcl} {@code PARM='2022071800'}); the value seeds the high-order ten
     * characters of every generated interest {@code TRAN-ID}. Because the {@code @StepScope}
     * {@link InterestCalculationProcessor} late-binds {@code parmDate} only when the step runs, a
     * launch that omits it would otherwise fail deep inside the step with an opaque SpEL/binding error
     * (or silently generate malformed transaction ids). Validating at launch fails fast with a clear
     * {@link JobParametersInvalidException} instead, and is the fail-fast contract documented in the
     * decision log (D33). The validator intentionally checks only presence/blankness, not calendar
     * validity: the ten-character positional {@code CCYYMMDD}+2 contract mirrors the fixed-width COBOL
     * {@code PARM-DATE} and is consumed positionally, exactly as the legacy program does.</p>
     *
     * @return a validator that rejects launches missing or blanking {@code parmDate}
     */
    private JobParametersValidator parmDateValidator() {
        return new JobParametersValidator() {
            @Override
            public void validate(JobParameters parameters) throws JobParametersInvalidException {
                String parmDate = (parameters == null) ? null : parameters.getString(PARAM_PARM_DATE);
                if (parmDate == null || parmDate.isBlank()) {
                    throw new JobParametersInvalidException(
                            "Job parameter '" + PARAM_PARM_DATE + "' is required: supply the CBACT04C "
                                    + "run date as ten characters (CCYYMMDD plus two trailing digits, "
                                    + "for example parmDate=2022071800). It seeds the high-order ten "
                                    + "characters of every generated interest TRAN-ID.");
                }
            }
        };
    }

    /**
     * Defines the chunk-oriented interest step. Each {@link TransactionCategoryBalance} row is read in
     * ascending composite-key order, transformed into an
     * {@link InterestCalculationProcessor.InterestResult} (rate lookup with {@code DEFAULT} fallback,
     * zero-rate filter, and the exact interest formula), and handed to the writer, which accumulates
     * the per-account total, applies the {@code 1050-UPDATE-ACCOUNT} control-break account update, and
     * serializes the interest transaction to the {@code SYSTRAN} file.
     *
     * <p>The reader, processor, and writer are the shared beans from the {@code batch/reader},
     * {@code batch/processor}, and {@code batch/writer} packages; they are injected here (rather than
     * constructed inline) because each holds parity-critical logic and the processor is
     * {@code @StepScope} (its {@code parmDate} is late-bound from the job parameters, so the framework
     * supplies a step-scoped proxy that binds the parameter when the step runs). The writer, which
     * also implements {@link org.springframework.batch.core.StepExecutionListener}, is registered as a
     * step-execution listener automatically by {@code SimpleStepBuilder.build()} when it is set via
     * {@code writer(...)}; it is therefore deliberately <em>not</em> registered again with an explicit
     * {@code .listener(...)} (see the class Javadoc). The chunk commit is governed by the
     * auto-configured batch {@link PlatformTransactionManager}.</p>
     *
     * @param jobRepository                        the auto-configured Spring Batch
     *                                             {@link JobRepository}; never {@code null}
     * @param transactionManager                   the auto-configured batch
     *                                             {@link PlatformTransactionManager} that bounds each
     *                                             chunk transaction; never {@code null}
     * @param transactionCategoryBalanceItemReader the shared reader over the {@code tran_cat_balance}
     *                                             table in composite-key order (COBOL {@code TCATBAL}
     *                                             sequential read); never {@code null}
     * @param interestCalculationProcessor         the shared, step-scoped per-row interest processor
     *                                             (COBOL {@code 1200}/{@code 1300}); never {@code null}
     * @param interestTransactionWriter            the shared writer that accumulates per-account
     *                                             interest, applies {@code 1050-UPDATE-ACCOUNT}, and
     *                                             writes the {@code SYSTRAN} file (COBOL
     *                                             {@code 1050}/{@code 1300-B-WRITE-TX});
     *                                             never {@code null}
     * @return the configured interest {@link Step}; never {@code null}
     */
    @Bean
    public Step interestCalculationStep(JobRepository jobRepository,
                                        PlatformTransactionManager transactionManager,
                                        TransactionCategoryBalanceItemReader transactionCategoryBalanceItemReader,
                                        InterestCalculationProcessor interestCalculationProcessor,
                                        InterestTransactionWriter interestTransactionWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<TransactionCategoryBalance, InterestCalculationProcessor.InterestResult>chunk(
                        CHUNK_SIZE, transactionManager)
                .reader(transactionCategoryBalanceItemReader)
                .processor(interestCalculationProcessor)
                .writer(interestTransactionWriter)
                .build();
    }
}
