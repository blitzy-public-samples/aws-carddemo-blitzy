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
package com.carddemo.billpay.service;

import com.carddemo.billpay.mapper.BillPaymentMapper;
import com.carddemo.billpay.repository.AccountRepository;
import com.carddemo.billpay.repository.CardXrefRepository;
import com.carddemo.billpay.repository.TransactionRepository;
import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Transaction;
import com.carddemo.common.dto.BillPaymentRequestDto;
import com.carddemo.common.dto.BillPaymentResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.OptimisticLockConflictException;
import com.carddemo.common.exception.RecordNotFoundException;

import jakarta.persistence.OptimisticLockException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * :purpose: Bill-payment business logic migrated from CICS program ``COBIL00C``
 *  (transaction ``CB00``). Pays an account balance in full, records the payment as a
 *  transaction, and decrements the account balance by that amount, reproducing the
 *  legacy ``PROCESS-ENTER-KEY`` control flow, validation order and user-facing
 *  messages with byte-identical fidelity.
 * :note: The transaction insert and the account-balance update run inside a single
 *  ``@Transactional`` unit, mirroring the legacy CICS logical-unit-of-work so both
 *  commit together or roll back together; concurrent account modification is detected
 *  through JPA ``@Version`` optimistic locking.
 */
@Service
public class BillPaymentService {

    /** SLF4J logger; never emits card number, SSN or CVV (AAP 0.6.7). */
    private static final Logger log = LoggerFactory.getLogger(BillPaymentService.class);

    // --- verbatim user-facing messages (character-for-character from COBIL00C) ---

    /** ``COBIL00C`` empty account-id message. */
    private static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";

    /** ``COBIL00C`` account / cross-reference not-found message (shared text). */
    private static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** ``COBIL00C`` invalid confirm-flag message. */
    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

    /** ``COBIL00C`` zero-or-negative balance message. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** ``COBIL00C`` confirm-payment prompt message. */
    private static final String MSG_CONFIRM_PAYMENT = "Confirm to make a bill payment...";

    /** ``COBIL00C`` duplicate transaction-id message. */
    private static final String MSG_TRAN_ID_EXISTS = "Tran ID already exist...";

    /** Monetary scale for balance and amount arithmetic (``NUMERIC(_,2)``). */
    private static final int MONEY_SCALE = 2;

    private final AccountRepository accountRepository;
    private final CardXrefRepository cardXrefRepository;
    private final TransactionRepository transactionRepository;
    private final BillPaymentMapper billPaymentMapper;

    /**
     * :purpose: Construct the service with its four collaborators via constructor
     *  injection (no field or setter injection).
     * :param accountRepository: persistence access to the account being paid
     *  (``READ-ACCTDAT-FILE`` / ``UPDATE-ACCTDAT-FILE``).
     * :param cardXrefRepository: card cross-reference access keyed by account id
     *  (``READ-CXACAIX-FILE``).
     * :param transactionRepository: transaction-id sequence and transaction insert
     *  (``WRITE-TRANSACT-FILE``).
     * :param billPaymentMapper: assembler of the fixed-value bill-payment transaction.
     */
    public BillPaymentService(AccountRepository accountRepository,
                              CardXrefRepository cardXrefRepository,
                              TransactionRepository transactionRepository,
                              BillPaymentMapper billPaymentMapper) {
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionRepository = transactionRepository;
        this.billPaymentMapper = billPaymentMapper;
    }

    /**
     * :purpose: Re-platform the ``COBIL00C`` (``CB00``) ``PROCESS-ENTER-KEY`` bill-payment
     *  flow. Validate the account id and confirm flag, read the account, and — when the
     *  payment is confirmed — record a bill-payment transaction and decrement the account
     *  balance in full, atomically.
     * :param request: the bill-payment request carrying the account id (``ACTIDIN``) and
     *  the confirm flag (``CONFIRM``); must not be null.
     * :param sessionContext: the externalized pseudo-conversational session context; may
     *  be null (for example in unit tests), in which case no session state is propagated.
     * :returns: the bill-payment response — the confirm prompt with the current balance
     *  (blank confirm), a cleared response (confirm ``N``), or the payment-success message
     *  with the generated transaction id and the post-payment balance (confirm ``Y``).
     * :raises CardDemoException: (HTTP 400) when the account id is empty, the confirm flag
     *  is invalid, the account has nothing to pay, or the generated transaction id already
     *  exists.
     * :raises RecordNotFoundException: (HTTP 404) when the account or its card
     *  cross-reference does not exist.
     * :raises OptimisticLockConflictException: (HTTP 409) when the account was modified
     *  concurrently between read and update.
     * :note: The transaction id is drawn from the database sequence ``transaction_id_seq``
     *  and rendered as the 16-digit zero-padded wire form.
     */
    @Transactional
    public BillPaymentResponseDto processBillPayment(BillPaymentRequestDto request,
                                                     SessionContext sessionContext) {
        // Step 1 - account id empty guard (COBIL00C L159-166).
        String accountIdInput = request == null ? null : request.getAccountId();
        if (isBlank(accountIdInput)) {
            throw new CardDemoException(MSG_ACCT_ID_EMPTY);
        }
        String accountId = accountIdInput.trim();

        // Step 2 - confirm-flag dispatch (COBIL00C L172-192). First failure wins.
        String confirm = request.getConfirm() == null ? "" : request.getConfirm().trim();
        boolean confirmPay = false;
        if ("Y".equals(confirm) || "y".equals(confirm)) {
            confirmPay = true;
        } else if ("N".equals(confirm) || "n".equals(confirm)) {
            // COBOL CLEAR-CURRENT-SCREEN: reset to a fresh, blank screen and stop.
            log.debug("Bill payment cleared (confirm=N) for account {}", accountId);
            return clearedResponse();
        } else if (!confirm.isEmpty()) {
            // Anything other than Y/y/N/n/blank is rejected before the account read.
            throw new CardDemoException(MSG_INVALID_CONFIRM);
        }

        // Step 3 - read the account for update (COBOL READ-ACCTDAT-FILE, READ ... UPDATE).
        // The account id is parsed here (not before the confirm dispatch) so an invalid
        // confirm flag is reported ahead of a not-found outcome, matching COBIL00C.
        Long acctId = parseAccountId(accountId);
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_ACCOUNT_NOT_FOUND));
        BigDecimal currentBalance = account.getAcctCurrBal();

        // Step 4 - nothing-to-pay guard (COBOL L198-204).
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CardDemoException(MSG_NOTHING_TO_PAY);
        }

        // Step 5 (blank confirm) - preview prompt without writing (COBOL L235-238).
        if (!confirmPay) {
            log.debug("Bill payment preview for account {}", acctId);
            return previewResponse(accountId, currentBalance);
        }

        // Step 5 (confirm Y) - atomic bill payment (COBOL L209-235), all within this tx.
        // 5.1 Card cross-reference lookup keyed by account id (READ-CXACAIX-FILE); a miss
        //     reuses the same "Account ID NOT found..." text as the account miss.
        CardXref cardXref = cardXrefRepository.findByXrefAcctId(acctId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_ACCOUNT_NOT_FOUND));

        // 5.2 Generate the 16-digit transaction id from the DB sequence (AAP 0.6.5),
        //     replacing the legacy browse-last-then-increment.
        long seq = transactionRepository.getNextTransactionId();
        String tranId = String.format("%016d", seq);

        // 5.3 Assemble the fixed-value bill-payment transaction; the frozen literals and
        //     the pay-in-full amount are owned by the mapper. Guard the amount defensively.
        Transaction tran = billPaymentMapper.toBillPaymentTransaction(account, cardXref, tranId);
        if (tran.getTranAmt() == null) {
            tran.setTranAmt(scale2(account.getAcctCurrBal()));
        }

        // 5.4 Persist the transaction (WRITE-TRANSACT-FILE); a duplicate key maps to the
        //     verbatim "Tran ID already exist..." message.
        try {
            // saveAndFlush, not save: the INSERT must reach the database inside this try
            // so a duplicate primary key is rejected with the verbatim
            // "Tran ID already exist..." message (COBIL00C L535-537) rather than
            // silently overwriting an existing transaction at commit time.
            transactionRepository.saveAndFlush(tran);
        } catch (DataIntegrityViolationException e) {
            log.warn("Bill payment rejected duplicate transaction id {}", tranId);
            throw new CardDemoException(MSG_TRAN_ID_EXISTS);
        }

        // 5.5 Decrement the balance exactly as COBOL:
        //     COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (L234). Pay-in-full -> 0.00.
        BigDecimal newBalance = account.getAcctCurrBal()
                .subtract(tran.getTranAmt())
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        account.setAcctCurrBal(newBalance);

        // 5.6 Persist the account (UPDATE-ACCTDAT-FILE / REWRITE) in the SAME transaction,
        //     with the optimistic-lock backstop mapping concurrent edits to HTTP 409.
        try {
            accountRepository.save(account);
            // The explicit flush forces the @Version check to run INSIDE this try block.
            // Without it the StaleStateException is raised at commit, past the catch, and
            // a concurrent bill payment surfaces as an unexpected 500 instead of the
            // COBOL concurrency outcome (HTTP 409, "Record changed by some one else...").
            accountRepository.flush();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            log.warn("Optimistic lock conflict posting bill payment for account {}", acctId);
            throw new OptimisticLockConflictException(e);
        }

        // 5.7 Propagate minimal session context (COMMAREA MOVEs); never log the card number.
        if (sessionContext != null) {
            sessionContext.setAcctId(acctId);
            sessionContext.setCardNum(cardXref.getXrefCardNum());
        }

        log.info("Bill payment posted for account {} with transaction id {}", acctId, tranId);

        // 5.8 Success response carrying the verbatim two-space success message.
        String message = String.format("Payment successful.  Your Transaction ID is %s.", tranId);
        return successResponse(accountId, newBalance, tranId, message);
    }

    /**
     * :purpose: Build the response for the confirm ``N`` (cancel) branch, reproducing the
     *  COBOL ``CLEAR-CURRENT-SCREEN`` reset to a fresh, blank screen.
     * :returns: an empty response with no account id, balance, transaction id or message.
     */
    private BillPaymentResponseDto clearedResponse() {
        return new BillPaymentResponseDto();
    }

    /**
     * :purpose: Build the preview response for the blank-confirm branch, prompting the
     *  user to confirm the payment while echoing the current balance (COBOL L235-238).
     * :param accountId: the entered account id echoed back to the screen.
     * :param currentBalance: the current account balance to display.
     * :returns: a response carrying the account id, the current balance and the confirm
     *  prompt message, with no transaction id.
     */
    private BillPaymentResponseDto previewResponse(String accountId, BigDecimal currentBalance) {
        BillPaymentResponseDto response = new BillPaymentResponseDto();
        response.setAccountId(accountId);
        response.setCurrentBalance(currentBalance);
        response.setMessage(MSG_CONFIRM_PAYMENT);
        return response;
    }

    /**
     * :purpose: Build the success response for a completed payment (COBOL L522-533).
     * :param accountId: the paid account id echoed back to the screen.
     * :param newBalance: the post-payment balance (``0.00`` for pay-in-full).
     * :param tranId: the 16-digit zero-padded transaction id of the recorded payment.
     * :param message: the verbatim two-space success message.
     * :returns: a response carrying the account id, the post-payment balance, the
     *  transaction id and the success message.
     */
    private BillPaymentResponseDto successResponse(String accountId, BigDecimal newBalance,
                                                   String tranId, String message) {
        BillPaymentResponseDto response = new BillPaymentResponseDto();
        response.setAccountId(accountId);
        response.setCurrentBalance(newBalance);
        response.setTransactionId(tranId);
        response.setMessage(message);
        return response;
    }

    /**
     * :purpose: Parse the trimmed account id to the numeric key used for the repository
     *  lookup. A non-numeric value cannot match any stored account, so it is treated as a
     *  not-found outcome — matching the legacy VSAM read that fails with ``NOTFND`` — rather
     *  than surfacing a parse error or inventing a numeric-format validation message.
     * :param accountId: the trimmed, non-empty account id.
     * :returns: the account id as a ``Long``.
     * :raises RecordNotFoundException: when the account id is not a valid number.
     */
    private Long parseAccountId(String accountId) {
        try {
            return Long.parseLong(accountId);
        } catch (NumberFormatException e) {
            throw new RecordNotFoundException(MSG_ACCOUNT_NOT_FOUND);
        }
    }

    /**
     * :purpose: Normalize a monetary value to the two-decimal scale used throughout the
     *  bill-payment arithmetic.
     * :param value: the value to normalize; must not be null.
     * :returns: the value at scale 2 using ``HALF_UP`` rounding.
     */
    private BigDecimal scale2(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * :purpose: Test whether a raw screen input is null, empty or whitespace-only,
     *  reproducing the COBOL ``SPACES``/``LOW-VALUES`` emptiness check.
     * :param value: the raw input value.
     * :returns: ``true`` when the value is null or blank; ``false`` otherwise.
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
