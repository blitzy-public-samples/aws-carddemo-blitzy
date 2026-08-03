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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * :purpose: Unit tests for {@link BillPaymentService}, the migration of CICS program
 *  ``COBIL00C`` (transaction ``CB00``). Exercises every ``PROCESS-ENTER-KEY`` branch with
 *  mocked collaborators and asserts the verbatim legacy messages, the frozen bill-payment
 *  transaction values, the 16-digit zero-padded transaction-id rendering, the pay-in-full
 *  balance arithmetic and the collaborator interaction order — without a Spring context,
 *  a database or Testcontainers.
 * :note: Exception types map to HTTP status in the controller layer: {@link CardDemoException}
 *  is 400, {@link RecordNotFoundException} is 404 and {@link OptimisticLockConflictException}
 *  is 409; this suite asserts the thrown types and messages only.
 */
@ExtendWith(MockitoExtension.class)
public class BillPaymentServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BillPaymentMapper billPaymentMapper;

    @InjectMocks
    private BillPaymentService billPaymentService;

    // --- Verbatim COBIL00C user-facing messages (byte-for-byte; see app/cbl/COBIL00C.cbl). ---

    /** COBIL00C L161 empty account-id message. */
    private static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";

    /** COBIL00C L361/L425 account / cross-reference not-found message (shared text). */
    private static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** COBIL00C L187 invalid confirm-flag message. */
    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

    /** COBIL00C L201 zero-or-negative balance message. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** COBIL00C L237 confirm-payment prompt message. */
    private static final String MSG_CONFIRM_PAYMENT = "Confirm to make a bill payment...";

    /** COBIL00C L536 duplicate transaction-id message. */
    private static final String MSG_TRAN_ID_EXISTS = "Tran ID already exist...";

    /**
     * COBIL00C L527-530 success-banner prefix. The COBOL ``STRING 'Payment successful. '``
     * plus ``' Your Transaction ID is '`` concatenates a trailing and a leading space, so
     * there are exactly TWO spaces after ``successful.``.
     */
    private static final String SUCCESS_MESSAGE_PREFIX = "Payment successful.  Your Transaction ID is ";

    // --- Frozen bill-payment transaction field values (COBIL00C L218-232 / BillPaymentMapper). ---

    private static final String FROZEN_TRAN_TYPE_CD = "02";
    private static final Integer FROZEN_TRAN_CAT_CD = 2;
    private static final String FROZEN_TRAN_SOURCE = "POS TERM";
    private static final String FROZEN_TRAN_DESC = "BILL PAYMENT - ONLINE";
    private static final Long FROZEN_MERCHANT_ID = 999999999L;
    private static final String FROZEN_MERCHANT_NAME = "BILL PAYMENT";
    private static final String FROZEN_MERCHANT_CITY = "N/A";
    private static final String FROZEN_MERCHANT_ZIP = "N/A";

    // --- Fixture identifiers and values. ---

    /** Numeric account key used for the repository lookups (ACCT-ID PIC 9(11)). */
    private static final Long ACCT_ID = 100000000001L;

    /** Account id as entered on the screen (COBIL00 ``ACTIDIN``); parses to {@link #ACCT_ID}. */
    private static final String ACCT_ID_INPUT = "100000000001";

    /** Card cross-reference number (XREF-CARD-NUM X(16)); never printed to test output. */
    private static final String CARD_NUM = "4111111111111111";

    /** Card cross-reference customer id (XREF-CUST-ID PIC 9(09)). */
    private static final Long CUST_ID = 999000001L;

    /** 26-character origination/processing timestamp (TRAN-ORIG-TS / TRAN-PROC-TS X(26)). */
    private static final String TIMESTAMP = "2024-01-01 12:00:00.000000";

    /** Positive balance exercising the pay-in-full path (ACCT-CURR-BAL S9(10)V99). */
    private static final BigDecimal POSITIVE_BALANCE = new BigDecimal("100.00");

    /** Post-payment balance after paying the positive balance in full. */
    private static final BigDecimal ZERO_BALANCE = new BigDecimal("0.00");

    // --- Private fixture builders (hand-written; no Lombok). ---

    /**
     * :purpose: Build an account carrying the supplied current balance and the fixture id.
     * :param balance: the current account balance (ACCT-CURR-BAL) to expose.
     * :returns: a transient {@link Account} with the fixture id and the supplied balance.
     */
    private Account payableAccount(BigDecimal balance) {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setAcctCurrBal(balance);
        return account;
    }

    /**
     * :purpose: Build the card cross-reference keyed by the fixture account id.
     * :returns: a transient {@link CardXref} linking the fixture card, customer and account.
     */
    private CardXref xref() {
        CardXref cardXref = new CardXref();
        cardXref.setXrefCardNum(CARD_NUM);
        cardXref.setXrefCustId(CUST_ID);
        cardXref.setXrefAcctId(ACCT_ID);
        return cardXref;
    }

    /**
     * :purpose: Build the transaction the mocked {@link BillPaymentMapper} returns, carrying
     *  the frozen bill-payment field values, the supplied id and pay-in-full amount, and
     *  identical origination and processing timestamps.
     * :param tranId: the 16-digit zero-padded transaction id assigned by the service.
     * :param amount: the pay-in-full transaction amount (equal to the account balance).
     * :returns: a transient {@link Transaction} populated with the frozen values.
     */
    private Transaction mappedTransaction(String tranId, BigDecimal amount) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranTypeCd(FROZEN_TRAN_TYPE_CD);
        transaction.setTranCatCd(FROZEN_TRAN_CAT_CD);
        transaction.setTranSource(FROZEN_TRAN_SOURCE);
        transaction.setTranDesc(FROZEN_TRAN_DESC);
        transaction.setTranAmt(amount);
        transaction.setTranCardNum(CARD_NUM);
        transaction.setTranMerchantId(FROZEN_MERCHANT_ID);
        transaction.setTranMerchantName(FROZEN_MERCHANT_NAME);
        transaction.setTranMerchantCity(FROZEN_MERCHANT_CITY);
        transaction.setTranMerchantZip(FROZEN_MERCHANT_ZIP);
        transaction.setTranOrigTs(TIMESTAMP);
        transaction.setTranProcTs(TIMESTAMP);
        return transaction;
    }

    /**
     * :purpose: Build a bill-payment request carrying the account id and confirm flag.
     * :param accountId: the entered account id (COBIL00 ``ACTIDIN``); may be null/blank.
     * :param confirm: the entered confirm flag (COBIL00 ``CONFIRM``); may be null/blank.
     * :returns: a {@link BillPaymentRequestDto} with the supplied field values.
     */
    private BillPaymentRequestDto request(String accountId, String confirm) {
        return new BillPaymentRequestDto(accountId, confirm);
    }

    /**
     * :purpose: An empty, null or whitespace-only account id is rejected by the first guard
     *  (COBIL00C L159-166) with the verbatim empty-id message and before any collaborator is
     *  touched, proving the guard short-circuits ahead of every read and write.
     * :raises CardDemoException: with message {@link #MSG_ACCT_ID_EMPTY}.
     */
    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {"   "})
    void emptyAccountIdThrowsCardDemoException(String accountId) {
        BillPaymentRequestDto req = request(accountId, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req, null))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_ACCT_ID_EMPTY);

        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository,
                billPaymentMapper);
    }

    /**
     * :purpose: A confirm flag that is neither ``Y``/``y``/``N``/``n`` nor blank is rejected by
     *  the confirm dispatch (COBIL00C L185-190) with the verbatim invalid-value message, before
     *  the account is read, so no collaborator is touched.
     * :raises CardDemoException: with message {@link #MSG_INVALID_CONFIRM}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"X", "YE", "1", "*"})
    void invalidConfirmationValueThrowsCardDemoException(String confirm) {
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, confirm);

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req, null))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_INVALID_CONFIRM);

        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository,
                billPaymentMapper);
    }

    /**
     * :purpose: Confirm ``N``/``n`` reproduces COBOL ``CLEAR-CURRENT-SCREEN`` (L178-180): the
     *  screen is reset to a blank response with no payment performed, so no collaborator is
     *  touched and no exception is thrown.
     * :returns: nothing — asserts the cleared response carries no id, message or balance.
     */
    @ParameterizedTest
    @ValueSource(strings = {"N", "n"})
    void cancelWithNMakesNoPayment(String confirm) {
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, confirm);

        BillPaymentResponseDto response = billPaymentService.processBillPayment(req, null);

        assertThat(response).isNotNull();
        assertThat(response.getTransactionId()).isNull();
        assertThat(response.getMessage()).isNull();
        assertThat(response.getCurrentBalance()).isNull();
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository,
                billPaymentMapper);
    }

    /**
     * :purpose: A missing account (COBOL ``READ-ACCTDAT-FILE`` NOTFND, L361) is rejected with the
     *  verbatim not-found message for both the blank-preview and the confirm-``Y`` paths, and no
     *  downstream collaborator (cross-reference, id sequence, mapper) is touched.
     * :raises RecordNotFoundException: with message {@link #MSG_ACCOUNT_NOT_FOUND}.
     */
    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {"Y"})
    void accountNotFoundThrowsRecordNotFound(String confirm) {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, confirm);

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req, null))
                .isExactlyInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_ACCOUNT_NOT_FOUND);

        verify(accountRepository).findById(ACCT_ID);
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardXrefRepository, transactionRepository, billPaymentMapper);
    }

    /**
     * :purpose: A zero or negative balance is rejected by the nothing-to-pay guard
     *  (COBIL00C L197-206) with the verbatim message, even when the payment is confirmed, and
     *  no cross-reference, id sequence, mapper or save runs.
     * :raises CardDemoException: with message {@link #MSG_NOTHING_TO_PAY}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-5.00", "-0.01"})
    void nothingToPayThrowsCardDemoException(String balance) {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(payableAccount(new BigDecimal(balance))));
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req, null))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_NOTHING_TO_PAY);

        verify(accountRepository).findById(ACCT_ID);
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardXrefRepository, transactionRepository, billPaymentMapper);
    }

    /**
     * :purpose: The nothing-to-pay guard (COBOL L197-206) fires ahead of the blank-confirm
     *  preview, so a zero balance is rejected rather than previewed.
     * :raises CardDemoException: with message {@link #MSG_NOTHING_TO_PAY}.
     */
    @Test
    void nothingToPayWithBlankConfirmThrowsBeforePreview() {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(payableAccount(ZERO_BALANCE)));
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, "");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req, null))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_NOTHING_TO_PAY);

        verify(accountRepository).findById(ACCT_ID);
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardXrefRepository, transactionRepository, billPaymentMapper);
    }

    /**
     * :purpose: A blank, null or whitespace-only confirm flag reads the account and returns the
     *  confirm prompt with the current balance (COBOL L236-239) without writing anything — no
     *  cross-reference lookup, no id generation, no mapper call and no save.
     * :returns: nothing — asserts the preview response and that no payment was performed.
     */
    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {"   "})
    void blankConfirmationReturnsPreviewWithConfirmMessage(String confirm) {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(payableAccount(POSITIVE_BALANCE)));
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, confirm);

        BillPaymentResponseDto response = billPaymentService.processBillPayment(req, null);

        assertThat(response.getMessage()).isEqualTo(MSG_CONFIRM_PAYMENT);
        assertThat(response.getCurrentBalance()).isNotNull();
        assertThat(response.getCurrentBalance().compareTo(POSITIVE_BALANCE)).isZero();
        assertThat(response.getTransactionId()).isNull();

        verify(accountRepository).findById(ACCT_ID);
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardXrefRepository, transactionRepository, billPaymentMapper);
    }

    /**
     * :purpose: A confirmed payment whose card cross-reference is missing (COBOL
     *  ``READ-CXACAIX-FILE`` NOTFND, L425) is rejected with the same not-found message as a
     *  missing account, after the account read and the cross-reference lookup, and before any
     *  id generation, mapper call or save.
     * :raises RecordNotFoundException: with message {@link #MSG_ACCOUNT_NOT_FOUND}.
     */
    @Test
    void xrefNotFoundThrowsRecordNotFound() {
        when(accountRepository.findById(ACCT_ID))
                .thenReturn(Optional.of(payableAccount(POSITIVE_BALANCE)));
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID)).thenReturn(Optional.empty());
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req, null))
                .isExactlyInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_ACCOUNT_NOT_FOUND);

        verify(accountRepository).findById(ACCT_ID);
        verify(cardXrefRepository).findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID);
        verify(transactionRepository, never()).getNextTransactionId();
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(billPaymentMapper);
    }

    /**
     * :purpose: A confirmed payment (COBIL00C L208-235) reads the account and its cross-reference,
     *  draws the 16-digit id from the sequence, assembles and saves the frozen bill-payment
     *  transaction, nets the balance to zero and returns the verbatim two-space success banner;
     *  a non-null session context receives the account id and card number.
     * :returns: nothing — asserts the response, the captured transaction's frozen values, the
     *  netted account balance and the propagated session context.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Y", "y"})
    void successfulPaymentPostsTransactionAndZeroesBalance(String confirm) {
        Account account = payableAccount(POSITIVE_BALANCE);
        CardXref cardXref = xref();
        String tranId = "0000000000000001";
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(transactionRepository.getNextTransactionId()).thenReturn(1L);
        when(billPaymentMapper.toBillPaymentTransaction(account, cardXref, tranId))
                .thenReturn(mappedTransaction(tranId, POSITIVE_BALANCE));
        SessionContext session = new SessionContext();
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, confirm);

        BillPaymentResponseDto response = billPaymentService.processBillPayment(req, session);

        assertThat(response.getTransactionId()).isEqualTo(tranId);
        assertThat(response.getMessage()).isEqualTo(SUCCESS_MESSAGE_PREFIX + tranId + ".");
        assertThat(response.getCurrentBalance()).isNotNull();
        assertThat(response.getCurrentBalance().compareTo(ZERO_BALANCE)).isZero();
        assertThat(response.getCurrentBalance().scale()).isEqualTo(2);

        ArgumentCaptor<Transaction> tranCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(tranCaptor.capture());
        Transaction savedTran = tranCaptor.getValue();
        assertThat(savedTran.getTranId()).isEqualTo(tranId);
        assertThat(savedTran.getTranTypeCd()).isEqualTo(FROZEN_TRAN_TYPE_CD);
        assertThat(savedTran.getTranCatCd()).isEqualTo(FROZEN_TRAN_CAT_CD);
        assertThat(savedTran.getTranSource()).isEqualTo(FROZEN_TRAN_SOURCE);
        assertThat(savedTran.getTranDesc()).isEqualTo(FROZEN_TRAN_DESC);
        assertThat(savedTran.getTranMerchantId()).isEqualTo(FROZEN_MERCHANT_ID);
        assertThat(savedTran.getTranMerchantName()).isEqualTo(FROZEN_MERCHANT_NAME);
        assertThat(savedTran.getTranMerchantCity()).isEqualTo(FROZEN_MERCHANT_CITY);
        assertThat(savedTran.getTranMerchantZip()).isEqualTo(FROZEN_MERCHANT_ZIP);
        assertThat(savedTran.getTranCardNum()).isEqualTo(CARD_NUM);
        assertThat(savedTran.getTranAmt()).isNotNull();
        assertThat(savedTran.getTranAmt().compareTo(POSITIVE_BALANCE)).isZero();
        assertThat(savedTran.getTranAmt().scale()).isEqualTo(2);
        assertThat(savedTran.getTranOrigTs()).isEqualTo(savedTran.getTranProcTs());

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        // The account rewrite must be flushed inside the service's try block so a
        // concurrent modification is caught there rather than at transaction commit.
        verify(accountRepository).flush();
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getAcctCurrBal().compareTo(ZERO_BALANCE)).isZero();
        assertThat(savedAccount.getAcctCurrBal().scale()).isEqualTo(2);

        assertThat(session.getAcctId()).isEqualTo(ACCT_ID);
        assertThat(session.getCardNum()).isEqualTo(CARD_NUM);
    }

    /**
     * :purpose: The transaction id is the raw sequence value rendered as a 16-digit zero-padded
     *  string (AAP 0.6.5), independent of magnitude, and is echoed verbatim in the success banner.
     * :returns: nothing — asserts the 16-digit id rendering and the success message.
     */
    @Test
    void transactionIdIsZeroPaddedTo16Digits() {
        Account account = payableAccount(POSITIVE_BALANCE);
        CardXref cardXref = xref();
        String tranId = "0000001234567890";
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(transactionRepository.getNextTransactionId()).thenReturn(1234567890L);
        when(billPaymentMapper.toBillPaymentTransaction(account, cardXref, tranId))
                .thenReturn(mappedTransaction(tranId, POSITIVE_BALANCE));
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, "Y");

        BillPaymentResponseDto response = billPaymentService.processBillPayment(req, null);

        assertThat(response.getTransactionId()).isEqualTo(tranId);
        assertThat(response.getTransactionId()).hasSize(16);
        assertThat(response.getMessage()).isEqualTo(SUCCESS_MESSAGE_PREFIX + tranId + ".");
    }

    /**
     * :purpose: A duplicate transaction id surfaced as a persistence integrity violation on the
     *  transaction insert (COBOL DUPKEY/DUPREC, L534-537) maps to the verbatim duplicate message,
     *  and the account update never runs.
     * :raises CardDemoException: with message {@link #MSG_TRAN_ID_EXISTS}.
     */
    @Test
    void duplicateTransactionIdThrowsCardDemoException() {
        Account account = payableAccount(POSITIVE_BALANCE);
        CardXref cardXref = xref();
        String tranId = "0000000000000001";
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(transactionRepository.getNextTransactionId()).thenReturn(1L);
        when(billPaymentMapper.toBillPaymentTransaction(account, cardXref, tranId))
                .thenReturn(mappedTransaction(tranId, POSITIVE_BALANCE));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"transactions_pkey\"",
                        new SQLException("duplicate key value violates unique constraint "
                                + "\"transactions_pkey\"", "23505")));
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req, null))
                .isExactlyInstanceOf(CardDemoException.class)
                .hasMessage(MSG_TRAN_ID_EXISTS);

        // The insert is flushed immediately, so the duplicate key is rejected here and
        // the account rewrite never runs; the pre-existing transaction row is untouched.
        verify(transactionRepository).saveAndFlush(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * :purpose: An integrity violation that is NOT a duplicate transaction id -- for example the
     *  numeric overflow a balance too large for ``NUMERIC(11,2)`` produces -- must not be
     *  reported as ``Tran ID already exist...``, which invited an endless client retry of a
     *  request that can never succeed. It propagates so the data-access handler reports it.
     */
    @Test
    void nonDuplicateIntegrityViolationIsNotReportedAsDuplicateId() {
        Account account = payableAccount(POSITIVE_BALANCE);
        CardXref cardXref = xref();
        String tranId = "0000000000000001";
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID))
                .thenReturn(Optional.of(cardXref));
        when(transactionRepository.getNextTransactionId()).thenReturn(1L);
        when(billPaymentMapper.toBillPaymentTransaction(account, cardXref, tranId))
                .thenReturn(mappedTransaction(tranId, POSITIVE_BALANCE));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("numeric field overflow",
                        new SQLException("numeric field overflow", "22003")));
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * :purpose: A concurrent account modification detected on the account update — surfaced as
     *  either a Spring {@link ObjectOptimisticLockingFailureException} or a JPA
     *  {@link OptimisticLockException} — maps to a 409 {@link OptimisticLockConflictException}
     *  after the transaction insert was attempted (COACTUPC lock pattern, AAP 0.6.2).
     * :raises OptimisticLockConflictException: with {@link OptimisticLockConflictException#MESSAGE}.
     */
    @ParameterizedTest
    @MethodSource("optimisticLockExceptions")
    void optimisticLockConflictOnAccountSaveThrows409(RuntimeException lockException) {
        Account account = payableAccount(POSITIVE_BALANCE);
        CardXref cardXref = xref();
        String tranId = "0000000000000001";
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(transactionRepository.getNextTransactionId()).thenReturn(1L);
        when(billPaymentMapper.toBillPaymentTransaction(account, cardXref, tranId))
                .thenReturn(mappedTransaction(tranId, POSITIVE_BALANCE));
        // The stale-state failure is raised by the explicit flush, which is where a real
        // Hibernate version mismatch surfaces; it must still be caught by the service.
        doThrow(lockException).when(accountRepository).flush();
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, "Y");

        assertThatThrownBy(() -> billPaymentService.processBillPayment(req, null))
                .isExactlyInstanceOf(OptimisticLockConflictException.class)
                .hasMessage(OptimisticLockConflictException.MESSAGE);

        verify(transactionRepository).saveAndFlush(any(Transaction.class));
        verify(accountRepository).save(any(Account.class));
        verify(accountRepository).flush();
    }

    /**
     * :purpose: Supply the two optimistic-lock exception types the service catches on the
     *  account update — the Spring data-access exception and the JPA exception.
     * :returns: a stream of one {@link ObjectOptimisticLockingFailureException} and one
     *  {@link OptimisticLockException} instance.
     */
    private static Stream<RuntimeException> optimisticLockExceptions() {
        return Stream.of(
                new ObjectOptimisticLockingFailureException(Account.class, ACCT_ID),
                new OptimisticLockException("conflict"));
    }

    /**
     * :purpose: The confirmed-payment collaborators run in the fixed order the migration
     *  preserves for deadlock avoidance and atomic write (AAP 0.6.2): read the account, read the
     *  cross-reference, draw the id, assemble the transaction, insert the transaction, then update
     *  the account.
     * :returns: nothing — asserts the interaction order with Mockito ``InOrder``.
     */
    @Test
    void collaboratorInteractionOrderIsReadAccountThenXrefThenIdThenSaveTranThenSaveAccount() {
        Account account = payableAccount(POSITIVE_BALANCE);
        CardXref cardXref = xref();
        String tranId = "0000000000000001";
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID)).thenReturn(Optional.of(cardXref));
        when(transactionRepository.getNextTransactionId()).thenReturn(1L);
        when(billPaymentMapper.toBillPaymentTransaction(account, cardXref, tranId))
                .thenReturn(mappedTransaction(tranId, POSITIVE_BALANCE));
        BillPaymentRequestDto req = request(ACCT_ID_INPUT, "Y");

        billPaymentService.processBillPayment(req, null);

        InOrder inOrder = inOrder(accountRepository, cardXrefRepository, transactionRepository,
                billPaymentMapper);
        inOrder.verify(accountRepository).findById(ACCT_ID);
        inOrder.verify(cardXrefRepository).findFirstByXrefAcctIdOrderByXrefCardNumAsc(ACCT_ID);
        inOrder.verify(transactionRepository).getNextTransactionId();
        inOrder.verify(billPaymentMapper).toBillPaymentTransaction(account, cardXref, tranId);
        inOrder.verify(transactionRepository).saveAndFlush(any(Transaction.class));
        inOrder.verify(accountRepository).save(any(Account.class));
        inOrder.verify(accountRepository).flush();
    }
}
