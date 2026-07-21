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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COTRN01Form;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.service.online.TransactionViewService.AidKey;
import com.aws.carddemo.service.online.TransactionViewService.TransactionViewResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Pure-Mockito unit tests for {@link TransactionViewService}.
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COTRN01C.cbl}
 * (CICS COBOL program {@code COTRN01C}, transaction id {@code CT01}) &mdash; the
 * "View a Transaction from TRANSACT file" single-record view screen. These tests
 * assert one-for-one control-flow parity with the numbered paragraphs of the
 * oracle that the service migrates (AAP &sect;0.1.2 / &sect;0.4.1 / &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link TransactionViewService#mainEntry} &mdash;
 *       the pseudo-conversational state machine ({@code EIBCALEN = 0} first entry,
 *       first program entry with an optional pre-loaded selection, and the
 *       {@code EVALUATE EIBAID} re-entry branches {@code DFHENTER}, {@code DFHPF3},
 *       {@code DFHPF4}, {@code DFHPF5}, and {@code WHEN OTHER}).</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr;
 *       {@link TransactionViewService#processEnterKey} &mdash; empty-id rejection,
 *       display-field clearing (preserving the search input), the keyed read, and
 *       the exact message literals.</li>
 *   <li>{@code READ-TRANSACT-FILE} &rarr;
 *       {@link TransactionViewService#readTransactFile} &mdash; the keyed
 *       {@code findById} read, the {@code NOTFND} &rarr; {@link RecordNotFoundException}
 *       mapping, and the {@code WHEN OTHER} unexpected-failure propagation.</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} &rarr;
 *       {@link TransactionViewService#clearCurrentScreen} &mdash; the PF4 blank-and-show.</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr;
 *       {@link TransactionViewService#initializeAllFields} &mdash; the full field reset.</li>
 * </ul>
 *
 * <p><b>Read-only guarantee (the defining assertion, AAP &sect;0.7.1):</b> although
 * the COBOL {@code READ-TRANSACT-FILE} issues {@code EXEC CICS READ ... UPDATE},
 * {@code COTRN01C} never rewrites the record. The migrated service is therefore a
 * pure read: {@link #viewFlow_neverInvokesSaveOrDelete()} proves that no
 * {@code save}/{@code delete} operation is ever issued against the repository, and
 * the individual read tests confirm {@code findById} is the only repository
 * interaction.</p>
 *
 * <p><b>Test tier:</b> a strict-stubs pure-Mockito unit test
 * ({@link MockitoExtension}); it uses no database, no Spring context, and no
 * Testcontainers. Collaborators are mocked and the service is exercised in
 * isolation. Routing outcomes (the COBOL {@code XCTL}) are asserted on the mocked
 * {@link CardDemoContext} (the {@code COMMAREA} replacement) together with the
 * returned {@link TransactionViewResult}, because the service records the routing
 * target and defers the actual redirect/render to the paired controller.</p>
 *
 * <p><b>Faithfulness note (checklist item 5):</b> on {@code DFHPF5} the oracle
 * performs {@code MOVE 'COTRN00C' TO CDEMO-TO-PROGRAM} then
 * {@code RETURN-TO-PREV-SCREEN} ({@code legacy/cbl/COTRN01C.cbl} lines 125-127,
 * 197-208). {@code RETURN-TO-PREV-SCREEN} sets only {@code CDEMO-FROM-TRANID},
 * {@code CDEMO-FROM-PROGRAM}, and {@code CDEMO-PGM-CONTEXT} &mdash; it never sets
 * {@code CDEMO-TO-TRANID}. The PF5 test therefore asserts
 * {@code setToProgram("COTRN00C")} and that {@code setToTranid} is never invoked,
 * testing the real service contract rather than a to-tranid that the oracle does
 * not set.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionViewServiceTest {

    /** COBOL {@code WS-PGMNAME} of {@code COTRN01C}; recorded as the origin program on routing. */
    private static final String PROGRAM_NAME = "COTRN01C";

    /** COBOL {@code WS-TRANID} of {@code COTRN01C}; recorded as the origin transaction id on routing. */
    private static final String TRANSACTION_ID = "CT01";

    /** Sign-on program ({@code COSGN00C}); the {@code XCTL} target on first entry ({@code EIBCALEN = 0}). */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Regular-user main-menu program ({@code COMEN01C}); the PF3 target when the caller is unset. */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /** Transaction-list program ({@code COTRN00C}); the PF5 target (return to the list). */
    private static final String TRANSACTION_LIST_PROGRAM = "COTRN00C";

    /** Exact oracle literal ({@code legacy/cbl/COTRN01C.cbl} line 149) for a blank transaction id. */
    private static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /** Exact oracle literal ({@code legacy/cbl/COTRN01C.cbl} line 285) for a not-found transaction. */
    private static final String MSG_TRAN_NOT_FOUND = "Transaction ID NOT found...";

    /** Exact oracle literal ({@code legacy/cbl/COTRN01C.cbl} line 292) for an unexpected read failure. */
    private static final String MSG_UNABLE_LOOKUP = "Unable to lookup Transaction...";

    /** Mirror of COBOL {@code CCDA-MSG-INVALID-KEY} ({@code WHEN OTHER} branch of {@code MAIN-PARA}). */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Canonical not-found FILE STATUS carried by {@link RecordNotFoundException}. */
    private static final String RECORD_NOT_FOUND_STATUS = "23";

    /** A representative 16-character transaction id ({@code TRAN-ID PIC X(16)}). */
    private static final String TRAN_ID = "0000000000000123";

    /** Session-scoped {@code COMMAREA} replacement, mocked so routing writes are observable. */
    @Mock
    private CardDemoContext context;

    /** {@code TRANSACT} VSAM replacement, mocked so the keyed read can be stubbed and writes forbidden. */
    @Mock
    private TransactionRepository transactionRepository;

    /** Service under test; Mockito constructor-injects the two mocked collaborators. */
    @InjectMocks
    private TransactionViewService service;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Builds a real, fully-populated {@link Transaction} fixture (positive amount).
     *
     * @return a transaction whose fields exercise every display formatter
     */
    private static Transaction sampleTransaction() {
        Transaction transaction = new Transaction();
        transaction.setTranId(TRAN_ID);
        transaction.setTranTypeCd("01");
        transaction.setTranCatCd(5);
        transaction.setTranSource("POS");
        transaction.setTranDesc("GROCERY STORE PURCHASE");
        transaction.setTranAmt(new BigDecimal("123.45"));
        transaction.setMerchantId(42L);
        transaction.setMerchantName("ACME GROCERY");
        transaction.setMerchantCity("SEATTLE");
        transaction.setMerchantZip("98101");
        transaction.setCardNum("4111111111111111");
        transaction.setOrigTs(LocalDateTime.of(2023, 3, 15, 10, 20, 30));
        transaction.setProcTs(LocalDateTime.of(2023, 3, 16, 8, 5, 0));
        return transaction;
    }

    /**
     * Builds a {@link COTRN01Form} with only the transaction-id search input set,
     * simulating the {@code TRNIDINI} value bound by {@code RECEIVE-TRNVIEW-SCREEN}.
     *
     * @param trnidin the raw {@code TRNIDINI} value to place on the form
     * @return a form with the search input populated
     */
    private static COTRN01Form formWithTrnidin(String trnidin) {
        COTRN01Form form = new COTRN01Form();
        form.setTrnidin(trnidin);
        return form;
    }

    /**
     * Builds a {@link COTRN01Form} with every value field pre-populated with a
     * distinctive marker, so a clear/reset can be observed field-by-field.
     *
     * @return a fully-populated form
     */
    private static COTRN01Form fullyPopulatedForm() {
        COTRN01Form form = new COTRN01Form();
        // Header fields (POPULATE-HEADER-INFO): NOT touched by INITIALIZE-ALL-FIELDS.
        form.setTrnname("CT01");
        form.setTitle01("TITLE ONE");
        form.setCurdate("01/02/23");
        form.setPgmname("COTRN01C");
        form.setTitle02("TITLE TWO");
        form.setCurtime("10:20:30");
        // Search input + the thirteen display fields + the error line.
        form.setTrnidin(TRAN_ID);
        form.setTrnid(TRAN_ID);
        form.setCardnum("4111111111111111");
        form.setTtypcd("01");
        form.setTcatcd("0005");
        form.setTrnsrc("POS");
        form.setTdesc("STALE DESCRIPTION");
        form.setTrnamt("+00000123.45");
        form.setTorigdt("2023-03-15");
        form.setTprocdt("2023-03-16");
        form.setMid("000000042");
        form.setMname("ACME GROCERY");
        form.setMcity("SEATTLE");
        form.setMzip("98101");
        form.setErrmsg("previous error");
        return form;
    }

    /**
     * Asserts that all thirteen transaction display fields on the form are blank,
     * reproducing the COBOL {@code MOVE SPACES} display-field reset.
     *
     * @param form the form whose display fields must be blank
     */
    private static void assertDisplayFieldsBlank(COTRN01Form form) {
        assertThat(form.getTrnid()).isEmpty();
        assertThat(form.getCardnum()).isEmpty();
        assertThat(form.getTtypcd()).isEmpty();
        assertThat(form.getTcatcd()).isEmpty();
        assertThat(form.getTrnsrc()).isEmpty();
        assertThat(form.getTrnamt()).isEmpty();
        assertThat(form.getTdesc()).isEmpty();
        assertThat(form.getTorigdt()).isEmpty();
        assertThat(form.getTprocdt()).isEmpty();
        assertThat(form.getMid()).isEmpty();
        assertThat(form.getMname()).isEmpty();
        assertThat(form.getMcity()).isEmpty();
        assertThat(form.getMzip()).isEmpty();
    }

    /**
     * Asserts that the form's thirteen display fields carry the exact values the
     * COBOL {@code PROCESS-ENTER-KEY} {@code MOVE} block produces from
     * {@link #sampleTransaction()} (widths, zero-padding, amount edit picture, and
     * timestamp date truncation all honoured).
     *
     * @param form the form populated from the sample transaction
     */
    private static void assertSampleTransactionDisplayed(COTRN01Form form) {
        assertThat(form.getTrnid()).isEqualTo(TRAN_ID);
        assertThat(form.getCardnum()).isEqualTo("4111111111111111");
        assertThat(form.getTtypcd()).isEqualTo("01");
        assertThat(form.getTcatcd()).isEqualTo("0005");
        assertThat(form.getTrnsrc()).isEqualTo("POS");
        assertThat(form.getTrnamt()).isEqualTo("+00000123.45");
        assertThat(form.getTdesc()).isEqualTo("GROCERY STORE PURCHASE");
        assertThat(form.getTorigdt()).isEqualTo("2023-03-15");
        assertThat(form.getTprocdt()).isEqualTo("2023-03-16");
        assertThat(form.getMid()).isEqualTo("000000042");
        assertThat(form.getMname()).isEqualTo("ACME GROCERY");
        assertThat(form.getMcity()).isEqualTo("SEATTLE");
        assertThat(form.getMzip()).isEqualTo("98101");
    }

    // ------------------------------------------------------------------
    // MAIN-PARA (state machine + EVALUATE EIBAID) - mainEntry
    // ------------------------------------------------------------------

    /**
     * First entry with no COMMAREA (COBOL {@code IF EIBCALEN = 0}, lines 94-96;
     * {@link CardDemoContext#isNew()}): the view cannot run without a signed-on
     * session, so {@code MAIN-PARA} sets the sign-on program as the hand-off target
     * and performs {@code RETURN-TO-PREV-SCREEN}, yielding a redirect to
     * {@code COSGN00C}. The origin program/tran id are recorded and the program
     * context is reset to enter ({@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}).
     */
    @Test
    void mainEntry_firstEntryNoCommareaRedirectsToSignon() {
        when(context.isNew()).thenReturn(true);
        when(context.getToProgram()).thenReturn(SIGNON_PROGRAM);

        TransactionViewResult result = service.mainEntry(new COTRN01Form(), AidKey.ENTER, null);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(SIGNON_PROGRAM);
        assertThat(result.error()).isFalse();
        verify(context).setToProgram(SIGNON_PROGRAM);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).markEnter();
        verifyNoInteractions(transactionRepository);
    }

    /**
     * First program entry (COBOL {@code IF NOT CDEMO-PGM-REENTER}, lines 99-109) with
     * a transaction id handed over from the list screen
     * ({@code CDEMO-CT01-TRN-SELECTED} not blank): the service flips to re-enter,
     * pre-loads the selection into {@code TRNIDINI}, and immediately performs
     * {@code PROCESS-ENTER-KEY}, so the record's fields populate the view form
     * (checklist items 1 and 7). No routing occurs.
     */
    @Test
    void mainEntry_firstProgramEntryWithSelectedTranIdViewsRecord() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(sampleTransaction()));
        COTRN01Form form = new COTRN01Form();

        TransactionViewResult result = service.mainEntry(form, AidKey.ENTER, TRAN_ID);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.error()).isFalse();
        assertThat(form.getTrnidin()).isEqualTo(TRAN_ID);
        assertSampleTransactionDisplayed(form);
        verify(context).markReenter();
        verify(transactionRepository).findById(TRAN_ID);
        verifyNoMoreInteractions(transactionRepository);
    }

    /**
     * First program entry with no pre-loaded selection ({@code CDEMO-CT01-TRN-SELECTED}
     * blank, lines 99-109): the service flips to re-enter and shows a fresh, empty
     * screen with no message and performs no read (checklist item 7, first entry
     * initializes empty).
     */
    @Test
    void mainEntry_firstProgramEntryWithoutSelectionShowsFreshScreen() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);
        COTRN01Form form = new COTRN01Form();

        TransactionViewResult result = service.mainEntry(form, AidKey.ENTER, null);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.error()).isFalse();
        assertThat(result.hasMessage()).isFalse();
        verify(context).markReenter();
        verifyNoInteractions(transactionRepository);
    }

    /**
     * On re-entry (lines 111-114), {@code ENTER} ({@code DFHENTER}) delegates to
     * {@code PROCESS-ENTER-KEY}; a valid typed transaction id is read and its record
     * populates the view form. Exercises the {@code MAIN-PARA} {@code EVALUATE EIBAID}
     * ENTER branch end-to-end (checklist item 6, direct id entry).
     */
    @Test
    void mainEntry_reentryEnterDelegatesToProcessEnterKey() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(sampleTransaction()));
        COTRN01Form form = formWithTrnidin(TRAN_ID);

        TransactionViewResult result = service.mainEntry(form, AidKey.ENTER, null);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.error()).isFalse();
        assertSampleTransactionDisplayed(form);
        verify(transactionRepository).findById(TRAN_ID);
        verifyNoMoreInteractions(transactionRepository);
        verify(context, never()).setToProgram(anyString());
    }

    /**
     * On re-entry, {@code PF3} ({@code DFHPF3}, lines 115-122) with an unset caller
     * ({@code CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES}) routes to the regular-user
     * main menu {@code COMEN01C}, recording the origin and resetting the program
     * context to enter.
     */
    @Test
    void mainEntry_reentryPf3WithoutCallerRoutesToMainMenu() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(context.getToProgram()).thenReturn(MAIN_MENU_PROGRAM);

        TransactionViewResult result = service.mainEntry(new COTRN01Form(), AidKey.PFK03, null);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(MAIN_MENU_PROGRAM);
        verify(context).setToProgram(MAIN_MENU_PROGRAM);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).markEnter();
        verifyNoInteractions(transactionRepository);
    }

    /**
     * On re-entry, {@code PF3} with a known caller ({@code CDEMO-FROM-PROGRAM} set)
     * returns to that caller ({@code MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM},
     * lines 118-120) rather than the main menu.
     */
    @Test
    void mainEntry_reentryPf3WithCallerReturnsToCaller() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(context.getFromProgram()).thenReturn("COACTVWC");
        when(context.getToProgram()).thenReturn("COACTVWC");

        TransactionViewResult result = service.mainEntry(new COTRN01Form(), AidKey.PFK03, null);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo("COACTVWC");
        verify(context).setToProgram("COACTVWC");
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).markEnter();
        verifyNoInteractions(transactionRepository);
    }

    /**
     * On re-entry, {@code PF4} ({@code DFHPF4}, lines 123-124) performs
     * {@code CLEAR-CURRENT-SCREEN}: the search input, all display fields, and the
     * error line are blanked and a fresh screen is shown with no routing
     * (checklist item 4).
     */
    @Test
    void mainEntry_reentryPf4ClearsScreen() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        COTRN01Form form = fullyPopulatedForm();

        TransactionViewResult result = service.mainEntry(form, AidKey.PFK04, null);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.error()).isFalse();
        assertThat(form.getTrnidin()).isEmpty();
        assertDisplayFieldsBlank(form);
        assertThat(form.getErrmsg()).isEmpty();
        verify(context, never()).setToProgram(anyString());
        verifyNoInteractions(transactionRepository);
    }

    /**
     * On re-entry, {@code PF5} ({@code DFHPF5}, lines 125-127) returns to the
     * transaction-list program {@code COTRN00C} (checklist item 5). Per
     * {@code RETURN-TO-PREV-SCREEN} (lines 197-208), only the origin program/tran id
     * and the program context are set; {@code CDEMO-TO-TRANID} is never set, so this
     * asserts {@code setToProgram("COTRN00C")} and that {@code setToTranid} is never
     * invoked (see the class-level faithfulness note).
     */
    @Test
    void mainEntry_reentryPf5ReturnsToTransactionList() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(context.getToProgram()).thenReturn(TRANSACTION_LIST_PROGRAM);

        TransactionViewResult result = service.mainEntry(new COTRN01Form(), AidKey.PFK05, null);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(TRANSACTION_LIST_PROGRAM);
        verify(context).setToProgram(TRANSACTION_LIST_PROGRAM);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).markEnter();
        verify(context, never()).setToTranid(anyString());
        verifyNoInteractions(transactionRepository);
    }

    /**
     * On re-entry, any other key takes the COBOL {@code WHEN OTHER} branch (lines
     * 128-131): the invalid-key error is shown and no routing occurs.
     */
    @Test
    void mainEntry_reentryOtherKeyShowsInvalidKey() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        TransactionViewResult result = service.mainEntry(new COTRN01Form(), AidKey.OTHER, null);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        verify(context, never()).setToProgram(anyString());
        verifyNoInteractions(transactionRepository);
    }

    /**
     * A {@code null} attention id is mapped to the {@code WHEN OTHER} branch (the
     * service coalesces a missing key to {@link AidKey#OTHER}), yielding the
     * invalid-key error rather than throwing.
     */
    @Test
    void mainEntry_reentryNullKeyTreatedAsInvalidKey() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        TransactionViewResult result = service.mainEntry(new COTRN01Form(), null, null);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        verify(context, never()).setToProgram(anyString());
    }

    // ------------------------------------------------------------------
    // PROCESS-ENTER-KEY - processEnterKey
    // ------------------------------------------------------------------

    /**
     * {@code PROCESS-ENTER-KEY} with a blank {@code TRNIDINI} (COBOL
     * {@code WHEN TRNIDINI = SPACES OR LOW-VALUES}, lines 146-152): returns the
     * empty-id error and reads nothing. {@code PROCESS-ENTER-KEY} never touches the
     * COMMAREA, so the context is left untouched.
     */
    @Test
    void processEnterKey_emptyTranIdReturnsEmptyMessage() {
        COTRN01Form form = formWithTrnidin("    ");

        TransactionViewResult result = service.processEnterKey(form, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_TRAN_ID_EMPTY);
        assertThat(result.isRedirect()).isFalse();
        verifyNoInteractions(context);
        verifyNoInteractions(transactionRepository);
    }

    /**
     * {@code PROCESS-ENTER-KEY} with a valid id (lines 158-192): the record is read
     * and its thirteen display fields populate the form with the exact BMS widths,
     * zero-padding, amount edit picture ({@code +99999999.99}), and {@code yyyy-MM-dd}
     * timestamp truncation (checklist item 1). Only {@code findById} is called on the
     * repository, and the COMMAREA is untouched.
     */
    @Test
    void processEnterKey_validIdPopulatesAllDisplayFields() {
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(sampleTransaction()));
        COTRN01Form form = formWithTrnidin(TRAN_ID);

        TransactionViewResult result = service.processEnterKey(form, context);

        assertThat(result.error()).isFalse();
        assertThat(result.isRedirect()).isFalse();
        assertThat(form.getTrnidin()).isEqualTo(TRAN_ID);
        assertSampleTransactionDisplayed(form);
        verify(transactionRepository).findById(TRAN_ID);
        verifyNoMoreInteractions(transactionRepository);
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-ENTER-KEY} honours the BMS field widths when a source field is
     * wider than its map field (COBOL {@code MOVE} truncation): {@code TRAN-DESC
     * PIC X(100)} into {@code TDESCI PIC X(60)} keeps sixty characters, and
     * {@code TRAN-MERCHANT-NAME PIC X(50)} into {@code MNAMEI PIC X(30)} keeps thirty
     * (AAP UI field-length preservation).
     */
    @Test
    void processEnterKey_truncatesWideFieldsToBmsWidths() {
        Transaction wide = sampleTransaction();
        wide.setTranDesc("D".repeat(70));
        wide.setMerchantName("M".repeat(40));
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(wide));
        COTRN01Form form = formWithTrnidin(TRAN_ID);

        service.processEnterKey(form, context);

        assertThat(form.getTdesc()).hasSize(60).isEqualTo("D".repeat(60));
        assertThat(form.getMname()).hasSize(30).isEqualTo("M".repeat(30));
        verify(transactionRepository).findById(TRAN_ID);
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-ENTER-KEY} with a valid id that is not on file: the CICS
     * {@code NOTFND} branch of {@code READ-TRANSACT-FILE} (lines 283-287) moves
     * "Transaction ID NOT found..." to {@code WS-MESSAGE}, sets {@code ERR-FLG-ON}, and
     * re-displays the SAME screen inline (it is not an abend). The service therefore
     * catches the {@link RecordNotFoundException} and returns an error
     * {@link TransactionViewService.TransactionViewResult} carrying that literal (checklist
     * item 3; AAP &sect;0.6.5 exception parity), rather than letting it escape to the
     * full-page handler. No write is issued.
     */
    @Test
    void processEnterKey_notFoundReDisplaysInlineError() {
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.empty());
        COTRN01Form form = formWithTrnidin(TRAN_ID);

        TransactionViewService.TransactionViewResult result = service.processEnterKey(form, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_TRAN_NOT_FOUND);

        verify(transactionRepository).findById(TRAN_ID);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(transactionRepository, never()).delete(any(Transaction.class));
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-ENTER-KEY} with an unexpected repository failure: the CICS
     * {@code WHEN OTHER} branch of {@code READ-TRANSACT-FILE} (lines 289-295) is
     * reproduced by catching a {@link org.springframework.dao.DataAccessException}
     * and returning the "Unable to lookup Transaction..." error. The display fields
     * were cleared before the read (COBOL {@code MOVE SPACES} block, lines 159-171)
     * while the search input {@code TRNIDINI} is preserved.
     */
    @Test
    void processEnterKey_unexpectedDataAccessReturnsUnableToLookup() {
        when(transactionRepository.findById(TRAN_ID))
                .thenThrow(new DataAccessResourceFailureException("simulated read failure"));
        COTRN01Form form = fullyPopulatedForm();

        TransactionViewResult result = service.processEnterKey(form, context);

        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_UNABLE_LOOKUP);
        assertThat(result.isRedirect()).isFalse();
        assertThat(form.getTrnidin()).isEqualTo(TRAN_ID);
        assertDisplayFieldsBlank(form);
        verify(transactionRepository).findById(TRAN_ID);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verifyNoInteractions(context);
    }

    /**
     * The amount edit picture {@code +99999999.99} renders a negative
     * {@link BigDecimal} with a leading minus and eight zero-padded integer digits:
     * {@code -50.00} becomes {@code "-00000050.00"} (AAP &sect;0.6.1 decimal fidelity;
     * the money display is a String, never floating point).
     */
    @Test
    void processEnterKey_negativeAmountFormatsWithMinusSign() {
        Transaction negative = sampleTransaction();
        negative.setTranAmt(new BigDecimal("-50.00"));
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(negative));
        COTRN01Form form = formWithTrnidin(TRAN_ID);

        service.processEnterKey(form, context);

        assertThat(form.getTrnamt()).isEqualTo("-00000050.00");
        verify(transactionRepository).findById(TRAN_ID);
        verifyNoInteractions(context);
    }

    // ------------------------------------------------------------------
    // READ-TRANSACT-FILE - readTransactFile
    // ------------------------------------------------------------------

    /**
     * {@code READ-TRANSACT-FILE} on the CICS {@code NORMAL} response (lines 280-282):
     * the keyed {@code findById} returns the record, which {@code readTransactFile}
     * returns unchanged. The authoritative amount is a {@link BigDecimal} (never
     * floating point).
     */
    @Test
    void readTransactFile_returnsRecordWhenFound() {
        Transaction expected = sampleTransaction();
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(expected));

        Transaction actual = service.readTransactFile(TRAN_ID);

        assertThat(actual).isSameAs(expected);
        assertThat(actual.getTranAmt()).isInstanceOf(BigDecimal.class);
        verify(transactionRepository).findById(TRAN_ID);
        verifyNoMoreInteractions(transactionRepository);
        verifyNoInteractions(context);
    }

    /**
     * {@code READ-TRANSACT-FILE} on the CICS {@code NOTFND} response (lines 283-287):
     * an empty {@code findById} result is mapped to a {@link RecordNotFoundException}
     * carrying "Transaction ID NOT found..." and the canonical FILE STATUS {@code "23"}
     * (checklist item 3). No write is issued.
     */
    @Test
    void readTransactFile_throwsRecordNotFoundWhenAbsent() {
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> service.readTransactFile(TRAN_ID))
                .withMessage(MSG_TRAN_NOT_FOUND)
                .satisfies(ex -> assertThat(ex.getFileStatus()).isEqualTo(RECORD_NOT_FOUND_STATUS));

        verify(transactionRepository).findById(TRAN_ID);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verifyNoInteractions(context);
    }

    // ------------------------------------------------------------------
    // CLEAR-CURRENT-SCREEN - clearCurrentScreen
    // ------------------------------------------------------------------

    /**
     * {@code CLEAR-CURRENT-SCREEN} (lines 301-304, the PF4 handler): performs
     * {@code INITIALIZE-ALL-FIELDS} then shows the screen. The search input, the
     * thirteen display fields, and the error line are blanked; the header fields
     * (populated separately by {@code POPULATE-HEADER-INFO}) are left intact. No
     * repository or context interaction occurs (checklist item 4).
     */
    @Test
    void clearCurrentScreen_blanksScreenAndShows() {
        COTRN01Form form = fullyPopulatedForm();

        TransactionViewResult result = service.clearCurrentScreen(form);

        assertThat(result.isRedirect()).isFalse();
        assertThat(result.error()).isFalse();
        assertThat(result.hasMessage()).isFalse();
        assertThat(form.getTrnidin()).isEmpty();
        assertDisplayFieldsBlank(form);
        assertThat(form.getErrmsg()).isEmpty();
        // Header fields are not part of INITIALIZE-ALL-FIELDS and remain untouched.
        assertThat(form.getTrnname()).isEqualTo("CT01");
        assertThat(form.getPgmname()).isEqualTo("COTRN01C");
        verifyNoInteractions(context, transactionRepository);
    }

    // ------------------------------------------------------------------
    // INITIALIZE-ALL-FIELDS - initializeAllFields
    // ------------------------------------------------------------------

    /**
     * {@code INITIALIZE-ALL-FIELDS} (lines 309-326): blanks the search input
     * {@code TRNIDINI}, all thirteen display fields, and the message line
     * ({@code WS-MESSAGE} &rarr; {@code errmsg}). It does not render or route.
     */
    @Test
    void initializeAllFields_resetsInputDisplayAndMessage() {
        COTRN01Form form = fullyPopulatedForm();

        service.initializeAllFields(form);

        assertThat(form.getTrnidin()).isEmpty();
        assertDisplayFieldsBlank(form);
        assertThat(form.getErrmsg()).isEmpty();
        verifyNoInteractions(context, transactionRepository);
    }

    // ------------------------------------------------------------------
    // Read-only guarantee + money rule (checklist item 2; Section 7)
    // ------------------------------------------------------------------

    /**
     * Read-only guarantee (checklist item 2, the defining assertion for this service):
     * although the COBOL {@code READ-TRANSACT-FILE} issues {@code EXEC CICS READ ...
     * UPDATE}, {@code COTRN01C} never rewrites the record. After a full successful
     * view, the ONLY repository interaction is {@code findById}; no {@code save},
     * {@code saveAndFlush}, {@code delete}, {@code deleteById}, or {@code deleteAll}
     * is ever issued.
     */
    @Test
    void viewFlow_neverInvokesSaveOrDelete() {
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(sampleTransaction()));
        COTRN01Form form = formWithTrnidin(TRAN_ID);

        service.processEnterKey(form, context);

        verify(transactionRepository).findById(TRAN_ID);
        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        verify(transactionRepository, never()).delete(any(Transaction.class));
        verify(transactionRepository, never()).deleteById(anyString());
        verify(transactionRepository, never()).deleteAll();
        verifyNoMoreInteractions(transactionRepository);
    }

    /**
     * Money rule (AAP &sect;0.6.1 / Section 7): the authoritative transaction amount is
     * a {@link BigDecimal}, never a binary floating-point type. Asserted structurally
     * on the {@link Transaction#getTranAmt()} return type and on the fixture value.
     *
     * @throws NoSuchMethodException never (the getter is part of the entity contract)
     */
    @Test
    void transactionAmount_isBigDecimalNeverFloatingPoint() throws NoSuchMethodException {
        Class<?> amountType = Transaction.class.getMethod("getTranAmt").getReturnType();

        assertThat(amountType).isEqualTo(BigDecimal.class);
        assertThat(amountType).isNotEqualTo(float.class);
        assertThat(amountType).isNotEqualTo(double.class);
        assertThat(amountType).isNotEqualTo(Float.class);
        assertThat(amountType).isNotEqualTo(Double.class);
        assertThat(sampleTransaction().getTranAmt()).isInstanceOf(BigDecimal.class);
    }
}
