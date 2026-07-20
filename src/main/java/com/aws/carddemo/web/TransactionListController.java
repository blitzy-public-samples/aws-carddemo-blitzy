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
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.aws.carddemo.domain.Transaction;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.dto.TransactionListRequest;
import com.aws.carddemo.dto.TransactionListResponse;
import com.aws.carddemo.mapper.TransactionMapper;
import com.aws.carddemo.service.TransactionService;

import jakarta.validation.Valid;

/**
 * REST controller for the CardDemo <strong>Transaction List</strong> screen &mdash; the
 * Java re-platform of the online COBOL program {@code COTRN00C} (CICS transaction
 * {@code CT00}, BMS map {@code COTRN00}), relocated during the migration to
 * {@code legacy/cbl/COTRN00C.cbl}.
 *
 * <p>The legacy program is a paged browse of the {@code TRANSACT} VSAM KSDS in
 * {@code TRAN-ID} (primary-key) order, ten rows per page, with a transaction-id
 * search key and a per-row single-character selection marker. This controller
 * preserves every observable behavior of that screen while re-expressing the 3270
 * field contract as request/response DTOs (there is no terminal emulator; see AAP
 * &sect;0.3.3).</p>
 *
 * <h2>Preserved behavior (COTRN00C {@code MAIN-PARA} / {@code PROCESS-ENTER-KEY},
 * L100&ndash;L215)</h2>
 * <ul>
 *   <li>Ten selectable rows ({@code SEL0001I}&hellip;{@code SEL0010I}). A row marked
 *       {@code 'S'}/{@code 's'} navigates (legacy {@code XCTL}) to the transaction-view
 *       program {@code COTRN01C} ({@code CT01}) carrying the selected transaction id. Any
 *       other non-blank marker yields the message
 *       <em>"Invalid selection. Valid value is S"</em> on the same screen.</li>
 *   <li>The transaction-id search key must be numeric; a non-numeric value yields
 *       <em>"Tran ID must be Numeric ..."</em> on the same screen.</li>
 *   <li>{@code PF7} pages backward (with a top-of-page boundary message), {@code PF8}
 *       pages forward (with a bottom-of-page boundary message), and {@code PF3} returns to
 *       the main menu {@code COMEN01C} ({@code CM00}). Any other attention key yields the
 *       shared invalid-key message ({@code CCDA-MSG-INVALID-KEY}).</li>
 * </ul>
 *
 * <h2>Pseudo-conversational &rarr; stateless translation (AAP &sect;0.7.1 H1)</h2>
 * The COBOL program is pseudo-conversational: it {@code RECEIVE}s the map, routes on
 * {@code EIBAID}, and either re-{@code SEND}s the map (with an optional {@code WS-MESSAGE})
 * or {@code XCTL}s to the next program, carrying its state in the {@code CARDDEMO-COMMAREA}.
 * There is no COMMAREA here, so:
 * <ul>
 *   <li>the first screen display is a {@code GET} that returns the first page;</li>
 *   <li>each subsequent key press is a stateless {@code POST} whose
 *       {@link TransactionListRequest#action() action} is the translated attention key;</li>
 *   <li>the <em>current page</em> the operator is viewing &mdash; state the COBOL kept in
 *       {@code CDEMO-CT00-PAGE-NUM} &mdash; is carried by the {@code page} query parameter,
 *       because {@link TransactionListRequest} models only the 3270 <em>unprotected</em>
 *       input fields and therefore has no page field; and</li>
 *   <li>{@code XCTL} navigation becomes response headers ({@code X-CardDemo-Next-Program},
 *       {@code X-CardDemo-Next-Transaction} and, for a row selection,
 *       {@code X-CardDemo-Selected-Transaction-Id}) that tell the client which screen to
 *       enter next; the body remains a {@link TransactionListResponse} so the endpoint
 *       contract is uniform.</li>
 * </ul>
 *
 * <h2>Browse semantics (AAP &sect;0.7.1 H5)</h2>
 * The COBOL {@code STARTBR}/{@code READNEXT}/{@code READPREV} key-ordered browse is
 * reproduced by {@link TransactionService#listTransactions(org.springframework.data.domain.Pageable)},
 * the confirmed faithful {@code COTRN00C} browse that returns all rows in ascending
 * {@code tranId} order. (The service's card-scoped, processing-timestamp-ordered overload
 * {@code listTransactions(String, Pageable)} is intentionally <em>not</em> used here: it is
 * the browse for other screens and does not match the {@code COTRN00C} primary-key browse.)
 * The transaction-id search key is validated for the numeric contract exactly as the legacy
 * program did; because the transaction service exposes a key-ordered paged browse rather
 * than a start-key reposition, a valid or empty key displays the first page from the start
 * of the browse. This preserves the caller-visible message contract while mapping the VSAM
 * reposition onto the available paged query.
 *
 * <h2>Design constraints</h2>
 * Constructor injection only (no field injection, no Lombok). The controller is a thin
 * routing/mapping layer: all querying lives in {@link TransactionService} and all field
 * projection in {@link TransactionMapper}; monetary amounts are carried as
 * {@code BigDecimal} by the DTO layer and are never handled as binary floating point here.
 * The transaction list has no keyed lookup, so no not-found handling (and therefore no
 * {@code try}/{@code catch}) is required on these endpoints.
 *
 * @see TransactionService
 * @see TransactionMapper
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transaction List",
        description = "Transaction List screen (COBOL COTRN00C / CICS transaction CT00): browse transactions chronologically with PF7/PF8 paging.")
public class TransactionListController {

    /** Fixed page size &mdash; the ten row slots ({@code SEL0001}..{@code SEL0010}) of map {@code COTRN00}. */
    private static final int PAGE_SIZE = 10;

    /**
     * Largest zero-based page index that keeps the repository offset
     * ({@code index * PAGE_SIZE}) within {@code int} range, guarding against the
     * {@code InvalidDataAccessApiUsageException} a pathologically large page value
     * would otherwise trigger (QA finding F-P4-C). A requested page beyond this
     * bound is clamped so it degrades to an out-of-range browse that returns an
     * empty page &mdash; a bounded, same-screen outcome &mdash; rather than an
     * HTTP 500. Every page reachable by ordinary PF7/PF8 navigation is far below
     * this ceiling, so legitimate paging is unaffected.
     */
    private static final int MAX_PAGE_INDEX = Integer.MAX_VALUE / PAGE_SIZE;

    /**
     * Sort property for the browse: the {@link Transaction} primary key, reproducing the
     * {@code COTRN00C} {@code TRAN-ID}-ordered VSAM browse.
     */
    private static final String SORT_PROPERTY = "tranId";

    /** The single valid row-selection marker ({@code 'S'}), matched case-insensitively as the COBOL did ({@code 'S'}/{@code 's'}). */
    private static final String SELECT_FLAG = "S";

    /** Row-selection navigation target program: the transaction-view program {@code COTRN01C}. */
    private static final String VIEW_PROGRAM = "COTRN01C";

    /** Row-selection navigation target transaction: {@code CT01}. */
    private static final String VIEW_TRANSACTION = "CT01";

    /** {@code PF3} (back) navigation target program: the main menu {@code COMEN01C}. */
    private static final String BACK_PROGRAM = "COMEN01C";

    /** {@code PF3} (back) navigation target transaction: {@code CM00}. */
    private static final String BACK_TRANSACTION = "CM00";

    /** Response header naming the COBOL program the client should enter next (the {@code XCTL} target). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Response header naming the CICS transaction id the client should enter next. */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** Response header carrying the selected 16-character transaction id for the view screen. */
    private static final String HEADER_SELECTED_TRANSACTION_ID = "X-CardDemo-Selected-Transaction-Id";

    /** Message when a row marker other than {@code 'S'} is used ({@code COTRN00C} L199). */
    private static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /** Message when the search key is present but not numeric ({@code COTRN00C} L214). */
    private static final String MSG_TRAN_ID_NUMERIC = "Tran ID must be Numeric ...";

    /** Shared invalid-key message ({@code CCDA-MSG-INVALID-KEY}, {@code CSMSG01Y.cpy}), trimmed. */
    private static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /** Boundary message when paging backward from the first page ({@code COTRN00C} L248). */
    private static final String MSG_TOP_OF_PAGE = "You are already at the top of the page...";

    /** Boundary message when paging forward past the last page ({@code COTRN00C} L270). */
    private static final String MSG_BOTTOM_OF_PAGE = "You are already at the bottom of the page...";

    /** Browse service over the {@code TRANSACT} store ({@code COTRN00C} file operations). */
    private final TransactionService transactionService;

    /** Hand-written mapper that projects {@link Transaction} rows onto the list response DTO. */
    private final TransactionMapper transactionMapper;

    /**
     * Creates the controller with its collaborators via constructor injection.
     *
     * @param transactionService the transaction browse service; must not be {@code null}
     * @param transactionMapper  the entity&harr;DTO mapper; must not be {@code null}
     */
    public TransactionListController(TransactionService transactionService,
                                     TransactionMapper transactionMapper) {
        this.transactionService = transactionService;
        this.transactionMapper = transactionMapper;
    }

    /**
     * First display of the transaction-list screen &mdash; the entry equivalent to the
     * COBOL first (non-reenter) invocation, which shows page one of the browse.
     *
     * @return {@code 200 OK} with the first page (up to ten rows) in {@code tranId} order
     */
    @GetMapping
    @Operation(summary = "First page of the Transaction List",
            description = "First-entry screen listing the initial page of transactions (COBOL COTRN00C initial browse).")
    public ResponseEntity<TransactionListResponse> firstPage() {
        return ResponseEntity.ok(body(query(1, null), null));
    }

    /**
     * Handles a screen submission &mdash; the reentry path of {@code COTRN00C}
     * {@code MAIN-PARA}, routing on the transmitted attention key
     * ({@link TransactionListRequest#action()}).
     *
     * @param request the submitted screen fields (search key, row markers, attention key)
     * @param page    the one-based page the operator is currently viewing (state the COBOL
     *                held in {@code CDEMO-CT00-PAGE-NUM}); defaults to the first page
     * @return {@code 200 OK} with the resulting page and any message; navigation keys add
     *         the {@code X-CardDemo-Next-*} headers naming the screen to enter next
     */
    @PostMapping
    @Operation(summary = "Page the Transaction List",
            description = "Reproduces the COTRN00C EVALUATE EIBAID paging: PF7/PF8 browse the transaction list backward and forward.")
    public ResponseEntity<TransactionListResponse> list(
            @Valid @RequestBody TransactionListRequest request,
            @RequestParam(name = "page", defaultValue = "1") int page) {

        int currentPage = Math.max(1, page);
        PfKeyAction action = request.action();
        // The (numeric) search key repositions the browse and is carried across page turns
        // so PF7/PF8 page within the repositioned browse (COTRN00C reenter behavior).
        String startKey = startKeyOf(request.transactionId());

        if (action == PfKeyAction.ENTER) {
            return handleEnter(request, currentPage);
        }
        if (action == PfKeyAction.PF3) {
            return handleBack(currentPage, startKey);
        }
        if (action == PfKeyAction.PF7) {
            return handlePageBackward(currentPage, startKey);
        }
        if (action == PfKeyAction.PF8) {
            return handlePageForward(currentPage, startKey);
        }
        // COBOL EVALUATE EIBAID ... WHEN OTHER: any other (or absent) key is invalid.
        return ResponseEntity.ok(body(query(currentPage, startKey), MSG_INVALID_KEY));
    }

    /**
     * Reproduces {@code PROCESS-ENTER-KEY}: evaluate a row selection first, then the search
     * key, then (re)display the first page.
     *
     * <p>Evaluation order and short-circuiting mirror the COBOL exactly:</p>
     * <ol>
     *   <li>The first non-blank row marker is examined. It is honored only when the marked
     *       row actually carries a transaction id (the COBOL guard
     *       {@code TRN-SEL-FLG NOT = SPACES AND TRN-SELECTED NOT = SPACES}); the id is read
     *       from the current page because the request carries only the markers, not the
     *       protected id fields. An {@code 'S'}/{@code 's'} marker navigates to the view
     *       screen; any other marker sets the invalid-selection message and processing
     *       continues.</li>
     *   <li>A present-but-non-numeric search key sets the numeric message and, exactly as
     *       the COBOL error flag suppresses {@code PROCESS-PAGE-FORWARD}, the page is not
     *       advanced (the current page is redisplayed).</li>
     *   <li>Otherwise the first page is displayed, carrying any invalid-selection message.</li>
     * </ol>
     *
     * @param request     the submitted screen fields
     * @param currentPage the one-based page currently displayed
     * @return the response for the selection/search outcome
     */
    private ResponseEntity<TransactionListResponse> handleEnter(TransactionListRequest request, int currentPage) {
        String errorMessage = null;
        // A numeric TRNIDIN search key repositions the browse (STARTBR RIDFLD(TRAN-ID));
        // a null/blank or non-numeric key means browse from the first transaction.
        String startKey = startKeyOf(request.transactionId());

        int selectedIndex = firstSelectionIndex(request.rowSelections());
        if (selectedIndex >= 0) {
            Page<Transaction> current = query(currentPage, startKey);
            List<Transaction> rows = current.getContent();
            String selectedId = (selectedIndex < rows.size()) ? rows.get(selectedIndex).getTranId() : null;
            if (selectedId != null && !selectedId.isBlank()) {
                String marker = request.rowSelections().get(selectedIndex).trim();
                if (marker.equalsIgnoreCase(SELECT_FLAG)) {
                    // 'S'/'s' -> XCTL COTRN01C carrying the selected transaction id.
                    return ResponseEntity.ok()
                            .header(HEADER_NEXT_PROGRAM, VIEW_PROGRAM)
                            .header(HEADER_NEXT_TRANSACTION, VIEW_TRANSACTION)
                            .header(HEADER_SELECTED_TRANSACTION_ID, selectedId)
                            .body(body(current, null));
                }
                errorMessage = MSG_INVALID_SELECTION;
            }
        }

        String searchKey = request.transactionId();
        if (searchKey != null) {
            String trimmed = searchKey.trim();
            if (!trimmed.isEmpty() && !isAllDigits(trimmed)) {
                // Non-numeric search key: message only; the browse is not repositioned or advanced.
                return ResponseEntity.ok(body(query(currentPage, null), MSG_TRAN_ID_NUMERIC));
            }
        }

        // Valid or empty search key: display the first page, repositioned at the supplied
        // start key (COTRN00C STARTBR RIDFLD(TRAN-ID)) or from the first row when none was given.
        return ResponseEntity.ok(body(query(1, startKey), errorMessage));
    }

    /**
     * Reproduces the {@code PF3} branch: return to the main menu {@code COMEN01C}
     * ({@code CM00}). The navigation is signalled through the {@code X-CardDemo-Next-*}
     * headers; the body redisplays the current page so the endpoint contract stays uniform.
     *
     * @param currentPage the one-based page currently displayed
     * @param startKey    the active transaction-id start key, or {@code null} when unfiltered
     * @return {@code 200 OK} with back-navigation headers and the current page
     */
    private ResponseEntity<TransactionListResponse> handleBack(int currentPage, String startKey) {
        return ResponseEntity.ok()
                .header(HEADER_NEXT_PROGRAM, BACK_PROGRAM)
                .header(HEADER_NEXT_TRANSACTION, BACK_TRANSACTION)
                .body(body(query(currentPage, startKey), null));
    }

    /**
     * Reproduces {@code PROCESS-PF7-KEY}: page backward. When already on the first page the
     * page is unchanged and the top-of-page boundary message is shown. Any active start key
     * is preserved so paging stays within the repositioned browse.
     *
     * @param currentPage the one-based page currently displayed
     * @param startKey    the active transaction-id start key, or {@code null} when unfiltered
     * @return the previous page, or the first page with the top-of-page message
     */
    private ResponseEntity<TransactionListResponse> handlePageBackward(int currentPage, String startKey) {
        if (currentPage > 1) {
            return ResponseEntity.ok(body(query(currentPage - 1, startKey), null));
        }
        return ResponseEntity.ok(body(query(1, startKey), MSG_TOP_OF_PAGE));
    }

    /**
     * Reproduces {@code PROCESS-PF8-KEY}: page forward. When there is no next page (the
     * legacy {@code NEXT-PAGE-NO} state, mapped to {@link Page#hasNext()}) the page is
     * unchanged and the bottom-of-page boundary message is shown. Any active start key is
     * preserved so paging stays within the repositioned browse.
     *
     * @param currentPage the one-based page currently displayed
     * @param startKey    the active transaction-id start key, or {@code null} when unfiltered
     * @return the next page, or the current page with the bottom-of-page message
     */
    private ResponseEntity<TransactionListResponse> handlePageForward(int currentPage, String startKey) {
        Page<Transaction> current = query(currentPage, startKey);
        if (current.hasNext()) {
            return ResponseEntity.ok(body(query(currentPage + 1, startKey), null));
        }
        return ResponseEntity.ok(body(current, MSG_BOTTOM_OF_PAGE));
    }

    /**
     * Executes the key-ordered paged browse for a one-based page number, reproducing the
     * {@code COTRN00C} {@code TRAN-ID}-ordered VSAM browse via
     * {@link TransactionService#listTransactionsFrom(String, org.springframework.data.domain.Pageable)}.
     *
     * <p>When {@code startKey} is a (numeric) transaction id the browse is repositioned at
     * the first record at or after that key ({@code STARTBR ... RIDFLD(TRAN-ID)}); when it
     * is {@code null} the browse begins at the first transaction. The {@code startKey}
     * context is threaded through every page turn so that, once repositioned, PF7/PF8 page
     * <em>within</em> the repositioned browse exactly as the legacy screen did.</p>
     *
     * @param oneBasedPage the one-based page number (values below one are clamped to the first page)
     * @param startKey     the inclusive transaction-id start key, or {@code null} to browse from the first row
     * @return the requested page of transactions in ascending {@code tranId} order; never {@code null}
     */
    private Page<Transaction> query(int oneBasedPage, String startKey) {
        // Floor at the first page and ceiling at MAX_PAGE_INDEX so an oversized page value cannot
        // overflow the repository offset (F-P4-C); a clamped page browses past the end and yields
        // an empty page (a bounded same-screen result) rather than an HTTP 500.
        int zeroBasedPage = Math.min(Math.max(0, oneBasedPage - 1), MAX_PAGE_INDEX);
        return transactionService.listTransactionsFrom(startKey,
                PageRequest.of(zeroBasedPage, PAGE_SIZE, Sort.by(SORT_PROPERTY)));
    }

    /**
     * Builds the transaction-list response body from a page of results, deriving the
     * one-based {@code pageNumber} from the returned {@link Page} and delegating field
     * projection (header, rows, message) to {@link TransactionMapper}.
     *
     * @param page         the page of transactions to render; must not be {@code null}
     * @param errorMessage the message-line text, or {@code null} when there is no message
     * @return the fully populated {@link TransactionListResponse}
     */
    private TransactionListResponse body(Page<Transaction> page, String errorMessage) {
        String pageNumber = String.valueOf(page.getNumber() + 1);
        return transactionMapper.toListResponse(page.getContent(), pageNumber, errorMessage, LocalDateTime.now());
    }

    /**
     * Finds the index of the first non-blank row marker, reproducing the COBOL
     * {@code EVALUATE TRUE} that scans {@code SEL0001I}&hellip;{@code SEL0010I} in order and
     * acts on the first populated slot. The scan is bounded by the ten fixed row slots.
     *
     * @param selections the ordered per-row markers; may be {@code null}
     * @return the zero-based index of the first non-blank marker, or {@code -1} if none
     */
    private static int firstSelectionIndex(List<String> selections) {
        if (selections == null) {
            return -1;
        }
        int limit = Math.min(selections.size(), PAGE_SIZE);
        for (int i = 0; i < limit; i++) {
            String marker = selections.get(i);
            if (marker != null && !marker.isBlank()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reproduces the COBOL {@code IS NUMERIC} class test: the value is numeric only when it
     * is non-empty and every character is an ASCII digit.
     *
     * @param value the value to test; must be non-{@code null}
     * @return {@code true} if {@code value} is a non-empty run of ASCII digits
     */
    private static boolean isAllDigits(String value) {
        if (value.isEmpty()) {
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
     * Extracts a usable transaction-id start key from the submitted search field: the
     * trimmed value when it is a non-empty run of digits (COBOL {@code TRNIDIN IS
     * NUMERIC}), otherwise {@code null}. A {@code null}/blank or non-numeric field means
     * "do not reposition" &mdash; the browse begins at the first transaction &mdash; so a
     * non-numeric residual key carried on a page-turn cannot corrupt the browse (the ENTER
     * path separately surfaces {@link #MSG_TRAN_ID_NUMERIC} for a non-numeric key).
     *
     * @param raw the raw {@code transactionId} search field; may be {@code null}
     * @return the trimmed numeric start key, or {@code null} when absent or non-numeric
     */
    private static String startKeyOf(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return (!trimmed.isEmpty() && isAllDigits(trimmed)) ? trimmed : null;
    }
}
