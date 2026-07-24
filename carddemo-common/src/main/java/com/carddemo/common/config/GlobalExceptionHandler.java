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
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.common.exception.TransactionRejectException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

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
 *     pure batch module. Framework MVC exceptions are intentionally left to
 *     Spring Boot's default error handling so their native 4xx statuses (for
 *     example 405, 415) are preserved.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * :purpose: Logger for expected client/business error outcomes; handled cases
     *     are recorded at WARN without stack traces.
     */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * :purpose: Assemble a populated {@link ErrorResponse} body for a handled failure.
     * :param status: the HTTP status mapped for the failure.
     * :param message: the non-sensitive detail message surfaced to the caller.
     * :param request: the current web request, used to derive the request path.
     * :returns: an {@link ErrorResponse} carrying the status, reason phrase, message, path, and trace id.
     */
    private ErrorResponse buildBody(HttpStatus status, String message, WebRequest request) {
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(), message, extractPath(request));
        body.setTraceId(resolveTraceId());
        return body;
    }

    /**
     * :purpose: Derive the request URI from a {@link WebRequest} without the servlet API.
     * :param request: the current web request.
     * :returns: the request path with the leading ``"uri="`` stripped, or the raw description when it is absent.
     */
    private String extractPath(WebRequest request) {
        String description = request.getDescription(false);
        if (description != null && description.startsWith("uri=")) {
            return description.substring(4);
        }
        return description;
    }

    /**
     * :purpose: Resolve the trace id, preferring the Micrometer Tracing MDC value and
     *     falling back to the business correlation id.
     * :returns: the current trace id, or the correlation id when no trace id is present.
     */
    private String resolveTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = CorrelationIdContext.getCorrelationId();
        }
        return traceId;
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
        log.warn("Record not found at {}: {}", extractPath(request), ex.getMessage());
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
        log.warn("Optimistic-lock conflict at {}: {}", extractPath(request), ex.getMessage());
        return ResponseEntity.status(status).body(buildBody(status, ex.getMessage(), request));
    }

    /**
     * :purpose: Map a posting reject (legacy ``CBTRN02C`` codes 100-103, including the over-limit and expired subtypes) to HTTP 422.
     * :param ex: the reject exception carrying the numeric reject code and its exact description.
     * :param request: the current web request.
     * :returns: a ``422 Unprocessable Entity`` {@link ResponseEntity} wrapping the error body.
     */
    @ExceptionHandler(TransactionRejectException.class)
    public ResponseEntity<ErrorResponse> handleTransactionReject(TransactionRejectException ex, WebRequest request) {
        HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
        log.warn("Transaction rejected (code {}) at {}: {}", ex.getRejectCode(), extractPath(request), ex.getMessage());
        return ResponseEntity.status(status).body(buildBody(status, ex.getMessage(), request));
    }

    /**
     * :purpose: Map an uncategorized base {@link CardDemoException} (or any subtype without a more specific handler) to HTTP 400.
     * :param ex: the domain exception carrying the detail message.
     * :param request: the current web request.
     * :returns: a ``400 Bad Request`` {@link ResponseEntity} wrapping the error body.
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ErrorResponse> handleCardDemoException(CardDemoException ex, WebRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        log.warn("Domain error at {}: {}", extractPath(request), ex.getMessage());
        return ResponseEntity.status(status).body(buildBody(status, ex.getMessage(), request));
    }

    /**
     * :purpose: Map a request-body bean-validation failure to HTTP 400 with per-field messages.
     * :param ex: the validation exception carrying the binding-result field errors.
     * :param request: the current web request.
     * :returns: a ``400 Bad Request`` {@link ResponseEntity} whose body lists each field error.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, WebRequest request) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        ErrorResponse body = buildBody(status, "Validation failed", request);
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            body.addFieldError(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("Request validation failed at {}: {} field error(s)", extractPath(request), ex.getBindingResult().getFieldErrors().size());
        return ResponseEntity.status(status).body(body);
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
        ErrorResponse body = buildBody(status, "Validation failed", request);
        for (ConstraintViolation<?> cv : ex.getConstraintViolations()) {
            body.addFieldError(cv.getPropertyPath().toString(), cv.getMessage());
        }
        log.warn("Constraint validation failed at {}: {} violation(s)", extractPath(request), ex.getConstraintViolations().size());
        return ResponseEntity.status(status).body(body);
    }
}
