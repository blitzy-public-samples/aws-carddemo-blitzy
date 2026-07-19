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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.CardXref;
import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COTRN02Form;
import com.aws.carddemo.exception.DuplicateKeyException;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.service.online.TransactionAddService.MessageSeverity;
import com.aws.carddemo.service.online.TransactionAddService.ScreenAction;
import com.aws.carddemo.service.online.TransactionAddService.TransactionAddResult;
import com.aws.carddemo.util.CobolDecimal;
import com.aws.carddemo.util.DateConversionService;
import com.aws.carddemo.util.DateConversionService.DateValidationResult;
import com.aws.carddemo.util.DateConversionService.ValidationOutcome;
import com.aws.carddemo.util.constants.Messages;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Pure-Mockito unit tests for {@link TransactionAddService}.
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COTRN02C.cbl}
 * (CICS COBOL program {@code COTRN02C}, transaction id {@code CT02}) &mdash; the
 * online "Add Transaction" program. These tests assert one-for-one control-flow
 * parity with the numbered paragraphs of the oracle that the service migrates
 * (AAP &sect;0.1.2 / &sect;0.4.1 / &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link TransactionAddService#mainEntry} &mdash;
 *       the pseudo-conversational state machine ({@code EIBCALEN = 0} first entry,
 *       first program entry with/without a pre-selected card, and the
 *       {@code EVALUATE EIBAID} re-entry branches for {@code DFHENTER},
 *       {@code DFHPF3}, {@code DFHPF4}, {@code DFHPF5}, and {@code WHEN OTHER}).</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr;
 *       {@link TransactionAddService#processEnterKey} &mdash; the two validation
 *       passes followed by the {@code CONFIRM} {@code EVALUATE} (Y/N/blank/other).</li>
 *   <li>{@code VALIDATE-INPUT-KEY-FIELDS} &mdash; account/card key normalization,
 *       the mutually exclusive account-first {@code EVALUATE}, and the
 *       cross-reference resolution.</li>
 *   <li>{@code VALIDATE-INPUT-DATA-FIELDS} &mdash; the empty checks, numeric
 *       checks, the fixed {@code -99999999.99} amount layout, the
 *       {@code YYYY-MM-DD} date layouts, and the {@code CSUTLDTC} calendar
 *       validation.</li>
 *   <li>{@code READ-CXACAIX-FILE} / {@code READ-CCXREF-FILE} &mdash; the
 *       alternate-index cross-reference reads and their {@code NOTFND} handling.</li>
 *   <li>{@code ADD-TRANSACTION} / {@code WRITE-TRANSACT-FILE} &mdash; the
 *       next-transaction-id derivation ({@code MOVE HIGH-VALUES} browse + 1,
 *       zero-padded to 16), the field mapping, and the {@code DUPKEY}/{@code OTHER}
 *       write outcomes.</li>
 *   <li>{@code COPY-LAST-TRAN-DATA} &mdash; the PF5 convenience copy.</li>
 * </ul>
 *
 * <p><b>Test tier:</b> a strict-stubs pure-Mockito unit test
 * ({@link MockitoExtension}); it uses no database, no Spring context, and no
 * Testcontainers. Collaborators are mocked and the service is exercised in
 * isolation. The one exception is {@link CobolDecimal}, a stateless static helper
 * that is used <em>for real</em> (never mocked) so the {@link BigDecimal} scale-2
 * {@link java.math.RoundingMode#DOWN} money semantics are proven end-to-end.</p>
 *
 * <p><b>Self-invocation note:</b> {@link TransactionAddService#addTransaction} is
 * {@code @Transactional} and is invoked from
 * {@link TransactionAddService#processEnterKey} through the injected self
 * {@link ObjectProvider} so the transaction advice would be applied at runtime.
 * In these unit tests the {@code @Transactional} annotation is inert (no Spring
 * proxy), so the provider is simply stubbed to return the service under test,
 * exercising the real method.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionAddServiceTest {

    // -- Program identity (COBOL WS-PGMNAME / WS-TRANID and XCTL targets) --------------------------

    /** COBOL {@code WS-TRANID VALUE 'CT02'}; recorded as the origin tran id on a return/redirect. */
    private static final String TRANSACTION_ID = "CT02";

    /** COBOL {@code WS-PGMNAME VALUE 'COTRN02C'}; recorded as the origin program on a return/redirect. */
    private static final String PROGRAM_NAME = "COTRN02C";

    /** Sign-on program ({@code COSGN00C}); the {@code XCTL} target on first entry. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Main-menu program ({@code COMEN01C}); the PF3 target when {@code CDEMO-FROM-PROGRAM} is blank. */
    private static final String MENU_PROGRAM = "COMEN01C";

    // -- Message literals (byte-exact from legacy/cbl/COTRN02C.cbl) --------------------------------

    /** COBOL line 178: confirm-required prompt. */
    private static final String MSG_CONFIRM_PROMPT = "Confirm to add this transaction...";

    /** COBOL line 184: invalid CONFIRM value. */
    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

    /** COBOL line 199: account id entered but not numeric. */
    private static final String MSG_ACCT_NOT_NUMERIC = "Account ID must be Numeric...";

    /** COBOL line 213: card number entered but not numeric. */
    private static final String MSG_CARD_NOT_NUMERIC = "Card Number must be Numeric...";

    /** COBOL line 226: neither account id nor card number entered. */
    private static final String MSG_ACCT_OR_CARD_REQUIRED = "Account or Card Number must be entered...";

    /** COBOL line 254: transaction type code empty. */
    private static final String MSG_TYPE_EMPTY = "Type CD can NOT be empty...";

    /** COBOL line 278: amount empty. */
    private static final String MSG_AMT_EMPTY = "Amount can NOT be empty...";

    /** COBOL line 325: transaction type code not numeric. */
    private static final String MSG_TYPE_NOT_NUMERIC = "Type CD must be Numeric...";

    /** COBOL line 331: transaction category code not numeric. */
    private static final String MSG_CAT_NOT_NUMERIC = "Category CD must be Numeric...";

    /** COBOL line 345: amount not in the fixed {@code -99999999.99} layout. */
    private static final String MSG_AMT_FORMAT = "Amount should be in format -99999999.99";

    /** COBOL line 360: original date not in {@code YYYY-MM-DD} layout. */
    private static final String MSG_ORIG_FORMAT = "Orig Date should be in format YYYY-MM-DD";

    /** COBOL line 375: processing date not in {@code YYYY-MM-DD} layout. */
    private static final String MSG_PROC_FORMAT = "Proc Date should be in format YYYY-MM-DD";

    /** COBOL line 401: original date well-formed but not a valid calendar date. */
    private static final String MSG_ORIG_INVALID = "Orig Date - Not a valid date...";

    /** COBOL line 421: processing date well-formed but not a valid calendar date. */
    private static final String MSG_PROC_INVALID = "Proc Date - Not a valid date...";

    /** COBOL line 432: merchant id not numeric. */
    private static final String MSG_MID_NOT_NUMERIC = "Merchant ID must be Numeric...";

    /** COBOL line 593: account id not found in the {@code CXACAIX} cross-reference. */
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

    /** COBOL line 626: card number not found in the {@code CCXREF} cross-reference. */
    private static final String MSG_CARD_NOT_FOUND = "Card Number NOT found...";

    /** COBOL line 738: duplicate key on the {@code TRANSACT} {@code WRITE}. */
    private static final String MSG_DUPE = "Tran ID already exist...";

    /** COBOL line 745: unexpected error on the {@code TRANSACT} {@code WRITE}. */
    private static final String MSG_ADD_ERR = "Unable to Add Transaction...";

    /** The {@code CSUTLDTC} picture mask passed by the service ({@code WS-DATE-FORMAT}). */
    private static final String DATE_MASK = "YYYY-MM-DD";

    // -- Cursor field ids (BMS input ids; the COBOL MOVE -1 TO xxxxL target) -----------------------

    /** Account-id input cursor ({@code ACTIDINL}). */
    private static final String CURSOR_ACTIDIN = "actidin";

    /** Card-number input cursor ({@code CARDNINL}). */
    private static final String CURSOR_CARDNIN = "cardnin";

    /** Type-code input cursor ({@code TTYPCDL}). */
    private static final String CURSOR_TTYPCD = "ttypcd";

    /** Category-code input cursor ({@code TCATCDL}). */
    private static final String CURSOR_TCATCD = "tcatcd";

    /** Amount input cursor ({@code TRNAMTL}). */
    private static final String CURSOR_TRNAMT = "trnamt";

    /** Confirm input cursor ({@code CONFIRML}). */
    private static final String CURSOR_CONFIRM = "confirm";

    // -- Shared fixture values ---------------------------------------------------------------------

    /** Account id echoed zero-padded to {@code 9(11)}: the {@link #validForm()} account input. */
    private static final long ACCT_ID = 11L;

    /** Normalized {@code 9(11)} account-id echo produced by key-field validation. */
    private static final String ACCT_ID_PADDED = "00000000011";

    /** Card number (16 digits) resolved from the cross-reference for {@link #validForm()}. */
    private static final String CARD_NUM = "1234567890123456";

    // -- Mocked collaborators (constructor-injected into the service under test) -------------------

    /** Session-scoped {@code COMMAREA} replacement, mocked so navigation writes are observable. */
    @Mock
    private CardDemoContext context;

    /** Migrated {@code TRANSACT} KSDS; the max-key browse and the keyed {@code WRITE}. */
    @Mock
    private TransactionRepository transactionRepository;

    /** Migrated {@code CCXREF} / {@code CXACAIX} cross-reference (account&harr;card resolution). */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /** Migrated {@code ACCTDAT} handle; declared by {@code COTRN02C} but never exercised (no I/O). */
    @Mock
    private AccountRepository accountRepository;

    /** Migrated {@code CSUTLDTC} date validator ({@code CALL 'CSUTLDTC'}). */
    @Mock
    private DateConversionService dateConversionService;

    /** Self {@link ObjectProvider} yielding the transactional proxy (stubbed to the real service). */
    @Mock
    private ObjectProvider<TransactionAddService> selfProvider;

    /** Service under test; Mockito constructor-injects the six mocked collaborators above. */
    @InjectMocks
    private TransactionAddService service;

    // -- Fixtures ----------------------------------------------------------------------------------

    /**
     * Builds a fully valid add-transaction form: an account id that resolves through the
     * cross-reference, well-formed numeric codes, the fixed {@code +99999999.99} amount layout, two
     * distinct {@code YYYY-MM-DD} dates, and {@code CONFIRM = 'Y'}. Individual tests mutate exactly
     * one field to drive a specific validation branch.
     *
     * @return a populated, valid {@link COTRN02Form}
     */
    private static COTRN02Form validForm() {
        COTRN02Form form = new COTRN02Form();
        form.setActidin(ACCT_ID_PADDED);
        form.setCardnin(CARD_NUM);
        form.setTtypcd("01");
        form.setTcatcd("0005");
        form.setTrnsrc("POS");
        form.setTdesc("Test purchase");
        form.setTrnamt("+00000100.00");
        form.setTorigdt("2023-01-15");
        form.setTprocdt("2023-02-20");
        form.setMid("000000123");
        form.setMname("Test Merchant");
        form.setMcity("Seattle");
        form.setMzip("98101");
        form.setConfirm("Y");
        return form;
    }

    /**
     * Builds a cross-reference row linking a card, customer, and account.
     *
     * @param cardNum the 16-character card number
     * @param custId  the customer id
     * @param acctId  the account id
     * @return a populated {@link CardXref}
     */
    private static CardXref xref(String cardNum, long custId, long acctId) {
        return new CardXref(cardNum, Long.valueOf(custId), Long.valueOf(acctId));
    }

    /**
     * A valid {@link DateValidationResult} (the COBOL {@code CEE000}/severity-0 success), matching a
     * date that {@code CSUTLDTC} accepts.
     *
     * @return a severity-0 valid result
     */
    private static DateValidationResult validDate() {
        return new DateValidationResult(true, 0, 0, "Date is valid", "",
                OptionalLong.of(0L), ValidationOutcome.FC_INVALID_DATE);
    }

    /**
     * An invalid {@link DateValidationResult} (the COBOL {@code FC-BAD-DATE-VALUE}, severity 3, message
     * {@code 2508}), a well-formed but non-existent calendar date. The message number is deliberately
     * <em>not</em> the tolerated pre-1582 code {@code 2513}, so the service rejects it.
     *
     * @return a severity-3 invalid result
     */
    private static DateValidationResult invalidDate() {
        return new DateValidationResult(false, 3, 2508, "Datevalue error", "",
                OptionalLong.empty(), ValidationOutcome.FC_BAD_DATE_VALUE);
    }

    /**
     * Wraps a single transaction in a {@link org.springframework.data.domain.Page}, mirroring the
     * one-row descending page the service reads for the max transaction id
     * ({@code readLastTransaction}).
     *
     * @param transaction the highest-keyed transaction
     * @return a page whose sole element is {@code transaction}
     */
    private static PageImpl<Transaction> pageOf(Transaction transaction) {
        return new PageImpl<>(List.of(transaction));
    }

    /**
     * An empty transaction page, mirroring the COBOL {@code READPREV} {@code ENDFILE} branch (no rows
     * yet), which drives the next id to {@code 1}.
     *
     * @return an empty page
     */
    private static PageImpl<Transaction> emptyPage() {
        return new PageImpl<>(List.of());
    }

    /**
     * Builds a transaction record used as the "last" row for the PF5 copy and next-id derivation.
     *
     * @param tranId the transaction id to assign
     * @return a populated {@link Transaction}
     */
    private static Transaction sampleTransaction(String tranId) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranTypeCd("02");
        transaction.setTranCatCd(Integer.valueOf(7));
        transaction.setTranSource("ONLINE");
        transaction.setTranDesc("Prior purchase");
        transaction.setTranAmt(new BigDecimal("250.75"));
        transaction.setCardNum(CARD_NUM);
        transaction.setMerchantId(Long.valueOf(456L));
        transaction.setMerchantName("Prior Merchant");
        transaction.setMerchantCity("Portland");
        transaction.setMerchantZip("97201");
        transaction.setOrigTs(LocalDateTime.of(2022, 11, 5, 0, 0));
        transaction.setProcTs(LocalDateTime.of(2022, 11, 6, 0, 0));
        return transaction;
    }

    // =============================================================================================
    // mainEntry - MAIN-PARA pseudo-conversational state machine (COBOL lines 107-159)
    // =============================================================================================

    /**
     * {@code EIBCALEN = 0} (no COMMAREA): {@code MAIN-PARA} sets the sign-on program as the transfer
     * target and hands off to it (COBOL lines 116-118). The result is a redirect and the context
     * carries this program's identity as the caller.
     */
    @Test
    void mainEntry_firstEntryNoCommarea_redirectsToSignon() {
        when(context.isNew()).thenReturn(true);

        TransactionAddResult result = service.mainEntry(new COTRN02Form(), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(ScreenAction.REDIRECT);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        verify(context, atLeastOnce()).setToProgram(SIGNON_PROGRAM);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).markEnter();
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * First program entry ({@code NOT CDEMO-PGM-REENTER}) with no pre-selected card: {@code MAIN-PARA}
     * flips to re-enter and shows a fresh screen with the cursor on the account-id field (COBOL lines
     * 120-123).
     */
    @Test
    void mainEntry_firstProgramEntryNoSelectedCard_showsFreshScreen() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);
        when(context.getCardNum()).thenReturn(null);

        TransactionAddResult result = service.mainEntry(new COTRN02Form(), PfKey.ENTER);

        verify(context).markReenter();
        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.cursorField()).isEqualTo(CURSOR_ACTIDIN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.message()).isEmpty();
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * First program entry with a pre-selected card ({@code CDEMO-CT02-TRN-SELECTED}): {@code MAIN-PARA}
     * copies the selected card into the card-number input and runs {@code PROCESS-ENTER-KEY}
     * immediately (COBOL lines 124-131). Here the card resolves through the cross-reference and the
     * subsequent data validation stops at the (empty) type-code field, proving the enter path ran.
     */
    @Test
    void mainEntry_firstProgramEntryWithSelectedCard_runsEnterPath() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);
        when(context.getCardNum()).thenReturn(CARD_NUM);
        when(cardXrefRepository.findByXrefCardNum(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, 9L, 22L)));

        COTRN02Form form = new COTRN02Form();
        TransactionAddResult result = service.mainEntry(form, PfKey.ENTER);

        verify(context).markReenter();
        assertThat(form.getCardnin()).isEqualTo(CARD_NUM);
        assertThat(form.getActidin()).isEqualTo("00000000022");
        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.message()).isEqualTo(MSG_TYPE_EMPTY);
        assertThat(result.cursorField()).isEqualTo(CURSOR_TTYPCD);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * Re-entry with {@code ENTER}: {@code MAIN-PARA}'s {@code EVALUATE EIBAID} routes to
     * {@code PROCESS-ENTER-KEY} (COBOL lines 133-134). With an empty form the delegated validation
     * reports that an account or card is required, proving the delegation.
     */
    @Test
    void mainEntry_reentryEnterKey_delegatesToProcessEnterKey() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        TransactionAddResult result = service.mainEntry(new COTRN02Form(), PfKey.ENTER);

        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_ACCT_OR_CARD_REQUIRED);
        assertThat(result.cursorField()).isEqualTo(CURSOR_ACTIDIN);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * Re-entry with {@code PF3} and a non-blank {@code CDEMO-FROM-PROGRAM}: {@code MAIN-PARA} transfers
     * back to that caller (COBOL lines 138-144). The transfer target recorded on the context is the
     * calling program.
     */
    @Test
    void mainEntry_reentryPf3WithFromProgram_redirectsToCaller() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(context.getFromProgram()).thenReturn(MENU_PROGRAM);

        TransactionAddResult result = service.mainEntry(new COTRN02Form(), PfKey.PFK03);

        assertThat(result.action()).isEqualTo(ScreenAction.REDIRECT);
        verify(context).setToProgram(MENU_PROGRAM);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).markEnter();
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * Re-entry with {@code PF3} and a blank {@code CDEMO-FROM-PROGRAM}: {@code MAIN-PARA} defaults the
     * transfer target to the main menu {@code COMEN01C} (COBOL lines 138-144).
     */
    @Test
    void mainEntry_reentryPf3BlankFromProgram_redirectsToMenu() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        TransactionAddResult result = service.mainEntry(new COTRN02Form(), PfKey.PFK03);

        assertThat(result.action()).isEqualTo(ScreenAction.REDIRECT);
        verify(context).setToProgram(MENU_PROGRAM);
        verify(context).markEnter();
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * Re-entry with {@code PF4}: {@code MAIN-PARA} clears the current screen (COBOL lines 145-146).
     * Every input and the message line are blanked and the cursor returns to the account-id field.
     */
    @Test
    void mainEntry_reentryPf4_clearsScreen() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        COTRN02Form form = validForm();
        TransactionAddResult result = service.mainEntry(form, PfKey.PFK04);

        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.cursorField()).isEqualTo(CURSOR_ACTIDIN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(form.getActidin()).isEmpty();
        assertThat(form.getCardnin()).isEmpty();
        assertThat(form.getTrnamt()).isEmpty();
        assertThat(form.getConfirm()).isEmpty();
        assertThat(form.getErrmsg()).isEmpty();
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * Re-entry with {@code PF5}: {@code MAIN-PARA} copies the last transaction onto the screen (COBOL
     * lines 147-148). The copied business fields (rendered through the COBOL edit masks) appear on the
     * form, proving the delegation to {@code COPY-LAST-TRAN-DATA}.
     */
    @Test
    void mainEntry_reentryPf5_copiesLastTransaction() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(sampleTransaction("0000000000000009")));
        when(dateConversionService.validateDate("2022-11-05", DATE_MASK)).thenReturn(validDate());
        when(dateConversionService.validateDate("2022-11-06", DATE_MASK)).thenReturn(validDate());

        COTRN02Form form = new COTRN02Form();
        form.setActidin(ACCT_ID_PADDED);
        TransactionAddResult result = service.mainEntry(form, PfKey.PFK05);

        assertThat(form.getTtypcd()).isEqualTo("02");
        assertThat(form.getTrnamt()).isEqualTo("+00000250.75");
        assertThat(form.getTdesc()).isEqualTo("Prior purchase");
        assertThat(result.message()).isEqualTo(MSG_CONFIRM_PROMPT);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * Re-entry with any other attention key: {@code MAIN-PARA}'s {@code WHEN OTHER} reports the invalid
     * key message (COBOL lines 149-154).
     */
    @Test
    void mainEntry_reentryOtherKey_returnsInvalidKeyMessage() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        TransactionAddResult result = service.mainEntry(validForm(), PfKey.PFK12);

        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(Messages.CCDA_MSG_INVALID_KEY);
        assertThat(result.cursorField()).isEmpty();
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * A {@code null} attention id is treated as the {@code WHEN OTHER} branch (defensive mapping of an
     * unresolved {@code EIBAID}), yielding the same invalid-key message.
     */
    @Test
    void mainEntry_reentryNullKey_treatedAsOtherKey() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        TransactionAddResult result = service.mainEntry(validForm(), null);

        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.message()).isEqualTo(Messages.CCDA_MSG_INVALID_KEY);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    // =============================================================================================
    // VALIDATE-INPUT-KEY-FIELDS + READ-CXACAIX-FILE + READ-CCXREF-FILE (COBOL lines 193-231, 573-635)
    // =============================================================================================

    /**
     * Neither the account id nor the card number is supplied: {@code VALIDATE-INPUT-KEY-FIELDS}'
     * {@code WHEN OTHER} reports that an account or card must be entered (COBOL lines 225-229). No
     * cross-reference read and no write occur.
     */
    @Test
    void processEnterKey_neitherAccountNorCard_returnsRequiredMessage() {
        TransactionAddResult result = service.processEnterKey(new COTRN02Form());

        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_ACCT_OR_CARD_REQUIRED);
        assertThat(result.cursorField()).isEqualTo(CURSOR_ACTIDIN);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * A non-numeric account id (checked first, ahead of the card): {@code VALIDATE-INPUT-KEY-FIELDS}
     * reports the account-numeric error (COBOL lines 198-201). The card is never consulted.
     */
    @Test
    void processEnterKey_accountNotNumeric_returnsAccountNumericMessage() {
        COTRN02Form form = validForm();
        form.setActidin("ABC123");

        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_ACCT_NOT_NUMERIC);
        assertThat(result.cursorField()).isEqualTo(CURSOR_ACTIDIN);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * A non-numeric card number (account blank): {@code VALIDATE-INPUT-KEY-FIELDS} reports the
     * card-numeric error (COBOL lines 212-215).
     */
    @Test
    void processEnterKey_cardNotNumeric_returnsCardNumericMessage() {
        COTRN02Form form = validForm();
        form.setActidin(null);
        form.setCardnin("12AB");

        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_CARD_NOT_NUMERIC);
        assertThat(result.cursorField()).isEqualTo(CURSOR_CARDNIN);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * An account id resolves through {@code READ-CXACAIX-FILE}: the account is echoed back zero-padded
     * to {@code 9(11)} and the lowest-sorting card among the cross-reference matches is loaded into the
     * card-number input (COBOL lines 205-209; AAP &sect;0.6.2/&sect;0.6.6 deterministic collation). The
     * flow then advances into data validation, proving the key fields passed.
     */
    @Test
    void processEnterKey_accountResolvesToLowestCard_echoesNormalizedKeys() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID))
                .thenReturn(List.of(xref("2222222222222222", 9L, ACCT_ID), xref("1111111111111111", 8L, ACCT_ID)));

        COTRN02Form form = new COTRN02Form();
        form.setActidin("11");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(form.getActidin()).isEqualTo(ACCT_ID_PADDED);
        assertThat(form.getCardnin()).isEqualTo("1111111111111111");
        assertThat(result.message()).isEqualTo(MSG_TYPE_EMPTY);
        verify(cardXrefRepository).findByXrefAcctId(ACCT_ID);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * A card number resolves through {@code READ-CCXREF-FILE}: the card is echoed back zero-padded to
     * {@code 9(16)} and the cross-referenced account id is loaded into the account-id input (COBOL
     * lines 220-224).
     */
    @Test
    void processEnterKey_cardResolvesToAccount_echoesNormalizedKeys() {
        when(cardXrefRepository.findByXrefCardNum(CARD_NUM)).thenReturn(Optional.of(xref(CARD_NUM, 9L, 22L)));

        COTRN02Form form = new COTRN02Form();
        form.setCardnin(CARD_NUM);
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(form.getActidin()).isEqualTo("00000000022");
        assertThat(form.getCardnin()).isEqualTo(CARD_NUM);
        assertThat(result.message()).isEqualTo(MSG_TYPE_EMPTY);
        verify(cardXrefRepository).findByXrefCardNum(CARD_NUM);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * {@code READ-CXACAIX-FILE} finds no cross-reference for the account (the CICS {@code NOTFND}
     * branch): the account-not-found message is surfaced (COBOL lines 587-596).
     */
    @Test
    void processEnterKey_accountNotFound_returnsAccountNotFound() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of());

        COTRN02Form form = new COTRN02Form();
        form.setActidin(ACCT_ID_PADDED);
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_ACCT_NOT_FOUND);
        assertThat(result.cursorField()).isEqualTo(CURSOR_ACTIDIN);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * {@code READ-CCXREF-FILE} finds no cross-reference for the card (the CICS {@code NOTFND} branch):
     * the card-not-found message is surfaced (COBOL lines 620-629).
     */
    @Test
    void processEnterKey_cardNotFound_returnsCardNotFound() {
        when(cardXrefRepository.findByXrefCardNum(CARD_NUM)).thenReturn(Optional.empty());

        COTRN02Form form = new COTRN02Form();
        form.setCardnin(CARD_NUM);
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_CARD_NOT_FOUND);
        assertThat(result.cursorField()).isEqualTo(CURSOR_CARDNIN);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    // =============================================================================================
    // VALIDATE-INPUT-DATA-FIELDS - empties, numerics, amount layout, dates (COBOL lines 236-435)
    // =============================================================================================

    /**
     * The type code is empty: {@code VALIDATE-INPUT-DATA-FIELDS} reports it first among the empty
     * checks (COBOL lines 253-256).
     */
    @Test
    void processEnterKey_typeCodeEmpty_returnsTypeEmpty() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));

        COTRN02Form form = validForm();
        form.setTtypcd("");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_TYPE_EMPTY);
        assertThat(result.cursorField()).isEqualTo(CURSOR_TTYPCD);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * The amount is empty: {@code VALIDATE-INPUT-DATA-FIELDS} reports the amount-empty error once the
     * preceding fields are present (COBOL lines 277-280).
     */
    @Test
    void processEnterKey_amountEmpty_returnsAmountEmpty() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));

        COTRN02Form form = validForm();
        form.setTrnamt("");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_AMT_EMPTY);
        assertThat(result.cursorField()).isEqualTo(CURSOR_TRNAMT);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * A non-numeric type code (present but not digits): {@code VALIDATE-INPUT-DATA-FIELDS} reports the
     * type-numeric error (COBOL lines 322-325).
     */
    @Test
    void processEnterKey_typeCodeNotNumeric_returnsTypeNotNumeric() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));

        COTRN02Form form = validForm();
        form.setTtypcd("AB");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_TYPE_NOT_NUMERIC);
        assertThat(result.cursorField()).isEqualTo(CURSOR_TTYPCD);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * A non-numeric category code: {@code VALIDATE-INPUT-DATA-FIELDS} reports the category-numeric
     * error (COBOL lines 328-331).
     */
    @Test
    void processEnterKey_categoryCodeNotNumeric_returnsCategoryNotNumeric() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));

        COTRN02Form form = validForm();
        form.setTcatcd("XX");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_CAT_NOT_NUMERIC);
        assertThat(result.cursorField()).isEqualTo(CURSOR_TCATCD);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * The amount is not in the fixed 12-byte {@code -99999999.99} layout: {@code
     * VALIDATE-INPUT-DATA-FIELDS} reports the amount-format error (COBOL lines 336-345). A free-form
     * "1234.5" has no leading sign in the fixed field and is rejected.
     */
    @Test
    void processEnterKey_amountBadFormat_returnsAmountFormat() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));

        COTRN02Form form = validForm();
        form.setTrnamt("1234.5");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_AMT_FORMAT);
        assertThat(result.cursorField()).isEqualTo(CURSOR_TRNAMT);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * The original date is not in the fixed {@code YYYY-MM-DD} layout: {@code
     * VALIDATE-INPUT-DATA-FIELDS} reports the original-date-format error before any calendar
     * validation (COBOL lines 353-360). {@link DateConversionService} is therefore not consulted.
     */
    @Test
    void processEnterKey_origDateBadFormat_returnsOrigFormat() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));

        COTRN02Form form = validForm();
        form.setTorigdt("2023/01/15");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_ORIG_FORMAT);
        assertThat(result.cursorField()).isEqualTo("torigdt");
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * The processing date is not in the fixed {@code YYYY-MM-DD} layout: {@code
     * VALIDATE-INPUT-DATA-FIELDS} reports the processing-date-format error (COBOL lines 368-375),
     * again before any calendar validation.
     */
    @Test
    void processEnterKey_procDateBadFormat_returnsProcFormat() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));

        COTRN02Form form = validForm();
        form.setTprocdt("20230220");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_PROC_FORMAT);
        assertThat(result.cursorField()).isEqualTo("tprocdt");
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * The original date is well-formed but not a valid calendar date: the service delegates to
     * {@link DateConversionService} (the {@code CALL 'CSUTLDTC'}) and, on a non-zero severity, reports
     * the original-date-invalid error (COBOL lines 388-407). The service does not re-implement date
     * parsing - the invalid verdict comes entirely from the mocked date service.
     */
    @Test
    void processEnterKey_origDateInvalidCalendar_returnsOrigInvalid() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(dateConversionService.validateDate("2023-01-15", DATE_MASK)).thenReturn(invalidDate());

        TransactionAddResult result = service.processEnterKey(validForm());

        assertThat(result.message()).isEqualTo(MSG_ORIG_INVALID);
        assertThat(result.cursorField()).isEqualTo("torigdt");
        verify(dateConversionService).validateDate("2023-01-15", DATE_MASK);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * The processing date is well-formed but not a valid calendar date: the original date validates
     * successfully and the processing date's non-zero severity yields the processing-date-invalid
     * error (COBOL lines 409-428).
     */
    @Test
    void processEnterKey_procDateInvalidCalendar_returnsProcInvalid() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(dateConversionService.validateDate("2023-01-15", DATE_MASK)).thenReturn(validDate());
        when(dateConversionService.validateDate("2023-02-20", DATE_MASK)).thenReturn(invalidDate());

        TransactionAddResult result = service.processEnterKey(validForm());

        assertThat(result.message()).isEqualTo(MSG_PROC_INVALID);
        assertThat(result.cursorField()).isEqualTo("tprocdt");
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * A non-numeric merchant id (the final data check, after both dates validate): {@code
     * VALIDATE-INPUT-DATA-FIELDS} reports the merchant-id-numeric error (COBOL lines 430-434).
     */
    @Test
    void processEnterKey_merchantIdNotNumeric_returnsMerchantIdNumeric() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(dateConversionService.validateDate("2023-01-15", DATE_MASK)).thenReturn(validDate());
        when(dateConversionService.validateDate("2023-02-20", DATE_MASK)).thenReturn(validDate());

        COTRN02Form form = validForm();
        form.setMid("12A45");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_MID_NOT_NUMERIC);
        assertThat(result.cursorField()).isEqualTo("mid");
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    // =============================================================================================
    // PROCESS-ENTER-KEY - the CONFIRM Y/N gate (COBOL lines 168-188)
    // =============================================================================================

    /**
     * All fields valid but {@code CONFIRM} blank: {@code PROCESS-ENTER-KEY} prompts for confirmation
     * and does not write (COBOL lines 173-181).
     */
    @Test
    void processEnterKey_confirmBlank_returnsConfirmPrompt() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(dateConversionService.validateDate("2023-01-15", DATE_MASK)).thenReturn(validDate());
        when(dateConversionService.validateDate("2023-02-20", DATE_MASK)).thenReturn(validDate());

        COTRN02Form form = validForm();
        form.setConfirm("");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_CONFIRM_PROMPT);
        assertThat(result.cursorField()).isEqualTo(CURSOR_CONFIRM);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * All fields valid and {@code CONFIRM = 'N'}: {@code PROCESS-ENTER-KEY} prompts for confirmation
     * and does not write (COBOL lines 173-181).
     */
    @Test
    void processEnterKey_confirmN_returnsConfirmPrompt() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(dateConversionService.validateDate("2023-01-15", DATE_MASK)).thenReturn(validDate());
        when(dateConversionService.validateDate("2023-02-20", DATE_MASK)).thenReturn(validDate());

        COTRN02Form form = validForm();
        form.setConfirm("N");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.message()).isEqualTo(MSG_CONFIRM_PROMPT);
        assertThat(result.cursorField()).isEqualTo(CURSOR_CONFIRM);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * All fields valid but {@code CONFIRM} is neither Y/y nor N/n/blank: {@code PROCESS-ENTER-KEY}'s
     * {@code WHEN OTHER} reports the invalid-value error (COBOL lines 182-187).
     */
    @Test
    void processEnterKey_confirmInvalidValue_returnsInvalidConfirm() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(dateConversionService.validateDate("2023-01-15", DATE_MASK)).thenReturn(validDate());
        when(dateConversionService.validateDate("2023-02-20", DATE_MASK)).thenReturn(validDate());

        COTRN02Form form = validForm();
        form.setConfirm("X");
        TransactionAddResult result = service.processEnterKey(form);

        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_INVALID_CONFIRM);
        assertThat(result.cursorField()).isEqualTo(CURSOR_CONFIRM);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * All fields valid and {@code CONFIRM = 'Y'}: {@code PROCESS-ENTER-KEY} invokes the transactional
     * add through the self proxy, which writes the record and returns the green success message (COBOL
     * lines 169-172). The derived id is the last id (9) plus one, zero-padded to 16, the amount is a
     * scale-2 {@link BigDecimal}, and the screen is cleared on success.
     */
    @Test
    void processEnterKey_confirmY_writesTransactionAndReturnsSuccess() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(dateConversionService.validateDate("2023-01-15", DATE_MASK)).thenReturn(validDate());
        when(dateConversionService.validateDate("2023-02-20", DATE_MASK)).thenReturn(validDate());
        when(selfProvider.getObject()).thenReturn(service);
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(sampleTransaction("0000000000000009")));

        COTRN02Form form = validForm();
        TransactionAddResult result = service.processEnterKey(form);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(captor.capture());
        Transaction written = captor.getValue();

        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.INFORMATION);
        assertThat(result.message())
                .isEqualTo("Transaction added successfully.  Your Tran ID is 0000000000000010.");
        assertThat(written.getTranId()).isEqualTo("0000000000000010");
        assertThat(written.getTranAmt()).isInstanceOf(BigDecimal.class);
        assertThat(written.getTranAmt().scale()).isEqualTo(2);
        assertThat(written.getTranAmt()).isEqualByComparingTo("100.00");
        // On success INITIALIZE-ALL-FIELDS clears the screen (COBOL lines 726-735).
        assertThat(form.getActidin()).isEmpty();
        assertThat(form.getConfirm()).isEmpty();
    }

    // =============================================================================================
    // WRITE-TRANSACT-FILE outcomes via PROCESS-ENTER-KEY (COBOL lines 711-749, caught lines 168-188)
    // =============================================================================================

    /**
     * The keyed write hits a duplicate: {@code WRITE-TRANSACT-FILE}'s {@code WHEN DFHRESP(DUPKEY)} /
     * {@code DUPREC} path (COBOL lines 736-742). The {@code saveAndFlush} raises a
     * {@link DataIntegrityViolationException} which the service translates to a
     * {@link DuplicateKeyException}; {@code PROCESS-ENTER-KEY} catches it - outside the transaction
     * boundary - and reports the duplicate message.
     */
    @Test
    void processEnterKey_confirmY_duplicateKey_returnsDuplicateMessage() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(dateConversionService.validateDate("2023-01-15", DATE_MASK)).thenReturn(validDate());
        when(dateConversionService.validateDate("2023-02-20", DATE_MASK)).thenReturn(validDate());
        when(selfProvider.getObject()).thenReturn(service);
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(sampleTransaction("0000000000000009")));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        TransactionAddResult result = service.processEnterKey(validForm());

        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_DUPE);
        assertThat(result.cursorField()).isEqualTo(CURSOR_ACTIDIN);
    }

    /**
     * The keyed write fails for a reason other than a duplicate: {@code WRITE-TRANSACT-FILE}'s
     * {@code WHEN OTHER} path (COBOL lines 743-748). Any other {@link org.springframework.dao.
     * DataAccessException} propagates out of the transaction and {@code PROCESS-ENTER-KEY} reports the
     * generic add-error message.
     */
    @Test
    void processEnterKey_confirmY_dataAccessError_returnsAddErrorMessage() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(dateConversionService.validateDate("2023-01-15", DATE_MASK)).thenReturn(validDate());
        when(dateConversionService.validateDate("2023-02-20", DATE_MASK)).thenReturn(validDate());
        when(selfProvider.getObject()).thenReturn(service);
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(sampleTransaction("0000000000000009")));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        TransactionAddResult result = service.processEnterKey(validForm());

        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.ERROR);
        assertThat(result.message()).isEqualTo(MSG_ADD_ERR);
        assertThat(result.cursorField()).isEqualTo(CURSOR_ACTIDIN);
    }

    // =============================================================================================
    // ADD-TRANSACTION - next-id derivation + field mapping + duplicate key (COBOL lines 443-466)
    // =============================================================================================

    /**
     * An empty transaction file (the COBOL {@code READPREV} {@code ENDFILE} branch): {@code
     * ADD-TRANSACTION} derives the first id {@code 0000000000000001} (COBOL lines 447-451).
     */
    @Test
    void addTransaction_emptyTransactionFile_derivesFirstId() {
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());

        TransactionAddResult result = service.addTransaction(validForm());

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTranId()).isEqualTo("0000000000000001");
        assertThat(result.severity()).isEqualTo(MessageSeverity.INFORMATION);
        assertThat(result.message()).contains("0000000000000001");
    }

    /**
     * A populated file: {@code ADD-TRANSACTION} derives the next id as the current maximum plus one,
     * zero-padded to 16 characters (COBOL lines 447-451). Last id {@code 9} yields
     * {@code 0000000000000010}.
     */
    @Test
    void addTransaction_existingMaxId_derivesNextIdZeroPaddedTo16() {
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(sampleTransaction("0000000000000009")));

        service.addTransaction(validForm());

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTranId()).isEqualTo("0000000000000010");
        assertThat(captor.getValue().getTranId()).hasSize(16);
    }

    /**
     * {@code ADD-TRANSACTION} maps every validated form field onto the persisted record with the COBOL
     * type conversions (COBOL lines 452-465): the category code becomes an {@link Integer}, the
     * merchant id a {@link Long}, the dates {@link LocalDateTime}s at start-of-day, and the amount a
     * scale-2 {@link BigDecimal} (never {@code float}/{@code double}).
     */
    @Test
    void addTransaction_success_capturesAllPersistedFields() {
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(sampleTransaction("0000000000000041")));

        TransactionAddResult result = service.addTransaction(validForm());

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(captor.capture());
        Transaction written = captor.getValue();

        assertThat(written.getTranId()).isEqualTo("0000000000000042");
        assertThat(written.getTranTypeCd()).isEqualTo("01");
        assertThat(written.getTranCatCd()).isEqualTo(5);
        assertThat(written.getTranSource()).isEqualTo("POS");
        assertThat(written.getTranDesc()).isEqualTo("Test purchase");
        assertThat(written.getCardNum()).isEqualTo(CARD_NUM);
        assertThat(written.getMerchantId()).isEqualTo(123L);
        assertThat(written.getMerchantName()).isEqualTo("Test Merchant");
        assertThat(written.getMerchantCity()).isEqualTo("Seattle");
        assertThat(written.getMerchantZip()).isEqualTo("98101");
        assertThat(written.getOrigTs()).isEqualTo(LocalDateTime.of(2023, 1, 15, 0, 0));
        assertThat(written.getProcTs()).isEqualTo(LocalDateTime.of(2023, 2, 20, 0, 0));
        assertThat(written.getTranAmt()).isInstanceOf(BigDecimal.class);
        assertThat(written.getTranAmt().scale()).isEqualTo(2);
        assertThat(written.getTranAmt()).isEqualByComparingTo("100.00");
        assertThat(result.severity()).isEqualTo(MessageSeverity.INFORMATION);
    }

    /**
     * {@code ADD-TRANSACTION} propagates a duplicate key from {@code WRITE-TRANSACT-FILE} as CardDemo's
     * own {@link DuplicateKeyException} (file status {@code 22}), wrapping the underlying
     * {@link DataIntegrityViolationException} - not Spring's {@code org.springframework.dao.
     * DuplicateKeyException} (COBOL lines 736-742).
     */
    @Test
    void addTransaction_duplicateKey_throwsDuplicateKeyException() {
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(sampleTransaction("0000000000000009")));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        COTRN02Form form = validForm();
        assertThatThrownBy(() -> service.addTransaction(form))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessage(MSG_DUPE)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    // =============================================================================================
    // COPY-LAST-TRAN-DATA (PF5) + CLEAR-CURRENT-SCREEN (PF4) + CobolDecimal money semantics
    // =============================================================================================

    /**
     * {@code COPY-LAST-TRAN-DATA} runs {@code VALIDATE-INPUT-KEY-FIELDS} first (COBOL line 473); when
     * the key fields are invalid it returns the key error and never browses for the last transaction.
     */
    @Test
    void copyLastTranData_keyFieldsInvalid_returnsErrorWithoutReadingLast() {
        TransactionAddResult result = service.copyLastTranData(new COTRN02Form());

        assertThat(result.message()).isEqualTo(MSG_ACCT_OR_CARD_REQUIRED);
        verify(transactionRepository, never()).findAll(any(Pageable.class));
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * {@code COPY-LAST-TRAN-DATA} copies the global last transaction's business fields onto the screen
     * - rendered through the COBOL edit masks (amount {@code +99999999.99}, zero-padded category and
     * merchant id, {@code YYYY-MM-DD} dates) - deliberately leaving the card number as set by key-field
     * validation (COBOL lines 481-492), then re-runs {@code PROCESS-ENTER-KEY} which prompts for
     * confirmation because {@code CONFIRM} is still blank (COBOL line 495). Nothing is written.
     */
    @Test
    void copyLastTranData_success_copiesFieldsThenPromptsConfirm() {
        when(cardXrefRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref(CARD_NUM, 9L, ACCT_ID)));
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(pageOf(sampleTransaction("0000000000000009")));
        when(dateConversionService.validateDate("2022-11-05", DATE_MASK)).thenReturn(validDate());
        when(dateConversionService.validateDate("2022-11-06", DATE_MASK)).thenReturn(validDate());

        COTRN02Form form = new COTRN02Form();
        form.setActidin(ACCT_ID_PADDED);
        TransactionAddResult result = service.copyLastTranData(form);

        assertThat(form.getTtypcd()).isEqualTo("02");
        assertThat(form.getTcatcd()).isEqualTo("0007");
        assertThat(form.getTrnsrc()).isEqualTo("ONLINE");
        assertThat(form.getTrnamt()).isEqualTo("+00000250.75");
        assertThat(form.getTdesc()).isEqualTo("Prior purchase");
        assertThat(form.getTorigdt()).isEqualTo("2022-11-05");
        assertThat(form.getTprocdt()).isEqualTo("2022-11-06");
        assertThat(form.getMid()).isEqualTo("000000456");
        assertThat(form.getMname()).isEqualTo("Prior Merchant");
        assertThat(form.getMcity()).isEqualTo("Portland");
        assertThat(form.getMzip()).isEqualTo("97201");
        // The card number is intentionally not copied - it stays as set by key-field validation.
        assertThat(form.getCardnin()).isEqualTo(CARD_NUM);
        assertThat(result.message()).isEqualTo(MSG_CONFIRM_PROMPT);
        assertThat(result.cursorField()).isEqualTo(CURSOR_CONFIRM);
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    /**
     * {@code CLEAR-CURRENT-SCREEN} (PF4) blanks every input and the message line and positions the
     * cursor on the account-id field (COBOL lines 753-778).
     */
    @Test
    void clearCurrentScreen_blanksAllFieldsAndPositionsCursor() {
        COTRN02Form form = validForm();

        TransactionAddResult result = service.clearCurrentScreen(form);

        assertThat(result.action()).isEqualTo(ScreenAction.SHOW_SCREEN);
        assertThat(result.cursorField()).isEqualTo(CURSOR_ACTIDIN);
        assertThat(result.severity()).isEqualTo(MessageSeverity.NONE);
        assertThat(result.message()).isEmpty();
        assertThat(form.getActidin()).isEmpty();
        assertThat(form.getCardnin()).isEmpty();
        assertThat(form.getTtypcd()).isEmpty();
        assertThat(form.getTcatcd()).isEmpty();
        assertThat(form.getTrnsrc()).isEmpty();
        assertThat(form.getTdesc()).isEmpty();
        assertThat(form.getTrnamt()).isEmpty();
        assertThat(form.getTorigdt()).isEmpty();
        assertThat(form.getTprocdt()).isEmpty();
        assertThat(form.getMid()).isEmpty();
        assertThat(form.getMname()).isEmpty();
        assertThat(form.getMcity()).isEmpty();
        assertThat(form.getMzip()).isEmpty();
        assertThat(form.getConfirm()).isEmpty();
        assertThat(form.getErrmsg()).isEmpty();
    }

    /**
     * The amount uses the <em>real</em> {@link CobolDecimal} (never mocked): money is truncated toward
     * zero to scale 2 ({@link java.math.RoundingMode#DOWN}), matching the COBOL {@code S9(9)V99} field
     * with no {@code ROUNDED} clause (AAP &sect;0.6.1). Truncation, not rounding, is proven: both
     * {@code 12.999} and {@code -12.999} drop the third decimal rather than rounding away from zero.
     */
    @Test
    void cobolDecimal_moneyTruncatesToScale2Down_positiveAndNegative() {
        BigDecimal positive = CobolDecimal.money(new BigDecimal("12.999"));
        BigDecimal negative = CobolDecimal.money(new BigDecimal("-12.999"));

        assertThat(positive).isInstanceOf(BigDecimal.class);
        assertThat(positive.scale()).isEqualTo(2);
        assertThat(positive).isEqualByComparingTo("12.99");
        assertThat(negative.scale()).isEqualTo(2);
        assertThat(negative).isEqualByComparingTo("-12.99");
        assertThat(CobolDecimal.money(new BigDecimal("100")).scale()).isEqualTo(2);
    }
}
