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
import com.carddemo.common.dto.TransactionAddRequestDto;
import com.carddemo.common.dto.TransactionAddResponseDto;
import com.carddemo.common.dto.TransactionListRequestDto;
import com.carddemo.common.dto.TransactionListResponseDto;
import com.carddemo.common.dto.TransactionViewResponseDto;
import com.carddemo.transaction.service.TransactionService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;
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

    /**
     * :purpose: ``HttpSession`` attribute key under which the externalized
     *  pseudo-conversational {@link SessionContext} is stored and retrieved.
     */
    private static final String SESSION_CONTEXT_ATTR = "carddemoSessionContext";

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
     * :param httpSession: the HTTP session backing the externalized session context.
     * :returns: the list response DTO serialized as JSON (HTTP 200).
     */
    @GetMapping
    public TransactionListResponseDto listTransactions(@ModelAttribute TransactionListRequestDto request,
                                                        HttpSession httpSession) {
        SessionContext sessionContext = resolveSessionContext(httpSession);
        TransactionListResponseDto response = transactionService.listTransactions(request, sessionContext);
        storeSessionContext(httpSession, sessionContext);
        return response;
    }

    /**
     * :purpose: View a single transaction by its 16-character zero-padded id (CICS
     *  ``CT01`` / ``COTRN01C``). The empty/blank-id guard and the not-found lookup are
     *  business logic owned by the service; the raw path variable is passed straight
     *  through without local validation.
     * :param id: the 16-character zero-padded transaction id in its String wire form.
     * :param httpSession: the HTTP session backing the externalized session context.
     * :returns: the view response DTO serialized as JSON (HTTP 200).
     * :raises CardDemoException: for an empty or blank id (mapped to HTTP 400 by the
     *  shared ``GlobalExceptionHandler``).
     * :raises RecordNotFoundException: when no transaction exists for the id (mapped to
     *  HTTP 404 by the shared ``GlobalExceptionHandler``).
     */
    @GetMapping("/{id}")
    public TransactionViewResponseDto viewTransaction(@PathVariable("id") String id,
                                                      HttpSession httpSession) {
        SessionContext sessionContext = resolveSessionContext(httpSession);
        TransactionViewResponseDto response = transactionService.viewTransaction(id, sessionContext);
        storeSessionContext(httpSession, sessionContext);
        return response;
    }

    /**
     * :purpose: Add a transaction (CICS ``CT02`` / ``COTRN02C``). The Y/N confirmation
     *  flag, the ordered field validations, the 16-digit id generation, and the success
     *  message are all service-owned; a successful add returns HTTP 201.
     * :param request: the validated add request DTO; bean-validation violations become
     *  an HTTP 400 with per-field errors produced by the shared ``GlobalExceptionHandler``.
     * :param httpSession: the HTTP session backing the externalized session context.
     * :returns: the add response DTO carrying the created transaction and its generated
     *  id (HTTP 201).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionAddResponseDto addTransaction(@Valid @RequestBody TransactionAddRequestDto request,
                                                    HttpSession httpSession) {
        SessionContext sessionContext = resolveSessionContext(httpSession);
        TransactionAddResponseDto response = transactionService.addTransaction(request, sessionContext);
        storeSessionContext(httpSession, sessionContext);
        return response;
    }

    /**
     * :purpose: Resolve the externalized pseudo-conversational session context from the
     *  servlet session, returning a fresh instance when none is present (COMMAREA
     *  bridge, AAP section 0.6.3).
     * :param httpSession: the servlet HTTP session.
     * :returns: the stored {@link SessionContext}, or a new instance when the attribute
     *  is absent or of an unexpected type.
     */
    private SessionContext resolveSessionContext(HttpSession httpSession) {
        Object attribute = httpSession.getAttribute(SESSION_CONTEXT_ATTR);
        if (attribute instanceof SessionContext sessionContext) {
            return sessionContext;
        }
        return new SessionContext();
    }

    /**
     * :purpose: Re-store the (possibly mutated) session context so any paging cursor,
     *  last-map, or selected-id state the service updated is flushed to the session for
     *  the next stateless request.
     * :param httpSession: the servlet HTTP session.
     * :param sessionContext: the session context to persist.
     */
    private void storeSessionContext(HttpSession httpSession, SessionContext sessionContext) {
        httpSession.setAttribute(SESSION_CONTEXT_ATTR, sessionContext);
    }
}
