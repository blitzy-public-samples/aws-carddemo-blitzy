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
import com.carddemo.common.domain.TranCatBal;
import com.carddemo.common.domain.TranCatBalId;
import com.carddemo.common.domain.Transaction;
import com.carddemo.transaction.repository.AccountRepository;
import com.carddemo.transaction.repository.TranCatBalRepository;
import com.carddemo.transaction.repository.TransactionRepository;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Spring Batch writer that posts validated daily transactions.
 *
 * :purpose: Post a validated daily transaction as one atomic unit — update the
 *     transaction-category running balance, update the account balances, then
 *     insert the transaction-master row — mirroring the ``CBTRN02C``
 *     ``2000-POST-TRANSACTION`` paragraph and its ``2700-UPDATE-TCATBAL``,
 *     ``2800-UPDATE-ACCOUNT-REC`` and ``2900-WRITE-TRANSACTION-FILE`` sub-updates
 *     (``app/cbl/CBTRN02C.cbl`` L424-L579). The three updates run in the legacy
 *     order within the step's chunk transaction (chunk size one, configured by
 *     the posting job), so they commit or roll back together.
 * :output: For each valid {@link PostingItem}, a created-or-updated
 *     transaction-category-balance row, an updated account aggregate, and a new
 *     transaction-master row whose ``tranId`` is the daily id verbatim and whose
 *     ``tranProcTs`` is the write-time DB2-format timestamp. Rejected items are
 *     skipped defensively (they are routed to the reject writer by the classifier).
 */
@Component
public class TransactionPostingItemWriter implements ItemWriter<PostingItem> {

    /**
     * Seconds-precision component of the DB2 timestamp format
     * (``EEEE-MM-DD-UU.MM.SS``); the fractional hundredths and trailing zeros are
     * appended by {@link #currentDb2Timestamp()}.
     */
    private static final DateTimeFormatter DB2_TS_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss");

    /** ``TCATBAL-FILE`` upsert access — the transaction-category-balance table. */
    private final TranCatBalRepository tranCatBalRepository;

    /** ``ACCOUNT-FILE`` rewrite access — the account master table. */
    private final AccountRepository accountRepository;

    /** ``TRANSACT-FILE`` write access — the transaction master table. */
    private final TransactionRepository transactionRepository;

    /**
     * :purpose: Construct the posting writer with its three data-access
     *     collaborators.
     * :param tranCatBalRepository: repository for the transaction-category-balance
     *     upsert (``2700-UPDATE-TCATBAL``).
     * :param accountRepository: repository for the account-balance rewrite
     *     (``2800-UPDATE-ACCOUNT-REC``).
     * :param transactionRepository: repository for the transaction-master insert
     *     (``2900-WRITE-TRANSACTION-FILE``).
     */
    public TransactionPostingItemWriter(TranCatBalRepository tranCatBalRepository,
                                        AccountRepository accountRepository,
                                        TransactionRepository transactionRepository) {
        this.tranCatBalRepository = tranCatBalRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * :purpose: Post every valid item of the chunk, reproducing
     *     ``2000-POST-TRANSACTION`` for each record in the fixed order
     *     category-balance update, account update, then transaction insert.
     * :param chunk: the chunk of posting items delivered by the step; rejected
     *     items are skipped so only validated records are posted.
     */
    @Override
    public void write(Chunk<? extends PostingItem> chunk) {
        for (PostingItem item : chunk.getItems()) {
            if (item.isRejected()) {
                continue;
            }
            DailyTransaction dt = item.getDailyTransaction();
            Account account = item.getAccount();
            Long acctId = item.getXrefAcctId();

            updateTranCatBal(acctId, dt);
            updateAccount(account, dt);
            writeTransaction(dt);
        }
    }

    /**
     * :purpose: Upsert the transaction-category running balance, mirroring
     *     ``2700-UPDATE-TCATBAL``: an absent key creates a new row seeded with the
     *     transaction amount (``2700-A``), while an existing key accumulates the
     *     amount onto the current balance (``2700-B``).
     * :param acctId: the cross-referenced account id (``XREF-ACCT-ID``) forming the
     *     account component of the composite key.
     * :param dt: the daily transaction supplying the type code, category code and
     *     signed amount.
     */
    private void updateTranCatBal(Long acctId, DailyTransaction dt) {
        TranCatBalId id = new TranCatBalId(acctId, dt.getDalytranTypeCd(), dt.getDalytranCatCd());
        Optional<TranCatBal> existing = tranCatBalRepository.findById(id);
        if (existing.isEmpty()) {
            BigDecimal balance = dt.getDalytranAmt().setScale(2, RoundingMode.HALF_UP);
            TranCatBal created = new TranCatBal(acctId, dt.getDalytranTypeCd(),
                    dt.getDalytranCatCd(), balance);
            tranCatBalRepository.save(created);
        } else {
            TranCatBal row = existing.get();
            row.setTranCatBal(row.getTranCatBal().add(dt.getDalytranAmt())
                    .setScale(2, RoundingMode.HALF_UP));
            tranCatBalRepository.save(row);
        }
    }

    /**
     * :purpose: Apply the transaction amount to the account balances, mirroring
     *     ``2800-UPDATE-ACCOUNT-REC``: add the amount to the current balance and,
     *     when the amount is non-negative, to the current-cycle credit, otherwise
     *     to the current-cycle debit; then rewrite the account.
     * :param account: the resolved account aggregate to update in place.
     * :param dt: the daily transaction supplying the signed amount.
     */
    private void updateAccount(Account account, DailyTransaction dt) {
        BigDecimal amt = dt.getDalytranAmt();
        account.setAcctCurrBal(account.getAcctCurrBal().add(amt).setScale(2, RoundingMode.HALF_UP));
        if (amt.signum() >= 0) {
            account.setAcctCurrCycCredit(account.getAcctCurrCycCredit().add(amt)
                    .setScale(2, RoundingMode.HALF_UP));
        } else {
            account.setAcctCurrCycDebit(account.getAcctCurrCycDebit().add(amt)
                    .setScale(2, RoundingMode.HALF_UP));
        }
        accountRepository.save(account);
    }

    /**
     * :purpose: Assemble and insert the transaction-master row, mirroring the
     *     ``2000-POST-TRANSACTION`` field copy and ``2900-WRITE-TRANSACTION-FILE``.
     *     The transaction id and origination timestamp are copied verbatim from the
     *     daily record; the processing timestamp is generated at write time.
     * :param dt: the daily transaction whose fields populate the new master row.
     */
    private void writeTransaction(DailyTransaction dt) {
        Transaction tran = new Transaction();
        tran.setTranId(dt.getDalytranId());
        tran.setTranTypeCd(dt.getDalytranTypeCd());
        tran.setTranCatCd(dt.getDalytranCatCd());
        tran.setTranSource(dt.getDalytranSource());
        tran.setTranDesc(dt.getDalytranDesc());
        tran.setTranAmt(dt.getDalytranAmt().setScale(2, RoundingMode.HALF_UP));
        tran.setTranMerchantId(dt.getDalytranMerchantId());
        tran.setTranMerchantName(dt.getDalytranMerchantName());
        tran.setTranMerchantCity(dt.getDalytranMerchantCity());
        tran.setTranMerchantZip(dt.getDalytranMerchantZip());
        tran.setTranCardNum(dt.getDalytranCardNum());
        tran.setTranOrigTs(dt.getDalytranOrigTs());
        tran.setTranProcTs(currentDb2Timestamp());
        transactionRepository.save(tran);
    }

    /**
     * :purpose: Build the current processing timestamp in DB2 format, mirroring
     *     ``Z-GET-DB2-FORMAT-TIMESTAMP``: the wall-clock seconds component followed
     *     by a two-digit hundredths-of-second value and a fixed ``0000`` tail.
     * :returns: a 26-character string of the form
     *     ``yyyy-MM-dd-HH.mm.ss.<hundredths>0000``.
     */
    private static String currentDb2Timestamp() {
        LocalDateTime now = LocalDateTime.now();
        int hundredths = now.getNano() / 10_000_000;
        return now.format(DB2_TS_SECONDS) + "." + String.format("%02d", hundredths) + "0000";
    }
}
