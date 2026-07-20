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
package com.aws.carddemo.web;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.TransactionViewRequest;
import com.aws.carddemo.dto.TransactionViewResponse;
import com.aws.carddemo.mapper.TransactionMapper;
import com.aws.carddemo.service.TransactionService;

/**
 * REST controller re-platform of the CardDemo online <em>transaction view /
 * detail</em> program {@code COTRN01C} (CICS transaction {@code CT01}, relocated
 * during the migration to {@code legacy/cbl/COTRN01C.cbl}). The screen accepts a
 * 16-character transaction id and displays the full detail of that single
 * transaction.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/v1/transactions/view} &mdash; the screen-entry (first
 *       display) request. Reproduces the COBOL {@code NOT CDEMO-PGM-REENTER}
 *       branch that clears the map and sends a blank {@code COTRN1A} screen (no
 *       error, current date/time in the header).</li>
 *   <li>{@code POST /api/v1/transactions/view} &mdash; a screen submission. The
 *       {@link TransactionViewRequest#action() action} is the transport-neutral
 *       translation of the 3270 Attention Identifier ({@code EIBAID}); the
 *       controller routes on it exactly as the COBOL {@code EVALUATE EIBAID}
 *       block does.</li>
 * </ul>
 *
 * <h2>PF-key routing (parity with {@code COTRN01C} {@code MAIN-PARA})</h2>
 * <table border="1">
 *   <caption>{@link PfKeyAction} &rarr; behavior</caption>
 *   <tr><th>Action</th><th>Behavior</th><th>COBOL origin</th></tr>
 *   <tr><td>{@code null}</td><td>screen-entry default: blank screen, no error</td>
 *       <td>{@code NOT CDEMO-PGM-REENTER} first-entry {@code SEND}</td></tr>
 *   <tr><td>{@link PfKeyAction#ENTER}</td>
 *       <td>fetch the transaction by id and display it</td>
 *       <td>{@code DFHENTER} &rarr; {@code PROCESS-ENTER-KEY}</td></tr>
 *   <tr><td>{@link PfKeyAction#PF3}</td>
 *       <td>navigate back to the main menu ({@code COMEN01C}/{@code CM00})</td>
 *       <td>{@code DFHPF3} &rarr; {@code RETURN-TO-PREV-SCREEN}</td></tr>
 *   <tr><td>{@link PfKeyAction#PF4}</td><td>clear the screen (blank fields)</td>
 *       <td>{@code DFHPF4} &rarr; {@code CLEAR-CURRENT-SCREEN}</td></tr>
 *   <tr><td>{@link PfKeyAction#PF5}</td>
 *       <td>navigate to the transaction list ({@code COTRN00C}/{@code CT00})</td>
 *       <td>{@code DFHPF5} &rarr; {@code RETURN-TO-PREV-SCREEN}</td></tr>
 *   <tr><td>any other key</td><td>re-display with "Invalid key pressed..."</td>
 *       <td>{@code WHEN OTHER} &rarr; {@code CCDA-MSG-INVALID-KEY}</td></tr>
 * </table>
 *
 * <h2>Pseudo-conversational translation (AAP &sect;0.7.1 H1)</h2>
 * The COBOL program is pseudo-conversational: it {@code RECEIVE}s the map,
 * evaluates {@code EIBAID}, and either {@code SEND}s the screen again or
 * transfers control with {@code XCTL}. There is no terminal here, so each screen
 * turn becomes one stateless HTTP request. The two {@code XCTL} navigations
 * (PF3, PF5) are surfaced as response headers ({@value #NAV_HEADER_PROGRAM} /
 * {@value #NAV_HEADER_TRANSACTION}) carrying the target program and transaction
 * id, mirroring the COBOL {@code CDEMO-TO-PROGRAM} hand-off, so the client knows
 * which screen to request next while still receiving a well-formed
 * {@code 200 OK} body. The COBOL {@code EIBCALEN = 0} (no COMMAREA) hand-off to
 * the sign-on program is an unauthenticated-session concern handled by Spring
 * Security, not by this controller.
 *
 * <h2>Outcome signalling (caller-visible COBOL messages preserved)</h2>
 * <ul>
 *   <li>A successful {@code ENTER} lookup returns {@code 200 OK} with the fully
 *       populated {@link TransactionViewResponse} produced by
 *       {@link TransactionMapper#toViewResponse(Transaction, String, LocalDateTime)}.</li>
 *   <li>An empty transaction id is rejected <em>before</em> the service call and
 *       returned as {@code 200 OK} carrying the exact legacy message
 *       {@value #MSG_TRAN_ID_EMPTY} on the same screen &mdash; reproducing the
 *       first check of {@code PROCESS-ENTER-KEY} and avoiding a throw for a
 *       routine, expected input edit.</li>
 *   <li>A transaction id that matches nothing surfaces as
 *       {@link com.aws.carddemo.exception.RecordNotFoundException} thrown by the
 *       service (COBOL {@code DFHRESP(NOTFND)} &rarr; "Transaction ID NOT
 *       found..."); it is <strong>not</strong> caught here and propagates to the
 *       {@code GlobalExceptionHandler}, which maps it to {@code 404 Not
 *       Found}.</li>
 * </ul>
 *
 * <h2>Header construction</h2>
 * Every COBOL {@code SEND} runs {@code POPULATE-HEADER-INFO}, so the standard
 * header (transaction name, the two title lines, program name, current date and
 * time) appears on every screen &mdash; blank, cleared, navigation, error and
 * detail alike. The detail (successful {@code ENTER}) response builds that header
 * through the shared {@link TransactionMapper}. The blank/cleared/navigation
 * responses cannot use the mapper (its {@code toViewResponse} requires a
 * non-{@code null} entity) and the date/time helper is outside this file's
 * dependency set, so the header is assembled locally from constants that are
 * byte-identical to their mapper counterparts: the two 40-character title lines
 * are the verbatim {@code CCDA-TITLE01}/{@code CCDA-TITLE02} literals of
 * {@code legacy/cpy/COTTL01Y.cpy}, and the date/time masks
 * ({@code MM/dd/uu}, {@code HH:mm:ss}) match the shared date utility exactly, so
 * a blank screen and a populated screen render an identical header.
 *
 * <h2>Design constraints</h2>
 * Constructor injection only (no field injection, no Lombok); the collaborators
 * are the {@link TransactionService} (business logic / keyed read) and the
 * {@link TransactionMapper} (entity&rarr;DTO projection). The controller is
 * stateless and therefore thread-safe. It holds no {@code try}/{@code catch}
 * &mdash; not-found and unexpected outcomes are translated centrally by the
 * {@code GlobalExceptionHandler}. In keeping with the observability discipline,
 * this controller never logs the request or the response (a transaction detail
 * is potentially sensitive), and monetary values are carried strictly as
 * {@link java.math.BigDecimal} on the response DTO &mdash; never binary floating
 * point.
 *
 * @see TransactionService#viewTransaction(String)
 * @see TransactionMapper#toViewResponse(Transaction, String, LocalDateTime)
 * @see TransactionViewRequest
 * @see TransactionViewResponse
 */
@RestController
@RequestMapping("/api/v1/transactions/view")
@Tag(name = "Transaction View",
        description = "Transaction View screen (COBOL COTRN01C / CICS transaction CT01): view a single transaction's detail.")
public class TransactionViewController {

    /**
     * This program's name ({@code WS-PGMNAME}); rendered into the response header
     * {@code programName} ({@code PGMNAMEO}) on locally built screens.
     */
    private static final String PROGRAM_NAME = "COTRN01C";

    /**
     * This program's CICS transaction id ({@code WS-TRANID}); rendered into the
     * response header {@code transactionName} ({@code TRNNAMEO}).
     */
    private static final String TRANSACTION_ID = "CT01";

    /**
     * PF3 navigation target program &mdash; the main menu {@code COMEN01C}, the
     * default {@code CDEMO-TO-PROGRAM} of the COBOL {@code DFHPF3} branch.
     */
    private static final String BACK_PROGRAM = "COMEN01C";

    /** PF3 navigation target CICS transaction id for the main menu ({@code CM00}). */
    private static final String BACK_TRANSACTION = "CM00";

    /**
     * PF5 navigation target program &mdash; the transaction list {@code COTRN00C},
     * the {@code CDEMO-TO-PROGRAM} of the COBOL {@code DFHPF5} branch.
     */
    private static final String LIST_PROGRAM = "COTRN00C";

    /** PF5 navigation target CICS transaction id for the transaction list ({@code CT00}). */
    private static final String LIST_TRANSACTION = "CT00";

    /**
     * Response header naming the next program to display, reproducing the COBOL
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} hand-off for the navigation keys.
     */
    private static final String NAV_HEADER_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header naming the next program's CICS transaction id. */
    private static final String NAV_HEADER_TRANSACTION = "X-CardDemo-Next-Transaction";

    /**
     * Verbatim message for an empty transaction id, matching the COBOL
     * {@code PROCESS-ENTER-KEY} literal moved into {@code WS-MESSAGE}.
     */
    private static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /**
     * Verbatim invalid-key advisory ({@code CCDA-MSG-INVALID-KEY} of
     * {@code legacy/cpy/CSMSG01Y.cpy}), with the fixed-field trailing padding of
     * the {@code PIC X(50)} literal removed for the REST projection.
     */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * First screen title line &mdash; the verbatim 40-character
     * {@code CCDA-TITLE01} literal of {@code legacy/cpy/COTTL01Y.cpy}, kept
     * byte-identical to {@code TransactionMapper} so a locally built header
     * matches the mapper-built header exactly.
     */
    private static final String TITLE_01 = "      AWS Mainframe Modernization       ";

    /**
     * Second screen title line &mdash; the verbatim 40-character
     * {@code CCDA-TITLE02} literal of {@code legacy/cpy/COTTL01Y.cpy}.
     */
    private static final String TITLE_02 = "              CardDemo                  ";

    /**
     * Header date mask ({@code MM/dd/uu}); identical to the shared date utility's
     * {@code formatDateMmDdYy} pattern so locally built headers match the mapper.
     * The proleptic-year symbol {@code uu} (last two digits) matches the utility.
     */
    private static final DateTimeFormatter HEADER_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/uu");

    /**
     * Header time mask ({@code HH:mm:ss}); identical to the shared date utility's
     * {@code formatTimeHhMmSs} pattern.
     */
    private static final DateTimeFormatter HEADER_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Blank value for a protected detail field on a cleared/blank screen,
     * reproducing the COBOL {@code MOVE SPACES} of {@code INITIALIZE-ALL-FIELDS}.
     */
    private static final String BLANK = "";

    /** Business-logic collaborator: keyed transaction read ({@code COTRN01C} logic). */
    private final TransactionService transactionService;

    /** Presentation collaborator: {@link Transaction} &rarr; {@link TransactionViewResponse} projection. */
    private final TransactionMapper transactionMapper;

    /**
     * Creates the controller with its collaborators. Dependencies are assigned
     * directly (no overridable method is invoked), so the constructor performs no
     * {@code this} escape.
     *
     * @param transactionService the transaction service; must not be {@code null}
     * @param transactionMapper  the transaction mapper; must not be {@code null}
     */
    public TransactionViewController(TransactionService transactionService,
                                     TransactionMapper transactionMapper) {
        this.transactionService = Objects.requireNonNull(
                transactionService, "transactionService must not be null");
        this.transactionMapper = Objects.requireNonNull(
                transactionMapper, "transactionMapper must not be null");
    }

    /**
     * Handles the screen-entry (first display) request &mdash; the Java form of
     * the COBOL {@code NOT CDEMO-PGM-REENTER} branch, which clears the map and
     * sends a blank {@code COTRN1A} screen. Returns {@code 200 OK} with a header
     * populated for the current date/time, blank detail fields and no message.
     *
     * @return {@code 200 OK} carrying a blank transaction-view screen
     */
    @GetMapping
    @Operation(summary = "Blank Transaction View screen",
            description = "First-entry screen prompting for a transaction id (COBOL COTRN01C initial SEND MAP).")
    public ResponseEntity<TransactionViewResponse> initialScreen() {
        return ResponseEntity.ok(blankScreen(null, LocalDateTime.now()));
    }

    /**
     * Handles a screen submission, routing on the request
     * {@link TransactionViewRequest#action() action} exactly as the COBOL
     * {@code EVALUATE EIBAID} block routes on the Attention Identifier.
     *
     * <p>A {@code null} action denotes screen entry (the request carried no key),
     * so the screen-entry default &mdash; a blank screen &mdash; is applied, as
     * the DTO contract specifies. {@link PfKeyAction#ENTER} performs the keyed
     * lookup; {@link PfKeyAction#PF3} and {@link PfKeyAction#PF5} produce
     * navigation responses; {@link PfKeyAction#PF4} clears the screen; every
     * other key yields the invalid-key advisory. This method deliberately
     * contains no {@code try}/{@code catch}: a not-found lookup propagates as
     * {@link com.aws.carddemo.exception.RecordNotFoundException} and is mapped to
     * {@code 404} by the {@code GlobalExceptionHandler}.</p>
     *
     * @param request the submitted screen request; validated ({@code @Valid}) and
     *                never {@code null}
     * @return the {@code ResponseEntity} for the routed action (always
     *         {@code 200 OK} for the handled keys; a not-found {@code ENTER}
     *         lookup instead propagates to become {@code 404})
     */
    @PostMapping
    @Operation(summary = "Submit the Transaction View screen",
            description = "Reproduces the COTRN01C lookup: fetch and display the requested transaction detail.")
    public ResponseEntity<TransactionViewResponse> view(@Valid @RequestBody TransactionViewRequest request) {
        LocalDateTime now = LocalDateTime.now();
        PfKeyAction action = request.action();
        if (action == null) {
            // COBOL first-entry (NOT CDEMO-PGM-REENTER): clear map and SEND a blank screen.
            return ResponseEntity.ok(blankScreen(null, now));
        }
        return switch (action) {
            case ENTER -> handleEnter(request, now);
            case PF3 -> navigate(BACK_PROGRAM, BACK_TRANSACTION, now);
            case PF4 -> ResponseEntity.ok(blankScreen(null, now));
            case PF5 -> navigate(LIST_PROGRAM, LIST_TRANSACTION, now);
            default -> ResponseEntity.ok(blankScreen(MSG_INVALID_KEY, now));
        };
    }

    /**
     * Performs the {@code ENTER} lookup &mdash; the Java form of the COBOL
     * {@code PROCESS-ENTER-KEY} paragraph. The evaluation order is preserved: an
     * empty transaction id is rejected first with {@value #MSG_TRAN_ID_EMPTY} and
     * a {@code 200 OK} same-screen response (never invoking the service), exactly
     * as the COBOL tests {@code TRNIDINI = SPACES OR LOW-VALUES} before the keyed
     * read. Only a non-blank id reaches {@link TransactionService#viewTransaction(String)},
     * whose result is projected by
     * {@link TransactionMapper#toViewResponse(Transaction, String, LocalDateTime)}.
     * A not-found id throws {@link com.aws.carddemo.exception.RecordNotFoundException},
     * which is intentionally not caught here.
     *
     * @param request the submitted screen request
     * @param now     the timestamp used to render the header
     * @return {@code 200 OK} with the populated detail on success, or with the
     *         empty-id message when the id is blank
     */
    private ResponseEntity<TransactionViewResponse> handleEnter(TransactionViewRequest request, LocalDateTime now) {
        String tranId = request.transactionId();
        if (tranId == null || tranId.isBlank()) {
            return ResponseEntity.ok(blankScreen(MSG_TRAN_ID_EMPTY, now));
        }
        Transaction txn = transactionService.viewTransaction(tranId);
        return ResponseEntity.ok(transactionMapper.toViewResponse(txn, null, now));
    }

    /**
     * Builds a navigation response &mdash; the Java form of the COBOL
     * {@code RETURN-TO-PREV-SCREEN} / {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)}
     * hand-off. The target program and transaction id are surfaced as response
     * headers so the client can request the next screen, and a blank
     * (message-free) {@code COTRN1A} body accompanies the {@code 200 OK}.
     *
     * @param targetProgram     the target program name (e.g. {@code COMEN01C})
     * @param targetTransaction the target CICS transaction id (e.g. {@code CM00})
     * @param now               the timestamp used to render the header
     * @return {@code 200 OK} carrying the navigation headers and a blank body
     */
    private ResponseEntity<TransactionViewResponse> navigate(String targetProgram,
                                                             String targetTransaction,
                                                             LocalDateTime now) {
        return ResponseEntity.ok()
                .header(NAV_HEADER_PROGRAM, targetProgram)
                .header(NAV_HEADER_TRANSACTION, targetTransaction)
                .body(blankScreen(null, now));
    }

    /**
     * Assembles a blank {@code COTRN1A} screen: the standard header (transaction
     * name, both title lines, program name, current date and time) with every
     * detail field blank and the supplied message. This reproduces the header
     * that {@code POPULATE-HEADER-INFO} places on every {@code SEND} together with
     * the blanked fields of {@code INITIALIZE-ALL-FIELDS}. The monetary
     * {@code amount} is {@code null} because a blank screen shows no amount.
     *
     * @param errorMessage the message-line text ({@code ERRMSGO}); {@code null}
     *                     when there is no message
     * @param now          the timestamp used to render the header date and time;
     *                     must not be {@code null}
     * @return a header-populated {@link TransactionViewResponse} with blank detail
     */
    private TransactionViewResponse blankScreen(String errorMessage, LocalDateTime now) {
        return new TransactionViewResponse(
                TRANSACTION_ID,                             // transactionName (TRNNAMEO)
                TITLE_01,                                   // title01 (TITLE01O)
                TITLE_02,                                   // title02 (TITLE02O)
                now.toLocalDate().format(HEADER_DATE_FORMAT),   // currentDate (CURDATEO)
                PROGRAM_NAME,                               // programName (PGMNAMEO)
                now.toLocalTime().format(HEADER_TIME_FORMAT),   // currentTime (CURTIMEO)
                BLANK,                                      // transactionId (TRNIDO)
                BLANK,                                      // cardNumber (CARDNUMO)
                BLANK,                                      // typeCode (TTYPCDO)
                BLANK,                                      // categoryCode (TCATCDO)
                BLANK,                                      // source (TRNSRCO)
                BLANK,                                      // description (TDESCO)
                null,                                       // amount (TRNAMTO) - none on a blank screen
                BLANK,                                      // originDate (TORIGDTO)
                BLANK,                                      // processDate (TPROCDTO)
                BLANK,                                      // merchantId (MIDO)
                BLANK,                                      // merchantName (MNAMEO)
                BLANK,                                      // merchantCity (MCITYO)
                BLANK,                                      // merchantZip (MZIPO)
                errorMessage);                              // errorMessage (ERRMSGO)
    }
}
