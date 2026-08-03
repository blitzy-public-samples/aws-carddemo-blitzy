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
package com.carddemo.batch.service;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.DiscGroup;
import com.carddemo.common.domain.DiscGroupId;
import com.carddemo.common.domain.TranCatBal;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.exception.RecordNotFoundException;
import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.DiscGroupRepository;
import com.carddemo.batch.repository.TranCatBalRepository;
import com.carddemo.batch.repository.TransactionRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * :purpose: Computes monthly interest per transaction-category balance and posts the
 *     resulting interest transactions and account balance roll-ups, re-platformed from the
 *     batch program ``CBACT04C``. Exposes the interest formula, the disclosure-group
 *     resolution with ``DEFAULT`` fallback, the account and card cross-reference reads, the
 *     interest-transaction assembly, the transaction write, the per-account cycle roll-up
 *     and the account-ordered category-balance read as stateless, independently testable
 *     operations that hold no per-run state.
 * :output: Scale-2 monetary results and persisted interest {@link Transaction} and
 *     {@link Account} rows; the driving read yields {@link TranCatBal} rows in account-key
 *     order so a downstream control-break sees contiguous per-account rows.
 * :composition: The ``batch/`` chunk components (``InterestItemProcessor`` /
 *     ``InterestTransactionWriter``) and the ``config/`` job hold the iteration state and
 *     the ``PARM-DATE`` job parameter and drive these methods to reproduce the ``CBACT04C``
 *     MAIN control-break loop: read category-balance rows in account order via
 *     {@link #readAccountOrderedBalances}; on each ``trancatAcctId`` control break flush the
 *     prior account with {@link #updateAccount} (skipping the first account), reset the
 *     running interest total to {@link java.math.BigDecimal#ZERO}, then call
 *     {@link #loadAccount} and {@link #resolveCardNumber} once for the new account; for each
 *     row resolve the rate with {@link #resolveDiscGroup} and, only when the rate is non-zero
 *     (via {@code compareTo} against zero), compute interest with
 *     {@link #computeMonthlyInterest}, add it to the running total, assemble the row with
 *     {@link #buildInterestTransaction} and persist it with {@link #saveTransaction}; after
 *     the reader is exhausted flush the last account with {@link #updateAccount}.
 * :note: The tran-id suffix is a job-run-global monotonic counter (starting at 0, first
 *     written transaction using ``000001``) that is incremented only when a transaction is
 *     written (non-zero rate), so zero-rate categories consume no suffix number and suffixes
 *     stay contiguous.
 * :note: {@link #updateAccount} is invoked for every account processed, including accounts
 *     whose total interest is zero, because it also zeroes the current-cycle credit and debit
 *     figures.
 */
@Service
public class InterestCalculationService {

    /** SLF4J logger; every line carries the MDC correlation id via ``%X{correlationId}``. */
    private static final Logger log = LoggerFactory.getLogger(InterestCalculationService.class);

    /** The ``/ 1200`` divisor of the monthly-interest formula (``CBACT04C`` line 465). */
    private static final BigDecimal MONTHLY_INTEREST_DIVISOR = new BigDecimal("1200");

    /** The ``'DEFAULT'`` disclosure account-group id used on fallback (``CBACT04C`` line 437). */
    private static final String DEFAULT_ACCT_GROUP_ID = "DEFAULT";

    /** ``MOVE SPACES TO TRAN-MERCHANT-NAME``: the full ``X(50)`` field in blanks. */
    private static final String TRAN_MERCHANT_NAME_SPACES = " ".repeat(50);

    /** ``MOVE SPACES TO TRAN-MERCHANT-CITY``: the full ``X(50)`` field in blanks. */
    private static final String TRAN_MERCHANT_CITY_SPACES = " ".repeat(50);

    /** ``MOVE SPACES TO TRAN-MERCHANT-ZIP``: the full ``X(10)`` field in blanks. */
    private static final String TRAN_MERCHANT_ZIP_SPACES = " ".repeat(10);

    /** Repository for the account-ordered transaction-category-balance driving read. */
    private final TranCatBalRepository tranCatBalRepository;

    /** Repository for the per-account keyed read and cycle roll-up rewrite. */
    private final AccountRepository accountRepository;

    /** Repository for the account-scoped card cross-reference read. */
    private final CardXrefRepository cardXrefRepository;

    /** Repository for the disclosure-group interest-rate lookup. */
    private final DiscGroupRepository discGroupRepository;

    /** Repository for persisting assembled interest transactions. */
    private final TransactionRepository transactionRepository;

    /**
     * :purpose: Construct the service with its collaborating repositories injected by Spring.
     * :param tranCatBalRepository: repository supplying account-ordered category balances.
     * :param accountRepository: repository for the account keyed read and cycle roll-up.
     * :param cardXrefRepository: repository for the account-scoped card cross-reference.
     * :param discGroupRepository: repository for the disclosure-group interest-rate lookup.
     * :param transactionRepository: repository for persisting interest transactions.
     */
    public InterestCalculationService(TranCatBalRepository tranCatBalRepository,
                                      AccountRepository accountRepository,
                                      CardXrefRepository cardXrefRepository,
                                      DiscGroupRepository discGroupRepository,
                                      TransactionRepository transactionRepository) {
        this.tranCatBalRepository = tranCatBalRepository;
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.discGroupRepository = discGroupRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * :purpose: Compute the monthly interest for one transaction-category balance,
     *     reproducing ``1300-COMPUTE-INTEREST`` (``CBACT04C`` lines 462-466):
     *     ``(TRAN-CAT-BAL * DIS-INT-RATE) / 1200``. The multiplication is performed before
     *     the division to match the COBOL parenthesization, and the quotient is TRUNCATED
     *     toward zero at the receiver's scale.
     * :param categoryBalance: the transaction-category balance (``TRAN-CAT-BAL``).
     * :param interestRate: the disclosure-group annual interest rate (``DIS-INT-RATE``).
     * :returns: the monthly interest at scale 2.
     * :note: The rounding mode is {@link RoundingMode#DOWN}, not ``HALF_UP``. The COBOL
     *     ``COMPUTE`` carries no ``ROUNDED`` phrase, so the excess fractional digits of the
     *     quotient are simply dropped as it is stored into ``WS-MONTHLY-INT``
     *     (``PIC S9(09)V99``): ``0.41666… -> 0.41``, ``0.125 -> 0.12`` (never ``0.13``),
     *     and for a credit balance ``-0.41666… -> -0.41``. Rounding half up instead added a
     *     cent to every non-terminating quotient and that error propagated into
     *     ``accounts.acct_curr_bal`` through ``1050-UPDATE-ACCOUNT``, diverging financial
     *     output in breach of AAP 0.6.1, 0.7.1 and 0.7.6 (QA Issue 9).
     */
    public BigDecimal computeMonthlyInterest(BigDecimal categoryBalance, BigDecimal interestRate) {
        return categoryBalance
                .multiply(interestRate)
                .divide(MONTHLY_INTEREST_DIVISOR, 2, RoundingMode.DOWN);
    }

    /**
     * :purpose: Resolve the disclosure group for the interest-rate lookup, reproducing
     *     ``1200-GET-INTEREST-RATE`` with its ``1200-A-GET-DEFAULT-INT-RATE`` fallback
     *     (``CBACT04C`` lines 415-460): read by the supplied account group id, and on a
     *     not-found result re-read with the ``DEFAULT`` account group id while keeping the
     *     transaction type and category codes unchanged. A still-missing group is
     *     unrecoverable and fails the step.
     * :param acctGroupId: the account's group id (``ACCT-GROUP-ID``).
     * :param tranTypeCd: the balance's transaction type code (``TRANCAT-TYPE-CD``).
     * :param tranCatCd: the balance's transaction category code (``TRANCAT-CD``).
     * :returns: the resolved disclosure group.
     */
    public DiscGroup resolveDiscGroup(String acctGroupId, String tranTypeCd, Integer tranCatCd) {
        Optional<DiscGroup> found =
                discGroupRepository.findById(new DiscGroupId(acctGroupId, tranTypeCd, tranCatCd));
        if (found.isEmpty()) {
            found = discGroupRepository.findById(new DiscGroupId(DEFAULT_ACCT_GROUP_ID, tranTypeCd, tranCatCd));
        }
        return found.orElseThrow(() -> new RecordNotFoundException(
                "Disclosure group not found for group=" + acctGroupId
                + " type=" + tranTypeCd + " cat=" + tranCatCd + " (DEFAULT fallback failed)"));
    }

    /**
     * :purpose: Read the account master record by id, reproducing ``1100-GET-ACCT-DATA``
     *     (``CBACT04C`` lines 372-391). A missing account is unrecoverable and fails the step.
     * :param acctId: the account id (``TRANCAT-ACCT-ID`` promoted to ``FD-ACCT-ID``).
     * :returns: the account.
     */
    public Account loadAccount(Long acctId) {
        return accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException("Account not found: acctId=" + acctId));
    }

    /**
     * :purpose: Resolve the card number for an account through the card cross-reference
     *     alternate index, reproducing ``1110-GET-XREF-DATA`` (``CBACT04C`` lines 393-413).
     *     The resolved card number becomes ``TRAN-CARD-NUM`` on every interest transaction for
     *     the account. A missing cross-reference is unrecoverable and fails the step.
     * :param acctId: the account id used as the ``FD-XREF-ACCT-ID`` alternate key.
     * :returns: the 16-character card number.
     * :note: The read is ordered by ``XREF-CARD-NUM`` ascending. A VSAM alternate-index
     *     read returns the records sharing an alternate key in PRIMARY-key order, so for an
     *     account holding several cards ``1110-GET-XREF-DATA`` always yielded the lowest
     *     card number. An unordered ``findFirst`` returned whichever row PostgreSQL
     *     happened to reach first, so the same data and the same parameters could stamp a
     *     DIFFERENT ``TRAN-CARD-NUM`` on the interest transaction from run to run
     *     (QA Issue 11).
     */
    public String resolveCardNumber(Long acctId) {
        return cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(acctId)
                .map(CardXref::getXrefCardNum)
                .orElseThrow(() -> new RecordNotFoundException("Card cross-reference not found: acctId=" + acctId));
    }

    /**
     * :purpose: Assemble (without persisting) one interest transaction, reproducing the field
     *     assembly of ``1300-B-WRITE-TX`` (``CBACT04C`` lines 473-498). A single timestamp is
     *     obtained once and used for both the origination and processing timestamps.
     * :param account: the owning account, supplying the 11-digit id for the description.
     * :param cardNumber: the account's card number (``XREF-CARD-NUM``).
     * :param monthlyInterest: the computed monthly interest (``WS-MONTHLY-INT``).
     * :param parmDate: the 10-character job parameter date (``PARM-DATE``).
     * :param tranIdSuffix: the job-run-global six-digit transaction-id suffix.
     * :returns: the assembled, unsaved transaction.
     */
    public Transaction buildInterestTransaction(Account account, String cardNumber,
                                                BigDecimal monthlyInterest, String parmDate,
                                                long tranIdSuffix) {
        String timestamp = currentDb2Timestamp();
        Transaction transaction = new Transaction();
        transaction.setTranId(parmDate + String.format("%06d", tranIdSuffix));
        transaction.setTranTypeCd("01");
        transaction.setTranCatCd(5);
        transaction.setTranSource("System");
        transaction.setTranDesc("Int. for a/c " + String.format("%011d", account.getAcctId()));
        transaction.setTranAmt(monthlyInterest);
        transaction.setTranMerchantId(0L);
        // MOVE SPACES TO TRAN-MERCHANT-NAME / -CITY / -ZIP: a COBOL MOVE of SPACES fills the
        // whole fixed-width field, so the stored value is the field's width in blanks, not an
        // empty string (QA Issue 12). The widths are the CVTRA05Y declarations X(50), X(50)
        // and X(10), which the column definitions match exactly.
        transaction.setTranMerchantName(TRAN_MERCHANT_NAME_SPACES);
        transaction.setTranMerchantCity(TRAN_MERCHANT_CITY_SPACES);
        transaction.setTranMerchantZip(TRAN_MERCHANT_ZIP_SPACES);
        transaction.setTranCardNum(cardNumber);
        transaction.setTranOrigTs(timestamp);
        transaction.setTranProcTs(timestamp);
        return transaction;
    }

    /**
     * :purpose: Persist an assembled interest transaction, reproducing the ``WRITE`` of
     *     ``1300-B-WRITE-TX`` (``CBACT04C`` line 500). A persistence failure propagates to fail
     *     the batch step.
     * :param transaction: the assembled interest transaction to persist.
     * :returns: the persisted transaction.
     */
    public Transaction saveTransaction(Transaction transaction) {
        return transactionRepository.save(transaction);
    }

    /**
     * :purpose: Apply the per-account cycle roll-up, reproducing ``1050-UPDATE-ACCOUNT``
     *     (``CBACT04C`` lines 350-370): add the accumulated interest to the current balance,
     *     zero the current-cycle credit and debit figures, and rewrite the account. The
     *     rewrite honors the JPA optimistic-lock version on the account; a save failure
     *     propagates to fail the batch step.
     * :param account: the account to roll up and rewrite.
     * :param totalInterest: the interest total accumulated for the account by the caller.
     */
    public void updateAccount(Account account, BigDecimal totalInterest) {
        CorrelationIdContext.getOrCreateCorrelationId();
        account.setAcctCurrBal(account.getAcctCurrBal().add(totalInterest));
        account.setAcctCurrCycCredit(BigDecimal.ZERO);
        account.setAcctCurrCycDebit(BigDecimal.ZERO);
        accountRepository.save(account);
        log.debug("Applied interest cycle roll-up for account {}", account.getAcctId());
    }

    /**
     * :purpose: Read transaction-category balances in account-key order, exposing the
     *     sequential-by-key driving read of ``1000-TCATBALF-GET-NEXT`` (``CBACT04C`` lines
     *     325-348) so that all category rows for one account arrive contiguously for the
     *     downstream control break.
     * :param pageable: the paging directive supplied by the batch reader for chunked iteration.
     * :returns: the requested page of category balances ordered by account id, transaction type
     *     code then transaction category code ascending.
     */
    public List<TranCatBal> readAccountOrderedBalances(Pageable pageable) {
        CorrelationIdContext.getOrCreateCorrelationId();
        List<TranCatBal> balances =
                tranCatBalRepository.findAllByOrderByTrancatAcctIdAscTrancatTypeCdAscTrancatCdAsc(pageable);
        log.debug("Read {} transaction-category balance rows for interest calculation", balances.size());
        return balances;
    }

    /**
     * :purpose: Build a 26-character DB2-format timestamp, reproducing
     *     ``Z-GET-DB2-FORMAT-TIMESTAMP`` (``CBACT04C`` lines 613-626) as
     *     ``YYYY-MM-DD-HH.MM.SS.mmmmmm`` where the six microsecond digits are the two-digit
     *     hundredths-of-a-second followed by ``0000``.
     * :returns: the current timestamp as a 26-character string.
     */
    private String currentDb2Timestamp() {
        LocalDateTime now = LocalDateTime.now();
        int hundredths = now.getNano() / 10_000_000;
        return String.format("%04d-%02d-%02d-%02d.%02d.%02d.%02d0000",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                now.getHour(), now.getMinute(), now.getSecond(), hundredths);
    }
}
