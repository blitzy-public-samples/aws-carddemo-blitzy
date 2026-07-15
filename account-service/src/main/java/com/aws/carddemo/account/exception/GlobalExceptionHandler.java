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

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
 * </table>
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
 * to logs or copied into an error body in plaintext. The domain exceptions
 * ({@link ValidationException}, {@link AccountNotFoundException}) already carry only the
 * field/condition name or the account key that is itself part of the request URI, and this
 * advice never reads submitted values from framework exceptions
 * ({@code getInvalidValue()} is intentionally ignored). For the concurrency case a fixed
 * constant is emitted rather than the Hibernate exception message, which could otherwise
 * leak entity or row detail. Consistent with that contract, expected {@code 4xx}
 * conditions are not logged with stack traces.</p>
 *
 * <h2>Wiring</h2>
 * <p>The component scan rooted at {@code AccountServiceApplication}
 * ({@code com.aws.carddemo.account}) auto-discovers this advice, so no manual registration
 * is required. Because it is a plain {@code @RestControllerAdvice} (it does not extend
 * {@code ResponseEntityExceptionHandler}), its explicit
 * {@code @ExceptionHandler(MethodArgumentNotValidException.class)} takes precedence over
 * Spring Boot's default handling for this module's controllers. The advice is loaded by
 * {@code @WebMvcTest} slices (exercising the 404/400 mappings) and by the full
 * {@code @SpringBootTest} + Testcontainers integration test (exercising the 409 path against
 * real PostgreSQL).</p>
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
     * Maps {@link AccountNotFoundException} to {@code 404 Not Found}.
     *
     * <p>Reproduces the {@code DFHRESP(NOTFND)} branch of {@code COACTVWC}'s
     * {@code 9300-GETACCTDATA-BYACCT} paragraph ({@code app/cbl/COACTVWC.cbl:L786-L807}). The
     * exception already carries the REST-friendly, sanitized message
     * {@code "Account: {id} not found in Acct Master file."} (only the account key &mdash;
     * which is part of the request URI &mdash; is embedded), so it is passed through verbatim.</p>
     *
     * @param ex      the not-found signal raised by the service layer
     * @param request the current request, used only to record its URI in the error body
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
                request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps {@link ValidationException} to {@code 400 Bad Request}.
     *
     * <p>Represents the service-layer business edits migrated from {@code COACTUPC}
     * (active-status {@code Y}/{@code N}, signed decimal range and scale, strict date rules,
     * 11-digit account-id domain, and path/body agreement). The exception's summary message
     * and its optional field&rarr;message detail are copied into the {@link ApiError} body;
     * when the detail map is {@code null} the {@code ApiError} contract omits it from the
     * JSON. Every string carried here already references only the field or condition, never a
     * submitted value.</p>
     *
     * @param ex      the business-rule failure raised by the service/validator layer
     * @param request the current request, used only to record its URI in the error body
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
                request.getRequestURI(),
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
     * @param request the current request, used only to record its URI in the error body
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
                request.getRequestURI(),
                fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps {@link ConstraintViolationException} to {@code 400 Bad Request}.
     *
     * <p>Raised when method-level constraints fail &mdash; for example a {@code @Pattern} or
     * {@code @Size} declared on a {@code @PathVariable} of a {@code @Validated} controller,
     * such as the 11-digit numeric account-id path segment. Each violation is collected into
     * an insertion-ordered {@link LinkedHashMap} keyed by its property path, with the
     * constraint message as the value; the first message wins for any duplicated path. The
     * offending value ({@code getInvalidValue()}) is intentionally excluded because it could
     * be sensitive.</p>
     *
     * @param ex      the constraint-violation failure raised by the validation runtime
     * @param request the current request, used only to record its URI in the error body
     * @return a {@code 400} response whose body is an {@link ApiError} with a
     *         {@code fieldErrors} map and the generic {@value #VALIDATION_FAILED_MESSAGE}
     *         summary
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(final ConstraintViolationException ex,
                                                              final HttpServletRequest request) {
        final Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation ->
                fieldErrors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage()));
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        final ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                VALIDATION_FAILED_MESSAGE,
                request.getRequestURI(),
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
     * @param request the current request, used only to record its URI in the error body
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
                request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
