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
 * language governing permissions and limitations under the License.
 */
package com.carddemo.batch.config;

import com.carddemo.common.config.CorrelationIdContext;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.UUID;

/**
 * On-demand launch orchestration for the batch-service Spring Batch jobs.
 *
 * :purpose: Expose an asynchronous {@link JobLauncher} bean (named
 *     ``asyncJobLauncher``) backed by a {@link SimpleAsyncTaskExecutor}, and one
 *     public ``launch*`` method per batch job that builds the job's parameters
 *     and submits it through that launcher. Each submission runs on a separate
 *     ``batch-`` thread and returns a {@link JobExecution} without waiting for
 *     the job to finish. This is the Java analogue of the ``CORPT00C`` online
 *     report-submission path, which built a JCL skeleton and wrote it to the
 *     extra-partition transient data queue ``'JOBS'`` for the JES internal
 *     reader (``INTRDR``) to run [app/cbl/CORPT00C.cbl]. The nine launchable jobs
 *     are defined by {@link InterestCalculationJobConfig}
 *     (``interestCalculationJob``) and {@link DataManagementJobConfig}
 *     (``accountReadJob``, ``cardReadJob``, ``cardXrefReadJob``,
 *     ``customerReadJob``, ``dailyTransactionValidationJob``,
 *     ``categoryBalanceReportJob``, ``transactionDetailReportJob``,
 *     ``combineTransactionsJob``). Every launch adds a non-identifying
 *     ``correlationId`` job parameter, obtained from the static
 *     {@link CorrelationIdContext}, and an identifying unique ``run.id`` so each
 *     launch starts a fresh ``JobInstance``.
 * :output: The ``asyncJobLauncher`` {@link JobLauncher} bean and nine public
 *     ``launch*`` methods, each returning the submitted job's
 *     {@link JobExecution}. The {@link JobRepository} and the nine ``Job`` beans
 *     are supplied by Spring Boot batch auto-configuration and this
 *     configuration's collaborators; no batch infrastructure is
 *     self-instantiated beyond the launcher, and ``@EnableBatchProcessing`` is
 *     intentionally absent so the Boot auto-configuration stays active.
 * :note: ``REPROC.prc`` — the generic IDCAMS REPRO VSAM unload/load utility
 *     invoked by ``TRANREPT.prc`` ``STEP01R`` [app/proc/REPROC.prc,
 *     app/proc/TRANREPT.prc] — is a database backup / point-in-time-recovery
 *     infrastructure concern (AAP 0.4.6) and is not implemented as an application
 *     job here; no launch method corresponds to it.
 */
@Configuration
@SuppressWarnings({"deprecation", "removal"})
public class JobSchedulingConfig {

    /**
     * :purpose: The job-parameter key holding the per-launch unique run token
     *     that makes every launch a fresh ``JobInstance``; it is added as an
     *     identifying parameter.
     */
    private static final String RUN_ID_KEY = "run.id";

    /**
     * :purpose: Asynchronous launcher used by every ``launch*`` method; built in
     *     the constructor from the injected {@link JobRepository} and exposed as
     *     the ``asyncJobLauncher`` bean.
     */
    private final JobLauncher asyncJobLauncher;

    /** :purpose: The monthly interest-calculation job (``INTCALC`` / ``CBACT04C``). */
    private final Job interestCalculationJob;

    /** :purpose: The account read-and-print job (``READACCT`` / ``CBACT01C``). */
    private final Job accountReadJob;

    /** :purpose: The card read-and-print job (``READCARD`` / ``CBACT02C``). */
    private final Job cardReadJob;

    /** :purpose: The card cross-reference read-and-print job (``READXREF`` / ``CBACT03C``). */
    private final Job cardXrefReadJob;

    /** :purpose: The customer read-and-print job (``READCUST`` / ``CBCUS01C``). */
    private final Job customerReadJob;

    /** :purpose: The daily-transaction validation-read job (``CBTRN01C``). */
    private final Job dailyTransactionValidationJob;

    /** :purpose: The transaction-category-balance report job (``PRTCATBL``). */
    private final Job categoryBalanceReportJob;

    /** :purpose: The transaction detail report job (``CBTRN03C`` via ``TRANREPT.prc``). */
    private final Job transactionDetailReportJob;

    /** :purpose: The transaction combine job (``COMBTRAN``). */
    private final Job combineTransactionsJob;

    /**
     * :purpose: Build the asynchronous launcher from the batch job repository and
     *     capture the nine job beans so each ``launch*`` method can submit its
     *     job.
     * :param jobRepository: batch job repository (Spring Boot auto-configured);
     *     wired into the launcher.
     * :param interestCalculationJob: the ``interestCalculationJob`` bean.
     * :param accountReadJob: the ``accountReadJob`` bean.
     * :param cardReadJob: the ``cardReadJob`` bean.
     * :param cardXrefReadJob: the ``cardXrefReadJob`` bean.
     * :param customerReadJob: the ``customerReadJob`` bean.
     * :param dailyTransactionValidationJob: the ``dailyTransactionValidationJob`` bean.
     * :param categoryBalanceReportJob: the ``categoryBalanceReportJob`` bean.
     * :param transactionDetailReportJob: the ``transactionDetailReportJob`` bean.
     * :param combineTransactionsJob: the ``combineTransactionsJob`` bean.
     */
    public JobSchedulingConfig(
            JobRepository jobRepository,
            @Qualifier("interestCalculationJob") Job interestCalculationJob,
            @Qualifier("accountReadJob") Job accountReadJob,
            @Qualifier("cardReadJob") Job cardReadJob,
            @Qualifier("cardXrefReadJob") Job cardXrefReadJob,
            @Qualifier("customerReadJob") Job customerReadJob,
            @Qualifier("dailyTransactionValidationJob") Job dailyTransactionValidationJob,
            @Qualifier("categoryBalanceReportJob") Job categoryBalanceReportJob,
            @Qualifier("transactionDetailReportJob") Job transactionDetailReportJob,
            @Qualifier("combineTransactionsJob") Job combineTransactionsJob) {
        // Built here (not constructor-injected) because this class also defines
        // the asyncJobLauncher bean; injecting it would be a self-referential cycle.
        this.asyncJobLauncher = buildAsyncJobLauncher(jobRepository);
        this.interestCalculationJob = interestCalculationJob;
        this.accountReadJob = accountReadJob;
        this.cardReadJob = cardReadJob;
        this.cardXrefReadJob = cardXrefReadJob;
        this.customerReadJob = customerReadJob;
        this.dailyTransactionValidationJob = dailyTransactionValidationJob;
        this.categoryBalanceReportJob = categoryBalanceReportJob;
        this.transactionDetailReportJob = transactionDetailReportJob;
        this.combineTransactionsJob = combineTransactionsJob;
    }

    /**
     * :purpose: Construct a {@link TaskExecutorJobLauncher} bound to the batch job
     *     repository and a {@link SimpleAsyncTaskExecutor} whose threads are named
     *     ``batch-N``, so submitted jobs run on their own threads.
     * :param jobRepository: batch job repository the launcher records executions in.
     * :returns: a fully initialized asynchronous {@link JobLauncher}.
     */
    private static JobLauncher buildAsyncJobLauncher(JobRepository jobRepository) {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("batch-"));
        try {
            // afterPropertiesSet() declares a checked Exception; a failure here
            // means the launcher can never run a job, so fail fast at startup.
            launcher.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize asyncJobLauncher", ex);
        }
        return launcher;
    }

    /**
     * :purpose: Expose the asynchronous launcher built in the constructor as the
     *     ``asyncJobLauncher`` bean, kept distinct by name from the synchronous
     *     launcher supplied by Spring Boot batch auto-configuration.
     * :returns: the {@link JobLauncher} that runs jobs on ``batch-`` threads.
     */
    @Bean("asyncJobLauncher")
    public JobLauncher asyncJobLauncher() {
        return this.asyncJobLauncher;
    }

    /**
     * :purpose: Enrich a job's business parameters with observability and
     *     uniqueness parameters, then submit the job on the asynchronous
     *     launcher. A ``correlationId`` obtained from the static
     *     {@link CorrelationIdContext} is added as a non-identifying parameter
     *     (also seeded into the launching thread's MDC) and a random ``run.id`` is
     *     added as an identifying parameter so each launch starts a new
     *     ``JobInstance``.
     * :param job: the batch job to submit.
     * :param businessParameters: the job-specific parameters already assembled by
     *     the calling ``launch*`` method.
     * :returns: the {@link JobExecution} returned by the asynchronous launcher.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    private JobExecution launch(Job job, JobParameters businessParameters) throws JobExecutionException {
        String correlationId = CorrelationIdContext.getOrCreateCorrelationId();
        JobParameters parameters = new JobParametersBuilder(businessParameters)
                .addString(CorrelationIdContext.CORRELATION_ID_KEY, correlationId, false)
                .addString(RUN_ID_KEY, UUID.randomUUID().toString(), true)
                .toJobParameters();
        return this.asyncJobLauncher.run(job, parameters);
    }

    /**
     * :purpose: Launch the monthly interest-calculation job
     *     (``interestCalculationJob``) on the asynchronous launcher.
     * :param parmDate: the 10-character ``YYYYMMDDHH`` business date supplied as
     *     the ``parmDate`` job parameter (legacy ``INTCALC.jcl`` ``PARM='2022071800'``).
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchInterestCalculation(String parmDate) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString("parmDate", parmDate)
                .toJobParameters();
        return launch(interestCalculationJob, businessParameters);
    }

    /**
     * :purpose: Launch the transaction detail report job
     *     (``transactionDetailReportJob``) on the asynchronous launcher; this is
     *     the primary migration of the ``CORPT00C`` online report submission.
     * :param startDate: inclusive range start in ``YYYY-MM-DD`` form, supplied as
     *     the ``startDate`` job parameter (``TRANREPT.prc`` ``DATEPARM``
     *     ``PARM-START-DATE``).
     * :param endDate: inclusive range end in ``YYYY-MM-DD`` form, supplied as the
     *     ``endDate`` job parameter (``TRANREPT.prc`` ``DATEPARM``
     *     ``PARM-END-DATE``).
     * :param reportFile: the report output path, supplied as the ``reportFile``
     *     job parameter.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchTransactionDetailReport(String startDate, String endDate, String reportFile)
            throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString("startDate", startDate)
                .addString("endDate", endDate)
                .addString("reportFile", reportFile)
                .toJobParameters();
        return launch(transactionDetailReportJob, businessParameters);
    }

    /**
     * :purpose: Launch the transaction-category-balance report job
     *     (``categoryBalanceReportJob``) on the asynchronous launcher.
     * :param outputFile: the report output path, supplied as the ``outputFile``
     *     job parameter.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCategoryBalanceReport(String outputFile) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString("outputFile", outputFile)
                .toJobParameters();
        return launch(categoryBalanceReportJob, businessParameters);
    }

    /**
     * :purpose: Launch the daily-transaction validation-read job
     *     (``dailyTransactionValidationJob``) on the asynchronous launcher.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchDailyTransactionValidation() throws JobExecutionException {
        return launch(dailyTransactionValidationJob, new JobParametersBuilder().toJobParameters());
    }

    /**
     * :purpose: Launch the account read-and-print job (``accountReadJob``) on the
     *     asynchronous launcher.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchAccountRead() throws JobExecutionException {
        return launch(accountReadJob, new JobParametersBuilder().toJobParameters());
    }

    /**
     * :purpose: Launch the card read-and-print job (``cardReadJob``) on the
     *     asynchronous launcher.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCardRead() throws JobExecutionException {
        return launch(cardReadJob, new JobParametersBuilder().toJobParameters());
    }

    /**
     * :purpose: Launch the card cross-reference read-and-print job
     *     (``cardXrefReadJob``) on the asynchronous launcher.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCardXrefRead() throws JobExecutionException {
        return launch(cardXrefReadJob, new JobParametersBuilder().toJobParameters());
    }

    /**
     * :purpose: Launch the customer read-and-print job (``customerReadJob``) on the
     *     asynchronous launcher.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCustomerRead() throws JobExecutionException {
        return launch(customerReadJob, new JobParametersBuilder().toJobParameters());
    }

    /**
     * :purpose: Launch the transaction combine job (``combineTransactionsJob``) on
     *     the asynchronous launcher.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCombineTransactions() throws JobExecutionException {
        return launch(combineTransactionsJob, new JobParametersBuilder().toJobParameters());
    }
}
