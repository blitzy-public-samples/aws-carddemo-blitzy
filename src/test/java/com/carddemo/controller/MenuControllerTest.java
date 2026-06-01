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
package com.carddemo.controller;

import com.carddemo.controller.advice.GlobalExceptionHandler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc web-slice test for {@link MenuController} ({@code GET /api/menu}).
 *
 * <p>{@code MenuController} consolidates and replaces the two legacy CardDemo CICS menu
 * programs &mdash; {@code app/cbl/COMEN01C.cbl} (the regular-user main menu, TRANID
 * {@code CM00}) and {@code app/cbl/COADM01C.cbl} (the administrator menu, TRANID
 * {@code CA00}). The concrete option lists come verbatim from the COBOL menu copybooks:
 * {@code app/cpy/COMEN02Y.cpy} defines the 10 user options (every entry
 * {@code CDEMO-MENU-OPT-USRTYPE = 'U'}) and {@code app/cpy/COADM02Y.cpy} defines the 4
 * user-administration options (implicitly {@code 'A'}). This test verifies the controller's
 * single observable behaviour: <strong>role-based menu filtering</strong>.</p>
 *
 * <h2>What is asserted</h2>
 * <ul>
 *   <li><strong>ADMIN role</strong> &rarr; {@code 200 OK} with EXACTLY the 4 admin
 *       (COADM02Y) options; the response advertises the user-administration program
 *       {@code COUSR00C}; {@code userType == 'A'}.</li>
 *   <li><strong>USER role</strong> &rarr; {@code 200 OK} with EXACTLY the 10 user
 *       (COMEN02Y) options; the response NEVER advertises any of the 4 admin-only
 *       {@code COUSR00C}&ndash;{@code COUSR03C} programs; {@code userType == 'U'}.</li>
 *   <li><strong>Anonymous principal</strong> &rarr; in this filter-less slice the request
 *       reaches the controller and is treated as a non-admin user, so it must never be shown
 *       the privileged {@code COUSR*} options (the production {@code 401} for anonymous
 *       callers is enforced by the {@code SecurityFilterChain} and is covered by
 *       {@code com.carddemo.integration.SecurityIT}).</li>
 *   <li><strong>Empty security context</strong> &rarr; the controller's defensive guard
 *       returns {@code 401 Unauthorized} (the REST analogue of {@code COMEN01C}'s
 *       {@code IF EIBCALEN = 0 ... RETURN-TO-SIGNON-SCREEN} missing-COMMAREA check).</li>
 * </ul>
 *
 * <h2>Why the assertions use {@code pgmName} (not {@code transactionId})</h2>
 * <p>The actual {@code com.carddemo.dto.menu.MenuOption} record exposes the fields
 * {@code optNum}, {@code optName}, {@code pgmName}, {@code requiredRole} and {@code route}
 * &mdash; it preserves the original COBOL program name (e.g. {@code "COUSR00C"},
 * {@code "COACTVWC"}) in {@code pgmName} for audit/traceability, and there is no
 * {@code transactionId} field. The admin "marker" advertised to ADMIN callers is therefore
 * {@code pgmName == "COUSR00C"} (User List / Security), and the canonical user option is
 * {@code pgmName == "COACTVWC"} (Account View). These assertions are deliberately written
 * against the real DTO contract.</p>
 *
 * <h2>Slice configuration</h2>
 * <ul>
 *   <li>{@link WebMvcTest @WebMvcTest(controllers = MenuController.class)} loads only the
 *       {@code MenuController} web layer.</li>
 *   <li>{@code excludeFilters} drops the whole {@code com.carddemo.security} package from the
 *       slice's component scan. A {@code @WebMvcTest} slice always registers application
 *       {@code jakarta.servlet.Filter} beans, and the production {@code JwtAuthenticationFilter}
 *       is a {@code @Component} extending {@code OncePerRequestFilter}; left untouched it would
 *       be instantiated here and fail the context with an {@code UnsatisfiedDependencyException}
 *       because its {@code CustomAuthorityMapper} collaborator (a plain {@code @Component}) is
 *       not loaded by the slice. {@code @AutoConfigureMockMvc(addFilters = false)} only removes
 *       filters from the MockMvc chain &mdash; it does NOT prevent the bean from being created
 *       &mdash; so the exclusion is required.</li>
 *   <li>{@link Import @Import(GlobalExceptionHandler.class)} wires the
 *       {@code @RestControllerAdvice} so HTTP status-code assertions reflect the production
 *       error-handling behaviour.</li>
 *   <li>{@link AutoConfigureMockMvc @AutoConfigureMockMvc(addFilters = false)} disables the
 *       security filter chain so that the {@link WithMockUser}-populated
 *       {@code SecurityContextHolder} flows straight through to the controller (which resolves
 *       the caller's role from the security context rather than via {@code @PreAuthorize}).</li>
 * </ul>
 *
 * <p>{@code MenuController} declares no injected collaborators (its menus are immutable static
 * configuration and the caller identity is read from {@code SecurityContextHolder}), so no
 * {@code @MockBean} is required.</p>
 *
 * <h2>Refactoring rules exercised</h2>
 * <ul>
 *   <li><strong>PR-18</strong> &mdash; the user menu MUST NOT advertise the admin-only
 *       {@code COUSR00C}&ndash;{@code COUSR03C} programs to a non-admin caller.</li>
 *   <li><strong>PR-19</strong> &mdash; {@code ROLE_ADMIN} ({@code 'A'}) selects the admin menu;
 *       {@code ROLE_USER} ({@code 'U'}) selects the user menu.</li>
 *   <li><strong>AAP &sect;0.6.1</strong> &mdash; the legacy {@code CDEMO-USER-ID} /
 *       {@code CDEMO-USER-TYPE} COMMAREA fields are sourced from the Spring Security
 *       {@code Authentication} (asserted via {@code $.userId} / {@code $.userType}).</li>
 *   <li><strong>PR-28</strong> &mdash; Jakarta EE 10 baseline (no {@code javax.*}).</li>
 * </ul>
 *
 * @see MenuController
 * @see com.carddemo.dto.menu.MenuResponse
 * @see com.carddemo.dto.menu.MenuOption
 * @see GlobalExceptionHandler
 */
@WebMvcTest(
        controllers = MenuController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("MenuController web-slice tests (GET /api/menu role filtering)")
class MenuControllerTest {

    /** Auto-configured MockMvc for the {@code MenuController} web slice (security filters disabled). */
    @Autowired
    private MockMvc mockMvc;

    // -------------------------------------------------------------------------
    // Expected COBOL program names (the MenuOption.pgmName field), kept verbatim
    // from the source copybooks so the assertions document the exact contract.
    // -------------------------------------------------------------------------

    /** COADM02Y option 1 &mdash; "User List (Security)"; the admin-menu marker program. */
    private static final String ADMIN_PGM_USER_LIST = "COUSR00C";
    /** COADM02Y option 2 &mdash; "User Add (Security)". */
    private static final String ADMIN_PGM_USER_ADD = "COUSR01C";
    /** COADM02Y option 3 &mdash; "User Update (Security)". */
    private static final String ADMIN_PGM_USER_UPDATE = "COUSR02C";
    /** COADM02Y option 4 &mdash; "User Delete (Security)". */
    private static final String ADMIN_PGM_USER_DELETE = "COUSR03C";

    /** COMEN02Y option 1 &mdash; "Account View"; the canonical user-menu program. */
    private static final String USER_PGM_ACCOUNT_VIEW = "COACTVWC";

    /**
     * The 10 user-menu program names in exact {@code COMEN02Y.cpy} order. Used with
     * {@code everyItem(in(...))} to assert that the user menu advertises ONLY these
     * known transactional programs and nothing foreign.
     */
    private static final List<String> EXPECTED_USER_PGM_NAMES = List.of(
            "COACTVWC", // 1  Account View
            "COACTUPC", // 2  Account Update
            "COCRDLIC", // 3  Credit Card List
            "COCRDSLC", // 4  Credit Card View
            "COCRDUPC", // 5  Credit Card Update
            "COTRN00C", // 6  Transaction List
            "COTRN01C", // 7  Transaction View
            "COTRN02C", // 8  Transaction Add
            "CORPT00C", // 9  Transaction Reports
            "COBIL00C"  // 10 Bill Payment
    );

    /**
     * Behavioural tests for {@code GET /api/menu}. The endpoint returns a role-filtered
     * {@code MenuResponse}: an ADMIN caller receives the 4 COADM02Y user-administration
     * options, any other authenticated caller receives the 10 COMEN02Y transactional
     * options, and the two menus are never merged (strict COBOL parity).
     */
    @Nested
    @DisplayName("GET /api/menu — role-based filtering (COMEN01C user menu + COADM01C admin menu)")
    class GetMenu {

        @Test
        @DisplayName("ADMIN role → 200 OK with the 4-option admin menu (COADM02Y; advertises COUSR00C)")
        @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
        void shouldReturnAdminMenu() throws Exception {
            mockMvc.perform(get("/api/menu"))
                    .andExpect(status().isOk())
                    // PR-19: ROLE_ADMIN → userType 'A'. AAP §0.6.1: userId echoes auth.getName()
                    // (the legacy CDEMO-USER-ID carried in the COMMAREA).
                    .andExpect(jsonPath("$.userType").value("A"))
                    .andExpect(jsonPath("$.userId").value("ADMIN001"))
                    .andExpect(jsonPath("$.options").isArray())
                    // Strict COBOL parity: the admin menu is EXACTLY the 4 COADM02Y options.
                    .andExpect(jsonPath("$.options", hasSize(4)))
                    .andExpect(jsonPath("$.options.length()", greaterThanOrEqualTo(4)))
                    // The admin menu MUST advertise the user-administration program COUSR00C.
                    .andExpect(jsonPath("$.options[*].pgmName", hasItem(ADMIN_PGM_USER_LIST)))
                    // Every admin option carries requiredRole 'A' (COADM02Y is implicitly admin-only).
                    .andExpect(jsonPath("$.options[*].requiredRole", everyItem(in(List.of("A")))));
        }

        @Test
        @DisplayName("USER role → 200 OK with the 10-option user menu (COMEN02Y; never advertises COUSR00C–COUSR03C)")
        @WithMockUser(username = "USER0001", roles = {"USER"})
        void shouldReturnUserMenu() throws Exception {
            mockMvc.perform(get("/api/menu"))
                    .andExpect(status().isOk())
                    // PR-19: ROLE_USER → userType 'U'. AAP §0.6.1: userId echoes auth.getName().
                    .andExpect(jsonPath("$.userType").value("U"))
                    .andExpect(jsonPath("$.userId").value("USER0001"))
                    .andExpect(jsonPath("$.options").isArray())
                    // Strict COBOL parity: the user menu is EXACTLY the 10 COMEN02Y options.
                    .andExpect(jsonPath("$.options", hasSize(10)))
                    // PR-18 (critical): the user menu MUST NOT advertise ANY admin-only (COUSR*)
                    // program — a non-admin caller must never even see these navigation targets.
                    .andExpect(jsonPath("$.options[*].pgmName", not(hasItem(ADMIN_PGM_USER_LIST))))
                    .andExpect(jsonPath("$.options[*].pgmName", not(hasItem(ADMIN_PGM_USER_ADD))))
                    .andExpect(jsonPath("$.options[*].pgmName", not(hasItem(ADMIN_PGM_USER_UPDATE))))
                    .andExpect(jsonPath("$.options[*].pgmName", not(hasItem(ADMIN_PGM_USER_DELETE))));
        }

        @Test
        @DisplayName("USER role menu includes the standard transactional options (Account View → COACTVWC)")
        @WithMockUser(roles = {"USER"})
        void shouldIncludeStandardUserTransactions() throws Exception {
            mockMvc.perform(get("/api/menu"))
                    .andExpect(status().isOk())
                    // Account View (COMEN02Y option 1) must be present in the user menu.
                    .andExpect(jsonPath("$.options[*].pgmName", hasItem(USER_PGM_ACCOUNT_VIEW)))
                    // Every advertised user program must be one of the 10 known COMEN02Y programs.
                    .andExpect(jsonPath("$.options[*].pgmName", everyItem(in(EXPECTED_USER_PGM_NAMES))));
        }

        @Test
        @DisplayName("ADMIN role menu is non-empty")
        @WithMockUser(roles = {"ADMIN"})
        void shouldReturnNonEmptyAdminMenu() throws Exception {
            mockMvc.perform(get("/api/menu"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.options").isArray())
                    .andExpect(jsonPath("$.options.length()", greaterThan(0)));
        }

        @Test
        @DisplayName("Each menu option exposes optNum, optName, pgmName, requiredRole and route")
        @WithMockUser(roles = {"USER"})
        void shouldExposeExpectedMenuOptionStructure() throws Exception {
            mockMvc.perform(get("/api/menu"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.options").isArray())
                    // The MenuOption record fields: optNum, optName, pgmName, requiredRole, route.
                    .andExpect(jsonPath("$.options[0].optNum").exists())
                    .andExpect(jsonPath("$.options[0].optName").exists())
                    .andExpect(jsonPath("$.options[0].pgmName").exists())
                    .andExpect(jsonPath("$.options[0].requiredRole").exists())
                    .andExpect(jsonPath("$.options[0].route").exists());
        }

        @Test
        @DisplayName("Anonymous principal never receives admin options (slice returns 200; prod 401 via SecurityFilterChain)")
        @WithAnonymousUser
        void anonymousNeverReceivesAdminOptions() throws Exception {
            // With @AutoConfigureMockMvc(addFilters = false) the Spring Security filter chain is
            // NOT applied, and an AnonymousAuthenticationToken reports isAuthenticated() == true,
            // so the controller's "auth == null || !auth.isAuthenticated()" guard is not triggered:
            // the request proceeds and — because the anonymous principal holds no ROLE_ADMIN
            // authority — receives the USER menu. The production rejection of anonymous callers
            // with 401 happens in the SecurityFilterChain (see com.carddemo.integration.SecurityIT),
            // which this web slice deliberately omits. The security-critical invariant verified here
            // (PR-18) is that an unauthenticated/anonymous principal is NEVER shown the privileged
            // COUSR* options.
            mockMvc.perform(get("/api/menu"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.options[*].pgmName", not(hasItem(ADMIN_PGM_USER_LIST))));
        }

        @Test
        @DisplayName("Empty security context → 401 Unauthorized (controller defensive guard; COMEN01C EIBCALEN=0 analogue)")
        void unauthenticatedNoSecurityContextReturns401() throws Exception {
            // No @WithMockUser / @WithAnonymousUser annotation → the security test listener leaves
            // the SecurityContextHolder empty (it is cleared after every test method), so
            // SecurityContextHolder.getContext().getAuthentication() returns null. With the security
            // filter chain disabled (addFilters = false) nothing populates an anonymous token either,
            // so the controller's "auth == null" guard fires and returns 401 — the REST analogue of
            // COMEN01C's "IF EIBCALEN = 0 ... RETURN-TO-SIGNON-SCREEN" missing-COMMAREA check.
            mockMvc.perform(get("/api/menu"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
