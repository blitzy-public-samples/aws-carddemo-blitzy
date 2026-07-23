package com.aws.carddemo.web.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.dto.CardDemoContext;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Full-context integration test (Maven Failsafe, {@code verify} phase) for
 * {@link AdminMenuController}, the Spring MVC web-tier migration of the CICS pseudo-conversational
 * COBOL administrator main-menu program {@code COADM01C} (CICS transaction {@code CA00}, mapset
 * {@code COADM01}).
 *
 * <p><strong>COBOL oracle (traceability, AAP &sect;0.6.10):</strong> {@code legacy/cbl/COADM01C.cbl}
 * &mdash; program {@code COADM01C}, transaction {@code CA00}. The paragraph {@code MAIN-PARA}
 * {@code EVALUATE EIBAID} (ENTER &rarr; {@code PROCESS-ENTER-KEY}; PF3 &rarr; move
 * {@code 'COSGN00C'} then {@code RETURN-TO-SIGNON-SCREEN}; {@code WHEN OTHER} &rarr; invalid-key
 * message), {@code PROCESS-ENTER-KEY} option validation
 * ({@code "Please enter a valid option number..."} when the option is non-numeric, zero, or
 * greater than {@code CDEMO-ADMIN-OPT-COUNT}), and {@code BUILD-MENU-OPTIONS} (the four numbered
 * admin options) are the behavioural contract asserted here.</p>
 *
 * <p><strong>Scope &mdash; the {@code /admin/**} authorization contract.</strong> This is the
 * primary place the RACF &rarr; Spring Security migration for the administration surface is
 * proven: {@code config.SecurityConfig} restricts {@code /admin/**} to
 * {@code hasAuthority("ROLE_ADMIN")}, so a {@code ROLE_USER} principal is forbidden (403), a
 * {@code ROLE_ADMIN} principal is admitted (200), and an anonymous request is bounced to the
 * sign-on screen. The four option targets themselves live under {@code /admin/**} and are covered
 * by {@code UserAdminControllerIT}; this class asserts only the redirect target strings.</p>
 *
 * <p><strong>Infrastructure (no duplicated config).</strong> The class extends
 * {@link AbstractPostgresIntegrationTest} and adds only {@link AutoConfigureMockMvc}; it does not
 * re-declare {@code @SpringBootTest}, {@code @ActiveProfiles}, {@code @Testcontainers}, the
 * container, or the datasource wiring &mdash; all are inherited from the shared base, which starts
 * a single {@code postgres:18-alpine} Testcontainers instance, activates the {@code test} profile,
 * and resets the Flyway seed before each test. The exercised stack is real end-to-end: the real
 * {@code AdminMenuController}, {@code AdminMenuService}, {@code dto.menu.AdminMenuOptions}, and the
 * real {@code SecurityConfig} filter chain (no Mockito, no {@code @MockBean}). CSRF stays enabled,
 * so every mutating {@code POST} carries a token except the dedicated negative test.</p>
 *
 * <p>The admin menu is read-only (it performs no persistence mutation), so this class declares no
 * {@code @Transactional} boundary; committed-state isolation is provided by the base class's
 * per-test seed reset.</p>
 *
 * @see AdminMenuController
 * @see AbstractPostgresIntegrationTest
 */
@AutoConfigureMockMvc
class AdminMenuControllerIT extends AbstractPostgresIntegrationTest {

    /** Shared request path for the admin menu (GET displays, POST submits). */
    private static final String ADMIN_MENU_PATH = "/admin/menu";

    /** Logical Thymeleaf view name for the admin-menu screen (BMS map name {@code COADM01}). */
    private static final String VIEW_COADM01 = "COADM01";

    /** Model attribute under which the controller binds the {@code COADM01Form}. */
    private static final String MODEL_ATTR_FORM = "form";

    /** POST parameter carrying the numeric menu option (COBOL {@code OPTION PIC X(02)}). */
    private static final String PARAM_OPTION = "option";

    /** POST parameter carrying the activated PF-key token. */
    private static final String PARAM_PFKEY = "pfkey";

    /** Web PF-key token for the ENTER action (as submitted by {@code COADM01.html}). */
    private static final String KEY_ENTER = "ENTER";

    /** Web PF-key token for the PF3 (exit / back-to-sign-on) action. */
    private static final String KEY_PF3 = "PF3";

    /** Web PF-key token that is valid but unmapped by the controller (COBOL {@code WHEN OTHER}). */
    private static final String KEY_UNMAPPED = "PF9";

    /** Sign-on route the PF3 / anonymous paths resolve to (COBOL {@code COSGN00C}). */
    private static final String ROUTE_SIGNON = "/signon";

    /** Glob for the anonymous-redirect assertion; tolerant of the entry point's absolute URL. */
    private static final String SIGNON_URL_PATTERN = "**/signon";

    /** Scoped-proxy session attribute under which the session-scoped {@link CardDemoContext} lives. */
    private static final String CONTEXT_SESSION_ATTR = "scopedTarget.cardDemoContext";

    /** A non-sign-on hand-off target ({@code COADM01C}) left in {@code CDEMO-TO-PROGRAM} by a prior turn. */
    private static final String PGM_ADMIN_MENU = "COADM01C";

    /** Seeded administrator id ({@code SEC-USR-TYPE = 'A'}) from {@code V2__reference_data.sql}. */
    private static final String SEEDED_ADMIN_ID = "ADMIN001";

    /** An out-of-range option (menu has four options) exercising the invalid-option path. */
    private static final String OPTION_OUT_OF_RANGE = "9";

    /** Fragment of the COBOL invalid-option message ({@code "Please enter a valid option..."}). */
    private static final String MSG_FRAGMENT_VALID_OPTION = "valid option";

    /** Fragment of {@code Messages.CCDA_MSG_INVALID_KEY} ({@code "Invalid key pressed..."}). */
    private static final String MSG_FRAGMENT_INVALID_KEY = "Invalid key pressed";

    /**
     * The Spring-configured {@link MockMvc} for the full application context. Field injection is the
     * standard, warning-free choice for an integration test (set by the Spring TestContext
     * framework, never by user code), so no constructor is declared.
     */
    @Autowired
    private MockMvc mockMvc;

    // ---------------------------------------------------------------------------------------------
    // Phase 1 - Authorization contract: /admin/** is admin-only (the headline of this class).
    // ---------------------------------------------------------------------------------------------

    /**
     * A {@code ROLE_USER} principal lacks {@code ROLE_ADMIN}, so {@code GET /admin/menu} is
     * forbidden (403). This is the negative half of the RACF &rarr; Spring Security admin-surface
     * contract.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("GET /admin/menu is forbidden (403) for ROLE_USER")
    @WithMockUser(roles = "USER")
    void adminMenuForbiddenForRoleUser() throws Exception {
        mockMvc.perform(get(ADMIN_MENU_PATH))
                .andExpect(status().isForbidden());
    }

    /**
     * A {@code ROLE_ADMIN} principal is admitted: {@code GET /admin/menu} renders the {@code COADM01}
     * view. The four administration options built by the COBOL {@code BUILD-MENU-OPTIONS}
     * ({@code OPTN001}..{@code OPTN004}) are present on the bound form.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("GET /admin/menu is OK (200) for ROLE_ADMIN and renders the four options")
    @WithMockUser(roles = "ADMIN")
    void adminMenuOkForRoleAdmin() throws Exception {
        mockMvc.perform(get(ADMIN_MENU_PATH))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_COADM01))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("optn001", containsString("User List"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("optn002", containsString("User Add"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("optn003", containsString("User Update"))))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("optn004", containsString("User Delete"))));
    }

    /**
     * An unauthenticated (anonymous) request to a protected admin URL is redirected to the sign-on
     * screen by the {@code LoginUrlAuthenticationEntryPoint("/signon")}, reproducing the "sign on
     * first" behaviour. The pattern assertion tolerates the entry point's absolute redirect URL.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("GET /admin/menu redirects an anonymous user to the sign-on screen")
    void adminMenuRedirectsAnonymousToSignon() throws Exception {
        mockMvc.perform(get(ADMIN_MENU_PATH))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern(SIGNON_URL_PATTERN));
    }

    /**
     * A real, Flyway-seeded {@code USRSEC} administrator ({@code ADMIN001}, {@code SEC-USR-TYPE 'A'})
     * loaded through the genuine {@code CardDemoUserDetailsService} resolves to {@code ROLE_ADMIN}
     * and is admitted, strengthening the authorization parity assertion against a real database row.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("GET /admin/menu is OK (200) for the seeded admin user ADMIN001")
    @WithUserDetails(SEEDED_ADMIN_ID)
    void adminMenuOkForSeededAdminUser() throws Exception {
        mockMvc.perform(get(ADMIN_MENU_PATH))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_COADM01));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 2 - Option -> route dispatch (COADM01C PROCESS-ENTER-KEY -> XCTL).
    // ---------------------------------------------------------------------------------------------

    /**
     * Each valid option (1..4) selected with ENTER redirects to its user-administration route,
     * reproducing the COBOL {@code PROCESS-ENTER-KEY} routing to programs
     * {@code COUSR00C}/{@code COUSR01C}/{@code COUSR02C}/{@code COUSR03C} (the {@code XCTL}). Both
     * routes require {@code ROLE_ADMIN}; only the redirect target string is asserted here.
     *
     * @param option        the submitted menu option ({@code 1}..{@code 4})
     * @param expectedRoute the redirect target the option must resolve to
     * @throws Exception if the request cannot be performed
     */
    @ParameterizedTest(name = "option {0} redirects to {1}")
    @CsvSource({
        "1, /admin/users",
        "2, /admin/users/add",
        "3, /admin/users/update",
        "4, /admin/users/delete"
    })
    @DisplayName("POST /admin/menu with ENTER redirects each admin option to its route")
    @WithMockUser(roles = "ADMIN")
    void adminMenuOptionRedirectsToUserAdminRoute(String option, String expectedRoute)
            throws Exception {
        mockMvc.perform(post(ADMIN_MENU_PATH)
                        .param(PARAM_OPTION, option)
                        .param(PARAM_PFKEY, KEY_ENTER)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedRoute));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 3 - PF3 and invalid input (COADM01C RETURN-TO-SIGNON-SCREEN / WHEN OTHER).
    // ---------------------------------------------------------------------------------------------

    /**
     * PF3 returns control to the sign-on program {@code COSGN00C}, reproducing the COBOL
     * {@code RETURN-TO-SIGNON-SCREEN}; the controller redirects to {@code /signon}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /admin/menu with PF3 logs out and redirects to the sign-on screen (?logout)")
    @WithMockUser(roles = "ADMIN")
    void adminMenuPf3RedirectsToSignon() throws Exception {
        // Finding P5-01: PF3 Exit performs a real server-side logout and lands on the sign-on screen
        // with the ?logout marker (finding P5-11 renders the accessible notice).
        mockMvc.perform(post(ADMIN_MENU_PATH)
                        .param(PARAM_PFKEY, KEY_PF3)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ROUTE_SIGNON + "?logout"));
    }

    /**
     * PF3 must return to the sign-on screen ({@code /signon}) <em>even when the session's
     * {@code CDEMO-TO-PROGRAM} ({@link CardDemoContext#getToProgram()}) was already populated by a
     * prior turn</em> &mdash; the live pseudo-conversational state that {@link #adminMenuPf3RedirectsToSignon()}
     * (a brand-new session with a blank hand-off target) does not exercise.
     *
     * <p><strong>Regression for w045 Finding C (admin-menu PF3 self-loop).</strong> COBOL
     * {@code COADM01C} line 97 performs an <em>unconditional</em>
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} in the {@code WHEN DFHPF3} branch before
     * {@code PERFORM RETURN-TO-SIGNON-SCREEN}; {@code RETURN-TO-SIGNON-SCREEN} itself only defaults
     * the target when it is {@code LOW-VALUES OR SPACES} (lines 162-163, a defensive fallback).
     * Before the fix, the controller's PF3 branch called {@code returnToSignonScreen} without the
     * line-97 move, so a populated {@code CDEMO-TO-PROGRAM} (here {@code COADM01C}) survived and drove
     * {@code routeForProgram(...)} back to {@code /admin/menu} (the self-loop). Seeding the
     * session-scoped context with a non-sign-on hand-off target reproduces the defect and locks in
     * the fix.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /admin/menu with PF3 redirects to sign-on even when CDEMO-TO-PROGRAM is already set (w045 Finding C)")
    @WithMockUser(roles = "ADMIN")
    void adminMenuPf3RedirectsToSignonWhenToProgramAlreadyPopulated() throws Exception {
        // Reproduce the live state: a prior turn left CDEMO-TO-PROGRAM = 'COADM01C' (non-blank,
        // non-sign-on). markInitialized()+markReenter() model an established pseudo-conversation.
        CardDemoContext context = new CardDemoContext();
        context.markInitialized();
        context.markReenter();
        context.setToProgram(PGM_ADMIN_MENU);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CONTEXT_SESSION_ATTR, context);

        mockMvc.perform(post(ADMIN_MENU_PATH)
                        .session(session)
                        .param(PARAM_PFKEY, KEY_PF3)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(ROUTE_SIGNON + "?logout"));
    }

    /**
     * An out-of-range option with ENTER re-renders the menu ({@code COADM01}) with the COBOL
     * invalid-option message ({@code "Please enter a valid option number..."}), reproducing
     * {@code PROCESS-ENTER-KEY}'s {@code WS-OPTION > CDEMO-ADMIN-OPT-COUNT} branch.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /admin/menu with an invalid option re-renders with the invalid-option message")
    @WithMockUser(roles = "ADMIN")
    void adminMenuInvalidOptionRerenders() throws Exception {
        mockMvc.perform(post(ADMIN_MENU_PATH)
                        .param(PARAM_OPTION, OPTION_OUT_OF_RANGE)
                        .param(PARAM_PFKEY, KEY_ENTER)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_COADM01))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("errmsg", containsString(MSG_FRAGMENT_VALID_OPTION))))
                // Finding #11: the invalid-option line is a COBOL error, so it renders in the BMS
                // default red (ERRMSGC never overridden to DFHGREEN/DFHNEUTR on this branch).
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("errmsgColor", is("red"))));
    }

    /**
     * An unmapped PF-key re-renders the menu ({@code COADM01}) with
     * {@code Messages.CCDA_MSG_INVALID_KEY} ({@code "Invalid key pressed..."}), reproducing the
     * COBOL {@code EVALUATE EIBAID ... WHEN OTHER} branch.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /admin/menu with an unmapped key re-renders with the invalid-key message")
    @WithMockUser(roles = "ADMIN")
    void adminMenuInvalidKeyRerenders() throws Exception {
        mockMvc.perform(post(ADMIN_MENU_PATH)
                        .param(PARAM_PFKEY, KEY_UNMAPPED)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_COADM01))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("errmsg", containsString(MSG_FRAGMENT_INVALID_KEY))))
                // Finding #11: the invalid-key line is a COBOL error, so it renders in the BMS
                // default red.
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty("errmsgColor", is("red"))));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 4 - CSRF negative: a state-changing POST without a token is rejected.
    // ---------------------------------------------------------------------------------------------

    /**
     * A {@code POST /admin/menu} without a CSRF token is forbidden (403) even for a
     * {@code ROLE_ADMIN} principal. The admin role isolates the failure cause to the missing CSRF
     * token (a {@code ROLE_USER} would be forbidden for the separate authorization reason).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /admin/menu without a CSRF token is forbidden (403) for ROLE_ADMIN")
    @WithMockUser(roles = "ADMIN")
    void adminMenuPostWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post(ADMIN_MENU_PATH)
                        .param(PARAM_OPTION, "1"))
                .andExpect(status().isForbidden());
    }
}
