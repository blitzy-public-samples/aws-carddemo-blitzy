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
import com.carddemo.common.dto.SessionContextSupport;
import com.carddemo.common.exception.CardDemoException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
     * :purpose: Verbatim account-filter edit message of the VIEW screen
     *  (``COACTVWC`` ``2210-EDIT-ACCOUNT``, L672). The double space after ``must`` and
     *  the hyphen in ``non-zero`` are the source literal's own; both are preserved.
     * :note: The two screens publish DIFFERENT text for the same edit, so one shared
     *  constant could not be right for both. ``COACTVWC`` and ``COACTUPC`` each also
     *  DECLARE an 88-level reading ``'Account number must be a non zero 11 digit
     *  number'`` (L125/L127 and L493/L495) that neither program ever ``SET``s — a dead
     *  condition name. Serving it here published text no legacy screen can emit.
     */
    private static final String MSG_ACCT_FILTER_INVALID =
            "Account Filter must  be a non-zero 11 digit number";

    /**
     * :purpose: Verbatim account-key edit message of the UPDATE screen
     *  (``COACTUPC`` ``1210-EDIT-ACCOUNT``, L1806-L1809, assembled by ``STRING``
     *  ``'Account Number if supplied must be a 11 digit'`` + ``' Non-Zero Number'``).
     */
    private static final String MSG_ACCT_NUMBER_INVALID =
            "Account Number if supplied must be a 11 digit Non-Zero Number";

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
        Long acctId = parseAccountId(id, MSG_ACCT_FILTER_INVALID);
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
        Long acctId = parseAccountId(id, MSG_ACCT_NUMBER_INVALID);
        SessionContext context = resolveSessionContext(httpRequest);
        AccountUpdateResponseDto response = accountService.updateAccount(acctId, request, context);
        storeSessionContext(httpRequest, context);
        return response;
    }

    /**
     * :purpose: Run the ``COACTUPC`` edit pass over a submission without rewriting anything
     *  -- the ENTER half of CICS ``CAUP``, whose ``1200-EDIT-MAP-INPUTS`` runs inside the
     *  program before ``CHANGES-OK-NOT-CONFIRMED`` is set and the operator is invited to
     *  press PF5. The edits and their frozen literals live in the service, so the screen
     *  reports ``Changes validated.Press F5 to save`` only once they have actually run
     *  against the values on display.
     * :param id: the account id path variable (``ACCT-ID PIC 9(11)``).
     * :param request: the editable account and customer fields as currently entered.
     * :param httpRequest: the current servlet request, carrying the externalized context.
     * :returns: HTTP 204 with no body when every edit passes; the failing edit is reported
     *  through the shared error envelope instead.
     * :raises CardDemoException: when the id is not a non-zero eleven-digit number, when the
     *  submission carries no field, when it matches the display-time snapshot, or when a
     *  field edit fails (HTTP 400 / 422 through the shared handler).
     */
    @PostMapping("/{id}/validate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void validateAccountUpdate(@PathVariable("id") String id,
                                      @Valid @RequestBody AccountUpdateRequestDto request,
                                      HttpServletRequest httpRequest) {
        Long acctId = parseAccountId(id, MSG_ACCT_NUMBER_INVALID);
        // The context is resolved so this route is gated and audited exactly as the rewrite
        // is, and so an expired session is reported here rather than at PF5.
        resolveSessionContext(httpRequest);
        accountService.validateAccountUpdate(acctId, request);
    }

    /**
     * :purpose: Edit the account-id path variable per the legacy
     *  ``COACTVWC``/``COACTUPC`` ``2210-EDIT-ACCOUNT`` rule (``ACCT-ID PIC 9(11)``):
     *  the key must be EXACTLY eleven ASCII digits and must not be all zeros.
     * :param id: the raw ``{id}`` path variable.
     * :param message: the verbatim edit literal of the screen making the call, because
     *  the view and update screens publish different text for the same edit.
     * :returns: the validated account id as a ``Long``.
     * :raises CardDemoException: when the id is not eleven ASCII digits or is all zeros (HTTP 400).
     * :note: The width is exact, not a maximum. Both programs receive the key in a
     *  ``PIC X(11)`` map field and test it with ``IF CC-ACCT-ID IS NOT NUMERIC``
     *  (``COACTVWC`` L665, ``COACTUPC`` L1801); a class test on an alphanumeric item is
     *  true only when EVERY character is a digit, and BMS blank-pads the positions the
     *  operator did not type. A shorter run therefore arrives as digits followed by
     *  spaces and is refused. Accepting one to eleven digits let an unpadded id resolve
     *  the same record as its zero-padded form, which is the observable divergence.
     *  ``\\d`` is deliberate rather than a ``Character.isDigit`` loop: the regex class is
     *  ASCII-only, so a full-width Unicode digit cannot slip through and then be
     *  normalised by ``Long.valueOf``.
     */
    private Long parseAccountId(String id, String message) {
        if (id == null || !id.matches("\\d{11}") || id.chars().allMatch(c -> c == '0')) {
            throw new CardDemoException(message);
        }
        return Long.valueOf(id);
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
     * :param context: the session context to persist.
     */
    private void storeSessionContext(HttpServletRequest httpRequest, SessionContext context) {
        SessionContextSupport.store(httpRequest, context);
    }
}
