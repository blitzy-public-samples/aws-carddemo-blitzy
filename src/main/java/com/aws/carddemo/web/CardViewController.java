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

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardViewRequest;
import com.aws.carddemo.dto.CardViewResponse;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.CardMapper;
import com.aws.carddemo.service.CardService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * REST controller for the CardDemo <em>View Credit Card Detail</em> screen &mdash;
 * the Java re-platform of the online COBOL program {@code COCRDSLC} (CICS
 * transaction {@code CCDL}, BMS map {@code COCRDSL}), relocated for reference to
 * {@code legacy/cbl/COCRDSLC.cbl}.
 *
 * <p>The legacy program is a read-only card lookup: the operator keys an account
 * number and/or a card number and transmits the screen; the program reads the
 * {@code CARDDATA} VSAM KSDS (now the PostgreSQL {@code card} table) and either
 * re-displays the screen with a message or shows the resolved card detail. This
 * controller preserves that field-level contract without rendering a 3270
 * terminal: the screen becomes a REST request/response pair
 * ({@link CardViewRequest} / {@link CardViewResponse}) and the CICS attention
 * identifier becomes the transport-neutral {@link PfKeyAction} carried on the
 * request.</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/v1/cards/view} &mdash; the first-entry (blank) screen,
 *       reproducing the COBOL {@code EIBCALEN = 0} initialization that primes the
 *       header, current date/time, and the {@code 'Please enter Account and Card
 *       Number'} prompt (COBOL {@code WS-PROMPT-FOR-INPUT}).</li>
 *   <li>{@code POST /api/v1/cards/view} &mdash; a screen submission, routed on the
 *       {@link PfKeyAction} exactly as the COBOL {@code 0000-MAIN}
 *       {@code EVALUATE} routed on the AID.</li>
 * </ul>
 *
 * <h2>Pseudo-conversational translation (COBOL {@code 0000-MAIN})</h2>
 * <p>The legacy COMMAREA navigation context ({@code CDEMO-FROM-PROGRAM} /
 * {@code CDEMO-FROM-TRANID}) has no stateless HTTP analog, so it is carried on the
 * optional request headers {@value #HEADER_FROM_PROGRAM} /
 * {@value #HEADER_FROM_TRANSACTION}. The {@code POST} routing is:</p>
 * <ul>
 *   <li><strong>{@link PfKeyAction#PF3}</strong> &mdash; the exit key. Reproduces
 *       the COBOL {@code EXEC CICS XCTL} back to the calling program: a
 *       {@code 200 OK} carrying the navigation headers {@value #NEXT_PROGRAM_HEADER}
 *       / {@value #NEXT_TRANSACTION_HEADER} set to the caller's from-program /
 *       from-transaction, or to the main menu ({@code COMEN01C} / {@code CM00})
 *       when none was supplied &mdash; exactly the COBOL default. When the caller
 *       arrived from the card-list screen it passes {@code COCRDLIC} /
 *       {@code CCLI} in the from-headers, so this generic rule navigates back
 *       there without any screen-specific special case.</li>
 *   <li><strong>{@link PfKeyAction#ENTER}</strong> (and an unspecified action,
 *       matching the COBOL remap of any non-{@code PF3} key to {@code ENTER} at
 *       {@code 0000-MAIN} lines 291-299) &mdash; resolve the requested card via
 *       {@link CardService#viewCard(Long, String)} and render its detail. This
 *       delegates the field edits and the keyed read (COBOL
 *       {@code 2210-EDIT-ACCOUNT} / {@code 2220-EDIT-CARD} /
 *       {@code 9100-GETCARD-BYACCTCARD}) to the service.</li>
 *   <li><strong>any other explicit attention key</strong> &mdash; re-display the
 *       screen with the {@code 'Invalid key pressed'} message (COBOL
 *       {@code CCDA-MSG-INVALID-KEY}).</li>
 * </ul>
 *
 * <h2>Exception handling</h2>
 * <p>A not-found read raises {@link com.aws.carddemo.service.CardService}'s
 * {@code RecordNotFoundException} (COBOL/CICS {@code NOTFND}); it is deliberately
 * left uncaught so {@code GlobalExceptionHandler} maps it to HTTP {@code 404},
 * preserving the caller-visible "not found" parity. An input-edit failure raises
 * the service's {@link IllegalArgumentException} (the COBOL {@code 2210}/{@code 2220}
 * edit messages); because the legacy program surfaced that as an ordinary
 * same-screen field edit rather than an abend, the {@code ENTER} branch catches it
 * and redisplays the screen at HTTP {@code 200} carrying the verbatim message
 * (QA finding F1). Ownership of the edit logic stays with the service, so this
 * controller performs no input validation of its own beyond the request DTO's
 * Bean Validation constraints (which surface as HTTP {@code 400}).</p>
 *
 * <h2>Sensitive data</h2>
 * <p>The card verification value (CVV) is never part of this screen:
 * {@link CardViewResponse} carries no CVV field and {@link CardMapper} never reads
 * it. This controller additionally <strong>never logs</strong> the request or the
 * response and never reads {@code Card#getCvv()} on any path (AAP&nbsp;&sect;0.7.3,
 * &sect;0.9.3).</p>
 *
 * <h2>Design constraints</h2>
 * <p>Collaborators are supplied by constructor injection (never field injection,
 * no Lombok). The class holds no mutable state and is thread-safe. The screen
 * header identity, titles, and program-function-key legend are presentation
 * chrome owned here (the Java equivalents of the COBOL {@code 1100-SCREEN-INIT}
 * {@code MOVE}s) and are passed to {@link CardMapper} / the response builder.</p>
 */
@RestController
@RequestMapping("/api/v1/cards/view")
@Tag(name = "Card View",
        description = "Card View screen (COBOL COCRDSLC / CICS transaction CCDL): view a single card's detail.")
public class CardViewController {

    /** This program's identifier &mdash; COBOL {@code LIT-THISPGM} ({@code 'COCRDSLC'}). */
    static final String PROGRAM_ID = "COCRDSLC";

    /** This program's transaction id &mdash; COBOL {@code LIT-THISTRANID} ({@code 'CCDL'}). */
    static final String TRANSACTION_ID = "CCDL";

    /**
     * Default back-navigation target program when no caller context is supplied
     * &mdash; COBOL {@code LIT-MENUPGM} ({@code 'COMEN01C'}, the main menu).
     */
    static final String DEFAULT_BACK_PROGRAM = "COMEN01C";

    /**
     * Default back-navigation target transaction when no caller context is
     * supplied &mdash; COBOL {@code LIT-MENUTRANID} ({@code 'CM00'}, the main menu).
     */
    static final String DEFAULT_BACK_TRANSACTION = "CM00";

    /** First header title line &mdash; COBOL {@code CCDA-TITLE01} ({@code COTTL01Y}). */
    private static final String TITLE01 = "AWS Mainframe Modernization";

    /** Second header title line &mdash; COBOL {@code CCDA-TITLE02} ({@code COTTL01Y}). */
    private static final String TITLE02 = "CardDemo";

    /**
     * Program-function-key legend for the view screen &mdash; the two meaningful
     * keys defined by the {@code COCRDSL} footer ({@code ENTER} searches,
     * {@code PF3} exits).
     */
    private static final String FUNCTION_KEYS = "ENTER=Search Cards  F3=Exit";

    /**
     * First-entry prompt &mdash; COBOL {@code WS-PROMPT-FOR-INPUT} in
     * {@code legacy/cbl/COCRDSLC.cbl}: {@code 'Please enter Account and Card Number'}.
     */
    static final String MSG_PROMPT_FOR_INPUT = "Please enter Account and Card Number";

    /**
     * Invalid-attention-key message &mdash; COBOL {@code CCDA-MSG-INVALID-KEY}
     * ({@code legacy/cpy/CSMSG01Y.cpy}): {@code 'Invalid key pressed. Please see below...'}.
     */
    static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Request header carrying the calling program (the stateless carrier of the
     * COBOL COMMAREA {@code CDEMO-FROM-PROGRAM}); optional.
     */
    static final String HEADER_FROM_PROGRAM = "X-CardDemo-From-Program";

    /**
     * Request header carrying the calling transaction (the stateless carrier of the
     * COBOL COMMAREA {@code CDEMO-FROM-TRANID}); optional.
     */
    static final String HEADER_FROM_TRANSACTION = "X-CardDemo-From-Tranid";

    /**
     * Response header naming the program to navigate to next &mdash; the Java
     * analog of the COBOL {@code CDEMO-TO-PROGRAM} set before {@code XCTL}. The
     * header name matches the uniform navigation contract emitted by every other
     * online controller (QA finding F7).
     */
    static final String NEXT_PROGRAM_HEADER = "X-CardDemo-Next-Program";

    /**
     * Response header naming the transaction to navigate to next &mdash; the Java
     * analog of the COBOL {@code CDEMO-TO-TRANID} set before {@code XCTL}. The
     * header name matches the uniform navigation contract emitted by every other
     * online controller (QA finding F7).
     */
    static final String NEXT_TRANSACTION_HEADER = "X-CardDemo-Next-Transaction";

    /**
     * Header date format &mdash; matches the COBOL {@code WS-CURDATE-MM-DD-YY}
     * layout ({@code mm/dd/yy}) and {@code DateUtils.formatDateMmDdYy}.
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/uu");

    /**
     * Header time format &mdash; matches the COBOL {@code WS-CURTIME-HH-MM-SS}
     * layout ({@code hh:mm:ss}) and {@code DateUtils.formatTimeHhMmSs}.
     */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Card business-logic service; sole data collaborator. Injected via the constructor. */
    private final CardService cardService;

    /** Hand-written entity&harr;DTO mapper for the card screens. Injected via the constructor. */
    private final CardMapper cardMapper;

    /**
     * Creates the controller with its required collaborators.
     *
     * <p>Constructor injection is used (never field injection) so the dependencies
     * are explicit and the instance is fully initialized and immutable after
     * construction. The body only assigns the fields.</p>
     *
     * @param cardService the card business-logic service; must not be {@code null}
     * @param cardMapper  the card entity/DTO mapper; must not be {@code null}
     */
    public CardViewController(CardService cardService, CardMapper cardMapper) {
        this.cardService = cardService;
        this.cardMapper = cardMapper;
    }

    /**
     * Renders the first-entry (blank) card-view screen &mdash; the COBOL
     * {@code EIBCALEN = 0} path that initializes the header and current date/time
     * and shows the {@link #MSG_PROMPT_FOR_INPUT} prompt with no error.
     *
     * @return {@code 200 OK} carrying an empty {@link CardViewResponse} with the
     *         prompt as its informational message
     */
    @GetMapping
    @Operation(summary = "Blank Card View screen",
            description = "First-entry screen prompting for the card key (COBOL COCRDSLC initial SEND MAP).")
    public ResponseEntity<CardViewResponse> view() {
        return ResponseEntity.ok(
                blankScreen(null, null, MSG_PROMPT_FOR_INPUT, null, LocalDateTime.now()));
    }

    /**
     * Processes a card-view screen submission, routing on the attention key
     * exactly as the COBOL {@code 0000-MAIN} {@code EVALUATE} did.
     *
     * <ul>
     *   <li>{@link PfKeyAction#PF3} &rarr; {@code 200 OK} with the
     *       {@value #NEXT_PROGRAM_HEADER} / {@value #NEXT_TRANSACTION_HEADER} navigation
     *       headers set to the caller's from-program / from-transaction, or to
     *       {@link #DEFAULT_BACK_PROGRAM} / {@link #DEFAULT_BACK_TRANSACTION} when
     *       absent.</li>
     *   <li>{@link PfKeyAction#ENTER} or {@code null} &rarr; resolve the card via
     *       {@link CardService#viewCard(Long, String)} and return {@code 200 OK}
     *       with its detail; a not-found read propagates as {@code 404}, while an
     *       input-edit failure (the service's {@link IllegalArgumentException}
     *       carrying a COBOL {@code 2210}/{@code 2220} edit message) is caught and
     *       redisplayed at {@code 200 OK} same-screen with that message.</li>
     *   <li>any other key &rarr; {@code 200 OK} re-displaying the screen with
     *       {@link #MSG_INVALID_KEY}.</li>
     * </ul>
     *
     * @param request         the submitted screen; its {@code action} selects the
     *                        branch, and its account/card keys drive the lookup
     * @param fromProgram     the optional calling program (COMMAREA
     *                        {@code CDEMO-FROM-PROGRAM}); may be {@code null}
     * @param fromTransaction the optional calling transaction (COMMAREA
     *                        {@code CDEMO-FROM-TRANID}); may be {@code null}
     * @return the {@code 200 OK} card-view response for the selected branch
     */
    @PostMapping
    @Operation(summary = "Submit the Card View screen",
            description = "Reproduces the COCRDSLC lookup: fetch and display the requested card detail.")
    public ResponseEntity<CardViewResponse> view(
            @Valid @RequestBody CardViewRequest request,
            @RequestHeader(value = HEADER_FROM_PROGRAM, required = false) String fromProgram,
            @RequestHeader(value = HEADER_FROM_TRANSACTION, required = false) String fromTransaction) {

        LocalDateTime now = LocalDateTime.now();
        PfKeyAction action = request.action();

        // COBOL PF03: EXEC CICS XCTL to CDEMO-FROM-PROGRAM (else the main menu).
        if (action == PfKeyAction.PF3) {
            String toProgram = firstNonBlank(fromProgram, DEFAULT_BACK_PROGRAM);
            String toTransaction = firstNonBlank(fromTransaction, DEFAULT_BACK_TRANSACTION);
            return ResponseEntity.ok()
                    .header(NEXT_PROGRAM_HEADER, toProgram)
                    .header(NEXT_TRANSACTION_HEADER, toTransaction)
                    .body(blankScreen(null, null, null, null, now));
        }

        // COBOL ENTER path (any non-PF3 key is remapped to ENTER at 0000-MAIN L291-299,
        // so a null/unspecified action is treated as ENTER): read and display the card.
        // The service owns the field edits and the keyed read; exceptions are not caught
        // here (RecordNotFoundException -> 404; input-edit IllegalArgumentException -> 400/service).
        if (action == null || action == PfKeyAction.ENTER) {
            // The service owns the field edits (COBOL 2210-EDIT-ACCOUNT / 2220-EDIT-CARD)
            // and the keyed read. A not-found read raises RecordNotFoundException, which is
            // deliberately NOT caught here so it propagates to the GlobalExceptionHandler
            // (-> 404). An input-edit failure raises IllegalArgumentException carrying a
            // COBOL edit message; that is a same-screen field edit, not a server fault, so
            // it is caught and the screen is redisplayed at 200 OK echoing the operator's
            // search keys and the verbatim message (QA finding F1, card recurrence).
            Card card;
            try {
                card = cardService.viewCard(parseAccountId(request.accountId()), request.cardId());
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.ok(
                        blankScreen(request.accountId(), request.cardId(), null, ex.getMessage(), now));
            }
            return ResponseEntity.ok(cardMapper.toViewResponse(
                    card, null, null, now, TRANSACTION_ID, TITLE01, TITLE02, PROGRAM_ID, FUNCTION_KEYS));
        }

        // Any other explicit attention key: re-display the screen with the invalid-key message,
        // echoing the operator's search keys.
        return ResponseEntity.ok(
                blankScreen(request.accountId(), request.cardId(), null, MSG_INVALID_KEY, now));
    }

    // ------------------------------------------------------------------------
    // Private, stateless helpers
    // ------------------------------------------------------------------------

    /**
     * Builds a card-view response for a screen that carries no resolved card
     * (first-entry, exit navigation, or an invalid-key redisplay). The card-detail
     * fields (name, status, expiry month/year) are left empty; the header identity,
     * titles, current date/time, and function-key legend are always populated,
     * mirroring the COBOL {@code 1100-SCREEN-INIT} initialization.
     *
     * <p>The field order below deliberately mirrors the {@link CardViewResponse}
     * record component order (itself the physical order of the BMS map
     * {@code CCRDSLAO}) for one-to-one traceability. The sensitive card
     * verification value is never referenced.</p>
     *
     * @param accountId    the echoed account key ({@code ACCTSIDO}); may be {@code null}
     * @param cardId       the echoed card key ({@code CARDSIDO}); may be {@code null}
     * @param infoMessage  the informational message line ({@code INFOMSGO}); may be {@code null}
     * @param errorMessage the error message line ({@code ERRMSGO}); may be {@code null}
     * @param now          the timestamp used to render the header date/time; never {@code null}
     * @return the populated header-only {@link CardViewResponse}
     */
    private CardViewResponse blankScreen(String accountId,
                                         String cardId,
                                         String infoMessage,
                                         String errorMessage,
                                         LocalDateTime now) {
        return new CardViewResponse(
                TRANSACTION_ID,           // transactionName (TRNNAMEO)
                TITLE01,                  // title01         (TITLE01O)
                now.format(DATE_FORMAT),  // currentDate     (CURDATEO)
                PROGRAM_ID,               // programName     (PGMNAMEO)
                TITLE02,                  // title02         (TITLE02O)
                now.format(TIME_FORMAT),  // currentTime     (CURTIMEO)
                accountId,                // accountId       (ACCTSIDO)
                cardId,                   // cardId          (CARDSIDO)
                null,                     // cardName        (CRDNAMEO)
                null,                     // cardStatus      (CRDSTCDO)
                null,                     // expiryMonth     (EXPMONO)
                null,                     // expiryYear      (EXPYEARO)
                infoMessage,              // infoMessage     (INFOMSGO)
                errorMessage,             // errorMessage    (ERRMSGO)
                FUNCTION_KEYS);           // functionKeys    (FKEYSO)
    }

    /**
     * Parses the request's account-key text into the {@link Long} the service
     * expects, treating {@code null} or blank as "no account supplied". The value
     * is guaranteed to be digits-only and at most eleven characters by the request
     * DTO's Bean Validation ({@code @Pattern}/{@code @Size}), so the parse is safe;
     * an eleven-digit maximum ({@code 99,999,999,999}) fits comfortably in a
     * {@code long}.
     *
     * @param accountId the request account-key text; may be {@code null} or blank
     * @return the parsed account id, or {@code null} when none was supplied
     */
    private static Long parseAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return null;
        }
        return Long.valueOf(accountId.trim());
    }

    /**
     * Returns {@code value} when it is non-{@code null} and not blank, otherwise
     * {@code fallback}. Used to resolve the exit-navigation target, reproducing the
     * COBOL {@code IF CDEMO-FROM-PROGRAM = LOW-VALUES OR SPACES} default-to-menu
     * guard.
     *
     * @param value    the candidate value; may be {@code null} or blank
     * @param fallback the value to use when {@code value} is absent
     * @return {@code value} if present, else {@code fallback}
     */
    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
