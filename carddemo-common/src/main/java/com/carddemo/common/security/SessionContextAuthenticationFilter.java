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

import com.carddemo.common.dto.SessionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * :purpose: Restore the authenticated principal and its role authority for every request
 *     from the shared, Redis-backed pseudo-conversational {@link SessionContext} written by
 *     ``auth-service`` sign-on, so the ``ROLE_ADMIN`` / ``ROLE_USER`` authorization rules of
 *     the API gateway and of every microservice can be satisfied without persisting Spring
 *     Security's own ``SPRING_SECURITY_CONTEXT`` attribute. This is the servlet-layer
 *     equivalent of the CICS COMMAREA carrying ``CDEMO-USER-TYPE`` from ``COSGN00C`` into
 *     every subsequent transaction.
 * :output: A request-scoped {@link SecurityContext} holding a {@link
 *     UsernamePasswordAuthenticationToken} whose name is the signed-on user id and whose
 *     single authority is derived from ``CDEMO-USER-TYPE``.
 * :note: The filter NEVER creates an HTTP session (it uses ``request.getSession(false)``),
 *     so an anonymous request cannot cause a persisted Redis session to be minted. It also
 *     never mutates the session, so it is safe on read-only and stateless chains. It does not
 *     run at all for the anonymous ``/auth/**`` sign-on routes -- see {@link
 *     #shouldNotFilter(HttpServletRequest)} -- so no hop holds a handle to, or reads, the
 *     session that a sign-on rotates.
 * :note: The authentication is intentionally not persisted between requests: the session
 *     context itself is the durable state, and rebuilding the authentication from it on every
 *     request means a revoked or expired session immediately stops authorizing, and the Redis
 *     payload contains only CardDemo types (keeping the allowlisted JSON serializer
 *     effective).
 */
public class SessionContextAuthenticationFilter extends OncePerRequestFilter {

    /**
     * :purpose: Authority prefix Spring Security's ``hasRole`` / ``hasAnyRole``
     *     expressions prepend to a role name.
     */
    public static final String ROLE_PREFIX = "ROLE_";

    /**
     * :purpose: Authority granted to a ``CDEMO-USRTYP-ADMIN`` (``SEC-USR-TYPE`` 'A')
     *     principal.
     */
    public static final String ROLE_ADMIN = ROLE_PREFIX + "ADMIN";

    /**
     * :purpose: Authority granted to a ``CDEMO-USRTYP-USER`` (``SEC-USR-TYPE`` 'U')
     *     principal.
     */
    public static final String ROLE_USER = ROLE_PREFIX + "USER";

    /**
     * :purpose: Path of the sign-on endpoint (``COSGN00C`` / CICS ``CC00``), the one
     *     request that REPLACES the caller's session identity and therefore must not be
     *     served while holding a handle to the session it replaces.
     */
    public static final String SIGNON_PATH = "/auth/signon";

    /**
     * :purpose: Repository the restored context is published to, so the rest of the
     *     Spring Security chain can tell that the context was LOADED for this request
     *     rather than produced by an authentication that happened during it. Without
     *     this, ``SessionManagementFilter`` treats every request as a fresh
     *     authentication and applies its session-authentication strategy, which
     *     changes the session id on each request and discards the shared session.
     */
    private final SecurityContextRepository securityContextRepository;

    /**
     * :purpose: Distinguishes a real principal from an anonymous placeholder token, which
     *     this filter is allowed to replace.
     * :note: In the shared chain this filter now runs ahead of
     *     ``AnonymousAuthenticationFilter`` (it is installed before ``CsrfFilter`` so a
     *     token-less write can be classified against the real principal), so the context
     *     it inspects is normally empty. The check is retained because the same filter is
     *     also exercised behind a pre-populated context, and replacing a placeholder must
     *     stay correct wherever it is positioned.
     */
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    /**
     * :purpose: Build the filter with the request-scoped context repository used by the
     *     stateless CardDemo chains.
     */
    public SessionContextAuthenticationFilter() {
        this(new RequestAttributeSecurityContextRepository());
    }

    /**
     * :purpose: Skip the sign-on endpoint, the ONE request whose session identity is
     *     replaced while it is being served. Sign-on is unauthenticated, so no principal
     *     has to be restored for it; and because this filter is the only thing that
     *     touches the session on that path, skipping it means the process serving the
     *     request holds no session handle at all. That matters at the api-gateway: it
     *     used to load the session to rebuild the principal, ``auth-service`` then
     *     rotated the id (which deletes the previous Redis key), and Spring Session's
     *     write-back of the now-deleted session failed the response the gateway had
     *     already produced -- a successful sign-on reported to the caller as ``500``.
     *     Not touching the session removes the cause rather than the symptom, and drops a
     *     Redis round trip from the anonymous path.
     * :param request: the current HTTP request.
     * :returns: ``true`` for the sign-on endpoint, ``false`` for every other path.
     * :note: The path is compared with the context path removed, so a service deployed
     *     under one (``/api/auth/signon``) skips the same route. Only the rotation route
     *     is skipped: the rest of ``/auth/**`` -- and every other anonymous route -- still
     *     restores a principal when the caller already has a session, because nothing on
     *     those paths replaces it.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        return SIGNON_PATH.equals(path) || (SIGNON_PATH + "/").equals(path);
    }

    /**
     * :purpose: Build the filter with an explicit context repository, which MUST be the
     *     repository configured on the same ``HttpSecurity`` chain.
     * :param securityContextRepository: repository the restored context is saved to.
     */
    public SessionContextAuthenticationFilter(SecurityContextRepository securityContextRepository) {
        this.securityContextRepository = securityContextRepository;
    }

    /**
     * :purpose: Rebuild the authentication for the current request from the shared
     *     session context, leaving the context empty (anonymous) when no signed-on
     *     session exists.
     * :param request: the current HTTP request; inspected for an EXISTING session only.
     * :param response: the current HTTP response, passed through unchanged.
     * :param filterChain: the remainder of the filter chain.
     * :raises ServletException: if a downstream filter or the servlet fails.
     * :raises IOException: if request or response I/O fails.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean installed = false;
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        // An anonymous token is a placeholder, not a principal, so it is replaced.
        if (existing == null || !existing.isAuthenticated() || this.trustResolver.isAnonymous(existing)) {
            Authentication restored = restoreAuthentication(request);
            if (restored != null) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(restored);
                SecurityContextHolder.setContext(context);
                // Publishing the context marks it as loaded-from-repository for this
                // request, which keeps SessionManagementFilter from rotating the
                // session id on every request (the restoration is not a new
                // authentication - sign-on already rotated the id exactly once).
                this.securityContextRepository.saveContext(context, request, response);
                installed = true;
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Only the context this filter installed is removed, so an anonymous
            // token established by a later filter is left for the chain to clear.
            if (installed) {
                SecurityContextHolder.clearContext();
            }
        }
    }

    /**
     * :purpose: Read the shared session context from an existing session and convert
     *     it into an authenticated token.
     * :param request: the current HTTP request.
     * :returns: an authenticated {@link UsernamePasswordAuthenticationToken}, or
     *     ``null`` when there is no session, no session context, no user id, no
     *     resolvable user type, or the session was revoked while it was being read.
     */
    private Authentication restoreAuthentication(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object attribute = session.getAttribute(SessionContext.SESSION_ATTRIBUTE_NAME);
        if (!(attribute instanceof SessionContext sessionContext)) {
            return null;
        }
        String userId = sessionContext.getUserId();
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String authority = resolveAuthority(sessionContext.getUserType());
        if (authority == null) {
            return null;
        }
        if (isRevoked(session)) {
            return null;
        }
        return UsernamePasswordAuthenticationToken.authenticated(
                userId.trim(),
                null,
                List.of(new SimpleGrantedAuthority(authority)));
    }

    /**
     * :purpose: Re-read the session AFTER the context has been resolved and refuse it when
     *     the context has since been stripped or the revocation marker has been written, so
     *     a session revoked concurrently with this read cannot still authorize the request.
     * :param session: the session the context was read from.
     * :returns: ``true`` when the session no longer carries authority.
     */
    private boolean isRevoked(HttpSession session) {
        try {
            return session.getAttribute(SessionPrincipalIndex.REVOKED_REASON_ATTRIBUTE) != null
                    || !(session.getAttribute(SessionContext.SESSION_ATTRIBUTE_NAME) instanceof SessionContext);
        } catch (IllegalStateException ex) {
            // The store entry was deleted between the two reads, which invalidates the
            // handle: no session, therefore no authority.
            return true;
        }
    }

    /**
     * :purpose: Map the COMMAREA ``CDEMO-USER-TYPE`` 88-level to a Spring Security
     *     authority, preserving the legacy two-role model.
     * :param userType: the session-context user type, possibly ``null``.
     * :returns: ``ROLE_ADMIN`` for ``CDEMO_USRTYP_ADMIN``, ``ROLE_USER`` for
     *     ``CDEMO_USRTYP_USER``, or ``null`` when the type is absent.
     */
    private String resolveAuthority(SessionContext.UserType userType) {
        if (userType == null) {
            return null;
        }
        return switch (userType) {
            case CDEMO_USRTYP_ADMIN -> ROLE_ADMIN;
            case CDEMO_USRTYP_USER -> ROLE_USER;
        };
    }
}
