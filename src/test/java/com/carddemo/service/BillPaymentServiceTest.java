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
package com.carddemo.service;

import com.carddemo.dto.billpayment.BillPaymentRequest;
import com.carddemo.dto.billpayment.BillPaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.util.TransactionIdGenerator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mockito unit tests for {@link BillPaymentService#processBillPayment(Long, BillPaymentRequest)} —
 * the online bill-payment flow that replaces the CICS program {@code app/cbl/COBIL00C.cbl}
 * (TRANID {@code CB00}, "Bill Payment").
 *
 * <p>COBIL00C, after reading the account and confirming the request, posts a single payment
 * {@code Transaction} against the account's card and reduces {@code ACCT-CURR-BAL}. These tests
 * verify that the Java service reproduces that flow exactly, with the same hard-coded COBOL field
 * constants ({@code COBIL00C} L218-233), the same verbatim message literals, and the same
 * transaction/account write pair inside a single {@code @Transactional} unit of work (PR-24).</p>
 *
 * <h2>Why this test mirrors the COMMITTED service, not the migration-plan sketch</h2>
 * <p>The class-under-test is verified against the actual committed
 * {@code com.carddemo.service.BillPaymentService}, which differs from the illustrative snippet in
 * this file's agent prompt in several ways that these tests faithfully reflect (the same situation
 * the sibling {@code TransactionServiceTest} documents for {@code TransactionService}):</p>
 * <ul>
 *   <li><b>Method name:</b> the public entry point is
 *       {@code processBillPayment(Long acctId, BillPaymentRequest request)} returning
 *       {@link BillPaymentResponse} — not {@code processPayment}.</li>
 *   <li><b>Real constructor collaborators (4):</b> {@link AccountRepository},
 *       <b>{@link CardXrefRepository}</b>, {@link TransactionRepository} and
 *       <b>{@link TransactionIdGenerator}</b>. The service resolves the account's card number from
 *       the cross-reference and generates the 16-character {@code tranId} (PR-10) exactly like the
 *       online add-transaction path, so {@code CardXrefRepository} and {@code TransactionIdGenerator}
 *       are genuine, committed dependencies (required so {@code @InjectMocks} can build the service)
 *       even though they are absent from the originally-declared dependency set. Conversely the
 *       service does <em>not</em> depend on {@code TransactionService}, so it is not referenced
 *       here.</li>
 *   <li><b>Account is rewritten with {@code saveAndFlush}</b> (not {@code save}) so JPA
 *       {@code @Version} optimistic locking is exercised within the transaction (PR-22); the
 *       transaction itself is written with {@code transactionRepository.save}.</li>
 *   <li><b>Guards use {@link IllegalStateException}</b> with the verbatim COBIL00C messages
 *       ({@code "You have nothing to pay..."} for {@code ACCT-CURR-BAL <= ZEROS}, and
 *       {@code "Confirm to make a bill payment..."} when the {@code Y}/{@code N} flag is not
 *       {@code "Y"}); a missing account or card cross-reference raises
 *       {@link AccountNotFoundException} carrying the verbatim {@code "Account ID NOT found..."}
 *       message (COBOL reason code 101).</li>
 *   <li><b>Hard-coded transaction fields</b> map to the committed {@link Transaction} property
 *       names: {@code typeCd = "02"}, {@code categoryCd = "0002"} (a fixed-width {@code String}
 *       preserving the leading zeros of {@code TRAN-CAT-CD PIC 9(04)}, not the bare integer
 *       {@code 2}), {@code description = "BILL PAYMENT - ONLINE"}, {@code merchantId = 999999999L},
 *       {@code merchantName = "BILL PAYMENT"}, {@code source = "POS TERM"},
 *       {@code merchantCity = "N/A"}, {@code merchantZip = "N/A"}.</li>
 * </ul>
 *
 * <h2>Conventions (matching the sibling service tests)</h2>
 * <ul>
 *   <li>{@code @ExtendWith(MockitoExtension.class)} in default <b>strict-stubs</b> mode: every stub
 *       declared in a test must be exercised by that test, so each test stubs only what its path
 *       uses (the happy-path helper stubs exactly the four collaborators the success flow touches;
 *       the rejection tests stub only {@code findById}).</li>
 *   <li>The stateless static helpers {@code BigDecimalUtil} and {@code DateConversionUtil} are
 *       <em>not</em> mocked; they run for real and produce self-consistent values, keeping these
 *       unit tests independent of any fixed DB2 timestamp string (the same approach used by
 *       {@code TransactionServiceTest}).</li>
 *   <li>Money is asserted with AssertJ {@code isEqualByComparingTo} (PR-16) so scale never affects
 *       equality. Exact messages are asserted with {@code hasMessage(..)}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillPaymentService - replaces COBOL COBIL00C bill payment")
class BillPaymentServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionIdGenerator transactionIdGenerator;

    @InjectMocks
    private BillPaymentService billPaymentService;

    @Captor
    private ArgumentCaptor<Transaction> transactionCaptor;

    @Captor
    private ArgumentCaptor<Account> accountCaptor;

    // ------------------------------------------------------------------------------------------
    // Canonical fixtures
    // ------------------------------------------------------------------------------------------

    /** Primary account identifier (&le; 11 digits, matching {@code ACCT-ID PIC 9(11)}). */
    private static final Long ACCT_ID = 11_111_111_111L;

    /** A well-formed 16-digit card number returned by the cross-reference (happy path). */
    private static final String CARD_NUM = "4111111111111111";

    /** Customer identifier carried by the cross-reference record. */
    private static final Long CUST_ID = 100_000_001L;

    /** The 6-digit online suffix the (mocked) DB sequence hands to the id generator. */
    private static final long ONLINE_SUFFIX = 1L;

    /** A canonical 16-character transaction id (parmDate(10) + suffix(6)) per PR-10. */
    private static final String SIXTEEN_CHAR_ID = "2024011500000001";

    /** Credit limit used by every fixture account; drives the availableCredit assertion. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Builds an account fixture with the given current balance and a fixed {@link #CREDIT_LIMIT}.
     * Only {@code currBal} and {@code creditLimit} are read by {@code processBillPayment}; the other
     * fields are populated to keep the fixture realistic. Note the setter is {@code setActiveStatus}
     * (the committed {@link Account} field is {@code activeStatus}).
     */
    private Account buildAccount(Long acctId, BigDecimal currBal) {
        Account a = new Account();
        a.setAcctId(acctId);
        a.setActiveStatus("Y");
        a.setCurrBal(currBal);
        a.setCreditLimit(CREDIT_LIMIT);
        a.setCashCreditLimit(new BigDecimal("1000.00"));
        a.setCurrCycCredit(BigDecimal.ZERO);
        a.setCurrCycDebit(BigDecimal.ZERO);
        return a;
    }

    /** Builds the cross-reference record returned for {@link #ACCT_ID} / {@link #CARD_NUM}. */
    private CardXref buildXref(Long acctId, String cardNum) {
        CardXref xref = new CardXref();
        xref.setXrefCardNum(cardNum);
        xref.setCustId(CUST_ID);
        xref.setAccountId(acctId);
        return xref;
    }

    /**
     * Builds a confirmed, FULL-method bill-payment request. {@code confirmation = "Y"} clears the
     * COBIL00C confirmation guard; {@code paymentMethod} is left {@code null} so the service defaults
     * to FULL — the exact COBIL00C behavior of paying the entire current balance
     * ({@code TRAN-AMT = ACCT-CURR-BAL}). {@code amount} is intentionally not set because the FULL
     * path never reads it.
     */
    private BillPaymentRequest paymentRequest(Long acctId) {
        BillPaymentRequest request = new BillPaymentRequest();
        request.setAccountId(acctId);
        request.setConfirmation("Y");
        return request;
    }

    /**
     * Stubs the complete success flow — and ONLY the four collaborators the success path actually
     * invokes (strict-stubs friendly): the account read, the card cross-reference lookup, the online
     * id suffix sequence, and the id generator. Neither {@code transactionRepository.save(..)} nor
     * {@code accountRepository.saveAndFlush(..)} is stubbed: the service ignores their return values,
     * so the tests assert on the captured arguments via {@code verify(..)} instead.
     */
    private void stubHappyPath(Account account, String cardNum) {
        when(accountRepository.findById(account.getAcctId())).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByAccountId(account.getAcctId()))
                .thenReturn(List.of(buildXref(account.getAcctId(), cardNum)));
        when(transactionRepository.nextTransactionIdSuffix()).thenReturn(ONLINE_SUFFIX);
        when(transactionIdGenerator.nextOnlineId(anyString(), anyLong())).thenReturn(SIXTEEN_CHAR_ID);
    }

    // ==========================================================================================
    // Group 1 — Successful bill payment
    // ==========================================================================================

    @Nested
    @DisplayName("Successful bill payment")
    class SuccessfulPayment {

        @Test
        @DisplayName("Should create a transaction for the full balance and zero the account balance "
                + "(COBIL00C TRAN-AMT = ACCT-CURR-BAL, then ACCT-CURR-BAL reduced to ZERO)")
        void shouldProcessFullBalancePayment() {
            // given — a $1,500.00 balance; FULL (default) method pays the entire balance
            Account account = buildAccount(ACCT_ID, new BigDecimal("1500.00"));
            stubHappyPath(account, CARD_NUM);

            // when
            billPaymentService.processBillPayment(ACCT_ID, paymentRequest(ACCT_ID));

            // then — the written transaction carries the full pre-payment balance...
            verify(transactionRepository).save(transactionCaptor.capture());
            assertThat(transactionCaptor.getValue().getAmount())
                    .isEqualByComparingTo(new BigDecimal("1500.00"));

            // ...and the rewritten account has been reduced to zero (saveAndFlush, PR-22)
            verify(accountRepository).saveAndFlush(accountCaptor.capture());
            assertThat(accountCaptor.getValue().getCurrBal())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Should return a confirmation echoing the generated transaction id, the paid "
                + "amount, the before/after balances, and the resulting available credit")
        void shouldReturnPaymentConfirmation() {
            // given
            Account account = buildAccount(ACCT_ID, new BigDecimal("1500.00"));
            stubHappyPath(account, CARD_NUM);

            // when
            BillPaymentResponse response =
                    billPaymentService.processBillPayment(ACCT_ID, paymentRequest(ACCT_ID));

            // then — every response field reflects the COBIL00C outcome
            assertThat(response).isNotNull();
            assertThat(response.tranId()).isEqualTo(SIXTEEN_CHAR_ID);
            assertThat(response.accountId()).isEqualTo(ACCT_ID);
            assertThat(response.paymentAmount()).isEqualByComparingTo(new BigDecimal("1500.00"));
            assertThat(response.previousBalance()).isEqualByComparingTo(new BigDecimal("1500.00"));
            assertThat(response.newBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            // availableCredit = creditLimit - newBalance = 5000.00 - 0.00
            assertThat(response.availableCredit()).isEqualByComparingTo(new BigDecimal("5000.00"));
            // processedAt is the 26-char DB2 external timestamp (PR-11)
            assertThat(response.processedAt()).isNotNull().hasSize(26);
            // success message references the generated transaction id
            assertThat(response.successMessage())
                    .contains("Payment successful")
                    .contains(SIXTEEN_CHAR_ID);
        }
    }

    // ==========================================================================================
    // Group 2 — Zero / negative balance validation (COBIL00C L198-205)
    // ==========================================================================================

    @Nested
    @DisplayName("Validation - zero balance (COBIL00C L198-205 'You have nothing to pay...')")
    class ZeroBalanceValidation {

        @Test
        @DisplayName("Should reject a payment when the balance is exactly zero")
        void shouldRejectPaymentWhenBalanceIsZero() {
            // given — nothing to pay
            Account account = buildAccount(ACCT_ID, BigDecimal.ZERO);
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            // when / then — verbatim COBIL00C message; no persistence side effects
            assertThatThrownBy(() -> billPaymentService.processBillPayment(ACCT_ID, paymentRequest(ACCT_ID)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("You have nothing to pay...");

            verifyNoInteractions(cardXrefRepository, transactionRepository, transactionIdGenerator);
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }

        @Test
        @DisplayName("Should reject a payment when the balance is negative (a credit balance)")
        void shouldRejectPaymentWhenBalanceIsNegative() {
            // given — a credit (negative) balance is also 'nothing to pay' (ACCT-CURR-BAL <= ZEROS)
            Account account = buildAccount(ACCT_ID, new BigDecimal("-100.00"));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            // when / then
            assertThatThrownBy(() -> billPaymentService.processBillPayment(ACCT_ID, paymentRequest(ACCT_ID)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("You have nothing to pay...");

            verifyNoInteractions(cardXrefRepository, transactionRepository, transactionIdGenerator);
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }
    }

    // ==========================================================================================
    // Group 3 — Hardcoded transaction fields (COBIL00C L218-233)
    // ==========================================================================================

    @Nested
    @DisplayName("Hardcoded transaction fields (COBIL00C L218-233)")
    class HardcodedTransactionFields {

        @BeforeEach
        void setupHappyPath() {
            // A positive balance so the flow reaches WRITE-TRANSACT-FILE; FULL method pays it all.
            Account account = buildAccount(ACCT_ID, new BigDecimal("500.00"));
            stubHappyPath(account, CARD_NUM);
        }

        /** Runs the payment and returns the single captured, saved payment transaction. */
        private Transaction processAndCaptureTransaction() {
            billPaymentService.processBillPayment(ACCT_ID, paymentRequest(ACCT_ID));
            verify(transactionRepository).save(transactionCaptor.capture());
            return transactionCaptor.getValue();
        }

        @Test
        @DisplayName("Should set TRAN-TYPE-CD to '02' (payment type, COBIL00C L220)")
        void shouldSetTransactionTypeCdTo02() {
            assertThat(processAndCaptureTransaction().getTypeCd()).isEqualTo("02");
        }

        @Test
        @DisplayName("Should set TRAN-CAT-CD to '0002' (payment category PIC 9(04), COBIL00C L221)")
        void shouldSetTransactionCategoryCdTo0002() {
            // The COBOL TRAN-CAT-CD = 2 (PIC 9(04)) is stored fixed-width with leading zeros.
            assertThat(processAndCaptureTransaction().getCategoryCd()).isEqualTo("0002");
        }

        @Test
        @DisplayName("Should set TRAN-DESC to 'BILL PAYMENT - ONLINE' (COBIL00C L223)")
        void shouldSetTransactionDescriptionToBillPaymentOnline() {
            assertThat(processAndCaptureTransaction().getDescription()).isEqualTo("BILL PAYMENT - ONLINE");
        }

        @Test
        @DisplayName("Should set TRAN-MERCHANT-ID to 999999999 (COBIL00C L226)")
        void shouldSetMerchantIdTo999999999() {
            assertThat(processAndCaptureTransaction().getMerchantId()).isEqualTo(999999999L);
        }

        @Test
        @DisplayName("Should set TRAN-MERCHANT-NAME to 'BILL PAYMENT' (COBIL00C L227)")
        void shouldSetMerchantNameToBillPayment() {
            assertThat(processAndCaptureTransaction().getMerchantName()).isEqualTo("BILL PAYMENT");
        }

        @Test
        @DisplayName("Should set TRAN-AMT to the full current balance (COBIL00C L224 TRAN-AMT = ACCT-CURR-BAL)")
        void shouldSetTransactionAmountToFullCurrentBalance() {
            // PR-16: compare numerically, ignoring scale.
            assertThat(processAndCaptureTransaction().getAmount())
                    .isEqualByComparingTo(new BigDecimal("500.00"));
        }

        @Test
        @DisplayName("Should set TRAN-SOURCE 'POS TERM', merchant city/zip 'N/A', and the cross-reference "
                + "card number (COBIL00C L222, L228-229, L225)")
        void shouldSetSourceMerchantLocationAndCardNumber() {
            Transaction saved = processAndCaptureTransaction();
            assertThat(saved.getSource()).isEqualTo("POS TERM");
            assertThat(saved.getMerchantCity()).isEqualTo("N/A");
            assertThat(saved.getMerchantZip()).isEqualTo("N/A");
            // TRAN-CARD-NUM = XREF-CARD-NUM resolved from the cross-reference.
            assertThat(saved.getCardNum()).isEqualTo(CARD_NUM);
        }
    }

    // ==========================================================================================
    // Group 4 — Atomicity (PR-24): one @Transactional unit of work writes the txn then the account
    // ==========================================================================================

    @Nested
    @DisplayName("Atomicity (PR-24)")
    class TransactionalAtomicity {

        @Test
        @DisplayName("Should persist BOTH the payment transaction AND the updated account, writing the "
                + "transaction first (COBIL00C L234 WRITE-TRANSACT-FILE before L235 UPDATE-ACCTDAT-FILE)")
        void shouldSaveBothTransactionAndUpdatedAccount() {
            // given
            Account account = buildAccount(ACCT_ID, new BigDecimal("750.00"));
            stubHappyPath(account, CARD_NUM);

            // when
            billPaymentService.processBillPayment(ACCT_ID, paymentRequest(ACCT_ID));

            // then — both writes occur, in the COBOL order, inside the single @Transactional scope
            InOrder ordered = inOrder(transactionRepository, accountRepository);
            ordered.verify(transactionRepository).save(any(Transaction.class));
            ordered.verify(accountRepository).saveAndFlush(any(Account.class));
        }
    }

    // ==========================================================================================
    // Group 5 — Confirmation guard (COBIL00C L210/L236-238: CONF-PAY-YES)
    // ==========================================================================================

    @Nested
    @DisplayName("Confirmation guard (COBIL00C 'Confirm to make a bill payment...')")
    class ConfirmationGuard {

        @Test
        @DisplayName("Should reject an unconfirmed request (confirmation != 'Y') and persist nothing")
        void shouldRejectUnconfirmedPayment() {
            // given — a positive balance clears the nothing-to-pay guard, but the request is not confirmed
            Account account = buildAccount(ACCT_ID, new BigDecimal("500.00"));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

            BillPaymentRequest request = paymentRequest(ACCT_ID);
            request.setConfirmation("N");

            // when / then
            assertThatThrownBy(() -> billPaymentService.processBillPayment(ACCT_ID, request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Confirm to make a bill payment...");

            verifyNoInteractions(cardXrefRepository, transactionRepository, transactionIdGenerator);
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }
    }

    // ==========================================================================================
    // Group 6 — Error handling (missing account -> AccountNotFoundException, COBOL code 101)
    // ==========================================================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should throw AccountNotFoundException with the verbatim COBIL00C message when the "
                + "account does not exist (READ-ACCTDAT-FILE absent)")
        void shouldThrowAccountNotFoundExceptionForMissingAccount() {
            // given — the account lookup returns empty
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> billPaymentService.processBillPayment(ACCT_ID, paymentRequest(ACCT_ID)))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account ID NOT found...");

            // nothing downstream is touched and nothing is persisted
            verifyNoInteractions(cardXrefRepository, transactionRepository, transactionIdGenerator);
            verify(accountRepository, never()).saveAndFlush(any(Account.class));
        }
    }
}
