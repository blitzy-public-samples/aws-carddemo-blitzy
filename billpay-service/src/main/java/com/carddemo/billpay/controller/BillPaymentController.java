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
package com.carddemo.billpay.controller;

import com.carddemo.billpay.service.BillPaymentService;
import com.carddemo.common.dto.BillPaymentRequestDto;
import com.carddemo.common.dto.BillPaymentResponseDto;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.dto.SessionContextSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * :purpose: REST entry point for the CardDemo bill-payment microservice. Exposes the
 *  single endpoint ``POST /billpay`` (CICS transaction ``CB00``, legacy program
 *  ``COBIL00C``): it validates the request body, bridges the externalized
 *  pseudo-conversational session, and delegates all behavior to
 *  {@link BillPaymentService}. It holds no business logic, no persistence, no
 *  transactional boundary and no user-facing message text of its own.
 * :note: PF-key navigation from the legacy ``COBIL00`` screen is preserved as
 *  client-side React SPA hints only; it is not a backend input and has no request
 *  field or endpoint here. ENTER submits the payment (this ``POST /billpay``); PF3
 *  returns to the main menu (legacy ``COMEN01C``, or ``CDEMO-FROM-PROGRAM`` when set);
 *  PF4 clears the current screen.
 */
@RestController
@RequestMapping("/billpay")
public class BillPaymentController {

    /** :purpose: Bill-payment collaborator that owns all migrated ``COBIL00C`` behavior. */
    private final BillPaymentService billPaymentService;

    /**
     * :purpose: Construct the controller with its service collaborator via explicit
     *  constructor injection (no field or setter injection).
     * :param billPaymentService: the service performing the migrated ``COBIL00C``
     *  bill-payment logic.
     */
    public BillPaymentController(BillPaymentService billPaymentService) {
        this.billPaymentService = billPaymentService;
    }

    /**
     * :purpose: Process an online bill payment (CICS ``CB00``, program ``COBIL00C``) —
     *  pay the account balance in full and record the payment transaction. Validates the
     *  request body, resolves and re-persists the externalized session, and delegates the
     *  entire flow to {@link BillPaymentService}.
     * :param request: the validated bill-payment request carrying the account id
     *  (``ACTIDIN``) and the confirm flag (``CONFIRM``).
     * :param httpRequest: the current servlet request; its already-established session,
     *  when present, carries the externalized {@link SessionContext}.
     * :returns: the bill-payment response carrying the account id, the balance to
     *  display, the generated 16-digit transaction id (on a posted payment) and the
     *  outcome message (HTTP 200).
     * :raises CardDemoException: (HTTP 400) empty account id, invalid confirm value,
     *  a zero-or-negative balance, or a duplicate transaction id.
     * :raises RecordNotFoundException: (HTTP 404) account or card cross-reference not
     *  found.
     * :raises OptimisticLockConflictException: (HTTP 409) concurrent account
     *  modification.
     */
    @PostMapping
    public BillPaymentResponseDto processBillPayment(@Valid @RequestBody BillPaymentRequestDto request,
                                                     HttpServletRequest httpRequest) {
        SessionContext context = resolveSessionContext(httpRequest);
        BillPaymentResponseDto response = billPaymentService.processBillPayment(request, context);
        storeSessionContext(httpRequest, context);
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
     *  session so the next stateless request sees the updated COMMAREA replacement. A caller
     *  without a session carries no pseudo-conversational state, and none is created for it.
     * :param httpRequest: the current servlet request.
     * :param context: the session context to persist.
     */
    private void storeSessionContext(HttpServletRequest httpRequest, SessionContext context) {
        SessionContextSupport.store(httpRequest, context);
    }
}
