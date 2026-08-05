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

import com.carddemo.common.config.CorrelationIdTaskDecorator;
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
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import org.springframework.core.task.SimpleAsyncTaskExecutor;
import java.util.UUID;

/**
 * :purpose: Re-platforms the legacy CICS asynchronous batch-submission mechanism
 *  of the online report program ``CORPT00C`` (the ``SUBMIT-JOB-TO-INTRDR`` and
 *  ``WIRTE-JOBSUB-TDQ`` paragraphs, which wrote 80-byte JCL records to the
 *  extra-partition Transient Data Queue ``'JOBS'`` for the JES internal reader to
 *  run asynchronously). In the target architecture the submission becomes an
 *  on-demand, non-blocking {@link org.springframework.batch.core.launch.JobOperator}
 *  invocation of the sibling ``statementGenerationJob`` batch job.
 * :output: Exposes a single parameterized, asynchronous launch entry point that
 *  the reporting-service statement endpoint calls to submit the
 *  statement-generation job with the output file names of the run; submission
 *  failures are surfaced as a {@link CardDemoException}.
 */
@Configuration
public class JobSchedulingConfig {

    /** :purpose: Logger for asynchronous statement-generation job submission. */
    private static final Logger LOGGER = LoggerFactory.getLogger(JobSchedulingConfig.class);

    /**
     * :purpose: Job-parameter key carrying a per-submission unique token, recorded as an
     *  IDENTIFYING parameter so every statement request is a distinct ``JobInstance``.
     *  Statement generation only reads business data and rewrites its own two output
     *  files, so it is a repeatable print request: ``CORPT00C`` wrote a TDQ ``'JOBS'``
     *  record on EVERY request and JES ran the job again. With the token
     *  non-identifying, the remaining business parameters became the instance key and a
     *  statement could be printed exactly once per key - the second identical request
     *  was refused as already complete. The business parameter set is the two output
     *  file names alone - the ``STMTFILE`` and ``HTMLFILE`` DD names of the legacy job
     *  stream - because ``CREASTMT`` carries no ``PARM``.
     */
    private static final String PARAM_RUN_ID = "run.id";

    /** :purpose: Job-parameter key carrying the plain-text statement output name. */
    private static final String PARAM_STMT_FILE = "stmtFile";

    /** :purpose: Job-parameter key carrying the HTML statement output name. */
    private static final String PARAM_HTML_FILE = "htmlFile";

    /** :purpose: Frozen operator-visible message emitted when the submission fails. */
    public static final String SUBMIT_FAILURE_MESSAGE = "Unable to Write TDQ (JOBS)...";



    /** :purpose: Upper bound on jobs running concurrently on the launch executor. */
    private static final int MAX_CONCURRENT_JOBS = 4;

    /**
     * :purpose: Asynchronous operator used to submit the job. Submission is synchronous
     *  - an unusable parameter set, an instance already running or already complete is
     *  raised to the caller - while the accepted run proceeds on a bounded executor.
     */
    private final JobOperator jobOperator;

    /** :purpose: The statement-generation job launched on each statement request. */
    private final Job statementGenerationJob;

    /** :purpose: Configured default plain-text statement output file name. */
    private final String statementTextFile;

    /** :purpose: Configured default HTML statement output file name. */
    private final String statementHtmlFile;

    /**
     * :purpose: Construct the scheduler with a bounded asynchronous operator and the
     *  statement-generation job resolved by bean name.
     * :param jobRepository: batch job repository the operator records executions in.
     * :param statementGenerationJob: the ``statementGenerationJob`` batch job bean.
     * :param statementTextFile: configured default plain-text statement output name.
     * :param statementHtmlFile: configured default HTML statement output name.
     */
    @Autowired
    public JobSchedulingConfig(JobRepository jobRepository,
                               @Qualifier("statementGenerationJob") Job statementGenerationJob,
                               @Value("${carddemo.batch.statement-text-file:statements.txt}")
                               String statementTextFile,
                               @Value("${carddemo.batch.statement-html-file:statements.html}")
                               String statementHtmlFile) {
        this(buildAsyncJobOperator(jobRepository), statementGenerationJob,
                statementTextFile, statementHtmlFile);
    }

    /**
     * :purpose: Construct the scheduler over an explicitly supplied operator, so a
     *  caller (or a test) can control how submitted runs are executed.
     * :param jobOperator: the operator submitted runs are handed to.
     * :param statementGenerationJob: the ``statementGenerationJob`` batch job bean.
     * :param statementTextFile: configured default plain-text statement output name.
     * :param statementHtmlFile: configured default HTML statement output name.
     */
    public JobSchedulingConfig(JobOperator jobOperator,
                               Job statementGenerationJob,
                               String statementTextFile,
                               String statementHtmlFile) {
        this.jobOperator = jobOperator;
        this.statementGenerationJob = statementGenerationJob;
        this.statementTextFile = statementTextFile;
        this.statementHtmlFile = statementHtmlFile;
    }

    /**
     * :purpose: Construct a {@link TaskExecutorJobOperator} bound to the batch job
     *  repository and a bounded {@link SimpleAsyncTaskExecutor} whose threads are named
     *  ``statement-N``. The executor is decorated so the submitting request's correlation
     *  id follows the job onto its worker thread.
     * :param jobRepository: batch job repository the operator records executions in.
     * :returns: a fully initialized asynchronous {@link JobOperator}.
     * :raises IllegalStateException: if the operator cannot be initialized, which would
     *  leave the statement job with no reachable submission surface.
     */
    private static JobOperator buildAsyncJobOperator(JobRepository jobRepository) {
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("statement-");
        taskExecutor.setTaskDecorator(new CorrelationIdTaskDecorator());
        // SimpleAsyncTaskExecutor pools no threads, so without a limit each submission
        // spawns a new thread and a burst of launches can exhaust memory.
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
            operator.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize the statement job operator", ex);
        }
        return operator;
    }

    /**
     * :purpose: Launch the statement-generation batch job asynchronously over the
     *  configured default output file names, re-platforming ``CORPT00C``'s TDQ
     *  ``'JOBS'`` internal-reader submission as a non-blocking {@link JobOperator}
     *  invocation.
     * :return: the accepted run's {@link JobExecution}, carrying its durable execution
     *  id so the outcome can be followed.
     * :raises CardDemoException: when the job cannot be submitted to the operator. The
     *  submission is synchronous precisely so this failure reaches the caller: the method
     *  was previously ``@Async`` on a ``@Configuration`` class and returned a
     *  ``CompletableFuture`` that the caller discarded, so neither a refused submission
     *  nor a failed run could ever be observed.
     */
    public JobExecution launchStatementGeneration() {
        return launchStatementGeneration(null, null);
    }

    /**
     * :purpose: Launch the statement-generation job with explicit output file names.
     * :param stmtFile: plain-text statement output file name; the configured default is
     *  used when blank.
     * :param htmlFile: HTML statement output file name; the configured default is used
     *  when blank.
     * :return: the accepted run's {@link JobExecution}.
     * :raises CardDemoException: when the job cannot be submitted to the operator.
     * :note: Both file names are always passed as job parameters. They were previously
     *  omitted entirely, so the writer fell back to the working-directory-relative
     *  ``output/statements.txt``, which is unwritable in the delivered read-only
     *  container; the writer now resolves every name through the shared batch output
     *  resolver.
     * :note: The two file names are the whole BUSINESS parameter set. A
     *  ``reportType``/``startDate``/``endDate`` triple used to be recorded as
     *  identifying, but ``CREASTMT`` carries no ``PARM`` at all and its SORT step
     *  re-keys the entire ``TRANSACT`` file with no date filter, so those values could
     *  never influence the statement a run produced: they told the caller a windowed
     *  statement had been generated and keyed duplicate detection on values that had no
     *  effect [app/jcl/CREASTMT.JCL]. The instance key is therefore the two file names
     *  plus the identifying per-submission ``run.id``, so a repeated print request for
     *  the same destination is accepted and re-run rather than refused as a duplicate.
     */
    public JobExecution launchStatementGeneration(String stmtFile, String htmlFile) {
        JobParameters jobParameters = new JobParametersBuilder()
                .addString(PARAM_STMT_FILE, effectiveFile(stmtFile, statementTextFile))
                .addString(PARAM_HTML_FILE, effectiveFile(htmlFile, statementHtmlFile))
                // IDENTIFYING: statement generation only reads business data and rewrites
                // its own two output files, so it is a repeatable print request. With a
                // non-identifying id the report window became the instance key and a
                // statement run could be requested exactly once per window -- never again
                // [app/cbl/CBSTM03A.CBL, app/jcl/CREASTMT.jcl].
                .addString(PARAM_RUN_ID, UUID.randomUUID().toString(), true)
                .toJobParameters();

        LOGGER.info("Submitting statementGenerationJob (stmtFile={}, htmlFile={})",
                jobParameters.getString(PARAM_STMT_FILE), jobParameters.getString(PARAM_HTML_FILE));

        JobExecution jobExecution;
        try {
            jobExecution = jobOperator.start(statementGenerationJob, jobParameters);
        } catch (JobExecutionAlreadyRunningException | JobRestartException
                 | JobInstanceAlreadyCompleteException | InvalidJobParametersException e) {
            throw new CardDemoException(SUBMIT_FAILURE_MESSAGE, e);
        }

        // The operator returns normally even when the run itself failed, so the
        // exit status is inspected here and an unsuccessful run is surfaced with
        // the same operator-visible message as a failed submission.
        if (jobExecution.getStatus().isUnsuccessful()) {
            LOGGER.error("statementGenerationJob {} ended with status {} (exitCode={}, description={})",
                    jobExecution.getId(), jobExecution.getStatus(),
                    jobExecution.getExitStatus().getExitCode(),
                    jobExecution.getExitStatus().getExitDescription());
            throw new CardDemoException(SUBMIT_FAILURE_MESSAGE);
        }

        LOGGER.info("statementGenerationJob {} ended with status {}",
                jobExecution.getId(), jobExecution.getStatus());
        return jobExecution;
    }

    /**
     * :purpose: Choose the requested file name when supplied, else the configured default.
     * :param requested: the caller-supplied name, possibly null or blank.
     * :param configured: the configured default name.
     * :returns: the effective file name, never blank.
     */
    private static String effectiveFile(String requested, String configured) {
        return (requested == null || requested.isBlank()) ? configured : requested.trim();
    }

}
