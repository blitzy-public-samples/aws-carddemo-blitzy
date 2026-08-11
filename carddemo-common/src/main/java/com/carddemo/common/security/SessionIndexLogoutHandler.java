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
package com.carddemo.common.security;

import com.carddemo.common.dto.SessionAttributes;
import com.carddemo.common.dto.SessionContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * :purpose: Remove the session being signed out from the principal-to-session index, so the
 *     index holds only sessions that can still authorize. Without this the index accumulates
 *     the ids of every session a user ever signed on with, and a later administrator
 *     revocation reports a revoked-session count that includes sessions the user had already
 *     signed out of.
 * :output: Registered on the logout chain of the browser-facing surface; it performs the
 *     de-index and leaves session invalidation to Spring Security's own handlers.
 * :note: Runs BEFORE the session is invalidated, because both the user id (carried by the
 *     {@link SessionContext} attribute) and the session id are read from the live session.
 * :note: Inert by design when the index has no Redis collaborators (a web-layer slice test),
 *     because :java:meth:`SessionPrincipalIndex.deregister` is itself a no-op there.
 */
public class SessionIndexLogoutHandler implements LogoutHandler {

    private final SessionPrincipalIndex sessionPrincipalIndex;

    /**
     * :purpose: Construct the handler.
     * :param sessionPrincipalIndex: the shared principal-to-session index to update.
     */
    public SessionIndexLogoutHandler(SessionPrincipalIndex sessionPrincipalIndex) {
        this.sessionPrincipalIndex = sessionPrincipalIndex;
    }

    /**
     * :purpose: De-index the session that is being signed out.
     * :param request: the logout request.
     * :param response: the logout response; not written to here.
     * :param authentication: the principal being signed out, or ``null``.
     */
    @Override
    public void logout(HttpServletRequest request,
                       HttpServletResponse response,
                       Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        String userId = resolveUserId(session, authentication);
        if (userId == null) {
            return;
        }
        sessionPrincipalIndex.deregister(userId, session.getId());
    }

    /**
     * :purpose: Resolve the security-user id whose index entry must be removed.
     * :param session: the live session being signed out.
     * :param authentication: the principal being signed out, or ``null``.
     * :returns: the user id carried by the session context, falling back to the
     *     authenticated principal name, or ``null`` when neither is available.
     */
    private String resolveUserId(HttpSession session, Authentication authentication) {
        Object attribute = session.getAttribute(SessionAttributes.SESSION_CONTEXT);
        if (attribute instanceof SessionContext sessionContext) {
            String userId = sessionContext.getUserId();
            if (userId != null && !userId.isBlank()) {
                return userId;
            }
        }
        return authentication == null ? null : authentication.getName();
    }
}
