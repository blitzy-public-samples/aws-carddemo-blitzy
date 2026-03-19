/*
 * GlobalExceptionHandler.java — Centralized REST Exception Handling
 *
 * Provides a unified @ControllerAdvice for consistent error response formatting
 * across all REST controllers in the CardDemo application. Replaces per-controller
 * @ExceptionHandler methods and inline try-catch blocks with a single, centralized
 * error handling strategy.
 *
 * Maps COBOL/CICS error handling patterns to Spring REST error responses:
 *   - VSAM file status '23' (NOTFND)      → 404 Not Found
 *   - VSAM file status '22' (DUPKEY)      → 409 Conflict
 *   - BMS field validation failures        → 400 Bad Request
 *   - CICS READ UPDATE concurrent modify   → 409 Conflict (optimistic lock)
 *   - 9999-ABEND-PROGRAM                  → 500 Internal Server Error
 *
 * Ver: CardDemo_v1.0 — Migrated from COBOL to Java 25 + Spring Boot 3.5.x
 */
package com.cardemo.controller;

import com.cardemo.common.exception.AuthenticationException;
import com.cardemo.common.exception.CardDemoException;
import com.cardemo.common.exception.DuplicateRecordException;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;

import jakarta.persistence.OptimisticLockException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralized exception handler for all CardDemo REST controllers.
 *
 * <p>Implements a consistent error response format across all API endpoints,
 * mapping application-specific exceptions and JPA/validation exceptions to
 * appropriate HTTP status codes with structured error bodies.</p>
 *
 * <p>Error response format:
 * <pre>{@code
 * {
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "Card not found with key: 9999999999999999"
 * }
 * }</pre></p>
 *
 * @see RecordNotFoundException
 * @see DuplicateRecordException
 * @see ValidationException
 * @see AuthenticationException
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles record not found exceptions — maps to HTTP 404 Not Found.
     * Translates VSAM file status '23' (record not found) and CICS
     * DFHRESP(NOTFND) conditions.
     *
     * @param ex the RecordNotFoundException thrown by service layer
     * @return 404 response with error details
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleRecordNotFound(
            RecordNotFoundException ex) {
        log.warn("Record not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Handles duplicate record exceptions — maps to HTTP 409 Conflict.
     * Translates VSAM file status '22' (duplicate key) condition.
     *
     * @param ex the DuplicateRecordException thrown by service layer
     * @return 409 response with error details
     */
    @ExceptionHandler(DuplicateRecordException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateRecord(
            DuplicateRecordException ex) {
        log.warn("Duplicate record: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * Handles validation exceptions — maps to HTTP 400 Bad Request.
     * Translates BMS field validation failures (e.g., 1200-EDIT-MAP-INPUTS
     * in COCRDUPC.cbl, COACTUPC.cbl validation paragraphs).
     *
     * @param ex the ValidationException thrown by service layer
     * @return 400 response with error details
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            ValidationException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Handles Jakarta Bean Validation failures from {@code @Valid} annotated
     * {@code @RequestBody} parameters — maps to HTTP 400 Bad Request.
     *
     * <p>For single-field validation errors, the {@code error} response field
     * contains the specific validation message directly (e.g., "User ID Cannot
     * Be Empty"), preserving COBOL parity with the BMS field-level validation
     * messages from COSGN00C.cbl, COACTUPC.cbl, and other programs.</p>
     *
     * <p>For multiple field errors, the messages are aggregated into a summary
     * string with each field's constraint violation listed.</p>
     *
     * @param ex the MethodArgumentNotValidException thrown by Spring MVC
     * @return 400 response with field validation error details
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {
        var fieldErrors = ex.getBindingResult().getFieldErrors();

        if (fieldErrors.size() == 1) {
            // Single field error: use the specific validation message directly
            // in the 'error' field for COBOL-parity (e.g., "User ID Cannot Be Empty"
            // matching COSGN00C.cbl line 118 WS-MESSAGE)
            String message = fieldErrors.getFirst().getDefaultMessage();
            log.warn("Bean validation failed: {}", message);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", HttpStatus.BAD_REQUEST.value());
            body.put("error", message);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }

        // Multiple field errors: aggregate all violations into a summary
        String aggregated = fieldErrors.stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Bean validation failed: {}", aggregated);
        return buildErrorResponse(HttpStatus.BAD_REQUEST,
                "Validation failed: " + aggregated);
    }

    /**
     * Handles authentication exceptions — maps to HTTP 401 Unauthorized.
     * Translates COSGN00C.cbl sign-on failure conditions.
     *
     * @param ex the AuthenticationException thrown by SignonService
     * @return 401 response with error details
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(
            AuthenticationException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    /**
     * Handles JPA optimistic lock exceptions — maps to HTTP 409 Conflict.
     * Translates the CICS READ UPDATE → REWRITE concurrent modification pattern
     * where another transaction has modified the record between read and write.
     *
     * @param ex the OptimisticLockException thrown by JPA/Hibernate
     * @return 409 response with conflict details
     */
    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(
            OptimisticLockException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT,
                "Record was modified by another transaction. Please refresh and retry.");
    }

    /**
     * Handles malformed or unreadable HTTP request bodies — maps to HTTP 400 Bad Request.
     *
     * <p>This handler catches all Jackson deserialization failures including:</p>
     * <ul>
     *   <li>Malformed JSON syntax (missing braces, invalid tokens)</li>
     *   <li>Empty request bodies where JSON is expected</li>
     *   <li>Unrecognized JSON properties when {@code @JsonIgnoreProperties(ignoreUnknown = false)}
     *       is specified (e.g., BillPaymentRequest rejecting unknown 'amount' field)</li>
     *   <li>Type mismatch during deserialization (e.g., string where number expected)</li>
     * </ul>
     *
     * <p>Without this handler, Spring's default behavior returns HTTP 500 for
     * these conditions, which is incorrect per HTTP semantics — a client sending
     * malformed input should receive 400 Bad Request.</p>
     *
     * @param ex the HttpMessageNotReadableException wrapping the Jackson parse error
     * @return 400 response with descriptive error message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex) {
        String detail = "Malformed or unreadable request body";
        Throwable cause = ex.getCause();
        if (cause instanceof com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException upe) {
            detail = "Unrecognized field: '" + upe.getPropertyName()
                    + "'. Accepted fields: " + upe.getKnownPropertyIds();
        } else if (cause instanceof com.fasterxml.jackson.core.JsonParseException) {
            detail = "Malformed JSON in request body";
        } else if (cause instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException) {
            detail = "Invalid input: request body could not be parsed";
        }
        log.warn("HTTP message not readable: {} — detail: {}", ex.getMessage(), detail);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, detail);
    }

    /**
     * Handles general CardDemo application exceptions — maps to HTTP 500.
     * Translates COBOL 9999-ABEND-PROGRAM conditions.
     *
     * @param ex the CardDemoException thrown by any service
     * @return 500 response with error details
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<Map<String, Object>> handleCardDemoException(
            CardDemoException ex) {
        log.error("CardDemo application error: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getMessage());
    }

    /**
     * Fallback handler for unexpected exceptions — maps to HTTP 500.
     *
     * @param ex the unhandled exception
     * @return 500 response with generic error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
    }

    // =========================================================================
    // Response Builder
    // =========================================================================

    /**
     * Builds a consistent error response body with status code, reason phrase,
     * and descriptive message.
     *
     * @param status  the HTTP status code
     * @param message the error description
     * @return ResponseEntity with structured error body
     */
    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
