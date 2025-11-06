/*
 * ValidationException.java
 * 
 * CardDemo Application - Custom Validation Exception
 * 
 * This exception replaces COBOL 88-level validation flags from programs like
 * COACTUPC.cbl and COTRN02C.cbl, providing structured validation error handling
 * for REST API responses.
 * 
 * Original COBOL patterns transformed:
 * - FLG-ALPHA-NOT-OK, FLG-ALPHNANUM-NOT-OK, FLG-MANDATORY-NOT-OK
 * - INPUT-ERROR flag (line 173 in COACTUPC.cbl)
 * - Field validation methods: 1225-EDIT-ALPHA-REQD, 1245-EDIT-NUM-REQD,
 *   1260-EDIT-US-PHONE-NUM, 1270-EDIT-US-STATE-CD
 * 
 * Maps to HTTP 400 Bad Request in GlobalExceptionHandler
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

import java.util.HashMap;
import java.util.Map;

/**
 * Custom unchecked exception for data validation failures in the CardDemo application.
 * 
 * <p>This exception extends RuntimeException to provide structured validation error
 * handling across all input processing layers, replacing COBOL 88-level validation
 * flags from the mainframe application.</p>
 * 
 * <p><b>COBOL Validation Patterns Replaced:</b></p>
 * <ul>
 *   <li>88-level validation flags (FLG-ALPHA-NOT-OK, FLG-ALPHNANUM-NOT-OK, etc.)</li>
 *   <li>INPUT-ERROR flag indicating validation failures</li>
 *   <li>Field-specific validation for phone numbers, SSN, dates, state codes</li>
 *   <li>Cross-field validation logic</li>
 * </ul>
 * 
 * <p><b>Usage Scenarios:</b></p>
 * <ul>
 *   <li>Bean Validation constraint violations (@NotBlank, @NotNull, @Size, @Pattern)</li>
 *   <li>Business validation rule failures (invalid field combinations)</li>
 *   <li>Format validation failures (phone numbers, SSN, dates)</li>
 *   <li>Cross-field validation errors</li>
 * </ul>
 * 
 * <p><b>Exception Handling:</b></p>
 * <p>Caught by GlobalExceptionHandler and transformed into HTTP 400 Bad Request
 * responses with detailed field-level error messages in JSON format.</p>
 * 
 * <p><b>Example Usage:</b></p>
 * <pre>
 * // Single field validation error
 * if (accountId == null) {
 *     throw new ValidationException("Account ID is required");
 * }
 * 
 * // Multiple field validation errors
 * Map&lt;String, String&gt; errors = new HashMap&lt;&gt;();
 * errors.put("creditLimit", "Credit limit must be at least $1,000");
 * errors.put("accountStatus", "Account status must be 'Y' or 'N'");
 * throw new ValidationException("Account validation failed", errors);
 * </pre>
 * 
 * @see RuntimeException
 * @see java.util.HashMap
 */
public class ValidationException extends RuntimeException {
    
    /**
     * Serial version UID for serialization compatibility.
     */
    private static final long serialVersionUID = 1L;
    
    /**
     * Map of field-level validation errors.
     * Key: field name (e.g., "creditLimit", "accountStatus")
     * Value: error message describing the validation failure
     * 
     * <p>This structure provides detailed feedback for multi-field validation
     * failures, matching COBOL's ability to report multiple field-level errors
     * in a single validation pass.</p>
     */
    private final Map<String, String> fieldErrors;
    
    /**
     * Constructs a new ValidationException with the specified detail message.
     * 
     * <p>Use this constructor for general validation failures that don't
     * require field-level error details.</p>
     * 
     * <p><b>COBOL Pattern Replaced:</b> Setting INPUT-ERROR flag to '1' with
     * a general error message.</p>
     * 
     * @param message the detail message explaining the validation failure
     * 
     * @example
     * <pre>
     * throw new ValidationException("Invalid account data provided");
     * </pre>
     */
    public ValidationException(String message) {
        super(message);
        this.fieldErrors = new HashMap<>();
    }
    
    /**
     * Constructs a new ValidationException with the specified detail message
     * and cause.
     * 
     * <p>Use this constructor when a validation failure is caused by an
     * underlying exception (e.g., parsing error, format exception).</p>
     * 
     * <p><b>COBOL Pattern Replaced:</b> Validation errors caused by data
     * type conversion failures or format parsing errors.</p>
     * 
     * @param message the detail message explaining the validation failure
     * @param cause the underlying exception that caused this validation failure
     * 
     * @example
     * <pre>
     * try {
     *     BigDecimal amount = new BigDecimal(amountString);
     * } catch (NumberFormatException e) {
     *     throw new ValidationException("Invalid amount format", e);
     * }
     * </pre>
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.fieldErrors = new HashMap<>();
    }
    
    /**
     * Constructs a new ValidationException with field-level validation errors.
     * 
     * <p>Use this constructor when multiple fields fail validation and you
     * need to report all errors at once.</p>
     * 
     * <p><b>COBOL Pattern Replaced:</b> Multiple 88-level validation flags
     * set to error states (FLG-ALPHA-NOT-OK, FLG-CRED-LIMIT-NOT-OK, etc.)
     * with INPUT-ERROR flag set to '1'.</p>
     * 
     * @param fieldErrors map of field names to error messages
     * 
     * @example
     * <pre>
     * Map&lt;String, String&gt; errors = new HashMap&lt;&gt;();
     * errors.put("userId", "User ID is required");
     * errors.put("password", "Password must be at least 8 characters");
     * throw new ValidationException(errors);
     * </pre>
     */
    public ValidationException(Map<String, String> fieldErrors) {
        super(buildMessageFromFieldErrors(fieldErrors));
        this.fieldErrors = new HashMap<>(fieldErrors);
    }
    
    /**
     * Constructs a new ValidationException with a detail message and
     * field-level validation errors.
     * 
     * <p>Use this constructor when you want to provide both a general
     * validation message and detailed field-level errors.</p>
     * 
     * <p><b>COBOL Pattern Replaced:</b> General validation failure message
     * combined with specific field-level error flags, matching patterns where
     * COBOL programs display a summary message plus field-specific highlights.</p>
     * 
     * @param message the detail message explaining the overall validation failure
     * @param fieldErrors map of field names to error messages
     * 
     * @example
     * <pre>
     * Map&lt;String, String&gt; errors = new HashMap&lt;&gt;();
     * errors.put("creditLimit", "Must be between $1,000 and $999,999,999");
     * errors.put("cashCreditLimit", "Cannot exceed credit limit");
     * throw new ValidationException("Account update validation failed", errors);
     * </pre>
     */
    public ValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = new HashMap<>(fieldErrors);
    }
    
    /**
     * Returns the map of field-level validation errors.
     * 
     * <p>This method provides access to detailed validation error information
     * for each field that failed validation, enabling the GlobalExceptionHandler
     * to construct detailed JSON error responses.</p>
     * 
     * <p><b>Response Structure:</b> The returned map contains field names as keys
     * and error messages as values, matching the JSON error response format
     * expected by the React frontend.</p>
     * 
     * @return unmodifiable map of field names to error messages; empty map if
     *         no field-level errors were recorded
     * 
     * @example
     * <pre>
     * {
     *   "creditLimit": "Credit limit must be at least $1,000",
     *   "accountStatus": "Account status must be 'Y' or 'N'"
     * }
     * </pre>
     */
    public Map<String, String> getFieldErrors() {
        // Return a new HashMap to prevent external modification while allowing
        // the GlobalExceptionHandler to read the errors
        return new HashMap<>(fieldErrors);
    }
    
    /**
     * Returns the detail message string of this ValidationException.
     * 
     * <p>This method overrides the RuntimeException getMessage() to provide
     * consistent message formatting, especially when constructed with field
     * errors only.</p>
     * 
     * @return the detail message string of this ValidationException instance
     */
    @Override
    public String getMessage() {
        return super.getMessage();
    }
    
    /**
     * Builds a comprehensive error message from field-level validation errors.
     * 
     * <p>This helper method constructs a human-readable error message that
     * summarizes all field validation failures, used when ValidationException
     * is constructed with field errors but no explicit message.</p>
     * 
     * <p><b>Message Format:</b> "Validation failed for [N] field(s): field1, field2, ..."</p>
     * 
     * @param fieldErrors map of field names to error messages
     * @return formatted error message summarizing all validation failures
     */
    private static String buildMessageFromFieldErrors(Map<String, String> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return "Validation failed";
        }
        
        int errorCount = fieldErrors.size();
        String fieldList = String.join(", ", fieldErrors.keySet());
        
        return String.format("Validation failed for %d field%s: %s",
                errorCount,
                errorCount == 1 ? "" : "s",
                fieldList);
    }
}
