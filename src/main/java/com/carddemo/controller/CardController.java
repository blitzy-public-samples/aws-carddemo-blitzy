/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.controller;

import com.carddemo.dto.card.CardDto;
import com.carddemo.dto.card.CardListResponse;
import com.carddemo.service.CardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Credit-card list, view, and update REST endpoints &mdash; the stateless replacement for the
 * three legacy CICS online card programs, organized by functional domain (AAP &sect;0.4.1.1):
 *
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl} (card list, TRANID {@code CCLI}) &mdash; browsed the
 *       {@code CARDDAT} master through the {@code CARDAIX} alternate index by account id, paging
 *       seven rows at a time ({@code WS-MAX-SCREEN-LINES VALUE 7}, COCRDLIC line 177) with
 *       {@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR} and PF7/PF8 navigation
 *       &rarr; {@link #listCardsByAccount(Long, Pageable)};</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} (card view, TRANID {@code CCDL}) &mdash; a card-number keyed
 *       read of {@code CARDDAT} ({@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(card-num)})
 *       &rarr; {@link #getCard(String)};</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} (card update, TRANID {@code CCUP}) &mdash; a
 *       {@code READ UPDATE}/{@code REWRITE} of {@code CARDDAT} (COCRDUPC line 1478) after
 *       field-level editing &rarr; {@link #updateCard(String, CardDto)}.</li>
 * </ul>
 *
 * <h2>Data model</h2>
 * <p>The COBOL record layout is the 150-byte {@code CARD-RECORD} from {@code app/cpy/CVACT02Y.cpy}
 * ({@code CARD-NUM PIC X(16)}, {@code CARD-ACCT-ID PIC 9(11)}, {@code CARD-CVV-CD PIC 9(03)},
 * {@code CARD-EMBOSSED-NAME PIC X(50)}, {@code CARD-EXPIRAION-DATE PIC X(10)},
 * {@code CARD-ACTIVE-STATUS PIC X(01)}). The {@code CARDAIX} alternate index on
 * {@code CARD-ACCT-ID} is replaced by the JPA
 * {@code @Index(name="idx_card_account_id", columnList="account_id")} on the {@code Card} entity
 * and surfaced through {@code CardRepository.findByAccountId(...)} (AAP &sect;0.6.2, &sect;0.6.13).
 * Card numbers ({@code CARD-NUM PIC X(16)}) and CVV codes ({@code CARD-CVV-CD PIC 9(03)}) are
 * carried as {@code String} on {@link CardDto} to preserve leading zeros (PR-13); the COBOL typo
 * {@code CARD-EXPIRAION-DATE} is corrected to {@code expirationDate} on the DTO (PR-14).</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/accounts/{acctId}/cards} &mdash; list the cards for an account, paginated;
 *       {@code 200 OK} with a {@link CardListResponse} (replaces the COCRDLIC {@code CARDAIX}
 *       browse; PF7/PF8 paging becomes the stateless {@code page}/{@code size} request parameters,
 *       AAP &sect;0.6.1). An empty result is a valid {@code 200} with an empty page &mdash; NOT a
 *       {@code 404}.</li>
 *   <li>{@code GET /api/cards/{cardNum}} &mdash; retrieve one card by its 16-digit number;
 *       {@code 200 OK} with a {@link CardDto} (replaces COCRDSLC).</li>
 *   <li>{@code PUT /api/cards/{cardNum}} &mdash; update an existing card; {@code 200 OK} with the
 *       refreshed {@link CardDto}. Optimistic locking via JPA {@code @Version} surfaces concurrent
 *       modifications as {@code 409 Conflict} (replaces COCRDUPC; PR-22).</li>
 * </ul>
 *
 * <h2>Mixed base paths (no class-level {@code @RequestMapping})</h2>
 * <p>The list endpoint is account-scoped ({@code /api/accounts/{acctId}/cards}) while the
 * view/update endpoints are card-scoped ({@code /api/cards/{cardNum}}); because the two route
 * families do not share a common prefix beyond {@code /api}, each method declares its full path and
 * the class intentionally carries <strong>no</strong> class-level {@code @RequestMapping}.</p>
 *
 * <h2>Thin HTTP boundary (business logic lives in {@link CardService})</h2>
 * <p>This controller is a thin, stateless HTTP adapter. All card business semantics &mdash; the
 * COCRDLIC cross-reference existence check and paged browse, the COCRDSLC card-number input edits
 * and keyed read, the COCRDUPC change-detection, field editing and optimistic-lock handling, and
 * every preserved COBOL diagnostic message literal &mdash; live in {@link CardService}. The
 * controller performs only boundary input edits (the {@code acctId} {@code @Min}/{@code @Max} range
 * and the 16-digit {@code cardNum} {@code @Pattern}) and delegates everything else. The exact COBOL
 * messages reach the client through {@code com.carddemo.controller.advice.GlobalExceptionHandler},
 * which maps:</p>
 * <ul>
 *   <li>{@code ConstraintViolationException} (path-variable edits: {@code acctId} out of the
 *       1..99999999999 range, or a non-16-digit {@code cardNum} carrying the COCRDSLC line&nbsp;149
 *       message {@code "Card number if supplied must be a 16 digit number"}) &rarr;
 *       {@code 400 Bad Request};</li>
 *   <li>{@code MethodArgumentNotValidException} ({@code @Valid} {@link CardDto} body edits) &rarr;
 *       {@code 400 Bad Request};</li>
 *   <li>{@code MethodArgumentTypeMismatchException} (a non-numeric {@code acctId} path segment)
 *       &rarr; {@code 400 Bad Request};</li>
 *   <li>{@code IllegalArgumentException} (COCRDSLC/COCRDUPC field edits raised by the service)
 *       &rarr; {@code 400 Bad Request};</li>
 *   <li>{@code InvalidCardException} (COCRDLIC "NO RECORDS FOUND FOR THIS SEARCH CONDITION.",
 *       line&nbsp;122) &rarr; {@code 400 Bad Request};</li>
 *   <li>{@code AccountNotFoundException} (account absent from the card cross-reference, COCRDLIC;
 *       card number not found, COCRDSLC line&nbsp;154; card absent on update, COCRDUPC
 *       line&nbsp;202) &rarr; {@code 404 Not Found};</li>
 *   <li>{@code IllegalStateException} (COCRDUPC "No change detected with respect to values
 *       fetched.", line&nbsp;188) &rarr; {@code 422 Unprocessable Entity};</li>
 *   <li>{@code ObjectOptimisticLockingFailureException} (COCRDUPC "Record changed by some one else.
 *       Please review", line&nbsp;208) &rarr; {@code 409 Conflict} (PR-22).</li>
 * </ul>
 *
 * <h2>Authorization</h2>
 * <p>Intentionally <strong>no</strong> class-level {@code @PreAuthorize}: any
 * <em>authenticated</em> caller (USER or ADMIN) may list, view, and update cards, exactly as the
 * legacy card transactions were reachable from the regular user menu ({@code COMEN01C}). The
 * requirement that the caller be authenticated at all is enforced by the application
 * {@code SecurityFilterChain} (see {@code com.carddemo.security.SecurityConfig}); anonymous
 * requests are rejected with {@code 401} before reaching these methods. The COCRDLIC
 * admin-sees-all / user-sees-own scoping is a {@link CardService} concern, not a controller one
 * (AAP &sect;0.7.2 &mdash; no feature additions).</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-13</b> &mdash; {@code acctId} validated as an 11-digit non-zero numeric
 *       ({@code @Min(1)}/{@code @Max(99999999999L)}) mirroring {@code CARD-ACCT-ID PIC 9(11)}; the
 *       16-digit {@code cardNum} ({@code CARD-NUM PIC X(16)}) is validated with
 *       {@code @Pattern("\\d{16}")} preserving the COCRDSLC format message and is typed as
 *       {@code String} so leading zeros survive.</li>
 *   <li><b>PR-22</b> &mdash; the {@code Card} entity carries {@code @Version}; a concurrent update
 *       surfaces as {@code ObjectOptimisticLockingFailureException} mapped to {@code 409 Conflict}
 *       by {@code GlobalExceptionHandler}.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 namespace only ({@code jakarta.validation.*}); no
 *       {@code javax.*} imports.</li>
 *   <li><b>PR-29</b> &mdash; constructor injection only, via Lombok {@link RequiredArgsConstructor}
 *       over the {@code final} {@link CardService} field; no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * <h2>PCI hygiene</h2>
 * <p>The full Primary Account Number (PAN) is never written to logs. All log statements that
 * reference a card number route it through {@link #maskCardNumber(String)} so only the last four
 * digits are emitted; request/response bodies carry the PAN but are never logged.</p>
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (CVACT02Y card record layout).</p>
 *
 * @see CardService the service that performs the COCRDLIC/COCRDSLC/COCRDUPC logic
 * @see CardDto the card view/update payload
 * @see CardListResponse the paginated card-list payload
 * @see com.carddemo.controller.advice.GlobalExceptionHandler exception-to-HTTP-status mapping
 * @since 1.0
 */
@RestController
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Credit Card", description = "Credit card endpoints (replaces COCRDLIC, COCRDSLC, COCRDUPC)")
@SecurityRequirement(name = "bearerAuth")
public class CardController {

    /**
     * Card business service replacing the COCRDLIC (list), COCRDSLC (view), and COCRDUPC (update)
     * online programs. Injected by type through the Lombok-generated constructor (PR-29). Every
     * handler returns a DTO ({@link CardDto} / {@link CardListResponse}), so this controller never
     * touches the JPA entity directly.
     */
    private final CardService cardService;

    /**
     * Lists the credit cards belonging to an account, one page at a time &mdash; the REST
     * replacement for {@code app/cbl/COCRDLIC.cbl} (TRANID {@code CCLI}).
     *
     * <p>The COCRDLIC program browsed {@code CARDDAT} through the {@code CARDAIX} alternate index by
     * account id, displaying at most {@code WS-MAX-SCREEN-LINES VALUE 7} rows per 3270 screen with
     * PF7 (page-up) / PF8 (page-down) navigation. That stateful browse cursor is replaced by
     * stateless Spring Data {@link Pageable} pagination (AAP &sect;0.6.1): each request recomputes
     * its window from the {@code page}/{@code size} request parameters with no server-side cursor
     * lifecycle. The {@link PageableDefault} default size of {@code 7} preserves the COCRDLIC
     * seven-row screen. The result order is <strong>always {@code cardNum} ascending</strong> &mdash;
     * {@link CardService#listByAccount(Long, int, int)} enforces this canonical order server-side (it
     * builds the page request with an explicit {@code Sort.by("cardNum").ascending()}), so the window
     * is deterministic and stable, faithfully replacing the ordered {@code CARDDATA.AIX} browse. The
     * order is therefore not client-overridable; this is intentional parity with COCRDLIC (which only
     * ever browsed in {@code CARDAIX} order) and the review finding F2 fix.</p>
     *
     * <p>The cross-reference existence check, the paged browse, and the preserved COBOL messages are
     * delegated entirely to {@link CardService#listByAccount(Long, int, int)}:</p>
     * <ul>
     *   <li>account absent from the card cross-reference &rarr; {@code AccountNotFoundException}
     *       (mapped to {@code 404});</li>
     *   <li>cross-reference lists the account but the master returns no rows on the first page
     *       &rarr; {@code InvalidCardException} (COCRDLIC "NO RECORDS FOUND FOR THIS SEARCH
     *       CONDITION.", line&nbsp;122; mapped to {@code 400});</li>
     *   <li>paging past the end &rarr; an empty {@link CardListResponse} with {@code 200 OK} (an
     *       empty page is a valid result, never a {@code 404}).</li>
     * </ul>
     *
     * <p>Validation runs during argument binding (PR-13): the class-level {@code @Validated} causes
     * the {@link Min @Min(1)} / {@link Max @Max(99999999999L)} constraints on {@code acctId} to be
     * enforced, so a zero, negative, or greater-than-11-digit account id is rejected with
     * {@code 400 Bad Request} and the exact COBOL message
     * {@code "Account number must be a non zero 11 digit number"}; a non-numeric path segment is
     * rejected with {@code 400} via {@code MethodArgumentTypeMismatchException}.</p>
     *
     * @param acctId   the account primary key, mirroring {@code CARD-ACCT-ID PIC 9(11)}; must be a
     *                 non-zero 11-digit number ({@code 1 .. 99999999999})
     * @param pageable the pagination request bound from the {@code page}/{@code size} parameters;
     *                 defaults to page&nbsp;0, size&nbsp;7. Only the page index and size are used: the
     *                 result order is fixed to {@code cardNum} ascending by the service (the
     *                 deterministic {@code CARDDATA.AIX} replacement), so any client-supplied
     *                 {@code sort} component is intentionally not honored
     * @return {@code 200 OK} with the requested page of cards as a {@link CardListResponse}
     */
    @GetMapping("/api/accounts/{acctId}/cards")
    @Operation(
            summary = "List cards for an account (paginated)",
            description = "Returns a paginated list of credit cards for the given account. "
                    + "Default page size is 7 (matches BMS COCRDLI 7-row display). "
                    + "Replaces COCRDLIC CICS card list (TRANID=CCLI) and CARDDATA.AIX browse. "
                    + "Pagination uses Spring Data Pageable (page, size) instead of the PF7/PF8 "
                    + "cursor; results are always sorted by cardNum ascending (enforced server-side, "
                    + "the deterministic CARDDATA.AIX order). An empty result is a valid 200 with an "
                    + "empty list, not a 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cards returned (possibly empty list)"),
            @ApiResponse(responseCode = "400", description = "Invalid account ID format, or no card rows for a cross-referenced account"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Account not found in card cross-reference"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<CardListResponse> listCardsByAccount(
            @Parameter(description = "11-digit non-zero account ID", example = "00000000001")
            @PathVariable("acctId")
            @Min(value = 1L, message = "Account number must be a non zero 11 digit number")
            @Max(value = 99999999999L, message = "Account number must be a non zero 11 digit number")
            Long acctId,
            @Parameter(description = "Pagination parameters (page=0-indexed, size). Results are always "
                    + "sorted by cardNum ascending, enforced server-side; a client sort is not honored.")
            @PageableDefault(size = 7, sort = "cardNum", direction = Sort.Direction.ASC)
            Pageable pageable) {

        // DEBUG only; the CardListResponse body carries the (account-scoped) card data, never logged.
        log.debug("GET /api/accounts/{}/cards page={} size={}",
                acctId, pageable.getPageNumber(), pageable.getPageSize());

        // Delegate to the service. The service signature is (acctId, page, size); the stateless
        // Pageable is destructured into its zero-based page index and size, replacing the COCRDLIC
        // PF7/PF8 cursor. The service itself enforces the deterministic cardNum-ascending order (it
        // builds the PageRequest with Sort.by("cardNum").ascending()), so the canonical CARDDATA.AIX
        // browse order is guaranteed regardless of the page/size passed here (F2). The service performs
        // the cross-reference existence check and the paged browse, and returns a CardListResponse
        // (empty page => empty list, still 200).
        CardListResponse response = cardService.listByAccount(
                acctId, pageable.getPageNumber(), pageable.getPageSize());
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves a single card by its 16-digit card number &mdash; the REST replacement for
     * {@code app/cbl/COCRDSLC.cbl} (TRANID {@code CCDL}).
     *
     * <p>The COCRDSLC program read {@code CARDDAT} keyed by card number
     * ({@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(card-num)}). The 16-digit format edit is
     * enforced at the boundary by {@code @Pattern("\\d{16}")}, preserving the COCRDSLC line&nbsp;149
     * message {@code "Card number if supplied must be a 16 digit number"} (surfaced as
     * {@code 400 Bad Request} by {@code GlobalExceptionHandler}); a not-found card raises
     * {@code AccountNotFoundException} from the service and maps to {@code 404}. The
     * {@code cardNum} is typed as {@code String} so a leading-zero PAN survives intact (PR-13).</p>
     *
     * @param cardNum the 16-digit card number primary key ({@code CARD-NUM PIC X(16)})
     * @return {@code 200 OK} with the card as a {@link CardDto}
     */
    @GetMapping("/api/cards/{cardNum}")
    @Operation(
            summary = "Get card by 16-digit card number",
            description = "Retrieves a single card record. The card number must be exactly 16 digits; "
                    + "an invalid format returns 400 with 'Card number if supplied must be a 16 digit "
                    + "number'. Replaces COCRDSLC (TRANID=CCDL).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Card found"),
            @ApiResponse(responseCode = "400", description = "Card number if supplied must be a 16 digit number"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Did not find this card in master file"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<CardDto> getCard(
            @Parameter(description = "16-digit card number", example = "4111111111111111")
            @PathVariable("cardNum")
            @Pattern(regexp = "\\d{16}", message = "Card number if supplied must be a 16 digit number")
            String cardNum) {

        // PCI hygiene: never log the full PAN; only the last four digits are emitted.
        log.debug("GET /api/cards/{}", maskCardNumber(cardNum));

        CardDto card = cardService.getCard(cardNum);
        return ResponseEntity.ok(card);
    }

    /**
     * Updates a credit card's mutable attributes &mdash; the REST replacement for
     * {@code app/cbl/COCRDUPC.cbl} (TRANID {@code CCUP}).
     *
     * <p>The COCRDUPC program performed a {@code READ UPDATE} followed by a {@code REWRITE}
     * (line&nbsp;1478) after editing the embossed name (alphabetic), the active status
     * ({@code Y}/{@code N}), the expiry month (1..12) and the expiry year (1950..2099). Those
     * field edits, the "no change detected" guard (line&nbsp;188), and the change persistence are
     * delegated to {@link CardService#updateCard(String, CardDto)}; the {@code @Valid} body
     * triggers the {@link CardDto} Bean Validation constraints first. The {@code cardNum} path
     * variable is the authoritative key (the immutable {@code CARD-NUM} primary key); any value on
     * the request body is ignored by the service.</p>
     *
     * <p>Optimistic locking (PR-22): the service re-saves a {@code @Version}-bearing entity, so a
     * concurrent modification raises {@code ObjectOptimisticLockingFailureException} carrying the
     * exact COCRDUPC message {@code "Record changed by some one else. Please review"} (line&nbsp;208)
     * &mdash; mapped to {@code 409 Conflict}, the modern non-blocking equivalent of the VSAM
     * {@code READ UPDATE} exclusive lock.</p>
     *
     * @param cardNum the 16-digit card number primary key ({@code CARD-NUM PIC X(16)})
     * @param request the desired card field values ({@link CardDto}); {@code @Valid}-checked, and
     *                only the mutable fields are applied by the service
     * @return {@code 200 OK} with the updated card as a {@link CardDto}
     */
    @PutMapping("/api/cards/{cardNum}")
    @Operation(
            summary = "Update a credit card",
            description = "Updates card name (alphabetic), status (Y/N), expiration month (1-12) and "
                    + "expiration year (1950-2099). Uses optimistic locking; a concurrent update "
                    + "returns 409. Replaces COCRDUPC (TRANID=CCUP).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Card updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error in card number or card fields"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Card not found"),
            @ApiResponse(responseCode = "409", description = "Record changed by some one else. Please review"),
            @ApiResponse(responseCode = "422", description = "No change detected with respect to values fetched"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<CardDto> updateCard(
            @Parameter(description = "16-digit card number", example = "4111111111111111")
            @PathVariable("cardNum")
            @Pattern(regexp = "\\d{16}", message = "Card number if supplied must be a 16 digit number")
            String cardNum,
            @Valid @RequestBody CardDto request) {

        // PCI hygiene: the masked card number bounds the operation in the logs without exposing PAN.
        log.info("PUT /api/cards/{} updating card", maskCardNumber(cardNum));
        CardDto updated = cardService.updateCard(cardNum, request);
        log.info("PUT /api/cards/{} update successful", maskCardNumber(cardNum));
        return ResponseEntity.ok(updated);
    }

    /**
     * Masks a card number for safe logging, exposing only the last four digits (PCI hygiene). A
     * {@code null} or too-short value collapses to {@code "****"}. This is a logging-only helper and
     * never alters the value handed to the service or returned to the client.
     *
     * @param cardNum the raw card number (may be {@code null})
     * @return a masked representation safe to emit to logs (e.g. {@code "************1111"})
     */
    private String maskCardNumber(String cardNum) {
        if (cardNum == null || cardNum.length() < 4) {
            return "****";
        }
        return "************" + cardNum.substring(cardNum.length() - 4);
    }
}
