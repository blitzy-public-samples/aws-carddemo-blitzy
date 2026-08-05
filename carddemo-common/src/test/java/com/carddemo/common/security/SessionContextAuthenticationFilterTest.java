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
package com.carddemo.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.common.dto.SessionContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * :purpose: Unit tests for the session-to-principal bridge that makes the role-gated CardDemo routes
 *     reachable after sign-on. Covers the ``SEC-USR-TYPE`` to authority mapping (AAP 0.6.7), the
 *     anonymous paths that must NOT be authenticated (and must not mint a session), and the clearing
 *     of the security context once the request completes.
 */
@DisplayName("SessionContextAuthenticationFilter")
class SessionContextAuthenticationFilterTest {

    private final SessionContextAuthenticationFilter filter = new SessionContextAuthenticationFilter();

    /**
     * :purpose: Start each case with an empty security context.
     */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    /**
     * :purpose: Leave no principal behind on the test thread.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * :purpose: An administrator session yields ``ROLE_ADMIN`` with the legacy user id as principal.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("administrator session grants ROLE_ADMIN")
    void adminSessionGrantsRoleAdmin() throws ServletException, IOException {
        MockHttpServletRequest request = requestWithSessionContext("ADMIN001",
                SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.authentication, "the chain must run with a principal in scope");
        assertEquals("ADMIN001", chain.authentication.getPrincipal());
        assertTrue(chain.authentication.isAuthenticated());
        assertEquals(List.of(SessionContextAuthenticationFilter.ROLE_ADMIN), authorities(chain.authentication));
    }

    /**
     * :purpose: A regular-user session yields ``ROLE_USER``.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("regular-user session grants ROLE_USER")
    void userSessionGrantsRoleUser() throws ServletException, IOException {
        MockHttpServletRequest request = requestWithSessionContext("USER0001",
                SessionContext.UserType.CDEMO_USRTYP_USER);
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("USER0001", chain.authentication.getPrincipal());
        assertEquals(List.of(SessionContextAuthenticationFilter.ROLE_USER), authorities(chain.authentication));
    }

    /**
     * :purpose: A session carrying the administrator revocation marker no longer authorizes,
     *     even though its context attribute would still resolve a role.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("a revoked session no longer authorizes")
    void revokedSessionStaysAnonymous() throws ServletException, IOException {
        MockHttpServletRequest request = requestWithSessionContext("ADMIN001",
                SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        request.getSession().setAttribute(
                SessionPrincipalIndex.REVOKED_REASON_ATTRIBUTE, "USER_DELETED");
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.authentication, "a revoked session must not authorize");
    }

    /**
     * :purpose: A request with no session stays anonymous AND does not acquire one, so an
     *     unauthenticated caller cannot leak session state into Redis.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("no session stays anonymous and no session is created")
    void noSessionStaysAnonymous() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.authentication, "an anonymous request must not be authenticated here");
        assertNull(request.getSession(false), "the filter must never create a session");
    }

    /**
     * :purpose: A session without the sign-on context stays anonymous.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("session without the sign-on context stays anonymous")
    void sessionWithoutContextStaysAnonymous() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        request.setSession(new MockHttpSession());
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.authentication);
    }

    /**
     * :purpose: A sign-on context without a user type (an incomplete legacy COMMAREA) stays anonymous
     *     rather than being granted a default role.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("context without a user type stays anonymous")
    void contextWithoutUserTypeStaysAnonymous() throws ServletException, IOException {
        MockHttpServletRequest request = requestWithSessionContext("ADMIN001", null);
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.authentication);
    }

    /**
     * :purpose: The security context is cleared after the request so a pooled container thread never
     *     carries one caller's principal into the next request.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("security context is cleared after the request")
    void securityContextClearedAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = requestWithSessionContext("ADMIN001",
                SessionContext.UserType.CDEMO_USRTYP_ADMIN);

        filter.doFilter(request, new MockHttpServletResponse(), new CapturingFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * :purpose: An ANONYMOUS authentication already in the context must not stop the derivation:
     *     Spring Security's anonymous token reports ``isAuthenticated() == true``, and treating that
     *     as a real principal left every signed-on request unauthenticated.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("an anonymous token in the context does not block derivation")
    void anonymousTokenDoesNotBlockDerivation() throws ServletException, IOException {
        SecurityContext anonymousContext = SecurityContextHolder.createEmptyContext();
        anonymousContext.setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        SecurityContextHolder.setContext(anonymousContext);
        MockHttpServletRequest request = requestWithSessionContext("ADMIN001",
                SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.authentication);
        assertEquals("ADMIN001", chain.authentication.getPrincipal());
        assertEquals(List.of(SessionContextAuthenticationFilter.ROLE_ADMIN), authorities(chain.authentication));
    }

    /**
     * :purpose: A real principal already established upstream is left untouched.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("an existing real principal is preserved")
    void existingPrincipalIsPreserved() throws ServletException, IOException {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("UPSTREAM", null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        MockHttpServletRequest request = requestWithSessionContext("ADMIN001",
                SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("UPSTREAM", chain.authentication.getPrincipal(),
                "an upstream principal must not be replaced by the session-derived one");
    }

    /**
     * :purpose: Build a request whose existing session carries a sign-on context.
     * :param userId: the legacy ``SEC-USR-ID``.
     * :param userType: the legacy ``SEC-USR-TYPE``, or ``null`` for an incomplete context.
     * :returns: the prepared request.
     */
    private static MockHttpServletRequest requestWithSessionContext(String userId,
                                                                   SessionContext.UserType userType) {
        SessionContext context = new SessionContext();
        context.setUserId(userId);
        context.setUserType(userType);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_ATTRIBUTE_NAME, context);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        request.setSession(session);
        return request;
    }

    /**
     * :purpose: Read the authority strings from an authentication.
     * :param authentication: the authentication under test.
     * :returns: the granted authority names.
     */
    private static List<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    /**
     * :purpose: Test chain that records the principal visible while the chain runs.
     */
    private static final class CapturingFilterChain implements FilterChain {

        private Authentication authentication;

        /**
         * :purpose: Capture the in-scope authentication.
         * :param request: the delegated request.
         * :param response: the delegated response.
         */
        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            this.authentication = SecurityContextHolder.getContext().getAuthentication();
        }
    }
}
