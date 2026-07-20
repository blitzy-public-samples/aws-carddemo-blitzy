/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.service.online;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COBIL00Form;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.service.online.BillPayService.AidKey;
import com.aws.carddemo.service.online.BillPayService.BillPayResult;
import com.aws.carddemo.service.online.BillPayService.MessageSeverity;
import com.aws.carddemo.util.CobolDecimal;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Pure-Mockito unit tests for {@link BillPayService}.
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COBIL00C.cbl} &mdash; CICS COBOL
 * program {@code COBIL00C}, transaction id {@code CB00} (AWS CardDemo online bill payment). These
 * tests assert one-for-one control-flow parity with the numbered paragraphs of the oracle that the
 * service migrates (AAP &sect;0.1.2 / &sect;0.4.1 / &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link BillPayService#processEnterKey(COBIL00Form)} &mdash;
 *       account-id and confirm validation, the {@code EVALUATE CONFIRMI} branches
 *       ({@code 'Y'}/{@code 'N'}/blank/other), the {@code "You have nothing to pay..."} guard, and
 *       the confirmed-payment sequence that pays the <em>entire</em> current balance.</li>
 *   <li>{@code READ-ACCTDAT-FILE} &rarr; account read (COBOL {@code EXEC CICS READ ... UPDATE});
 *       {@code NOTFND} &rarr; {@link RecordNotFoundException} ({@code "Account ID NOT found..."}).</li>
 *   <li>{@code READ-CXACAIX-FILE} &rarr; card cross-reference read
 *       ({@link CardXrefRepository#findByXrefAcctId(Long)}) resolving {@code XREF-CARD-NUM}.</li>
 *   <li>{@code STARTBR}/{@code READPREV}/{@code ENDBR} &rarr; next-transaction-id derivation
 *       (ordered top-one query).</li>
 *   <li>{@code WRITE-TRANSACT-FILE} &rarr; transaction write ({@code EXEC CICS WRITE});
 *       {@code DUPKEY}/{@code DUPREC} &rarr; {@link DuplicateKeyException}
 *       ({@code "Tran ID already exist..."}, FILE STATUS {@code "22"}).</li>
 *   <li>{@code UPDATE-ACCTDAT-FILE} &rarr; account rewrite ({@code EXEC CICS REWRITE}) with the
 *       post-payment balance.</li>
 *   <li>{@code GET-CURRENT-TIMESTAMP} &rarr; {@code ORIG-TS}/{@code PROC-TS} population (the COBOL
 *       26-character {@code YYYY-MM-DD-HH.MM.SS.NNNNNN} with zero-filled fractional seconds).</li>
 *   <li>{@code MAIN-PARA} &rarr; {@link BillPayService#mainEntry(AidKey, COBIL00Form)} &mdash; the
 *       pseudo-conversational state machine: {@code EIBCALEN = 0} first entry, first program entry
 *       (with pre-selected account), and the {@code EVALUATE EIBAID} re-entry branches
 *       ({@code DFHENTER}, {@code DFHPF3}, {@code DFHPF4}, {@code WHEN OTHER}).</li>
 * </ul>
 *
 * <p><b>Test tier:</b> a strict-stubs pure-Mockito unit test ({@link MockitoExtension}); it uses no
 * database, no Spring context, and no Testcontainers. The three repositories and the session-scoped
 * {@link CardDemoContext} (the {@code COMMAREA} replacement) are mocked; the real
 * {@link CobolDecimal} static helper is used so that money arithmetic is exercised for real. Because
 * there is no Spring proxy in a unit test, the service's {@link org.springframework.transaction.annotation.Transactional}
 * annotations are inert, so the self-invocation from {@code mainEntry} to {@code processEnterKey}
 * simply runs as a plain method call.</p>
 *
 * <p><b>Money fidelity (AAP &sect;0.6.1):</b> the balance and transaction amount are
 * {@link BigDecimal} money fields; the canonical assertion is that paying the entire balance leaves
 * a new balance of {@code 0.00} (scale&nbsp;2), computed through the real {@link CobolDecimal}
 * subtraction ({@code RoundingMode.DOWN}). No {@code float}/{@code double} is ever used.</p>
 */
@ExtendWith(MockitoExtension.class)
class BillPayServiceTest {

    /** COBOL {@code WS-PGMNAME VALUE 'COBIL00C'}; recorded as the origin program on a return/redirect. */
    private static final String PROGRAM_NAME = "COBIL00C";

    /** COBOL {@code WS-TRANID VALUE 'CB00'}; recorded as the origin transaction id on a return/redirect. */
    private static final String TRANSACTION_ID = "CB00";

    /** Sign-on program ({@code COSGN00C}); the {@code XCTL} target on first entry ({@code EIBCALEN = 0}). */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Main-menu program ({@code COMEN01C}); the PF3 target when no originating program is recorded. */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** Account id used across the fixtures (COBOL {@code ACCT-ID} / {@code XREF-ACCT-ID}). */
    private static final Long ACCT_ID = 11L;

    /** The account id as entered in the {@code ACTIDIN} field (COBOL {@code PIC X(11)}), parses to {@link #ACCT_ID}. */
    private static final String ACCT_ID_FIELD = "00000000011";

    /** Customer id used to build the cross-reference fixture (COBOL {@code XREF-CUST-ID}). */
    private static final Long CUST_ID = 99L;

    /** Card number that owns the account (COBOL {@code XREF-CARD-NUM}, {@code PIC X(16)}). */
    private static final String CARD_NUM = "4111111111111111";

    // Byte-exact COBOL message literals (verified against legacy/cbl/COBIL00C.cbl).

    /** {@code PROCESS-ENTER-KEY}: empty account id. */
    private static final String MSG_ACCT_EMPTY = "Acct ID can NOT be empty...";

    /** {@code PROCESS-ENTER-KEY}: confirm value neither Y/N nor blank. */
    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

    /** {@code PROCESS-ENTER-KEY}: balance is zero or negative. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** {@code PROCESS-ENTER-KEY}: neutral confirm prompt (error flag off). */
    private static final String MSG_CONFIRM_PAYMENT = "Confirm to make a bill payment...";

    /** {@code READ-ACCTDAT-FILE}/{@code READ-CXACAIX-FILE}: {@code NOTFND}. */
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

    /** {@code WRITE-TRANSACT-FILE}: {@code DUPKEY}/{@code DUPREC}. */
    private static final String MSG_TRAN_ID_EXISTS = "Tran ID already exist...";

    /** {@code MAIN-PARA} {@code WHEN OTHER}: invalid AID key ({@code CCDA-MSG-INVALID-KEY}). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** {@code WRITE-TRANSACT-FILE} success line (note the two spaces after {@code "successful."}). */
    private static final String SUCCESS_MSG_PREFIX = "Payment successful.  Your Transaction ID is ";

    /** The first-ever transaction id (empty {@code TRANSACT}, COBOL {@code READPREV} end-of-file &rarr; 0, then +1). */
    private static final String FIRST_TRAN_ID = "0000000000000001";

    /** Session-scoped {@code COMMAREA} replacement, mocked so navigation writes are observable. */
    @Mock
    private CardDemoContext context;

    /** Account repository (VSAM {@code ACCTDAT}); mocked for {@code findByIdForUpdate}/{@code save}. */
    @Mock
    private AccountRepository accountRepository;

    /** Card cross-reference repository (VSAM {@code CCXREF}, alternate index {@code CXACAIX}); mocked. */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Transaction repository (VSAM {@code TRANSACT}); mocked for the browse-to-last-key and write. */
    @Mock
    private TransactionRepository transactionRepository;

    /** Service under test; Mockito constructor-injects the four mocked collaborators. */
    @InjectMocks
    private BillPayService service;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Builds a real {@link COBIL00Form} with the account-id and confirm input fields set.
     *
     * @param actidin the raw {@code ACTIDIN} value
     * @param confirm the raw {@code CONFIRM} value
     * @return a populated form
     */
    private static COBIL00Form form(String actidin, String confirm) {
        COBIL00Form form = new COBIL00Form();
        form.setActidin(actidin);
        form.setConfirm(confirm);
        return form;
    }

    /**
     * Builds a real {@link Account} fixture keyed by {@link #ACCT_ID} with the given current balance.
     *
     * @param balance the {@code ACCT-CURR-BAL} value
     * @return a populated account
     */
    private static Account accountWithBalance(BigDecimal balance) {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setCurrBal(balance);
        return account;
    }

    /**
     * Builds a real {@link CardXref} fixture linking {@link #CARD_NUM} to {@link #ACCT_ID}.
     *
     * @return a populated cross-reference record
     */
    private static CardXref cardXref() {
        return new CardXref(CARD_NUM, CUST_ID, ACCT_ID);
    }

    /**
     * An empty {@code TRANSACT} browse result (COBOL {@code READPREV} end-of-file), so the next id is one.
     *
     * @return an empty transaction page
     */
    private static Page<Transaction> noTransactions() {
        return new PageImpl<>(List.of());
    }

    /**
     * A single-record {@code TRANSACT} browse result holding the current highest-keyed transaction.
     *
     * @param highest the highest-keyed existing transaction (COBOL {@code READPREV} of the last record)
     * @return a one-element transaction page
     */
    private static Page<Transaction> highestTransaction(Transaction highest) {
        return new PageImpl<>(List.of(highest));
    }

    // ==================================================================
    // PROCESS-ENTER-KEY — validation branches (no data access)
    // ==================================================================

    /**
     * A blank (all-spaces) account id takes the COBOL {@code WHEN ACTIDINI = SPACES OR LOW-VALUES}
     * branch and returns {@code "Acct ID can NOT be empty..."} without touching any store.
     */
    @Test
    void processEnterKey_blankAccountId_returnsErrorAndReadsNothing() {
        BillPayResult result = service.processEnterKey(form("   ", "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_ACCT_EMPTY);
        assertThat(result.isRedirect()).isFalse();
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository, context);
    }

    /**
     * A confirm value that is neither {@code Y}/{@code y}, {@code N}/{@code n}, nor blank takes the
     * COBOL {@code EVALUATE CONFIRMI WHEN OTHER} branch and returns
     * {@code "Invalid value. Valid values are (Y/N)..."} before any read.
     */
    @Test
    void processEnterKey_invalidConfirmValue_returnsErrorAndReadsNothing() {
        BillPayResult result = service.processEnterKey(form(ACCT_ID_FIELD, "X"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_CONFIRM);
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository, context);
    }

    /**
     * Confirm {@code 'N'} takes the COBOL {@code WHEN 'N'} branch: {@code CLEAR-CURRENT-SCREEN}
     * ({@code INITIALIZE-ALL-FIELDS} then re-send) then the silent error flag &mdash; so the input
     * fields are cleared, no message is shown, and nothing is read.
     */
    @Test
    void processEnterKey_confirmNo_clearsScreenWithoutReading() {
        COBIL00Form form = form(ACCT_ID_FIELD, "N");

        BillPayResult result = service.processEnterKey(form);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.hasMessage()).isFalse();
        assertThat(form.getActidin()).isEmpty();
        assertThat(form.getConfirm()).isEmpty();
        assertThat(form.getCurbal()).isEmpty();
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository, context);
    }

    // ==================================================================
    // READ-ACCTDAT-FILE — account read
    // ==================================================================

    /**
     * READ-ACCTDAT-FILE {@code NOTFND}: a confirmed payment for an unknown account throws
     * {@link RecordNotFoundException} carrying {@code "Account ID NOT found..."} (FILE STATUS
     * {@code "23"}); the cross-reference, the transaction store, and the account rewrite are never
     * reached (checklist item 1).
     */
    @Test
    void processEnterKey_accountNotFound_throwsRecordNotFound() {
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.empty());

        COBIL00Form form = form(ACCT_ID_FIELD, "Y");

        assertThatThrownBy(() -> service.processEnterKey(form))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_ACCT_NOT_FOUND)
                .hasFieldOrPropertyWithValue("fileStatus", RecordNotFoundException.FILE_STATUS);

        verify(accountRepository).findByIdForUpdate(ACCT_ID);
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardXrefRepository, transactionRepository, context);
    }

    /**
     * Blank confirm reads the account (COBOL {@code WHEN SPACES/LOW-VALUES &rarr; READ-ACCTDAT-FILE})
     * but does not confirm, so the balance is echoed to the display field
     * ({@code MOVE ACCT-CURR-BAL TO WS-CURR-BAL TO CURBALI}, edit mask {@code +9999999999.99}) and
     * the neutral {@code "Confirm to make a bill payment..."} prompt is returned. No payment occurs.
     */
    @Test
    void processEnterKey_blankConfirm_readsAccountAndPromptsForConfirmation() {
        Account account = accountWithBalance(new BigDecimal("500.00"));
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));

        COBIL00Form form = form(ACCT_ID_FIELD, "");
        BillPayResult result = service.processEnterKey(form);

        assertThat(result.severity()).isEqualTo(MessageSeverity.NEUTRAL);
        assertThat(result.message()).isEqualTo(MSG_CONFIRM_PAYMENT);
        assertThat(result.isRedirect()).isFalse();
        assertThat(form.getCurbal()).isEqualTo("+0000000500.00");
        verify(accountRepository).findByIdForUpdate(ACCT_ID);
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardXrefRepository, transactionRepository, context);
    }

    // ==================================================================
    // "Nothing to pay" guard (ACCT-CURR-BAL <= ZEROS)
    // ==================================================================

    /**
     * A zero balance takes the COBOL {@code IF ACCT-CURR-BAL <= ZEROS} guard and returns
     * {@code "You have nothing to pay..."}; the account is read but neither a transaction is written
     * nor the account rewritten (checklist item 3).
     */
    @Test
    void processEnterKey_zeroBalance_returnsNothingToPayWithoutWriting() {
        Account account = accountWithBalance(new BigDecimal("0.00"));
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));

        BillPayResult result = service.processEnterKey(form(ACCT_ID_FIELD, "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_NOTHING_TO_PAY);
        verify(accountRepository).findByIdForUpdate(ACCT_ID);
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardXrefRepository, transactionRepository, context);
    }

    /**
     * A negative balance also satisfies {@code ACCT-CURR-BAL <= ZEROS} and yields the same
     * nothing-to-pay outcome (the boundary is inclusive and covers the {@code < 0} case).
     */
    @Test
    void processEnterKey_negativeBalance_returnsNothingToPay() {
        Account account = accountWithBalance(new BigDecimal("-25.00"));
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));

        BillPayResult result = service.processEnterKey(form(ACCT_ID_FIELD, "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_NOTHING_TO_PAY);
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(cardXrefRepository, transactionRepository, context);
    }

    // ==================================================================
    // Confirmed payment — the canonical read/write/update sequence
    // ==================================================================

    /**
     * <b>Canonical money assertion (checklist item 2).</b> A confirmed payment pays the
     * <em>entire</em> current balance (there is no partial-amount capability): the written
     * transaction amount equals the original balance, and after the COBOL
     * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} (no {@code ROUNDED}) the rewritten
     * balance is exactly {@code 0.00} at scale&nbsp;2, computed through the real
     * {@link CobolDecimal} subtraction. Both money values are {@link BigDecimal}, never
     * {@code float}/{@code double}.
     */
    @Test
    void processEnterKey_confirmedPayment_paysEntireBalanceDownToZero() {
        BigDecimal originalBalance = new BigDecimal("500.00");
        Account account = accountWithBalance(originalBalance);
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(noTransactions());

        BillPayResult result = service.processEnterKey(form(ACCT_ID_FIELD, "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.isRedirect()).isFalse();
        assertThat(result.message()).isEqualTo(SUCCESS_MSG_PREFIX + FIRST_TRAN_ID + ".");

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(txnCaptor.capture());
        Transaction written = txnCaptor.getValue();

        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(acctCaptor.capture());
        Account saved = acctCaptor.getValue();

        // TRAN-AMT == the original current balance (the entire balance is paid).
        assertThat(written.getTranAmt()).isInstanceOf(BigDecimal.class);
        assertThat(written.getTranAmt()).isEqualTo(originalBalance);
        assertThat(written.getTranAmt().scale()).isEqualTo(2);

        // New balance == ACCT-CURR-BAL - TRAN-AMT == 0.00 (scale 2), via the real CobolDecimal.
        BigDecimal expectedNewBalance = CobolDecimal.money(originalBalance.subtract(written.getTranAmt()));
        assertThat(expectedNewBalance).isEqualByComparingTo("0.00");
        assertThat(saved.getCurrBal()).isInstanceOf(BigDecimal.class);
        assertThat(saved.getCurrBal()).isEqualTo(expectedNewBalance);
        assertThat(saved.getCurrBal()).isEqualByComparingTo("0.00");
        assertThat(saved.getCurrBal().scale()).isEqualTo(2);

        // processEnterKey is context-free: the COMMAREA replacement is not touched here.
        verifyNoInteractions(context);
    }

    /**
     * <b>Generated-transaction constants (checklist items 4 and 6).</b> The written transaction is
     * tagged exactly as the COBOL confirmed branch tags it: type {@code "02"}, category {@code 2},
     * source {@code "POS TERM"}, description {@code "BILL PAYMENT - ONLINE"}, merchant id
     * {@code 999999999}, merchant name {@code "BILL PAYMENT"}, merchant city/zip {@code "N/A"}, the
     * card number resolved from the cross-reference alternate index, and the zero-padded next
     * transaction id.
     */
    @Test
    void processEnterKey_confirmedPayment_writesTransactionWithCobolConstants() {
        Account account = accountWithBalance(new BigDecimal("1234.56"));
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(noTransactions());

        service.processEnterKey(form(ACCT_ID_FIELD, "Y"));

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(txnCaptor.capture());
        Transaction t = txnCaptor.getValue();

        assertThat(t.getTranId()).isEqualTo(FIRST_TRAN_ID);
        assertThat(t.getTranTypeCd()).isEqualTo("02");
        assertThat(t.getTranCatCd()).isEqualTo(2);
        assertThat(t.getTranSource()).isEqualTo("POS TERM");
        assertThat(t.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
        assertThat(t.getMerchantId()).isEqualTo(999999999L);
        assertThat(t.getMerchantName()).isEqualTo("BILL PAYMENT");
        assertThat(t.getMerchantCity()).isEqualTo("N/A");
        assertThat(t.getMerchantZip()).isEqualTo("N/A");
        assertThat(t.getTranAmt()).isEqualByComparingTo("1234.56");

        // READ-CXACAIX-FILE resolved the owning card, which flows onto the transaction.
        assertThat(t.getCardNum()).isEqualTo(CARD_NUM);
        verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
    }

    /**
     * <b>26-character timestamp (checklist item 5).</b> {@code GET-CURRENT-TIMESTAMP} assembles a
     * {@code YYYY-MM-DD-HH.MM.SS.NNNNNN} timestamp with the fractional-seconds positions zero-filled
     * ({@code MOVE ZEROS TO WS-TIMESTAMP-TM-MS6}); the migrated {@link LocalDateTime} is therefore
     * truncated to whole seconds ({@code withNano(0)}) and the same value is moved to both
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}.
     */
    @Test
    void processEnterKey_confirmedPayment_setsWholeSecondOrigAndProcTimestamps() {
        Account account = accountWithBalance(new BigDecimal("10.00"));
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(noTransactions());

        LocalDateTime before = LocalDateTime.now().withNano(0);
        service.processEnterKey(form(ACCT_ID_FIELD, "Y"));
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(txnCaptor.capture());
        Transaction t = txnCaptor.getValue();

        assertThat(t.getOrigTs()).isNotNull();
        assertThat(t.getProcTs()).isNotNull();
        assertThat(t.getOrigTs()).isEqualTo(t.getProcTs());
        assertThat(t.getOrigTs().getNano()).isZero();
        assertThat(t.getOrigTs()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    /**
     * <b>Successful flow order (checklist item 7).</b> The confirmed payment performs, in order:
     * read the account ({@code READ-ACCTDAT-FILE}), resolve the card ({@code READ-CXACAIX-FILE}),
     * derive the next id (browse-to-last-key), <em>write the transaction</em>
     * ({@code WRITE-TRANSACT-FILE}), and only <em>then</em> rewrite the account with the zeroed
     * balance ({@code UPDATE-ACCTDAT-FILE}). The write-before-update ordering matches the COBOL.
     */
    @Test
    void processEnterKey_confirmedPayment_writesTransactionBeforeUpdatingAccount() {
        Account account = accountWithBalance(new BigDecimal("500.00"));
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(noTransactions());

        service.processEnterKey(form(ACCT_ID_FIELD, "Y"));

        InOrder inOrder = inOrder(accountRepository, cardXrefRepository, transactionRepository);
        inOrder.verify(accountRepository).findByIdForUpdate(ACCT_ID);
        inOrder.verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
        inOrder.verify(transactionRepository).findAll(any(Pageable.class));
        inOrder.verify(transactionRepository).saveAndFlush(any(Transaction.class));
        inOrder.verify(accountRepository).save(any(Account.class));
        inOrder.verifyNoMoreInteractions();
    }

    /**
     * READ-CXACAIX-FILE {@code NOTFND}: when no cross-reference exists for the account, the confirmed
     * payment throws {@link RecordNotFoundException} ({@code "Account ID NOT found..."}) before any
     * transaction is written or the account rewritten.
     */
    @Test
    void processEnterKey_confirmedPayment_crossReferenceNotFound_throwsRecordNotFound() {
        Account account = accountWithBalance(new BigDecimal("500.00"));
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

        COBIL00Form form = form(ACCT_ID_FIELD, "Y");

        assertThatThrownBy(() -> service.processEnterKey(form))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage(MSG_ACCT_NOT_FOUND);

        verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    /**
     * Next-transaction-id derivation: the browse-to-last-key triplet reads the highest existing id
     * and adds one (COBOL {@code MOVE TRAN-ID TO WS-TRAN-ID-NUM}; {@code ADD 1}). Given an existing
     * highest id of {@code ...0041}, the written transaction and success message carry {@code ...0042}.
     */
    @Test
    void processEnterKey_confirmedPayment_derivesNextTransactionIdFromHighestExisting() {
        Account account = accountWithBalance(new BigDecimal("500.00"));
        Transaction highest = new Transaction();
        highest.setTranId("0000000000000041");
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(highestTransaction(highest));

        BillPayResult result = service.processEnterKey(form(ACCT_ID_FIELD, "Y"));

        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getTranId()).isEqualTo("0000000000000042");
        assertThat(result.message()).isEqualTo(SUCCESS_MSG_PREFIX + "0000000000000042" + ".");
    }

    /**
     * <b>Duplicate key (checklist item 8).</b> A primary-key collision on the transaction write
     * (COBOL {@code DUPKEY}/{@code DUPREC}) surfaces the underlying Spring
     * {@link DataIntegrityViolationException} as the CardDemo
     * {@link com.aws.carddemo.exception.DuplicateKeyException} (FILE STATUS {@code "22"}, message
     * {@code "Tran ID already exist..."}) &mdash; not Spring's {@code DuplicateKeyException}. The
     * account is not rewritten after the failed write.
     */
    @Test
    void processEnterKey_confirmedPayment_duplicateTransactionId_throwsCardDemoDuplicateKey() {
        Account account = accountWithBalance(new BigDecimal("500.00"));
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(noTransactions());
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate transaction id"));

        COBIL00Form form = form(ACCT_ID_FIELD, "Y");

        assertThatThrownBy(() -> service.processEnterKey(form))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessage(MSG_TRAN_ID_EXISTS)
                .hasFieldOrPropertyWithValue("fileStatus", DuplicateKeyException.FILE_STATUS)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);

        verify(transactionRepository).saveAndFlush(any(Transaction.class));
        verify(accountRepository, never()).save(any(Account.class));
    }

    // ==================================================================
    // MAIN-PARA — pseudo-conversational state machine (first entry vs re-entry)
    // ==================================================================

    /**
     * <b>First entry (checklist item 9).</b> With no COMMAREA (COBOL {@code EIBCALEN = 0}, reproduced
     * by {@link CardDemoContext#isNew()}) the program bounces back to the sign-on screen
     * ({@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}; {@code RETURN-TO-PREV-SCREEN}): the result is a
     * redirect to {@code COSGN00C}, the from-program/from-tran-id are recorded, the context is reset
     * to enter, and no store is read.
     */
    @Test
    void mainEntry_firstEntryWithoutCommarea_redirectsToSignon() {
        when(context.isNew()).thenReturn(true);
        when(context.getToProgram()).thenReturn(SIGNON_PROGRAM);

        BillPayResult result = service.mainEntry(AidKey.ENTER, form(ACCT_ID_FIELD, "Y"));

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(SIGNON_PROGRAM);
        verify(context).setToProgram(SIGNON_PROGRAM);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).markEnter();
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
    }

    /**
     * <b>Re-entry with ENTER (checklist item 9).</b> With a COMMAREA present and the program already
     * re-entered ({@code isNew()} and {@code isProgramEnter()} both false), pressing ENTER
     * ({@code DFHENTER}) drives {@code PROCESS-ENTER-KEY} through the controller-facing entry point,
     * completing the payment: the balance is paid down to zero and the green success line is returned.
     */
    @Test
    void mainEntry_reentryWithEnter_processesPaymentToZeroBalance() {
        BigDecimal originalBalance = new BigDecimal("500.00");
        Account account = accountWithBalance(originalBalance);
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(cardXref()));
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(noTransactions());

        BillPayResult result = service.mainEntry(AidKey.ENTER, form(ACCT_ID_FIELD, "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.SUCCESS);
        assertThat(result.message()).isEqualTo(SUCCESS_MSG_PREFIX + FIRST_TRAN_ID + ".");

        ArgumentCaptor<Account> acctCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(acctCaptor.capture());
        assertThat(acctCaptor.getValue().getCurrBal()).isEqualByComparingTo("0.00");
        assertThat(acctCaptor.getValue().getCurrBal().scale()).isEqualTo(2);
        verify(transactionRepository).saveAndFlush(any(Transaction.class));
    }

    /**
     * <b>First program entry with a pre-selected account (checklist item 9).</b> On the first display
     * within the conversation ({@code IF NOT CDEMO-PGM-REENTER}) the program is marked re-entered and,
     * when an account has been pre-selected (COBOL {@code CDEMO-CB00-TRN-SELECTED}, mapped to
     * {@link CardDemoContext#getAcctId()}), that id is pre-loaded and {@code PROCESS-ENTER-KEY} runs
     * immediately. With a blank confirm this reads the account and returns the confirm prompt.
     */
    @Test
    void mainEntry_firstProgramEntryWithPreselectedAccount_readsAndPromptsForConfirmation() {
        when(context.isProgramEnter()).thenReturn(true);
        when(context.getAcctId()).thenReturn(ACCT_ID);
        Account account = accountWithBalance(new BigDecimal("500.00"));
        when(accountRepository.findByIdForUpdate(ACCT_ID)).thenReturn(Optional.of(account));

        BillPayResult result = service.mainEntry(AidKey.ENTER, new COBIL00Form());

        assertThat(result.severity()).isEqualTo(MessageSeverity.NEUTRAL);
        assertThat(result.message()).isEqualTo(MSG_CONFIRM_PAYMENT);
        verify(context).markReenter();
        verify(accountRepository).findByIdForUpdate(ACCT_ID);
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        verifyNoInteractions(cardXrefRepository);
    }

    /**
     * MAIN-PARA {@code DFHPF4}: pressing PF4 clears the current screen ({@code CLEAR-CURRENT-SCREEN}):
     * the input fields are reset and a message-free redisplay is returned, with no data access.
     */
    @Test
    void mainEntry_reentryWithPf4_clearsScreen() {
        COBIL00Form form = form(ACCT_ID_FIELD, "Y");

        BillPayResult result = service.mainEntry(AidKey.PF4, form);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.hasMessage()).isFalse();
        assertThat(form.getActidin()).isEmpty();
        assertThat(form.getConfirm()).isEmpty();
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
    }

    /**
     * MAIN-PARA {@code DFHPF3}: pressing PF3 returns to the previous screen. With no originating
     * program recorded (COBOL {@code CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES}) the target defaults
     * to the main menu ({@code COMEN01C}); the result is a redirect there and no store is read.
     */
    @Test
    void mainEntry_reentryWithPf3_redirectsToMainMenuWhenNoOriginRecorded() {
        when(context.getToProgram()).thenReturn(MENU_PROGRAM);

        BillPayResult result = service.mainEntry(AidKey.PF3, form(ACCT_ID_FIELD, "Y"));

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(MENU_PROGRAM);
        verify(context).setToProgram(MENU_PROGRAM);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).markEnter();
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
    }

    /**
     * MAIN-PARA {@code WHEN OTHER}: any unmapped AID key yields the invalid-key message
     * ({@code CCDA-MSG-INVALID-KEY}) with no data access.
     */
    @Test
    void mainEntry_reentryWithOtherKey_returnsInvalidKeyMessage() {
        BillPayResult result = service.mainEntry(AidKey.OTHER, form(ACCT_ID_FIELD, "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
    }

    /**
     * MAIN-PARA: a {@code null} AID collapses to the {@code WHEN OTHER} default, yielding the same
     * invalid-key message (defensive parity with the COBOL default branch).
     */
    @Test
    void mainEntry_reentryWithNullKey_collapsesToInvalidKeyMessage() {
        BillPayResult result = service.mainEntry(null, form(ACCT_ID_FIELD, "Y"));

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        verifyNoInteractions(accountRepository, cardXrefRepository, transactionRepository);
    }
}
