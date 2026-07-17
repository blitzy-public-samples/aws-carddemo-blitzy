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
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.aws.carddemo.domain.Card;
import com.aws.carddemo.dto.CardListRequest;
import com.aws.carddemo.dto.CardListResponse;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.CardMapper;
import com.aws.carddemo.service.CardService;

/**
 * REST controller for the CardDemo <strong>Card List</strong> screen &mdash; the
 * Java re-platform of the online COBOL program {@code COCRDLIC} (CICS transaction
 * {@code CCLI}), whose source is retained for reference at
 * {@code legacy/cbl/COCRDLIC.cbl}.
 *
 * <p>The legacy program is a pseudo-conversational 3270 transaction that browses
 * the {@code CARDDATA} VSAM dataset by card number, displaying up to seven card
 * rows per screen, and lets the operator (a) filter by account and/or card
 * number, (b) page backward / forward with PF7 / PF8, (c) return to the calling
 * menu with PF3, and (d) select a single row with {@code S} (view) or {@code U}
 * (update) to drill into the card detail / update screen. This controller
 * reproduces those observable behaviors without any feature expansion
 * (AAP&nbsp;&sect;0.7.1 H1, H2, H5).</p>
 *
 * <h2>Pseudo-conversational translation (COMMAREA &rarr; request/response)</h2>
 * <p>The COBOL program carries its browse state (current page number, first and
 * last card keys of the displayed page, and the from/to program navigation
 * context) in the {@code CARDDEMO-COMMAREA} plus its private
 * {@code WS-THIS-PROGCOMMAREA}. There is no terminal or persistent conversation
 * here, so that state is re-expressed statelessly:</p>
 * <ul>
 *   <li>The <em>page number</em> is supplied by the caller as the {@code page}
 *       request parameter (1-based, defaulting to the first page). The legacy
 *       {@code WS-CA-SCREEN-NUM} increment / decrement performed by PF8 / PF7
 *       becomes simple arithmetic on that value.</li>
 *   <li>The {@code EXEC CICS XCTL PROGRAM(CCARD-NEXT-PROG)} transfers of control
 *       (to the menu on PF3, and to the card view / update programs on a row
 *       selection) become navigation hints returned to the caller as the
 *       {@value #HEADER_NEXT_PROGRAM} / {@value #HEADER_NEXT_TRANSACTION}
 *       response headers; a row selection additionally returns the chosen card's
 *       number and owning account via {@value #HEADER_CARD_NUMBER} /
 *       {@value #HEADER_ACCOUNT_ID} so the client can carry them to the next
 *       screen (the legacy {@code MOVE WS-ROW-ACCTNO/WS-ROW-CARD-NUM TO
 *       CDEMO-ACCT-ID/CDEMO-CARD-NUM}).</li>
 *   <li>The attention identifier ({@code EIBAID}) becomes the request
 *       {@link CardListRequest#action() action}; the legacy remap of any invalid
 *       key back to Enter is re-expressed as an explicit "invalid key" redisplay
 *       branch.</li>
 * </ul>
 *
 * <h2>Layering and delegation</h2>
 * <p>This is a deliberately <em>thin</em> controller: the filtering and paging
 * query lives entirely in {@link CardService#listCards(Long, String, Pageable)}
 * (the re-platform of the COBOL {@code STARTBR}/{@code READNEXT}/{@code READPREV}
 * alternate-index browse and the {@code 9500-FILTER-RECORDS} filter), and the
 * entity-to-DTO projection lives entirely in {@link CardMapper}. The controller
 * only builds the {@link Pageable}, routes on the pressed key, applies the
 * screen-level row-selection edits and the caller-visible message text, and maps
 * the resulting page. There is no {@code try}/{@code catch}: input-format
 * failures surface through Bean Validation and are handled centrally, and data
 * access errors surface through the shared exception handling.</p>
 *
 * <h2>Sensitive data</h2>
 * <p>The card verification value (CVV) is never part of the list contract:
 * {@link CardMapper#toListRow(Card)} projects only the account number, card
 * number, and status. Neither the request nor the response is ever logged
 * (AAP&nbsp;&sect;0.9.3).</p>
 *
 * <p>All caller-visible message strings defined below are behavioral-parity
 * contracts copied verbatim from the COBOL working storage of
 * {@code COCRDLIC} and must not be paraphrased.</p>
 */
@RestController
@RequestMapping("/api/v1/cards")
public class CardListController {

    // ------------------------------------------------------------------
    // Screen geometry and ordering
    // ------------------------------------------------------------------

    /**
     * Number of card rows shown per browse page. Mirrors the COBOL
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} of {@code COCRDLIC}:
     * the 3270 map {@code COCRDLI} renders exactly seven card occurrences.
     */
    private static final int PAGE_SIZE = 7;

    /**
     * JPA property used to order the browse by primary key (card number
     * ascending), preserving the {@code CARDDATA.VSAM.KSDS} key order the legacy
     * {@code STARTBR}/{@code READNEXT} browse produced. Matches the
     * {@code cardNum} field of {@link Card}.
     */
    private static final String SORT_PROPERTY_CARD_NUM = "cardNum";

    // ------------------------------------------------------------------
    // Screen identity (COBOL LIT-THISPGM / LIT-THISTRANID and titles)
    // ------------------------------------------------------------------

    /** Header program identifier ({@code LIT-THISPGM VALUE 'COCRDLIC'}). */
    private static final String PROGRAM_NAME = "COCRDLIC";

    /** Header transaction identifier ({@code LIT-THISTRANID VALUE 'CCLI'}). */
    private static final String TRANSACTION_NAME = "CCLI";

    /**
     * First header title line ({@code CCDA-TITLE01} in
     * {@code legacy/cpy/COTTL01Y.cpy}).
     */
    private static final String TITLE_01 = "AWS Mainframe Modernization";

    /**
     * Second header title line ({@code CCDA-TITLE02} in
     * {@code legacy/cpy/COTTL01Y.cpy}).
     */
    private static final String TITLE_02 = "CardDemo";

    // ------------------------------------------------------------------
    // Navigation targets (COBOL XCTL destinations)
    // ------------------------------------------------------------------

    /** Back / exit target program on PF3 ({@code LIT-MENUPGM VALUE 'COMEN01C'}). */
    private static final String MENU_PROGRAM = "COMEN01C";

    /** Back / exit target transaction on PF3 ({@code LIT-MENUTRANID VALUE 'CM00'}). */
    private static final String MENU_TRANSACTION = "CM00";

    /** Row-select view target program ({@code LIT-CARDDTLPGM VALUE 'COCRDSLC'}). */
    private static final String CARD_VIEW_PROGRAM = "COCRDSLC";

    /** Row-select view target transaction ({@code LIT-CARDDTLTRANID VALUE 'CCDL'}). */
    private static final String CARD_VIEW_TRANSACTION = "CCDL";

    /** Row-select update target program ({@code LIT-CARDUPDPGM VALUE 'COCRDUPC'}). */
    private static final String CARD_UPDATE_PROGRAM = "COCRDUPC";

    /** Row-select update target transaction ({@code LIT-CARDUPDTRANID VALUE 'CCUP'}). */
    private static final String CARD_UPDATE_TRANSACTION = "CCUP";

    // ------------------------------------------------------------------
    // Navigation response headers (re-expression of EXEC CICS XCTL)
    // ------------------------------------------------------------------

    /** Response header carrying the next program to navigate to (COBOL {@code CCARD-NEXT-PROG}). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header carrying the next transaction id to navigate to. */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** Response header carrying the selected card number to carry forward (COBOL {@code CDEMO-CARD-NUM}). */
    private static final String HEADER_CARD_NUMBER = "X-CardDemo-Card-Number";

    /** Response header carrying the selected card's owning account to carry forward (COBOL {@code CDEMO-ACCT-ID}). */
    private static final String HEADER_ACCOUNT_ID = "X-CardDemo-Account-Id";

    // ------------------------------------------------------------------
    // Row-selection action codes (COBOL 88-levels SELECT-OK: 'S', 'U')
    // ------------------------------------------------------------------

    /** Selection flag requesting the card <em>view</em> screen ({@code VIEW-REQUESTED-ON VALUE 'S'}). */
    private static final String SELECT_VIEW = "S";

    /** Selection flag requesting the card <em>update</em> screen ({@code UPDATE-REQUESTED-ON VALUE 'U'}). */
    private static final String SELECT_UPDATE = "U";

    // ------------------------------------------------------------------
    // Caller-visible messages (behavioral-parity contracts, verbatim COBOL)
    // ------------------------------------------------------------------

    /**
     * Informational message shown while records are displayed. Exact COBOL
     * literal {@code WS-INFORM-REC-ACTIONS} of {@code COCRDLIC}:
     * {@code 'TYPE S FOR DETAIL, U TO UPDATE ANY RECORD'}.
     */
    private static final String MSG_INFORM_REC_ACTIONS =
            "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /**
     * Error message shown when the search matched no cards. Exact COBOL literal
     * {@code WS-NO-RECORDS-FOUND} of {@code COCRDLIC}:
     * {@code 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.'}.
     */
    private static final String MSG_NO_RECORDS_FOUND =
            "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /**
     * Boundary message shown when PF7 is pressed on the first page. Exact COBOL
     * literal moved to {@code WS-ERROR-MSG} in {@code 1400-SETUP-MESSAGE}:
     * {@code 'NO PREVIOUS PAGES TO DISPLAY'}.
     */
    private static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /**
     * Boundary message shown when PF8 is pressed past the last page. Exact COBOL
     * literal moved to {@code WS-ERROR-MSG} in {@code 1400-SETUP-MESSAGE}:
     * {@code 'NO MORE PAGES TO DISPLAY'}.
     */
    private static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    /**
     * Error message shown when more than one row is selected. Exact COBOL literal
     * {@code WS-MORE-THAN-1-ACTION} of {@code COCRDLIC}:
     * {@code 'PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE'}.
     */
    private static final String MSG_MORE_THAN_ONE_ACTION =
            "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /**
     * Error message shown when a row-selection flag is neither {@code S},
     * {@code U}, nor blank. Exact COBOL literal {@code WS-INVALID-ACTION-CODE} of
     * {@code COCRDLIC}: {@code 'INVALID ACTION CODE'}.
     */
    private static final String MSG_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /**
     * Message set when PF3 is pressed to exit to the menu. Exact COBOL literal
     * {@code WS-EXIT-MESSAGE} of {@code COCRDLIC} (an {@code 88} level of the
     * error-message field): {@code 'PF03 PRESSED.EXITING'}.
     */
    private static final String MSG_EXIT = "PF03 PRESSED.EXITING";

    /**
     * Message shown when an attention key other than Enter / PF3 / PF7 / PF8 is
     * received. The legacy program silently remapped every unrecognized key back
     * to Enter and simply redisplayed the current page; this REST re-expression
     * redisplays the current page and additionally surfaces a concise
     * "invalid key" notice (documented in {@code docs/decision-log.md} as a
     * benign UI-contract addition, not a behavior change).
     */
    private static final String MSG_INVALID_KEY = "INVALID KEY PRESSED";

    // ------------------------------------------------------------------
    // Collaborators (constructor-injected, never field-injected)
    // ------------------------------------------------------------------

    /** Business/data-access service reproducing the {@code COCRDLIC} browse. */
    private final CardService cardService;

    /** Hand-written mapper projecting {@link Card} entities onto the list DTO. */
    private final CardMapper cardMapper;

    /**
     * Creates the controller with its required collaborators.
     *
     * <p>Constructor injection is used (never field injection) so the
     * dependencies are explicit and the instance is fully initialized and
     * immutable after construction.</p>
     *
     * @param cardService the card browse service (never {@code null})
     * @param cardMapper  the entity-to-DTO mapper (never {@code null})
     */
    public CardListController(CardService cardService, CardMapper cardMapper) {
        this.cardService = cardService;
        this.cardMapper = cardMapper;
    }

    // ==================================================================
    // Endpoints
    // ==================================================================

    /**
     * Displays the first page of the unfiltered card list &mdash; the fresh
     * entry into the screen, equivalent to the COBOL first invocation
     * ({@code EIBCALEN = 0}) that initializes the browse and reads forward from
     * the start of {@code CARDDATA}.
     *
     * <p>No filters are applied and the browse starts at page one, ordered by
     * card number ascending. When at least one card exists the informational
     * message {@value #MSG_INFORM_REC_ACTIONS} is returned; when the table is
     * empty the error message {@value #MSG_NO_RECORDS_FOUND} is returned. The
     * page indicator in the response is always 1-based.</p>
     *
     * @return {@code 200 OK} with the first {@link CardListResponse} page
     */
    @GetMapping
    public ResponseEntity<CardListResponse> firstPage() {
        Page<Card> page = query(null, null, 0);
        return okResponse(page, null);
    }

    /**
     * Processes a card-list screen submission, routing on the attention key the
     * operator pressed. This reproduces the COBOL {@code EVALUATE TRUE} over the
     * mapped PF key in {@code COCRDLIC} ({@code 0000-MAIN}).
     *
     * <ul>
     *   <li>{@link PfKeyAction#ENTER} &mdash; apply the account / card filters
     *       and re-display the requested page; if exactly one row is selected,
     *       additionally return the navigation headers for the card view
     *       ({@code S}) or update ({@code U}) screen.</li>
     *   <li>{@link PfKeyAction#PF3} &mdash; exit to the main menu
     *       ({@value #MENU_PROGRAM} / {@value #MENU_TRANSACTION}).</li>
     *   <li>{@link PfKeyAction#PF7} &mdash; page backward (floored at the first
     *       page, where {@value #MSG_NO_PREVIOUS_PAGES} is shown).</li>
     *   <li>{@link PfKeyAction#PF8} &mdash; page forward (at the end,
     *       {@value #MSG_NO_MORE_PAGES} is shown).</li>
     *   <li>any other key &mdash; redisplay the current page with an invalid-key
     *       notice.</li>
     * </ul>
     *
     * @param request the screen input (filters, per-row selections, and the
     *                pressed key); validated against its field-format contract
     * @param page    the 1-based current page indicator carried across the
     *                stateless submission (the re-expression of the COBOL
     *                {@code WS-CA-SCREEN-NUM} COMMAREA field); defaults to the
     *                first page
     * @return {@code 200 OK} with the resulting {@link CardListResponse}; a row
     *         selection or PF3 additionally sets the navigation headers
     */
    @PostMapping
    public ResponseEntity<CardListResponse> browse(
            @Valid @RequestBody CardListRequest request,
            @RequestParam(name = "page", defaultValue = "1") int page) {

        PfKeyAction action = request.action();
        if (action == null) {
            return handleInvalidKey(request, page);
        }
        return switch (action) {
            case ENTER -> handleEnter(request, page);
            case PF3 -> handleExit();
            case PF7 -> handlePageBackward(request, page);
            case PF8 -> handlePageForward(request, page);
            default -> handleInvalidKey(request, page);
        };
    }

    // ==================================================================
    // Key handlers (parity with the COCRDLIC main EVALUATE branches)
    // ==================================================================

    /**
     * Handles Enter: applies the filters, re-queries the requested page, and
     * either drills into a selected row or redisplays the page.
     *
     * <p>The per-row selection edits reproduce {@code 2250-EDIT-ARRAY}: more than
     * one selected row yields {@value #MSG_MORE_THAN_ONE_ACTION}; a selection
     * flag other than {@code S}, {@code U}, or blank yields
     * {@value #MSG_INVALID_ACTION_CODE}; and a single valid selection navigates
     * to the card view ({@code S}) or update ({@code U}) screen, carrying the
     * chosen card's number and account forward (COBOL
     * {@code MOVE WS-ROW-ACCTNO/WS-ROW-CARD-NUM TO CDEMO-ACCT-ID/CDEMO-CARD-NUM}
     * before {@code EXEC CICS XCTL}).</p>
     */
    private ResponseEntity<CardListResponse> handleEnter(CardListRequest request, int page) {
        int pageIndex = toPageIndex(page);
        Long accountFilter = parseAccountFilter(request.accountId());
        String cardFilter = request.cardId();
        Page<Card> resultPage = query(accountFilter, cardFilter, pageIndex);

        SelectionResult selection = scanSelections(request.rowSelections());
        if (selection.error() != null) {
            // Selection edit failed (>1 selection, or an invalid flag): redisplay
            // the page with the edit message and perform no navigation.
            return okResponse(resultPage, selection.error());
        }

        if (selection.index() >= 0) {
            List<Card> content = resultPage.getContent();
            if (selection.index() < content.size()) {
                Card selected = content.get(selection.index());
                String nextProgram = selection.update() ? CARD_UPDATE_PROGRAM : CARD_VIEW_PROGRAM;
                String nextTransaction =
                        selection.update() ? CARD_UPDATE_TRANSACTION : CARD_VIEW_TRANSACTION;
                CardListResponse body = bodyWithDefaults(resultPage, null);
                return ResponseEntity.ok()
                        .header(HEADER_NEXT_PROGRAM, nextProgram)
                        .header(HEADER_NEXT_TRANSACTION, nextTransaction)
                        .header(HEADER_CARD_NUMBER, selected.getCardNum())
                        .header(HEADER_ACCOUNT_ID, String.valueOf(selected.getAcctId()))
                        .body(body);
            }
            // A row with no backing card was selected (out of the page's bounds):
            // treat as an invalid action, matching the COBOL edit failure.
            return okResponse(resultPage, MSG_INVALID_ACTION_CODE);
        }

        // No row selected: plain (re)display of the filtered page.
        return okResponse(resultPage, null);
    }

    /**
     * Handles PF3: signals a return to the main menu. Reproduces the COBOL
     * {@code EXEC CICS XCTL PROGRAM(LIT-MENUPGM)} with the exit notice
     * {@value #MSG_EXIT}. No card query is performed because control transfers
     * away from the list screen; the navigation headers tell the client where to
     * go next.
     */
    private ResponseEntity<CardListResponse> handleExit() {
        CardListResponse body = cardMapper.toListResponse(
                List.of(),
                "1",
                null,
                MSG_EXIT,
                LocalDateTime.now(),
                TRANSACTION_NAME,
                TITLE_01,
                TITLE_02,
                PROGRAM_NAME);
        return ResponseEntity.ok()
                .header(HEADER_NEXT_PROGRAM, MENU_PROGRAM)
                .header(HEADER_NEXT_TRANSACTION, MENU_TRANSACTION)
                .body(body);
    }

    /**
     * Handles PF7 (page backward). At the first page the browse cannot move back,
     * so the current page is redisplayed with {@value #MSG_NO_PREVIOUS_PAGES}
     * (COBOL {@code CCARD-AID-PFK07 AND CA-FIRST-PAGE}); otherwise the previous
     * page is displayed (COBOL {@code 9100-READ-BACKWARDS}).
     */
    private ResponseEntity<CardListResponse> handlePageBackward(CardListRequest request, int page) {
        int currentIndex = toPageIndex(page);
        Long accountFilter = parseAccountFilter(request.accountId());
        String cardFilter = request.cardId();
        if (currentIndex == 0) {
            Page<Card> firstPage = query(accountFilter, cardFilter, 0);
            return okResponse(firstPage, MSG_NO_PREVIOUS_PAGES);
        }
        Page<Card> previousPage = query(accountFilter, cardFilter, currentIndex - 1);
        return okResponse(previousPage, null);
    }

    /**
     * Handles PF8 (page forward). When a further page exists it is displayed
     * (COBOL {@code CCARD-AID-PFK08 AND CA-NEXT-PAGE-EXISTS} &rarr;
     * {@code 9000-READ-FORWARD}); when the browse is already at the end the last
     * populated page is redisplayed with {@value #MSG_NO_MORE_PAGES}. When there
     * are no records at all, the empty page is returned with the standard
     * no-records message.
     */
    private ResponseEntity<CardListResponse> handlePageForward(CardListRequest request, int page) {
        int currentIndex = toPageIndex(page);
        Long accountFilter = parseAccountFilter(request.accountId());
        String cardFilter = request.cardId();
        Page<Card> nextPage = query(accountFilter, cardFilter, currentIndex + 1);
        if (nextPage.getContent().isEmpty()) {
            Page<Card> currentPage = query(accountFilter, cardFilter, currentIndex);
            if (currentPage.getContent().isEmpty()) {
                // No records at all: fall through to the no-records message.
                return okResponse(currentPage, null);
            }
            return okResponse(currentPage, MSG_NO_MORE_PAGES);
        }
        return okResponse(nextPage, null);
    }

    /**
     * Handles any attention key other than Enter / PF3 / PF7 / PF8 (including a
     * missing key): redisplays the current page with {@value #MSG_INVALID_KEY}.
     * This re-expresses the COBOL invalid-key remap, which fell through to a
     * redisplay of the current page.
     */
    private ResponseEntity<CardListResponse> handleInvalidKey(CardListRequest request, int page) {
        int currentIndex = toPageIndex(page);
        Long accountFilter = parseAccountFilter(request.accountId());
        Page<Card> currentPage = query(accountFilter, request.cardId(), currentIndex);
        return okResponse(currentPage, MSG_INVALID_KEY);
    }

    // ==================================================================
    // Internal helpers
    // ==================================================================

    /**
     * Runs the card browse for the given filters and page index, always ordered
     * by card number ascending to preserve the VSAM key order. Delegates to
     * {@link CardService#listCards(Long, String, Pageable)}.
     */
    private Page<Card> query(Long accountFilter, String cardFilter, int pageIndex) {
        Pageable pageable = PageRequest.of(pageIndex, PAGE_SIZE, Sort.by(SORT_PROPERTY_CARD_NUM));
        return cardService.listCards(accountFilter, cardFilter, pageable);
    }

    /**
     * Wraps a browse page into a {@code 200 OK} response, applying the default
     * message policy via {@link #bodyWithDefaults(Page, String)}.
     */
    private ResponseEntity<CardListResponse> okResponse(Page<Card> page, String presetError) {
        return ResponseEntity.ok(bodyWithDefaults(page, presetError));
    }

    /**
     * Builds a {@link CardListResponse} from a browse page, deriving the info /
     * error message lines from the COBOL {@code 1400-SETUP-MESSAGE} policy:
     * <ul>
     *   <li>if {@code presetError} is supplied (a boundary / invalid-key / edit
     *       message) it is shown as the error line and no info line is set;</li>
     *   <li>otherwise, an empty page shows {@value #MSG_NO_RECORDS_FOUND} and a
     *       populated page shows the {@value #MSG_INFORM_REC_ACTIONS} info
     *       line.</li>
     * </ul>
     */
    private CardListResponse bodyWithDefaults(Page<Card> page, String presetError) {
        boolean empty = page.getContent().isEmpty();
        String errorMessage;
        String infoMessage;
        if (presetError != null) {
            errorMessage = presetError;
            infoMessage = null;
        } else if (empty) {
            errorMessage = MSG_NO_RECORDS_FOUND;
            infoMessage = null;
        } else {
            errorMessage = null;
            infoMessage = MSG_INFORM_REC_ACTIONS;
        }
        return cardMapper.toListResponse(
                page.getContent(),
                String.valueOf(page.getNumber() + 1),
                infoMessage,
                errorMessage,
                LocalDateTime.now(),
                TRANSACTION_NAME,
                TITLE_01,
                TITLE_02,
                PROGRAM_NAME);
    }

    /**
     * Converts a 1-based page indicator to a 0-based page index, flooring at the
     * first page so a missing or non-positive value yields page zero.
     */
    private static int toPageIndex(int oneBasedPage) {
        return Math.max(0, oneBasedPage - 1);
    }

    /**
     * Parses the optional account filter. A {@code null} or blank value means "no
     * account filter" and yields {@code null}; otherwise the value (guaranteed to
     * be at most eleven digits by the request's field-format contract) is parsed
     * to a {@link Long}. {@link CardService#listCards(Long, String, Pageable)}
     * treats a zero or out-of-range value as "no filter", so no range check is
     * duplicated here.
     */
    private static Long parseAccountFilter(String accountId) {
        if (accountId == null) {
            return null;
        }
        String trimmed = accountId.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return Long.valueOf(trimmed);
    }

    /**
     * Scans the seven per-row selection flags, reproducing COBOL
     * {@code 2250-EDIT-ARRAY}.
     *
     * <p>Blank flags ({@code null}, empty, or whitespace) are skipped. An
     * {@code S} marks a view request and a {@code U} marks an update request
     * (uppercase only, matching {@code SELECT-OK VALUES 'S','U'}); any other
     * non-blank flag is an invalid action. If more than one row is selected the
     * result carries {@value #MSG_MORE_THAN_ONE_ACTION} (which takes precedence,
     * mirroring the COBOL message latching); otherwise a single invalid flag
     * carries {@value #MSG_INVALID_ACTION_CODE}. Only the first
     * {@value #PAGE_SIZE} entries (the seven screen rows) are considered.</p>
     *
     * @param selections the per-row selection flags in screen order; may be
     *                   {@code null}
     * @return the resolved selection outcome
     */
    private static SelectionResult scanSelections(List<String> selections) {
        if (selections == null) {
            return SelectionResult.none();
        }
        int selectedIndex = -1;
        boolean update = false;
        int selectionCount = 0;
        boolean invalidFlagSeen = false;
        int limit = Math.min(selections.size(), PAGE_SIZE);
        for (int i = 0; i < limit; i++) {
            String raw = selections.get(i);
            if (raw == null) {
                continue;
            }
            String flag = raw.strip();
            if (flag.isEmpty()) {
                continue; // SELECT-BLANK
            }
            if (SELECT_VIEW.equals(flag)) {
                selectionCount++;
                selectedIndex = i;
                update = false;
            } else if (SELECT_UPDATE.equals(flag)) {
                selectionCount++;
                selectedIndex = i;
                update = true;
            } else {
                invalidFlagSeen = true; // OTHER -> INVALID ACTION CODE
            }
        }
        if (selectionCount > 1) {
            return SelectionResult.error(MSG_MORE_THAN_ONE_ACTION);
        }
        if (invalidFlagSeen) {
            return SelectionResult.error(MSG_INVALID_ACTION_CODE);
        }
        if (selectedIndex >= 0) {
            return SelectionResult.selected(selectedIndex, update);
        }
        return SelectionResult.none();
    }

    /**
     * Immutable outcome of scanning the per-row selection flags.
     *
     * @param index  the 0-based index of the single selected row, or {@code -1}
     *               when no valid row is selected
     * @param update {@code true} when the selection requested the update screen
     *               ({@code U}); {@code false} for the view screen ({@code S}) or
     *               when no row is selected
     * @param error  the edit-failure message to display, or {@code null} when the
     *               selection is valid (including the no-selection case)
     */
    private record SelectionResult(int index, boolean update, String error) {

        /** No row was selected and no edit failed. */
        private static SelectionResult none() {
            return new SelectionResult(-1, false, null);
        }

        /** Exactly one valid row was selected. */
        private static SelectionResult selected(int index, boolean update) {
            return new SelectionResult(index, update, null);
        }

        /** A selection edit failed with the given message. */
        private static SelectionResult error(String error) {
            return new SelectionResult(-1, false, error);
        }
    }
}
