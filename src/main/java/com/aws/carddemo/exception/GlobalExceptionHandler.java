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
package com.aws.carddemo.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;

/**
 * Central web-layer exception translator for the CardDemo online (REST) surface.
 *
 * <p>This {@link RestControllerAdvice} is the Java equivalent of the COBOL
 * {@code EVALUATE WS-RESP-CD} / {@code EVALUATE <file>-STATUS} error-handling
 * blocks that every online CardDemo program runs after a CICS file/command
 * request. Each legacy program inspected the CICS response (or the VSAM
 * {@code FILE STATUS}) and surfaced a specific screen message; this advice
 * inspects the typed exception raised by the service/repository layer and
 * surfaces the equivalent HTTP status. Responses use the Spring Framework 6
 * native RFC&nbsp;7807 {@link ProblemDetail} body, which is serialized as
 * {@code application/problem+json}. Spring reads the HTTP response status from
 * {@link ProblemDetail#getStatus()}, so each handler need only return the body.</p>
 *
 * <h2>Online status mapping (caller-visible COBOL outcomes preserved)</h2>
 * <table border="1">
 *   <caption>Exception&nbsp;-&gt;&nbsp;HTTP status</caption>
 *   <tr><th>Exception</th><th>HTTP status</th><th>COBOL origin</th></tr>
 *   <tr><td>{@link RecordNotFoundException}</td><td>404 Not&nbsp;Found</td>
 *       <td>CICS {@code DFHRESP(NOTFND)} / {@code FILE STATUS '23'}
 *           (e.g. {@code legacy/cbl/COTRN01C.cbl} "Transaction ID NOT found")</td></tr>
 *   <tr><td>{@link DuplicateKeyException}</td><td>409 Conflict</td>
 *       <td>CICS {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)} / {@code FILE STATUS '22'}
 *           (e.g. {@code legacy/cbl/COUSR01C.cbl} "User ID already exist")</td></tr>
 *   <tr><td>{@link OptimisticLockingFailureException} /
 *           {@link OptimisticLockException}</td><td>409 Conflict</td>
 *       <td>COBOL READ-UPDATE-REWRITE cycle re-expressed as JPA {@code @Version}
 *           optimistic locking (documented integrity improvement)</td></tr>
 *   <tr><td>{@link MethodArgumentNotValidException}</td><td>400 Bad&nbsp;Request</td>
 *       <td>COBOL edit/validation paragraphs re-expressed as Bean Validation on the request DTO</td></tr>
 *   <tr><td>{@link ConstraintViolationException}</td><td>400 Bad&nbsp;Request</td>
 *       <td>path/query-parameter validation</td></tr>
 *   <tr><td>{@link RequestRejectedException}</td><td>400 Bad&nbsp;Request</td>
 *       <td>Spring Security {@code StrictHttpFirewall} rejection of a malformed
 *           URL/header (a client error, not a server fault)</td></tr>
 *   <tr><td>{@link NoResourceFoundException}</td><td>404 Not&nbsp;Found</td>
 *       <td>Spring MVC "no handler/static resource for path" (bots, scanners,
 *           favicon, path typos)</td></tr>
 *   <tr><td>{@link FileStatusException} (base)</td><td>500 Internal&nbsp;Server&nbsp;Error</td>
 *       <td>non-recoverable I/O, i.e. COBOL {@code 9999-ABEND-PROGRAM}</td></tr>
 *   <tr><td>{@link Exception} (fallback)</td><td>500 Internal&nbsp;Server&nbsp;Error</td>
 *       <td>any unanticipated failure</td></tr>
 * </table>
 *
 * <p>Because {@link RecordNotFoundException} and {@link DuplicateKeyException}
 * both extend {@link FileStatusException}, Spring resolves the most specific
 * {@code @ExceptionHandler} first; the dedicated 404/409 handlers therefore take
 * precedence over the base 500 handler. The subclass handlers are declared
 * explicitly so those outcomes can never be accidentally downgraded to 500.</p>
 *
 * <h2>Batch counterpart (not handled here)</h2>
 * <p>This advice is web-only. The batch layer ({@code com.aws.carddemo.batch})
 * translates the <em>same</em> typed exceptions into mainframe-style job return
 * codes rather than HTTP statuses:</p>
 * <ul>
 *   <li><strong>RC&nbsp;0</strong> &mdash; success (all records processed).</li>
 *   <li><strong>RC&nbsp;4</strong> &mdash; completed with business rejects. This
 *       mirrors {@code legacy/cbl/CBTRN02C.cbl}
 *       ({@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}), where valid
 *       records post but rejected records are written to the reject file.</li>
 *   <li><strong>RC&nbsp;8</strong> &mdash; unrecoverable/abend, raised as a
 *       {@link FileStatusException} at the I/O level (COBOL
 *       {@code 9999-ABEND-PROGRAM}).</li>
 * </ul>
 *
 * <h2>Security and observability discipline</h2>
 * <p>No card CVV, password, or other sensitive value is ever placed in a
 * response body or a log line. Concretely: rejected/invalid input values are
 * never echoed (only field names and constraint messages are surfaced);
 * optimistic-lock and unexpected-error responses use fixed, safe messages; and
 * the raw message of an unknown exception is logged server-side only, never
 * returned to the client. Client errors (4xx) are logged at {@code WARN} (or
 * {@code DEBUG} for a routine {@link NoResourceFoundException}) with no stack
 * trace; genuine server errors (5xx) are logged at {@code ERROR} with a stack
 * trace. Framework client-error signals &mdash; {@link NoResourceFoundException}
 * (404) and {@link RequestRejectedException} (400) &mdash; are handled explicitly
 * so they can never be mis-mapped to a 5xx by the {@link Exception} catch-all,
 * which would otherwise corrupt the error-rate metric and alerting. The
 * per-request correlation ID placed into the SLF4J {@link MDC} by the
 * observability {@code CorrelationIdFilter} (MDC key {@code "correlationId"}) is
 * attached to every log line automatically and, when present, is also copied
 * onto the {@link ProblemDetail} as an extension property so a client can quote
 * it when reporting a problem.</p>
 *
 * <p>This class needs no injected collaborators, so it declares no fields beyond
 * the standard static SLF4J logger (which is not dependency injection) and relies
 * on the implicit default constructor. It is auto-discovered by the
 * root-package {@code @SpringBootApplication} component scan; no manual
 * registration is required. The rationale for the typed-exception-to-status
 * design is recorded in {@code docs/decision-log.md}.</p>
 *
 * @see FileStatusException
 * @see RecordNotFoundException
 * @see DuplicateKeyException
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * SLF4J logger for this advice. A {@code private static final} logger is the
     * standard SLF4J idiom and is intentionally not a Spring-injected dependency,
     * so it does not violate the project's constructor-injection-only rule.
     */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * MDC key under which the observability {@code CorrelationIdFilter} stores the
     * per-request correlation ID. Kept as a local literal (rather than importing
     * the observability package) so the exception package carries no compile-time
     * dependency on the observability layer; the value must stay in sync with
     * {@code CorrelationIdFilter.CORRELATION_ID_MDC_KEY} and the
     * {@code %X{correlationId:-}} token in {@code logback-spring.xml}.
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * Fixed, non-revealing detail used for optimistic-lock conflicts. The raw JPA
     * message can contain internal entity/identifier details, so a safe constant
     * is returned to the client instead.
     */
    private static final String OPTIMISTIC_LOCK_DETAIL =
            "The record was updated by another transaction; please retry.";

    /**
     * Maps a not-found lookup to HTTP {@code 404 Not Found} &mdash; the online
     * equivalent of the CICS {@code DFHRESP(NOTFND)} / {@code FILE STATUS '23'}
     * branch. The exception message carries only a non-sensitive identifier, so it
     * is safe to surface as the problem detail.
     *
     * @param ex the not-found exception raised by a service or repository
     * @return a {@link ProblemDetail} with status 404 and title "Record Not Found"
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ProblemDetail handleNotFound(RecordNotFoundException ex) {
        log.warn("Record not found: {}", ex.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Record Not Found", ex.getMessage());
    }

    /**
     * Maps a duplicate-key write to HTTP {@code 409 Conflict} &mdash; the online
     * equivalent of the CICS {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)} /
     * {@code FILE STATUS '22'} branch. The exception message carries only a
     * non-sensitive natural key, so it is safe to surface.
     *
     * @param ex the duplicate-key exception raised by a service or repository
     * @return a {@link ProblemDetail} with status 409 and title "Duplicate Record"
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ProblemDetail handleDuplicate(DuplicateKeyException ex) {
        log.warn("Duplicate record: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "Duplicate Record", ex.getMessage());
    }

    /**
     * Maps an optimistic-locking failure to HTTP {@code 409 Conflict}. Both the
     * Spring Data abstraction ({@link OptimisticLockingFailureException}) and the
     * raw JPA type ({@link OptimisticLockException}, in case it escapes without
     * translation) are handled here. This reproduces the last-writer integrity of
     * the COBOL READ-UPDATE-REWRITE cycle now enforced by a JPA {@code @Version}
     * column. A fixed, safe detail is returned; the raw provider message (which may
     * expose internal entity details) is deliberately not echoed to the client.
     *
     * @param ex the optimistic-lock failure; declared as the common supertype
     *           {@link RuntimeException} because the two handled types share no
     *           closer ancestor
     * @return a {@link ProblemDetail} with status 409 and title
     *         "Concurrent Update Conflict"
     */
    @ExceptionHandler({ OptimisticLockingFailureException.class, OptimisticLockException.class })
    public ProblemDetail handleOptimisticLock(RuntimeException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "Concurrent Update Conflict", OPTIMISTIC_LOCK_DETAIL);
    }

    /**
     * Maps a request-body Bean Validation failure to HTTP {@code 400 Bad Request}
     * &mdash; the online equivalent of the COBOL edit/validation paragraphs. The
     * detail lists only the offending <em>field names</em> and their constraint
     * messages; the rejected values are never included, because a rejected value
     * could be a password or a card CVV.
     *
     * @param ex the validation failure produced when a {@code @Valid} request body
     *           fails binding/validation
     * @return a {@link ProblemDetail} with status 400 and title "Validation Failed"
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .reduce((first, second) -> first + "; " + second)
                .orElse("Validation failed");
        log.warn("Request validation failed: {}", detail);
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", detail);
    }

    /**
     * Maps a path/query-parameter validation failure to HTTP {@code 400 Bad
     * Request}. The detail lists only the offending property path and its
     * constraint message; the invalid value is never included, for the same
     * sensitive-data reason as {@link #handleValidation(MethodArgumentNotValidException)}.
     *
     * @param ex the constraint-violation failure raised for method-level parameter
     *           validation
     * @return a {@link ProblemDetail} with status 400 and title "Validation Failed"
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraint(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .reduce((first, second) -> first + "; " + second)
                .orElse("Validation failed");
        log.warn("Constraint violation: {}", detail);
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", detail);
    }

    /**
     * Maps any {@link FileStatusException} not caught by a more specific subclass
     * handler to HTTP {@code 500 Internal Server Error} &mdash; the online
     * equivalent of a non-recoverable I/O status routed to COBOL
     * {@code 9999-ABEND-PROGRAM}. The raw {@code FILE STATUS} code and message are
     * logged server-side for diagnosis, but only a generic detail is returned to
     * the client so that internal storage semantics are not leaked.
     *
     * @param ex the base file-status exception carrying the original two-character
     *           COBOL {@code FILE STATUS} code
     * @return a {@link ProblemDetail} with status 500 and title "Data Access Error"
     */
    @ExceptionHandler(FileStatusException.class)
    public ProblemDetail handleFileStatus(FileStatusException ex) {
        log.error("Unhandled file-status error [status={}]: {}", ex.getFileStatus(), ex.getMessage());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Data Access Error",
                "A data access error occurred.");
    }

    /**
     * Maps Spring MVC's {@link NoResourceFoundException} to HTTP {@code 404 Not
     * Found}. This is the framework's own "no handler/static resource matched the
     * request path" signal (bots, scanners, favicon probes, and path typos). Left
     * to the catch-all it would otherwise be mis-reported as a {@code 500}
     * &mdash; polluting the ERROR log stream with stack traces and inflating the
     * {@code http_server_requests} {@code SERVER_ERROR} (5xx) series and
     * {@code logback_events_total{level="error"}}, producing false server-error
     * alerts on entirely benign not-found traffic.
     *
     * <p>Because a missing resource is a routine <em>client</em> error rather than
     * a server fault, it is logged at {@code DEBUG} with no stack trace, and a
     * fixed, non-revealing detail is returned (the request path already appears in
     * the problem-detail {@code instance}). Declaring this handler explicitly is
     * equivalent to letting Spring Boot render its default 404 for an unmatched
     * path, but it keeps the response shape consistent with the rest of this advice
     * (RFC&nbsp;7807 {@link ProblemDetail} carrying the correlation ID).</p>
     *
     * @param ex the not-found signal raised by the {@code DispatcherServlet} when
     *           no handler or static resource matches the request path
     * @return a {@link ProblemDetail} with status 404 and title "Resource Not Found"
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("No resource found for request path: {}", ex.getResourcePath());
        return problem(HttpStatus.NOT_FOUND, "Resource Not Found",
                "The requested resource was not found.");
    }

    /**
     * Maps Spring Security's {@link RequestRejectedException} to HTTP {@code 400
     * Bad Request}. The {@code StrictHttpFirewall} raises this when a request's URL
     * or a header value contains characters it deems potentially malicious (for
     * example a request carrying a malformed correlation header). This is a
     * <em>client</em> error: the request never reaches application logic, so
     * mapping it to a {@code 500} via the catch-all would wrongly log it at ERROR
     * with a stack trace and count it as a server error in
     * {@code http_server_requests} and {@code logback_events_total{level="error"}}.
     *
     * <p>It is logged at {@code WARN} (a rejected request is worth noticing but is
     * not a server fault) with <strong>no stack trace and without echoing the
     * rejected value</strong> &mdash; the offending, client-controlled string
     * (which the firewall embeds in the exception message) is deliberately kept out
     * of both the log line and the response body, consistent with this advice's
     * rule of never surfacing rejected input. A fixed, generic detail is returned
     * to the client.</p>
     *
     * @param ex the firewall rejection raised by Spring Security's
     *           {@code StrictHttpFirewall}
     * @return a {@link ProblemDetail} with status 400 and title "Bad Request"
     */
    @ExceptionHandler(RequestRejectedException.class)
    public ProblemDetail handleRequestRejected(RequestRejectedException ex) {
        log.warn("Request rejected by HTTP firewall: {}", ex.getClass().getSimpleName());
        return problem(HttpStatus.BAD_REQUEST, "Bad Request",
                "The request was rejected as malformed.");
    }

    /**
     * Fallback handler for any exception not matched by a more specific handler.
     * Maps to HTTP {@code 500 Internal Server Error}. The full stack trace is
     * logged server-side only; the client receives a fixed, generic message so
     * that internal details (which could include sensitive data) are never
     * disclosed.
     *
     * @param ex the unanticipated exception
     * @return a {@link ProblemDetail} with status 500 and title "Internal Server Error"
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred.");
    }

    /**
     * Builds an RFC&nbsp;7807 {@link ProblemDetail} for the given status, title, and
     * detail, and &mdash; when a per-request correlation ID is present in the SLF4J
     * {@link MDC} &mdash; attaches it as an extension property named
     * {@code "correlationId"}. The MDC lookup is fully defensive: a missing
     * correlation ID simply yields a problem detail without that property.
     *
     * @param status the HTTP status to report; also drives the response status code
     * @param title  a short, human-readable summary of the problem type
     * @param detail a human-readable, non-sensitive explanation of this occurrence
     * @return the assembled {@link ProblemDetail}
     */
    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            problemDetail.setProperty(CORRELATION_ID_MDC_KEY, correlationId);
        }
        return problemDetail;
    }
}
