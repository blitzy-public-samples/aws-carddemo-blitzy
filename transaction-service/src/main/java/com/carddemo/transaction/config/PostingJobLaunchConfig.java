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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * :purpose: Give the daily transaction-posting job stream (``app/jcl/POSTTRAN.jcl`` +
 *     ``CBTRN02C``, AAP 0.4.4) the operator entry point the mainframe had. It exposes
 *     an asynchronous {@link JobLauncher} and one ``launch`` method that assembles the
 *     job parameters and submits ``transactionPostingJob``, which is the Java analogue
 *     of an operator submitting POSTTRAN to JES; the job then runs on a bounded
 *     ``posting-`` thread while the submission returns a durable execution handle.
 * :output: The ``postingJobLauncher`` {@link JobLauncher} bean and
 *     {@link #launchTransactionPosting(String)}, which returns the submitted
 *     {@link JobExecution}.
 * :note: Before this configuration existed the job had NO launch surface in any
 *     deployable artefact (QA Issue 2): ``spring.batch.job.enabled`` is false, no
 *     scheduler, runner or endpoint referenced the job, and batch-service's launch
 *     surface can only launch the jobs defined in its own context. The single most
 *     business-critical batch job of the migration was therefore reachable only from
 *     tests. The scheduled (unattended) route is
 *     ``k8s/cronjob-transaction-posting.yaml``, which runs this service's image with
 *     ``spring.batch.job.enabled=true`` so Spring Boot's ``JobLauncherApplicationRunner``
 *     executes the same job bean and the pod exits with its outcome.
 * :note: ``@EnableBatchProcessing`` is intentionally absent so Spring Boot's batch
 *     auto-configuration stays active; only the asynchronous launcher is built here,
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

    /** Asynchronous launcher used by {@link #launchTransactionPosting(String)}. */
    private final JobLauncher postingJobLauncher;

    /** The daily transaction-posting job (``POSTTRAN`` / ``CBTRN02C``). */
    private final Job transactionPostingJob;

    /**
     * :purpose: Build the asynchronous launcher over the batch job repository and
     *     capture the posting job bean.
     * :param jobRepository: batch job repository (Spring Boot auto-configured), wired
     *     into the launcher so every run is recorded in the durable ``BATCH_*`` tables.
     * :param transactionPostingJob: the ``transactionPostingJob`` bean.
     */
    public PostingJobLaunchConfig(JobRepository jobRepository,
                                  @Qualifier("transactionPostingJob") Job transactionPostingJob) {
        // Built here (not constructor-injected) because this class also defines the
        // postingJobLauncher bean; injecting it would be a self-referential cycle.
        this.postingJobLauncher = buildAsyncJobLauncher(jobRepository);
        this.transactionPostingJob = transactionPostingJob;
    }

    /**
     * :purpose: Construct a {@link TaskExecutorJobLauncher} bound to the batch job
     *     repository and a {@link SimpleAsyncTaskExecutor} whose threads are named
     *     ``posting-``, so a submitted run executes off the request thread.
     * :param jobRepository: batch job repository the launcher records executions in.
     * :returns: a fully initialized asynchronous {@link JobLauncher}.
     */
    private static JobLauncher buildAsyncJobLauncher(JobRepository jobRepository) {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("posting-");
        // Carry the submitting request's observation/trace scope and correlation id onto
        // the worker thread: this executor is built by hand, so Boot's automatic
        // application of the shared TaskDecorator bean does not reach it, and without the
        // decorator every log line and span produced by the run would be orphaned from
        // the request that launched it (AAP 0.7.5).
        taskExecutor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        taskExecutor.setConcurrencyLimit(MAX_CONCURRENT_JOBS);
        launcher.setTaskExecutor(taskExecutor);
        try {
            // afterPropertiesSet() declares a checked Exception; a failure here means the
            // launcher can never run the job, so fail fast at startup.
            launcher.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to initialize postingJobLauncher", ex);
        }
        return launcher;
    }

    /**
     * :purpose: Expose the asynchronous launcher built in the constructor as the
     *     ``postingJobLauncher`` bean, kept distinct by name from the synchronous
     *     launcher supplied by Spring Boot batch auto-configuration.
     * :returns: the {@link JobLauncher} that runs the posting job on ``posting-`` threads.
     */
    @Bean("postingJobLauncher")
    public JobLauncher postingJobLauncher() {
        return this.postingJobLauncher;
    }

    /**
     * :purpose: Submit the daily transaction-posting job for one business date.
     * :param postingDate: the posting cycle's business date in ``YYYY-MM-DD`` form;
     *     when ``null`` or blank the current date is used, so an operator submission
     *     that names no date posts today's feed exactly as the daily job stream did.
     * :returns: the {@link JobExecution} returned by the asynchronous launcher.
     * :raises JobExecutionException: if the launcher refuses the submission — an
     *     already-running instance, an already-completed instance (the day's feed has
     *     been posted) or a restart violation — so a refused submission is never
     *     reported as an accepted one.
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
            return this.postingJobLauncher.run(transactionPostingJob, parameters);
        } finally {
            if (previous == null || previous.isBlank()) {
                CorrelationIdContext.clear();
            }
        }
    }

    /**
     * :purpose: Resolve the business date of a submission, defaulting to the current
     *     date so an unattended or parameterless submission still carries the
     *     identifying date the daily cycle needs.
     * :param postingDate: the requested date, possibly ``null`` or blank.
     * :returns: the effective ``YYYY-MM-DD`` posting date.
     */
    private static String effectivePostingDate(String postingDate) {
        if (postingDate == null || postingDate.isBlank()) {
            return LocalDate.now().format(POSTING_DATE_FORMAT);
        }
        return postingDate.trim();
    }
}
