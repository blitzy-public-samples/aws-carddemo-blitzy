package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.carddemo.dto.SignonRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MockMvc REST-contract test for {@link AuthController} &mdash; the parity gate proving that the
 * legacy CICS sign-on program {@code app/cbl/COSGN00C.cbl} (transaction {@code CC00}) is faithfully
 * re-expressed as the public REST endpoint {@code POST /auth/signon}.
 *
 * <h2>What is being proven (COSGN00C source-of-truth)</h2>
 * <ul>
 *   <li><strong>Empty-field rejects &rarr; HTTP&nbsp;400.</strong> {@code COSGN00C} rejected a blank
 *       user id ({@code "Please enter User ID ..."}) or blank password
 *       ({@code "Please enter Password ..."}); in the migration these become Bean-Validation
 *       ({@code @NotBlank}) failures surfaced as HTTP&nbsp;400 by {@code GlobalExceptionHandler}. The
 *       {@code PIC X(8)} field widths become {@code @Size(max = 8)} (also HTTP&nbsp;400 when
 *       exceeded).</li>
 *   <li><strong>Case-insensitive credentials.</strong> The legacy
 *       {@code FUNCTION UPPER-CASE(USERIDI)} / {@code FUNCTION UPPER-CASE(PASSWDI)} moves
 *       (COSGN00C&nbsp;L132/L135) upper-cased both inputs before the {@code USRSEC} lookup, so a
 *       lower-case user id must still sign on successfully and the returned {@code userId} is the
 *       upper-cased value.</li>
 *   <li><strong>Credential check &rarr; HTTP&nbsp;200 + JWT or HTTP&nbsp;401.</strong> The legacy
 *       plaintext {@code SEC-USR-PWD = WS-USER-PWD} compare (COSGN00C&nbsp;L223) is hardened to a
 *       BCrypt (strength&nbsp;12) verification. A match returns HTTP&nbsp;200 with a signed JWT and the
 *       identity/role hint; a wrong password (L242) or unknown user (L249) both collapse to a single
 *       uniform HTTP&nbsp;401 (no user/credential enumeration).</li>
 *   <li><strong>User-type routing &rarr; {@code userType}/{@code role} fields.</strong> The
 *       {@code CDEMO-USRTYP-ADMIN} ({@code 'A'}) &rarr; {@code COADM01C} vs regular
 *       ({@code 'U'}) &rarr; {@code COMEN01C} {@code XCTL} routing (COSGN00C&nbsp;L230-L238) becomes
 *       the {@code userType} ({@code "A"}/{@code "U"}) and {@code role} ({@code "ADMIN"}/{@code "USER"})
 *       response fields the client uses to route itself.</li>
 *   <li><strong>PII suppression.</strong> The response must <em>never</em> echo the password.</li>
 * </ul>
 *
 * <h2>Test strategy &mdash; the REAL authentication path</h2>
 * <p>This is a full-context {@link SpringBootTest} on the H2 {@code test} profile. Flyway applies
 * {@code V1__schema.sql} .. {@code V4__seed_users.sql} on context startup, seeding exactly two users
 * &mdash; {@code ADMIN001} (type {@code 'A'} &rarr; role {@code ADMIN}) and {@code USER0001}
 * (type {@code 'U'} &rarr; role {@code USER}) &mdash; whose BCrypt(strength&nbsp;12) password hash is
 * that of the literal {@code "PASSWORD"} (README.md&nbsp;L157-158). Nothing is mocked: every test
 * exercises the real {@code AuthController} &rarr; {@code AuthService} &rarr;
 * {@code AuthenticationManager} (BCrypt) &rarr; {@code JwtTokenProvider} path, so a green run proves
 * the wired security stack actually authenticates the seeded users and mints a usable token.</p>
 *
 * <p>The {@code /auth/signon} endpoint is {@code permitAll} in {@code SecurityConfig} and the chain is
 * stateless with CSRF disabled; therefore this class uses <strong>no</strong> {@code @WithMockUser}
 * (the tests authenticate for real) and <strong>no</strong> CSRF post-processor. Sign-on is read-only,
 * so {@code @Transactional} is intentionally absent.</p>
 *
 * @see AuthController
 * @see com.carddemo.service.AuthService
 * @see com.carddemo.dto.SignonRequest
 * @see com.carddemo.dto.SignonResponse
 * @see com.carddemo.security.SecurityConfig
 * @see com.carddemo.security.JwtTokenProvider
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @see <a href="file:app/cbl/COSGN00C.cbl">app/cbl/COSGN00C.cbl (source-of-truth)</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    /**
     * The seeded administrator user id ({@code user_type 'A'} &rarr; role {@code ADMIN}), inserted by
     * {@code V4__seed_users.sql}. Its password is the BCrypt(strength&nbsp;12) hash of {@link #PASSWORD}.
     */
    private static final String ADMIN_ID = "ADMIN001";

    /**
     * The seeded regular user id ({@code user_type 'U'} &rarr; role {@code USER}), inserted by
     * {@code V4__seed_users.sql}. Its password is the BCrypt(strength&nbsp;12) hash of {@link #PASSWORD}.
     */
    private static final String USER_ID = "USER0001";

    /**
     * The cleartext password for <strong>both</strong> seeded users. It is exactly {@code "PASSWORD"}
     * (upper-case), matching the generated BCrypt seed and the legacy case-insensitive semantics
     * (the service upper-cases the supplied password before verification).
     */
    private static final String PASSWORD = "PASSWORD";

    /**
     * Spring-managed MockMvc bound to the full application context (the real security filter chain,
     * controllers, services, and the H2-backed persistence layer are all live).
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Jackson mapper used to build valid request payloads from the {@link SignonRequest} record and to
     * parse response bodies when a test needs the raw token string.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Serializes a {@link SignonRequest} to its JSON wire form for a <em>valid-shape</em> request body.
     *
     * <p>Used for the success and authentication-failure scenarios where the payload is structurally
     * valid (non-null fields). The malformed / constraint-violating scenarios deliberately use raw JSON
     * string literals instead, so the exact bytes (empty string, missing key, over-length value) reach
     * the endpoint unaltered.</p>
     *
     * @param userId   the user id to place in the {@code userId} field
     * @param password the password to place in the {@code password} field
     * @return the JSON representation, e.g. {@code {"userId":"ADMIN001","password":"PASSWORD"}}
     * @throws Exception if JSON serialization fails
     */
    private String json(String userId, String password) throws Exception {
        return objectMapper.writeValueAsString(new SignonRequest(userId, password));
    }

    // =============================================================================================
    // Phase 2 — Success tests (HTTP 200 + signed JWT)
    // =============================================================================================

    /**
     * Admin sign-on with correct credentials returns HTTP&nbsp;200 carrying a non-empty JWT, the
     * {@code "Bearer"} scheme, the upper-cased user id, the legacy user type {@code "A"}, the derived
     * role {@code "ADMIN"}, and <strong>no</strong> password field &mdash; the success branch of
     * {@code COSGN00C} that {@code XCTL}'d to {@code COADM01C}.
     */
    @Test
    @DisplayName("POST /auth/signon — valid admin credentials → 200 with Bearer JWT, userType 'A', role 'ADMIN', no password")
    void signon_validAdmin_returns200WithAdminRoleAndJwt() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(ADMIN_ID, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value("ADMIN001"))
                .andExpect(jsonPath("$.userType").value("A"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * Regular-user sign-on with correct credentials returns HTTP&nbsp;200 with a non-empty JWT, the
     * legacy user type {@code "U"}, and the derived role {@code "USER"} &mdash; the branch of
     * {@code COSGN00C} that {@code XCTL}'d to {@code COMEN01C}.
     */
    @Test
    @DisplayName("POST /auth/signon — valid regular-user credentials → 200 with Bearer JWT, userType 'U', role 'USER'")
    void signon_validRegularUser_returns200WithUserRole() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(USER_ID, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * A lower-case user id still signs on and the returned {@code userId} is upper-cased &mdash; the
     * direct parity proof of {@code COSGN00C}'s {@code FUNCTION UPPER-CASE(USERIDI)} normalization
     * (the lookup key is upper-cased before the {@code USRSEC}/{@code users} read). The password
     * {@code "PASSWORD"} is already upper-case.
     */
    @Test
    @DisplayName("POST /auth/signon — lower-case user id is upper-cased before lookup → 200, userId 'ADMIN001' (COSGN00C UPPER-CASE parity)")
    void signon_lowercaseUserId_isUppercased_returns200() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("admin001", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("ADMIN001"))
                .andExpect(jsonPath("$.userType").value("A"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    /**
     * The success response must never echo the credential back to the client (PII rule, AAP
     * &sect;0.6.8 / &sect;0.7.1). Captures the raw response body and asserts it contains neither the
     * upper-case nor lower-case form of the password.
     */
    @Test
    @DisplayName("POST /auth/signon — successful response body never contains the password (PII suppression)")
    void signon_responseNeverContainsPassword() throws Exception {
        String body = mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(ADMIN_ID, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain(PASSWORD)
                .doesNotContain(PASSWORD.toLowerCase());
    }

    /**
     * The issued token is a structurally well-formed JWS: three non-empty dot-separated segments
     * (header.payload.signature). This is a lightweight HS256 structural check that does not decode the
     * token or assert the signing secret.
     */
    @Test
    @DisplayName("POST /auth/signon — issued token is a well-formed 3-segment JWT (header.payload.signature)")
    void signon_tokenIsWellFormedJwt() throws Exception {
        String body = mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(ADMIN_ID, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readTree(body).get("token").asText();

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(token.split("\\.")).allSatisfy(segment -> assertThat(segment).isNotEmpty());
    }

    // =============================================================================================
    // Phase 3 — Authentication-failure tests (HTTP 401)
    // =============================================================================================

    /**
     * A correct, existing user id with the wrong password yields HTTP&nbsp;401 &mdash; the BCrypt
     * re-expression of {@code COSGN00C}'s {@code "Wrong Password. Try again ..."} branch (L242). The
     * wrong password {@code "BADPASS"} is 7 characters (&le;&nbsp;8) so it passes the {@code @Size}
     * constraint and actually reaches the {@code AuthenticationManager} (a 401), rather than being
     * short-circuited as a 400.
     */
    @Test
    @DisplayName("POST /auth/signon — known user, wrong password → 401 (COSGN00C 'Wrong Password' branch)")
    void signon_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(ADMIN_ID, "BADPASS")))
                .andExpect(status().isUnauthorized());
    }

    /**
     * An unknown (un-seeded) user id yields HTTP&nbsp;401 &mdash; the re-expression of
     * {@code COSGN00C}'s {@code "User not found. Try again ..."} branch (L249, {@code RESP=13}). The
     * user id {@code "NOSUCH"} is 6 characters (&le;&nbsp;8) so it reaches authentication. Both the
     * wrong-password and unknown-user paths intentionally collapse to the same uniform 401 so the API
     * never leaks which field was wrong.
     */
    @Test
    @DisplayName("POST /auth/signon — unknown user → 401 (COSGN00C 'User not found' branch; uniform with wrong-password)")
    void signon_unknownUser_returns401() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("NOSUCH", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    // =============================================================================================
    // Phase 4 — Validation-failure tests (HTTP 400)
    //
    // These use raw JSON string literals (not the SignonRequest record) so the malformed values
    // (empty string, absent key, over-length value) reach the endpoint exactly as written and trip
    // the @NotBlank / @Size(max = 8) constraints, which GlobalExceptionHandler maps to HTTP 400.
    // =============================================================================================

    /**
     * A blank user id violates {@code @NotBlank} &rarr; HTTP&nbsp;400 &mdash; the re-expression of
     * {@code COSGN00C}'s {@code "Please enter User ID ..."} empty-field reject (L120).
     */
    @Test
    @DisplayName("POST /auth/signon — blank userId → 400 (@NotBlank; COSGN00C 'Please enter User ID')")
    void signon_blankUserId_returns400() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"\",\"password\":\"PASSWORD\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A blank password violates {@code @NotBlank} &rarr; HTTP&nbsp;400 &mdash; the re-expression of
     * {@code COSGN00C}'s {@code "Please enter Password ..."} empty-field reject (L125).
     */
    @Test
    @DisplayName("POST /auth/signon — blank password → 400 (@NotBlank; COSGN00C 'Please enter Password')")
    void signon_blankPassword_returns400() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"ADMIN001\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * An empty JSON object omits both required fields, so both {@code @NotBlank} constraints fail
     * &rarr; HTTP&nbsp;400 (both legacy empty-field rejects at once).
     */
    @Test
    @DisplayName("POST /auth/signon — missing both fields ({}) → 400 (both @NotBlank fail)")
    void signon_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A 9-character user id exceeds {@code @Size(max = 8)} &rarr; HTTP&nbsp;400, enforcing the legacy
     * {@code USERID PIC X(8)} field width.
     */
    @Test
    @DisplayName("POST /auth/signon — userId longer than 8 chars → 400 (@Size(max=8); PIC X(8) width)")
    void signon_userIdTooLong_returns400() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"TOOLONG12\",\"password\":\"PASSWORD\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A 9-character password exceeds {@code @Size(max = 8)} &rarr; HTTP&nbsp;400, enforcing the legacy
     * {@code PASSWD PIC X(8)} field width.
     */
    @Test
    @DisplayName("POST /auth/signon — password longer than 8 chars → 400 (@Size(max=8); PIC X(8) width)")
    void signon_passwordTooLong_returns400() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"ADMIN001\",\"password\":\"PASSWORD9\"}"))
                .andExpect(status().isBadRequest());
    }
}
