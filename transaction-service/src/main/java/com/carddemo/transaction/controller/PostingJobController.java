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
package com.carddemo.transaction.controller;

import com.carddemo.common.batch.BatchExitMessageSanitizer;
import com.carddemo.common.batch.BatchLaunchRequestGuard;
import com.carddemo.common.dto.BatchJobExecutionDto;
import com.carddemo.common.exception.CardDemoException;
import jakarta.servlet.http.HttpServletRequest;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.transaction.config.PostingJobLaunchConfig;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * :purpose: Operator entry point for the daily transaction-posting job stream
 *     (``app/jcl/POSTTRAN.jcl`` + ``CBTRN02C``, AAP 0.4.4). It re-platforms the legacy
 *     operator submission of POSTTRAN to JES as an HTTP submission handled by {@link
 *     PostingJobLaunchConfig}, and exposes the durable execution handle so the outcome of an
 *     asynchronous run can be followed to completion or failure — the same contract
 *     batch-service publishes for its nine job streams.
 * :output: ``POST /transactions/batch/jobs/transactionPostingJob`` (202 Accepted with the
 *     execution handle), ``GET /transactions/batch/jobs/executions/{id}`` (the persisted
 *     execution) and ``GET /transactions/batch/jobs`` (the launchable job names). Every route
 *     sits under ``/transactions``, which the API gateway already routes to this service and
 *     role-gates for ``ROLE_USER`` / ``ROLE_ADMIN``, and this service's own filter chain
 *     requires one of those authorities as well.
 * :note: Submission is synchronous while execution stays asynchronous: a refused
 *     submission (an already-running instance, or a business date whose feed has already been
 *     posted) is raised to the caller instead of being reported as an accepted run, matching
 *     the legacy submit-and-return semantics without ever signalling a false success.
 */
@RestController
@RequestMapping("/transactions/batch")
public class PostingJobController {

    /**
     * :purpose: The job this service can launch, given verbatim as the job bean name so
     *     the wire contract carries no invented identifier.
     */
    private static final String TRANSACTION_POSTING_JOB = "transactionPostingJob";

    private static final Logger LOGGER = LoggerFactory.getLogger(PostingJobController.class);

    /** On-demand launcher owning the posting job's parameter contract. */
    private final PostingJobLaunchConfig postingJobLaunchConfig;

    /** Durable batch metadata store consulted for execution status. */
    private final JobRepository jobRepository;

    /**
     * :purpose: Construct the controller over the launcher and the metadata store.
     * :param postingJobLaunchConfig: the on-demand posting-job launcher.
     * :param jobRepository: the durable batch metadata store.
     */
    public PostingJobController(PostingJobLaunchConfig postingJobLaunchConfig,
                                JobRepository jobRepository) {
        this.postingJobLaunchConfig = postingJobLaunchConfig;
        this.jobRepository = jobRepository;
    }

    /**
     * :purpose: List the job names this service can launch.
     * :returns: the launchable job names, verbatim as their bean names.
     */
    @GetMapping("/jobs")
    public List<String> listJobs() {
        return List.of(TRANSACTION_POSTING_JOB);
    }

    /**
     * :purpose: Submit the daily transaction-posting job for one business date, re-platforming
     *     the operator submission of ``POSTTRAN.jcl``.
     * :param jobName: the job bean name; only ``transactionPostingJob`` is launchable here, so
     *     any other name is refused rather than silently ignored.
     * :param postingDate: business date of the posting cycle in ``YYYY-MM-DD`` form; when
     *     absent the current date is used. It is the job's identifying parameter, so a completed
     *     date cannot be posted twice while an interrupted run for that date restarts where it
     *     stopped.
     * :param request: the current servlet request, consulted only to refuse a body this
     *     endpoint does not read (see {@link BatchLaunchRequestGuard}).
     * :returns: the accepted execution's durable handle and current status.
     * :raises CardDemoException: when the job name is unknown or the submission is refused, so
     *     a rejected submission never reads as an accepted one.
     */
    @PostMapping("/jobs/{jobName}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BatchJobExecutionDto launchJob(@PathVariable String jobName,
                                          @RequestParam(required = false) String postingDate,
                                          HttpServletRequest request) {
        BatchLaunchRequestGuard.requireNoRequestBody(request);
        if (!TRANSACTION_POSTING_JOB.equals(jobName)) {
            throw new CardDemoException("Unknown batch job: " + jobName);
        }
        LOGGER.info("Batch launch requested for job {}", jobName);
        try {
            JobExecution execution = postingJobLaunchConfig.launchTransactionPosting(postingDate);
            return toDto(jobName, execution);
        } catch (CardDemoException e) {
            throw e;
        } catch (JobInstanceAlreadyCompleteException e) {
            // A resubmission of a completed instance is an expected outcome of the launch
            // contract, not an internal fault. The framework's own message publishes the
            // whole JobParameters map and its class name; a stable domain sentence says
            // the same thing without handing the caller the internals.
            LOGGER.info("Batch launch refused for job {}: the instance for these parameters has"
                    + " already completed", jobName);
            throw new CardDemoException("Batch job " + jobName + " has already completed for these"
                    + " parameters. A completed run cannot be repeated: change a parameter to run"
                    + " a new instance.", e);
        } catch (JobExecutionAlreadyRunningException e) {
            LOGGER.info("Batch launch refused for job {}: an execution for these parameters is"
                    + " already running", jobName);
            throw new CardDemoException("Batch job " + jobName + " is already running for these"
                    + " parameters. Wait for that execution to finish before submitting again.", e);
        } catch (JobRestartException e) {
            LOGGER.info("Batch launch refused for job {}: the instance cannot be restarted", jobName);
            throw new CardDemoException("Batch job " + jobName + " cannot be restarted for these"
                    + " parameters.", e);
        } catch (Exception e) {
            // Anything else: unusable parameters, or a launcher that could not start.
            // Raising synchronously is the point: the caller must not be told a rejected
            // submission was accepted.
            LOGGER.error("Batch launch rejected for job {}: {}", jobName, e.getMessage());
            throw new CardDemoException("Unable to submit batch job " + jobName
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * :purpose: Report the outcome of a previously launched run, so an asynchronous
     *     submission can be followed to completion or failure.
     * :param jobExecutionId: the durable execution identifier returned at launch.
     * :returns: the execution's identity, status, exit code and exit description.
     * :raises RecordNotFoundException: when no execution carries that identifier.
     */
    @GetMapping("/jobs/executions/{jobExecutionId}")
    public BatchJobExecutionDto getExecution(@PathVariable long jobExecutionId) {
        JobExecution execution;
        try {
            execution = jobRepository.getJobExecution(jobExecutionId);
        } catch (EmptyResultDataAccessException e) {
            // The JDBC-backed repository resolves the owning job instance with a
            // single-row query, so an id with no BATCH_JOB_EXECUTION row surfaces as an
            // empty result rather than a null return. Both spellings of "not found"
            // must answer 404, never the 500 a raw data-access failure gets.
            throw new RecordNotFoundException(
                    "No batch job execution found for id " + jobExecutionId, e);
        }
        if (execution == null) {
            throw new RecordNotFoundException("No batch job execution found for id " + jobExecutionId);
        }
        String jobName = execution.getJobInstance() == null
                ? "" : execution.getJobInstance().getJobName();
        if (!TRANSACTION_POSTING_JOB.equals(jobName)) {
            // BATCH_JOB_EXECUTION is SHARED by every service in this deployment, so a
            // bare id lookup answered for executions this service does not own. An
            // execution of another service's job is not found HERE.
            throw new RecordNotFoundException(
                    "No batch job execution found for id " + jobExecutionId);
        }
        return toDto(jobName, execution);
    }

    /**
     * :purpose: Project a {@link JobExecution} onto the wire contract.
     * :param jobName: the job's name.
     * :param execution: the execution to project.
     * :returns: the populated {@link BatchJobExecutionDto}.
     * :note: The exit description passes through {@link BatchExitMessageSanitizer}: Spring
     *  Batch records the stack trace of the cause there for a failed run, and publishing it
     *  handed the caller the exception type, the generated SQL and the framework frames. The
     *  legacy-visible ``Return code 4: N transaction(s) rejected`` tally still passes through
     *  unchanged.
     */
    private BatchJobExecutionDto toDto(String jobName, JobExecution execution) {
        return new BatchJobExecutionDto(
                jobName,
                execution.getId(),
                execution.getJobInstance() == null ? null : execution.getJobInstance().getInstanceId(),
                execution.getStatus() == null ? null : execution.getStatus().name(),
                execution.getExitStatus() == null ? null : execution.getExitStatus().getExitCode(),
                execution.getExitStatus() == null
                        ? null
                        : BatchExitMessageSanitizer.sanitize(execution.getExitStatus().getExitDescription()));
    }
}
