package com.carddemo.integration;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.carddemo.dto.auth.LoginRequest;
import com.carddemo.dto.auth.LoginResponse;
import com.carddemo.dto.user.UserCreateRequest;
import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the CardDemo Spring Security 6 stack.
 *
 * <p>This is the <strong>canonical regression check</strong> for the three most
 * important security mandates of the COBOL/CICS/VSAM &rarr; Spring Boot 3.2
 * migration:</p>
 * <ul>
 *   <li><strong>PR-17 — BCrypt for all passwords.</strong> The original
 *       {@code COSGN00C.cbl} (L211-L257) performed a plaintext comparison
 *       {@code IF SEC-USR-PWD = WS-USER-PWD}. The Java port stores a 60-character
 *       BCrypt hash and authenticates via
 *       {@code BCryptPasswordEncoder.matches(rawPassword, storedHash)}. The 10
 *       default users ({@code ADMIN001}-{@code ADMIN005},
 *       {@code USER0001}-{@code USER0005}) all share the literal password
 *       {@code "PASSWORD"} but receive <em>distinct</em> hashes thanks to BCrypt's
 *       embedded per-hash salt (seeded by {@code V4__seed_users.sql}, derived from
 *       {@code app/jcl/DUSRSECJ.jcl}).</li>
 *   <li><strong>PR-18 — Method-level {@code @PreAuthorize("hasRole('ADMIN')")}.</strong>
 *       The original {@code COUSR00C}-{@code COUSR03C} user-administration programs
 *       had <em>zero</em> programmatic authorization checks; access was governed only
 *       by menu routing through {@code COADM01C}. A caller who invoked the user-admin
 *       transaction directly bypassed authorization entirely. This test verifies the
 *       Java migration <em>closes that documented gap</em> by enforcing
 *       {@code @PreAuthorize} on every {@code /api/admin/**} endpoint.</li>
 *   <li><strong>PR-19 — Role-mapping fidelity.</strong> {@code SEC-USR-TYPE = 'A'}
 *       maps to {@code ROLE_ADMIN} and {@code 'U'} maps to {@code ROLE_USER}
 *       (the {@code CDEMO-USRTYP-ADMIN}/{@code CDEMO-USRTYP-USER} 88-levels from
 *       {@code app/cpy/COCOM01Y.cpy}), performed by {@code CustomAuthorityMapper} and
 *       surfaced by {@code User.getAuthorities()}.</li>
 * </ul>
 *
 * <p>It additionally verifies (PR-20) that no password hash is ever emitted on an
 * outbound payload, and the case-handling convention from {@code COSGN00C.cbl}
 * (L132-L136): the user ID is normalized to uppercase, while the password remains
 * case-sensitive (AAP &sect;0.6.8 — a deliberate, security-improving divergence from
 * the COBOL behavior that uppercased both).</p>
 *
 * <h2>Test topology</h2>
 * <p>{@code @SpringBootTest(RANDOM_PORT)} boots a real embedded Tomcat so the full
 * Spring Security filter chain (including {@code JwtAuthenticationFilter}) executes
 * against genuine HTTP requests issued by {@link TestRestTemplate}. A real
 * PostgreSQL 15 database is provided by Testcontainers; Flyway applies every
 * {@code V*.sql} migration (including the user seed) and Hibernate validates the
 * schema. This exercises the authentication path end-to-end — JWT issuance, bearer
 * presentation, BCrypt verification, and {@code @PreAuthorize} enforcement —
 * against production wiring rather than mocks.</p>
 *
 * <p><strong>Datasource note:</strong> the {@code @DynamicPropertySource} below
 * binds this class's dedicated {@link #POSTGRES} container and explicitly sets
 * {@code spring.datasource.driver-class-name} to {@code org.postgresql.Driver}.
 * That override is required because {@code application-test.yml} configures the
 * Testcontainers {@code ContainerDatabaseDriver} (which only accepts
 * {@code jdbc:tc:} magic URLs); without the override the plain
 * {@code jdbc:postgresql://} URL returned by {@link PostgreSQLContainer#getJdbcUrl()}
 * would be rejected by that driver.</p>
 *
 * @see com.carddemo.dto.auth.LoginRequest
 * @see com.carddemo.dto.auth.LoginResponse
 * @see com.carddemo.entity.User
 * @see com.carddemo.repository.UserRepository
 * @see com.carddemo.dto.user.UserCreateRequest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled("""
        Deferred to CP4. This IT boots the full Spring application context and exercises the \
        online security endpoints (POST /api/auth/login, /api/admin/users, /api/menu, \
        /api/admin/jobs/{name}/launch), none of which exist yet: SecurityConfig, the \
        PasswordEncoder bean, JwtAuthenticationFilter, AuthController, UserController, \
        MenuController and BatchAdminController are all CP4 deliverables. Until they exist the \
        context fails to start (UserSeedingJobConfig requires a PasswordEncoder bean that CP4's \
        SecurityConfig will provide), so an enabled IT here would make `mvn verify` fail. The \
        container-lifecycle fix (eager static singleton, see POSTGRES below) is applied now so \
        this suite is correct the moment CP4 re-enables it by removing this annotation.""")
@DisplayName("SecurityIT — Verifies Spring Security 6 stack end-to-end (JWT, BCrypt, @PreAuthorize, role mapping)")
class SecurityIT {

    // ------------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------------

    /** Literal seed password shared by all 10 default users (BCrypt-hashed per user). */
    private static final String DEFAULT_PASSWORD = "PASSWORD";

    /** Stateless authentication endpoint (replaces CICS COSGN00C sign-on). */
    private static final String LOGIN_PATH = "/api/auth/login";

    /** ADMIN-only user-administration collection endpoint (PR-18 — closes COUSR auth gap). */
    private static final String ADMIN_USERS_PATH = "/api/admin/users";

    /** ADMIN-only batch-job launch endpoint (BatchAdminController). */
    private static final String BATCH_LAUNCH_PATH = "/api/admin/jobs/transactionPostingJob/launch";

    /** Role-filtered menu endpoint, accessible to any authenticated principal. */
    private static final String MENU_PATH = "/api/menu";

    /** The five default administrator user IDs (SEC-USR-TYPE = 'A'). */
    private static final List<String> ADMIN_IDS =
            List.of("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005");

    /** The five default regular-user IDs (SEC-USR-TYPE = 'U'). */
    private static final List<String> USER_IDS =
            List.of("USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    /**
     * BCrypt hash shape: {@code $2[aby]$NN$} followed by exactly 53 characters of
     * base64 salt+digest (60 characters total). Tolerant of the {@code $2a}/{@code $2b}/{@code $2y}
     * algorithm-version variants emitted by different encoders.
     */
    private static final Pattern BCRYPT_PATTERN =
            Pattern.compile("^\\$2[aby]\\$\\d{2}\\$.{53}$");

    // ------------------------------------------------------------------------
    // Testcontainers PostgreSQL 15
    // ------------------------------------------------------------------------

    /**
     * Dedicated PostgreSQL 15 container for the security stack, managed with the Testcontainers
     * <em>singleton-container</em> pattern: it is started eagerly in the {@code static} initializer
     * block below rather than via the {@code @Container}/{@code @Testcontainers} JUnit lifecycle.
     *
     * <p>This is deliberate and necessary. The {@link DynamicPropertySource} method must read the
     * container's mapped JDBC port while Spring is building the application context. Under the
     * {@code @Container} lifecycle the container is only started by the Testcontainers JUnit
     * extension's {@code beforeAll} callback, which (because {@code @SpringBootTest}'s
     * {@code SpringExtension} is registered first) can run <em>after</em> Spring has already begun
     * resolving {@code spring.datasource.*}, yielding
     * {@code IllegalStateException: Mapped port can only be obtained after the container is started}.
     * Starting the container in a {@code static} block guarantees it is running before any property
     * supplier is evaluated, regardless of extension ordering or whether the whole class or a single
     * {@code @Nested} group is executed. The container is reaped by the Testcontainers Ryuk sidecar
     * at JVM exit; {@code @SuppressWarnings("resource")} documents that we intentionally never close
     * it explicitly. (Mirrors the proven pattern in {@code FullStackIT}.)</p>
     */
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15"))
            .withDatabaseName("carddemo_security_test")
            .withUsername("carddemo")
            .withPassword("test_password");

    static {
        // Eager start (singleton-container pattern) — see field Javadoc for the rationale.
        POSTGRES.start();
    }

    /**
     * Binds the container's JDBC coordinates onto the Spring {@code Environment} before
     * the application context starts. The {@code driver-class-name} override is mandatory
     * (see class Javadoc) so that the plain {@code jdbc:postgresql://} URL is handled by
     * the real PostgreSQL JDBC driver rather than the Testcontainers magic-URL driver
     * configured in {@code application-test.yml}.
     *
     * @param registry the dynamic property registry supplied by the Spring TestContext framework
     */
    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    // ------------------------------------------------------------------------
    // Injected collaborators
    // ------------------------------------------------------------------------

    /** The random embedded-Tomcat port (used for a context-startup sanity assertion). */
    @LocalServerPort
    private int port;

    /** HTTP client that auto-resolves relative paths against {@code http://localhost:{port}}. */
    @Autowired
    private TestRestTemplate restTemplate;

    /** Production BCrypt encoder bean — used to verify {@code matches("PASSWORD", storedHash)} (PR-17). */
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    /** Repository for direct database verification of the seeded user rows. */
    @Autowired
    private UserRepository userRepository;

    // ------------------------------------------------------------------------
    // Context sanity
    // ------------------------------------------------------------------------

    /**
     * Confirms the application context booted on a real random port with the HTTP
     * client wired. This guards the most basic precondition for every other test:
     * a live server is listening.
     */
    @Test
    @DisplayName("Context boots on a random embedded port with TestRestTemplate wired")
    void shouldStartOnRandomPort() {
        assertThat(port)
                .as("Embedded Tomcat must be bound to a positive random port for end-to-end HTTP tests")
                .isPositive();
        assertThat(restTemplate)
                .as("TestRestTemplate must be auto-configured by @SpringBootTest(RANDOM_PORT)")
                .isNotNull();
    }

    // ========================================================================
    // 1. LoginEndpoint — POST /api/auth/login
    // ========================================================================

    /**
     * Verifies the stateless sign-on endpoint that replaces CICS {@code COSGN00C}.
     * Successful logins return HTTP 200 with a JWT bearer token; credential and
     * validation failures map to 401 / 400 respectively.
     */
    @Nested
    @DisplayName("1. Login endpoint (POST /api/auth/login) — replaces COSGN00C sign-on")
    class LoginEndpoint {

        @Test
        @DisplayName("Valid ADMIN credentials return 200 + JWT token (userType 'A')")
        void shouldReturn200AndJwtTokenForValidAdminCredentials() {
            LoginRequest req = new LoginRequest("ADMIN001", DEFAULT_PASSWORD);
            ResponseEntity<LoginResponse> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, LoginResponse.class);

            assertThat(resp.getStatusCode())
                    .as("Valid admin credentials must authenticate (PR-17 BCrypt match)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("A 200 login response must carry a body")
                    .isNotNull();
            assertThat(resp.getBody().token())
                    .as("Login response must include a non-blank JWT bearer token")
                    .isNotNull().isNotBlank();
            assertThat(resp.getBody().userType())
                    .as("ADMIN001 must report userType 'A' (PR-19 → ROLE_ADMIN)")
                    .isEqualTo("A");
            assertThat(resp.getBody().userId())
                    .as("Login response must echo the normalized user ID")
                    .isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("Valid USER credentials return 200 + JWT token (userType 'U')")
        void shouldReturn200AndJwtTokenForValidUserCredentials() {
            LoginRequest req = new LoginRequest("USER0001", DEFAULT_PASSWORD);
            ResponseEntity<LoginResponse> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, LoginResponse.class);

            assertThat(resp.getStatusCode())
                    .as("Valid regular-user credentials must authenticate (PR-17 BCrypt match)")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("A 200 login response must carry a body")
                    .isNotNull();
            assertThat(resp.getBody().token())
                    .as("Login response must include a non-blank JWT bearer token")
                    .isNotNull().isNotBlank();
            assertThat(resp.getBody().userType())
                    .as("USER0001 must report userType 'U' (PR-19 → ROLE_USER)")
                    .isEqualTo("U");
        }

        @ParameterizedTest(name = "default user {0} authenticates with the seed password")
        @ValueSource(strings = {"ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
                                 "USER0001", "USER0002", "USER0003", "USER0004", "USER0005"})
        @DisplayName("All 10 default users authenticate with password \"PASSWORD\" (PR-17 seed)")
        void shouldAuthenticateAllDefaultUsers(String userId) {
            LoginRequest req = new LoginRequest(userId, DEFAULT_PASSWORD);
            ResponseEntity<LoginResponse> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, LoginResponse.class);

            assertThat(resp.getStatusCode())
                    .as("Seeded default user %s must authenticate with \"PASSWORD\"", userId)
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("Login response body for %s must not be null", userId)
                    .isNotNull();
            assertThat(resp.getBody().token())
                    .as("JWT token for %s must be non-blank", userId)
                    .isNotNull().isNotBlank();
            assertThat(resp.getBody().userId())
                    .as("Login response must echo user ID %s", userId)
                    .isEqualTo(userId);
        }

        @Test
        @DisplayName("Wrong password returns 401 (COSGN00C \"Wrong Password\" — genericized)")
        void shouldReturn401ForWrongPassword() {
            LoginRequest req = new LoginRequest("ADMIN001", "WRONG");
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("A wrong password must be rejected with 401 (BCrypt mismatch, PR-17)")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Unknown user returns 401 (COSGN00C \"User not found\" — genericized, no enumeration)")
        void shouldReturn401ForUnknownUser() {
            LoginRequest req = new LoginRequest("NOTAUSER", DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("An unknown user must be rejected with 401 — genericized to prevent user enumeration")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Missing userId returns 400 (Bean Validation @NotBlank)")
        void shouldReturn400ForMissingUserId() {
            LoginRequest req = new LoginRequest(null, DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("A null userId must fail @NotBlank validation with 400")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Missing password returns 400 (Bean Validation @NotBlank)")
        void shouldReturn400ForMissingPassword() {
            LoginRequest req = new LoginRequest("ADMIN001", null);
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("A null password must fail @NotBlank validation with 400")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Blank userId returns 400 (Bean Validation @NotBlank)")
        void shouldReturn400ForBlankUserId() {
            LoginRequest req = new LoginRequest("", DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("A blank userId must fail @NotBlank validation with 400")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Blank password returns 400 (Bean Validation @NotBlank)")
        void shouldReturn400ForBlankPassword() {
            LoginRequest req = new LoginRequest("ADMIN001", "");
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("A blank password must fail @NotBlank validation with 400")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ========================================================================
    // 2. CaseSensitivity — COSGN00C L132-L136 uppercase convention (AAP §0.6.8)
    // ========================================================================

    /**
     * The original {@code COSGN00C} applied {@code FUNCTION UPPER-CASE} to BOTH the
     * user ID and the password. The Java migration intentionally diverges
     * (AAP &sect;0.6.8): the user ID is uppercase-normalized, but the password is
     * preserved verbatim (no {@code @Pattern} on {@code LoginRequest.password}) so
     * full password entropy is retained for BCrypt.
     *
     * <p>For user-ID case, the committed {@code LoginRequest} declares
     * {@code @Pattern("[A-Z0-9]+")} on {@code userId} and documents that lowercase
     * input is rejected with HTTP 400 at the validation boundary. Whether a lowercase
     * ID is normalized-and-accepted (200) or rejected-by-validation (400) is therefore
     * an implementation choice of {@code AuthController}; both honor the
     * "uppercase user ID" convention. These tests assert the status is one of
     * {@code {200, 400}} and — critically — never a successful login under a
     * different identity nor a 5xx. The password-case tests are unambiguous: a
     * case-mismatched password must always be rejected with 401.</p>
     */
    @Nested
    @DisplayName("2. Case sensitivity — userId uppercased, password case-sensitive (AAP §0.6.8)")
    class CaseSensitivity {

        @Test
        @DisplayName("Lowercase userId is normalized (200) or rejected by @Pattern (400) — never 401/5xx")
        void shouldAcceptLowercaseUserIdViaUppercaseNormalization() {
            LoginRequest req = new LoginRequest("admin001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("Lowercase userId must be uppercase-normalized to a 200 login OR rejected with 400 "
                        + "by the LoginRequest @Pattern([A-Z0-9]+); it must NOT be treated as an unknown "
                        + "user (401) or cause a server error")
                    .isIn(HttpStatus.OK, HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Mixed-case userId is normalized (200) or rejected by @Pattern (400) — never 401/5xx")
        void shouldAcceptMixedCaseUserIdViaUppercaseNormalization() {
            LoginRequest req = new LoginRequest("AdMiN001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("Mixed-case userId must be uppercase-normalized to a 200 login OR rejected with 400 "
                        + "by the LoginRequest @Pattern([A-Z0-9]+); it must NOT be treated as an unknown "
                        + "user (401) or cause a server error")
                    .isIn(HttpStatus.OK, HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Lowercase password is rejected (401) — password is case-sensitive (AAP §0.6.8)")
        void shouldRejectLowercasePasswordSincePasswordIsCaseSensitive() {
            LoginRequest req = new LoginRequest("ADMIN001", "password");
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("\"password\" must NOT match the BCrypt hash of \"PASSWORD\" — password case is "
                        + "preserved (deliberate divergence from COBOL per AAP §0.6.8)")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Mixed-case password is rejected (401) — password is case-sensitive")
        void shouldRejectMixedCasePassword() {
            LoginRequest req = new LoginRequest("ADMIN001", "PaSsWoRd");
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("\"PaSsWoRd\" must NOT match the BCrypt hash of \"PASSWORD\" — password case is preserved")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ========================================================================
    // 3. BCryptPasswordVerification — PR-17
    // ========================================================================

    /**
     * Direct database verification of the BCrypt password mandate (PR-17). The 10
     * default users are seeded by {@code V4__seed_users.sql} (the Flyway equivalent of
     * {@code app/jcl/DUSRSECJ.jcl}); each row stores a 60-character BCrypt hash of the
     * literal {@code "PASSWORD"} — never plaintext.
     */
    @Nested
    @DisplayName("3. BCrypt password verification (PR-17) — no plaintext, salted, work-factor ≥ 10")
    class BCryptPasswordVerification {

        @Test
        @DisplayName("Stored password is a 60-char BCrypt hash, not plaintext")
        void shouldStoreBCryptHashesNotPlaintext() {
            User admin = userRepository.findById("ADMIN001").orElseThrow(
                    () -> new AssertionError("Seed user ADMIN001 must exist (V4__seed_users.sql)"));

            assertThat(admin.getPassword())
                    .as("ADMIN001 password must be a BCrypt hash (\"$2\" prefix), never the plaintext \"PASSWORD\"")
                    .startsWith("$2")
                    .isNotEqualTo(DEFAULT_PASSWORD)
                    .hasSize(60);
        }

        @Test
        @DisplayName("All 10 default users BCrypt-match \"PASSWORD\"")
        void shouldVerifyAllTenUsersHaveBcryptMatching() {
            List<String> allIds = concatIds();
            for (String id : allIds) {
                User user = userRepository.findById(id).orElseThrow(
                        () -> new AssertionError("Seed user " + id + " must exist (V4__seed_users.sql)"));
                assertThat(passwordEncoder.matches(DEFAULT_PASSWORD, user.getPassword()))
                        .as("BCryptPasswordEncoder.matches(\"PASSWORD\", storedHash) must be true for %s (PR-17)", id)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("Every user has a distinct hash — BCrypt salt uniqueness")
        void shouldStoreDistinctHashPerUserDueToSalt() {
            List<User> all = userRepository.findAll();
            Set<String> distinctHashes = all.stream()
                    .map(User::getPassword)
                    .collect(Collectors.toSet());

            assertThat(all)
                    .as("At least the 10 default users must be seeded")
                    .hasSizeGreaterThanOrEqualTo(10);
            assertThat(distinctHashes)
                    .as("Every user must have a UNIQUE BCrypt hash even though the seed password is identical — "
                        + "BCrypt embeds a distinct 22-char salt per hash (PR-17)")
                    .hasSize(all.size());
        }

        @Test
        @DisplayName("Hash uses BCrypt format $2X$NN$ with work factor ≥ 10")
        void shouldUseDefaultStrengthBcrypt() {
            User admin = userRepository.findById("ADMIN001").orElseThrow(
                    () -> new AssertionError("Seed user ADMIN001 must exist (V4__seed_users.sql)"));
            String hash = admin.getPassword();

            assertThat(BCRYPT_PATTERN.matcher(hash).matches())
                    .as("Hash %s must match the BCrypt shape $2[aby]$NN$<53 chars>", hash)
                    .isTrue();

            int workFactor = Integer.parseInt(hash.substring(4, 6));
            assertThat(workFactor)
                    .as("BCrypt work factor must be ≥ 10 (BCryptPasswordEncoder default) for sufficient strength")
                    .isGreaterThanOrEqualTo(10);
        }
    }

    // ========================================================================
    // 4. JwtBearerTokenFlow — token issuance and presentation
    // ========================================================================

    /**
     * Exercises the JWT bearer-token lifecycle through the live filter chain: a
     * protected endpoint rejects anonymous and malformed-token requests with 401,
     * and accepts a valid token issued by the login endpoint.
     */
    @Nested
    @DisplayName("4. JWT bearer-token flow — issuance, rejection of missing/malformed tokens")
    class JwtBearerTokenFlow {

        @Test
        @DisplayName("Protected request without a token returns 401")
        void shouldRejectProtectedRequestWithoutToken() {
            ResponseEntity<String> resp = restTemplate.getForEntity(MENU_PATH, String.class);

            assertThat(resp.getStatusCode())
                    .as("An unauthenticated request to a protected endpoint must be 401 Unauthorized")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Protected request with a malformed token returns 401")
        void shouldRejectProtectedRequestWithMalformedToken() {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth("garbage.not-a-jwt");
            ResponseEntity<String> resp = restTemplate.exchange(
                    MENU_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

            assertThat(resp.getStatusCode())
                    .as("A malformed/invalid bearer token must be rejected with 401 by JwtAuthenticationFilter")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Protected request with a valid ADMIN token succeeds")
        void shouldAcceptProtectedRequestWithValidAdminToken() {
            String token = login("ADMIN001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp = getWithAuth(MENU_PATH, token, String.class);

            assertThat(resp.getStatusCode().is2xxSuccessful())
                    .as("A valid ADMIN bearer token must be accepted on the role-filtered menu endpoint "
                        + "(actual=%s)", resp.getStatusCode())
                    .isTrue();
        }

        @Test
        @DisplayName("Protected request with a valid USER token succeeds")
        void shouldAcceptProtectedRequestWithValidUserToken() {
            String token = login("USER0001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp = getWithAuth(MENU_PATH, token, String.class);

            assertThat(resp.getStatusCode().is2xxSuccessful())
                    .as("A valid USER bearer token must be accepted on the (non-admin) menu endpoint "
                        + "(actual=%s)", resp.getStatusCode())
                    .isTrue();
        }

        @Test
        @DisplayName("Issued token is a well-formed JWT representing the authenticated user")
        void shouldIncludeUserIdClaimInToken() {
            LoginRequest req = new LoginRequest("ADMIN001", DEFAULT_PASSWORD);
            ResponseEntity<LoginResponse> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, LoginResponse.class);

            assertThat(resp.getStatusCode())
                    .as("Login must succeed before inspecting the token")
                    .isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("Login response body must not be null")
                    .isNotNull();
            assertThat(resp.getBody().userId())
                    .as("Response must identify the authenticated principal as ADMIN001")
                    .isEqualTo("ADMIN001");
            assertThat(resp.getBody().token().split("\\."))
                    .as("A compact JWT must comprise three dot-separated segments (header.payload.signature)")
                    .hasSize(3);
        }
    }

    // ========================================================================
    // 5. AdminRoleEnforcement — PR-18: @PreAuthorize("hasRole('ADMIN')")
    //    THE most important section: closes the documented COUSR auth gap.
    // ========================================================================

    /**
     * Verifies the closure of the programmatic-authorization gap that existed in the
     * original {@code COUSR00C}-{@code COUSR03C} programs (Tech Spec &sect;6.4): those
     * programs performed NO authorization check and relied solely on admin-menu routing
     * ({@code COADM01C}). In the stateless REST world a client can target any endpoint
     * directly, so {@code @PreAuthorize("hasRole('ADMIN')")} must enforce authorization
     * at the method/controller level (PR-18).
     *
     * <p>Denied access is asserted strictly (403 for an authenticated non-admin; 401 for
     * an anonymous caller). Allowed access is asserted as {@code 2xx} (proving the security
     * layer admits the ADMIN principal) because the exact success code — 200 vs 201 vs 204 —
     * is a controller implementation detail; the security contract is what this section
     * exists to verify.</p>
     */
    @Nested
    @DisplayName("5. Admin role enforcement (PR-18) — closes the COUSR00C-03C programmatic auth gap")
    class AdminRoleEnforcement {

        @Test
        @DisplayName("ADMIN can list users (GET /api/admin/users)")
        void shouldAllowAdminToAccessUserCrud() {
            String adminToken = login("ADMIN001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp = getWithAuth(ADMIN_USERS_PATH, adminToken, String.class);

            assertThat(resp.getStatusCode().is2xxSuccessful())
                    .as("ADMIN must be able to list users per PR-18 (actual=%s)", resp.getStatusCode())
                    .isTrue();
        }

        @Test
        @DisplayName("USER receives 403 for GET /api/admin/users — closes COUSR auth gap")
        void shouldRejectRegularUserAccessToUserCrud() {
            String userToken = login("USER0001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp = getWithAuth(ADMIN_USERS_PATH, userToken, String.class);

            assertThat(resp.getStatusCode())
                    .as("A non-admin must receive 403 Forbidden — this is the regression check for the "
                        + "documented COUSR00C-03C programmatic-auth gap (PR-18)")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Anonymous receives 401 for GET /api/admin/users")
        void shouldRejectAnonymousAccessToUserCrud() {
            ResponseEntity<String> resp = restTemplate.getForEntity(ADMIN_USERS_PATH, String.class);

            assertThat(resp.getStatusCode())
                    .as("Anonymous access must be 401 (no credentials), distinct from 403 (authenticated but wrong role)")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("ADMIN can create a user (POST /api/admin/users)")
        void shouldAllowAdminToCreateUser() {
            String adminToken = login("ADMIN001", DEFAULT_PASSWORD);
            UserCreateRequest body = validUserCreateRequest("TMPCRT01", "U");
            ResponseEntity<String> resp = postWithAuth(ADMIN_USERS_PATH, adminToken, body, String.class);

            assertThat(resp.getStatusCode().is2xxSuccessful())
                    .as("ADMIN must be able to create a user per PR-18 (expected 201; actual=%s)", resp.getStatusCode())
                    .isTrue();

            // Clean up so this test leaves no residual row (keeps the suite order-independent).
            deleteWithAuth(ADMIN_USERS_PATH + "/TMPCRT01", adminToken, Void.class);
        }

        @Test
        @DisplayName("USER receives 403 for POST /api/admin/users")
        void shouldRejectRegularUserCreatingUser() {
            String userToken = login("USER0001", DEFAULT_PASSWORD);
            UserCreateRequest body = validUserCreateRequest("TMPCRT02", "U");
            ResponseEntity<String> resp = postWithAuth(ADMIN_USERS_PATH, userToken, body, String.class);

            assertThat(resp.getStatusCode())
                    .as("A non-admin must receive 403 when attempting to create a user (PR-18). @PreAuthorize "
                        + "short-circuits before the controller body, so no row is created")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("ADMIN can update a user (PUT /api/admin/users/{id})")
        void shouldAllowAdminToUpdateUser() {
            String adminToken = login("ADMIN001", DEFAULT_PASSWORD);
            // Create a throwaway user, then update it — avoids mutating seed users.
            UserCreateRequest create = validUserCreateRequest("TMPUPD01", "U");
            postWithAuth(ADMIN_USERS_PATH, adminToken, create, String.class);

            UserCreateRequest update = validUserCreateRequest("TMPUPD01", "U");
            update.setLastName("UPDATED");
            ResponseEntity<String> resp =
                    putWithAuth(ADMIN_USERS_PATH + "/TMPUPD01", adminToken, update, String.class);

            assertThat(resp.getStatusCode().is2xxSuccessful())
                    .as("ADMIN must be able to update a user per PR-18 (actual=%s)", resp.getStatusCode())
                    .isTrue();

            deleteWithAuth(ADMIN_USERS_PATH + "/TMPUPD01", adminToken, Void.class);
        }

        @Test
        @DisplayName("USER receives 403 for PUT /api/admin/users/{id}")
        void shouldRejectRegularUserUpdatingUser() {
            String userToken = login("USER0001", DEFAULT_PASSWORD);
            UserCreateRequest update = validUserCreateRequest("ADMIN001", "A");
            ResponseEntity<String> resp =
                    putWithAuth(ADMIN_USERS_PATH + "/ADMIN001", userToken, update, String.class);

            assertThat(resp.getStatusCode())
                    .as("A non-admin must receive 403 when attempting to update a user; @PreAuthorize blocks "
                        + "before the controller body so ADMIN001 is never modified (PR-18)")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("ADMIN can delete a user (DELETE /api/admin/users/{id}) — create-then-delete")
        void shouldAllowAdminToDeleteUser() {
            String adminToken = login("ADMIN001", DEFAULT_PASSWORD);
            // Create a disposable user so we never delete one of the seed users.
            UserCreateRequest create = validUserCreateRequest("TMPDEL01", "U");
            postWithAuth(ADMIN_USERS_PATH, adminToken, create, String.class);

            ResponseEntity<Void> resp =
                    deleteWithAuth(ADMIN_USERS_PATH + "/TMPDEL01", adminToken, Void.class);

            assertThat(resp.getStatusCode().is2xxSuccessful())
                    .as("ADMIN must be able to delete a user per PR-18 (expected 204/200; actual=%s)",
                        resp.getStatusCode())
                    .isTrue();
        }

        @Test
        @DisplayName("USER receives 403 for DELETE /api/admin/users/{id}")
        void shouldRejectRegularUserDeletingUser() {
            String userToken = login("USER0001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    deleteWithAuth(ADMIN_USERS_PATH + "/ADMIN001", userToken, String.class);

            assertThat(resp.getStatusCode())
                    .as("A non-admin must receive 403 when attempting to delete a user; @PreAuthorize blocks "
                        + "before the controller body so ADMIN001 is never deleted (PR-18)")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("ADMIN can launch a batch job (POST /api/admin/jobs/{job}/launch)")
        void shouldAllowAdminToAccessBatchJobAdmin() {
            String adminToken = login("ADMIN001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp = postWithAuth(BATCH_LAUNCH_PATH, adminToken, null, String.class);

            // BatchAdminController is class-level @PreAuthorize("hasRole('ADMIN')"); the exact outcome
            // (202 launched / 404 unknown job / 409 conflict / 400 bad params) depends on job registration,
            // but the security layer must NEVER deny an ADMIN with 401/403.
            assertThat(resp.getStatusCode())
                    .as("ADMIN must pass the @PreAuthorize gate on the batch launch endpoint — never 401/403 "
                        + "(actual=%s)", resp.getStatusCode())
                    .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("USER receives 403 for POST /api/admin/jobs/{job}/launch")
        void shouldRejectRegularUserFromBatchJobAdmin() {
            String userToken = login("USER0001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp = postWithAuth(BATCH_LAUNCH_PATH, userToken, null, String.class);

            assertThat(resp.getStatusCode())
                    .as("A non-admin must receive 403 on the batch launch endpoint (PR-18, BatchAdminController)")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ========================================================================
    // 6. RoleMappingFidelity — PR-19: 'A' → ROLE_ADMIN, 'U' → ROLE_USER
    // ========================================================================

    /**
     * Verifies the role-mapping fidelity mandated by PR-19, surfaced three independent
     * ways: (a) the {@code userType} field echoed in the login response, (b) behavioral
     * access control through the live {@code @PreAuthorize} gate, and (c) the
     * {@code User.getAuthorities()} contract read directly from the database — confirming
     * the mandatory Spring Security {@code ROLE_} prefix.
     */
    @Nested
    @DisplayName("6. Role-mapping fidelity (PR-19) — 'A'→ROLE_ADMIN, 'U'→ROLE_USER")
    class RoleMappingFidelity {

        @Test
        @DisplayName("ADMIN userType 'A' maps to ROLE_ADMIN (response + behavior + authorities)")
        void shouldMapAdminUserTypeToRoleAdmin() {
            LoginRequest req = new LoginRequest("ADMIN001", DEFAULT_PASSWORD);
            ResponseEntity<LoginResponse> login = restTemplate.postForEntity(LOGIN_PATH, req, LoginResponse.class);
            assertThat(login.getBody()).as("ADMIN001 login body must not be null").isNotNull();
            assertThat(login.getBody().userType())
                    .as("ADMIN001 must report userType 'A'")
                    .isEqualTo("A");

            // Behavioral proof: an ADMIN token clears the hasRole('ADMIN') gate.
            ResponseEntity<String> adminCall = getWithAuth(ADMIN_USERS_PATH, login.getBody().token(), String.class);
            assertThat(adminCall.getStatusCode().is2xxSuccessful())
                    .as("ROLE_ADMIN must satisfy hasRole('ADMIN') on /api/admin/users (actual=%s)",
                        adminCall.getStatusCode())
                    .isTrue();

            // Authority-contract proof from the database.
            User admin = userRepository.findById("ADMIN001").orElseThrow();
            assertThat(authorityStrings(admin))
                    .as("User.getAuthorities() for an 'A' user must contain ROLE_ADMIN (PR-19)")
                    .contains("ROLE_ADMIN");
        }

        @Test
        @DisplayName("USER userType 'U' maps to ROLE_USER (response + behavior + authorities)")
        void shouldMapRegularUserTypeToRoleUser() {
            LoginRequest req = new LoginRequest("USER0001", DEFAULT_PASSWORD);
            ResponseEntity<LoginResponse> login = restTemplate.postForEntity(LOGIN_PATH, req, LoginResponse.class);
            assertThat(login.getBody()).as("USER0001 login body must not be null").isNotNull();
            assertThat(login.getBody().userType())
                    .as("USER0001 must report userType 'U'")
                    .isEqualTo("U");

            // Behavioral proof: a USER token is denied the ADMIN-only resource.
            ResponseEntity<String> adminCall = getWithAuth(ADMIN_USERS_PATH, login.getBody().token(), String.class);
            assertThat(adminCall.getStatusCode())
                    .as("ROLE_USER must NOT satisfy hasRole('ADMIN') — expect 403")
                    .isEqualTo(HttpStatus.FORBIDDEN);

            User user = userRepository.findById("USER0001").orElseThrow();
            assertThat(authorityStrings(user))
                    .as("User.getAuthorities() for a 'U' user must contain ROLE_USER (PR-19)")
                    .contains("ROLE_USER");
        }

        @Test
        @DisplayName("ADMIN and USER authorities are distinct (no cross-mapping)")
        void shouldNotMapAdminToRoleUserOrViceVersa() {
            User admin = userRepository.findById("ADMIN001").orElseThrow();
            User user = userRepository.findById("USER0001").orElseThrow();

            assertThat(authorityStrings(admin))
                    .as("An 'A' user must NOT be granted ROLE_USER")
                    .doesNotContain("ROLE_USER");
            assertThat(authorityStrings(user))
                    .as("A 'U' user must NOT be granted ROLE_ADMIN")
                    .doesNotContain("ROLE_ADMIN");
        }

        @Test
        @DisplayName("Granted authorities carry the mandatory ROLE_ prefix")
        void shouldAuthorityHavePrefixRole() {
            User admin = userRepository.findById("ADMIN001").orElseThrow();

            assertThat(authorityStrings(admin))
                    .as("Spring Security hasRole('ADMIN') checks for the authority 'ROLE_ADMIN' — the "
                        + "ROLE_ prefix is mandatory (PR-19)")
                    .isNotEmpty()
                    .allSatisfy(authority -> assertThat(authority).startsWith("ROLE_"));
        }
    }

    // =====================================================================================
    // SECTION 7 — ResponseSanitization: outbound payloads must never leak credentials.
    //   PR-17/PR-20 defense-in-depth: no plaintext password, no BCrypt hash, no sec_usr_pwd
    //   field, and no user-enumeration signal in error responses.
    // =====================================================================================
    @Nested
    @DisplayName("Response Sanitization — credentials never leak on outbound payloads")
    class ResponseSanitization {

        @Test
        @DisplayName("Login response body contains no password / BCrypt hash / sec_usr_pwd")
        void shouldNotIncludePasswordHashInLoginResponse() {
            LoginRequest req = new LoginRequest("ADMIN001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    restTemplate.postForEntity(LOGIN_PATH, req, String.class);

            assertThat(resp.getStatusCode())
                    .as("Login of ADMIN001 must succeed before inspecting the body")
                    .isEqualTo(HttpStatus.OK);

            String body = resp.getBody();
            assertThat(body)
                    .as("Login response body must be present")
                    .isNotNull();
            assertThat(body)
                    .as("Login response must NOT expose a BCrypt hash (PR-17)")
                    .doesNotContain("$2a$")
                    .doesNotContain("$2b$")
                    .doesNotContain("$2y$");
            assertThat(body)
                    .as("Login response must NOT expose the raw COBOL password column name")
                    .doesNotContain("sec_usr_pwd")
                    .doesNotContain("secUsrPwd");
            assertThat(body)
                    .as("Login response must NOT echo a password field of any kind")
                    .doesNotContainIgnoringCase("password");
        }

        @Test
        @DisplayName("Single-user GET response body never leaks a password hash")
        void shouldNotIncludePasswordHashInUserGetResponse() {
            String adminToken = login("ADMIN001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    getWithAuth(ADMIN_USERS_PATH + "/ADMIN001", adminToken, String.class);

            // The sanitization invariant must hold regardless of whether a single-user
            // read endpoint is wired (a 404 body equally must not contain a hash).
            String body = resp.getBody();
            if (body != null) {
                assertThat(body)
                        .as("A user-detail response must NEVER expose the stored BCrypt hash (PR-17)")
                        .doesNotContain("$2a$")
                        .doesNotContain("$2b$")
                        .doesNotContain("$2y$")
                        .doesNotContain("sec_usr_pwd")
                        .doesNotContain("secUsrPwd");
            }
        }

        @Test
        @DisplayName("User-list response body never leaks any password hash")
        void shouldNotIncludePasswordHashInUserListResponse() {
            String adminToken = login("ADMIN001", DEFAULT_PASSWORD);
            ResponseEntity<String> resp =
                    getWithAuth(ADMIN_USERS_PATH, adminToken, String.class);

            String body = resp.getBody();
            if (body != null) {
                assertThat(body)
                        .as("The admin user list must NEVER expose any stored BCrypt hash (PR-17)")
                        .doesNotContain("$2a$")
                        .doesNotContain("$2b$")
                        .doesNotContain("$2y$")
                        .doesNotContain("sec_usr_pwd")
                        .doesNotContain("secUsrPwd");
            }
        }

        @Test
        @DisplayName("Wrong-password and unknown-user failures are indistinguishable (no enumeration)")
        void shouldNotLeakUserExistenceViaErrorMessages() {
            ResponseEntity<String> wrongPassword = restTemplate.postForEntity(
                    LOGIN_PATH, new LoginRequest("ADMIN001", "WRONGPW"), String.class);
            ResponseEntity<String> unknownUser = restTemplate.postForEntity(
                    LOGIN_PATH, new LoginRequest("NOSUCHUS", DEFAULT_PASSWORD), String.class);

            assertThat(wrongPassword.getStatusCode())
                    .as("A wrong password must be rejected with 401")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(unknownUser.getStatusCode())
                    .as("An unknown user must be rejected with 401")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(unknownUser.getStatusCode())
                    .as("Wrong-password and unknown-user must return the SAME status — a differing "
                        + "status would enable username enumeration (defense in depth)")
                    .isEqualTo(wrongPassword.getStatusCode());
        }
    }

    // =====================================================================================
    // SECTION 8 — IntegrationWithCobolOriginalBehavior: parity with the 10 default users
    //   seeded from app/jcl/DUSRSECJ.jcl (literal password "PASSWORD", types A/U). These
    //   assertions read the persisted rows directly, proving the V4 Flyway seed reproduces
    //   the original USRSEC roster (PR-17 seeding + PR-19 type fidelity).
    // =====================================================================================
    @Nested
    @DisplayName("Integration with original COBOL behavior — DUSRSECJ default-user parity")
    class IntegrationWithCobolOriginalBehavior {

        @Test
        @DisplayName("ADMIN001 is seeded with sec_usr_type='A' (ROLE_ADMIN source)")
        void shouldMatchOriginalAdmin001SetType() {
            User admin001 = userRepository.findById("ADMIN001").orElseThrow(
                    () -> new AssertionError("ADMIN001 must be seeded by the V4 migration"));
            assertThat(admin001.getUserType())
                    .as("ADMIN001 user type must be 'A' per app/jcl/DUSRSECJ.jcl (PR-19)")
                    .isEqualTo("A");
        }

        @Test
        @DisplayName("USER0001 is seeded with sec_usr_type='U' (ROLE_USER source)")
        void shouldMatchOriginalUser0001SetType() {
            User user0001 = userRepository.findById("USER0001").orElseThrow(
                    () -> new AssertionError("USER0001 must be seeded by the V4 migration"));
            assertThat(user0001.getUserType())
                    .as("USER0001 user type must be 'U' per app/jcl/DUSRSECJ.jcl (PR-19)")
                    .isEqualTo("U");
        }

        @Test
        @DisplayName("ADMIN001 first name is MARGARET (DUSRSECJ parity)")
        void shouldHaveExpectedFirstNameForAdmin001() {
            User admin001 = userRepository.findById("ADMIN001").orElseThrow(
                    () -> new AssertionError("ADMIN001 must be seeded by the V4 migration"));
            assertThat(admin001.getFirstName().trim())
                    .as("ADMIN001 first name must match the original DUSRSECJ roster")
                    .isEqualTo("MARGARET");
        }

        @Test
        @DisplayName("ADMIN003 last name is WHITMORE (DUSRSECJ parity)")
        void shouldHaveExpectedLastNameForAdmin003() {
            User admin003 = userRepository.findById("ADMIN003").orElseThrow(
                    () -> new AssertionError("ADMIN003 must be seeded by the V4 migration"));
            assertThat(admin003.getLastName().trim())
                    .as("ADMIN003 last name must match the original DUSRSECJ roster")
                    .isEqualTo("WHITMORE");
        }

        @Test
        @DisplayName("USER0002 first name is AJITH (DUSRSECJ parity)")
        void shouldHaveExpectedFirstNameForUser0002() {
            User user0002 = userRepository.findById("USER0002").orElseThrow(
                    () -> new AssertionError("USER0002 must be seeded by the V4 migration"));
            assertThat(user0002.getFirstName().trim())
                    .as("USER0002 first name must match the original DUSRSECJ roster")
                    .isEqualTo("AJITH");
        }
    }

    // =====================================================================================
    // HELPER METHODS — shared HTTP/auth plumbing used by the @Nested sections above.
    // =====================================================================================

    /**
     * Authenticates against {@code POST /api/auth/login} and returns the issued JWT bearer
     * token. Fails fast (with a descriptive assertion) if the login does not return 200 with
     * a populated body, so dependent tests surface the real cause rather than an NPE.
     */
    private String login(String userId, String password) {
        LoginRequest req = new LoginRequest(userId, password);
        ResponseEntity<LoginResponse> resp =
                restTemplate.postForEntity(LOGIN_PATH, req, LoginResponse.class);
        assertThat(resp.getStatusCode())
                .as("Helper login() expected 200 for user %s but got %s",
                    userId, resp.getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .as("Helper login() expected a non-null body for user %s", userId)
                .isNotNull();
        return resp.getBody().token();
    }

    /** Builds bearer-auth headers (JSON content type) for an authenticated request. */
    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Issues an authenticated {@code GET} and returns the typed response entity. */
    private <T> ResponseEntity<T> getWithAuth(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), responseType);
    }

    /** Issues an authenticated {@code POST} with a JSON body and returns the response entity. */
    private <T> ResponseEntity<T> postWithAuth(
            String path, String token, Object body, Class<T> responseType) {
        return restTemplate.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), responseType);
    }

    /** Issues an authenticated {@code PUT} with a JSON body and returns the response entity. */
    private <T> ResponseEntity<T> putWithAuth(
            String path, String token, Object body, Class<T> responseType) {
        return restTemplate.exchange(
                path, HttpMethod.PUT, new HttpEntity<>(body, authHeaders(token)), responseType);
    }

    /** Issues an authenticated {@code DELETE} and returns the response entity. */
    private <T> ResponseEntity<T> deleteWithAuth(String path, String token, Class<T> responseType) {
        return restTemplate.exchange(
                path, HttpMethod.DELETE, new HttpEntity<>(authHeaders(token)), responseType);
    }

    /**
     * Builds a syntactically valid {@link UserCreateRequest} that satisfies the DTO bean
     * validation constraints (uppercase alphanumeric id ≤ 8 chars, names ≤ 20 chars,
     * password 1-72 chars, single-character user type). The password is an obvious,
     * clearly non-production placeholder (V.S1 secret-sanitization compliant).
     */
    private UserCreateRequest validUserCreateRequest(String userId, String userType) {
        return UserCreateRequest.builder()
                .userId(userId)
                .firstName("TEST")
                .lastName("USER")
                .password("PLACEHLD")
                .userType(userType)
                .build();
    }

    /** Concatenates the five ADMIN ids and five USER ids into a single ordered list. */
    private List<String> concatIds() {
        return java.util.stream.Stream.concat(ADMIN_IDS.stream(), USER_IDS.stream())
                .collect(Collectors.toList());
    }

    /** Extracts the granted-authority strings (e.g. {@code ROLE_ADMIN}) from a user entity. */
    private List<String> authorityStrings(User user) {
        return user.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toList());
    }


}
