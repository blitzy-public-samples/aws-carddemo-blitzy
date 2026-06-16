package com.carddemo.controller;

import com.carddemo.dto.CardListItem;
import com.carddemo.dto.CardResponse;
import com.carddemo.dto.CardUpdateRequest;
import com.carddemo.dto.PageResponse;
import com.carddemo.service.CardService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the CardDemo credit-card list / view / update operations.
 *
 * <p>This controller is the Spring Boot re-expression of three legacy CICS online programs that
 * managed credit cards on the 3270 terminal. Honoring the migration's "one operation per original
 * transaction id" rule, each source program maps to exactly one HTTP endpoint:</p>
 * <ul>
 *   <li>{@code COCRDLIC} &mdash; transaction {@code CCLI}, the <em>Credit Card List</em> browse.
 *       The legacy program showed "all cards if no context is passed and the user is an admin" or
 *       "only the ones associated with the {@code ACCT} in the COMMAREA if the user is not admin",
 *       paging seven rows at a time ({@code WS-MAX-SCREEN-LINES VALUE 7}; {@code STARTBR} /
 *       {@code READNEXT} with PF7/PF8). This becomes {@code GET /cards}.</li>
 *   <li>{@code COCRDSLC} &mdash; transaction {@code CCDL}, the <em>Credit Card View</em> detail
 *       lookup of a single card by its card number. This becomes {@code GET /cards/{cardNum}}.</li>
 *   <li>{@code COCRDUPC} &mdash; transaction {@code CCUP}, the <em>Credit Card Update</em>
 *       maintenance screen. The card number and owning account id are immutable search keys; only
 *       the embossed name, active status ({@code Y}/{@code N}) and expiration date are editable.
 *       The program performs change-detection and a lock-for-update, surfacing
 *       {@code 'Record changed by some one else. Please review'} when a concurrent change is
 *       detected. This becomes {@code PUT /cards/{cardNum}}.</li>
 * </ul>
 *
 * <p>The BMS screen maps {@code COCRDLI} / {@code COCRDSL} / {@code COCRDUP} that rendered these
 * functions are <strong>retired</strong>; their field layouts informed the request/response DTOs
 * ({@link CardListItem}, {@link CardResponse}, {@link CardUpdateRequest}) rather than any rendered
 * UI. The deliverable is a stateless JSON REST contract.</p>
 *
 * <h2>Thin-controller contract</h2>
 * <p>This class holds <strong>no business logic</strong>. Every endpoint simply binds the request
 * parameters / path variables / body and delegates to {@link CardService}, which owns all
 * behaviour ported from the COBOL paragraphs &mdash; the admin-vs-non-admin list scoping, the fixed
 * legacy page size of <strong>7</strong> (applied service-side as {@code PageRequest.of(page, 7)}),
 * card-number / account-id immutability, and optimistic-locked updates. The controller deliberately
 * forwards only the zero-based {@code page} index; it never supplies a page size, so the size-7
 * window can never be overridden from the API surface.</p>
 *
 * <h2>Error mapping</h2>
 * <p>Error translation is centralized in the application's {@code @RestControllerAdvice}
 * ({@code GlobalExceptionHandler}); the controller adds no per-endpoint error handling:</p>
 * <ul>
 *   <li>a view / update of an unknown card raises {@code ResourceNotFoundException} &rarr;
 *       HTTP&nbsp;404;</li>
 *   <li>a concurrent modification of a card's account raises
 *       {@code ObjectOptimisticLockingFailureException} (the JPA {@code @Version} guard that
 *       mirrors the legacy {@code READ ... UPDATE} / {@code REWRITE} conflict) &rarr;
 *       HTTP&nbsp;409;</li>
 *   <li>a request body that violates the {@link CardUpdateRequest} Bean Validation constraints
 *       raises {@code MethodArgumentNotValidException} &rarr; HTTP&nbsp;400.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>All three endpoints are reachable by <em>any</em> authenticated caller. Authentication is
 * enforced globally by the stateless JWT filter chain ({@code SecurityConfig}'s
 * {@code anyRequest().authenticated()} rule), so no method-level {@code @PreAuthorize} guard is
 * declared here &mdash; a missing or invalid token yields HTTP&nbsp;401. The paths are absolute
 * ({@code /cards}, {@code /cards/{cardNum}}); there is no {@code /api} prefix.</p>
 *
 * <h2>Sensitive-data &amp; immutability guarantees</h2>
 * <p>The card verification value ({@code CARD-CVV-CD}) is <strong>never</strong> serialized: it is
 * structurally absent from {@link CardListItem} and {@link CardResponse}, and this controller must
 * never reintroduce it or log card numbers / responses in cleartext. The card number and
 * {@code card_acct_id} are <strong>immutable</strong>: {@link CardUpdateRequest} carries neither, so
 * an update can change only the editable attributes &mdash; the card number is supplied exclusively
 * through the URL path and is never accepted in the body. These guarantees are enforced by the
 * DTO / mapper layer; the controller simply does not bypass them.</p>
 *
 * @see CardService
 * @see CardListItem
 * @see CardResponse
 * @see CardUpdateRequest
 * @see PageResponse
 */
@RestController
@RequestMapping("/cards")
public class CardController {

    /**
     * Application service that encapsulates all card business logic ported from {@code COCRDLIC},
     * {@code COCRDSLC} and {@code COCRDUPC}. Injected through the constructor and held {@code final}
     * so the collaborator is immutable for the lifetime of this singleton bean.
     */
    private final CardService cardService;

    /**
     * Creates the controller with its required {@link CardService} collaborator.
     *
     * <p>Constructor injection (replacing the COBOL {@code CALL} / {@code XCTL} static linkage) makes
     * the dependency explicit, mandatory and immutable, and keeps the controller trivially testable
     * with a mocked service.</p>
     *
     * @param cardService the card service to delegate every request to; never {@code null}
     */
    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    /**
     * Lists credit cards &mdash; the REST re-expression of {@code COCRDLIC} (transaction
     * {@code CCLI}).
     *
     * <p>The optional {@code accountId} reproduces the legacy admin-vs-non-admin scoping: when it is
     * absent ({@code null}) the result is the unfiltered "browse all cards" view; when it is present
     * the result is scoped to that account's cards. The controller forwards the value verbatim &mdash;
     * the service owns the null semantics and applies the fixed legacy window of seven rows per page
     * ({@code PageRequest.of(page, 7)}). Only the zero-based {@code page} index is forwarded; the
     * controller never passes a page size, so the size-7 contract cannot be overridden here.</p>
     *
     * @param accountId optional owning-account filter ({@code CARD-ACCT-ID}); {@code null} / absent
     *                  requests the admin browse-all view, a value requests an account-scoped list
     * @param page      the zero-based page index to retrieve; defaults to {@code 0} (the first page)
     *                  when the parameter is absent
     * @return HTTP&nbsp;200 with a {@link PageResponse} of {@link CardListItem} rows whose
     *         {@code size} is the legacy 7, never carrying a CVV
     */
    @GetMapping
    public ResponseEntity<PageResponse<CardListItem>> listCards(
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(cardService.listCards(accountId, page));
    }

    /**
     * Views a single card's detail &mdash; the REST re-expression of {@code COCRDSLC} (transaction
     * {@code CCDL}).
     *
     * <p>Looks up the card by its 16-character card number supplied in the path and returns the
     * detail projection. The returned {@link CardResponse} structurally omits the card verification
     * value, so the CVV can never reach the client. A card number that does not exist causes the
     * service to raise {@code ResourceNotFoundException}, which the global exception handler
     * translates into HTTP&nbsp;404.</p>
     *
     * @param cardNum the 16-character card number / PAN to look up ({@code CARD-NUM})
     * @return HTTP&nbsp;200 with the matching {@link CardResponse} (CVV-free); HTTP&nbsp;404 if no
     *         card has the given number
     */
    @GetMapping("/{cardNum}")
    public ResponseEntity<CardResponse> getCard(@PathVariable String cardNum) {
        return ResponseEntity.ok(cardService.getCard(cardNum));
    }

    /**
     * Updates a card's editable attributes &mdash; the REST re-expression of {@code COCRDUPC}
     * (transaction {@code CCUP}).
     *
     * <p>The card to update is identified solely by the card number in the URL path; the request
     * body ({@link CardUpdateRequest}) carries only the editable fields (embossed name, active
     * status, expiration date) and deliberately omits the card number and account id, which are
     * immutable search keys &mdash; consistent with {@code COCRDUPC}, which never permits those keys
     * to change. The body is validated with {@code @Valid}: a constraint violation (for example an
     * active status that is not {@code "Y"} / {@code "N"}, or an embossed name longer than 50
     * characters) raises {@code MethodArgumentNotValidException} &rarr; HTTP&nbsp;400. The service
     * performs the update under optimistic locking; a concurrent modification (the legacy
     * {@code 'Record changed by some one else. Please review'} condition) surfaces as
     * {@code ObjectOptimisticLockingFailureException} &rarr; HTTP&nbsp;409. An unknown card number
     * yields HTTP&nbsp;404.</p>
     *
     * @param cardNum the 16-character card number identifying the card to update ({@code CARD-NUM});
     *                immutable, taken only from the path
     * @param request the validated set of editable fields to apply; carries no card number / account
     *                id / CVV
     * @return HTTP&nbsp;200 with the {@link CardResponse} reflecting the persisted post-update state
     *         (CVV-free)
     */
    @PutMapping("/{cardNum}")
    public ResponseEntity<CardResponse> updateCard(
            @PathVariable String cardNum,
            @Valid @RequestBody CardUpdateRequest request) {
        return ResponseEntity.ok(cardService.updateCard(cardNum, request));
    }
}
