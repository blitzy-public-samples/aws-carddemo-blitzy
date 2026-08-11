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
package com.carddemo.card.controller;

import com.carddemo.card.service.CardService;
import com.carddemo.common.dto.CardDetailResponseDto;
import com.carddemo.common.dto.CardKeyRequestDto;
import com.carddemo.common.dto.CardListResponseDto;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.CardUpdateResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.SessionContextSupport;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.web.PageParameterGuard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * :purpose: Thin REST controller for the CardDemo card feature, exposing credit-card list,
 *     detail, and update operations. It re-expresses three legacy CICS online card
 *     transactions as HTTP endpoints and delegates all business logic to
 * :java: type:`com.carddemo.card.service.CardService`: ``CCLI`` / ``COCRDLIC`` (card list)
 *     becomes ``GET /cards``, ``CCDL`` / ``COCRDSLC`` (card detail) becomes ``GET
 *     /cards/{cardNumber}``, and ``CCUP`` / ``COCRDUPC`` (card update) becomes ``PUT
 *     /cards/{cardNumber}``.
 * :output: Card list, detail, and update response DTOs returned directly as HTTP 200
 *     bodies; domain, validation, not-found, and conflict failures are translated to the
 *     appropriate HTTP status by the shared ``GlobalExceptionHandler``.
 * :note: The controller performs only path-variable and account-filter format checks,
 *     resolves and writes back the externalized pseudo-conversational session context, and
 *     delegates to the service. It holds no business logic, persistence, security,
 *     observability, or exception-handling code.
 */
@RestController
@RequestMapping("/cards")
public class CardController {

    /**
     * :purpose: ``WS-PROMPT-FOR-CARD`` — the literal ``1220-EDIT-CARD`` /
     *  ``2220-EDIT-CARD`` report when no card key was supplied at all.
     */
    private static final String MSG_CARD_NOT_PROVIDED = "Card number not provided";

    /**
     * :purpose: The literal the card edits MOVE directly for a key that is present but not
     *  sixteen digits. The mixed-case ``SEARCHED-CARD-NOT-NUMERIC`` 88-level both card
     *  programs declare is never SET, so it is not text this service can report.
     */
    private static final String MSG_CARD_FILTER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * :purpose: The literal ``1210-EDIT-ACCOUNT`` / ``2210-EDIT-ACCOUNT`` MOVE directly for
     *  a rejected account filter, for the same reason.
     */
    private static final String MSG_ACCT_FILTER_NOT_NUMERIC =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** :purpose: Card feature business-logic service to which every request delegates. */
    private final CardService cardService;

    /**
     * :purpose: Construct the controller with its single collaborator via constructor
     *  injection.
     * :param cardService: the card feature business-logic service.
     */
    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    /**
     * :purpose: List cards for the card-list screen (legacy ``COCRDLIC``, CICS ``CCLI``),
     *     returning at most seven rows per page. The optional account and card-number filters the
     *     operator typed are the only browse scope the service applies -- ``COCRDLIC``
     *     ``9500-FILTER-RECORDS`` carries no user-type branch, so the browse is role independent
     *     (decision log §19.1); forward and backward paging (legacy PF8 / PF7) map to the
     *     one-based ``page`` parameter.
     * :param accountId: optional owning-account filter; when blank no account filter is
     *     applied.
     * :param cardNumber: optional exact card-number filter passed through to the service.
     * :param page: the one-based page number to return; defaults to the first page.
     * :param httpRequest: the current servlet request; its already-established session, when
     *     present, carries the pseudo-conversational :java:type:`SessionContext`.
     * :returns: the card-list response holding the requested page of card rows.
     * :raises CardDemoException: when the supplied account filter is not a one-to-eleven digit
     *     number, or the row-selection flag is neither ``S`` nor ``U`` (both translated to HTTP
     *     400).
     */
    @GetMapping
    public CardListResponseDto listCards(
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "cardNumber", required = false) String cardNumber,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "aid", required = false) String aid,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "selectedCardNumber", required = false) String selectedCardNumber,
            HttpServletRequest httpRequest) {
        // A page ordinal that identifies no screen is refused rather than clamped to the
        // first page: clamping made page=-1 and page=1 return byte-identical responses, so
        // a client paging bug was indistinguishable from correct behaviour.
        int requestedPage = PageParameterGuard.requirePositivePage(page, "page");
        Long acctIdFilter = parseAccountFilter(accountId);
        SessionContext ctx = resolveSessionContext(httpRequest);
        // The navigation action and the row selection are part of the COCRDLIC screen
        // contract, so they are bound here and passed through: without them the paging keys
        // and the S/U row selection could never reach 1400-SETUP-MESSAGE.
        CardListResponseDto response = cardService.listCards(acctIdFilter, cardNumber, requestedPage,
                aid, action, selectedCardNumber, ctx);
        storeSessionContext(httpRequest, ctx);
        return response;
    }

    /**
     * :purpose: Read a single card for the card-detail screen (legacy ``COCRDSLC``, CICS
     *     ``CCDL``) by its sixteen-digit card number. The key is submitted in the request BODY,
     *     which is why this read is a POST. A card number is a Primary Account Number, and a URL
     *     -- path segment or query string alike -- is written verbatim into every access log,
     *     proxy log and distributed trace along the request path, so carrying it there would
     *     persist the PAN in plaintext across the whole infrastructure. The request body is not
     *     recorded by any of them. The read itself is unchanged and remains side-effect free apart
     *     from the session-context update the pseudo-conversational flow requires.
     * :param key: the composite card key -- the sixteen-digit card number and the optional
     *     ``ACCTSID`` that completes the selection; a supplied account id must be a non-zero
     *     eleven-digit number.
     * :param httpRequest: the current servlet request; its already-established session, when
     *     present, carries the pseudo-conversational :java:type:`SessionContext`.
     * :returns: the card-detail response for the resolved card.
     * :raises CardDemoException: when the card number is not sixteen digits or the account
     *     number is not a non-zero eleven-digit value (translated to HTTP 400).
     */
    @PostMapping("/detail")
    public CardDetailResponseDto getCardDetail(
            @RequestBody CardKeyRequestDto key,
            HttpServletRequest httpRequest) {
        String validated = parseCardNumber(key == null ? null : key.getCardNumber());
        Long acctIdFilter = parseAccountFilter(key == null ? null : key.getAccountId());
        SessionContext ctx = resolveSessionContext(httpRequest);
        CardDetailResponseDto response = cardService.getCardDetail(validated, acctIdFilter);
        ctx.setCardNum(validated);
        ctx.setAcctId(response.getCardAcctId());
        storeSessionContext(httpRequest, ctx);
        return response;
    }

    /**
     * :purpose: Update a card for the card-update screen (legacy ``COCRDUPC``, CICS ``CCUP``).
     *     The editable card fields are validated by the service and the update is applied as a
     *     single atomic transaction. The addressed card number travels in the request body for the
     *     same reason the detail read submits it there: a PAN must not be written into a URL,
     *     because every access log, proxy and trace along the path records one.
     * :param request: the editable card fields (embossed name, active status, expiry date, and
     *     CVV) together with the card number and optional account id that address the record; a
     *     supplied account id must be a non-zero eleven-digit number.
     * :param httpRequest: the current servlet request; its already-established session, when
     *     present, carries the pseudo-conversational :java:type:`SessionContext`.
     * :returns: the card-update response reflecting the persisted card.
     * :raises CardDemoException: when the card number is not sixteen digits, the account
     *     number is not a non-zero eleven-digit value, or a card field fails a validation edit
     *     (translated to HTTP 400).
     */
    @PutMapping
    public CardUpdateResponseDto updateCard(
            @Valid @RequestBody CardUpdateRequestDto request,
            HttpServletRequest httpRequest) {
        String validated = parseCardNumber(request == null ? null : request.getCardNumber());
        Long acctIdFilter = parseAccountFilter(request == null ? null : request.getAccountId());
        SessionContext ctx = resolveSessionContext(httpRequest);
        CardUpdateResponseDto response =
                cardService.updateCard(validated, acctIdFilter, request, ctx);
        storeSessionContext(httpRequest, ctx);
        return response;
    }

    /**
     * :purpose: Validate the card-number path variable, requiring exactly sixteen
     *  numeric digits and preserving the value as a string (including any leading
     *  zeros) to match the legacy ``CARD-NUM PIC X(16)`` semantics.
     * :param cardNumber: the raw card-number path variable.
     * :returns: the trimmed sixteen-digit card number.
     * :raises CardDemoException: when the card number is null or not sixteen digits.
     */
    private String parseCardNumber(String cardNumber) {
        String trimmed = cardNumber == null ? "" : cardNumber.trim();
        // 1220-EDIT-CARD / 2220-EDIT-CARD split the rejection in two, and each branch has
        // its own literal. An absent or all-zero key takes the blank branch
        // (WS-PROMPT-FOR-CARD); anything else that is not sixteen digits takes the
        // IS NOT NUMERIC branch, whose text the program MOVEs directly. The mixed-case
        // SEARCHED-CARD-NOT-NUMERIC 88-level both programs declare is never SET, so it is
        // not a message this service can legitimately report.
        if (trimmed.isEmpty() || trimmed.matches("0+")) {
            throw new CardDemoException(MSG_CARD_NOT_PROVIDED);
        }
        if (!trimmed.matches("\\d{16}")) {
            throw new CardDemoException(MSG_CARD_FILTER_NOT_NUMERIC);
        }
        return trimmed;
    }

    /**
     * :purpose: Convert the optional account-filter query parameter to the numeric type the
     *     service expects, applying the legacy account-filter edit at the type boundary — the one
     *     place the raw digit run is still visible. A null or blank value yields no filter; the
     *     value is neither reformatted nor zero-padded.
     * :param accountId: the raw account-filter query parameter, or ``null``.
     * :returns: the parsed account-id filter, or ``null`` when no filter was supplied.
     * :raises CardDemoException: when a supplied value is not exactly eleven ASCII digits.
     * :note: The width is EXACT. ``COCRDLIC``/``COCRDSLC``/``COCRDUPC`` all receive the filter
     *     in a ``PIC X(11)`` map field and test ``IF CC-ACCT-ID IS NOT NUMERIC`` (``COCRDSLC``
     *     L665), which is true unless every one of the eleven characters is a digit; BMS
     *     blank-pads the untyped positions, so a shorter run is refused. The service takes a
     *     ``Long``, which cannot tell ``1`` from ``00000000001``, so this boundary is the only
     *     place the rule can be enforced.
     */
    private Long parseAccountFilter(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            return null;
        }
        String trimmed = accountId.trim();
        if (!trimmed.matches("\\d{11}")) {
            throw new CardDemoException(MSG_ACCT_FILTER_NOT_NUMERIC);
        }
        return Long.valueOf(trimmed);
    }

    /**
     * :purpose: Resolve the externalized pseudo-conversational session context for this request
     *  through the ONE shared, fail-closed resolver, so every CardDemo controller answers a
     *  missing or wrong-typed context identically instead of fabricating a blank identity.
     * :param httpRequest: the current servlet request.
     * :returns: the {@link SessionContext} the caller's session carries; never ``null``.
     * :raises IllegalStateException: when the caller has no session or the session carries no
     *  usable context. Every route here is gated by the shared filter chain, so a request
     *  cannot legitimately arrive in that state.
     */
    private SessionContext resolveSessionContext(HttpServletRequest httpRequest) {
        return SessionContextSupport.require(httpRequest);
    }

    /**
     * :purpose: Flush the (possibly mutated) session context back to the caller's EXISTING HTTP
     *  session so the next stateless request sees the updated COMMAREA replacement. A caller
     *  without a session carries no pseudo-conversational state, and none is created for it.
     * :param httpRequest: the current servlet request.
     * :param ctx: the session context to persist.
     */
    private void storeSessionContext(HttpServletRequest httpRequest, SessionContext ctx) {
        SessionContextSupport.store(httpRequest, ctx);
    }
}
