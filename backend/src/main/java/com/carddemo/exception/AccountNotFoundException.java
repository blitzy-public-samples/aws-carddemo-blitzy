/*****************************************************************
 * Program:     AccountNotFoundException.java
 * Layer:       Exception handling
 * Function:    Custom runtime exception for account not found scenarios
 *              Maps to COBOL file-status 23 and CICS DFHRESP(NOTFND)
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

/**
 * Custom runtime exception thrown when account lookup operations fail to find
 * the requested account in the database.
 * <p>
 * This exception represents the Java/Spring Boot equivalent of COBOL/CICS
 * error handling scenarios where EXEC CICS READ ACCTDAT operations return
 * DFHRESP(NOTFND) response code (value 13) or COBOL file-status 23 (record not found).
 * </p>
 * <p>
 * <b>COBOL Error Mapping:</b>
 * <ul>
 *   <li>CICS Response: DFHRESP(NOTFND) = 13</li>
 *   <li>File Status: '23' (record not found)</li>
 *   <li>Source Programs: COACTVWC.cbl, COACTUPC.cbl, COBIL00C.cbl</li>
 * </ul>
 * </p>
 * <p>
 * <b>Example COBOL Error Pattern:</b>
 * <pre>
 * EXEC CICS READ
 *      DATASET   (LIT-ACCTFILENAME)
 *      RIDFLD    (WS-CARD-RID-ACCT-ID-X)
 *      INTO      (ACCOUNT-RECORD)
 *      RESP      (WS-RESP-CD)
 *      RESP2     (WS-REAS-CD)
 * END-EXEC
 * 
 * EVALUATE WS-RESP-CD
 *     WHEN DFHRESP(NOTFND)
 *        SET INPUT-ERROR TO TRUE
 *        SET FLG-ACCTFILTER-NOT-OK TO TRUE
 *        STRING 'Account:' WS-CARD-RID-ACCT-ID-X
 *               ' not found in Acct Master file.Resp:' ERROR-RESP
 *               INTO WS-RETURN-MSG
 *        END-STRING
 * END-EVALUATE
 * </pre>
 * </p>
 * <p>
 * This exception carries contextual information about the failed lookup including
 * the account identifier used in the search and the type of identifier (account number,
 * account ID, or customer ID).
 * </p>
 * <p>
 * <b>Usage in Services:</b>
 * <ul>
 *   <li>AccountViewService.getAccountDetails() - when AccountRepository.findById() returns empty</li>
 *   <li>AccountUpdateService.updateAccount() - when attempting to update non-existent account</li>
 *   <li>BillPaymentService.processBillPayment() - when source or destination account not found</li>
 *   <li>CardListService.getCardsByAccount() - when listing cards for non-existent account</li>
 *   <li>TransactionCreationService.postTransaction() - when posting to invalid account</li>
 * </ul>
 * </p>
 * <p>
 * <b>REST API Error Response:</b>
 * <ul>
 *   <li>HTTP Status: 404 NOT_FOUND</li>
 *   <li>Error Code: "ACCOUNT_NOT_FOUND"</li>
 *   <li>Response Body Includes: accountIdentifier, identifierType, message</li>
 * </ul>
 * </p>
 *
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see com.carddemo.exception.GlobalExceptionHandler
 */
public class AccountNotFoundException extends RuntimeException {

    /**
     * Enumeration defining the types of identifiers that can be used when
     * looking up accounts in the database.
     * <p>
     * This enum corresponds to the different COBOL RIDFLD (Record Identification Field)
     * types used in VSAM KSDS file access operations.
     * </p>
     * <p>
     * <b>COBOL Mapping:</b>
     * <ul>
     *   <li>ACCOUNT_NUMBER - Maps to WS-CARD-RID-ACCT-ID (PIC 9(11))</li>
     *   <li>ACCOUNT_ID - Maps to ACCT-ID primary key field</li>
     *   <li>CUSTOMER_ID - Maps to WS-CARD-RID-CUST-ID (PIC 9(09)) for customer-based lookups</li>
     * </ul>
     * </p>
     */
    public enum IdentifierType {
        /**
         * Account number identifier (11-digit numeric account number).
         * COBOL equivalent: PIC 9(11) COMP
         */
        ACCOUNT_NUMBER,

        /**
         * Account ID identifier (internal database primary key).
         * COBOL equivalent: PIC 9(11) COMP
         */
        ACCOUNT_ID,

        /**
         * Customer ID identifier (used for customer-based account lookups).
         * COBOL equivalent: PIC 9(09) COMP
         */
        CUSTOMER_ID
    }

    /**
     * The account identifier that was used in the failed lookup operation.
     * <p>
     * This field stores the actual value (account number, account ID, or customer ID)
     * that was searched for but not found in the database.
     * </p>
     * <p>
     * <b>COBOL Equivalent:</b> WS-CARD-RID-ACCT-ID-X or WS-CARD-RID-CUST-ID-X
     * </p>
     */
    private final String accountIdentifier;

    /**
     * The type of identifier that was used in the failed lookup operation.
     * <p>
     * This field indicates whether the search was performed using an account number,
     * account ID, or customer ID, providing context for error handling and logging.
     * </p>
     */
    private final IdentifierType identifierType;

    /**
     * Constructs a new AccountNotFoundException with the specified account identifier.
     * <p>
     * This constructor creates an exception with a default message format and
     * defaults the identifier type to ACCOUNT_NUMBER.
     * </p>
     * <p>
     * <b>Usage Example:</b>
     * <pre>
     * if (account.isEmpty()) {
     *     throw new AccountNotFoundException("12345678901");
     * }
     * </pre>
     * </p>
     *
     * @param accountIdentifier The account identifier that was not found
     *                          (must not be null or empty)
     */
    public AccountNotFoundException(String accountIdentifier) {
        super(String.format("Account with identifier '%s' not found", accountIdentifier));
        this.accountIdentifier = accountIdentifier;
        this.identifierType = IdentifierType.ACCOUNT_NUMBER;
    }

    /**
     * Constructs a new AccountNotFoundException with the specified account identifier
     * and identifier type.
     * <p>
     * This constructor allows specifying the exact type of identifier used in the
     * lookup operation, providing more precise error context.
     * </p>
     * <p>
     * <b>Usage Example:</b>
     * <pre>
     * if (account.isEmpty()) {
     *     throw new AccountNotFoundException("12345678901", IdentifierType.ACCOUNT_ID);
     * }
     * </pre>
     * </p>
     *
     * @param accountIdentifier The account identifier that was not found
     *                          (must not be null or empty)
     * @param identifierType    The type of identifier used in the lookup
     *                          (must not be null)
     */
    public AccountNotFoundException(String accountIdentifier, IdentifierType identifierType) {
        super(String.format("Account with %s '%s' not found",
                identifierType.name().toLowerCase().replace('_', ' '),
                accountIdentifier));
        this.accountIdentifier = accountIdentifier;
        this.identifierType = identifierType;
    }

    /**
     * Constructs a new AccountNotFoundException with a custom message and
     * account identifier.
     * <p>
     * This constructor allows for a fully customized error message while still
     * capturing the account identifier. The identifier type defaults to ACCOUNT_NUMBER.
     * </p>
     * <p>
     * <b>COBOL Error Message Pattern:</b> Matches the STRING operation that builds
     * error messages like "Account: 12345678901 not found in Acct Master file"
     * </p>
     * <p>
     * <b>Usage Example:</b>
     * <pre>
     * throw new AccountNotFoundException(
     *     "Account 12345678901 not found in Acct Master file. Resp: 13",
     *     "12345678901"
     * );
     * </pre>
     * </p>
     *
     * @param message           The detail message explaining the exception
     *                          (must not be null or empty)
     * @param accountIdentifier The account identifier that was not found
     *                          (must not be null or empty)
     */
    public AccountNotFoundException(String message, String accountIdentifier) {
        super(message);
        this.accountIdentifier = accountIdentifier;
        this.identifierType = IdentifierType.ACCOUNT_NUMBER;
    }

    /**
     * Constructs a new AccountNotFoundException with a custom message,
     * account identifier, and identifier type.
     * <p>
     * This constructor provides the most flexibility, allowing full customization
     * of the error message while capturing both the identifier value and type.
     * </p>
     * <p>
     * <b>Usage Example:</b>
     * <pre>
     * throw new AccountNotFoundException(
     *     "Account with customer ID 123456789 not found in customer master. Resp: 13",
     *     "123456789",
     *     IdentifierType.CUSTOMER_ID
     * );
     * </pre>
     * </p>
     *
     * @param message           The detail message explaining the exception
     *                          (must not be null or empty)
     * @param accountIdentifier The account identifier that was not found
     *                          (must not be null or empty)
     * @param identifierType    The type of identifier used in the lookup
     *                          (must not be null)
     */
    public AccountNotFoundException(String message, String accountIdentifier, IdentifierType identifierType) {
        super(message);
        this.accountIdentifier = accountIdentifier;
        this.identifierType = identifierType;
    }

    /**
     * Constructs a new AccountNotFoundException with a custom message and cause.
     * <p>
     * This constructor is useful for wrapping lower-level exceptions (such as
     * database access exceptions) while providing a domain-specific error message.
     * </p>
     * <p>
     * When using this constructor, the accountIdentifier field will be null and
     * identifierType will default to ACCOUNT_NUMBER. This constructor is primarily
     * used for chaining exceptions where the identifier context is already captured
     * in the message or cause.
     * </p>
     * <p>
     * <b>Usage Example:</b>
     * <pre>
     * try {
     *     account = accountRepository.findById(accountId);
     * } catch (DataAccessException e) {
     *     throw new AccountNotFoundException(
     *         "Database error while searching for account: " + e.getMessage(),
     *         e
     *     );
     * }
     * </pre>
     * </p>
     *
     * @param message The detail message explaining the exception
     *                (must not be null or empty)
     * @param cause   The underlying cause of this exception
     *                (typically a lower-level exception)
     */
    public AccountNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.accountIdentifier = null;
        this.identifierType = IdentifierType.ACCOUNT_NUMBER;
    }

    /**
     * Returns the account identifier that was used in the failed lookup operation.
     * <p>
     * This method provides access to the specific identifier value (account number,
     * account ID, or customer ID) that was searched for but not found in the database.
     * </p>
     * <p>
     * <b>COBOL Equivalent:</b> This corresponds to the WS-CARD-RID-ACCT-ID-X or
     * WS-CARD-RID-CUST-ID-X values used in the RIDFLD parameter of EXEC CICS READ
     * operations.
     * </p>
     * <p>
     * The identifier is used by the GlobalExceptionHandler to construct detailed
     * error responses for REST API clients, enabling them to understand exactly
     * which account was not found.
     * </p>
     *
     * @return The account identifier that was not found, or null if constructed
     *         using the message-and-cause constructor
     */
    public String getAccountIdentifier() {
        return accountIdentifier;
    }

    /**
     * Returns the type of identifier that was used in the failed lookup operation.
     * <p>
     * This method provides context about whether the search was performed using
     * an account number, account ID, or customer ID. This information is valuable
     * for error handling, logging, and constructing appropriate error responses.
     * </p>
     * <p>
     * <b>Usage in Error Handling:</b>
     * <ul>
     *   <li>ACCOUNT_NUMBER - Indicates search by 11-digit account number</li>
     *   <li>ACCOUNT_ID - Indicates search by internal database primary key</li>
     *   <li>CUSTOMER_ID - Indicates search by 9-digit customer ID</li>
     * </ul>
     * </p>
     * <p>
     * The identifier type is included in REST API error responses to help clients
     * understand which lookup criteria failed and potentially retry with different
     * search parameters.
     * </p>
     *
     * @return The type of identifier used in the lookup operation, never null
     */
    public IdentifierType getIdentifierType() {
        return identifierType;
    }

    /**
     * Returns the detail message string of this exception.
     * <p>
     * This method is inherited from RuntimeException but documented here for
     * completeness. It returns the message passed to the constructor or the
     * default formatted message.
     * </p>
     * <p>
     * <b>COBOL Error Message Pattern:</b>
     * <pre>
     * "Account: 12345678901 not found in Acct Master file. Resp: 13"
     * "CustId: 123456789 not found in customer master. Resp: 13"
     * "Account: 12345678901 not found in Cross ref file. Resp: 13"
     * </pre>
     * </p>
     *
     * @return The detail message string of this exception instance
     */
    @Override
    public String getMessage() {
        return super.getMessage();
    }
}
