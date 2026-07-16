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
package com.aws.carddemo.account.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized REST exception handling for the Account Management service (Feature F-003).
 *
 * <p>This single {@link RestControllerAdvice} is the one place where domain and framework
 * exceptions are translated into HTTP responses. Every response it produces carries an
 * {@link ApiError} body, giving the migrated REST API a single, machine-readable error
 * contract in place of the ad-hoc 3270 screen messages emitted by the legacy CardDemo
 * COBOL programs {@code COACTVWC} (account view, transaction {@code CAVW}) and
 * {@code COACTUPC} (account update, transaction {@code CAUP}).</p>
 *
 * <h2>Exception-to-status mapping</h2>
 * <table border="1">
 *   <caption>Handled exceptions and the HTTP status each yields</caption>
 *   <tr><th>Exception</th><th>HTTP status</th><th>Legacy origin</th></tr>
 *   <tr>
 *     <td>{@link AccountNotFoundException}</td>
 *     <td>{@code 404 Not Found}</td>
 *     <td>{@code COACTVWC} {@code 9300-GETACCTDATA-BYACCT} {@code DFHRESP(NOTFND)} branch</td>
 *   </tr>
 *   <tr>
 *     <td>{@link ValidationException}</td>
 *     <td>{@code 400 Bad Request}</td>
 *     <td>{@code COACTUPC} field edits {@code 1210}/{@code 1220}/{@code 1250} and date edits</td>
 *   </tr>
 *   <tr>
 *     <td>{@link MethodArgumentNotValidException}</td>
 *     <td>{@code 400 Bad Request}</td>
 *     <td>Bean Validation on the {@code @Valid @RequestBody} update DTO</td>
 *   </tr>
 *   <tr>
 *     <td>{@link ConstraintViolationException}</td>
 *     <td>{@code 400 Bad Request}</td>
 *     <td>Method-level constraints on a validated path variable (e.g. the 11-digit id)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link ObjectOptimisticLockingFailureException}</td>
 *     <td>{@code 409 Conflict}</td>
 *     <td>{@code COACTUPC} {@code 9700-CHECK-CHANGE-IN-REC} before-image comparison</td>
 *   </tr>
 *   <tr>
 *     <td>{@link HttpMessageNotReadableException}</td>
 *     <td>{@code 400 Bad Request}</td>
 *     <td>Malformed, empty, or type-incoercible JSON request body (framework)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link HttpRequestMethodNotSupportedException}</td>
 *     <td>{@code 405 Method Not Allowed}</td>
 *     <td>HTTP method not mapped for the account resource (framework)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link HttpMediaTypeNotSupportedException}</td>
 *     <td>{@code 415 Unsupported Media Type}</td>
 *     <td>Request {@code Content-Type} is not {@code application/json} (framework)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link HttpMediaTypeNotAcceptableException}</td>
 *     <td>{@code 406 Not Acceptable}</td>
 *     <td>Request {@code Accept} header cannot be satisfied by JSON (framework)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link NoResourceFoundException}</td>
 *     <td>{@code 404 Not Found}</td>
 *     <td>No route matches the request path (framework)</td>
 *   </tr>
 *   <tr>
 *     <td>{@link Exception} (catch-all safety net)</td>
 *     <td>{@code 500 Internal Server Error}</td>
 *     <td>Any unexpected server-side fault (e.g. a persistence {@code DataAccessException})</td>
 *   </tr>
 * </table>
 *
 * <p>These five framework mappings ensure that <em>every</em> error a REST client can provoke
 * &mdash; not just the domain conditions &mdash; is returned in the uniform {@link ApiError}
 * shape rather than Spring Boot's default (and less controlled) error representation. Each
 * emits a fixed, generic summary message and never echoes parser detail or any submitted
 * value (AAP &sect;0.6.6).</p>
 *
 * <h2>Concurrency mapping</h2>
 * <p>The legacy update path took an exclusive record lock
 * ({@code EXEC CICS READ ... UPDATE ACCTFILE}) and then, in
 * {@code 9700-CHECK-CHANGE-IN-REC}, compared the freshly re-read record against a
 * before-image field by field, aborting the write on any difference
 * ({@code app/cbl/COACTUPC.cbl:L4109-L4193}). That "changed-under-me &rarr; reject"
 * behavior is reproduced here with JPA optimistic locking: a {@code @Version} column is
 * incremented on every update, and a stale version raises
 * {@link ObjectOptimisticLockingFailureException}, which this advice maps to
 * {@code 409 Conflict}. The emitted body message is the modernized text
 * {@value #CONFLICT_MESSAGE} (AAP &sect;0.6.4 / &sect;4.2.3.2) &mdash; deliberately
 * <em>not</em> the raw legacy COBOL conflict literal defined at
 * {@code app/cbl/COACTUPC.cbl:L521-L522}, which is the <em>behavior</em> reproduced here
 * rather than the text surfaced to REST clients.</p>
 *
 * <h2>Sanitization contract (AAP &sect;0.6.6)</h2>
 * <p>No handler logs or embeds sensitive data. Full account/card numbers and monetary
 * values (balance, credit limit, cash credit limit, cycle credit/debit) are never written
 * to logs or copied into an error body in plaintext. Every response records the sanitized
 * request-mapping route template (for example {@code /api/v1/accounts/{accountId}}) as its
 * {@code path} &mdash; never the raw request URI &mdash; so the 11-digit account id (classified
 * sensitive "full account number" data) is kept out of the serialized body and out of any log
 * that captures it (derived by {@link #resolvePath}; CWE-209 / CWE-532). The domain exceptions
 * ({@link ValidationException}, {@link AccountNotFoundException}) carry only a field/condition
 * name or a fixed, id-free message, and this advice never reads submitted values from framework
 * exceptions ({@code getInvalidValue()} is intentionally ignored; the malformed-body, method,
 * media-type, and no-route handlers emit fixed, generic summaries rather than parser text or the
 * offending token). For the concurrency case a fixed constant is emitted rather than the
 * Hibernate exception message, which could otherwise leak entity or row detail. Consistent with
 * that contract, expected {@code 4xx} conditions are not logged with stack traces.</p>
 *
 * <h2>Wiring</h2>
 * <p>The component scan rooted at {@code AccountServiceApplication}
 * ({@code com.aws.carddemo.account}) auto-discovers this advice, so no manual registration
 * is required. Because it is a plain {@code @RestControllerAdvice} (it does not extend
 * {@code ResponseEntityExceptionHandler}), its explicit
 * {@code @ExceptionHandler(MethodArgumentNotValidException.class)} takes precedence over
 * Spring Boot's default handling for this module's controllers. The advice is loaded and
 * exercised by the {@code @WebMvcTest} slice ({@code AccountControllerTest} &mdash; covering the
 * 400/404/405/415 and malformed-body / strict-coercion mappings) and, under {@code mvn verify},
 * by the full {@code @SpringBootTest} + Testcontainers integration test
 * ({@code AccountApiIntegrationTest} &mdash; covering the 404/400/409 and malformed / strict-token
 * paths against a real PostgreSQL). Per the module's Surefire/Failsafe split the integration test
 * runs in the {@code verify} phase (it requires a Docker daemon), not in {@code mvn test}.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Generic, sanitized message used for every {@code 400 Bad Request} produced by the
     * framework-driven validation handlers. The specific, per-field detail lives in the
     * {@code fieldErrors} map of the {@link ApiError} body; this summary intentionally
     * carries no submitted values.
     */
    private static final String VALIDATION_FAILED_MESSAGE = "Validation failed";

    /**
     * Exact modernized conflict message emitted on optimistic-lock failure.
     *
     * <p>Per AAP &sect;0.6.4 / &sect;4.2.3.2 this is the literal REST text and MUST remain
     * character-for-character stable; it is deliberately <em>not</em> the raw legacy COBOL
     * conflict literal defined at {@code app/cbl/COACTUPC.cbl:L521-L522}. It is defined once
     * as a constant so that the emitted value cannot drift and so tests can assert equality
     * against a single source.</p>
     */
    private static final String CONFLICT_MESSAGE = "Record updated by another user - please retry";

    /**
     * Fixed, generic summary for a malformed, empty, or type-incoercible JSON request body.
     * Deliberately carries no parser detail and no fragment of the submitted payload, so the
     * offending token can never leak into the error body or logs (AAP &sect;0.6.6).
     */
    private static final String MALFORMED_BODY_MESSAGE = "Malformed or unreadable request body";

    /** Fixed summary for an HTTP method that is not mapped for the account resource. */
    private static final String METHOD_NOT_ALLOWED_MESSAGE = "Request method not supported";

    /** Fixed summary for a request whose {@code Content-Type} is not a supported media type. */
    private static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE = "Request content type is not supported";

    /** Fixed summary for a request whose {@code Accept} header cannot be satisfied by JSON. */
    private static final String NOT_ACCEPTABLE_MESSAGE = "Not acceptable";

    /** Fixed summary for a request path that matches no route. */
    private static final String NO_RESOURCE_MESSAGE = "Requested resource was not found";

    /**
     * Fixed, generic summary for any unexpected server-side failure mapped to {@code 500}.
     * Deliberately carries no exception type, framework or database detail, stack frame, SQL, or
     * submitted value, so an internal fault can never leak implementation detail to the client
     * (CWE-209; AAP &sect;0.6.6). The specific cause is recorded only in the server-side log.
     */
    private static final String UNEXPECTED_ERROR_MESSAGE =
            "An unexpected error occurred while processing the request";

    /**
     * SLF4J logger used <em>only</em> by the catch-all {@link #handleUnexpected} handler to record
     * unexpected server-side failures at {@code ERROR} for operability. The entry is limited to the
     * sanitized route template (never the raw URI or the 11-digit account id) plus the exception;
     * request bodies, bound parameters, account numbers, and monetary values are never logged
     * (AAP &sect;0.6.6 / CWE-532).
     */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps {@link AccountNotFoundException} to {@code 404 Not Found}.
     *
     * <p>Reproduces the {@code DFHRESP(NOTFND)} branch of {@code COACTVWC}'s
     * {@code 9300-GETACCTDATA-BYACCT} paragraph ({@code app/cbl/COACTVWC.cbl:L786-L807}). The
     * exception carries a fixed, id-free message ({@code "Account not found in Acct Master file."})
     * &mdash; it embeds no account identifier &mdash; so it is passed through verbatim, while the
     * sensitive id is kept out of the body by the route-template {@code path} (see
     * {@link #resolvePath}).</p>
     *
     * @param ex      the not-found signal raised by the service layer
     * @param request the current request, used only to derive the sanitized route template
     *                recorded in the error body
     * @return a {@code 404} response whose body is a fully populated {@link ApiError}
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(final AccountNotFoundException ex,
                                                   final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.NOT_FOUND;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                ex.getMessage(),
                resolvePath(request));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps {@link ValidationException} to {@code 400 Bad Request}.
     *
     * <p>Represents the service-layer business edits migrated from {@code COACTUPC}
     * (active-status {@code Y}/{@code N}, signed decimal range and scale, strict date rules,
     * and the 11-digit account-id domain). The exception's summary message
     * and its optional field&rarr;message detail are copied into the {@link ApiError} body;
     * when the detail map is {@code null} the {@code ApiError} contract omits it from the
     * JSON. Every string carried here already references only the field or condition, never a
     * submitted value.</p>
     *
     * @param ex      the business-rule failure raised by the service/validator layer
     * @param request the current request, used only to derive the sanitized route template
     *                recorded in the error body
     * @return a {@code 400} response whose body is a fully populated {@link ApiError},
     *         including {@code fieldErrors} when the exception supplies them
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> handleValidation(final ValidationException ex,
                                                     final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                ex.getMessage(),
                resolvePath(request),
                ex.getFieldErrors());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps {@link MethodArgumentNotValidException} to {@code 400 Bad Request}.
     *
     * <p>Raised when Bean Validation (JSR-380) on a {@code @Valid @RequestBody} DTO fails.
     * Each rejected field is collected into an insertion-ordered {@link LinkedHashMap} of
     * field name &rarr; default (constraint) message; a stable, deterministic result is
     * guaranteed by keeping the first message encountered for any field that is reported more
     * than once. Only the field name and its constraint message are captured &mdash; the
     * submitted (potentially sensitive) value is never read.</p>
     *
     * @param ex      the binding failure raised by the framework for the request body
     * @param request the current request, used only to derive the sanitized route template
     *                recorded in the error body
     * @return a {@code 400} response whose body is an {@link ApiError} with a
     *         {@code fieldErrors} map and the generic {@value #VALIDATION_FAILED_MESSAGE}
     *         summary
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(final MethodArgumentNotValidException ex,
                                                                 final HttpServletRequest request) {
        final Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (final FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                VALIDATION_FAILED_MESSAGE,
                resolvePath(request),
                fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps {@link ConstraintViolationException} to {@code 400 Bad Request}.
     *
     * <p>Raised when method-level constraints fail &mdash; for example a {@code @Pattern} or
     * {@code @Size} declared on a {@code @PathVariable} of a {@code @Validated} controller,
     * such as the 11-digit numeric account-id path segment. Each violation is collected into
     * an insertion-ordered {@link LinkedHashMap} keyed by its <em>public leaf property name</em>
     * (for example {@code accountId}), <strong>not</strong> the internal, method-qualified path the
     * validation runtime produces for method-level constraints (for example {@code getAccount.accountId}
     * or {@code updateAccount.accountId}); see {@link #leafPropertyName}. The constraint message is the
     * value; the first message wins for any duplicated key. The offending value
     * ({@code getInvalidValue()}) is intentionally excluded because it could be sensitive.</p>
     *
     * @param ex      the constraint-violation failure raised by the validation runtime
     * @param request the current request, used only to derive the sanitized route template
     *                recorded in the error body
     * @return a {@code 400} response whose body is an {@link ApiError} with a
     *         {@code fieldErrors} map and the generic {@value #VALIDATION_FAILED_MESSAGE}
     *         summary
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(final ConstraintViolationException ex,
                                                              final HttpServletRequest request) {
        final Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation ->
                fieldErrors.putIfAbsent(leafPropertyName(violation.getPropertyPath()), violation.getMessage()));
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                VALIDATION_FAILED_MESSAGE,
                resolvePath(request),
                fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps {@link ObjectOptimisticLockingFailureException} to {@code 409 Conflict}.
     *
     * <p>This is the modern equivalent of the legacy before-image conflict check in
     * {@code COACTUPC}'s {@code 9700-CHECK-CHANGE-IN-REC} paragraph
     * ({@code app/cbl/COACTUPC.cbl:L4109-L4193}): if another writer advanced the account's
     * {@code @Version} between the client's read and its update, the write is rejected. The
     * body message is the fixed constant {@value #CONFLICT_MESSAGE}; the raw exception message
     * is deliberately ignored so that no Hibernate entity or row detail can leak.</p>
     *
     * @param ex      the optimistic-lock failure raised by the persistence layer on a stale
     *                {@code @Version}
     * @param request the current request, used only to derive the sanitized route template
     *                recorded in the error body
     * @return a {@code 409} response whose body is an {@link ApiError} carrying the exact
     *         modernized conflict message
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(final ObjectOptimisticLockingFailureException ex,
                                                         final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.CONFLICT;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                CONFLICT_MESSAGE,
                resolvePath(request));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps {@link HttpMessageNotReadableException} to {@code 400 Bad Request}.
     *
     * <p>Raised by Spring MVC when the request body cannot be read or bound: malformed or
     * truncated JSON, an empty body where one is required, a token that the strict Jackson
     * coercion policy (the consolidated JSON input-hardening customizer, see
     * {@code AccountServiceApplication#jsonHardeningCustomizer}) refuses to reshape (a fractional or
     * quoted {@code version}, or a quoted monetary value), or a payload that violates a stream-read
     * limit (oversized document, over-long string, or excessive nesting depth &mdash; SEC-INPUT-1).
     * Only a fixed, generic summary
     * ({@value #MALFORMED_BODY_MESSAGE}) is returned; the framework exception message &mdash;
     * which can quote the offending token, byte offset, or a fragment of the payload &mdash; is
     * deliberately never read, so no submitted value can leak into the body or logs
     * (AAP &sect;0.6.6).</p>
     *
     * @param ex      the framework body-read failure (parser detail intentionally ignored)
     * @param request the current request, used only to derive the sanitized route template
     *                recorded in the error body
     * @return a {@code 400} response whose body is a sanitized {@link ApiError}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(final HttpMessageNotReadableException ex,
                                                      final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                MALFORMED_BODY_MESSAGE,
                resolvePath(request));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps {@link HttpRequestMethodNotSupportedException} to {@code 405 Method Not Allowed}.
     *
     * <p>Raised when a request targets a mapped account path with an HTTP method the controller
     * does not expose (for example {@code POST} or {@code DELETE} on
     * {@code /api/v1/accounts/{accountId}}, which supports only {@code GET} and {@code PUT}). A
     * fixed generic summary is returned in the uniform {@link ApiError} shape rather than Spring
     * Boot's default error body.</p>
     *
     * <p>Per RFC&nbsp;7231&nbsp;&sect;7.4.1 (and RFC&nbsp;9110&nbsp;&sect;15.5.6), a {@code 405}
     * response SHOULD carry an {@code Allow} header enumerating the methods the target resource
     * does support. Spring surfaces that set on the exception; it is emitted here in a
     * deterministic (alphabetically sorted) order so clients (and tests) see a stable value such
     * as {@code Allow: GET, HEAD, OPTIONS, PUT}. The header lists only framework-derived method
     * names &mdash; it embeds no submitted value &mdash; so it is consistent with the sanitization
     * contract (AAP &sect;0.6.6). The set is defensively guarded in the rare case Spring cannot
     * supply it, in which case the header is simply omitted (the {@code 405} status and body are
     * unchanged).</p>
     *
     * @param ex      the framework method-not-supported signal (also the source of the supported
     *                methods advertised in the {@code Allow} header)
     * @param request the current request, used only to derive the sanitized route template
     *                recorded in the error body
     * @return a {@code 405} response whose body is a sanitized {@link ApiError} and which carries
     *         an {@code Allow} header when the supported-method set is available
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(final HttpRequestMethodNotSupportedException ex,
                                                             final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.METHOD_NOT_ALLOWED;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                METHOD_NOT_ALLOWED_MESSAGE,
                resolvePath(request));
        final ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        // RFC 7231 §7.4.1: advertise the supported methods via the Allow header, in a
        // deterministic (sorted) order. Guard against a null/empty set (framework edge case).
        final Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            final String allow = supported.stream()
                    .map(HttpMethod::name)
                    .sorted()
                    .collect(Collectors.joining(", "));
            response.header(HttpHeaders.ALLOW, allow);
        }
        return response.body(body);
    }

    /**
     * Maps {@link HttpMediaTypeNotSupportedException} to {@code 415 Unsupported Media Type}.
     *
     * <p>Raised when the request {@code Content-Type} is not one the controller can consume
     * (the update endpoint consumes {@code application/json}). A fixed generic summary is
     * returned in the uniform {@link ApiError} shape.</p>
     *
     * @param ex      the framework media-type-not-supported signal
     * @param request the current request, used only to derive the sanitized route template
     *                recorded in the error body
     * @return a {@code 415} response whose body is a sanitized {@link ApiError}
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotSupported(final HttpMediaTypeNotSupportedException ex,
                                                                final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                UNSUPPORTED_MEDIA_TYPE_MESSAGE,
                resolvePath(request));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps {@link HttpMediaTypeNotAcceptableException} to {@code 406 Not Acceptable}.
     *
     * <p>Raised during content negotiation when the request {@code Accept} header cannot be
     * satisfied by any representation this API produces (it emits {@code application/json}
     * only). The response {@code Content-Type} is pinned to {@code application/json} so the
     * message converters serialize the uniform {@link ApiError} body directly instead of
     * re-entering negotiation and recursing into another {@code 406}. The {@code path} is the
     * sanitized route template (see {@link #resolvePath}), so no concrete account id is ever
     * serialized or logged (AAP &sect;0.6.6).</p>
     *
     * @param ex      the framework content-negotiation failure
     * @param request the current request, used only to derive the sanitized route template
     *                recorded in the error body
     * @return a {@code 406} response whose body is a sanitized {@link ApiError}
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotAcceptable(final HttpMediaTypeNotAcceptableException ex,
                                                                 final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.NOT_ACCEPTABLE;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                NOT_ACCEPTABLE_MESSAGE,
                resolvePath(request));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Maps {@link NoResourceFoundException} to {@code 404 Not Found}.
     *
     * <p>Raised by Spring MVC (Boot 3.2+) when no route matches the request path &mdash; an
     * unknown URL rather than a known account that is absent from the store (that case is
     * {@link AccountNotFoundException}). Returning the uniform {@link ApiError} keeps even
     * unmatched-route 404s consistent with the rest of the API. The {@code path} is the
     * digit-masked request path (see {@link #resolvePath}), so a numeric segment cannot leak.</p>
     *
     * @param ex      the framework no-route signal
     * @param request the current request, used only to derive the sanitized route template
     *                recorded in the error body
     * @return a {@code 404} response whose body is a sanitized {@link ApiError}
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResourceFound(final NoResourceFoundException ex,
                                                          final HttpServletRequest request) {
        final HttpStatus status = HttpStatus.NOT_FOUND;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                NO_RESOURCE_MESSAGE,
                resolvePath(request));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Catch-all mapping for any otherwise-unhandled exception, translating it to a sanitized
     * {@code 500 Internal Server Error}.
     *
     * <p>Every error a REST client can deliberately provoke is already translated by a dedicated
     * handler above. This final safety net exists so that an <em>unexpected</em> server-side fault
     * &mdash; for example an {@code org.springframework.dao.DataAccessException} surfacing from the
     * persistence layer, or any other unchecked {@link RuntimeException} &mdash; is still returned in
     * the uniform {@link ApiError} shape rather than Spring Boot's default error representation, which
     * would otherwise expose the concrete request URI (including the 11-digit account id) and internal
     * framework/database detail (CWE-209 / CWE-532). The body carries only the fixed, generic
     * {@value #UNEXPECTED_ERROR_MESSAGE} summary and the digit-masked route template (see
     * {@link #resolvePath}); the raw exception message, type, and stack trace are never serialized to
     * the client, and no {@code fieldErrors} map is attached.</p>
     *
     * <p>Because it targets {@link Exception}, this handler is the least specific in the advice, so
     * Spring selects it only when no more specific {@code @ExceptionHandler} matches. The failure IS
     * recorded server-side at {@code ERROR} for operability, but the log entry is limited to the
     * sanitized route template and the exception object; request bodies, bound parameters, account
     * numbers, and monetary values are never logged (AAP &sect;0.6.6).</p>
     *
     * @param ex      the unexpected failure not matched by any more specific handler
     * @param request the current request, used only to derive the sanitized route template recorded
     *                in the error body and log entry
     * @return a {@code 500} response whose body is a fully sanitized {@link ApiError}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(final Exception ex,
                                                     final HttpServletRequest request) {
        final String path = resolvePath(request);
        // Sanitized server-side diagnostic: route template + exception only; never the raw URI,
        // account id, request body, or bound parameter values (AAP 0.6.6 / CWE-532).
        log.error("Unexpected error while handling request for path {}", path, ex);
        final HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                UNEXPECTED_ERROR_MESSAGE,
                path);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Reduces a Bean Validation {@link Path} to its <em>leaf</em> node name so that a
     * {@code fieldErrors} key is the public parameter/field name the client supplied (for example
     * {@code accountId}) rather than the internal, method-qualified path the validation runtime
     * produces for method-level constraints (for example {@code getAccount.accountId} or
     * {@code updateAccount.accountId}). Exposing the controller method name in the error contract
     * would leak an internal implementation detail (CWE-209) and make the key unstable across
     * refactors; the leaf name is the stable, public key.
     *
     * <p>The leaf is the last non-blank node name on the path. If the path yields no named node
     * (which is not expected for property or parameter constraints) the full path string is used as
     * a defensive fallback, so a violation is never silently dropped.</p>
     *
     * @param propertyPath the constraint-violation property path (never {@code null})
     * @return the leaf node name, or the full path string when no named node is present
     */
    private static String leafPropertyName(final Path propertyPath) {
        String leaf = null;
        for (final Path.Node node : propertyPath) {
            final String name = node.getName();
            if (name != null && !name.isEmpty()) {
                leaf = name;
            }
        }
        return leaf != null ? leaf : propertyPath.toString();
    }

    /**
     * Derives the sanitized {@code path} value for an {@link ApiError} body: the request-mapping
     * route template (for example {@code /api/v1/accounts/{accountId}}) rather than the raw
     * request URI, so the sensitive 11-digit account id is never serialized or logged
     * (AAP &sect;0.6.6; CWE-209 / CWE-532).
     *
     * <p>When the request was matched to a controller method, Spring MVC exposes the best-matching
     * pattern under {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}; that template is used
     * verbatim. When no handler matched (for example a {@link NoResourceFoundException}, or certain
     * {@code 405}/{@code 415} dispatch failures) the attribute is absent, so a defensive fallback
     * masks every all-digit path segment with the {@code {accountId}} placeholder. Either way, no
     * concrete numeric identifier reaches the error body.</p>
     *
     * @param request the current request
     * @return the sanitized route template, or a digit-masked request path when no template is available
     */
    private static String resolvePath(final HttpServletRequest request) {
        final Object bestMatch = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatch instanceof String pattern && !pattern.isBlank()) {
            return pattern;
        }
        final String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return uri;
        }
        final String[] segments = uri.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            final String segment = segments[i];
            if (!segment.isEmpty() && segment.chars().allMatch(Character::isDigit)) {
                segments[i] = "{accountId}";
            }
        }
        return String.join("/", segments);
    }
}
