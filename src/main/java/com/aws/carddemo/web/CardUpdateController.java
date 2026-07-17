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

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardUpdateRequest;
import com.aws.carddemo.dto.CardUpdateResponse;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.CardMapper;
import com.aws.carddemo.service.CardService;

/**
 * REST controller for the CardDemo <em>Card Update</em> screen &mdash; the Java
 * re-platform of the online CICS program {@code COCRDUPC} (transaction
 * {@code CCUP}, source {@code legacy/cbl/COCRDUPC.cbl}).
 *
 * <p>The legacy program is a pseudo-conversational 3270 transaction implementing
 * a <strong>fetch &rarr; validate &rarr; confirm &rarr; save</strong> flow. It
 * reads the card keyed by card number, presents the editable attributes, edits
 * the operator's changes and &mdash; only after an explicit confirmation
 * (PF5) &mdash; performs the {@code READ ... UPDATE} / {@code REWRITE} cycle
 * ({@code 9200-WRITE-PROCESSING}). There is no terminal in the Java target, so
 * each 3270 attention key becomes an explicit {@link PfKeyAction} carried on the
 * request DTO and each screen becomes a request/response contract rather than a
 * rendered map (AAP&nbsp;&sect;0.3.3, hotspot&nbsp;H2).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/v1/cards/update} &mdash; the first-entry screen that
 *       prompts for the account and card key (COBOL {@code CCUP-DETAILS-NOT-FETCHED}).</li>
 *   <li>{@code POST /api/v1/cards/update} &mdash; a single screen submit routed on
 *       the attention key ({@link CardUpdateRequest#action()}).</li>
 * </ul>
 *
 * <h2>PF-key routing (parity with {@code COCRDUPC})</h2>
 * <ul>
 *   <li><strong>ENTER</strong> (and an absent key) &mdash; validate and preview
 *       the entered changes. This turn <em>never writes</em>: it re-presents the
 *       card detail with the "press F5 to save" confirmation prompt, exactly as
 *       the legacy program set {@code CCUP-CHANGES-OK-NOT-CONFIRMED} before any
 *       rewrite.</li>
 *   <li><strong>PF5</strong> &mdash; confirm and persist. This is the
 *       <em>sole writer</em>; it delegates to {@link CardService#updateCard} which
 *       reproduces the field edits and the atomic {@code REWRITE}.</li>
 *   <li><strong>PF3</strong> &mdash; exit; navigate back to the main menu
 *       ({@code COMEN01C} / {@code CM00}) via response headers.</li>
 *   <li><strong>PF12</strong> &mdash; cancel; discard the pending edits and
 *       re-present the stored detail (or the key-entry screen when no key is held).</li>
 *   <li><strong>any other key</strong> &mdash; the shared "invalid key" message.</li>
 * </ul>
 *
 * <h2>Why ENTER never writes</h2>
 * {@link CardService#updateCard} is an <em>atomic</em> validate-and-persist
 * operation (only its {@code CHANGES_OK} outcome writes). To preserve the legacy
 * invariant that ENTER validates without rewriting and PF5 is the only key that
 * commits, ENTER fetches the current card with the read-only
 * {@link CardService#viewCard} and builds the change preview in memory with
 * {@link CardMapper#updateEntity}. Because {@code spring.jpa.open-in-view} is
 * disabled, the entity returned by the service is detached in this controller, so
 * mutating it for the preview performs no database write &mdash; the rewrite
 * happens only on the PF5 path through {@link CardService#updateCard}.
 *
 * <h2>Exceptions</h2>
 * This controller contains <em>no</em> {@code try}/{@code catch}. Typed
 * exceptions propagate to {@code GlobalExceptionHandler}, which maps
 * {@code RecordNotFoundException} to HTTP&nbsp;404, and
 * {@code OptimisticLockingFailureException} / {@code OptimisticLockException} (the
 * concurrent-change guard reproducing {@code 9300-CHECK-CHANGE-IN-REC}) to
 * HTTP&nbsp;409. Bean Validation failures on the request body surface as
 * HTTP&nbsp;400.
 *
 * <h2>Sensitive data</h2>
 * The card verification value (CVV) is never read, never returned and never
 * logged: {@link CardUpdateResponse} carries no CVV field, and neither the update
 * ({@link CardService#updateCard}) nor the preview mapping
 * ({@link CardMapper#updateEntity}) touches it (AAP&nbsp;&sect;0.7.3,
 * &sect;0.9.3). This controller performs no request/response logging.
 *
 * <p>Dependencies are supplied by constructor injection and this class holds no
 * mutable state, so it is thread-safe.
 */
@RestController
@RequestMapping("/api/v1/cards/update")
public class CardUpdateController {

    /** This program's identifier, shown in the screen header ({@code PGMNAMEO}). */
    private static final String PROGRAM_ID = "COCRDUPC";

    /** This program's CICS transaction id, shown in the header ({@code TRNNAMEO}). */
    private static final String TRANSACTION_ID = "CCUP";

    /**
     * Back-navigation program target &mdash; the main menu {@code COMEN01C}
     * ({@code LIT-MENUPGM}) that {@code COCRDUPC} transfers to on PF3 when no
     * originating program was supplied.
     */
    private static final String BACK_PROGRAM_ID = "COMEN01C";

    /** Back-navigation transaction target &mdash; {@code CM00} ({@code LIT-MENUTRANID}). */
    private static final String BACK_TRANSACTION_ID = "CM00";

    /** Response header naming the program to navigate to (the {@code XCTL} target). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header naming the transaction to navigate to. */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** First header title line ({@code CCDA-TITLE01} of {@code COTTL01Y}). */
    private static final String TITLE_01 = "AWS Mainframe Modernization";

    /** Second header title line ({@code CCDA-TITLE02} of {@code COTTL01Y}). */
    private static final String TITLE_02 = "CardDemo";

    /** First segment of the PF-key legend ({@code FKEYS} of BMS map {@code COCRDUP}). */
    private static final String FKEYS = "ENTER=Process F3=Exit";

    /** Second segment of the split PF-key legend ({@code FKEYSC} of BMS map {@code COCRDUP}). */
    private static final String FKEYS_CONT = "F5=Save F12=Cancel";

    // ------------------------------------------------------------------
    // Caller-visible messages (verbatim COBOL — behavioral-parity contracts).
    // Info-line texts trace to WS-INFO-MSG; return-line texts to WS-RETURN-MSG
    // in legacy/cbl/COCRDUPC.cbl (CSMSG01Y for the shared invalid-key text).
    // ------------------------------------------------------------------

    /** {@code PROMPT-FOR-SEARCH-KEYS} &mdash; shown on the blank first-entry screen. */
    private static final String MSG_PROMPT_SEARCH_KEYS = "Please enter Account and Card Number";

    /** {@code PROMPT-FOR-CHANGES} &mdash; shown when the stored detail is presented for editing. */
    private static final String MSG_PROMPT_CHANGES = "Update card details presented above.";

    /** {@code PROMPT-FOR-CONFIRMATION} &mdash; shown after ENTER validates the changes. */
    private static final String MSG_PROMPT_CONFIRMATION = "Changes validated.Press F5 to save";

    /** {@code CONFIRM-UPDATE-SUCCESS} &mdash; shown after PF5 commits the rewrite. */
    private static final String MSG_CONFIRM_SUCCESS = "Changes committed to database";

    /** {@code WS-EXIT-MESSAGE} &mdash; shown on the PF3 exit. */
    private static final String MSG_EXIT = "PF03 pressed.Exiting";

    /** {@code CCDA-MSG-INVALID-KEY} ({@code CSMSG01Y}) &mdash; shown for an unrecognized key. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Card business-logic service reproducing the {@code COCRDUPC} paragraphs
     * (field edits, the no-change short-circuit and the atomic
     * READ-UPDATE-REWRITE). Sole business collaborator for reads and writes.
     */
    private final CardService cardService;

    /**
     * Hand-written entity&harr;DTO mapper that builds the card-update response
     * (preserving the field-level screen contract, never the CVV) and applies the
     * operator-editable fields onto a card entity for the in-memory ENTER preview.
     */
    private final CardMapper cardMapper;

    /**
     * Creates the controller with its required collaborators.
     *
     * <p>Constructor injection is used (never field injection), so the
     * dependencies are explicit and the instance is fully initialized and
     * immutable after construction. The body only assigns fields and invokes no
     * overridable method, so no {@code this} reference escapes.
     *
     * @param cardService the card business-logic service; must not be {@code null}
     * @param cardMapper  the card entity&harr;DTO mapper; must not be {@code null}
     */
    public CardUpdateController(CardService cardService, CardMapper cardMapper) {
        this.cardService = cardService;
        this.cardMapper = cardMapper;
    }

    /**
     * Presents the blank first-entry card-update screen, prompting the operator
     * for the account and card key. This is the Java equivalent of the
     * {@code COCRDUPC} fresh-entry path ({@code CCUP-DETAILS-NOT-FETCHED} with
     * {@code CDEMO-PGM-ENTER}), which sends the empty map with the
     * {@code PROMPT-FOR-SEARCH-KEYS} information message.
     *
     * @return {@code 200 OK} carrying an empty {@link CardUpdateResponse} with the
     *         key-entry prompt and the function-key legend
     */
    @GetMapping
    public ResponseEntity<CardUpdateResponse> showUpdateScreen() {
        return ResponseEntity.ok(blankScreen(MSG_PROMPT_SEARCH_KEYS, ""));
    }

    /**
     * Handles a card-update screen submit, routing on the operator's attention
     * key exactly as the {@code COCRDUPC} main {@code EVALUATE} does. An absent
     * key is treated as ENTER (the legacy program folds an unrecognized or missing
     * AID onto ENTER before deciding the action).
     *
     * <p>The request body is validated by Bean Validation ({@link Valid}); a
     * malformed field surfaces as HTTP&nbsp;400 through {@code GlobalExceptionHandler},
     * reproducing the {@code PIC}-derived format edits of the BMS map. The
     * substantive business edits and the rewrite are owned by
     * {@link CardService#updateCard} and are exercised on the PF5 (save) path.
     *
     * @param request the submitted card key, edited fields and attention key;
     *                 validated and never {@code null}
     * @return the next screen state as a {@link CardUpdateResponse}; always
     *         HTTP&nbsp;200 for the modelled keys (not-found and concurrent-change
     *         outcomes propagate as 404 / 409 from the service)
     */
    @PostMapping
    public ResponseEntity<CardUpdateResponse> update(@Valid @RequestBody CardUpdateRequest request) {
        PfKeyAction action = (request.action() == null) ? PfKeyAction.ENTER : request.action();
        return switch (action) {
            case ENTER -> handleValidate(request);
            case PF5 -> handleConfirm(request);
            case PF3 -> handleExit();
            case PF12 -> handleCancel(request);
            default -> handleInvalidKey();
        };
    }

    /**
     * ENTER &mdash; validate and preview the entered changes <em>without writing</em>.
     *
     * <p>Reproduces the {@code COCRDUPC} ENTER turn: with no key supplied it
     * re-presents the key-entry prompt ({@code CCUP-DETAILS-NOT-FETCHED}); otherwise
     * it fetches the current card by key ({@code 9000-READ-DATA}, card number taking
     * read precedence), applies the operator's editable fields onto the
     * <em>detached</em> entity to build the echo preview, and re-presents the detail
     * with the {@code PROMPT-FOR-CONFIRMATION} ("press F5 to save") message.
     *
     * <p>No persistence occurs here: the entity is detached
     * ({@code spring.jpa.open-in-view=false}) and {@link CardMapper#updateEntity}
     * only mutates in memory &mdash; no {@code save} is invoked on this path, so the
     * legacy invariant "ENTER never rewrites" is preserved. A missing card raises
     * {@code RecordNotFoundException} (&rarr; HTTP&nbsp;404).
     *
     * @param request the submitted key and edited fields
     * @return {@code 200 OK} echoing the previewed detail with the confirmation prompt
     */
    private ResponseEntity<CardUpdateResponse> handleValidate(CardUpdateRequest request) {
        if (isBlank(request.cardId()) && isBlank(request.accountId())) {
            return ResponseEntity.ok(blankScreen(MSG_PROMPT_SEARCH_KEYS, ""));
        }
        Card card = cardService.viewCard(parseAccountId(request.accountId()), request.cardId());
        // Build the change preview on the detached entity; this performs no write
        // (no save is called and open-in-view is disabled). The CVV is not touched.
        cardMapper.updateEntity(request, card);
        return ResponseEntity.ok(cardMapper.toUpdateResponse(
                card, MSG_PROMPT_CONFIRMATION, "", LocalDateTime.now(),
                TRANSACTION_ID, TITLE_01, TITLE_02, PROGRAM_ID, FKEYS, FKEYS_CONT));
    }

    /**
     * PF5 &mdash; confirm and persist. This is the only path that writes.
     *
     * <p>Delegates to {@link CardService#updateCard}, which reproduces the field
     * edits, the no-change short-circuit and the atomic {@code REWRITE}
     * ({@code 9200-WRITE-PROCESSING}). The outcome is mapped to a same-screen
     * response: {@code CHANGES_OK} shows the {@code CONFIRM-UPDATE-SUCCESS} message
     * ("Changes committed to database"), while {@code CHANGES_NOT_OK} (a failing
     * field edit) and {@code NO_CHANGES_DETECTED} (nothing to save) carry the exact
     * COBOL message on the error line. A concurrent modification throws
     * {@code OptimisticLockingFailureException} (&rarr; HTTP&nbsp;409) and a missing
     * card throws {@code RecordNotFoundException} (&rarr; HTTP&nbsp;404); both
     * propagate untouched to {@code GlobalExceptionHandler}.
     *
     * @param request the submitted key and edited fields
     * @return {@code 200 OK} with the success, edit-failure or no-change message
     */
    private ResponseEntity<CardUpdateResponse> handleConfirm(CardUpdateRequest request) {
        CardService.CardUpdateResult result = cardService.updateCard(
                request.cardId(),
                request.cardName(),
                request.cardStatus(),
                request.expiryMonth(),
                request.expiryYear());
        LocalDateTime now = LocalDateTime.now();
        return switch (result.status()) {
            case CHANGES_OK -> ResponseEntity.ok(cardMapper.toUpdateResponse(
                    result.card(), MSG_CONFIRM_SUCCESS, "", now,
                    TRANSACTION_ID, TITLE_01, TITLE_02, PROGRAM_ID, FKEYS, FKEYS_CONT));
            case CHANGES_NOT_OK, NO_CHANGES_DETECTED -> ResponseEntity.ok(cardMapper.toUpdateResponse(
                    result.card(), "", result.message(), now,
                    TRANSACTION_ID, TITLE_01, TITLE_02, PROGRAM_ID, FKEYS, FKEYS_CONT));
        };
    }

    /**
     * PF3 &mdash; exit the screen and navigate back.
     *
     * <p>Reproduces the {@code COCRDUPC} PF3 path, which performs a {@code SYNCPOINT}
     * and {@code XCTL} to the originating program or, when none was supplied, to the
     * main menu ({@code COMEN01C} / {@code CM00}). With no rendered terminal here the
     * navigation target is conveyed through response headers, and the
     * {@code WS-EXIT-MESSAGE} exit notice is carried on the otherwise-blank body.
     *
     * @return {@code 200 OK} with the next-program / next-transaction navigation
     *         headers and the exit message
     */
    private ResponseEntity<CardUpdateResponse> handleExit() {
        return ResponseEntity.ok()
                .header(HEADER_NEXT_PROGRAM, BACK_PROGRAM_ID)
                .header(HEADER_NEXT_TRANSACTION, BACK_TRANSACTION_ID)
                .body(blankScreen("", MSG_EXIT));
    }

    /**
     * PF12 &mdash; cancel the pending edits.
     *
     * <p>Reproduces the {@code COCRDUPC} cancel path ({@code 2000-DECIDE-ACTION}),
     * which discards the operator's uncommitted changes and re-reads the stored
     * record for redisplay. When a key is held the stored detail is fetched afresh
     * (so the edits are dropped) and shown with the {@code PROMPT-FOR-CHANGES}
     * message; when no key is held the blank key-entry screen is presented. A
     * missing card raises {@code RecordNotFoundException} (&rarr; HTTP&nbsp;404).
     *
     * @param request the submitted key (edited fields are intentionally ignored)
     * @return {@code 200 OK} with the re-read stored detail, or the key-entry prompt
     */
    private ResponseEntity<CardUpdateResponse> handleCancel(CardUpdateRequest request) {
        if (isBlank(request.cardId()) && isBlank(request.accountId())) {
            return ResponseEntity.ok(blankScreen(MSG_PROMPT_SEARCH_KEYS, ""));
        }
        Card card = cardService.viewCard(parseAccountId(request.accountId()), request.cardId());
        return ResponseEntity.ok(cardMapper.toUpdateResponse(
                card, MSG_PROMPT_CHANGES, "", LocalDateTime.now(),
                TRANSACTION_ID, TITLE_01, TITLE_02, PROGRAM_ID, FKEYS, FKEYS_CONT));
    }

    /**
     * Any other key &mdash; report an invalid key.
     *
     * <p>{@code COCRDUPC} folds an unrecognized AID back onto ENTER; the Java target
     * instead surfaces the shared {@code CSMSG01Y} "invalid key" message on an
     * otherwise-blank screen, keeping the attention-key contract explicit.
     *
     * @return {@code 200 OK} with the invalid-key message
     */
    private ResponseEntity<CardUpdateResponse> handleInvalidKey() {
        return ResponseEntity.ok(blankScreen("", MSG_INVALID_KEY));
    }

    /**
     * Builds a card-less {@link CardUpdateResponse} for the key-entry, exit and
     * invalid-key screens, populating the static header chrome and the given
     * message lines while leaving the card detail fields empty.
     *
     * <p>The header clock ({@code currentDate} / {@code currentTime}) is left
     * {@code null} on these transient screens: the shared date-formatting utility
     * is not a permitted dependency of this controller, and the card-detail screens
     * obtain their formatted header time from {@link CardMapper#toUpdateResponse}.
     *
     * @param infoMessage  the information-line message (may be empty, never {@code null})
     * @param errorMessage the error-line message (may be empty, never {@code null})
     * @return a populated, card-less response
     */
    private CardUpdateResponse blankScreen(String infoMessage, String errorMessage) {
        return new CardUpdateResponse(
                TRANSACTION_ID,   // transactionName (TRNNAMEO)
                TITLE_01,         // title01 (TITLE01O)
                null,             // currentDate (CURDATEO) — omitted on transient screens
                PROGRAM_ID,       // programName (PGMNAMEO)
                TITLE_02,         // title02 (TITLE02O)
                null,             // currentTime (CURTIMEO) — omitted on transient screens
                null,             // accountId (ACCTSIDO)
                null,             // cardId (CARDSIDO)
                null,             // cardName (CRDNAMEO)
                null,             // cardStatus (CRDSTCDO)
                null,             // expiryMonth (EXPMONO)
                null,             // expiryYear (EXPYEARO)
                null,             // expiryDay (EXPDAYO)
                infoMessage,      // infoMessage (INFOMSGO)
                errorMessage,     // errorMessage (ERRMSGO)
                FKEYS,            // functionKeys (FKEYSO)
                FKEYS_CONT);      // functionKeysContinued (FKEYSCO)
    }

    /**
     * Parses the optional account-id key into the {@link Long} expected by
     * {@link CardService#viewCard}, returning {@code null} for an absent value so
     * the service treats it as "no account filter".
     *
     * <p>The value is safe to parse without catching: the request DTO constrains
     * {@code accountId} to at most eleven digits ({@code ^\d{0,11}$}), whose
     * numeric value always fits in a {@code long}.
     *
     * @param accountId the submitted account id; may be {@code null} or blank
     * @return the parsed account id, or {@code null} when none was supplied
     */
    private static Long parseAccountId(String accountId) {
        if (isBlank(accountId)) {
            return null;
        }
        return Long.valueOf(accountId.strip());
    }

    /**
     * Returns whether the supplied value is {@code null} or contains only
     * whitespace &mdash; used to detect an absent screen key.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} when {@code value} is {@code null} or blank
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

