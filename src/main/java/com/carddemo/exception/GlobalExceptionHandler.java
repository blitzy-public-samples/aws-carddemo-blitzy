/*
 * GlobalExceptionHandler.java
 * 
 * Centralized exception handling component for CardDemo Spring Boot application
 * using Spring's @ControllerAdvice and @ExceptionHandler annotations.
 * 
 * This handler replaces distributed COBOL error handling patterns (RESP/RESP2
 * evaluation, 88-level condition checks, error flag management) with unified
 * exception-to-HTTP-status mapping for consistent RESTful error responses.
 * 
 * COBOL Error Patterns Replaced:
 * - COSGN00C.cbl lines 241-257: Authentication errors (password mismatch, user not found)
 * - COACTUPC.cbl lines 1671-1676: Field validation INPUT-ERROR flag handling
 * - COTRN02C.cbl: Transaction validation failures and business rule violations
 * - COBIL00C.cbl: Payment processing errors
 * 
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
package com.carddemo.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Global exception handler providing centralized exception handling for all REST controllers.
 * 
 * <p>This @ControllerAdvice component intercepts exceptions thrown from any @RestController
 * in the application and transforms them into structured JSON error responses with appropriate
 * HTTP status codes, replacing COBOL's distributed error handling patterns with unified
 * exception management.</p>
 * 
 * <h2>Exception Handling Strategy</h2>
 * <ul>
 *   <li><b>ResourceNotFoundException</b> → HTTP 404 Not Found (COBOL RESP-CD 13)</li>
 *   <li><b>ValidationException</b> → HTTP 400 Bad Request (COBOL 88-level validation flags)</li>
 *   <li><b>AuthenticationException</b> → HTTP 401 Unauthorized (COBOL authentication errors)</li>
 *   <li><b>BusinessLogicException</b> → HTTP 500 Internal Server Error or 409 Conflict</li>
 *   <li><b>MethodArgumentNotValidException</b> → HTTP 400 Bad Request (Bean Validation)</li>
 *   <li><b>Generic Exception</b> → HTTP 500 Internal Server Error (unexpected errors)</li>
 * </ul>
 * 
 * <h2>Error Response Structure</h2>
 * <p>All exception handlers return a standardized ErrorResponse JSON structure:</p>
 * <pre>
 * {
 *   "timestamp": "2024-11-06T20:42:15",
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "Validation failed for 2 fields: creditLimit, accountStatus",
 *   "path": "/api/accounts/123",
 *   "fieldErrors": {
 *     "creditLimit": "Credit limit must be at least $1,000",
 *     "accountStatus": "Account status must be 'Y' or 'N'"
 *   }
 * }
 * </pre>
 * 
 * <h2>Logging Strategy</h2>
 * <ul>
 *   <li>WARN level: Business logic errors, validation failures, authentication failures</li>
 *   <li>ERROR level: Unexpected runtime exceptions with full stack traces</li>
 * </ul>
 * 
 * @see ControllerAdvice
 * @see ExceptionHandler
 * @see ResponseEntity
 * @since 1.0
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Logger for exception handling operations.
     */
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles ResourceNotFoundException thrown when requested entities are not found.
     * 
     * <p><b>COBOL Pattern Replaced:</b> CICS RESP-CD 13 (NOTFND) from VSAM READ operations
     * where requested record key does not exist (COSGN00C.cbl lines 247-251).</p>
     * 
     * <p><b>HTTP Response:</b> 404 Not Found</p>
     * 
     * @param ex the ResourceNotFoundException containing entity details
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing structured error details with HTTP 404 status
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException ex,
            HttpServletRequest request) {
        
        logger.warn("Resource not found: {} - Request path: {}", ex.getMessage(), request.getRequestURI());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.NOT_FOUND.value())
                .error(HttpStatus.NOT_FOUND.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        
        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /**
     * Handles ValidationException thrown when data validation fails.
     * 
     * <p><b>COBOL Pattern Replaced:</b> 88-level validation flags (INPUT-ERROR,
     * FLG-ALPHA-NOT-OK, FLG-MANDATORY-NOT-OK) from COACTUPC.cbl lines 1671-1676
     * and similar validation patterns across all online programs.</p>
     * 
     * <p><b>HTTP Response:</b> 400 Bad Request</p>
     * 
     * @param ex the ValidationException containing validation error details
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing structured error details with HTTP 400 status
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            ValidationException ex,
            HttpServletRequest request) {
        
        logger.warn("Validation error: {} - Request path: {} - Field errors: {}", 
                ex.getMessage(), request.getRequestURI(), ex.getFieldErrors());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .fieldErrors(ex.getFieldErrors())
                .build();
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles AuthenticationException thrown when authentication or authorization fails.
     * 
     * <p><b>COBOL Pattern Replaced:</b> Authentication error handling from COSGN00C.cbl
     * including:</p>
     * <ul>
     *   <li>Lines 241-246: "Wrong Password. Try again ..."</li>
     *   <li>Lines 247-251: "User not found. Try again ..."</li>
     *   <li>Lines 252-257: "Unable to verify the User ..."</li>
     * </ul>
     * 
     * <p><b>HTTP Response:</b> 401 Unauthorized</p>
     * 
     * @param ex the AuthenticationException containing authentication failure details
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing structured error details with HTTP 401 status
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException ex,
            HttpServletRequest request) {
        
        logger.warn("Authentication failed: {} - Request path: {}", ex.getMessage(), request.getRequestURI());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        
        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    /**
     * Handles Spring Security UsernameNotFoundException when user authentication fails.
     * 
     * <p>This handler catches Spring Security's UsernameNotFoundException thrown during
     * authentication attempts when the specified user cannot be found in the system.</p>
     * 
     * <p><b>HTTP Response:</b> 401 Unauthorized with structured error details</p>
     * 
     * @param ex the UsernameNotFoundException from Spring Security
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing structured error details with HTTP 401 status
     */
    @ExceptionHandler(org.springframework.security.core.userdetails.UsernameNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUsernameNotFoundException(
            org.springframework.security.core.userdetails.UsernameNotFoundException ex,
            HttpServletRequest request) {
        
        logger.warn("User not found during authentication: {} - Request path: {}", ex.getMessage(), request.getRequestURI());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        
        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    /**
     * Handles Spring Security BadCredentialsException when authentication credentials are invalid.
     * 
     * <p>This handler catches Spring Security's BadCredentialsException thrown when
     * authentication fails due to incorrect password or invalid credentials.</p>
     * 
     * <p><b>HTTP Response:</b> 401 Unauthorized with structured error details</p>
     * 
     * @param ex the BadCredentialsException from Spring Security
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing structured error details with HTTP 401 status
     */
    @ExceptionHandler(org.springframework.security.authentication.BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentialsException(
            org.springframework.security.authentication.BadCredentialsException ex,
            HttpServletRequest request) {
        
        logger.warn("Bad credentials during authentication: {} - Request path: {}", ex.getMessage(), request.getRequestURI());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        
        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    /**
     * Handles Spring Security AccessDeniedException when authorization fails.
     * 
     * <p>This handler catches Spring Security's AccessDeniedException thrown when
     * a user attempts to access a resource or perform an operation they are not
     * authorized to access based on role-based access control (@PreAuthorize annotations).</p>
     * 
     * <p><b>COBOL Pattern Replaced:</b> RACF authorization checks where user type
     * (SEC-USR-TYPE) determines access to administrative functions in COUSR00C, 
     * COUSR01C, COUSR02C, COUSR03C programs.</p>
     * 
     * <p><b>HTTP Response:</b> 403 Forbidden with structured error details</p>
     * 
     * @param ex the AccessDeniedException from Spring Security
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing structured error details with HTTP 403 status
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            org.springframework.security.access.AccessDeniedException ex,
            HttpServletRequest request) {
        
        logger.warn("Access denied: {} - Request path: {}", ex.getMessage(), request.getRequestURI());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error(HttpStatus.FORBIDDEN.getReasonPhrase())
                .message("Access denied. You do not have permission to access this resource.")
                .path(request.getRequestURI())
                .build();
        
        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }

    /**
     * Handles BusinessLogicException thrown when business rules are violated.
     * 
     * <p><b>COBOL Pattern Replaced:</b> Business logic error patterns from:</p>
     * <ul>
     *   <li>COTRN02C.cbl: Transaction validation failures (credit limit, card status)</li>
     *   <li>COACTUPC.cbl: Optimistic locking failures and concurrent modifications</li>
     *   <li>COBIL00C.cbl: Payment processing failures</li>
     *   <li>COUSR01C.cbl: VSAM DUPKEY/DUPREC errors (lines 260-268) for duplicate user IDs</li>
     * </ul>
     * 
     * <p><b>HTTP Response:</b> 422 Unprocessable Entity (business rule violations), 
     * 409 Conflict (concurrent modification or duplicate key), or 400 Bad Request 
     * (invalid input data)</p>
     * 
     * @param ex the BusinessLogicException containing business rule violation details
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing structured error details with appropriate HTTP status
     */
    @ExceptionHandler(BusinessLogicException.class)
    public ResponseEntity<ErrorResponse> handleBusinessLogicException(
            BusinessLogicException ex,
            HttpServletRequest request) {
        
        // Default to 422 Unprocessable Entity for business rule violations
        // This is the appropriate status for semantically invalid requests that
        // cannot be processed due to business logic constraints
        HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
        
        String message = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        String errorCode = ex.getErrorCode() != null ? ex.getErrorCode() : "";
        
        // Check error code first for specific status codes
        if (errorCode.contains("CONCURRENT_MODIFICATION") ||
            errorCode.contains("DUPLICATE_KEY") ||
            errorCode.contains("DUPLICATE")) {
            // Concurrent modification or duplicate key -> 409 Conflict
            status = HttpStatus.CONFLICT;
        } else if (errorCode.contains("INSUFFICIENT_BALANCE") ||
                   errorCode.contains("INVALID_AMOUNT") ||
                   errorCode.contains("INVALID_ACCOUNT_STATUS")) {
            // Input validation errors -> 400 Bad Request
            status = HttpStatus.BAD_REQUEST;
        }
        // Check message content for specific scenarios
        else if (message.contains("already exist") ||
                 message.contains("duplicate")) {
            // Duplicate scenarios -> 409 Conflict
            status = HttpStatus.CONFLICT;
        } else if (message.contains("insufficient") ||
                   message.contains("invalid amount") ||
                   message.contains("inactive account")) {
            // Input validation errors -> 400 Bad Request
            status = HttpStatus.BAD_REQUEST;
        }
        // Business rule violations -> 422 Unprocessable Entity
        // These include: expired card, blocked/inactive card, exceeds credit limit
        // The request is well-formed but violates business rules
        else if (message.contains("expired") ||
                 message.contains("not active") ||
                 message.contains("exceeds available credit") ||
                 message.contains("blocked") ||
                 message.contains("suspended")) {
            // Business rule violations -> 422 Unprocessable Entity
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        }
        
        logger.warn("Business logic error [{}]: {} - Request path: {}", 
                status.value(), ex.getMessage(), request.getRequestURI());
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        
        return new ResponseEntity<>(errorResponse, status);
    }

    /**
     * Handles MethodArgumentNotValidException thrown by Spring's Bean Validation.
     * 
     * <p>This exception is automatically thrown when @Valid annotation on @RequestBody
     * parameters fails validation constraints (@NotBlank, @NotNull, @Size, @Pattern,
     * @DecimalMin, @DecimalMax).</p>
     * 
     * <p><b>COBOL Pattern Replaced:</b> Field-level validation using PERFORM paragraphs
     * like 1225-EDIT-ALPHA-REQD, 1245-EDIT-NUM-REQD, 1260-EDIT-US-PHONE-NUM from
     * COACTUPC.cbl.</p>
     * 
     * <p><b>HTTP Response:</b> 400 Bad Request with field-level error details</p>
     * 
     * @param ex the MethodArgumentNotValidException containing Bean Validation errors
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing structured error details with HTTP 400 status
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        
        // Extract field errors from Spring's BindingResult with priority handling
        // Prioritize "required" constraints (@NotBlank, @NotNull, @NotEmpty) over others
        // to ensure blank/null fields show "required" messages instead of size/pattern errors
        Map<String, String> fieldErrors = new HashMap<>();
        Map<String, List<String>> allFieldErrors = new HashMap<>();
        
        // Collect all errors for each field
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            allFieldErrors.computeIfAbsent(fieldError.getField(), k -> new ArrayList<>())
                    .add(fieldError.getDefaultMessage());
        }
        
        // For each field, select the most appropriate error message
        List<String> fieldNames = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : allFieldErrors.entrySet()) {
            String field = entry.getKey();
            List<String> messages = entry.getValue();
            
            // Priority 1: "required" or "must not be blank/null/empty" messages
            String selectedMessage = messages.stream()
                    .filter(msg -> msg != null && (
                            msg.toLowerCase().contains("required") ||
                            msg.toLowerCase().contains("must not be blank") ||
                            msg.toLowerCase().contains("must not be null") ||
                            msg.toLowerCase().contains("must not be empty")))
                    .findFirst()
                    .orElse(messages.get(0)); // Fallback to first message if no priority match
            
            fieldErrors.put(field, selectedMessage);
            fieldNames.add(field);
        }
        
        String message = String.format("Validation failed for %d field%s: %s",
                fieldErrors.size(),
                fieldErrors.size() == 1 ? "" : "s",
                String.join(", ", fieldNames));
        
        logger.warn("Bean validation error: {} - Request path: {} - Field errors: {}", 
                message, request.getRequestURI(), fieldErrors);
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .fieldErrors(fieldErrors)
                .build();
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles HttpMessageNotReadableException thrown when request body cannot be parsed.
     * 
     * <p>This handler catches Spring's HttpMessageNotReadableException thrown when the
     * HTTP request body contains malformed JSON, invalid syntax, or cannot be deserialized
     * into the expected object type.</p>
     * 
     * <p><b>Common causes:</b></p>
     * <ul>
     *   <li>Invalid JSON syntax (missing braces, quotes, commas)</li>
     *   <li>Type mismatch (string where number expected, etc.)</li>
     *   <li>Empty request body when content is required</li>
     * </ul>
     * 
     * <p><b>HTTP Response:</b> 400 Bad Request with structured error details</p>
     * 
     * @param ex the HttpMessageNotReadableException from Spring MVC
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing structured error details with HTTP 400 status
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        
        logger.warn("Malformed JSON request body: {} - Request path: {}", ex.getMessage(), request.getRequestURI());
        
        // Extract the most relevant error message
        String message = "Malformed JSON request";
        if (ex.getCause() != null) {
            message = "Invalid request body: " + ex.getCause().getMessage();
        } else if (ex.getMessage() != null && ex.getMessage().contains("Required request body is missing")) {
            message = "Required request body is missing";
        }
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build();
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles MethodArgumentTypeMismatchException thrown when request parameter type conversion fails.
     * 
     * <p>This handler catches Spring's MethodArgumentTypeMismatchException thrown when a
     * request parameter (like a date in @RequestParam) cannot be converted to the expected
     * Java type. Common examples include:</p>
     * <ul>
     *   <li>Invalid date format (e.g., "01/01/2024" when ISO 8601 "2024-01-01" is expected)</li>
     *   <li>Non-numeric value where numeric type expected</li>
     *   <li>Malformed enum value</li>
     * </ul>
     * 
     * <p><b>COBOL Pattern Replaced:</b> Date format validation from CORPT00C.cbl lines 236-354
     * which validated date component ranges and formats before processing.</p>
     * 
     * <p><b>HTTP Response:</b> 400 Bad Request with structured error details</p>
     * 
     * @param ex the MethodArgumentTypeMismatchException from Spring MVC
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing structured error details with HTTP 400 status
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        
        // Extract meaningful error message based on the parameter type
        String parameterName = ex.getName();
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown";
        String providedValue = ex.getValue() != null ? ex.getValue().toString() : "null";
        
        String message = String.format("Invalid value '%s' for parameter '%s'. Expected format: %s",
                providedValue,
                parameterName,
                getExpectedFormatMessage(requiredType));
        
        logger.warn("Parameter type conversion error: {} - Request path: {} - Parameter: {} - Value: {} - Required type: {}",
                message, request.getRequestURI(), parameterName, providedValue, requiredType);
        
        // Build field errors map for structured error response
        Map<String, String> fieldErrorsMap = new HashMap<>();
        fieldErrorsMap.put(parameterName, message);
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Invalid request parameter: " + parameterName)
                .path(request.getRequestURI())
                .fieldErrors(fieldErrorsMap)
                .build();
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Helper method to generate user-friendly expected format messages for different parameter types.
     * 
     * @param typeName the simple name of the required Java type
     * @return user-friendly format description
     */
    private String getExpectedFormatMessage(String typeName) {
        return switch (typeName) {
            case "LocalDate" -> "ISO 8601 date format (YYYY-MM-DD), e.g., 2024-01-31";
            case "LocalDateTime" -> "ISO 8601 date-time format (YYYY-MM-DDTHH:MM:SS), e.g., 2024-01-31T14:30:00";
            case "Integer", "Long" -> "numeric value";
            case "BigDecimal" -> "decimal number";
            case "Boolean" -> "true or false";
            default -> typeName;
        };
    }

    /**
     * Handles all other unexpected exceptions not caught by specific handlers.
     * 
     * <p>This is the catch-all handler for any RuntimeException or checked Exception
     * that doesn't have a more specific handler. It logs the full stack trace and
     * returns a sanitized error message to the client.</p>
     * 
     * <p><b>COBOL Pattern Replaced:</b> WHEN OTHER clause in EVALUATE RESP-CD statements
     * (COSGN00C.cbl lines 252-257: "Unable to verify the User ...").</p>
     * 
     * <p><b>HTTP Response:</b> 500 Internal Server Error</p>
     * 
     * @param ex the Exception that was not handled by specific handlers
     * @param request the HTTP request that caused the exception
     * @return ResponseEntity containing sanitized error details with HTTP 500 status
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            HttpServletRequest request) {
        
        logger.error("Unexpected error occurred - Request path: {} - Exception: {}", 
                request.getRequestURI(), ex.getClass().getSimpleName(), ex);
        
        // Sanitize error message for security - don't expose internal details
        String safeMessage = "An unexpected error occurred. Please contact support if the problem persists.";
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
                .message(safeMessage)
                .path(request.getRequestURI())
                .build();
        
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Standardized error response structure for all exception handlers.
     * 
     * <p>This inner class defines the JSON structure returned by all exception handlers,
     * providing consistent error response format across the entire REST API.</p>
     * 
     * <h3>JSON Structure Example:</h3>
     * <pre>
     * {
     *   "timestamp": "2024-11-06T20:42:15",
     *   "status": 400,
     *   "error": "Bad Request",
     *   "message": "Validation failed for 2 fields: creditLimit, accountStatus",
     *   "path": "/api/accounts/123",
     *   "fieldErrors": {
     *     "creditLimit": "Credit limit must be at least $1,000",
     *     "accountStatus": "Account status must be 'Y' or 'N'"
     *   }
     * }
     * </pre>
     */
    @JsonPropertyOrder({"timestamp", "status", "error", "message", "path", "fieldErrors"})
    public static class ErrorResponse {
        
        /**
         * Timestamp when the error occurred in ISO 8601 format.
         */
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime timestamp;
        
        /**
         * HTTP status code (e.g., 400, 401, 404, 500).
         */
        private Integer status;
        
        /**
         * HTTP status reason phrase (e.g., "Bad Request", "Not Found").
         */
        private String error;
        
        /**
         * Detailed error message describing what went wrong.
         */
        private String message;
        
        /**
         * Request URI path that triggered the error.
         */
        private String path;
        
        /**
         * Optional map of field-level validation errors.
         * Only included in response when validation failures occur.
         * Key: field name, Value: error message
         */
        @JsonProperty("fieldErrors")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Map<String, String> fieldErrors;

        /**
         * Default constructor for Jackson deserialization.
         */
        public ErrorResponse() {
        }

        /**
         * All-arguments constructor for creating ErrorResponse instances.
         * 
         * @param timestamp the timestamp when error occurred
         * @param status the HTTP status code
         * @param error the HTTP status reason phrase
         * @param message the detailed error message
         * @param path the request URI path
         * @param fieldErrors optional map of field-level validation errors
         */
        public ErrorResponse(LocalDateTime timestamp, Integer status, String error, 
                           String message, String path, Map<String, String> fieldErrors) {
            this.timestamp = timestamp;
            this.status = status;
            this.error = error;
            this.message = message;
            this.path = path;
            this.fieldErrors = fieldErrors;
        }

        /**
         * Creates a builder for constructing ErrorResponse instances.
         * 
         * @return new ErrorResponseBuilder instance
         */
        public static ErrorResponseBuilder builder() {
            return new ErrorResponseBuilder();
        }

        // Getters and setters

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Map<String, String> getFieldErrors() {
            return fieldErrors;
        }

        public void setFieldErrors(Map<String, String> fieldErrors) {
            this.fieldErrors = fieldErrors;
        }

        /**
         * Builder class for ErrorResponse construction.
         */
        public static class ErrorResponseBuilder {
            private LocalDateTime timestamp;
            private Integer status;
            private String error;
            private String message;
            private String path;
            private Map<String, String> fieldErrors;

            public ErrorResponseBuilder timestamp(LocalDateTime timestamp) {
                this.timestamp = timestamp;
                return this;
            }

            public ErrorResponseBuilder status(Integer status) {
                this.status = status;
                return this;
            }

            public ErrorResponseBuilder error(String error) {
                this.error = error;
                return this;
            }

            public ErrorResponseBuilder message(String message) {
                this.message = message;
                return this;
            }

            public ErrorResponseBuilder path(String path) {
                this.path = path;
                return this;
            }

            public ErrorResponseBuilder fieldErrors(Map<String, String> fieldErrors) {
                this.fieldErrors = fieldErrors;
                return this;
            }

            public ErrorResponse build() {
                return new ErrorResponse(timestamp, status, error, message, path, fieldErrors);
            }
        }
    }
}
