package com.carddemo.controller;

import com.carddemo.dto.report.ReportRequest;
import com.carddemo.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Transaction-report submission REST endpoint &mdash; the stateless replacement for the legacy
 * CICS report-request program {@code app/cbl/CORPT00C.cbl} (TRANID {@code 'CR00'}).
 *
 * <p>The original COBOL program let an online user choose a report period
 * (Monthly / Yearly / Custom), confirm the request, then assembled an 80-byte JCL stream
 * ({@code //STEP10 EXEC PROC=TRANREPT}) and handed it to the mainframe internal reader for
 * asynchronous batch execution by writing each JCL line to the {@code 'JOBS'} extra-partition
 * Transient Data Queue &mdash; {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}
 * ({@code app/cbl/CORPT00C.cbl:L517-L523}, within the {@code SUBMIT-JOB-TO-INTRDR} paragraph
 * {@code L462-L510}). Control returned to the user immediately; the report itself was produced
 * later by the {@code TRANREPT} batch job.</p>
 *
 * <p>In the modernized stack that internal-reader hand-off becomes a single REST operation:</p>
 * <ul>
 *   <li>{@code POST /api/reports} &mdash; submit a report job. The request is validated, delegated
 *       to {@link ReportService}, and the response is {@code 202 Accepted} carrying the launched
 *       {@code jobExecutionId} so the client can track progress (e.g. via
 *       {@code BatchAdminController}). The actual report generation runs asynchronously on the
 *       Spring Batch task executor &mdash; the REST analogue of the fire-and-forget CICS
 *       internal-reader submission (AAP &sect;0.6.1).</li>
 * </ul>
 *
 * <h2>COBOL parity (replaces {@code CORPT00C})</h2>
 * <p>This controller is a thin, stateless HTTP boundary; the business semantics of {@code CORPT00C}
 * are preserved by its collaborators rather than re-implemented here:</p>
 * <ul>
 *   <li><b>Report-type / date-range derivation</b> ({@code CORPT00C.cbl:L213-L443}, the
 *       {@code EVALUATE TRUE} on {@code MONTHLYI}/{@code YEARLYI}/{@code CUSTOMI}) is carried on the
 *       request as a typed {@code ReportType} plus {@code startDate}/{@code endDate} and consumed by
 *       {@link ReportService}, which resolves the registered {@code transactionReportJob} and builds
 *       its {@code JobParameters}.</li>
 *   <li><b>Confirmation gate</b> ({@code SUBMIT-JOB-TO-INTRDR}, {@code CORPT00C.cbl:L464-L494}:
 *       proceed only when {@code CONFIRMI = 'Y' OR 'y'}; cancel on {@code 'N'/'n'}; reject any other
 *       value) is enforced in two layers: {@code ReportRequest}'s {@code @Pattern("[YyNn]")}
 *       constrains the wire format (HTTP 400 on a malformed value), and {@link ReportService} treats
 *       only an affirmative {@code 'Y'/'y'} as a go, rejecting anything else with
 *       {@link IllegalArgumentException}.</li>
 *   <li><b>Job submission</b> ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}, {@code CORPT00C.cbl:L517})
 *       becomes {@code JobLauncher.run(jobRegistry.getJob(...), params)} inside the {@code @Async}
 *       {@link ReportService#submitReport(ReportRequest)} method.</li>
 * </ul>
 *
 * <p>The COBOL on-screen error messages map onto HTTP&nbsp;400 responses produced before any job is
 * launched, almost entirely by Jakarta Bean Validation on {@code ReportRequest}:</p>
 * <table border="1">
 *   <caption>CORPT00C validation message &rarr; modernized outcome</caption>
 *   <tr><th>COBOL behaviour</th><th>HTTP result</th></tr>
 *   <tr><td>"Please indicate the type of report to print&hellip;"</td>
 *       <td>400 ({@code @NotNull reportType})</td></tr>
 *   <tr><td>"Start Date can NOT be empty&hellip;" / "End Date can NOT be empty&hellip;"</td>
 *       <td>400 ({@code @NotNull startDate}/{@code endDate})</td></tr>
 *   <tr><td>"End Date must not be less than Start Date&hellip;"</td>
 *       <td>400 ({@code @AssertTrue} cross-field validator)</td></tr>
 *   <tr><td>"Start Date is not a valid date&hellip;"</td>
 *       <td>400 (ISO date parse failure during deserialization)</td></tr>
 *   <tr><td>"Confirm to submit this report&hellip;" / "Invalid value. Valid values are (Y/N)&hellip;"</td>
 *       <td>400 ({@code @NotBlank}/{@code @Pattern("[YyNn]")} confirmation)</td></tr>
 * </table>
 *
 * <h2>Asynchronous response handling ({@code CompletableFuture})</h2>
 * <p>{@link ReportService#submitReport(ReportRequest)} is annotated {@code @Async} and returns a
 * {@link CompletableFuture}{@code <Long>} completed with the launched {@code jobExecutionId}. This
 * controller therefore returns a {@link CompletableFuture} of its {@link ResponseEntity}: Spring MVC
 * recognizes the reactive return type, releases the servlet container thread while the future is
 * pending, and writes the {@code 202 Accepted} response when the future completes &mdash; preserving
 * the "return to the user immediately" semantics of the original pseudo-conversational program
 * without blocking a request thread.</p>
 *
 * <p>If the service's future completes <em>exceptionally</em> (e.g. {@link IllegalArgumentException}
 * for a non-affirmative confirmation or absent report type, or {@link IllegalStateException} for a
 * launch failure), the {@link CompletableFuture#thenApply(java.util.function.Function) thenApply}
 * mapping is skipped and the exceptional completion is propagated to Spring's asynchronous
 * exception handling, which unwraps the {@link java.util.concurrent.CompletionException} and routes
 * the original cause through {@code GlobalExceptionHandler} (mapping
 * {@link IllegalArgumentException} &rarr; 400 and {@link IllegalStateException} &rarr; 422).</p>
 *
 * <h2>Runtime collaborator (constructor-injected by type)</h2>
 * <ul>
 *   <li>{@link ReportService} &mdash; submits the report Spring Batch job asynchronously and returns
 *       the {@code jobExecutionId}; injected as a {@code final} field through the Lombok-generated
 *       constructor (PR-29).</li>
 * </ul>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-18</b> &mdash; this controller is intentionally <em>not</em> annotated with a
 *       class-level {@code @PreAuthorize("hasRole('ADMIN')")}. Per AAP &sect;0.7.2 the transaction
 *       report feature is available to both {@code ROLE_USER} and {@code ROLE_ADMIN} (the legacy
 *       {@code COMEN01C} user menu exposed "Transaction Reports"); the endpoint requires
 *       authentication ({@link SecurityRequirement bearerAuth}) but not a specific role. This
 *       contrasts with {@code UserController}/{@code BatchAdminController}, which <em>do</em> carry
 *       class-level {@code @PreAuthorize}.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 namespace only ({@code jakarta.validation.Valid}); no
 *       {@code javax.*} imports.</li>
 *   <li><b>PR-29</b> &mdash; constructor injection only, via Lombok {@link RequiredArgsConstructor}
 *       over the {@code final} {@link ReportService} field; no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><b>Why 202, not 200/201:</b> {@code 202 Accepted} is the semantically correct status for
 *       "request accepted, processing has not completed" &mdash; the report file does not yet exist
 *       when the response is written. The body carries the {@code jobExecutionId} for status
 *       tracking rather than a finished result.</li>
 *   <li><b>Ordered response body:</b> a {@link LinkedHashMap} is used so the JSON keys serialize in a
 *       stable, human-friendly order ({@code jobExecutionId} first).</li>
 *   <li><b>No PII in logs:</b> only the report type, date window and confirmation flag are logged;
 *       no cardholder data is present on this request.</li>
 *   <li><b>Output retrieval is out of scope here:</b> the generated report file is produced by the
 *       batch job; fetching it is a separate operational concern and is not exposed by this
 *       controller (no feature additions beyond the legacy behavior).</li>
 * </ul>
 *
 * @see ReportService
 * @see ReportRequest
 * @see com.carddemo.batch.TransactionReportJobConfig
 * @see com.carddemo.controller.advice.GlobalExceptionHandler
 * @since 1.0
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Report", description = "Transaction report submission (replaces CORPT00C, TRANID=CR00)")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

    /**
     * Status value returned in the {@code 202 Accepted} body to signal that the report submission was
     * accepted for asynchronous processing. Mirrors the HTTP semantics of {@link HttpStatus#ACCEPTED}.
     */
    private static final String STATUS_ACCEPTED = "ACCEPTED";

    /**
     * Human-readable acknowledgment echoed in the {@code 202 Accepted} body. Conveys the same intent
     * as the legacy program returning control to the user immediately after enqueuing the batch job.
     */
    private static final String ACCEPTED_MESSAGE =
            "Report submission accepted. Job is running asynchronously.";

    /**
     * Report submission service replacing the COBOL {@code SUBMIT-JOB-TO-INTRDR} /
     * {@code WRITEQ TD QUEUE('JOBS')} flow. Injected by type through the Lombok-generated constructor
     * (PR-29); {@code submitReport(...)} is {@code @Async} and returns the launched
     * {@code jobExecutionId} wrapped in a {@link CompletableFuture}.
     */
    private final ReportService reportService;

    /**
     * Submits a transaction report for asynchronous batch generation and returns
     * {@code 202 Accepted} with the launched {@code jobExecutionId}.
     *
     * <p>This is the REST replacement for {@code CORPT00C}'s {@code SUBMIT-JOB-TO-INTRDR} paragraph
     * ({@code app/cbl/CORPT00C.cbl:L462-L523}), which validated the user's confirmation and then
     * enqueued a {@code TRANREPT} JCL job on the {@code 'JOBS'} Transient Data Queue. Here the request
     * is validated, delegated to {@link ReportService#submitReport(ReportRequest)}, and the resulting
     * execution id is wrapped in a {@code 202 Accepted} response.</p>
     *
     * <p>Validation ({@code @Valid}) runs synchronously during argument binding, so malformed
     * requests are rejected with HTTP&nbsp;400 before any job is launched:</p>
     * <ul>
     *   <li>{@code reportType} &mdash; required ({@code @NotNull}); one of {@code MONTHLY},
     *       {@code YEARLY}, {@code CUSTOM}.</li>
     *   <li>{@code startDate} / {@code endDate} &mdash; required ISO-8601 dates ({@code @NotNull});
     *       a cross-field {@code @AssertTrue} enforces {@code endDate >= startDate}.</li>
     *   <li>{@code outputFormat} &mdash; optional ({@code PDF}/{@code CSV}/{@code HTML}); the service
     *       applies a default when absent.</li>
     *   <li>{@code confirmation} &mdash; required, exactly one of {@code [YyNn]} ({@code @Pattern}).
     *       Only an affirmative {@code 'Y'/'y'} causes a launch; the service rejects a non-affirmative
     *       value with {@link IllegalArgumentException} (mapped to 400 by the global handler).</li>
     * </ul>
     *
     * <p>The method returns a {@link CompletableFuture} of the {@link ResponseEntity}: the service
     * launch runs on a task-executor thread and the {@code 202 Accepted} response is written once the
     * {@code jobExecutionId} is available, without blocking the servlet container thread. Should the
     * service future complete exceptionally, the {@link CompletableFuture#thenApply(java.util.function.Function)
     * thenApply} mapping is bypassed and the cause is surfaced to
     * {@code GlobalExceptionHandler} for translation to the appropriate HTTP status.</p>
     *
     * @param request the validated report submission payload (report type, date window, optional
     *                output format, and confirmation flag)
     * @return a {@link CompletableFuture} that completes with a {@code 202 Accepted}
     *         {@link ResponseEntity} whose ordered body contains {@code jobExecutionId},
     *         {@code status}, {@code message}, and {@code reportType}
     */
    @PostMapping
    @Operation(
        summary = "Submit a transaction report job (async)",
        description = "Submits a Spring Batch report job and returns immediately with the job execution ID. "
            + "The actual report generation runs asynchronously. Client can poll job status via "
            + "the BatchAdminController (GET /api/admin/jobs/{jobName}/executions/{executionId}). "
            + "Replaces CORPT00C's WRITEQ TD QUEUE('JOBS') pattern with JobLauncher.run() @Async. "
            + "Report types: MONTHLY (current month), YEARLY (current year), CUSTOM (user-supplied date range). "
            + "Cross-field validation: when reportType=CUSTOM, both startDate and endDate are required and "
            + "endDate >= startDate.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Report submission accepted; jobExecutionId returned for tracking"),
        @ApiResponse(responseCode = "400", description = "Validation error (e.g., 'End Date must not be less than Start Date...')"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "500", description = "Job launch failed (e.g., JobRegistry could not resolve job name)")
    })
    public CompletableFuture<ResponseEntity<Map<String, Object>>> submitReport(
            @Valid @RequestBody ReportRequest request) {

        log.info("POST /api/reports received: reportType={} startDate={} endDate={} outputFormat={} confirmation={}",
            request.getReportType(), request.getStartDate(), request.getEndDate(),
            request.getOutputFormat(), request.getConfirmation());

        // Delegate to the @Async service (replaces EXEC CICS WRITEQ TD QUEUE('JOBS')). The service
        // returns a CompletableFuture<Long> completed with the launched jobExecutionId; map it to the
        // 202 Accepted response once available. An exceptional completion (IllegalArgumentException /
        // IllegalStateException) skips this mapping and is routed to GlobalExceptionHandler.
        return reportService.submitReport(request).thenApply(jobExecutionId -> {
            // LinkedHashMap preserves JSON key order (jobExecutionId first) per the response contract.
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobExecutionId", jobExecutionId);
            response.put("status", STATUS_ACCEPTED);
            response.put("message", ACCEPTED_MESSAGE);
            response.put("reportType", request.getReportType());

            log.info("POST /api/reports accepted: reportType={} jobExecutionId={}",
                request.getReportType(), jobExecutionId);

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        });
    }
}
