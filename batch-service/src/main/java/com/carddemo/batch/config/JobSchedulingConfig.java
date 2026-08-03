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

import com.carddemo.common.batch.BatchOutputPathResolver;
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
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

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
     * :purpose: Upper bound on batch jobs executing concurrently on the async launcher.
     *     ``SimpleAsyncTaskExecutor`` pools no threads, so an unbounded executor would
     *     start a new thread for every submission and let a burst of launches exhaust
     *     memory and saturate the connection pool. The legacy environment bounded this
     *     naturally through JES initiator classes.
     */
    private static final int MAX_CONCURRENT_JOBS = 4;

    /**
     * :purpose: The job-parameter key naming the file a read/report job writes;
     *     bound by the owning step's writer through
     *     ``#{jobParameters['outputFile']}``.
     */
    public static final String OUTPUT_FILE_KEY = "outputFile";

    /**
     * :purpose: The job-parameter key naming the sequential feed file the
     *     daily-transaction validation job reads; bound by its reader through
     *     ``#{jobParameters['inputFile']}``.
     */
    public static final String INPUT_FILE_KEY = "inputFile";

    /**
     * :purpose: The job-parameter key naming the file the transaction-detail report job
     *     writes; bound by its writer through ``#{jobParameters['reportFile']}``.
     */
    public static final String REPORT_FILE_KEY = "reportFile";

    /**
     * :purpose: Default report file written by ``accountReadJob``, named after the
     *     ``ACCTFILE`` data set the legacy ``READACCT`` job read
     *     [app/jcl/READACCT.jcl].
     */
    public static final String DEFAULT_ACCOUNT_REPORT_FILE = "acctdata-report.txt";

    /**
     * :purpose: Default report file written by ``cardReadJob``, named after the
     *     ``CARDFILE`` data set the legacy ``READCARD`` job read
     *     [app/jcl/READCARD.jcl].
     */
    public static final String DEFAULT_CARD_REPORT_FILE = "carddata-report.txt";

    /**
     * :purpose: Default report file written by ``cardXrefReadJob``, named after the
     *     ``CARDXREF`` data set the legacy ``READXREF`` job read
     *     [app/jcl/READXREF.jcl].
     */
    public static final String DEFAULT_CARD_XREF_REPORT_FILE = "cardxref-report.txt";

    /**
     * :purpose: Default report file written by ``customerReadJob``, named after the
     *     ``CUSTFILE`` data set the legacy ``READCUST`` job read
     *     [app/jcl/READCUST.jcl].
     */
    public static final String DEFAULT_CUSTOMER_REPORT_FILE = "custdata-report.txt";

    /**
     * :purpose: Default report file written by ``categoryBalanceReportJob``, named
     *     after the ``TCATBALF`` data set the legacy ``PRTCATBL`` job printed
     *     [app/jcl/PRTCATBL.jcl].
     */
    public static final String DEFAULT_CATEGORY_BALANCE_REPORT_FILE = "tcatbal-report.txt";

    /**
     * :purpose: Default report file written by ``transactionDetailReportJob``,
     *     named after the report the legacy ``TRANREPT`` proc produced
     *     [app/proc/TRANREPT.prc].
     */
    public static final String DEFAULT_TRANSACTION_DETAIL_REPORT_FILE = "tran-detail-report.txt";

    /**
     * :purpose: Default combined-transaction file written by
     *     ``combineTransactionsJob``, named after the ``COMBTRAN`` output data set
     *     [app/jcl/COMBTRAN.jcl].
     */
    public static final String DEFAULT_COMBINED_TRANSACTION_FILE = "combined-transactions.txt";

    /**
     * :purpose: Default sequential feed file read by
     *     ``dailyTransactionValidationJob``, named after the ``DALYTRAN`` data set
     *     the legacy ``CBTRN01C`` program read; the repository fixture
     *     ``app/data/ASCII/dailytran.txt`` carries exactly this name.
     */
    public static final String DEFAULT_DAILY_TRANSACTION_FEED_FILE = "dailytran.txt";

    /**
     * :purpose: Default business date supplied to ``interestCalculationJob``,
     *     carried verbatim from the legacy job stream, whose single step hard-codes
     *     it on the ``EXEC`` card: ``//STEP15 EXEC PGM=CBACT04C,PARM='2022071800'``
     *     [app/jcl/INTCALC.jcl:L22]. The date belongs to the migrated job stream,
     *     not to the caller, so a submission that names no date runs exactly the
     *     job the mainframe operator submitted.
     */
    public static final String DEFAULT_PARM_DATE = "2022071800";

    /**
     * :purpose: Default inclusive start of the reporting window used by
     *     ``transactionDetailReportJob``, carried verbatim from the ``SYMNAMES``
     *     control card of the legacy job stream
     *     (``PARM-START-DATE,C'2022-01-01'``) [app/jcl/TRANREPT.jcl,
     *     app/proc/TRANREPT.prc:L41].
     */
    public static final String DEFAULT_REPORT_START_DATE = "2022-01-01";

    /**
     * :purpose: Default inclusive end of the reporting window used by
     *     ``transactionDetailReportJob``, carried verbatim from the ``SYMNAMES``
     *     control card of the legacy job stream (``PARM-END-DATE,C'2022-07-06'``)
     *     [app/jcl/TRANREPT.jcl, app/proc/TRANREPT.prc:L42].
     */
    public static final String DEFAULT_REPORT_END_DATE = "2022-07-06";

    /**
     * :purpose: Asynchronous launcher used by every ``launch*`` method; built in
     *     the constructor from the injected {@link JobRepository} and exposed as
     *     the ``asyncJobLauncher`` bean.
     */
    private final JobLauncher asyncJobLauncher;

    /**
     * :purpose: Confines every batch file to the configured roots. Held here so a path that
     *     escapes them is refused while the caller is still on the line, instead of being
     *     accepted and then failing on a worker thread the caller cannot observe.
     */
    private final BatchOutputPathResolver outputPathResolver;

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
            @Qualifier("combineTransactionsJob") Job combineTransactionsJob,
            BatchOutputPathResolver outputPathResolver) {
        // Built here (not constructor-injected) because this class also defines
        // the asyncJobLauncher bean; injecting it would be a self-referential cycle.
        this.asyncJobLauncher = buildAsyncJobLauncher(jobRepository);
        this.outputPathResolver = outputPathResolver;
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
        SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("batch-");
        // Carry the submitting request's observation/trace scope and correlation id onto
        // the batch- thread. This executor is built by hand, so Spring Boot's automatic
        // application of the shared TaskDecorator bean does not reach it; without the
        // decorator every job log line and every span produced by the job would be
        // orphaned from the request that launched it (AAP 0.7.5).
        taskExecutor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        // Bound the concurrency: SimpleAsyncTaskExecutor pools no threads, so without a
        // limit each submission spawns a new thread and a burst of launches can exhaust
        // memory and saturate the JDBC pool.
        taskExecutor.setConcurrencyLimit(MAX_CONCURRENT_JOBS);
        launcher.setTaskExecutor(taskExecutor);
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
    /**
     * :purpose: Resolve every file parameter of a submission through the shared resolver
     *     before the run is accepted, so a path that escapes the configured root is refused
     *     synchronously. The writers resolve the same way when they open the file, so this
     *     adds no second policy: it only moves the refusal to the point where the caller can
     *     still be told the submission was not accepted, rather than handing back a
     *     ``STARTING`` execution for a job certain to fail on a worker thread.
     * :param businessParameters: the business parameters of the submission.
     * :raises IllegalArgumentException: if a file parameter escapes its configured root.
     */
    private void requireContainedPaths(JobParameters businessParameters) {
        String outputFile = businessParameters.getString(OUTPUT_FILE_KEY);
        if (outputFile != null) {
            outputPathResolver.resolveOutput(outputFile);
        }
        String reportFile = businessParameters.getString(REPORT_FILE_KEY);
        if (reportFile != null) {
            outputPathResolver.resolveOutput(reportFile);
        }
        String inputFile = businessParameters.getString(INPUT_FILE_KEY);
        if (inputFile != null) {
            outputPathResolver.resolveInput(inputFile);
        }
    }

    private JobExecution launch(Job job, JobParameters businessParameters) throws JobExecutionException {
        // Remember whether an id was already in scope: when it was, it belongs to the
        // CorrelationIdFilter, which clears it at the end of the request. When it was not,
        // this method seeded it and must clear it again, because the launching thread is a
        // pooled container thread that would otherwise carry the id into the next,
        // unrelated request handled by that thread.
        requireContainedPaths(businessParameters);

        String previous = CorrelationIdContext.getCorrelationId();
        String correlationId = CorrelationIdContext.getOrCreateCorrelationId();
        try {
            JobParameters parameters = new JobParametersBuilder(businessParameters)
                    .addString(CorrelationIdContext.CORRELATION_ID_KEY, correlationId, false)
                    // Non-identifying: job identity must derive from the business
                    // parameters alone, so re-submitting an already-completed instance is
                    // refused exactly as JES refused a duplicate job. An identifying
                    // run.id would silently make every submission a new instance and
                    // duplicate detection could never fire.
                    .addString(RUN_ID_KEY, UUID.randomUUID().toString(), false)
                    .toJobParameters();
            return this.asyncJobLauncher.run(job, parameters);
        } finally {
            if (previous == null || previous.isBlank()) {
                CorrelationIdContext.clear();
            }
        }
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
     * :purpose: Launch the monthly interest-calculation job over the business date
     *     the legacy job stream hard-codes on its ``EXEC`` card
     *     ({@value #DEFAULT_PARM_DATE}), so a submission that names no date runs
     *     exactly the job the mainframe operator submitted [app/jcl/INTCALC.jcl:L22].
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchInterestCalculation() throws JobExecutionException {
        return launchInterestCalculation(DEFAULT_PARM_DATE);
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
                .addString(REPORT_FILE_KEY, reportFile)
                .toJobParameters();
        return launch(transactionDetailReportJob, businessParameters);
    }

    /**
     * :purpose: Launch the transaction detail report job over the supplied date
     *     range, writing to the default report file
     *     ({@value #DEFAULT_TRANSACTION_DETAIL_REPORT_FILE}) inside the configured
     *     batch output root.
     * :param startDate: inclusive range start in ``YYYY-MM-DD`` form.
     * :param endDate: inclusive range end in ``YYYY-MM-DD`` form.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchTransactionDetailReport(String startDate, String endDate)
            throws JobExecutionException {
        return launchTransactionDetailReport(startDate, endDate, DEFAULT_TRANSACTION_DETAIL_REPORT_FILE);
    }

    /**
     * :purpose: Launch the transaction detail report job over the reporting window
     *     the legacy job stream hard-codes on its ``SYMNAMES`` control card
     *     ({@value #DEFAULT_REPORT_START_DATE} through
     *     {@value #DEFAULT_REPORT_END_DATE}), writing to the default report file
     *     ({@value #DEFAULT_TRANSACTION_DETAIL_REPORT_FILE}) inside the configured
     *     batch output root [app/jcl/TRANREPT.jcl, app/proc/TRANREPT.prc:L41-L42].
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchTransactionDetailReport() throws JobExecutionException {
        return launchTransactionDetailReport(DEFAULT_REPORT_START_DATE, DEFAULT_REPORT_END_DATE);
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
     * :purpose: Launch the transaction-category-balance report job, writing to the
     *     default report file ({@value #DEFAULT_CATEGORY_BALANCE_REPORT_FILE})
     *     inside the configured batch output root.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCategoryBalanceReport() throws JobExecutionException {
        return launchCategoryBalanceReport(DEFAULT_CATEGORY_BALANCE_REPORT_FILE);
    }

    /**
     * :purpose: Launch the daily-transaction validation-read job
     *     (``dailyTransactionValidationJob``) on the asynchronous launcher, reading
     *     the default feed file ({@value #DEFAULT_DAILY_TRANSACTION_FEED_FILE})
     *     from the configured batch input root. The ``inputFile`` parameter is
     *     supplied explicitly because the step's reader binds it through
     *     ``#{jobParameters['inputFile']}`` and previously received ``null``, which
     *     failed the job before it read a single record.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchDailyTransactionValidation() throws JobExecutionException {
        return launchDailyTransactionValidation(DEFAULT_DAILY_TRANSACTION_FEED_FILE);
    }

    /**
     * :purpose: Launch ``dailyTransactionValidationJob`` with an explicit sequential feed file.
     * :param inputFile: the ``inputFile`` job parameter; a bare file name resolved
     *     inside the configured batch root by ``BatchOutputPathResolver``.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchDailyTransactionValidation(String inputFile) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(INPUT_FILE_KEY, inputFile)
                .toJobParameters();
        return launch(dailyTransactionValidationJob, businessParameters);
    }

    /**
     * :purpose: Launch the account read-and-print job (``accountReadJob``) on the
     *     asynchronous launcher, writing the default report file
     *     ({@value #DEFAULT_ACCOUNT_REPORT_FILE}) into the configured batch output
     *     root. The ``outputFile`` parameter is supplied explicitly because the
     *     step's writer binds it through ``#{jobParameters['outputFile']}`` and
     *     previously received ``null``, which failed the job at step start.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchAccountRead() throws JobExecutionException {
        return launchAccountRead(DEFAULT_ACCOUNT_REPORT_FILE);
    }

    /**
     * :purpose: Launch ``accountReadJob`` with an explicit report file.
     * :param outputFile: the ``outputFile`` job parameter; a bare file name resolved
     *     inside the configured batch root by ``BatchOutputPathResolver``.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchAccountRead(String outputFile) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY, outputFile)
                .toJobParameters();
        return launch(accountReadJob, businessParameters);
    }

    /**
     * :purpose: Launch the card read-and-print job (``cardReadJob``) on the
     *     asynchronous launcher, writing the default report file
     *     ({@value #DEFAULT_CARD_REPORT_FILE}) into the configured batch output root.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCardRead() throws JobExecutionException {
        return launchCardRead(DEFAULT_CARD_REPORT_FILE);
    }

    /**
     * :purpose: Launch ``cardReadJob`` with an explicit report file.
     * :param outputFile: the ``outputFile`` job parameter; a bare file name resolved
     *     inside the configured batch root by ``BatchOutputPathResolver``.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCardRead(String outputFile) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY, outputFile)
                .toJobParameters();
        return launch(cardReadJob, businessParameters);
    }

    /**
     * :purpose: Launch the card cross-reference read-and-print job
     *     (``cardXrefReadJob``) on the asynchronous launcher, writing the default
     *     report file ({@value #DEFAULT_CARD_XREF_REPORT_FILE}) into the configured
     *     batch output root.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCardXrefRead() throws JobExecutionException {
        return launchCardXrefRead(DEFAULT_CARD_XREF_REPORT_FILE);
    }

    /**
     * :purpose: Launch ``cardXrefReadJob`` with an explicit report file.
     * :param outputFile: the ``outputFile`` job parameter; a bare file name resolved
     *     inside the configured batch root by ``BatchOutputPathResolver``.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCardXrefRead(String outputFile) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY, outputFile)
                .toJobParameters();
        return launch(cardXrefReadJob, businessParameters);
    }

    /**
     * :purpose: Launch the customer read-and-print job (``customerReadJob``) on the
     *     asynchronous launcher, writing the default report file
     *     ({@value #DEFAULT_CUSTOMER_REPORT_FILE}) into the configured batch output
     *     root.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCustomerRead() throws JobExecutionException {
        return launchCustomerRead(DEFAULT_CUSTOMER_REPORT_FILE);
    }

    /**
     * :purpose: Launch ``customerReadJob`` with an explicit report file.
     * :param outputFile: the ``outputFile`` job parameter; a bare file name resolved
     *     inside the configured batch root by ``BatchOutputPathResolver``.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCustomerRead(String outputFile) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY, outputFile)
                .toJobParameters();
        return launch(customerReadJob, businessParameters);
    }

    /**
     * :purpose: Launch the transaction combine job (``combineTransactionsJob``) on
     *     the asynchronous launcher, writing the default combined file
     *     ({@value #DEFAULT_COMBINED_TRANSACTION_FILE}) into the configured batch
     *     output root.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCombineTransactions() throws JobExecutionException {
        return launchCombineTransactions(DEFAULT_COMBINED_TRANSACTION_FILE);
    }

    /**
     * :purpose: Launch ``combineTransactionsJob`` with an explicit combined output file.
     * :param outputFile: the ``outputFile`` job parameter; a bare file name resolved
     *     inside the configured batch root by ``BatchOutputPathResolver``.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the launcher cannot start the job.
     */
    public JobExecution launchCombineTransactions(String outputFile) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY, outputFile)
                .toJobParameters();
        return launch(combineTransactionsJob, businessParameters);
    }
}
