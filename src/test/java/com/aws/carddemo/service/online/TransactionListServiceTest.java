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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COTRN00Form;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.service.online.TransactionListService.AidKey;
import com.aws.carddemo.service.online.TransactionListService.RoutingAction;
import com.aws.carddemo.service.online.TransactionListService.TransactionListResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

/**
 * Pure-Mockito unit tests for {@link TransactionListService}.
 *
 * <p><b>Origin / parity oracle (read-only):</b> {@code legacy/cbl/COTRN00C.cbl} (CICS COBOL
 * program {@code COTRN00C}, transaction id {@code CT00}) &mdash; the AWS CardDemo paged
 * transaction-list browse. These tests assert one-for-one control-flow parity with the numbered
 * paragraphs of the oracle that the service migrates (AAP &sect;0.1.2 / &sect;0.4.1 /
 * &sect;0.6.10):</p>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link TransactionListService#mainEntry} &mdash; the
 *       pseudo-conversational state machine ({@code EIBCALEN = 0} first-entry bounce,
 *       {@code NOT CDEMO-PGM-REENTER} first display, and the {@code EVALUATE EIBAID} re-entry
 *       branches for {@code DFHENTER}, {@code DFHPF3}, {@code DFHPF7}, {@code DFHPF8}, and
 *       {@code WHEN OTHER}).</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link TransactionListService#processEnterKey} &mdash;
 *       the ten-cell selection scan, the {@code 'S'}/{@code 's'} dispatch to {@code COTRN01C},
 *       the invalid-selection message, and the {@code TRNIDIN} numeric-filter edit.</li>
 *   <li>{@code PROCESS-PF7-KEY} &rarr; {@link TransactionListService#processPf7Key} and
 *       {@code PROCESS-PF8-KEY} &rarr; {@link TransactionListService#processPf8Key} &mdash; the
 *       page-up / page-down keys and their already-at-top / already-at-bottom boundaries.</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} &rarr; {@link TransactionListService#processPageForward} and
 *       {@code PROCESS-PAGE-BACKWARD} &rarr; {@link TransactionListService#processPageBackward}
 *       &mdash; the ten-row browse fill, the next-page peek, and the backward page-number
 *       decrement.</li>
 *   <li>{@code POPULATE-TRAN-DATA} &rarr; the row-population logic exercised through a page load
 *       &mdash; the {@code TRAN-AMT PIC S9(09)V99} edit mask, the {@code MM/DD/YY} date, and the
 *       {@code X(26)} description truncation.</li>
 * </ul>
 *
 * <p><b>Test tier:</b> a strict-stubs pure-Mockito unit test ({@link MockitoExtension}); it uses
 * no database, no Spring context, and no Testcontainers. The two collaborators are mocked and the
 * service is exercised in isolation. Routing outcomes are asserted on the mocked
 * {@link CardDemoContext} (the {@code COMMAREA} replacement) rather than on any HTTP redirect,
 * because this service records the routing target in the context and returns a
 * {@link TransactionListResult} describing the follow-up action, deferring the actual
 * {@code XCTL}/redirect and screen rendering to the paired controller.</p>
 *
 * <p><b>Browse fixture:</b> the VSAM {@code STARTBR}/{@code READNEXT}/{@code READPREV} browse is
 * reproduced by the service reading each page as a bounded, key-ordered window (review finding
 * #21): a forward page via {@code findByTranIdGreaterThanEqualOrderByTranIdAsc}, a backward page
 * via {@code findByTranIdLessThanEqualOrderByTranIdDesc}, and the next-page indicator via
 * {@code existsByTranIdGreaterThan}. The mocked repository's window finders are stubbed with a
 * controlled, already-ascending list of {@link Transaction} rows (transaction ids are 16-character
 * zero-padded numerics, so {@code String} order matches the legacy key order); each stub computes
 * the exact slice a real {@code C}-collated, key-ordered query would return for the requested start
 * key and limit. A fixture of at least eleven rows proves the exact ten-row page limit and the
 * forward/backward paging while the query stays bounded.</p>
 *
 * <p><b>Money fidelity:</b> transaction amounts are {@link BigDecimal} (never {@code float} or
 * {@code double}); the amount assertions confirm the COBOL {@code +99999999.99} edit mask,
 * including the {@code RoundingMode.DOWN} truncation of the COBOL statement that omits
 * {@code ROUNDED} and the high-order digit truncation into the eight-integer-digit field
 * (AAP &sect;0.6.1).</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionListServiceTest {

    /** COBOL {@code WS-PGMNAME VALUE 'COTRN00C'}; recorded as the origin program on an 'S' dispatch. */
    private static final String PROGRAM_NAME = "COTRN00C";

    /** COBOL {@code WS-TRANID VALUE 'CT00'}; recorded as the origin transaction id on an 'S' dispatch. */
    private static final String TRANSACTION_ID = "CT00";

    /** Sign-on program ({@code COSGN00C}); the {@code XCTL} target on the {@code EIBCALEN = 0} bounce. */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /** Main-menu program ({@code COMEN01C}); the PF3 return target ({@code MAIN-PARA WHEN DFHPF3}). */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** Transaction-view program ({@code COTRN01C}); the {@code XCTL} target for an 'S' selection. */
    private static final String DETAIL_PROGRAM = "COTRN01C";

    /** Transaction-view transaction id ({@code CT01}); the hand-off target tran id for an 'S' selection. */
    private static final String DETAIL_TRANSACTION_ID = "CT01";

    /** Exact oracle literal ({@code CCDA-MSG-INVALID-KEY}) for the {@code WHEN OTHER} branch. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Exact oracle literal (COTRN00C L199) for a selection flag other than {@code 'S'}/{@code 's'}. */
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /** Exact oracle literal (COTRN00C L214) for a non-numeric {@code TRNIDIN} filter. */
    private static final String MSG_TRAN_ID_NUMERIC = "Tran ID must be Numeric ...";

    /** Exact oracle literal (COTRN00C L248) for PF7 pressed while already on the first page. */
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";

    /** Exact oracle literal (COTRN00C L270) for PF8 pressed while no further page exists. */
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";

    /** Exact oracle literal (COTRN00C L608) for a {@code STARTBR NOTFND} (empty/at-top browse). */
    private static final String MSG_STARTBR_TOP = "You are at the top of the page...";

    /** Exact oracle literal (COTRN00C L642) for a forward {@code READNEXT ENDFILE}. */
    private static final String MSG_READNEXT_BOTTOM = "You have reached the bottom of the page...";

    /** Exact oracle literal (COTRN00C L676) for a backward {@code READPREV ENDFILE}. */
    private static final String MSG_READPREV_TOP = "You have reached the top of the page...";

    /** Common non-null origination timestamp for fixtures (drives the {@code MM/DD/YY} row date). */
    private static final LocalDateTime BASE_TS = LocalDateTime.of(2023, 1, 15, 9, 0, 0);

    /** Session-scoped {@code COMMAREA} replacement, mocked so routing writes are observable. */
    @Mock
    private CardDemoContext context;

    /** Transaction repository, mocked so the in-memory browse reads a controlled ordered set. */
    @Mock
    private TransactionRepository transactionRepository;

    /** Service under test; Mockito constructor-injects the two mocked collaborators by type. */
    @InjectMocks
    private TransactionListService service;

    // ------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------

    /**
     * Builds a 16-character zero-padded transaction id (COBOL {@code TRAN-ID PIC X(16)} holding a
     * numeric value), matching the legacy key format so ascending {@code String} order equals the
     * legacy VSAM key order.
     *
     * @param n the ordinal transaction number
     * @return the 16-character zero-padded id
     */
    private static String tranId(int n) {
        return String.format("%016d", n);
    }

    /**
     * Builds a single transaction with the given id and amount plus deterministic non-null
     * supporting fields (card number, timestamps, and a description).
     *
     * @param id     the transaction id
     * @param amount the transaction amount ({@link BigDecimal})
     * @return the populated transaction
     */
    private static Transaction transaction(String id, BigDecimal amount) {
        Transaction tran = new Transaction();
        tran.setTranId(id);
        tran.setTranAmt(amount);
        tran.setCardNum("4111111111111111");
        tran.setOrigTs(BASE_TS);
        tran.setProcTs(BASE_TS.plusHours(1));
        tran.setTranDesc("TRANSACTION " + id);
        return tran;
    }

    /**
     * Builds an ascending list of {@code count} transactions with ids {@code tranId(1)}..
     * {@code tranId(count)}, reproducing an ordered {@code TRANSACT} browse snapshot.
     *
     * @param count the number of rows to build
     * @return the ordered transaction list
     */
    private static List<Transaction> sequentialTransactions(int count) {
        List<Transaction> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(transaction(tranId(i), new BigDecimal(i + ".00")));
        }
        return list;
    }

    /**
     * Stubs the three bounded browse-window repository methods to behave like the real
     * {@code C}-collated, key-ordered queries over the {@code ascending} fixture: the forward window
     * ({@code findByTranIdGreaterThanEqualOrderByTranIdAsc}), the backward window
     * ({@code findByTranIdLessThanEqualOrderByTranIdDesc}, returned descending for the service to
     * reverse), and the next-page existence check ({@code existsByTranIdGreaterThan}). The stubs are
     * {@code lenient} because a given test exercises only the subset of these paths its scenario
     * reaches (a forward page, a backward page, and/or the PF8 next-page probe), so the unused stubs
     * must not trip strict-stub checking.
     *
     * @param ascending the fixture, already sorted ascending by transaction id
     */
    private void stubBrowseWindows(List<Transaction> ascending) {
        lenient().when(transactionRepository.findByTranIdGreaterThanEqualOrderByTranIdAsc(
                anyString(), any(Limit.class)))
                .thenAnswer(invocation -> forwardWindow(ascending, invocation.getArgument(0),
                        invocation.getArgument(1)));
        lenient().when(transactionRepository.findByTranIdLessThanEqualOrderByTranIdDesc(
                anyString(), any(Limit.class)))
                .thenAnswer(invocation -> backwardWindow(ascending, invocation.getArgument(0),
                        invocation.getArgument(1)));
        lenient().when(transactionRepository.existsByTranIdGreaterThan(anyString()))
                .thenAnswer(invocation -> existsAfter(ascending, invocation.getArgument(0)));
    }

    /**
     * Computes the forward (ascending, greater-than-or-equal) window a real key-ordered query would
     * return, using the same {@code String} comparison the service and the {@code C}-collated column
     * use on the 16-character zero-padded transaction ids.
     *
     * @param ascending the fixture sorted ascending by transaction id
     * @param startKey  the inclusive lower-bound start key
     * @param limit     the row cap
     * @return the ascending window, at most {@code limit} records
     */
    private static List<Transaction> forwardWindow(List<Transaction> ascending, String startKey,
                                                   Limit limit) {
        List<Transaction> window = new ArrayList<>();
        for (Transaction candidate : ascending) {
            String id = (candidate.getTranId() == null) ? "" : candidate.getTranId();
            if (id.compareTo(startKey) >= 0) {
                window.add(candidate);
                if (window.size() == limit.max()) {
                    break;
                }
            }
        }
        return window;
    }

    /**
     * Computes the backward (descending, less-than-or-equal) window a real key-ordered query would
     * return, matching the repository contract that hands back rows in descending key order for the
     * service to reverse.
     *
     * @param ascending the fixture sorted ascending by transaction id
     * @param startKey  the inclusive upper-bound start key
     * @param limit     the row cap
     * @return the descending window, at most {@code limit} records
     */
    private static List<Transaction> backwardWindow(List<Transaction> ascending, String startKey,
                                                    Limit limit) {
        List<Transaction> window = new ArrayList<>();
        for (int i = ascending.size() - 1; i >= 0; i--) {
            Transaction candidate = ascending.get(i);
            String id = (candidate.getTranId() == null) ? "" : candidate.getTranId();
            if (id.compareTo(startKey) <= 0) {
                window.add(candidate);
                if (window.size() == limit.max()) {
                    break;
                }
            }
        }
        return window;
    }

    /**
     * Reports whether any fixture transaction sorts strictly after {@code key}, reproducing the
     * bounded existence query behind the next-page indicator.
     *
     * @param ascending the fixture sorted ascending by transaction id
     * @param key       the exclusive lower-bound key (the current page's last id)
     * @return {@code true} when at least one transaction id is strictly greater than {@code key}
     */
    private static boolean existsAfter(List<Transaction> ascending, String key) {
        for (Transaction candidate : ascending) {
            String id = (candidate.getTranId() == null) ? "" : candidate.getTranId();
            if (id.compareTo(key) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Simulates the pseudo-conversational round-trip by writing a currently-displayed page onto the
     * form: the page number field and the ten row transaction ids (the paging cursor the service
     * reconstructs from the screen). Rows beyond the supplied list are left unset.
     *
     * @param form       the screen form to populate
     * @param rows       the transactions currently shown (up to ten)
     * @param pageNumber the current one-based page number
     */
    private static void displayPage(COTRN00Form form, List<Transaction> rows, int pageNumber) {
        form.setPagenum(String.format("%08d", pageNumber));
        form.setTrnid01(rowId(rows, 0));
        form.setTrnid02(rowId(rows, 1));
        form.setTrnid03(rowId(rows, 2));
        form.setTrnid04(rowId(rows, 3));
        form.setTrnid05(rowId(rows, 4));
        form.setTrnid06(rowId(rows, 5));
        form.setTrnid07(rowId(rows, 6));
        form.setTrnid08(rowId(rows, 7));
        form.setTrnid09(rowId(rows, 8));
        form.setTrnid10(rowId(rows, 9));
    }

    /**
     * Returns the transaction id of the given zero-based row, or {@code null} when the row is beyond
     * the supplied list.
     *
     * @param rows  the transactions being displayed
     * @param index the zero-based row index
     * @return the row's transaction id, or {@code null}
     */
    private static String rowId(List<Transaction> rows, int index) {
        return (index < rows.size()) ? rows.get(index).getTranId() : null;
    }

    /**
     * Collects the ten row transaction ids from the form in row order (nulls permitted), so a
     * whole page can be asserted at once.
     *
     * @param form the screen form
     * @return the ten row transaction ids, row 1 first
     */
    private static List<String> displayedRowIds(COTRN00Form form) {
        return Arrays.asList(
                form.getTrnid01(), form.getTrnid02(), form.getTrnid03(), form.getTrnid04(),
                form.getTrnid05(), form.getTrnid06(), form.getTrnid07(), form.getTrnid08(),
                form.getTrnid09(), form.getTrnid10());
    }

    /**
     * Returns the inclusive range of zero-padded transaction ids {@code tranId(from)}..
     * {@code tranId(to)}, used to assert which slice of the browse a page displays.
     *
     * @param fromInclusive the first ordinal (inclusive)
     * @param toInclusive   the last ordinal (inclusive)
     * @return the ordered list of expected transaction ids
     */
    private static List<String> idRange(int fromInclusive, int toInclusive) {
        List<String> ids = new ArrayList<>();
        for (int i = fromInclusive; i <= toInclusive; i++) {
            ids.add(tranId(i));
        }
        return ids;
    }

    /**
     * Returns the ten-row (or shorter, trailing) slice of {@code all} that a given one-based page
     * would display, so a "currently displayed" page can be laid onto the form for the PF7/PF8
     * re-entry tests.
     *
     * @param all        the full ordered transaction list
     * @param pageNumber the one-based page number
     * @return the transactions on that page (possibly fewer than ten on the last page)
     */
    private static List<Transaction> pageRows(List<Transaction> all, int pageNumber) {
        int from = (pageNumber - 1) * 10;
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        int to = Math.min(from + 10, all.size());
        return new ArrayList<>(all.subList(from, to));
    }

    // ==================================================================
    // MAIN-PARA state machine (mainEntry)
    // ==================================================================

    /**
     * {@code MAIN-PARA} first entry ({@code EIBCALEN = 0}): control bounces back to the sign-on
     * program, the origin fields are recorded, and no browse is performed.
     */
    @Test
    void mainEntry_firstEntry_bouncesToSignonProgram() {
        when(context.isNew()).thenReturn(true);
        when(context.getToProgram()).thenReturn(SIGNON_PROGRAM);

        TransactionListResult result = service.mainEntry(AidKey.ENTER, new COTRN00Form());

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(SIGNON_PROGRAM);
        verify(context).setToProgram(SIGNON_PROGRAM);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).setPgmContext(0);
        verifyNoInteractions(transactionRepository);
    }

    /**
     * {@code MAIN-PARA} first display ({@code NOT CDEMO-PGM-REENTER}): the program is marked
     * re-entered and {@code PROCESS-ENTER-KEY} runs immediately, loading page one from the top.
     */
    @Test
    void mainEntry_firstDisplay_marksReenterAndLoadsFirstPage() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(true);
        stubBrowseWindows(sequentialTransactions(25));

        COTRN00Form form = new COTRN00Form();
        TransactionListResult result = service.mainEntry(AidKey.ENTER, form);

        verify(context).markReenter();
        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.hasMessage()).isFalse();
        assertThat(form.getPagenum()).isEqualTo("00000001");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(1, 10));
    }

    /**
     * {@code MAIN-PARA} re-entry {@code WHEN DFHPF3}: PF3 records the main-menu program and returns
     * to it via {@code RETURN-TO-PREV-SCREEN}; no browse is performed.
     */
    @Test
    void mainEntry_reentryPf3_returnsToMainMenu() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        when(context.getToProgram()).thenReturn(MENU_PROGRAM);

        TransactionListResult result = service.mainEntry(AidKey.PF3, new COTRN00Form());

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(MENU_PROGRAM);
        verify(context).setToProgram(MENU_PROGRAM);
        verifyNoInteractions(transactionRepository);
    }

    /**
     * {@code MAIN-PARA} re-entry {@code WHEN OTHER}: an unmapped key yields the invalid-key error
     * line and no browse.
     */
    @Test
    void mainEntry_reentryOtherKey_showsInvalidKeyMessage() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        TransactionListResult result = service.mainEntry(AidKey.OTHER, new COTRN00Form());

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        verifyNoInteractions(transactionRepository);
    }

    /**
     * {@code MAIN-PARA} re-entry with a {@code null} AID collapses to {@code WHEN OTHER}, yielding
     * the invalid-key error line.
     */
    @Test
    void mainEntry_reentryNullAid_showsInvalidKeyMessage() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);

        TransactionListResult result = service.mainEntry(null, new COTRN00Form());

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_INVALID_KEY);
        verifyNoInteractions(transactionRepository);
    }

    /**
     * {@code MAIN-PARA} re-entry {@code WHEN DFHPF8}: PF8 pages forward from the displayed page one
     * to the next full page (rows 11-20) via {@code PROCESS-PF8-KEY}.
     */
    @Test
    void mainEntry_reentryPf8_pagesForwardToNextPage() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        List<Transaction> all = sequentialTransactions(25);
        stubBrowseWindows(all);

        COTRN00Form form = new COTRN00Form();
        displayPage(form, pageRows(all, 1), 1);

        TransactionListResult result = service.mainEntry(AidKey.PF8, form);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(form.getPagenum()).isEqualTo("00000002");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(11, 20));
    }

    /**
     * {@code MAIN-PARA} re-entry {@code WHEN DFHPF7}: PF7 pages backward from the displayed page two
     * to the prior page (rows 1-10) via {@code PROCESS-PF7-KEY}.
     */
    @Test
    void mainEntry_reentryPf7_pagesBackwardToPriorPage() {
        when(context.isNew()).thenReturn(false);
        when(context.isProgramEnter()).thenReturn(false);
        List<Transaction> all = sequentialTransactions(25);
        stubBrowseWindows(all);

        COTRN00Form form = new COTRN00Form();
        displayPage(form, pageRows(all, 2), 2);

        TransactionListResult result = service.mainEntry(AidKey.PF7, form);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(form.getPagenum()).isEqualTo("00000001");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(1, 10));
    }

    // ==================================================================
    // PROCESS-ENTER-KEY (selection, filter, first page)
    // ==================================================================

    /**
     * {@code PROCESS-ENTER-KEY} / {@code PROCESS-PAGE-FORWARD}: given eleven matching transactions
     * only the first ten populate the page (the exact ten-row page limit); the eleventh is left
     * for the next page and no boundary message is raised.
     */
    @Test
    void processEnterKey_moreThanTenMatches_populatesExactlyTenRows() {
        stubBrowseWindows(sequentialTransactions(11));

        COTRN00Form form = new COTRN00Form();
        TransactionListResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.hasMessage()).isFalse();
        assertThat(form.getPagenum()).isEqualTo("00000001");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(1, 10));
        assertThat(displayedRowIds(form)).doesNotContain(tranId(11));
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-ENTER-KEY} selection {@code WHEN 'S'}: dispatches the chosen transaction to the
     * view program {@code COTRN01C}/{@code CT01}, forwarding the selected id and setting the
     * hand-off context; no browse is performed (the COBOL {@code XCTL} returns immediately).
     */
    @Test
    void processEnterKey_selectionUpperS_routesToTransactionView() {
        COTRN00Form form = new COTRN00Form();
        form.setSel0005("S");
        form.setTrnid05(tranId(5));

        TransactionListResult result = service.processEnterKey(form, context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(DETAIL_PROGRAM);
        assertThat(result.hasSelection()).isTrue();
        assertThat(result.selectedTransactionId()).isEqualTo(tranId(5));
        verify(context).setToProgram(DETAIL_PROGRAM);
        verify(context).setToTranid(DETAIL_TRANSACTION_ID);
        verify(context).setFromTranid(TRANSACTION_ID);
        verify(context).setFromProgram(PROGRAM_NAME);
        verify(context).setPgmContext(0);
        verifyNoInteractions(transactionRepository);
    }

    /**
     * {@code PROCESS-ENTER-KEY} selection {@code WHEN 's'}: the lower-case selection flag is
     * accepted equivalently and dispatches to the view program.
     */
    @Test
    void processEnterKey_selectionLowerS_routesToTransactionView() {
        COTRN00Form form = new COTRN00Form();
        form.setSel0003("s");
        form.setTrnid03(tranId(3));

        TransactionListResult result = service.processEnterKey(form, context);

        assertThat(result.isRedirect()).isTrue();
        assertThat(result.targetProgram()).isEqualTo(DETAIL_PROGRAM);
        assertThat(result.selectedTransactionId()).isEqualTo(tranId(3));
        verify(context).setToProgram(DETAIL_PROGRAM);
        verify(context).setToTranid(DETAIL_TRANSACTION_ID);
        verifyNoInteractions(transactionRepository);
    }

    /**
     * {@code PROCESS-ENTER-KEY} selection {@code WHEN OTHER}: an invalid selection flag sets the
     * neutral "Invalid selection" message but the COBOL still continues to load the page.
     */
    @Test
    void processEnterKey_invalidSelectionFlag_showsMessageAndStillLoadsPage() {
        stubBrowseWindows(sequentialTransactions(11));

        COTRN00Form form = new COTRN00Form();
        form.setSel0002("X");
        form.setTrnid02(tranId(2));

        TransactionListResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.error()).isFalse();
        assertThat(result.message()).isEqualTo(MSG_INVALID_SELECTION);
        assertThat(form.getPagenum()).isEqualTo("00000001");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(1, 10));
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-ENTER-KEY} filter: a full sixteen-digit numeric {@code TRNIDIN} positions the
     * browse at that id ({@code STARTBR GTEQ}); the page then shows that id and the nine following.
     */
    @Test
    void processEnterKey_numericFilter_positionsBrowseAtFilterId() {
        stubBrowseWindows(sequentialTransactions(25));

        COTRN00Form form = new COTRN00Form();
        form.setTrnidin(tranId(5));

        TransactionListResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.hasMessage()).isFalse();
        assertThat(form.getTrnid01()).isEqualTo(tranId(5));
        assertThat(form.getPagenum()).isEqualTo("00000001");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(5, 14));
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-ENTER-KEY} filter: a non-numeric {@code TRNIDIN} raises the error flag with the
     * "Tran ID must be Numeric ..." message and performs no browse (COBOL {@code IF NOT ERR-FLG-ON}
     * guard skips paging).
     */
    @Test
    void processEnterKey_nonNumericFilter_showsNumericErrorAndDoesNotBrowse() {
        COTRN00Form form = new COTRN00Form();
        form.setTrnidin("ABC");

        TransactionListResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.error()).isTrue();
        assertThat(result.message()).isEqualTo(MSG_TRAN_ID_NUMERIC);
        assertThat(form.getTrnid01()).isNull();
        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-ENTER-KEY} / {@code STARTBR-TRANSACT-FILE} {@code NOTFND}: an empty transaction
     * file yields the neutral top-of-file message and an unchanged (page zero) position.
     */
    @Test
    void processEnterKey_emptyFile_showsTopOfPageMessage() {
        stubBrowseWindows(new ArrayList<>());

        COTRN00Form form = new COTRN00Form();
        TransactionListResult result = service.processEnterKey(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.error()).isFalse();
        assertThat(result.message()).isEqualTo(MSG_STARTBR_TOP);
        assertThat(form.getPagenum()).isEqualTo("00000000");
        verifyNoInteractions(context);
    }

    // ==================================================================
    // PROCESS-PF8-KEY (page forward / bottom boundary)
    // ==================================================================

    /**
     * {@code PROCESS-PF8-KEY}: from the displayed page one, PF8 advances to the next full page
     * (rows 11-20) and increments the page number.
     */
    @Test
    void processPf8Key_notAtBottom_advancesToNextPage() {
        List<Transaction> all = sequentialTransactions(25);
        stubBrowseWindows(all);

        COTRN00Form form = new COTRN00Form();
        displayPage(form, pageRows(all, 1), 1);

        TransactionListResult result = service.processPf8Key(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(form.getPagenum()).isEqualTo("00000002");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(11, 20));
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-PF8-KEY} at the bottom: with no transaction beyond the last displayed row, PF8
     * shows the verbatim "You are already at the bottom of the page..." message and leaves the page
     * unchanged.
     */
    @Test
    void processPf8Key_atBottom_showsAlreadyAtBottomAndLeavesPageUnchanged() {
        List<Transaction> all = sequentialTransactions(10);
        stubBrowseWindows(all);

        COTRN00Form form = new COTRN00Form();
        displayPage(form, pageRows(all, 1), 1);

        TransactionListResult result = service.processPf8Key(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.error()).isFalse();
        assertThat(result.message()).isEqualTo(MSG_ALREADY_BOTTOM);
        assertThat(form.getPagenum()).isEqualTo("00000001");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(1, 10));
        verifyNoInteractions(context);
    }

    // ==================================================================
    // PROCESS-PF7-KEY (page backward / top boundary)
    // ==================================================================

    /**
     * {@code PROCESS-PF7-KEY}: from the displayed page three, PF7 pages back to the prior full page
     * (rows 11-20) and decrements the page number.
     */
    @Test
    void processPf7Key_notAtTop_pagesBackToPriorPage() {
        List<Transaction> all = sequentialTransactions(25);
        stubBrowseWindows(all);

        COTRN00Form form = new COTRN00Form();
        displayPage(form, pageRows(all, 3), 3);

        TransactionListResult result = service.processPf7Key(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(form.getPagenum()).isEqualTo("00000002");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(11, 20));
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-PF7-KEY} at the top: on page one PF7 shows the verbatim "You are already at the
     * top of the page..." message, performs no browse, and leaves the page unchanged.
     */
    @Test
    void processPf7Key_atTop_showsAlreadyAtTopAndDoesNotBrowse() {
        List<Transaction> all = sequentialTransactions(10);

        COTRN00Form form = new COTRN00Form();
        displayPage(form, pageRows(all, 1), 1);

        TransactionListResult result = service.processPf7Key(form, context);

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.error()).isFalse();
        assertThat(result.message()).isEqualTo(MSG_ALREADY_TOP);
        assertThat(form.getPagenum()).isEqualTo("00000001");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(1, 10));
        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(context);
    }

    // ==================================================================
    // PROCESS-PAGE-FORWARD / PROCESS-PAGE-BACKWARD (direct)
    // ==================================================================

    /**
     * {@code PROCESS-PAGE-FORWARD} direct: from page zero with a {@code LOW-VALUES} start key the
     * first ten rows are loaded and the page number becomes one.
     */
    @Test
    void processPageForward_fromStart_loadsFirstPage() {
        stubBrowseWindows(sequentialTransactions(25));

        COTRN00Form form = new COTRN00Form();
        form.setPagenum("00000000");

        TransactionListResult result = service.processPageForward(form, context, false, "");

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(result.hasMessage()).isFalse();
        assertThat(form.getPagenum()).isEqualTo("00000001");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(1, 10));
        verifyNoInteractions(context);
    }

    /**
     * {@code PROCESS-PAGE-BACKWARD} direct: from page three the page number is decremented to two
     * (not reset to one, because a previous record still exists) and rows 11-20 are shown.
     */
    @Test
    void processPageBackward_fromPageThree_decrementsPageNumberNotReset() {
        stubBrowseWindows(sequentialTransactions(25));

        COTRN00Form form = new COTRN00Form();
        form.setPagenum("00000003");

        TransactionListResult result = service.processPageBackward(form, context, tranId(21));

        assertThat(result.action()).isEqualTo(RoutingAction.SHOW_LIST);
        assertThat(form.getPagenum()).isEqualTo("00000002");
        assertThat(displayedRowIds(form)).containsExactlyElementsOf(idRange(11, 20));
        verifyNoInteractions(context);
    }

    // ==================================================================
    // POPULATE-TRAN-DATA (money edit mask, date, description)
    // ==================================================================

    /**
     * {@code POPULATE-TRAN-DATA}: the {@code TRAN-AMT PIC S9(09)V99} edit mask is reproduced exactly
     * using {@link BigDecimal} (never {@code float}/{@code double}) &mdash; two-decimal truncation
     * (no {@code ROUNDED}), always-shown sign, and high-order truncation into the eight-integer-digit
     * field.
     */
    @Test
    void populateTranData_formatsAmountsWithCobolEditMask() {
        List<Transaction> fixture = new ArrayList<>();
        fixture.add(transaction(tranId(1), new BigDecimal("123.45")));
        fixture.add(transaction(tranId(2), new BigDecimal("12.999")));
        fixture.add(transaction(tranId(3), new BigDecimal("-50.00")));
        fixture.add(transaction(tranId(4), new BigDecimal("123456789.99")));
        stubBrowseWindows(fixture);

        COTRN00Form form = new COTRN00Form();
        service.processEnterKey(form, context);

        assertThat(fixture.get(0).getTranAmt()).isInstanceOf(BigDecimal.class);
        assertThat(form.getTamt001()).isEqualTo("+00000123.45");
        assertThat(form.getTamt002()).isEqualTo("+00000012.99");
        assertThat(form.getTamt003()).isEqualTo("-00000050.00");
        assertThat(form.getTamt004()).isEqualTo("+23456789.99");
        verifyNoInteractions(context);
    }

    /**
     * {@code POPULATE-TRAN-DATA}: the origination timestamp is rendered {@code MM/DD/YY} and the
     * description is truncated to the screen's {@code X(26)} width.
     */
    @Test
    void populateTranData_formatsDateAndTruncatesDescription() {
        Transaction tran = transaction(tranId(1), new BigDecimal("5.00"));
        tran.setOrigTs(LocalDateTime.of(2023, 1, 15, 9, 30, 0));
        tran.setTranDesc("ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890");
        List<Transaction> fixture = new ArrayList<>();
        fixture.add(tran);
        stubBrowseWindows(fixture);

        COTRN00Form form = new COTRN00Form();
        service.processEnterKey(form, context);

        assertThat(form.getTdate01()).isEqualTo("01/15/23");
        assertThat(form.getTdesc01()).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        assertThat(form.getTdesc01()).hasSize(26);
        verifyNoInteractions(context);
    }
}
