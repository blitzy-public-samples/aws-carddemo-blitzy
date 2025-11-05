/*****************************************************************
 * Program:     GlobalExceptionHandler.java
 * Layer:       Exception handling
 * Function:    Centralized exception handling for REST API endpoints
 *              Maps COBOL file-status and CICS RESP codes to HTTP status codes
 ******************************************************************
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
 * language governing permissions and limitations under the License
 ******************************************************************/
package com.carddemo.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Global exception handler for CardDemo REST API endpoints.
 * <p>
 * This class provides centralized exception handling using Spring's @ControllerAdvice
 * mechanism, intercepting exceptions thrown by controller methods and service layers
 * to produce standardized HTTP error responses.
 * </p>
 * <p>
 * <b>COBOL Error Mapping:</b>
 * <ul>
 *   <li>COBOL file-status codes → HTTP status codes</li>
 *   <li>CICS RESP/RESP2 codes → HTTP status codes with error details</li>
 *   <li>DFHRESP(NOTFND) = 13 → HTTP 404 NOT_FOUND</li>
 *   <li>DFHRESP(DUPREC) = 14 → HTTP 409 CONFLICT</li>
 *   <li>DFHRESP(IOERR) → HTTP 500 INTERNAL_SERVER_ERROR</li>
 * </ul>
 * </p>
 * <p>
 * <b>Security Considerations:</b>
 * <ul>
 *   <li>No stack traces exposed in production responses</li>
 *   <li>Sensitive data (passwords, full card numbers) never included in error messages</li>
 *   <li>Card numbers masked in error responses (PCI compliance)</li>
 *   <li>Usernames not exposed in authentication failure responses</li>
 * </ul>
 * </p>
 * <p>
 * <b>COBOL Programs Mapped:</b>
 * <ul>
 *   <li>COSGN00C.cbl - Authentication error patterns</li>
 *   <li>COACTVWC.cbl - Account lookup error handling</li>
 *   <li>COCRDLIC.cbl - Card list error handling</li>
 *   <li>COTRN02C.cbl - Transaction posting error handling</li>
 *   <li>COBIL00C.cbl - Bill payment error handling</li>
 * </ul>
 * </p>
 *
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Logger instance for audit trail and error tracking.
     * <p>
     * All exception handling events are logged for:
     * <ul>
     *   <li>Audit trail compliance (regulatory requirements)</li>
     *   <li>Debugging and troubleshooting</li>
     *   <li>Security monitoring and incident response</li>
     *   <li>Performance analysis and error pattern detection</li>
     * </ul>
     * </p>
     */
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Standardized error response structure for all REST API errors.
     * <p>
     * Provides consistent error response format across all endpoints,
     * containing timestamp, status code, error type, message, request path,
     * and optional contextual details.
     * </p>
     * <p>
     * <b>Example JSON Response:</b>
     * <pre>
     * {
     *   "timestamp": "2024-10-31T14:30:45",
     *   "status": 404,
     *   "error": "ACCOUNT_NOT_FOUND",
     *   "message": "Account with number 12345678901 not found",
     *   "path": "/api/accounts/12345678901",
     *   "details": {
     *     "accountIdentifier": "12345678901",
     *     "identifierType": "ACCOUNT_NUMBER"
     *   }
     * }
     * </pre>
     * </p>
     */
    public static class ErrorResponse {
        /**
         * Timestamp when the error occurred (ISO 8601 format).
         * <p>
         * Provides precise timing for audit trail and debugging.
         * Corresponds to COBOL CURRENT-DATE function.
         * </p>
         */
        private LocalDateTime timestamp;

        /**
         * HTTP status code (numeric).
         * <p>
         * Examples: 400 (Bad Request), 404 (Not Found), 500 (Internal Server Error)
         * </p>
         */
        private int status;

        /**
         * Error code identifier (uppercase snake_case).
         * <p>
         * Examples: "ACCOUNT_NOT_FOUND", "INVALID_PASSWORD", "INSUFFICIENT_BALANCE"
         * Maps to COBOL error condition names.
         * </p>
         */
        private String error;

        /**
         * Human-readable error message.
         * <p>
         * Provides detailed explanation of the error.
         * Preserves COBOL error messages for consistency.
         * </p>
         */
        private String message;

        /**
         * Request path that generated the error.
         * <p>
         * Full URI path excluding query parameters.
         * Example: "/api/accounts/12345678901"
         * </p>
         */
        private String path;

        /**
         * Optional contextual details about the error.
         * <p>
         * Flexible map for exception-specific data such as:
         * <ul>
         *   <li>Validation errors (field names and error messages)</li>
         *   <li>Resource identifiers (account IDs, card numbers)</li>
         *   <li>Business rule violation details</li>
         *   <li>Retry hints or suggested actions</li>
         * </ul>
         * </p>
         */
        private Map<String, Object> details;

        /**
         * Default constructor for JSON deserialization.
         */
        public ErrorResponse() {
            this.timestamp = LocalDateTime.now();
            this.details = new HashMap<>();
        }

        /**
         * Full constructor with all fields.
         *
         * @param timestamp Timestamp when error occurred
         * @param status HTTP status code
         * @param error Error code identifier
         * @param message Human-readable error message
         * @param path Request path that generated the error
         * @param details Optional contextual details
         */
        public ErrorResponse(LocalDateTime timestamp, int status, String error, 
                           String message, String path, Map<String, Object> details) {
            this.timestamp = timestamp;
            this.status = status;
            this.error = error;
            this.message = message;
            this.path = path;
            this.details = details != null ? details : new HashMap<>();
        }

        // Getters and setters
        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }

        public int getStatus() {
            return status;
        }

        public void setStatus(int status) {
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

        public Map<String, Object> getDetails() {
            return details;
        }

        public void setDetails(Map<String, Object> details) {
            this.details = details;
        }
    }

    /**
     * Handles AccountNotFoundException - account lookup failures.
     * <p>
     * Maps to COBOL DFHRESP(NOTFND) = 13 and file-status 23.
     * Returns HTTP 404 NOT_FOUND.
     * </p>
     * <p>
     * <b>COBOL Pattern:</b>
     * <pre>
     * EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(NOTFND)
     *        STRING 'Account:' WS-CARD-RID-ACCT-ID-X
     *               ' not found in Acct Master file.Resp:' ERROR-RESP
     *               INTO WS-RETURN-MSG
     * </pre>
     * </p>
     *
     * @param ex AccountNotFoundException containing account identifier details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 404 status and error details
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFoundException(
            AccountNotFoundException ex, WebRequest request) {
        
        logger.warn("Account not found: {} (type: {})", 
                   ex.getAccountIdentifier(), ex.getIdentifierType());
        
        Map<String, Object> details = new HashMap<>();
        details.put("accountIdentifier", ex.getAccountIdentifier());
        details.put("identifierType", ex.getIdentifierType().name());
        
        ErrorResponse errorResponse = buildErrorResponse(
            "ACCOUNT_NOT_FOUND",
            ex.getMessage(),
            HttpStatus.NOT_FOUND,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /**
     * Handles CardNotFoundException - card lookup failures.
     * <p>
     * Maps to COBOL DFHRESP(NOTFND) for card file access.
     * Returns HTTP 404 NOT_FOUND with PCI-compliant masked card numbers.
     * </p>
     *
     * @param ex CardNotFoundException containing card identifier details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 404 status and masked card details
     */
    @ExceptionHandler(CardNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCardNotFoundException(
            CardNotFoundException ex, WebRequest request) {
        
        // Card identifier is already masked in the exception
        logger.warn("Card not found: {} (type: {})", 
                   ex.getCardIdentifier(), ex.getIdentifierType());
        
        Map<String, Object> details = new HashMap<>();
        details.put("cardIdentifier", ex.getCardIdentifier()); // Already masked
        details.put("identifierType", ex.getIdentifierType() != null ? ex.getIdentifierType().name() : "UNKNOWN");
        
        ErrorResponse errorResponse = buildErrorResponse(
            "CARD_NOT_FOUND",
            ex.getMessage(),
            HttpStatus.NOT_FOUND,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /**
     * Handles AuthenticationFailedException - user authentication failures.
     * <p>
     * Maps to COBOL COSGN00C.cbl authentication error patterns:
     * <ul>
     *   <li>RESP 13: "User not found. Try again..."</li>
     *   <li>Password mismatch: "Wrong Password. Try again..."</li>
     *   <li>Other errors: "Unable to verify the User..."</li>
     * </ul>
     * Returns HTTP 401 UNAUTHORIZED.
     * </p>
     * <p>
     * <b>Security Note:</b> Username is not included in response to prevent
     * username enumeration attacks.
     * </p>
     *
     * @param ex AuthenticationFailedException with failure reason
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 401 status and generic error message
     */
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationFailedException(
            AuthenticationFailedException ex, WebRequest request) {
        
        // Log with username for audit trail, but don't expose in response
        logger.warn("Authentication failed for user: {} - Reason: {}", 
                   ex.getUsername(), ex.getReason());
        
        Map<String, Object> details = new HashMap<>();
        if (ex.getReason() != null) {
            details.put("reason", ex.getReason().name());
        }
        
        // Generic message for security - don't reveal if username exists
        String message = "Authentication failed. Please check your credentials and try again.";
        
        ErrorResponse errorResponse = buildErrorResponse(
            "AUTHENTICATION_FAILED",
            message,
            HttpStatus.UNAUTHORIZED,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    /**
     * Handles InsufficientBalanceException - balance validation failures.
     * <p>
     * Maps to COBOL balance check logic in transaction posting and bill payment.
     * Returns HTTP 422 UNPROCESSABLE_ENTITY.
     * </p>
     * <p>
     * <b>COBOL Pattern:</b>
     * <pre>
     * IF WS-AVAILABLE-CREDIT < WS-TRANSACTION-AMOUNT
     *    MOVE 'Insufficient credit available' TO WS-MESSAGE
     *    SET INPUT-ERROR TO TRUE
     * END-IF
     * </pre>
     * </p>
     *
     * @param ex InsufficientBalanceException with amount details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 422 status and balance details
     */
    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalanceException(
            InsufficientBalanceException ex, WebRequest request) {
        
        logger.warn("Insufficient balance: requested={}, available={}, accountId={}", 
                   ex.getRequestedAmount(), ex.getAvailableCredit(), ex.getAccountId());
        
        Map<String, Object> details = new HashMap<>();
        details.put("requestedAmount", ex.getRequestedAmount());
        details.put("availableCredit", ex.getAvailableCredit());
        details.put("accountId", ex.getAccountId());
        
        ErrorResponse errorResponse = buildErrorResponse(
            "INSUFFICIENT_BALANCE",
            ex.getMessage(),
            HttpStatus.UNPROCESSABLE_ENTITY,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /**
     * Handles TransactionException - general transaction processing errors.
     * <p>
     * Maps to various COBOL RESP/RESP2 codes from transaction operations.
     * Returns HTTP 400 BAD_REQUEST or 409 CONFLICT based on error type.
     * </p>
     *
     * @param ex TransactionException with transaction error details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with appropriate status and error details
     */
    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<ErrorResponse> handleTransactionException(
            TransactionException ex, WebRequest request) {
        
        logger.error("Transaction exception: {}", ex.getMessage(), ex);
        
        Map<String, Object> details = new HashMap<>();
        if (ex.getErrorCode() != null) {
            details.put("errorCode", ex.getErrorCode());
        }
        if (ex.getRespCode() != null) {
            details.put("respCode", ex.getRespCode());
        }
        if (ex.getResp2Code() != null) {
            details.put("resp2Code", ex.getResp2Code());
        }
        
        // Determine status based on error type
        HttpStatus status;
        if (ex.getMessage().contains("duplicate") || ex.getMessage().contains("conflict")) {
            status = HttpStatus.CONFLICT;
        } else if (ex.getErrorCode() != null && 
                  (ex.getErrorCode().contains("DB_") || 
                   ex.getErrorCode().contains("SYSTEM_") ||
                   ex.getMessage().toLowerCase().contains("database") ||
                   ex.getMessage().toLowerCase().contains("unexpected"))) {
            // Database or system errors should return 500
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        } else {
            // Validation or business logic errors return 400
            status = HttpStatus.BAD_REQUEST;
        }
        
        ErrorResponse errorResponse = buildErrorResponse(
            "TRANSACTION_ERROR",
            ex.getMessage(),
            status,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, status);
    }

    /**
     * Handles UserNotFoundException - user lookup failures during authentication.
     * <p>
     * Maps to COBOL USRSEC file-status 23 and DFHRESP(NOTFND) RESP=13.
     * Returns HTTP 401 UNAUTHORIZED to prevent username enumeration attacks.
     * Per Agent Action Plan Section 0.2: "User not found (RESP=13): 401 Unauthorized"
     * </p>
     *
     * @param ex UserNotFoundException containing user identifier details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 401 status and error details
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFoundException(
            UserNotFoundException ex, WebRequest request) {
        
        logger.warn("User not found: {}", ex.getUserId());
        
        Map<String, Object> details = new HashMap<>();
        // Don't include userId in response to prevent username enumeration
        
        ErrorResponse errorResponse = buildErrorResponse(
            "AUTHENTICATION_FAILED",
            ex.getMessage(),
            HttpStatus.UNAUTHORIZED,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
    }

    /**
     * Handles UserAlreadyExistsException - duplicate user creation attempts.
     * <p>
     * Maps to COBOL DFHRESP(DUPREC) and file-status 22.
     * Returns HTTP 409 CONFLICT.
     * </p>
     *
     * @param ex UserAlreadyExistsException with duplicate user details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 409 status and error details
     */
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserAlreadyExistsException(
            UserAlreadyExistsException ex, WebRequest request) {
        
        logger.warn("User already exists: {}", ex.getUserId());
        
        Map<String, Object> details = new HashMap<>();
        details.put("userId", ex.getUserId());
        
        ErrorResponse errorResponse = buildErrorResponse(
            "USER_ALREADY_EXISTS",
            ex.getMessage(),
            HttpStatus.CONFLICT,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }

    /**
     * Handles InvalidPayeeException - payee validation failures.
     * <p>
     * Maps to COBOL COBIL00C.cbl payee validation logic.
     * Returns HTTP 400 BAD_REQUEST.
     * </p>
     *
     * @param ex InvalidPayeeException with payee validation details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 400 status and validation details
     */
    @ExceptionHandler(InvalidPayeeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPayeeException(
            InvalidPayeeException ex, WebRequest request) {
        
        logger.warn("Invalid payee: {} - Reason: {}", ex.getPayeeId(), ex.getReason());
        
        Map<String, Object> details = new HashMap<>();
        details.put("payeeId", ex.getPayeeId());
        if (ex.getPayeeName() != null) {
            details.put("payeeName", ex.getPayeeName());
        }
        if (ex.getReason() != null) {
            details.put("reason", ex.getReason().name());
        }
        
        ErrorResponse errorResponse = buildErrorResponse(
            "INVALID_PAYEE",
            ex.getMessage(),
            HttpStatus.BAD_REQUEST,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles AccountCreationException - account creation failures.
     * <p>
     * Maps to COBOL COACTADD.cbl account creation error handling.
     * Returns HTTP 400 BAD_REQUEST or 409 CONFLICT based on error type.
     * </p>
     *
     * @param ex AccountCreationException with creation failure details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with appropriate status and error details
     */
    @ExceptionHandler(AccountCreationException.class)
    public ResponseEntity<ErrorResponse> handleAccountCreationException(
            AccountCreationException ex, WebRequest request) {
        
        logger.error("Account creation failed: {}", ex.getMessage(), ex);
        
        Map<String, Object> details = new HashMap<>();
        details.put("failureReason", ex.getFailureReason());
        
        // Determine status based on error type
        HttpStatus status = ex.getMessage().contains("duplicate") || 
                          ex.getMessage().contains("already exists") ?
                          HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        
        ErrorResponse errorResponse = buildErrorResponse(
            "ACCOUNT_CREATION_FAILED",
            ex.getMessage(),
            status,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, status);
    }

    /**
     * Handles CardUpdateException - card update failures.
     * <p>
     * Maps to COBOL COCRDUPC.cbl card update error handling.
     * Returns HTTP 400 BAD_REQUEST or 409 CONFLICT.
     * </p>
     *
     * @param ex CardUpdateException with update failure details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with appropriate status and masked card details
     */
    @ExceptionHandler(CardUpdateException.class)
    public ResponseEntity<ErrorResponse> handleCardUpdateException(
            CardUpdateException ex, WebRequest request) {
        
        logger.error("Card update failed: {} - Reason: {}", ex.getCardNumber(), ex.getFailureReason());
        
        Map<String, Object> details = new HashMap<>();
        details.put("cardNumber", ex.getCardNumber()); // Already masked
        if (ex.getFailureReason() != null) {
            details.put("failureReason", ex.getFailureReason().name());
        }
        
        // Determine status based on error type
        HttpStatus status = ex.getMessage().contains("concurrent") || 
                          ex.getMessage().contains("conflict") ?
                          HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        
        ErrorResponse errorResponse = buildErrorResponse(
            "CARD_UPDATE_FAILED",
            ex.getMessage(),
            status,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, status);
    }

    /**
     * Handles AccountUpdateException - account update failures.
     * <p>
     * Maps to COBOL COACTUPC.cbl account update error handling.
     * Returns HTTP 400 BAD_REQUEST or 409 CONFLICT.
     * </p>
     *
     * @param ex AccountUpdateException with update failure details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with appropriate status and error details
     */
    @ExceptionHandler(AccountUpdateException.class)
    public ResponseEntity<ErrorResponse> handleAccountUpdateException(
            AccountUpdateException ex, WebRequest request) {
        
        logger.error("Account update failed: {} - Reason: {}", ex.getAccountId(), ex.getFailureReason());
        
        Map<String, Object> details = new HashMap<>();
        details.put("accountId", ex.getAccountId());
        if (ex.getFailureReason() != null) {
            details.put("failureReason", ex.getFailureReason().name());
        }
        
        // Determine status based on error type
        HttpStatus status = ex.getMessage().contains("concurrent") || 
                          ex.getMessage().contains("conflict") ?
                          HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        
        ErrorResponse errorResponse = buildErrorResponse(
            "ACCOUNT_UPDATE_FAILED",
            ex.getMessage(),
            status,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, status);
    }

    /**
     * Handles ProfileUpdateException - user profile update failures.
     * <p>
     * Maps to COBOL COUSR01C.cbl profile update error handling.
     * Returns HTTP 400 BAD_REQUEST, 403 FORBIDDEN, or 409 CONFLICT.
     * </p>
     *
     * @param ex ProfileUpdateException with update failure details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with appropriate status and error details
     */
    @ExceptionHandler(ProfileUpdateException.class)
    public ResponseEntity<ErrorResponse> handleProfileUpdateException(
            ProfileUpdateException ex, WebRequest request) {
        
        logger.error("Profile update failed: {} - Reason: {}", ex.getUserId(), ex.getFailureReason());
        
        Map<String, Object> details = new HashMap<>();
        details.put("userId", ex.getUserId());
        if (ex.getFailureReason() != null) {
            details.put("failureReason", ex.getFailureReason().name());
        }
        
        // Determine status based on error type
        HttpStatus status;
        if (ex.getMessage().contains("unauthorized") || ex.getMessage().contains("permission")) {
            status = HttpStatus.FORBIDDEN;
        } else if (ex.getMessage().contains("concurrent") || ex.getMessage().contains("conflict")) {
            status = HttpStatus.CONFLICT;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        
        ErrorResponse errorResponse = buildErrorResponse(
            "PROFILE_UPDATE_FAILED",
            ex.getMessage(),
            status,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, status);
    }

    /**
     * Handles MethodArgumentNotValidException - Bean Validation failures.
     * <p>
     * Thrown when @Valid annotation on controller method parameters fails.
     * Maps to COBOL field validation logic.
     * Returns HTTP 400 BAD_REQUEST with field-level error details.
     * </p>
     * <p>
     * <b>COBOL Pattern:</b>
     * <pre>
     * IF WS-ACCOUNT-ID NOT NUMERIC
     *    MOVE 'Account ID must be numeric' TO WS-MESSAGE
     *    SET INPUT-ERROR TO TRUE
     * END-IF
     * </pre>
     * </p>
     *
     * @param ex MethodArgumentNotValidException with validation errors
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 400 status and field validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {
        
        logger.warn("Validation failed: {} errors", ex.getBindingResult().getErrorCount());
        
        Map<String, Object> details = new HashMap<>();
        List<Map<String, String>> fieldErrors = new ArrayList<>();
        
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            Map<String, String> fieldError = new HashMap<>();
            fieldError.put("field", error.getField());
            fieldError.put("rejectedValue", String.valueOf(error.getRejectedValue()));
            fieldError.put("message", error.getDefaultMessage());
            fieldErrors.add(fieldError);
            
            logger.debug("Validation error - Field: {}, Value: {}, Message: {}",
                        error.getField(), error.getRejectedValue(), error.getDefaultMessage());
        }
        
        details.put("fieldErrors", fieldErrors);
        details.put("errorCount", fieldErrors.size());
        
        ErrorResponse errorResponse = buildErrorResponse(
            "VALIDATION_ERROR",
            "Request validation failed. Please check field errors.",
            HttpStatus.BAD_REQUEST,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles IllegalArgumentException - invalid method arguments or business validation failures.
     * 
     * <p>Thrown when controller or service receives invalid parameters that fail business validation
     * rules (e.g., negative page numbers, missing required filters, invalid enum values).</p>
     * 
     * <p><strong>HTTP Status:</strong> 400 BAD_REQUEST</p>
     * 
     * @param ex IllegalArgumentException with validation error message
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 400 status and validation error details
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest request) {
        
        logger.warn("Illegal argument: {}", ex.getMessage());
        
        Map<String, Object> details = new HashMap<>();
        details.put("validationError", ex.getMessage());
        
        ErrorResponse errorResponse = buildErrorResponse(
            "VALIDATION_ERROR",
            ex.getMessage(),
            HttpStatus.BAD_REQUEST,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles ConstraintViolationException - constraint validation failures.
     * <p>
     * Thrown when @Validated annotation on controller class validates method
     * parameters or path variables.
     * Returns HTTP 400 BAD_REQUEST with constraint violation details.
     * </p>
     *
     * @param ex ConstraintViolationException with constraint violations
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 400 status and constraint violation details
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException ex, WebRequest request) {
        
        logger.warn("Constraint violation: {} violations", ex.getConstraintViolations().size());
        
        Map<String, Object> details = new HashMap<>();
        List<Map<String, String>> violations = new ArrayList<>();
        
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            Map<String, String> violationDetail = new HashMap<>();
            violationDetail.put("property", violation.getPropertyPath().toString());
            violationDetail.put("invalidValue", String.valueOf(violation.getInvalidValue()));
            violationDetail.put("message", violation.getMessage());
            violations.add(violationDetail);
            
            logger.debug("Constraint violation - Property: {}, Value: {}, Message: {}",
                        violation.getPropertyPath(), violation.getInvalidValue(), violation.getMessage());
        }
        
        details.put("violations", violations);
        details.put("violationCount", violations.size());
        
        ErrorResponse errorResponse = buildErrorResponse(
            "CONSTRAINT_VIOLATION",
            "Request constraint validation failed. Please check violations.",
            HttpStatus.BAD_REQUEST,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles DataAccessException - database access errors.
     * <p>
     * Maps to COBOL IOERR conditions from VSAM file operations.
     * Returns HTTP 500 INTERNAL_SERVER_ERROR.
     * </p>
     * <p>
     * <b>COBOL Pattern:</b>
     * <pre>
     * EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(IOERR)
     *        MOVE 'Database I/O error occurred' TO WS-MESSAGE
     * END-EVALUATE
     * </pre>
     * </p>
     * <p>
     * <b>Security Note:</b> Database error details are logged but not exposed
     * in response to prevent information disclosure.
     * </p>
     *
     * @param ex DataAccessException with database error details
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 500 status and generic error message
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccessException(
            DataAccessException ex, WebRequest request) {
        
        logger.error("Database access error: {}", ex.getMessage(), ex);
        
        // Don't expose database details in response for security
        Map<String, Object> details = new HashMap<>();
        details.put("errorType", ex.getClass().getSimpleName());
        
        ErrorResponse errorResponse = buildErrorResponse(
            "DATABASE_ERROR",
            "A database error occurred. Please try again later.",
            HttpStatus.INTERNAL_SERVER_ERROR,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Handles AccessDeniedException - authorization failures.
     * <p>
     * Thrown by Spring Security when user lacks required role or permission.
     * Maps to COBOL authorization check failures.
     * Returns HTTP 403 FORBIDDEN.
     * </p>
     * <p>
     * <b>COBOL Pattern:</b>
     * <pre>
     * IF SEC-USR-TYPE NOT = 'A'
     *    MOVE 'Administrative access required' TO WS-MESSAGE
     *    SET ACCESS-DENIED TO TRUE
     * END-IF
     * </pre>
     * </p>
     *
     * @param ex AccessDeniedException from Spring Security
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 403 status and generic error message
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex, WebRequest request) {
        
        logger.warn("Access denied: {}", ex.getMessage());
        
        Map<String, Object> details = new HashMap<>();
        
        ErrorResponse errorResponse = buildErrorResponse(
            "ACCESS_DENIED",
            "You do not have permission to access this resource.",
            HttpStatus.FORBIDDEN,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }

    /**
     * Handles all unhandled exceptions - catch-all handler.
     * <p>
     * Provides fallback handling for any exceptions not caught by specific handlers.
     * Returns HTTP 500 INTERNAL_SERVER_ERROR.
     * </p>
     * <p>
     * <b>Security Note:</b> Exception details are logged but generic message
     * is returned to prevent information disclosure.
     * </p>
     *
     * @param ex Any unhandled exception
     * @param request WebRequest for extracting request path
     * @return ResponseEntity with 500 status and generic error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(
            Exception ex, WebRequest request) {
        
        logger.error("Unhandled exception: {}", ex.getMessage(), ex);
        
        Map<String, Object> details = new HashMap<>();
        details.put("errorType", ex.getClass().getSimpleName());
        
        ErrorResponse errorResponse = buildErrorResponse(
            "INTERNAL_SERVER_ERROR",
            "An unexpected error occurred. Please try again later.",
            HttpStatus.INTERNAL_SERVER_ERROR,
            request,
            details
        );
        
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Builds a standardized ErrorResponse object.
     * <p>
     * Helper method to construct consistent error responses across all exception handlers.
     * Sets timestamp, status, error code, message, request path, and optional details.
     * </p>
     *
     * @param error Error code identifier (uppercase snake_case)
     * @param message Human-readable error message
     * @param status HTTP status code enum
     * @param request WebRequest for extracting request path
     * @param details Optional contextual details map
     * @return Fully populated ErrorResponse object
     */
    private ErrorResponse buildErrorResponse(String error, String message, 
                                            HttpStatus status, WebRequest request, 
                                            Map<String, Object> details) {
        ErrorResponse errorResponse = new ErrorResponse();
        errorResponse.setTimestamp(LocalDateTime.now());
        errorResponse.setStatus(status.value());
        errorResponse.setError(error);
        errorResponse.setMessage(message);
        errorResponse.setPath(extractRequestPath(request));
        errorResponse.setDetails(details != null ? details : new HashMap<>());
        
        return errorResponse;
    }

    /**
     * Extracts the request URI path from WebRequest.
     * <p>
     * Helper method to get the request path for including in error responses.
     * Handles various WebRequest implementations safely.
     * </p>
     *
     * @param request WebRequest to extract path from
     * @return Request URI path, or "unknown" if extraction fails
     */
    private String extractRequestPath(WebRequest request) {
        try {
            String description = request.getDescription(false);
            if (description != null && description.startsWith("uri=")) {
                return description.substring(4);
            }
            return description != null ? description : "unknown";
        } catch (Exception e) {
            logger.debug("Failed to extract request path: {}", e.getMessage());
            return "unknown";
        }
    }
}
