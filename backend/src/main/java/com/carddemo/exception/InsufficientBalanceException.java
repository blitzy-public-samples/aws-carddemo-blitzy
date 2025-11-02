/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.exception;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Custom runtime exception thrown when a transaction or payment operation
 * cannot be completed due to insufficient available credit or balance.
 * 
 * <p>This exception maps to COBOL balance validation logic patterns found in:
 * <ul>
 *   <li><b>COBIL00C.cbl</b>: Bill payment processing where payment amount validation
 *       checks prevent processing when account balance is insufficient</li>
 *   <li><b>COTRN02C.cbl</b>: Transaction creation where credit limit validation
 *       prevents transactions that would cause balance to exceed credit limit</li>
 * </ul>
 * 
 * <p>COBOL Pattern Mapping:
 * <pre>
 * COBOL:
 *   IF ACCT-CURR-BAL + TRAN-AMT &gt; ACCT-CREDIT-LIMIT
 *      MOVE 'Insufficient credit available' TO WS-MESSAGE
 *      MOVE 'Y' TO WS-ERR-FLG
 *   END-IF
 * 
 * Java Equivalent:
 *   if (currentBalance.add(transactionAmount).compareTo(creditLimit) &gt; 0) {
 *       throw new InsufficientBalanceException(
 *           "Insufficient credit available",
 *           transactionAmount,
 *           availableCredit,
 *           accountId
 *       );
 *   }
 * </pre>
 * 
 * <p>This exception extends {@link RuntimeException} to enable:
 * <ul>
 *   <li>Unchecked exception semantics (no forced exception declaration)</li>
 *   <li>Automatic transaction rollback with Spring's {@code @Transactional} annotation</li>
 *   <li>Consistent with Spring's exception handling conventions</li>
 * </ul>
 * 
 * <p>The exception carries contextual information about the failed operation:
 * <ul>
 *   <li>{@code requestedAmount}: The transaction or payment amount requested</li>
 *   <li>{@code availableCredit}: The available credit/balance at the time of validation</li>
 *   <li>{@code accountId}: The account identifier for which the validation failed</li>
 * </ul>
 * 
 * <p>Usage in Services:
 * <ul>
 *   <li><b>BillPaymentService</b>: Thrown when payment amount exceeds available balance</li>
 *   <li><b>TransactionCreationService</b>: Thrown when transaction would exceed credit limit</li>
 *   <li><b>AccountUpdateService</b>: Thrown when credit limit reduction would violate balance constraints</li>
 * </ul>
 * 
 * <p>Error Response Integration:
 * <br>This exception is caught by {@code GlobalExceptionHandler} and transformed into
 * a standardized REST API error response:
 * <pre>
 * HTTP Status: 400 BAD_REQUEST or 422 UNPROCESSABLE_ENTITY
 * Response Body:
 * {
 *   "error": "INSUFFICIENT_BALANCE",
 *   "message": "Insufficient credit available for transaction",
 *   "requestedAmount": 1500.00,
 *   "availableCredit": 1000.00,
 *   "accountId": 123456789,
 *   "timestamp": "2024-01-15T10:30:00Z"
 * }
 * </pre>
 * 
 * <p>Thread Safety: This class is immutable and thread-safe. All fields are
 * final and defensive copies are not required for immutable types like
 * {@code BigDecimal}, {@code String}, and {@code Long}.
 * 
 * @see java.lang.RuntimeException
 * @see java.math.BigDecimal
 * @since 1.0
 * @version 1.0
 */
public class InsufficientBalanceException extends RuntimeException implements Serializable {

    /**
     * Serial version UID for serialization compatibility.
     * This enables the exception to be serialized for distributed systems,
     * logging frameworks, and remote exception handling.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The requested transaction or payment amount that caused the validation failure.
     * This amount, when added to the current balance, would exceed the credit limit
     * or represents a payment that exceeds the available balance.
     * 
     * <p>Maps to COBOL {@code TRAN-AMT} field (PIC S9(9)V99 COMP-3) which requires
     * BigDecimal for exact decimal precision preservation without floating-point errors.
     * 
     * <p>May be null if the exception is thrown without contextual amount information.
     */
    private final BigDecimal requestedAmount;

    /**
     * The available credit or balance at the time of validation failure.
     * This represents the maximum amount that could be processed without
     * exceeding credit limits or causing overdraft conditions.
     * 
     * <p>Calculated as: {@code creditLimit - currentBalance} for credit cards,
     * or simply {@code currentBalance} for debit/payment scenarios.
     * 
     * <p>Maps to COBOL balance validation logic:
     * {@code ACCT-CREDIT-LIMIT - ACCT-CURR-BAL} (both PIC S9(9)V99 COMP-3).
     * 
     * <p>May be null if the exception is thrown without contextual credit information.
     */
    private final BigDecimal availableCredit;

    /**
     * The account identifier for which the balance validation failed.
     * This enables clients to display account-specific error messages
     * and perform account-specific error handling or retry logic.
     * 
     * <p>Maps to COBOL {@code ACCT-ID} field (PIC 9(11)).
     * 
     * <p>May be null if the exception is thrown without account context.
     */
    private final Long accountId;

    /**
     * Constructs a new InsufficientBalanceException with the specified detail message.
     * This constructor is used when contextual information about the validation
     * failure is not available or not relevant.
     * 
     * <p>All contextual fields ({@code requestedAmount}, {@code availableCredit},
     * {@code accountId}) will be null when using this constructor.
     * 
     * @param message the detail message explaining why the balance validation failed;
     *                saved for later retrieval by the {@link #getMessage()} method
     */
    public InsufficientBalanceException(String message) {
        super(message);
        this.requestedAmount = null;
        this.availableCredit = null;
        this.accountId = null;
    }

    /**
     * Constructs a new InsufficientBalanceException with detailed contextual information
     * about the validation failure. This is the preferred constructor for production use
     * as it enables rich error responses and detailed logging.
     * 
     * <p>Example Usage:
     * <pre>
     * BigDecimal requestedAmount = new BigDecimal("1500.00");
     * BigDecimal availableCredit = new BigDecimal("1000.00");
     * Long accountId = 123456789L;
     * 
     * throw new InsufficientBalanceException(
     *     "Transaction amount exceeds available credit limit",
     *     requestedAmount,
     *     availableCredit,
     *     accountId
     * );
     * </pre>
     * 
     * @param message the detail message explaining the validation failure
     * @param requestedAmount the transaction or payment amount that was requested;
     *                        should use proper scale (e.g., 2 decimal places for currency)
     * @param availableCredit the available credit or balance at validation time;
     *                        should use same scale as requestedAmount
     * @param accountId the account identifier for the failed validation;
     *                  may be null if account context is not available
     */
    public InsufficientBalanceException(
            String message,
            BigDecimal requestedAmount,
            BigDecimal availableCredit,
            Long accountId) {
        super(message);
        this.requestedAmount = requestedAmount;
        this.availableCredit = availableCredit;
        this.accountId = accountId;
    }

    /**
     * Constructs a new InsufficientBalanceException with the specified detail message
     * and cause. This constructor is used when the balance validation failure is a
     * consequence of another exception (e.g., database access error during balance check).
     * 
     * <p>The cause is saved for later retrieval by the {@link #getCause()} method.
     * A null cause is permitted and indicates that the cause is nonexistent or unknown.
     * 
     * <p>All contextual fields will be null when using this constructor.
     * 
     * @param message the detail message explaining the validation failure
     * @param cause the underlying cause of this exception; may be null
     */
    public InsufficientBalanceException(String message, Throwable cause) {
        super(message, cause);
        this.requestedAmount = null;
        this.availableCredit = null;
        this.accountId = null;
    }

    /**
     * Returns the requested transaction or payment amount that caused the validation failure.
     * 
     * <p>This value represents the amount that, when combined with the current balance,
     * would exceed the credit limit or represents a payment exceeding available balance.
     * 
     * <p>The returned BigDecimal maintains the exact precision from COBOL COMP-3 fields,
     * typically with scale of 2 for currency amounts (e.g., 1500.00).
     * 
     * @return the requested amount, or null if not provided during construction
     */
    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    /**
     * Returns the available credit or balance at the time of validation failure.
     * 
     * <p>For credit accounts, this represents: {@code creditLimit - currentBalance}
     * <br>For payment operations, this represents: {@code currentBalance}
     * 
     * <p>This value indicates the maximum amount that could have been processed
     * without triggering the insufficient balance condition.
     * 
     * @return the available credit, or null if not provided during construction
     */
    public BigDecimal getAvailableCredit() {
        return availableCredit;
    }

    /**
     * Returns the account identifier for which the balance validation failed.
     * 
     * <p>This identifier corresponds to the COBOL {@code ACCT-ID} field and
     * can be used to:
     * <ul>
     *   <li>Display account-specific error messages to users</li>
     *   <li>Log validation failures for audit and monitoring purposes</li>
     *   <li>Implement account-specific retry or fallback logic</li>
     *   <li>Track patterns of insufficient balance across accounts</li>
     * </ul>
     * 
     * @return the account ID, or null if not provided during construction
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Returns a detailed string representation of this exception including
     * all contextual information. This is useful for logging and debugging.
     * 
     * <p>Example output:
     * <pre>
     * InsufficientBalanceException: Transaction amount exceeds available credit limit
     *   Account ID: 123456789
     *   Requested Amount: 1500.00
     *   Available Credit: 1000.00
     * </pre>
     * 
     * @return a string representation including message and all contextual data
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName())
          .append(": ")
          .append(getMessage());
        
        if (accountId != null) {
            sb.append("\n  Account ID: ").append(accountId);
        }
        if (requestedAmount != null) {
            sb.append("\n  Requested Amount: ").append(requestedAmount);
        }
        if (availableCredit != null) {
            sb.append("\n  Available Credit: ").append(availableCredit);
        }
        
        return sb.toString();
    }
}
