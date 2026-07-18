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

import com.aws.carddemo.batch.reader.DailyTransactionFileItemReader;
import com.aws.carddemo.batch.writer.DailyTransactionStagingWriter;
import com.aws.carddemo.domain.DailyTransaction;

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
 * Spring Batch {@link Configuration} that provides the executable raw-file loader for the
 * daily-transaction pipeline: it reads the external, fixed-width {@code DALYTRAN} sequential file
 * (350-byte {@code DALYTRAN-RECORD}, copybook {@code legacy/cpy/CVTRA06Y.cpy}) and materializes it
 * into the {@code daily_transaction} staging table (AAP &sect;0.7.2 hotspot M2 "external fixed-width
 * file contracts"; &sect;0.5.4 batch layer).
 *
 * <h2>Why this job exists</h2>
 * The daily-transaction <em>validate</em> ({@code CBTRN01C}) and <em>posting</em> ({@code CBTRN02C})
 * jobs consume the {@code daily_transaction} staging table through the DB-backed
 * {@link com.aws.carddemo.batch.reader.DailyTransactionItemReader}. This job is the operational
 * front door that populates that table from the raw external file, so a real daily batch does not
 * depend on the {@code @Profile("local")} CSV seed loader for its input. It closes the ingestion
 * gap for the preserved 350-byte external {@code DALYTRAN} contract.
 *
 * <h2>Target design</h2>
 * <ul>
 *   <li><strong>Streaming fixed-width read.</strong> The single chunk-oriented step is driven by the
 *       {@code @StepScope} {@link DailyTransactionFileItemReader} (bean
 *       {@code dailyTransactionFileItemReader}), which streams the raw file line-by-line and decodes
 *       each 350-byte record with {@link com.aws.carddemo.common.util.FixedWidthCodec} over
 *       {@code ISO-8859-1} (preserving the {@code DALYTRAN-AMT} overpunch sign byte).</li>
 *   <li><strong>Staging load.</strong> The decoded {@link DailyTransaction} items are inserted into
 *       the {@code daily_transaction} table by the {@link DailyTransactionStagingWriter} (bean
 *       {@code dailyTransactionStagingWriter}). No validation, posting, or reject semantics belong
 *       here; those are owned by {@code DailyTransactionValidateJob} / {@code DailyTransactionPostingJob}.</li>
 *   <li><strong>Late-bound input.</strong> The input file location is supplied by the
 *       {@code inputResource} job parameter (validated by {@link #dailyTransactionLoadJob}); no path
 *       is hard-coded.</li>
 * </ul>
 *
 * <h2>Infrastructure contract</h2>
 * <ul>
 *   <li>This class carries only {@link Configuration @Configuration} and deliberately does
 *       <strong>not</strong> declare {@code @EnableBatchProcessing}; the injected {@link JobRepository}
 *       and batch {@link PlatformTransactionManager} are the auto-configured infrastructure beans
 *       (owned by {@code com.aws.carddemo.config.BatchConfig}).</li>
 *   <li>The configuration bean is explicitly named {@code "dailyTransactionLoadJobConfig"} so the
 *       default component name ({@code dailyTransactionLoadJob}) does not collide with the {@code Job}
 *       bean of the same name declared below.</li>
 *   <li>The job never runs at application startup ({@code spring.batch.job.enabled=false}); it is
 *       launched explicitly by name via {@code JobLauncher}/{@code JobOperator} or the CI/CD workflow
 *       (the modern JCL-scheduler equivalent per AAP 0.4.4), supplying the {@code inputResource}
 *       parameter.</li>
 *   <li>The {@link CorrelationIdJobListener} is registered so every batch log line carries a
 *       correlation id across the job/step boundary (Observability rule, AAP 0.9.5).</li>
 * </ul>
 *
 * @see DailyTransactionFileItemReader
 * @see DailyTransactionStagingWriter
 * @see com.aws.carddemo.batch.reader.DailyTransactionItemReader
 * @see CorrelationIdJobListener
 */
@Configuration("dailyTransactionLoadJobConfig")
public class DailyTransactionLoadJob {

    /**
     * Job bean name. Exactly {@code "dailyTransactionLoadJob"} so it can be launched by name through
     * {@code JobOperator}/{@code JobRegistry} (the JCL-scheduling equivalent).
     */
    private static final String JOB_NAME = "dailyTransactionLoadJob";

    /** Step bean name for the single chunk-oriented read&rarr;load step. */
    private static final String STEP_NAME = "dailyTransactionLoadStep";

    /**
     * Job parameter name for the raw external {@code DALYTRAN} input file location. Late-bound into
     * {@link DailyTransactionFileItemReader} and required by {@link #inputResourceValidator()}.
     */
    private static final String PARAM_INPUT_RESOURCE = "inputResource";

    /**
     * Chunk (commit-interval) size for the load step. A page of {@code 100} records per commit keeps
     * memory bounded for an arbitrarily large daily-transaction file while checkpointing metadata
     * regularly. Unlike the posting step (which is chunk size 1 for per-record reject semantics), the
     * load step has no per-record business logic, so a larger chunk is appropriate.
     */
    private static final int CHUNK_SIZE = 100;

    /**
     * Defines the {@code dailyTransactionLoadJob}: a single-step job that reads the raw fixed-width
     * {@code DALYTRAN} file and loads the {@code daily_transaction} staging table.
     *
     * <p>A {@link JobParametersValidator} ({@link #inputResourceValidator()}) requires the
     * {@code inputResource} parameter so a launch without an input file fails fast with a clear
     * {@link JobParametersInvalidException} rather than starting and reading nothing. The
     * {@link CorrelationIdJobListener} is registered so the job and its step log lines carry a
     * correlation id end-to-end.</p>
     *
     * @param jobRepository              the auto-configured Spring Batch {@link JobRepository};
     *                                   never {@code null}
     * @param dailyTransactionLoadStep   the single read&rarr;load {@link Step} defined by
     *                                   {@link #dailyTransactionLoadStep(JobRepository, PlatformTransactionManager, DailyTransactionFileItemReader, DailyTransactionStagingWriter)};
     *                                   never {@code null}
     * @param correlationIdJobListener   the cross-cutting correlation-id listener; never {@code null}
     * @return the fully built daily-transaction load {@link Job}
     */
    @Bean
    public Job dailyTransactionLoadJob(JobRepository jobRepository,
                                       Step dailyTransactionLoadStep,
                                       CorrelationIdJobListener correlationIdJobListener) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(inputResourceValidator())
                .listener(correlationIdJobListener)
                .start(dailyTransactionLoadStep)
                .build();
    }

    /**
     * Defines the chunk-oriented read&rarr;load step. Each chunk of {@link #CHUNK_SIZE} decoded
     * {@link DailyTransaction} records is read from the raw fixed-width file and inserted into the
     * staging table.
     *
     * <p>The reader is the {@code @StepScope} {@link DailyTransactionFileItemReader}; it is injected
     * here as a scoped proxy so its late-bound {@code inputResource} job parameter binds per launch.
     * The writer is the singleton {@link DailyTransactionStagingWriter}. The step has no processor
     * because the load performs no per-record transformation.</p>
     *
     * @param jobRepository                    the auto-configured Spring Batch {@link JobRepository};
     *                                         never {@code null}
     * @param transactionManager               the auto-configured batch
     *                                         {@link PlatformTransactionManager} bounding each chunk;
     *                                         never {@code null}
     * @param dailyTransactionFileItemReader   the step-scoped fixed-width file reader; never
     *                                         {@code null}
     * @param dailyTransactionStagingWriter    the staging-table insert writer; never {@code null}
     * @return the configured load {@link Step}
     */
    @Bean
    public Step dailyTransactionLoadStep(JobRepository jobRepository,
                                         PlatformTransactionManager transactionManager,
                                         DailyTransactionFileItemReader dailyTransactionFileItemReader,
                                         DailyTransactionStagingWriter dailyTransactionStagingWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<DailyTransaction, DailyTransaction>chunk(CHUNK_SIZE, transactionManager)
                .reader(dailyTransactionFileItemReader)
                .writer(dailyTransactionStagingWriter)
                .build();
    }

    /**
     * Builds the {@link JobParametersValidator} that requires a non-blank {@code inputResource}
     * parameter. Launching the load without an input file location is a caller error: failing fast
     * with {@link JobParametersInvalidException} is clearer than starting a job that would read no
     * records.
     *
     * @return a validator that rejects launches missing or blanking {@code inputResource}
     */
    private JobParametersValidator inputResourceValidator() {
        return new JobParametersValidator() {
            @Override
            public void validate(JobParameters parameters) throws JobParametersInvalidException {
                String inputResource = (parameters == null)
                        ? null : parameters.getString(PARAM_INPUT_RESOURCE);
                if (inputResource == null || inputResource.isBlank()) {
                    throw new JobParametersInvalidException(
                            "Job parameter '" + PARAM_INPUT_RESOURCE + "' is required: supply the "
                                    + "location of the raw fixed-width DALYTRAN input file "
                                    + "(for example inputResource=file:/path/to/DALYTRAN.PS).");
                }
            }
        };
    }
}
