package com.carddemo.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

/**
 * Standard error response DTO for consistent REST API error handling.
 * 
 * Provides structured error information returned to clients when exceptions occur.
 * This DTO standardizes error responses across all API endpoints following Spring Boot
 * best practices for REST API error handling.
 * 
 * Purpose:
 * - Provides uniform error response structure for all REST API exceptions
 * - Used by GlobalExceptionHandler to transform Java exceptions into JSON error responses
 * - Ensures consistent client-side error handling with predictable error format
 * - Includes timestamp, HTTP status code, error type, message, details, and request path
 * 
 * Pattern:
 * - Implements immutable Java record pattern (Java 14+)
 * - All fields are final and initialized via constructor
 * - Provides accessor methods (timestamp(), status(), error(), etc.)
 * - Includes static factory methods for common error scenarios
 * 
 * Usage:
 * - Created by GlobalExceptionHandler @ExceptionHandler methods
 * - Returned as ResponseEntity body with appropriate HTTP status code
 * - Serialized to JSON by Jackson ObjectMapper
 * 
 * Example JSON output:
 * <pre>
 * {
 *   "timestamp": "2024-01-15T10:30:45",
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "Account not found",
 *   "details": "Account with ID 12345 does not exist in the system",
 *   "path": "/api/accounts/12345"
 * }
 * </pre>
 * 
 * Migration Notes:
 * - No direct COBOL equivalent (new Spring Boot best practice pattern)
 * - Replaces COBOL error handling with APPL-RESULT codes and ERRMSG fields
 * - Provides more structured error information than COBOL CICS ABEND codes
 * - Maps COBOL file-status codes and CICS RESP codes to HTTP status codes
 * 
 * @param timestamp LocalDateTime when the error occurred (formatted as yyyy-MM-dd'T'HH:mm:ss)
 * @param status HTTP status code (e.g., 400, 404, 500)
 * @param error HTTP status reason phrase (e.g., "Bad Request", "Not Found", "Internal Server Error")
 * @param message Brief error message describing what went wrong
 * @param details Detailed error description with additional context
 * @param path Request URI path where the error occurred
 * 
 * @author CardDemo Development Team
 * @version 1.0
 * @since 2024-01-15
 */
public record ErrorResponse(
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    LocalDateTime timestamp,
    int status,
    String error,
    String message,
    String details,
    String path
) {
    
    /**
     * Creates a standard ErrorResponse with all required fields.
     * 
     * This is the canonical constructor called by all factory methods and
     * used by Jackson for JSON deserialization.
     * 
     * @param timestamp LocalDateTime when the error occurred
     * @param status HTTP status code
     * @param error HTTP status reason phrase
     * @param message Brief error message
     * @param details Detailed error description
     * @param path Request URI path
     */
    public ErrorResponse {
        // Compact constructor for validation (Java 14+ record feature)
        // Validate non-null required fields
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
        if (error == null || error.isBlank()) {
            error = "Unknown Error";
        }
        if (message == null || message.isBlank()) {
            message = "An unexpected error occurred";
        }
    }
    
    /**
     * Factory method to create a Bad Request (400) error response.
     * 
     * Used for client errors where the request cannot be processed due to
     * validation failures, malformed syntax, or invalid request parameters.
     * 
     * COBOL Mapping:
     * - Maps from COBOL field validation errors (PIC clause violations)
     * - Replaces COBOL numeric validation failures (NUMERIC test)
     * - Corresponds to COBOL APPL-RESULT error codes
     * 
     * @param message Brief error message
     * @param details Detailed validation error description
     * @param path Request URI path
     * @return ErrorResponse with HTTP 400 status
     */
    public static ErrorResponse badRequest(String message, String details, String path) {
        return new ErrorResponse(
            LocalDateTime.now(),
            400,
            "Bad Request",
            message,
            details,
            path
        );
    }
    
    /**
     * Factory method to create a Not Found (404) error response.
     * 
     * Used when a requested resource (account, card, transaction, user) does not exist.
     * 
     * COBOL Mapping:
     * - Maps from COBOL file-status 23 (record not found)
     * - Replaces COBOL EXEC CICS READ ... NOTFND condition
     * - Corresponds to VSAM KSDS key not found errors
     * 
     * @param message Brief error message (e.g., "Account not found")
     * @param details Detailed description (e.g., "Account with ID 12345 does not exist")
     * @param path Request URI path
     * @return ErrorResponse with HTTP 404 status
     */
    public static ErrorResponse notFound(String message, String details, String path) {
        return new ErrorResponse(
            LocalDateTime.now(),
            404,
            "Not Found",
            message,
            details,
            path
        );
    }
    
    /**
     * Factory method to create an Internal Server Error (500) error response.
     * 
     * Used for unexpected server-side errors including database failures,
     * programming errors, or system resource issues.
     * 
     * COBOL Mapping:
     * - Maps from COBOL EXEC CICS ABEND codes
     * - Replaces COBOL file-status codes 90+ (system errors)
     * - Corresponds to VSAM I/O errors and CICS system failures
     * 
     * @param message Brief error message (e.g., "Database connection failed")
     * @param details Detailed error description with stack trace information
     * @param path Request URI path
     * @return ErrorResponse with HTTP 500 status
     */
    public static ErrorResponse internalError(String message, String details, String path) {
        return new ErrorResponse(
            LocalDateTime.now(),
            500,
            "Internal Server Error",
            message,
            details,
            path
        );
    }
    
    /**
     * Factory method to create an Unauthorized (401) error response.
     * 
     * Used when authentication is required but not provided or invalid.
     * 
     * COBOL Mapping:
     * - Maps from COBOL RACF authentication failures
     * - Replaces COBOL signon validation errors in COSGN00C.cbl
     * - Corresponds to USRSEC file record not found or password mismatch
     * 
     * @param message Brief error message (e.g., "Authentication failed")
     * @param details Detailed description (e.g., "Invalid user ID or password")
     * @param path Request URI path
     * @return ErrorResponse with HTTP 401 status
     */
    public static ErrorResponse unauthorized(String message, String details, String path) {
        return new ErrorResponse(
            LocalDateTime.now(),
            401,
            "Unauthorized",
            message,
            details,
            path
        );
    }
    
    /**
     * Factory method to create a Forbidden (403) error response.
     * 
     * Used when the authenticated user lacks sufficient permissions for the requested resource.
     * 
     * COBOL Mapping:
     * - Maps from COBOL RACF authorization failures
     * - Replaces COBOL user type validation (SEC-USR-TYPE checks)
     * - Corresponds to CICS transaction authorization failures
     * 
     * @param message Brief error message (e.g., "Access denied")
     * @param details Detailed description (e.g., "User does not have admin privileges")
     * @param path Request URI path
     * @return ErrorResponse with HTTP 403 status
     */
    public static ErrorResponse forbidden(String message, String details, String path) {
        return new ErrorResponse(
            LocalDateTime.now(),
            403,
            "Forbidden",
            message,
            details,
            path
        );
    }
    
    /**
     * Factory method to create a Conflict (409) error response.
     * 
     * Used when the request conflicts with the current state (e.g., duplicate key, version conflict).
     * 
     * COBOL Mapping:
     * - Maps from COBOL file-status 22 (duplicate key on WRITE)
     * - Replaces COBOL EXEC CICS WRITE ... DUPREC condition
     * - Corresponds to VSAM KSDS duplicate primary key violations
     * 
     * @param message Brief error message (e.g., "Duplicate account ID")
     * @param details Detailed description (e.g., "Account ID 12345 already exists")
     * @param path Request URI path
     * @return ErrorResponse with HTTP 409 status
     */
    public static ErrorResponse conflict(String message, String details, String path) {
        return new ErrorResponse(
            LocalDateTime.now(),
            409,
            "Conflict",
            message,
            details,
            path
        );
    }
    
    /**
     * Factory method to create a generic error response with custom status code.
     * 
     * Used for any HTTP status code not covered by specific factory methods.
     * 
     * @param status HTTP status code
     * @param error HTTP status reason phrase
     * @param message Brief error message
     * @param details Detailed error description
     * @param path Request URI path
     * @return ErrorResponse with specified status
     */
    public static ErrorResponse of(int status, String error, String message, String details, String path) {
        return new ErrorResponse(
            LocalDateTime.now(),
            status,
            error,
            message,
            details,
            path
        );
    }
}
