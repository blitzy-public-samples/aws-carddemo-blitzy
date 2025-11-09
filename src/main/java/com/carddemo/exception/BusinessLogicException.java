/*
 * BusinessLogicException.java
 * 
 * Custom unchecked exception for business rule violations and application logic errors
 * in the CardDemo application.
 * 
 * This exception replaces COBOL business logic error patterns including:
 * - COTRN02C.cbl: Transaction validation rejections (credit limit violations, expired cards)
 * - COACTUPC.cbl: Optimistic locking failures and concurrent modification detection
 * - CBTRN02C.cbl: Batch transaction posting business rule violations
 * - COBIL00C.cbl: Bill payment processing failures
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
 * Custom unchecked exception for business rule violations in the CardDemo application.
 * <p>
 * This exception extends RuntimeException to enable automatic exception propagation and
 * Spring @Transactional rollback without requiring explicit throws declarations, matching
 * COBOL RESP-CD error handling patterns that trigger SYNCPOINT ROLLBACK on business rule
 * violations.
 * </p>
 * 
 * <h2>Common Error Codes</h2>
 * <ul>
 *   <li><b>CREDIT_LIMIT_EXCEEDED</b> - Transaction amount exceeds available credit limit</li>
 *   <li><b>CARD_EXPIRED</b> - Card expiration date has passed</li>
 *   <li><b>CARD_INACTIVE</b> - Card status is not active</li>
 *   <li><b>ACCOUNT_CLOSED</b> - Account is closed or suspended</li>
 *   <li><b>INSUFFICIENT_FUNDS</b> - Insufficient balance for payment processing</li>
 *   <li><b>CONCURRENT_MODIFICATION</b> - Record modified by another user (optimistic locking failure)</li>
 *   <li><b>INVALID_CROSS_REFERENCE</b> - Cross-reference relationship validation failed</li>
 *   <li><b>TRANSACTION_VALIDATION_FAILED</b> - General transaction validation failure</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * <pre>
 * // Simple message-only exception
 * throw new BusinessLogicException("Credit limit exceeded for transaction");
 * 
 * // Exception with error code for categorization
 * throw new BusinessLogicException("CREDIT_LIMIT_EXCEEDED", 
 *     "Transaction amount exceeds available credit limit");
 * 
 * // Exception with structured error details
 * Map&lt;String, Object&gt; details = new HashMap&lt;&gt;();
 * details.put("creditLimit", accountCreditLimit);
 * details.put("transactionAmount", requestAmount);
 * details.put("availableCredit", availableCredit);
 * throw new BusinessLogicException("CREDIT_LIMIT_EXCEEDED", 
 *     "Transaction amount exceeds available credit limit", details);
 * 
 * // Exception with cause for cascading failures
 * try {
 *     // some operation
 * } catch (DataAccessException e) {
 *     throw new BusinessLogicException("Failed to validate account status", e);
 * }
 * </pre>
 * 
 * <h2>Integration with Spring Framework</h2>
 * <ul>
 *   <li>GlobalExceptionHandler catches this exception and transforms it into appropriate HTTP responses</li>
 *   <li>HTTP 500 Internal Server Error for general business logic failures</li>
 *   <li>HTTP 409 Conflict for concurrent modification errors (CONCURRENT_MODIFICATION code)</li>
 *   <li>Spring @Transactional automatically triggers rollback when this exception is thrown</li>
 * </ul>
 * 
 * @see RuntimeException
 * @since 1.0
 */
public class BusinessLogicException extends RuntimeException {

    /**
     * Serial version UID for serialization compatibility.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Error code categorizing the type of business logic failure.
     * <p>
     * This field enables programmatic handling of different error categories
     * and supports mapping to appropriate HTTP status codes in the exception handler.
     * </p>
     * 
     * @see #getErrorCode()
     * @see #setErrorCode(String)
     */
    private String errorCode;

    /**
     * Additional structured error details providing context about the business rule violation.
     * <p>
     * This map can contain relevant business data such as credit limits, transaction amounts,
     * account balances, or any other contextual information useful for debugging or error
     * reporting. The map is mutable to allow adding details after exception construction.
     * </p>
     * 
     * @see #getErrorDetails()
     * @see #setErrorDetails(Map)
     */
    private Map<String, Object> errorDetails;

    /**
     * Constructs a new BusinessLogicException with the specified detail message.
     * <p>
     * This constructor is used for general business rule violations where a simple
     * error message is sufficient and no error code categorization is needed.
     * </p>
     * <p>
     * <b>COBOL Pattern Mapping:</b> Replaces COBOL error flag checking patterns where
     * WS-ERR-FLG is set to 'Y' with a descriptive message in WS-MESSAGE, typically
     * used in COTRN02C.cbl validation routines.
     * </p>
     * 
     * @param message the detail message describing the business rule violation
     */
    public BusinessLogicException(String message) {
        super(message);
        this.errorDetails = new HashMap<>();
    }

    /**
     * Constructs a new BusinessLogicException with an error code and detail message.
     * <p>
     * This constructor is recommended for business rule violations that fall into
     * specific categories, enabling programmatic error handling and appropriate HTTP
     * status code mapping in the global exception handler.
     * </p>
     * <p>
     * <b>COBOL Pattern Mapping:</b> Replaces COBOL EVALUATE statements on RESP-CD values
     * where different response codes trigger different error handling paths, commonly
     * seen in COACTUPC.cbl for file operation failures and COBIL00C.cbl for payment
     * processing errors.
     * </p>
     * 
     * @param errorCode the error code categorizing the business logic failure
     * @param message the detail message describing the business rule violation
     */
    public BusinessLogicException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.errorDetails = new HashMap<>();
    }

    /**
     * Constructs a new BusinessLogicException with a detail message and cause.
     * <p>
     * This constructor is used when a business logic error is caused by an underlying
     * exception, typically from the data access layer or external service calls.
     * The cause is preserved for debugging and logging purposes.
     * </p>
     * <p>
     * <b>COBOL Pattern Mapping:</b> Replaces COBOL error handling where a file I/O
     * error or subprogram failure triggers a business logic error condition, preserving
     * the original error context while wrapping it in business-level semantics.
     * </p>
     * 
     * @param message the detail message describing the business rule violation
     * @param cause the underlying cause of the business logic failure
     */
    public BusinessLogicException(String message, Throwable cause) {
        super(message, cause);
        this.errorDetails = new HashMap<>();
    }

    /**
     * Constructs a new BusinessLogicException with error code, message, and structured details.
     * <p>
     * This is the most comprehensive constructor, providing full context about the business
     * rule violation including categorization, description, and specific data values that
     * contributed to the error condition.
     * </p>
     * <p>
     * <b>COBOL Pattern Mapping:</b> Replaces complex COBOL error handling in CBTRN02C.cbl
     * batch processing where multiple data points need to be captured for reject file
     * generation and error reporting, including transaction amounts, account limits,
     * balance calculations, and cross-reference validation results.
     * </p>
     * 
     * @param errorCode the error code categorizing the business logic failure
     * @param message the detail message describing the business rule violation
     * @param errorDetails map containing structured error context and business data
     */
    public BusinessLogicException(String errorCode, String message, Map<String, Object> errorDetails) {
        super(message);
        this.errorCode = errorCode;
        this.errorDetails = errorDetails != null ? new HashMap<>(errorDetails) : new HashMap<>();
    }

    /**
     * Returns the error code categorizing this business logic failure.
     * <p>
     * The error code enables programmatic handling of specific error types and
     * supports mapping to appropriate HTTP status codes. Returns null if no
     * error code was specified during exception construction.
     * </p>
     * 
     * @return the error code, or null if not set
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Sets the error code categorizing this business logic failure.
     * <p>
     * This setter allows modifying the error code after exception construction,
     * which can be useful in exception handling chains where additional context
     * is discovered during exception propagation.
     * </p>
     * 
     * @param errorCode the error code to set
     */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    /**
     * Returns the map containing structured error details.
     * <p>
     * The error details map provides additional context about the business rule
     * violation, including relevant business data values that contributed to the
     * error condition. The returned map is mutable, allowing callers to add
     * additional details if needed.
     * </p>
     * <p>
     * Returns an empty map if no error details were provided during construction
     * or if they were explicitly cleared.
     * </p>
     * 
     * @return map containing error details, never null
     */
    public Map<String, Object> getErrorDetails() {
        return errorDetails;
    }

    /**
     * Sets the map containing structured error details.
     * <p>
     * This setter allows replacing the entire error details map after exception
     * construction. If the provided map is null, an empty HashMap is set instead
     * to maintain the non-null contract of the getter.
     * </p>
     * 
     * @param errorDetails map containing error details, or null to set an empty map
     */
    public void setErrorDetails(Map<String, Object> errorDetails) {
        this.errorDetails = errorDetails != null ? new HashMap<>(errorDetails) : new HashMap<>();
    }

    /**
     * Returns a string representation of this exception including error code and details.
     * <p>
     * Overrides the default toString() method to include the error code and error details
     * in addition to the standard exception message and stack trace information, providing
     * comprehensive context for logging and debugging.
     * </p>
     * 
     * @return string representation of this exception
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        if (errorCode != null) {
            sb.append(" [errorCode=").append(errorCode).append("]");
        }
        if (errorDetails != null && !errorDetails.isEmpty()) {
            sb.append(" [errorDetails=").append(errorDetails).append("]");
        }
        return sb.toString();
    }
}
