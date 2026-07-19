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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.aws.carddemo.config.SecurityConfig;
import com.aws.carddemo.dto.MainMenuRequest;
import com.aws.carddemo.dto.PfKeyAction;
import com.aws.carddemo.mapper.MenuMapper;
import com.aws.carddemo.security.CardDemoUserDetails;
import com.aws.carddemo.security.UserRole;
import com.aws.carddemo.service.MenuService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code @WebMvcTest} slice tests for {@link MainMenuController} &mdash; the Java re-platform of the
 * CICS online COBOL program {@code COMEN01C} (BMS map {@code COMEN01}, CICS transaction
 * {@code CM00}). The tests lock down the Main Menu REST contract: field-contract parity with the BMS
 * screen, PF-key semantics ({@code EVALUATE EIBAID}), option routing ({@code PROCESS-ENTER-KEY}), and
 * the pseudo-conversational trust boundary in which the user type is carried by the security context
 * (the legacy {@code CDEMO-USER-TYPE} COMMAREA field), never by the request body (AAP hotspots
 * H1/H2).
 *
 * <h2>Why this is a pure web slice</h2>
 * The test loads only the web layer: the controller under test, the real {@link SecurityConfig}
 * filter chain, and the real {@link MenuMapper} response assembler. The business collaborator
 * {@link MenuService} is replaced with a Mockito mock ({@link MockitoBean}) so that each menu outcome
 * (route, invalid option, admin-only denial) can be driven deterministically without a database. No
 * Testcontainers or Docker are used, so the class is fully deterministic and headless and contributes
 * to the {@code >= 80%} JaCoCo line-coverage gate.
 *
 * <h2>Principal-aware authentication (mandatory)</h2>
 * The controller resolves {@code @AuthenticationPrincipal CardDemoUserDetails principal} and derives
 * the user type from {@code principal.getRole().getCode().charAt(0)}. A plain {@code @WithMockUser}
 * would inject a framework {@code User} (not a {@link CardDemoUserDetails}) and leave {@code principal}
 * null, so every authenticated case installs a real {@link CardDemoUserDetails} via
 * {@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#user(org.springframework.security.core.userdetails.UserDetails)}.
 * Security filters are left enabled (the argument resolver and authorization rules are part of the
 * contract under test); the real {@link SecurityConfig} disables CSRF for this stateless HTTP&nbsp;Basic
 * API, so POSTs need no CSRF token.
 */
@WebMvcTest(MainMenuController.class)
@Import({ SecurityConfig.class, MenuMapper.class })
class MainMenuControllerTest {

    /** Base path of the Main Menu endpoint (COBOL {@code COMEN01C} / CICS {@code CM00}). */
    private static final String MENU_PATH = "/api/v1/menu";

    /** Navigation header carrying the next program name (the transport-neutral {@code XCTL} target). */
    private static final String NEXT_PROGRAM_HEADER = "X-CardDemo-Next-Program";

    /** Navigation header carrying the next CICS transaction id for the routed program. */
    private static final String NEXT_TRANSACTION_HEADER = "X-CardDemo-Next-Transaction";

    /**
     * Caller-visible message for an unrecognized attention key, mirrored verbatim from the
     * {@code WHEN OTHER} branch of {@code COMEN01C} ({@code CCDA-MSG-INVALID-KEY}). Kept in sync with
     * {@code MainMenuController}'s private {@code INVALID_KEY_MESSAGE} constant.
     */
    private static final String INVALID_KEY_MESSAGE = "Invalid key pressed. Please see below...";

    /**
     * A representative, deterministic slice of the {@code COMEN02Y} main-menu catalog used to drive
     * {@link MenuService#getMainMenu()}. Only the first three options are needed to prove the
     * controller rebuilds and formats the option list on every response; the numbers, names, and
     * target programs are the real catalog values.
     */
    private static final List<MenuService.MenuOption> STUB_CATALOG = List.of(
            new MenuService.MenuOption(1, "Account View", "COACTVWC", false),
            new MenuService.MenuOption(2, "Account Update", "COACTUPC", false),
            new MenuService.MenuOption(3, "Credit Card List", "COCRDLIC", false));

    /**
     * An authenticated regular-user principal (role {@code 'U'}). The password is an opaque,
     * never-used placeholder (authentication is bypassed by the {@code user(...)} request
     * post-processor), so it is intentionally not a real credential.
     */
    private static final CardDemoUserDetails USER_PRINCIPAL =
            new CardDemoUserDetails("USER0001", "placeholder-unused", UserRole.USER);

    /**
     * An authenticated administrator principal (role {@code 'A'}). As with {@link #USER_PRINCIPAL},
     * the password is an opaque, never-used placeholder.
     */
    private static final CardDemoUserDetails ADMIN_PRINCIPAL =
            new CardDemoUserDetails("ADMIN001", "placeholder-unused", UserRole.ADMIN);

    /** Auto-configured MockMvc with the real Spring Security filter chain applied. */
    @Autowired
    private MockMvc mockMvc;

    /** Boot-configured Jackson mapper, used to serialize request DTOs to JSON. */
    @Autowired
    private ObjectMapper objectMapper;

    /** Mocked business collaborator; the controller's only injected service dependency. */
    @MockitoBean
    private MenuService menuService;

    /**
     * Stubs the menu catalog and label formatter before every test so the controller's
     * {@code buildMenuResponse(...)} always yields a non-empty, deterministically formatted option
     * list (the legacy {@code BUILD-MENU-OPTIONS} rebuilds the list before every SEND). The label
     * formatter reproduces the real {@code "NN. Name"} rendering so assertions can pin exact values.
     * Tests that never reach the controller (unauthenticated and validation-failure cases) simply do
     * not exercise these stubs.
     */
    @BeforeEach
    void stubMenuCatalog() {
        when(menuService.getMainMenu()).thenReturn(STUB_CATALOG);
        when(menuService.formatLabel(any(MenuService.MenuOption.class)))
                .thenAnswer(invocation -> {
                    MenuService.MenuOption option = invocation.getArgument(0);
                    return String.format("%02d. %s", option.number(), option.name());
                });
    }

    // ------------------------------------------------------------------
    // A. Authentication gate (stateless HTTP Basic, deny-by-default)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A1: GET /menu without authentication -> 401 Unauthorized")
    void getMenuWithoutAuthenticationReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(MENU_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A2: POST /menu without authentication -> 401 Unauthorized")
    void postMenuWithoutAuthenticationReturnsUnauthorized() throws Exception {
        mockMvc.perform(post(MENU_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MainMenuRequest("1", PfKeyAction.ENTER))))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // B. GET menu as a regular user (first-entry SEND parity)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B: GET /menu (USER) -> 200 with formatted catalog, header fields, no error line")
    void getMenuAsUserReturnsPopulatedMenu() throws Exception {
        mockMvc.perform(get(MENU_PATH).with(user(USER_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Header/metadata fields (BMS COMEN01 header contract).
                .andExpect(jsonPath("$.transactionName").value("CM00"))
                .andExpect(jsonPath("$.programName").value("COMEN01C"))
                .andExpect(jsonPath("$.title01").value(containsString("AWS Mainframe Modernization")))
                .andExpect(jsonPath("$.title02").value(containsString("CardDemo")))
                .andExpect(jsonPath("$.currentDate").isNotEmpty())
                .andExpect(jsonPath("$.currentTime").isNotEmpty())
                // Option label lines (OPTN001O..), rebuilt and formatted from the catalog.
                .andExpect(jsonPath("$.menuOptions", hasSize(3)))
                .andExpect(jsonPath("$.menuOptions[0]").value("01. Account View"))
                .andExpect(jsonPath("$.menuOptions[1]").value("02. Account Update"))
                .andExpect(jsonPath("$.menuOptions[2]").value("03. Credit Card List"))
                // No error/status line on the first-entry SEND (NON_NULL inclusion omits it).
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    // ------------------------------------------------------------------
    // C. GET menu as an administrator (display is role-independent)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C: GET /menu (ADMIN) -> 200; catalog built identically to the user view")
    void getMenuAsAdminBuildsSameCatalog() throws Exception {
        // COMEN01C BUILD-MENU-OPTIONS lists every option unconditionally; the admin-only restriction
        // is enforced only at selection time, so the *display* is independent of the caller's role.
        // The controller therefore builds the same catalog for an ADMIN as for a USER; no admin-only
        // display filtering is invented here.
        mockMvc.perform(get(MENU_PATH).with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.programName").value("COMEN01C"))
                .andExpect(jsonPath("$.menuOptions", hasSize(3)))
                .andExpect(jsonPath("$.menuOptions[0]").value("01. Account View"))
                .andExpect(jsonPath("$.menuOptions[1]").value("02. Account Update"))
                .andExpect(jsonPath("$.menuOptions[2]").value("03. Credit Card List"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());
    }

    // ------------------------------------------------------------------
    // D. ENTER -> valid option routes forward (XCTL replacement) and the
    //    user type is derived from the principal, never the request body.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D1: POST ENTER valid option -> 200 + navigation headers; userType from principal")
    void postEnterValidOptionRoutesForwardWithNavigationHeaders() throws Exception {
        // Option 1 (Account View) routes to COACTVWC; the controller maps that program to CICS
        // transaction CAVW via its CARDDEMO.CSD-derived registry.
        when(menuService.selectMainMenuOption("1", 'U'))
                .thenReturn(MenuService.MenuRouting.route("COACTVWC"));

        mockMvc.perform(post(MENU_PATH).with(user(USER_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MainMenuRequest("1", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(header().string(NEXT_PROGRAM_HEADER, "COACTVWC"))
                .andExpect(header().string(NEXT_TRANSACTION_HEADER, "CAVW"))
                // The menu body is still fully rebuilt alongside the navigation headers.
                .andExpect(jsonPath("$.menuOptions", hasSize(3)))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        // Prove the user type passed to the service is 'U' (from the principal), not the body.
        ArgumentCaptor<Character> userTypeCaptor = ArgumentCaptor.forClass(Character.class);
        verify(menuService).selectMainMenuOption(eq("1"), userTypeCaptor.capture());
        assertThat(userTypeCaptor.getValue())
                .as("userType must be derived from the authenticated principal (U)")
                .isEqualTo('U');
    }

    @Test
    @DisplayName("D2: POST ENTER with a smuggled userType field -> ignored; principal type wins")
    void postEnterDerivesUserTypeFromPrincipalNotBody() throws Exception {
        when(menuService.selectMainMenuOption("1", 'U'))
                .thenReturn(MenuService.MenuRouting.route("COACTVWC"));

        // The body attempts to smuggle an admin userType. MainMenuRequest has no userType component
        // and unknown JSON properties are ignored, so the smuggled value must have no effect: the
        // controller still derives 'U' from the USER principal (the COMMAREA trust boundary, H1/H2).
        String smuggledBody = "{\"option\":\"1\",\"action\":\"ENTER\",\"userType\":\"A\"}";

        mockMvc.perform(post(MENU_PATH).with(user(USER_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smuggledBody))
                .andExpect(status().isOk())
                .andExpect(header().string(NEXT_PROGRAM_HEADER, "COACTVWC"))
                .andExpect(header().string(NEXT_TRANSACTION_HEADER, "CAVW"));

        ArgumentCaptor<Character> userTypeCaptor = ArgumentCaptor.forClass(Character.class);
        verify(menuService).selectMainMenuOption(eq("1"), userTypeCaptor.capture());
        assertThat(userTypeCaptor.getValue())
                .as("smuggled body userType ('A') must be ignored; principal type ('U') must win")
                .isEqualTo('U');
    }

    // ------------------------------------------------------------------
    // E. ENTER -> invalid option: message re-displayed, menu rebuilt, no nav.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("E: POST ENTER invalid option -> 200 + invalid-option message, menu rebuilt, no nav")
    void postEnterInvalidOptionReturnsMessageAndRebuildsMenu() throws Exception {
        when(menuService.selectMainMenuOption(anyString(), eq('U')))
                .thenReturn(MenuService.MenuRouting.error(MenuService.INVALID_OPTION_MESSAGE));

        mockMvc.perform(post(MENU_PATH).with(user(USER_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MainMenuRequest("99", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MenuService.INVALID_OPTION_MESSAGE))
                // The option list is still rebuilt on the error re-display.
                .andExpect(jsonPath("$.menuOptions", hasSize(3)))
                .andExpect(header().doesNotExist(NEXT_PROGRAM_HEADER))
                .andExpect(header().doesNotExist(NEXT_TRANSACTION_HEADER));
    }

    @Test
    @DisplayName("E2: POST ENTER non-numeric option \"5A\" -> 200 (NOT 400) + invalid-option message; the raw value reaches MenuService")
    void postEnterNonNumericOptionReachesServiceReturns200() throws Exception {
        // F-MENU-1 regression guard. The option field's @Pattern is a width-only guard
        // ("^.{0,2}$"), so a non-numeric option is NOT rejected at the bean-validation boundary
        // with HTTP 400; it flows into MenuService whose IS-NOT-NUMERIC check (COBOL COMEN01C
        // L127-129) re-displays the same screen with the invalid-option message at HTTP 200. The
        // pre-fix strict "^\\d{0,2}$" pattern produced a 400 here and never invoked the service.
        when(menuService.selectMainMenuOption(anyString(), eq('U')))
                .thenReturn(MenuService.MenuRouting.error(MenuService.INVALID_OPTION_MESSAGE));

        mockMvc.perform(post(MENU_PATH).with(user(USER_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MainMenuRequest("5A", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MenuService.INVALID_OPTION_MESSAGE))
                .andExpect(jsonPath("$.menuOptions", hasSize(3)))
                .andExpect(header().doesNotExist(NEXT_PROGRAM_HEADER))
                .andExpect(header().doesNotExist(NEXT_TRANSACTION_HEADER));

        // The raw operator value reaches the service verbatim (no controller-side pre-filtering).
        verify(menuService).selectMainMenuOption(eq("5A"), eq('U'));
    }

    @Test
    @DisplayName("E3: POST ENTER blank/space option \" \" -> 200 (NOT 400) + invalid-option message; the raw value reaches MenuService")
    void postEnterSpaceOptionReachesServiceReturns200() throws Exception {
        // COBOL folds blanks to '0' -> WS-OPTION = ZEROS -> invalid (COMEN01C L123-129). A
        // space-only option must therefore reach the service and yield a 200 same-screen
        // redisplay, not a bean-validation 400.
        when(menuService.selectMainMenuOption(anyString(), eq('U')))
                .thenReturn(MenuService.MenuRouting.error(MenuService.INVALID_OPTION_MESSAGE));

        mockMvc.perform(post(MENU_PATH).with(user(USER_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MainMenuRequest(" ", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MenuService.INVALID_OPTION_MESSAGE))
                .andExpect(header().doesNotExist(NEXT_PROGRAM_HEADER));

        verify(menuService).selectMainMenuOption(eq(" "), eq('U'));
    }

    // ------------------------------------------------------------------
    // F. ENTER -> admin-only option selected by a regular user: denial surfaced.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("F: POST ENTER admin-only option (USER) -> 200 + denial message surfaced, no nav")
    void postEnterAdminOnlyOptionByUserSurfacesDenialMessage() throws Exception {
        // The admin-only gate lives in MenuService (COMEN01C PROCESS-ENTER-KEY); the controller must
        // faithfully surface the exact caller-visible message and not navigate.
        when(menuService.selectMainMenuOption(anyString(), eq('U')))
                .thenReturn(MenuService.MenuRouting.error(MenuService.NO_ACCESS_ADMIN_ONLY_MESSAGE));

        mockMvc.perform(post(MENU_PATH).with(user(USER_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MainMenuRequest("1", PfKeyAction.ENTER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MenuService.NO_ACCESS_ADMIN_ONLY_MESSAGE))
                .andExpect(jsonPath("$.menuOptions", hasSize(3)))
                .andExpect(header().doesNotExist(NEXT_PROGRAM_HEADER))
                .andExpect(header().doesNotExist(NEXT_TRANSACTION_HEADER));
    }

    // ------------------------------------------------------------------
    // G. PF3 -> back to the sign-on program (WHEN DFHPF3 XCTL to COSGN00C).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("G: POST PF3 -> 200 + navigation to COSGN00C/CC00; selection service not called")
    void postPf3NavigatesBackToSignon() throws Exception {
        mockMvc.perform(post(MENU_PATH).with(user(USER_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MainMenuRequest(null, PfKeyAction.PF3))))
                .andExpect(status().isOk())
                .andExpect(header().string(NEXT_PROGRAM_HEADER, "COSGN00C"))
                .andExpect(header().string(NEXT_TRANSACTION_HEADER, "CC00"))
                .andExpect(jsonPath("$.menuOptions", hasSize(3)))
                .andExpect(jsonPath("$.errorMessage").doesNotExist());

        // PF3 is a pure navigation branch: option selection/routing is never consulted.
        verify(menuService, never()).selectMainMenuOption(anyString(), anyChar());
    }

    // ------------------------------------------------------------------
    // H. Any other attention key -> invalid-key message (WHEN OTHER).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("H: POST unmapped key (PF7) -> 200 + invalid-key message, menu rebuilt, no nav")
    void postUnmappedKeyReturnsInvalidKeyMessage() throws Exception {
        mockMvc.perform(post(MENU_PATH).with(user(USER_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MainMenuRequest(null, PfKeyAction.PF7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(INVALID_KEY_MESSAGE))
                .andExpect(jsonPath("$.menuOptions", hasSize(3)))
                .andExpect(header().doesNotExist(NEXT_PROGRAM_HEADER))
                .andExpect(header().doesNotExist(NEXT_TRANSACTION_HEADER));

        verify(menuService, never()).selectMainMenuOption(anyString(), anyChar());
    }

    // ------------------------------------------------------------------
    // I. Field-contract parity: a STRUCTURAL (over-width) option violates
    //    @Size/@Pattern and yields an RFC-7807 problem+json 400 that names the
    //    field without leaking the value (I1); a within-width non-numeric option
    //    is NOT a 400 -- it is handled same-screen at 200 by MenuService, matching
    //    COBOL COMEN01C's IS-NOT-NUMERIC redisplay (I2).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("I1: POST option longer than 2 chars -> 400 problem+json naming 'option', value not leaked")
    void postOptionTooLongReturnsProblemDetail() throws Exception {
        mockMvc.perform(post(MENU_PATH).with(user(USER_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"option\":\"999\",\"action\":\"ENTER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                // The offending field name is surfaced ...
                .andExpect(jsonPath("$.detail", containsString("option")))
                // ... but the rejected value is never echoed (could be a password/CVV on other screens).
                .andExpect(jsonPath("$.detail", not(containsString("999"))));

        verify(menuService, never()).selectMainMenuOption(anyString(), anyChar());
    }

    @Test
    @DisplayName("I2: POST within-width non-digit option \"ab\" -> 200 same-screen invalid-option message (COBOL COMEN01C IS-NOT-NUMERIC), service consulted")
    void postNonDigitOptionHandledSameScreen() throws Exception {
        // F-MENU-1. A two-character non-numeric option fits the field width, so it is NOT a
        // structural (400) violation: COBOL COMEN01C (L123-129) folds it, finds
        // WS-OPTION IS NOT NUMERIC, and re-displays the same screen with
        // "Please enter a valid option number..." at HTTP 200. The pre-fix all-digits @Pattern
        // wrongly rejected this at the 400 bean-validation boundary before the service ran.
        when(menuService.selectMainMenuOption(anyString(), eq('U')))
                .thenReturn(MenuService.MenuRouting.error(MenuService.INVALID_OPTION_MESSAGE));

        mockMvc.perform(post(MENU_PATH).with(user(USER_PRINCIPAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"option\":\"ab\",\"action\":\"ENTER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorMessage").value(MenuService.INVALID_OPTION_MESSAGE))
                .andExpect(jsonPath("$.menuOptions", hasSize(3)))
                .andExpect(header().doesNotExist(NEXT_PROGRAM_HEADER))
                .andExpect(header().doesNotExist(NEXT_TRANSACTION_HEADER));

        // The within-width value reaches the service verbatim (no controller-side pre-filtering).
        verify(menuService).selectMainMenuOption(eq("ab"), eq('U'));
    }
}
