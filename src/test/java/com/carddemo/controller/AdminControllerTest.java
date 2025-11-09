/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.controller;

import com.carddemo.dto.request.UserRequest;
import com.carddemo.dto.response.UserResponse;
import com.carddemo.entity.User;
import com.carddemo.entity.User.UserType;
import com.carddemo.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive Integration Tests for AdminController REST API Endpoints
 * 
 * <p>This test class validates the complete user administration functionality in the CardDemo
 * Spring Boot application, ensuring functional equivalence with the original mainframe CICS
 * transactions COUSR00C (CU00), COUSR01C (CU01), COUSR02C (CU02), and COUSR03C (CU03).</p>
 * 
 * <h2>Mainframe to Spring Boot Transformation Validation</h2>
 * <p>These integration tests verify that the modernized REST API endpoints correctly replicate
 * the business logic, security controls, and data validation patterns from the COBOL VSAM
 * user security file operations.</p>
 * 
 * <pre>
 * COBOL Transaction   HTTP Method + Endpoint              Test Coverage
 * -------------------------------------------------------------------------
 * COUSR00C (CU00)  -> GET /api/admin/users             -> testListUsers*
 * COUSR01C (CU01)  -> POST /api/admin/users            -> testCreateUser*
 * COUSR02C (CU02)  -> PUT /api/admin/users/{id}        -> testUpdateUser*
 * COUSR03C (CU03)  -> DELETE /api/admin/users/{id}     -> testDeleteUser*
 * </pre>
 * 
 * <h2>Test Strategy and Scope</h2>
 * <ul>
 *   <li><b>Full Spring Context</b>: @SpringBootTest loads complete application</li>
 *   <li><b>MockMvc Integration</b>: Tests complete HTTP request/response cycle</li>
 *   <li><b>Security Testing</b>: Validates @PreAuthorize role-based access control</li>
 *   <li><b>Validation Testing</b>: Confirms Bean Validation annotations enforcement</li>
 *   <li><b>Database Integration</b>: Uses test database with @Transactional rollback</li>
 *   <li><b>JSON Serialization</b>: Verifies DTO mapping and field visibility</li>
 * </ul>
 * 
 * <h2>COBOL Security Model Preservation</h2>
 * <p>Tests validate that Spring Security correctly enforces the two-tier role model from
 * mainframe RACF security:</p>
 * <ul>
 *   <li>ROLE_ADMIN ('A'): Full user management access (create, update, delete)</li>
 *   <li>ROLE_USER ('U'): Denied access to admin endpoints (403 Forbidden)</li>
 * </ul>
 * 
 * <h2>Test Fixtures and Data Setup</h2>
 * <p>@BeforeEach setUp() method populates test database with predefined users matching
 * USRSEC file test data from app/data/ASCII/usrsec.txt, ensuring consistent test
 * environment for all test methods.</p>
 * 
 * <h2>Transaction Management</h2>
 * <p>@Transactional annotation ensures each test method runs in an isolated transaction
 * that is rolled back after test completion, maintaining clean database state and
 * preventing test pollution.</p>
 * 
 * @see AdminController
 * @see UserRequest
 * @see UserResponse
 * @see User
 * @see UserRepository
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@DisplayName("AdminController Integration Tests - User Administration REST API")
public class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Test fixture: Admin user for authentication context
     * Maps to COBOL SEC-USR-TYPE = 'A'
     */
    private User adminUser;

    /**
     * Test fixture: Regular user for authorization testing
     * Maps to COBOL SEC-USR-TYPE = 'U'
     */
    private User regularUser;

    /**
     * Test Setup Method
     * 
     * <p>Executes before each test method to establish consistent database state.
     * Creates two test users matching USRSEC file record structures:</p>
     * <ul>
     *   <li>ADMIN01: Administrative user with BCrypt-hashed password</li>
     *   <li>USER0001: Regular user with standard access</li>
     * </ul>
     * 
     * <p>Passwords are hashed using BCrypt to match UserCreateService and UserUpdateService
     * behavior, ensuring authentication tests work correctly with Spring Security.</p>
     */
    @BeforeEach
    public void setUp() {
        // Clean database before each test
        userRepository.deleteAll();

        // Create admin user test fixture
        // Maps to: SEC-USR-ID='ADMIN01', SEC-USR-TYPE='A'
        adminUser = User.builder()
                .userId("ADMIN01")
                .firstName("Admin")
                .lastName("User")
                .password(passwordEncoder.encode("Admin123!"))
                .userType(UserType.ADMIN)
                .deleted(false)
                .build();
        adminUser = userRepository.save(adminUser);

        // Create regular user test fixture
        // Maps to: SEC-USR-ID='USER0001', SEC-USR-TYPE='U'
        regularUser = User.builder()
                .userId("USER0001")
                .firstName("Regular")
                .lastName("User")
                .password(passwordEncoder.encode("User123!"))
                .userType(UserType.USER)
                .deleted(false)
                .build();
        regularUser = userRepository.save(regularUser);
    }

    // ========================================================================
    // GET /api/admin/users - List Users Tests (replaces COUSR00C/CU00)
    // ========================================================================

    /**
     * Test: GET /api/admin/users returns 200 OK with user list for admin user
     * 
     * <p>Validates successful user list retrieval matching COBOL COUSR00C.cbl transaction
     * which performs VSAM STARTBR/READNEXT operations to browse USRSEC file records.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 200 OK</li>
     *   <li>Response contains JSON array with at least 2 users (admin + regular)</li>
     *   <li>Each user object contains userId, firstName, lastName, userType fields</li>
     *   <li>Password field is excluded from response for security</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("GET /api/admin/users returns 200 OK with user list for authenticated admin")
    public void testListUsersReturnsOkWithUserList() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", isA(Object.class)))
                .andExpect(jsonPath("$.users", isA(java.util.List.class)))
                .andExpect(jsonPath("$.users", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.users[0].userId", notNullValue()))
                .andExpect(jsonPath("$.users[0].firstName", notNullValue()))
                .andExpect(jsonPath("$.users[0].lastName", notNullValue()))
                .andExpect(jsonPath("$.users[0].userType", notNullValue()))
                .andExpect(jsonPath("$.users[0].password").doesNotExist());
    }

    /**
     * Test: GET /api/admin/users returns 403 Forbidden for non-admin user
     * 
     * <p>Validates Spring Security @PreAuthorize("hasRole('ADMIN')") enforcement on
     * AdminController.listUsers() method, preserving RACF security pattern where only
     * administrators can access user management functions (CU00 transaction restricted
     * to admin users in COBOL system).</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 403 Forbidden</li>
     *   <li>Regular users (ROLE_USER) denied access to admin endpoints</li>
     *   <li>Security filter chain blocks request before reaching controller</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "USER0001", roles = {"USER"})
    @DisplayName("GET /api/admin/users returns 403 Forbidden for non-admin user")
    public void testListUsersReturnsForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * Test: GET /api/admin/users supports pagination parameters
     * 
     * <p>Validates pagination functionality matching COBOL COUSR00C.cbl page navigation
     * pattern with PF7/PF8 keys for scrolling through user lists (WS-PAGE-NUM field).</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 200 OK</li>
     *   <li>Response contains page metadata (pageNumber, pageSize, totalElements)</li>
     *   <li>Pagination parameters correctly applied: page=0, size=10</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("GET /api/admin/users supports pagination with page and size parameters")
    public void testListUsersSupportsPagination() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .param("page", "0")
                        .param("size", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", isA(java.util.List.class)))
                .andExpect(jsonPath("$.currentPage", is(0)))
                .andExpect(jsonPath("$.pageSize", is(10)))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(2)));
    }

    /**
     * Test: GET /api/admin/users supports filtering by user type
     * 
     * <p>Validates filter functionality matching COBOL COUSR00C.cbl conditional logic
     * for displaying only admin users or only regular users (SEC-USR-TYPE field filter).</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 200 OK</li>
     *   <li>Filtered results contain only users with specified userType</li>
     *   <li>userType=A returns only admin users, userType=U returns only regular users</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("GET /api/admin/users supports filtering by userType parameter")
    public void testListUsersSupportsUserTypeFilter() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .param("userTypeFilter", "A")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", isA(java.util.List.class)))
                .andExpect(jsonPath("$.users[*].userType", everyItem(is("Admin"))));
    }

    // ========================================================================
    // GET /api/admin/users/{id} - Get User By ID Tests
    // ========================================================================

    /**
     * Test: GET /api/admin/users/{id} returns 200 OK with user details
     * 
     * <p>Validates single user retrieval matching COBOL VSAM READ operation with
     * specific user ID key lookup (RIDFLD(SEC-USR-ID)).</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 200 OK</li>
     *   <li>Response contains complete user profile for specified userId</li>
     *   <li>All fields match expected values from test fixture</li>
     *   <li>Password field excluded from response</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("GET /api/admin/users/{id} returns 200 OK with user details for existing user")
    public void testGetUserByIdReturnsOkWithUserDetails() throws Exception {
        mockMvc.perform(get("/api/admin/users/{id}", "ADMIN01")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId", is("ADMIN01")))
                .andExpect(jsonPath("$.firstName", is("Admin")))
                .andExpect(jsonPath("$.lastName", is("User")))
                .andExpect(jsonPath("$.userType", is("Admin")))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * Test: GET /api/admin/users/{id} returns 404 Not Found for non-existent user
     * 
     * <p>Validates error handling matching COBOL RESP code 13 (record not found) from
     * VSAM READ operation when specified user ID does not exist in USRSEC file.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 404 Not Found</li>
     *   <li>Error message indicates user not found</li>
     *   <li>GlobalExceptionHandler catches ResourceNotFoundException</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("GET /api/admin/users/{id} returns 404 Not Found for non-existent user")
    public void testGetUserByIdReturnsNotFoundForNonExistentUser() throws Exception {
        mockMvc.perform(get("/api/admin/users/{id}", "NOEXIST")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("not found")));
    }

    /**
     * Test: GET /api/admin/users/{id} returns 403 Forbidden for non-admin user
     * 
     * <p>Validates role-based access control on single user retrieval endpoint.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 403 Forbidden</li>
     *   <li>Regular users denied access to view user details</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "USER0001", roles = {"USER"})
    @DisplayName("GET /api/admin/users/{id} returns 403 Forbidden for non-admin user")
    public void testGetUserByIdReturnsForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/users/{id}", "ADMIN01")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ========================================================================
    // POST /api/admin/users - Create User Tests (replaces COUSR01C/CU01)
    // ========================================================================

    /**
     * Test: POST /api/admin/users with valid request returns 201 Created
     * 
     * <p>Validates successful user creation matching COBOL COUSR01C.cbl VSAM WRITE
     * operation adding new record to USRSEC file with all required fields.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 201 Created</li>
     *   <li>Location header contains URI of newly created user</li>
     *   <li>Response body contains UserResponse with all fields except password</li>
     *   <li>Password is BCrypt hashed before storage (never stored in plain text)</li>
     *   <li>User record persisted to database with correct field values</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/users with valid request returns 201 Created with user details")
    public void testCreateUserReturnsCreatedWithValidRequest() throws Exception {
        // Build valid UserRequest matching COBOL SEC-USER-DATA structure
        UserRequest newUserRequest = UserRequest.builder()
                .userId("TESTUSER")
                .firstName("Test")
                .lastName("User")
                .password("Test123!")  // 8 characters max per COBOL PIC X(08)
                .userType("U")
                .build();

        String requestJson = objectMapper.writeValueAsString(newUserRequest);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("Location", containsString("/api/admin/users/TESTUSER")))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId", is("TESTUSER")))
                .andExpect(jsonPath("$.firstName", is("Test")))
                .andExpect(jsonPath("$.lastName", is("User")))
                .andExpect(jsonPath("$.userType", is("U")))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * Test: POST /api/admin/users with missing userId returns 400 Bad Request
     * 
     * <p>Validates @NotBlank validation on userId field matching COBOL validation
     * logic that checks SEC-USR-ID is not SPACES.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 400 Bad Request</li>
     *   <li>Validation error message indicates userId is required</li>
     *   <li>MethodArgumentNotValidException caught by GlobalExceptionHandler</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/users with missing userId returns 400 Bad Request")
    public void testCreateUserReturnsBadRequestWithMissingUserId() throws Exception {
        UserRequest invalidRequest = UserRequest.builder()
                .userId("")  // Empty userId violates @NotBlank
                .firstName("Test")
                .lastName("User")
                .password("Test123!")  // Valid 8 characters per COBOL PIC X(08)
                .userType("U")
                .build();

        String requestJson = objectMapper.writeValueAsString(invalidRequest);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.userId", containsString("required")));
    }

    /**
     * Test: POST /api/admin/users with userId exceeding 8 characters returns 400 Bad Request
     * 
     * <p>Validates @Size(max=8) constraint matching COBOL PIC X(08) field length limit
     * from SEC-USR-ID in CSUSR01Y.cpy copybook.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 400 Bad Request</li>
     *   <li>Validation error indicates userId length violation</li>
     *   <li>Maximum length constraint enforced before database persistence</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/users with userId > 8 chars returns 400 Bad Request")
    public void testCreateUserReturnsBadRequestWithUserIdTooLong() throws Exception {
        UserRequest invalidRequest = UserRequest.builder()
                .userId("TOOLONGID")  // 9 characters exceeds max=8
                .firstName("Test")
                .lastName("User")
                .password("Test123!")  // Valid 8 characters per COBOL PIC X(08)
                .userType("U")
                .build();

        String requestJson = objectMapper.writeValueAsString(invalidRequest);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.userId", containsString("between 1 and 8")));
    }

    /**
     * Test: POST /api/admin/users with invalid userType returns 400 Bad Request
     * 
     * <p>Validates @Pattern(regexp="^[AU]$") constraint matching COBOL 88-level conditions
     * CDEMO-USRTYP-ADMIN VALUE 'A' and CDEMO-USRTYP-USER VALUE 'U'.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 400 Bad Request</li>
     *   <li>Validation error indicates invalid userType value</li>
     *   <li>Only 'A' (Admin) and 'U' (User) accepted</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/users with invalid userType returns 400 Bad Request")
    public void testCreateUserReturnsBadRequestWithInvalidUserType() throws Exception {
        UserRequest invalidRequest = UserRequest.builder()
                .userId("TESTUSER")
                .firstName("Test")
                .lastName("User")
                .password("Test123!")  // Valid 8 characters per COBOL PIC X(08)
                .userType("X")  // Invalid userType (not 'A' or 'U')
                .build();

        String requestJson = objectMapper.writeValueAsString(invalidRequest);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.userType", containsString("'A' (Admin) or 'U' (User)")));
    }

    /**
     * Test: POST /api/admin/users with duplicate userId returns 409 Conflict
     * 
     * <p>Validates duplicate key error handling matching COBOL VSAM WRITE with RESP code 14
     * (duplicate record) when attempting to add user with existing SEC-USR-ID.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 409 Conflict</li>
     *   <li>Error message indicates userId already exists</li>
     *   <li>Database unique constraint prevents duplicate user IDs</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/users with duplicate userId returns 409 Conflict")
    public void testCreateUserReturnsConflictWithDuplicateUserId() throws Exception {
        UserRequest duplicateRequest = UserRequest.builder()
                .userId("ADMIN01")  // Already exists in test fixtures
                .firstName("Another")
                .lastName("Admin")
                .password("Pass123!")  // Valid 8 characters per COBOL PIC X(08)
                .userType("A")
                .build();

        String requestJson = objectMapper.writeValueAsString(duplicateRequest);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }

    /**
     * Test: POST /api/admin/users with missing password returns 400 Bad Request
     * 
     * <p>Validates @NotBlank validation on password field matching COBOL validation
     * that SEC-USR-PWD must not be SPACES.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 400 Bad Request</li>
     *   <li>Validation error indicates password is required</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/users with missing password returns 400 Bad Request")
    public void testCreateUserReturnsBadRequestWithMissingPassword() throws Exception {
        UserRequest invalidRequest = UserRequest.builder()
                .userId("TESTUSER")
                .firstName("Test")
                .lastName("User")
                .password("")  // Empty password violates @Size constraint
                .userType("U")
                .build();

        String requestJson = objectMapper.writeValueAsString(invalidRequest);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password", containsString("required")));
    }

    /**
     * Test: POST /api/admin/users returns 403 Forbidden for non-admin user
     * 
     * <p>Validates that only administrators can create new users, preserving RACF
     * security model where CU01 transaction is admin-only.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 403 Forbidden</li>
     *   <li>Regular users cannot create user accounts</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "USER0001", roles = {"USER"})
    @DisplayName("POST /api/admin/users returns 403 Forbidden for non-admin user")
    public void testCreateUserReturnsForbiddenForNonAdmin() throws Exception {
        UserRequest newUserRequest = UserRequest.builder()
                .userId("TESTUSER")
                .firstName("Test")
                .lastName("User")
                .password("Test123!")  // Valid 8 characters per COBOL PIC X(08)
                .userType("U")
                .build();

        String requestJson = objectMapper.writeValueAsString(newUserRequest);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ========================================================================
    // PUT /api/admin/users/{id} - Update User Tests (replaces COUSR02C/CU02)
    // ========================================================================

    /**
     * Test: PUT /api/admin/users/{id} with valid request returns 200 OK
     * 
     * <p>Validates successful user update matching COBOL COUSR02C.cbl VSAM REWRITE
     * operation modifying existing USRSEC file record.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 200 OK</li>
     *   <li>Response contains updated user details</li>
     *   <li>All modified fields persisted to database</li>
     *   <li>Password re-hashed if provided in update request</li>
     *   <li>Optimistic locking prevents concurrent update conflicts</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("PUT /api/admin/users/{id} with valid request returns 200 OK with updated user")
    public void testUpdateUserReturnsOkWithValidRequest() throws Exception {
        UserRequest updateRequest = UserRequest.builder()
                .userId("USER0001")
                .firstName("Updated")
                .lastName("Name")
                .password("NewPas1!")  // 8 characters max per COBOL PIC X(08)
                .userType("U")
                .build();

        String requestJson = objectMapper.writeValueAsString(updateRequest);

        mockMvc.perform(put("/api/admin/users/{id}", "USER0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId", is("USER0001")))
                .andExpect(jsonPath("$.firstName", is("Updated")))
                .andExpect(jsonPath("$.lastName", is("Name")))
                .andExpect(jsonPath("$.userType", is("U")))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * Test: PUT /api/admin/users/{id} returns 404 Not Found for non-existent user
     * 
     * <p>Validates error handling matching COBOL RESP code 13 (record not found) from
     * VSAM READ operation when attempting to update user that doesn't exist.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 404 Not Found</li>
     *   <li>Error message indicates user not found</li>
     *   <li>No database modifications performed</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("PUT /api/admin/users/{id} returns 404 Not Found for non-existent user")
    public void testUpdateUserReturnsNotFoundForNonExistentUser() throws Exception {
        UserRequest updateRequest = UserRequest.builder()
                .userId("NOEXIST")
                .firstName("Test")
                .lastName("User")
                .password("Test123!")  // Valid 8 characters per COBOL PIC X(08)
                .userType("U")
                .build();

        String requestJson = objectMapper.writeValueAsString(updateRequest);

        mockMvc.perform(put("/api/admin/users/{id}", "NOEXIST")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("not found")));
    }

    /**
     * Test: PUT /api/admin/users/{id} with validation errors returns 400 Bad Request
     * 
     * <p>Validates Bean Validation annotations on UserRequest DTO during update operation.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 400 Bad Request</li>
     *   <li>Validation errors for invalid field values</li>
     *   <li>firstName exceeding 20 characters triggers @Size constraint violation</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("PUT /api/admin/users/{id} with firstName > 20 chars returns 400 Bad Request")
    public void testUpdateUserReturnsBadRequestWithInvalidFirstName() throws Exception {
        UserRequest invalidRequest = UserRequest.builder()
                .userId("USER0001")
                .firstName("ThisFirstNameIsWayTooLongForValidation")  // Exceeds 20 chars
                .lastName("User")
                .password("Test123!")  // Valid 8 characters per COBOL PIC X(08)
                .userType("U")
                .build();

        String requestJson = objectMapper.writeValueAsString(invalidRequest);

        mockMvc.perform(put("/api/admin/users/{id}", "USER0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.firstName", containsString("between 1 and 20")));
    }

    /**
     * Test: PUT /api/admin/users/{id} returns 403 Forbidden for non-admin user
     * 
     * <p>Validates role-based access control on user update endpoint.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 403 Forbidden</li>
     *   <li>Regular users cannot update user records</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "USER0001", roles = {"USER"})
    @DisplayName("PUT /api/admin/users/{id} returns 403 Forbidden for non-admin user")
    public void testUpdateUserReturnsForbiddenForNonAdmin() throws Exception {
        UserRequest updateRequest = UserRequest.builder()
                .userId("USER0001")
                .firstName("Updated")
                .lastName("Name")
                .password("NewPas1!")  // Valid 8 characters per COBOL PIC X(08)
                .userType("U")
                .build();

        String requestJson = objectMapper.writeValueAsString(updateRequest);

        mockMvc.perform(put("/api/admin/users/{id}", "USER0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * Test: PUT /api/admin/users/{id} allows admin to change user type from USER to ADMIN
     * 
     * <p>Validates role promotion functionality matching COBOL COUSR02C.cbl logic for
     * modifying SEC-USR-TYPE field value.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 200 OK</li>
     *   <li>User type successfully changed from 'U' to 'A'</li>
     *   <li>Updated user has ADMIN role in response</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("PUT /api/admin/users/{id} allows role change from USER to ADMIN")
    public void testUpdateUserAllowsRolePromotion() throws Exception {
        UserRequest updateRequest = UserRequest.builder()
                .userId("USER0001")
                .firstName("Regular")
                .lastName("User")
                .password("User123!")  // 8 characters max per COBOL PIC X(08)
                .userType("A")  // Promote to ADMIN
                .build();

        String requestJson = objectMapper.writeValueAsString(updateRequest);

        mockMvc.perform(put("/api/admin/users/{id}", "USER0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is("USER0001")))
                .andExpect(jsonPath("$.userType", is("A")));
    }

    // ========================================================================
    // DELETE /api/admin/users/{id} - Delete User Tests (replaces COUSR03C/CU03)
    // ========================================================================

    /**
     * Test: DELETE /api/admin/users/{id} returns 204 No Content for successful soft delete
     * 
     * <p>Validates soft delete functionality matching COBOL COUSR03C.cbl logic which
     * marks user record as deleted rather than physically removing from USRSEC file.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 204 No Content</li>
     *   <li>User record marked as deleted (deleted=true)</li>
     *   <li>Deleted timestamp and deleted_by fields populated</li>
     *   <li>User no longer appears in active user lists</li>
     *   <li>User cannot authenticate after soft delete</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("DELETE /api/admin/users/{id} returns 204 No Content for successful soft delete")
    public void testDeleteUserReturnsNoContentForSuccessfulDelete() throws Exception {
        mockMvc.perform(delete("/api/admin/users/{id}", "USER0001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        // Verify user is soft deleted (deleted flag set to true)
        User deletedUser = userRepository.findById("USER0001").orElse(null);
        assert deletedUser != null;
        assert deletedUser.isDeleted();
        assert deletedUser.getDeletedDate() != null;
        assert deletedUser.getDeletedBy() != null;
    }

    /**
     * Test: DELETE /api/admin/users/{id} returns 404 Not Found for non-existent user
     * 
     * <p>Validates error handling when attempting to delete user that doesn't exist,
     * matching COBOL RESP code 13 (record not found).</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 404 Not Found</li>
     *   <li>Error message indicates user not found</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("DELETE /api/admin/users/{id} returns 404 Not Found for non-existent user")
    public void testDeleteUserReturnsNotFoundForNonExistentUser() throws Exception {
        mockMvc.perform(delete("/api/admin/users/{id}", "NOEXIST")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", containsString("not found")));
    }

    /**
     * Test: DELETE /api/admin/users/{id} returns 403 Forbidden for non-admin user
     * 
     * <p>Validates role-based access control on user deletion endpoint.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 403 Forbidden</li>
     *   <li>Regular users cannot delete user accounts</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "USER0001", roles = {"USER"})
    @DisplayName("DELETE /api/admin/users/{id} returns 403 Forbidden for non-admin user")
    public void testDeleteUserReturnsForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(delete("/api/admin/users/{id}", "ADMIN01")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * Test: DELETE /api/admin/users/{id} returns 400 Bad Request when attempting to delete last admin
     * 
     * <p>Validates business rule preventing deletion of last administrator user, ensuring
     * system always has at least one admin user for management operations.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 400 Bad Request</li>
     *   <li>Error message indicates last admin cannot be deleted</li>
     *   <li>Admin user remains active in database</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("DELETE /api/admin/users/{id} prevents deletion of last admin user")
    public void testDeleteUserPreventsLastAdminDeletion() throws Exception {
        // First delete the regular user to leave only one admin
        userRepository.deleteById("USER0001");

        // Attempt to delete the last remaining admin
        mockMvc.perform(delete("/api/admin/users/{id}", "ADMIN01")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("last admin")));
    }

    // ========================================================================
    // Additional Edge Case and Integration Tests
    // ========================================================================

    /**
     * Test: POST /api/admin/users with multiple validation errors returns all error details
     * 
     * <p>Validates that GlobalExceptionHandler returns comprehensive validation error details
     * for multiple field violations in a single request.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 400 Bad Request</li>
     *   <li>Errors array contains entries for each invalid field</li>
     *   <li>Each error includes field name and validation message</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/users with multiple validation errors returns all error details")
    public void testCreateUserReturnsAllValidationErrors() throws Exception {
        UserRequest invalidRequest = UserRequest.builder()
                .userId("")  // Empty userId
                .firstName("")  // Empty firstName
                .lastName("ThisLastNameIsWayTooLongForValidation")  // Exceeds 20 chars
                .password("")  // Empty password
                .userType("X")  // Invalid userType
                .build();

        String requestJson = objectMapper.writeValueAsString(invalidRequest);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", isA(java.util.Map.class)))
                .andExpect(jsonPath("$.fieldErrors.userId", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.firstName", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.lastName", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.password", notNullValue()))
                .andExpect(jsonPath("$.fieldErrors.userType", notNullValue()));
    }

    /**
     * Test: GET /api/admin/users with sorting parameters returns sorted results
     * 
     * <p>Validates sort functionality matching COBOL COUSR00C.cbl sorted browse pattern.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 200 OK</li>
     *   <li>Results sorted by specified field (lastName) in ascending order</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("GET /api/admin/users supports sorting by userId")
    public void testListUsersSupportsSorting() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .param("sortBy", "userId")
                        .param("sortDirection", "asc")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users", isA(java.util.List.class)))
                .andExpect(jsonPath("$.sortedBy", is("userId")))
                .andExpect(jsonPath("$.sortDirection", is("ASC")));
    }

    /**
     * Test: POST /api/admin/users creates user with BCrypt hashed password
     * 
     * <p>Validates that passwords are properly encrypted using BCrypt before storage,
     * replacing mainframe password storage with modern cryptographic hashing.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>HTTP Status: 201 Created</li>
     *   <li>Password in database is BCrypt hash (starts with $2a$)</li>
     *   <li>Plain text password never stored</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("POST /api/admin/users encrypts password using BCrypt")
    public void testCreateUserEncryptsPasswordWithBCrypt() throws Exception {
        UserRequest newUserRequest = UserRequest.builder()
                .userId("SECTEST1")
                .firstName("Security")
                .lastName("Test")
                .password("Plain12!")  // 8 characters max per COBOL PIC X(08)
                .userType("U")
                .build();

        String requestJson = objectMapper.writeValueAsString(newUserRequest);

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());

        // Verify password is BCrypt hashed in database
        User savedUser = userRepository.findById("SECTEST1").orElse(null);
        assert savedUser != null;
        assert savedUser.getPassword().startsWith("$2a$") || savedUser.getPassword().startsWith("$2b$");
        assert !savedUser.getPassword().equals("Plain123");
    }

    /**
     * Test: Verify CORS configuration allows cross-origin requests from React frontend
     * 
     * <p>Validates CORS headers are properly configured for React application integration.</p>
     * 
     * <p>Expected Behavior:</p>
     * <ul>
     *   <li>Access-Control-Allow-Origin header present in response</li>
     *   <li>Cross-origin requests from frontend allowed</li>
     * </ul>
     */
    @Test
    @WithMockUser(username = "ADMIN01", roles = {"ADMIN"})
    @DisplayName("Verify CORS configuration allows cross-origin requests")
    public void testCorsConfigurationAllowsCrossOriginRequests() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .header("Origin", "http://localhost:3000")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"));
    }
}
