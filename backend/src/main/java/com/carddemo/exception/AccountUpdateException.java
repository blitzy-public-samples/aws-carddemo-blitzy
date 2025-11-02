/*
 * AccountUpdateException.java
 * 
 * Custom runtime exception for account update operation failures in the CardDemo application.
 * This exception is thrown when account modification operations cannot be completed due to:
 * - Business rule validation failures (invalid credit limits, status transitions, dates)
 * - Concurrent modification conflicts (optimistic locking failures)
 * - Database constraint violations
 * - Record locking issues
 * - Customer update failures during account update transactions
 * 
 * Maps to COBOL COACTUPC.cbl error conditions including:
 * - COULD-NOT-LOCK-ACCT-FOR-UPDATE (lines 3912)
 * - COULD-NOT-LOCK-CUST-FOR-UPDATE (lines 3939)
 * - DATA-WAS-CHANGED-BEFORE-UPDATE (lines 3950-3951, 4143, 4189)
 * - LOCKED-BUT-UPDATE-FAILED (lines 4079, 4098)
 * - CRED-LIMIT-IS-NOT-VALID (lines 507-508)
 * - ACCT-STATUS-MUST-BE-YES-NO (lines 503-504)
 * - Invalid date validations (lines 509-512)
 * - DID-NOT-FIND-ACCT-IN-ACCTDAT (lines 499-500)
 * - DID-NOT-FIND-CUST-IN-CUSTDAT (lines 501-502)
 * 
 * Used by AccountUpdateService to enable GlobalExceptionHandler to return
 * appropriate HTTP status codes:
 * - 400 BAD_REQUEST for validation failures
 * - 404 NOT_FOUND for account not found
 * - 409 CONFLICT for concurrent update conflicts and locking issues
 * 
 * This exception is immutable and thread-safe, storing the failing account ID
 * and specific failure reason for detailed error reporting and client troubleshooting.
 * 
 * @see com.carddemo.service.AccountUpdateService
 * @see com.carddemo.exception.GlobalExceptionHandler
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.carddemo.exception;

/**
 * Runtime exception representing account update operation failures.
 * 
 * <p>This exception provides detailed context about account update failures including:
 * <ul>
 *   <li>The account ID that failed to update</li>
 *   <li>The specific reason for failure via UpdateFailureReason enum</li>
 *   <li>Optional underlying cause exception</li>
 *   <li>Custom error message for detailed diagnostics</li>
 * </ul>
 * 
 * <p><b>Thread Safety:</b> This class is immutable and thread-safe. All fields are final
 * and the class does not expose any mutator methods.
 * 
 * <p><b>Exception Hierarchy:</b> Extends RuntimeException to enable unchecked exception
 * handling and automatic transaction rollback via Spring's @Transactional annotation.
 * 
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * public void updateAccount(AccountUpdateRequest request) {
 *     Account account = accountRepository.findById(request.getAccountId())
 *         .orElseThrow(() -> new AccountUpdateException(
 *             request.getAccountId(),
 *             UpdateFailureReason.ACCOUNT_NOT_FOUND
 *         ));
 *     
 *     if (request.getCreditLimit().compareTo(MAX_CREDIT_LIMIT) > 0) {
 *         throw new AccountUpdateException(
 *             "Credit limit " + request.getCreditLimit() + " exceeds maximum allowed",
 *             request.getAccountId(),
 *             UpdateFailureReason.INVALID_CREDIT_LIMIT
 *         );
 *     }
 * }
 * }</pre>
 * 
 * @author CardDemo Development Team
 * @version 1.0
 * @since 1.0
 */
public class AccountUpdateException extends RuntimeException {
    
    /**
     * Serial version UID for serialization compatibility.
     */
    private static final long serialVersionUID = 1L;
    
    /**
     * The account identifier that failed to update.
     * Used for error logging, monitoring, and client error messages.
     * May be null if the failure occurred before account ID was determined.
     */
    private final String accountId;
    
    /**
     * The specific reason for the account update failure.
     * Enables precise error handling and appropriate HTTP status code mapping.
     * May be null if the failure reason cannot be categorized.
     */
    private final UpdateFailureReason failureReason;
    
    /**
     * Enumeration of all possible account update failure reasons.
     * 
     * <p>Each enum value maps to specific COBOL error conditions in COACTUPC.cbl
     * and determines the HTTP status code returned to REST API clients:
     * <ul>
     *   <li>ACCOUNT_NOT_FOUND → 404 NOT_FOUND</li>
     *   <li>CONCURRENT_UPDATE_CONFLICT → 409 CONFLICT</li>
     *   <li>ACCOUNT_LOCKED → 409 CONFLICT</li>
     *   <li>Validation failures → 400 BAD_REQUEST</li>
     *   <li>Database errors → 500 INTERNAL_SERVER_ERROR</li>
     * </ul>
     */
    public enum UpdateFailureReason {
        /**
         * Account ID does not exist in the account master file.
         * Maps to COBOL: DID-NOT-FIND-ACCT-IN-ACCTDAT (line 499).
         * HTTP Status: 404 NOT_FOUND.
         */
        ACCOUNT_NOT_FOUND,
        
        /**
         * Credit limit value exceeds business rules or is invalid format.
         * Maps to COBOL: CRED-LIMIT-IS-NOT-VALID (line 507), FLG-CRED-LIMIT-NOT-OK (line 198).
         * HTTP Status: 400 BAD_REQUEST.
         */
        INVALID_CREDIT_LIMIT,
        
        /**
         * Account status transition is not allowed (e.g., closed to active).
         * Maps to COBOL: ACCT-STATUS-MUST-BE-YES-NO (line 503), FLG-ACCT-STATUS-NOT-OK (line 194).
         * HTTP Status: 400 BAD_REQUEST.
         */
        INVALID_STATUS_TRANSITION,
        
        /**
         * Account expiration date is invalid or in the past.
         * Maps to COBOL: WS-EDIT-EXPIRY-IS-INVALID (line 250), expiry date validations (lines 509-512).
         * HTTP Status: 400 BAD_REQUEST.
         */
        INVALID_EXPIRATION_DATE,
        
        /**
         * Another transaction modified the account record after this transaction read it.
         * Optimistic locking failure detected during update.
         * Maps to COBOL: DATA-WAS-CHANGED-BEFORE-UPDATE (lines 521-522, 3950, 4143, 4189).
         * HTTP Status: 409 CONFLICT.
         */
        CONCURRENT_UPDATE_CONFLICT,
        
        /**
         * Cannot acquire exclusive lock on account record for update.
         * Another transaction currently holds the lock.
         * Maps to COBOL: COULD-NOT-LOCK-ACCT-FOR-UPDATE (lines 517-518, 3912).
         * HTTP Status: 409 CONFLICT.
         */
        ACCOUNT_LOCKED,
        
        /**
         * Generic update operation failed after successful lock acquisition.
         * Maps to COBOL: LOCKED-BUT-UPDATE-FAILED (lines 523-524, 4079, 4098).
         * HTTP Status: 500 INTERNAL_SERVER_ERROR.
         */
        UPDATE_FAILED,
        
        /**
         * One or more field validation rules failed (dates, amounts, formats).
         * Generic validation failure not covered by more specific reasons.
         * Maps to COBOL: Various FLG-*-NOT-OK conditions throughout validation sections.
         * HTTP Status: 400 BAD_REQUEST.
         */
        VALIDATION_ERROR,
        
        /**
         * Database constraint violation or other database-level error.
         * Maps to COBOL: File operation errors with non-NORMAL RESP codes.
         * HTTP Status: 500 INTERNAL_SERVER_ERROR.
         */
        DATABASE_ERROR,
        
        /**
         * Associated customer record update failed during account update transaction.
         * Maps to COBOL: COULD-NOT-LOCK-CUST-FOR-UPDATE (lines 519-520, 3939),
         * DID-NOT-FIND-CUST-IN-CUSTDAT (lines 501-502, 3769).
         * HTTP Status: 400 BAD_REQUEST or 409 CONFLICT depending on cause.
         */
        CUSTOMER_UPDATE_FAILED
    }
    
    /**
     * Constructs a new AccountUpdateException with account ID and failure reason.
     * 
     * <p>Uses default exception message constructed from the failure reason.
     * Suitable for standard error scenarios where additional context is not needed.
     * 
     * @param accountId the account identifier that failed to update, may be null
     * @param failureReason the specific reason for failure, may be null
     */
    public AccountUpdateException(String accountId, UpdateFailureReason failureReason) {
        super(buildDefaultMessage(accountId, failureReason));
        this.accountId = accountId;
        this.failureReason = failureReason;
    }
    
    /**
     * Constructs a new AccountUpdateException with custom message, account ID, and failure reason.
     * 
     * <p>Allows providing detailed error context beyond the default message.
     * Use this constructor when the failure requires specific diagnostic information.
     * 
     * @param message the detailed error message, may be null
     * @param accountId the account identifier that failed to update, may be null
     * @param failureReason the specific reason for failure, may be null
     */
    public AccountUpdateException(String message, String accountId, UpdateFailureReason failureReason) {
        super(message);
        this.accountId = accountId;
        this.failureReason = failureReason;
    }
    
    /**
     * Constructs a new AccountUpdateException with custom message and underlying cause.
     * 
     * <p>Use this constructor when wrapping lower-level exceptions (e.g., database exceptions)
     * that caused the account update to fail. The failure reason will be null.
     * 
     * @param message the detailed error message, may be null
     * @param cause the underlying exception that caused the failure, may be null
     */
    public AccountUpdateException(String message, Throwable cause) {
        super(message, cause);
        this.accountId = null;
        this.failureReason = null;
    }
    
    /**
     * Constructs a new AccountUpdateException with all available context.
     * 
     * <p>Most comprehensive constructor providing full diagnostic information:
     * custom message, underlying cause, account ID, and specific failure reason.
     * 
     * <p>Use this constructor for complex failure scenarios requiring maximum context
     * for debugging and error reporting.
     * 
     * @param message the detailed error message, may be null
     * @param cause the underlying exception that caused the failure, may be null
     * @param accountId the account identifier that failed to update, may be null
     * @param failureReason the specific reason for failure, may be null
     */
    public AccountUpdateException(String message, Throwable cause, String accountId, UpdateFailureReason failureReason) {
        super(message, cause);
        this.accountId = accountId;
        this.failureReason = failureReason;
    }
    
    /**
     * Returns the account identifier that failed to update.
     * 
     * <p>The account ID is used for:
     * <ul>
     *   <li>Error logging and monitoring dashboards</li>
     *   <li>Client-side error messages with specific account context</li>
     *   <li>Audit trail tracking of failed update attempts</li>
     * </ul>
     * 
     * @return the account ID, or null if not available
     */
    public String getAccountId() {
        return accountId;
    }
    
    /**
     * Returns the specific reason for the account update failure.
     * 
     * <p>The failure reason enables:
     * <ul>
     *   <li>Precise HTTP status code mapping in GlobalExceptionHandler</li>
     *   <li>Client-side conditional error handling</li>
     *   <li>Detailed error analytics and reporting</li>
     * </ul>
     * 
     * @return the failure reason, or null if not categorized
     */
    public UpdateFailureReason getFailureReason() {
        return failureReason;
    }
    
    /**
     * Builds a default error message from account ID and failure reason.
     * 
     * <p>Generates human-readable error messages for standard failure scenarios
     * when a custom message is not provided.
     * 
     * @param accountId the account identifier, may be null
     * @param failureReason the failure reason, may be null
     * @return constructed error message, never null
     */
    private static String buildDefaultMessage(String accountId, UpdateFailureReason failureReason) {
        StringBuilder message = new StringBuilder("Account update failed");
        
        if (accountId != null && !accountId.isEmpty()) {
            message.append(" for account ID: ").append(accountId);
        }
        
        if (failureReason != null) {
            message.append(". Reason: ").append(failureReason.name());
        }
        
        return message.toString();
    }
}
