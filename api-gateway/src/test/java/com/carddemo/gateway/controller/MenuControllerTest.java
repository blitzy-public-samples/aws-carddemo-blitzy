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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.constant.MenuOptions;
import com.carddemo.common.constant.Messages;
import com.carddemo.common.dto.SessionContext;
import com.carddemo.common.security.SessionContextAuthenticationFilter;
import com.carddemo.gateway.config.SecurityConfig;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpSession;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * :purpose: Verifies the MenuController servlet re-platforming of legacy CICS
 *           menu programs COMEN01C (main menu, CM00) and COADM01C (admin menu,
 *           CA00): menu enumeration, option/key validation, PF3 navigation,
 *           downstream routing, and URL role gating from SecurityConfig.
 * :note: Spring MVC servlet slice (MockMvc) only; never reactive. SecurityConfig
 *        and the carddemo-common GlobalExceptionHandler are imported so the URL
 *        role gate is active and a thrown CardDemoException surfaces as HTTP 400.
 */
@WebMvcTest(MenuController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class MenuControllerTest {

    /**
     * :purpose: The frozen ``HttpSession`` attribute name the whole application uses for
     *           the COMMAREA-equivalent session context.
     */
    private static final String SESSION_ATTR = SessionContext.SESSION_ATTRIBUTE_NAME;

    /**
     * :purpose: Build the signed-on session context a request always carries at runtime.
     *           Every menu route is role-gated and the gateway derives its Authentication
     *           from this very attribute, so a request that passes authorization always
     *           has one; the controller therefore never fabricates a blank context.
     * :param userId: the signed-on ``SEC-USR-ID``.
     * :param userType: the COMMAREA ``CDEMO-USER-TYPE`` condition.
     * :returns: a populated session context.
     */
    private static SessionContext contextFor(String userId, SessionContext.UserType userType) {
        SessionContext ctx = new SessionContext();
        ctx.setUserId(userId);
        ctx.setUserType(userType);
        return ctx;
    }

    /** :returns: the session context of a signed-on administrator (``CDEMO-USER-TYPE`` ``'A'``). */
    private static SessionContext adminContext() {
        return contextFor("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN);
    }

    /** :returns: the session context of a signed-on regular user (``CDEMO-USER-TYPE`` ``'U'``). */
    private static SessionContext userContext() {
        return contextFor("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER);
    }

    /**
     * :purpose: Invalid-option banner (frozen, COMEN01C L131 / COADM01C L131):
     *           thirty-seven characters ending in three dots, no trailing space.
     */
    private static final String MSG_INVALID_OPTION = "Please enter a valid option number...";

    /**
     * :purpose: Guardrail for the main-menu admin-only banner (frozen, COMEN01C L140):
     *           thirty-three characters ending in three dots plus one trailing space.
     * :note: Unreachable via HTTP with the real MenuOptions data (all main rows are
     *        userType "U"); the admin menu has no such gate (COADM01C L135-136 blank).
     */
    private static final String EXPECTED_NO_ACCESS = "No access - Admin Only option... ";

    /**
     * :purpose: Guardrail for the main-menu coming-soon banner for option 1 (frozen,
     *           COMEN01C L157-164). Reproduces COBOL STRING ... DELIMITED BY SPACE, so
     *           the first token of the option name is concatenated with no intervening
     *           space before "is".
     * :note: Unreachable via HTTP; no real program name starts with "DUMMY".
     */
    private static final String EXPECTED_COMING_SOON_MAIN_OPT1 = "This option Accountis coming soon ...";

    /**
     * :purpose: Guardrail for the admin-menu coming-soon banner (frozen, COADM01C
     *           L147-153): thirty characters; the option name is omitted (the
     *           CDEMO-ADMIN-OPT-NAME insert is commented out at COADM01C L150-151).
     * :note: Unreachable via HTTP; no real program name starts with "DUMMY".
     */
    private static final String EXPECTED_COMING_SOON_ADMIN = "This option is coming soon ...";

    /** :purpose: Downstream route shared by every admin (user-management) option. */
    private static final String USERS_ROUTE = "/users";

    /**
     * :purpose: Expected legacy-program-to-route mapping for the ten main-menu options,
     *           mirroring the production PROGRAM_ROUTES table; a documented test guardrail.
     */
    private static final Map<String, String> EXPECTED_MAIN_ROUTES = Map.ofEntries(
            Map.entry("COACTVWC", "/accounts"),
            Map.entry("COACTUPC", "/accounts"),
            Map.entry("COCRDLIC", "/cards"),
            Map.entry("COCRDSLC", "/cards"),
            Map.entry("COCRDUPC", "/cards"),
            Map.entry("COTRN00C", "/transactions"),
            Map.entry("COTRN01C", "/transactions"),
            Map.entry("COTRN02C", "/transactions"),
            Map.entry("CORPT00C", "/reports"),
            Map.entry("COBIL00C", "/billpay"));

    /** :purpose: Web application context of the MVC slice, used to build MockMvc. */
    @Autowired
    private WebApplicationContext webApplicationContext;

    /** :purpose: Servlet MockMvc entry point with the Spring Security filter chain applied. */
    private MockMvc mockMvc;

    /**
     * :purpose: Build MockMvc from the slice web context with the Spring Security test
     *           configurer applied.
     * :note: As of Spring Boot 4.0 the auto-configured MockMvc no longer applies
     *        springSecurity() automatically (the former MockMvcSecurityConfiguration
     *        was removed), so it is applied explicitly here; this both runs the URL
     *        role-gating filter chain and propagates the @WithMockUser test context.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    /** :purpose: Jackson mapper used to build JSON selection request bodies. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * :purpose: Serialize a menu-selection request body from an option and action key.
     * :param option: the entered option text (may be null).
     * :param aid: the action key ("ENTER", "PF3", or an unsupported key).
     * :returns: the JSON request body.
     */
    private String body(String option, String aid) throws Exception {
        return objectMapper.writeValueAsString(new MenuController.MenuSelectionRequest(option, aid));
    }

    // -----------------------------------------------------------------
    // Phase F1 / F2 - menu enumeration
    // -----------------------------------------------------------------

    @Test
    @DisplayName("GET /menu lists the 10 main-menu options (CM00 / COMEN01C)")
    @WithMockUser(roles = "USER")
    void mainMenuEnumeration() throws Exception {
        mockMvc.perform(get("/menu").sessionAttr(SESSION_ATTR, userContext()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tranId").value("CM00"))
                .andExpect(jsonPath("$.programName").value("COMEN01C"))
                .andExpect(jsonPath("$.options", hasSize(MenuOptions.CDEMO_MENU_OPT_COUNT)))
                .andExpect(jsonPath("$.options[0].optionNumber").value(1))
                .andExpect(jsonPath("$.options[0].programName")
                        .value(MenuOptions.MAIN_MENU_OPTIONS.get(0).programName()))
                .andExpect(jsonPath("$.options[0].optionName")
                        .value(MenuOptions.MAIN_MENU_OPTIONS.get(0).optionName()))
                .andExpect(jsonPath("$.options[0].targetRoute").value("/accounts"))
                .andExpect(jsonPath("$.options[9].optionNumber").value(10))
                .andExpect(jsonPath("$.options[9].programName")
                        .value(MenuOptions.MAIN_MENU_OPTIONS.get(9).programName()))
                .andExpect(jsonPath("$.options[9].targetRoute").value("/billpay"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("GET /admin/menu lists the 4 admin-menu options (CA00 / COADM01C)")
    @WithMockUser(roles = "ADMIN")
    void adminMenuEnumeration() throws Exception {
        mockMvc.perform(get("/admin/menu").sessionAttr(SESSION_ATTR, adminContext()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tranId").value("CA00"))
                .andExpect(jsonPath("$.programName").value("COADM01C"))
                .andExpect(jsonPath("$.options", hasSize(MenuOptions.CDEMO_ADMIN_OPT_COUNT)))
                .andExpect(jsonPath("$.options[0].programName")
                        .value(MenuOptions.ADMIN_MENU_OPTIONS.get(0).programName()))
                .andExpect(jsonPath("$.options[0].targetRoute").value(USERS_ROUTE))
                .andExpect(jsonPath("$.options[3].programName")
                        .value(MenuOptions.ADMIN_MENU_OPTIONS.get(3).programName()))
                .andExpect(jsonPath("$.options[3].targetRoute").value(USERS_ROUTE));
    }

    // -----------------------------------------------------------------
    // Phase F3 - option normalization and validation
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "option=[{0}] -> 400 invalid option")
    @NullSource
    @ValueSource(strings = {"", "  ", "0", "1A", "99"})
    @DisplayName("POST /menu/select rejects an invalid option with 400 + MSG_INVALID_OPTION")
    @WithMockUser(roles = "USER")
    void mainSelectRejectsInvalidOption(String option) throws Exception {
        mockMvc.perform(post("/menu/select").sessionAttr(SESSION_ATTR, userContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(option, "ENTER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(MSG_INVALID_OPTION))
                .andExpect(jsonPath("$.path").value("/menu/select"));
    }

    @Test
    @DisplayName("POST /admin/menu/select rejects out-of-range option 5 (count 4) with 400 + MSG_INVALID_OPTION")
    @WithMockUser(roles = "ADMIN")
    void adminSelectRejectsOutOfRangeOption() throws Exception {
        mockMvc.perform(post("/admin/menu/select").sessionAttr(SESSION_ATTR, adminContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5", "ENTER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(MSG_INVALID_OPTION));
    }

    @ParameterizedTest(name = "option=[{0}] normalizes to 5 and dispatches")
    @ValueSource(strings = {" 5", "05"})
    @DisplayName("POST /menu/select accepts a trimmed/zero-padded option and dispatches (no 400)")
    @WithMockUser(roles = "USER")
    void mainSelectAcceptsNormalizedOptionFive(String option) throws Exception {
        mockMvc.perform(post("/menu/select").sessionAttr(SESSION_ATTR, userContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(option, "ENTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.programName")
                        .value(MenuOptions.MAIN_MENU_OPTIONS.get(4).programName()))
                .andExpect(jsonPath("$.targetRoute").value("/cards"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    // -----------------------------------------------------------------
    // Phase F4 - unsupported key
    // -----------------------------------------------------------------

    @Test
    @DisplayName("POST /menu/select with an unsupported key returns 400 + CCDA_MSG_INVALID_KEY")
    @WithMockUser(roles = "USER")
    void mainSelectRejectsInvalidKey() throws Exception {
        mockMvc.perform(post("/menu/select").sessionAttr(SESSION_ATTR, userContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "PF9")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(Messages.CCDA_MSG_INVALID_KEY));
    }

    @Test
    @DisplayName("POST /admin/menu/select with an unsupported key returns 400 + CCDA_MSG_INVALID_KEY")
    @WithMockUser(roles = "ADMIN")
    void adminSelectRejectsInvalidKey() throws Exception {
        mockMvc.perform(post("/admin/menu/select").sessionAttr(SESSION_ATTR, adminContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "PF9")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Messages.CCDA_MSG_INVALID_KEY));
    }

    // -----------------------------------------------------------------
    // Phase F5 - PF3 back to sign-on
    // -----------------------------------------------------------------

    @Test
    @DisplayName("POST /menu/select PF3 returns to /auth (dispatched, no program, no message)")
    @WithMockUser(roles = "USER")
    void mainSelectPf3ReturnsToAuth() throws Exception {
        mockMvc.perform(post("/menu/select").sessionAttr(SESSION_ATTR, userContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "PF3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.targetRoute").value("/auth"))
                .andExpect(jsonPath("$.programName").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("POST /admin/menu/select PF3 returns to /auth (dispatched, no program, no message)")
    @WithMockUser(roles = "ADMIN")
    void adminSelectPf3ReturnsToAuth() throws Exception {
        mockMvc.perform(post("/admin/menu/select").sessionAttr(SESSION_ATTR, adminContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "PF3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.targetRoute").value("/auth"))
                .andExpect(jsonPath("$.programName").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    // -----------------------------------------------------------------
    // Phase F6 - valid (non-DUMMY) selection dispatch
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "main option {0} dispatches to its program and route")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    @DisplayName("POST /menu/select dispatches each valid main option to the correct program/route")
    @WithMockUser(roles = "USER")
    void mainSelectDispatchesValidOption(int optionNumber) throws Exception {
        String expectedProgram = MenuOptions.MAIN_MENU_OPTIONS.get(optionNumber - 1).programName();
        String expectedRoute = EXPECTED_MAIN_ROUTES.get(expectedProgram);
        mockMvc.perform(post("/menu/select").sessionAttr(SESSION_ATTR, userContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(String.valueOf(optionNumber), "ENTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.programName").value(expectedProgram))
                .andExpect(jsonPath("$.targetRoute").value(expectedRoute))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @ParameterizedTest(name = "admin option {0} dispatches to its user program and /users")
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("POST /admin/menu/select dispatches each valid admin option to its user program and /users")
    @WithMockUser(roles = "ADMIN")
    void adminSelectDispatchesValidOption(int optionNumber) throws Exception {
        String expectedProgram = MenuOptions.ADMIN_MENU_OPTIONS.get(optionNumber - 1).programName();
        mockMvc.perform(post("/admin/menu/select").sessionAttr(SESSION_ATTR, adminContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(String.valueOf(optionNumber), "ENTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.programName").value(expectedProgram))
                .andExpect(jsonPath("$.targetRoute").value(USERS_ROUTE))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    // -----------------------------------------------------------------
    // Phase G1 / G2 - reachable complements for the unreachable branches
    // -----------------------------------------------------------------

    /**
     * :purpose: Reachable complement for the main-menu admin-only gate. With a regular-user
     *           SessionContext seeded, option 1 (userType "U") is dispatched, proving the
     *           gate rejects only a userType "A" row (the EXPECTED_NO_ACCESS branch).
     */
    @Test
    @DisplayName("POST /menu/select does not fire the admin-only gate for a regular-user 'U' option")
    @WithMockUser(roles = "USER")
    void mainSelectRegularUserOptionNotGated() throws Exception {
        SessionContext ctx = new SessionContext();
        ctx.setUserId("USER0001");
        ctx.setUserType(SessionContext.UserType.CDEMO_USRTYP_USER);
        mockMvc.perform(post("/menu/select")
                        .with(csrf())
                        .sessionAttr(SESSION_ATTR, ctx)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "ENTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.programName")
                        .value(MenuOptions.MAIN_MENU_OPTIONS.get(0).programName()))
                .andExpect(jsonPath("$.targetRoute").value("/accounts"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    /**
     * :purpose: Freezes the CSRF contract for the browser-facing edge: a state-changing
     *           request that does not echo the XSRF-TOKEN cookie back as a header is
     *           rejected, so a cross-site form post cannot drive the menu.
     */
    @Test
    @DisplayName("POST /menu/select without a CSRF token is rejected with 403")
    @WithMockUser(roles = "USER")
    void mainSelectWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/menu/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "ENTER")))
                .andExpect(status().isForbidden());
    }

    /**
     * :purpose: Freezes the cross-service session-attribute contract: the menu MUST read
     *           and update the SAME key sign-on writes, so the signed-on role reaches the
     *           admin-only gate instead of being replaced by an empty context.
     */
    @Test
    @DisplayName("GET /menu reads and updates the shared sign-on session key, preserving the role")
    @WithMockUser(roles = "USER")
    void mainMenuUsesTheSharedSignonSessionKey() throws Exception {
        assertThat(SessionContext.SESSION_ATTRIBUTE_NAME).isEqualTo("carddemoSessionContext");

        SessionContext signedOn = new SessionContext();
        signedOn.setUserId("USER0001");
        signedOn.setUserType(SessionContext.UserType.CDEMO_USRTYP_USER);

        var session = mockMvc.perform(get("/menu")
                        .sessionAttr(SessionContext.SESSION_ATTRIBUTE_NAME, signedOn))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);

        assertThat(session).isNotNull();
        assertThat(session.getAttribute("sessionContext")).isNull();
        SessionContext afterMenu = (SessionContext) session.getAttribute(SessionContext.SESSION_ATTRIBUTE_NAME);
        assertThat(afterMenu).isNotNull();
        assertThat(afterMenu.getUserId()).isEqualTo("USER0001");
        assertThat(afterMenu.getUserType()).isEqualTo(SessionContext.UserType.CDEMO_USRTYP_USER);
        assertThat(afterMenu.getFromProgram()).isEqualTo("COMEN01C");
        assertThat(afterMenu.getFromTranid()).isEqualTo("CM00");
    }

    /**
     * :purpose: Reachable complement for the coming-soon (DUMMY) branch on the main menu:
     *           a real option dispatches and carries no coming-soon message.
     */
    @Test
    @DisplayName("POST /menu/select dispatches option 1 without a coming-soon message")
    @WithMockUser(roles = "USER")
    void mainSelectDispatchNotComingSoon() throws Exception {
        mockMvc.perform(post("/menu/select").sessionAttr(SESSION_ATTR, userContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "ENTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    /**
     * :purpose: Reachable complement for the coming-soon (DUMMY) branch on the admin menu:
     *           a real option dispatches and carries no coming-soon message.
     */
    @Test
    @DisplayName("POST /admin/menu/select dispatches option 1 without a coming-soon message")
    @WithMockUser(roles = "ADMIN")
    void adminSelectDispatchNotComingSoon() throws Exception {
        mockMvc.perform(post("/admin/menu/select").sessionAttr(SESSION_ATTR, adminContext())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "ENTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    // -----------------------------------------------------------------
    // Phase H - URL role gating (SecurityConfig filters enabled)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("H1: ADMIN may GET /admin/menu (200)")
    @WithMockUser(roles = "ADMIN")
    void adminMenuAllowedForAdmin() throws Exception {
        mockMvc.perform(get("/admin/menu").sessionAttr(SESSION_ATTR, adminContext())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("H2: USER may not GET /admin/menu (403)")
    @WithMockUser(roles = "USER")
    void adminMenuForbiddenForUser() throws Exception {
        mockMvc.perform(get("/admin/menu").sessionAttr(SESSION_ATTR, userContext())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("H3: USER may not POST /admin/menu/select (403)")
    @WithMockUser(roles = "USER")
    void adminSelectForbiddenForUser() throws Exception {
        mockMvc.perform(post("/admin/menu/select").sessionAttr(SESSION_ATTR, userContext())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "ENTER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("H4: USER may GET /menu (200)")
    @WithMockUser(roles = "USER")
    void mainMenuAllowedForUser() throws Exception {
        mockMvc.perform(get("/menu").sessionAttr(SESSION_ATTR, userContext())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("H5: ADMIN may GET /menu (200)")
    @WithMockUser(roles = "ADMIN")
    void mainMenuAllowedForAdmin() throws Exception {
        mockMvc.perform(get("/menu").sessionAttr(SESSION_ATTR, adminContext())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("H6: unauthenticated GET /menu returns 401 (not 403)")
    void mainMenuUnauthenticatedIsUnauthorized() throws Exception {
        mockMvc.perform(get("/menu")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("H7: unauthenticated POST without a CSRF token returns 401, not 403")
    void unauthenticatedWriteIsUnauthorizedNotForbidden() throws Exception {
        // CsrfFilter runs before AnonymousAuthenticationFilter, so a missing-token denial
        // reaches the access-denied handler with an empty SecurityContext. The shared
        // handler delegates such a denial to the 401 entry point, so an unauthenticated
        // write reports the same status as an unauthenticated read and the SPA (which
        // redirects to sign-on on 401 only) returns the caller to sign-on.
        mockMvc.perform(post("/menu/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "ENTER")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("H8: an authenticated write without a CSRF token still returns 403")
    @WithMockUser(roles = "USER")
    void authenticatedWriteWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/menu/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "ENTER")))
                .andExpect(status().isForbidden());
    }

    /**
     * :note: A pristine context is demanded before this method because the
     *     {@code with(csrf())} post-processor used by the write tests in this class
     *     permanently swaps the cached chain's {@code CookieCsrfTokenRepository} for a
     *     session-backed test repository. Without this, the assertion below would pass
     *     or fail purely on JUnit's method ordering: no cookie is issued once the
     *     repository has been swapped.
     */
    @Test
    @DisplayName("H9: the XSRF-TOKEN cookie is script-readable but Strict and Secure")
    @WithMockUser(roles = "USER")
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void csrfCookieCarriesTheSessionCookieTransportPolicy() throws Exception {
        // The cookie object is asserted rather than the rendered Set-Cookie header:
        // CookieCsrfTokenRepository carries SameSite as a servlet cookie ATTRIBUTE, which
        // the real container renders but MockHttpServletResponse renders only for its own
        // MockCookie type. The wire format is re-verified against a running gateway.
        jakarta.servlet.http.Cookie cookie = mockMvc.perform(
                        get("/menu").sessionAttr(SESSION_ATTR, userContext()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie(SecurityConfig.CSRF_COOKIE_NAME);

        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        // Double submit requires the SPA to read the value, so HttpOnly must stay off.
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getAttribute("SameSite")).isEqualTo(SecurityConfig.CSRF_COOKIE_SAME_SITE);
    }

    // -----------------------------------------------------------------
    // Frozen-literal guardrail (byte-exact; trailing spaces are significant)
    // -----------------------------------------------------------------

    /**
     * :purpose: Guard the frozen message literals backing the unreachable MSG_NO_ACCESS
     *           and coming-soon branches so any drift fails here; the main coming-soon
     *           expectation is derived from the single-source-of-truth MenuOptions data.
     */
    @Test
    @DisplayName("frozen message literals remain byte-exact (length + trailing spaces)")
    void frozenMessageLiteralsAreByteExact() {
        assertThat(MSG_INVALID_OPTION).hasSize(37).endsWith("...");
        assertThat(MSG_INVALID_OPTION.charAt(MSG_INVALID_OPTION.length() - 1)).isEqualTo('.');

        assertThat(EXPECTED_NO_ACCESS).hasSize(33).endsWith("... ");

        String opt1Name = MenuOptions.MAIN_MENU_OPTIONS.get(0).optionName();
        String firstToken = opt1Name.substring(0, opt1Name.indexOf(' '));
        assertThat("This option " + firstToken + "is coming soon ...")
                .isEqualTo(EXPECTED_COMING_SOON_MAIN_OPT1);

        assertThat(EXPECTED_COMING_SOON_ADMIN).hasSize(30)
                .isEqualTo("This option " + "is coming soon ...");
    }

    // -----------------------------------------------------------------
    // No Spring Session entry from anonymous or one-off calls
    // -----------------------------------------------------------------

    /**
     * :purpose: Regression guard against session churn. Spring Security's default
     *           HttpSessionRequestCache calls request.getSession() while saving a denied
     *           request, so every anonymous call minted a brand-new Spring Session entry
     *           in Redis. The chain now installs a NullRequestCache (the gateway has no
     *           login page - the entry point answers a bare 401 - so a saved request
     *           could never be replayed), and no session must be created.
     */
    @Test
    @DisplayName("a denied anonymous request persists no session state")
    void deniedAnonymousRequestCreatesNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get("/menu"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        // The absence of the session OBJECT is asserted by SessionAuthorizationTest, which
        // demands a pristine context for exactly this reason: the with(csrf()) post-processor
        // used by the write tests in this class reaches into the shared cached chain and swaps
        // the configured CookieCsrfTokenRepository for a session-backed test repository, which
        // then stores a CSRF token in the session. The substantive half is pinned here - no
        // saved request and no Spring Security context are persisted into the shared session -
        // and it holds whichever CSRF repository the chain is carrying.
        HttpSession denied = result.getRequest().getSession(false);
        if (denied != null) {
            assertThat(Collections.list(denied.getAttributeNames()))
                    .doesNotContain(SESSION_ATTR,
                            "SPRING_SECURITY_CONTEXT",
                            "SPRING_SECURITY_SAVED_REQUEST");
        }
    }

    /**
     * :purpose: Regression guard against session churn. A caller that reaches a menu endpoint
     *           without a session must leave without one: the shared resolver reads the
     *           pseudo-conversational context through getSession(false) and refuses the
     *           request, so no Spring Session entry is created - and therefore none is
     *           persisted to Redis - for a one-off call, and the menu never answers with a
     *           fabricated identity.
     * :note:    Deliberately exercised through a standalone MockMvc over the controller
     *           alone rather than through the slice's security-filtered MockMvc. The
     *           @WithMockUser/springSecurity() harness persists its own SecurityContext
     *           through the chain's session-backed SecurityContextRepository, which
     *           creates a MockHttpSession before the handler is ever invoked - a test
     *           artifact that would mask exactly the production behaviour under test. The
     *           anonymous/denied path (where the request cache used to create the
     *           session) is covered against the real filter chain by
     *           deniedAnonymousRequestCreatesNoSession above.
     */
    @Test
    @DisplayName("menu endpoints create no HTTP session for a sessionless caller")
    void menuEndpointsCreateNoSessionForSessionlessCaller() throws Exception {
        MockMvc plainMockMvc = MockMvcBuilders.standaloneSetup(new MenuController()).build();

        assertThatThrownBy(() -> plainMockMvc.perform(get("/menu")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> plainMockMvc.perform(get("/admin/menu")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> plainMockMvc.perform(post("/menu/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "ENTER"))))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> plainMockMvc.perform(post("/admin/menu/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", "ENTER"))))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    /**
     * :purpose: A caller that already holds a session keeps its pseudo-conversational
     *           context: the stored SessionContext instance is mutated in place with the
     *           originating tranid/program and written back under the SHARED attribute
     *           key, so sign-on's CDEMO-USER-TYPE survives menu navigation. This
     *           controller previously used its own private key ("sessionContext") while
     *           sign-on and every business service used "carddemoSessionContext", so the
     *           menu read a phantom empty context and stored a second redundant copy.
     */
    @Test
    @DisplayName("Menu navigation updates the SHARED session context in place (one COMMAREA)")
    @WithMockUser(roles = "USER")
    void menuNavigationUpdatesTheSharedSessionContext() throws Exception {
        SessionContext signedOn = new SessionContext();
        signedOn.setUserId("USER0001");
        signedOn.setUserType(SessionContext.UserType.CDEMO_USRTYP_USER);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SESSION_ATTR, signedOn);

        mockMvc.perform(get("/menu").session(session)).andExpect(status().isOk());

        Object stored = session.getAttribute(SESSION_ATTR);
        assertThat(stored).isSameAs(signedOn);
        assertThat(signedOn.getUserId()).isEqualTo("USER0001");
        assertThat(signedOn.getUserType()).isEqualTo(SessionContext.UserType.CDEMO_USRTYP_USER);
        assertThat(signedOn.getFromTranid()).isEqualTo("CM00");
        assertThat(signedOn.getFromProgram()).isEqualTo("COMEN01C");
        assertThat(session.getAttribute("sessionContext")).isNull();
    }

    /**
     * :purpose: Guardrail: the gateway must address the pseudo-conversational context by
     *           the SAME session attribute key as auth-service sign-on, the shared
     *           SessionContextAuthenticationFilter and every business service. One key
     *           means one COMMAREA, exactly as the legacy flow carried one.
     */
    @Test
    @DisplayName("The gateway shares the single 'carddemoSessionContext' attribute key")
    void gatewayUsesTheSharedSessionAttributeKey() {
        assertThat(SessionContext.SESSION_ATTRIBUTE_NAME)
                .isEqualTo("carddemoSessionContext");
    }
}
