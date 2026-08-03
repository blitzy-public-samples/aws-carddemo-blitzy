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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.dto.SessionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * :purpose: Unit tests for {@link SessionContextAuthenticationFilter}, the component
 *     that restores the signed-on principal and its role authority from the shared,
 *     Redis-backed ``SessionContext`` so the gateway and every microservice can
 *     authorize a request. Covers the administrator and regular-user mappings, the
 *     anonymous cases, and the guarantee that no HTTP session is ever created.
 */
@DisplayName("SessionContextAuthenticationFilter")
class SessionContextAuthenticationFilterTest {

    private final SessionContextAuthenticationFilter filter = new SessionContextAuthenticationFilter();

    /**
     * :purpose: Leave no authentication behind for the next test.
     */
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * :purpose: An administrator session yields an authenticated principal carrying
     *     ``ROLE_ADMIN``.
     */
    @Test
    @DisplayName("Admin session context yields an authenticated ROLE_ADMIN principal")
    void adminSessionContext_grantsRoleAdmin() throws Exception {
        Authentication authentication = runFilterWithContext(
                sessionContext("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN));

        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("ADMIN001");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly(SessionContextAuthenticationFilter.ROLE_ADMIN);
    }

    /**
     * :purpose: A regular-user session yields an authenticated principal carrying
     *     ``ROLE_USER`` and nothing more, preserving the legacy two-role model.
     */
    @Test
    @DisplayName("User session context yields an authenticated ROLE_USER principal")
    void userSessionContext_grantsRoleUser() throws Exception {
        Authentication authentication = runFilterWithContext(
                sessionContext("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER));

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("USER0001");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly(SessionContextAuthenticationFilter.ROLE_USER);
    }

    /**
     * :purpose: A session context without a user type cannot authorize anything.
     */
    @Test
    @DisplayName("Session context without a user type stays anonymous")
    void sessionContextWithoutUserType_staysAnonymous() throws Exception {
        SessionContext context = new SessionContext();
        context.setUserId("USER0001");

        assertThat(runFilterWithContext(context)).isNull();
    }

    /**
     * :purpose: A session context without a user id cannot authorize anything.
     */
    @Test
    @DisplayName("Session context without a user id stays anonymous")
    void sessionContextWithoutUserId_staysAnonymous() throws Exception {
        SessionContext context = new SessionContext();
        context.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);

        assertThat(runFilterWithContext(context)).isNull();
    }

    /**
     * :purpose: A request with no session is anonymous AND must not cause a session to
     *     be created, so an unauthenticated caller can never grow the Redis session
     *     store (CWE-770).
     */
    @Test
    @DisplayName("Request without a session stays anonymous and creates no session")
    void requestWithoutSession_createsNoSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        AtomicReference<Authentication> observed = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(observed));

        assertThat(observed.get()).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    /**
     * :purpose: The filter clears the context it installed, so a pooled container
     *     thread never leaks an identity into the next request.
     */
    @Test
    @DisplayName("Installed authentication is cleared after the chain completes")
    void installedAuthentication_isClearedAfterChain() throws Exception {
        runFilterWithContext(sessionContext("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * :purpose: Build a session context with the given identity.
     * :param userId: signed-on user id.
     * :param userType: COMMAREA ``CDEMO-USER-TYPE`` value.
     * :returns: the populated session context.
     */
    private SessionContext sessionContext(String userId, SessionContext.UserType userType) {
        SessionContext context = new SessionContext();
        context.setUserId(userId);
        context.setUserType(userType);
        return context;
    }

    /**
     * :purpose: Run the filter for a request whose existing session carries the supplied
     *     context, and report the authentication visible to the rest of the chain.
     * :param context: the session context to publish on the request session.
     * :returns: the authentication observed inside the chain, or ``null`` when the
     *     request remained anonymous.
     * :raises ServletException: if the filter fails.
     * :raises IOException: if the filter fails on I/O.
     */
    private Authentication runFilterWithContext(SessionContext context) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        request.getSession(true).setAttribute(SessionContext.SESSION_ATTRIBUTE_NAME, context);
        AtomicReference<Authentication> observed = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), capturingChain(observed));

        return observed.get();
    }

    /**
     * :purpose: Build a terminal filter chain that records the authentication in force.
     * :param sink: holder that receives the observed authentication.
     * :returns: the recording filter chain.
     */
    private FilterChain capturingChain(AtomicReference<Authentication> sink) {
        return (servletRequest, servletResponse) ->
                sink.set(SecurityContextHolder.getContext().getAuthentication());
    }
}
