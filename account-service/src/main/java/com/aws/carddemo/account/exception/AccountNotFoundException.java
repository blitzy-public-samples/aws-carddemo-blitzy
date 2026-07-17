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
 * <p>The message reproduces the legacy {@code COACTVWC} not-found text, which interpolates the
 * requested account identifier: {@code "Account: <id> not found in Acct Master file."}
 * ({@code app/cbl/COACTVWC.cbl:L796-L805}, modernized to drop the CICS {@code Resp:}/{@code Reas:}
 * diagnostics). Restoring the id in the <em>response</em> is behavioral parity (QA finding F-01):
 * the identifier echoed back is the very value the client supplied in the request path, returned
 * only to that same caller, so this is not a disclosure to a third party.</p>
 * <p>AAP &sect;0.6.6 is a <strong>logging</strong> constraint — the full account number must never be
 * written to logs in plaintext (CWE-532). That contract is upheld independently of this message:
 * {@code GlobalExceptionHandler#handleNotFound} maps this exception to a {@code 404} and
 * <strong>does not log</strong> it (only the catch-all {@code 500} handler logs, and it records the
 * sanitized route template — never the raw URI or id). The {@code path} field of the error body also
 * remains the digit-masked route template. Thus the id appears only in the {@code message} of the
 * {@code 404} body and never in any log line. No monetary value or card number ever enters this type.</p>
 */
public class AccountNotFoundException extends RuntimeException {

    /**
     * Serialization version identifier. {@link RuntimeException} is
     * {@link java.io.Serializable}, so an explicit value is declared to keep the
     * serialized form stable across builds.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Message prefix preceding the account identifier. Kept as constants so the wording stays
     * stable and is asserted against a single source in the test suite.
     */
    private static final String MESSAGE_PREFIX = "Account: ";

    /** Message suffix following the account identifier (modernized, without CICS Resp/Reas codes). */
    private static final String MESSAGE_SUFFIX = " not found in Acct Master file.";

    /**
     * Creates a new not-found exception whose message names the requested account, reproducing the
     * legacy {@code COACTVWC} {@code NOTFND} text {@code "Account: <id> not found in Acct Master file."}
     * ({@code app/cbl/COACTVWC.cbl:L796-L805}).
     *
     * <p>The {@code accountId} passed here is the same 11-digit key the client supplied in the request
     * path; it is embedded in the {@code 404} response message (behavioral parity, QA finding F-01) but
     * never written to any log (the not-found handler does not log; AAP &sect;0.6.6 log-hygiene is
     * preserved). Throw this only for a confirmed absent record; a data-access or infrastructure failure
     * is a different condition and must not be represented as a missing row.</p>
     *
     * @param accountId the zero-padded 11-digit account key that was not found (as supplied on the path)
     */
    public AccountNotFoundException(final String accountId) {
        super(MESSAGE_PREFIX + accountId + MESSAGE_SUFFIX);
    }
}
