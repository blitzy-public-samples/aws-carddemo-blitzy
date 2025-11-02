/*
 * TransactionException.java
 *
 * Custom runtime exception for transaction processing errors in the CardDemo application.
 * This exception represents various failures during transaction creation, updates, deletions,
 * and retrieval operations, mapping COBOL CICS RESP/RESP2 codes to Java exception handling.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.exception;

import java.io.Serializable;

/**
 * TransactionException is a flexible custom runtime exception for transaction processing failures.
 * 
 * <p>This exception is thrown during various transaction operations including:
 * <ul>
 *   <li>Transaction creation failures (validation, duplicate detection, posting errors)</li>
 *   <li>Transaction retrieval errors (not found, invalid parameters)</li>
 *   <li>Transaction update failures (concurrency issues, status conflicts)</li>
 *   <li>Transaction aggregation and category processing errors</li>
 *   <li>Bill payment transaction failures</li>
 * </ul>
 * 
 * <p>This exception maps COBOL CICS RESP/RESP2 error codes to Java exception handling:
 * <ul>
 *   <li>DFHRESP(NORMAL) = 0: Success (exception not thrown)</li>
 *   <li>DFHRESP(NOTFND) = 13: Transaction not found</li>
 *   <li>DFHRESP(DUPKEY) = 15: Duplicate transaction key</li>
 *   <li>DFHRESP(DUPREC) = 16: Duplicate transaction record</li>
 *   <li>DFHRESP(INVREQ) = 16: Invalid transaction request</li>
 *   <li>DFHRESP(IOERR) = 17: I/O error during transaction processing</li>
 *   <li>DFHRESP(ENDFILE): End of file during sequential processing</li>
 * </ul>
 * 
 * <p>As a RuntimeException, this exception triggers automatic rollback when thrown
 * within @Transactional methods, preserving transaction boundary semantics from
 * CICS SYNCPOINT operations.
 * 
 * <p>Usage Examples:
 * <pre>
 * // Simple message-only exception
 * throw new TransactionException("Transaction validation failed");
 * 
 * // With application error code
 * throw new TransactionException("Duplicate transaction detected", "TXN_DUPLICATE");
 * 
 * // With CICS RESP/RESP2 codes
 * throw new TransactionException("Transaction not found", 13, 0);
 * 
 * // Full context with cause
 * throw new TransactionException(
 *     "Database error during transaction insert",
 *     "TXN_DB_ERROR",
 *     17,
 *     0,
 *     sqlException
 * );
 * </pre>
 *
 * @see com.carddemo.service.TransactionCreationService
 * @see com.carddemo.service.TransactionListService
 * @see com.carddemo.service.TransactionCategoryService
 * @see com.carddemo.service.BillPaymentService
 * @see com.carddemo.exception.GlobalExceptionHandler
 * 
 * @author CardDemo Development Team
 * @version 1.0
 * @since 1.0
 */
public class TransactionException extends RuntimeException implements Serializable {

    /**
     * Serial version UID for serialization compatibility.
     * This ensures the exception can be properly serialized/deserialized
     * across distributed systems and remote method invocations.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Application-specific error code for categorizing the exception.
     * Examples: "TXN_NOT_FOUND", "TXN_DUPLICATE", "TXN_VALIDATION_FAILED"
     */
    private final String errorCode;

    /**
     * CICS RESP code equivalent for mainframe error mapping.
     * Represents the primary response code from CICS operations:
     * - 0: NORMAL (success)
     * - 13: NOTFND (not found)
     * - 15: DUPKEY (duplicate key)
     * - 16: DUPREC/INVREQ (duplicate record or invalid request)
     * - 17: IOERR (I/O error)
     */
    private final Integer respCode;

    /**
     * CICS RESP2 code equivalent for detailed mainframe error information.
     * Provides additional context about the primary RESP code.
     * May be null if detailed error information is not available.
     */
    private final Integer resp2Code;

    /**
     * Constructs a new TransactionException with the specified detail message.
     * 
     * <p>This constructor is suitable for simple error scenarios where
     * only a descriptive message is needed without additional error codes.
     *
     * @param message the detail message explaining the exception cause
     */
    public TransactionException(String message) {
        super(message);
        this.errorCode = null;
        this.respCode = null;
        this.resp2Code = null;
    }

    /**
     * Constructs a new TransactionException with a message and application error code.
     * 
     * <p>This constructor is suitable for application-level errors where a
     * categorized error code is needed for client-side error handling.
     *
     * @param message the detail message explaining the exception cause
     * @param errorCode the application-specific error code (e.g., "TXN_DUPLICATE")
     */
    public TransactionException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
        this.respCode = null;
        this.resp2Code = null;
    }

    /**
     * Constructs a new TransactionException with a message and CICS RESP/RESP2 codes.
     * 
     * <p>This constructor is suitable for errors that directly map to mainframe
     * CICS error conditions, preserving the original error semantics during migration.
     *
     * @param message the detail message explaining the exception cause
     * @param respCode the CICS RESP code equivalent (e.g., 13 for NOTFND)
     * @param resp2Code the CICS RESP2 code for additional error context
     */
    public TransactionException(String message, Integer respCode, Integer resp2Code) {
        super(message);
        this.errorCode = null;
        this.respCode = respCode;
        this.resp2Code = resp2Code;
    }

    /**
     * Constructs a new TransactionException with a message, error code, and cause.
     * 
     * <p>This constructor is suitable for wrapping lower-level exceptions
     * (e.g., DataAccessException, ConstraintViolationException) while preserving
     * the exception chain for debugging and logging.
     *
     * @param message the detail message explaining the exception cause
     * @param errorCode the application-specific error code
     * @param cause the underlying exception that caused this exception
     */
    public TransactionException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.respCode = null;
        this.resp2Code = null;
    }

    /**
     * Constructs a new TransactionException with full context including message,
     * error code, CICS RESP/RESP2 codes, and cause.
     * 
     * <p>This constructor provides complete error context suitable for complex
     * error scenarios requiring both application-level and mainframe-level error
     * information along with the underlying exception cause.
     *
     * @param message the detail message explaining the exception cause
     * @param errorCode the application-specific error code
     * @param respCode the CICS RESP code equivalent
     * @param resp2Code the CICS RESP2 code for additional error context
     * @param cause the underlying exception that caused this exception
     */
    public TransactionException(String message, String errorCode, Integer respCode, 
                                Integer resp2Code, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.respCode = respCode;
        this.resp2Code = resp2Code;
    }

    /**
     * Returns the application-specific error code.
     * 
     * <p>The error code is used for categorizing exceptions and providing
     * structured error information to API clients. May be null if not specified.
     *
     * @return the error code, or null if not set
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the CICS RESP code equivalent.
     * 
     * <p>The RESP code maps to mainframe CICS response codes for preserving
     * error semantics during migration. Common values:
     * <ul>
     *   <li>0: NORMAL (success - exception typically not thrown)</li>
     *   <li>13: NOTFND (resource not found)</li>
     *   <li>15: DUPKEY (duplicate key violation)</li>
     *   <li>16: DUPREC/INVREQ (duplicate record or invalid request)</li>
     *   <li>17: IOERR (I/O error)</li>
     * </ul>
     *
     * @return the RESP code, or null if not set
     */
    public Integer getRespCode() {
        return respCode;
    }

    /**
     * Returns the CICS RESP2 code equivalent.
     * 
     * <p>The RESP2 code provides additional detail about the primary RESP code.
     * This field preserves detailed error information from mainframe operations.
     * May be null if detailed error information is not available.
     *
     * @return the RESP2 code, or null if not set
     */
    public Integer getResp2Code() {
        return resp2Code;
    }

    /**
     * Returns a string representation of this exception including all error context.
     * 
     * <p>This method overrides the default toString() to include error code and
     * RESP/RESP2 codes when available, providing comprehensive error information
     * for logging and debugging.
     *
     * @return a detailed string representation of this exception
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName());
        
        String message = getMessage();
        if (message != null) {
            sb.append(": ").append(message);
        }
        
        if (errorCode != null) {
            sb.append(" [errorCode=").append(errorCode).append("]");
        }
        
        if (respCode != null) {
            sb.append(" [RESP=").append(respCode);
            if (resp2Code != null) {
                sb.append(", RESP2=").append(resp2Code);
            }
            sb.append("]");
        }
        
        return sb.toString();
    }
}
