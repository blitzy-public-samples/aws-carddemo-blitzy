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
package com.carddemo.transaction.controller;

import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.SessionContextSupport;
import com.carddemo.common.dto.TransactionAddRequestDto;
import com.carddemo.common.dto.TransactionAddResponseDto;
import com.carddemo.common.dto.TransactionKeyResponseDto;
import com.carddemo.common.dto.TransactionListRequestDto;
import com.carddemo.common.web.PageParameterGuard;
import com.carddemo.common.dto.TransactionKeyDto;
import com.carddemo.common.dto.TransactionListResponseDto;
import com.carddemo.common.dto.TransactionViewResponseDto;
import com.carddemo.transaction.service.TransactionService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * :purpose: REST adapter for the online transaction management screens (CICS
 *  ``CT00``/``CT01``/``CT02``, legacy programs ``COTRN00C``/``COTRN01C``/``COTRN02C``).
 *  Translates HTTP requests into {@link TransactionService} calls and returns the
 *  service's DTOs unchanged. The controller is stateless: the pseudo-conversational
 *  COMMAREA state is carried as an opaque {@link SessionContext} resolved from and
 *  re-stored to the servlet session on each request. It holds no business logic,
 *  validation, id generation, or error handling; validation failures and lookups
 *  raise exceptions that the shared ``GlobalExceptionHandler`` maps centrally.
 */
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    /** :purpose: Online transaction business-logic collaborator (list, view, add). */
    private final TransactionService transactionService;

    /**
     * :purpose: Construct the controller with its service collaborator.
     * :param transactionService: the online transaction service that owns all list,
     *  view, and add business logic.
     */
    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * :purpose: List posted transactions ten rows per page with an optional
     *  transaction-id filter and PF7/PF8 forward/backward paging, all delegated to the
     *  service (CICS ``CT00`` / ``COTRN00C``). Row selection ``'S'`` is represented as a
     *  client-side navigation to ``GET /transactions/{id}``, so this endpoint performs
     *  no special handling for it.
     * :param request: the list request DTO (tran-id filter, paging cursor, and page
     *  direction), bound from the query parameters.
     * :param httpRequest: the current servlet request; its already-established session,
     *  when present, backs the externalized session context.
     * :returns: the list response DTO serialized as JSON (HTTP 200).
     */
    @GetMapping
    public TransactionListResponseDto listTransactions(@ModelAttribute TransactionListRequestDto request,
                                                        @RequestParam(name = "pageNumber", required = false)
                                                        Integer suppliedPageNumber,
                                                        HttpServletRequest httpRequest) {
        // The bound DTO cannot distinguish an omitted pageNumber from an explicit 0 (the
        // field is a primitive int, so both arrive as 0). The raw parameter is bound
        // alongside it purely to tell those cases apart: omitted resolves to the first
        // page, while a supplied 0 or negative value identifies no screen and is refused
        // rather than silently clamped to the first page.
        request.setPageNumber(
                PageParameterGuard.requirePositivePage(suppliedPageNumber, "pageNumber"));
        SessionContext sessionContext = resolveSessionContext(httpRequest);
        TransactionListResponseDto response = transactionService.listTransactions(request, sessionContext);
        storeSessionContext(httpRequest, sessionContext);
        return response;
    }

    /**
     * :purpose: Read the last transaction on file, backing the add screen's
     *  ``F5=Copy Last Tran.`` action (``COTRN02C COPY-LAST-TRAN-DATA``). Declared before
     *  the ``/{id}`` template so the literal segment wins the mapping.
     * :returns: the view response DTO for the highest-keyed transaction (HTTP 200).
     * :raises RecordNotFoundException: when no transaction exists, mapped to HTTP 404 by
     *  the shared ``GlobalExceptionHandler``.
     */
    @GetMapping("/last")
    public TransactionViewResponseDto viewLastTransaction() {
        return transactionService.viewLastTransaction();
    }

    /**
     * :purpose: Run the add screen's key-field edit on its own (``COTRN02C``
     *     ``VALIDATE-INPUT-KEY-FIELDS``) and report the account/card pair it resolves.
     *     ``PROCESS-ENTER-KEY`` performs that paragraph before the data-field checks, so the
     *     screen needs the same lookup available before it examines its own fields; the paragraph
     *     also writes the counterpart key back onto the map, which is why the resolved pair is
     *     returned. Declared before the ``/{id}`` template so the literal segment wins the
     *     mapping.
     * :param acctId: the ``ACTIDIN`` entry value; optional.
     * :param tranCardNum: the ``CARDNIN`` entry value; optional.
     * :returns: the resolved account id and card number (HTTP 200).
     * :raises CardDemoException: when a key is non-numeric or neither key is present (mapped
     *     to HTTP 400 by the shared ``GlobalExceptionHandler``).
     * :raises RecordNotFoundException: when the cross-reference holds no such account or card
     *     (mapped to HTTP 404).
     */
    @GetMapping("/keys")
    public TransactionKeyResponseDto resolveKeys(
            @RequestParam(name = "acctId", required = false) String acctId,
            @RequestParam(name = "tranCardNum", required = false) String tranCardNum) {
        return transactionService.resolveKeys(acctId, tranCardNum);
    }

    /**
     * :purpose: View a single transaction by its 16-character zero-padded id (CICS ``CT01`` /
     *     ``COTRN01C``). The empty/blank-id guard and the not-found lookup are business logic
     *     owned by the service; the raw parameter is passed straight through without local
     *     validation, so the service's own literals answer every value.
     * :param tranId: the 16-character zero-padded transaction id in its String wire form,
     *     carried as a REQUEST PARAMETER rather than a path segment. ``TRNIDIN`` is an ``X(16)``
     *     field an operator may fill with any characters, and a value holding a path separator
     *     does not survive as a path segment: an intermediary normalizes the encoded form back
     *     into ``/..`` before it routes, which lifted the request out of the API prefix and
     *     answered it with the SPA document instead of this endpoint -- a silently dead screen. A
     *     query parameter is not path-normalized, so the value arrives verbatim and misses the
     *     read, which is what the legacy READ does too.
     * :param httpRequest: the current servlet request; its already-established session, when
     *     present, backs the externalized session context.
     * :returns: the view response DTO serialized as JSON (HTTP 200).
     * :raises CardDemoException: for an empty or blank id (mapped to HTTP 400 by the shared
     *     ``GlobalExceptionHandler``).
     * :raises RecordNotFoundException: when no transaction exists for the id (mapped to HTTP
     *     404 by the shared ``GlobalExceptionHandler``).
     */
    @GetMapping("/detail")
    public TransactionViewResponseDto viewTransaction(
            @RequestParam(name = "tranId", required = false) String tranId,
            HttpServletRequest httpRequest) {
        SessionContext sessionContext = resolveSessionContext(httpRequest);
        TransactionViewResponseDto response = transactionService.viewTransaction(tranId, sessionContext);
        storeSessionContext(httpRequest, sessionContext);
        return response;
    }

    /**
     * :purpose: Resolve the add screen's two key fields from either one (``COTRN02C``
     *     ``VALIDATE-INPUT-KEY-FIELDS``). The legacy program runs that paragraph BEFORE its eleven
     *     data-field blank guards, and the cross-reference read inside it is what publishes
     *     ``Account ID NOT found...`` and ``Card Number NOT found...``; exposing it as its own
     *     step is what keeps those two literals reachable while a data field is still empty. It is
     *     also the first thing ``COPY-LAST-TRAN-DATA`` performs, which is why the copy-last action
     *     fills BOTH key fields.
     * :param request: the key entry, carrying an account id, a card number, or neither. It is
     *     a request BODY because one of the two members is a card number, and a URL is recorded
     *     verbatim by every intermediary on the path.
     * :param httpRequest: the current servlet request; its already-established session, when
     *     present, backs the externalized session context.
     * :returns: both keys at their declared widths (HTTP 200).
     * :raises CardDemoException: when a key is non-numeric or neither key is present (HTTP
     *     400).
     * :raises RecordNotFoundException: when the cross-reference holds no such key (HTTP 404).
     */
    @PostMapping("/key")
    public TransactionKeyDto resolveAddKey(@Valid @RequestBody TransactionKeyDto request,
                                           HttpServletRequest httpRequest) {
        SessionContext sessionContext = resolveSessionContext(httpRequest);
        TransactionKeyDto response = transactionService.resolveAddKey(request);
        storeSessionContext(httpRequest, sessionContext);
        return response;
    }

    /**
     * :purpose: Add a transaction (CICS ``CT02`` / ``COTRN02C``). The Y/N confirmation
     *  flag, the ordered field validations, the 16-digit id generation, and the success
     *  message are all service-owned; a successful add returns HTTP 201.
     * :param request: the validated add request DTO; bean-validation violations become
     *  an HTTP 400 with per-field errors produced by the shared ``GlobalExceptionHandler``.
     * :param httpRequest: the current servlet request; its already-established session,
     *  when present, backs the externalized session context.
     * :returns: the add response DTO carrying the created transaction and its generated
     *  id (HTTP 201).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionAddResponseDto addTransaction(@Valid @RequestBody TransactionAddRequestDto request,
                                                    HttpServletRequest httpRequest) {
        SessionContext sessionContext = resolveSessionContext(httpRequest);
        TransactionAddResponseDto response = transactionService.addTransaction(request, sessionContext);
        storeSessionContext(httpRequest, sessionContext);
        return response;
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
     *  session so any paging cursor, last-map or selected-id state the service updated is seen
     *  by the next stateless request. A caller without a session carries no
     *  pseudo-conversational state, and none is created for it.
     * :param httpRequest: the current servlet request.
     * :param sessionContext: the session context to persist.
     */
    private void storeSessionContext(HttpServletRequest httpRequest, SessionContext sessionContext) {
        SessionContextSupport.store(httpRequest, sessionContext);
    }
}
