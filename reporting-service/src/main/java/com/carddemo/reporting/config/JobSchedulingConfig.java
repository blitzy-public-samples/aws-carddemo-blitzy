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
import com.carddemo.common.batch.BatchOutputPathResolver;
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

import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.UUID;
import java.util.concurrent.Semaphore;

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



    /**
     * :purpose: Statement runs that may EXECUTE at once. One, deliberately: every run
     *  writes the two output names of the legacy job stream (the ``STMTFILE`` and
     *  ``HTMLFILE`` DD names), so two overlapping runs opened the same two files. That
     *  produced ``ItemStreamException: Unable to create file`` for the loser, and the
     *  failed run's output-cleanup listener then deleted a concurrently SUCCEEDING run's
     *  statements after the API had already answered ``202``. Serializing the runs keeps
     *  the frozen output names byte-faithful — the alternative, per-execution file names,
     *  would break the ``CREASTMT`` DD-name contract [app/jcl/CREASTMT.JCL].
     */
    private static final int MAX_RUNNING_JOBS = 1;

    /**
     * :purpose: Submissions that may WAIT behind the running one. The legacy initiator
     *  bounded concurrent job streams the same way; beyond this the submission is refused
     *  with the frozen TDQ message rather than queued without limit.
     */
    private static final int MAX_QUEUED_JOBS = 4;

    /**
     * :purpose: Total submissions admitted at once: the running one plus the bounded
     *  backlog. Sized to the executor's own capacity so the pool itself can never reject a
     *  task the admission check already accepted.
     */
    private static final int MAX_ADMITTED_JOBS = MAX_RUNNING_JOBS + MAX_QUEUED_JOBS;

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
     * Resolver that confines an output name to the configured batch output root. Used to
     * VALIDATE a caller-supplied file name synchronously, before a run is submitted.
     */
    private final BatchOutputPathResolver pathResolver;

    /**
     * :purpose: Admission permits for the launch executor: one per running job plus the
     *  bounded backlog. A permit is taken before the job is submitted and released when the
     *  run ends, so a submission that cannot be admitted is REFUSED immediately instead of
     *  parking the request thread.
     * :note: ``null`` when the scheduler was constructed over a caller-supplied operator,
     *  whose admission policy belongs to that caller.
     */
    private final Semaphore admissions;

    /**
     * :purpose: Construct the scheduler with a bounded asynchronous operator and the
     *  statement-generation job resolved by bean name.
     * :param jobRepository: batch job repository the operator records executions in.
     * :param statementGenerationJob: the ``statementGenerationJob`` batch job bean.
     * :param statementTextFile: configured default plain-text statement output name.
     * :param statementHtmlFile: configured default HTML statement output name.
     * :param pathResolver: resolver used to validate a caller-supplied output name against
     *  the configured batch output root before a run is submitted.
     */
    @Autowired
    public JobSchedulingConfig(JobRepository jobRepository,
                               @Qualifier("statementGenerationJob") Job statementGenerationJob,
                               @Value("${carddemo.batch.statement-text-file:statements.txt}")
                               String statementTextFile,
                               @Value("${carddemo.batch.statement-html-file:statements.html}")
                               String statementHtmlFile,
                               BatchOutputPathResolver pathResolver) {
        this.admissions = new Semaphore(MAX_ADMITTED_JOBS);
        this.jobOperator = buildAsyncJobOperator(jobRepository, this.admissions);
        this.statementGenerationJob = statementGenerationJob;
        this.statementTextFile = statementTextFile;
        this.statementHtmlFile = statementHtmlFile;
        this.pathResolver = pathResolver;
    }

    /**
     * :purpose: Construct the scheduler over an explicitly supplied operator, so a
     *  caller (or a test) can control how submitted runs are executed.
     * :param jobOperator: the operator submitted runs are handed to.
     * :param statementGenerationJob: the ``statementGenerationJob`` batch job bean.
     * :param statementTextFile: configured default plain-text statement output name.
     * :param statementHtmlFile: configured default HTML statement output name.
     * :param pathResolver: resolver used to validate a caller-supplied output name against
     *  the configured batch output root before a run is submitted.
     * :note: No admission bound is applied on this path: the supplied operator owns how
     *  its runs are executed, so imposing a second bound here would silently refuse
     *  submissions the caller's own executor was ready to accept.
     */
    public JobSchedulingConfig(JobOperator jobOperator,
                               Job statementGenerationJob,
                               String statementTextFile,
                               String statementHtmlFile,
                               BatchOutputPathResolver pathResolver) {
        this.jobOperator = jobOperator;
        this.statementGenerationJob = statementGenerationJob;
        this.statementTextFile = statementTextFile;
        this.statementHtmlFile = statementHtmlFile;
        this.pathResolver = pathResolver;
        this.admissions = null;
    }

    /**
     * :purpose: Construct a {@link TaskExecutorJobOperator} bound to the batch job repository
     *     and a bounded {@link ThreadPoolTaskExecutor} whose threads are named ``statement-N``.
     *     The executor is decorated so the submitting request's correlation id follows the job
     *     onto its worker thread and so the run's admission permit is returned when it ends.
     * :param jobRepository: batch job repository the operator records executions in.
     * :param admissions: permits governing how many submissions may be in flight.
     * :returns: a fully initialized asynchronous {@link JobOperator}.
     * :raises IllegalStateException: if the operator cannot be initialized, which would leave
     *     the statement job with no reachable submission surface.
     * :note: A ``SimpleAsyncTaskExecutor`` with ``setConcurrencyLimit`` was used before. Its
     *     limit is a THROTTLE, not a queue: ``execute`` BLOCKS the calling thread until a slot
     *     frees, so a burst of submissions parked the HTTP request threads for tens of seconds
     *     (measured p95 27.6 s, max 40.5 s) and neither returned promptly nor refused. A pooled
     *     executor with a bounded queue never blocks the submitter, and the admission permits turn
     *     an over-capacity submission into an immediate, frozen refusal.
     */
    private static JobOperator buildAsyncJobOperator(JobRepository jobRepository, Semaphore admissions) {
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setThreadNamePrefix("statement-");
        taskExecutor.setCorePoolSize(MAX_RUNNING_JOBS);
        taskExecutor.setMaxPoolSize(MAX_RUNNING_JOBS);
        taskExecutor.setQueueCapacity(MAX_QUEUED_JOBS);
        // The queue is sized to the admission permits, so a task that passed admission can
        // never be rejected by the pool; the default AbortPolicy therefore only ever fires
        // if that invariant is broken, and is left in place as the fail-loud guard.
        taskExecutor.setTaskDecorator(releasingDecorator(admissions));
        taskExecutor.initialize();
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
     * :purpose: Build the executor's task decorator: propagate the submitting request's
     *  correlation id onto the worker thread, then return the run's admission permit once
     *  the run has ended whatever its outcome.
     * :param admissions: the permits to return.
     * :returns: the composed {@link TaskDecorator}.
     * :note: The release happens on the WORKER thread in a ``finally`` block, so a permit
     *  is held for exactly as long as the run occupies the executor. Releasing at
     *  submission time instead would make the bound meaningless, and releasing from the
     *  request thread would release it before the run had even started.
     */
    private static TaskDecorator releasingDecorator(Semaphore admissions) {
        TaskDecorator correlationIdDecorator = new CorrelationIdTaskDecorator();
        return runnable -> {
            Runnable correlated = correlationIdDecorator.decorate(runnable);
            return () -> {
                try {
                    correlated.run();
                } finally {
                    admissions.release();
                }
            };
        };
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
     * :note: Both file names are always passed as job parameters and resolved through
     *  the shared batch output resolver, so neither can fall back to a
     *  working-directory-relative path on the container's read-only root.
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
                .addString(PARAM_STMT_FILE,
                        validatedFile(effectiveFile(stmtFile, statementTextFile), PARAM_STMT_FILE))
                .addString(PARAM_HTML_FILE,
                        validatedFile(effectiveFile(htmlFile, statementHtmlFile), PARAM_HTML_FILE))
                // IDENTIFYING: statement generation only reads business data and rewrites
                // its own two output files, so it is a repeatable print request. With a
                // non-identifying id the report window became the instance key and a
                // statement run could be requested exactly once per window -- never again
                // [app/cbl/CBSTM03A.CBL, app/jcl/CREASTMT.jcl].
                .addString(PARAM_RUN_ID, UUID.randomUUID().toString(), true)
                .toJobParameters();

        LOGGER.info("Submitting statementGenerationJob (stmtFile={}, htmlFile={})",
                jobParameters.getString(PARAM_STMT_FILE), jobParameters.getString(PARAM_HTML_FILE));

        // Admission control BEFORE the job instance is created, so an over-capacity
        // submission leaves no execution record and is answered with the same frozen
        // message the legacy screen showed when the TDQ write failed. The legacy
        // initiator refused a submission it had no capacity for; it never held the
        // terminal.
        if (!admit()) {
            LOGGER.warn("Refusing statementGenerationJob submission: {} submission(s) already in flight",
                    MAX_ADMITTED_JOBS);
            throw new CardDemoException(SUBMIT_FAILURE_MESSAGE);
        }

        JobExecution jobExecution;
        try {
            jobExecution = jobOperator.start(statementGenerationJob, jobParameters);
        } catch (JobExecutionAlreadyRunningException | JobRestartException
                 | JobInstanceAlreadyCompleteException | InvalidJobParametersException e) {
            // The run never reached the executor, so its permit must be returned here: the
            // decorator that normally releases it only runs for a task that was submitted.
            release();
            throw new CardDemoException(SUBMIT_FAILURE_MESSAGE, e);
        } catch (RuntimeException e) {
            release();
            throw e;
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
     * :purpose: Refuse an output name that does not resolve inside the configured batch output
     *     root, SYNCHRONOUSLY, before a job instance is created.
     * :param fileName: the effective output name.
     * :param parameterName: the job-parameter name, quoted in the refusal.
     * :returns: the same name once it is proven resolvable.
     * :raises CardDemoException: when the name escapes the root, names a directory, or cannot
     *     be created — reported to the caller as a domain refusal.
     * :note: The containment rule itself was already enforced, but only later, inside the
     *     step-scoped writer factory. The caller therefore received ``202 ACCEPTED`` and the run
     *     then failed with ``BeanCreationException: Error creating bean with name
     *     'scopedTarget.statementCleanupListener' ...`` and a full stack trace persisted into
     *     ``BATCH_JOB_EXECUTION.exit_message`` — internal Spring plumbing as the operator-visible
     *     outcome of a bad parameter, and a spurious execution row for a run that could never have
     *     produced a statement. Validating here refuses it the way every other launch refusal is
     *     refused, and writes nothing to the batch metadata.
     * :note: The resolver's own message is surfaced because it names the rejected VALUE and
     *     nothing internal, so an operator can see which parameter was wrong.
     */
    private String validatedFile(String fileName, String parameterName) {
        try {
            pathResolver.resolveOutput(fileName);
            return fileName;
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) {
            LOGGER.error("Refusing statementGenerationJob submission: {} is not usable ({})",
                    parameterName, e.getMessage());
            throw new CardDemoException(e.getMessage(), e);
        }
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

    /**
     * :purpose: Take one admission permit for a submission about to be made.
     * :returns: ``true`` when the submission may proceed; ``false`` when the running job
     *  plus the bounded backlog are already full.
     * :note: Always ``true`` when the scheduler was constructed over a caller-supplied
     *  operator, which owns its own admission policy.
     */
    private boolean admit() {
        return admissions == null || admissions.tryAcquire();
    }

    /**
     * :purpose: Return an admission permit taken for a submission that never reached the
     *  executor.
     */
    private void release() {
        if (admissions != null) {
            admissions.release();
        }
    }

}
