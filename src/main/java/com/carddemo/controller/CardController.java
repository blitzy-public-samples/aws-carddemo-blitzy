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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Credit-card list, view, and update REST endpoints &mdash; the stateless replacement for the
 * three legacy CICS online card programs:
 * <ul>
 *   <li>{@code app/cbl/COCRDLIC.cbl} (card list, TRANID {@code CCLI}) &mdash; browses the
 *       {@code CARDDAT} master through the {@code CARDAIX} alternate index by account id, paging
 *       seven rows at a time with {@code STARTBR}/{@code READNEXT} and PF7/PF8 navigation;</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} (card view, TRANID {@code CCDL}) &mdash; a card-number keyed
 *       read of {@code CARDDAT} ({@code EXEC CICS READ DATASET('CARDDAT') RIDFLD(card-num)});</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} (card update, TRANID {@code CCUP}) &mdash; a
 *       {@code READ UPDATE}/{@code REWRITE} of {@code CARDDAT} after field-level editing.</li>
 * </ul>
 *
 * <p>The COBOL record layout is the 150-byte {@code CARD-RECORD} from {@code app/cpy/CVACT02Y.cpy}.
 * The {@code CARDAIX} alternate index on {@code CARD-ACCT-ID} is replaced by the JPA
 * {@code @Index(name="idx_card_account_id", columnList="account_id")} on the {@code Card} entity,
 * accessed through {@code CardRepository.findByAccountId(...)} (AAP &sect;0.6.2).</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET /api/accounts/{acctId}/cards} &mdash; list the cards for an account, paginated;
 *       {@code 200 OK} with a {@link CardListResponse} (replaces the COCRDLIC {@code CARDAIX}
 *       browse; PF7/PF8 paging becomes the {@code page}/{@code size} query parameters per AAP
 *       &sect;0.6.1).</li>
 *   <li>{@code GET /api/cards/{cardNum}} &mdash; retrieve one card by its 16-digit number;
 *       {@code 200 OK} with a {@link CardDto} (replaces COCRDSLC).</li>
 *   <li>{@code PUT /api/cards/{cardNum}} &mdash; update an existing card; {@code 200 OK} with the
 *       refreshed {@link CardDto}. Optimistic locking via JPA {@code @Version} surfaces concurrent
 *       modifications as {@code 409 Conflict} (replaces COCRDUPC; PR-22).</li>
 * </ul>
 *
 * <h2>Thin HTTP boundary (business logic lives in {@link CardService})</h2>
 * <p>This controller is intentionally a thin, stateless HTTP adapter. All card business
 * semantics &mdash; the COCRDLIC cross-reference existence check and paged browse, the COCRDSLC
 * card-number input edits (blank / non-16-digit) and keyed read, the COCRDUPC change-detection,
 * field editing and optimistic-lock handling, and every preserved COBOL diagnostic message literal
 * &mdash; live in {@link CardService}. In particular the {@code cardNum} path segment is
 * deliberately <em>not</em> shape-validated here: {@link CardService#getCard(String)} and
 * {@link CardService#updateCard(String, CardDto)} perform the exact COCRDSLC/COCRDUPC edits
 * (e.g. {@code "Card Number must be a 16 digit number"}) so the original COBOL messages reach the
 * client rather than a generic controller-level constraint message. The exact COBOL messages are
 * surfaced to clients by {@code com.carddemo.controller.advice.GlobalExceptionHandler}, which maps:
 * </p>
 * <ul>
 *   <li>{@code IllegalArgumentException} (blank or non-16-digit card number, COCRDSLC L141/L149;
 *       card-name and Y/N status edits, COCRDUPC L182/L184/L196) &rarr; {@code 400 Bad Request};</li>
 *   <li>{@code AccountNotFoundException} (account absent from the card cross-reference, COCRDLIC;
 *       card number not found, COCRDSLC L154; card absent on update, COCRDUPC L202) &rarr;
 *       {@code 404 Not Found};</li>
 *   <li>{@code IllegalStateException} (COCRDUPC "no change detected", L188) &rarr;
 *       {@code 422 Unprocessable Entity};</li>
 *   <li>{@code ObjectOptimisticLockingFailureException} (COCRDUPC concurrent-update race, L208)
 *       &rarr; {@code 409 Conflict} (PR-22);</li>
 *   <li>a non-numeric {@code acctId} path segment &rarr; {@code 400} via
 *       {@code MethodArgumentTypeMismatchException}.</li>
 * </ul>
 *
 * <h2>Authorization</h2>
 * <p>Intentionally <strong>no</strong> class-level {@code @PreAuthorize}: any
 * <em>authenticated</em> caller (USER or ADMIN) may list, view, and update cards, exactly as the
 * legacy card transactions were reachable from the regular user menu ({@code COMEN01C}). The
 * requirement that the caller be authenticated at all is enforced by the application
 * {@code SecurityFilterChain} (see {@code com.carddemo.security.SecurityConfig}); anonymous
 * requests are rejected with {@code 401} before reaching these methods. No finer-grained,
 * per-record authorization is part of the legacy behavior, so none is introduced here
 * (AAP &sect;0.7.2 &mdash; no feature additions).</p>
 *
 * <h2>Refactoring rules enforced</h2>
 * <ul>
 *   <li><b>PR-13</b> &mdash; {@code acctId} validated as an 11-digit non-zero numeric
 *       ({@code @Min(1)}/{@code @Max(99999999999L)}) mirroring {@code ACCT-ID PIC 9(11)}; the
 *       16-digit {@code cardNum} ({@code CARD-NUM PIC X(16)}) is validated by the service so the
 *       exact COBOL format message is preserved.</li>
 *   <li><b>PR-16</b> &mdash; the controller forwards {@link CardDto}/{@link CardListResponse}
 *       verbatim; any monetary field on those DTOs is {@link java.math.BigDecimal}.</li>
 *   <li><b>PR-22</b> &mdash; the {@code Card} entity carries {@code @Version}; a concurrent update
 *       is surfaced as {@code ObjectOptimisticLockingFailureException} and mapped to {@code 409
 *       Conflict} by {@code GlobalExceptionHandler}.</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE 10 namespace only ({@code jakarta.validation.*}); no
 *       {@code javax.*} imports.</li>
 *   <li><b>PR-29</b> &mdash; constructor injection only, via Lombok {@link RequiredArgsConstructor}
 *       over the {@code final} {@link CardService} field; no {@code @Autowired} field injection.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><b>Mixed base paths:</b> the class is mapped at {@code /api} and each method declares its
 *       full sub-path, because the list endpoint is account-scoped
 *       ({@code /api/accounts/{acctId}/cards}) while the view/update endpoints are card-scoped
 *       ({@code /api/cards/{cardNum}}), matching the routes advertised by {@code MenuController}.</li>
 *   <li><b>Stateless paging:</b> the COCRDLIC PF7/PF8 browse cursor is replaced by the
 *       {@code page}/{@code size} query parameters; the default page size of {@code 7} matches the
 *       COCRDLIC {@code WS-MAX-SCREEN-LINES} (seven rows per screen).</li>
 *   <li><b>Path is authoritative:</b> for {@code PUT}, the {@code cardNum} path variable identifies
 *       the card to update; the {@code card_num} primary key is immutable and any value on the body
 *       is ignored by {@link CardService}.</li>
 * </ul>
 *
 * <p>Version reference: CardDemo_v1.0-15-g27d6c6f-68 (CVACT02Y card record layout).
 *
 * @see CardService the service that performs the COCRDLIC/COCRDSLC/COCRDUPC logic
 * @see CardDto the card view/update payload
 * @see CardListResponse the paginated card-list payload
 * @see com.carddemo.controller.advice.GlobalExceptionHandler exception-to-HTTP-status mapping
 * @since 1.0
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
@Slf4j
@Tag(name = "Card", description = "Card list, view, and update endpoints (replaces COCRDLIC, COCRDSLC, COCRDUPC)")
@SecurityRequirement(name = "bearerAuth")
public class CardController {

    /**
     * Default page size for the account-scoped card list, matching the COCRDLIC
     * {@code WS-MAX-SCREEN-LINES} (seven rows per 3270 screen). A caller may override it with the
     * {@code size} query parameter; {@link CardService#listByAccount(Long, int, int)} falls back to
     * its own default when a non-positive size is supplied.
     */
    private static final String DEFAULT_PAGE_SIZE = "7";

    /**
     * Card business service replacing the COCRDLIC (list), COCRDSLC (view), and COCRDUPC (update)
     * online programs. Injected by type through the Lombok-generated constructor (PR-29). Each
     * method returns a DTO ({@link CardDto} / {@link CardListResponse}), so this controller never
     * accesses the JPA entity directly.
     */
    private final CardService cardService;

    /**
     * Lists the cards belonging to an account, one page at a time, and returns a
     * {@link CardListResponse}.
     *
     * <p>This is the REST replacement for {@code app/cbl/COCRDLIC.cbl} (TRANID {@code CCLI}), which
     * browsed {@code CARDDAT} through the {@code CARDAIX} alternate index by account id. The
     * cross-reference existence check, the paged browse, and the preserved COBOL messages are
     * delegated entirely to {@link CardService#listByAccount(Long, int, int)}:</p>
     * <ul>
     *   <li>account absent from the card cross-reference &rarr; {@code AccountNotFoundException}
     *       (COCRDLIC "Account ID not found in cross-reference"), mapped to {@code 404};</li>
     *   <li>cross-reference lists the account but the master returns no rows on the first page
     *       &rarr; {@code InvalidCardException} (COCRDLIC "NO RECORDS FOUND FOR THIS SEARCH
     *       CONDITION."), mapped to {@code 400}.</li>
     * </ul>
     *
     * <p>The COCRDLIC PF7/PF8 paging is replaced by the stateless {@code page}/{@code size} query
     * parameters; the cursor is recomputed per request (AAP &sect;0.6.1). The default page size of
     * {@value #DEFAULT_PAGE_SIZE} matches the COCRDLIC seven-row screen.</p>
     *
     * <p>Validation runs during argument binding (PR-13): the class-level {@code @Validated} causes
     * the {@link Min @Min(1)} / {@link Max @Max(99999999999L)} constraints on {@code acctId} to be
     * enforced, so a zero, negative, or greater-than-11-digit account id is rejected with
     * {@code 400 Bad Request} (via {@code ConstraintViolationException}) and the exact COBOL message
     * {@code "Account number must be a non zero 11 digit number"}; a non-numeric path segment is
     * rejected with {@code 400} (via {@code MethodArgumentTypeMismatchException}).</p>
     *
     * @param acctId the account primary key, mirroring COBOL {@code ACCT-ID PIC 9(11)}; must be a
     *               non-zero 11-digit number ({@code 1 .. 99999999999})
     * @param page   the zero-based page index (default {@code 0})
     * @param size   the page size (default {@value #DEFAULT_PAGE_SIZE}); a non-positive value lets
     *               the service apply its own default
     * @return {@code 200 OK} with the page of cards as a {@link CardListResponse}
     */
    @GetMapping("/accounts/{acctId}/cards")
    @Operation(
            summary = "List cards for an account",
            description = "Lists the credit cards associated with an account, paginated. "
                    + "Replaces the COCRDLIC CARDAIX browse (TRANID=CCLI); PF7/PF8 paging becomes "
                    + "the page/size query parameters. Default page size 7 matches the COCRDLIC screen.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cards listed (possibly empty page)"),
            @ApiResponse(responseCode = "400", description = "Invalid account ID format, or no card rows for a cross-referenced account"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Account not found in card cross-reference"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<CardListResponse> listCards(
            @Parameter(description = "11-digit non-zero numeric account ID", example = "00000000001")
            @PathVariable("acctId")
            @Min(value = 1L, message = "Account number must be a non zero 11 digit number")
            @Max(value = 99999999999L, message = "Account number must be a non zero 11 digit number")
            Long acctId,
            @Parameter(description = "Zero-based page index", example = "0")
            @RequestParam(name = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size (default 7, matching the COCRDLIC screen)", example = "7")
            @RequestParam(name = "size", defaultValue = DEFAULT_PAGE_SIZE) int size) {

        // Log the path/query coordinates only (DEBUG). The CardListResponse body carries no PII.
        log.debug("GET /api/accounts/{}/cards page={} size={}", acctId, page, size);

        // Delegate to the service, which performs the COCRDLIC cross-reference existence check and
        // paged CARDAIX browse, throws AccountNotFoundException (-> 404) when the account is absent
        // from the cross-reference, and returns a CardListResponse. The controller forwards it.
        CardListResponse response = cardService.listByAccount(acctId, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves a single card by its 16-digit card number and returns the {@link CardDto}.
     *
     * <p>This is the REST replacement for {@code app/cbl/COCRDSLC.cbl} (TRANID {@code CCDL}). The
     * input edits (blank / non-16-digit card number) and the keyed {@code CARDDAT} read are
     * delegated entirely to {@link CardService#getCard(String)}:</p>
     * <ul>
     *   <li>blank card number &rarr; {@code IllegalArgumentException} (COCRDSLC L141), mapped to
     *       {@code 400};</li>
     *   <li>card number not exactly 16 digits &rarr; {@code IllegalArgumentException} (COCRDSLC
     *       L149), mapped to {@code 400};</li>
     *   <li>card not found ({@code DFHRESP(NOTFND)}) &rarr; {@code AccountNotFoundException}
     *       (COCRDSLC L154), mapped to {@code 404}.</li>
     * </ul>
     *
     * <p>The {@code cardNum} path segment is intentionally not shape-validated at the controller so
     * that the exact COBOL format message is preserved by the service (see the class JavaDoc).</p>
     *
     * @param cardNum the 16-character card number primary key ({@code CARD-NUM PIC X(16)})
     * @return {@code 200 OK} with the card as a {@link CardDto}
     */
    @GetMapping("/cards/{cardNum}")
    @Operation(
            summary = "Get a card by number",
            description = "Retrieves a single card by its 16-digit card number. "
                    + "Replaces COCRDSLC CICS card view (TRANID=CCDL).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Card found"),
            @ApiResponse(responseCode = "400", description = "Card number missing or not a 16 digit number"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Card number not found"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<CardDto> getCard(
            @Parameter(description = "16-digit card number", example = "9680294154603697")
            @PathVariable("cardNum") String cardNum) {

        // Log nothing sensitive: the full PAN is never logged. The service masks/handles the value;
        // here we record only that a card view was requested.
        log.debug("GET /api/cards/**** (card view requested)");

        // Delegate to the service, which validates the card-number format (-> 400 with the exact
        // COCRDSLC message), performs the keyed read, throws AccountNotFoundException (-> 404) when
        // the card is absent, and returns a CardDto. The controller forwards it unchanged.
        CardDto card = cardService.getCard(cardNum);
        return ResponseEntity.ok(card);
    }

    /**
     * Updates an existing card identified by its 16-digit card number and returns the refreshed
     * {@link CardDto}.
     *
     * <p>This is the REST replacement for {@code app/cbl/COCRDUPC.cbl} (TRANID {@code CCUP}). The
     * field-level editing, change detection, and VSAM {@code READ UPDATE}/{@code REWRITE} race
     * handling are delegated entirely to {@link CardService#updateCard(String, CardDto)}, which
     * reproduces the COCRDUPC edits exactly:</p>
     * <ul>
     *   <li>embossed name blank / non-alphabetic &rarr; {@code IllegalArgumentException} (COCRDUPC
     *       L182/L184), mapped to {@code 400};</li>
     *   <li>active status other than {@code Y}/{@code N} &rarr; {@code IllegalArgumentException}
     *       (COCRDUPC L196), mapped to {@code 400};</li>
     *   <li>no field changed &rarr; {@code IllegalStateException} (COCRDUPC L188), mapped to
     *       {@code 422};</li>
     *   <li>concurrent modification &rarr; {@code ObjectOptimisticLockingFailureException} carrying
     *       the exact message {@code "Record changed by some one else. Please review"} (COCRDUPC
     *       L208), mapped to {@code 409 Conflict} (PR-22);</li>
     *   <li>card absent &rarr; {@code AccountNotFoundException} (COCRDUPC L202), mapped to
     *       {@code 404}.</li>
     * </ul>
     *
     * <p>The {@code cardNum} path variable is the source of truth for which card to update; the
     * {@code card_num} primary key is immutable and any value present on the request body is
     * ignored by the service. {@code @Valid} triggers Jakarta Bean Validation on the
     * {@link CardDto} body; a body-level violation raises {@code MethodArgumentNotValidException},
     * mapped to {@code 400} by {@code GlobalExceptionHandler}.</p>
     *
     * @param cardNum the 16-character card number primary key ({@code CARD-NUM PIC X(16)})
     * @param request the desired card field values; {@code null} fields are left unchanged by the
     *                service, mirroring the COCRDUPC change-by-change edit
     * @return {@code 200 OK} with the updated card as a {@link CardDto}
     */
    @PutMapping("/cards/{cardNum}")
    @Operation(
            summary = "Update an existing card",
            description = "Updates card fields (embossed name, active status, expiration date). "
                    + "Uses JPA @Version optimistic locking; concurrent updates return 409 Conflict "
                    + "with message: 'Record changed by some one else. Please review'. "
                    + "Replaces COCRDUPC CICS card update (TRANID=CCUP).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Card updated successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error (card number, embossed name, Y/N status, expiration date)"),
            @ApiResponse(responseCode = "401", description = "User not authenticated"),
            @ApiResponse(responseCode = "404", description = "Card not found"),
            @ApiResponse(responseCode = "409", description = "Optimistic lock conflict: another user updated this card"),
            @ApiResponse(responseCode = "422", description = "No change detected with respect to values fetched"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<CardDto> updateCard(
            @Parameter(description = "16-digit card number", example = "9680294154603697")
            @PathVariable("cardNum") String cardNum,
            @Valid @RequestBody CardDto request) {

        // Log nothing sensitive: the full PAN is never logged. Record only that an update occurred.
        log.info("PUT /api/cards/**** updating card");

        // Delegate to the service, which validates the card-number format and field edits (-> 400),
        // applies the COCRDUPC change-detection edit, performs the optimistically-locked REWRITE
        // (-> 409 on conflict), throws AccountNotFoundException when the card is absent (-> 404),
        // and returns the refreshed CardDto. The controller forwards it unchanged.
        CardDto updated = cardService.updateCard(cardNum, request);
        log.info("PUT /api/cards/**** update successful");
        return ResponseEntity.ok(updated);
    }
}
