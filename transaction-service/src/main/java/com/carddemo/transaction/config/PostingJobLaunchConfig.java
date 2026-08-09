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
package com.carddemo.transaction.config;

import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.util.DateUtil;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * :purpose: Give the daily transaction-posting job stream (``app/jcl/POSTTRAN.jcl`` +
 *     ``CBTRN02C``, AAP 0.4.4) the operator entry point the mainframe had. It exposes
 *     an asynchronous {@link JobOperator} and one ``launch`` method that assembles the
 *     job parameters and submits ``transactionPostingJob``, which is the Java analogue
 *     of an operator submitting POSTTRAN to JES; the job then runs on a bounded
 *     ``posting-`` thread while the submission returns a durable execution handle.
 * :output: The ``postingJobOperator`` {@link JobOperator} bean and
 *     {@link #launchTransactionPosting(String)}, which returns the submitted
 *     {@link JobExecution}.
 * :note: Before this configuration existed the job had NO launch surface in any
 *     deployable artefact: ``spring.batch.job.enabled`` is false, no
 *     scheduler, runner or endpoint referenced the job, and batch-service's launch
 *     surface can only launch the jobs defined in its own context. The single most
 *     business-critical batch job of the migration was therefore reachable only from
 *     tests. The scheduled (unattended) route is
 *     ``k8s/cronjob-transaction-posting.yaml``, which runs this service's image with
 *     ``spring.batch.job.enabled=true`` so Spring Boot's ``JobLauncherApplicationRunner``
 *     executes the same job bean and the pod exits with its outcome.
 * :note: ``@EnableBatchProcessing`` is intentionally absent so Spring Boot's batch
 *     auto-configuration stays active; only the asynchronous operator is built here,
 *     exactly as batch-service does for its nine job streams.
 */
@Configuration
public class PostingJobLaunchConfig {

    /**
     * :purpose: Job-parameter key carrying the business date of the posting cycle.
     *     It is IDENTIFYING, so the day's feed is posted at most once and an
     *     interrupted run restarts as the same ``JobInstance`` instead of
     *     double-posting. ``POSTTRAN.jcl`` carried no ``PARM``; its per-run identity
     *     came from the daily ``DALYTRAN`` delivery and the ``DALYREJS(+1)``
     *     generation-data-group, which this parameter reproduces.
     */
    public static final String POSTING_DATE_KEY = "postingDate";

    /**
     * :purpose: Refusal reported when a submission names a business date that is not a
     *     real calendar date in ``YYYY-MM-DD`` form. It quotes neither the submitted
     *     value nor any internal detail, so a hostile string is never reflected back.
     */
    public static final String INVALID_POSTING_DATE_MESSAGE =
            "Posting date must be a valid date in YYYY-MM-DD format.";

    /**
     * :purpose: Job-parameter key holding the per-launch unique run token, added as a
     *     NON-identifying parameter so job identity derives from the business
     *     parameters alone and a duplicate submission is still refused.
     */
    private static final String RUN_ID_KEY = "run.id";

    /**
     * :purpose: Upper bound on posting runs executing concurrently.
     *     ``SimpleAsyncTaskExecutor`` pools no threads, so an unbounded executor would
     *     start a new thread for every submission; the legacy environment bounded this
     *     naturally through JES initiator classes.
     */
    private static final int MAX_CONCURRENT_JOBS = 2;

    /** ``YYYY-MM-DD`` formatter for the default posting date. */
    private static final DateTimeFormatter POSTING_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final Logger LOGGER = LoggerFactory.getLogger(PostingJobLaunchConfig.class);

    /** Asynchronous operator used by {@link #launchTransactionPosting(String)}. */
    private final JobOperator postingJobOperator;

    /** The daily transaction-posting job (``POSTTRAN`` / ``CBTRN02C``). */
    private final Job transactionPostingJob;

    /**
     * :purpose: Build the asynchronous operator over the batch job repository and
     *     capture the posting job bean.
     * :param jobRepository: batch job repository (Spring Boot auto-configured), wired
     *     into the operator so every run is recorded in the durable ``BATCH_*`` tables.
     * :param transactionPostingJob: the ``transactionPostingJob`` bean.
     */
    public PostingJobLaunchConfig(JobRepository jobRepository,
                                  @Qualifier("transactionPostingJob") Job transactionPostingJob) {
        // Built here (not constructor-injected) because this class also defines the
        // postingJobOperator bean; injecting it would be a self-referential cycle.
        this.postingJobOperator = buildAsyncJobOperator(jobRepository);
        this.transactionPostingJob = transactionPostingJob;
    }

    /**
     * :purpose: Construct a {@link TaskExecutorJobOperator} bound to the batch job
     *     repository and a {@link SimpleAsyncTaskExecutor} whose threads are named
     *     ``posting-``, so a submitted run executes off the request thread.
     * :param jobRepository: batch job repository the operator records executions in.
     * :returns: a fully initialized asynchronous {@link JobOperator}.
     * :raises IllegalStateException: if the operator cannot be initialized, which would
     *     leave the posting job with no reachable launch surface.
     */
    private static JobOperator buildAsyncJobOperator(JobRepository jobRepository) {
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("posting-");
        // Carry the submitting request's observation/trace scope and correlation id onto
        // the worker thread: this executor is built by hand, so Boot's automatic
        // application of the shared TaskDecorator bean does not reach it, and without the
        // decorator every log line and span produced by the run would be orphaned from
        // the request that launched it (AAP 0.7.5).
        taskExecutor.setTaskDecorator(new ContextPropagatingTaskDecorator());
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
            // afterPropertiesSet() declares a checked Exception; a failure here means the
            // operator can never run the job, so fail fast at startup.
            operator.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize postingJobOperator", ex);
        }
        return operator;
    }

    /**
     * :purpose: Expose the asynchronous operator built in the constructor as the
     *     ``postingJobOperator`` bean, kept distinct by name from the synchronous
     *     operator supplied by Spring Boot batch auto-configuration.
     * :returns: the {@link JobOperator} that runs the posting job on ``posting-`` threads.
     */
    @Bean("postingJobOperator")
    public JobOperator postingJobOperator() {
        return this.postingJobOperator;
    }

    /**
     * :purpose: Submit the daily transaction-posting job for one business date.
     * :param postingDate: the posting cycle's business date in ``YYYY-MM-DD`` form;
     *     when ``null`` or blank the current date is used, so an operator submission
     *     that names no date posts today's feed exactly as the daily job stream did.
     * :returns: the {@link JobExecution} returned by the asynchronous operator.
     * :raises JobExecutionException: if the operator refuses the submission — an
     *     already-running instance, an already-completed instance (the day's feed has
     *     been posted) or a restart violation — so a refused submission is never
     *     reported as an accepted one.
     * :raises CardDemoException: when the supplied date is not a real calendar date in
     *     ``YYYY-MM-DD`` form, so it is refused BEFORE a job instance exists.
     */
    public JobExecution launchTransactionPosting(String postingDate) throws JobExecutionException {
        String effectiveDate = effectivePostingDate(postingDate);
        JobParameters businessParameters = new JobParametersBuilder()
                .addString(POSTING_DATE_KEY, effectiveDate)
                .toJobParameters();

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
                    .addString(RUN_ID_KEY, UUID.randomUUID().toString(), false)
                    .toJobParameters();
            LOGGER.info("Submitting transactionPostingJob (postingDate={})", effectiveDate);
            return this.postingJobOperator.start(transactionPostingJob, parameters);
        } finally {
            if (previous == null || previous.isBlank()) {
                CorrelationIdContext.clear();
            }
        }
    }

    /**
     * :purpose: Resolve and VALIDATE the business date of a submission, defaulting to the
     *     current date so an unattended or parameterless submission still carries the
     *     identifying date the daily cycle needs.
     * :param postingDate: the requested date, possibly ``null`` or blank.
     * :returns: the effective ``YYYY-MM-DD`` posting date.
     * :raises CardDemoException: when the value is not a real calendar date in
     *     ``YYYY-MM-DD`` form.
     * :note: ``postingDate`` is the IDENTIFYING job parameter: it is the instance key of a
     *     posting cycle and it is persisted verbatim in ``BATCH_JOB_EXECUTION_PARAMS`` as the
     *     audit record of the run. Unvalidated, any caller could mint unlimited instances
     *     that re-post the same feed under names like ``NOT-A-DATE``, ``2026-13-45``, 600
     *     nines or an injection string, and the batch audit trail carried them for good. The
     *     legacy ``PARM`` was a real date consumed by the program, so it is validated with
     *     the shared ``CSUTLDTC`` replacement — ``DateUtil`` with STRICT resolution, which
     *     rejects ``2026-02-30`` as well as a malformed string [app/cbl/CSUTLDTC.cbl].
     */
    private static String effectivePostingDate(String postingDate) {
        if (postingDate == null || postingDate.isBlank()) {
            return LocalDate.now().format(POSTING_DATE_FORMAT);
        }
        String trimmed = postingDate.trim();
        // The ISO mask must be named explicitly: the single-argument DateUtil.isValid
        // overload validates under MASK_CCYYMMDD ("YYYYMMDD"), which would refuse every
        // hyphenated date this parameter is defined to carry.
        if (!DateUtil.isValid(trimmed, DateUtil.MASK_ISO)) {
            LOGGER.warn("Refusing transactionPostingJob submission: postingDate is not a valid"
                    + " {} date", DateUtil.MASK_ISO);
            throw new CardDemoException(INVALID_POSTING_DATE_MESSAGE);
        }
        return trimmed;
    }
}
