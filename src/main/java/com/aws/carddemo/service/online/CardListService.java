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

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.CardWorkArea;
import com.aws.carddemo.dto.CardWorkArea.PfKey;
import com.aws.carddemo.dto.screen.COCRDLIForm;
import com.aws.carddemo.exception.EndOfFileException;
import com.aws.carddemo.repository.CardRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Credit-card list (paged browse + selection dispatch) service.
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COCRDLIC.cbl} (CICS COBOL program
 * {@code COCRDLIC}, transaction id {@code CCLI}). This class is the faithful Java
 * migration of the online <em>Credit Card List</em> program, preserving the COBOL
 * control flow one-for-one: each business {@code PARAGRAPH} becomes exactly one
 * method (AAP &sect;0.3.3, service layer; AAP &sect;0.4.1, online-programs table
 * {@code COCRDLIC.cbl -> CardListService + CardController list, tran CCLI};
 * AAP &sect;0.6.2, alternate-index &rarr; derived query).</p>
 *
 * <h2>Program archetype</h2>
 * <p>{@code COCRDLIC} is the <em>paged-browse + selection-dispatch</em> archetype of
 * the AWS CardDemo online tier. It lists credit cards seven rows at a time
 * (COBOL {@code WS-MAX-SCREEN-LINES VALUE 7}), optionally filtered by account and/or
 * card number, supports paging forward ({@code PF8}) and backward ({@code PF7}), and
 * dispatches to the card-detail ({@code COCRDSLC}) or card-update
 * ({@code COCRDUPC}) program when the operator marks a row with {@code S} (view) or
 * {@code U} (update). Its paragraphs divide cleanly into business logic (kept here)
 * and 3270/BMS presentation (owned by the paired {@code CardController} and the
 * Thymeleaf view); this service therefore implements only the business paragraphs and
 * deliberately contains no {@code SEND}/{@code RECEIVE}/attribute logic:</p>
 * <table border="1">
 *   <caption>COCRDLIC paragraph &rarr; Java mapping</caption>
 *   <tr><th>COBOL paragraph</th><th>Owner</th><th>Java member</th></tr>
 *   <tr><td>{@code 0000-MAIN}</td><td>service</td>
 *       <td>{@link #mainEntry(COCRDLIForm, PfKey, CardWorkArea, CardListPagingState)}</td></tr>
 *   <tr><td>{@code 2000-RECEIVE-MAP}</td><td>controller</td><td>&mdash; ({@code RECEIVE MAP})</td></tr>
 *   <tr><td>{@code 2100-RECEIVE-SCREEN}</td><td>controller</td><td>&mdash; (form binding)</td></tr>
 *   <tr><td>{@code 2200-EDIT-INPUTS}</td><td>service</td>
 *       <td>{@link #editInputs(COCRDLIForm, CardWorkArea, CardListState)}</td></tr>
 *   <tr><td>{@code 2210-EDIT-ACCOUNT}</td><td>service</td>
 *       <td>{@link #editAccount(CardWorkArea, CardListState)}</td></tr>
 *   <tr><td>{@code 2220-EDIT-CARD}</td><td>service</td>
 *       <td>{@link #editCard(CardWorkArea, CardListState)}</td></tr>
 *   <tr><td>{@code 2250-EDIT-ARRAY}</td><td>service</td>
 *       <td>{@link #editArray(COCRDLIForm, CardListState)}</td></tr>
 *   <tr><td>{@code 9000-READ-FORWARD}</td><td>service</td>
 *       <td>{@link #readForward(COCRDLIForm, CardWorkArea, CardListPagingState, CardListState)}</td></tr>
 *   <tr><td>{@code 9100-READ-BACKWARDS}</td><td>service</td>
 *       <td>{@link #readBackwards(COCRDLIForm, CardWorkArea, CardListPagingState, CardListState)}</td></tr>
 *   <tr><td>{@code 9500-FILTER-RECORDS}</td><td>service</td>
 *       <td>{@link #filterRecords(Card, CardListState)}</td></tr>
 *   <tr><td>{@code 1000/1100/1200/1250/1300/1400/1500-*}</td><td>controller</td>
 *       <td>&mdash; (screen build, attributes, message, {@code SEND})</td></tr>
 *   <tr><td>{@code SEND-PLAIN-TEXT} / {@code SEND-LONG-TEXT}</td><td>controller</td>
 *       <td>&mdash; (diagnostic text emit)</td></tr>
 *   <tr><td>{@code YYYY-STORE-PFKEY} ({@code COPY CSSTRPFY})</td><td>util</td>
 *       <td>&mdash; ({@code PfKeyHandler} / {@link PfKey})</td></tr>
 * </table>
 *
 * <h2>Two-part pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>{@code COCRDLIC} is pseudo-conversational and carries a <em>two-part</em>
 * COMMAREA across each {@code EXEC CICS RETURN TRANSID(CCLI)}: the shared
 * {@code CARDDEMO-COMMAREA} ({@code COPY COCOM01Y}) plus the program-local
 * {@code WS-THIS-PROGCOMMAREA} that holds the paging cursor and the seven displayed
 * rows.</p>
 * <ul>
 *   <li>The shared part is the session-scoped {@link CardDemoContext} (the COMMAREA
 *       replacement). {@code EIBCALEN = 0} (first entry) maps to
 *       {@link CardDemoContext#isNew()}.</li>
 *   <li>The program-local part is modeled by {@link CardListPagingState}, which the
 *       controller carries in the HTTP session alongside the context and passes into
 *       each call. It reproduces {@code WS-CA-FIRST-*}/{@code WS-CA-LAST-*} (the
 *       keyset cursor), {@code WS-CA-SCREEN-NUM}, the last-page and next-page
 *       indicators, and &mdash; crucially &mdash; the seven displayed rows
 *       ({@code WS-SCREEN-DATA}, which the copybook nests inside
 *       {@code WS-THIS-PROGCOMMAREA}). Because those rows persist across
 *       interactions, the selection dispatch reads the selected row's account and
 *       card number from the <em>persisted</em> rows, exactly as the COBOL reads
 *       {@code WS-ROW-ACCTNO(I-SELECTED)} / {@code WS-ROW-CARD-NUM(I-SELECTED)}
 *       (they are not re-received from the screen).</li>
 * </ul>
 *
 * <h2>Result contract</h2>
 * <p>Because presentation is externalized, {@link #mainEntry(COCRDLIForm, PfKey,
 * CardWorkArea, CardListPagingState) mainEntry} returns a {@link CardListResult}
 * describing the outcome: either {@link Routing#REDIRECT} (the COBOL {@code XCTL},
 * with the target program and transaction recorded on {@link CardDemoContext}) or
 * {@link Routing#SHOW_LIST} (the COBOL {@code 1000-SEND-MAP}), together with the business
 * error line ({@code WS-ERROR-MSG}) it produced. The controller renders the list screen or
 * issues the redirect accordingly. The purely presentational hints of
 * {@code 1400-SETUP-MESSAGE} (the {@code WS-INFO-MSG} default and the
 * {@code NO PREVIOUS PAGES} / {@code NO MORE PAGES} paging lines) remain controller-owned
 * and are derived by the controller from {@link CardListPagingState#isFirstPage()} and
 * {@link CardListPagingState#isNextPageExists()}.</p>
 *
 * <h2>Collaborators and dependencies</h2>
 * <p>The service constructor-injects three {@code private final} collaborators: the
 * session-scoped {@link CardDemoContext}, the {@link CardRepository}, and the
 * {@link CardXrefRepository}. Card-list browsing reads the {@code CARDDAT} base
 * cluster through {@link CardRepository}: the ascending {@code CARD-NUM} browse
 * ({@code STARTBR}/{@code READNEXT}) becomes a bounded keyset paging window
 * ({@link CardRepository#findByCardNumGreaterThanEqualOrderByCardNumAsc(String,
 * org.springframework.data.domain.Limit)} and its descending page-up counterpart) so the
 * whole base cluster is never materialized (review finding&nbsp;#21), and the
 * account-filtered {@code CARDAIX} alternate-index path becomes the derived query
 * {@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long)} (AAP
 * &sect;0.4.1, which assigns {@code CARDAIX} to this program). {@link CardXrefRepository}
 * is injected to satisfy the shared online card-service constructor contract used by
 * the paired {@code CardController}; the AAP assigns the {@code CCXREF}
 * cross-reference access path ({@code CXACAIX}) to the account-view program
 * ({@code COACTVWC}) rather than to {@code COCRDLIC}, so the card-list logic here does
 * not read the cross-reference.</p>
 *
 * <p>This service performs <b>no writes</b> (the card-list program is read-only) and
 * introduces no new behavior beyond the technology substitution (AAP &sect;0.2.2).
 * Plain Java, explicit accessors, no Lombok, no emoji; compiles warning-free under
 * {@code --release 25} with {@code -Xlint:all}.</p>
 *
 * @see CardDemoContext
 * @see CardListPagingState
 * @see COCRDLIForm
 * @see CardRepository#findByCardAcctIdOrderByCardNumAsc(Long)
 */
@Service
public class CardListService {

    /** COBOL {@code LIT-THISPGM PIC X(8) VALUE 'COCRDLIC'} - this program's name. */
    private static final String PROGRAM_NAME = "COCRDLIC";

    /** COBOL {@code LIT-THISTRANID PIC X(4) VALUE 'CCLI'} - this program's transaction id. */
    private static final String TRANSACTION_ID = "CCLI";

    /** COBOL {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} - main-menu program ({@code PF3} target). */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** COBOL {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} - main-menu transaction id. */
    private static final String MENU_TRANSACTION_ID = "CM00";

    /** COBOL {@code LIT-CARDDTLPGM PIC X(8) VALUE 'COCRDSLC'} - card-detail program ({@code S} target). */
    private static final String CARD_DETAIL_PROGRAM = "COCRDSLC";

    /** COBOL {@code LIT-CARDDTLTRANID PIC X(4) VALUE 'CCDL'} - card-detail transaction id. */
    private static final String CARD_DETAIL_TRANSACTION_ID = "CCDL";

    /** COBOL {@code LIT-CARDUPDPGM PIC X(8) VALUE 'COCRDUPC'} - card-update program ({@code U} target). */
    private static final String CARD_UPDATE_PROGRAM = "COCRDUPC";

    /** COBOL {@code LIT-CARDUPDTRANID PIC X(4) VALUE 'CCUP'} - card-update transaction id. */
    private static final String CARD_UPDATE_TRANSACTION_ID = "CCUP";

    /** COBOL {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} - rows per page. */
    private static final int MAX_SCREEN_LINES = 7;

    /**
     * Size of the bounded keyset browse window read per page for the unfiltered base-cluster
     * browse (review finding&nbsp;#21). A single {@link #readForward} page reads at most
     * {@value #MAX_SCREEN_LINES} surviving rows plus one look-ahead ({@code READNEXT}) record, and
     * a single {@link #readBackwards} page reads one priming {@code READPREV} plus at most
     * {@value #MAX_SCREEN_LINES} surviving rows; {@code MAX_SCREEN_LINES + 2} therefore bounds the
     * records either direction can touch, so a window of this size reproduces the full-cluster
     * browse (including the page-boundary look-ahead) without materializing the whole table.
     */
    private static final int BROWSE_WINDOW = MAX_SCREEN_LINES + 2;

    /** Row-select code {@code 'S'} - view the card (COBOL 88-level {@code VIEW-REQUESTED-ON}). */
    private static final String SELECT_VIEW = "S";

    /** Row-select code {@code 'U'} - update the card (COBOL 88-level {@code UPDATE-REQUESTED-ON}). */
    private static final String SELECT_UPDATE = "U";

    /** Number of digits an account-id filter must carry ({@code CC-ACCT-ID PIC 9(11)}). */
    private static final int ACCOUNT_FILTER_DIGITS = 11;

    /** Number of digits a card-number filter must carry ({@code CC-CARD-NUM PIC 9(16)}). */
    private static final int CARD_FILTER_DIGITS = 16;

    /**
     * COBOL {@code WS-ERROR-MSG} literal for a non-numeric account filter
     * (2210-EDIT-ACCOUNT, {@code legacy/cbl/COCRDLIC.cbl} lines 1021-1023).
     */
    private static final String MSG_ACCT_FILTER_INVALID =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /**
     * COBOL {@code WS-ERROR-MSG} literal for a non-numeric card filter
     * (2220-EDIT-CARD, {@code legacy/cbl/COCRDLIC.cbl} lines 1057-1059).
     */
    private static final String MSG_CARD_FILTER_INVALID =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * COBOL {@code WS-MORE-THAN-1-ACTION} literal (2250-EDIT-ARRAY,
     * {@code legacy/cbl/COCRDLIC.cbl} lines 123-124).
     */
    private static final String MSG_MORE_THAN_ONE_ACTION =
            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /**
     * COBOL {@code WS-INVALID-ACTION-CODE} literal (2250-EDIT-ARRAY,
     * {@code legacy/cbl/COCRDLIC.cbl} lines 125-126).
     */
    private static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /**
     * COBOL {@code WS-NO-RECORDS-FOUND} literal (9000-READ-FORWARD end-of-file with an
     * empty first page, {@code legacy/cbl/COCRDLIC.cbl} lines 121-122).
     */
    private static final String MSG_NO_RECORDS_FOUND =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /**
     * COBOL {@code WS-ERROR-MSG} literal set when a forward browse reaches end-of-file
     * (9000-READ-FORWARD, {@code legacy/cbl/COCRDLIC.cbl} lines 1219-1220, 1239).
     */
    private static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /**
     * COBOL {@code WS-EXIT-MESSAGE} literal set when the operator presses {@code PF3}
     * to leave the list ({@code 0000-MAIN}, {@code legacy/cbl/COCRDLIC.cbl} line 396).
     *
     * <p>The COBOL sets this immediately before the {@code XCTL} to the main menu; because
     * control transfers to {@code COMEN01C} (which renders its own screen) the text is not
     * itself displayed. The assignment is reproduced for behavioral parity.</p>
     */
    private static final String MSG_EXIT = "PF03 PRESSED.EXITING";

    /**
     * Session-scoped shared COMMAREA replacement ({@code COPY COCOM01Y}). Holds the
     * navigation hand-off, authenticated identity, and the selected account/card that
     * the dispatch writes for the target program's first-entry preload.
     */
    private final CardDemoContext context;

    /**
     * Repository over the {@code CARDDAT} base cluster (VSAM KSDS). Supplies the bounded
     * ascending {@code CARD-NUM} browse windows
     * ({@link CardRepository#findByCardNumGreaterThanEqualOrderByCardNumAsc(String,
     * org.springframework.data.domain.Limit)} and its descending page-up counterpart) and the
     * account-filtered {@code CARDAIX} alternate-index path
     * ({@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long)}).
     */
    private final CardRepository cardRepository;

    /**
     * Repository over the {@code CCXREF} card cross-reference (VSAM KSDS). Injected to
     * satisfy the shared online card-service constructor contract; the card-list
     * program reads {@code CARDDAT} directly, so this collaborator is held for
     * constructor-signature consistency with the paired card flows (AAP &sect;0.4.1
     * assigns the cross-reference access path to the account-view program).
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Creates the card-list service with its injected collaborators.
     *
     * <p>The constructor only stores the three references (it invokes no overridable
     * method), so it is free of the {@code this-escape} lint category under the
     * zero-warning build.</p>
     *
     * @param context            the session-scoped {@link CardDemoContext} (shared
     *                           COMMAREA replacement); must not be {@code null} in
     *                           production
     * @param cardRepository     the {@link CardRepository} over {@code CARDDAT}; must
     *                           not be {@code null} in production
     * @param cardXrefRepository the {@link CardXrefRepository} over {@code CCXREF};
     *                           must not be {@code null} in production
     */
    public CardListService(CardDemoContext context,
                           CardRepository cardRepository,
                           CardXrefRepository cardXrefRepository) {
        this.context = context;
        this.cardRepository = cardRepository;
        this.cardXrefRepository = cardXrefRepository;
    }

    /**
     * Main entry point - the Java migration of COBOL paragraph {@code 0000-MAIN}
     * ({@code legacy/cbl/COCRDLIC.cbl} lines 298-623).
     *
     * <p>Reproduces the pseudo-conversational driver of the card-list transaction
     * one-for-one:</p>
     * <ol>
     *   <li><b>Two-part COMMAREA restore/initialize.</b> First entry
     *       ({@code EIBCALEN = 0} &rarr; {@link CardDemoContext#isNew()}) initializes both the
     *       shared {@link CardDemoContext} and the program-local {@link CardListPagingState},
     *       sets the from-tranid/program to this program, defaults the user type, marks
     *       program-enter, and positions on the first page. On re-entry the caller has
     *       already restored both parts from the HTTP session, mirroring the COBOL
     *       {@code MOVE DFHCOMMAREA ...} split.</li>
     *   <li><b>Fresh start when arriving from another program.</b> When the context is
     *       marked program-enter and the from-program is not this program (i.e. the operator
     *       navigated in from the menu), the program-local paging state is reinitialized and
     *       reset to the first page ({@code legacy/cbl/COCRDLIC.cbl} lines 336-343).</li>
     *   <li><b>Edit inputs on self re-entry.</b> When a commarea is present and the
     *       from-program is this program, the submitted screen is edited via
     *       {@link #editInputs(COCRDLIForm, CardWorkArea, CardListState)} - the business
     *       half of {@code 2000-RECEIVE-MAP} (lines 357-362).</li>
     *   <li><b>PF-key remap.</b> Only {@code ENTER}, {@code PF3}, {@code PF7} and {@code PF8}
     *       are valid; any other key is treated as {@code ENTER} (lines 370-380).</li>
     *   <li><b>PF3 exit.</b> {@code PF3} pressed while already on this program routes back to
     *       the main menu {@code COMEN01C} (lines 384-406).</li>
     *   <li><b>Selection dispatch / paging.</b> The main {@code EVALUATE} (lines 418-583)
     *       decides between showing an input-error page, paging forward/backward, dispatching
     *       to the card-detail ({@code S}) or card-update ({@code U}) program for the selected
     *       row, or (re)listing the first page.</li>
     * </ol>
     *
     * <p>The controller has already performed {@code 2000-RECEIVE-MAP} /
     * {@code 2100-RECEIVE-SCREEN} by binding {@code form} and populating {@code work} (the
     * account and card filter fields) and by resolving the pressed {@code aid}.</p>
     *
     * @param form   the bound card-list screen ({@code CCRDLIAI}); never {@code null}
     * @param aid    the attention identifier the operator pressed, already resolved by the
     *               controller (COBOL {@code CCARD-AID} after {@code YYYY-STORE-PFKEY}); a
     *               {@code null} value is treated as {@link PfKey#OTHER} and hence remapped to
     *               {@code ENTER}
     * @param work   the card work area ({@code CC-WORK-AREAS}) carrying the account/card
     *               filter values the controller moved from the screen; never {@code null}
     * @param paging the program-local paging state ({@code WS-THIS-PROGCOMMAREA}) carried in
     *               the session; never {@code null}
     * @return the {@link CardListResult} describing whether to render the list
     *         ({@link Routing#SHOW_LIST}) or redirect ({@link Routing#REDIRECT}); the redirect
     *         target is recorded on {@link CardDemoContext}
     */
    public CardListResult mainEntry(COCRDLIForm form,
                                    PfKey aid,
                                    CardWorkArea work,
                                    CardListPagingState paging) {
        // INITIALIZE CC-WORK-AREA / WS-MISC-STORAGE / WS-COMMAREA (lines 300-302): the
        // per-request scratch state starts empty; SET WS-ERROR-MSG-OFF TO TRUE (line 311).
        CardListState state = new CardListState();

        // IF EIBCALEN = 0 -> first entry: initialize both COMMAREA parts (lines 315-332).
        if (context.isNew()) {
            context.setFromTranid(TRANSACTION_ID);
            context.setFromProgram(PROGRAM_NAME);
            context.setUser();
            context.markEnter();
            context.setLastMap(PROGRAM_NAME);
            context.setLastMapset(PROGRAM_NAME);
            context.markInitialized();
            paging.reset();
            paging.setScreenNum(1);            // SET CA-FIRST-PAGE TO TRUE
            paging.setLastPageShown(false);    // SET CA-LAST-PAGE-NOT-SHOWN TO TRUE
        }

        // IF coming in from another program, forget the past and start afresh (lines 336-343).
        if (context.isProgramEnter()
                && !PROGRAM_NAME.equals(context.getFromProgram())) {
            paging.reset();
            context.markEnter();
            context.setLastMap(PROGRAM_NAME);
            paging.setScreenNum(1);            // SET CA-FIRST-PAGE TO TRUE
            paging.setLastPageShown(false);    // SET CA-LAST-PAGE-NOT-SHOWN TO TRUE
        }

        // IF EIBCALEN > 0 AND from-program = this program -> edit the submitted inputs
        // (PERFORM 2000-RECEIVE-MAP, whose business half is 2200-EDIT-INPUTS; lines 357-362).
        if (!context.isNew() && PROGRAM_NAME.equals(context.getFromProgram())) {
            editInputs(form, work, state);
        }

        // Remap PF keys: only ENTER / PF3 / PF7 / PF8 are valid; else force ENTER
        // (lines 370-380).
        PfKey effectiveAid = aid;
        if (effectiveAid != PfKey.ENTER
                && effectiveAid != PfKey.PFK03
                && effectiveAid != PfKey.PFK07
                && effectiveAid != PfKey.PFK08) {
            effectiveAid = PfKey.ENTER;
        }

        // IF PF3 pressed while already on this program -> exit to the main menu
        // (lines 384-406). The COBOL XCTLs immediately, so we return the redirect here.
        if (effectiveAid == PfKey.PFK03
                && PROGRAM_NAME.equals(context.getFromProgram())) {
            context.setFromTranid(TRANSACTION_ID);
            context.setFromProgram(PROGRAM_NAME);
            context.setUser();
            context.markEnter();
            context.setLastMapset(PROGRAM_NAME);
            context.setLastMap(PROGRAM_NAME);
            context.setToProgram(MENU_PROGRAM);
            context.setToTranid(MENU_TRANSACTION_ID);
            state.setErrorMessage(MSG_EXIT);   // SET WS-EXIT-MESSAGE TO TRUE
            return CardListResult.redirect();
        }

        // IF the user did not press PF8, reset the last-page flag (lines 410-414).
        if (effectiveAid != PfKey.PFK08) {
            paging.setLastPageShown(false);    // SET CA-LAST-PAGE-NOT-SHOWN TO TRUE
        }

        // Main EVALUATE TRUE (lines 418-583): decide what to do.
        if (state.isInputError()) {
            // WHEN INPUT-ERROR (lines 419-438): ask for corrections. Re-list the first
            // page unless a filter was structurally invalid (NOT-OK), then send the map.
            context.setFromProgram(PROGRAM_NAME);
            context.setLastMapset(PROGRAM_NAME);
            context.setLastMap(PROGRAM_NAME);
            work.setNextProg(PROGRAM_NAME);
            work.setNextMapset(PROGRAM_NAME);
            work.setNextMap(PROGRAM_NAME);
            if (state.getAcctFilter() != CardListState.FilterFlag.NOT_OK
                    && state.getCardFilter() != CardListState.FilterFlag.NOT_OK) {
                state.setRidCardNum("");       // WS-CARD-RID left LOW-VALUES by INITIALIZE
                readForward(form, work, paging, state);
            }
            return CardListResult.showList(state.getErrorMessage());
        }

        if (effectiveAid == PfKey.PFK07 && paging.isFirstPage()) {
            // WHEN PF7 AND CA-FIRST-PAGE (lines 439-454): already on the first page - just
            // re-list forward from the current first key.
            state.setRidCardNum(paging.getFirstCardNum());
            readForward(form, work, paging, state);
            return CardListResult.showList(state.getErrorMessage());
        }

        if (effectiveAid == PfKey.PFK03
                || (context.isProgramReenter()
                        && !PROGRAM_NAME.equals(context.getFromProgram()))) {
            // WHEN PF3 / re-enter from another program (lines 458-482): forget the past,
            // reset to the first page and list forward from the beginning.
            context.setFromTranid(TRANSACTION_ID);
            context.setFromProgram(PROGRAM_NAME);
            context.setUser();
            context.markEnter();
            context.setLastMap(PROGRAM_NAME);
            context.setLastMapset(PROGRAM_NAME);
            paging.reset();
            paging.setScreenNum(1);            // SET CA-FIRST-PAGE TO TRUE
            paging.setLastPageShown(false);    // SET CA-LAST-PAGE-NOT-SHOWN TO TRUE
            state.setRidCardNum(paging.getFirstCardNum());
            readForward(form, work, paging, state);
            return CardListResult.showList(state.getErrorMessage());
        }

        if (effectiveAid == PfKey.PFK08 && paging.isNextPageExists()) {
            // WHEN PF8 AND CA-NEXT-PAGE-EXISTS (lines 486-497): page down from the last key.
            state.setRidCardNum(paging.getLastCardNum());
            paging.setScreenNum(paging.getScreenNum() + 1);   // ADD +1 TO WS-CA-SCREEN-NUM
            readForward(form, work, paging, state);
            return CardListResult.showList(state.getErrorMessage());
        }

        if (effectiveAid == PfKey.PFK07 && !paging.isFirstPage()) {
            // WHEN PF7 AND NOT CA-FIRST-PAGE (lines 501-513): page up from the first key.
            state.setRidCardNum(paging.getFirstCardNum());
            paging.setScreenNum(paging.getScreenNum() - 1);   // SUBTRACT 1 FROM WS-CA-SCREEN-NUM
            readBackwards(form, work, paging, state);
            return CardListResult.showList(state.getErrorMessage());
        }

        if (effectiveAid == PfKey.ENTER
                && isViewRequested(state)
                && PROGRAM_NAME.equals(context.getFromProgram())) {
            // WHEN ENTER AND VIEW-REQUESTED-ON(I-SELECTED) (lines 517-541): transfer to the
            // card-detail program, passing the selected row's account and card number.
            return dispatchSelection(work, paging, state,
                    CARD_DETAIL_PROGRAM, CARD_DETAIL_TRANSACTION_ID);
        }

        if (effectiveAid == PfKey.ENTER
                && isUpdateRequested(state)
                && PROGRAM_NAME.equals(context.getFromProgram())) {
            // WHEN ENTER AND UPDATE-REQUESTED-ON(I-SELECTED) (lines 545-569): transfer to the
            // card-update program, passing the selected row's account and card number.
            return dispatchSelection(work, paging, state,
                    CARD_UPDATE_PROGRAM, CARD_UPDATE_TRANSACTION_ID);
        }

        // WHEN OTHER (lines 572-582): default - list the first page forward.
        state.setRidCardNum(paging.getFirstCardNum());
        readForward(form, work, paging, state);
        return CardListResult.showList(state.getErrorMessage());
    }

    /**
     * Edits the submitted screen inputs - the Java migration of COBOL paragraph
     * {@code 2200-EDIT-INPUTS} ({@code legacy/cbl/COCRDLIC.cbl} lines 985-1000).
     *
     * <p>Starts from a clean {@code INPUT-OK} state with the select columns unprotected
     * ({@code FLG-PROTECT-SELECT-ROWS-NO}), then edits the account filter, the card filter and
     * the selection array in the exact COBOL order via {@link #editAccount(CardWorkArea,
     * CardListState)}, {@link #editCard(CardWorkArea, CardListState)} and
     * {@link #editArray(COCRDLIForm, CardListState)}.</p>
     *
     * @param form  the bound card-list screen (source of the seven row-select flags)
     * @param work  the card work area (source of the account/card filter fields)
     * @param state the per-request validation state to populate
     */
    public void editInputs(COCRDLIForm form, CardWorkArea work, CardListState state) {
        state.setInputError(false);            // SET INPUT-OK TO TRUE
        state.setProtectSelectRows(false);     // SET FLG-PROTECT-SELECT-ROWS-NO TO TRUE

        editAccount(work, state);
        editCard(work, state);
        editArray(form, state);
    }

    /**
     * Edits the account-number filter - the Java migration of COBOL paragraph
     * {@code 2210-EDIT-ACCOUNT} ({@code legacy/cbl/COCRDLIC.cbl} lines 1003-1030).
     *
     * <p>Reproduces the COBOL {@code PIC X(11)} filter semantics:</p>
     * <ul>
     *   <li><b>Not supplied</b> - a blank field ({@code LOW-VALUES}/{@code SPACES}) or a
     *       numerically zero value ({@code CC-ACCT-ID-N EQUAL ZEROS}) leaves the filter
     *       {@code BLANK} and stores zero into {@link CardDemoContext#setAcctId(Long)}.</li>
     *   <li><b>Invalid</b> - any supplied value that is not exactly {@value #ACCOUNT_FILTER_DIGITS}
     *       digits (the {@code IS NOT NUMERIC} test over the fixed-width, left-justified,
     *       space-padded field) raises an input error with the
     *       "{@value #MSG_ACCT_FILTER_INVALID}" message, protects the select columns and
     *       stores zero.</li>
     *   <li><b>Valid</b> - an {@value #ACCOUNT_FILTER_DIGITS}-digit non-zero value is stored on
     *       both the context and the request state and flagged {@code FLG-ACCTFILTER-ISVALID}.</li>
     * </ul>
     *
     * @param work  the card work area holding the raw account-filter value
     * @param state the per-request validation state to update
     */
    public void editAccount(CardWorkArea work, CardListState state) {
        state.setAcctFilter(CardListState.FilterFlag.BLANK);   // SET FLG-ACCTFILTER-BLANK
        String raw = rightTrim(work.getAcctId());

        // Not supplied: LOW-VALUES / SPACES / CC-ACCT-ID-N EQUAL ZEROS (lines 1007-1013).
        if (raw.isEmpty() || (isAllDigits(raw) && isZero(raw))) {
            state.setAcctFilter(CardListState.FilterFlag.BLANK);
            context.setAcctId(0L);             // MOVE ZEROES TO CDEMO-ACCT-ID
            return;
        }

        // Not numeric / not 11 digits (lines 1017-1025).
        if (!(isAllDigits(raw) && raw.length() == ACCOUNT_FILTER_DIGITS)) {
            state.setInputError(true);
            state.setAcctFilter(CardListState.FilterFlag.NOT_OK);
            state.setProtectSelectRows(true);
            state.setErrorMessage(MSG_ACCT_FILTER_INVALID);
            context.setAcctId(0L);             // MOVE ZERO TO CDEMO-ACCT-ID
            return;
        }

        // Valid (lines 1026-1029).
        Long value = Long.valueOf(raw);
        context.setAcctId(value);              // MOVE CC-ACCT-ID TO CDEMO-ACCT-ID
        state.setAcctFilterValue(value);
        state.setAcctFilter(CardListState.FilterFlag.VALID);   // SET FLG-ACCTFILTER-ISVALID
    }

    /**
     * Edits the card-number filter - the Java migration of COBOL paragraph
     * {@code 2220-EDIT-CARD} ({@code legacy/cbl/COCRDLIC.cbl} lines 1036-1066).
     *
     * <p>Mirrors {@link #editAccount(CardWorkArea, CardListState)} for the
     * {@code PIC X(16)} card filter. As in the COBOL, the
     * "{@value #MSG_CARD_FILTER_INVALID}" message is only set when no earlier edit has
     * already produced a message ({@code IF WS-ERROR-MSG-OFF}), so an account-filter error
     * takes precedence over a card-filter error.</p>
     *
     * @param work  the card work area holding the raw card-filter value
     * @param state the per-request validation state to update
     */
    public void editCard(CardWorkArea work, CardListState state) {
        state.setCardFilter(CardListState.FilterFlag.BLANK);   // SET FLG-CARDFILTER-BLANK
        String raw = rightTrim(work.getCardNum());

        // Not supplied (lines 1042-1048).
        if (raw.isEmpty() || (isAllDigits(raw) && isZero(raw))) {
            state.setCardFilter(CardListState.FilterFlag.BLANK);
            context.setCardNum(null);          // MOVE ZEROES TO CDEMO-CARD-NUM (no filter)
            return;
        }

        // Not numeric / not 16 digits (lines 1052-1062).
        if (!(isAllDigits(raw) && raw.length() == CARD_FILTER_DIGITS)) {
            state.setInputError(true);
            state.setCardFilter(CardListState.FilterFlag.NOT_OK);
            state.setProtectSelectRows(true);
            if (isMessageOff(state)) {         // IF WS-ERROR-MSG-OFF
                state.setErrorMessage(MSG_CARD_FILTER_INVALID);
            }
            context.setCardNum(null);          // MOVE ZERO TO CDEMO-CARD-NUM
            return;
        }

        // Valid (lines 1063-1065).
        context.setCardNum(raw);               // MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM
        state.setCardFilterValue(raw);
        state.setCardFilter(CardListState.FilterFlag.VALID);   // SET FLG-CARDFILTER-ISVALID
    }

    /**
     * Edits the seven-row selection array - the Java migration of COBOL paragraph
     * {@code 2250-EDIT-ARRAY} ({@code legacy/cbl/COCRDLIC.cbl} lines 1073-1117).
     *
     * <p>Skipped entirely when a prior edit already failed ({@code IF INPUT-ERROR}). Otherwise
     * it counts the {@code S} (view) and {@code U} (update) codes across the seven rows: more
     * than one selection is the "{@value #MSG_MORE_THAN_ONE_ACTION}" error (and every selected
     * row is marked); a code that is neither {@code S}, {@code U} nor blank is the
     * "{@value #MSG_INVALID_ACTION_CODE}" error (and that row is marked). The index of the
     * selected row is captured in {@link CardListState#getSelectedRowIndex()} (zero when none),
     * exactly as the COBOL {@code I-SELECTED}.</p>
     *
     * @param form  the bound card-list screen (source of {@code CRDSEL1..7})
     * @param state the per-request validation state to update
     */
    public void editArray(COCRDLIForm form, CardListState state) {
        if (state.isInputError()) {            // IF INPUT-ERROR GO TO 2250-EDIT-ARRAY-EXIT
            return;
        }

        // 2100-RECEIVE-SCREEN moved CRDSELxI to WS-EDIT-SELECT(x); normalize each row here.
        String[] flags = {
                normalizeSelect(form.getCrdsel1()),
                normalizeSelect(form.getCrdsel2()),
                normalizeSelect(form.getCrdsel3()),
                normalizeSelect(form.getCrdsel4()),
                normalizeSelect(form.getCrdsel5()),
                normalizeSelect(form.getCrdsel6()),
                normalizeSelect(form.getCrdsel7())
        };
        for (int i = 0; i < MAX_SCREEN_LINES; i++) {
            state.getSelectFlags()[i] = flags[i];
        }

        // INSPECT WS-EDIT-SELECT-FLAGS TALLYING I FOR ALL 'S' ALL 'U' (lines 1079-1082).
        int selectionCount = 0;
        for (String flag : flags) {
            if (SELECT_VIEW.equals(flag) || SELECT_UPDATE.equals(flag)) {
                selectionCount++;
            }
        }

        // IF I > 1 -> more than one action selected (lines 1084-1095).
        if (selectionCount > 1) {
            state.setInputError(true);
            state.setMoreThanOneAction(true);
            state.setErrorMessage(MSG_MORE_THAN_ONE_ACTION);   // SET WS-MORE-THAN-1-ACTION
        }

        // PERFORM VARYING I FROM 1 BY 1 UNTIL I > 7 (lines 1097-1115).
        state.setSelectedRowIndex(0);          // MOVE ZERO TO I-SELECTED
        for (int i = 1; i <= MAX_SCREEN_LINES; i++) {
            String flag = flags[i - 1];
            if (SELECT_VIEW.equals(flag) || SELECT_UPDATE.equals(flag)) {   // WHEN SELECT-OK(I)
                state.setSelectedRowIndex(i);  // MOVE I TO I-SELECTED
                if (state.isMoreThanOneAction()) {
                    state.getSelectRowError()[i - 1] = true;
                }
            } else if (flag.isEmpty()) {       // WHEN SELECT-BLANK(I)
                // CONTINUE
                continue;
            } else {                           // WHEN OTHER - invalid action code
                state.setInputError(true);
                state.getSelectRowError()[i - 1] = true;
                if (isMessageOff(state)) {     // IF WS-ERROR-MSG-OFF
                    state.setErrorMessage(MSG_INVALID_ACTION_CODE);
                }
            }
        }
    }

    /**
     * Reads a page of cards forward - the Java migration of COBOL paragraph
     * {@code 9000-READ-FORWARD} ({@code legacy/cbl/COCRDLIC.cbl} lines 1123-1260).
     *
     * <p>Reproduces the {@code STARTBR ... GTEQ} / {@code READNEXT} browse over the
     * {@code CARDDAT} cluster: it clears the seven rows, positions the cursor at the first
     * card whose number is greater-than-or-equal to the browse key
     * ({@link CardListState#getRidCardNum()}), then collects up to
     * {@value #MAX_SCREEN_LINES} surviving rows (each vetted by
     * {@link #filterRecords(Card, CardListState)}). The first surviving row seeds the
     * {@code WS-CA-FIRST-*} keyset cursor; once seven rows are collected it records the
     * {@code WS-CA-LAST-*} key and peeks one record beyond to set
     * {@link CardListPagingState#isNextPageExists()}. Running off the end of the browse is a
     * normal end-of-page (signalled internally by {@link EndOfFileException}) that stops the
     * loop and, when it happens on an empty first page, raises the
     * "{@value #MSG_NO_RECORDS_FOUND}" message - never a hard error.</p>
     *
     * <p>The COBOL browse is realized against an in-memory, ascending-by-card-number list
     * built by {@link #buildForwardWindow(CardListState)}: the account-filtered path uses the
     * {@code CARDAIX} alternate-index-equivalent derived query, a card-number filter resolves to a
     * unique primary-key point lookup, and the unfiltered path reads a bounded keyset window rather
     * than the whole base cluster (review finding&nbsp;#21). This service performs no writes.</p>
     *
     * @param form   the bound card-list screen whose row fields are populated for display
     * @param work   the card work area (unused for I/O here; carried for signature symmetry
     *               with the COBOL {@code PERFORM} chain and the paired controller)
     * @param paging the program-local paging state whose keyset cursor and rows are updated
     * @param state  the per-request state supplying the filters and browse key and receiving
     *               any browse-outcome message
     */
    public void readForward(COCRDLIForm form,
                            CardWorkArea work,
                            CardListPagingState paging,
                            CardListState state) {
        paging.clearRows();                    // MOVE LOW-VALUES TO WS-ALL-ROWS
        clearFormRows(form);

        CardBrowseCursor cursor = new CardBrowseCursor(buildForwardWindow(state));
        cursor.startBrowseGteq(state.getRidCardNum());     // EXEC CICS STARTBR ... GTEQ

        int counter = 0;                       // MOVE ZEROES TO WS-SCRN-COUNTER
        paging.setNextPageExists(true);        // SET CA-NEXT-PAGE-EXISTS TO TRUE
        boolean loopExit = false;

        while (!loopExit) {
            Card card;
            try {
                card = cursor.readNext();      // EXEC CICS READNEXT
            } catch (EndOfFileException endOfFile) {
                // WHEN DFHRESP(ENDFILE) (lines 1233-1245).
                loopExit = true;
                paging.setNextPageExists(false);           // SET CA-NEXT-PAGE-NOT-EXISTS
                if (isMessageOff(state)) {
                    state.setErrorMessage(MSG_NO_MORE_RECORDS);
                }
                if (paging.getScreenNum() == 1 && counter == 0) {
                    state.setNoRecordsFound(true);         // SET WS-NO-RECORDS-FOUND
                    state.setErrorMessage(MSG_NO_RECORDS_FOUND);
                }
                break;
            }

            // WHEN DFHRESP(NORMAL) / DFHRESP(DUPREC) (lines 1157-1232).
            boolean excluded = filterRecords(card, state);         // PERFORM 9500
            if (!excluded) {
                counter++;                     // ADD 1 TO WS-SCRN-COUNTER
                String acctNo = formatAcctNo(card.getCardAcctId());
                String cardNo = card.getCardNum();
                String status = card.getCardActiveStatus();
                paging.setRow(counter, new CardListRow(acctNo, cardNo, status));
                setFormRow(form, counter, acctNo, cardNo, status);
                if (counter == 1) {
                    paging.setFirstCardAcctId(card.getCardAcctId());   // WS-CA-FIRST-CARD-ACCT-ID
                    paging.setFirstCardNum(cardNo);                    // WS-CA-FIRST-CARD-NUM
                    if (paging.getScreenNum() == 0) {
                        paging.setScreenNum(paging.getScreenNum() + 1);
                    }
                }
            }

            // IF WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES (lines 1191-1232).
            if (counter == MAX_SCREEN_LINES) {
                loopExit = true;
                paging.setLastCardAcctId(card.getCardAcctId());        // 7th record key
                paging.setLastCardNum(card.getCardNum());
                try {
                    Card peek = cursor.readNext();                     // peek one beyond
                    paging.setNextPageExists(true);                    // SET CA-NEXT-PAGE-EXISTS
                    paging.setLastCardAcctId(peek.getCardAcctId());    // 8th record key
                    paging.setLastCardNum(peek.getCardNum());
                } catch (EndOfFileException endOfFile) {
                    paging.setNextPageExists(false);                   // SET CA-NEXT-PAGE-NOT-EXISTS
                    if (isMessageOff(state)) {
                        state.setErrorMessage(MSG_NO_MORE_RECORDS);
                    }
                }
            }
        }
        // EXEC CICS ENDBR (implicit - the in-memory cursor needs no release).
    }

    /**
     * Reads a page of cards backward - the Java migration of COBOL paragraph
     * {@code 9100-READ-BACKWARDS} ({@code legacy/cbl/COCRDLIC.cbl} lines 1264-1380).
     *
     * <p>Mirrors {@link #readForward(COCRDLIForm, CardWorkArea, CardListPagingState,
     * CardListState)} for {@code PF7} paging up. After copying the current first key to the
     * last key it positions the browse at the current first card, skips it with a first
     * {@code READPREV}, then reads backward collecting {@value #MAX_SCREEN_LINES} surviving
     * rows into the screen array from the bottom up (row seven downward) so the resulting page
     * is displayed in ascending order. The topmost surviving row seeds the new
     * {@code WS-CA-FIRST-*} key. Reaching the start of the browse simply stops the loop.</p>
     *
     * @param form   the bound card-list screen whose row fields are populated for display
     * @param work   the card work area (carried for signature symmetry; no I/O here)
     * @param paging the program-local paging state whose keyset cursor and rows are updated
     * @param state  the per-request state supplying the filters and browse key
     */
    public void readBackwards(COCRDLIForm form,
                              CardWorkArea work,
                              CardListPagingState paging,
                              CardListState state) {
        paging.clearRows();                    // MOVE LOW-VALUES TO WS-ALL-ROWS
        clearFormRows(form);

        // MOVE WS-CA-FIRST-CARDKEY TO WS-CA-LAST-CARDKEY (line 1268).
        paging.setLastCardNum(paging.getFirstCardNum());
        paging.setLastCardAcctId(paging.getFirstCardAcctId());

        CardBrowseCursor cursor = new CardBrowseCursor(buildBackwardWindow(state));
        cursor.startBrowseGteq(state.getRidCardNum());     // EXEC CICS STARTBR ... GTEQ

        int counter = MAX_SCREEN_LINES + 1;    // COMPUTE WS-SCRN-COUNTER = MAX + 1
        paging.setNextPageExists(true);        // SET CA-NEXT-PAGE-EXISTS TO TRUE
        boolean loopExit = false;

        // First READPREV skips the current first record (lines 1294-1318).
        try {
            cursor.readPrev();
            counter--;                         // SUBTRACT 1 FROM WS-SCRN-COUNTER
        } catch (EndOfFileException endOfFile) {
            // COBOL WHEN OTHER on the priming read -> stop (no records before this page).
            loopExit = true;
        }

        while (!loopExit) {
            Card card;
            try {
                card = cursor.readPrev();      // EXEC CICS READPREV
            } catch (EndOfFileException endOfFile) {
                // No ENDFILE arm in the COBOL backward loop; reaching the start stops it.
                break;
            }

            // WHEN DFHRESP(NORMAL) / DFHRESP(DUPREC) (lines 1333-1359).
            boolean excluded = filterRecords(card, state);         // PERFORM 9500
            if (!excluded) {
                String acctNo = formatAcctNo(card.getCardAcctId());
                String cardNo = card.getCardNum();
                String status = card.getCardActiveStatus();
                paging.setRow(counter, new CardListRow(acctNo, cardNo, status));
                setFormRow(form, counter, acctNo, cardNo, status);
                counter--;                     // SUBTRACT 1 FROM WS-SCRN-COUNTER
                if (counter == 0) {
                    loopExit = true;
                    paging.setFirstCardAcctId(card.getCardAcctId());   // WS-CA-FIRST-CARD-ACCT-ID
                    paging.setFirstCardNum(cardNo);                    // WS-CA-FIRST-CARD-NUM
                }
            }
        }
        // EXEC CICS ENDBR (implicit).
    }

    /**
     * Applies the account and card filters to a candidate record - the Java migration of
     * COBOL paragraph {@code 9500-FILTER-RECORDS} ({@code legacy/cbl/COCRDLIC.cbl} lines
     * 1382-1411).
     *
     * <p>A record is excluded when a valid account filter does not match the card's account id,
     * or when a valid card filter does not match the card number; otherwise it is retained.
     * When the account filter is valid the browse is already scoped by
     * {@link #buildForwardWindow(CardListState)} / {@link #buildBackwardWindow(CardListState)}, so
     * the account comparison is redundant yet preserved for one-for-one parity with the COBOL.</p>
     *
     * @param card  the candidate card record read from the browse
     * @param state the per-request state carrying the active filters
     * @return {@code true} when the record must be excluded ({@code WS-EXCLUDE-THIS-RECORD}),
     *         {@code false} when it survives ({@code WS-DONOT-EXCLUDE-THIS-RECORD})
     */
    public boolean filterRecords(Card card, CardListState state) {
        // IF FLG-ACCTFILTER-ISVALID (lines 1385-1394).
        if (state.getAcctFilter() == CardListState.FilterFlag.VALID) {
            Long filter = state.getAcctFilterValue();
            if (card.getCardAcctId() == null || !card.getCardAcctId().equals(filter)) {
                return true;                   // SET WS-EXCLUDE-THIS-RECORD TO TRUE
            }
        }

        // IF FLG-CARDFILTER-ISVALID (lines 1396-1405).
        if (state.getCardFilter() == CardListState.FilterFlag.VALID) {
            String filter = state.getCardFilterValue();
            String cardNo = rightTrim(card.getCardNum());
            if (!cardNo.equals(filter)) {
                return true;                   // SET WS-EXCLUDE-THIS-RECORD TO TRUE
            }
        }

        return false;                          // WS-DONOT-EXCLUDE-THIS-RECORD
    }

    // ----------------------------------------------------------------------------------------
    // Private helpers (selection dispatch, browse plumbing, and fixed-width edits). These carry
    // no COBOL paragraph of their own; they realize inline COBOL logic and the CICS verbs that
    // the 0000-MAIN EVALUATE and the 9000/9100 browse paragraphs rely on.
    // ----------------------------------------------------------------------------------------

    /**
     * Tests whether the currently selected row requests the card-detail view - the COBOL
     * 88-level {@code VIEW-REQUESTED-ON(I-SELECTED)} guarded against the {@code I-SELECTED = 0}
     * "no selection" case (the COBOL subscript-zero read that never matches {@code 'S'}).
     *
     * @param state the per-request state
     * @return {@code true} when a row is selected with the {@code S} code
     */
    private static boolean isViewRequested(CardListState state) {
        int index = state.getSelectedRowIndex();
        return index >= 1 && index <= MAX_SCREEN_LINES
                && SELECT_VIEW.equals(state.getSelectFlags()[index - 1]);
    }

    /**
     * Tests whether the currently selected row requests the card update - the COBOL 88-level
     * {@code UPDATE-REQUESTED-ON(I-SELECTED)} guarded against {@code I-SELECTED = 0}.
     *
     * @param state the per-request state
     * @return {@code true} when a row is selected with the {@code U} code
     */
    private static boolean isUpdateRequested(CardListState state) {
        int index = state.getSelectedRowIndex();
        return index >= 1 && index <= MAX_SCREEN_LINES
                && SELECT_UPDATE.equals(state.getSelectFlags()[index - 1]);
    }

    /**
     * Records the selected row's account and card number on the shared context and returns a
     * redirect result - the shared body of the two ENTER dispatch {@code WHEN} clauses of
     * {@code 0000-MAIN} ({@code legacy/cbl/COCRDLIC.cbl} lines 517-569). The account and card
     * are taken from the <em>persisted</em> screen rows ({@code WS-ROW-ACCTNO(I-SELECTED)} /
     * {@code WS-ROW-CARD-NUM(I-SELECTED)}), exactly as the COBOL, so the target program's
     * first-entry preload picks them up.
     *
     * @param work          the card work area receiving the next-program routing token
     * @param paging        the program-local paging state holding the persisted rows
     * @param state         the per-request state identifying the selected row
     * @param targetProgram the COBOL {@code XCTL} target program name
     * @param targetTranid  the target program's transaction id
     * @return a {@link Routing#REDIRECT} result
     */
    private CardListResult dispatchSelection(CardWorkArea work,
                                             CardListPagingState paging,
                                             CardListState state,
                                             String targetProgram,
                                             String targetTranid) {
        context.setFromTranid(TRANSACTION_ID);
        context.setFromProgram(PROGRAM_NAME);
        context.setUser();
        context.markEnter();
        context.setLastMapset(PROGRAM_NAME);
        context.setLastMap(PROGRAM_NAME);
        work.setNextProg(targetProgram);       // MOVE LIT-...PGM TO CCARD-NEXT-PROG

        CardListRow row = paging.getRow(state.getSelectedRowIndex());
        context.setAcctId(parseAccountNumber(row.accountNumber()));   // WS-ROW-ACCTNO(I-SELECTED)
        context.setCardNum(row.cardNumber());                         // WS-ROW-CARD-NUM(I-SELECTED)

        context.setToProgram(targetProgram);
        context.setToTranid(targetTranid);
        return CardListResult.redirect();
    }

    /**
     * Builds the ascending-by-card-number list backing a single {@link #readForward} page - the
     * {@code STARTBR ... GTEQ}/{@code READNEXT} data source, bounded so the whole base cluster is
     * never materialized (review finding&nbsp;#21). The returned list is always sorted ascending by
     * card number because {@link CardBrowseCursor} positions and reads over an ascending list.
     *
     * <p>Three cases mirror the COBOL {@code 9500-FILTER-RECORDS} outcomes exactly:</p>
     * <ol>
     *   <li><strong>Valid account filter</strong> ({@code FLG-ACCTFILTER-ISVALID}): the
     *       {@code CARDAIX} alternate-index-equivalent path
     *       ({@link CardRepository#findByCardAcctIdOrderByCardNumAsc(Long)}), inherently bounded to a
     *       single account and therefore left unwindowed. The in-loop account comparison in
     *       {@link #filterRecords(Card, CardListState)} is redundant on this path (kept for parity),
     *       and a co-active card filter still selects the unique matching card within the account.</li>
     *   <li><strong>Valid card filter only</strong> ({@code FLG-CARDFILTER-ISVALID}, no account
     *       filter): {@code CARD-NUM} is the unique primary key, so at most one record survives the
     *       in-loop filter. The full-cluster browse-to-{@code ENDFILE} is reproduced by a single
     *       primary-key point lookup ({@link CardRepository#findById(Object)}); an out-of-range or
     *       absent key yields an empty list, exactly matching the COBOL "no records found" outcome
     *       because every excluded record leaves the counter, rows, keys and next-page flag
     *       untouched.</li>
     *   <li><strong>No filter</strong>: a bounded ascending keyset window at or beyond the browse key
     *       ({@link CardRepository#findByCardNumGreaterThanEqualOrderByCardNumAsc(String, Limit)}). A
     *       blank/{@code LOW-VALUES} key (the empty string) selects the first page. The
     *       {@value #BROWSE_WINDOW}-row window covers the {@value #MAX_SCREEN_LINES} display rows plus
     *       the page-boundary look-ahead.</li>
     * </ol>
     *
     * @param state the per-request state carrying the filters and the forward browse key
     * @return the scoped, ascending-ordered list of cards to browse forward
     */
    private List<Card> buildForwardWindow(CardListState state) {
        if (state.getAcctFilter() == CardListState.FilterFlag.VALID) {
            return cardRepository.findByCardAcctIdOrderByCardNumAsc(state.getAcctFilterValue());
        }
        if (state.getCardFilter() == CardListState.FilterFlag.VALID) {
            return cardRepository.findById(state.getCardFilterValue())
                    .map(List::of)
                    .orElseGet(List::of);
        }
        return cardRepository.findByCardNumGreaterThanEqualOrderByCardNumAsc(
                state.getRidCardNum(), Limit.of(BROWSE_WINDOW));
    }

    /**
     * Builds the ascending-by-card-number list backing a single {@link #readBackwards} page - the
     * {@code STARTBR ... GTEQ}/{@code READPREV} data source, bounded so the whole base cluster is
     * never materialized (review finding&nbsp;#21). Like {@link #buildForwardWindow(CardListState)}
     * the returned list is ascending, because the cursor positions with {@code startBrowseGteq} and
     * walks backward with {@code readPrev} over an ascending list.
     *
     * <p>The account-filter and card-filter cases are identical to
     * {@link #buildForwardWindow(CardListState)} (an account-scoped list and a unique-key point
     * lookup are both valid in either direction). For the unfiltered case a descending keyset window
     * at or below the browse key
     * ({@link CardRepository#findByCardNumLessThanEqualOrderByCardNumDesc(String, Limit)}) is read
     * and then reversed to ascending order; the cursor positions at the boundary key, the priming
     * {@code READPREV} skips it, and the preceding {@value #MAX_SCREEN_LINES} rows are read. The
     * {@value #BROWSE_WINDOW}-row window covers the priming read plus the display rows.</p>
     *
     * @param state the per-request state carrying the filters and the backward browse key
     * @return the scoped, ascending-ordered list of cards to browse backward
     */
    private List<Card> buildBackwardWindow(CardListState state) {
        if (state.getAcctFilter() == CardListState.FilterFlag.VALID) {
            return cardRepository.findByCardAcctIdOrderByCardNumAsc(state.getAcctFilterValue());
        }
        if (state.getCardFilter() == CardListState.FilterFlag.VALID) {
            return cardRepository.findById(state.getCardFilterValue())
                    .map(List::of)
                    .orElseGet(List::of);
        }
        List<Card> window = new ArrayList<>(cardRepository.findByCardNumLessThanEqualOrderByCardNumDesc(
                state.getRidCardNum(), Limit.of(BROWSE_WINDOW)));
        Collections.reverse(window);
        return window;
    }

    /**
     * Writes one populated row to the screen ({@code WS-ROW-*} to the map output fields, the
     * data half of {@code 1000-SEND-MAP}). Row one has no protected-status symbolic in the
     * copybook, but all seven rows carry account number, card number and status output fields.
     *
     * @param form   the bound card-list screen to populate
     * @param row    the one-based row index ({@code 1}..{@value #MAX_SCREEN_LINES})
     * @param acctNo the account number to display
     * @param cardNo the card number to display
     * @param status the card active-status flag to display
     */
    private static void setFormRow(COCRDLIForm form, int row,
                                   String acctNo, String cardNo, String status) {
        switch (row) {
            case 1 -> {
                form.setAcctno1(acctNo);
                form.setCrdnum1(cardNo);
                form.setCrdsts1(status);
            }
            case 2 -> {
                form.setAcctno2(acctNo);
                form.setCrdnum2(cardNo);
                form.setCrdsts2(status);
            }
            case 3 -> {
                form.setAcctno3(acctNo);
                form.setCrdnum3(cardNo);
                form.setCrdsts3(status);
            }
            case 4 -> {
                form.setAcctno4(acctNo);
                form.setCrdnum4(cardNo);
                form.setCrdsts4(status);
            }
            case 5 -> {
                form.setAcctno5(acctNo);
                form.setCrdnum5(cardNo);
                form.setCrdsts5(status);
            }
            case 6 -> {
                form.setAcctno6(acctNo);
                form.setCrdnum6(cardNo);
                form.setCrdsts6(status);
            }
            case 7 -> {
                form.setAcctno7(acctNo);
                form.setCrdnum7(cardNo);
                form.setCrdsts7(status);
            }
            default -> {
                // Row indices are always 1..7 in the browse loops; no other value is reachable.
            }
        }
    }

    /**
     * Clears all seven screen rows to blanks - the effect of {@code MOVE LOW-VALUES TO
     * WS-ALL-ROWS} as seen on the rendered map before a fresh page is written.
     *
     * @param form the bound card-list screen to clear
     */
    private static void clearFormRows(COCRDLIForm form) {
        for (int row = 1; row <= MAX_SCREEN_LINES; row++) {
            setFormRow(form, row, "", "", "");
        }
    }

    /**
     * Formats an account id as its fixed-width {@code PIC X(11)} display form (left zero-padded
     * to {@value #ACCOUNT_FILTER_DIGITS} digits), mirroring the COBOL {@code MOVE} of a
     * {@code 9(11)} field into the {@code X(11)} screen row.
     *
     * @param acctId the numeric account id (may be {@code null})
     * @return the zero-padded 11-character account number, or the empty string when
     *         {@code acctId} is {@code null}
     */
    private static String formatAcctNo(Long acctId) {
        if (acctId == null) {
            return "";
        }
        return String.format("%011d", acctId);
    }

    /**
     * Parses a persisted screen account number ({@code WS-ROW-ACCTNO}) back into its numeric
     * form for the shared context. A blank or non-numeric value yields zero, mirroring the
     * COBOL {@code MOVE} of a {@code LOW-VALUES}/blank {@code X(11)} row into the {@code 9(11)}
     * {@code CDEMO-ACCT-ID}.
     *
     * @param accountNumber the persisted display account number
     * @return the numeric account id, or {@code 0} when blank or non-numeric
     */
    private static Long parseAccountNumber(String accountNumber) {
        String raw = rightTrim(accountNumber);
        if (raw.isEmpty() || !isAllDigits(raw)) {
            return 0L;
        }
        return Long.valueOf(raw);
    }

    /**
     * Right-trims trailing blanks from a fixed-width value, treating {@code null} as empty.
     *
     * @param value the value to trim
     * @return the value with trailing whitespace removed, never {@code null}
     */
    private static String rightTrim(String value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailing();
    }

    /**
     * Tests whether a value is non-empty and composed solely of ASCII digits, reproducing the
     * COBOL {@code IS NUMERIC} test for a {@code PIC X} field.
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is non-empty and all digits
     */
    private static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests whether an all-digit value is numerically zero ({@code CC-*-N EQUAL ZEROS}); only
     * called after {@link #isAllDigits(String)} has confirmed the value is all digits.
     *
     * @param value the all-digit value to test
     * @return {@code true} when every character is {@code '0'}
     */
    private static boolean isZero(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests whether no business error message has yet been produced, reproducing the COBOL
     * 88-level {@code WS-ERROR-MSG-OFF} (a {@code LOW-VALUES}/blank message buffer).
     *
     * @param state the per-request state
     * @return {@code true} when the error message is unset or blank
     */
    private static boolean isMessageOff(CardListState state) {
        String message = state.getErrorMessage();
        return message == null || message.isEmpty();
    }

    /**
     * Normalizes a single-character row-select code to upper case, mapping {@code null}/blank to
     * the empty string (the COBOL {@code SELECT-BLANK} condition of {@code ' '}/{@code LOW-VALUES}).
     *
     * @param value the raw select code from the screen
     * @return {@code "S"}, {@code "U"}, the empty string for blank, or the other upper-cased
     *         character for an invalid code
     */
    private static String normalizeSelect(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return String.valueOf(Character.toUpperCase(trimmed.charAt(0)));
    }

    /**
     * Compares two card numbers using a deterministic bytewise ({@code C}-collation) ordering,
     * treating {@code null} as {@code LOW-VALUES} (sorts before every real key). Card numbers
     * are fixed 16-character digit strings, so lexicographic order equals numeric order and
     * matches the ascending VSAM/{@code ORDER BY} browse (AAP &sect;0.6.6).
     *
     * @param left  the left card number (may be {@code null})
     * @param right the right card number (may be {@code null})
     * @return a negative, zero or positive value per {@link String#compareTo(String)}
     */
    private static int compareCardNum(String left, String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareTo(safeRight);
    }

    /**
     * An in-memory equivalent of a CICS VSAM browse over the {@code CARDDAT} cluster. It wraps
     * the scoped, ascending-by-card-number list and a browse position, exposing the three CICS
     * verbs the read paragraphs use: {@code STARTBR ... GTEQ}, {@code READNEXT} and
     * {@code READPREV}. Exhaustion in either direction is signalled with
     * {@link EndOfFileException} - the {@code FILE STATUS 10} / {@code DFHRESP(ENDFILE)}
     * equivalent used as an internal control signal, not a surfaced error.
     */
    private static final class CardBrowseCursor {

        /** The scoped, ascending-by-card-number records being browsed. */
        private final List<Card> records;

        /** The current browse position - the index of the record the browse is positioned on. */
        private int position;

        /**
         * Creates a cursor over the given ascending-ordered record list.
         *
         * @param records the scoped, ascending-by-card-number list (never {@code null})
         */
        CardBrowseCursor(List<Card> records) {
            this.records = records;
            this.position = 0;
        }

        /**
         * Positions the browse at the first record whose card number is greater than or equal to
         * the given key ({@code EXEC CICS STARTBR ... GTEQ}). A {@code null}/blank key positions
         * at the start of the browse.
         *
         * @param ridCardNum the browse key (card number)
         */
        void startBrowseGteq(String ridCardNum) {
            for (int i = 0; i < records.size(); i++) {
                if (compareCardNum(records.get(i).getCardNum(), ridCardNum) >= 0) {
                    position = i;
                    return;
                }
            }
            position = records.size();
        }

        /**
         * Returns the record at the current position and advances forward
         * ({@code EXEC CICS READNEXT}).
         *
         * @return the next card record
         * @throws EndOfFileException when the browse is exhausted forward ({@code ENDFILE})
         */
        Card readNext() {
            if (position >= records.size()) {
                throw new EndOfFileException("End of forward browse over CARDDAT");
            }
            return records.get(position++);
        }

        /**
         * Returns the record at the current position and moves backward
         * ({@code EXEC CICS READPREV}). A position past the end is first clamped to the last
         * record (a {@code GTEQ}-beyond-end followed by {@code READPREV}).
         *
         * @return the previous card record
         * @throws EndOfFileException when the browse is exhausted backward ({@code ENDFILE})
         */
        Card readPrev() {
            if (position >= records.size()) {
                position = records.size() - 1;
            }
            if (position < 0) {
                throw new EndOfFileException("Start of backward browse over CARDDAT");
            }
            return records.get(position--);
        }
    }

    /**
     * The routing outcome of {@link CardListService#mainEntry(COCRDLIForm, PfKey, CardWorkArea,
     * CardListPagingState)}: either render the card-list screen or redirect (the COBOL
     * {@code XCTL}) to the program recorded on {@link CardDemoContext}.
     */
    public enum Routing {

        /** Render the card-list screen - the COBOL {@code 1000-SEND-MAP}. */
        SHOW_LIST,

        /** Transfer control to another program - the COBOL {@code EXEC CICS XCTL}. */
        REDIRECT
    }

    /**
     * Immutable result of the card-list main entry: the {@link Routing} plus the business error
     * line the service produced ({@code WS-ERROR-MSG}). Presentational info/paging hints remain
     * controller-owned. The error message is normalized so it is never {@code null}.
     *
     * @param routing      the routing outcome
     * @param errorMessage the business error line (empty when none)
     */
    public record CardListResult(Routing routing, String errorMessage) {

        /**
         * Canonical constructor normalizing a {@code null} error message to the empty string.
         *
         * @param routing      the routing outcome
         * @param errorMessage the business error line, or {@code null}
         */
        public CardListResult {
            if (errorMessage == null) {
                errorMessage = "";
            }
        }

        /**
         * Creates a {@link Routing#SHOW_LIST} result with no error message.
         *
         * @return the show-list result
         */
        public static CardListResult showList() {
            return new CardListResult(Routing.SHOW_LIST, "");
        }

        /**
         * Creates a {@link Routing#SHOW_LIST} result carrying the given error message.
         *
         * @param errorMessage the business error line (may be {@code null})
         * @return the show-list result
         */
        public static CardListResult showList(String errorMessage) {
            return new CardListResult(Routing.SHOW_LIST, errorMessage);
        }

        /**
         * Creates a {@link Routing#REDIRECT} result; the target is recorded on
         * {@link CardDemoContext}.
         *
         * @return the redirect result
         */
        public static CardListResult redirect() {
            return new CardListResult(Routing.REDIRECT, "");
        }

        /**
         * Indicates whether a non-empty business error message is present.
         *
         * @return {@code true} when an error message was produced
         */
        public boolean hasErrorMessage() {
            return !errorMessage.isEmpty();
        }
    }

    /**
     * One persisted card-list screen row - the Java equivalent of a {@code WS-SCREEN-ROWS} entry
     * ({@code WS-ROW-ACCTNO} / {@code WS-ROW-CARD-NUM} / {@code WS-ROW-CARD-STATUS}). Because the
     * COBOL screen rows live inside {@code WS-THIS-PROGCOMMAREA}, they persist across
     * interactions and are the source of the selection dispatch. All components are normalized to
     * never be {@code null}.
     *
     * @param accountNumber the account number in fixed-width display form
     * @param cardNumber    the card number in fixed-width display form
     * @param activeStatus  the card active-status flag
     */
    public record CardListRow(String accountNumber, String cardNumber, String activeStatus) {

        /**
         * Canonical constructor normalizing {@code null} components to the empty string.
         *
         * @param accountNumber the account number, or {@code null}
         * @param cardNumber    the card number, or {@code null}
         * @param activeStatus  the active-status flag, or {@code null}
         */
        public CardListRow {
            if (accountNumber == null) {
                accountNumber = "";
            }
            if (cardNumber == null) {
                cardNumber = "";
            }
            if (activeStatus == null) {
                activeStatus = "";
            }
        }
    }

    /**
     * The program-local paging state - the Java migration of the COBOL
     * {@code WS-THIS-PROGCOMMAREA} ({@code legacy/cbl/COCRDLIC.cbl} lines 229-260). It carries the
     * keyset cursor ({@code WS-CA-FIRST-*} / {@code WS-CA-LAST-*}), the screen number
     * ({@code WS-CA-SCREEN-NUM}), the last-page and next-page indicators, and the seven persisted
     * screen rows ({@code WS-SCREEN-DATA}). The controller holds one instance in the HTTP session
     * alongside the shared {@link CardDemoContext} and passes it into each call, reproducing the
     * two-part pseudo-conversational COMMAREA.
     */
    public static final class CardListPagingState {

        /** Shared empty row used to blank unused screen slots (immutable, safe to share). */
        private static final CardListRow EMPTY_ROW = new CardListRow("", "", "");

        /** {@code WS-CA-FIRST-CARD-NUM} - card number of the first row on the current page. */
        private String firstCardNum;

        /** {@code WS-CA-FIRST-CARD-ACCT-ID} - account id of the first row on the current page. */
        private Long firstCardAcctId;

        /** {@code WS-CA-LAST-CARD-NUM} - keyset cursor for paging forward. */
        private String lastCardNum;

        /** {@code WS-CA-LAST-CARD-ACCT-ID} - account id paired with {@link #lastCardNum}. */
        private Long lastCardAcctId;

        /** {@code WS-CA-SCREEN-NUM} - current page number ({@code CA-FIRST-PAGE VALUE 1}). */
        private int screenNum;

        /** {@code WS-CA-LAST-PAGE-DISPLAYED} - whether the last page has been shown. */
        private boolean lastPageShown;

        /** {@code WS-CA-NEXT-PAGE-IND} - whether a next page exists ({@code CA-NEXT-PAGE-EXISTS}). */
        private boolean nextPageExists;

        /** {@code WS-SCREEN-ROWS OCCURS 7} - the seven persisted screen rows (always size seven). */
        private final List<CardListRow> rows;

        /**
         * Creates a paging state positioned before the first page with seven blank rows.
         */
        public CardListPagingState() {
            this.rows = new ArrayList<>(MAX_SCREEN_LINES);
            initialize();
        }

        /**
         * Initializes every field to its {@code INITIALIZE WS-THIS-PROGCOMMAREA} default. Private
         * so the constructor never invokes an overridable method.
         */
        private void initialize() {
            this.firstCardNum = "";
            this.firstCardAcctId = null;
            this.lastCardNum = "";
            this.lastCardAcctId = null;
            this.screenNum = 0;
            this.lastPageShown = false;
            this.nextPageExists = false;
            rows.clear();
            for (int i = 0; i < MAX_SCREEN_LINES; i++) {
                rows.add(EMPTY_ROW);
            }
        }

        /**
         * Resets the entire paging state - the COBOL {@code INITIALIZE WS-THIS-PROGCOMMAREA}.
         */
        public void reset() {
            initialize();
        }

        /**
         * Clears the seven screen rows to blanks - the COBOL {@code MOVE LOW-VALUES TO
         * WS-ALL-ROWS}.
         */
        public void clearRows() {
            rows.clear();
            for (int i = 0; i < MAX_SCREEN_LINES; i++) {
                rows.add(EMPTY_ROW);
            }
        }

        /**
         * Indicates whether the current page is the first page ({@code CA-FIRST-PAGE VALUE 1}).
         *
         * @return {@code true} when {@link #getScreenNum()} equals one
         */
        public boolean isFirstPage() {
            return screenNum == 1;
        }

        /**
         * Returns the persisted row at the given one-based index ({@code WS-SCREEN-ROWS(index)}).
         *
         * @param oneBasedIndex the one-based row index ({@code 1}..{@value #MAX_SCREEN_LINES})
         * @return the persisted row (never {@code null})
         */
        public CardListRow getRow(int oneBasedIndex) {
            return rows.get(oneBasedIndex - 1);
        }

        /**
         * Stores a persisted row at the given one-based index.
         *
         * @param oneBasedIndex the one-based row index ({@code 1}..{@value #MAX_SCREEN_LINES})
         * @param row           the row to store; {@code null} is treated as a blank row
         */
        public void setRow(int oneBasedIndex, CardListRow row) {
            rows.set(oneBasedIndex - 1, row == null ? EMPTY_ROW : row);
        }

        /**
         * Returns the live list of the seven persisted screen rows for rendering.
         *
         * @return the persisted rows (size {@value #MAX_SCREEN_LINES})
         */
        public List<CardListRow> getRows() {
            return rows;
        }

        /**
         * Returns the first-row card number ({@code WS-CA-FIRST-CARD-NUM}).
         *
         * @return the first card number (never {@code null})
         */
        public String getFirstCardNum() {
            return firstCardNum;
        }

        /**
         * Sets the first-row card number ({@code WS-CA-FIRST-CARD-NUM}).
         *
         * @param firstCardNum the first card number
         */
        public void setFirstCardNum(String firstCardNum) {
            this.firstCardNum = firstCardNum == null ? "" : firstCardNum;
        }

        /**
         * Returns the first-row account id ({@code WS-CA-FIRST-CARD-ACCT-ID}).
         *
         * @return the first account id, or {@code null}
         */
        public Long getFirstCardAcctId() {
            return firstCardAcctId;
        }

        /**
         * Sets the first-row account id ({@code WS-CA-FIRST-CARD-ACCT-ID}).
         *
         * @param firstCardAcctId the first account id
         */
        public void setFirstCardAcctId(Long firstCardAcctId) {
            this.firstCardAcctId = firstCardAcctId;
        }

        /**
         * Returns the last-row/keyset card number ({@code WS-CA-LAST-CARD-NUM}).
         *
         * @return the last card number (never {@code null})
         */
        public String getLastCardNum() {
            return lastCardNum;
        }

        /**
         * Sets the last-row/keyset card number ({@code WS-CA-LAST-CARD-NUM}).
         *
         * @param lastCardNum the last card number
         */
        public void setLastCardNum(String lastCardNum) {
            this.lastCardNum = lastCardNum == null ? "" : lastCardNum;
        }

        /**
         * Returns the last-row account id ({@code WS-CA-LAST-CARD-ACCT-ID}).
         *
         * @return the last account id, or {@code null}
         */
        public Long getLastCardAcctId() {
            return lastCardAcctId;
        }

        /**
         * Sets the last-row account id ({@code WS-CA-LAST-CARD-ACCT-ID}).
         *
         * @param lastCardAcctId the last account id
         */
        public void setLastCardAcctId(Long lastCardAcctId) {
            this.lastCardAcctId = lastCardAcctId;
        }

        /**
         * Returns the current page number ({@code WS-CA-SCREEN-NUM}).
         *
         * @return the page number
         */
        public int getScreenNum() {
            return screenNum;
        }

        /**
         * Sets the current page number ({@code WS-CA-SCREEN-NUM}).
         *
         * @param screenNum the page number
         */
        public void setScreenNum(int screenNum) {
            this.screenNum = screenNum;
        }

        /**
         * Indicates whether the last page has been shown ({@code CA-LAST-PAGE-SHOWN}).
         *
         * @return {@code true} when the last page has been displayed
         */
        public boolean isLastPageShown() {
            return lastPageShown;
        }

        /**
         * Sets the last-page-shown indicator ({@code WS-CA-LAST-PAGE-DISPLAYED}).
         *
         * @param lastPageShown {@code true} when the last page has been displayed
         */
        public void setLastPageShown(boolean lastPageShown) {
            this.lastPageShown = lastPageShown;
        }

        /**
         * Indicates whether a next page exists ({@code CA-NEXT-PAGE-EXISTS}).
         *
         * @return {@code true} when a further page can be shown
         */
        public boolean isNextPageExists() {
            return nextPageExists;
        }

        /**
         * Sets the next-page indicator ({@code WS-CA-NEXT-PAGE-IND}).
         *
         * @param nextPageExists {@code true} when a further page exists
         */
        public void setNextPageExists(boolean nextPageExists) {
            this.nextPageExists = nextPageExists;
        }
    }

    /**
     * The per-request validation and browse work state - the subset of {@code WS-MISC-STORAGE}
     * that the business paragraphs read and write within a single interaction (it is not carried
     * across pseudo-conversational returns). It holds the input-error flag, the filter outcomes,
     * the seven row-select codes and their per-row error markers, the selected row index
     * ({@code I-SELECTED}), the browse key ({@code WS-CARD-RID-CARDNUM}) and the produced business
     * error message.
     */
    public static final class CardListState {

        /**
         * A filter's validation outcome: not supplied ({@code BLANK}), a valid value
         * ({@code VALID}, {@code FLG-*FILTER-ISVALID}) or structurally invalid
         * ({@code NOT_OK}, {@code FLG-*FILTER-NOT-OK}).
         */
        public enum FilterFlag {

            /** Filter not supplied ({@code FLG-*FILTER-BLANK}). */
            BLANK,

            /** Filter supplied and valid ({@code FLG-*FILTER-ISVALID}). */
            VALID,

            /** Filter supplied but structurally invalid ({@code FLG-*FILTER-NOT-OK}). */
            NOT_OK
        }

        /** {@code INPUT-ERROR} / {@code INPUT-OK} - whether any edit failed. */
        private boolean inputError;

        /** {@code FLG-ACCTFILTER-*} - account filter outcome. */
        private FilterFlag acctFilter = FilterFlag.BLANK;

        /** {@code FLG-CARDFILTER-*} - card filter outcome. */
        private FilterFlag cardFilter = FilterFlag.BLANK;

        /** Parsed account filter value when {@link #acctFilter} is {@link FilterFlag#VALID}. */
        private Long acctFilterValue;

        /** Parsed card filter value when {@link #cardFilter} is {@link FilterFlag#VALID}. */
        private String cardFilterValue;

        /** {@code WS-EDIT-SELECT} OCCURS 7 - the seven normalized row-select codes. */
        private final String[] selectFlags = new String[MAX_SCREEN_LINES];

        /** {@code WS-EDIT-SELECT-ERROR-FLAGS} - per-row select error markers. */
        private final boolean[] selectRowError = new boolean[MAX_SCREEN_LINES];

        /** {@code I-SELECTED} - one-based index of the selected row, or zero when none. */
        private int selectedRowIndex;

        /** {@code WS-MORE-THAN-1-ACTION} - whether more than one row was selected. */
        private boolean moreThanOneAction;

        /** {@code WS-ERROR-MSG} - the produced business error line (blank when off). */
        private String errorMessage;

        /** {@code WS-NO-RECORDS-FOUND} - whether the browse found no records. */
        private boolean noRecordsFound;

        /** {@code FLG-PROTECT-SELECT-ROWS} - whether the select columns are protected. */
        private boolean protectSelectRows;

        /** {@code WS-CARD-RID-CARDNUM} - the browse start key for the next read. */
        private String ridCardNum = "";

        /**
         * Creates an empty request state with blank row-select codes.
         */
        public CardListState() {
            for (int i = 0; i < MAX_SCREEN_LINES; i++) {
                selectFlags[i] = "";
            }
        }

        /**
         * Indicates whether an input edit failed ({@code INPUT-ERROR}).
         *
         * @return {@code true} when an edit failed
         */
        public boolean isInputError() {
            return inputError;
        }

        /**
         * Sets the input-error flag ({@code INPUT-ERROR} / {@code INPUT-OK}).
         *
         * @param inputError {@code true} when an edit failed
         */
        public void setInputError(boolean inputError) {
            this.inputError = inputError;
        }

        /**
         * Returns the account filter outcome.
         *
         * @return the account {@link FilterFlag}
         */
        public FilterFlag getAcctFilter() {
            return acctFilter;
        }

        /**
         * Sets the account filter outcome.
         *
         * @param acctFilter the account {@link FilterFlag}
         */
        public void setAcctFilter(FilterFlag acctFilter) {
            this.acctFilter = acctFilter;
        }

        /**
         * Returns the card filter outcome.
         *
         * @return the card {@link FilterFlag}
         */
        public FilterFlag getCardFilter() {
            return cardFilter;
        }

        /**
         * Sets the card filter outcome.
         *
         * @param cardFilter the card {@link FilterFlag}
         */
        public void setCardFilter(FilterFlag cardFilter) {
            this.cardFilter = cardFilter;
        }

        /**
         * Returns the parsed account filter value (valid only when the account filter is valid).
         *
         * @return the account filter value, or {@code null}
         */
        public Long getAcctFilterValue() {
            return acctFilterValue;
        }

        /**
         * Sets the parsed account filter value.
         *
         * @param acctFilterValue the account filter value
         */
        public void setAcctFilterValue(Long acctFilterValue) {
            this.acctFilterValue = acctFilterValue;
        }

        /**
         * Returns the parsed card filter value (valid only when the card filter is valid).
         *
         * @return the card filter value, or {@code null}
         */
        public String getCardFilterValue() {
            return cardFilterValue;
        }

        /**
         * Sets the parsed card filter value.
         *
         * @param cardFilterValue the card filter value
         */
        public void setCardFilterValue(String cardFilterValue) {
            this.cardFilterValue = cardFilterValue;
        }

        /**
         * Returns the live seven-element array of normalized row-select codes.
         *
         * @return the row-select codes ({@code WS-EDIT-SELECT})
         */
        public String[] getSelectFlags() {
            return selectFlags;
        }

        /**
         * Returns the live seven-element array of per-row select error markers.
         *
         * @return the per-row error markers ({@code WS-EDIT-SELECT-ERROR-FLAGS})
         */
        public boolean[] getSelectRowError() {
            return selectRowError;
        }

        /**
         * Returns the one-based selected-row index, or zero when none ({@code I-SELECTED}).
         *
         * @return the selected row index
         */
        public int getSelectedRowIndex() {
            return selectedRowIndex;
        }

        /**
         * Sets the one-based selected-row index ({@code I-SELECTED}).
         *
         * @param selectedRowIndex the selected row index (zero when none)
         */
        public void setSelectedRowIndex(int selectedRowIndex) {
            this.selectedRowIndex = selectedRowIndex;
        }

        /**
         * Indicates whether more than one row was selected ({@code WS-MORE-THAN-1-ACTION}).
         *
         * @return {@code true} when multiple rows were selected
         */
        public boolean isMoreThanOneAction() {
            return moreThanOneAction;
        }

        /**
         * Sets the more-than-one-action flag ({@code WS-MORE-THAN-1-ACTION}).
         *
         * @param moreThanOneAction {@code true} when multiple rows were selected
         */
        public void setMoreThanOneAction(boolean moreThanOneAction) {
            this.moreThanOneAction = moreThanOneAction;
        }

        /**
         * Returns the produced business error message ({@code WS-ERROR-MSG}).
         *
         * @return the error message, or {@code null} when none
         */
        public String getErrorMessage() {
            return errorMessage;
        }

        /**
         * Sets the produced business error message ({@code WS-ERROR-MSG}).
         *
         * @param errorMessage the error message
         */
        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        /**
         * Indicates whether the browse found no records ({@code WS-NO-RECORDS-FOUND}).
         *
         * @return {@code true} when no records were found
         */
        public boolean isNoRecordsFound() {
            return noRecordsFound;
        }

        /**
         * Sets the no-records-found flag ({@code WS-NO-RECORDS-FOUND}).
         *
         * @param noRecordsFound {@code true} when no records were found
         */
        public void setNoRecordsFound(boolean noRecordsFound) {
            this.noRecordsFound = noRecordsFound;
        }

        /**
         * Indicates whether the select columns are protected ({@code FLG-PROTECT-SELECT-ROWS}).
         *
         * @return {@code true} when the select columns are protected
         */
        public boolean isProtectSelectRows() {
            return protectSelectRows;
        }

        /**
         * Sets the protect-select-rows flag ({@code FLG-PROTECT-SELECT-ROWS}).
         *
         * @param protectSelectRows {@code true} when the select columns are protected
         */
        public void setProtectSelectRows(boolean protectSelectRows) {
            this.protectSelectRows = protectSelectRows;
        }

        /**
         * Returns the browse start key ({@code WS-CARD-RID-CARDNUM}).
         *
         * @return the browse key (never {@code null})
         */
        public String getRidCardNum() {
            return ridCardNum;
        }

        /**
         * Sets the browse start key ({@code WS-CARD-RID-CARDNUM}).
         *
         * @param ridCardNum the browse key
         */
        public void setRidCardNum(String ridCardNum) {
            this.ridCardNum = ridCardNum == null ? "" : ridCardNum;
        }
    }
}
