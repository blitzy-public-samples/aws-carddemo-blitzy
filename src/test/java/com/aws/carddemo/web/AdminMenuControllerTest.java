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
package com.aws.carddemo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.dto.AdminMenuRequest;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.MenuMapper;
import com.aws.carddemo.security.CardDemoUserDetails;
import com.aws.carddemo.security.UserRole;
import com.aws.carddemo.service.MenuService;
import com.aws.carddemo.service.MenuService.MenuOption;
import com.aws.carddemo.service.MenuService.MenuRouting;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code @WebMvcTest} slice tests for {@link AdminMenuController} &mdash; the Java re-platform
 * of the online COBOL program {@code COADM01C} (CICS transaction {@code CA00}, BMS map
 * {@code COADM01}).
 *
 * <p>These tests lock down the caller-visible <strong>Admin Menu</strong> REST contract that the
 * migration must preserve (AAP &sect;0.7.1 H1/H2 and &sect;0.7.3 L1):</p>
 * <ul>
 *   <li><strong>Admin-only authorization</strong> (defense-in-depth): the URL rule
 *       {@code /api/v1/admin/**} &rarr; {@code hasRole("ADMIN")} in {@link SecurityConfig} plus
 *       the class-level {@code @PreAuthorize("hasRole('ADMIN')")} on the controller. The full
 *       authorization matrix is asserted &mdash; anonymous &rarr; 401, authenticated non-admin
 *       &rarr; 403 (with the service never invoked), authenticated admin &rarr; 200.</li>
 *   <li><strong>Option routing</strong> reproducing {@code PROCESS-ENTER-KEY}: option 1&ndash;4
 *       navigates to the four user-management programs {@code COUSR00C}&ndash;{@code COUSR03C}
 *       (CICS {@code CU00}&ndash;{@code CU03}), surfaced as the {@code X-CardDemo-Next-Program} /
 *       {@code X-CardDemo-Next-Transaction} navigation headers.</li>
 *   <li><strong>PF-key semantics</strong>: {@code Enter} processes the option, {@code PF3} returns
 *       to sign-on ({@code COSGN00C} / {@code CC00}), and any other attention key re-displays the
 *       menu with the "invalid key" message (the {@code EVALUATE EIBAID} {@code WHEN OTHER}).</li>
 *   <li><strong>Field-contract parity + {@code @Valid}</strong>: an out-of-shape {@code option}
 *       (non-digit or longer than two characters) yields an RFC&nbsp;7807
 *       {@code application/problem+json} 400 whose detail names the {@code option} field and never
 *       echoes the rejected value.</li>
 * </ul>
 *
 * <h2>Harness</h2>
 * <p>This is a pure web slice: the real {@link SecurityConfig} (so the URL rules,
 * {@code @EnableMethodSecurity} and the RFC&nbsp;7807 401/403 responders are active) and the real
 * {@link MenuMapper} (so the response body is assembled exactly as in production) are imported,
 * while the collaborating {@link MenuService} is a Mockito mock. No {@code UserDetailsService} is
 * declared (the web slice never loads the application's {@code @Service} beans, so there is no
 * database or {@code UserSecurityRepository} dependency); the tests supply the security context
 * directly with {@code @WithMockUser} or an explicit principal rather than performing a real
 * credential login. CSRF is disabled by the imported {@link SecurityConfig}, so the {@code POST}
 * cases need no CSRF token. The tests are deterministic and headless &mdash; no Docker, no
 * Testcontainers.</p>
 */
@WebMvcTest(AdminMenuController.class)
@Import({ SecurityConfig.class, MenuMapper.class })
class AdminMenuControllerTest {

    /** The single Admin-Menu endpoint path, shared by the {@code GET} and {@code POST} handlers. */
    private static final String MENU_PATH = "/api/v1/admin/menu";

    /** Navigation header carrying the target program name (the legacy {@code XCTL PROGRAM(...)}). */
    private static final String HEADER_NEXT_PROGRAM = "X-CardDemo-Next-Program";

    /** Navigation header carrying the target program's CICS transaction id. */
    private static final String HEADER_NEXT_TRANSACTION = "X-CardDemo-Next-Transaction";

    /** Header echoing the authenticated administrator's login id ({@code SEC-USR-ID}). */
    private static final String HEADER_USER_ID = "X-CardDemo-User-Id";

    /** Target program of the {@code PF3} back navigation (the sign-on screen). */
    private static final String BACK_PROGRAM = "COSGN00C";

    /** CICS transaction id under which {@link #BACK_PROGRAM} runs. */
    private static final String BACK_TRANSACTION = "CC00";

    /**
     * Expected "invalid key" status line for the {@code EVALUATE EIBAID} {@code WHEN OTHER} branch.
     * Mirrors the controller's private {@code INVALID_KEY_MESSAGE} constant (the trimmed text of
     * the COBOL {@code CCDA-MSG-INVALID-KEY} literal, copybook {@code CSMSG01Y}).
     */
    private static final String EXPECTED_INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /** Formatted admin option labels the mocked {@link MenuService} produces from {@code COADM02Y}. */
    private static final List<String> EXPECTED_ADMIN_LABELS = List.of(
            "01. User List (Security)",
            "02. User Add (Security)",
            "03. User Update (Security)",
            "04. User Delete (Security)");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MenuService menuService;

    /**
     * Stubs the mocked {@link MenuService} so {@code buildMenuResponse} rebuilds the four admin
     * option labels on every controller-body-reaching request, reproducing the {@code COADM02Y}
     * catalog and the {@code BUILD-MENU-OPTIONS} {@code "NN. Name"} label formatting. Called
     * explicitly only by tests that reach the controller body (authorization-denied tests must not
     * stub it, to prove the service is never invoked).
     */
    private void stubAdminCatalog() {
        List<MenuOption> options = List.of(
                new MenuOption(1, "User List (Security)", "COUSR00C", true),
                new MenuOption(2, "User Add (Security)", "COUSR01C", true),
                new MenuOption(3, "User Update (Security)", "COUSR02C", true),
                new MenuOption(4, "User Delete (Security)", "COUSR03C", true));
        when(menuService.getAdminMenu()).thenReturn(options);
        when(menuService.formatLabel(any(MenuOption.class))).thenAnswer(invocation -> {
            MenuOption option = invocation.getArgument(0);
            return String.format("%02d. %s", option.number(), option.name());
        });
    }

    /**
     * Serializes an {@link AdminMenuRequest} to its JSON body using the application
     * {@link ObjectMapper}; the record carries no constructor validation, so out-of-shape option
     * values (used by the {@code @Valid} tests) serialize just as a client would send them.
     */
    private String body(String option, PfKeyAction action) throws Exception {
        return objectMapper.writeValueAsString(new AdminMenuRequest(option, action));
    }

    // ---------------------------------------------------------------------------------------------
    // A. Authorization matrix (critical) - anonymous 401, non-admin 403, admin 200.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("A1: unauthenticated GET is rejected with 401 and never reaches the service")
    void getMenu_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(MENU_PATH))
                .andExpect(status().isUnauthorized());
        verify(menuService, never()).getAdminMenu();
    }

    @Test
    @DisplayName("A1: unauthenticated POST is rejected with 401 and never reaches the service")
    void postMenu_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(MENU_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", PfKeyAction.ENTER)))
                .andExpect(status().isUnauthorized());
        verify(menuService, never()).selectAdminMenuOption(any());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("A2: authenticated non-admin (USER) GET is forbidden with 403; service not invoked")
    void getMenu_asUser_returns403() throws Exception {
        mockMvc.perform(get(MENU_PATH))
                .andExpect(status().isForbidden());
        verify(menuService, never()).getAdminMenu();
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("A2: authenticated non-admin (USER) POST is forbidden with 403; service not invoked")
    void postMenu_asUser_returns403() throws Exception {
        mockMvc.perform(post(MENU_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", PfKeyAction.ENTER)))
                .andExpect(status().isForbidden());
        verify(menuService, never()).selectAdminMenuOption(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("A3: authenticated ADMIN GET is allowed with 200")
    void getMenu_asAdmin_returns200() throws Exception {
        stubAdminCatalog();
        mockMvc.perform(get(MENU_PATH))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------------
    // B. GET admin menu (ADMIN) - populated screen with the four COADM02Y labels, no error line.
    // ---------------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("B: GET returns the populated admin menu screen with the four admin labels")
    void getMenu_asAdmin_returnsPopulatedMenu() throws Exception {
        stubAdminCatalog();

        mockMvc.perform(get(MENU_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Header fields (COADM01 symbolic map) - transaction id and program name are the
                // controller's byte-for-byte COBOL header constants; the two title lines carry the
                // COTTL01Y text; the date/time lines are rendered by the mapper from a single now.
                .andExpect(jsonPath("$.transactionName").value("CA00"))
                .andExpect(jsonPath("$.programName").value("COADM01C"))
                .andExpect(jsonPath("$.title01", containsString("AWS Mainframe Modernization")))
                .andExpect(jsonPath("$.title02", containsString("CardDemo")))
                .andExpect(jsonPath("$.currentDate").exists())
                .andExpect(jsonPath("$.currentTime").exists())
                // The four admin option labels in COADM02Y order (BUILD-MENU-OPTIONS).
                .andExpect(jsonPath("$.menuOptions", hasSize(EXPECTED_ADMIN_LABELS.size())))
                .andExpect(jsonPath("$.menuOptions", contains(EXPECTED_ADMIN_LABELS.toArray(new String[0]))))
                // First-entry SEND path carries no status/error line (ERRMSGO blank -> omitted).
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        verify(menuService, never()).selectAdminMenuOption(any());
    }

    // ---------------------------------------------------------------------------------------------
    // C. POST Enter - route to each user-management screen (COUSR00C..COUSR03C / CU00..CU03).
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "option {0} routes to {1} / {2}")
    @CsvSource({
            "1, COUSR00C, CU00",
            "2, COUSR01C, CU01",
            "3, COUSR02C, CU02",
            "4, COUSR03C, CU03"
    })
    @WithMockUser(roles = "ADMIN")
    @DisplayName("C: POST Enter with a valid option navigates to the routed program via nav headers")
    void postEnter_validOption_routesToUserManagementProgram(String option, String program, String transaction)
            throws Exception {
        stubAdminCatalog();
        when(menuService.selectAdminMenuOption(option)).thenReturn(MenuRouting.route(program));

        mockMvc.perform(post(MENU_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(option, PfKeyAction.ENTER)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, program))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, transaction))
                // Menu is always rebuilt before the response is sent, and a successful navigation
                // carries no error line.
                .andExpect(jsonPath("$.menuOptions", hasSize(EXPECTED_ADMIN_LABELS.size())))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        // The raw operator-typed option is routed verbatim (no parsing/range-check in the controller).
        ArgumentCaptor<String> optionCaptor = ArgumentCaptor.forClass(String.class);
        verify(menuService).selectAdminMenuOption(optionCaptor.capture());
        assertThat(optionCaptor.getValue()).isEqualTo(option);
    }

    // ---------------------------------------------------------------------------------------------
    // D. POST Enter - invalid option number: set message and re-display, no navigation.
    // ---------------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("D: POST Enter with an invalid option re-displays the menu with the error message")
    void postEnter_invalidOption_rebuildsMenuWithMessage() throws Exception {
        stubAdminCatalog();
        // A well-formed (two-digit) but out-of-range option: the service reports the exact COBOL
        // "Please enter a valid option number..." message and no navigation occurs.
        when(menuService.selectAdminMenuOption("9"))
                .thenReturn(MenuRouting.error(MenuService.INVALID_OPTION_MESSAGE));

        mockMvc.perform(post(MENU_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("9", PfKeyAction.ENTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MenuService.INVALID_OPTION_MESSAGE))
                // Menu rebuilt; a non-navigating outcome carries no navigation headers.
                .andExpect(jsonPath("$.menuOptions", hasSize(EXPECTED_ADMIN_LABELS.size())))
                .andExpect(header().doesNotExist(HEADER_NEXT_PROGRAM))
                .andExpect(header().doesNotExist(HEADER_NEXT_TRANSACTION));

        verify(menuService).selectAdminMenuOption("9");
    }

    // ---------------------------------------------------------------------------------------------
    // E. POST PF3 - RETURN-TO-SIGNON-SCREEN (XCTL to COSGN00C / CC00), service not consulted.
    // ---------------------------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("E: POST PF3 navigates back to the sign-on screen (COSGN00C / CC00)")
    void postPf3_navigatesBackToSignon() throws Exception {
        stubAdminCatalog();

        mockMvc.perform(post(MENU_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, PfKeyAction.PF3)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_NEXT_PROGRAM, BACK_PROGRAM))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, BACK_TRANSACTION))
                .andExpect(jsonPath("$.menuOptions", hasSize(EXPECTED_ADMIN_LABELS.size())))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        // PF3 is a pure navigation: the option-selection service is never consulted.
        verify(menuService, never()).selectAdminMenuOption(any());
    }

    // ---------------------------------------------------------------------------------------------
    // F. POST any other attention key (WHEN OTHER) - invalid-key message, no navigation.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "action {0}")
    @EnumSource(value = PfKeyAction.class, names = { "PF7", "PF8", "CLEAR", "PA1", "PA2", "PF1", "PF12" })
    @WithMockUser(roles = "ADMIN")
    @DisplayName("F: POST an unsupported attention key re-displays the menu with the invalid-key message")
    void postOtherKey_rebuildsMenuWithInvalidKeyMessage(PfKeyAction action) throws Exception {
        stubAdminCatalog();

        mockMvc.perform(post(MENU_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(EXPECTED_INVALID_KEY_MESSAGE))
                .andExpect(jsonPath("$.menuOptions", hasSize(EXPECTED_ADMIN_LABELS.size())))
                .andExpect(header().doesNotExist(HEADER_NEXT_PROGRAM))
                .andExpect(header().doesNotExist(HEADER_NEXT_TRANSACTION));

        verify(menuService, never()).selectAdminMenuOption(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("F: POST with a null/absent action falls through to the invalid-key WHEN OTHER branch")
    void postNullAction_rebuildsMenuWithInvalidKeyMessage() throws Exception {
        stubAdminCatalog();

        mockMvc.perform(post(MENU_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(EXPECTED_INVALID_KEY_MESSAGE))
                .andExpect(jsonPath("$.menuOptions", hasSize(EXPECTED_ADMIN_LABELS.size())))
                .andExpect(header().doesNotExist(HEADER_NEXT_PROGRAM))
                .andExpect(header().doesNotExist(HEADER_NEXT_TRANSACTION));

        verify(menuService, never()).selectAdminMenuOption(any());
    }

    // ---------------------------------------------------------------------------------------------
    // G. Field-contract parity + @Valid - out-of-shape option -> RFC 7807 400, no value leaked.
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "invalid option \"{0}\"")
    @ValueSource(strings = { "AB", "1A", "999" })
    @WithMockUser(roles = "ADMIN")
    @DisplayName("G: POST with an out-of-shape option returns a 400 problem+json naming the field, no value leaked")
    void postEnter_invalidOptionShape_returns400ProblemDetail(String badOption) throws Exception {
        String requestBody = body(badOption, PfKeyAction.ENTER);

        String responseBody = mockMvc.perform(post(MENU_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                // The detail names the offending field ("option") ...
                .andExpect(jsonPath("$.detail", containsString("option")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // ... but never echoes the rejected value (it could be a password or a card CVV elsewhere).
        assertThat(responseBody).doesNotContain(badOption);
        // Validation fails before the controller body, so the routing service is never consulted.
        verify(menuService, never()).selectAdminMenuOption(any());
    }

    // ---------------------------------------------------------------------------------------------
    // H. Authenticated principal - the login id is echoed in the X-CardDemo-User-Id header.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("H: a bound CardDemoUserDetails principal has its login id echoed in X-CardDemo-User-Id")
    void postEnter_withPrincipal_echoesUserIdHeader() throws Exception {
        stubAdminCatalog();
        when(menuService.selectAdminMenuOption("1")).thenReturn(MenuRouting.route("COUSR00C"));

        CardDemoUserDetails principal = new CardDemoUserDetails("ADMIN001", "x", UserRole.ADMIN);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, "x", principal.getAuthorities());

        mockMvc.perform(post(MENU_PATH)
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1", PfKeyAction.ENTER)))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER_USER_ID, "ADMIN001"))
                .andExpect(header().string(HEADER_NEXT_PROGRAM, "COUSR00C"))
                .andExpect(header().string(HEADER_NEXT_TRANSACTION, "CU00"));
    }
}
