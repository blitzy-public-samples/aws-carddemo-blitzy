/*
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
package com.aws.carddemo.account.exception;

/**
 * Signals that an account key was not present in the {@code accounts} table.
 *
 * <p>This unchecked exception reproduces the legacy {@code NOTFND} branch of the
 * {@code 9300-GETACCTDATA-BYACCT} paragraph in the CardDemo account-view program
 * {@code COACTVWC} (CICS transaction {@code CAVW}). When the legacy VSAM read of
 * {@code ACCTFILE} returned {@code DFHRESP(NOTFND)}, the program surfaced the
 * text {@code "Account:<id> not found in Acct Master file.Resp:<r> Reas:<r2>"}.
 * The CICS-specific {@code Resp:}/{@code Reas:} response and reason codes carry
 * no meaning over a REST boundary and are intentionally dropped, leaving the
 * migrated, REST-friendly message:</p>
 *
 * <pre>Account: {accountId} not found in Acct Master file.</pre>
 *
 * <p>It is mapped to an HTTP {@code 404 Not Found} response by
 * {@code GlobalExceptionHandler}. As an unchecked ({@link RuntimeException})
 * type it does not force {@code throws} declarations to ripple through the
 * service and controller call chain.</p>
 *
 * <p>Only the account identifier — the 11-digit account key that is already
 * part of the request URI — is embedded in the message. No monetary values,
 * card numbers (PANs), or other sensitive data are ever included, consistent
 * with the "no sensitive data in logs" requirement of the migration.</p>
 */
public class AccountNotFoundException extends RuntimeException {

    /**
     * Serialization version identifier. {@link RuntimeException} is
     * {@link java.io.Serializable}, so an explicit value is declared to keep
     * the serialized form stable across builds.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The account identifier (11-digit, zero-padded account key) that could not
     * be located. Retained so that exception handlers and tests can reference
     * the offending key without having to parse it back out of the message.
     */
    private final String accountId;

    /**
     * Builds the canonical, REST-friendly not-found message for the supplied
     * account identifier. Kept as a single, private source of the literal text
     * so that every constructor produces a byte-for-byte identical message.
     *
     * @param accountId the account key that was not found (may be {@code null})
     * @return the formatted message
     *         {@code "Account: {accountId} not found in Acct Master file."}
     */
    private static String buildMessage(final String accountId) {
        return "Account: " + accountId + " not found in Acct Master file.";
    }

    /**
     * Creates a new exception for the account key that could not be located.
     *
     * @param accountId the account key that was not found; embedded verbatim in
     *                  the exception message and exposed via
     *                  {@link #getAccountId()}
     */
    public AccountNotFoundException(final String accountId) {
        super(buildMessage(accountId));
        this.accountId = accountId;
    }

    /**
     * Creates a new exception for the account key that could not be located,
     * chaining the underlying cause. Provided for completeness so that a lower
     * level failure (for example a data-access error surfaced as a missing row)
     * can be preserved for diagnostics.
     *
     * @param accountId the account key that was not found; embedded verbatim in
     *                  the exception message and exposed via
     *                  {@link #getAccountId()}
     * @param cause     the underlying cause, retained for the exception chain;
     *                  may be {@code null}
     */
    public AccountNotFoundException(final String accountId, final Throwable cause) {
        super(buildMessage(accountId), cause);
        this.accountId = accountId;
    }

    /**
     * Returns the account identifier that could not be located.
     *
     * @return the offending 11-digit account key exactly as supplied to the
     *         constructor
     */
    public String getAccountId() {
        return accountId;
    }
}
