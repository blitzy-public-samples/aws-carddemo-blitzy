package com.carddemo.controller;

import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.ReportResponse;
import com.carddemo.service.ReportService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * REST controller exposing the CardDemo transaction-report submission operation.
 *
 * <p>This controller is the Spring Boot re-expression of the legacy CICS online program
 * {@code CORPT00C} ("Print Transaction reports by submitting batch job from online using extra
 * partition TDQ", transaction {@code CR00}). On the 3270 terminal the operator selected one of
 * three mutually-exclusive report options on the BMS screen {@code CORPT00} &mdash;
 * <strong>Monthly</strong>, <strong>Yearly</strong>, or a keyed <strong>Custom</strong> start/end
 * range &mdash; whereupon the program assembled a JCL job stream and handed it to the CICS internal
 * reader for execution. Crucially, that hand-off was <em>asynchronous</em>: the
 * {@code SUBMIT-JOB-TO-INTRDR} paragraph performed an {@code EXEC CICS WRITEQ TD} to the
 * extra-partition transient-data queue ({@code CORPT00C} L462-535) and the transaction returned
 * immediately with the confirmation "{@code <report> report submitted for printing ...}"
 * ({@code CORPT00C} L450). It never rendered the report inline and never blocked on its
 * completion.</p>
 *
 * <h2>Fire-and-forget semantics &rarr; HTTP 202 Accepted (AAP &sect;0.3.2)</h2>
 * <p>The migrated architecture preserves that fire-and-forget behavior exactly. The work of
 * resolving the effective date range (MONTHLY = first&ndash;last day of the current month;
 * YEARLY = Jan&nbsp;1&ndash;Dec&nbsp;31 of the current year), validating a CUSTOM range through the
 * {@code CSUTLDTC}-equivalent date validator, and launching the Spring Batch
 * {@code transactionReportJob} via {@code JobLauncher} lives entirely in
 * {@link ReportService#submitReport(ReportRequest)}. That call returns synchronously with a
 * reference to the submitted job (its {@code JobExecution} id and status) rather than the report
 * content itself. This controller therefore responds with <strong>HTTP&nbsp;202&nbsp;Accepted</strong>
 * &mdash; the canonical status for a request that has been accepted for processing but is not yet
 * complete &mdash; carrying a {@link ReportResponse} whose {@code jobExecutionId} is the async
 * report reference the caller uses to poll for status or retrieve the finished output separately.</p>
 *
 * <h2>Why this controller is <em>not</em> {@code @Async}</h2>
 * <p>The asynchronicity is the Spring Batch {@code JobLauncher} submission performed inside the
 * service, not the HTTP request handling. The HTTP call itself returns synchronously with the job
 * reference; the 202 status &mdash; not an {@code @Async} dispatch &mdash; is what communicates
 * "accepted for processing". Annotating the controller (or method) {@code @Async} would be
 * incorrect: a method returning a plain value rather than {@code void}/{@code Future} cannot be
 * meaningfully proxied asynchronously and would hand the caller a {@code null} body.</p>
 *
 * <h2>Thin-controller contract</h2>
 * <p>Honoring the strict layered architecture (Controller &rarr; Service &rarr; Repository), this
 * controller contains <strong>no business logic</strong>: no date-range arithmetic, no job
 * construction, no launching, and no validation beyond declarative Bean Validation. It is pure
 * delegation. Field-level request validation is driven by {@link Valid @Valid} on the
 * {@link ReportRequest} body &mdash; a {@code null} or non-{@code MONTHLY/YEARLY/CUSTOM}
 * {@code reportType} is rejected and surfaced as HTTP&nbsp;400 by the application's
 * {@code GlobalExceptionHandler}. The conditional cross-field rule (CUSTOM requires a valid,
 * non-reversed start/end range) cannot be expressed as a static annotation and is enforced inside
 * {@link ReportService}, which raises a validation error that likewise maps to HTTP&nbsp;400.</p>
 *
 * <h2>Routing and security</h2>
 * <p>The single endpoint is mapped at the absolute path {@code /reports} (there is no {@code /api}
 * prefix, consistent with the rest of the API surface). No method-level {@code @PreAuthorize} guard
 * is applied: mirroring the legacy program, any authenticated user may request a report. The
 * security filter chain's {@code anyRequest().authenticated()} rule already requires a valid JWT,
 * so an unauthenticated request yields HTTP&nbsp;401 before reaching this handler.</p>
 *
 * <p>Collaborators are supplied by constructor injection into a {@code final} field, keeping the
 * controller immutable and trivially testable; as a stateless singleton it is inherently
 * thread-safe.</p>
 *
 * @see ReportService
 * @see ReportRequest
 * @see ReportResponse
 * @see <a href="file:app/cbl/CORPT00C.cbl">CORPT00C.cbl</a>
 */
@RestController
@RequestMapping("/reports")
public class ReportController {

    /**
     * The application service that ports the {@code CORPT00C} submission flow: it resolves the
     * effective reporting range, validates a CUSTOM range, launches the {@code transactionReportJob}
     * batch job through {@code JobLauncher}, and returns the resulting {@code JobExecution}
     * reference. Held {@code final} and injected via the constructor.
     */
    private final ReportService reportService;

    /**
     * Creates the report controller with its single collaborator.
     *
     * <p>Constructor injection (rather than field injection) keeps the controller immutable and
     * unit-testable, and lets Spring resolve the {@link ReportService} bean at construction time.</p>
     *
     * @param reportService the report-submission service; must not be {@code null}
     */
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Submits a transaction-report request for asynchronous batch processing &mdash; the REST
     * re-expression of {@code CORPT00C}'s "resolve range &rarr; validate &rarr; submit to internal
     * reader &rarr; confirm" flow (transaction {@code CR00}).
     *
     * <p>The request body's {@code reportType} ({@code MONTHLY}, {@code YEARLY}, or {@code CUSTOM})
     * is first checked by Bean Validation via {@link Valid @Valid}; a missing or unrecognized value
     * is rejected as HTTP&nbsp;400 before this method body executes. The validated request is then
     * delegated, unmodified, to {@link ReportService#submitReport(ReportRequest)}, which derives the
     * effective date range (for MONTHLY/YEARLY), validates and orders a CUSTOM range, and launches
     * the Spring Batch report job. The service returns synchronously with the
     * {@code JobExecution} reference.</p>
     *
     * <p>Because the report is produced out of band (fire-and-forget, exactly as the legacy program
     * behaved), the response is <strong>HTTP&nbsp;202&nbsp;Accepted</strong> carrying a
     * {@link ReportResponse} whose {@code jobExecutionId} is the asynchronous report reference.</p>
     *
     * @param request the report request to submit; its {@code reportType} selects the range mode and,
     *                for {@code CUSTOM}, supplies the {@code startDate}/{@code endDate}. Validated with
     *                {@link Valid @Valid}; a constraint violation is surfaced as HTTP&nbsp;400.
     * @return HTTP&nbsp;202&nbsp;Accepted with a {@link ReportResponse} carrying the
     *         {@code jobExecutionId}, submission-time {@code status}, echoed {@code reportType}, and
     *         the effective {@code startDate}/{@code endDate}
     */
    @PostMapping
    public ResponseEntity<ReportResponse> submitReport(@Valid @RequestBody ReportRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(reportService.submitReport(request));
    }
}
