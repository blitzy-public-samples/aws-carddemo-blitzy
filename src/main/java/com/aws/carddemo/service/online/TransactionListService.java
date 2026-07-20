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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COTRN00Form;
import com.aws.carddemo.exception.EndOfFileException;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.TransactionRepository;

/**
 * Transaction-list online service, the Java migration of the CICS COBOL program
 * {@code COTRN00C} (the AWS CardDemo paged transaction browse).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COTRN00C.cbl} &mdash; program {@code COTRN00C},
 * CICS transaction id {@code CT00}. This service preserves the original program's control
 * flow one-for-one: each business COBOL paragraph becomes exactly one Java method
 * (AAP &sect;0.3.3, &sect;0.4.1). It is the ten-row paged-browse plus selection-dispatch
 * archetype: it lists ten transactions at a time, pages forward and backward, and dispatches a
 * selected transaction to the transaction-view program {@code COTRN01C} (transaction
 * {@code CT01}).</p>
 *
 * <h2>Paragraph &rarr; method mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #mainEntry(AidKey, COTRN00Form)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey(COTRN00Form, CardDemoContext)}</li>
 *   <li>{@code PROCESS-PF7-KEY} &rarr; {@link #processPf7Key(COTRN00Form, CardDemoContext)}</li>
 *   <li>{@code PROCESS-PF8-KEY} &rarr; {@link #processPf8Key(COTRN00Form, CardDemoContext)}</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} &rarr; {@link #processPageForward(COTRN00Form, CardDemoContext, boolean, String)}</li>
 *   <li>{@code PROCESS-PAGE-BACKWARD} &rarr; {@link #processPageBackward(COTRN00Form, CardDemoContext, String)}</li>
 *   <li>{@code POPULATE-TRAN-DATA} &rarr; {@link #populateTranData(COTRN00Form, int, Transaction)}</li>
 *   <li>{@code INITIALIZE-TRAN-DATA} &rarr; {@link #initializeTranData(COTRN00Form, int)}</li>
 *   <li>{@code STARTBR-TRANSACT-FILE} &rarr; {@link #startBrowseTransactFile(List, String)}</li>
 *   <li>{@code READNEXT-TRANSACT-FILE} &rarr; {@link #readNextTransactFile(BrowseCursor)}</li>
 *   <li>{@code READPREV-TRANSACT-FILE} &rarr; {@link #readPrevTransactFile(BrowseCursor)}</li>
 *   <li>{@code ENDBR-TRANSACT-FILE} &rarr; {@link #endBrowseTransactFile(BrowseCursor)}</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr; {@link #returnToPrevScreen(CardDemoContext)}</li>
 * </ul>
 *
 * <p>The presentation paragraphs {@code SEND-TRNLST-SCREEN}, {@code RECEIVE-TRNLST-SCREEN} and
 * {@code POPULATE-HEADER-INFO} are intentionally <em>not</em> implemented here: sending and
 * receiving the 3270/BMS map and populating the header (title/date/time) are presentation
 * concerns owned by the paired {@code TransactionController}. This service contains business
 * logic only &mdash; it validates input, decides routing, drives the browse, and populates the
 * screen data rows &mdash; and never performs screen I/O.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL COMMAREA ({@code COCOM01Y}) that carried navigation state across CICS returns
 * becomes the session-scoped {@link CardDemoContext}, injected here. First entry into the
 * transaction (COBOL {@code EIBCALEN = 0}) is reproduced by {@link CardDemoContext#isNew()}; the
 * first-display-versus-resubmission distinction (COBOL {@code CDEMO-PGM-REENTER}) is reproduced
 * by {@link CardDemoContext#isProgramEnter()} / {@link CardDemoContext#markReenter()}; and the
 * {@code XCTL}/{@code RETURN TRANSID} hand-off is reproduced through the context's
 * {@code from*}/{@code to*} program fields, which the controller consults when redirecting.</p>
 *
 * <h2>Paging-state carriage (parity decision)</h2>
 * <p>{@code COTRN00C} keeps its paging cursor in a program-appended COMMAREA block
 * ({@code CDEMO-CT00-INFO}: {@code TRNID-FIRST}, {@code TRNID-LAST}, {@code PAGE-NUM},
 * {@code NEXT-PAGE-FLG}, {@code TRN-SEL-FLG}, {@code TRN-SELECTED}). The migrated
 * {@link CardDemoContext} does not expose those fields, so this service reconstructs the same
 * state from artefacts it does control, preserving behaviour exactly:</p>
 * <ul>
 *   <li><b>Page number</b> ({@code CDEMO-CT00-PAGE-NUM}) round-trips through the screen's
 *       {@code PAGENUM} field ({@link COTRN00Form#getPagenum()} / {@code setPagenum}).</li>
 *   <li><b>Cursor keys</b> ({@code CDEMO-CT00-TRNID-FIRST} / {@code -TRNID-LAST}) are the first
 *       and last populated data rows of the current page &mdash; {@link COTRN00Form#getTrnid01()}
 *       and the last non-blank {@code TRNIDnn}.</li>
 *   <li><b>Next-page flag</b> ({@code CDEMO-CT00-NEXT-PAGE-FLG}) is recomputed on demand by
 *       checking whether any transaction key sorts strictly after the current last key; this is
 *       provably equivalent to the persisted flag and preserves the exact "already at the bottom"
 *       versus "reached the bottom" message distinction.</li>
 *   <li><b>Selected transaction</b> ({@code CDEMO-CT00-TRN-SELECTED}) is surfaced on
 *       {@link TransactionListResult#selectedTransactionId()} for the controller to forward to
 *       {@code COTRN01C}, since the context has no dedicated field.</li>
 * </ul>
 *
 * <h2>Browse model (AAP &sect;0.3.3, &sect;0.6.6)</h2>
 * <p>The VSAM {@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR} browse over the
 * {@code TRANSACT} KSDS is reproduced with Spring Data: each page is read as a bounded, key-ordered
 * window (review finding #21) &mdash; a forward page via
 * {@code findByTranIdGreaterThanEqualOrderByTranIdAsc} and a backward page via
 * {@code findByTranIdLessThanEqualOrderByTranIdDesc}, each capped at the page size plus the
 * browse's skip-one and look-ahead reads &mdash; and the cursor is then navigated in memory over
 * that ordered window ({@link BrowseCursor}), rather than materialising the whole table. Transaction
 * ids are sixteen-character zero-padded numeric strings, so ASCII/{@code String} ordering matches
 * the legacy key order under the database's {@code C}/{@code POSIX} collation. The page size is
 * exactly ten, matching the BMS map. {@code STARTBR NOTFND} maps to
 * {@link RecordNotFoundException} and {@code READNEXT}/{@code READPREV ENDFILE} map to
 * {@link EndOfFileException}; both are treated as <em>normal</em> end-of-page control signals
 * (caught internally to set the boundary message), never as hard errors. A genuinely abnormal
 * data-access failure is left to propagate to the {@code GlobalExceptionHandler}
 * ({@code @ControllerAdvice}, AAP &sect;0.6.5).</p>
 *
 * <h2>Outcome model</h2>
 * <p>Because the controller performs the redirect and the screen rendering, each business entry
 * method returns an immutable {@link TransactionListResult} describing what should happen next: a
 * redirect to a target program (optionally carrying the selected transaction id), or a redisplay
 * of the list with a message flagged as an error line or as a neutral informational line. As it
 * runs, the service populates the ten data rows of the supplied {@link COTRN00Form} in place
 * (the COBOL {@code POPULATE-TRAN-DATA} moving into the shared map storage); the controller
 * renders the mutated form.</p>
 *
 * <h2>Access and design constraints</h2>
 * <ul>
 *   <li><b>Read-only:</b> the browse never mutates persistent state.</li>
 *   <li><b>Constructor injection:</b> the service depends only on the session context and the
 *       transaction repository.</li>
 *   <li><b>Message literals:</b> the boundary, invalid-selection, non-numeric and invalid-key
 *       messages are declared as local constants that mirror the COBOL literals (the invalid-key
 *       text mirrors {@code CCDA-MSG-INVALID-KEY} from {@code legacy/cpy/CSMSG01Y.cpy}); the
 *       shared message-constants holder is not a declared dependency of this service, so its
 *       values are reproduced locally rather than imported.</li>
 * </ul>
 */
@Service
public class TransactionListService {

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COTRN00C'} &mdash; this program's name. */
    private static final String PROGRAM_NAME = "COTRN00C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CT00'} &mdash; this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CT00";

    /**
     * Sign-on program (COBOL literal {@code 'COSGN00C'}) &mdash; the destination for the
     * first-entry bounce ({@code EIBCALEN = 0}), reproduced through {@code RETURN-TO-PREV-SCREEN}.
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Main-menu program (COBOL literal {@code 'COMEN01C'}) &mdash; the PF3 return destination
     * ({@code MAIN-PARA} {@code WHEN DFHPF3}).
     */
    private static final String MENU_PROGRAM = "COMEN01C";

    /**
     * Transaction-view program (COBOL literal {@code 'COTRN01C'}) &mdash; the {@code XCTL}
     * destination for an {@code 'S'} selection in {@code PROCESS-ENTER-KEY}.
     */
    private static final String DETAIL_PROGRAM = "COTRN01C";

    /**
     * Transaction-view transaction id (COBOL literal {@code 'CT01'}) &mdash; recorded as the
     * hand-off target transaction when dispatching a selected transaction to {@code COTRN01C}.
     */
    private static final String DETAIL_TRANSACTION_ID = "CT01";

    /**
     * Number of data rows on the transaction-list screen (COBOL browse limit of ten rows, the
     * fixed BMS map size). The browse advances exactly this many records per page.
     */
    private static final int PAGE_SIZE = 10;

    /**
     * Number of records fetched into one browse window (review finding #21).
     *
     * <p>The forward browse issues at most a skip-one {@code READNEXT} (the PF8 case), then up to
     * {@link #PAGE_SIZE} row {@code READNEXT}s, then one look-ahead {@code READNEXT}; the backward
     * browse issues the symmetric {@code READPREV} sequence. The most records the browse can consume
     * from the positioned start key is therefore {@code PAGE_SIZE + 2}. Fetching exactly this many
     * rows in the bounded keyset window guarantees the window contains every record the legacy
     * browse would have read for the page, so the paged output is byte-identical to the full-table
     * scan it replaces while never materialising the whole {@code TRANSACT} table.</p>
     */
    private static final int BROWSE_WINDOW = PAGE_SIZE + 2;

    /** Width of the {@code TRAN-ID} key ({@code PIC X(16)}), used when right-padding a filter. */
    private static final int TRAN_ID_WIDTH = 16;

    /**
     * Number of high-order integer digits displayed in the amount field. COBOL moves
     * {@code TRAN-AMT} ({@code PIC S9(09)V99}) into {@code WS-TRAN-AMT} ({@code PIC +99999999.99}),
     * whose eight integer digits truncate the high-order (ninth) digit; the sign is always shown.
     */
    private static final int AMOUNT_INTEGER_DIGITS = 8;

    /** Fixed fractional scale (two decimal places) of the packed-decimal amount. */
    private static final int AMOUNT_SCALE = 2;

    /**
     * Modulus (ten raised to {@link #AMOUNT_INTEGER_DIGITS}) used to keep only the low-order eight
     * integer digits of the amount, reproducing the high-order truncation when the nine-integer-
     * digit {@code TRAN-AMT} is moved into the eight-integer-digit edited field.
     */
    private static final BigDecimal AMOUNT_MODULUS = BigDecimal.TEN.pow(AMOUNT_INTEGER_DIGITS);

    /**
     * {@link String#format} pattern for the edited amount: eight zero-padded integer digits, a
     * decimal point, and two fractional digits (the sign is prepended separately), reproducing the
     * COBOL {@code PIC +99999999.99} edit mask.
     */
    private static final String AMOUNT_FORMAT = "%0" + AMOUNT_INTEGER_DIGITS + "d.%02d";

    /** Screen width of the transaction description field (COBOL {@code TDESCnnI PIC X(26)}). */
    private static final int TRAN_DESC_WIDTH = 26;

    /**
     * Date formatter producing the {@code MM/DD/YY} display used by the row date field, matching
     * the COBOL {@code WS-CURDATE-MM-DD-YY} built from the transaction timestamp (two-digit month,
     * two-digit day, and the last two digits of the year). {@link Locale#ROOT} keeps the output
     * locale-independent.
     */
    private static final DateTimeFormatter TRAN_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * Selection-flag value {@code 'S'} (upper case) &mdash; the only valid transaction-list
     * selection, dispatching the chosen transaction to the view program ({@code PROCESS-ENTER-KEY}
     * {@code EVALUATE ... WHEN 'S' WHEN 's'}).
     */
    private static final String SELECT_FLAG_UPPER = "S";

    /** Selection-flag value {@code 's'} (lower case) &mdash; accepted alongside {@code 'S'}. */
    private static final String SELECT_FLAG_LOWER = "s";

    /**
     * Invalid-key message. Mirrors COBOL {@code CCDA-MSG-INVALID-KEY} (from
     * {@code legacy/cpy/CSMSG01Y.cpy}), moved to {@code WS-MESSAGE} in {@code MAIN-PARA} for any
     * unmapped AID key. Rendered as an error line.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Invalid-selection message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code PROCESS-ENTER-KEY} when the selection flag is present but is not {@code 'S'}/{@code 's'}.
     * Rendered as a neutral informational line (the COBOL path does not raise the error flag).
     */
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /**
     * Non-numeric filter message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code PROCESS-ENTER-KEY} when the {@code TRNIDIN} filter is present but not numeric.
     * Rendered as an error line (the COBOL path raises the error flag).
     */
    private static final String MSG_TRAN_ID_NUMERIC = "Tran ID must be Numeric ...";

    /**
     * Start-of-file boundary message. Byte-exact COBOL literal set in {@code STARTBR-TRANSACT-FILE}
     * when the browse start returns {@code NOTFND} (no record at or beyond the start key).
     */
    private static final String MSG_STARTBR_TOP = "You are at the top of the page...";

    /**
     * Bottom-of-page boundary message. Byte-exact COBOL literal set in {@code READNEXT-TRANSACT-FILE}
     * when a forward read reaches {@code ENDFILE}.
     */
    private static final String MSG_READNEXT_BOTTOM = "You have reached the bottom of the page...";

    /**
     * Top-of-page boundary message. Byte-exact COBOL literal set in {@code READPREV-TRANSACT-FILE}
     * when a backward read reaches {@code ENDFILE}.
     */
    private static final String MSG_READPREV_TOP = "You have reached the top of the page...";

    /**
     * Already-at-top message. Byte-exact COBOL literal set in {@code PROCESS-PF7-KEY} when the user
     * pages up while already on the first page.
     */
    private static final String MSG_ALREADY_TOP = "You are already at the top of the page...";

    /**
     * Already-at-bottom message. Byte-exact COBOL literal set in {@code PROCESS-PF8-KEY} when the
     * user pages down while no further page exists.
     */
    private static final String MSG_ALREADY_BOTTOM = "You are already at the bottom of the page...";

    /**
     * Session-scoped navigation and selection context, the modern replacement for the COBOL
     * COMMAREA ({@code COCOM01Y}). Injected as a Spring session-scoped proxy.
     */
    private final CardDemoContext context;

    /**
     * Transaction repository over the {@code TRANSACT} table, the migrated VSAM KSDS. Supplies the
     * ordered full-set read that backs the in-memory browse cursor.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the transaction-list service via Spring constructor injection.
     *
     * <p>A single constructor means no {@code @Autowired} annotation is required. Neither argument
     * is dereferenced here, so the constructor introduces no {@code this}-escape.</p>
     *
     * @param context               the session-scoped CardDemo context (COMMAREA replacement);
     *                              must not be {@code null}
     * @param transactionRepository the transaction repository backing the browse; must not be
     *                              {@code null}
     */
    public TransactionListService(CardDemoContext context, TransactionRepository transactionRepository) {
        this.context = context;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Attention-identifier (AID) keys handled by {@link #mainEntry(AidKey, COTRN00Form)},
     * mirroring the COBOL {@code EVALUATE EIBAID} of {@code MAIN-PARA}.
     *
     * <p>The paired {@code TransactionController} maps the inbound HTTP submission (the pressed
     * button or PF key) to one of these constants before delegating to the service. Only the keys
     * with distinct behaviour in {@code COTRN00C} are modelled: {@link #ENTER} ({@code DFHENTER}),
     * {@link #PF3} ({@code DFHPF3}), {@link #PF7} ({@code DFHPF7}) and {@link #PF8}
     * ({@code DFHPF8}); every other key collapses to {@link #OTHER}, matching the COBOL
     * {@code WHEN OTHER} default.</p>
     */
    public enum AidKey {

        /** COBOL {@code DFHENTER} &mdash; the ENTER key; applies selection and/or filter. */
        ENTER,

        /** COBOL {@code DFHPF3} &mdash; the PF3 key; returns to the main-menu screen. */
        PF3,

        /** COBOL {@code DFHPF7} &mdash; the PF7 key; pages backward (up). */
        PF7,

        /** COBOL {@code DFHPF8} &mdash; the PF8 key; pages forward (down). */
        PF8,

        /** COBOL {@code WHEN OTHER} &mdash; any other key; yields the invalid-key message. */
        OTHER
    }

    /**
     * The kind of action a {@link TransactionListResult} asks the controller to take.
     */
    public enum RoutingAction {

        /**
         * Transfer control to another program (the COBOL {@code XCTL} equivalent): either the
         * PF3/first-entry navigation hand-off or the {@code 'S'}-selection dispatch to the
         * transaction-view program.
         */
        REDIRECT,

        /**
         * Redisplay the transaction-list screen (the COBOL {@code SEND-TRNLST-SCREEN} equivalent),
         * optionally with a boundary, invalid-selection or error message.
         */
        SHOW_LIST
    }

    /**
     * Immutable outcome of a transaction-list interaction, describing what the controller should
     * do next: perform a redirect, or redisplay the list with an optional message.
     *
     * <p>Two shapes are produced:</p>
     * <ul>
     *   <li><b>Redirect</b> &mdash; {@link #action()} is {@link RoutingAction#REDIRECT} and
     *       {@link #targetProgram()} is set; the controller redirects to the route mapped from
     *       that program name. For an {@code 'S'} selection, {@link #selectedTransactionId()} also
     *       carries the chosen transaction id for the view program to preload. This reproduces the
     *       COBOL {@code XCTL}.</li>
     *   <li><b>Redisplay</b> &mdash; {@link #action()} is {@link RoutingAction#SHOW_LIST}; the
     *       controller redisplays the list (the data rows already populated on the form). When a
     *       {@link #message()} is present, {@link #error()} distinguishes an error line (COBOL
     *       error flag raised, styled red) from a neutral informational/boundary line.</li>
     * </ul>
     *
     * @param action                the kind of follow-up action; never {@code null}
     * @param targetProgram         the redirect target program name, or {@code null} for a
     *                              redisplay outcome
     * @param selectedTransactionId the selected transaction id to forward to the view program, or
     *                              {@code null} when not dispatching a selection
     * @param message               the message to redisplay, or {@code null} when there is none
     * @param error                 {@code true} when {@code message} is an error line; {@code false}
     *                              for a neutral informational/boundary line or a redirect
     */
    public record TransactionListResult(RoutingAction action, String targetProgram,
            String selectedTransactionId, String message, boolean error) {

        /**
         * Canonical constructor; normalizes a {@code null} action to a safe default is <em>not</em>
         * performed &mdash; a {@code null} action is rejected so that every result has a defined
         * follow-up action.
         *
         * @throws NullPointerException if {@code action} is {@code null}
         */
        public TransactionListResult {
            if (action == null) {
                throw new NullPointerException("action must not be null");
            }
        }

        /**
         * Reports whether this outcome is a redirect (the COBOL {@code XCTL} equivalent).
         *
         * @return {@code true} when {@link #action()} is {@link RoutingAction#REDIRECT}
         */
        public boolean isRedirect() {
            return action == RoutingAction.REDIRECT;
        }

        /**
         * Reports whether this outcome carries a message to redisplay.
         *
         * @return {@code true} when a non-blank {@link #message()} is present
         */
        public boolean hasMessage() {
            return message != null && !message.isBlank();
        }

        /**
         * Reports whether this outcome carries a selected transaction id to forward.
         *
         * @return {@code true} when a non-blank {@link #selectedTransactionId()} is present
         */
        public boolean hasSelection() {
            return selectedTransactionId != null && !selectedTransactionId.isBlank();
        }

        /**
         * Creates a plain redirect outcome targeting the given program (no selection forwarded).
         *
         * @param targetProgram the target program name (the COBOL {@code XCTL} destination)
         * @return a redirect outcome
         */
        private static TransactionListResult redirect(String targetProgram) {
            return new TransactionListResult(RoutingAction.REDIRECT, targetProgram, null, null, false);
        }

        /**
         * Creates a redirect outcome that dispatches a selected transaction to the view program.
         *
         * @param targetProgram         the view program name (the COBOL {@code XCTL} destination)
         * @param selectedTransactionId the chosen transaction id to preload
         * @return a selection-dispatch redirect outcome
         */
        private static TransactionListResult redirectWithSelection(String targetProgram,
                String selectedTransactionId) {
            return new TransactionListResult(RoutingAction.REDIRECT, targetProgram,
                    selectedTransactionId, null, false);
        }

        /**
         * Creates a plain redisplay outcome with no message.
         *
         * @return a redisplay outcome
         */
        private static TransactionListResult showList() {
            return new TransactionListResult(RoutingAction.SHOW_LIST, null, null, null, false);
        }

        /**
         * Creates a redisplay outcome carrying a message.
         *
         * @param message the message to redisplay (may be {@code null} for none)
         * @param error   {@code true} for an error line; {@code false} for a neutral line
         * @return a redisplay outcome with the given message
         */
        private static TransactionListResult showList(String message, boolean error) {
            return new TransactionListResult(RoutingAction.SHOW_LIST, null, null, message, error);
        }
    }

    /**
     * Handles a transaction-list interaction, the Java migration of paragraph {@code MAIN-PARA}
     * in {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces the COBOL control flow. On first entry into the transaction (COBOL
     * {@code EIBCALEN = 0}, reproduced by {@link CardDemoContext#isNew()}) the main menu is not
     * yet reachable, so the originating program is recorded as the sign-on program and control
     * returns there via {@link #returnToPrevScreen(CardDemoContext)}.</p>
     *
     * <p>Otherwise the COBOL first-display test ({@code IF NOT CDEMO-PGM-REENTER}) is honoured:
     * on the first display of this transaction ({@link CardDemoContext#isProgramEnter()}) the
     * program is marked re-entered and {@link #processEnterKey(COTRN00Form, CardDemoContext)} runs
     * immediately to load and show page one &mdash; {@code COTRN00C} always performs
     * {@code PROCESS-ENTER-KEY} on first display, unlike the menu programs. On a subsequent
     * submission the pressed AID key is evaluated: {@code ENTER} applies the selection/filter,
     * {@code PF3} returns to the main menu, {@code PF7} pages backward, {@code PF8} pages forward,
     * and any other key yields the invalid-key message.</p>
     *
     * <p>The COBOL {@code SEND-TRNLST-SCREEN} / {@code RECEIVE-TRNLST-SCREEN} and the final
     * {@code EXEC CICS RETURN TRANSID} are presentation/flow concerns owned by the paired
     * controller and are not reproduced here.</p>
     *
     * @param aid  the attention-identifier key pressed; a {@code null} value is treated as
     *             {@link AidKey#OTHER}, matching the COBOL {@code WHEN OTHER} default
     * @param form the submitted transaction-list screen form (map {@code COTRN0A} of mapset
     *             {@code COTRN00})
     * @return the interaction outcome: a redirect target, or a list redisplay with an optional
     *         message
     */
    public TransactionListResult mainEntry(AidKey aid, COTRN00Form form) {
        // MAIN-PARA: IF EIBCALEN = 0 -> record the sign-on program and bounce back to it.
        if (context.isNew()) {
            context.setToProgram(SIGNON_PROGRAM);
            return returnToPrevScreen(context);
        }

        // ELSE: IF NOT CDEMO-PGM-REENTER -> first display: mark re-entered, then PROCESS-ENTER-KEY
        // (which loads page one) and SEND. COTRN00C unconditionally runs PROCESS-ENTER-KEY here.
        if (context.isProgramEnter()) {
            context.markReenter();
            return processEnterKey(form, context);
        }

        // ELSE (re-entry): RECEIVE then EVALUATE EIBAID. A null AID collapses to WHEN OTHER.
        AidKey effectiveAid = (aid == null) ? AidKey.OTHER : aid;
        return switch (effectiveAid) {
            case ENTER -> processEnterKey(form, context);
            case PF3 -> {
                // DFHPF3: MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM, then RETURN-TO-PREV-SCREEN.
                context.setToProgram(MENU_PROGRAM);
                yield returnToPrevScreen(context);
            }
            case PF7 -> processPf7Key(form, context);
            case PF8 -> processPf8Key(form, context);
            case OTHER -> TransactionListResult.showList(MSG_INVALID_KEY, true);
        };
    }

    /**
     * Applies the row selection and/or the transaction-id filter, the Java migration of paragraph
     * {@code PROCESS-ENTER-KEY} in {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces the COBOL logic in order:</p>
     * <ol>
     *   <li><b>Selection scan</b> &mdash; the ten selection cells ({@code SEL0001I}..{@code SEL0010I})
     *       are scanned; the first cell that is neither spaces nor low-values captures its flag
     *       ({@code CDEMO-CT00-TRN-SEL-FLG}) and that row's transaction id ({@code TRNIDnnI} into
     *       {@code CDEMO-CT00-TRN-SELECTED}). When a selection is present the flag is evaluated:
     *       {@code 'S'}/{@code 's'} dispatches to the transaction-view program {@code COTRN01C}
     *       (setting the hand-off fields and returning immediately, the COBOL {@code XCTL}); any
     *       other flag sets the neutral "Invalid selection. Valid value is S" message and
     *       <em>continues</em> (the COBOL path does not return).</li>
     *   <li><b>Filter</b> &mdash; the {@code TRNIDIN} filter is examined: blank (COBOL
     *       {@code SPACES OR LOW-VALUES}) starts the browse at the beginning of the file
     *       ({@code LOW-VALUES}); an all-numeric value becomes the start key; a non-numeric value
     *       raises the error flag with the "Tran ID must be Numeric ..." message. Because the
     *       COBOL {@code PROCESS-PAGE-FORWARD} body is guarded by {@code IF NOT ERR-FLG-ON}, the
     *       non-numeric path performs no paging; that is reproduced here by returning the error
     *       outcome directly.</li>
     *   <li><b>Page one</b> &mdash; the page number is reset to zero and
     *       {@link #processPageForward(COTRN00Form, CardDemoContext, boolean, String)} loads the
     *       first page. If page-forward set a boundary message it takes precedence (the COBOL
     *       single {@code WS-MESSAGE} field); otherwise a pending invalid-selection message (if
     *       any) is shown.</li>
     * </ol>
     *
     * <p>The trailing COBOL {@code MOVE SPACE TO TRNIDINO} echo-clear is a presentation concern
     * left to the controller.</p>
     *
     * @param form the submitted screen form supplying the selection cells and the filter; must not
     *             be {@code null}
     * @param ctx  the session context whose hand-off fields are set on an {@code 'S'} selection
     * @return the routing outcome: a dispatch to the view program, or a list redisplay
     */
    public TransactionListResult processEnterKey(COTRN00Form form, CardDemoContext ctx) {
        // EVALUATE the ten selection cells: capture the first non-blank flag and its row's id.
        String selectionFlag = null;
        String selectedTranId = null;
        for (int row = 1; row <= PAGE_SIZE; row++) {
            String cell = selectionCellAt(form, row);
            if (isNotBlankNotLow(cell)) {
                selectionFlag = cell;
                selectedTranId = tranIdCellAt(form, row);
                break;
            }
        }

        // IF a selection flag and id are both present -> EVALUATE the flag.
        String pendingMessage = null;
        if (isNotBlankNotLow(selectionFlag) && isNotBlankNotLow(selectedTranId)) {
            String flag = selectionFlag.trim();
            if (SELECT_FLAG_UPPER.equals(flag) || SELECT_FLAG_LOWER.equals(flag)) {
                // WHEN 'S'/'s': MOVE 'COTRN01C' TO CDEMO-TO-PROGRAM, set from-fields, reset
                // context to enter, and XCTL. The selected id is forwarded for the view program.
                ctx.setFromTranid(TRANSACTION_ID);
                ctx.setFromProgram(PROGRAM_NAME);
                ctx.setPgmContext(0);
                ctx.setToProgram(DETAIL_PROGRAM);
                ctx.setToTranid(DETAIL_TRANSACTION_ID);
                return TransactionListResult.redirectWithSelection(DETAIL_PROGRAM, selectedTranId.trim());
            }
            // WHEN OTHER: neutral invalid-selection message; the COBOL then continues to paging.
            pendingMessage = MSG_INVALID_SELECTION;
        }

        // Filter: derive the browse start key from TRNIDIN (SPACES/LOW-VALUES, numeric, or error).
        String rawFilter = form.getTrnidin();
        String filter = (rawFilter == null) ? "" : rawFilter.trim();
        String startKey;
        if (filter.isEmpty()) {
            // MOVE LOW-VALUES TO TRAN-ID: start at the beginning of the file.
            startKey = "";
        } else if (isAllDigits(filter)) {
            // MOVE TRNIDINI TO TRAN-ID: right-pad into the X(16) key for the GTEQ start.
            startKey = rightPadToKeyWidth(filter);
        } else {
            // Non-numeric: raise the error flag; PROCESS-PAGE-FORWARD body would be skipped.
            return TransactionListResult.showList(MSG_TRAN_ID_NUMERIC, true);
        }

        // MOVE 0 TO CDEMO-CT00-PAGE-NUM, then PERFORM PROCESS-PAGE-FORWARD.
        form.setPagenum(formatPageNum(0));
        TransactionListResult forward = processPageForward(form, ctx, false, startKey);
        if (forward.error()) {
            return forward;
        }
        // The COBOL WS-MESSAGE is a single field: a page-forward boundary message overwrites the
        // pending invalid-selection message; otherwise the invalid-selection message survives.
        String finalMessage = forward.hasMessage() ? forward.message() : pendingMessage;
        return TransactionListResult.showList(finalMessage, false);
    }


    /**
     * Pages backward (up), the Java migration of paragraph {@code PROCESS-PF7-KEY} in
     * {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces the COBOL: the browse start key is the first transaction id of the current
     * page ({@code CDEMO-CT00-TRNID-FIRST}, reconstructed from {@link COTRN00Form#getTrnid01()});
     * when that is blank the start key is the beginning of the file ({@code LOW-VALUES}). If the
     * current page number is greater than one the previous page is loaded via
     * {@link #processPageBackward(COTRN00Form, CardDemoContext, String)}; otherwise the user is
     * already at the top and the "You are already at the top of the page..." message is shown
     * without changing the displayed rows (COBOL {@code SEND-ERASE-NO}).</p>
     *
     * @param form the current screen form supplying the first-row cursor and the page number
     * @param ctx  the session context (carried through to the page-backward step)
     * @return the previous page as a redisplay, or the already-at-top message
     */
    public TransactionListResult processPf7Key(COTRN00Form form, CardDemoContext ctx) {
        // IF CDEMO-CT00-TRNID-FIRST = SPACES OR LOW-VALUES -> LOW-VALUES; ELSE the first-row id.
        String firstKey = normalizeKey(form.getTrnid01());
        String startKey = firstKey.isEmpty() ? "" : firstKey;

        // IF CDEMO-CT00-PAGE-NUM > 1 -> PROCESS-PAGE-BACKWARD; ELSE already at the top.
        int pageNum = parsePageNum(form.getPagenum());
        if (pageNum > 1) {
            return processPageBackward(form, ctx, startKey);
        }
        return TransactionListResult.showList(MSG_ALREADY_TOP, false);
    }

    /**
     * Pages forward (down), the Java migration of paragraph {@code PROCESS-PF8-KEY} in
     * {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces the COBOL: the browse start key is the last transaction id of the current
     * page ({@code CDEMO-CT00-TRNID-LAST}, reconstructed as the last populated {@code TRNIDnn});
     * when that is blank the start key is the end of the file ({@code HIGH-VALUES}). The COBOL
     * next-page flag ({@code CDEMO-CT00-NEXT-PAGE-FLG}) is recomputed here by checking whether any
     * transaction key sorts strictly after the current last key: if so the next page is loaded via
     * {@link #processPageForward(COTRN00Form, CardDemoContext, boolean, String)} (with the PF8
     * skip-one semantics); otherwise the user is already at the bottom and the "You are already at
     * the bottom of the page..." message is shown without changing the displayed rows (COBOL
     * {@code SEND-ERASE-NO}).</p>
     *
     * @param form the current screen form supplying the last-row cursor and the page number
     * @param ctx  the session context (carried through to the page-forward step)
     * @return the next page as a redisplay, or the already-at-bottom message
     */
    public TransactionListResult processPf8Key(COTRN00Form form, CardDemoContext ctx) {
        // IF CDEMO-CT00-TRNID-LAST = SPACES OR LOW-VALUES -> HIGH-VALUES; ELSE the last-row id.
        String lastKey = lastPopulatedTranId(form);
        String startKey = lastKey.isEmpty() ? highValuesKey() : lastKey;

        // IF NEXT-PAGE-YES -> PROCESS-PAGE-FORWARD; ELSE already at the bottom. The persisted flag
        // is recomputed as "does any key sort strictly after the current last key".
        if (hasTransactionAfter(lastKey)) {
            return processPageForward(form, ctx, true, startKey);
        }
        return TransactionListResult.showList(MSG_ALREADY_BOTTOM, false);
    }

    /**
     * Loads the next page of up to ten transactions, the Java migration of paragraph
     * {@code PROCESS-PAGE-FORWARD} in {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces the COBOL browse: {@link #startBrowseTransactFile(List, String)} positions at
     * the start key; when the caller arrives via PF8 ({@code skipFirst}) one
     * {@link #readNextTransactFile(BrowseCursor)} is consumed to step past the current last record
     * (the COBOL {@code IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3} skip). The ten data rows
     * are cleared ({@link #initializeTranData(COTRN00Form, int)}); then up to ten records are read
     * forward, each populated via {@link #populateTranData(COTRN00Form, int, Transaction)}. When
     * the page fills without end-of-file the page number is incremented and one more record is
     * peeked to set the next-page indicator; at end-of-file the next-page indicator is cleared and,
     * if at least one row was read, the page number is still incremented. The browse is then ended
     * ({@link #endBrowseTransactFile(BrowseCursor)}).</p>
     *
     * <p>A {@code STARTBR NOTFND} ({@link RecordNotFoundException}) yields the neutral
     * "You are at the top of the page..." message and an empty page; a forward {@code ENDFILE}
     * ({@link EndOfFileException}) during the fill yields the neutral "You have reached the bottom
     * of the page..." message. Neither is a hard error.</p>
     *
     * @param form      the screen form whose data rows and page number are populated in place
     * @param ctx       the session context (unused for state here; retained for signature parity
     *                  with the paragraph and for future context-carried cursors)
     * @param skipFirst {@code true} on the PF8 path, to step past the current last record before
     *                  reading the next page
     * @param startKey  the browse start key ({@code ""} for {@code LOW-VALUES}, a padded id, or a
     *                  {@code HIGH-VALUES} sentinel)
     * @return a list redisplay, optionally carrying a boundary message
     */
    TransactionListResult processPageForward(COTRN00Form form, CardDemoContext ctx,
            boolean skipFirst, String startKey) {
        List<Transaction> ordered = loadForwardWindow(startKey);
        int pageNum = parsePageNum(form.getPagenum());

        // PERFORM STARTBR-TRANSACT-FILE. NOTFND -> top-of-file message, empty page.
        BrowseCursor cursor;
        try {
            cursor = startBrowseTransactFile(ordered, startKey);
        } catch (RecordNotFoundException notFound) {
            // COBOL STARTBR NOTFND: TRANSACT-EOF is set but the error flag is NOT raised, so the
            // guarded row-clear and the fill loop are both skipped. The current data rows and the
            // page number are left unchanged; only the top-of-file message is shown.
            form.setPagenum(formatPageNum(pageNum));
            return TransactionListResult.showList(MSG_STARTBR_TOP, false);
        }

        String boundaryMessage = null;
        try {
            // PF8 skip (COBOL IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3): consume the current
            // last record so the fill starts at the next one. If the skip itself reaches
            // end-of-file there is no next page, so the guarded clear and the fill are skipped, the
            // rows and page number are left unchanged, and the bottom-of-page message is shown.
            boolean skipHitEof = false;
            if (skipFirst) {
                try {
                    readNextTransactFile(cursor);
                } catch (EndOfFileException eof) {
                    skipHitEof = true;
                    boundaryMessage = MSG_READNEXT_BOTTOM;
                }
            }

            if (!skipHitEof) {
                // Clear the ten rows (COBOL guarded by TRANSACT-NOT-EOF), then read up to ten
                // records forward, populating each.
                clearAllRows(form);
                int index = 1;
                boolean endOfFile = false;
                while (index <= PAGE_SIZE && !endOfFile) {
                    try {
                        Transaction tran = readNextTransactFile(cursor);
                        populateTranData(form, index, tran);
                        index++;
                    } catch (EndOfFileException eof) {
                        endOfFile = true;
                        boundaryMessage = MSG_READNEXT_BOTTOM;
                    }
                }

                if (!endOfFile) {
                    // Page filled without end-of-file: increment the page number, then peek one
                    // more record to determine the next-page indicator (COBOL lines 305-313).
                    pageNum++;
                    try {
                        readNextTransactFile(cursor);
                        // A record exists beyond this page (COBOL SET NEXT-PAGE-YES): no message.
                        boundaryMessage = null;
                    } catch (EndOfFileException eof) {
                        // No further record (COBOL SET NEXT-PAGE-NO). The peek READNEXT that hit
                        // ENDFILE sets the bottom-of-page message even though the page is full.
                        boundaryMessage = MSG_READNEXT_BOTTOM;
                    }
                } else if (index > 1) {
                    // Reached end-of-file while filling but showed at least one row: still advance
                    // the page number (COBOL: IF WS-IDX > 1 ADD 1 TO CDEMO-CT00-PAGE-NUM).
                    pageNum++;
                }
            }
        } finally {
            // PERFORM ENDBR-TRANSACT-FILE.
            endBrowseTransactFile(cursor);
        }

        form.setPagenum(formatPageNum(pageNum));
        return TransactionListResult.showList(boundaryMessage, false);
    }


    /**
     * Loads the previous page of up to ten transactions, the Java migration of paragraph
     * {@code PROCESS-PAGE-BACKWARD} in {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces the COBOL backward browse: {@link #startBrowseTransactFile(List, String)}
     * positions at the start key (the current page's first transaction id); one
     * {@link #readPrevTransactFile(BrowseCursor)} is consumed to step past that current first
     * record (the COBOL {@code IF EIBAID NOT = DFHENTER AND DFHPF8} skip, always taken on the PF7
     * path). The ten data rows are cleared; then up to ten records are read backward, populated
     * from the bottom row upward (COBOL {@code MOVE 10 TO WS-IDX}, decrementing per populated
     * row), so a full page still displays in ascending order. When the fill completes without
     * reaching the top, one more record is peeked to adjust the page number: the page number is
     * decremented (floored at one) when a previous record exists, or reset to one when the peek
     * reaches the top of file. The browse is then ended.</p>
     *
     * <p>A {@code STARTBR NOTFND} ({@link RecordNotFoundException}) or a top-of-file
     * {@code READPREV ENDFILE} ({@link EndOfFileException}) yields the neutral boundary message
     * and leaves the current rows unchanged; neither is a hard error.</p>
     *
     * @param form     the screen form whose data rows and page number are populated in place
     * @param ctx      the session context (retained for signature parity with the paragraph)
     * @param startKey the browse start key (the current page's first transaction id, or {@code ""}
     *                 for {@code LOW-VALUES})
     * @return a list redisplay, optionally carrying a boundary message
     */
    TransactionListResult processPageBackward(COTRN00Form form, CardDemoContext ctx, String startKey) {
        List<Transaction> ordered = loadBackwardWindow(startKey);
        int pageNum = parsePageNum(form.getPagenum());

        // PERFORM STARTBR-TRANSACT-FILE. NOTFND -> top-of-file message, rows unchanged.
        BrowseCursor cursor;
        try {
            cursor = startBrowseTransactFile(ordered, startKey);
        } catch (RecordNotFoundException notFound) {
            form.setPagenum(formatPageNum(pageNum));
            return TransactionListResult.showList(MSG_STARTBR_TOP, false);
        }

        String boundaryMessage = null;
        try {
            // PF7 skip (COBOL IF EIBAID NOT = DFHENTER AND DFHPF8): step past the current first
            // record. If this reaches the top of file there is no previous page, so the guarded
            // clear and the fill are skipped, the rows and page number are left unchanged, and the
            // top-of-page message is shown.
            boolean topHitDuringSkip = false;
            try {
                readPrevTransactFile(cursor);
            } catch (EndOfFileException eof) {
                topHitDuringSkip = true;
                boundaryMessage = MSG_READPREV_TOP;
            }

            if (!topHitDuringSkip) {
                // Clear the ten rows, then read up to ten records backward, filling from the bottom
                // row upward (COBOL MOVE 10 TO WS-IDX; SUBTRACT 1 per populated row).
                clearAllRows(form);
                int index = PAGE_SIZE;
                boolean endOfFile = false;
                while (index >= 1 && !endOfFile) {
                    try {
                        Transaction tran = readPrevTransactFile(cursor);
                        populateTranData(form, index, tran);
                        index--;
                    } catch (EndOfFileException eof) {
                        endOfFile = true;
                        boundaryMessage = MSG_READPREV_TOP;
                    }
                }

                if (!endOfFile) {
                    // Peek one more record backward to adjust the page number (COBOL lines
                    // 359-368; the next-page flag is always YES on the PF7 path).
                    try {
                        readPrevTransactFile(cursor);
                        // A previous record exists: decrement the page number, floored at one.
                        pageNum = (pageNum > 1) ? pageNum - 1 : 1;
                    } catch (EndOfFileException eof) {
                        // The peek reached the top of file: reset the page number to one and show
                        // the top-of-page message.
                        pageNum = 1;
                        boundaryMessage = MSG_READPREV_TOP;
                    }
                }
                // else: end-of-file during the fill -> no peek, page number unchanged, the
                // top-of-page message was already set by the fill's terminating read.
            }
        } finally {
            // PERFORM ENDBR-TRANSACT-FILE.
            endBrowseTransactFile(cursor);
        }

        form.setPagenum(formatPageNum(pageNum));
        return TransactionListResult.showList(boundaryMessage, false);
    }

    /**
     * Populates one screen data row from a transaction, the Java migration of paragraph
     * {@code POPULATE-TRAN-DATA} in {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces the COBOL field moves for the row selected by {@code WS-IDX}: the transaction
     * id ({@code TRAN-ID}, {@code X(16)}), the origination date derived from the timestamp
     * ({@code TRAN-ORIG-TS} formatted {@code MM/DD/YY} into the {@code X(8)} date field), the
     * description ({@code TRAN-DESC}, truncated to the screen's {@code X(26)} width), and the
     * amount ({@code TRAN-AMT}, {@code PIC S9(09)V99}, edited into the {@code X(12)}
     * {@code +99999999.99} field). The COBOL also captures {@code CDEMO-CT00-TRNID-FIRST} at row
     * one and {@code CDEMO-CT00-TRNID-LAST} at row ten; those cursor keys are reconstructed on
     * demand from the populated rows rather than stored, so no extra assignment is needed here.</p>
     *
     * @param form     the screen form whose row is populated in place
     * @param rowIndex the one-based row number ({@code 1}-{@code 10}); any other value is ignored,
     *                 matching the COBOL {@code WHEN OTHER CONTINUE}
     * @param tran     the transaction supplying the row values; must not be {@code null}
     */
    private void populateTranData(COTRN00Form form, int rowIndex, Transaction tran) {
        String id = (tran.getTranId() == null) ? "" : tran.getTranId();
        String date = formatTranDate(tran.getOrigTs());
        String desc = truncate(tran.getTranDesc(), TRAN_DESC_WIDTH);
        String amount = formatTranAmount(tran.getTranAmt());
        setRowFields(form, rowIndex, id, date, desc, amount);
    }

    /**
     * Blanks one screen data row, the Java migration of paragraph {@code INITIALIZE-TRAN-DATA} in
     * {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces the COBOL {@code MOVE SPACES} to the four fields of the row selected by
     * {@code WS-IDX}. Empty strings are used rather than fixed-width blank runs; the fixed display
     * width is a presentation concern of the rendered template.</p>
     *
     * @param form     the screen form whose row is blanked in place
     * @param rowIndex the one-based row number ({@code 1}-{@code 10}); any other value is ignored,
     *                 matching the COBOL {@code WHEN OTHER CONTINUE}
     */
    private void initializeTranData(COTRN00Form form, int rowIndex) {
        setRowFields(form, rowIndex, "", "", "", "");
    }

    /**
     * Writes the four fields of one screen data row, the shared {@code EVALUATE WS-IDX} dispatch
     * used by both {@code POPULATE-TRAN-DATA} and {@code INITIALIZE-TRAN-DATA}.
     *
     * @param form     the screen form to mutate
     * @param rowIndex the one-based row number ({@code 1}-{@code 10}); any other value is ignored
     * @param id       the transaction-id field value
     * @param date     the date field value
     * @param desc     the description field value
     * @param amount   the amount field value
     */
    private static void setRowFields(COTRN00Form form, int rowIndex, String id, String date,
            String desc, String amount) {
        switch (rowIndex) {
            case 1 -> {
                form.setTrnid01(id);
                form.setTdate01(date);
                form.setTdesc01(desc);
                form.setTamt001(amount);
            }
            case 2 -> {
                form.setTrnid02(id);
                form.setTdate02(date);
                form.setTdesc02(desc);
                form.setTamt002(amount);
            }
            case 3 -> {
                form.setTrnid03(id);
                form.setTdate03(date);
                form.setTdesc03(desc);
                form.setTamt003(amount);
            }
            case 4 -> {
                form.setTrnid04(id);
                form.setTdate04(date);
                form.setTdesc04(desc);
                form.setTamt004(amount);
            }
            case 5 -> {
                form.setTrnid05(id);
                form.setTdate05(date);
                form.setTdesc05(desc);
                form.setTamt005(amount);
            }
            case 6 -> {
                form.setTrnid06(id);
                form.setTdate06(date);
                form.setTdesc06(desc);
                form.setTamt006(amount);
            }
            case 7 -> {
                form.setTrnid07(id);
                form.setTdate07(date);
                form.setTdesc07(desc);
                form.setTamt007(amount);
            }
            case 8 -> {
                form.setTrnid08(id);
                form.setTdate08(date);
                form.setTdesc08(desc);
                form.setTamt008(amount);
            }
            case 9 -> {
                form.setTrnid09(id);
                form.setTdate09(date);
                form.setTdesc09(desc);
                form.setTamt009(amount);
            }
            case 10 -> {
                form.setTrnid10(id);
                form.setTdate10(date);
                form.setTdesc10(desc);
                form.setTamt010(amount);
            }
            default -> {
                // COBOL WHEN OTHER CONTINUE: rows outside 1-10 are ignored.
            }
        }
    }


    /**
     * Returns control to the previous screen, the Java migration of paragraph
     * {@code RETURN-TO-PREV-SCREEN} in {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Mirrors the COBOL default-and-transfer exactly: when the hand-off target
     * ({@code CDEMO-TO-PROGRAM}) is unset (COBOL {@code LOW-VALUES OR SPACES}) it defaults to the
     * sign-on program {@code COSGN00C}; the from-transaction ({@code WS-TRANID = 'CT00'}) and
     * from-program ({@code WS-PGMNAME = 'COTRN00C'}) are recorded and the program context is reset
     * to enter ({@code CDEMO-PGM-CONTEXT = ZEROS}); then control is transferred (COBOL
     * {@code XCTL}), expressed here as a redirect outcome for the controller to act on.</p>
     *
     * @param ctx the session context supplying, and if necessary defaulting, the hand-off target
     * @return a redirect outcome targeting the (possibly defaulted) previous program
     */
    private TransactionListResult returnToPrevScreen(CardDemoContext ctx) {
        String target = ctx.getToProgram();
        if (target == null || target.isBlank()) {
            ctx.setToProgram(SIGNON_PROGRAM);
        }
        ctx.setFromTranid(TRANSACTION_ID);
        ctx.setFromProgram(PROGRAM_NAME);
        ctx.setPgmContext(0);
        return TransactionListResult.redirect(ctx.getToProgram());
    }

    /**
     * Starts a browse of the transaction set positioned at the given start key, the Java migration
     * of paragraph {@code STARTBR-TRANSACT-FILE} in {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces the CICS {@code STARTBR ... RIDFLD(TRAN-ID)} with the default {@code GTEQ}
     * positioning: the cursor is placed at the first transaction whose id is greater than or equal
     * to {@code startKey}. An empty start key ({@code LOW-VALUES}) positions at the beginning of
     * the set. When no transaction satisfies the key (CICS {@code NOTFND}) a
     * {@link RecordNotFoundException} is thrown, which the caller treats as the neutral
     * top-of-file boundary rather than as a hard error.</p>
     *
     * @param ordered  the ordered (ascending by transaction id) snapshot to browse
     * @param startKey the start key ({@code ""} for {@code LOW-VALUES}, a padded id, or a
     *                 {@code HIGH-VALUES} sentinel)
     * @return a cursor positioned at the first record at or beyond the start key
     * @throws RecordNotFoundException when no record satisfies the start key (CICS {@code NOTFND})
     */
    private BrowseCursor startBrowseTransactFile(List<Transaction> ordered, String startKey) {
        int index = firstIndexGE(ordered, startKey);
        if (index < 0) {
            throw new RecordNotFoundException(
                    "STARTBR TRANSACT NOTFND for start key '" + startKey + "'");
        }
        return new BrowseCursor(ordered, index);
    }

    /**
     * Reads the next transaction in the browse, the Java migration of paragraph
     * {@code READNEXT-TRANSACT-FILE} in {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces CICS {@code READNEXT}: returns the record at the cursor and advances. Reaching
     * the end of the set (CICS {@code ENDFILE}) throws {@link EndOfFileException} &mdash; the
     * normal end-of-page control signal the caller converts into the bottom-of-page boundary
     * message, never a hard error.</p>
     *
     * @param cursor the active browse cursor
     * @return the next transaction
     * @throws EndOfFileException at the end of the set (CICS {@code ENDFILE})
     */
    private Transaction readNextTransactFile(BrowseCursor cursor) {
        return cursor.readNext();
    }

    /**
     * Reads the previous transaction in the browse, the Java migration of paragraph
     * {@code READPREV-TRANSACT-FILE} in {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces CICS {@code READPREV}: returns the record at the cursor and retreats. Reaching
     * the top of the set (CICS {@code ENDFILE}) throws {@link EndOfFileException} &mdash; the
     * normal end-of-page control signal the caller converts into the top-of-page boundary message,
     * never a hard error.</p>
     *
     * @param cursor the active browse cursor
     * @return the previous transaction
     * @throws EndOfFileException at the top of the set (CICS {@code ENDFILE})
     */
    private Transaction readPrevTransactFile(BrowseCursor cursor) {
        return cursor.readPrev();
    }

    /**
     * Ends the browse, the Java migration of paragraph {@code ENDBR-TRANSACT-FILE} in
     * {@code legacy/cbl/COTRN00C.cbl}.
     *
     * <p>Reproduces CICS {@code ENDBR}. The in-memory cursor holds no external resource, so this is
     * a no-op retained to preserve the one-method-per-paragraph structure and to document the
     * browse-close boundary.</p>
     *
     * @param cursor the browse cursor being closed (retained for structural fidelity)
     */
    private void endBrowseTransactFile(BrowseCursor cursor) {
        // No external browse handle to release: the cursor is a transient in-memory position, so
        // ending the browse requires no action. The parameter is retained for structural fidelity
        // with the COBOL paragraph and to keep call sites symmetric with the CICS ENDBR.
    }

    /**
     * In-memory cursor over an ordered snapshot of the transaction set, reproducing the VSAM
     * {@code STARTBR}/{@code READNEXT}/{@code READPREV} browse position.
     *
     * <p>The cursor is positioned at the first record whose key is greater than or equal to the
     * start key (the CICS {@code GTEQ} default). {@link #readNext()} returns the record at the
     * position and advances; {@link #readPrev()} returns the record at the position and retreats.
     * Reaching either bound throws {@link EndOfFileException}, the carrier for CICS
     * {@code ENDFILE}. Because the whole set is read once and navigated in memory, the browse is a
     * read-only, self-consistent snapshot for the duration of a single page operation.</p>
     */
    static final class BrowseCursor {

        /** Ordered (ascending by transaction id) snapshot backing the browse. */
        private final List<Transaction> records;

        /** Current browse position: an index into {@link #records}. */
        private int position;

        /**
         * Creates a cursor positioned at the given index.
         *
         * @param records  the ordered snapshot to browse; must not be {@code null}
         * @param position the initial position (an index into {@code records})
         */
        private BrowseCursor(List<Transaction> records, int position) {
            this.records = records;
            this.position = position;
        }

        /**
         * Returns the record at the current position and advances, reproducing CICS
         * {@code READNEXT}.
         *
         * @return the record at the current position
         * @throws EndOfFileException when the position is past the last record (CICS {@code ENDFILE})
         */
        private Transaction readNext() {
            if (position < 0 || position >= records.size()) {
                throw new EndOfFileException();
            }
            Transaction record = records.get(position);
            position++;
            return record;
        }

        /**
         * Returns the record at the current position and retreats, reproducing CICS
         * {@code READPREV}.
         *
         * @return the record at the current position
         * @throws EndOfFileException when the position is before the first record (CICS {@code ENDFILE})
         */
        private Transaction readPrev() {
            if (position < 0 || position >= records.size()) {
                throw new EndOfFileException();
            }
            Transaction record = records.get(position);
            position--;
            return record;
        }
    }


    /**
     * Loads the forward browse window: the bounded, ascending slice of {@code TRANSACT} at or after
     * {@code startKey} (review finding #21).
     *
     * <p>Reproduces the VSAM {@code STARTBR}-at-key plus forward {@code READNEXT} sequence of the
     * COBOL transaction-list browse without materialising the whole {@code TRANSACT} table. The
     * forward browse reads at most {@link #BROWSE_WINDOW} records from the positioned start key (a
     * possible PF8 skip-one, a page of rows, and a look-ahead), so fetching exactly that many rows
     * &ge; {@code startKey} in ascending key order yields a window that contains every record the
     * legacy browse would have read for the page. The window is handed to
     * {@link #startBrowseTransactFile(List, String)}, whose {@code firstIndexGE} re-derives the
     * greater-than-or-equal position with the identical comparison, so the emitted rows are
     * byte-identical to the previous full-table scan. The transaction ids are sixteen-character
     * zero-padded numeric strings and the {@code transaction.tran_id} {@code CHAR(16)} column uses
     * the {@code C}/{@code POSIX} collation, so the query's key order matches the legacy KSDS order.
     * </p>
     *
     * <p>A {@code startKey} of {@code ""} is the COBOL {@code LOW-VALUES} start (first page): the
     * {@code >=} predicate treats it as a lower bound below every real id, selecting the first
     * {@link #BROWSE_WINDOW} rows. A {@code HIGH-VALUES} sentinel start key is above every real id,
     * so the window is empty and {@code startBrowseTransactFile} reports {@code NOTFND} &mdash;
     * exactly the legacy {@code STARTBR} outcome.</p>
     *
     * @param startKey the inclusive lower-bound start key ({@code ""} for {@code LOW-VALUES}, a
     *                 padded id, or a {@code HIGH-VALUES} sentinel)
     * @return the ascending forward window, at most {@link #BROWSE_WINDOW} records (possibly empty)
     */
    private List<Transaction> loadForwardWindow(String startKey) {
        String lowerBound = (startKey == null) ? "" : startKey;
        return transactionRepository.findByTranIdGreaterThanEqualOrderByTranIdAsc(
                lowerBound, Limit.of(BROWSE_WINDOW));
    }

    /**
     * Loads the backward browse window: the bounded slice of {@code TRANSACT} at or before
     * {@code startKey}, returned ascending for the browse cursor (review finding #21).
     *
     * <p>Reproduces the VSAM {@code STARTBR} plus backward {@code READPREV} sequence of paragraph
     * {@code PROCESS-PAGE-BACKWARD} without materialising the whole table. The backward browse reads
     * at most {@link #BROWSE_WINDOW} records at or before the start key, so the repository fetches
     * that many rows &le; {@code startKey} in <em>descending</em> key order and this method reverses
     * them to ascending &mdash; the order {@link #startBrowseTransactFile(List, String)} always
     * expects. The cursor positions at the greater-than-or-equal index (the start key, the last
     * element of the reversed window) and reads backward from it, so the paged output is
     * byte-identical to the full-table scan it replaces.</p>
     *
     * <p>A {@code startKey} of {@code ""} is the {@code LOW-VALUES} start (the PF7-with-blank-first
     * case): the backward browse then positions before the first record and reads backward, so the
     * first record must be present. The same ascending head window as the forward low-values case is
     * returned, which places the first transaction at index zero exactly as the full-table scan
     * did.</p>
     *
     * @param startKey the inclusive upper-bound start key (the current page's first id, or
     *                 {@code ""} for {@code LOW-VALUES})
     * @return the ascending backward window, at most {@link #BROWSE_WINDOW} records (possibly empty)
     */
    private List<Transaction> loadBackwardWindow(String startKey) {
        if (startKey == null || startKey.isEmpty()) {
            return transactionRepository.findByTranIdGreaterThanEqualOrderByTranIdAsc(
                    "", Limit.of(BROWSE_WINDOW));
        }
        List<Transaction> descending = transactionRepository
                .findByTranIdLessThanEqualOrderByTranIdDesc(startKey, Limit.of(BROWSE_WINDOW));
        Collections.reverse(descending);
        return descending;
    }

    /**
     * Recomputes the COBOL next-page indicator ({@code CDEMO-CT00-NEXT-PAGE-FLG}) by reporting
     * whether any transaction key sorts strictly after the current last key.
     *
     * <p>This is equivalent to the COBOL forward peek that set {@code NEXT-PAGE-YES} when one more
     * record existed beyond the tenth row: if the record after the current last exists, some key
     * sorts strictly after {@code lastKey}; otherwise none does. A blank last key (an empty page)
     * has nothing after it.</p>
     *
     * @param lastKey the current page's last transaction id (blank for an empty page)
     * @return {@code true} when a transaction sorts strictly after {@code lastKey}
     */
    private boolean hasTransactionAfter(String lastKey) {
        if (lastKey == null || lastKey.isEmpty()) {
            return false;
        }
        return transactionRepository.existsByTranIdGreaterThan(lastKey);
    }

    /**
     * Finds the index of the first transaction whose id is greater than or equal to the key,
     * reproducing the CICS {@code STARTBR} {@code GTEQ} positioning.
     *
     * @param ordered the ordered (ascending by transaction id) snapshot
     * @param key     the start key ({@code null} or {@code ""} means {@code LOW-VALUES})
     * @return the zero-based index of the first record at or beyond the key, or {@code -1} when no
     *         record satisfies it (CICS {@code NOTFND})
     */
    private static int firstIndexGE(List<Transaction> ordered, String key) {
        if (ordered.isEmpty()) {
            return -1;
        }
        if (key == null || key.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < ordered.size(); i++) {
            String tid = ordered.get(i).getTranId();
            String candidate = (tid == null) ? "" : tid;
            if (candidate.compareTo(key) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Formats a transaction amount into the twelve-character {@code +99999999.99} edited field,
     * reproducing the COBOL {@code MOVE TRAN-AMT TO WS-TRAN-AMT}.
     *
     * <p>The scale is fixed at two decimals (truncating any excess toward zero); only the low-order
     * eight integer digits are kept (the ninth, high-order digit of {@code S9(09)V99} is truncated
     * by the shorter receiving field); and the sign is always shown ({@code '+'} for zero or
     * positive, {@code '-'} for negative). {@link BigDecimal} arithmetic is used throughout &mdash;
     * never floating point (AAP &sect;0.6.1).</p>
     *
     * @param amount the transaction amount (a {@code null} value is treated as zero)
     * @return the twelve-character signed, zero-padded, two-decimal string
     */
    private static String formatTranAmount(BigDecimal amount) {
        BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
        BigDecimal scaled = value.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        char sign = (scaled.signum() < 0) ? '-' : '+';
        BigDecimal magnitude = scaled.abs().remainder(AMOUNT_MODULUS);
        long totalCents = magnitude.movePointRight(AMOUNT_SCALE).longValueExact();
        long units = totalCents / 100L;
        long cents = totalCents % 100L;
        return sign + String.format(Locale.ROOT, AMOUNT_FORMAT, units, cents);
    }

    /**
     * Formats a transaction timestamp into the {@code MM/DD/YY} row date, reproducing the COBOL
     * derivation of {@code WS-TRAN-DATE} from {@code TRAN-ORIG-TS}.
     *
     * @param origTs the transaction origination timestamp (a {@code null} value yields an empty
     *               string)
     * @return the eight-character {@code MM/DD/YY} date, or {@code ""} when the timestamp is absent
     */
    private static String formatTranDate(LocalDateTime origTs) {
        if (origTs == null) {
            return "";
        }
        return origTs.format(TRAN_DATE_FORMATTER);
    }

    /**
     * Formats a page number into the eight-digit zero-padded {@code PAGENUM} field, reproducing the
     * COBOL {@code MOVE CDEMO-CT00-PAGE-NUM} ({@code PIC 9(08)}) into the {@code X(8)} field.
     *
     * @param pageNum the page number
     * @return the eight-character zero-padded page-number string
     */
    private static String formatPageNum(int pageNum) {
        return String.format(Locale.ROOT, "%08d", pageNum);
    }

    /**
     * Parses the page number carried on the {@code PAGENUM} screen field, tolerating blank or
     * non-numeric content by treating it as page zero.
     *
     * @param raw the raw page-number field value (may be {@code null})
     * @return the parsed page number, or {@code 0} when blank or non-numeric
     */
    private static int parsePageNum(String raw) {
        if (raw == null) {
            return 0;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Reports whether every character of the supplied value is an ASCII digit, reproducing the
     * COBOL numeric class test ({@code IS NUMERIC}) applied to the {@code TRNIDIN} filter.
     *
     * @param value the value to test (may be {@code null})
     * @return {@code true} when the value is non-empty and every character is {@code '0'}-{@code '9'}
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
     * Reports whether the supplied value is neither spaces nor low-values, reproducing the COBOL
     * test {@code field NOT = SPACES AND LOW-VALUES} used for the selection cells and the captured
     * flag/id.
     *
     * @param value the value to test (may be {@code null})
     * @return {@code true} when at least one character is neither a space nor a NUL
     */
    private static boolean isNotBlankNotLow(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ' ' && c != '\0') {
                return true;
            }
        }
        return false;
    }

    /**
     * Right-pads (or truncates) a numeric filter into the sixteen-character {@code TRAN-ID} key,
     * reproducing the COBOL {@code MOVE TRNIDINI TO TRAN-ID} into a {@code PIC X(16)} field.
     *
     * <p>The legacy {@code STARTBR ... GTEQ} then positions at the first key greater than or equal
     * to this space-padded value; because stored keys are left-zero-padded, a partial numeric
     * filter reproduces the legacy positioning (and its quirks) exactly.</p>
     *
     * @param value the numeric filter (already validated as all-digits)
     * @return the sixteen-character key (space-padded on the right, or truncated when longer)
     */
    private static String rightPadToKeyWidth(String value) {
        if (value.length() >= TRAN_ID_WIDTH) {
            return value.substring(0, TRAN_ID_WIDTH);
        }
        return value + " ".repeat(TRAN_ID_WIDTH - value.length());
    }

    /**
     * Normalizes a screen-supplied key by trimming surrounding whitespace and mapping {@code null}
     * to the empty string (the {@code LOW-VALUES} sentinel used by the browse).
     *
     * @param value the raw key value (may be {@code null})
     * @return the stripped key, or {@code ""} when blank or {@code null}
     */
    private static String normalizeKey(String value) {
        return (value == null) ? "" : value.strip();
    }

    /**
     * Returns the transaction id of the last populated row, reconstructing the COBOL
     * {@code CDEMO-CT00-TRNID-LAST} cursor key from the visible page.
     *
     * @param form the current screen form
     * @return the last populated row's transaction id (stripped), or {@code ""} when the page is
     *         empty
     */
    private static String lastPopulatedTranId(COTRN00Form form) {
        for (int row = PAGE_SIZE; row >= 1; row--) {
            String id = tranIdCellAt(form, row);
            if (isNotBlankNotLow(id)) {
                return id.strip();
            }
        }
        return "";
    }

    /**
     * Truncates a value to at most {@code maxLength} characters (mapping {@code null} to the empty
     * string), reproducing the COBOL move of a longer alphanumeric field into a shorter one.
     *
     * @param value     the value to truncate (may be {@code null})
     * @param maxLength the maximum length
     * @return the value truncated to {@code maxLength} characters, or {@code ""} when {@code null}
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return (value.length() <= maxLength) ? value : value.substring(0, maxLength);
    }

    /**
     * Returns the {@code HIGH-VALUES} sentinel start key, a sixteen-character run of the maximum
     * code unit that sorts after every numeric transaction id, reproducing the COBOL
     * {@code MOVE HIGH-VALUES TO TRAN-ID}.
     *
     * @return the {@code HIGH-VALUES} start-key sentinel
     */
    private static String highValuesKey() {
        return "\uFFFF".repeat(TRAN_ID_WIDTH);
    }

    /**
     * Clears all ten data rows by invoking {@link #initializeTranData(COTRN00Form, int)} for each,
     * reproducing the COBOL {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10} that
     * blanks the map before a page is populated.
     *
     * @param form the screen form whose rows are cleared in place
     */
    private void clearAllRows(COTRN00Form form) {
        for (int row = 1; row <= PAGE_SIZE; row++) {
            initializeTranData(form, row);
        }
    }

    /**
     * Returns the selection cell for the given one-based row, the read side of the COBOL
     * {@code EVALUATE} over {@code SEL0001I}..{@code SEL0010I}.
     *
     * @param form the screen form
     * @param row  the one-based row number ({@code 1}-{@code 10})
     * @return the row's selection-cell value, or {@code null} for an out-of-range row
     */
    private static String selectionCellAt(COTRN00Form form, int row) {
        return switch (row) {
            case 1 -> form.getSel0001();
            case 2 -> form.getSel0002();
            case 3 -> form.getSel0003();
            case 4 -> form.getSel0004();
            case 5 -> form.getSel0005();
            case 6 -> form.getSel0006();
            case 7 -> form.getSel0007();
            case 8 -> form.getSel0008();
            case 9 -> form.getSel0009();
            case 10 -> form.getSel0010();
            default -> null;
        };
    }

    /**
     * Returns the transaction-id cell for the given one-based row, the read side of the COBOL
     * {@code EVALUATE} over {@code TRNID01I}..{@code TRNID10I}.
     *
     * @param form the screen form
     * @param row  the one-based row number ({@code 1}-{@code 10})
     * @return the row's transaction-id value, or {@code null} for an out-of-range row
     */
    private static String tranIdCellAt(COTRN00Form form, int row) {
        return switch (row) {
            case 1 -> form.getTrnid01();
            case 2 -> form.getTrnid02();
            case 3 -> form.getTrnid03();
            case 4 -> form.getTrnid04();
            case 5 -> form.getTrnid05();
            case 6 -> form.getTrnid06();
            case 7 -> form.getTrnid07();
            case 8 -> form.getTrnid08();
            case 9 -> form.getTrnid09();
            case 10 -> form.getTrnid10();
            default -> null;
        };
    }

}
