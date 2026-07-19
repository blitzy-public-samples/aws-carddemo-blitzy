package com.aws.carddemo.config;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.aws.carddemo.AbstractPostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Failsafe integration test that proves the production {@link SecurityConfig} reproduces the legacy
 * z/OS authorization contract under Spring Security: RACF dataset/transaction protection plus the
 * application-level COBOL signon and CICS transaction-to-program routing become URL/method
 * authorization rules, an {@link AuthenticationManager}, and CSRF enforcement (AAP &sect;0.6.7).
 *
 * <p>This is the <em>single</em> test class of the {@code config} test package. It intentionally
 * covers only the security-authorization behavior of {@link SecurityConfig}, because that is the one
 * part of the {@code config} package that carries behavioral parity for RACF/transaction security.
 * The other configuration classes ({@code DataSourceConfig}, {@code BatchConfig},
 * {@code ObservabilityConfig}, {@code WebConfig}) are exercised transitively by the application
 * smoke test and the other {@code *IT} classes and are not re-tested here.</p>
 *
 * <p><strong>Infrastructure (no duplicated config).</strong> The class only extends
 * {@link AbstractPostgresIntegrationTest} and adds {@link AutoConfigureMockMvc}. The shared base
 * starts a single {@code postgres:18-alpine} Testcontainer, activates the {@code test} profile, binds
 * the container's JDBC coordinates onto the Spring datasource, applies the Flyway migrations in
 * version order ({@code V0} &rarr; {@code V1} &rarr; {@code V2} &rarr; {@code V3} &rarr; {@code V4}),
 * runs Hibernate {@code ddl-auto=validate}, and resets the database to the pristine seed before each
 * test. No {@code @SpringBootTest}, {@code @ActiveProfiles}, container field, or datasource wiring is
 * re-declared here. Execution requires a reachable Docker daemon (the documented local-validation
 * constraint); the class name ends in {@code IT} so it runs under the Maven Failsafe plugin.</p>
 *
 * <p><strong>Design principle.</strong> This is an authorization test, not a controller-navigation
 * test. For routes that must be <em>allowed</em>, it asserts the request was not blocked by security
 * (status is neither {@code 401} nor {@code 403}) rather than a specific {@code 200}, because a real
 * controller may render a Thymeleaf view (200) or issue a pseudo-conversational redirect (3xx) when a
 * fresh session holds no {@code CardDemoContext} selection. For routes that must be <em>denied</em>,
 * it asserts the exact blocking status. This decoupling keeps the test focused on the
 * {@link SecurityConfig} rules and robust against unrelated controller behavior.</p>
 *
 * <p><strong>COBOL / CICS oracles (traceability, AAP &sect;0.6.7 / &sect;0.6.10).</strong></p>
 * <ul>
 *   <li>Origin: {@code legacy/cbl/COSGN00C.cbl} lines 205-245 &mdash; paragraph
 *       {@code READ-USER-SEC-FILE}: the {@code USRSEC} read
 *       ({@code EXEC CICS READ DATASET(WS-USRSEC-FILE) RIDFLD(WS-USER-ID)}), the cleartext compare
 *       {@code IF SEC-USR-PWD = WS-USER-PWD}, the user-type routing
 *       ({@code IF CDEMO-USRTYP-ADMIN} &rarr; {@code XCTL COADM01C}, else {@code XCTL COMEN01C}),
 *       and the {@code WHEN 13} (NOTFND) "User not found. Try again ..." path (versus the wrong-password
 *       branch). Lines 132-136 uppercase BOTH the entered user id and the entered password
 *       ({@code MOVE FUNCTION UPPER-CASE(USERIDI ...) / UPPER-CASE(PASSWDI ...)}) before the read and
 *       compare &mdash; the parity source for the case-insensitive sign-on proven by
 *       {@link #lowercaseCredentialsAreUppercasedForParity()}.</li>
 *   <li>Origin: {@code legacy/csd/CARDDEMO.CSD} &mdash; the 18 CICS transaction/program definitions.
 *       The admin transactions {@code CA00} (&rarr; {@code COADM01C}) and {@code CU00}-{@code CU03}
 *       (&rarr; {@code COUSR0[0-3]C}) map to {@code /admin/**} = {@code ROLE_ADMIN}; the
 *       {@code CDV1} &rarr; {@code COCRDSEC} card-detail security variant (which has no {@code .cbl}
 *       program) is realized purely as {@code /card/detail} URL authorization, verified by
 *       {@link #standardRoutesAllowedForUser()}.</li>
 * </ul>
 *
 * <p><strong>Password parity (AAP &sect;0.6.7).</strong> The cleartext password comparison of the
 * COBOL signon is preserved for functional parity, so {@link SecurityConfig} configures no
 * {@code PasswordEncoder}; that contract is proven behaviorally by the {@link AuthenticationManager}
 * tests below rather than by asserting the (fragile) absence of a bean. There are no hardcoded
 * credentials: the users come from the Flyway seed and the datasource credentials are supplied
 * dynamically by Testcontainers. Introducing password hashing is a documented suggested next task
 * (see {@code docs/decision-log.md}).</p>
 *
 * @see SecurityConfig
 * @see AbstractPostgresIntegrationTest
 */
@AutoConfigureMockMvc
class SecurityConfigIT extends AbstractPostgresIntegrationTest {

    /** Seeded administrator id ({@code SEC-USR-TYPE = 'A'} &rarr; {@code ROLE_ADMIN}). */
    private static final String SEED_ADMIN_ID = "ADMIN001";

    /** Seeded standard-user id ({@code SEC-USR-TYPE = 'U'} &rarr; {@code ROLE_USER}). */
    private static final String SEED_USER_ID = "USER0001";

    /** Cleartext password shared by every seeded {@code user_security} row ({@code SEC-USR-PWD}). */
    private static final String SEED_PASSWORD = "PASSWORD";

    /** Granted authority for the admin user type (COBOL {@code SEC-USR-TYPE = 'A'}). */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** Granted authority for the standard user type (COBOL {@code SEC-USR-TYPE = 'U'}). */
    private static final String ROLE_USER = "ROLE_USER";

    /** Admin-only routes ({@code /admin/**} = {@code ROLE_ADMIN}; trans {@code CA00} / {@code CU00}). */
    private static final List<String> ADMIN_ROUTES = List.of("/admin/menu", "/admin/users");

    /**
     * Authenticated-but-non-admin routes gated by the terminal {@code anyRequest().authenticated()}
     * rule. {@code /card/detail} realizes the legacy {@code CDV1}/{@code COCRDSEC} security variant.
     */
    private static final List<String> STANDARD_ROUTES =
            List.of("/account/view", "/card/detail", "/transaction/list", "/billpay", "/report");

    /** HTTP status for an unauthenticated request blocked by the entry point. */
    private static final int SC_UNAUTHORIZED = 401;

    /** HTTP status for an authenticated-but-unauthorized request. */
    private static final int SC_FORBIDDEN = 403;

    /** HTTP status for a successful response. */
    private static final int SC_OK = 200;

    /** MockMvc bound to the full Spring Security filter chain built by {@link SecurityConfig}. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * The application {@link AuthenticationManager} bean ({@code ProviderManager} wrapping the custom
     * {@code CardDemoAuthenticationProvider}) exposed by {@link SecurityConfig}. Authenticating through
     * it exercises the real provider &rarr; user-details-service &rarr; seeded {@code user_security}
     * path end-to-end.
     */
    @Autowired
    private AuthenticationManager authenticationManager;

    // ---------------------------------------------------------------------------------------------
    // Phase B - URL authorization matrix (MockMvc)
    // ---------------------------------------------------------------------------------------------

    /**
     * The public landing pages are reachable without authentication. {@code GET /signon} is the
     * sign-on screen (legacy tran {@code CC00} &rarr; {@code COSGN00C}) and returns {@code 200};
     * {@code GET /} is {@code permitAll} and must not be blocked by security (it redirects to
     * {@code /signon}). The invariant for the root is simply status &notin; {401, 403}.
     *
     * @throws Exception if the MockMvc request cannot be performed
     */
    @Test
    void anonymousReachesSignon() throws Exception {
        mockMvc.perform(get("/signon")).andExpect(status().isOk());

        int rootStatus = mockMvc.perform(get("/")).andReturn().getResponse().getStatus();
        assertThat(rootStatus)
                .as("GET / (permitAll) must not be blocked by security, was %s", rootStatus)
                .isNotIn(SC_UNAUTHORIZED, SC_FORBIDDEN);
    }

    /**
     * An unauthenticated request to a protected business route is redirected to the sign-on screen.
     * {@code anyRequest().authenticated()} plus the {@code LoginUrlAuthenticationEntryPoint("/signon")}
     * turns anonymous access to {@code /menu} into a 3xx redirect to {@code /signon}, reproducing the
     * COBOL signon gate (the {@code EIBCALEN = 0} bounce to {@code COSGN00C}).
     *
     * @throws Exception if the MockMvc request cannot be performed
     */
    @Test
    void anonymousIsRedirectedFromMenu() throws Exception {
        mockMvc.perform(get("/menu"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/signon"));
    }

    /**
     * A {@code ROLE_USER} principal is authenticated but not authorized for {@code /admin/**}: every
     * admin route returns {@code 403}. These routes are the legacy admin transactions {@code CA00}
     * (admin menu) and {@code CU00} (user list). {@code @WithMockUser(roles = "USER")} grants the
     * authority {@code ROLE_USER} (the {@code ROLE_} prefix is added automatically).
     *
     * @throws Exception if a MockMvc request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void adminRoutesForbiddenForUser() throws Exception {
        for (String url : ADMIN_ROUTES) {
            int sc = mockMvc.perform(get(url)).andReturn().getResponse().getStatus();
            assertThat(sc)
                    .as("ROLE_USER must be forbidden from admin route %s", url)
                    .isEqualTo(SC_FORBIDDEN);
        }
    }

    /**
     * A {@code ROLE_ADMIN} principal is authorized for {@code /admin/**}: every admin route is not
     * blocked (status &notin; {401, 403}). {@code @WithMockUser(roles = "ADMIN")} grants the authority
     * {@code ROLE_ADMIN}, matching {@code hasAuthority("ROLE_ADMIN")} in {@link SecurityConfig}.
     *
     * @throws Exception if a MockMvc request cannot be performed
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRoutesAllowedForAdmin() throws Exception {
        for (String url : ADMIN_ROUTES) {
            int sc = mockMvc.perform(get(url)).andReturn().getResponse().getStatus();
            assertThat(sc)
                    .as("ADMIN should reach %s", url)
                    .isNotIn(SC_UNAUTHORIZED, SC_FORBIDDEN);
        }
    }

    /**
     * A {@code ROLE_USER} principal is authorized for every non-admin business route (status &notin;
     * {401, 403}), as dictated by the terminal {@code anyRequest().authenticated()} rule. This
     * critically includes {@code /card/detail}, which realizes the legacy {@code CDV1}/{@code COCRDSEC}
     * card-detail security variant (no standalone {@code .cbl} program) purely through Spring Security
     * URL authorization.
     *
     * @throws Exception if a MockMvc request cannot be performed
     */
    @Test
    @WithMockUser(roles = "USER")
    void standardRoutesAllowedForUser() throws Exception {
        for (String url : STANDARD_ROUTES) {
            int sc = mockMvc.perform(get(url)).andReturn().getResponse().getStatus();
            assertThat(sc)
                    .as("USER should reach %s", url)
                    .isNotIn(SC_UNAUTHORIZED, SC_FORBIDDEN);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Phase C - Actuator endpoint authorization
    // ---------------------------------------------------------------------------------------------

    /**
     * The health endpoint is public. {@link SecurityConfig} permits {@code /actuator/health/**} and
     * the base configuration exposes {@code health}; with the PostgreSQL container UP, anonymous
     * {@code GET /actuator/health} resolves to {@code 200} (details stay hidden for an unauthenticated
     * caller, but the aggregate status is still returned).
     *
     * @throws Exception if the MockMvc request cannot be performed
     */
    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    /**
     * A non-permitted actuator endpoint is protected. {@code /actuator/env} is not in the
     * {@code permitAll} set, so {@code anyRequest().authenticated()} blocks anonymous access through
     * the login-url entry point: the response is a 3xx redirect to {@code /signon} (a {@code 401} is
     * also tolerated), and never {@code 200}.
     *
     * @throws Exception if the MockMvc request cannot be performed
     */
    @Test
    void actuatorEnvIsProtected() throws Exception {
        int sc = mockMvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus();
        assertThat(sc == SC_UNAUTHORIZED || (sc >= 300 && sc < 400))
                .as("anonymous /actuator/env must be blocked (401 or 3xx redirect), was %s", sc)
                .isTrue();
        assertThat(sc)
                .as("anonymous /actuator/env must not be 200 OK")
                .isNotEqualTo(SC_OK);
    }

    // ---------------------------------------------------------------------------------------------
    // Phase D - CSRF enforcement (CSRF is enabled via Customizer.withDefaults())
    // ---------------------------------------------------------------------------------------------

    /**
     * A state-changing POST without a CSRF token is rejected with {@code 403}. Because {@code /signon}
     * is {@code permitAll} for authorization, the only reason for the {@code 403} is the missing CSRF
     * token, cleanly isolating CSRF enforcement.
     *
     * @throws Exception if the MockMvc request cannot be performed
     */
    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/signon")).andExpect(status().isForbidden());
    }

    /**
     * The same POST with a valid CSRF token passes CSRF enforcement and reaches
     * {@code SignonController} (which re-renders the screen with {@code 200} or issues a redirect), so
     * the status is never {@code 403}.
     *
     * @throws Exception if the MockMvc request cannot be performed
     */
    @Test
    void postWithCsrfTokenProceeds() throws Exception {
        int sc = mockMvc.perform(post("/signon").with(csrf())).andReturn().getResponse().getStatus();
        assertThat(sc)
                .as("POST /signon with a CSRF token must pass CSRF (status != 403), was %s", sc)
                .isNotEqualTo(SC_FORBIDDEN);
    }

    // ---------------------------------------------------------------------------------------------
    // Phase E - AuthenticationManager integration against the Flyway-seeded database
    // ---------------------------------------------------------------------------------------------

    /**
     * A seeded administrator authenticates through the real {@link AuthenticationManager} and is
     * granted {@code ROLE_ADMIN}. Parity: {@code COSGN00C} sets {@code CDEMO-USER-TYPE} from
     * {@code SEC-USR-TYPE} and, when {@code CDEMO-USRTYP-ADMIN}, {@code XCTL}s to the admin menu
     * {@code COADM01C} &mdash; i.e. type {@code 'A'} &rarr; {@code ROLE_ADMIN}.
     */
    @Test
    void authenticationManagerAuthenticatesAdmin() {
        Authentication result = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(SEED_ADMIN_ID, SEED_PASSWORD));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .contains(ROLE_ADMIN);
    }

    /**
     * A seeded standard user authenticates through the real {@link AuthenticationManager} and is
     * granted {@code ROLE_USER}. Parity: a non-admin type routes to the main menu {@code COMEN01C},
     * i.e. type {@code 'U'} &rarr; {@code ROLE_USER}.
     */
    @Test
    void authenticationManagerAuthenticatesUser() {
        Authentication result = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(SEED_USER_ID, SEED_PASSWORD));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .contains(ROLE_USER);
    }

    /**
     * A wrong password for a known user raises {@link BadCredentialsException}. Parity:
     * {@code COSGN00C} L241-245, the {@code SEC-USR-PWD = WS-USER-PWD} mismatch drives the
     * "Wrong Password. Try again ..." branch.
     */
    @Test
    void wrongPasswordThrowsBadCredentials() {
        assertThatThrownBy(() -> authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(SEED_ADMIN_ID, "WRONGPASS")))
                .isInstanceOf(BadCredentialsException.class);
    }

    /**
     * An unknown user id raises {@link UsernameNotFoundException} (not {@link BadCredentialsException}):
     * the {@code CardDemoAuthenticationProvider} propagates the service's not-found exception unchanged.
     * Parity: {@code COSGN00C} L247-249, {@code WS-RESP-CD = 13} (NOTFND) &rarr; "User not found. Try
     * again ...". {@code NOSUCH99} is guaranteed absent from the seed.
     */
    @Test
    void unknownUserThrowsUsernameNotFound() {
        assertThatThrownBy(() -> authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken("NOSUCH99", SEED_PASSWORD)))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    /**
     * Lowercase credentials still authenticate, proving the COBOL uppercasing parity. Parity:
     * {@code COSGN00C} L132-136 applies {@code FUNCTION UPPER-CASE} to BOTH the entered user id and
     * password before the {@code USRSEC} read and cleartext compare; the migration preserves this
     * ({@code CardDemoUserDetailsService} uppercases the id, {@code CardDemoAuthenticationProvider}
     * uppercases the presented password, both with {@code Locale.ROOT}), so lowercase input must
     * resolve to the uppercase-stored {@code ADMIN001} row with {@code ROLE_ADMIN}.
     */
    @Test
    void lowercaseCredentialsAreUppercasedForParity() {
        Authentication result = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken("admin001", "password"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .contains(ROLE_ADMIN);
    }
}
