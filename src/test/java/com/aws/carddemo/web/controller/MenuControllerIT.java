/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.dto.CardDemoContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Failsafe integration test for {@link MenuController}, the Spring MVC replacement for the CICS
 * regular-user main-menu program.
 *
 * <p><b>COBOL oracle (traceability, AAP &sect;0.6.10):</b> {@code legacy/cbl/COMEN01C.cbl} &mdash;
 * the online main-menu program driven by CICS transaction {@code CM00} (mapset {@code COMEN01},
 * verified in {@code legacy/csd/CARDDEMO.CSD}: {@code DEFINE TRANSACTION(CM00) ...
 * PROGRAM(COMEN01C)}). Each assertion below is bound to a specific COBOL construct so a behavioral
 * regression in the migrated web tier is caught:</p>
 * <ul>
 *   <li>{@code MAIN-PARA} ({@code COMEN01C.cbl} lines 75-110): first-entry / re-entry state machine
 *       and the {@code EVALUATE EIBAID} dispatch. The {@code IF EIBCALEN = 0} bounce to sign-on
 *       (lines 82-84) is the modern {@link CardDemoContext#isNew()} first-entry test.</li>
 *   <li>{@code PROCESS-ENTER-KEY} ({@code COMEN01C.cbl} lines 115-165): option parse, the
 *       "{@code Please enter a valid option number...}" error (line 131), and the {@code XCTL}
 *       to the selected program.</li>
 *   <li>{@code RETURN-TO-SIGNON-SCREEN} ({@code COMEN01C.cbl} lines 170-177): the {@code PF3}
 *       {@code XCTL COSGN00C} that returns to the sign-on screen.</li>
 *   <li>{@code BUILD-MENU-OPTIONS} ({@code COMEN01C.cbl} lines 236-277): the ten populated menu
 *       option display lines.</li>
 * </ul>
 *
 * <p><b>Full-stack integration.</b> This test drives the production {@link MenuController} through
 * the complete Spring application context with the real {@code MainMenuService}, the real
 * {@code dto.menu.MainMenuOptions} option catalog, the real
 * {@link com.aws.carddemo.config.SecurityConfig} filter chain, and a real Testcontainers
 * PostgreSQL database &mdash; there are no mocks. All shared infrastructure
 * ({@code @SpringBootTest}, the {@code test} profile, the {@code postgres:18-alpine} container, and
 * the per-test Flyway seed reset) is inherited from {@link AbstractPostgresIntegrationTest}; this
 * class adds only {@link AutoConfigureMockMvc} so the container-backed context is exercised through
 * {@link MockMvc}. The menu program performs no file I/O, so no database mutation occurs here.</p>
 *
 * <p><b>Pseudo-conversational state (AAP &sect;0.6.8).</b> The CICS {@code COMMAREA}
 * ({@code COCOM01Y}) is the session-scoped {@link CardDemoContext}. A freshly created context
 * reports {@link CardDemoContext#isNew()} {@code == true} (the {@code EIBCALEN = 0} equivalent), so
 * {@code GET /menu} on a brand-new session bounces to the sign-on screen exactly as
 * {@code MAIN-PARA} does. The authenticated-render test therefore seeds an already-initialized
 * context into the HTTP session, reproducing the post-signon state that {@code SignonController}
 * establishes (it calls {@link CardDemoContext#markInitialized()} and redirects to {@code /menu}).</p>
 *
 * <p><b>CSRF and authorization.</b> CSRF protection is enabled (the Spring Security default): every
 * state-changing {@code POST} carries a token via {@code .with(csrf())}, and one dedicated test
 * asserts that a token-less {@code POST} is rejected with {@code 403 Forbidden}. Unauthenticated
 * access to the protected {@code /menu} route redirects to the sign-on screen through the
 * configured {@code LoginUrlAuthenticationEntryPoint}.</p>
 *
 * <p><b>Runtime prerequisite.</b> Execution requires a Testcontainers-capable environment (a
 * reachable Docker daemon able to start {@code postgres:18-alpine}); this is the documented
 * local-validation constraint for the migration. The class name ends in {@code IT} so it runs under
 * the Maven Failsafe plugin (the {@code integration-test}/{@code verify} phase), disjoint from the
 * {@code *Test} unit suite run by Surefire.</p>
 *
 * @see MenuController
 * @see AbstractPostgresIntegrationTest
 * @see CardDemoContext
 */
@AutoConfigureMockMvc
class MenuControllerIT extends AbstractPostgresIntegrationTest {

    /** The logical Thymeleaf view name of the main menu (BMS map {@code COMEN01}). */
    private static final String VIEW_MENU = "COMEN01";

    /** Model attribute under which {@link MenuController} exposes the {@code COMEN01} screen form. */
    private static final String MODEL_ATTR_FORM = "form";

    /** Sign-on route the controller redirects to for {@code PF3} / first entry ({@code XCTL COSGN00C}). */
    private static final String SIGNON_ROUTE = "/signon";

    /** Web token that {@link MenuController} maps to the {@code ENTER} attention id. */
    private static final String PFKEY_ENTER = "ENTER";

    /** Web token that {@link MenuController} maps to {@code PF3} (COBOL {@code RETURN-TO-SIGNON-SCREEN}). */
    private static final String PFKEY_PF3 = "PF3";

    /** Web token for an unmapped program-function key, driving the {@code WHEN OTHER} invalid-key path. */
    private static final String PFKEY_PF9 = "PF9";

    /**
     * HTTP-session attribute name under which the {@code TARGET_CLASS} session-scoped
     * {@link CardDemoContext} bean is stored. Computed with {@link ScopedProxyUtils} (rather than a
     * hardcoded literal) so it always matches Spring's scoped-proxy convention
     * ({@code scopedTarget.cardDemoContext}). Seeding this attribute lets the authenticated-render
     * test present an already-initialized context, mirroring the post-signon session state.
     */
    private static final String CONTEXT_SESSION_ATTR =
            ScopedProxyUtils.getTargetBeanName("cardDemoContext");

    /** Auto-configured {@link MockMvc} bound to the full, container-backed application context. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Builds an already-initialized regular-user ({@code "U"}) {@link CardDemoContext}, reproducing
     * the session state left behind by a successful sign-on. Marking it initialized flips
     * {@link CardDemoContext#isNew()} to {@code false} so {@code GET /menu} renders the menu instead
     * of bouncing to the sign-on screen (COBOL {@code MAIN-PARA} lines 82-84).
     *
     * @return a fresh, initialized {@code "U"}-type context suitable for session seeding
     */
    private static CardDemoContext signedOnUserContext() {
        CardDemoContext context = new CardDemoContext();
        context.setUser();
        context.setUserId("USER0001");
        context.markInitialized();
        return context;
    }

    // ---------------------------------------------------------------------
    // Phase 1 - Authorization & GET (COMEN01C MAIN-PARA)
    // ---------------------------------------------------------------------

    /**
     * Unauthenticated {@code GET /menu} is redirected to the sign-on screen.
     *
     * <p>{@code /menu} (CICS tran {@code CM00}) is covered by the terminal
     * {@code anyRequest().authenticated()} rule, so an anonymous request is intercepted by the
     * configured {@code LoginUrlAuthenticationEntryPoint("/signon")}. The entry point emits an
     * absolute {@code Location} (for example {@code http://localhost/signon}), hence the pattern
     * match rather than an exact URL.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("GET /menu without authentication redirects to the sign-on screen")
    void menuRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/menu"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/signon"));
    }

    /**
     * An authenticated regular user with an initialized session sees the ten-option main menu.
     *
     * <p>Reproduces the {@code NOT CDEMO-PGM-REENTER} branch of {@code MAIN-PARA} (lines 87-90)
     * followed by {@code SEND-MENU-SCREEN} / {@code BUILD-MENU-OPTIONS}. The session is seeded with
     * an already-initialized context so {@link CardDemoContext#isNew()} is {@code false} and the
     * menu renders (view {@link #VIEW_MENU}) rather than bouncing to sign-on. The secondary
     * assertions confirm all ten option display lines ({@code optn001}..{@code optn010}, COBOL
     * {@code BUILD-MENU-OPTIONS}) are present on the {@code form} model attribute, in menu order.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("GET /menu (authenticated, initialized session) renders COMEN01 with all ten options")
    void menuScreenForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/menu").sessionAttr(CONTEXT_SESSION_ATTR, signedOnUserContext()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_MENU))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("optn001", containsString("Account View"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("optn002", containsString("Account Update"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("optn003", containsString("Credit Card List"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("optn004", containsString("Credit Card View"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("optn005", containsString("Credit Card Update"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("optn006", containsString("Transaction List"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("optn007", containsString("Transaction View"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("optn008", containsString("Transaction Add"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("optn009", containsString("Transaction Reports"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("optn010", containsString("Bill Payment"))));
    }

    // ---------------------------------------------------------------------
    // Phase 2 - Option -> route dispatch (COMEN01C PROCESS-ENTER-KEY)
    // ---------------------------------------------------------------------

    /**
     * Each of the ten regular-user menu options routes to its mapped screen.
     *
     * <p>Reproduces {@code PROCESS-ENTER-KEY} + the COBOL {@code XCTL PROGRAM(...)}: for a valid,
     * implemented option the controller records the target program on the context and issues a
     * {@code redirect:} to that program's web route. All ten options carry user type {@code "U"}, so
     * a {@code ROLE_USER} principal may select every one (no admin-gate rejection applies on the
     * user menu). Asserting the complete {@code 1..10} mapping catches a regression in any single
     * route.</p>
     *
     * @param option        the entered menu option number (BMS {@code OPTION}, {@code PIC X(02)})
     * @param expectedRoute the web route the option must redirect to (COBOL {@code XCTL} target)
     * @throws Exception if the request cannot be performed
     */
    @ParameterizedTest(name = "option {0} -> {1}")
    @CsvSource({
            "1,/account/view",
            "2,/account/update",
            "3,/card/list",
            "4,/card/detail",
            "5,/card/update",
            "6,/transaction/list",
            "7,/transaction/view",
            "8,/transaction/add",
            "9,/report",
            "10,/billpay"
    })
    @WithMockUser(roles = "USER")
    @DisplayName("POST /menu with ENTER routes each option to its mapped screen")
    void menuOptionRedirectsToRoute(String option, String expectedRoute) throws Exception {
        mockMvc.perform(post("/menu")
                        .param("option", option)
                        .param("pfkey", PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedRoute));
    }

    // ---------------------------------------------------------------------
    // Phase 3 - PF3 + invalid input (verbatim COBOL, substring assertions)
    // ---------------------------------------------------------------------

    /**
     * {@code PF3} on the menu returns to the sign-on screen.
     *
     * <p>Reproduces the {@code PF3} branch of {@code MAIN-PARA} (lines 96-98) and
     * {@code RETURN-TO-SIGNON-SCREEN} (lines 170-177): {@code XCTL COSGN00C} becomes a
     * {@code redirect:} to the sign-on route.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /menu with PF3 redirects to the sign-on screen")
    void menuPf3RedirectsToSignon() throws Exception {
        mockMvc.perform(post("/menu")
                        .param("pfkey", PFKEY_PF3)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(SIGNON_ROUTE));
    }

    /**
     * An out-of-range option re-displays the menu with the COBOL invalid-option message.
     *
     * <p>Reproduces {@code PROCESS-ENTER-KEY} lines 127-134: an option greater than the option
     * count yields "{@code Please enter a valid option number...}" (line 131) and re-renders the
     * menu ({@code SEND-MENU-SCREEN}). The message is asserted as a substring on the {@code form}
     * model attribute's {@code errmsg} property.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /menu with an invalid option re-renders COMEN01 with the invalid-option message")
    void menuInvalidOptionRerenders() throws Exception {
        mockMvc.perform(post("/menu")
                        .param("option", "99")
                        .param("pfkey", PFKEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_MENU))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("errmsg", containsString("valid option"))));
    }

    /**
     * An unmapped attention key re-displays the menu with the invalid-key message.
     *
     * <p>Reproduces the {@code EVALUATE EIBAID} {@code WHEN OTHER} branch of {@code MAIN-PARA}
     * (lines 99-102): any key other than {@code ENTER} or {@code PF3} re-renders the menu carrying
     * {@code CCDA-MSG-INVALID-KEY} ("{@code Invalid key pressed. Please see below...}"). {@code PF9}
     * is an unmapped program-function key on this screen.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /menu with an unmapped key re-renders COMEN01 with the invalid-key message")
    void menuInvalidKeyRerenders() throws Exception {
        mockMvc.perform(post("/menu")
                        .param("pfkey", PFKEY_PF9)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_MENU))
                .andExpect(model().attribute(MODEL_ATTR_FORM, hasProperty("errmsg", containsString("Invalid key pressed"))));
    }

    // ---------------------------------------------------------------------
    // Phase 4 - CSRF negative
    // ---------------------------------------------------------------------

    /**
     * A {@code POST /menu} without a CSRF token is rejected, even when authenticated.
     *
     * <p>CSRF protection stays enabled (the Spring Security default); the server-rendered
     * {@code COMEN01} form carries the token on every submission, so a token-less state-changing
     * request must be refused with {@code 403 Forbidden}.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /menu without a CSRF token is forbidden")
    void menuPostWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/menu")
                        .param("option", "1"))
                .andExpect(status().isForbidden());
    }
}
