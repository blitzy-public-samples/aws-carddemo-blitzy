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
import com.carddemo.common.dto.CardListResponseDto;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.CardUpdateResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.SessionContextSupport;
import com.carddemo.common.exception.CardDemoException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * :purpose: Thin REST controller for the CardDemo card feature, exposing credit-card
 *  list, detail, and update operations. It re-expresses three legacy CICS online card
 *  transactions as HTTP endpoints and delegates all business logic to
 *  :java:type:`com.carddemo.card.service.CardService`:
 *  ``CCLI`` / ``COCRDLIC`` (card list) becomes ``GET /cards``, ``CCDL`` / ``COCRDSLC``
 *  (card detail) becomes ``GET /cards/{cardNumber}``, and ``CCUP`` / ``COCRDUPC``
 *  (card update) becomes ``PUT /cards/{cardNumber}``.
 * :output: Card list, detail, and update response DTOs returned directly as HTTP 200
 *  bodies; domain, validation, not-found, and conflict failures are translated to the
 *  appropriate HTTP status by the shared ``GlobalExceptionHandler``.
 * :note: The controller performs only path-variable and account-filter format checks,
 *  resolves and writes back the externalized pseudo-conversational session context,
 *  and delegates to the service. It holds no business logic, persistence, security,
 *  observability, or exception-handling code.
 */
@RestController
@RequestMapping("/cards")
public class CardController {

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
     *  returning at most seven rows per page. Optional account and card-number filters
     *  and admin-versus-user account scoping are applied by the service; forward and
     *  backward paging (legacy PF8 / PF7) map to the one-based ``page`` parameter.
     * :param accountId: optional owning-account filter; when blank no account filter is
     *  applied.
     * :param cardNumber: optional exact card-number filter passed through to the service.
     * :param page: the one-based page number to return; defaults to the first page.
     * :param httpRequest: the current servlet request; its already-established session,
     *  when present, carries the pseudo-conversational :java:type:`SessionContext`.
     * :returns: the card-list response holding the requested page of card rows.
     * :raises CardDemoException: when the supplied account filter is not a one-to-eleven
     *  digit number, or the row-selection flag is neither ``S`` nor ``U`` (both translated
     *  to HTTP 400).
     */
    @GetMapping
    public CardListResponseDto listCards(
            @RequestParam(name = "accountId", required = false) String accountId,
            @RequestParam(name = "cardNumber", required = false) String cardNumber,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "aid", required = false) String aid,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "selectedCardNumber", required = false) String selectedCardNumber,
            HttpServletRequest httpRequest) {
        Long acctIdFilter = parseAccountFilter(accountId);
        SessionContext ctx = resolveSessionContext(httpRequest);
        // The navigation action and the row selection are part of the COCRDLIC screen
        // contract, so they are bound here and passed through: without them the paging keys
        // and the S/U row selection could never reach 1400-SETUP-MESSAGE.
        CardListResponseDto response = cardService.listCards(acctIdFilter, cardNumber, page,
                aid, action, selectedCardNumber, ctx);
        storeSessionContext(httpRequest, ctx);
        return response;
    }

    /**
     * :purpose: Read a single card for the card-detail screen (legacy ``COCRDSLC``,
     *  CICS ``CCDL``) by its sixteen-digit card number.
     * :param cardNumber: the sixteen-digit card number path variable.
     * :param accountId: the ``ACCTSID`` the screen collects alongside ``CARDSID``,
     *  completing the composite selection; optional, and when supplied it must be a
     *  non-zero eleven-digit number.
     * :param httpRequest: the current servlet request; its already-established session,
     *  when present, carries the pseudo-conversational :java:type:`SessionContext`.
     * :returns: the card-detail response for the resolved card.
     * :raises CardDemoException: when the card number is not sixteen digits or the account
     *  number is not a non-zero eleven-digit value (translated to HTTP 400).
     */
    @GetMapping("/{cardNumber}")
    public CardDetailResponseDto getCardDetail(
            @PathVariable String cardNumber,
            @RequestParam(name = "accountId", required = false) String accountId,
            HttpServletRequest httpRequest) {
        String validated = parseCardNumber(cardNumber);
        Long acctIdFilter = parseAccountFilter(accountId);
        SessionContext ctx = resolveSessionContext(httpRequest);
        CardDetailResponseDto response = cardService.getCardDetail(validated, acctIdFilter);
        ctx.setCardNum(validated);
        ctx.setAcctId(response.getCardAcctId());
        storeSessionContext(httpRequest, ctx);
        return response;
    }

    /**
     * :purpose: Update a card for the card-update screen (legacy ``COCRDUPC``, CICS
     *  ``CCUP``). The editable card fields are validated by the service and the update
     *  is applied as a single atomic transaction.
     * :param cardNumber: the sixteen-digit card number path variable.
     * :param accountId: the ``ACCTSID`` the screen collects alongside ``CARDSID``,
     *  completing the composite selection; optional, and when supplied it must be a
     *  non-zero eleven-digit number.
     * :param request: the editable card fields (embossed name, active status, expiry
     *  date, and CVV).
     * :param httpRequest: the current servlet request; its already-established session,
     *  when present, carries the pseudo-conversational :java:type:`SessionContext`.
     * :returns: the card-update response reflecting the persisted card.
     * :raises CardDemoException: when the card number is not sixteen digits, the account
     *  number is not a non-zero eleven-digit value, or a card field fails a validation
     *  edit (translated to HTTP 400).
     */
    @PutMapping("/{cardNumber}")
    public CardUpdateResponseDto updateCard(
            @PathVariable String cardNumber,
            @RequestParam(name = "accountId", required = false) String accountId,
            @Valid @RequestBody CardUpdateRequestDto request,
            HttpServletRequest httpRequest) {
        String validated = parseCardNumber(cardNumber);
        Long acctIdFilter = parseAccountFilter(accountId);
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
        if (cardNumber == null || !cardNumber.trim().matches("\\d{16}")) {
            throw new CardDemoException("Card number if supplied must be a 16 digit number");
        }
        return cardNumber.trim();
    }

    /**
     * :purpose: Convert the optional account-filter query parameter to the numeric type
     *  the service expects, applying the legacy one-to-eleven digit edit at the type
     *  boundary. A null or blank value yields no filter; the value is neither reformatted
     *  nor zero-padded.
     * :param accountId: the raw account-filter query parameter, or ``null``.
     * :returns: the parsed account-id filter, or ``null`` when no filter was supplied.
     * :raises CardDemoException: when a supplied value is not a one-to-eleven digit
     *  number.
     */
    private Long parseAccountFilter(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            return null;
        }
        String trimmed = accountId.trim();
        if (!trimmed.matches("\\d{1,11}")) {
            throw new CardDemoException("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
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
