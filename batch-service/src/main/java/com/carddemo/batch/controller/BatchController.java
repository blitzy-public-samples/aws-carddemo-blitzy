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
package com.carddemo.batch.controller;

import com.carddemo.batch.config.JobSchedulingConfig;
import com.carddemo.common.batch.BatchExitMessageSanitizer;
import com.carddemo.common.dto.BatchJobExecutionDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.RecordNotFoundException;
import org.springframework.dao.EmptyResultDataAccessException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * :purpose: Expose the batch job streams as an on-demand launch surface, re-platforming
 *  the legacy operator submission of the ``JCL`` job streams through the CICS transient
 *  data queue ``'JOBS'`` and the JES internal reader. Without it the configured jobs -
 *  including the ``CBTRN03C`` / ``TRANREPT`` transaction-detail report that a
 *  ``CORPT00C`` report request must submit - had no reachable runtime entry point at
 *  all, and every request under ``/batch`` answered ``404``.
 * :output: ``GET /batch/jobs`` listing the launchable job names, ``POST
 *  /batch/jobs/{jobName}`` launching one job and acknowledging it with a durable
 *  execution handle, and ``GET /batch/jobs/executions/{jobExecutionId}`` reporting the
 *  outcome of a previously launched run.
 * :note: The launch is non-blocking, mirroring the legacy asynchronous submission: the
 *  response acknowledges acceptance while the run proceeds on the bounded launch
 *  executor. Submission failures - unusable parameters, an instance already running, an
 *  instance already complete - are raised synchronously so a caller never mistakes a
 *  rejected submission for an accepted one. The outcome of an accepted run is read back
 *  from the execution-status endpoint.
 */
@RestController
@RequestMapping("/batch")
public class BatchController {

    private static final Logger LOGGER = LoggerFactory.getLogger(BatchController.class);

    /**
     * Launchable job names, given verbatim as the job bean names so the wire contract
     * carries no invented identifiers.
     */
    private static final List<String> LAUNCHABLE_JOBS = List.of(
            "interestCalculationJob",
            "transactionDetailReportJob",
            "categoryBalanceReportJob",
            "dailyTransactionValidationJob",
            "accountReadJob",
            "cardReadJob",
            "cardXrefReadJob",
            "customerReadJob",
            "combineTransactionsJob");

    /** On-demand launcher owning the job parameters and the bounded launch executor. */
    private final JobSchedulingConfig jobSchedulingConfig;

    /** Durable batch metadata store consulted for execution status. */
    private final JobRepository jobRepository;

    /**
     * :purpose: Construct the controller over the launcher and the metadata store.
     * :param jobSchedulingConfig: the on-demand job launcher.
     * :param jobRepository: the durable batch metadata store.
     */
    public BatchController(JobSchedulingConfig jobSchedulingConfig, JobRepository jobRepository) {
        this.jobSchedulingConfig = jobSchedulingConfig;
        this.jobRepository = jobRepository;
    }

    /**
     * :purpose: List the job names this service can launch.
     * :returns: the launchable job names in declaration order.
     */
    @GetMapping("/jobs")
    public List<String> listJobs() {
        return LAUNCHABLE_JOBS;
    }

    /**
     * :purpose: Launch one job by name, passing through the parameters that job requires
     *  and letting the launcher apply its configured defaults for the rest.
     * :param jobName: the job bean name, one of {@link #LAUNCHABLE_JOBS}.
     * :param parmDate: business date for ``interestCalculationJob``
     *  (``INTCALC`` ``PARM``); ignored by the other jobs.
     * :param startDate: inclusive start of the reporting window for
     *  ``transactionDetailReportJob``.
     * :param endDate: inclusive end of the reporting window for
     *  ``transactionDetailReportJob``.
     * :param file: explicit output file name, or input feed name for
     *  ``dailyTransactionValidationJob``; when absent the launcher's configured default
     *  for that job is used.
     * :returns: the accepted execution's durable handle and current status.
     * :raises CardDemoException: when the job name is unknown or the submission is
     *  rejected, so a rejected submission never reads as an accepted one.
     */
    @PostMapping("/jobs/{jobName}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BatchJobExecutionDto launchJob(@PathVariable String jobName,
                                          @RequestParam(required = false) String parmDate,
                                          @RequestParam(required = false) String startDate,
                                          @RequestParam(required = false) String endDate,
                                          @RequestParam(required = false) String file) {
        LOGGER.info("Batch launch requested for job {}", jobName);
        try {
            JobExecution execution = dispatch(jobName, parmDate, startDate, endDate, file);
            return toDto(jobName, execution);
        } catch (CardDemoException e) {
            throw e;
        } catch (Exception e) {
            // Covers an already-running instance, an already-complete instance, a
            // restart violation and unusable parameters. Raising synchronously is the
            // point: the caller must not be told a rejected submission was accepted.
            LOGGER.error("Batch launch rejected for job {}: {}", jobName, e.getMessage());
            throw new CardDemoException("Unable to submit batch job " + jobName
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * :purpose: Report the outcome of a previously launched run, so an asynchronous
     *  submission can be followed to completion or failure.
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
            // empty result rather than a null return. Both spellings of "not found" must
            // answer 404, never the 500 a raw data-access failure gets.
            throw new RecordNotFoundException(
                    "No batch job execution found for id " + jobExecutionId, e);
        }
        if (execution == null) {
            throw new RecordNotFoundException("No batch job execution found for id " + jobExecutionId);
        }
        String jobName = execution.getJobInstance() == null
                ? "" : execution.getJobInstance().getJobName();
        return toDto(jobName, execution);
    }

    /**
     * :purpose: Treat a blank request parameter as absent, so a submission that carries an
     *  empty value still runs on the JCL default rather than on an empty file name or date.
     * :param value: the raw request-parameter value.
     * :returns: the trimmed value, or ``null`` when it carries no content.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * :purpose: Route a launch request to the launcher method that owns the job's
     *  parameter contract.
     * :param jobName: the job bean name.
     * :param parmDate: interest-calculation business date.
     * :param startDate: report window start.
     * :param endDate: report window end.
     * :param file: explicit output or input file name, possibly null.
     * :returns: the accepted {@link JobExecution}.
     * :raises Exception: when the launcher rejects the submission.
     * :raises CardDemoException: when the job name is not launchable.
     */
    private JobExecution dispatch(String jobName, String parmDate, String startDate,
                                  String endDate, String file) throws Exception {
        parmDate = trimToNull(parmDate);
        startDate = trimToNull(startDate);
        endDate = trimToNull(endDate);
        file = trimToNull(file);
        return switch (jobName) {
            case "interestCalculationJob" -> parmDate == null
                    ? jobSchedulingConfig.launchInterestCalculation()
                    : jobSchedulingConfig.launchInterestCalculation(parmDate);
            case "transactionDetailReportJob" -> {
                if (startDate == null || endDate == null) {
                    // Neither window bound was supplied, so the JCL PARM defaults apply.
                    yield jobSchedulingConfig.launchTransactionDetailReport();
                }
                yield file == null
                        ? jobSchedulingConfig.launchTransactionDetailReport(startDate, endDate)
                        : jobSchedulingConfig.launchTransactionDetailReport(startDate, endDate, file);
            }
            case "categoryBalanceReportJob" -> file == null
                    ? jobSchedulingConfig.launchCategoryBalanceReport()
                    : jobSchedulingConfig.launchCategoryBalanceReport(file);
            case "dailyTransactionValidationJob" -> file == null
                    ? jobSchedulingConfig.launchDailyTransactionValidation()
                    : jobSchedulingConfig.launchDailyTransactionValidation(file);
            case "accountReadJob" -> file == null
                    ? jobSchedulingConfig.launchAccountRead()
                    : jobSchedulingConfig.launchAccountRead(file);
            case "cardReadJob" -> file == null
                    ? jobSchedulingConfig.launchCardRead()
                    : jobSchedulingConfig.launchCardRead(file);
            case "cardXrefReadJob" -> file == null
                    ? jobSchedulingConfig.launchCardXrefRead()
                    : jobSchedulingConfig.launchCardXrefRead(file);
            case "customerReadJob" -> file == null
                    ? jobSchedulingConfig.launchCustomerRead()
                    : jobSchedulingConfig.launchCustomerRead(file);
            case "combineTransactionsJob" -> file == null
                    ? jobSchedulingConfig.launchCombineTransactions()
                    : jobSchedulingConfig.launchCombineTransactions(file);
            default -> throw new CardDemoException("Unknown batch job: " + jobName);
        };
    }

    /**
     * :purpose: Project a {@link JobExecution} onto the wire contract.
     * :param jobName: the job's name.
     * :param execution: the execution to project.
     * :returns: the populated {@link BatchJobExecutionDto}.
     * :note: The exit description passes through {@link BatchExitMessageSanitizer}: Spring
     *  Batch records the stack trace of the cause there for a failed run, and publishing it
     *  handed the caller the exception type, the generated SQL and the framework frames.
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
