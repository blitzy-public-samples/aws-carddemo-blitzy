/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.gateway.controller;

import com.carddemo.common.dto.SessionAttributes;
import com.carddemo.common.dto.SessionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * :purpose: Publish the identity half of the externalized COMMAREA so the single-page
 *  application can resolve who is signed on, and with which ``CDEMO-USER-TYPE``, from the
 *  server-held session instead of from client-writable browser storage. The legacy screens
 *  received ``CDEMO-USER-ID`` / ``CDEMO-USER-TYPE`` in the COMMAREA on every
 *  pseudo-conversational turn; a stateless SPA needs one route that returns the same two
 *  fields (AAP 0.3.4, 0.6.3).
 * :output: ``GET /session`` returning the signed-on user id and role, or both fields empty
 *  when the session carries no sign-on context.
 */
@RestController
public class SessionController {

    /**
     * :purpose: Report the signed-on identity carried by the shared session.
     * :param httpRequest: the current servlet request; the already-established session is
     *  read through ``getSession(false)`` so the probe never creates one.
     * :returns: the :java:type:`SessionIdentity` of the signed-on caller, or
     *  :data:`ANONYMOUS` when no session, no session context, or no user type is present,
     *  so an expired or revoked session reads the same as never having signed on.
     * :note: The empty identity is returned rather than a ``401`` because this route
     *  answers a question about the caller's own session instead of guarding a resource.
     *  Refusing the question made the sign-on screen's boot probe fail on every cold load,
     *  which the browser records as a page error. Nothing is disclosed either way: the
     *  anonymous answer carries no identity at all, and the protected routes are unchanged.
     */
    @GetMapping("/session")
    public SessionIdentity currentSession(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        Object attribute = (session == null)
                ? null
                : session.getAttribute(SessionAttributes.SESSION_CONTEXT);
        if (!(attribute instanceof SessionContext context)
                || context.getUserId() == null
                || context.getUserType() == null) {
            return ANONYMOUS;
        }
        return new SessionIdentity(context.getUserId(),
                String.valueOf(context.getUserType().getCode()));
    }

    /**
     * Identity half of the externalized COMMAREA.
     *
     * :param userId: the signed-on user id (``CDEMO-USER-ID`` / ``SEC-USR-ID``), or
     *  ``null`` when no session is signed on.
     * :param userType: the signed-on role code (``CDEMO-USER-TYPE``): ``"A"`` for
     *  ``CDEMO-USRTYP-ADMIN`` or ``"U"`` for ``CDEMO-USRTYP-USER``, or ``null`` when no
     *  session is signed on.
     */
    public record SessionIdentity(String userId, String userType) {
    }

    /**
     * :purpose: The answer for a caller carrying no sign-on context. Both fields are null,
     *  which is neither a valid ``CDEMO-USER-ID`` nor a valid ``CDEMO-USER-TYPE``, so a
     *  client cannot mistake it for an identity.
     */
    private static final SessionIdentity ANONYMOUS = new SessionIdentity(null, null);
}
