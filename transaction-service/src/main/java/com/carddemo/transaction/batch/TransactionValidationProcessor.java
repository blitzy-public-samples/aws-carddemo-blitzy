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
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.DailyTransaction;
import com.carddemo.common.exception.TransactionRejectException;
import com.carddemo.transaction.repository.AccountRepository;
import com.carddemo.transaction.repository.CardXrefRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Spring Batch validation processor for the transaction-posting job.
 *
 * :purpose: Validate a daily-transaction record via the ordered
 *     card-cross-reference then account lookup and the recomputed
 *     cycle-balance/expiration checks, producing a valid or rejected
 *     {@link PostingItem} that mirrors the legacy ``CBTRN02C``
 *     ``1500-VALIDATE-TRAN`` paragraph (``1500-A-LOOKUP-XREF`` +
 *     ``1500-B-LOOKUP-ACCT``). The lookups are read-only; this processor does
 *     not post and does not write.
 * :output: For every input record exactly one {@link PostingItem} (never
 *     {@code null}) carrying either reason code {@code 0} (valid) or one of the
 *     reject codes 100, 101, 102 or 103 with its exact legacy description.
 */
@Component
public class TransactionValidationProcessor implements ItemProcessor<DailyTransaction, PostingItem> {

    /**
     * ``XREF-FILE`` access — resolves the card cross-reference keyed by card
     * number during ``1500-A-LOOKUP-XREF``.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * ``ACCOUNT-FILE`` access — resolves the account keyed by the
     * cross-referenced account id during ``1500-B-LOOKUP-ACCT``.
     */
    private final AccountRepository accountRepository;

    /**
     * :purpose: Wire the read-only cross-reference and account repositories used
     *     by the ordered validation lookups.
     * :param cardXrefRepository: repository backing the ``1500-A-LOOKUP-XREF`` read.
     * :param accountRepository: repository backing the ``1500-B-LOOKUP-ACCT`` read.
     */
    public TransactionValidationProcessor(CardXrefRepository cardXrefRepository,
                                          AccountRepository accountRepository) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * :purpose: Reproduce ``CBTRN02C`` ``1500-VALIDATE-TRAN`` for a single
     *     daily-transaction record: look up the card cross-reference first and,
     *     only when it resolves, look up the account and apply the sequential
     *     cycle-balance (over-limit) and expiration checks.
     * :param item: the daily-transaction record read from the ``DALYTRAN`` feed.
     * :returns: a {@link PostingItem} (never {@code null}) carrying reason code
     *     {@code 0} when valid, or reject code 100, 101, 102 or 103 with its
     *     description when rejected.
     * :raises com.carddemo.common.exception.CardDemoException: when the record is not
     *     a usable fixed-width ``DALYTRAN`` record, so the fault is reported against
     *     the named record and field rather than as a raw dereference failure inside
     *     the cycle-balance or expiration check.
     */
    @Override
    public PostingItem process(DailyTransaction item) {
        // A fixed-width DALYTRAN record always carries the fields the checks below
        // dereference; a staged row that does not is a feed fault, not a business
        // rejection, and is reported as such before any lookup is issued.
        DailyTransactionFeedValidator.requireUsableRecord(item);

        // 1500-A-LOOKUP-XREF: READ XREF-FILE by DALYTRAN-CARD-NUM. INVALID KEY -> 100.
        Optional<CardXref> xref = cardXrefRepository.findByXrefCardNum(item.getDalytranCardNum());
        if (xref.isEmpty()) {
            // Short-circuit exactly as 1500-VALIDATE-TRAN skips 1500-B-LOOKUP-ACCT
            // when WS-VALIDATION-FAIL-REASON is already non-zero: the account is
            // never queried once the cross-reference is missing.
            return PostingItem.rejected(item,
                    TransactionRejectException.INVALID_CARD_NUMBER,
                    TransactionRejectException.MSG_INVALID_CARD_NUMBER,
                    null,
                    null);
        }
        Long xrefAcctId = xref.get().getXrefAcctId();

        // 1500-B-LOOKUP-ACCT: READ ACCOUNT-FILE by XREF-ACCT-ID. INVALID KEY -> 101.
        Optional<Account> acctOpt = accountRepository.findById(xrefAcctId);
        if (acctOpt.isEmpty()) {
            return PostingItem.rejected(item,
                    TransactionRejectException.ACCOUNT_NOT_FOUND,
                    TransactionRejectException.MSG_ACCOUNT_NOT_FOUND,
                    null,
                    xrefAcctId);
        }
        Account account = acctOpt.get();

        int rejectCode = 0;
        String rejectDescription = null;

        // Over-limit (102): WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT
        // + DALYTRAN-AMT (PIC S9(09)V99, scale 2). Pass when ACCT-CREDIT-LIMIT >=
        // WS-TEMP-BAL, i.e. reject when the credit limit is strictly below tempBal.
        BigDecimal tempBal = account.getAcctCurrCycCredit()
                .subtract(account.getAcctCurrCycDebit())
                .add(item.getDalytranAmt())
                .setScale(2, RoundingMode.HALF_UP);
        if (account.getAcctCreditLimit().compareTo(tempBal) < 0) {
            rejectCode = TransactionRejectException.OVER_LIMIT;
            rejectDescription = TransactionRejectException.MSG_OVER_LIMIT;
        }

        // Expiration (103): a second, independent IF (not an else) inside the
        // account-found branch, so it overwrites a 102 verdict when both apply.
        // ACCT-EXPIRAION-DATE (frozen legacy misspelling) is compared against the
        // first ten characters (YYYY-MM-DD) of the 26-char DALYTRAN-ORIG-TS; pass
        // when ACCT-EXPIRAION-DATE >= that date, reject when strictly less-than.
        String origDatePart = item.getDalytranOrigTs().substring(0, 10);
        if (account.getAcctExpiraionDate().compareTo(origDatePart) < 0) {
            rejectCode = TransactionRejectException.ACCOUNT_EXPIRED;
            rejectDescription = TransactionRejectException.MSG_ACCOUNT_EXPIRED;
        }

        if (rejectCode != 0) {
            return PostingItem.rejected(item, rejectCode, rejectDescription, account, xrefAcctId);
        }
        return PostingItem.valid(item, account, xrefAcctId);
    }
}
