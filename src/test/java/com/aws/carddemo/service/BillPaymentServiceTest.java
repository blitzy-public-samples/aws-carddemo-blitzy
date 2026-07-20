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
package com.aws.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.service.BillPaymentService.BillPaymentResult;

/**
 * Pure JUnit&nbsp;5 + Mockito unit tests for {@link BillPaymentService}, the Java
 * re-platform of the CardDemo COBOL bill-payment program {@code COBIL00C} (CICS
 * transaction {@code CB00}, source relocated to {@code legacy/cbl/COBIL00C.cbl}).
 * The suite locks down the behavioral-parity contract the migration must preserve
 * exactly (AAP &sect;0.9.2 field-contract parity and &sect;0.8.3 "preserve
 * public/observable contracts"): the {@code PROCESS-ENTER-KEY} validation order,
 * the confirmation-flag branching ({@code Y}/{@code N}/blank/other), the fixed
 * bill-payment transaction attributes, the reverse-browse transaction-id
 * generation, and the {@link java.math.BigDecimal} (scale&nbsp;2) balance
 * arithmetic that reduces a full payment to {@code 0.00}.
 *
 * <h2>Construction (no Spring, no database, no Testcontainers)</h2>
 * {@code BillPaymentService} is a {@code @Service} {@code @Transactional} bean with
 * three constructor-injected repositories ({@link AccountRepository},
 * {@link CardXrefRepository}, {@link TransactionRepository}). Each is a Mockito
 * mock; the service is instantiated directly in {@link #setUp()} in the authored
 * constructor order, so these tests exercise the service's own logic in complete
 * isolation &mdash; there is no Spring {@code ApplicationContext}, no JDBC
 * connection, and no container.
 *
 * <h2>Static collaborators are exercised for real</h2>
 * The service derives the posted transaction id from
 * {@link com.aws.carddemo.common.util.IdGenerator#nextTransactionId(String)} and
 * its timestamps from
 * {@link com.aws.carddemo.common.util.DateUtils#currentTimestamp()}. Both are
 * {@code final} utility classes exposing only {@code static} methods and therefore
 * cannot (and must not) be mocked. Instead the tests control the id deterministically
 * by stubbing the repository max-id lookup
 * ({@link TransactionRepository#findMaxTranId()}) and asserting the <em>real</em>
 * {@code %016d} value the generator produces, while the timestamp is asserted by
 * shape (the {@code YYYY-MM-DD HH:MM:SS.ffffff} mask), never by an exact clock value.
 *
 * <h2>Parity evidence (verified against the relocated COBOL)</h2>
 * The asserted message literals are the exact COBOL {@code WS-MESSAGE} strings:
 * {@code 'Acct ID can NOT be empty...'} ({@code COBIL00C} L161),
 * {@code 'Invalid value. Valid values are (Y/N)...'} (L187),
 * {@code 'You have nothing to pay...'} (L201),
 * {@code 'Confirm to make a bill payment...'} (L237),
 * {@code 'Account ID NOT found...'} (L361) and
 * {@code 'Unable to lookup XREF AIX file...'} (L432). The fixed transaction
 * attributes reproduce {@code PROCESS-ENTER-KEY} L220-L229 verbatim, and the success
 * message reproduces the {@code WRITE-TRANSACT-FILE} {@code STRING} at L527-L528
 * (note the two significant spaces after {@code "successful."}).
 *
 * <h2>Mockito strictness</h2>
 * The suite runs under {@link MockitoExtension} with the default
 * {@code STRICT_STUBS} policy: each test stubs only the collaborators its code path
 * actually reaches, and short-circuiting paths (blank id, invalid/declined confirm,
 * nothing-to-pay) assert {@code verifyNoInteractions}/{@code never()} rather than
 * declaring stubs that would never be used.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillPaymentService — COBIL00C bill-payment posting / confirm-flag parity")
public class BillPaymentServiceTest {

    /** Account id typed on the screen ({@code ACTIDINI}); numeric {@code PIC 9(11)}. */
    private static final String ACCT_ID = "1";

    /** The same account id as the {@link Long} primary key the repository is keyed on. */
    private static final long ACCT_KEY = 1L;

    /**
     * Representative 16-digit card number resolved from the cross-reference. It is a
     * well-known test PAN, not a real card, and bill payment never exposes a CVV.
     */
    private static final String CARD_NUMBER = "4111111111111111";

    /** A positive current balance used by the posting and display paths. */
    private static final String POSITIVE_BALANCE = "1500.75";

    /**
     * Current maximum transaction id seeded into the reverse-browse lookup. Chosen as
     * the 311-row seed count so the generated successor is visibly derived, not fixed.
     */
    private static final String MAX_TRAN_ID = "0000000000000311";

    /** The {@code %016d} successor of {@link #MAX_TRAN_ID} the real generator must produce. */
    private static final String NEXT_TRAN_ID = "0000000000000312";

    /** The first id generated when the transaction table is empty (COBOL {@code ENDFILE}&rarr;{@code ZEROS}). */
    private static final String FIRST_TRAN_ID = "0000000000000001";

    /** Data-access mock for the {@code account} table (COBOL {@code ACCTDAT} file). */
    @Mock
    private AccountRepository accountRepository;

    /** Data-access mock for the {@code card_xref} table (COBOL {@code CXACAIX} alternate index). */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Data-access mock for the {@code transaction} table (COBOL {@code TRANSACT} file). */
    @Mock
    private TransactionRepository transactionRepository;

    /** System under test, constructed explicitly in the authored constructor order. */
    private BillPaymentService service;

    /**
     * Builds the system under test before each test, wiring the three mocks through the
     * authored constructor order ({@code account}, {@code cardXref}, {@code transaction}).
     */
    @BeforeEach
    void setUp() {
        service = new BillPaymentService(accountRepository, cardXrefRepository, transactionRepository);
    }

    /**
     * Builds an {@link Account} fixture with the given key and current balance. Only
     * {@code acctId} and {@code currBal} influence bill payment; the remaining fields
     * carry innocuous, valid values so the fixture is realistic without affecting the
     * asserted behavior.
     *
     * @param acctId  the account primary key
     * @param currBal the current balance as a decimal string (parsed with {@link BigDecimal})
     * @return a populated, unpersisted {@link Account}
     */
    private static Account account(long acctId, String currBal) {
        return new Account(
                acctId,
                "Y",
                new BigDecimal(currBal),
                new BigDecimal("5000.00"),
                new BigDecimal("2000.00"),
                "2020-01-01",
                "2030-01-01",
                "2025-01-01",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "12345",
                "DEFAULT");
    }

    /**
     * Builds a {@link CardXref} fixture linking {@link #CARD_NUMBER} to the given account.
     *
     * @param acctId the account the card belongs to
     * @return a populated, unpersisted {@link CardXref}
     */
    private static CardXref xref(long acctId) {
        return new CardXref(CARD_NUMBER, 100000001L, acctId);
    }

    // ------------------------------------------------------------------
    // Verbatim caller-visible message contracts (COBOL WS-MESSAGE strings).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("public message constants reproduce the COBOL WS-MESSAGE literals verbatim, including significant spacing")
    void messageConstantsAreVerbatimCobolLiterals() {
        // Precondition/validation messages (COBIL00C PROCESS-ENTER-KEY / READ-* paragraphs).
        assertThat(BillPaymentService.ACCT_ID_EMPTY_MESSAGE)
                .isEqualTo("Acct ID can NOT be empty...")
                .endsWith("...")
                .doesNotEndWith(" ");
        assertThat(BillPaymentService.INVALID_CONFIRM_MESSAGE)
                .isEqualTo("Invalid value. Valid values are (Y/N)...");
        assertThat(BillPaymentService.NOTHING_TO_PAY_MESSAGE)
                .isEqualTo("You have nothing to pay...");
        assertThat(BillPaymentService.CONFIRM_PROMPT_MESSAGE)
                .isEqualTo("Confirm to make a bill payment...");
        assertThat(BillPaymentService.ACCOUNT_NOT_FOUND_MESSAGE)
                .isEqualTo("Account ID NOT found...");
        assertThat(BillPaymentService.XREF_NOT_FOUND_MESSAGE)
                .isEqualTo("Unable to lookup XREF AIX file...");
    }

    // ------------------------------------------------------------------
    // Blank account id (COBIL00C PROCESS-ENTER-KEY L159-L164): rejected before
    // any file is read.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("blank/null account id returns 'Acct ID can NOT be empty...' and touches no repository")
    void blankAccountId_returnsAcctIdEmpty_noRepoInteraction(String candidate) {
        BillPaymentResult result = service.payBill(candidate, "Y");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo(BillPaymentService.ACCT_ID_EMPTY_MESSAGE);
        assertThat(result.postedTransaction()).isNull();
        assertThat(result.newBalance()).isNull();

        // The account is never read and nothing is posted (no file I/O at all).
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
    }

    // ------------------------------------------------------------------
    // Account not found (COBIL00C READ-ACCTDAT-FILE L361, DFHRESP(NOTFND)).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("missing account raises RecordNotFoundException('Account ID NOT found...') with nothing posted")
    void accountNotFound_throwsRecordNotFound() {
        when(accountRepository.findById(ACCT_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.payBill(ACCT_ID, "Y"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(BillPaymentService.ACCOUNT_NOT_FOUND_MESSAGE);

        // The confirmed path never reaches the cross-reference or the transaction write.
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        verifyNoInteractions(cardXrefRepository);
    }

    @Test
    @DisplayName("non-numeric account id maps to the NOTFND outcome (RecordNotFoundException) without any repository read")
    void nonNumericAccountId_throwsRecordNotFound() {
        assertThatThrownBy(() -> service.payBill("ABC", "Y"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(BillPaymentService.ACCOUNT_NOT_FOUND_MESSAGE)
                .hasCauseInstanceOf(NumberFormatException.class);

        // parseAccountKey throws while evaluating the findById argument, so findById is
        // never actually invoked and no collaborator is touched.
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
    }

    // ------------------------------------------------------------------
    // Nothing to pay (COBIL00C PROCESS-ENTER-KEY L198-L205: ACCT-CURR-BAL <= ZEROS).
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-50.00", "-0.01"})
    @DisplayName("non-positive balance returns 'You have nothing to pay...' and posts nothing")
    void nothingToPay_nonPositiveBalance_noPost(String balance) {
        when(accountRepository.findById(ACCT_KEY)).thenReturn(Optional.of(account(ACCT_KEY, balance)));

        BillPaymentResult result = service.payBill(ACCT_ID, "Y");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo(BillPaymentService.NOTHING_TO_PAY_MESSAGE);
        assertThat(result.postedTransaction()).isNull();
        assertThat(result.newBalance()).isEqualByComparingTo(new BigDecimal(balance));

        // The credit-limit-style guard short-circuits before any posting work.
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardXrefRepository);
    }

    // ------------------------------------------------------------------
    // Confirmed payment posts the full balance (COBIL00C PROCESS-ENTER-KEY
    // L208-L235: READ-CXACAIX-FILE, reverse-browse id, WRITE-TRANSACT-FILE,
    // COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT, UPDATE-ACCTDAT-FILE).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("confirm 'Y' posts a full-balance bill payment: fixed attributes, resolved card, real %016d id, balance -> 0.00")
    void confirmYes_postsFullBalancePayment_fixedAttributes() {
        Account acct = account(ACCT_KEY, POSITIVE_BALANCE);
        when(accountRepository.findById(ACCT_KEY)).thenReturn(Optional.of(acct));
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.of(xref(ACCT_KEY)));
        when(transactionRepository.findMaxTranId()).thenReturn(Optional.of(MAX_TRAN_ID));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, Transaction.class));

        BillPaymentResult result = service.payBill(ACCT_ID, "Y");

        // Capture the persisted transaction and assert the fixed bill-payment attributes
        // (COBIL00C L220-L229) plus the resolved card number and the generated id.
        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(txnCaptor.capture());
        Transaction posted = txnCaptor.getValue();

        assertThat(posted.getTypeCd()).isEqualTo("02");
        assertThat(posted.getCatCd()).isEqualTo(2);
        assertThat(posted.getTranSource()).isEqualTo("POS TERM");
        assertThat(posted.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
        assertThat(posted.getTranMerchantId()).isEqualTo(999999999L);
        assertThat(posted.getTranMerchantName()).isEqualTo("BILL PAYMENT");
        assertThat(posted.getTranMerchantCity()).isEqualTo("N/A");
        assertThat(posted.getTranMerchantZip()).isEqualTo("N/A");
        assertThat(posted.getCardNum()).isEqualTo(CARD_NUMBER);

        // Amount is the full current balance (positive), preserved to the cent via BigDecimal.
        assertThat(posted.getTranAmt()).isEqualByComparingTo(new BigDecimal(POSITIVE_BALANCE));
        assertThat(posted.getTranAmt()).isGreaterThan(BigDecimal.ZERO);

        // Id is the real %016d successor of the stubbed maximum (IdGenerator is not mocked).
        assertThat(posted.getTranId()).isEqualTo(NEXT_TRAN_ID);

        // Timestamps are asserted by shape (YYYY-MM-DD HH:MM:SS.ffffff), never by an exact
        // clock value, and both origination and processing stamps carry the same instant.
        assertThat(posted.getOrigTs())
                .isNotNull()
                .hasSize(26)
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}");
        assertThat(posted.getProcTs()).isEqualTo(posted.getOrigTs());

        // Capture the rewritten account and assert the balance was reduced to zero.
        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(acctCaptor.capture());
        assertThat(acctCaptor.getValue().getCurrBal()).isEqualByComparingTo(new BigDecimal("0.00"));

        // The result reflects a successful posting with the zeroed balance and the exact
        // COBOL success message (two spaces after "successful." from the two STRING literals).
        assertThat(result.success()).isTrue();
        assertThat(result.newBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(result.postedTransaction()).isNotNull();
        assertThat(result.postedTransaction().getTranId()).isEqualTo(NEXT_TRAN_ID);
        assertThat(result.message())
                .isEqualTo("Payment successful. " + " Your Transaction ID is " + NEXT_TRAN_ID + ".");
    }

    @ParameterizedTest
    @ValueSource(strings = {"y", " Y ", " y "})
    @DisplayName("confirm 'y' and whitespace-padded 'Y'/'y' also post the full-balance payment")
    void affirmativeConfirmVariants_post(String confirm) {
        Account acct = account(ACCT_KEY, POSITIVE_BALANCE);
        when(accountRepository.findById(ACCT_KEY)).thenReturn(Optional.of(acct));
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.of(xref(ACCT_KEY)));
        when(transactionRepository.findMaxTranId()).thenReturn(Optional.of(MAX_TRAN_ID));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, Transaction.class));

        BillPaymentResult result = service.payBill(ACCT_ID, confirm);

        assertThat(result.success()).isTrue();
        assertThat(result.postedTransaction()).isNotNull();
        assertThat(result.postedTransaction().getTypeCd()).isEqualTo("02");
        assertThat(result.postedTransaction().getTranAmt())
                .isEqualByComparingTo(new BigDecimal(POSITIVE_BALANCE));
        assertThat(result.newBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("an empty transaction table seeds the first id '0000000000000001' (COBOL ENDFILE -> ZEROS)")
    void emptyTransactionTable_generatesFirstId() {
        Account acct = account(ACCT_KEY, POSITIVE_BALANCE);
        when(accountRepository.findById(ACCT_KEY)).thenReturn(Optional.of(acct));
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.of(xref(ACCT_KEY)));
        when(transactionRepository.findMaxTranId()).thenReturn(Optional.empty());
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, Transaction.class));

        BillPaymentResult result = service.payBill(ACCT_ID, "Y");

        assertThat(result.success()).isTrue();
        assertThat(result.postedTransaction().getTranId()).isEqualTo(FIRST_TRAN_ID);
        assertThat(result.message()).contains(FIRST_TRAN_ID);
    }

    @Test
    @DisplayName("confirmed payment with no card cross-reference raises RecordNotFoundException('Unable to lookup XREF AIX file...')")
    void confirmYes_xrefNotFound_throwsRecordNotFound() {
        when(accountRepository.findById(ACCT_KEY))
                .thenReturn(Optional.of(account(ACCT_KEY, POSITIVE_BALANCE)));
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.payBill(ACCT_ID, "Y"))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(BillPaymentService.XREF_NOT_FOUND_MESSAGE);

        // The id lookup and both writes are downstream of the failed cross-reference read.
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(transactionRepository);
    }

    // ------------------------------------------------------------------
    // Narrowed data-integrity mapping at the posting boundary (QA CRITICAL-1 /
    // MAJOR-2): the eager INSERT surfaces a real tran_id collision here, and ONLY
    // a genuine duplicate tran_id is translated to DuplicateKeyException — every
    // other integrity violation propagates unchanged (consistent with
    // TransactionService), never a silently swallowed or misclassified error.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a duplicate tran_id (SQLState 23505 on pk_transaction) is translated to DuplicateKeyException; the balance is not reduced")
    void confirmYes_duplicateTranId_translatedToDuplicateKey() {
        when(accountRepository.findById(ACCT_KEY))
                .thenReturn(Optional.of(account(ACCT_KEY, POSITIVE_BALANCE)));
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.of(xref(ACCT_KEY)));
        when(transactionRepository.findMaxTranId()).thenReturn(Optional.of(MAX_TRAN_ID));
        // The eager INSERT (Persistable forces persist) fails the pk_transaction unique
        // constraint under a MAX+1 race instead of silently overwriting the racing row.
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "could not execute statement",
                        new SQLException(
                                "ERROR: duplicate key value violates unique constraint \"pk_transaction\"",
                                "23505")));

        assertThatThrownBy(() -> service.payBill(ACCT_ID, "Y"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessage("Tran ID already exist...");

        // The insert failed, so the balance-reducing account rewrite never runs — no
        // audit-trail loss with a still-reduced balance (the CRITICAL-1 hazard).
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    @DisplayName("a NON-tran_id integrity violation propagates unchanged (raw DIVE), not mislabelled as a duplicate key or swallowed")
    void confirmYes_nonTranIdIntegrityViolation_propagatesUnchanged() {
        when(accountRepository.findById(ACCT_KEY))
                .thenReturn(Optional.of(account(ACCT_KEY, POSITIVE_BALANCE)));
        when(cardXrefRepository.findFirstByAcctIdOrderByXrefCardNumAsc(ACCT_KEY))
                .thenReturn(Optional.of(xref(ACCT_KEY)));
        when(transactionRepository.findMaxTranId()).thenReturn(Optional.of(MAX_TRAN_ID));
        DataIntegrityViolationException foreignKeyViolation = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException(
                        "ERROR: insert or update on table \"transaction\" violates "
                                + "foreign key constraint \"fk_transaction_card\"",
                        "23503"));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(foreignKeyViolation);

        assertThatThrownBy(() -> service.payBill(ACCT_ID, "Y"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class)
                .isSameAs(foreignKeyViolation);

        verify(accountRepository, never()).save(any(Account.class));
    }


    // ------------------------------------------------------------------
    // Confirm 'N' (COBIL00C EVALUATE CONFIRMI WHEN 'N'/'n'): CLEAR-CURRENT-SCREEN and
    // set the error flag; the account is never read and nothing is posted.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"N", "n", " N ", " n "})
    @DisplayName("confirm 'N' declines the payment (cleared screen, empty message) without reading the account")
    void confirmNo_noPost_clearedScreen(String confirm) {
        BillPaymentResult result = service.payBill(ACCT_ID, confirm);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEmpty();
        assertThat(result.postedTransaction()).isNull();
        assertThat(result.newBalance()).isNull();

        // WHEN 'N' returns before READ-ACCTDAT-FILE, so no collaborator is touched.
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
    }

    // ------------------------------------------------------------------
    // Blank confirm (COBIL00C EVALUATE CONFIRMI WHEN SPACES/LOW-VALUES): the account is
    // read and its balance displayed with the "confirm to pay" prompt; no posting.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\t"})
    @DisplayName("blank confirm displays the balance with 'Confirm to make a bill payment...' and posts nothing")
    void blankConfirm_displaysBalance(String confirm) {
        when(accountRepository.findById(ACCT_KEY))
                .thenReturn(Optional.of(account(ACCT_KEY, POSITIVE_BALANCE)));

        BillPaymentResult result = service.payBill(ACCT_ID, confirm);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo(BillPaymentService.CONFIRM_PROMPT_MESSAGE);
        assertThat(result.postedTransaction()).isNull();
        assertThat(result.newBalance()).isEqualByComparingTo(new BigDecimal(POSITIVE_BALANCE));

        // The balance is shown but nothing is written and no card lookup occurs.
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardXrefRepository, transactionRepository);
    }

    // ------------------------------------------------------------------
    // Other confirm value (COBIL00C EVALUATE CONFIRMI WHEN OTHER): invalid, no read.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"X", "Z", "yes", "no", "1", "0"})
    @DisplayName("any other confirm value returns 'Invalid value. Valid values are (Y/N)...' without reading the account")
    void otherConfirm_invalid(String confirm) {
        BillPaymentResult result = service.payBill(ACCT_ID, confirm);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo(BillPaymentService.INVALID_CONFIRM_MESSAGE);
        assertThat(result.postedTransaction()).isNull();
        assertThat(result.newBalance()).isNull();

        // WHEN OTHER short-circuits before READ-ACCTDAT-FILE.
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
    }
}
