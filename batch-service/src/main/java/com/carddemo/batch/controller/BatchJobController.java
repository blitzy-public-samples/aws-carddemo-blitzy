/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.batch.controller;

import com.carddemo.batch.config.JobSchedulingConfig;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.RecordNotFoundException;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * :purpose: On-demand launch surface for the nine ``batch-service`` Spring Batch
 *  jobs. It is the Java analogue of the legacy JES submission path: the online
 *  ``CORPT00C`` program built a JCL skeleton and wrote it to the extra-partition
 *  transient data queue ``'JOBS'`` for the internal reader, which started the job
 *  and returned control immediately [app/cbl/CORPT00C.cbl]. This controller
 *  reproduces that fire-and-forget contract - it submits the job on the
 *  asynchronous {@code asyncJobLauncher} and answers HTTP 202 with the resulting
 *  execution identity rather than waiting for completion.
 * :output: Three endpoints under ``/batch``:
 *  ``GET /batch/jobs`` (the launchable job catalogue and each job's accepted
 *  parameters), ``POST /batch/jobs/{jobName}`` (submit; HTTP 202 with the
 *  execution identity) and ``GET /batch/jobs/executions/{executionId}``
 *  (execution status, exit code and step summary).
 * :note: Without this surface the nine ``Job`` beans of
 *  {@link JobSchedulingConfig} were unreachable at runtime: nothing in the service
 *  declared a controller or a scheduler, so no ``spring_batch_*`` metric labelled
 *  ``job="batch-service"`` was ever produced and the six batch panels of the
 *  Grafana dashboard could only read "No data" (QA Issue 14).
 * :note: Authorization is enforced at the API gateway, whose filter chain gates
 *  ``/batch/**`` to ``ROLE_USER`` or ``ROLE_ADMIN``, and this service's port is
 *  never published outside the private network. That matches both the legacy model
 *  - the CICS resource definitions set ``RESSEC(NO)``/``CMDSEC(NO)`` and gate in
 *  application logic (AAP 0.6.7), and main-menu option 9 "Transaction Reports" is
 *  a ``userType`` ``U`` option - and the deployment model of the other five
 *  business services, none of which carries its own filter chain.
 */
@RestController
@RequestMapping("/batch/jobs")
public class BatchJobController {

    /** :purpose: Launch coordinator owning the nine ``launch*`` methods. */
    private final JobSchedulingConfig jobLaunchers;

    /**
     * :purpose: Read-side access to job executions for the status endpoint. Spring
     *  Batch 6's {@link JobRepository} extends ``JobExplorer``, so the repository the
     *  launcher writes through is also the read model, and no separate explorer bean
     *  is required.
     */
    private final JobRepository jobRepository;

    /**
     * :purpose: The launchable job catalogue: job name to the ordered list of
     *  parameter names the job accepts. Every parameter is optional - each job
     *  falls back to the default {@link JobSchedulingConfig} carries verbatim from
     *  the legacy job stream, whether that is a data-set name (``DEFAULT_*_FILE``)
     *  or a business date hard-coded on an ``EXEC``/``SYMNAMES`` card
     *  (``DEFAULT_PARM_DATE``, ``DEFAULT_REPORT_START_DATE``,
     *  ``DEFAULT_REPORT_END_DATE``) - so a bare ``POST`` with no body launches a
     *  runnable job that reproduces the submitted mainframe job exactly. A
     *  {@link LinkedHashMap} backs it so the catalogue is rendered in a stable,
     *  documented order rather than a hash order that can change between JVM runs.
     */
    private static final Map<String, List<String>> JOB_CATALOGUE = buildJobCatalogue();

    /**
     * :purpose: Assemble the launchable job catalogue in declaration order.
     * :returns: an unmodifiable, insertion-ordered map of job name to accepted
     *  parameter names.
     */
    private static Map<String, List<String>> buildJobCatalogue() {
        LinkedHashMap<String, List<String>> catalogue = new LinkedHashMap<>();
        catalogue.put("interestCalculationJob", List.of("parmDate"));
        catalogue.put("accountReadJob", List.of("outputFile"));
        catalogue.put("cardReadJob", List.of("outputFile"));
        catalogue.put("cardXrefReadJob", List.of("outputFile"));
        catalogue.put("customerReadJob", List.of("outputFile"));
        catalogue.put("dailyTransactionValidationJob", List.of("inputFile"));
        catalogue.put("categoryBalanceReportJob", List.of("outputFile"));
        catalogue.put("transactionDetailReportJob", List.of("startDate", "endDate", "reportFile"));
        catalogue.put("combineTransactionsJob", List.of("outputFile"));
        return Collections.unmodifiableMap(catalogue);
    }

    /**
     * :purpose: Verbatim message for a request naming a job this service does not
     *  own; surfaced as the HTTP 404 body by the shared exception handler.
     */
    private static final String MSG_UNKNOWN_JOB = "Unknown batch job: ";

    /**
     * :purpose: Message for a submission the launcher rejected (for example a
     *  duplicate running instance); surfaced as the HTTP 400 body.
     */
    private static final String MSG_LAUNCH_FAILED = "Unable to submit batch job: ";

    /**
     * :purpose: Verbatim message for a poll naming an execution id no
     *  ``BATCH_JOB_EXECUTION`` row carries; surfaced as the HTTP 404 body.
     */
    private static final String MSG_UNKNOWN_EXECUTION = "Unknown batch job execution: ";

    /**
     * :purpose: Construct the controller with its launch coordinator and the batch
     *  execution read model.
     * :param jobLaunchers: the configuration exposing one ``launch*`` method per job.
     * :param jobRepository: the batch repository, used here as the read model for
     *  {@link JobExecution} records.
     */
    public BatchJobController(JobSchedulingConfig jobLaunchers, JobRepository jobRepository) {
        this.jobLaunchers = jobLaunchers;
        this.jobRepository = jobRepository;
    }

    /**
     * :purpose: List the launchable jobs and the parameters each accepts, so an
     *  operator (or the SPA) can discover the surface without reading the source.
     * :returns: a map of job name to its accepted parameter names (HTTP 200).
     */
    @GetMapping
    public Map<String, List<String>> listJobs() {
        return JOB_CATALOGUE;
    }

    /**
     * :purpose: Submit a batch job asynchronously, mirroring the legacy TDQ/JES
     *  hand-off: the response is returned as soon as the job is accepted, not when
     *  it finishes.
     * :param jobName: one of the nine names returned by {@link #listJobs()}.
     * :param parameters: optional job parameters; any name from the catalogue entry
     *  for the job. Omitted values fall back to the job's documented default.
     * :returns: the submitted execution's identity and initial status (HTTP 202).
     * :raises RecordNotFoundException: when the job name is not one this service
     *  owns (mapped to HTTP 404 by the shared ``GlobalExceptionHandler``).
     * :raises CardDemoException: when the launcher rejects the submission (mapped
     *  to HTTP 400).
     */
    @PostMapping("/{jobName}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> launchJob(@PathVariable("jobName") String jobName,
                                         @RequestBody(required = false) Map<String, String> parameters) {
        if (!JOB_CATALOGUE.containsKey(jobName)) {
            throw new RecordNotFoundException(MSG_UNKNOWN_JOB + jobName);
        }
        Map<String, String> params = parameters == null ? Map.of() : parameters;
        try {
            JobExecution execution = submit(jobName, params);
            return describe(execution);
        } catch (JobExecutionException e) {
            throw new CardDemoException(MSG_LAUNCH_FAILED + jobName, e);
        }
    }

    /**
     * :purpose: Report the current state of a previously submitted execution so a
     *  caller can poll a fire-and-forget submission to completion.
     * :param executionId: the ``jobExecutionId`` returned by
     *  {@link #launchJob(String, Map)}.
     * :returns: the execution's status, exit code, timestamps and per-step summary
     *  (HTTP 200).
     * :raises RecordNotFoundException: when no execution carries that id (HTTP 404).
     */
    @GetMapping("/executions/{executionId}")
    public Map<String, Object> jobExecution(@PathVariable("executionId") Long executionId) {
        JobExecution execution;
        try {
            execution = jobRepository.getJobExecution(executionId);
        } catch (EmptyResultDataAccessException e) {
            // The JDBC-backed repository resolves the owning job instance with a
            // single-row query, so an id with no BATCH_JOB_EXECUTION row surfaces as
            // an empty result rather than a null return. Both spellings of "not
            // found" must answer 404, never the 500 a raw data-access failure gets.
            throw new RecordNotFoundException(MSG_UNKNOWN_EXECUTION + executionId, e);
        }
        if (execution == null) {
            throw new RecordNotFoundException(MSG_UNKNOWN_EXECUTION + executionId);
        }
        return describe(execution);
    }

    /**
     * :purpose: Dispatch to the ``launch*`` method that owns the named job,
     *  selecting the parameterized overload when the caller supplied the relevant
     *  parameter and the defaulted overload otherwise.
     * :param jobName: the validated job name.
     * :param params: the caller-supplied parameters (never ``null``).
     * :returns: the {@link JobExecution} returned by the asynchronous launcher.
     * :raises JobExecutionException: propagated from the launcher.
     */
    private JobExecution submit(String jobName, Map<String, String> params)
            throws JobExecutionException {
        String outputFile = trimToNull(params.get("outputFile"));
        return switch (jobName) {
            case "interestCalculationJob" -> {
                String parmDate = trimToNull(params.get("parmDate"));
                yield parmDate == null
                        ? jobLaunchers.launchInterestCalculation()
                        : jobLaunchers.launchInterestCalculation(parmDate);
            }
            case "accountReadJob" -> outputFile == null
                    ? jobLaunchers.launchAccountRead()
                    : jobLaunchers.launchAccountRead(outputFile);
            case "cardReadJob" -> outputFile == null
                    ? jobLaunchers.launchCardRead()
                    : jobLaunchers.launchCardRead(outputFile);
            case "cardXrefReadJob" -> outputFile == null
                    ? jobLaunchers.launchCardXrefRead()
                    : jobLaunchers.launchCardXrefRead(outputFile);
            case "customerReadJob" -> outputFile == null
                    ? jobLaunchers.launchCustomerRead()
                    : jobLaunchers.launchCustomerRead(outputFile);
            case "combineTransactionsJob" -> outputFile == null
                    ? jobLaunchers.launchCombineTransactions()
                    : jobLaunchers.launchCombineTransactions(outputFile);
            case "categoryBalanceReportJob" -> outputFile == null
                    ? jobLaunchers.launchCategoryBalanceReport()
                    : jobLaunchers.launchCategoryBalanceReport(outputFile);
            case "dailyTransactionValidationJob" -> {
                String inputFile = trimToNull(params.get("inputFile"));
                yield inputFile == null
                        ? jobLaunchers.launchDailyTransactionValidation()
                        : jobLaunchers.launchDailyTransactionValidation(inputFile);
            }
            case "transactionDetailReportJob" -> {
                String startDate = trimToNull(params.get("startDate"));
                String endDate = trimToNull(params.get("endDate"));
                String reportFile = trimToNull(params.get("reportFile"));
                String from = startDate == null
                        ? JobSchedulingConfig.DEFAULT_REPORT_START_DATE : startDate;
                String to = endDate == null
                        ? JobSchedulingConfig.DEFAULT_REPORT_END_DATE : endDate;
                yield reportFile == null
                        ? jobLaunchers.launchTransactionDetailReport(from, to)
                        : jobLaunchers.launchTransactionDetailReport(from, to, reportFile);
            }
            // Unreachable: the catalogue membership check precedes this switch.
            default -> throw new RecordNotFoundException(MSG_UNKNOWN_JOB + jobName);
        };
    }

    /**
     * :purpose: Render a {@link JobExecution} as the JSON response body, exposing
     *  the identity a caller polls with plus the status fields the operator needs.
     * :param execution: the execution to describe.
     * :returns: an ordered, null-free map of execution attributes.
     */
    private Map<String, Object> describe(JobExecution execution) {
        List<Map<String, Object>> steps = execution.getStepExecutions().stream()
                .map(step -> Map.<String, Object>of(
                        "stepName", step.getStepName(),
                        "status", step.getStatus().toString(),
                        "readCount", step.getReadCount(),
                        "writeCount", step.getWriteCount(),
                        "skipCount", step.getSkipCount()))
                .toList();
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("jobName", execution.getJobInstance().getJobName());
        body.put("jobInstanceId", execution.getJobInstance().getInstanceId());
        body.put("jobExecutionId", execution.getId());
        body.put("status", execution.getStatus().toString());
        body.put("exitCode", execution.getExitStatus().getExitCode());
        body.put("exitDescription", execution.getExitStatus().getExitDescription());
        body.put("startTime", String.valueOf(execution.getStartTime()));
        body.put("endTime", String.valueOf(execution.getEndTime()));
        body.put("steps", steps);
        return body;
    }

    /**
     * :purpose: Normalize an optional parameter so a blank string is treated as
     *  absent and therefore selects the job's default.
     * :param value: the raw parameter value (may be ``null``).
     * :returns: the trimmed value, or ``null`` when absent or blank.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
