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
package com.carddemo.account.controller;

import com.carddemo.account.service.AccountService;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.dto.AccountUpdateResponseDto;
import com.carddemo.common.dto.AccountViewResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.exception.CardDemoException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * :purpose: REST entry point for the CardDemo account microservice. Exposes
 *  ``GET /accounts/{id}`` (CICS ``CAVW``, program ``COACTVWC``) for the read-only
 *  account-and-customer view and ``PUT /accounts/{id}`` (CICS ``CAUP``, program
 *  ``COACTUPC``) for the optimistic-locked account-and-customer update. This thin
 *  adapter edits the account-id path variable, validates the update body with
 *  ``@Valid``, bridges the externalized session context, and delegates all
 *  business logic to {@link AccountService}; it holds no persistence, transaction,
 *  read-order, or optimistic-lock logic and declares no local exception handling.
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    /**
     * :purpose: Verbatim account-id edit message (legacy ``COACTVWC``/``COACTUPC``
     *  ``2210-EDIT-ACCOUNT``; ``ACCT-ID PIC 9(11)``); surfaced as the HTTP 400 body
     *  message when the path id is not a non-zero eleven-digit number.
     */
    private static final String MSG_INVALID_ACCT_ID = "Account number must be a non zero 11 digit number";

    /**
     * :purpose: ``HttpSession`` attribute key under which the externalized
     *  {@link SessionContext} (the pseudo-conversational COMMAREA replacement) is
     *  stored in Spring Session.
     */
    private static final String SESSION_CONTEXT_ATTRIBUTE = SessionContext.SESSION_ATTRIBUTE_NAME;

    /** :purpose: Account view/update business-logic service (``COACTVWC``/``COACTUPC``). */
    private final AccountService accountService;

    /**
     * :purpose: Construct the controller with its account service collaborator.
     * :param accountService: the account view/update business-logic service.
     */
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * :purpose: View a single account and its associated customer (CICS ``CAVW``,
     *  program ``COACTVWC``). Edits the path id, bridges the externalized session,
     *  and delegates the ordered short-circuit read to {@link AccountService}.
     * :param id: the account id path variable (``ACCT-ID PIC 9(11)``).
     * :param httpRequest: the current servlet request; its already-established session,
     *  when present, carries the externalized context.
     * :returns: the read-only account view response (HTTP 200).
     * :raises CardDemoException: when the id is not a non-zero eleven-digit number (HTTP 400).
     * :raises RecordNotFoundException: when the cross-reference, account, or customer is not found (HTTP 404).
     */
    @GetMapping("/{id}")
    public AccountViewResponseDto viewAccount(@PathVariable("id") String id, HttpServletRequest httpRequest) {
        Long acctId = parseAccountId(id);
        SessionContext context = resolveSessionContext(httpRequest);
        AccountViewResponseDto response = accountService.viewAccount(acctId, context);
        storeSessionContext(httpRequest, context);
        return response;
    }

    /**
     * :purpose: Update an account and its associated customer under optimistic
     *  locking (CICS ``CAUP``, program ``COACTUPC``). Edits the path id, validates
     *  the request body, bridges the externalized session, and delegates the
     *  single-transaction read-snapshot-compare-rewrite to {@link AccountService}.
     * :param id: the account id path variable (``ACCT-ID PIC 9(11)``).
     * :param request: the validated editable account and customer master fields.
     * :param httpRequest: the current servlet request; its already-established session,
     *  when present, carries the externalized context.
     * :returns: the post-update account response (HTTP 200).
     * :raises CardDemoException: when the id is not a non-zero eleven-digit number (HTTP 400).
     * :raises RecordNotFoundException: when the cross-reference, account, or customer is not found (HTTP 404).
     * :raises OptimisticLockConflictException: when the account was modified concurrently (HTTP 409).
     */
    @PutMapping("/{id}")
    public AccountUpdateResponseDto updateAccount(@PathVariable("id") String id,
                                                  @Valid @RequestBody AccountUpdateRequestDto request,
                                                  HttpServletRequest httpRequest) {
        Long acctId = parseAccountId(id);
        SessionContext context = resolveSessionContext(httpRequest);
        AccountUpdateResponseDto response = accountService.updateAccount(acctId, request, context);
        storeSessionContext(httpRequest, context);
        return response;
    }

    /**
     * :purpose: Edit the account-id path variable per the legacy
     *  ``COACTVWC``/``COACTUPC`` ``2210-EDIT-ACCOUNT`` rule (``ACCT-ID PIC 9(11)``):
     *  the id must be numeric, non-zero, and at most eleven digits.
     * :param id: the raw ``{id}`` path variable.
     * :returns: the validated account id as a ``Long``.
     * :raises CardDemoException: when the id is non-numeric, all zeros, or longer than eleven digits (HTTP 400).
     */
    private Long parseAccountId(String id) {
        if (id == null || !id.matches("\\d{1,11}") || id.chars().allMatch(c -> c == '0')) {
            throw new CardDemoException(MSG_INVALID_ACCT_ID);
        }
        return Long.valueOf(id);
    }

    /**
     * :purpose: Resolve the externalized session context from the request's
     *  *already-established* HTTP session, returning a fresh empty context when the
     *  caller has no session or the session carries no context (pre-navigation /
     *  tests). ``getSession(false)`` never creates a session, so an anonymous call
     *  persists no Spring Session entry.
     * :param httpRequest: the current servlet request.
     * :returns: the existing {@link SessionContext}, or a new empty instance.
     */
    private SessionContext resolveSessionContext(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            return new SessionContext();
        }
        Object attribute = session.getAttribute(SESSION_CONTEXT_ATTRIBUTE);
        return attribute instanceof SessionContext context ? context : new SessionContext();
    }

    /**
     * :purpose: Flush the (possibly mutated) session context back to the caller's HTTP
     *  session so the next stateless request sees the updated COMMAREA replacement.
     *  Only an existing session is written to: a caller without one carries no
     *  pseudo-conversational state to preserve.
     * :param httpRequest: the current servlet request.
     * :param context: the session context to persist.
     */
    private void storeSessionContext(HttpServletRequest httpRequest, SessionContext context) {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.setAttribute(SESSION_CONTEXT_ATTRIBUTE, context);
        }
    }
}
