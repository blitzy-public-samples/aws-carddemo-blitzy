package com.carddemo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * End-to-end <strong>security parity gate</strong> for the COBOL&rarr;Java CardDemo migration: it
 * proves that the net-new Spring Security stack &mdash; a <strong>stateless HS256 JWT filter chain
 * backed by BCrypt&nbsp;(strength&nbsp;12)</strong> &mdash; faithfully reproduces the behaviour of
 * the legacy CICS sign-on program {@code app/cbl/COSGN00C.cbl} together with the COMMAREA
 * session-hand-off model {@code app/cpy/COCOM01Y.cpy}, while hardening credential storage
 * (plaintext compare &rarr; BCrypt) and session transport (COMMAREA/{@code XCTL} &rarr; stateless
 * JWT). Authority: AAP &sect;0.2.1.3 (lists {@code SecurityTest} among the mandated test classes),
 * &sect;0.6.7 (COMMAREA&rarr;JWT and the security upgrade), &sect;0.3.2 (stateless authentication
 * design), and &sect;0.7.1/&sect;0.7.2 (MUST-rules and the &ge;80% JaCoCo coverage gate).
 *
 * <h2>Legacy behaviour pinned down (the source-of-truth authority)</h2>
 * <p>{@code COSGN00C} received a user id and password, <strong>upper-cased both</strong> with
 * {@code FUNCTION UPPER-CASE} (source lines&nbsp;132-136), read the {@code USRSEC} security file by
 * id, and on a hit compared the stored {@code SEC-USR-PWD} to the entered password
 * (<em>plaintext</em>; here replaced by a BCrypt verification). On success it populated the COMMAREA
 * identity ({@code CDEMO-USER-ID}, {@code CDEMO-USER-TYPE}) and routed by user type &mdash; type
 * {@code 'A'} (admin) to {@code COADM01C}, type {@code 'U'} (regular) to {@code COMEN01C} (source
 * lines&nbsp;209-257). A password mismatch produced {@code "Wrong Password. Try again ..."} and a
 * missing record ({@code RESP=13}) produced {@code "User not found. Try again ..."}; both collapse
 * to HTTP&nbsp;401 in the migration.</p>
 *
 * <h2>COMMAREA&rarr;JWT claim contract ({@code app/cpy/COCOM01Y.cpy})</h2>
 * <p>The five COMMAREA session fields that the legacy app threaded program-to-program on every
 * {@code XCTL} become JWT claims (set by {@link JwtTokenProvider}, re-materialized into the
 * {@code SecurityContext} on every request by {@link JwtAuthenticationFilter}):
 * {@code CDEMO-USER-ID} {@code PIC X(08)} &rarr; subject &amp; {@value JwtTokenProvider#CLAIM_USER_ID};
 * {@code CDEMO-USER-TYPE} {@code PIC X(01)} (88-levels {@code 'A'}/{@code 'U'}) &rarr;
 * {@value JwtTokenProvider#CLAIM_USER_TYPE}; {@code CDEMO-CUST-ID} {@code PIC 9(09)} &rarr;
 * {@value JwtTokenProvider#CLAIM_CUST_ID}; {@code CDEMO-ACCT-ID} {@code PIC 9(11)} &rarr;
 * {@value JwtTokenProvider#CLAIM_ACCT_ID}; {@code CDEMO-CARD-NUM} {@code PIC 9(16)} &rarr;
 * {@value JwtTokenProvider#CLAIM_CARD_NUM}. The latter three are absent at sign-on time.</p>
 *
 * <h2>What this test exercises together</h2>
 * <p>Running under {@link SpringBootTest} with the {@code test} profile (in-memory H2 in PostgreSQL
 * mode; Flyway {@code V1}&ndash;{@code V4} applied on context startup, so the generated users
 * {@code ADMIN001}/{@code USER0001} exist with BCrypt&nbsp;(12) hashes of {@code "PASSWORD"}; batch
 * jobs are <em>not</em> auto-run), it drives real HTTP requests through {@link MockMvc} and the full
 * Spring Security filter chain. The end-to-end gating tests use <strong>real JWTs minted by
 * {@code POST /auth/signon}</strong> &mdash; the whole point being to prove BCrypt-12 verification,
 * JWT issuance, {@link JwtAuthenticationFilter}, and {@link SecurityConfig} all cooperate. The
 * asserted accept/reject matrix is: sign-on 200 (admin&nbsp;+&nbsp;user), wrong-password 401,
 * unknown-user 401, no-token 401, malformed/tampered-token 401, user-on-admin 403, admin-on-admin
 * 200, and public sign-on reachable without a token. It additionally pins the COMMAREA&rarr;JWT
 * five-claim mapping, the {@code 'A'&rarr;ROLE_ADMIN}/{@code 'U'&rarr;ROLE_USER} mapping, and the
 * BCrypt strength-12 storage hardening.</p>
 *
 * @see <a href="file:app/cbl/COSGN00C.cbl">app/cbl/COSGN00C.cbl (sign-on source-of-truth)</a>
 * @see <a href="file:app/cpy/COCOM01Y.cpy">app/cpy/COCOM01Y.cpy (COMMAREA source-of-truth)</a>
 * @see JwtTokenProvider
 * @see SecurityConfig
 * @see JwtAuthenticationFilter
 * @see CustomUserDetailsService
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Security parity: COSGN00C sign-on + COCOM01Y COMMAREA -> stateless HS256 JWT / BCrypt-12")
public class SecurityTest {

    /** Seeded administrator id ({@code V4__seed_users.sql}, user type {@code 'A'}). 8 chars. */
    private static final String ADMIN_ID = "ADMIN001";

    /** Seeded regular-user id ({@code V4__seed_users.sql}, user type {@code 'U'}). 8 chars. */
    private static final String USER_ID = "USER0001";

    /**
     * The seeded clear-text credential (README.md L157-158). Exactly 8 characters, so it passes the
     * {@code @Size(max = 8)} sign-on validation, and it is stored upper-cased as a BCrypt&nbsp;(12)
     * hash &mdash; matching {@code AuthService}'s upper-casing of the entered password.
     */
    private static final String PASSWORD = "PASSWORD";

    /** Performs real HTTP requests through the full Spring Security filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Builds JSON request bodies and parses the {@code SignonResponse} token field. */
    @Autowired
    private ObjectMapper objectMapper;

    /** The production token provider under test (same package &mdash; no import required). */
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    /** The application's single {@code PasswordEncoder} bean &mdash; {@code BCryptPasswordEncoder(12)}. */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Repository used to read the seeded {@code ADMIN001}/{@code USER0001} rows and their hashes. */
    @Autowired
    private UserRepository userRepository;

    /**
     * The HS256 signing secret, injected so the test can decode a token <em>independently</em> with
     * JJWT (proving the configured key actually signs the token) without relying on the optional
     * {@code getCustId}/{@code getAcctId}/{@code getCardNum} accessors.
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    /**
     * Performs a real {@code POST /auth/signon} for the given credentials, asserts HTTP&nbsp;200,
     * and returns the issued JWT from the {@code SignonResponse.token} field.
     *
     * <p>The request body is built from a {@link Map} rather than the {@code SignonRequest} record
     * constructor so the test does not couple to the record's component order.</p>
     *
     * @param userId   the user id to sign on with (kept &le; 8 characters by callers)
     * @param password the password to sign on with (kept &le; 8 characters by callers)
     * @return the compact JWT string minted by {@code AuthService}/{@code JwtTokenProvider}
     * @throws Exception if the MockMvc exchange fails
     */
    private String signonAndGetToken(String userId, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("userId", userId, "password", password));
        MvcResult result = mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    /**
     * Verifies and decodes a token <em>independently</em> of {@link JwtTokenProvider}, using the
     * injected {@code jwt.secret}. This both proves the token was HS256-signed with the configured
     * key and exposes every claim for assertion without depending on optional getter methods.
     *
     * @param token the compact JWT string
     * @return the verified {@link Claims} payload
     */
    private Claims decodeClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /**
     * Returns a copy of {@code token} whose signature is corrupted so that verification must fail.
     *
     * <p>The <strong>first</strong> character of the signature segment is flipped (not the last):
     * for an HMAC-SHA256 (32-byte / 256-bit) signature the final base64url character carries only
     * the four high bits of the last byte, so flipping the <em>last</em> character can decode to the
     * identical signature bytes and leave the token valid. The first character's six bits are always
     * significant, so flipping it is guaranteed to change the decoded signature and force a
     * verification failure.</p>
     *
     * @param token a valid compact JWT
     * @return the same header/payload with a single-character-corrupted signature
     */
    private static String tamperSignature(String token) {
        int lastDot = token.lastIndexOf('.');
        String headerAndPayload = token.substring(0, lastDot + 1);
        String signature = token.substring(lastDot + 1);
        char first = signature.charAt(0);
        char flipped = (first == 'A') ? 'B' : 'A';
        return headerAndPayload + flipped + signature.substring(1);
    }

    /**
     * Mints a structurally valid JWT that is <strong>already expired</strong>, signed with the SAME
     * configured HS256 secret ({@code jwt.secret}) the production {@link JwtTokenProvider} uses. The
     * token carries the COMMAREA-equivalent subject and user-type claims, so it is well-formed in every
     * respect EXCEPT its temporal validity: {@code iat} is two hours ago and {@code exp} is one hour
     * ago. Verification therefore fails on <em>expiry</em> &mdash; not on signature or structure &mdash;
     * isolating the "correctly-signed but lapsed lifetime" case that the {@code exp}-in-the-future
     * sanity assertions ({@code commareaFields_mapToJwtClaims}) cannot prove.
     *
     * @param userId   the subject / {@code CDEMO-USER-ID} claim (kept &le; 8 chars by callers)
     * @param userType the {@code CDEMO-USER-TYPE} claim ({@code 'A'} admin / {@code 'U'} user)
     * @return a compact HS256 JWT whose {@code exp} is in the past
     */
    private String mintExpiredToken(String userId, String userType) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim(JwtTokenProvider.CLAIM_USER_ID, userId)
                .claim(JwtTokenProvider.CLAIM_USER_TYPE, userType)
                .issuedAt(new Date(now - 7_200_000L))    // issued 2 hours ago
                .expiration(new Date(now - 3_600_000L))  // expired 1 hour ago
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Asserts the <strong>stateless</strong> contract (AAP &sect;0.6.7 / &sect;0.3.2: the legacy
     * COMMAREA/{@code XCTL} session hand-off is replaced by a stateless JWT): a completed exchange must
     * neither establish a server-side HTTP session nor emit a {@code JSESSIONID} session cookie, because
     * {@code SecurityConfig} runs with
     * {@link org.springframework.security.config.http.SessionCreationPolicy#STATELESS}.
     *
     * @param result a completed MockMvc exchange to inspect
     */
    private static void assertNoSessionCreated(MvcResult result) {
        // No JSESSIONID cookie is set on the response.
        assertThat(result.getResponse().getCookie("JSESSIONID"))
                .as("stateless: no JSESSIONID cookie may be emitted")
                .isNull();
        // No Set-Cookie response header advertises a session cookie.
        assertThat(result.getResponse().getHeaders("Set-Cookie"))
                .as("stateless: no Set-Cookie header may reference JSESSIONID")
                .noneMatch(header -> header.contains("JSESSIONID"));
        // No server-side HTTP session was created (STATELESS policy: nothing calls request.getSession()).
        assertThat(result.getRequest().getSession(false))
                .as("stateless: no HTTP session may be created")
                .isNull();
    }

    // ==================================================================
    // Group 2 - Public sign-on + JWT issuance (BCrypt-12 + role routing parity).
    // ==================================================================

    /**
     * {@code POST /auth/signon} is reachable WITHOUT a token (parity with the public legacy
     * {@code CC00} sign-on screen) and, for {@code ADMIN001}/{@code PASSWORD}, succeeds via the
     * BCrypt&nbsp;(12) verification that replaces {@code COSGN00C}'s plaintext compare, issuing a
     * JWT and surfacing the admin identity/role. The response must never leak a password.
     */
    @Test
    @DisplayName("POST /auth/signon is public and issues a JWT for the admin user")
    void signon_isPublic_andIssuesJwt_forAdmin() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("userId", ADMIN_ID, "password", PASSWORD));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value(ADMIN_ID))
                .andExpect(jsonPath("$.userType").value("A"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                // Defensive PII guard: the response must not echo any password field.
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * {@code USER0001}/{@code PASSWORD} signs on successfully and is surfaced as the legacy regular
     * user type {@code 'U'} (which would route to {@code COMEN01C}; here mapped to {@code ROLE_USER}).
     */
    @Test
    @DisplayName("POST /auth/signon issues a JWT for a regular user with user type 'U'")
    void signon_issuesJwt_forRegularUser_withUserType() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("userId", USER_ID, "password", PASSWORD));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    /**
     * The token returned by sign-on is valid and carries the COMMAREA-equivalent subject
     * ({@code CDEMO-USER-ID}) and user type ({@code CDEMO-USER-TYPE}).
     */
    @Test
    @DisplayName("Sign-on token validates and carries the subject + user type")
    void signon_returnedToken_isValid_andCarriesSubjectAndType() throws Exception {
        String token = signonAndGetToken(ADMIN_ID, PASSWORD);

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getUserId(token)).isEqualTo(ADMIN_ID);
        assertThat(jwtTokenProvider.getUserType(token)).isEqualTo("A");
    }

    // ==================================================================
    // Group 3 - Authentication enforcement (401/400 paths; stateless Bearer).
    // ==================================================================

    /**
     * A protected endpoint ({@code GET /menu}) is reachable with a valid bearer token: the
     * {@link JwtAuthenticationFilter} parses {@code Authorization: Bearer <jwt>} into the
     * {@code SecurityContext} (the stateless replacement for COMMAREA propagation).
     */
    @Test
    @DisplayName("GET /menu with a valid Bearer token -> 200")
    void protectedEndpoint_withValidBearer_returns200() throws Exception {
        String token = signonAndGetToken(USER_ID, PASSWORD);

        mockMvc.perform(get("/menu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /**
     * A protected endpoint with no token is rejected by the inline {@code AuthenticationEntryPoint}
     * with HTTP&nbsp;401.
     */
    @Test
    @DisplayName("GET /menu without a token -> 401")
    void protectedEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/menu"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A structurally malformed bearer value cannot establish a principal: {@code validateToken}
     * returns {@code false} (never throwing) and the request is rejected with HTTP&nbsp;401.
     */
    @Test
    @DisplayName("GET /menu with a malformed token -> 401")
    void protectedEndpoint_withMalformedToken_returns401() throws Exception {
        mockMvc.perform(get("/menu").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A genuine JWT whose signature has been corrupted fails verification: the filter establishes no
     * principal and the request is rejected with HTTP&nbsp;401. The signature failure is swallowed by
     * {@code validateToken} (which returns {@code false} rather than throwing).
     */
    @Test
    @DisplayName("GET /menu with a tampered-signature token -> 401")
    void protectedEndpoint_withTamperedToken_returns401() throws Exception {
        String validToken = signonAndGetToken(ADMIN_ID, PASSWORD);
        String tampered = tamperSignature(validToken);

        // The corruption must actually invalidate the token (guards against a no-op tamper).
        assertThat(tampered).isNotEqualTo(validToken);
        assertThat(jwtTokenProvider.validateToken(tampered)).isFalse();

        mockMvc.perform(get("/menu").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A genuine, correctly-signed JWT whose lifetime has lapsed ({@code exp} before {@code now}) is
     * rejected with HTTP&nbsp;401: {@link JwtAuthenticationFilter} establishes no principal because
     * {@link JwtTokenProvider#validateToken(String)} returns {@code false} for an expired token (JJWT's
     * {@code ExpiredJwtException} is swallowed by {@code validateToken}, never thrown). This proves the
     * stateless JWT's <strong>bounded lifetime is actually enforced</strong> &mdash; the temporal
     * counterpart to the structural malformed/tampered rejections above, and the negative case the
     * {@code exp}-in-the-future sanity assertions cannot cover. The token is minted with the configured
     * {@code jwt.secret}, so the failure is unambiguously expiry (not a signature or structural defect).
     */
    @Test
    @DisplayName("GET /menu with an expired (but correctly-signed) token -> 401")
    void protectedEndpoint_withExpiredToken_returns401() throws Exception {
        String expired = mintExpiredToken(USER_ID, "U");

        // Sanity: the token is correctly signed with the configured key but past its exp, so the
        // provider must reject it on EXPIRY (validateToken swallows ExpiredJwtException -> false).
        assertThat(jwtTokenProvider.validateToken(expired)).isFalse();

        mockMvc.perform(get("/menu").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The migration replaces the CICS COMMAREA/{@code XCTL} session hand-off with a STATELESS JWT
     * filter chain (AAP &sect;0.6.7 / &sect;0.3.2), so the server must never create an HTTP session:
     * neither the public token-issuing {@code POST /auth/signon} nor a subsequent
     * bearer-authenticated protected request ({@code GET /menu}) may emit a {@code JSESSIONID} cookie or
     * a session {@code Set-Cookie} header, and no server-side {@code HttpSession} may be created. This
     * pins {@code SecurityConfig}'s {@code SessionCreationPolicy.STATELESS} at the HTTP boundary &mdash;
     * the transport-level guarantee that identity is carried solely by the JWT, exactly as the legacy
     * COMMAREA carried it from program to program.
     */
    @Test
    @DisplayName("Sign-on and bearer-authenticated requests are stateless (no JSESSIONID / no session)")
    void statelessAuth_emitsNoSessionCookie_andCreatesNoSession() throws Exception {
        // (1) Sign-on (public, token-issuing) must not start a session or emit a session cookie.
        String body = objectMapper.writeValueAsString(Map.of("userId", USER_ID, "password", PASSWORD));
        MvcResult signon = mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertNoSessionCreated(signon);

        // (2) A bearer-authenticated protected request must likewise remain stateless.
        String token = objectMapper.readTree(signon.getResponse().getContentAsString()).get("token").asText();
        MvcResult protectedCall = mockMvc.perform(get("/menu").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertNoSessionCreated(protectedCall);
    }

    /**
     * A correct user with a wrong password is rejected with HTTP&nbsp;401 &mdash; the migrated form
     * of {@code COSGN00C}'s {@code "Wrong Password. Try again ..."} (now a BCrypt mismatch). The
     * 8-character {@code "WRONGPWD"} passes {@code @Size(max = 8)} so authentication is actually
     * reached (a longer value would fail validation with 400 instead).
     */
    @Test
    @DisplayName("POST /auth/signon with a wrong password -> 401")
    void signon_wrongPassword_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("userId", ADMIN_ID, "password", "WRONGPWD"));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    /**
     * An unknown user is rejected with HTTP&nbsp;401 &mdash; the migrated form of {@code COSGN00C}'s
     * {@code "User not found. Try again ..."} ({@code RESP=13}). The 8-character {@code "NOUSER99"}
     * passes {@code @Size(max = 8)} so the lookup/authentication path is reached.
     */
    @Test
    @DisplayName("POST /auth/signon with an unknown user -> 401")
    void signon_unknownUser_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("userId", "NOUSER99", "password", PASSWORD));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A blank {@code userId} violates the {@code @NotBlank} constraint on {@code SignonRequest},
     * raising {@code MethodArgumentNotValidException} which {@code GlobalExceptionHandler} maps to
     * HTTP&nbsp;400 &mdash; the migrated form of {@code COSGN00C}'s {@code "Please enter User ID ..."}
     * empty-field reject (validation precedes authentication).
     */
    @Test
    @DisplayName("POST /auth/signon with a blank userId -> 400")
    void signon_blankUserId_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("userId", "", "password", PASSWORD));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ==================================================================
    // Group 4 - Authorization / role gating (403 paths; admin-menu COADM01C parity).
    // ==================================================================

    /**
     * A regular user ({@code ROLE_USER}) is forbidden from the admin-only surfaces: both the
     * {@code /users/**} URL rule in {@link SecurityConfig} and the method-security
     * {@code @PreAuthorize("hasRole('ADMIN')")} guards deny with HTTP&nbsp;403, reproducing the
     * legacy {@code COADM01C} admin-only reachability ({@code CDEMO-USRTYP-ADMIN}).
     */
    @Test
    @DisplayName("Admin-only endpoints with a USER token -> 403")
    void adminEndpoint_withUserRole_returns403() throws Exception {
        String userToken = signonAndGetToken(USER_ID, PASSWORD);

        // URL-rule gate (/users/** hasRole ADMIN).
        mockMvc.perform(get("/users").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // Method-security gate (@PreAuthorize on GET /admin/menu).
        mockMvc.perform(get("/admin/menu").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /**
     * An administrator ({@code ROLE_ADMIN}) is granted the admin-only listing: {@code GET /users}
     * returns HTTP&nbsp;200 in the seeded H2 database. This proves the {@code 'A' -> ROLE_ADMIN}
     * mapping of {@code CDEMO-USER-TYPE}.
     */
    @Test
    @DisplayName("GET /users with an ADMIN token -> 200")
    void adminEndpoint_withAdminRole_returns200() throws Exception {
        String adminToken = signonAndGetToken(ADMIN_ID, PASSWORD);

        mockMvc.perform(get("/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    /**
     * Authentication is enforced before authorization: an unauthenticated request to the admin-only
     * {@code /users} surface yields HTTP&nbsp;401 (not 403).
     */
    @Test
    @DisplayName("GET /users without a token -> 401")
    void adminEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // Group 5 - COMMAREA -> JWT claim mapping (the AAP 0.6.7 parity core).
    // ==================================================================

    /**
     * Generates a token carrying the full COMMAREA session via the five-argument overload and
     * decodes it independently with JJWT (proving HS256 signing with the configured key), then
     * asserts all five {@code COCOM01Y} fields mapped to their claims. Numeric claims are read via
     * {@link Number#longValue()} because JJWT/Jackson may deserialize them as {@code Integer} or
     * {@code Long} depending on magnitude.
     */
    @Test
    @DisplayName("All five COMMAREA fields map to JWT claims")
    void commareaFields_mapToJwtClaims() {
        long custId = 123456789L;       // CDEMO-CUST-ID  PIC 9(09)
        long acctId = 12345678901L;     // CDEMO-ACCT-ID  PIC 9(11)
        String cardNum = "1234567890123456"; // CDEMO-CARD-NUM PIC 9(16)

        String token = jwtTokenProvider.generateToken(ADMIN_ID, "A", custId, acctId, cardNum);
        Claims claims = decodeClaims(token);

        assertThat(claims.getSubject()).isEqualTo(ADMIN_ID);
        assertThat(claims.get(JwtTokenProvider.CLAIM_USER_ID, String.class)).isEqualTo(ADMIN_ID);
        assertThat(claims.get(JwtTokenProvider.CLAIM_USER_TYPE, String.class)).isEqualTo("A");
        assertThat(((Number) claims.get(JwtTokenProvider.CLAIM_CUST_ID)).longValue()).isEqualTo(custId);
        assertThat(((Number) claims.get(JwtTokenProvider.CLAIM_ACCT_ID)).longValue()).isEqualTo(acctId);
        assertThat(claims.get(JwtTokenProvider.CLAIM_CARD_NUM, String.class)).isEqualTo(cardNum);

        // Expiry sanity: in the future and after issuance (no hardcoded duration so an env override
        // of jwt.expiration-ms cannot make this brittle).
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    /**
     * A token minted by the two-argument overload (the sign-on contract) is valid, carries the user
     * type {@code 'U'}, and <em>omits</em> the optional customer/account/card claims entirely (they
     * are never serialized as JSON {@code null}), matching the COMMAREA state right after sign-on.
     */
    @Test
    @DisplayName("Token without optional claims is valid and omits cust/acct/card")
    void tokenWithoutOptionalClaims_stillValid_andTypeUserMapsToUserRole() {
        String token = jwtTokenProvider.generateToken(USER_ID, "U");

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();

        Claims claims = decodeClaims(token);
        assertThat(claims.getSubject()).isEqualTo(USER_ID);
        assertThat(claims.get(JwtTokenProvider.CLAIM_USER_TYPE, String.class)).isEqualTo("U");
        assertThat(claims.get(JwtTokenProvider.CLAIM_CUST_ID)).isNull();
        assertThat(claims.get(JwtTokenProvider.CLAIM_ACCT_ID)).isNull();
        assertThat(claims.get(JwtTokenProvider.CLAIM_CARD_NUM)).isNull();
    }

    // ==================================================================
    // Group 6 - BCrypt strength-12 verification (credential-storage hardening).
    // ==================================================================

    /**
     * The application {@code PasswordEncoder} is BCrypt at cost factor 12: a freshly encoded value
     * carries the {@code $2[aby]$12$} prefix and round-trips through {@code matches} (true for the
     * original, false for a different password).
     */
    @Test
    @DisplayName("PasswordEncoder is BCrypt strength 12 and round-trips")
    void passwordEncoder_isBcryptStrength12() {
        String hash = passwordEncoder.encode(PASSWORD);

        assertThat(hash).matches("^\\$2[aby]\\$12\\$.*");
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
        assertThat(passwordEncoder.matches("WRONGPWD", hash)).isFalse();
    }

    /**
     * The seeded {@code ADMIN001} credential is stored as a BCrypt&nbsp;(12) hash &mdash; NOT
     * plaintext &mdash; proving {@code COSGN00C}'s {@code SEC-USR-PWD = WS-USER-PWD} plaintext
     * comparison was replaced, and the upper-cased {@code "PASSWORD"} verifies against it. The
     * seeded user type is the admin code {@code 'A'}; {@code USER0001} is the regular code {@code 'U'}.
     */
    @Test
    @DisplayName("Seeded ADMIN001/USER0001 passwords are BCrypt-12 and verify")
    void seededAdminPassword_isBcrypted_andVerifies() {
        User admin = userRepository.findById(ADMIN_ID).orElseThrow();
        assertThat(admin.getPassword()).matches("^\\$2[aby]\\$12\\$.*");
        assertThat(passwordEncoder.matches(PASSWORD, admin.getPassword())).isTrue();
        assertThat(admin.getUserType()).isEqualTo("A");

        User user = userRepository.findById(USER_ID).orElseThrow();
        assertThat(user.getPassword()).matches("^\\$2[aby]\\$12\\$.*");
        assertThat(passwordEncoder.matches(PASSWORD, user.getPassword())).isTrue();
        assertThat(user.getUserType()).isEqualTo("U");
    }

    // ==================================================================
    // Group 7 - Upper-case normalization parity with COSGN00C (lines 132-134).
    // ==================================================================

    /**
     * Sign-on is case-insensitive on the user id, exactly as {@code COSGN00C}'s
     * {@code FUNCTION UPPER-CASE(USERIDI)} (source lines 132-134): a lower-case {@code "admin001"}
     * authenticates and the response surfaces the canonical upper-cased {@code "ADMIN001"}. The
     * password is left upper-case ({@code "PASSWORD"}) because the service also upper-cases the
     * password before BCrypt verification, so an already-upper-case value is unaffected.
     */
    @Test
    @DisplayName("Sign-on upper-cases the user id (COSGN00C FUNCTION UPPER-CASE parity)")
    void signon_isCaseInsensitiveOnUserId_matchingCobolUpperCase() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("userId", "admin001", "password", PASSWORD));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ADMIN_ID))
                .andExpect(jsonPath("$.userType").value("A"));
    }
}
