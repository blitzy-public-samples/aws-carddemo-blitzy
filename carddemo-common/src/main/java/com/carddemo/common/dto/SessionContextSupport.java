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
package com.carddemo.common.dto;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * :purpose: Single shared reader and writer of the externalized pseudo-conversational
 *     {@link SessionContext} (the ``app/cpy/COCOM01Y.cpy`` COMMAREA replacement) for every
 *     CardDemo controller. One implementation guarantees one behaviour: a request whose
 *     session carries no usable context is REFUSED rather than being handed a fabricated
 *     blank context whose absent ``CDEMO-USER-TYPE`` would gate nothing.
 * :output: Static ``require`` and ``store`` operations; the class is not instantiable.
 * :note: Fails closed. ``require`` never returns a synthesized context, and ``store`` never
 *     creates a session, so an anonymous call persists no Spring Session entry.
 */
public final class SessionContextSupport {

    /**
     * :purpose: Message of the refusal raised when an authenticated request carries no
     *     usable session context.
     */
    public static final String NO_SESSION_CONTEXT_MESSAGE =
            "No CardDemo session context on the authenticated session; sign on again";

    /**
     * :purpose: Prevent instantiation of this static helper.
     */
    private SessionContextSupport() {
    }

    /**
     * :purpose: Resolve the session context from the request's ALREADY-established HTTP
     *     session under the one shared attribute key.
     * :param httpRequest: the current servlet request.
     * :returns: the {@link SessionContext} the session carries; never ``null``.
     * :raises IllegalStateException: when the caller has no session, the session carries no
     *     context, or the stored attribute is of an unexpected type (the Redis
     *     serialization-drift case). Every protected route is gated by the shared filter
     *     chain, so a request cannot legitimately reach a controller in that state.
     */
    public static SessionContext require(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest == null ? null : httpRequest.getSession(false);
        Object attribute = session == null
                ? null
                : session.getAttribute(SessionAttributes.SESSION_CONTEXT);
        if (attribute instanceof SessionContext sessionContext) {
            return sessionContext;
        }
        throw new IllegalStateException(NO_SESSION_CONTEXT_MESSAGE);
    }

    /**
     * :purpose: Flush the (possibly mutated) session context back to the caller's EXISTING
     *     HTTP session so the next stateless request observes the updated COMMAREA
     *     replacement.
     * :param httpRequest: the current servlet request.
     * :param sessionContext: the context to publish for the next interaction.
     */
    public static void store(HttpServletRequest httpRequest, SessionContext sessionContext) {
        HttpSession session = httpRequest == null ? null : httpRequest.getSession(false);
        if (session != null) {
            session.setAttribute(SessionAttributes.SESSION_CONTEXT, sessionContext);
        }
    }
}
