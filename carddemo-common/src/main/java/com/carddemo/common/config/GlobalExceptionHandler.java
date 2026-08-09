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
package com.carddemo.common.config;

import com.carddemo.common.dto.ErrorResponse;
import com.carddemo.common.crypto.SensitiveDataCryptoException;
import com.carddemo.common.security.SensitiveDataMasker;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.FieldValidationException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.PiiEncryptionException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.common.exception.TransactionRejectException;
import com.carddemo.common.exception.UpstreamUnavailableException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;



/**
 * :purpose: Centralized ``@RestControllerAdvice`` that translates the CardDemo
 *     domain exceptions (``com.carddemo.common.exception``) and Jakarta
 *     bean-validation failures into the shared {@link ErrorResponse} JSON body
 *     with a consistent HTTP status and a correlation/trace id, so every
 *     CardDemo web service returns one uniform, non-sensitive error contract.
 *     Re-expresses the legacy COBOL error outcomes -- CICS ``RESP(NOTFND)``, the
 *     ``COACTUPC`` optimistic-lock conflict, and the ``CBTRN02C`` posting reject
 *     codes -- as HTTP responses; the frozen messages and codes are carried by
 *     the domain exception classes, not by this advice.
 * :note: Effective only inside a Spring MVC web context. This library ships no
 *     auto-configuration import, so a web-enabled service activates the advice
 *     via ``@Import(GlobalExceptionHandler.class)`` or by component-scanning
 *     ``com.carddemo.common``; import it only into web-enabled services, not the
 *     pure batch module.
 * :note: Framework MVC failures keep their native statuses (405, 415, and an
 *     unmapped path's 404 among them) but are still rendered through the shared
 *     error body so that every response carries a trace id; an unreadable request
 *     body is additionally split into ``413`` and ``400`` because the shared
 *     request-size cap surfaces through it. Spring Security authentication and
 *     authorization failures are re-thrown untouched so the security filter chain
 *     keeps producing the configured ``401``/``403`` and its audit record.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * :purpose: The ONE caller-facing message for a failure of a backing dependency -- the
     *     database or the session store. Deliberately generic: the exception text of a
     *     JPA/JDBC or Redis failure can echo SQL, column values, host names and
     *     cryptographic detail, none of which may leave the service.
     * :note: A single constant, referenced by the data-access advice, by
     *     {@link DatastoreOutageErrorFilter} and by {@link CardDemoErrorController}'s
     *     container dispatch. One PostgreSQL outage previously produced three different
     *     bodies for the same cause -- this text, "An unexpected error occurred", and the
     *     bare "Internal Server Error" reason phrase -- so a client could not classify the
     *     failure and an operator could not correlate the reports.
     */
    static final String DEPENDENCY_FAILURE_MESSAGE =
            "Unable to complete the request because of a data access error. Please retry; "
                    + "quote the correlation id if the problem persists.";

    /**
     * :purpose: Logger for expected client/business error outcomes; handled cases
     *     are recorded at WARN without stack traces.
     */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** :purpose: Caller-facing message for a body refused by the shared request-size cap. */
    /**
     * :purpose: Stable, status-independent codes published in ``ErrorResponse.errorCode``
     *  so a caller can branch on the KIND of failure without parsing the human message
     *  (which is a frozen legacy literal) or overloading the HTTP status. Every code is
     *  non-sensitive and describes only the class of outcome.
     */
    private static final String ERROR_CODE_VALIDATION = "VALIDATION_FAILED";

    /** :purpose: Code for a keyed record that does not exist. */
    private static final String ERROR_CODE_NOT_FOUND = "RECORD_NOT_FOUND";

    /** :purpose: Code for a record another writer changed first (``@Version`` conflict). */
    private static final String ERROR_CODE_CONFLICT = "OPTIMISTIC_LOCK_CONFLICT";

    private static final String MSG_BODY_TOO_LARGE = "Request body exceeds the configured maximum size";

    /** :purpose: Caller-facing message for a body the framework could not parse. */
    private static final String MSG_BODY_MALFORMED = "Malformed request body";

    /**
     * :purpose: Optional, service-supplied mapping from a request property to the legacy message
     *     its screen edit owns, used when a submitted value cannot be converted to the property's
     *     declared type. ``null`` when the owning service contributes none.
     */
    private TypeMismatchMessageResolver typeMismatchMessageResolver;

    /** :purpose: Caller-facing message for an internal fault; the detail stays in the log. */
    private static final String MSG_UNEXPECTED = "An unexpected error occurred";

    /**
     * :purpose: ``Retry-After`` value, in seconds, advertised with a dependency-outage
     *     ``503``; those outages are transient by definition.
     */
    private static final String RETRY_AFTER_SECONDS = "10";

    /**
     * :purpose: ``Retry-After`` value, in seconds, advertised with a capacity refusal. It
     *     matches the order of magnitude of the HikariCP acquisition timeout the services
     *     configure (``spring.datasource.hikari.connection-timeout``), so a client that
     *     honours the header retries after the contended window rather than immediately -- a
     *     shorter wait than the outage value above, because saturation clears as soon as an
     *     in-flight request returns its connection.
     */
    private static final String RETRY_AFTER_CAPACITY_SECONDS = "5";

    /**
     * :purpose: Caller-facing message for a request that could not obtain a database
     *     connection within the configured acquisition timeout. Distinct from
     *     {@link #DEPENDENCY_FAILURE_MESSAGE} because the condition is transient and
     *     retryable rather than a fault in the request or the service.
     */
    private static final String MSG_CAPACITY_EXHAUSTED =
            "The service is temporarily at capacity. Please retry; "
                    + "quote the correlation id if the problem persists.";

    /**
     * :purpose: Depth bound on cause-chain inspection, so a self-referential or pathological
     *     chain cannot make classification loop.
     */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * :purpose: Fixed text for a request that matched no handler. Spring's own detail for a
     *     ``NoResourceFoundException`` names the resolver that declined it — ``No static
     *     resource accounts/00000000001.`` — which tells a caller which internal handler
     *     chain the path fell through and echoes the path a second time. A caller can act on
     *     neither, so the status is reported and the detail is not.
     */
    private static final String MSG_NOT_FOUND = "The requested resource was not found";

    /**
     * :purpose: Assemble a populated {@link ErrorResponse} body for a handled failure.
     * :param status: the HTTP status mapped for the failure.
     * :param message: the non-sensitive detail message surfaced to the caller.
     * :param request: the current web request, used to derive the request path.
     * :returns: an {@link ErrorResponse} carrying the status, reason phrase, message, path, and trace id.
     */
    private ErrorResponse buildBody(HttpStatus status, String message, WebRequest request) {
        return ErrorResponseFactory.build(status, message, request);
    }

    /**
     * :purpose: Derive the request URI from a {@link WebRequest} without the servlet API.
     * :param request: the current web request.
     * :returns: the request path with the leading ``"uri="`` stripped, or the raw description when it is absent.
     */
    private String extractPath(WebRequest request) {
        return ErrorResponseFactory.path(request);
    }

    /**
     * :purpose: Derive the request URI for LOG output with every PAN-shaped digit run
     *     masked. A resource path can embed a card number (``/cards/{pan}``), and a card
     *     number must not be retained in a log file even though the API response itself
     *     still carries the field the legacy screens display.
     * :param request: the current web request.
     * :returns: the request path with a card-number segment reduced to its last four
     *     digits and every other segment intact.
     * :note: Path-POSITIONAL masking: only the ``/cards/{cardNumber}`` variable carries a
     *     PAN, and masking every PAN-shaped digit run also redacted the 16-character
     *     transaction id, reporting a path the caller never requested.
     */
    private String loggedPath(WebRequest request) {
        return SensitiveDataMasker.maskPath(extractPath(request));
    }

    /**
     * :purpose: Mask a detail message before it is logged, so a message that echoes a
     *     card number back (for example a not-found or validation message) cannot put a
     *     PAN in a log record.
     * :param message: the exception detail message, possibly ``null``.
     * :returns: the masked message.
     */
    private String loggedMessage(String message) {
        return SensitiveDataMasker.maskPan(message);
    }



    /**
     * :purpose: Map a not-found lookup (legacy CICS ``RESP(NOTFND)``) to HTTP 404.
     * :param ex: the record-not-found exception carrying the detail message.
     * :param request: the current web request.
     * :returns: a ``404 Not Found`` {@link ResponseEntity} wrapping the error body.
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleRecordNotFound(RecordNotFoundException ex, WebRequest request) {
        HttpStatus status = HttpStatus.NOT_FOUND;
        ErrorResponse body = buildBody(status, ex.getMessage(), request);
        body.setErrorCode(ERROR_CODE_NOT_FOUND);
        log.warn("Record not found at {}: {}", loggedPath(request), loggedMessage(ex.getMessage()));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * :purpose: Map an optimistic-lock conflict (legacy ``DATA-WAS-CHANGED-BEFORE-UPDATE``) to HTTP 409.
     * :param ex: the conflict exception carrying the frozen legacy conflict message.
     * :param request: the current web request.
     * :returns: a ``409 Conflict`` {@link ResponseEntity} wrapping the error body.
     */
    @ExceptionHandler(OptimisticLockConflictException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockConflict(OptimisticLockConflictException ex, WebRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        ErrorResponse body = buildBody(status, ex.getMessage(), request);
        body.setErrorCode(ERROR_CODE_CONFLICT);
        log.warn("Optimistic-lock conflict at {}: {}", loggedPath(request), loggedMessage(ex.getMessage()));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * :purpose: Map a posting reject (legacy ``CBTRN02C`` codes 100-103, including the over-limit and expired subtypes) to HTTP 422.
     * :param ex: the reject exception carrying the numeric reject code and its exact description.
     * :param request: the current web request.
     * :returns: a ``422 Unprocessable Entity`` {@link ResponseEntity} wrapping the error body.
     */
    // HttpStatus.UNPROCESSABLE_ENTITY is deprecated in favour of UNPROCESSABLE_CONTENT,
    // but the two constants carry different reason phrases and the phrase is copied into
    // the ``error`` field of every error body. Switching would silently change a
    // published response field for an unchanged input, so the original constant is kept
    // deliberately; both denote HTTP 422.
    @SuppressWarnings("deprecation")
    @ExceptionHandler(TransactionRejectException.class)
    public ResponseEntity<ErrorResponse> handleTransactionReject(TransactionRejectException ex, WebRequest request) {
        HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
        ErrorResponse body = buildBody(status, ex.getMessage(), request);
        // The legacy reject code (100-103) is the discrete outcome identifier CBTRN02C
        // wrote into the reject trailer, so it is published as the error code verbatim.
        body.setErrorCode(String.valueOf(ex.getRejectCode()));
        log.warn("Transaction rejected (code {}) at {}: {}", ex.getRejectCode(), loggedPath(request), loggedMessage(ex.getMessage()));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * :purpose: Map a failure to protect or recover a regulated attribute to HTTP 500.
     *     This is a data-at-rest or key-configuration fault, never a caller error, so it
     *     must not be reported as ``400``; the response carries a generic message while
     *     the operator-facing detail goes to the log.
     * :param ex: the crypto exception; its message names the fault category and never
     *     contains the protected value.
     * :param request: the current web request.
     * :returns: a ``500 Internal Server Error`` {@link ResponseEntity} wrapping the error body.
     */
    @ExceptionHandler(SensitiveDataCryptoException.class)
    public ResponseEntity<ErrorResponse> handleSensitiveDataCrypto(SensitiveDataCryptoException ex, WebRequest request) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        log.error("Sensitive attribute could not be processed at {}: {}", loggedPath(request), loggedMessage(ex.getMessage()), ex);
        return ResponseEntity.status(status)
                .body(buildBody(status, "Sensitive data could not be processed", request));
    }

    /**
     * :purpose: Translate a failure to encrypt or decrypt a protected customer or card
     *  attribute into a controlled server-side error carrying the standard envelope and
     *  the frozen non-disclosing message, instead of letting an ORM-wrapped
     *  cryptographic fault escape as a framework error page. At-rest field encryption is
     *  a target-only control for AAP 0.6.7 with no legacy analogue.
     * :param ex: the encryption/decryption failure.
     * :param request: the current web request, used for the ``path`` field.
     * :returns: HTTP 500 with the standard ``ErrorResponse`` envelope.
     */
    @ExceptionHandler(PiiEncryptionException.class)
    public ResponseEntity<ErrorResponse> handlePiiEncryptionFailure(PiiEncryptionException ex,
                                                                    WebRequest request) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        // The cause carries the cryptographic detail; it is logged, never returned.
        log.error("Protected-data conversion failed at {}", loggedPath(request), ex);
        return ResponseEntity.status(status)
                .body(buildBody(status, PiiEncryptionException.MESSAGE, request));
    }

    /**
     * :purpose: Map a collaborating service that could not be REACHED to HTTP 503 with a
     *     ``Retry-After`` hint, carrying the caller's own detail message unchanged. The call
     *     never landed, so nothing was attempted downstream and the identical request may be
     *     retried; reporting it as ``400`` would tell the caller its request was at fault.
     * :param ex: the upstream-unavailable exception carrying the detail message, which for a
     *     legacy-derived path is the frozen legacy literal.
     * :param request: the current web request.
     * :returns: a ``503 Service Unavailable`` {@link ResponseEntity} wrapping the error body.
     * :note: Declared ahead of {@link #handleCardDemoException} in the type hierarchy, so
     *     Spring's most-specific-handler selection keeps a transport failure out of the 400
     *     mapping while the observable message stays byte-identical.
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamUnavailable(UpstreamUnavailableException ex,
                                                                   WebRequest request) {
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        log.error("Upstream service unreachable at {}: {}", loggedPath(request),
                loggedMessage(ex.getMessage()), ex);
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(buildBody(status, ex.getMessage(), request));
    }

    /**
     * :purpose: Map a named-field input edit failure to HTTP 400, reporting the legacy
     *     message on ``message`` and the rejected field in ``fieldErrors`` so the calling
     *     screen can paint that field ``DFHRED`` and park the cursor on it exactly as
     *     ``3300-SETUP-SCREEN-ATTRS`` does when the edit runs inside the screen program.
     * :param ex: the field-edit failure carrying the field name and the legacy message.
     * :param request: the current web request.
     * :returns: a ``400 Bad Request`` {@link ResponseEntity} whose body names the field.
     */
    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ErrorResponse> handleFieldValidation(FieldValidationException ex, WebRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ErrorResponse body = buildBody(status, ex.getMessage(), request);
        for (String field : ex.getFields()) {
            body.addFieldError(field, ex.getMessage());
        }
        log.warn("Field edit failed at {}: reporting '{}' for field(s) {}",
                loggedPath(request), loggedMessage(ex.getMessage()), ex.getFields());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * :purpose: Map an uncategorized base {@link CardDemoException} (or any subtype without a
     *     more specific handler) to HTTP 400.
     * :param ex: the domain exception carrying the detail message.
     * :param request: the current web request.
     * :returns: a ``400 Bad Request`` {@link ResponseEntity} wrapping the error body.
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ErrorResponse> handleCardDemoException(CardDemoException ex, WebRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ErrorResponse body = buildBody(status, ex.getMessage(), request);
        // The envelope carries a code and, when the failure names one, the offending
        // field. Without them a caller could only restate the message: it could not mark
        // the control that was refused or place the cursor on it, which is what the
        // legacy screens do beside every edit failure.
        body.setErrorCode(ex.getErrorCode() == null ? ERROR_CODE_VALIDATION : ex.getErrorCode());
        if (ex.getField() != null) {
            body.addFieldError(ex.getField(), ex.getMessage());
        }
        log.warn("Domain error at {}: {}", loggedPath(request), loggedMessage(ex.getMessage()));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * :purpose: Map a request-body bean-validation failure to HTTP 400 with per-field messages.
     * :param ex: the validation exception carrying the binding-result field errors.
     * :param request: the current web request.
     * :returns: a ``400 Bad Request`` {@link ResponseEntity} whose body lists each field error.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldError> ordered = orderFieldErrors(ex);
        if (ordered.isEmpty()) {
            return super.handleMethodArgumentNotValid(ex, headers, status, request);
        }
        FieldError winner = ordered.get(0);
        ErrorResponse body = buildBody(HttpStatus.BAD_REQUEST, winner.getDefaultMessage(), request);
        body.setErrorCode(ERROR_CODE_VALIDATION);
        body.addFieldError(winner.getField(), winner.getDefaultMessage());
        log.warn("Request validation failed at {}: reporting '{}' for field '{}' ({} violation(s) evaluated)",
                loggedPath(request), winner.getDefaultMessage(), winner.getField(), ordered.size());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(body);
    }

    /**
     * :purpose: Order the bean-validation field errors the way the legacy screen
     *     programs evaluate their edits -- top to bottom in screen (declaration)
     *     order, and for a single field presence before format -- so the first
     *     element is the one the COBOL ``EVALUATE TRUE`` would have surfaced.
     * :param ex: the validation exception carrying the binding-result field errors.
     * :returns: the field errors in legacy evaluation order; empty when none.
     */
    private List<FieldError> orderFieldErrors(MethodArgumentNotValidException ex) {
        List<FieldError> errors = new ArrayList<>(ex.getBindingResult().getFieldErrors());
        List<String> declarationOrder = declaredFieldOrder(ex.getBindingResult().getTarget());
        errors.sort(Comparator
                .comparingInt((FieldError fe) -> {
                    int index = declarationOrder.indexOf(fe.getField());
                    return index < 0 ? Integer.MAX_VALUE : index;
                })
                .thenComparingInt(fe -> isPresenceConstraint(fe) ? 0 : 1)
                .thenComparing(FieldError::getField));
        return errors;
    }

    /**
     * :purpose: Read the declared field order of the validated payload, which
     *     mirrors the BMS screen field order the legacy programs edit in.
     * :param target: the validated request object; may be ``null``.
     * :returns: the declared field names from the type and its supertypes, or an
     *     empty list when the target is unavailable.
     */
    private List<String> declaredFieldOrder(Object target) {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> type = (target == null) ? null : target.getClass();
                type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    names.add(field.getName());
                }
            }
        }
        return new ArrayList<>(names);
    }

    /**
     * :purpose: Report whether a field error came from a presence constraint, which
     *     the legacy programs always edit before a format or range rule.
     * :param error: the field error to classify.
     * :returns: ``true`` for ``@NotNull``/``@NotBlank``/``@NotEmpty`` violations.
     */
    private boolean isPresenceConstraint(FieldError error) {
        String code = error.getCode();
        return "NotNull".equals(code) || "NotBlank".equals(code) || "NotEmpty".equals(code);
    }

    /**
     * :purpose: Map a parameter/path bean-validation failure to HTTP 400 with per-field messages.
     * :param ex: the constraint-violation exception carrying the violated constraints.
     * :param request: the current web request.
     * :returns: a ``400 Bad Request`` {@link ResponseEntity} whose body lists each constraint violation.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ConstraintViolation<?> winner = ex.getConstraintViolations().stream().findFirst().orElse(null);
        String message = (winner == null) ? status.getReasonPhrase() : winner.getMessage();
        ErrorResponse body = buildBody(status, message, request);
        body.setErrorCode(ERROR_CODE_VALIDATION);
        if (winner != null) {
            body.addFieldError(winner.getPropertyPath().toString(), winner.getMessage());
        }
        log.warn("Constraint validation failed at {}: reporting '{}' ({} violation(s) evaluated)",
                loggedPath(request), loggedMessage(message), ex.getConstraintViolations().size());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * :purpose: Guarantee that every failure reaching the web layer answers with the
     *     shared {@link ErrorResponse} body, instead of the container's default error page
     *     which carries no trace id at all. A failure that already declares its own HTTP
     *     status keeps it (405, 415, and an unmapped path's 404 among them, together with
     *     any response headers such as ``Allow``); anything else is an internal fault and
     *     reports ``500``.
     * :param ex: the exception no more specific handler claimed.
     * :param request: the current web request.
     * :returns: a {@link ResponseEntity} carrying the preserved or ``500`` status and the
     *     shared error body.
     * :raises Exception: re-thrown for a Spring Security authentication or authorization
     *     failure, which must continue to the security filter chain so the configured
     *     ``401``/``403`` handling and its audit record still apply.
     * :note: A body refused by the shared request-size cap reports ``413`` here as well as
     *     in {@link #handleUnreadableRequestBody}. At the api-gateway the proxy streams the
     *     request body downstream from its own publisher thread, so the size signal is
     *     raised inside ``RestClientProxyExchange.copyBody`` and reaches the web layer
     *     unwrapped rather than inside a message-converter exception; without this branch
     *     an oversized chunked request at the edge would report ``500``.
     * :note: An internal fault reports a fixed message only; the diagnostic detail and
     *     stack trace go to the log, never to the caller.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnhandled(Exception ex, WebRequest request) throws Exception {
        if (ex instanceof AuthenticationException || ex instanceof AccessDeniedException) {
            throw ex;
        }
        if (RequestSizeLimitFilter.isSizeExceededSignal(ex)) {
            return contentTooLarge(request);
        }
        if (ex instanceof org.springframework.web.ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            log.warn("Request rejected with {} at {}: {}",
                    status.value(), loggedPath(request), ex.getClass().getSimpleName());
            return ResponseEntity.status(status)
                    .headers(errorResponse.getHeaders())
                    .body(buildBody(status,
                            safeFrameworkMessage(status, frameworkDetail(errorResponse, status)),
                            request));
        }
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        log.error("Unhandled failure at {}: {}", loggedPath(request), ex.getClass().getName(), ex);
        return ResponseEntity.status(status).body(buildBody(status, MSG_UNEXPECTED, request));
    }

    /**
     * :purpose: Classify a request body the framework could not read. A body refused by
     *     the shared request-size cap reports ``413``; a genuinely malformed body reports
     *     ``400``. Without this classification the size signal reaches the default
     *     resolver wrapped in a converter exception and is reported as ``400``, hiding the
     *     fact that the request was refused for its size.
     * :param ex: the unreadable-body exception raised by the message converter.
     * :param request: the current web request.
     * :returns: a ``413 Content Too Large`` or ``400 Bad Request`` {@link ResponseEntity}
     *     wrapping the error body.
     * :note: Reached through the inherited {@code handleHttpMessageNotReadable} hook rather
     *     than through a second ``@ExceptionHandler`` for the same type, which would be an
     *     ambiguous mapping in this class.
     * :note: Neither message echoes the submitted content: the converter's own message
     *     quotes the offending payload, which must never be reflected to the caller or
     *     written to the log.
     */
    public ResponseEntity<ErrorResponse> handleUnreadableRequestBody(HttpMessageNotReadableException ex,
                                                                    WebRequest request) {
        if (RequestSizeLimitFilter.isSizeExceededSignal(ex)) {
            return contentTooLarge(request);
        }
        HttpStatus status = HttpStatus.BAD_REQUEST;
        // A value that is present but of the wrong type is a FIELD edit failure, not a malformed
        // document: the JSON parsed, and exactly one property could not be converted. Report the
        // legacy message the screen edit owns for that property, so binding a non-numeric value
        // into a numeric field reads the same as failing the edit itself.
        String property = mismatchedProperty(ex);
        if (property != null) {
            String fieldMessage = resolveTypeMismatchMessage(property);
            if (fieldMessage != null) {
                log.warn("Type mismatch on property {} at {}", property, loggedPath(request));
                ErrorResponse body = buildBody(status, fieldMessage, request);
                body.setFieldErrors(Map.of(property, fieldMessage));
                return ResponseEntity.status(status).body(body);
            }
        }
        log.warn("Malformed request body at {}", loggedPath(request));
        return ResponseEntity.status(status).body(buildBody(status, MSG_BODY_MALFORMED, request));
    }

    /**
     * :purpose: Identify the single request property whose submitted value could not be converted
     *     to the property's declared type.
     * :param ex: the unreadable-body exception raised by the message converter.
     * :returns: the dotted property path, or ``null`` when the failure is not a per-property type
     *     mismatch (a truncated document or a syntax error, for example) and therefore has no
     *     field of its own to report.
     * :note: Only the property NAMES are read from the converter's path. The converter's message
     *     and its ``getValue()`` both quote the submitted content, which must never be reflected to
     *     the caller or written to the log.
     */
    private String mismatchedProperty(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (!(cause instanceof MismatchedInputException mismatch)) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : mismatch.getPath()) {
            String name = reference.getPropertyName();
            if (name == null) {
                // An index rather than a named field (an array element); the enclosing property is
                // the most specific thing that can be named.
                continue;
            }
            if (!path.isEmpty()) {
                path.append('.');
            }
            path.append(name);
        }
        return path.isEmpty() ? null : path.toString();
    }

    /**
     * :purpose: Ask the service's resolver, when it supplied one, for the legacy message belonging
     *     to a property that failed type conversion.
     * :param property: the dotted property path.
     * :returns: the resolved message, or ``null`` when no resolver is present or none owns the
     *     property.
     */
    private String resolveTypeMismatchMessage(String property) {
        TypeMismatchMessageResolver resolver = this.typeMismatchMessageResolver;
        if (resolver == null) {
            return null;
        }
        String message = resolver.messageFor(property);
        return (message == null || message.isBlank()) ? null : message;
    }

    /**
     * :purpose: Accept the optional, service-supplied resolver that maps a request property to the
     *     legacy message its screen edit owns.
     * :param typeMismatchMessageResolver: the resolver contributed by the owning service, or
     *     ``null`` when the service contributes none and the generic malformed-body message
     *     applies.
     * :note: Injected through a setter rather than a constructor so this advice keeps a no-argument
     *     constructor and remains directly instantiable in unit tests.
     */
    @Autowired(required = false)
    public void setTypeMismatchMessageResolver(TypeMismatchMessageResolver typeMismatchMessageResolver) {
        this.typeMismatchMessageResolver = typeMismatchMessageResolver;
    }

    /**
     * :purpose: Route the inherited unreadable-body handling through
     *     {@link #handleUnreadableRequestBody} so the size-versus-malformed classification
     *     applies without declaring a duplicate mapping for the same exception type.
     * :param ex: the unreadable-body exception raised by the message converter.
     * :param headers: the response headers the framework assembled.
     * :param status: the status the framework resolved.
     * :param request: the current web request.
     * :returns: the classified response entity.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ResponseEntity<ErrorResponse> classified = handleUnreadableRequestBody(ex, request);
        return ResponseEntity.status(classified.getStatusCode())
                .headers(classified.getHeaders())
                .body(classified.getBody());
    }

    /**
     * :purpose: Answer a request whose body was refused by the shared size cap.
     * :param request: the current web request.
     * :returns: a ``413 Content Too Large`` {@link ResponseEntity} wrapping the shared
     *     error body.
     * :note: The message states only that the cap was exceeded; the submitted content is
     *     never reflected to the caller nor written to the log.
     */
    private ResponseEntity<ErrorResponse> contentTooLarge(WebRequest request) {
        HttpStatus status = HttpStatus.CONTENT_TOO_LARGE;
        log.warn("Request body exceeds the configured maximum size at {}", loggedPath(request));
        return ResponseEntity.status(status).body(buildBody(status, MSG_BODY_TOO_LARGE, request));
    }

    /**
     * :purpose: Choose the caller-facing detail for a framework failure that declares its
     *     own status.
     * :param errorResponse: the framework failure.
     * :param status: the status it declares.
     * :returns: the failure's own problem detail when it has one, otherwise the status
     *     reason phrase.
     */
    private String frameworkDetail(org.springframework.web.ErrorResponse errorResponse, HttpStatus status) {
        String detail = errorResponse.getBody() == null ? null : errorResponse.getBody().getDetail();
        return detail == null || detail.isBlank() ? status.getReasonPhrase() : detail;
    }

    /**
     * :purpose: Answer a request that matched no handler and no static resource with the
     *     documented envelope carrying only the status reason phrase. The framework's own
     *     detail for this failure names its internal resource-resolution machinery (for
     *     example ``No static resource admin.``), which is an implementation detail of the
     *     server rather than an outcome of the API, so it is replaced.
     * :param ex: the resource-not-found failure raised by the resource handler.
     * :param headers: the response headers the framework assembled.
     * :param status: the resolved HTTP status (``404``).
     * :param request: the current web request.
     * :returns: a ``404 Not Found`` {@link ResponseEntity} wrapping the error body.
     * :note: Overrides the inherited hook rather than declaring a second
     *     ``@ExceptionHandler`` for the same type, which would be an ambiguous mapping in
     *     this class.
     * :note: The status was never wrong; the MESSAGE was. ``NoResourceFoundException`` is a
     *     ``org.springframework.web.ErrorResponse``, so the generic path copied its framework
     *     detail text and ``GET /billpay/90000000001`` answered ``"No static resource
     *     billpay/90000000001."`` - wording that tells a caller of a JSON API that the service
     *     serves static files, and names an internal handler.
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
                                                                    HttpHeaders headers,
                                                                    HttpStatusCode status,
                                                                    WebRequest request) {
        log.warn("No handler mapped for {}: {}", loggedPath(request), ex.getClass().getSimpleName());
        return unmatchedPath(headers, status, request);
    }

    /**
     * :purpose: Answer a request the dispatcher could not map to any handler with the same
     *     neutral envelope as {@link #handleNoResourceFoundException}, so the two ways an
     *     unmatched path can be reported are indistinguishable to a client.
     * :param ex: the no-handler-found failure raised by the dispatcher.
     * :param headers: the response headers the framework assembled.
     * :param status: the resolved HTTP status (``404``).
     * :param request: the current web request.
     * :returns: a ``404 Not Found`` {@link ResponseEntity} wrapping the error body.
     */
    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException ex,
                                                                   HttpHeaders headers,
                                                                   HttpStatusCode status,
                                                                   WebRequest request) {
        return unmatchedPath(headers, status, request);
    }

    /**
     * :purpose: Build the neutral ``404`` answer shared by both unmatched-path hooks.
     * :param headers: the response headers the framework assembled.
     * :param status: the resolved HTTP status, defaulted to ``404`` when unresolvable.
     * :param request: the current web request.
     * :returns: the response entity carrying the envelope with the status reason phrase as
     *     its message.
     */
    private ResponseEntity<Object> unmatchedPath(HttpHeaders headers,
                                                 HttpStatusCode status,
                                                 WebRequest request) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        if (resolved == null) {
            resolved = HttpStatus.NOT_FOUND;
        }
        log.warn("No handler for {}: reporting {}", loggedPath(request), resolved.value());
        return ResponseEntity.status(resolved).headers(headers)
                .body(buildBody(resolved, MSG_NOT_FOUND, request));
    }

    /**
     * :purpose: Keep a framework-supplied detail out of the envelope when the status makes it
     *     a description of this application's internals rather than of the caller's request.
     * :param status: the resolved HTTP status.
     * :param message: the message assembled so far, normally the framework's own detail.
     * :returns: the fixed not-found text for a ``404``, and ``message`` unchanged for every
     *     other status — a ``405`` naming the allowed methods and a ``415`` naming the
     *     supported media types describe the REQUEST and stay as they are.
     * :note: A domain ``404`` never reaches here: {@link RecordNotFoundException} has its own
     *     handler and keeps its verbatim legacy literal (``Account ID NOT found...`` and its
     *     siblings). This governs only the framework's routing ``404``.
     */
    private String safeFrameworkMessage(HttpStatus status, String message) {
        return status == HttpStatus.NOT_FOUND ? MSG_NOT_FOUND : message;
    }

    /**
     * :purpose: Force every response produced by the inherited framework-exception
     *     handling through the documented {@link ErrorResponse} envelope, replacing
     *     the RFC 9457 ``ProblemDetail`` body Spring builds by default. This is the
     *     single interception point that makes 404, 405, 415, an unreadable body and
     *     a parameter type mismatch answer with the same contract as the domain
     *     failures.
     * :param body: the body Spring assembled, normally a {@link ProblemDetail}.
     * :param headers: the response headers Spring assembled (for example ``Allow``).
     * :param statusCode: the resolved HTTP status.
     * :param request: the current web request.
     * :returns: the response entity carrying the {@link ErrorResponse} envelope.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body,
                                                          HttpHeaders headers,
                                                          HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ErrorResponse) {
            return super.createResponseEntity(body, headers, statusCode, request);
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = status.getReasonPhrase();
        if (body instanceof ProblemDetail detail && detail.getDetail() != null
                && !detail.getDetail().isBlank()) {
            message = detail.getDetail();
        }
        return super.createResponseEntity(buildBody(status, safeFrameworkMessage(status, message),
                request), headers, statusCode, request);
    }

    /**
     * :purpose: Map a framework-level optimistic-locking failure that was not already translated
     *     into {@link OptimisticLockConflictException} to HTTP 409, carrying the frozen legacy
     *     message so the caller sees the same outcome as the COBOL
     *     ``DATA-WAS-CHANGED-BEFORE-UPDATE`` path.
     * :param ex: the Spring optimistic-locking failure (for example an
     *     ``ObjectOptimisticLockingFailureException`` raised while flushing at commit time, after
     *     the service-level catch has already returned).
     * :param request: the current web request.
     * :returns: a ``409 Conflict`` {@link ResponseEntity} wrapping the error body.
     * :note: Declared explicitly so the broader {@link #handleDataAccess} mapping below (a
     *     ``DataAccessException`` supertype) can never downgrade a genuine concurrency conflict
     *     into a 500. Spring selects the most specific handler for the thrown type, so this method
     *     wins for locking failures.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException ex,
                                                                       WebRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        log.warn("Optimistic-locking failure at {}: {}", loggedPath(request), loggedMessage(ex.getMessage()));
        return ResponseEntity.status(status)
                .body(buildBody(status, OptimisticLockConflictException.MESSAGE, request));
    }

    /**
     * :purpose: Map a datastore or session-store that timed out or could not be reached to
     *     HTTP 503 with a ``Retry-After`` hint, rather than to the 500 the generic data-access
     *     mapping below would report. A Redis command timeout on the session read, or an
     *     exhausted/unreachable connection pool, is a transient dependency outage that the
     *     caller may retry -- not a fault in the request or in the application.
     * :param ex: the timeout or resource-failure exception raised by the datastore or the
     *     session store; both are ``DataAccessException`` subtypes.
     * :param request: the current web request.
     * :returns: a ``503 Service Unavailable`` {@link ResponseEntity} carrying the same generic
     *     detail the pre-dispatcher {@link DatastoreOutageErrorFilter} writes, the correlation
     *     id, and the trace id, so a caller sees ONE outage contract wherever in the chain the
     *     failure was detected.
     * :note: The two ``org.springframework.dao`` types are named rather than the Redis or JDBC
     *     specific ones: Spring's exception translation maps a Lettuce command timeout to
     *     ``QueryTimeoutException`` and a connection failure to
     *     ``RedisConnectionFailureException`` (a ``DataAccessResourceFailureException``), so
     *     this advice stays compilable in the api-gateway, which carries neither spring-orm nor
     *     a JDBC driver.
     */
    @ExceptionHandler({QueryTimeoutException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ErrorResponse> handleInfrastructureUnavailable(DataAccessException ex,
                                                                        WebRequest request) {
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        log.error("Datastore unavailable at {}: {}", loggedPath(request),
                loggedMessage(ex.getMessage()), ex);
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(buildBody(status, DatastoreOutageErrorFilter.MESSAGE, request));
    }

    /**
     * :purpose: Map an infrastructure data-access failure -- ``JpaSystemException`` and every other
     *     ``DataAccessException`` raised while reading or writing PostgreSQL -- to a uniform HTTP 500
     *     {@link ErrorResponse} instead of Spring Boot's generic ``/error`` body.
     * :param ex: the data-access exception thrown by the repository or the persistence provider.
     * :param request: the current web request.
     * :returns: a ``500 Internal Server Error`` {@link ResponseEntity} whose body carries the
     *     correlation and trace ids of the failing request and a generic, non-sensitive message.
     * :note: Logged at ERROR with the stack trace because, unlike the business outcomes above, an
     *     infrastructure failure is never expected.
     * :note: ``DataAccessException`` (spring-tx) is referenced rather than ``JpaSystemException``
     *     (spring-orm) on purpose: this advice is also imported by the api-gateway, which has no
     *     spring-orm on its classpath. ``JpaSystemException`` is a ``DataAccessException``, so it is
     *     handled here in every JPA service.
     * :note: An UNREACHABLE datastore is answered by {@link #handleDatastoreUnavailable}
     *     below with a ``503`` instead, which Spring selects for those narrower types.
     */

    /**
     * :purpose: Map a transaction-infrastructure failure -- most often
     *     ``CannotCreateTransactionException`` ("Could not open JPA EntityManager for
     *     transaction") when the database is unreachable and the connection pool times out --
     *     to the SAME ``500`` body a data-access failure produces.
     * :param ex: the transaction failure raised while beginning, committing or rolling back.
     * :param request: the current web request.
     * :returns: a ``500 Internal Server Error`` {@link ResponseEntity} carrying
     *     {@link #DEPENDENCY_FAILURE_MESSAGE}.
     * :note: ``TransactionException`` is NOT a ``DataAccessException``, so one PostgreSQL
     *     outage answered with two different messages depending on whether the read happened
     *     inside a transaction: a repository call that failed on its own reported the
     *     data-access text, while ``@Transactional`` reads reported the generic "An unexpected
     *     error occurred". A caller could not tell the two apart, and neither could an
     *     operator grepping for one string.
     * :note: ``OptimisticLockingFailureException`` is a ``DataAccessException`` and keeps its
     *     own ``409`` mapping above; a concurrency conflict is a business outcome, not an
     *     outage.
     */
    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<ErrorResponse> handleTransactionInfrastructure(TransactionException ex,
                                                                        WebRequest request) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        log.error("Transaction infrastructure failure at {}: {}", loggedPath(request),
                loggedMessage(ex.getMessage()), ex);
        return ResponseEntity.status(status).body(buildBody(status, DEPENDENCY_FAILURE_MESSAGE, request));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex, WebRequest request) {
        if (isConnectionAcquisitionTimeout(ex)) {
            return capacityExhausted(ex, request);
        }
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        // The path and the driver message are masked: a resource path embeds the card
        // number (/cards/{pan}) and a failing statement echoes its bind values, so this
        // was the one log line that still wrote a full PAN to disk (CWE-532).
        log.error("Data access failure at {}: {}", loggedPath(request),
                loggedMessage(ex.getMessage()), ex);
        return ResponseEntity.status(status).body(buildBody(status, DEPENDENCY_FAILURE_MESSAGE, request));
    }

    /**
     * :purpose: Map a transaction that could not be STARTED to HTTP status. A request that
     *     never obtained a database connection within the pool's acquisition timeout is a
     *     transient capacity refusal and reports ``503`` with ``Retry-After``; any other
     *     failure to begin a transaction remains a ``500``.
     * :param ex: the transaction-creation failure raised by the transaction manager.
     * :param request: the current web request.
     * :returns: a ``503 Service Unavailable`` or ``500 Internal Server Error``
     *     {@link ResponseEntity} wrapping the shared error body.
     * :note: ``CannotCreateTransactionException`` is a ``TransactionException``, NOT a
     *     ``DataAccessException``, so it reached the generic handler and every pool-timeout
     *     refusal was reported as ``500`` with no retry signal -- telling the caller the
     *     service is broken when it is merely saturated, and giving load shedders and
     *     clients nothing to act on.
     * :note: ``org.springframework.transaction`` ships in the same artifact as
     *     ``org.springframework.dao``, so referencing it keeps this shared advice compilable
     *     in the api-gateway, which has no ``spring-orm`` on its classpath.
     */
    @ExceptionHandler(org.springframework.transaction.CannotCreateTransactionException.class)
    public ResponseEntity<ErrorResponse> handleCannotCreateTransaction(
            org.springframework.transaction.CannotCreateTransactionException ex, WebRequest request) {
        if (isConnectionAcquisitionTimeout(ex)) {
            return capacityExhausted(ex, request);
        }
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        log.error("Could not begin a transaction at {}: {}", loggedPath(request),
                loggedMessage(ex.getMessage()), ex);
        return ResponseEntity.status(status).body(buildBody(status, DEPENDENCY_FAILURE_MESSAGE, request));
    }

    /**
     * :purpose: Build the shared capacity-refusal response: ``503`` with ``Retry-After`` and
     *     a generic, retryable message.
     * :param ex: the failure that carried the pool-timeout signal.
     * :param request: the current web request.
     * :returns: a ``503 Service Unavailable`` {@link ResponseEntity} carrying ``Retry-After``.
     * :note: Logged at WARN, not ERROR: saturation is an expected operating condition that
     *     the ``hikaricp_connections_timeout_total`` counter and the pool dashboard already
     *     quantify, and an ERROR-per-refused-request would bury the genuine faults during
     *     exactly the window an operator is reading the log.
     */
    private ResponseEntity<ErrorResponse> capacityExhausted(Exception ex, WebRequest request) {
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        log.warn("Connection pool exhausted at {}: {}", loggedPath(request),
                loggedMessage(ex.getMessage()));
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_CAPACITY_SECONDS)
                .body(buildBody(status, MSG_CAPACITY_EXHAUSTED, request));
    }

    /**
     * :purpose: Report whether a failure was caused by the connection pool refusing to hand
     *     out a connection within its acquisition timeout.
     * :param throwable: the failure to classify; may be ``null``.
     * :returns: ``true`` when the cause chain holds a
     *     {@link java.sql.SQLTransientConnectionException}, which is what HikariCP raises
     *     when ``connection-timeout`` elapses with the pool saturated.
     * :note: The chain is walked rather than the top-level type inspected, because the same
     *     signal arrives wrapped as a ``CannotCreateTransactionException`` when a
     *     ``@Transactional`` boundary is entered and as a
     *     ``DataAccessResourceFailureException`` when a connection is taken outside one.
     *     The walk is depth-bounded so a self-referential chain cannot loop.
     */
    private static boolean isConnectionAcquisitionTimeout(Throwable throwable) {
        Throwable cursor = throwable;
        for (int depth = 0; cursor != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cursor instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
            Throwable next = cursor.getCause();
            if (next == cursor) {
                return false;
            }
            cursor = next;
        }
        return false;
    }
}
