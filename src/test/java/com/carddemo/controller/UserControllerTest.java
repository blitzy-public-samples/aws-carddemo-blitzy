package com.carddemo.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MockMvc REST-contract integration test for {@link UserController} &mdash; the mandated
 * {@code UserControllerTest} of <strong>AAP &sect;0.2.1.3</strong> (Validation Framework per-program
 * test classes) and <strong>&sect;0.4.1.4</strong> ({@code UserController &larr; COUSR00C..COUSR03C};
 * <em>"Admin-only CRUD; {@code @PreAuthorize(\"hasRole('ADMIN')\")}"</em>).
 *
 * <p>It proves that the four legacy admin-only CICS user-maintenance online programs are faithfully
 * re-expressed as a stateless JSON CRUD contract whose 3270 presentation tier
 * ({@code COUSR00}&ndash;{@code COUSR03} BMS maps) has been retired, while preserving every
 * cross-cutting rule the migration must keep intact:</p>
 * <ul>
 *   <li>{@code app/cbl/COUSR00C.cbl} (transaction {@code CU00}, <em>List Users</em>) &rarr;
 *       {@code GET /users}. The legacy program browsed the {@code USRSEC} file
 *       ({@code STARTBR}/{@code READNEXT} for PF8 forward, {@code READPREV} for PF7 backward),
 *       rendering a fixed window of rows per screen. That fixed window is standardized to the
 *       migration-wide page size of <strong>7</strong> (AAP &sect;0.6.4 / &sect;0.7.1 &mdash; AAP
 *       governs over the legacy 10-per-screen literal), asserted here as {@code $.size == 7}.</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} (transaction {@code CU01}, <em>Add User</em>) &rarr;
 *       {@code POST /users}. The legacy program required all five fields (First&nbsp;Name,
 *       Last&nbsp;Name, User&nbsp;ID, Password, User&nbsp;Type), rejecting any empty field with a
 *       "... can NOT be empty..." message and a duplicate key with "User ID already exist...". These
 *       become Bean-Validation failures (HTTP&nbsp;400) and a {@code BusinessRuleException}
 *       (HTTP&nbsp;400) respectively; success returns HTTP&nbsp;<strong>201&nbsp;Created</strong>.</li>
 *   <li>{@code app/cbl/COUSR02C.cbl} (transaction {@code CU02}, <em>Update User</em>) &rarr;
 *       {@code PUT /users/{userId}}. The legacy program read the record by id (rejecting a missing
 *       record with "User ID NOT found...") and rewrote the editable name / password / type fields.
 *       A missing record becomes HTTP&nbsp;404; success returns HTTP&nbsp;200.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} (transaction {@code CU03}, <em>Delete User</em>) &rarr;
 *       {@code DELETE /users/{userId}}. The legacy program read the record by id
 *       ("User ID NOT found..." when absent) and, after a PF5 confirmation, deleted it. A missing
 *       record becomes HTTP&nbsp;404; success returns HTTP&nbsp;<strong>204&nbsp;No&nbsp;Content</strong>
 *       with no body.</li>
 * </ul>
 *
 * <h2>The headline parity gate &mdash; admin-only access</h2>
 * <p>All four legacy programs were reachable only through the administrator menu ({@code COADM01C}).
 * In the migrated system that restriction is the class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} on {@link UserController} (enforced by
 * {@code @EnableMethodSecurity}) plus a defense-in-depth {@code /users/**} &rarr; {@code hasRole('ADMIN')}
 * rule in {@code SecurityConfig}'s filter chain. This test makes the authorization distinction its
 * core assertion: an authenticated <em>non-admin</em> caller is rejected with HTTP&nbsp;403
 * (the {@code AccessDeniedException} path), while an <em>unauthenticated</em> caller is rejected with
 * HTTP&nbsp;401 (the stateless entry-point path).</p>
 *
 * <h2>PII suppression is structural</h2>
 * <p>{@link com.carddemo.dto.UserResponse} declares no password component at all, so a credential
 * (plaintext or BCrypt hash) can never be serialized into any response shape. Every response asserted
 * here &mdash; list rows, single-user GET, POST create, PUT update &mdash; is checked with
 * {@code jsonPath(...).doesNotExist()}, and the list body is additionally scanned to confirm it
 * carries neither the literal {@code "password"} key nor a BCrypt {@code "$2"} hash prefix
 * (AAP &sect;0.6.8 / &sect;0.7.1).</p>
 *
 * <h2>Harness &amp; house style</h2>
 * <p>Full {@code @SpringBootTest} integration against the seeded H2 {@code test} profile: Flyway applies
 * {@code V1__schema.sql}&hellip;{@code V4__seed_users.sql} on context startup, seeding exactly
 * <strong>two</strong> users &mdash; {@code ADMIN001} (type {@code 'A'} &rarr; role {@code ADMIN}) and
 * {@code USER0001} (type {@code 'U'} &rarr; role {@code USER}). The exact seed count makes
 * {@code totalElements == 2} and a single full page (size&nbsp;7) deterministic. The class is
 * {@code @Transactional} so the mutating {@code POST}/{@code PUT}/{@code DELETE} tests roll back,
 * restoring the two seed users after each method. The CRUD tests run as the seeded administrator via
 * the class-level {@code @WithMockUser(username = "ADMIN001", roles = {"ADMIN"})}; the negative
 * authorization tests override that context per-method ({@code @WithMockUser(roles = {"USER"})} for the
 * 403 cases, {@code @WithAnonymousUser} for the 401 case). CSRF is disabled globally, so no
 * {@code .with(csrf())} post-processor is needed; the {@code JwtAuthenticationFilter} skips when the
 * {@code SecurityContext} is already populated, so {@code @WithMockUser} authenticates without a real
 * JWT. No production class is modified by this test.</p>
 *
 * @see UserController
 * @see com.carddemo.service.UserService
 * @see com.carddemo.dto.UserResponse
 * @see com.carddemo.dto.UserCreateRequest
 * @see com.carddemo.dto.UserUpdateRequest
 * @see com.carddemo.dto.PageResponse
 * @see com.carddemo.security.SecurityConfig
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @see <a href="file:app/cbl/COUSR00C.cbl">app/cbl/COUSR00C.cbl (List Users, tran CU00)</a>
 * @see <a href="file:app/cbl/COUSR01C.cbl">app/cbl/COUSR01C.cbl (Add User, tran CU01)</a>
 * @see <a href="file:app/cbl/COUSR02C.cbl">app/cbl/COUSR02C.cbl (Update User, tran CU02)</a>
 * @see <a href="file:app/cbl/COUSR03C.cbl">app/cbl/COUSR03C.cbl (Delete User, tran CU03)</a>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "ADMIN001", roles = {"ADMIN"})
@DisplayName("UserController REST contract - COUSR00C..COUSR03C admin-only CRUD (list 7 / 200 / 201 / 204 / 404 / 400; non-admin 403; anonymous 401)")
class UserControllerTest {

    /** Seeded administrator id ({@code V4__seed_users.sql}, {@code user_type 'A'} -> ROLE_ADMIN). 8 chars. */
    private static final String ADMIN_ID = "ADMIN001";

    /** Seeded regular-user id ({@code V4__seed_users.sql}, {@code user_type 'U'} -> ROLE_USER). 8 chars. */
    private static final String USER_ID = "USER0001";

    /** An id that is guaranteed absent from the seed, used for the 404 (not-found) paths. 8 chars. */
    private static final String MISSING_ID = "NOSUCHID";

    /**
     * The fixed legacy browse window, standardized migration-wide (AAP &sect;0.6.4 / &sect;0.7.1) and
     * sourced service-side from {@code CardDemoConstants.PAGE_SIZE}. Asserted as {@code $.size}.
     */
    private static final int PAGE_SIZE = 7;

    /** The exact number of seeded users (ADMIN001 + USER0001). Asserted as {@code $.totalElements}. */
    private static final int SEED_USER_COUNT = 2;

    /**
     * Spring-managed {@link MockMvc} bound to the full application context &mdash; the real security
     * filter chain, {@link UserController}, {@code UserService} and the H2-backed persistence layer are
     * all live, so a green run proves the wired stack actually enforces admin-only CRUD end to end.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * The application's configured {@link ObjectMapper}; used to serialize the create/update request
     * bodies from a field map so the exact JSON wire form the controller deserializes is produced.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Builds a JSON request body from an ordered sequence of {@code key, value} pairs.
     *
     * <p>Using a {@link LinkedHashMap} keeps the emitted JSON field order stable and &mdash; unlike
     * {@code Map.of} &mdash; permits empty-string values, which the blank-field validation case
     * requires (an empty {@code userId} must reach the endpoint as {@code "userId":""} so the
     * {@code @NotBlank} constraint fires). The value type is {@code String} throughout because every
     * field of the user create/update contract is a string.</p>
     *
     * @param keyValues an even-length sequence of alternating field names and field values
     * @return the JSON object string, e.g. {@code {"userId":"NEWUSER1",...}}
     * @throws Exception if JSON serialization fails
     */
    private String json(String... keyValues) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            fields.put(keyValues[i], keyValues[i + 1]);
        }
        return objectMapper.writeValueAsString(fields);
    }

    // =============================================================================================
    // Phase 1 - GET /users : list browse (COUSR00C). Page size 7, exactly 2 seeded users, no password.
    // =============================================================================================

    /**
     * {@code GET /users?page=0} as ADMIN returns HTTP&nbsp;200 with a {@code PageResponse} whose
     * {@code size} is the fixed legacy window of <strong>7</strong> (AAP &sect;0.6.4), whose
     * {@code page} is {@code 0}, and which reports exactly the two seeded users
     * ({@code totalElements == 2}) on a single page ({@code first == last == true},
     * {@code content.length() == 2}). Each row carries {@code userId} and {@code userType} but
     * <strong>never</strong> a {@code password} &mdash; the direct re-expression of {@code COUSR00C}'s
     * {@code USRSEC} browse, with the credential structurally suppressed.
     */
    @Test
    @DisplayName("GET /users (admin) -> 200; size==7, page==0, totalElements==2, single full page, rows carry no password")
    void listUsers_asAdmin_pageSize7_twoElements_noPassword() throws Exception {
        mockMvc.perform(get("/users").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(PAGE_SIZE))                 // page size 7 (AAP 0.6.4)
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(SEED_USER_COUNT))  // ADMIN001 + USER0001
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.content.length()").value(SEED_USER_COUNT))
                .andExpect(jsonPath("$.content[0].password").doesNotExist())    // PII: password never serialized
                .andExpect(jsonPath("$.content[0].userId").exists())
                .andExpect(jsonPath("$.content[0].userType").exists());
    }

    /**
     * Defense-in-depth PII proof: the raw serialized list body must contain neither the literal
     * {@code "password"} JSON key nor a BCrypt {@code "$2"} hash prefix. Because
     * {@link com.carddemo.dto.UserResponse} declares no credential component, this lock can never be
     * tripped by a future change without also breaking the response contract (AAP &sect;0.6.8 /
     * &sect;0.7.1). The {@code "password"} check is case-insensitive; none of the seeded field values
     * (ids, names, type codes) contain that substring, so the assertion is unambiguous.
     */
    @Test
    @DisplayName("GET /users (admin) -> raw body never contains a password key or a BCrypt hash (PII suppression)")
    void listUsers_rawBody_neverContainsPasswordOrHash() throws Exception {
        String body = mockMvc.perform(get("/users").param("page", "0"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("$2");              // no BCrypt hash ($2a$/$2b$) ever leaks
        assertThat(body.toLowerCase()).doesNotContain("password");
    }

    // =============================================================================================
    // Phase 2 - GET /users/{userId} : single-user view (keyed READ backing COUSR02C/COUSR03C). 200/404.
    // =============================================================================================

    /**
     * {@code GET /users/ADMIN001} returns HTTP&nbsp;200 with the seeded administrator's detail &mdash;
     * {@code userId == "ADMIN001"}, {@code userType == "A"} &mdash; and <strong>no</strong> password,
     * the keyed-read projection that backs the legacy maintenance screens.
     */
    @Test
    @DisplayName("GET /users/{id} (admin) existing -> 200 with userId/userType, no password")
    void getUser_existingAdmin_returns200_noPassword() throws Exception {
        mockMvc.perform(get("/users/{userId}", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("ADMIN001"))
                .andExpect(jsonPath("$.userType").value("A"))
                .andExpect(jsonPath("$.firstName").exists())
                .andExpect(jsonPath("$.lastName").exists())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * Parity proof of the case-insensitive lookup: a <em>lower-case</em> id is upper-cased by the
     * service before the keyed read (the same {@code trim().toUpperCase(Locale.ROOT)} applied by the
     * sign-on path), so {@code GET /users/admin001} still resolves with HTTP&nbsp;200 and the returned
     * {@code userId} is the canonical {@code "ADMIN001"} &mdash; the {@code FUNCTION UPPER-CASE}
     * normalization of {@code COSGN00C}/{@code COUSR0xC} preserved (AAP &sect;0.6.7).
     */
    @Test
    @DisplayName("GET /users/{id} lower-case id is upper-cased before lookup -> 200, userId 'ADMIN001'")
    void getUser_lowercaseId_uppercased_returns200() throws Exception {
        mockMvc.perform(get("/users/{userId}", "admin001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("ADMIN001"))
                .andExpect(jsonPath("$.userType").value("A"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * An unknown id raises {@code ResourceNotFoundException} (the legacy "User ID NOT found..." path),
     * which {@code GlobalExceptionHandler} maps to HTTP&nbsp;404.
     */
    @Test
    @DisplayName("GET /users/{id} unknown id -> 404 Not Found")
    void getUser_unknown_returns404() throws Exception {
        mockMvc.perform(get("/users/{userId}", MISSING_ID))
                .andExpect(status().isNotFound());
    }

    // =============================================================================================
    // Phase 3 - POST /users : create (COUSR01C). 201 + no password; duplicate -> 400; validation -> 400.
    // =============================================================================================

    /**
     * {@code POST /users} as ADMIN with a valid body creates the user and returns
     * HTTP&nbsp;<strong>201&nbsp;Created</strong> with the new {@code userId}/{@code userType} and
     * <strong>no</strong> password &mdash; the success path of {@code COUSR01C}'s
     * {@code WRITE-USER-SEC-FILE}. The {@code userId} ({@code "NEWUSER1"}) and {@code password}
     * ({@code "PASS1234"}) are each 8 characters, satisfying the {@code @Size(max = 8)} constraints.
     * The class-level {@code @Transactional} rolls the insert back, restoring the two seed users.
     */
    @Test
    @DisplayName("POST /users (admin) valid -> 201 Created with userId/userType, no password")
    void createUser_valid_returns201_noPassword() throws Exception {
        String body = json(
                "userId", "NEWUSER1",
                "firstName", "New",
                "lastName", "User",
                "password", "PASS1234",
                "userType", "U");

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())                       // 201
                .andExpect(jsonPath("$.userId").value("NEWUSER1"))
                .andExpect(jsonPath("$.firstName").value("New"))
                .andExpect(jsonPath("$.lastName").value("User"))
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.password").doesNotExist());     // PII never echoed
    }

    /**
     * Creating a user whose id already exists raises {@code BusinessRuleException}
     * ({@code USER_ALREADY_EXISTS}) &mdash; the re-expression of {@code COUSR01C}'s
     * {@code DUPKEY}/{@code DUPREC} "User ID already exist..." rejection &mdash; which
     * {@code GlobalExceptionHandler} maps to HTTP&nbsp;400. The otherwise-valid body targets the seeded
     * {@code ADMIN001}.
     */
    @Test
    @DisplayName("POST /users (admin) duplicate userId (ADMIN001) -> 400 Bad Request (BusinessRuleException)")
    void createUser_duplicate_returns400() throws Exception {
        String body = json(
                "userId", ADMIN_ID,
                "firstName", "Dupe",
                "lastName", "User",
                "password", "PASS1234",
                "userType", "A");

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * A blank {@code userId} violates {@code @NotBlank} &mdash; the re-expression of {@code COUSR01C}'s
     * "User ID can NOT be empty..." check &mdash; surfaced as {@code MethodArgumentNotValidException}
     * &rarr; HTTP&nbsp;400 with a populated {@code fieldErrors} array. The empty string reaches the
     * endpoint as {@code "userId":""} (built via the {@link LinkedHashMap} helper, which permits empty
     * values).
     */
    @Test
    @DisplayName("POST /users (admin) blank userId -> 400 Bad Request with fieldErrors (COUSR01C 'can NOT be empty')")
    void createUser_blankUserId_returns400_fieldErrors() throws Exception {
        String body = json(
                "userId", "",
                "firstName", "New",
                "lastName", "User",
                "password", "PASS1234",
                "userType", "U");

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    /**
     * A {@code userType} outside {@code [AU]} violates {@code @Pattern("[AU]")} &mdash; preserving the
     * legacy two-valued user-type domain ({@code 'A'} admin / {@code 'U'} user) &mdash; surfaced as
     * HTTP&nbsp;400 with a populated {@code fieldErrors} array. All other fields are valid so the
     * failure is isolated to {@code userType}.
     */
    @Test
    @DisplayName("POST /users (admin) invalid userType 'X' -> 400 Bad Request with fieldErrors (@Pattern [AU])")
    void createUser_invalidUserType_returns400_fieldErrors() throws Exception {
        String body = json(
                "userId", "NEWUSER2",
                "firstName", "New",
                "lastName", "User",
                "password", "PASS1234",
                "userType", "X");

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    // =============================================================================================
    // Phase 4 - PUT /users/{id} (COUSR02C) 200/404 ; DELETE /users/{id} (COUSR03C) 204/404.
    // =============================================================================================

    /**
     * {@code PUT /users/USER0001} with a valid body updates the editable fields and returns
     * HTTP&nbsp;200 with the persisted post-update state &mdash; {@code userId == "USER0001"} (taken
     * from the path, never the body), {@code firstName == "Updated"} &mdash; and <strong>no</strong>
     * password, the re-expression of {@code COUSR02C}'s {@code READ}&hellip;{@code REWRITE}. The body
     * deliberately carries <strong>no</strong> {@code userId} ({@link com.carddemo.dto.UserUpdateRequest}
     * has none; the id is immutable and path-bound). The class-level {@code @Transactional} rolls the
     * update back, restoring the seed.
     */
    @Test
    @DisplayName("PUT /users/{id} (admin) existing, body has no userId -> 200 with updated fields, no password")
    void updateUser_existing_returns200_noPassword() throws Exception {
        String body = json(
                "firstName", "Updated",
                "lastName", "Name",
                "password", "PASS1234",
                "userType", "U");

        mockMvc.perform(put("/users/{userId}", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.firstName").value("Updated"))
                .andExpect(jsonPath("$.lastName").value("Name"))
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * Updating an unknown id raises {@code ResourceNotFoundException} (the legacy "User ID NOT found..."
     * path) &rarr; HTTP&nbsp;404, even though the body is otherwise valid (the {@code @Valid} bind
     * succeeds; the service's keyed read is what fails).
     */
    @Test
    @DisplayName("PUT /users/{id} (admin) valid body on unknown id -> 404 Not Found")
    void updateUser_unknown_returns404() throws Exception {
        String body = json(
                "firstName", "Updated",
                "lastName", "Name",
                "password", "PASS1234",
                "userType", "U");

        mockMvc.perform(put("/users/{userId}", MISSING_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    /**
     * {@code DELETE /users/USER0001} returns HTTP&nbsp;<strong>204&nbsp;No&nbsp;Content</strong> with an
     * empty body &mdash; the precise contract of {@code ResponseEntity.noContent().build()} and the
     * re-expression of {@code COUSR03C}'s {@code DELETE-USER-SEC-FILE}. The 204 (not 200) status and the
     * empty body are both asserted. The class-level {@code @Transactional} rolls the delete back,
     * restoring the seed.
     */
    @Test
    @DisplayName("DELETE /users/{id} (admin) existing -> 204 No Content with empty body")
    void deleteUser_existing_returns204_noBody() throws Exception {
        String body = mockMvc.perform(delete("/users/{userId}", USER_ID))
                .andExpect(status().isNoContent())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).isEmpty();
    }

    /**
     * Deleting an unknown id raises {@code ResourceNotFoundException} (the legacy "User ID NOT found..."
     * path) &rarr; HTTP&nbsp;404 &mdash; the service checks existence first so the delete surfaces as a
     * not-found rather than a silent no-op.
     */
    @Test
    @DisplayName("DELETE /users/{id} (admin) unknown id -> 404 Not Found")
    void deleteUser_unknown_returns404() throws Exception {
        mockMvc.perform(delete("/users/{userId}", MISSING_ID))
                .andExpect(status().isNotFound());
    }

    // =============================================================================================
    // Phase 5 - Authorization gating : authenticated non-admin -> 403 ; anonymous -> 401.
    // =============================================================================================

    /**
     * An authenticated <em>non-admin</em> caller (role {@code USER}, overriding the class-level admin
     * context) is rejected from the read endpoint with HTTP&nbsp;403 &mdash; the
     * {@code /users/**} &rarr; {@code hasRole('ADMIN')} filter-chain rule (and the controller's
     * class-level {@code @PreAuthorize}) denying an authenticated-but-forbidden principal. This is the
     * headline parity gate: legacy user maintenance was reachable only from the admin menu
     * ({@code COADM01C}).
     */
    @Test
    @WithMockUser(username = "USER0001", roles = {"USER"})
    @DisplayName("GET /users non-admin (ROLE_USER) -> 403 Forbidden (admin-only parity gate)")
    void listUsers_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isForbidden());
    }

    /**
     * Write endpoints are gated identically: a non-admin {@code POST /users} with an otherwise-valid
     * body is rejected with HTTP&nbsp;403 before any body validation or service logic runs &mdash;
     * confirming the admin-only restriction covers creation, not just the read.
     */
    @Test
    @WithMockUser(username = "USER0001", roles = {"USER"})
    @DisplayName("POST /users non-admin (ROLE_USER), valid body -> 403 Forbidden (write endpoints gated too)")
    void createUser_nonAdmin_returns403() throws Exception {
        String requestBody = json(
                "userId", "NEWUSER3",
                "firstName", "New",
                "lastName", "User",
                "password", "PASS1234",
                "userType", "U");

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isForbidden());
    }

    /**
     * An <em>unauthenticated</em> caller (overriding the class-level admin context with
     * {@code @WithAnonymousUser}) is rejected with HTTP&nbsp;401 by the stateless filter chain's
     * authentication entry point &mdash; the 401-vs-403 distinction that, together with
     * {@link #listUsers_nonAdmin_returns403()}, forms the core authorization assertion of this test.
     */
    @Test
    @WithAnonymousUser
    @DisplayName("GET /users anonymous (unauthenticated) -> 401 Unauthorized")
    void listUsers_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized());
    }
}
