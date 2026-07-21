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

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.CardDemoContext;
import com.aws.carddemo.dto.screen.COTRN01Form;
import com.aws.carddemo.exception.RecordNotFoundException;
import com.aws.carddemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Transaction-view online service, the Java migration of the CICS COBOL program
 * {@code COTRN01C} (the AWS CardDemo "View a Transaction from TRANSACT file" screen).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COTRN01C.cbl} &mdash; program {@code COTRN01C},
 * CICS transaction id {@code CT01}. This service preserves the original program's control
 * flow one-for-one: each business COBOL paragraph becomes exactly one Java method
 * (AAP &sect;0.3.3 service layer; AAP &sect;0.4.1 online-programs table
 * {@code COTRN01C.cbl -> TransactionViewService + TransactionController}, tran {@code CT01}).
 * It is a <em>read-only single-record view</em>: it looks a transaction up by id and formats
 * it for display.</p>
 *
 * <h2>Paragraph &rarr; method mapping</h2>
 * <table border="1">
 *   <caption>COTRN01C paragraph &rarr; Java owner</caption>
 *   <tr><th>COBOL paragraph</th><th>Owner</th><th>Java member</th></tr>
 *   <tr><td>{@code MAIN-PARA}</td><td>service</td>
 *       <td>{@link #mainEntry(COTRN01Form, AidKey, String)}</td></tr>
 *   <tr><td>{@code PROCESS-ENTER-KEY}</td><td>service</td>
 *       <td>{@link #processEnterKey(COTRN01Form, CardDemoContext)}</td></tr>
 *   <tr><td>{@code READ-TRANSACT-FILE}</td><td>service</td>
 *       <td>{@link #readTransactFile(String)}</td></tr>
 *   <tr><td>{@code CLEAR-CURRENT-SCREEN}</td><td>service</td>
 *       <td>{@link #clearCurrentScreen(COTRN01Form)}</td></tr>
 *   <tr><td>{@code INITIALIZE-ALL-FIELDS}</td><td>service</td>
 *       <td>{@link #initializeAllFields(COTRN01Form)}</td></tr>
 *   <tr><td>{@code SEND-TRNVIEW-SCREEN}</td><td>controller</td><td>&mdash; (view render)</td></tr>
 *   <tr><td>{@code RECEIVE-TRNVIEW-SCREEN}</td><td>controller</td><td>&mdash; (form binding)</td></tr>
 *   <tr><td>{@code POPULATE-HEADER-INFO}</td><td>controller</td><td>&mdash; (header fields)</td></tr>
 *   <tr><td>{@code RETURN-TO-PREV-SCREEN}</td><td>controller</td><td>&mdash; (redirect execution)</td></tr>
 * </table>
 *
 * <p>The presentation paragraphs {@code SEND-TRNVIEW-SCREEN}, {@code RECEIVE-TRNVIEW-SCREEN}
 * and {@code POPULATE-HEADER-INFO} are intentionally not implemented here: sending and
 * receiving the 3270/BMS map and populating the header (title/date/time) are presentation
 * concerns owned by the paired {@code TransactionController}. The navigation-state bookkeeping
 * of {@code RETURN-TO-PREV-SCREEN} is reproduced by the private helper
 * {@link #returnToPreviousScreen()} so that the {@code from}/{@code to} hand-off fields stay
 * correct, but the actual {@code EXEC CICS XCTL} transfer is executed by the controller using
 * {@link CardDemoContext#getToProgram()}.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>{@code COTRN01C} is pseudo-conversational: every interaction ends with
 * {@code EXEC CICS RETURN TRANSID(CT01) COMMAREA(...)}, and re-entry is driven by the
 * {@code COMMAREA} plus {@code EIBCALEN} and {@code EIBAID}. That state carrier is the
 * session-scoped {@link CardDemoContext}. {@code EIBCALEN = 0} (no COMMAREA) maps to
 * {@link CardDemoContext#isNew()}; the within-program enter/re-enter flag
 * {@code CDEMO-PGM-CONTEXT} ({@code NOT CDEMO-PGM-REENTER}) maps to
 * {@link CardDemoContext#isProgramEnter()} / {@link CardDemoContext#markReenter()}.</p>
 *
 * <p>The screen is reached from the transaction-list program ({@code COTRN00C}) by selecting a
 * row with {@code 'S'}, which places the chosen transaction id in the COMMAREA extension field
 * {@code CDEMO-CT01-TRN-SELECTED}. That field is not part of the shared {@code COCOM01Y}
 * COMMAREA modeled by {@link CardDemoContext}, so the controller supplies it to
 * {@link #mainEntry(COTRN01Form, AidKey, String)} as the {@code selectedTranId} argument; on
 * first entry a non-blank value is pre-loaded into the input field and looked up immediately.</p>
 *
 * <h2>Outcome model</h2>
 * <p>Because the controller performs the redirect and the screen rendering, each business
 * method returns an immutable {@link TransactionViewResult} describing what should happen next:
 * a redirect to a target program (the COBOL {@code XCTL}), or a screen redisplay (the COBOL
 * {@code SEND-TRNVIEW-SCREEN}) optionally carrying an error message. {@code COTRN01C} has no
 * green/"coming soon" informational path &mdash; every message it raises is an error driven by
 * {@code WS-ERR-FLG} &mdash; so the outcome carries a simple {@code error} flag.</p>
 *
 * <h2>Access and design constraints</h2>
 * <ul>
 *   <li><b>Read-only:</b> although {@code READ-TRANSACT-FILE} issues {@code EXEC CICS READ ...
 *       UPDATE}, {@code COTRN01C} never rewrites the record; this service therefore performs no
 *       {@code save} and declares no write transaction (no feature expansion, AAP &sect;0.7.1).</li>
 *   <li><b>Decimal fidelity (AAP &sect;0.6.1):</b> the authoritative amount is the
 *       {@link java.math.BigDecimal} {@code Transaction.tranAmt}; it is formatted into the
 *       money-as-String display field {@code trnamt} exactly as the COBOL edit picture
 *       {@code PIC +99999999.99} renders it (see {@link #formatAmount(BigDecimal)}).</li>
 *   <li><b>Constructor injection, no Lombok:</b> the service constructor-injects two
 *       {@code private final} collaborators &mdash; the session-scoped {@link CardDemoContext}
 *       (COMMAREA replacement) and the {@link TransactionRepository} (the {@code TRANSACT} VSAM
 *       replacement). Explicit accessors, no emoji.</li>
 *   <li><b>Message literals:</b> the empty-id, not-found, unable-to-lookup and invalid-key
 *       messages are declared as local constants mirroring the COBOL literals (the invalid-key
 *       text mirrors {@code CCDA-MSG-INVALID-KEY} from {@code legacy/cpy/CSMSG01Y.cpy}); the
 *       shared message-constants holder is not a declared dependency of this service, so its
 *       value is reproduced locally rather than imported.</li>
 * </ul>
 *
 * <p>Plain Java; compiles warning-free under {@code --release 25} with {@code -Xlint:all} and
 * {@code failOnWarning}.</p>
 *
 * @see CardDemoContext
 * @see COTRN01Form
 * @see Transaction
 * @see TransactionRepository
 */
@Service
public class TransactionViewService {

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COTRN01C'} &mdash; this program's name. */
    private static final String PROGRAM_NAME = "COTRN01C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CT01'} &mdash; this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CT01";

    /**
     * Sign-on program (COBOL literal {@code 'COSGN00C'}) &mdash; the destination for the
     * first-entry bounce ({@code EIBCALEN = 0}) and the default hand-off target in
     * {@code RETURN-TO-PREV-SCREEN} when {@code CDEMO-TO-PROGRAM} is unset.
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Regular-user main-menu program (COBOL literal {@code 'COMEN01C'}) &mdash; the PF3 target
     * when the caller ({@code CDEMO-FROM-PROGRAM}) is unset.
     */
    private static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /**
     * Transaction-list program (COBOL literal {@code 'COTRN00C'}) &mdash; the PF5 target,
     * returning to the list this view was reached from.
     */
    private static final String TRANSACTION_LIST_PROGRAM = "COTRN00C";

    /**
     * Empty transaction-id message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code PROCESS-ENTER-KEY} ({@code legacy/cbl/COTRN01C.cbl} line 149) when the entered
     * transaction id is blank.
     */
    private static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /**
     * Transaction-not-found message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code READ-TRANSACT-FILE} ({@code legacy/cbl/COTRN01C.cbl} line 285) on the CICS
     * {@code NOTFND} response; carried by the {@link RecordNotFoundException}.
     */
    private static final String MSG_TRAN_NOT_FOUND = "Transaction ID NOT found...";

    /**
     * Unexpected-read-failure message. Byte-exact COBOL literal moved to {@code WS-MESSAGE} in
     * {@code READ-TRANSACT-FILE} ({@code legacy/cbl/COTRN01C.cbl} line 292) on the CICS
     * {@code WHEN OTHER} branch (any non-{@code NORMAL}, non-{@code NOTFND} response).
     */
    private static final String MSG_UNABLE_LOOKUP = "Unable to lookup Transaction...";

    /**
     * Invalid-key message. Mirrors COBOL {@code CCDA-MSG-INVALID-KEY} (from
     * {@code legacy/cpy/CSMSG01Y.cpy}), moved to {@code WS-MESSAGE} in {@code MAIN-PARA} for any
     * unmapped AID key. Declared locally because the shared message-constants holder is not a
     * declared dependency of this service.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Number of integer digits in the COBOL amount edit picture {@code WS-TRAN-AMT PIC
     * +99999999.99}. The source field {@code TRAN-AMT PIC S9(09)V99} carries nine integer
     * digits, so a value with a non-zero ninth digit loses its high-order digit when moved into
     * this eight-digit edit field; {@link #formatAmount(BigDecimal)} reproduces that truncation.
     */
    private static final int AMOUNT_INTEGER_DIGITS = 8;

    /**
     * Display width (in characters) of the transaction original/processing "date" fields
     * {@code TORIGDTI}/{@code TPROCDTI} ({@code PIC X(10)}). The source timestamps
     * {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} are {@code PIC X(26)}
     * ({@code yyyy-MM-dd HH:mm:ss.SSSSSS}); the COBOL {@code MOVE} into the ten-character field
     * keeps the leftmost ten characters, i.e. the {@code yyyy-MM-dd} date portion.
     */
    private static final int TIMESTAMP_DISPLAY_WIDTH = 10;

    // --- BMS display-field widths (map COTRN1A of mapset COTRN01) -----------
    // Each COBOL MOVE into a fixed-width BMS field truncates to that width; these constants
    // preserve the 24x80 field-length contract when populating the form (AAP UI preservation).

    /** {@code TRNIDINI PIC X(16)} width - the transaction-id search input field. */
    private static final int TRNIDIN_WIDTH = 16;

    /** {@code TRNIDI PIC X(16)} width - the displayed transaction id. */
    private static final int TRNID_WIDTH = 16;

    /** {@code CARDNUMI PIC X(16)} width - the card number ({@code TRAN-CARD-NUM PIC X(16)}). */
    private static final int CARDNUM_WIDTH = 16;

    /** {@code TTYPCDI PIC X(2)} width - the transaction type code ({@code TRAN-TYPE-CD PIC X(02)}). */
    private static final int TTYPCD_WIDTH = 2;

    /** {@code TRNSRCI PIC X(10)} width - the transaction source ({@code TRAN-SOURCE PIC X(10)}). */
    private static final int TRNSRC_WIDTH = 10;

    /**
     * {@code TDESCI PIC X(60)} width - the transaction description. The source
     * {@code TRAN-DESC PIC X(100)} is wider, so the COBOL {@code MOVE} truncates to 60 characters.
     */
    private static final int TDESC_WIDTH = 60;

    /**
     * {@code MNAMEI PIC X(30)} width - the merchant name. The source
     * {@code TRAN-MERCHANT-NAME PIC X(50)} is wider, so the COBOL {@code MOVE} truncates to 30.
     */
    private static final int MNAME_WIDTH = 30;

    /**
     * {@code MCITYI PIC X(25)} width - the merchant city. The source
     * {@code TRAN-MERCHANT-CITY PIC X(50)} is wider, so the COBOL {@code MOVE} truncates to 25.
     */
    private static final int MCITY_WIDTH = 25;

    /** {@code MZIPI PIC X(10)} width - the merchant ZIP ({@code TRAN-MERCHANT-ZIP PIC X(10)}). */
    private static final int MZIP_WIDTH = 10;

    /**
     * Number of digits in the transaction category code display ({@code TCATCDI PIC X(4)} fed from
     * {@code TRAN-CAT-CD PIC 9(04)}): the numeric value renders zero-padded to four digits.
     */
    private static final int CATEGORY_CODE_DIGITS = 4;

    /**
     * Number of digits in the merchant-id display ({@code MIDI PIC X(9)} fed from
     * {@code TRAN-MERCHANT-ID PIC 9(09)}): the numeric value renders zero-padded to nine digits.
     */
    private static final int MERCHANT_ID_DIGITS = 9;

    /**
     * Formatter reproducing the leftmost ten characters ({@code yyyy-MM-dd}) of the 26-character
     * COBOL timestamp when it is moved into the {@code PIC X(10)} date display fields. Uses
     * {@link Locale#ROOT} so the rendering is locale-independent.
     */
    private static final DateTimeFormatter TIMESTAMP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    /**
     * Session-scoped navigation and selection context, the modern replacement for the COBOL
     * COMMAREA ({@code COCOM01Y}). Injected as a Spring session-scoped proxy.
     */
    private final CardDemoContext context;

    /**
     * Repository over the {@code TRANSACT} table, the Spring Data replacement for the VSAM KSDS
     * read performed by {@code READ-TRANSACT-FILE}. Used read-only (keyed {@code findById}).
     */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the transaction-view service via Spring constructor injection.
     *
     * <p>A single constructor means no {@code @Autowired} annotation is required. Neither
     * argument is dereferenced here, so the constructor introduces no {@code this}-escape under
     * the zero-warning build.</p>
     *
     * @param context               the session-scoped CardDemo context (COMMAREA replacement);
     *                              must not be {@code null} in production
     * @param transactionRepository the transaction repository (the {@code TRANSACT} VSAM
     *                              replacement); must not be {@code null} in production
     */
    public TransactionViewService(CardDemoContext context, TransactionRepository transactionRepository) {
        this.context = context;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Attention-identifier (AID) keys handled by {@link #mainEntry(COTRN01Form, AidKey, String)},
     * mirroring the COBOL {@code EVALUATE EIBAID} of {@code MAIN-PARA}.
     *
     * <p>The paired {@code TransactionController} maps the inbound HTTP submission (the pressed
     * button or PF key) to one of these constants before delegating to the service. Only the keys
     * with distinct behaviour in {@code COTRN01C} are modelled &mdash; {@link #ENTER}
     * ({@code DFHENTER}), {@link #PFK03} ({@code DFHPF3}), {@link #PFK04} ({@code DFHPF4}) and
     * {@link #PFK05} ({@code DFHPF5}) &mdash; and every other key collapses to {@link #OTHER},
     * matching the COBOL {@code WHEN OTHER} default.</p>
     */
    public enum AidKey {

        /** COBOL {@code DFHENTER} &mdash; the ENTER key; looks up and displays the transaction. */
        ENTER,

        /** COBOL {@code DFHPF3} &mdash; the PF3 key; returns to the calling screen (or main menu). */
        PFK03,

        /** COBOL {@code DFHPF4} &mdash; the PF4 key; clears the current screen. */
        PFK04,

        /** COBOL {@code DFHPF5} &mdash; the PF5 key; returns to the transaction list. */
        PFK05,

        /** COBOL {@code WHEN OTHER} &mdash; any other key; yields the invalid-key message. */
        OTHER
    }

    /**
     * Immutable outcome of a transaction-view interaction, describing what the controller should
     * do next: perform a redirect, or redisplay the screen (optionally with an error message).
     *
     * <p>Exactly one of two shapes is produced:</p>
     * <ul>
     *   <li><b>Redirect</b> &mdash; {@link #targetProgram()} is set (and {@link #isRedirect()} is
     *       {@code true}); the controller redirects to the route mapped from that program name.
     *       This reproduces the COBOL {@code XCTL} of {@code RETURN-TO-PREV-SCREEN}.</li>
     *   <li><b>Redisplay</b> &mdash; {@link #targetProgram()} is {@code null}; the controller
     *       redisplays the transaction-view screen (the COBOL {@code SEND-TRNVIEW-SCREEN}). When
     *       {@link #error()} is {@code true} the accompanying {@link #message()} is shown on the
     *       error line (the COBOL {@code WS-ERR-FLG} path); otherwise the (populated or cleared)
     *       screen is shown with no message.</li>
     * </ul>
     *
     * <p>{@code COTRN01C} raises only error messages (there is no green/informational path), so a
     * single {@code error} flag is sufficient.</p>
     *
     * @param targetProgram the target program name for a redirect, or {@code null} for a
     *                      redisplay outcome
     * @param message       the error message to redisplay, or {@code null} when there is none
     * @param error         {@code true} when {@code message} is an error line to display
     */
    public record TransactionViewResult(String targetProgram, String message, boolean error) {

        /**
         * Reports whether this outcome is a redirect (the COBOL {@code XCTL} equivalent).
         *
         * @return {@code true} when a non-blank {@link #targetProgram()} is present
         */
        public boolean isRedirect() {
            return targetProgram != null && !targetProgram.isBlank();
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
         * Creates a redirect outcome targeting the given program (the COBOL {@code XCTL}).
         *
         * @param targetProgram the target program name (the COBOL {@code XCTL} destination)
         * @return a redirect outcome
         */
        private static TransactionViewResult ofRedirect(String targetProgram) {
            return new TransactionViewResult(targetProgram, null, false);
        }

        /**
         * Creates an error-message (redisplay) outcome (the COBOL {@code WS-ERR-FLG} path).
         *
         * @param message the error message to redisplay
         * @return an error outcome
         */
        private static TransactionViewResult ofError(String message) {
            return new TransactionViewResult(null, message, true);
        }

        /**
         * Creates a plain screen-redisplay outcome with no message (the COBOL
         * {@code SEND-TRNVIEW-SCREEN} on the success path).
         *
         * @return a redisplay outcome with no message
         */
        private static TransactionViewResult ofShow() {
            return new TransactionViewResult(null, null, false);
        }
    }

    /**
     * Handles a transaction-view interaction, the Java migration of paragraph {@code MAIN-PARA}
     * in {@code legacy/cbl/COTRN01C.cbl} (lines 86-139).
     *
     * <p>Reproduces the pseudo-conversational control flow exactly. The COBOL house-keeping that
     * opens the paragraph &mdash; {@code SET ERR-FLG-OFF}, {@code SET USR-MODIFIED-NO} and
     * {@code MOVE SPACES TO WS-MESSAGE} &mdash; is represented by returning a fresh
     * {@link TransactionViewResult} rather than mutating persistent state (the read-only view
     * never consults the user-modified flag).</p>
     *
     * <ul>
     *   <li><b>{@code EIBCALEN = 0}</b> ({@link CardDemoContext#isNew()}, lines 94-96): no
     *       COMMAREA &mdash; set the sign-on program as the hand-off target and return to it via
     *       {@link #returnToPreviousScreen()}.</li>
     *   <li><b>First program entry</b> ({@link CardDemoContext#isProgramEnter()}, i.e.
     *       {@code NOT CDEMO-PGM-REENTER}, lines 99-109): flip to re-enter
     *       ({@link CardDemoContext#markReenter()}); if the list program supplied a selected
     *       transaction id ({@code CDEMO-CT01-TRN-SELECTED} not blank), pre-load it into
     *       {@code TRNIDINI} and run {@link #processEnterKey(COTRN01Form, CardDemoContext)}
     *       immediately (its result is the screen to show); otherwise show a fresh screen. The
     *       COBOL {@code MOVE LOW-VALUES TO COTRN1AO} / {@code MOVE -1 TO TRNIDINL} are the
     *       controller's render/cursor concerns.</li>
     *   <li><b>Re-entry</b> (lines 111-132): evaluate the attention id &mdash; {@code ENTER}
     *       drives {@link #processEnterKey(COTRN01Form, CardDemoContext)}; {@code PF3}
     *       ({@link AidKey#PFK03}) routes back to {@code CDEMO-FROM-PROGRAM} (or {@code COMEN01C}
     *       when unset); {@code PF4} ({@link AidKey#PFK04}) clears the screen; {@code PF5}
     *       ({@link AidKey#PFK05}) routes back to the transaction list ({@code COTRN00C}); any
     *       other key yields the invalid-key message {@link #MSG_INVALID_KEY}. A {@code null} key
     *       is treated as the {@code WHEN OTHER} branch.</li>
     * </ul>
     *
     * <p>The concluding {@code EXEC CICS RETURN TRANSID(CT01) COMMAREA(...)} (lines 136-139) is
     * the controller's responsibility: it writes the mutated {@link CardDemoContext} back to the
     * HTTP session and either renders the screen or performs the redirect indicated by the
     * returned {@link TransactionViewResult}.</p>
     *
     * @param form           the bound transaction-view screen form ({@code COTRN1AI}); supplies
     *                       the entered transaction id and receives the populated display fields
     * @param aid            the resolved attention id (COBOL {@code EIBAID}); {@code null} is
     *                       treated as {@link AidKey#OTHER}
     * @param selectedTranId the transaction id chosen on the list screen
     *                       ({@code CDEMO-CT01-TRN-SELECTED}, supplied by the controller from the
     *                       {@code COTRN00C} hand-off); may be {@code null} or blank
     * @return the outcome describing whether to redirect or redisplay the screen, plus any error
     *         message
     * @throws RecordNotFoundException when a first-entry pre-loaded lookup finds no transaction
     *                                 (the CICS {@code NOTFND} path; surfaced by the controller's
     *                                 exception handler)
     */
    public TransactionViewResult mainEntry(COTRN01Form form, AidKey aid, String selectedTranId) {
        // MAIN-PARA lines 94-96: IF EIBCALEN = 0 -> no COMMAREA; bounce to sign-on.
        if (context.isNew()) {
            context.setToProgram(SIGNON_PROGRAM);   // MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
            return returnToPreviousScreen();          // PERFORM RETURN-TO-PREV-SCREEN
        }

        // MAIN-PARA lines 99-109: IF NOT CDEMO-PGM-REENTER -> first display this conversation.
        if (context.isProgramEnter()) {
            context.markReenter();                    // SET CDEMO-PGM-REENTER TO TRUE
            // MOVE LOW-VALUES TO COTRN1AO / MOVE -1 TO TRNIDINL -> controller (render/cursor).
            if (!isBlankOrLowValues(selectedTranId)) {
                // MOVE CDEMO-CT01-TRN-SELECTED TO TRNIDINI; PERFORM PROCESS-ENTER-KEY.
                form.setTrnidin(fit(selectedTranId, TRNIDIN_WIDTH));
                return processEnterKey(form, context);
            }
            // PERFORM SEND-TRNVIEW-SCREEN.
            return TransactionViewResult.ofShow();
        }

        // MAIN-PARA lines 111-132: re-entry -> EVALUATE EIBAID. A null key falls through to OTHER.
        AidKey pressedKey = (aid == null) ? AidKey.OTHER : aid;
        return switch (pressedKey) {
            case ENTER -> processEnterKey(form, context);          // WHEN DFHENTER
            case PFK03 -> {                                         // WHEN DFHPF3
                if (isBlankOrLowValues(context.getFromProgram())) {
                    context.setToProgram(MAIN_MENU_PROGRAM);        // MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
                } else {
                    context.setToProgram(context.getFromProgram()); // MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM
                }
                yield returnToPreviousScreen();                     // PERFORM RETURN-TO-PREV-SCREEN
            }
            case PFK04 -> clearCurrentScreen(form);                 // WHEN DFHPF4 -> CLEAR-CURRENT-SCREEN
            case PFK05 -> {                                         // WHEN DFHPF5
                context.setToProgram(TRANSACTION_LIST_PROGRAM);     // MOVE 'COTRN00C' TO CDEMO-TO-PROGRAM
                yield returnToPreviousScreen();                     // PERFORM RETURN-TO-PREV-SCREEN
            }
            case OTHER -> TransactionViewResult.ofError(MSG_INVALID_KEY); // WHEN OTHER
        };
    }

    /**
     * Validates the entered transaction id, reads the record, and populates the display fields;
     * the Java migration of paragraph {@code PROCESS-ENTER-KEY} in
     * {@code legacy/cbl/COTRN01C.cbl} (lines 144-192).
     *
     * <p>Reproduces the COBOL control flow:</p>
     * <ul>
     *   <li><b>Empty id</b> (lines 146-152): when {@code TRNIDINI} is blank
     *       ({@code = SPACES OR LOW-VALUES}) the error {@link #MSG_TRAN_ID_EMPTY} is returned and
     *       nothing is read.</li>
     *   <li><b>Read</b> (lines 158-174): otherwise the thirteen display fields are cleared
     *       ({@link #clearDisplayFields(COTRN01Form)}; note {@code TRNIDINI} itself is left
     *       intact), the search key {@code TRAN-ID} is set from {@code TRNIDINI}, and
     *       {@link #readTransactFile(String)} is performed. A CICS {@code NOTFND} surfaces as a
     *       {@link RecordNotFoundException} (propagated to the controller's exception handler);
     *       the CICS {@code WHEN OTHER} branch (an unexpected {@link DataAccessException} from the
     *       repository) is translated here into the {@link #MSG_UNABLE_LOOKUP} redisplay,
     *       reproducing the COBOL post-read {@code IF NOT ERR-FLG-ON} guard.</li>
     *   <li><b>Populate</b> (lines 176-192): on a successful read the thirteen display fields are
     *       populated from the record ({@link #populateDisplayFields(COTRN01Form, Transaction)}),
     *       with the amount edited via {@link #formatAmount(BigDecimal)}, and a plain screen
     *       redisplay is returned.</li>
     * </ul>
     *
     * @param form the bound screen form supplying {@code TRNIDINI} and receiving the populated
     *             display fields
     * @param ctx  the session context (COBOL {@code PROCESS-ENTER-KEY} does not itself touch the
     *             COMMAREA; the parameter preserves the paragraph's declared context and mirrors
     *             the sibling online services)
     * @return the outcome: an empty-id error, an unable-to-lookup error, or a populated screen
     *         redisplay
     * @throws RecordNotFoundException when the transaction id is not found (the CICS
     *                                 {@code NOTFND} path)
     */
    public TransactionViewResult processEnterKey(COTRN01Form form, CardDemoContext ctx) {
        // EVALUATE TRUE WHEN TRNIDINI = SPACES OR LOW-VALUES -> "Tran ID can NOT be empty...".
        if (isBlankOrLowValues(form.getTrnidin())) {
            return TransactionViewResult.ofError(MSG_TRAN_ID_EMPTY);
        }

        // IF NOT ERR-FLG-ON: clear the thirteen display fields (TRNIDINI is preserved),
        // MOVE TRNIDINI TO TRAN-ID, PERFORM READ-TRANSACT-FILE.
        clearDisplayFields(form);
        String tranId = form.getTrnidin();
        Transaction transaction;
        try {
            transaction = readTransactFile(tranId);
        } catch (RecordNotFoundException notFound) {
            // READ-TRANSACT-FILE WHEN NOTFND: MOVE "Transaction ID NOT found..." TO
            // WS-MESSAGE, SET ERR-FLG-ON, and re-display the SAME screen inline (it is
            // not an abend). Reproduce that inline re-display here rather than letting the
            // RecordNotFoundException escape to the full-page handler (AAP 0.6.5 parity).
            return TransactionViewResult.ofError(notFound.getMessage());
        } catch (DataAccessException unexpected) {
            // READ-TRANSACT-FILE WHEN OTHER: unexpected read failure -> redisplay with message.
            return TransactionViewResult.ofError(MSG_UNABLE_LOOKUP);
        }

        // IF NOT ERR-FLG-ON: populate the display fields and PERFORM SEND-TRNVIEW-SCREEN.
        populateDisplayFields(form, transaction);
        return TransactionViewResult.ofShow();
    }

    /**
     * Reads a single transaction by id, the Java migration of paragraph
     * {@code READ-TRANSACT-FILE} in {@code legacy/cbl/COTRN01C.cbl} (lines 267-296).
     *
     * <p>The COBOL {@code EXEC CICS READ DATASET('TRANSACT') ... UPDATE} becomes a keyed
     * {@link TransactionRepository#findById(Object)}. Although the COBOL specifies the
     * {@code UPDATE} option, {@code COTRN01C} never rewrites the record, so this lookup is
     * strictly read-only (no {@code save}, no write transaction).</p>
     *
     * <p>Response mapping (COBOL {@code EVALUATE WS-RESP-CD}):</p>
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} &rarr; the found {@link Transaction} is returned.</li>
     *   <li>{@code DFHRESP(NOTFND)} &rarr; a {@link RecordNotFoundException} carrying
     *       {@link #MSG_TRAN_NOT_FOUND} is thrown (the "Transaction ID NOT found..." path). The
     *       controller's {@code @ControllerAdvice} handler renders it on the screen's error
     *       line (AAP &sect;0.6.5).</li>
     *   <li>{@code WHEN OTHER} &rarr; an unexpected {@link DataAccessException} from the
     *       repository propagates to the caller, which translates it into the
     *       "Unable to lookup Transaction..." redisplay. It is deliberately <em>not</em> mapped
     *       to {@link RecordNotFoundException}, since an infrastructure error is not a
     *       record-not-found condition (only {@code RecordNotFoundException} is a declared
     *       dependency of this service).</li>
     * </ul>
     *
     * @param tranId the 16-character transaction id to look up (COBOL {@code TRAN-ID})
     * @return the matching transaction (COBOL {@code NORMAL} response)
     * @throws RecordNotFoundException when no transaction has the given id (COBOL {@code NOTFND})
     * @throws DataAccessException     when the repository read fails unexpectedly (COBOL
     *                                 {@code WHEN OTHER}); handled by the caller
     */
    public Transaction readTransactFile(String tranId) {
        return transactionRepository.findById(tranId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_TRAN_NOT_FOUND));
    }

    /**
     * Clears every field of the screen and redisplays it, the Java migration of paragraph
     * {@code CLEAR-CURRENT-SCREEN} in {@code legacy/cbl/COTRN01C.cbl} (lines 301-304).
     *
     * <p>Performs {@link #initializeAllFields(COTRN01Form)} (the COBOL
     * {@code PERFORM INITIALIZE-ALL-FIELDS}) and then returns a plain screen redisplay (the COBOL
     * {@code PERFORM SEND-TRNVIEW-SCREEN}). This is the PF4 handler.</p>
     *
     * @param form the screen form to clear
     * @return a plain screen-redisplay outcome with no message
     */
    public TransactionViewResult clearCurrentScreen(COTRN01Form form) {
        initializeAllFields(form);
        return TransactionViewResult.ofShow();
    }

    /**
     * Blanks every input and display field of the screen form, the Java migration of paragraph
     * {@code INITIALIZE-ALL-FIELDS} in {@code legacy/cbl/COTRN01C.cbl} (lines 309-326).
     *
     * <p>Reproduces the COBOL {@code MOVE SPACES} to the transaction-id search input
     * ({@code TRNIDINI}), the thirteen display fields, and the message line ({@code WS-MESSAGE},
     * surfaced as the form's {@code errmsg}). Unlike {@link #clearDisplayFields(COTRN01Form)}
     * &mdash; used by {@link #processEnterKey(COTRN01Form, CardDemoContext)} before a read, which
     * preserves {@code TRNIDINI} &mdash; this full reset also clears the search input. The COBOL
     * {@code MOVE -1 TO TRNIDINL} that positions the cursor is a presentation concern handled by
     * the controller and is intentionally omitted.</p>
     *
     * @param form the screen form whose fields are reset to blank
     */
    public void initializeAllFields(COTRN01Form form) {
        form.setTrnidin("");   // MOVE SPACES TO TRNIDINI (MOVE -1 TO TRNIDINL -> controller cursor)
        clearDisplayFields(form);
        form.setErrmsg("");    // MOVE SPACES TO WS-MESSAGE
    }

    /**
     * Returns control to the previous screen, reproducing the navigation-state bookkeeping of
     * paragraph {@code RETURN-TO-PREV-SCREEN} in {@code legacy/cbl/COTRN01C.cbl} (lines 197-208).
     *
     * <p>Mirrors the COBOL default-and-hand-off: when the target program ({@code CDEMO-TO-PROGRAM})
     * is unset ({@code LOW-VALUES OR SPACES}) it defaults to the sign-on program {@code COSGN00C};
     * the origin transaction id and program are recorded ({@code CDEMO-FROM-TRANID = 'CT01'},
     * {@code CDEMO-FROM-PROGRAM = 'COTRN01C'}); and the within-program context is reset to enter
     * ({@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}) so the target program starts fresh. The actual
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} transfer is executed by the paired
     * controller from the returned redirect outcome; this helper only maintains the session state
     * (hence it is private &mdash; {@code RETURN-TO-PREV-SCREEN} itself is a controller concern).</p>
     *
     * @return a redirect outcome targeting the (possibly defaulted) hand-off program
     */
    private TransactionViewResult returnToPreviousScreen() {
        if (isBlankOrLowValues(context.getToProgram())) {
            context.setToProgram(SIGNON_PROGRAM);   // IF CDEMO-TO-PROGRAM = LOW-VALUES/SPACES -> 'COSGN00C'
        }
        context.setFromTranid(TRANSACTION_ID);      // MOVE WS-TRANID TO CDEMO-FROM-TRANID
        context.setFromProgram(PROGRAM_NAME);        // MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
        context.markEnter();                         // MOVE ZEROS TO CDEMO-PGM-CONTEXT
        return TransactionViewResult.ofRedirect(context.getToProgram());
    }

    /**
     * Blanks the thirteen transaction display fields (leaving the {@code TRNIDINI} search input
     * intact), reproducing the {@code MOVE SPACES} block of {@code PROCESS-ENTER-KEY}
     * ({@code legacy/cbl/COTRN01C.cbl} lines 159-171).
     *
     * @param form the screen form whose display fields are cleared
     */
    private void clearDisplayFields(COTRN01Form form) {
        form.setTrnid("");     // TRNIDI
        form.setCardnum("");   // CARDNUMI
        form.setTtypcd("");    // TTYPCDI
        form.setTcatcd("");    // TCATCDI
        form.setTrnsrc("");    // TRNSRCI
        form.setTrnamt("");    // TRNAMTI
        form.setTdesc("");     // TDESCI
        form.setTorigdt("");   // TORIGDTI
        form.setTprocdt("");   // TPROCDTI
        form.setMid("");       // MIDI
        form.setMname("");     // MNAMEI
        form.setMcity("");     // MCITYI
        form.setMzip("");      // MZIPI
    }

    /**
     * Populates the thirteen transaction display fields from the read record, reproducing the
     * {@code MOVE} block of {@code PROCESS-ENTER-KEY} ({@code legacy/cbl/COTRN01C.cbl} lines
     * 176-192).
     *
     * <p>Each assignment honours the target BMS field width (the COBOL {@code MOVE} truncates a
     * wider source to the fixed field, e.g. {@code TRAN-DESC PIC X(100)} into {@code TDESCI PIC
     * X(60)}). Numeric source fields are rendered zero-padded to their picture width
     * ({@code TRAN-CAT-CD 9(04)}, {@code TRAN-MERCHANT-ID 9(09)}); the amount is edited via
     * {@link #formatAmount(BigDecimal)}; the 26-character timestamps are reduced to their
     * {@code yyyy-MM-dd} date portion via {@link #formatTimestampDate(LocalDateTime)}.</p>
     *
     * @param form        the screen form receiving the display values
     * @param transaction the transaction record that was read
     */
    private void populateDisplayFields(COTRN01Form form, Transaction transaction) {
        form.setTrnid(fit(transaction.getTranId(), TRNID_WIDTH));            // MOVE TRAN-ID TO TRNIDI
        form.setCardnum(fit(transaction.getCardNum(), CARDNUM_WIDTH));       // MOVE TRAN-CARD-NUM TO CARDNUMI
        form.setTtypcd(fit(transaction.getTranTypeCd(), TTYPCD_WIDTH));      // MOVE TRAN-TYPE-CD TO TTYPCDI
        form.setTcatcd(formatCategoryCode(transaction.getTranCatCd()));      // MOVE TRAN-CAT-CD TO TCATCDI
        form.setTrnsrc(fit(transaction.getTranSource(), TRNSRC_WIDTH));      // MOVE TRAN-SOURCE TO TRNSRCI
        form.setTrnamt(formatAmount(transaction.getTranAmt()));              // MOVE WS-TRAN-AMT TO TRNAMTI
        form.setTdesc(fit(transaction.getTranDesc(), TDESC_WIDTH));          // MOVE TRAN-DESC TO TDESCI
        form.setTorigdt(formatTimestampDate(transaction.getOrigTs()));       // MOVE TRAN-ORIG-TS TO TORIGDTI
        form.setTprocdt(formatTimestampDate(transaction.getProcTs()));       // MOVE TRAN-PROC-TS TO TPROCDTI
        form.setMid(formatMerchantId(transaction.getMerchantId()));          // MOVE TRAN-MERCHANT-ID TO MIDI
        form.setMname(fit(transaction.getMerchantName(), MNAME_WIDTH));      // MOVE TRAN-MERCHANT-NAME TO MNAMEI
        form.setMcity(fit(transaction.getMerchantCity(), MCITY_WIDTH));      // MOVE TRAN-MERCHANT-CITY TO MCITYI
        form.setMzip(fit(transaction.getMerchantZip(), MZIP_WIDTH));         // MOVE TRAN-MERCHANT-ZIP TO MZIPI
    }

    /**
     * Reports whether the supplied value is blank in the COBOL sense, reproducing the
     * {@code = SPACES OR LOW-VALUES} test used throughout {@code COTRN01C}.
     *
     * @param value the value to test (may be {@code null})
     * @return {@code true} when {@code value} is {@code null}, empty, or consists solely of
     *         spaces and/or low-value ({@code NUL}) characters
     */
    private static boolean isBlankOrLowValues(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ' ' && c != '\0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Fits an alphanumeric value into a fixed BMS field width, reproducing the truncation of a
     * COBOL {@code MOVE} of a (possibly wider) source into a {@code PIC X(n)} field: the leftmost
     * {@code maxLength} characters are kept. A {@code null} value becomes the empty string.
     *
     * <p>Right-padding to the exact field width is a presentation concern handled by the view, so
     * a shorter value is returned unchanged.</p>
     *
     * @param value     the source value (may be {@code null})
     * @param maxLength the target BMS field width
     * @return the value truncated to at most {@code maxLength} characters, or {@code ""} when
     *         {@code value} is {@code null}
     */
    private static String fit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * Formats a monetary amount into the COBOL edit picture {@code WS-TRAN-AMT PIC +99999999.99},
     * reproducing {@code MOVE TRAN-AMT TO WS-TRAN-AMT} ({@code legacy/cbl/COTRN01C.cbl} line 177).
     *
     * <p>The rendering is a leading sign ({@code '+'} for zero or positive, {@code '-'} for
     * negative), eight integer digits zero-padded on the left, a decimal point, and two fraction
     * digits &mdash; twelve characters in total. Because the source {@code TRAN-AMT PIC S9(09)V99}
     * carries nine integer digits but this edit field holds only eight, a value whose integer part
     * exceeds eight digits loses its high-order digit(s); that COBOL truncation is reproduced by
     * keeping the low-order {@value #AMOUNT_INTEGER_DIGITS} integer digits. The scale is fixed at
     * two digits with {@link RoundingMode#DOWN} (the stored value is already scale 2, so this only
     * guards against an unexpected wider scale, matching COBOL's non-rounding {@code MOVE}). A
     * {@code null} amount renders as {@code "+00000000.00"}.</p>
     *
     * @param amount the authoritative transaction amount (may be {@code null})
     * @return the twelve-character edited amount string
     */
    private static String formatAmount(BigDecimal amount) {
        BigDecimal value = (amount == null) ? BigDecimal.ZERO : amount;
        BigDecimal scaled = value.setScale(2, RoundingMode.DOWN);
        char sign = (scaled.signum() < 0) ? '-' : '+';
        BigDecimal absolute = scaled.abs();

        // Total value in cents (scale 2 -> exact integer), split into whole units and fraction.
        long cents = absolute.movePointRight(2).longValueExact();
        long whole = cents / 100L;
        long fraction = cents % 100L;

        String wholeDigits = Long.toString(whole);
        if (wholeDigits.length() > AMOUNT_INTEGER_DIGITS) {
            // PIC has eight integer digits; the COBOL MOVE drops the high-order overflow digit(s).
            wholeDigits = wholeDigits.substring(wholeDigits.length() - AMOUNT_INTEGER_DIGITS);
        } else {
            wholeDigits = "0".repeat(AMOUNT_INTEGER_DIGITS - wholeDigits.length()) + wholeDigits;
        }
        return String.format(Locale.ROOT, "%c%s.%02d", sign, wholeDigits, fraction);
    }

    /**
     * Formats the transaction category code for display, reproducing {@code MOVE TRAN-CAT-CD TO
     * TCATCDI} where {@code TRAN-CAT-CD PIC 9(04)} renders zero-padded into {@code TCATCDI PIC
     * X(4)}. A {@code null} value renders as the empty string.
     *
     * @param categoryCode the numeric category code (may be {@code null})
     * @return the four-digit zero-padded code, or {@code ""} when {@code categoryCode} is
     *         {@code null}
     */
    private static String formatCategoryCode(Integer categoryCode) {
        if (categoryCode == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%0" + CATEGORY_CODE_DIGITS + "d", categoryCode);
    }

    /**
     * Formats the merchant id for display, reproducing {@code MOVE TRAN-MERCHANT-ID TO MIDI} where
     * {@code TRAN-MERCHANT-ID PIC 9(09)} renders zero-padded into {@code MIDI PIC X(9)}. A
     * {@code null} value renders as the empty string.
     *
     * @param merchantId the numeric merchant id (may be {@code null})
     * @return the nine-digit zero-padded id, or {@code ""} when {@code merchantId} is {@code null}
     */
    private static String formatMerchantId(Long merchantId) {
        if (merchantId == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%0" + MERCHANT_ID_DIGITS + "d", merchantId);
    }

    /**
     * Formats a transaction timestamp for the {@code PIC X(10)} date display fields, reproducing
     * the COBOL {@code MOVE} of a {@code PIC X(26)} timestamp ({@code TRAN-ORIG-TS} /
     * {@code TRAN-PROC-TS}, formatted {@code yyyy-MM-dd HH:mm:ss.SSSSSS}) into the ten-character
     * {@code TORIGDTI} / {@code TPROCDTI} fields: the leftmost ten characters &mdash; the
     * {@code yyyy-MM-dd} date &mdash; are kept. A {@code null} timestamp renders as the empty
     * string.
     *
     * @param timestamp the transaction timestamp (may be {@code null})
     * @return the {@code yyyy-MM-dd} date string, or {@code ""} when {@code timestamp} is
     *         {@code null}
     */
    private static String formatTimestampDate(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "";
        }
        return fit(TIMESTAMP_DATE_FORMAT.format(timestamp), TIMESTAMP_DISPLAY_WIDTH);
    }
}
