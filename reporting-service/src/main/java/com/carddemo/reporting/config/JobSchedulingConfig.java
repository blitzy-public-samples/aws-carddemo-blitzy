/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 */
package com.carddemo.reporting.config;

import com.carddemo.common.exception.CardDemoException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * :purpose: Re-platforms the legacy CICS asynchronous batch-submission mechanism
 *  of the online report program ``CORPT00C`` (the ``SUBMIT-JOB-TO-INTRDR`` and
 *  ``WIRTE-JOBSUB-TDQ`` paragraphs, which wrote 80-byte JCL records to the
 *  extra-partition Transient Data Queue ``'JOBS'`` for the JES internal reader to
 *  run asynchronously). In the target architecture the submission becomes an
 *  on-demand, non-blocking {@link org.springframework.batch.core.launch.JobLauncher}
 *  invocation of the sibling ``statementGenerationJob`` batch job.
 * :output: Exposes a single parameterized, asynchronous launch entry point that
 *  the reporting-service report service calls to submit the statement-generation
 *  job with the requested report type and date range; submission failures are
 *  surfaced as a {@link CardDemoException}.
 */
@Configuration
@EnableAsync
public class JobSchedulingConfig {

    /** :purpose: Logger for asynchronous statement-generation job submission. */
    private static final Logger LOGGER = LoggerFactory.getLogger(JobSchedulingConfig.class);

    /** :purpose: Job-parameter key carrying the report name (``Monthly``/``Yearly``/``Custom``). */
    private static final String PARAM_REPORT_TYPE = "reportType";

    /** :purpose: Job-parameter key carrying the ``PARM-START-DATE`` (``YYYY-MM-DD``). */
    private static final String PARAM_START_DATE = "startDate";

    /** :purpose: Job-parameter key carrying the ``PARM-END-DATE`` (``YYYY-MM-DD``). */
    private static final String PARAM_END_DATE = "endDate";

    /**
     * :purpose: Job-parameter key whose unique value forces a fresh ``JobInstance``
     *  per submission, mirroring the legacy behavior where every CICS submit wrote
     *  a brand-new job to the JES internal reader.
     */
    private static final String PARAM_RUN_ID = "run.id";

    /** :purpose: Frozen operator-visible message emitted when the submission fails. */
    private static final String SUBMIT_FAILURE_MESSAGE = "Unable to Write TDQ (JOBS)...";

    /** :purpose: Auto-configured Spring Batch launcher used to submit the job. */
    private final JobLauncher jobLauncher;

    /** :purpose: The statement-generation job launched on each report request. */
    private final Job statementGenerationJob;

    /**
     * :purpose: Construct the scheduler with the auto-configured launcher and the
     *  statement-generation job resolved by bean name.
     * :param jobLauncher: the Spring Boot auto-configured job launcher.
     * :param statementGenerationJob: the ``statementGenerationJob`` batch job bean.
     */
    public JobSchedulingConfig(JobLauncher jobLauncher,
                               @Qualifier("statementGenerationJob") Job statementGenerationJob) {
        this.jobLauncher = jobLauncher;
        this.statementGenerationJob = statementGenerationJob;
    }

    /**
     * :purpose: Launch the statement-generation batch job asynchronously,
     *  re-platforming ``CORPT00C``'s TDQ ``'JOBS'`` internal-reader submission as a
     *  non-blocking {@link JobLauncher} invocation carrying the report parameters.
     *  A unique ``run.id`` parameter is added so each request starts a fresh
     *  ``JobInstance`` and repeat report submissions are never rejected.
     * :param reportType: the report name (``Monthly``, ``Yearly`` or ``Custom``).
     * :param startDate: the ``PARM-START-DATE`` in ``YYYY-MM-DD`` wire form.
     * :param endDate: the ``PARM-END-DATE`` in ``YYYY-MM-DD`` wire form.
     * :return: a {@link CompletableFuture} completed with the launched job's
     *  {@link JobExecution}.
     * :raises CardDemoException: when the job cannot be submitted to the launcher.
     */
    @Async
    public CompletableFuture<JobExecution> launchStatementGeneration(String reportType,
                                                                     String startDate,
                                                                     String endDate) {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(PARAM_REPORT_TYPE, reportType)
                .addString(PARAM_START_DATE, startDate)
                .addString(PARAM_END_DATE, endDate)
                .addString(PARAM_RUN_ID, UUID.randomUUID().toString())
                .toJobParameters();

        LOGGER.info("Submitting statementGenerationJob (reportType={}, startDate={}, endDate={})",
                reportType, startDate, endDate);

        try {
            JobExecution jobExecution = jobLauncher.run(statementGenerationJob, jobParameters);
            return CompletableFuture.completedFuture(jobExecution);
        } catch (JobExecutionAlreadyRunningException | JobRestartException
                 | JobInstanceAlreadyCompleteException | InvalidJobParametersException e) {
            throw new CardDemoException(SUBMIT_FAILURE_MESSAGE, e);
        }
    }
}
