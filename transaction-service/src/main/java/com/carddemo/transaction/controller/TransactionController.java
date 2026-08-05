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

import jakarta.servlet.http.HttpServletRequest;
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
    private static final String SESSION_CONTEXT_ATTR = SessionContext.SESSION_ATTRIBUTE_NAME;

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
                                                        HttpServletRequest httpRequest) {
        SessionContext sessionContext = resolveSessionContext(httpRequest);
        TransactionListResponseDto response = transactionService.listTransactions(request, sessionContext);
        storeSessionContext(httpRequest, sessionContext);
        return response;
    }

    /**
     * :purpose: View a single transaction by its 16-character zero-padded id (CICS
     *  ``CT01`` / ``COTRN01C``). The empty/blank-id guard and the not-found lookup are
     *  business logic owned by the service; the raw path variable is passed straight
     *  through without local validation.
     * :param id: the 16-character zero-padded transaction id in its String wire form.
     * :param httpRequest: the current servlet request; its already-established session,
     *  when present, backs the externalized session context.
     * :returns: the view response DTO serialized as JSON (HTTP 200).
     * :raises CardDemoException: for an empty or blank id (mapped to HTTP 400 by the
     *  shared ``GlobalExceptionHandler``).
     * :raises RecordNotFoundException: when no transaction exists for the id (mapped to
     *  HTTP 404 by the shared ``GlobalExceptionHandler``).
     */
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

    @GetMapping("/{id}")
    public TransactionViewResponseDto viewTransaction(@PathVariable("id") String id,
                                                      HttpServletRequest httpRequest) {
        SessionContext sessionContext = resolveSessionContext(httpRequest);
        TransactionViewResponseDto response = transactionService.viewTransaction(id, sessionContext);
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
     * :purpose: Resolve the externalized pseudo-conversational session context from the
     *  request's *already-established* servlet session, returning a fresh instance when
     *  the caller has no session or none is present (COMMAREA bridge, AAP section
     *  0.6.3). ``getSession(false)`` never creates a session, so an anonymous call
     *  persists no Spring Session entry.
     * :param httpRequest: the current servlet request.
     * :returns: the stored {@link SessionContext}, or a new instance when the caller has
     *  no session, or the attribute is absent or of an unexpected type.
     */
    private SessionContext resolveSessionContext(HttpServletRequest httpRequest) {
        HttpSession httpSession = httpRequest.getSession(false);
        if (httpSession == null) {
            return new SessionContext();
        }
        Object attribute = httpSession.getAttribute(SESSION_CONTEXT_ATTR);
        if (attribute instanceof SessionContext sessionContext) {
            return sessionContext;
        }
            throw new IllegalStateException(
                    "No CardDemo session context on the authenticated session; sign on again");
    }

    /**
     * :purpose: Re-store the (possibly mutated) session context so any paging cursor,
     *  last-map, or selected-id state the service updated is flushed to the session for
     *  the next stateless request. Only an existing session is written to: a caller
     *  without one carries no pseudo-conversational state to preserve.
     * :param httpRequest: the current servlet request.
     * :param sessionContext: the session context to persist.
     */
    private void storeSessionContext(HttpServletRequest httpRequest, SessionContext sessionContext) {
        HttpSession httpSession = httpRequest.getSession(false);
        if (httpSession != null) {
            httpSession.setAttribute(SESSION_CONTEXT_ATTR, sessionContext);
        }
    }
}
