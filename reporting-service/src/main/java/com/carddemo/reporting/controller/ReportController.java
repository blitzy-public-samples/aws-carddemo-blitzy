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
package com.carddemo.reporting.controller;

import com.carddemo.common.batch.BatchExitMessageSanitizer;
import com.carddemo.common.batch.BatchLaunchRequestGuard;
import jakarta.servlet.http.HttpServletRequest;
import com.carddemo.common.dto.BatchJobExecutionDto;
import com.carddemo.common.dto.ReportRequestDto;
import com.carddemo.common.dto.ReportResponseDto;
import com.carddemo.reporting.config.JobSchedulingConfig;
import com.carddemo.reporting.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for the CardDemo reporting microservice.
 *
 * :purpose: Expose ``POST /reports`` (CICS transaction ``CR00``, legacy program
 *     ``CORPT00C``) as the stateless HTTP boundary of the reporting-service.
 *     Report-type resolution, date-range validation, the confirmation gate, and
 *     asynchronous report/statement job launch are delegated in full to
 *     {@link ReportService}; the controller itself holds no conversational
 *     state and performs no business logic.
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reportService;

    /** Launcher of the ``CREASTMT`` / ``CBSTM03A`` statement-generation job stream. */
    private final JobSchedulingConfig jobSchedulingConfig;

    /**
     * :purpose: Construct the controller with its collaborating report service and the
     *     statement job launcher.
     * :param reportService: the report application service that performs all
     *     report-request business logic (validation, confirmation gate, and
     *     asynchronous job launch).
     * :param jobSchedulingConfig: launcher of the statement-generation job stream.
     */
    public ReportController(ReportService reportService, JobSchedulingConfig jobSchedulingConfig) {
        this.reportService = reportService;
        this.jobSchedulingConfig = jobSchedulingConfig;
    }

    /**
     * :purpose: Submit a transaction-report request (CICS ``CR00``, legacy program
     *     ``CORPT00C``). Report-type resolution, date validation, the confirmation gate, and
     *     asynchronous job launch are delegated to {@link ReportService}.
     * :param request: the report-request payload (report-type selector, optional custom
     *     start/end date parts, and the confirmation flag).
     * :returns: the report-response DTO carrying the confirmation prompt, a validation
     *     message, or the submission acknowledgement (HTTP 200).
     * :raises com.carddemo.common.exception.CardDemoException: when the asynchronous report
     *     job cannot be launched; mapped to HTTP 400 by the shared ``GlobalExceptionHandler`` and
     *     therefore not caught here.
     * :raises org.springframework.web.bind.MethodArgumentNotValidException: when a submitted
     *     field exceeds the CORPT00 screen field width declared on {@link ReportRequestDto};
     *     mapped to HTTP 400 by the shared ``GlobalExceptionHandler``.
     */
    @PostMapping
    public ReportResponseDto requestReport(@Valid @RequestBody ReportRequestDto request) {
        return reportService.requestReport(request);
    }

    /**
     * :purpose: Submit the ``CREASTMT`` statement-generation job stream (legacy ``CBSTM03A``),
     *     which produces the plain-text and HTML account statements. It is the entry point for the
     *     statement path, kept distinct from the ``CORPT00C`` report request that submits the
     *     transaction-detail report; without it the statement job stream would have no caller at
     *     all.
     * :param stmtFile: optional plain-text statement output file name (the ``STMTFILE`` DD
     *     name); the configured default is used when omitted.
     * :param htmlFile: optional HTML statement output file name (the ``HTMLFILE`` DD name);
     *     the configured default is used when omitted.
     * :returns: the accepted run's durable execution handle (HTTP 202).
     * :raises com.carddemo.common.exception.CardDemoException: when the job cannot be
     *     submitted; mapped by the shared ``GlobalExceptionHandler``.
     * :note: The endpoint takes no report type and no date window. ``CREASTMT`` carries no
     *     ``PARM``: its SORT step re-keys the entire ``TRANSACT`` file by card number and
     *     transaction id with no date filter, so ``CBSTM03A`` always statements a card's full
     *     history [app/jcl/CREASTMT.JCL]. A mandatory ``reportType``/``startDate``/``endDate``
     *     triple used to be accepted and recorded as identifying job parameters even though
     *     nothing in the job could read them, which told the caller a windowed statement had been
     *     produced and keyed duplicate detection on values that could not change the output.
     *     Callers that still send them are unaffected: unknown query parameters are ignored.
     */
    @PostMapping("/statements")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BatchJobExecutionDto generateStatements(
            @RequestParam(required = false) String stmtFile,
            @RequestParam(required = false) String htmlFile,
            HttpServletRequest request) {
        BatchLaunchRequestGuard.requireNoRequestBody(request);
        JobExecution execution = jobSchedulingConfig.launchStatementGeneration(stmtFile, htmlFile);
        // The exit description passes through BatchExitMessageSanitizer: Spring Batch records
        // the stack trace of the cause there for a failed run, and publishing it verbatim
        // handed the caller the exception type, the generated SQL and the framework frames.
        return new BatchJobExecutionDto(
                "statementGenerationJob",
                execution.getId(),
                execution.getJobInstance() == null ? null : execution.getJobInstance().getInstanceId(),
                execution.getStatus() == null ? null : execution.getStatus().name(),
                execution.getExitStatus() == null ? null : execution.getExitStatus().getExitCode(),
                execution.getExitStatus() == null
                        ? null
                        : BatchExitMessageSanitizer.sanitize(execution.getExitStatus().getExitDescription()));
    }
}
