/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.batch.batch;

import com.carddemo.common.domain.Transaction;

import java.math.BigDecimal;

/**
 * :purpose: Immutable carrier conveying the per-row result of the monthly interest
 *  calculation from {@code InterestItemProcessor} to {@code InterestTransactionWriter}.
 *  Each instance bundles the values that flow between the interest computation
 *  (``1300-COMPUTE-INTEREST`` / ``1300-B-WRITE-TX``) and the per-account cycle rollup
 *  (``1050-UPDATE-ACCOUNT``) in the legacy program ``CBACT04C``: the computed monthly
 *  interest, the built interest {@link Transaction} (when one was emitted), and the
 *  owning account id.
 * :output: A read-only value object. The owning account id is ALWAYS present so the
 *  writer can perform the cycle-zeroing account rollup for every account — including
 *  accounts whose category rows all carried a zero disclosure interest rate, for which
 *  no transaction is emitted.
 */
public final class InterestPostingItem {

    /**
     * ``TRANCAT-ACCT-ID`` PIC 9(11) — owning account id of the transaction-category
     * balance row. Always set (never {@code null}); the writer rolls up and zeroes
     * ``ACCT-CURR-CYC-CREDIT`` / ``ACCT-CURR-CYC-DEBIT`` for every account on the
     * control break regardless of whether interest was posted.
     */
    private final Long acctId;

    /**
     * ``WS-MONTHLY-INT`` PIC S9(09)V99 — monthly interest computed for this one
     * category-balance row as ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200``; carries
     * {@link BigDecimal#ZERO} when the disclosure interest rate was zero.
     */
    private final BigDecimal monthlyInterest;

    /**
     * Fully-built interest {@link Transaction} to be persisted (the ``1300-B-WRITE-TX``
     * ``TRAN-RECORD``); {@code null} when the disclosure interest rate was zero, mirroring
     * ``CBACT04C`` which writes no transaction when ``DIS-INT-RATE = 0``.
     */
    private final Transaction transaction;

    /**
     * :purpose: Construct an immutable interest-posting carrier.
     * :param acctId: owning account id (``TRANCAT-ACCT-ID``); must not be {@code null}.
     * :param monthlyInterest: computed monthly interest (``WS-MONTHLY-INT``); must not be
     *  {@code null} ({@link BigDecimal#ZERO} for a zero-rate row).
     * :param transaction: built interest {@link Transaction}, or {@code null} when no
     *  transaction is emitted for a zero-rate row.
     */
    private InterestPostingItem(Long acctId, BigDecimal monthlyInterest, Transaction transaction) {
        // acctId and monthlyInterest are invariants of every carrier: a zero-rate row
        // still carries the account id and a ZERO amount, so a null in either position is
        // a wiring defect and is rejected at construction. The Transaction is intentionally
        // nullable, and both the BigDecimal and the Transaction are stored by reference
        // (no defensive copy), as specified for this carrier.
        if (acctId == null) {
            throw new NullPointerException("acctId must not be null");
        }
        if (monthlyInterest == null) {
            throw new NullPointerException("monthlyInterest must not be null");
        }
        this.acctId = acctId;
        this.monthlyInterest = monthlyInterest;
        this.transaction = transaction;
    }

    /**
     * :purpose: Create a carrier for a category-balance row that produced interest,
     *  bundling the computed amount with the interest transaction to be persisted.
     * :param acctId: owning account id (``TRANCAT-ACCT-ID``); must not be {@code null}.
     * :param monthlyInterest: computed monthly interest (``WS-MONTHLY-INT``); must not be
     *  {@code null}.
     * :param transaction: the built interest {@link Transaction} to be persisted.
     * :returns: an immutable {@code InterestPostingItem} carrying the interest and its
     *  transaction.
     */
    public static InterestPostingItem withInterest(Long acctId,
                                                   BigDecimal monthlyInterest,
                                                   Transaction transaction) {
        return new InterestPostingItem(acctId, monthlyInterest, transaction);
    }

    /**
     * :purpose: Create a carrier for a category-balance row whose disclosure interest rate
     *  was zero: no transaction is emitted, yet the account id is still carried so the
     *  writer performs the cycle-zeroing account rollup (``1050-UPDATE-ACCOUNT``).
     * :param acctId: owning account id (``TRANCAT-ACCT-ID``); must not be {@code null}.
     * :returns: an immutable {@code InterestPostingItem} whose monthly interest is
     *  {@link BigDecimal#ZERO} and whose transaction is {@code null}.
     */
    public static InterestPostingItem zeroInterest(Long acctId) {
        return new InterestPostingItem(acctId, BigDecimal.ZERO, null);
    }

    /**
     * :purpose: Return the owning account id (``TRANCAT-ACCT-ID``).
     * :returns: the non-{@code null} account id.
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * :purpose: Return the computed monthly interest (``WS-MONTHLY-INT``).
     * :returns: the non-{@code null} monthly interest ({@link BigDecimal#ZERO} for a
     *  zero-rate row).
     */
    public BigDecimal getMonthlyInterest() {
        return monthlyInterest;
    }

    /**
     * :purpose: Return the built interest {@link Transaction} to be persisted.
     * :returns: the transaction, or {@code null} when no transaction was emitted
     *  (zero-rate row).
     */
    public Transaction getTransaction() {
        return transaction;
    }

    /**
     * :purpose: Report whether this item carries an interest transaction to persist.
     * :returns: {@code true} when a {@link Transaction} is present; {@code false} for a
     *  zero-rate row.
     */
    public boolean hasTransaction() {
        return transaction != null;
    }

    /**
     * :purpose: Provide a diagnostic string representation of this carrier.
     * :output: A string containing only the account id and monthly interest; the built
     *  transaction is intentionally omitted so that no card number or other PII is ever
     *  written to logs.
     */
    @Override
    public String toString() {
        return "InterestPostingItem{"
                + "acctId=" + acctId
                + ", monthlyInterest=" + monthlyInterest
                + '}';
    }
}
