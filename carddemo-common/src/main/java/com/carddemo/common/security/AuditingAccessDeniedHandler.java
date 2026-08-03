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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * :purpose: Answer an authenticated-but-unauthorized request with HTTP
 *     ``403 Forbidden`` and write one audit record naming the denied principal, so a
 *     role-boundary probe (for example a ``ROLE_USER`` session reaching an
 *     administrator-only route) is always visible in the log stream. A denial raised for
 *     a caller who is not authenticated at all is handed to the ``401`` entry point
 *     instead, so an unauthenticated caller always receives ``401`` whatever refused it.
 * :output: A body-less ``403`` response plus one ``WARN`` audit record; or, for an
 *     unauthenticated caller, the body-less ``401`` and the audit record the entry point
 *     writes.
 * :note: The delegation is required because ``CsrfFilter`` runs BEFORE
 *     ``AnonymousAuthenticationFilter``, so a missing-token denial on a write reaches this
 *     handler with an empty ``SecurityContext``. Spring Security's
 *     ``ExceptionTranslationFilter`` decides between the entry point and this handler with
 *     ``AuthenticationTrustResolver``, whose ``isAnonymous(null)`` is ``false`` — which
 *     turned an unauthenticated ``POST`` into ``403`` while an unauthenticated ``GET``
 *     answered ``401``. The SPA redirects to sign-on on ``401`` only, so the inconsistency
 *     left an expired session silently refused instead of returning the user to sign-on.
 * :note: As with the ``401`` entry point the response carries no body: the denied caller
 *     is told only that the request was refused, while the shared hardened headers and
 *     the ``X-Correlation-Id`` on the response tie it to the audit record above.
 */
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    /**
     * :purpose: Distinguish a genuinely authenticated principal from ``null`` or an
     *     anonymous token, exactly as ``ExceptionTranslationFilter`` does.
     */
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    /**
     * :purpose: The ``401`` entry point a denial for an unauthenticated caller is handed
     *     to, so the status and the audit record are produced in exactly one place.
     */
    private final AuthenticationEntryPoint unauthenticatedEntryPoint = new AuditingAuthenticationEntryPoint();

    /**
     * :purpose: Log the denial and send ``403``, or delegate to the ``401`` entry point
     *     when the caller carries no authenticated principal.
     * :param request: the denied request.
     * :param response: the response to complete with ``403`` (or ``401``).
     * :param accessDeniedException: the triggering Spring Security exception.
     * :raises IOException: if the delegated ``401`` response cannot be written.
     * :raises ServletException: propagated by the delegated entry point.
     */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || trustResolver.isAnonymous(authentication)) {
            // Exactly one audit record is written, by the entry point.
            unauthenticatedEntryPoint.commence(request, response,
                    new InsufficientAuthenticationException(
                            "Full authentication is required to access this resource",
                            accessDeniedException));
            return;
        }
        SecurityAuditLogger.authorizationDenied(
                authentication.getName(),
                request,
                accessDeniedException == null
                        ? "access denied"
                        : accessDeniedException.getClass().getSimpleName());
        if (!response.isCommitted()) {
            // Status is set directly (never sendError) so the container ERROR dispatch
            // is not started and the chain is not re-entered.
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
