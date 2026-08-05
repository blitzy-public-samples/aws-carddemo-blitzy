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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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

    /**
     * :purpose: ``HttpSession`` attribute key under which the externalized
     *  {@link SessionContext} (COMMAREA replacement) is stored in Spring Session
     *  (Redis); kept identical across services so they share one session attribute.
     */
    private static final String SESSION_CONTEXT_ATTRIBUTE = SessionContext.SESSION_ATTRIBUTE_NAME;

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
     * :purpose: Resolve the externalized session context from the request's
     *  *already-established* HTTP session, returning an empty one when the caller has no
     *  session or it carries none (pre-navigation or tests). ``getSession(false)`` never
     *  creates a session, so an anonymous call persists no Spring Session entry.
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
     *  session so the next stateless request sees the updated COMMAREA replacement. Only
     *  an existing session is written to: a caller without one carries no
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
