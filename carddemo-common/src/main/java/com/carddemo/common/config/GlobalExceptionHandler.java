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
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.PiiEncryptionException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.common.exception.TransactionRejectException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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
     * :purpose: Caller-facing message for an infrastructure data-access failure. Deliberately
     *     generic: the exception text of a JPA/JDBC failure can echo SQL, column values and
     *     cryptographic detail, none of which may leave the service.
     */
    private static final String DATA_ACCESS_FAILURE_MESSAGE =
            "Unable to complete the request because of a data access error. Please retry; "
                    + "quote the correlation id if the problem persists.";

    /**
     * :purpose: Logger for expected client/business error outcomes; handled cases
     *     are recorded at WARN without stack traces.
     */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** :purpose: Caller-facing message for a body refused by the shared request-size cap. */
    private static final String MSG_BODY_TOO_LARGE = "Request body exceeds the configured maximum size";

    /** :purpose: Caller-facing message for a body the framework could not parse. */
    private static final String MSG_BODY_MALFORMED = "Malformed request body";

    /** :purpose: Caller-facing message for an internal fault; the detail stays in the log. */
    private static final String MSG_UNEXPECTED = "An unexpected error occurred";

    /**
     * :purpose: MDC key under which Micrometer Tracing publishes the current distributed-trace id;
     *     the same key the structured log encoder renders.
     */
    private static final String TRACE_ID_MDC_KEY = "traceId";

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
     * :returns: the request path with any PAN reduced to its last four digits.
     */
    private String loggedPath(WebRequest request) {
        return SensitiveDataMasker.maskPan(extractPath(request));
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
        log.warn("Record not found at {}: {}", loggedPath(request), loggedMessage(ex.getMessage()));
        return ResponseEntity.status(status).body(buildBody(status, ex.getMessage(), request));
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
        log.warn("Optimistic-lock conflict at {}: {}", loggedPath(request), loggedMessage(ex.getMessage()));
        return ResponseEntity.status(status).body(buildBody(status, ex.getMessage(), request));
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
        log.warn("Transaction rejected (code {}) at {}: {}", ex.getRejectCode(), loggedPath(request), loggedMessage(ex.getMessage()));
        return ResponseEntity.status(status).body(buildBody(status, ex.getMessage(), request));
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
     * :purpose: Map an uncategorized base {@link CardDemoException} (or any subtype without a more specific handler) to HTTP 400.
     * :param ex: the domain exception carrying the detail message.
     * :param request: the current web request.
     * :returns: a ``400 Bad Request`` {@link ResponseEntity} wrapping the error body.
     */
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
        log.error("Protected-data conversion failed at {}", extractPath(request), ex);
        return ResponseEntity.status(status)
                .body(buildBody(status, PiiEncryptionException.MESSAGE, request));
    }

    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ErrorResponse> handleCardDemoException(CardDemoException ex, WebRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        log.warn("Domain error at {}: {}", loggedPath(request), loggedMessage(ex.getMessage()));
        return ResponseEntity.status(status).body(buildBody(status, ex.getMessage(), request));
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
        if (winner != null) {
            body.addFieldError(winner.getPropertyPath().toString(), winner.getMessage());
        }
        log.warn("Constraint validation failed at {}: reporting '{}' ({} violation(s) evaluated)",
                loggedPath(request), loggedMessage(message), ex.getConstraintViolations().size());
        log.warn("Constraint validation failed at {}: {} violation(s)", loggedPath(request), ex.getConstraintViolations().size());
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
                    .body(buildBody(status, frameworkDetail(errorResponse, status), request));
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
        log.warn("Malformed request body at {}", loggedPath(request));
        return ResponseEntity.status(status).body(buildBody(status, MSG_BODY_MALFORMED, request));
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
        return super.createResponseEntity(buildBody(status, message, request), headers, statusCode, request);
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
        log.warn("Optimistic-locking failure at {}: {}", extractPath(request), ex.getMessage());
        return ResponseEntity.status(status)
                .body(buildBody(status, OptimisticLockConflictException.MESSAGE, request));
    }

    /**
     * :purpose: Map an infrastructure data-access failure -- ``JpaSystemException`` and every other
     *     ``DataAccessException`` raised while reading or writing PostgreSQL -- to a uniform HTTP 500
     *     {@link ErrorResponse} instead of Spring Boot's generic ``/error`` body.
     * :param ex: the data-access exception thrown by the repository or the persistence provider.
     * :param request: the current web request.
     * :returns: a ``500 Internal Server Error`` {@link ResponseEntity} whose body carries the
     *     correlation and trace ids of the failing request and a generic, non-sensitive message.
     * :note: This closes the last gap of QA Issue 9. An unmapped ``JpaSystemException`` (raised, for
     *     example, when an attribute converter cannot read a stored value) produced a body with no
     *     correlation id and an ERROR line emitted by the container AFTER the correlation filter had
     *     cleared the MDC, so a 500 could not be tied back to its request. It is logged at ERROR
     *     WITH the stack trace because, unlike the business outcomes above, it is never expected.
     * :note: ``DataAccessException`` (spring-tx) is referenced rather than ``JpaSystemException``
     *     (spring-orm) on purpose: this advice is also imported by the api-gateway, which has no
     *     spring-orm on its classpath. ``JpaSystemException`` is a ``DataAccessException``, so it is
     *     handled here in every JPA service.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex, WebRequest request) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        // The path and the driver message are masked: a resource path embeds the card
        // number (/cards/{pan}) and a failing statement echoes its bind values, so this
        // was the one log line that still wrote a full PAN to disk (CWE-532).
        log.error("Data access failure at {}: {}", loggedPath(request),
                loggedMessage(ex.getMessage()), ex);
        return ResponseEntity.status(status).body(buildBody(status, DATA_ACCESS_FAILURE_MESSAGE, request));
    }
}
