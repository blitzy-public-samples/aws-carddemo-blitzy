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
import com.carddemo.common.exception.CardDemoException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

import java.util.UUID;

/**
 * On-demand launch orchestration for the batch-service Spring Batch jobs.
 *
 * :purpose: Expose an asynchronous {@link JobOperator} bean (named
 *     ``asyncJobOperator``) backed by a {@link SimpleAsyncTaskExecutor}, and one
 *     public ``launch*`` method per batch job that builds the job's parameters
 *     and submits it through that operator. Each submission runs on a separate
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
 *     {@link CorrelationIdContext}, and a non-identifying unique ``run.id``
 *     recorded purely for traceability. Because neither participates in job
 *     identity, a ``JobInstance`` is identified by its business parameters
 *     alone, so a failed instance can be restarted and a duplicate completed
 *     instance is rejected.
 * :output: The ``asyncJobOperator`` {@link JobOperator} bean and nine public
 *     ``launch*`` methods, each returning the submitted job's
 *     {@link JobExecution}. The {@link JobRepository} and the nine ``Job`` beans
 *     are supplied by the shared ``JdbcBatchConfiguration`` (which provides the durable
 *     JDBC ``JobRepository``) and this configuration's collaborators; no batch
 *     infrastructure is self-instantiated beyond the operator.
 * :note: Every ``launch*`` method that drives a file-producing or file-consuming job
 *     accepts an optional explicit path and otherwise falls back to a configured
 *     default, and validates it through {@link BatchOutputPathResolver} *before*
 *     submitting, so a path fault is answered to the caller rather than surfacing
 *     asynchronously after a ``STARTING`` ``JobExecution`` has been handed back.
 * :note: ``REPROC.prc`` — the generic IDCAMS REPRO VSAM unload/load utility
 *     invoked by ``TRANREPT.prc`` ``STEP01R`` [app/proc/REPROC.prc,
 *     app/proc/TRANREPT.prc] — is a database backup / point-in-time-recovery
 *     infrastructure concern (AAP 0.4.6) and is not implemented as an application
 *     job here; no launch method corresponds to it.
 */
@Configuration
public class JobSchedulingConfig {

    /**
     * :purpose: The job-parameter key holding a per-launch unique run token, recorded
     *     for traceability as a NON-identifying parameter, so job identity derives
     *     solely from the business parameters — exactly as the legacy JCL identified a
     *     run by its ``PARM`` values. A failed instance is therefore restartable and a
     *     duplicate completed instance is refused.
     */
    private static final String RUN_ID_KEY = "run.id";

    /** :purpose: Job-parameter key for the file a job writes its output to. */
    private static final String OUTPUT_FILE_KEY = "outputFile";

    /** :purpose: Job-parameter key for the file a job reads its input from. */
    private static final String INPUT_FILE_KEY = "inputFile";

    /**
     * :purpose: Upper bound on batch jobs executing concurrently on the async
     *     operator. ``SimpleAsyncTaskExecutor`` pools no threads, so an unbounded
     *     executor would start a new thread for every submission and let a burst of
     *     launches exhaust memory and saturate the connection pool. The legacy
     *     environment bounded this naturally through JES initiator classes.
     */
    private static final int MAX_CONCURRENT_JOBS = 4;




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

    /** Length of the legacy ``YYYYMMDDHH`` interest PARM. */
    private static final int PARM_DATE_LENGTH = 10;

    /** Highest hour the two trailing PARM digits may carry. */
    private static final int MAX_PARM_HOUR = 23;

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
     * :purpose: Asynchronous operator used by every ``launch*`` method; built in
     *     the constructor from the injected {@link JobRepository} and exposed as
     *     the ``asyncJobOperator`` bean.
     */
    private final JobOperator asyncJobOperator;

    /**
     * :purpose: Resolver that confines and validates every batch file path, used here
     *     to reject an unusable path at submission time.
     */
    private final BatchOutputPathResolver pathResolver;

    /** :purpose: Configured default output file name for ``accountReadJob``. */
    private final String accountReportFile;

    /** :purpose: Configured default output file name for ``cardReadJob``. */
    private final String cardReportFile;

    /** :purpose: Configured default output file name for ``cardXrefReadJob``. */
    private final String cardXrefReportFile;

    /** :purpose: Configured default output file name for ``customerReadJob``. */
    private final String customerReportFile;

    /** :purpose: Configured default output file name for ``categoryBalanceReportJob``. */
    private final String categoryBalanceReportFile;

    /** :purpose: Configured default output file name for ``transactionDetailReportJob``. */
    private final String transactionDetailReportFile;

    /** :purpose: Configured default output file name for ``combineTransactionsJob``. */
    private final String combinedTransactionFile;

    /** :purpose: Configured default input file name for ``dailyTransactionValidationJob``. */
    private final String dailyTransactionFile;

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
     * :purpose: Build the asynchronous operator from the batch job repository and capture the
     *     nine job beans so each ``launch*`` method can submit its job.
     * :param jobRepository: batch job repository (Spring Boot auto-configured); wired into the
     *     operator.
     * :param interestCalculationJob: the ``interestCalculationJob`` bean.
     * :param accountReadJob: the ``accountReadJob`` bean.
     * :param cardReadJob: the ``cardReadJob`` bean.
     * :param cardXrefReadJob: the ``cardXrefReadJob`` bean.
     * :param customerReadJob: the ``customerReadJob`` bean.
     * :param dailyTransactionValidationJob: the ``dailyTransactionValidationJob`` bean.
     * :param categoryBalanceReportJob: the ``categoryBalanceReportJob`` bean.
     * :param transactionDetailReportJob: the ``transactionDetailReportJob`` bean.
     * :param combineTransactionsJob: the ``combineTransactionsJob`` bean.
     * :param pathResolver: resolver used to validate a requested batch file path before
     *     submission, so an unusable path is reported to the caller synchronously instead of
     *     failing the job asynchronously.
     * :param accountReportFile: default output file name for ``accountReadJob``.
     * :param cardReportFile: default output file name for ``cardReadJob``.
     * :param cardXrefReportFile: default output file name for ``cardXrefReadJob``.
     * :param customerReportFile: default output file name for ``customerReadJob``.
     * :param categoryBalanceReportFile: default output file name for
     *     ``categoryBalanceReportJob``.
     * :param transactionDetailReportFile: default output file name for
     *     ``transactionDetailReportJob``.
     * :param combinedTransactionFile: default output file name for ``combineTransactionsJob``.
     * :param dailyTransactionFile: default input file name for
     *     ``dailyTransactionValidationJob``.
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
            BatchOutputPathResolver pathResolver,
            @Value("${carddemo.batch.account-report-file:" + DEFAULT_ACCOUNT_REPORT_FILE + "}") String accountReportFile,
            @Value("${carddemo.batch.card-report-file:" + DEFAULT_CARD_REPORT_FILE + "}") String cardReportFile,
            @Value("${carddemo.batch.card-xref-report-file:" + DEFAULT_CARD_XREF_REPORT_FILE + "}") String cardXrefReportFile,
            @Value("${carddemo.batch.customer-report-file:" + DEFAULT_CUSTOMER_REPORT_FILE + "}") String customerReportFile,
            @Value("${carddemo.batch.category-balance-report-file:" + DEFAULT_CATEGORY_BALANCE_REPORT_FILE + "}") String categoryBalanceReportFile,
            @Value("${carddemo.batch.transaction-detail-report-file:" + DEFAULT_TRANSACTION_DETAIL_REPORT_FILE + "}") String transactionDetailReportFile,
            @Value("${carddemo.batch.combined-transaction-file:" + DEFAULT_COMBINED_TRANSACTION_FILE + "}") String combinedTransactionFile,
            @Value("${carddemo.batch.daily-transaction-file:" + DEFAULT_DAILY_TRANSACTION_FEED_FILE + "}") String dailyTransactionFile) {
        // Built here (not constructor-injected) because this class also defines
        // the asyncJobOperator bean; injecting it would be a self-referential cycle.
        this.asyncJobOperator = buildAsyncJobOperator(jobRepository);
        this.interestCalculationJob = interestCalculationJob;
        this.accountReadJob = accountReadJob;
        this.cardReadJob = cardReadJob;
        this.cardXrefReadJob = cardXrefReadJob;
        this.customerReadJob = customerReadJob;
        this.dailyTransactionValidationJob = dailyTransactionValidationJob;
        this.categoryBalanceReportJob = categoryBalanceReportJob;
        this.transactionDetailReportJob = transactionDetailReportJob;
        this.combineTransactionsJob = combineTransactionsJob;
        this.pathResolver = pathResolver;
        this.accountReportFile = accountReportFile;
        this.cardReportFile = cardReportFile;
        this.cardXrefReportFile = cardXrefReportFile;
        this.customerReportFile = customerReportFile;
        this.categoryBalanceReportFile = categoryBalanceReportFile;
        this.transactionDetailReportFile = transactionDetailReportFile;
        this.combinedTransactionFile = combinedTransactionFile;
        this.dailyTransactionFile = dailyTransactionFile;
    }

    /**
     * :purpose: Resolve the effective batch file name for a launch: the caller's
     *     explicit request when supplied, otherwise the configured default. This is
     *     what keeps a parameter-less launch from submitting a job that is certain to
     *     fail on a blank path.
     * :param requested: the caller's requested file name, possibly ``null`` or blank.
     * :param configuredDefault: the configured default file name for this job.
     * :returns: the effective file name to pass as the job parameter.
     */
    private static String effectiveFile(String requested, String configuredDefault) {
        return (requested == null || requested.isBlank()) ? configuredDefault : requested;
    }

    /**
     * :purpose: Validate an output path before submission so an unusable path is
     *     reported to the caller synchronously rather than failing the job on a batch
     *     worker thread where the caller never sees it.
     * :param outputFile: the effective output file name.
     * :returns: the same file name, once proven resolvable within the output root.
     * :raises IllegalArgumentException: when the path is blank or escapes the
     *     configured output root.
     */
    private String validatedOutput(String outputFile) {
        pathResolver.resolveOutput(outputFile);
        return outputFile;
    }

    /**
     * :purpose: Validate an input path before submission, for the same reason as
     *     {@link #validatedOutput(String)}.
     * :param inputFile: the effective input file name.
     * :returns: the same file name, once proven resolvable within the input root.
     * :raises IllegalArgumentException: when the path is blank or escapes the
     *     configured input root.
     */
    private String validatedInput(String inputFile) {
        pathResolver.resolveInput(inputFile);
        return inputFile;
    }

    /**
     * :purpose: Construct a {@link TaskExecutorJobOperator} bound to the batch job
     *     repository and a {@link SimpleAsyncTaskExecutor} whose threads are named
     *     ``batch-N``, so submitted jobs run on their own threads. The executor is
     *     decorated so the launching request's correlation id follows the job onto its
     *     worker thread; without the decorator every line a job logged rendered an
     *     empty ``correlationId`` and could not be tied back to its caller.
     * :param jobRepository: batch job repository the operator records executions in.
     * :returns: a fully initialized asynchronous {@link JobOperator}.
     * :raises IllegalStateException: if the operator cannot be initialized, which would
     *     leave all nine job streams with no reachable launch surface.
     */
    private static JobOperator buildAsyncJobOperator(JobRepository jobRepository) {
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
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
        operator.setTaskExecutor(taskExecutor);
        // TaskExecutorJobOperator requires a non-null job locator to initialize, but this
        // operator is driven exclusively through start(Job, JobParameters), which resolves
        // the job from the instance handed to it and never consults the locator; the
        // name-keyed lookups (getJobNames, restart-by-id) are not part of this component's
        // surface. An empty registry therefore satisfies the initialization contract without
        // asserting a name-to-job mapping the component does not own.
        operator.setJobRegistry(new MapJobRegistry());
        try {
            // afterPropertiesSet() declares a checked Exception; a failure here
            // means the operator can never run a job, so fail fast at startup.
            operator.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize asyncJobOperator", ex);
        }
        return operator;
    }

    /**
     * :purpose: Expose the asynchronous operator built in the constructor as the
     *     ``asyncJobOperator`` bean, kept distinct by name from the synchronous
     *     operator supplied by Spring Boot batch auto-configuration.
     * :returns: the {@link JobOperator} that runs jobs on ``batch-`` threads.
     */
    @Bean("asyncJobOperator")
    public JobOperator asyncJobOperator() {
        return this.asyncJobOperator;
    }

    /**
     * :purpose: Enrich a job's business parameters with observability and uniqueness
     *     parameters, then submit the job on the asynchronous operator. A ``correlationId``
     *     obtained from the static {@link CorrelationIdContext} is added as a non-identifying
     *     parameter (also seeded into the launching thread's MDC), as is a random ``run.id``
     *     recorded purely for traceability. Neither participates in job identity, so a
     *     ``JobInstance`` is identified by its business parameters alone and both restart of a
     *     failed instance and rejection of a duplicate completed instance behave as Spring Batch
     *     intends.
     * :param job: the batch job to submit.
     * :param businessParameters: the job-specific parameters already assembled by the calling
     *     ``launch*`` method.
     * :returns: the {@link JobExecution} returned by the asynchronous operator.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    private JobExecution launch(Job job, JobParameters businessParameters)
            throws JobExecutionException {
        return launch(job, businessParameters, false);
    }

    /**
     * :purpose: Submit a job that only reads business data and (re)writes its own output, so
     *     every submission is a run in its own right. A print or read request is a repeatable
     *     operation on the mainframe: ``CORPT00C`` writes a TDQ 'JOBS' record and JES runs the
     *     stream on EVERY request, so an operator may print the monthly report as often as they
     *     like. Deriving identity from the business parameters alone made the report window itself
     *     the instance key, which let the monthly report be printed once per calendar month -- and
     *     never again -- so the whole workflow became unusable after its first run
     *     [app/cbl/CORPT00C.cbl, app/proc/TRANREPT.prc].
     * :param job: the batch job to submit.
     * :param businessParameters: the parameters assembled by the calling ``launch*`` method.
     * :returns: the {@link JobExecution} returned by the asynchronous operator.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    private JobExecution launchRepeatable(Job job, JobParameters businessParameters)
            throws JobExecutionException {
        return launch(job, businessParameters, true);
    }

    /**
     * :purpose: Enrich the business parameters with the correlation id and the ``run.id``
     *     uniqueness token, then submit the job on the asynchronous operator.
     * :param job: the batch job to submit.
     * :param businessParameters: the parameters assembled by the calling ``launch*`` method.
     * :param repeatable: record ``run.id`` as IDENTIFYING, so a read/print run may be
     *     repeated; ``false`` for a state-changing run, whose identity must derive from
     *     its business parameters alone.
     * :returns: the {@link JobExecution} returned by the asynchronous operator.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    private JobExecution launch(Job job, JobParameters businessParameters, boolean repeatable)
            throws JobExecutionException {
        // Remember whether an id was already in scope: when it was, it belongs to the
        // CorrelationIdFilter, which clears it at the end of the request. When it was
        // not, this method seeded it and must clear it again, because the launching
        // thread is a pooled container thread that would otherwise carry the id into
        // the next, unrelated request handled by that thread.
        String previous = CorrelationIdContext.getCorrelationId();
        String correlationId = CorrelationIdContext.getOrCreateCorrelationId();
        try {
            JobParameters parameters = new JobParametersBuilder(businessParameters)
                    .addString(CorrelationIdContext.CORRELATION_ID_KEY, correlationId, false)
                    // For a state-changing run the id is NON-identifying, so job identity
                    // derives from the business parameters alone and re-submitting an
                    // already-completed instance is refused exactly as JES refused a duplicate
                    // job. For a repeatable read/print run it is IDENTIFYING, so each
                    // submission is its own JobInstance and the run can be repeated -- which is
                    // what the TDQ 'JOBS' hand-off does on every CORPT00C request.
                    .addString(RUN_ID_KEY, UUID.randomUUID().toString(), repeatable)
                    .toJobParameters();
            return this.asyncJobOperator.start(job, parameters);
        } finally {
            if (previous == null || previous.isBlank()) {
                CorrelationIdContext.clear();
            }
        }
    }

    /**
     * :purpose: Launch the monthly interest-calculation job
     *     (``interestCalculationJob``) on the asynchronous operator.
     * :param parmDate: the 10-character ``YYYYMMDDHH`` business date supplied as
     *     the ``parmDate`` job parameter (legacy ``INTCALC.jcl`` ``PARM='2022071800'``).
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchInterestCalculation(String parmDate) throws JobExecutionException {
        requireParmDate(parmDate);
        JobParameters businessParameters = new JobParametersBuilder()
                .addString("parmDate", parmDate)
                .toJobParameters();
        return launch(interestCalculationJob, businessParameters);
    }

    /**
     * :purpose: Reject a transaction-detail report window that is not a valid, ordered pair of
     *     ``YYYY-MM-DD`` dates, BEFORE a JobInstance is created.
     * :param startDate: inclusive range start.
     * :param endDate: inclusive range end.
     * :raises CardDemoException: when either bound is missing or malformed, or the start is
     *     later than the end.
     * :note: Both bounds are required TOGETHER. An inverted window is refused for the same
     *     reason: it silently reports nothing.
     */
    private static void requireReportWindow(String startDate, String endDate) {
        LocalDate start = parseReportDate(startDate, "startDate");
        LocalDate end = parseReportDate(endDate, "endDate");
        if (start.isAfter(end)) {
            throw new CardDemoException("startDate " + startDate + " is after endDate " + endDate
                    + "; the report window must be ordered, as the legacy DATEPARM control card is.");
        }
    }

    /**
     * :purpose: Parse one bound of the report window strictly.
     * :param value: the caller-supplied date.
     * :param name: the parameter name, for the message.
     * :returns: the parsed date.
     * :raises CardDemoException: when the value is absent or not a real ``YYYY-MM-DD`` date.
     */
    private static LocalDate parseReportDate(String value, String name) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new CardDemoException(name + " is required: the transaction detail report takes a"
                    + " startDate and endDate pair in YYYY-MM-DD form, exactly as the legacy"
                    + " DATEPARM control card carries both bounds.");
        }
        try {
            return LocalDate.parse(trimmed,
                    DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT));
        } catch (DateTimeParseException e) {
            throw new CardDemoException(name + " must be a real calendar date in YYYY-MM-DD form"
                    + " (received '" + trimmed + "').");
        }
    }

    /**
     * :purpose: Reject an interest-calculation business date that is not the legacy
     *     ``YYYYMMDDHH`` PARM shape, BEFORE a JobInstance is created or a single interest
     *     transaction is written.
     * :param parmDate: the caller-supplied business date.
     * :raises CardDemoException: when the value is not exactly ten digits, or does not denote
     *     a real calendar date with an hour of 00-23.
     * :note: This is not defensive tidying. The value becomes the first ten characters of
     *     every transaction id the run generates (``TRAN-ID`` is ``PIC X(16)``, filled as
     *     ``parmDate`` + a 6-digit sequence), so ``parmDate=NOTADATE00`` produced transaction ids
     *     such as ``NOTADATE00000001`` - non-numeric values in a field whose frozen contract is 16
     *     digits (AAP 0.6.5) - and the run reported COMPLETED while doing it. An invalid PARM on
     *     the legacy ``EXEC`` card could not reach the file either: the program moved it into a
     *     numeric working-storage field.
     */
    private static void requireParmDate(String parmDate) {
        String value = parmDate == null ? "" : parmDate.trim();
        boolean shaped = value.length() == PARM_DATE_LENGTH;
        if (shaped) {
            for (int i = 0; i < value.length(); i++) {
                if (!Character.isDigit(value.charAt(i))) {
                    shaped = false;
                    break;
                }
            }
        }
        if (shaped) {
            int hour = Integer.parseInt(value.substring(8, 10));
            shaped = hour <= MAX_PARM_HOUR;
            if (shaped) {
                try {
                    // STRICT so 2022-02-30 is refused rather than shifted, matching the
                    // CEEDAYS-based validation DateUtil performs for every other date.
                    LocalDate.parse(value.substring(0, 8),
                            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
                } catch (DateTimeParseException e) {
                    shaped = false;
                }
            }
        }
        if (!shaped) {
            throw new CardDemoException("parmDate must be a 10-character YYYYMMDDHH business date"
                    + " with a real calendar date and an hour of 00-23, as the legacy INTCALC PARM"
                    + " carries (for example " + DEFAULT_PARM_DATE + "); it prefixes every generated"
                    + " transaction id, which must stay 16 digits.");
        }
    }

    /**
     * :purpose: Launch the monthly interest-calculation job over the business date
     *     the legacy job stream hard-codes on its ``EXEC`` card
     *     ({@value #DEFAULT_PARM_DATE}), so a submission that names no date runs
     *     exactly the job the mainframe operator submitted [app/jcl/INTCALC.jcl:L22].
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchInterestCalculation() throws JobExecutionException {
        return launchInterestCalculation(DEFAULT_PARM_DATE);
    }

    /**
     * :purpose: Launch the transaction detail report job
     *     (``transactionDetailReportJob``) on the asynchronous operator; this is
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
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchTransactionDetailReport(String startDate, String endDate, String reportFile)
            throws JobExecutionException {
        requireReportWindow(startDate, endDate);
        JobParameters businessParameters = new JobParametersBuilder()
                .addString("startDate", startDate)
                .addString("endDate", endDate)
                .addString(REPORT_FILE_KEY, reportFile)
                .addString("reportFile",
                        validatedOutput(effectiveFile(reportFile, transactionDetailReportFile)))
                .toJobParameters();
        return launchRepeatable(transactionDetailReportJob, businessParameters);
    }

    /**
     * :purpose: Launch the transaction detail report job over the supplied date
     *     range, writing to the default report file
     *     ({@value #DEFAULT_TRANSACTION_DETAIL_REPORT_FILE}) inside the configured
     *     batch output root.
     * :param startDate: inclusive range start in ``YYYY-MM-DD`` form.
     * :param endDate: inclusive range end in ``YYYY-MM-DD`` form.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
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
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchTransactionDetailReport() throws JobExecutionException {
        return launchTransactionDetailReport(DEFAULT_REPORT_START_DATE, DEFAULT_REPORT_END_DATE);
    }



    /**
     * :purpose: Launch the transaction-category-balance report job
     *     (``categoryBalanceReportJob``) on the asynchronous operator.
     * :param outputFile: the report output path, supplied as the ``outputFile``
     *     job parameter.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchCategoryBalanceReport(String outputFile) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY,
                        validatedOutput(effectiveFile(outputFile, categoryBalanceReportFile)))
                .toJobParameters();
        return launchRepeatable(categoryBalanceReportJob, businessParameters);
    }

    /**
     * :purpose: Launch the transaction-category-balance report job, writing to the
     *     default report file ({@value #DEFAULT_CATEGORY_BALANCE_REPORT_FILE})
     *     inside the configured batch output root.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchCategoryBalanceReport() throws JobExecutionException {
        return launchCategoryBalanceReport(DEFAULT_CATEGORY_BALANCE_REPORT_FILE);
    }


    /**
     * :purpose: Launch the daily-transaction validation-read job
     *     (``dailyTransactionValidationJob``) on the asynchronous operator, reading
     *     the default feed file ({@value #DEFAULT_DAILY_TRANSACTION_FEED_FILE})
     *     from the configured batch input root. The ``inputFile`` parameter is
     *     supplied explicitly because the step's reader binds it through
     *     ``#{jobParameters['inputFile']}`` and previously received ``null``, which
     *     failed the job before it read a single record.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchDailyTransactionValidation() throws JobExecutionException {
        return launchDailyTransactionValidation(null);
    }

    /**
     * :purpose: Launch ``dailyTransactionValidationJob`` writing to (or reading from) an explicit path.
     * :param file: the requested file name; when ``null`` or blank the configured
     *     default is used. The path is validated before submission.
     * :returns: the {@link JobExecution} returned by the asynchronous operator.
     * :throws JobExecutionException: if the operator cannot start the job.
     * :raises IllegalArgumentException: when the resulting path is unusable.
     */
    public JobExecution launchDailyTransactionValidation(String file) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(INPUT_FILE_KEY, validatedInput(effectiveFile(file, dailyTransactionFile)))
                .toJobParameters();
        return launchRepeatable(dailyTransactionValidationJob, businessParameters);
    }

    /**
     * :purpose: Launch the account read-and-print job (``accountReadJob``) on the
     *     asynchronous operator, writing the default report file
     *     ({@value #DEFAULT_ACCOUNT_REPORT_FILE}) into the configured batch output
     *     root. The ``outputFile`` parameter is supplied explicitly because the
     *     step's writer binds it through ``#{jobParameters['outputFile']}`` and
     *     previously received ``null``, which failed the job at step start.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchAccountRead() throws JobExecutionException {
        return launchAccountRead(null);
    }

    /**
     * :purpose: Launch ``accountReadJob`` writing to (or reading from) an explicit path.
     * :param file: the requested file name; when ``null`` or blank the configured
     *     default is used. The path is validated before submission.
     * :returns: the {@link JobExecution} returned by the asynchronous operator.
     * :throws JobExecutionException: if the operator cannot start the job.
     * :raises IllegalArgumentException: when the resulting path is unusable.
     */
    public JobExecution launchAccountRead(String file) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY, validatedOutput(effectiveFile(file, accountReportFile)))
                .toJobParameters();
        return launchRepeatable(accountReadJob, businessParameters);
    }

    /**
     * :purpose: Launch the card read-and-print job (``cardReadJob``) on the
     *     asynchronous operator, writing the default report file
     *     ({@value #DEFAULT_CARD_REPORT_FILE}) into the configured batch output root.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchCardRead() throws JobExecutionException {
        return launchCardRead(null);
    }

    /**
     * :purpose: Launch ``cardReadJob`` writing to (or reading from) an explicit path.
     * :param file: the requested file name; when ``null`` or blank the configured
     *     default is used. The path is validated before submission.
     * :returns: the {@link JobExecution} returned by the asynchronous operator.
     * :throws JobExecutionException: if the operator cannot start the job.
     * :raises IllegalArgumentException: when the resulting path is unusable.
     */
    public JobExecution launchCardRead(String file) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY, validatedOutput(effectiveFile(file, cardReportFile)))
                .toJobParameters();
        return launchRepeatable(cardReadJob, businessParameters);
    }

    /**
     * :purpose: Launch the card cross-reference read-and-print job
     *     (``cardXrefReadJob``) on the asynchronous operator, writing the default
     *     report file ({@value #DEFAULT_CARD_XREF_REPORT_FILE}) into the configured
     *     batch output root.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchCardXrefRead() throws JobExecutionException {
        return launchCardXrefRead(null);
    }

    /**
     * :purpose: Launch ``cardXrefReadJob`` writing to (or reading from) an explicit path.
     * :param file: the requested file name; when ``null`` or blank the configured
     *     default is used. The path is validated before submission.
     * :returns: the {@link JobExecution} returned by the asynchronous operator.
     * :throws JobExecutionException: if the operator cannot start the job.
     * :raises IllegalArgumentException: when the resulting path is unusable.
     */
    public JobExecution launchCardXrefRead(String file) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY, validatedOutput(effectiveFile(file, cardXrefReportFile)))
                .toJobParameters();
        return launchRepeatable(cardXrefReadJob, businessParameters);
    }

    /**
     * :purpose: Launch the customer read-and-print job (``customerReadJob``) on the
     *     asynchronous operator, writing the default report file
     *     ({@value #DEFAULT_CUSTOMER_REPORT_FILE}) into the configured batch output
     *     root.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchCustomerRead() throws JobExecutionException {
        return launchCustomerRead(null);
    }

    /**
     * :purpose: Launch ``customerReadJob`` writing to (or reading from) an explicit path.
     * :param file: the requested file name; when ``null`` or blank the configured
     *     default is used. The path is validated before submission.
     * :returns: the {@link JobExecution} returned by the asynchronous operator.
     * :throws JobExecutionException: if the operator cannot start the job.
     * :raises IllegalArgumentException: when the resulting path is unusable.
     */
    public JobExecution launchCustomerRead(String file) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY, validatedOutput(effectiveFile(file, customerReportFile)))
                .toJobParameters();
        return launchRepeatable(customerReadJob, businessParameters);
    }

    /**
     * :purpose: Launch the transaction combine job (``combineTransactionsJob``) on
     *     the asynchronous operator, writing the default combined file
     *     ({@value #DEFAULT_COMBINED_TRANSACTION_FILE}) into the configured batch
     *     output root.
     * :returns: the {@link JobExecution} of the submitted job.
     * :throws JobExecutionException: if the operator cannot start the job.
     */
    public JobExecution launchCombineTransactions() throws JobExecutionException {
        return launchCombineTransactions(null);
    }

    /**
     * :purpose: Launch ``combineTransactionsJob`` writing to (or reading from) an explicit path.
     * :param file: the requested file name; when ``null`` or blank the configured
     *     default is used. The path is validated before submission.
     * :returns: the {@link JobExecution} returned by the asynchronous operator.
     * :throws JobExecutionException: if the operator cannot start the job.
     * :raises IllegalArgumentException: when the resulting path is unusable.
     */
    public JobExecution launchCombineTransactions(String file) throws JobExecutionException {
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(OUTPUT_FILE_KEY, validatedOutput(effectiveFile(file, combinedTransactionFile)))
                .toJobParameters();
        return launchRepeatable(combineTransactionsJob, businessParameters);
    }
}
