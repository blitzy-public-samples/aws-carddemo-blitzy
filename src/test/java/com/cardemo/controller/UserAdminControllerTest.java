package com.cardemo.controller;

import com.cardemo.common.enums.UserType;
import com.cardemo.common.exception.DuplicateRecordException;
import com.cardemo.common.exception.RecordNotFoundException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.config.SecurityConfig;
import com.cardemo.entity.UserSecurity;
import com.cardemo.service.online.UserAddService;
import com.cardemo.service.online.UserDeleteService;
import com.cardemo.service.online.UserListService;
import com.cardemo.service.online.UserUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring {@code @WebMvcTest} slice test for {@link UserAdminController} — validates
 * the admin-only REST user CRUD endpoints that translate CICS transactions CU00
 * (User List — COUSR00C.cbl), CU01 (User Add — COUSR01C.cbl), CU02 (User Update —
 * COUSR02C.cbl), and CU03 (User Delete — COUSR03C.cbl) into stateless REST.
 *
 * <p>This test uses {@code @WebMvcTest(UserAdminController.class)} to limit the
 * Spring application context to only the web layer (UserAdminController), plus the
 * imported {@link SecurityConfig} for the security filter chain. All four service
 * dependencies are mocked via {@code @MockitoBean}.</p>
 *
 * <p><strong>CRITICAL</strong>: All endpoints require ADMIN role. The SecurityConfig
 * enforces {@code /api/admin/**} → {@code hasRole("ADMIN")} via URL-based security,
 * mapping to COBOL 88-level condition {@code CDEMO-USRTYP-ADMIN VALUE 'A'} from
 * COCOM01Y.cpy.</p>
 *
 * <h2>COBOL-to-Java User Field Mapping (CSUSR01Y.cpy):</h2>
 * <table>
 *   <tr><th>COBOL Field</th><th>Java Field</th><th>Type</th><th>Constraint</th></tr>
 *   <tr><td>SEC-USR-ID PIC X(08)</td><td>userId</td><td>String</td><td>Max 8 chars, PK</td></tr>
 *   <tr><td>SEC-USR-FNAME PIC X(20)</td><td>firstName</td><td>String</td><td>Max 20 chars</td></tr>
 *   <tr><td>SEC-USR-LNAME PIC X(20)</td><td>lastName</td><td>String</td><td>Max 20 chars</td></tr>
 *   <tr><td>SEC-USR-PWD PIC X(08)</td><td>password</td><td>String</td><td>BCrypt hashed, NEVER in response</td></tr>
 *   <tr><td>SEC-USR-TYPE PIC X(01)</td><td>userType</td><td>String</td><td>'A' (ADMIN) or 'U' (USER)</td></tr>
 * </table>
 *
 * <h2>HTTP Status Code Mapping:</h2>
 * <ul>
 *   <li>200 OK — Successful list, get, or update</li>
 *   <li>201 Created — Successful user creation (← COUSR01C WRITE RESP=0)</li>
 *   <li>204 No Content — Successful user deletion (← COUSR03C DELETE RESP=0)</li>
 *   <li>400 Bad Request — Validation failure (blank/invalid fields)</li>
 *   <li>401 Unauthorized — Unauthenticated request</li>
 *   <li>403 Forbidden — Non-admin user attempting admin operations</li>
 *   <li>404 Not Found — User not found (← VSAM status '23' / CICS RESP=13 NOTFND)</li>
 *   <li>409 Conflict — Duplicate userId (← VSAM status '22' / CICS RESP=22 DUPREC)</li>
 * </ul>
 *
 * @see UserAdminController
 * @see UserListService
 * @see UserAddService
 * @see UserUpdateService
 * @see UserDeleteService
 * @see SecurityConfig
 */
@WebMvcTest(UserAdminController.class)
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
class UserAdminControllerTest {

    /**
     * Auto-configured MockMvc instance for performing HTTP requests against
     * the UserAdminController without starting a real HTTP server. Provided
     * by {@code @WebMvcTest} and {@code @AutoConfigureMockMvc}.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Jackson ObjectMapper for serializing request bodies (user creation and
     * update payloads) into JSON format. Auto-configured by Spring Boot's
     * Jackson support via {@code spring-boot-starter-web}.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mocked UserListService — replaces the real service for paginated user
     * list operations (← COUSR00C.cbl CU00 transaction). Uses
     * {@code @MockitoBean} (Spring Framework 6.2+) instead of the deprecated
     * {@code @MockBean} to avoid compilation errors under
     * {@code -Xlint:all -Werror}.
     */
    @MockitoBean
    private UserListService userListService;

    /**
     * Mocked UserAddService — replaces the real service for user creation
     * (← COUSR01C.cbl CU01 transaction). Mock stubs for success, duplicate
     * userId, and validation error scenarios.
     */
    @MockitoBean
    private UserAddService userAddService;

    /**
     * Mocked UserUpdateService — replaces the real service for user update
     * (← COUSR02C.cbl CU02 transaction) and user retrieval via
     * {@code readUserSecFile()}.
     */
    @MockitoBean
    private UserUpdateService userUpdateService;

    /**
     * Mocked UserDeleteService — replaces the real service for user deletion
     * (← COUSR03C.cbl CU03 transaction). Mock stubs for success and not-found
     * scenarios.
     */
    @MockitoBean
    private UserDeleteService userDeleteService;

    // ═══════════════════════════════════════════════════════════════════════════
    // Group A: Admin-Only Access Control (← 88-level CDEMO-USRTYP-ADMIN VALUE 'A')
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 1: GET /api/admin/users as ADMIN returns HTTP 200 with paginated
     * user list.
     *
     * <p>Maps to COUSR00C.cbl MAIN-PARA → STARTBR/READNEXT on USRSEC file,
     * displaying up to 10 users per page (WS-MAX-SCREEN-LINES) with PF7/PF8
     * navigation for pagination.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("GET /api/admin/users — admin returns 200 with paginated list "
            + "(← COUSR00C STARTBR/READNEXT)")
    @WithMockUser(roles = "ADMIN")
    void listUsers_asAdmin_returnsOk() throws Exception {
        // Arrange: Create test user data matching CSUSR01Y.cpy record layout
        UserSecurity user1 = new UserSecurity("USER0001", "John", "Doe",
                "$2a$10$dummyhash1fortest000", UserType.USER);
        UserSecurity user2 = new UserSecurity("ADMIN001", "Admin", "User",
                "$2a$10$dummyhash2fortest000", UserType.ADMIN);
        List<UserSecurity> users = List.of(user1, user2);
        Page<UserSecurity> usersPage = new PageImpl<>(users);

        // Mock service: listUsers(null filter, page 0, size 10) returns page of users
        given(userListService.listUsers(isNull(), eq(0), eq(10))).willReturn(usersPage);

        // Act & Assert: GET list endpoint and verify paginated response
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].userId").value("USER0001"))
                .andExpect(jsonPath("$.content[0].firstName").value("John"))
                .andExpect(jsonPath("$.content[0].lastName").value("Doe"))
                .andExpect(jsonPath("$.content[0].userType").value("USER"))
                .andExpect(jsonPath("$.content[0].password").doesNotExist())
                .andExpect(jsonPath("$.content[1].userId").value("ADMIN001"))
                .andExpect(jsonPath("$.content[1].userType").value("ADMIN"));

        // Verify: Service called once with null filter, page 0, and default size 10
        verify(userListService).listUsers(isNull(), eq(0), eq(10));
    }

    /**
     * Test 2: GET /api/admin/users as regular user returns HTTP 403 Forbidden.
     *
     * <p>Maps to COBOL 88-level condition check in all COUSR programs:
     * {@code CDEMO-USRTYP-ADMIN VALUE 'A'} — non-admin users (type 'U') are
     * denied access to user management functions. SecurityConfig enforces
     * {@code /api/admin/**} → {@code hasRole("ADMIN")}.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("GET /api/admin/users — regular user returns 403 "
            + "(← COUSR CDEMO-USRTYP-ADMIN check)")
    @WithMockUser(roles = "USER")
    void listUsers_asRegularUser_returns403() throws Exception {
        // Act & Assert: USER role lacks ADMIN authority → 403 Forbidden
        // SecurityConfig: /api/admin/** requires hasRole("ADMIN")
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden());
    }

    /**
     * Test 3: GET /api/admin/users without authentication returns HTTP 401.
     *
     * <p>Unauthenticated requests are rejected by Spring Security's HTTP Basic
     * authentication requirement before reaching the controller. No
     * {@code @WithMockUser} annotation means no authentication context.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("GET /api/admin/users — unauthenticated returns 401 (no credentials)")
    void listUsers_unauthenticated_returns401() throws Exception {
        // Act & Assert: No @WithMockUser → unauthenticated → 401 Unauthorized
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Group B: User List with Filter (← COUSR00C.cbl)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 4: GET /api/admin/users with userIdFilter returns filtered results.
     *
     * <p>Maps to COUSR00C.cbl RECEIVE-USRLST-SCREEN → USRIDIN input field
     * used for filtered browse via STARTBR with RIDFLD=SEC-USR-ID starting
     * from the specified prefix.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("GET /api/admin/users?userIdFilter=USER — filtered list "
            + "(← COUSR00C USRIDIN browse)")
    @WithMockUser(roles = "ADMIN")
    void listUsers_withFilter_returnsFilteredResults() throws Exception {
        // Arrange: Create filtered result matching USER prefix
        UserSecurity filteredUser = new UserSecurity("USER0001", "John", "Doe",
                "$2a$10$dummyhash1fortest000", UserType.USER);
        Page<UserSecurity> filteredPage = new PageImpl<>(List.of(filteredUser));

        // Mock service with "USER" filter and default size 10
        given(userListService.listUsers(eq("USER"), eq(0), eq(10))).willReturn(filteredPage);

        // Act & Assert: GET with filter parameter
        mockMvc.perform(get("/api/admin/users").param("userIdFilter", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userId").value("USER0001"));

        // Verify: Service called with filter "USER", page 0, and default size 10
        verify(userListService).listUsers(eq("USER"), eq(0), eq(10));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Group C: User Creation (← COUSR01C.cbl)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 5: POST /api/admin/users as ADMIN returns HTTP 201 Created.
     *
     * <p>Maps to COUSR01C.cbl MAIN-PARA → PROCESS-ENTER-KEY →
     * WRITE-USER-SEC-FILE:</p>
     * <pre>
     *   EXEC CICS WRITE DATASET(WS-USRSEC-FILE)
     *     FROM(SEC-USER-DATA)
     *     RIDFLD(SEC-USR-ID)
     *     RESP(WS-RESP-CD)
     *   END-EXEC
     * </pre>
     * <p>On RESP=0, user is created successfully.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/admin/users — admin creates user returns 201 "
            + "(← COUSR01C WRITE RESP=0)")
    @WithMockUser(roles = "ADMIN")
    void createUser_asAdmin_returns201() throws Exception {
        // Arrange: Mock service to return created user
        UserSecurity createdUser = new UserSecurity("NEWUSR01", "John", "Doe",
                "$2a$10$Xe8vp9wLBhRr3C4ZVGBOBe", UserType.USER);
        given(userAddService.addUser(any())).willReturn(createdUser);

        // Build request body matching CSUSR01Y.cpy field layout:
        // SEC-USR-ID PIC X(08) → userId, SEC-USR-PWD PIC X(08) → password,
        // SEC-USR-FNAME PIC X(20) → firstName, SEC-USR-LNAME PIC X(20) → lastName,
        // SEC-USR-TYPE PIC X(01) → userType
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "userId", "NEWUSR01",
                "password", "P@ssw0rd",
                "firstName", "John",
                "lastName", "Doe",
                "userType", "U"
        ));

        // Act & Assert: POST creates user, returns 201 with user info (no password)
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("NEWUSR01"))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Doe"))
                .andExpect(jsonPath("$.userType").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());

        // Verify: addUser service called once
        verify(userAddService).addUser(any());
    }

    /**
     * Test 6: POST /api/admin/users with duplicate userId returns HTTP 409.
     *
     * <p>Maps to COUSR01C.cbl WRITE-USER-SEC-FILE → DFHRESP(DUPKEY) or
     * DFHRESP(DUPREC):</p>
     * <pre>
     *   EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(DUPKEY)  → "User ID already exist..."
     *     WHEN DFHRESP(DUPREC)  → "User ID already exist..."
     * </pre>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/admin/users — duplicate userId returns 409 "
            + "(← COUSR01C RESP=22 DUPREC)")
    @WithMockUser(roles = "ADMIN")
    void createUser_duplicateUserId_returns409() throws Exception {
        // Arrange: Mock service to throw DuplicateRecordException (VSAM status '22')
        willThrow(new DuplicateRecordException("User ID already exists"))
                .given(userAddService).addUser(any());

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "userId", "USER0001",
                "password", "P@ssw0rd",
                "firstName", "John",
                "lastName", "Doe",
                "userType", "U"
        ));

        // Act & Assert: Expect 409 Conflict with error message
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("User ID already exists"));
    }

    /**
     * Test 7: POST /api/admin/users with validation error returns HTTP 400.
     *
     * <p>Maps to COUSR01C.cbl PROCESS-ENTER-KEY validation — blank field checks
     * for FNAMEI, LNAMEI, USERIDI, PASSWDI, USRTYPEI empty checks return
     * appropriate error messages.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/admin/users — validation error returns 400 "
            + "(← COUSR01C field validation)")
    @WithMockUser(roles = "ADMIN")
    void createUser_validationError_returns400() throws Exception {
        // Arrange: Mock service to throw ValidationException for blank fields
        willThrow(new ValidationException("User ID is required"))
                .given(userAddService).addUser(any());

        // Request with missing required fields
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "firstName", "John",
                "lastName", "Doe"
        ));

        // Act & Assert: Expect 400 Bad Request with error message
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("User ID is required"));
    }

    /**
     * Test 8: POST /api/admin/users response NEVER contains password field.
     *
     * <p><strong>CRITICAL SECURITY INVARIANT</strong>: The original COBOL stored
     * SEC-USR-PWD PIC X(08) in plaintext. The Java migration uses BCrypt hashing,
     * but EVEN the hash must NEVER appear in any HTTP response body. The
     * controller's {@code toUserResponse()} method strips the password from the
     * response map.</p>
     *
     * <p>Verifies absence of common password field names: {@code password},
     * {@code pwd}, {@code passwd}, {@code hash} — none may appear in the
     * response JSON.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/admin/users — response NEVER contains password "
            + "(security invariant)")
    @WithMockUser(roles = "ADMIN")
    void createUser_passwordNeverInResponse() throws Exception {
        // Arrange: Mock service with user that has a BCrypt password internally
        UserSecurity createdUser = new UserSecurity("NEWUSR01", "John", "Doe",
                "$2a$10$Xe8vp9wLBhRr3C4ZVGBOBeN9sDQxiYmF4nHvQl0MF7qklKvRXQdi",
                UserType.USER);
        given(userAddService.addUser(any())).willReturn(createdUser);

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "userId", "NEWUSR01",
                "password", "P@ssw0rd",
                "firstName", "John",
                "lastName", "Doe",
                "userType", "U"
        ));

        // Act & Assert: Verify NO password-related fields in response
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.pwd").doesNotExist())
                .andExpect(jsonPath("$.passwd").doesNotExist())
                .andExpect(jsonPath("$.hash").doesNotExist());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Group D: User Update (← COUSR02C.cbl)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 9: PUT /api/admin/users/{userId} as ADMIN returns HTTP 200.
     *
     * <p>Maps to COUSR02C.cbl MAIN-PARA → PROCESS-ENTER-KEY → UPDATE-USER-INFO
     * → UPDATE-USER-SEC-FILE:</p>
     * <pre>
     *   EXEC CICS READ DATASET(WS-USRSEC-FILE)
     *     INTO(SEC-USER-DATA) RIDFLD(SEC-USR-ID) UPDATE ...
     *   → apply field changes →
     *   EXEC CICS REWRITE DATASET(WS-USRSEC-FILE)
     *     FROM(SEC-USER-DATA) ...
     * </pre>
     * <p>JPA {@code @Version} optimistic locking replaces the CICS
     * READ UPDATE → REWRITE pattern.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("PUT /api/admin/users/{userId} — admin updates user returns 200 "
            + "(← COUSR02C REWRITE)")
    @WithMockUser(roles = "ADMIN")
    void updateUser_asAdmin_returnsOk() throws Exception {
        // Arrange: Mock service to return updated user
        UserSecurity updatedUser = new UserSecurity("USER0001", "Jane", "Smith",
                "$2a$10$existinghashunchanged", UserType.ADMIN);
        given(userUpdateService.updateUser(eq("USER0001"), any()))
                .willReturn(updatedUser);

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "firstName", "Jane",
                "lastName", "Smith",
                "userType", "A"
        ));

        // Act & Assert: PUT update returns 200 with updated fields, no password
        mockMvc.perform(put("/api/admin/users/USER0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.firstName").value("Jane"))
                .andExpect(jsonPath("$.lastName").value("Smith"))
                .andExpect(jsonPath("$.userType").value("ADMIN"))
                .andExpect(jsonPath("$.password").doesNotExist());

        // Verify: updateUser service called with correct userId
        verify(userUpdateService).updateUser(eq("USER0001"), any());
    }

    /**
     * Test 10: PUT /api/admin/users/{userId} with new password returns HTTP 200.
     *
     * <p>Maps to COUSR02C.cbl PASSWD field update flow — password change is
     * applied by the service (BCrypt re-hashing). Neither old nor new password
     * may appear in the response.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("PUT /api/admin/users/{userId} — password change returns 200, "
            + "no password in response")
    @WithMockUser(roles = "ADMIN")
    void updateUser_withNewPassword_returnsOk() throws Exception {
        // Arrange: Mock service returning updated user with new BCrypt hash
        UserSecurity updatedUser = new UserSecurity("USER0001", "Jane", "Smith",
                "$2a$10$newbcrypthashafterchng", UserType.ADMIN);
        given(userUpdateService.updateUser(eq("USER0001"), any()))
                .willReturn(updatedUser);

        // Request body includes newPassword field for password change
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "firstName", "Jane",
                "lastName", "Smith",
                "newPassword", "NewP@ss1",
                "userType", "A"
        ));

        // Act & Assert: Verify 200 OK and NO password-related fields
        mockMvc.perform(put("/api/admin/users/USER0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.firstName").value("Jane"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.newPassword").doesNotExist())
                .andExpect(jsonPath("$.pwd").doesNotExist());
    }

    /**
     * Test 11: PUT /api/admin/users/{userId} with non-existent user returns 404.
     *
     * <p>Maps to COUSR02C.cbl READ-USER-SEC-FILE → RESP=13 NOTFND: the user
     * record does not exist in the USRSEC VSAM dataset (file status '23').</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("PUT /api/admin/users/{userId} — user not found returns 404 "
            + "(← COUSR02C RESP=13 NOTFND)")
    @WithMockUser(roles = "ADMIN")
    void updateUser_notFound_returns404() throws Exception {
        // Arrange: Mock service to throw RecordNotFoundException (VSAM status '23')
        willThrow(new RecordNotFoundException("User NOEXIST1 not found"))
                .given(userUpdateService).updateUser(eq("NOEXIST1"), any());

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "firstName", "Jane",
                "lastName", "Smith",
                "userType", "A"
        ));

        // Act & Assert: Expect 404 Not Found with error body
        mockMvc.perform(put("/api/admin/users/NOEXIST1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("User NOEXIST1 not found"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Group E: User Deletion (← COUSR03C.cbl)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Test 12: DELETE /api/admin/users/{userId} as ADMIN returns HTTP 204.
     *
     * <p>Maps to COUSR03C.cbl MAIN-PARA → PROCESS-ENTER-KEY → DELETE-USER-INFO
     * → DELETE-USER-SEC-FILE:</p>
     * <pre>
     *   EXEC CICS DELETE DATASET(WS-USRSEC-FILE)
     *     RIDFLD(SEC-USR-ID)
     *     RESP(WS-RESP-CD)
     *   END-EXEC
     * </pre>
     * <p>The COBOL two-step confirmation (display details then require 'Y') is
     * mapped to REST idiom: GET retrieves for review, DELETE performs the action.
     * The controller calls {@code deleteUser(userId, true)} where
     * {@code confirmed=true} represents the REST convention that the DELETE
     * request itself is the confirmation.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("DELETE /api/admin/users/{userId} — admin deletes user returns 204 "
            + "(← COUSR03C DELETE)")
    @WithMockUser(roles = "ADMIN")
    void deleteUser_asAdmin_returns204() throws Exception {
        // Arrange: Default mock behavior — deleteUser returns null (ignored by
        // controller which returns 204 No Content regardless of return value)

        // Act & Assert: DELETE returns 204 No Content
        mockMvc.perform(delete("/api/admin/users/USER0001"))
                .andExpect(status().isNoContent());

        // Verify: deleteUser service called with correct userId and confirmed=true
        verify(userDeleteService).deleteUser(eq("USER0001"), eq(true));
    }

    /**
     * Test 13: DELETE /api/admin/users/{userId} with non-existent user returns 404.
     *
     * <p>Maps to COUSR03C.cbl — user not found during READ verification before
     * deletion. The controller returns {@code ResponseEntity.notFound().build()}
     * with NO response body (unlike other 404 responses that include an error
     * body via {@code errorBody()}).</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("DELETE /api/admin/users/{userId} — user not found returns 404 "
            + "(← COUSR03C NOTFND)")
    @WithMockUser(roles = "ADMIN")
    void deleteUser_notFound_returns404() throws Exception {
        // Arrange: Mock service to throw RecordNotFoundException
        willThrow(new RecordNotFoundException("User NOEXIST1 not found"))
                .given(userDeleteService).deleteUser(eq("NOEXIST1"), eq(true));

        // Act & Assert: Expect 404 Not Found (no response body —
        // controller uses notFound().build() unlike other 404 handlers)
        mockMvc.perform(delete("/api/admin/users/NOEXIST1"))
                .andExpect(status().isNotFound());
    }

    /**
     * Test 14: DELETE /api/admin/users/{userId} as regular user returns 403.
     *
     * <p>Maps to COBOL 88-level condition check in all COUSR programs:
     * {@code CDEMO-USRTYP-ADMIN VALUE 'A'} — non-admin users cannot perform
     * deletion operations. SecurityConfig enforces {@code /api/admin/**} →
     * {@code hasRole("ADMIN")}.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("DELETE /api/admin/users/{userId} — regular user returns 403 "
            + "(← COUSR ADMIN check)")
    @WithMockUser(roles = "USER")
    void deleteUser_asRegularUser_returns403() throws Exception {
        // Act & Assert: USER role lacks ADMIN authority → 403 Forbidden
        mockMvc.perform(delete("/api/admin/users/USER0001"))
                .andExpect(status().isForbidden());
    }
}
