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
package com.carddemo.gateway.config;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.SessionAttributes;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.gateway.controller.MenuController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Regression guard for the gateway's session-based authorization. A caller
 *     that has completed sign-on carries ONLY the externalized
 *     COMMAREA-equivalent {@code SessionContext} in the shared session, and that
 *     alone must be enough to reach the role-gated routes; before this contract
 *     existed every business route answered ``401`` after a successful sign-on and
 *     the gateway was a closed door.
 * :note: The chain must never persist a Spring Security type (or a saved request)
 *     into the shared session, because five downstream services carry no Spring
 *     Security on their classpath and would fail to deserialize it. CSRF state is
 *     held in the double-submit cookie for exactly that reason.
 * :note: A pristine context is demanded because {@code MenuControllerTest} declares
 *     the identical slice configuration and therefore shares this cached context.
 *     Its {@code with(csrf())} post-processor reaches into the cached chain and
 *     swaps the configured {@code CookieCsrfTokenRepository} for a session-backed
 *     test repository, which would then persist a CSRF token into the shared
 *     session and mask the production wiring this class asserts.
 */
@WebMvcTest(MenuController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SessionAuthorizationTest {

    /** :purpose: Every role-gated route prefix declared by the gateway chain. */
    private static final List<String> USER_ROUTES = List.of(
            "/menu", "/accounts/90000000001", "/cards", "/transactions", "/billpay", "/reports");

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    /**
     * :purpose: Build a shared session that looks exactly like the one sign-on leaves
     *     behind: a single {@code SessionContext} attribute and nothing else.
     * :param userId: the signed-on user id (``SEC-USR-ID``).
     * :param type: the signed-on user type (``SEC-USR-TYPE``).
     * :returns: a mock session carrying only the COMMAREA-equivalent context.
     */
    private static MockHttpSession signedOnSession(String userId, SessionContext.UserType type) {
        SessionContext context = new SessionContext();
        context.setUserId(userId);
        context.setUserType(type);
        context.setFromProgram("COSGN00C");
        context.setFromTranid("CC00");
        context.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAttributes.SESSION_CONTEXT, context);
        return session;
    }

    @Test
    @DisplayName("An anonymous caller is rejected with 401 on every gated route")
    void anonymousIsRejected() throws Exception {
        for (String route : USER_ROUTES) {
            mockMvc.perform(get(route)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/menu")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A signed-on USER session reaches /menu and is refused /admin/** and /users")
    void signedOnUserReachesUserRoutesOnly() throws Exception {
        MockHttpSession session = signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);

        mockMvc.perform(get("/menu").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/menu").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/users").session(session)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A signed-on ADMIN session reaches both /menu and /admin/menu")
    void signedOnAdminReachesAdminRoutes() throws Exception {
        MockHttpSession session = signedOnSession("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN);

        mockMvc.perform(get("/menu").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/admin/menu").session(session)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Authorization leaves no Spring Security type or saved request in the shared session")
    void sharedSessionStaysFreeOfFrameworkTypes() throws Exception {
        MockHttpSession session = signedOnSession("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);
        MvcResult result = mockMvc.perform(get("/menu").session(session))
                .andExpect(status().isOk())
                .andReturn();

        // The CSRF token is materialized on every response, so proving the session stays
        // clean only means something if the token demonstrably went somewhere else: the
        // double-submit cookie. Asserting both halves pins the mechanism, not just the
        // symptom, so a reversion to a session-backed repository fails here explicitly.
        assertThat(result.getResponse().getCookie(SecurityConfig.CSRF_COOKIE_NAME))
                .as("CSRF state must be carried by the double-submit cookie, never the session")
                .isNotNull();

        List<String> attributes = Collections.list(session.getAttributeNames());
        assertThat(attributes)
                .as("only the single COMMAREA-equivalent context may live in the shared session")
                .containsExactly(SessionAttributes.SESSION_CONTEXT);

        // An anonymous rejection must not stash a DefaultSavedRequest either.
        MockHttpSession rejected = new MockHttpSession();
        mockMvc.perform(get("/menu").session(rejected)).andExpect(status().isUnauthorized());
        assertThat(Collections.list(rejected.getAttributeNames())).isEmpty();
    }

    @Test
    @DisplayName("Gateway and sign-on service agree on one session attribute name")
    void oneSessionAttributeName() {
        assertThat(SessionAttributes.SESSION_CONTEXT).isEqualTo("carddemoSessionContext");
        // The gateway reads the session through the shared constant; a divergent
        // private literal would split the COMMAREA and disable the role gate.
        assertThat(MenuController.class.getDeclaredFields())
                .noneMatch(field -> "sessionContext".equals(field.getName()));
    }
}
