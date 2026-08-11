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
package com.carddemo.transaction.batch;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.DailyTransaction;

/**
 * :purpose: Immutable carrier flowing from the transaction-posting validation
 *  ``ItemProcessor`` to the posting and reject ``ItemWriter``s. Each instance holds
 *  one daily-transaction record together with the verdict of the legacy
 *  ``CBTRN02C`` ``1500-VALIDATE-TRAN`` step: either "valid + resolved account" or
 *  "rejected + reason code + description". The classifier {@link #isRejected()}
 *  reproduces the COBOL ``IF WS-VALIDATION-FAIL-REASON = 0`` post-validation branch
 *  that routes each record to ``2000-POST-TRANSACTION`` or ``2500-WRITE-REJECT-REC``.
 * :output: A read-only value object exposing the carried daily-transaction record,
 *  the reject reason code (``0`` when valid) with its description, the resolved
 *  account, and the cross-referenced account id required by the posting writer.
 */
public final class PostingItem {

    /**
     * ``DALYTRAN-RECORD`` — the daily-transaction record read from the DALYTRAN
     * feed. Always present (never {@code null}).
     */
    private final DailyTransaction dailyTransaction;

    /**
     * ``WS-VALIDATION-FAIL-REASON`` PIC 9(04) — validation reason code: {@code 0}
     * for a valid record, otherwise the reject reason code (100, 101, 102 or 103).
     */
    private final int rejectCode;

    /**
     * ``WS-VALIDATION-FAIL-REASON-DESC`` PIC X(76) — reject reason description;
     * {@code null} when the record is valid.
     */
    private final String rejectDescription;

    /**
     * ``ACCOUNT-RECORD`` resolved during ``1500-B-LOOKUP-ACCT``. Non-{@code null}
     * for valid items and for over-limit (102) and expiration (103) rejects (the
     * account was found); {@code null} for card-not-found (100) and
     * account-not-found (101) rejects.
     */
    private final Account account;

    /**
     * ``XREF-ACCT-ID`` — account id obtained from the card cross-reference, required
     * as the ``2700-UPDATE-TCATBAL`` composite-key account component and the ``2800``
     * account key; {@code null} only for card-not-found (100) rejects.
     */
    private final Long xrefAcctId;

    /**
     * :purpose: Construct an immutable posting-pipeline carrier from a fully
     *  determined validation verdict.
     * :param dailyTransaction: the daily-transaction record; must not be {@code null}.
     * :param rejectCode: the validation reason code ({@code 0} when valid).
     * :param rejectDescription: the reject reason description, or {@code null} when valid.
     * :param account: the resolved account, or {@code null} when the ordered lookup
     *  did not reach or resolve the account.
     * :param xrefAcctId: the cross-referenced account id, or {@code null} when the
     *  card cross-reference was not found.
     */
    private PostingItem(DailyTransaction dailyTransaction,
                        int rejectCode,
                        String rejectDescription,
                        Account account,
                        Long xrefAcctId) {
        // The daily-transaction record is an invariant of every carrier — a valid or a
        // rejected item always wraps the record it was judged from — so a null here is a
        // wiring defect and is rejected at construction. The account and cross-referenced
        // account id are intentionally nullable (they depend on how far the ordered lookup
        // progressed), and every reference field is stored as-is with no defensive copy.
        if (dailyTransaction == null) {
            throw new NullPointerException("dailyTransaction must not be null");
        }
        this.dailyTransaction = dailyTransaction;
        this.rejectCode = rejectCode;
        this.rejectDescription = rejectDescription;
        this.account = account;
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * :purpose: Create a carrier for a record that passed ``1500-VALIDATE-TRAN``
     *  (reason {@code 0}) and will be routed to ``2000-POST-TRANSACTION``.
     * :param dailyTransaction: the validated daily-transaction record.
     * :param account: the resolved account.
     * :param xrefAcctId: the cross-referenced account id.
     * :returns: an immutable valid {@code PostingItem} with reject code {@code 0} and
     *  no reject description.
     */
    public static PostingItem valid(DailyTransaction dailyTransaction, Account account, Long xrefAcctId) {
        return new PostingItem(dailyTransaction, 0, null, account, xrefAcctId);
    }

    /**
     * :purpose: Create a carrier for a record that failed ``1500-VALIDATE-TRAN`` and
     *  will be routed to ``2500-WRITE-REJECT-REC``.
     * :param dailyTransaction: the rejected daily-transaction record.
     * :param rejectCode: the non-zero reject reason code (100, 101, 102 or 103).
     * :param rejectDescription: the reject reason description.
     * :param account: the resolved account, or {@code null} when the account was not
     *  reached or found.
     * :param xrefAcctId: the cross-referenced account id, or {@code null} for a
     *  card-not-found reject.
     * :returns: an immutable rejected {@code PostingItem} carrying the reject code and
     *  description.
     */
    public static PostingItem rejected(DailyTransaction dailyTransaction,
                                       int rejectCode,
                                       String rejectDescription,
                                       Account account,
                                       Long xrefAcctId) {
        return new PostingItem(dailyTransaction, rejectCode, rejectDescription, account, xrefAcctId);
    }

    /**
     * :purpose: Return the carried daily-transaction record read from the DALYTRAN feed.
     * :returns: the non-{@code null} daily-transaction record.
     */
    public DailyTransaction getDailyTransaction() {
        return dailyTransaction;
    }

    /**
     * :purpose: Return the validation reason code (``WS-VALIDATION-FAIL-REASON``).
     * :returns: {@code 0} when valid, otherwise the reject reason code.
     */
    public int getRejectCode() {
        return rejectCode;
    }

    /**
     * :purpose: Return the reject reason description (``WS-VALIDATION-FAIL-REASON-DESC``).
     * :returns: the reject description, or {@code null} when the record is valid.
     */
    public String getRejectDescription() {
        return rejectDescription;
    }

    /**
     * :purpose: Return the account resolved during ``1500-B-LOOKUP-ACCT``.
     * :returns: the resolved account, or {@code null} when the account was not reached
     *  or found.
     */
    public Account getAccount() {
        return account;
    }

    /**
     * :purpose: Return the cross-referenced account id (``XREF-ACCT-ID``).
     * :returns: the cross-referenced account id, or {@code null} for a card-not-found
     *  reject.
     */
    public Long getXrefAcctId() {
        return xrefAcctId;
    }

    /**
     * :purpose: Report whether this item was rejected, reproducing the COBOL
     *  ``IF WS-VALIDATION-FAIL-REASON = 0`` post-validation branch used to route the
     *  item to the reject writer versus the posting writer.
     * :returns: {@code true} when the reject code is non-zero; {@code false} when valid.
     */
    public boolean isRejected() {
        return rejectCode != 0;
    }
}
