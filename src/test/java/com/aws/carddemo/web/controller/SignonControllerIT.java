package com.aws.carddemo.web.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.aws.carddemo.AbstractPostgresIntegrationTest;
import com.aws.carddemo.TestCredentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Full-context controller integration test for {@link SignonController}, the Spring MVC
 * replacement for the CICS pseudo-conversational sign-on program.
 *
 * <p><strong>COBOL oracle (traceability, AAP &sect;0.6.10):</strong> {@code legacy/cbl/COSGN00C.cbl}
 * &mdash; program {@code COSGN00C}, CICS transaction id {@code CC00} (verified in
 * {@code legacy/csd/CARDDEMO.CSD}: {@code DEFINE TRANSACTION(CC00) ... PROGRAM(COSGN00C)}). Each test
 * pins an observable of the migrated screen to the behaviour of the original program's paragraphs:
 * {@code MAIN-PARA} (first-entry display and the {@code EVALUATE EIBAID} dispatch of ENTER / PF3 /
 * other), {@code PROCESS-ENTER-KEY} (the blank User ID / blank password edits), and
 * {@code READ-USER-SEC-FILE} (the successful {@code XCTL} to {@code COADM01C} / {@code COMEN01C} plus
 * the "Wrong Password" and "User not found" failure lines).</p>
 *
 * <p><strong>What this test proves.</strong> It exercises the production controller through the
 * <em>whole</em> application context &mdash; the real {@code SignonService}, the real
 * {@code CardDemoUserDetailsService} + {@code CardDemoAuthenticationProvider}, the real
 * {@code config.SecurityConfig} filter chain (CSRF enabled, {@code /} and {@code /signon}
 * {@code permitAll}), the real Spring Data repositories, and a real Testcontainers PostgreSQL seeded
 * by the Flyway migrations ({@code V1__schema.sql} &rarr; {@code V2__reference_data.sql} &rarr;
 * {@code V3__indexes.sql}). There are no Mockito mocks: authentication is performed against the
 * genuine, Flyway-seeded {@code user_security} rows, so the assertions are true end-to-end parity
 * checks (AAP &sect;0.2.1) that also contribute to the &ge;80% line-coverage gate (AAP &sect;0.7.1).</p>
 *
 * <p><strong>Server-rendered, never JSON.</strong> {@code SignonController} is a {@link
 * org.springframework.stereotype.Controller} that returns logical Thymeleaf view names where the view
 * name equals the BMS map name {@code COSGN00}; it is never a {@code @RestController}. Consequently
 * these tests assert HTTP status, {@code view().name("COSGN00")}, {@code redirectedUrl(...)} and the
 * error line carried on the {@code form} model attribute &mdash; never {@code jsonPath}. The sign-on
 * error line is surfaced by the controller through {@code COSGN00Form.setErrmsg(...)}, so it is checked
 * as the {@code errmsg} property of the {@code form} model attribute using a {@code containsString}
 * substring match (the verbatim COBOL literals are {@code PIC X} space-padded and therefore brittle to
 * assert in full).</p>
 *
 * <p><strong>Request encodings (confirmed against the materialised controller source).</strong> The
 * {@code COSGN00} form binds the two entry fields as {@code userid} and {@code passwd}
 * ({@code COSGN00Form}), submits the pressed key in the {@code pfkey} request parameter using the web
 * tokens {@code ENTER} (the default when absent) and {@code PFnn} (for example {@code PF3}), and binds
 * the form under the default model attribute name {@code form}. CSRF protection stays enabled, so every
 * state-changing {@code POST} carries a token via {@code with(csrf())}; a single dedicated negative
 * test omits the token to prove the protection is active.</p>
 *
 * <p><strong>Infrastructure (no duplicated config).</strong> This class extends
 * {@link AbstractPostgresIntegrationTest} and adds <em>only</em> {@link AutoConfigureMockMvc}; it does
 * not re-declare {@code @SpringBootTest}, {@code @ActiveProfiles}, any container, or datasource
 * configuration. The shared base starts the single {@code postgres:18-alpine} container, activates the
 * {@code test} profile, binds the container's JDBC coordinates, and resets the database to the pristine
 * Flyway seed before every test. Sign-on is a read-only {@code USRSEC} lookup, so no test mutates the
 * database and no cleanup beyond that shared reset is required.</p>
 *
 * <p><strong>Runtime prerequisite.</strong> Execution requires a Testcontainers-capable environment (a
 * reachable Docker daemon able to start {@code postgres:18-alpine}); this is the documented local
 * validation constraint for the migration. The class name ends in {@code IT} so it runs under the Maven
 * Failsafe plugin (the {@code integration-test} / {@code verify} phase), disjoint from the {@code *Test}
 * unit suite run by Surefire.</p>
 *
 * @see SignonController
 * @see AbstractPostgresIntegrationTest
 */
@AutoConfigureMockMvc
class SignonControllerIT extends AbstractPostgresIntegrationTest {

    /** Logical Thymeleaf view name for the sign-on screen; equals the BMS map name {@code COSGN00}. */
    private static final String VIEW_SIGNON = "COSGN00";

    /** Default model attribute name Spring binds the {@code COSGN00Form} under (confirmed in source). */
    private static final String MODEL_ATTR_FORM = "form";

    /** Error-line property on {@code COSGN00Form} the controller sets via {@code setErrmsg(...)}. */
    private static final String FORM_PROP_ERRMSG = "errmsg";

    /** Sign-on field parameter for the entered User ID ({@code COSGN00Form.userid}). */
    private static final String PARAM_USERID = "userid";

    /** Sign-on field parameter for the entered password ({@code COSGN00Form.passwd}). */
    private static final String PARAM_PASSWD = "passwd";

    /** Request parameter carrying the pressed PF-key web token ({@code ENTER} / {@code PFnn}). */
    private static final String PARAM_PFKEY = "pfkey";

    /** A seeded administrator id ({@code SEC-USR-TYPE = 'A'} &rarr; {@code ROLE_ADMIN}). */
    private static final String ADMIN_USER_ID = "ADMIN001";

    /** A seeded standard-user id ({@code SEC-USR-TYPE = 'U'} &rarr; {@code ROLE_USER}). */
    private static final String STANDARD_USER_ID = "USER0001";

    /** The cleartext password shared by every seeded user ({@code SEC-USR-PWD}), for parity. */
    // Review finding #5: the seed password is externalized (CARDDEMO_SEED_PASSWORD env var,
    // no committed default) and read here to authenticate against the real Flyway-seeded rows.
    private static final String SEEDED_PASSWORD = TestCredentials.seedPassword();

    /**
     * MockMvc bound to the full web application context by {@link AutoConfigureMockMvc}, exercising the
     * real filter chain (including CSRF) and the real {@code SignonController}.
     */
    @Autowired
    private MockMvc mockMvc;

    // --- Phase 1: GET routes & permitAll (COSGN00C MAIN-PARA first entry) ----

    /**
     * {@code GET /} routes an anonymous operator to the sign-on screen, reproducing the CICS entry
     * transaction {@code CC00}: a fresh terminal starts at {@code COSGN00C}. The root is
     * {@code permitAll}, so no authentication is required and the controller returns a redirect to
     * {@code /signon}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("GET / redirects an anonymous user to the sign-on screen (CICS entry tran CC00)")
    void rootRedirectsToSignon() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/signon"));
    }

    /**
     * {@code GET /signon} is permitted for an anonymous user and renders the sign-on map, reproducing
     * the COBOL first-entry {@code SEND-SIGNON-SCREEN} path ({@code MAIN-PARA}, {@code EIBCALEN = 0}).
     * Proves {@code /signon} is {@code permitAll} and that the sign-on form is bound under the
     * {@code form} model attribute for the {@code COSGN00} template.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("GET /signon is permitted for an anonymous user and renders the COSGN00 map")
    void signonScreenPermittedForAnonymous() throws Exception {
        mockMvc.perform(get("/signon"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SIGNON))
                .andExpect(model().attributeExists(MODEL_ATTR_FORM));
    }

    // --- Phase 2: POST sign-on success -> role-based redirect ---------------
    //     (COSGN00C PROCESS-ENTER-KEY + READ-USER-SEC-FILE + XCTL)

    /**
     * A successful sign-on with seeded administrator credentials redirects to the admin menu,
     * reproducing the COBOL {@code READ-USER-SEC-FILE} success branch for {@code SEC-USR-TYPE = 'A'}
     * ({@code XCTL PROGRAM('COADM01C')}, tran {@code CA00}). The request is anonymous with a CSRF token;
     * the real {@code CardDemoAuthenticationProvider} authenticates the cleartext seeded password.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /signon with admin credentials redirects to /admin/menu (XCTL COADM01C)")
    void postSignonAdminRedirectsToAdminMenu() throws Exception {
        mockMvc.perform(post("/signon")
                        .param(PARAM_USERID, ADMIN_USER_ID)
                        .param(PARAM_PASSWD, SEEDED_PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/menu"));
    }

    /**
     * A successful sign-on with seeded standard-user credentials redirects to the main menu,
     * reproducing the COBOL {@code READ-USER-SEC-FILE} {@code ELSE} branch for {@code SEC-USR-TYPE = 'U'}
     * ({@code XCTL PROGRAM('COMEN01C')}, tran {@code CM00}).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /signon with user credentials redirects to /menu (XCTL COMEN01C)")
    void postSignonUserRedirectsToMenu() throws Exception {
        mockMvc.perform(post("/signon")
                        .param(PARAM_USERID, STANDARD_USER_ID)
                        .param(PARAM_PASSWD, SEEDED_PASSWORD)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/menu"));
    }

    /**
     * Review finding #6 (session fixation, CWE-384): a successful sign-on must rotate the
     * {@code HttpSession} id so a pre-authentication session id cannot be reused to ride the now
     * authenticated session, and the authenticated identity must survive the rotation. The request is
     * made with a pre-existing session; afterwards the session id must differ from the id it had before
     * sign-on, and the persisted Spring Security context must be present on the (rotated) session so
     * the identity carries to the post-sign-on redirect. This proves the controller-managed sign-on
     * invokes the {@code SessionAuthenticationStrategy} (change-session-id + register) that a
     * filter-managed login would otherwise have applied.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /signon rotates the session id (fixation protection) and preserves the identity")
    void postSignonRotatesSessionIdAndPreservesIdentity() throws Exception {
        MockHttpSession preAuthSession = new MockHttpSession();
        String preAuthId = preAuthSession.getId();

        MvcResult result = mockMvc.perform(post("/signon")
                        .param(PARAM_USERID, STANDARD_USER_ID)
                        .param(PARAM_PASSWD, SEEDED_PASSWORD)
                        .session(preAuthSession)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/menu"))
                .andReturn();

        MockHttpSession postAuthSession = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(postAuthSession)
                .as("a session must still exist after sign-on")
                .isNotNull();
        assertThat(postAuthSession.getId())
                .as("session id must rotate on sign-on (fixation protection, finding #6)")
                .isNotEqualTo(preAuthId);
        assertThat(postAuthSession.getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .as("authenticated SecurityContext must be persisted on the rotated session")
                .isNotNull();
    }

    // --- Phase 3: POST validation & error paths -----------------------------
    //     (verbatim COBOL messages, asserted by substring)

    /**
     * A blank User ID re-renders the sign-on screen with the "Please enter User ID ..." message,
     * reproducing the COBOL {@code PROCESS-ENTER-KEY} first edit
     * ({@code WHEN USERIDI = SPACES OR LOW-VALUES}). The edit runs before authentication, so no
     * {@code USRSEC} read occurs; the screen is re-displayed at HTTP 200 with the error line on the
     * {@code form} model attribute.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /signon with a blank User ID re-renders COSGN00 with the enter-User-ID message")
    void postSignonBlankUserIdRerenders() throws Exception {
        mockMvc.perform(post("/signon")
                        .param(PARAM_USERID, "")
                        .param(PARAM_PASSWD, SEEDED_PASSWORD)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SIGNON))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROP_ERRMSG, containsString("Please enter User ID"))));
    }

    /**
     * A blank password (with a non-blank User ID) re-renders the sign-on screen with the
     * "Please enter Password ..." message, reproducing the COBOL {@code PROCESS-ENTER-KEY} second edit
     * ({@code WHEN PASSWDI = SPACES OR LOW-VALUES}). The edit precedes authentication, so a valid User
     * ID still yields the blank-password error rather than a credential check.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /signon with a blank password re-renders COSGN00 with the enter-Password message")
    void postSignonBlankPasswordRerenders() throws Exception {
        mockMvc.perform(post("/signon")
                        .param(PARAM_USERID, ADMIN_USER_ID)
                        .param(PARAM_PASSWD, "")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SIGNON))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROP_ERRMSG, containsString("Please enter Password"))));
    }

    /**
     * A known user with the wrong password re-renders the sign-on screen with the
     * "Wrong Password. Try again ..." message, reproducing the COBOL {@code READ-USER-SEC-FILE}
     * password-mismatch branch ({@code WHEN 0} with {@code SEC-USR-PWD} not equal to the entered
     * password). The real {@code CardDemoAuthenticationProvider} raises {@code BadCredentialsException},
     * which the sign-on path maps to the wrong-password line and re-displays at HTTP 200.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /signon with a wrong password re-renders COSGN00 with the wrong-password message")
    void postSignonWrongPasswordRerenders() throws Exception {
        mockMvc.perform(post("/signon")
                        .param(PARAM_USERID, ADMIN_USER_ID)
                        .param(PARAM_PASSWD, "WRONGPWD")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SIGNON))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROP_ERRMSG, containsString("Wrong Password"))));
    }

    /**
     * An unknown User ID re-renders the sign-on screen with the "User not found. Try again ..."
     * message, reproducing the COBOL {@code READ-USER-SEC-FILE} {@code WHEN 13} branch. The real
     * {@code CardDemoUserDetailsService} raises {@code UsernameNotFoundException}, which the provider
     * lets propagate unchanged (deliberately distinct from the wrong-password branch) so the sign-on
     * path renders the user-not-found line at HTTP 200.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /signon with an unknown user re-renders COSGN00 with the user-not-found message")
    void postSignonUnknownUserRerenders() throws Exception {
        mockMvc.perform(post("/signon")
                        .param(PARAM_USERID, "NOSUCH99")
                        .param(PARAM_PASSWD, SEEDED_PASSWORD)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SIGNON))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROP_ERRMSG, containsString("not found"))));
    }

    // --- Phase 4: PF keys (COSGN00C honours ENTER + PFK03 only) -------------

    /**
     * PF3 ends the sign-on conversation with the thank-you message, reproducing the COBOL
     * {@code MAIN-PARA} {@code WHEN DFHPF3} branch ({@code MOVE CCDA-MSG-THANK-YOU},
     * {@code SEND-PLAIN-TEXT}, {@code EXEC CICS RETURN}). The controller clears the session context and
     * re-renders {@code COSGN00} at HTTP 200 carrying the informational thank-you line; the web token
     * {@code PF3} is resolved to the CICS AID {@code DFHPF3}.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /signon with PF3 re-renders COSGN00 with the thank-you exit message")
    void postSignonPf3ShowsThankYou() throws Exception {
        mockMvc.perform(post("/signon")
                        .param(PARAM_PFKEY, "PF3")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SIGNON))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROP_ERRMSG, containsString("Thank you for using"))));
    }

    /**
     * An unmapped attention key re-renders the sign-on screen with the "Invalid key pressed ..."
     * message, reproducing the COBOL {@code MAIN-PARA} {@code WHEN OTHER} branch
     * ({@code MOVE 'Y' TO WS-ERR-FLG}, {@code MOVE CCDA-MSG-INVALID-KEY}, {@code SEND-SIGNON-SCREEN}).
     * {@code PF9} is a valid 3270 key that sign-on does not honour, so it collapses to the invalid-key
     * path (the controller honours only ENTER and PF3).
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /signon with an unmapped key re-renders COSGN00 with the invalid-key message")
    void postSignonInvalidKeyRerenders() throws Exception {
        mockMvc.perform(post("/signon")
                        .param(PARAM_PFKEY, "PF9")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SIGNON))
                .andExpect(model().attribute(MODEL_ATTR_FORM,
                        hasProperty(FORM_PROP_ERRMSG, containsString("Invalid key pressed"))));
    }

    // --- Phase 5: CSRF negative test (proves CSRF is enabled) ---------------

    /**
     * A sign-on {@code POST} without a CSRF token is rejected with HTTP 403, proving that CSRF
     * protection is enabled in {@code config.SecurityConfig} ({@code http.csrf(withDefaults())}). The
     * request is otherwise identical to the successful admin sign-on; only the missing token differs,
     * so the {@code CsrfFilter} denies it before the controller is reached.
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /signon without a CSRF token is forbidden (proves CSRF is enabled)")
    void postSignonWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/signon")
                        .param(PARAM_USERID, ADMIN_USER_ID)
                        .param(PARAM_PASSWD, SEEDED_PASSWORD))
                .andExpect(status().isForbidden());
    }
}
