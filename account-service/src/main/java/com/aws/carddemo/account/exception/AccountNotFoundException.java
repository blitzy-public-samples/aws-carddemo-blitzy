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
 * Signals that a requested account key was not present in the {@code accounts} table.
 *
 * <p>This unchecked exception reproduces the legacy {@code NOTFND} branch of the
 * {@code 9300-GETACCTDATA-BYACCT} paragraph in the CardDemo account-view program
 * {@code COACTVWC} (CICS transaction {@code CAVW}). When the legacy VSAM read of
 * {@code ACCTFILE} returned {@code DFHRESP(NOTFND)}, the program surfaced a 3270-screen
 * message. That screen contract is retired; the migrated behavior preserves the
 * <em>outcome</em> only — {@code GlobalExceptionHandler} maps this exception to an HTTP
 * {@code 404 Not Found} response. As an unchecked ({@link RuntimeException}) type it does
 * not force {@code throws} declarations to ripple through the service and controller call
 * chain.</p>
 *
 * <h2>Sensitive-data handling (AAP &sect;0.6.6)</h2>
 * <p>The account identifier is treated as sensitive "full account number" data that must
 * never be written to logs in plaintext. Because an exception's message is routinely
 * captured by application, framework, and access logs (CWE-532) and echoed in error
 * responses (CWE-209), this exception deliberately carries a <strong>generic, id-free
 * message</strong> and stores <strong>no account identifier</strong>. Callers that need to
 * report which account was requested must do so from the already-known request path
 * variable, applying masking as appropriate — never by reading it back off this exception.
 * No account id, monetary value, or card number ever enters this type.</p>
 */
public class AccountNotFoundException extends RuntimeException {

    /**
     * Serialization version identifier. {@link RuntimeException} is
     * {@link java.io.Serializable}, so an explicit value is declared to keep the
     * serialized form stable across builds.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Fixed, id-free not-found message. Kept as a single constant so the wording stays
     * stable and is guaranteed never to interpolate an account identifier.
     */
    private static final String MESSAGE = "Account not found in Acct Master file.";

    /**
     * Creates a new not-found exception with a generic, id-free message.
     *
     * <p>No account identifier is accepted or retained: the 404 outcome is independent of
     * the specific key, and omitting the id guarantees it can never leak into logs or error
     * payloads through this exception (AAP &sect;0.6.6). Throw this only for a confirmed
     * absent record; a data-access or infrastructure failure is a different condition and
     * must not be represented as a missing row.</p>
     */
    public AccountNotFoundException() {
        super(MESSAGE);
    }
}
