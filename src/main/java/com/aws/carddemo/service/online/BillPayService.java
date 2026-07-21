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
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.aws.carddemo.util.CobolDecimal;

/**
 * Bill-payment online service, the Java migration of the CICS COBOL program
 * {@code COBIL00C} (the AWS CardDemo online bill-payment transaction).
 *
 * <p><b>Origin:</b> {@code legacy/cbl/COBIL00C.cbl} &mdash; program {@code COBIL00C},
 * CICS transaction id {@code CB00}. This service preserves the original program's control
 * flow one-for-one: each business COBOL paragraph becomes exactly one Java method
 * (AAP &sect;0.3.3, &sect;0.4.1). {@code COBIL00C} is a <em>read&ndash;update&ndash;rewrite
 * plus write</em> archetype: it reads the account, writes a new bill-payment transaction for
 * the <em>entire</em> current balance, and rewrites the account with a zeroed balance. That
 * three-step sequence is one unit of work, so the confirmed-payment path is
 * {@link Transactional}.</p>
 *
 * <h2>Paragraph &rarr; method mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} &rarr; {@link #mainEntry(AidKey, COBIL00Form)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} &rarr; {@link #processEnterKey(COBIL00Form)}</li>
 *   <li>{@code GET-CURRENT-TIMESTAMP} &rarr; {@link #getCurrentTimestamp()}</li>
 *   <li>{@code READ-ACCTDAT-FILE} &rarr; {@link #readAcctdatFile(Long)}</li>
 *   <li>{@code UPDATE-ACCTDAT-FILE} &rarr; {@link #updateAcctdatFile(Account)}</li>
 *   <li>{@code READ-CXACAIX-FILE} &rarr; {@link #readCxacaixFile(Long)}</li>
 *   <li>{@code STARTBR-TRANSACT-FILE} + {@code READPREV-TRANSACT-FILE} +
 *       {@code ENDBR-TRANSACT-FILE} &rarr; {@link #nextTransactionId()} (the browse-to-last-key
 *       triplet collapses to a single ordered top-1 query; see that method)</li>
 *   <li>{@code WRITE-TRANSACT-FILE} &rarr; {@link #writeTransactFile(Transaction)}</li>
 *   <li>{@code CLEAR-CURRENT-SCREEN} &rarr; {@link #clearCurrentScreen(COBIL00Form)}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} &rarr; {@link #initializeAllFields(COBIL00Form)}</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} &rarr; {@link #returnToPrevScreen()}</li>
 * </ul>
 *
 * <p>The presentation paragraphs {@code SEND-BILLPAY-SCREEN}, {@code RECEIVE-BILLPAY-SCREEN}
 * and {@code POPULATE-HEADER-INFO} are intentionally <em>not</em> implemented here: sending and
 * receiving the 3270/BMS map ({@code COBIL0A} of mapset {@code COBIL00}), populating the header
 * (title/date/time), performing the {@code ERASE}/cursor handling, and executing the actual
 * navigation redirect are presentation concerns owned by the paired {@code BillPayController}.
 * This service contains business logic only &mdash; it validates input, performs the data
 * access, computes the balance, and produces a message &mdash; and returns a
 * {@link BillPayResult} that tells the controller what to render next.</p>
 *
 * <h2>Pseudo-conversational state (AAP &sect;0.6.8)</h2>
 * <p>The COBOL COMMAREA ({@code COCOM01Y}) that carried navigation state across CICS returns
 * becomes the session-scoped {@link CardDemoContext}, injected here. First entry into the
 * transaction (COBOL {@code EIBCALEN = 0}) is reproduced by {@link CardDemoContext#isNew()};
 * the {@code XCTL}/{@code RETURN TRANSID} hand-off is reproduced through the context's
 * {@code from*}/{@code to*} program fields, which the controller consults when redirecting.
 * The COBOL first-entry pre-selection field {@code CDEMO-CB00-TRN-SELECTED} has no dedicated
 * slot in {@link CardDemoContext}; it is mapped to the session-selected account id
 * ({@link CardDemoContext#getAcctId()}). When that id is present on first entry (the
 * menu&rarr;bill-pay hand-off), it is pre-loaded into the account-id field and
 * {@code PROCESS-ENTER-KEY} runs immediately, exactly as {@code MAIN-PARA} does; when absent,
 * the empty bill-pay screen is shown.</p>
 *
 * <h2>Decimal fidelity (AAP &sect;0.6.1)</h2>
 * <p>The account balance and transaction amount are packed-decimal money fields
 * ({@code ACCT-CURR-BAL PIC S9(10)V99}, {@code TRAN-AMT PIC S9(09)V99}). All balance
 * arithmetic goes through {@link CobolDecimal} (scale 2, {@code RoundingMode.DOWN}); no
 * floating-point type is ever used. The COBOL statement
 * {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} has no {@code ROUNDED} clause, so
 * the migrated subtraction truncates to two decimals via {@link CobolDecimal#money(BigDecimal)}.
 * Because {@code TRAN-AMT} is set to the entire current balance, the post-payment balance is
 * exactly zero.</p>
 *
 * <h2>Exception mapping (AAP &sect;0.6.5)</h2>
 * <p>COBOL {@code FILE STATUS} / CICS {@code RESP} handling is preserved as follows. A
 * {@code NOTFND} on the account or cross-reference read raises a {@link RecordNotFoundException}
 * carrying the COBOL message {@code "Account ID NOT found..."}; {@link #processEnterKey} catches it
 * and re-displays the SAME screen inline with that error line (COBOL {@code READ-ACCTDAT-FILE WHEN
 * NOTFND} sets {@code WS-ERR-FLG} and re-sends the map - it is not an abend), rather than surfacing
 * a full-page error. A {@code DUPKEY}/{@code DUPREC} on the transaction write becomes a
 * {@link DuplicateKeyException} carrying {@code "Tran ID already exist..."}. Any other
 * unexpected data-access failure (the COBOL {@code WHEN OTHER} branches, e.g.
 * {@code "Unable to lookup Account..."}, {@code "Unable to Update Account..."},
 * {@code "Unable to lookup XREF AIX file..."}, {@code "Unable to Add Bill pay Transaction..."})
 * is allowed to propagate as a Spring {@code DataAccessException} to the global exception
 * handler; those message literals are documented on the individual I/O methods. Validation
 * outcomes (empty account id, invalid Y/N, nothing to pay, confirm prompt) are <em>not</em>
 * exceptions: they are returned as a {@link BillPayResult} for redisplay, mirroring the COBOL
 * error-flag ({@code WS-ERR-FLG}) paths that set {@code WS-MESSAGE} and re-send the screen.</p>
 *
 * <h2>Access and design constraints</h2>
 * <ul>
 *   <li><b>Constructor injection:</b> the session context and the three repositories are
 *       injected through a single constructor (no {@code @Autowired} needed).</li>
 *   <li><b>No Lombok:</b> plain Java with explicit members, compiled warning-free under
 *       {@code --release 25}.</li>
 *   <li><b>Message literals:</b> every user-facing message is a local constant that mirrors the
 *       COBOL literal byte-for-byte (the invalid-key text mirrors {@code CCDA-MSG-INVALID-KEY}
 *       from {@code legacy/cpy/CSMSG01Y.cpy}); the shared message-constants holder is not a
 *       declared dependency of this service, so its values are reproduced locally rather than
 *       imported.</li>
 *   <li><b>No feature expansion (AAP &sect;0.2.2):</b> {@code COBIL00C} always pays the entire
 *       current balance; there is deliberately no partial-amount capability.</li>
 * </ul>
 */
@Service
public class BillPayService {

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'COBIL00C'} &mdash; this program's name. */
    private static final String PROGRAM_NAME = "COBIL00C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CB00'} &mdash; this program's CICS transaction id. */
    private static final String TRANSACTION_ID = "CB00";

    /**
     * Sign-on program (COBOL literal {@code 'COSGN00C'}) &mdash; the destination for the
     * first-entry bounce when no COMMAREA is present ({@code EIBCALEN = 0}) and the default
     * hand-off target when none is otherwise set.
     */
    private static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Main-menu program (COBOL literal {@code 'COMEN01C'}) &mdash; the PF3 return target when the
     * originating program ({@code CDEMO-FROM-PROGRAM}) is not recorded.
     */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** Transaction type code for a bill payment (COBOL {@code MOVE '02' TO TRAN-TYPE-CD}). */
    private static final String TRAN_TYPE_BILL_PAYMENT = "02";

    /** Transaction category code for a bill payment (COBOL {@code MOVE 2 TO TRAN-CAT-CD}). */
    private static final Integer TRAN_CAT_BILL_PAYMENT = 2;

    /** Transaction source for a bill payment (COBOL {@code MOVE 'POS TERM' TO TRAN-SOURCE}). */
    private static final String TRAN_SOURCE_POS_TERM = "POS TERM";

    /**
     * Transaction description for a bill payment
     * (COBOL {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC}).
     */
    private static final String TRAN_DESC_BILL_PAYMENT = "BILL PAYMENT - ONLINE";

    /** Fixed merchant id for a bill payment (COBOL {@code MOVE 999999999 TO TRAN-MERCHANT-ID}). */
    private static final Long MERCHANT_ID_BILL_PAYMENT = 999999999L;

    /** Merchant name for a bill payment (COBOL {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME}). */
    private static final String MERCHANT_NAME_BILL_PAYMENT = "BILL PAYMENT";

    /** Merchant city for a bill payment (COBOL {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY}). */
    private static final String MERCHANT_CITY_NA = "N/A";

    /** Merchant zip for a bill payment (COBOL {@code MOVE 'N/A' TO TRAN-MERCHANT-ZIP}). */
    private static final String MERCHANT_ZIP_NA = "N/A";

    /** Width of the {@code TRAN-ID} key (COBOL {@code PIC 9(16)} / {@code PIC X(16)}). */
    private static final int TRAN_ID_WIDTH = 16;

    /** Width of the {@code ACTID} account-id field (COBOL {@code PIC X(11)}). */
    private static final int ACCT_ID_WIDTH = 11;

    /**
     * Number of whole integer digit positions in the balance edit mask
     * (COBOL {@code WS-CURR-BAL PIC +9999999999.99} &mdash; ten digits before the decimal point).
     */
    private static final int CURR_BAL_INT_DIGITS = 10;

    /** Empty-account-id message. Byte-exact COBOL literal (paragraph {@code PROCESS-ENTER-KEY}). */
    private static final String MSG_ACCT_EMPTY = "Acct ID can NOT be empty...";

    /** Invalid confirm-value message. Byte-exact COBOL literal (paragraph {@code PROCESS-ENTER-KEY}). */
    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

    /** Nothing-to-pay message. Byte-exact COBOL literal (paragraph {@code PROCESS-ENTER-KEY}). */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** Confirm-payment prompt. Byte-exact COBOL literal (paragraph {@code PROCESS-ENTER-KEY}). */
    private static final String MSG_CONFIRM_PAYMENT = "Confirm to make a bill payment...";

    /**
     * Account-not-found message. Byte-exact COBOL literal used by both the account read
     * ({@code READ-ACCTDAT-FILE}) and the cross-reference read ({@code READ-CXACAIX-FILE}) on
     * {@code NOTFND}. Carried by the thrown {@link RecordNotFoundException}.
     */
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

    /**
     * Duplicate-transaction message. Byte-exact COBOL literal from {@code WRITE-TRANSACT-FILE}
     * on {@code DUPKEY}/{@code DUPREC}. Carried by the thrown {@link DuplicateKeyException}.
     */
    private static final String MSG_TRAN_ID_EXISTS = "Tran ID already exist...";

    /**
     * Invalid-key message. Mirrors COBOL {@code CCDA-MSG-INVALID-KEY} (from
     * {@code legacy/cpy/CSMSG01Y.cpy}), moved to {@code WS-MESSAGE} in {@code MAIN-PARA} for any
     * unmapped AID key. Declared locally because the shared message-constants holder is not a
     * declared dependency of this service.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Success-message prefix. Reproduces the COBOL {@code STRING 'Payment successful. '
     * ' Your Transaction ID is '} concatenation; note the <em>two</em> spaces after
     * {@code "successful."} (the trailing space of the first literal plus the leading space of
     * the second) and the single trailing space after {@code "is"}. The transaction id and a
     * terminating {@code '.'} are appended by {@link #buildSuccessMessage(String)}.
     */
    private static final String SUCCESS_MSG_PREFIX = "Payment successful.  Your Transaction ID is ";

    /** Success-message suffix (COBOL {@code STRING ... '.'}). */
    private static final String SUCCESS_MSG_SUFFIX = ".";

    /**
     * Session-scoped navigation and selection context, the modern replacement for the COBOL
     * COMMAREA ({@code COCOM01Y}). Injected as a Spring session-scoped proxy.
     */
    private final CardDemoContext context;

    /**
     * Account data-access repository (VSAM {@code ACCTDAT} KSDS). Used to read the account for
     * update and to rewrite it with the post-payment balance.
     */
    private final AccountRepository accountRepository;

    /**
     * Card cross-reference repository (VSAM {@code CCXREF}, alternate index {@code CXACAIX}).
     * Used to resolve the card number that owns the account for the bill-payment transaction.
     */
    private final CardXrefRepository cardXrefRepository;

    /**
     * Transaction repository (VSAM {@code TRANSACT} KSDS). Used to derive the next transaction
     * id (the browse-to-last-key equivalent) and to write the bill-payment transaction.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Creates the bill-payment service via Spring constructor injection.
     *
     * <p>A single constructor means no {@code @Autowired} annotation is required. No argument is
     * dereferenced here, so the constructor introduces no {@code this}-escape.</p>
     *
     * @param context               the session-scoped CardDemo context (COMMAREA replacement);
     *                              must not be {@code null}
     * @param accountRepository     the account repository (VSAM {@code ACCTDAT}); must not be
     *                              {@code null}
     * @param cardXrefRepository    the card cross-reference repository (VSAM {@code CCXREF} via
     *                              alternate index {@code CXACAIX}); must not be {@code null}
     * @param transactionRepository the transaction repository (VSAM {@code TRANSACT}); must not
     *                              be {@code null}
     */
    public BillPayService(CardDemoContext context,
                          AccountRepository accountRepository,
                          CardXrefRepository cardXrefRepository,
                          TransactionRepository transactionRepository) {
        this.context = context;
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Attention-identifier (AID) keys handled by {@link #mainEntry(AidKey, COBIL00Form)},
     * mirroring the COBOL {@code EVALUATE EIBAID} of {@code MAIN-PARA}.
     *
     * <p>The paired {@code BillPayController} maps the inbound HTTP submission (the pressed
     * button or PF key) to one of these constants before delegating to the service. Only the
     * keys with distinct behaviour in {@code COBIL00C} are modelled: {@link #ENTER}
     * ({@code DFHENTER}), {@link #PF3} ({@code DFHPF3}) and {@link #PF4} ({@code DFHPF4});
     * every other key collapses to {@link #OTHER}, matching the COBOL {@code WHEN OTHER}
     * default.</p>
     */
    public enum AidKey {

        /** COBOL {@code DFHENTER} &mdash; the ENTER key; processes the bill payment. */
        ENTER,

        /** COBOL {@code DFHPF3} &mdash; the PF3 key; returns to the previous screen. */
        PF3,

        /** COBOL {@code DFHPF4} &mdash; the PF4 key; clears the current screen. */
        PF4,

        /** COBOL {@code WHEN OTHER} &mdash; any other key; yields the invalid-key message. */
        OTHER
    }

    /**
     * Severity (and thus rendering) of the message a {@link BillPayResult} carries, capturing
     * the COBOL screen colouring distinctions of {@code COBIL00C}.
     *
     * <p>{@code COBIL00C} produces three visible message states plus "no message":</p>
     * <ul>
     *   <li>{@link #NONE} &mdash; no message (a blank or freshly cleared screen).</li>
     *   <li>{@link #NEUTRAL} &mdash; the confirm-payment prompt. The COBOL {@code ELSE} branch
     *       of {@code PROCESS-ENTER-KEY} sets this text <em>without</em> raising the error flag
     *       ({@code WS-ERR-FLG}) and without setting an explicit colour, so the legacy screen
     *       renders it in the map's default {@code ERRMSG} colour; it is neither a hard error
     *       nor the green success line.</li>
     *   <li>{@link #ERROR} &mdash; an error line. Every COBOL path that sets
     *       {@code WS-ERR-FLG = 'Y'} (empty account id, invalid Y/N, nothing to pay,
     *       invalid key) maps here.</li>
     *   <li>{@link #SUCCESS} &mdash; the payment-successful line, which the COBOL colours green
     *       ({@code MOVE DFHGREEN TO ERRMSGC}).</li>
     * </ul>
     *
     * <p>Selecting the concrete style/colour for each severity is a presentation concern owned
     * by the controller; this enum only conveys the COBOL semantics.</p>
     */
    public enum MessageSeverity {

        /** No message to display. */
        NONE,

        /** A neutral prompt (the confirm-payment line; error flag off, default colour). */
        NEUTRAL,

        /** An error line (a COBOL {@code WS-ERR-FLG = 'Y'} path). */
        ERROR,

        /** The green success line (COBOL {@code DFHGREEN}). */
        SUCCESS
    }

    /**
     * Immutable outcome of a bill-payment interaction, describing what the controller should do
     * next: perform a redirect, or redisplay the bill-payment screen with (optionally) a
     * message.
     *
     * <p>Exactly one of two shapes is produced:</p>
     * <ul>
     *   <li><b>Redirect</b> &mdash; {@link #targetProgram()} is set (and {@link #isRedirect()}
     *       is {@code true}); the controller redirects to the route mapped from that program
     *       name. This reproduces the COBOL {@code XCTL} performed by
     *       {@code RETURN-TO-PREV-SCREEN}.</li>
     *   <li><b>Redisplay</b> &mdash; {@link #targetProgram()} is {@code null}; the controller
     *       redisplays the bill-payment screen. {@link #message()} is the line to show (possibly
     *       empty) and {@link #severity()} says how to render it, reproducing the COBOL
     *       {@code WS-MESSAGE} / {@code ERRMSGC} distinctions.</li>
     * </ul>
     *
     * @param targetProgram the target program name for a redirect, or {@code null} for a
     *                      redisplay outcome
     * @param message       the message to redisplay; never {@code null} (normalised to the empty
     *                      string), and empty when there is nothing to show
     * @param severity      how the message should be rendered; never {@code null} (normalised to
     *                      {@link MessageSeverity#NONE})
     */
    public record BillPayResult(String targetProgram, String message, MessageSeverity severity) {

        /**
         * Canonical constructor normalising {@code null} inputs so that consumers never have to
         * null-check: a {@code null} message becomes the empty string and a {@code null}
         * severity becomes {@link MessageSeverity#NONE}.
         *
         * @param targetProgram the redirect target, or {@code null} for a redisplay
         * @param message       the message text, or {@code null} (treated as empty)
         * @param severity      the message severity, or {@code null} (treated as
         *                      {@link MessageSeverity#NONE})
         */
        public BillPayResult {
            if (message == null) {
                message = "";
            }
            if (severity == null) {
                severity = MessageSeverity.NONE;
            }
        }

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
         * @return {@code true} when {@link #message()} is non-empty
         */
        public boolean hasMessage() {
            return !message.isEmpty();
        }

        /**
         * Creates a redirect outcome targeting the given program (the COBOL {@code XCTL}
         * destination).
         *
         * @param targetProgram the target program name
         * @return a redirect outcome
         */
        private static BillPayResult redirect(String targetProgram) {
            return new BillPayResult(targetProgram, "", MessageSeverity.NONE);
        }

        /**
         * Creates a redisplay outcome with no message (a blank or cleared screen).
         *
         * @return a message-free redisplay outcome
         */
        private static BillPayResult showScreen() {
            return new BillPayResult(null, "", MessageSeverity.NONE);
        }

        /**
         * Creates an error-message (redisplay) outcome, reproducing a COBOL
         * {@code WS-ERR-FLG = 'Y'} path.
         *
         * @param message the error message to redisplay
         * @return an error outcome
         */
        private static BillPayResult error(String message) {
            return new BillPayResult(null, message, MessageSeverity.ERROR);
        }

        /**
         * Creates a neutral-prompt (redisplay) outcome, reproducing the COBOL confirm-payment
         * line (error flag off, default colour).
         *
         * @param message the prompt to redisplay
         * @return a neutral outcome
         */
        private static BillPayResult neutral(String message) {
            return new BillPayResult(null, message, MessageSeverity.NEUTRAL);
        }

        /**
         * Creates a success (redisplay) outcome, reproducing the COBOL green
         * ({@code DFHGREEN}) payment-successful line.
         *
         * @param message the success message to redisplay
         * @return a success outcome
         */
        private static BillPayResult success(String message) {
            return new BillPayResult(null, message, MessageSeverity.SUCCESS);
        }
    }

    /**
     * Handles a bill-payment interaction, the Java migration of paragraph {@code MAIN-PARA} in
     * {@code legacy/cbl/COBIL00C.cbl}.
     *
     * <p>Reproduces the COBOL control flow one-for-one:</p>
     * <ul>
     *   <li>On first entry with no COMMAREA (COBOL {@code EIBCALEN = 0}, reproduced by
     *       {@link CardDemoContext#isNew()}) control returns to the sign-on screen
     *       ({@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}, then {@code RETURN-TO-PREV-SCREEN}).</li>
     *   <li>On the first entry <em>within</em> the conversation (COBOL
     *       {@code IF NOT CDEMO-PGM-REENTER}) the program is marked re-entered and, when an
     *       account has been pre-selected (COBOL {@code CDEMO-CB00-TRN-SELECTED}, mapped to
     *       {@link CardDemoContext#getAcctId()}), that id is loaded into the account-id field and
     *       {@code PROCESS-ENTER-KEY} runs immediately; otherwise the empty screen is shown.</li>
     *   <li>On re-entry the pressed AID key is evaluated: {@code ENTER} processes the payment;
     *       {@code PF3} returns to the originating program (or the main menu {@code COMEN01C}
     *       when none is recorded); {@code PF4} clears the screen; any other key yields the
     *       invalid-key message.</li>
     * </ul>
     *
     * <p>This method is {@link Transactional}: it is the controller-facing entry point, so the
     * transaction boundary is established here (at the Spring proxy). The internal call to
     * {@link #processEnterKey(COBIL00Form)} &mdash; both the pre-load path and the {@code ENTER}
     * path &mdash; therefore runs within this transaction, which is what makes the
     * read&ndash;write&ndash;update payment sequence a single atomic unit of work despite the
     * self-invocation (a nested {@code @Transactional} would otherwise be bypassed).</p>
     *
     * @param aid  the attention-identifier key pressed; a {@code null} value is treated as
     *             {@link AidKey#OTHER}, matching the COBOL {@code WHEN OTHER} default
     * @param form the submitted bill-payment screen form (map {@code COBIL0A} of mapset
     *             {@code COBIL00}); must not be {@code null}
     * @return the interaction outcome: a redirect target, or a screen to redisplay (optionally
     *         with a message)
     */
    @Transactional
    public BillPayResult mainEntry(AidKey aid, COBIL00Form form) {
        // MAIN-PARA: SET ERR-FLG-OFF / USR-MODIFIED-NO and MOVE SPACES TO WS-MESSAGE are
        // represented by producing a fresh BillPayResult per call (no residual state here).

        // IF EIBCALEN = 0 -> bounce back to the sign-on screen.
        if (context.isNew()) {
            context.setToProgram(SIGNON_PROGRAM);
            return returnToPrevScreen();
        }

        // ELSE: a COMMAREA is present. IF NOT CDEMO-PGM-REENTER -> first display of this
        // transaction; mark re-enter and optionally pre-process a pre-selected account.
        if (context.isProgramEnter()) {
            context.markReenter();
            Long selectedAcctId = context.getAcctId();
            // IF CDEMO-CB00-TRN-SELECTED NOT = SPACES AND LOW-VALUES -> pre-load + PROCESS-ENTER-KEY.
            if (selectedAcctId != null) {
                form.setActidin(formatAccountId(selectedAcctId));
                return processEnterKey(form);
            }
            // Otherwise just show the (empty) bill-pay screen.
            return BillPayResult.showScreen();
        }

        // ELSE (CDEMO-PGM-REENTER): RECEIVE-BILLPAY-SCREEN then EVALUATE EIBAID.
        // A null AID collapses to the WHEN OTHER branch.
        AidKey effectiveAid = (aid == null) ? AidKey.OTHER : aid;
        return switch (effectiveAid) {
            case ENTER -> processEnterKey(form);
            case PF3 -> {
                // DFHPF3: IF CDEMO-FROM-PROGRAM blank -> 'COMEN01C' ELSE CDEMO-FROM-PROGRAM.
                if (isBlankOrLowValues(context.getFromProgram())) {
                    context.setToProgram(MENU_PROGRAM);
                } else {
                    context.setToProgram(context.getFromProgram());
                }
                yield returnToPrevScreen();
            }
            case PF4 -> clearCurrentScreen(form);
            case OTHER -> BillPayResult.error(MSG_INVALID_KEY);
        };
    }

    /**
     * Records the hand-off state and yields a redirect, the Java migration of paragraph
     * {@code RETURN-TO-PREV-SCREEN} in {@code legacy/cbl/COBIL00C.cbl}.
     *
     * <p>Reproduces the COBOL exactly: when the target program is unset it defaults to the
     * sign-on program ({@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES MOVE 'COSGN00C'}); the
     * from-transaction and from-program are set to this program's ids
     * ({@code MOVE WS-TRANID TO CDEMO-FROM-TRANID}, {@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM});
     * and the program context is reset to enter ({@code MOVE ZEROS TO CDEMO-PGM-CONTEXT},
     * reproduced by {@link CardDemoContext#markEnter()}). The COBOL {@code XCTL PROGRAM(...)}
     * is expressed as a redirect outcome naming the target program.</p>
     *
     * @return a redirect outcome naming the (possibly defaulted) target program
     */
    private BillPayResult returnToPrevScreen() {
        if (isBlankOrLowValues(context.getToProgram())) {
            context.setToProgram(SIGNON_PROGRAM);
        }
        context.setFromTranid(TRANSACTION_ID);
        context.setFromProgram(PROGRAM_NAME);
        context.markEnter();
        return BillPayResult.redirect(context.getToProgram());
    }

    /**
     * Validates the account id and confirmation, then either reports a validation outcome or
     * performs the bill payment. The Java migration of paragraph {@code PROCESS-ENTER-KEY} in
     * {@code legacy/cbl/COBIL00C.cbl}.
     *
     * <p>Reproduces the COBOL control flow one-for-one:</p>
     * <ol>
     *   <li>{@code SET CONF-PAY-NO TO TRUE} &mdash; the local {@code confirmed} flag starts
     *       {@code false}.</li>
     *   <li>If the account id is blank ({@code ACTIDINI = SPACES OR LOW-VALUES}) return the
     *       {@code "Acct ID can NOT be empty..."} error.</li>
     *   <li>{@code EVALUATE CONFIRMI}: {@code 'Y'}/{@code 'y'} confirms and reads the account;
     *       {@code 'N'}/{@code 'n'} clears the screen (the COBOL then silently sets the error
     *       flag, so nothing further happens); blanks/low-values read the account without
     *       confirming; anything else returns the {@code "Invalid value..."} error.</li>
     *   <li>Move the balance to the display field ({@code MOVE ACCT-CURR-BAL TO WS-CURR-BAL} then
     *       {@code TO CURBALI}).</li>
     *   <li>If the balance is not positive ({@code ACCT-CURR-BAL <= ZEROS}) return the
     *       {@code "You have nothing to pay..."} error (the account id is already known to be
     *       non-blank at this point).</li>
     *   <li>If confirmed, perform the payment (block C): resolve the card via the cross-reference
     *       alternate index, derive the next transaction id, build and write the bill-payment
     *       transaction for the <em>entire</em> current balance, subtract it from the balance
     *       (yielding zero), rewrite the account, clear the fields and return the green success
     *       line. If not confirmed, return the neutral {@code "Confirm to make a bill
     *       payment..."} prompt.</li>
     * </ol>
     *
     * <p>This method is {@link Transactional} so that the confirmed-payment sequence &mdash;
     * read account, write transaction, rewrite account &mdash; is one atomic unit of work
     * (AAP &sect;0.6.1); a {@link DuplicateKeyException} thrown mid-sequence rolls the whole payment
     * back. When invoked internally from {@link #mainEntry(AidKey, COBIL00Form)} it joins that
     * method's transaction (default {@code REQUIRED} propagation); when invoked directly by the
     * controller through the Spring proxy its own annotation applies.</p>
     *
     * <p>A {@code NOTFND} on either the account read ({@code READ-ACCTDAT-FILE}) or the
     * cross-reference read ({@code READ-CXACAIX-FILE}) is <em>not</em> an abend: the COBOL sets
     * {@code WS-ERR-FLG}, moves {@code "Account ID NOT found..."} to {@code WS-MESSAGE}, and
     * re-sends the map. This method reproduces that by catching the {@link RecordNotFoundException}
     * and returning an error {@link BillPayResult} for inline re-display (AAP &sect;0.6.5), so it
     * does not escape to the full-page handler.</p>
     *
     * @param form the submitted bill-payment screen form supplying the account id and
     *             confirmation, and receiving the balance display and cleared fields; must not
     *             be {@code null}
     * @return the interaction outcome: an error (including the inline account/cross-reference
     *         not-found line), the confirm prompt, the success line, or a cleared screen
     * @throws DuplicateKeyException   if the transaction id already exists (COBOL
     *                                 {@code DUPKEY}/{@code DUPREC}, message {@code "Tran ID
     *                                 already exist..."})
     */
    @Transactional
    public BillPayResult processEnterKey(COBIL00Form form) {
        // SET CONF-PAY-NO TO TRUE.
        boolean confirmed = false;

        // EVALUATE TRUE WHEN ACTIDINI = SPACES OR LOW-VALUES -> empty account id error.
        String actid = (form.getActidin() == null) ? "" : form.getActidin();
        if (isBlankOrLowValues(actid)) {
            return BillPayResult.error(MSG_ACCT_EMPTY);
        }

        // IF NOT ERR-FLG-ON: MOVE ACTIDINI TO ACCT-ID / XREF-ACCT-ID, then EVALUATE CONFIRMI.
        Account account;
        String confirm = form.getConfirm();
        // READ-ACCTDAT-FILE WHEN NOTFND moves 'Account ID NOT found...' to the message, sets
        // ERR-FLG, and re-displays the SAME screen inline (it is not an abend). Reproduce that
        // inline re-display here rather than letting the RecordNotFoundException escape to the
        // full-page handler (AAP 0.6.5 exception parity). No payment is attempted on a miss.
        try {
            if ("Y".equals(confirm) || "y".equals(confirm)) {
                // WHEN 'Y' WHEN 'y': SET CONF-PAY-YES; READ-ACCTDAT-FILE.
                confirmed = true;
                account = readAcctdatFile(parseAccountId(actid));
            } else if ("N".equals(confirm) || "n".equals(confirm)) {
                // WHEN 'N' WHEN 'n': CLEAR-CURRENT-SCREEN then set the error flag (silent) -> stop.
                return clearCurrentScreen(form);
            } else if (isBlankOrLowValues(confirm)) {
                // WHEN SPACES WHEN LOW-VALUES: READ-ACCTDAT-FILE (no confirmation yet).
                account = readAcctdatFile(parseAccountId(actid));
            } else {
                // WHEN OTHER: invalid confirmation value (COBIL00C lines 185-190). The COBOL sets the
                // error and PERFORMs SEND-BILLPAY-SCREEN while CURBALI holds LOW-VALUES (the protected
                // balance field is not transmitted on the RECEIVE), so BMS leaves the balance displayed
                // on the prior confirm-prompt turn UNCHANGED on the terminal - the balance "remains
                // visible" (review finding #13). The stateless Thymeleaf model re-renders CURBALI from
                // the form each turn and COBIL00.html renders it as a display-only <span> (never
                // round-tripped), so the balance would otherwise vanish. Re-read the account - the
                // balance is unchanged because no payment was made - and populate CURBALI before
                // returning the "Invalid value..." error, reproducing the observable contract.
                account = readAcctdatFile(parseAccountId(actid));
                form.setCurbal(formatCurrentBalance(CobolDecimal.nullToZero(account.getCurrBal())));
                return BillPayResult.error(MSG_INVALID_CONFIRM);
            }
        } catch (RecordNotFoundException notFound) {
            // WHEN NOTFND on the ACCTDAT (or its cross-reference) read -> inline error re-display.
            return BillPayResult.error(notFound.getMessage());
        }

        // MOVE ACCT-CURR-BAL TO WS-CURR-BAL; MOVE WS-CURR-BAL TO CURBALI (display).
        BigDecimal currentBalance = CobolDecimal.nullToZero(account.getCurrBal());
        form.setCurbal(formatCurrentBalance(currentBalance));

        // IF ACCT-CURR-BAL <= ZEROS AND ACTIDINI not blank -> nothing to pay.
        // (The account id is already known to be non-blank here.)
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return BillPayResult.error(MSG_NOTHING_TO_PAY);
        }

        // IF CONF-PAY-YES -> perform the payment (block C); ELSE -> confirm prompt.
        if (confirmed) {
            // READ-CXACAIX-FILE -> the card number that owns this account. Its WHEN NOTFND
            // branch also moves 'Account ID NOT found...' to the message, sets ERR-FLG, and
            // re-displays the SAME screen inline (PERFORM SEND-BILLPAY-SCREEN) - it is not an
            // abend and no transaction is written. Reproduce that inline re-display here rather
            // than letting the RecordNotFoundException escape to the full-page handler
            // (AAP 0.6.5 exception parity).
            String cardNum;
            try {
                cardNum = readCxacaixFile(account.getAcctId());
            } catch (RecordNotFoundException notFound) {
                return BillPayResult.error(notFound.getMessage());
            }

            // MOVE HIGH-VALUES TO TRAN-ID; STARTBR/READPREV/ENDBR; MOVE TRAN-ID TO
            // WS-TRAN-ID-NUM; ADD 1 -> the next transaction id.
            String newTranId = nextTransactionId();

            // TRAN-AMT = ACCT-CURR-BAL (the entire current balance; no partial payment).
            BigDecimal amount = CobolDecimal.money(currentBalance);

            // INITIALIZE TRAN-RECORD and MOVE the bill-payment field values.
            Transaction transaction = buildBillPaymentTransaction(newTranId, amount, cardNum);

            // WRITE-TRANSACT-FILE (throws DuplicateKeyException on DUPKEY/DUPREC).
            writeTransactFile(transaction);

            // COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (no ROUNDED -> truncate; -> 0).
            BigDecimal newBalance = CobolDecimal.money(currentBalance.subtract(amount));
            account.setCurrBal(newBalance);

            // UPDATE-ACCTDAT-FILE (REWRITE).
            updateAcctdatFile(account);

            // WRITE-TRANSACT-FILE NORMAL branch: INITIALIZE-ALL-FIELDS + green success line.
            initializeAllFields(form);
            return BillPayResult.success(buildSuccessMessage(newTranId));
        }

        // ELSE (not yet confirmed): prompt for confirmation.
        return BillPayResult.neutral(MSG_CONFIRM_PAYMENT);
    }

    /**
     * Clears the screen fields and yields a redisplay, the Java migration of paragraph
     * {@code CLEAR-CURRENT-SCREEN} in {@code legacy/cbl/COBIL00C.cbl}
     * ({@code PERFORM INITIALIZE-ALL-FIELDS} then {@code PERFORM SEND-BILLPAY-SCREEN}).
     *
     * @param form the screen form to clear; must not be {@code null}
     * @return a message-free redisplay outcome (the cleared screen)
     */
    public BillPayResult clearCurrentScreen(COBIL00Form form) {
        initializeAllFields(form);
        return BillPayResult.showScreen();
    }

    /**
     * Resets the editable screen fields to their initial (blank) state, the Java migration of
     * paragraph {@code INITIALIZE-ALL-FIELDS} in {@code legacy/cbl/COBIL00C.cbl}.
     *
     * <p>The COBOL moves {@code -1} to {@code ACTIDINL} (positioning the cursor on the account-id
     * field) and {@code SPACES} to {@code ACTIDINI}, {@code CURBALI}, {@code CONFIRMI} and
     * {@code WS-MESSAGE}. The cursor position and the message are presentation concerns owned by
     * the controller (the message is conveyed through the returned {@link BillPayResult}), so
     * here only the three editable input fields are cleared, to the empty string.</p>
     *
     * @param form the screen form whose input fields are cleared; must not be {@code null}
     */
    private void initializeAllFields(COBIL00Form form) {
        form.setActidin("");
        form.setCurbal("");
        form.setConfirm("");
    }

    /**
     * Reads the account for update, the Java migration of paragraph {@code READ-ACCTDAT-FILE} in
     * {@code legacy/cbl/COBIL00C.cbl}.
     *
     * <p>The COBOL {@code EXEC CICS READ ... UPDATE RIDFLD(ACCT-ID)} becomes a
     * <strong>pessimistic-write-lock</strong> lookup ({@link AccountRepository#findByIdForUpdate(Long)};
     * PostgreSQL {@code SELECT ... FOR UPDATE}) within the surrounding {@link Transactional} unit of
     * work. A plain {@code findById} would <em>not</em> lock the row under {@code READ COMMITTED}, so
     * the read-modify-{@code REWRITE} of the balance would leave a lost-update window (CWE-362) the
     * COBOL record lock never had; acquiring the lock here, held until commit, serializes concurrent
     * payers of the same account and reproduces the legacy semantics (review finding #14, AAP
     * &sect;0.6.5). The COBOL {@code RESP} handling is preserved: {@code NORMAL} returns the account;
     * {@code NOTFND} becomes a {@link RecordNotFoundException} carrying {@code "Account ID NOT
     * found..."}; any other response (the COBOL {@code WHEN OTHER} branch, message {@code "Unable to
     * lookup Account..."}) surfaces as the underlying Spring {@code DataAccessException}, which
     * propagates to the global exception handler. Bill-pay locks a single record (the account) only,
     * so no lock-order/deadlock concern arises.</p>
     *
     * @param acctId the account id (COBOL {@code ACCT-ID}); must not be {@code null}
     * @return the locked account record
     * @throws RecordNotFoundException if no account exists for {@code acctId}
     */
    private Account readAcctdatFile(Long acctId) {
        return accountRepository.findByIdForUpdate(acctId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_FOUND));
    }

    /**
     * Rewrites the account, the Java migration of paragraph {@code UPDATE-ACCTDAT-FILE} in
     * {@code legacy/cbl/COBIL00C.cbl}.
     *
     * <p>The COBOL {@code EXEC CICS REWRITE} becomes {@code save} on the managed entity. The
     * {@code RESP} handling is preserved: {@code NORMAL} continues; the {@code NOTFND} branch
     * (message {@code "Account ID NOT found..."}) cannot occur here because the account was just
     * read within the same transaction; any other response (the COBOL {@code WHEN OTHER} branch,
     * message {@code "Unable to Update Account..."}) surfaces as the underlying Spring
     * {@code DataAccessException}, which propagates to the global exception handler.</p>
     *
     * @param account the account to rewrite with its updated balance; must not be {@code null}
     */
    private void updateAcctdatFile(Account account) {
        accountRepository.save(account);
    }

    /**
     * Resolves the card number that owns an account via the cross-reference alternate index, the
     * Java migration of paragraph {@code READ-CXACAIX-FILE} in {@code legacy/cbl/COBIL00C.cbl}.
     *
     * <p>The COBOL {@code EXEC CICS READ DATASET('CXACAIX') RIDFLD(XREF-ACCT-ID)} reads the
     * cross-reference record by the account-id alternate key and takes {@code XREF-CARD-NUM}. In
     * the relational model the alternate index is a derived query
     * ({@link CardXrefRepository#findByXrefAcctId(Long)}); because that key is non-unique (an
     * account can own more than one card) the query returns a list and this method takes the
     * cross-reference with the <em>lowest</em> {@code XREF-CARD-NUM}
     * ({@link Comparator#comparing(java.util.function.Function) min by card number}). A native VSAM
     * {@code READ} through a non-unique alternate index returns the base-cluster record with the
     * lowest prime key among the duplicates - and the {@code CCXREF} base cluster is keyed by
     * {@code XREF-CARD-NUM} - so lowest-card-number selection reproduces the single-record CICS
     * {@code READ} deterministically (review finding w045-J; matches {@code TransactionAddService}'s
     * {@code .min(comparing(getXrefCardNum))}). Taking an unordered {@code findFirst()} instead would
     * make the derived card depend on database row order. The COBOL
     * {@code RESP} handling is preserved: {@code NORMAL} returns the card number; {@code NOTFND}
     * (no cross-reference for the account) becomes a {@link RecordNotFoundException} carrying
     * {@code "Account ID NOT found..."}; any other response (the COBOL {@code WHEN OTHER} branch,
     * message {@code "Unable to lookup XREF AIX file..."}) surfaces as the underlying Spring
     * {@code DataAccessException}, which propagates to the global exception handler.</p>
     *
     * @param acctId the account id (COBOL {@code XREF-ACCT-ID}); must not be {@code null}
     * @return the card number that owns the account (COBOL {@code XREF-CARD-NUM})
     * @throws RecordNotFoundException if no cross-reference exists for {@code acctId}
     */
    private String readCxacaixFile(Long acctId) {
        return cardXrefRepository.findByXrefAcctId(acctId).stream()
                .min(Comparator.comparing(CardXref::getXrefCardNum))
                .map(CardXref::getXrefCardNum)
                .orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_FOUND));
    }

    /**
     * Derives the next transaction id, the Java migration of the browse-to-last-key triplet
     * {@code STARTBR-TRANSACT-FILE} + {@code READPREV-TRANSACT-FILE} + {@code ENDBR-TRANSACT-FILE}
     * in {@code legacy/cbl/COBIL00C.cbl}.
     *
     * <p>The COBOL positions the browse at {@code HIGH-VALUES} ({@code STARTBR}), reads the
     * previous (highest-keyed) record ({@code READPREV}), ends the browse ({@code ENDBR}), then
     * moves that key to {@code WS-TRAN-ID-NUM} and adds one; on {@code READPREV} end-of-file the
     * key is treated as zero, so the first-ever id is one. Per the transaction repository's
     * documented design, this VSAM browse maps to an ordered top-one query; here
     * {@code findAll(PageRequest.of(0, 1, Sort.by(DESC, "tranId")))} reads only the highest
     * existing id (an {@code ORDER BY tran_id DESC LIMIT 1}), reproducing {@code READPREV} of the
     * last record without loading the whole table. An empty table yields the base value zero,
     * matching the COBOL end-of-file behaviour.</p>
     *
     * <p>The three COBOL paragraphs collapse into this single method because the JPA query model
     * has no explicit browse cursor to start and end; the mapping is recorded in the
     * paragraph&rarr;method table and the traceability matrix. The COBOL browse error branches
     * ({@code STARTBR} {@code NOTFND} {@code "Transaction ID NOT found..."} and {@code READPREV}
     * {@code WHEN OTHER} {@code "Unable to lookup Transaction..."}) either do not arise (an empty
     * table is handled as the base value) or surface as a propagating Spring
     * {@code DataAccessException}.</p>
     *
     * @return the next transaction id as a 16-digit zero-padded string (COBOL {@code PIC 9(16)})
     */
    private String nextTransactionId() {
        List<Transaction> highest = transactionRepository
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "tranId")))
                .getContent();
        long lastId = highest.isEmpty() ? 0L : parseTranId(highest.get(0).getTranId());
        long nextId = lastId + 1L;
        return String.format(Locale.ROOT, "%0" + TRAN_ID_WIDTH + "d", nextId);
    }

    /**
     * Writes the bill-payment transaction, the Java migration of paragraph
     * {@code WRITE-TRANSACT-FILE} in {@code legacy/cbl/COBIL00C.cbl}.
     *
     * <p>The COBOL {@code EXEC CICS WRITE} becomes {@code saveAndFlush}, which forces the insert
     * to be issued immediately (like the synchronous CICS {@code WRITE}) so that a duplicate key
     * is detected here rather than at a later commit. The {@code RESP} handling is preserved: on
     * {@code NORMAL} the caller builds the green success line and clears the fields (the COBOL
     * {@code NORMAL} branch's {@code INITIALIZE-ALL-FIELDS} and success {@code STRING} are done in
     * the caller so that this method stays focused on the I/O, with an identical observable
     * result); {@code DUPKEY}/{@code DUPREC} becomes a {@link DuplicateKeyException} carrying
     * {@code "Tran ID already exist..."}; any other failure (the COBOL {@code WHEN OTHER} branch,
     * message {@code "Unable to Add Bill pay Transaction..."}) surfaces as the underlying Spring
     * {@code DataAccessException}, which propagates to the global exception handler. The
     * transaction table's only insert-time integrity constraint is its primary key, so a
     * data-integrity violation on insert denotes a duplicate transaction id.</p>
     *
     * @param transaction the fully populated bill-payment transaction to write; must not be
     *                    {@code null}
     * @throws DuplicateKeyException if the transaction id already exists
     */
    private void writeTransactFile(Transaction transaction) {
        try {
            transactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateKeyException(MSG_TRAN_ID_EXISTS, ex);
        }
    }

    /**
     * Produces the current timestamp, the Java migration of paragraph
     * {@code GET-CURRENT-TIMESTAMP} in {@code legacy/cbl/COBIL00C.cbl}.
     *
     * <p>The COBOL uses CICS {@code ASKTIME} + {@code FORMATTIME} to assemble a 26-character
     * timestamp of the form {@code YYYY-MM-DD HH:MM:SS} with the fractional-seconds positions
     * zero-filled ({@code MOVE ZEROS TO WS-TIMESTAMP-TM-MS6}). Because the transaction's
     * {@code ORIG-TS}/{@code PROC-TS} are modelled as {@link LocalDateTime}, this returns
     * {@code LocalDateTime.now()} truncated to whole seconds via {@code withNano(0)}, which is
     * the faithful equivalent of the zero-filled fractional part.</p>
     *
     * @return the current local date-time truncated to whole seconds
     */
    private LocalDateTime getCurrentTimestamp() {
        return LocalDateTime.now().withNano(0);
    }

    /**
     * Builds the bill-payment transaction record, reproducing the COBOL
     * {@code INITIALIZE TRAN-RECORD} followed by the field {@code MOVE}s in the confirmed branch
     * of {@code PROCESS-ENTER-KEY} ({@code legacy/cbl/COBIL00C.cbl}).
     *
     * <p>The tagging is fixed exactly as in the COBOL: type {@code '02'}, category {@code 2},
     * source {@code 'POS TERM'}, description {@code 'BILL PAYMENT - ONLINE'}, merchant id
     * {@code 999999999}, merchant name {@code 'BILL PAYMENT'}, and city/zip {@code 'N/A'}. The
     * amount is the entire current balance (there is no partial-payment field). Both the
     * origination and processing timestamps are set to the same value from
     * {@link #getCurrentTimestamp()} ({@code MOVE WS-TIMESTAMP TO TRAN-ORIG-TS TRAN-PROC-TS}).</p>
     *
     * @param tranId  the new transaction id (COBOL {@code TRAN-ID})
     * @param amount  the payment amount, equal to the current balance (COBOL {@code TRAN-AMT})
     * @param cardNum the card number resolved from the cross-reference (COBOL {@code TRAN-CARD-NUM})
     * @return the fully populated transaction ready to write
     */
    private Transaction buildBillPaymentTransaction(String tranId, BigDecimal amount, String cardNum) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranTypeCd(TRAN_TYPE_BILL_PAYMENT);
        transaction.setTranCatCd(TRAN_CAT_BILL_PAYMENT);
        transaction.setTranSource(TRAN_SOURCE_POS_TERM);
        transaction.setTranDesc(TRAN_DESC_BILL_PAYMENT);
        transaction.setTranAmt(amount);
        transaction.setCardNum(cardNum);
        transaction.setMerchantId(MERCHANT_ID_BILL_PAYMENT);
        transaction.setMerchantName(MERCHANT_NAME_BILL_PAYMENT);
        transaction.setMerchantCity(MERCHANT_CITY_NA);
        transaction.setMerchantZip(MERCHANT_ZIP_NA);
        LocalDateTime timestamp = getCurrentTimestamp();
        transaction.setOrigTs(timestamp);
        transaction.setProcTs(timestamp);
        return transaction;
    }

    /**
     * Assembles the payment-successful message, reproducing the COBOL {@code STRING} statement in
     * the {@code NORMAL} branch of {@code WRITE-TRANSACT-FILE} ({@code legacy/cbl/COBIL00C.cbl}):
     * {@code 'Payment successful. '} + {@code ' Your Transaction ID is '} + {@code TRAN-ID}
     * (delimited by space) + {@code '.'}. The transaction id is a 16-digit string with no
     * embedded spaces, so it appears in full.
     *
     * @param tranId the id of the written transaction
     * @return the success message (rendered green by the controller)
     */
    private static String buildSuccessMessage(String tranId) {
        return SUCCESS_MSG_PREFIX + tranId + SUCCESS_MSG_SUFFIX;
    }

    /**
     * Parses the entered account-id field into its numeric key, reproducing the COBOL
     * {@code MOVE ACTIDINI TO ACCT-ID / XREF-ACCT-ID} ({@code legacy/cbl/COBIL00C.cbl}).
     *
     * <p>The field is trimmed and parsed as a base-ten number. A non-numeric value cannot match
     * any {@code ACCTDAT}/{@code CXACAIX} key, so &mdash; matching the observable COBOL outcome
     * where the subsequent {@code READ} returns {@code NOTFND} &mdash; it is reported as a
     * {@link RecordNotFoundException} carrying {@code "Account ID NOT found..."}. This is only
     * reached on the confirm and blank-confirm paths (the paths that read the account); the
     * {@code 'N'} path clears the screen without parsing, as in the COBOL.</p>
     *
     * @param actid the entered account id (COBOL {@code ACTIDINI}); already known to be non-blank
     * @return the numeric account id
     * @throws RecordNotFoundException if {@code actid} is not a valid number
     */
    private static Long parseAccountId(String actid) {
        try {
            return Long.parseLong(actid.trim());
        } catch (NumberFormatException ex) {
            throw new RecordNotFoundException(MSG_ACCT_NOT_FOUND, ex);
        }
    }

    /**
     * Interprets a transaction-id key as a number, reproducing the COBOL
     * {@code MOVE TRAN-ID TO WS-TRAN-ID-NUM} where {@code TRAN-ID} ({@code PIC X(16)}) is treated
     * as {@code WS-TRAN-ID-NUM} ({@code PIC 9(16)}) ({@code legacy/cbl/COBIL00C.cbl}). A blank id
     * yields zero, mirroring the {@code READPREV} end-of-file case that moves {@code ZEROS} to
     * {@code TRAN-ID}.
     *
     * @param tranId the stored transaction id (a 16-digit numeric string), or blank
     * @return the numeric value of the id, or {@code 0} when blank
     */
    private static long parseTranId(String tranId) {
        String trimmed = (tranId == null) ? "" : tranId.trim();
        return trimmed.isEmpty() ? 0L : Long.parseLong(trimmed);
    }

    /**
     * Formats a numeric account id into the fixed-width account-id field, reproducing the COBOL
     * move of the selected account id into {@code ACTIDINI} ({@code PIC X(11)}) on the
     * first-entry pre-load ({@code legacy/cbl/COBIL00C.cbl}).
     *
     * @param acctId the numeric account id; must not be {@code null}
     * @return the id as an 11-digit zero-padded string
     */
    private static String formatAccountId(Long acctId) {
        return String.format(Locale.ROOT, "%0" + ACCT_ID_WIDTH + "d", acctId);
    }

    /**
     * Formats the current balance into the display edit mask, reproducing the COBOL
     * {@code MOVE ACCT-CURR-BAL TO WS-CURR-BAL} where {@code WS-CURR-BAL} is
     * {@code PIC +9999999999.99} ({@code legacy/cbl/COBIL00C.cbl}): a leading sign
     * ({@code '+'} for zero or positive, {@code '-'} for negative), ten zero-padded integer
     * digits, a decimal point, and two decimal digits.
     *
     * @param balance the account balance (COBOL {@code ACCT-CURR-BAL}); {@code null} is treated
     *                as zero
     * @return the edit-masked balance string (14 characters)
     */
    private static String formatCurrentBalance(BigDecimal balance) {
        BigDecimal scaled = CobolDecimal.money(CobolDecimal.nullToZero(balance));
        char sign = scaled.signum() < 0 ? '-' : '+';
        long totalCents = scaled.abs().movePointRight(2).longValueExact();
        long integerPart = totalCents / 100L;
        long fractionalPart = totalCents % 100L;
        return String.format(Locale.ROOT, "%c%0" + CURR_BAL_INT_DIGITS + "d.%02d",
                sign, integerPart, fractionalPart);
    }

    /**
     * Reports whether a screen field is effectively unset, reproducing the COBOL class tests
     * {@code = SPACES OR LOW-VALUES} used throughout {@code COBIL00C}
     * ({@code legacy/cbl/COBIL00C.cbl}). A {@code null}, empty, all-space, or all-{@code NUL}
     * value is considered blank.
     *
     * @param value the field value to test
     * @return {@code true} when the value is {@code null}, empty, all spaces, or all {@code NUL}
     */
    private static boolean isBlankOrLowValues(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != ' ' && ch != '\0') {
                return false;
            }
        }
        return true;
    }
}
