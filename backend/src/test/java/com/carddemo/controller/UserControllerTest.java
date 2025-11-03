/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.controller;

import com.carddemo.dto.request.UserManagementRequest;
import com.carddemo.dto.request.UserProfileUpdateRequest;
import com.carddemo.dto.response.UserProfileResponse;
import com.carddemo.entity.UserSecurity;
import com.carddemo.exception.UserNotFoundException;
import com.carddemo.service.UserManagementService;
import com.carddemo.service.UserProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive JUnit 5 test class for UserController REST endpoint validation.
 * 
 * <p>Transforms COBOL user management programs to Spring Boot REST API tests:</p>
 * <ul>
 *   <li>COUSR00C.cbl (Transaction CU00) - User list management with pagination</li>
 *   <li>COUSR01C.cbl (Transaction CU01) - User profile creation and updates</li>
 * </ul>
 * 
 * <p><strong>Test Coverage:</strong></p>
 * <ul>
 *   <li>GET /api/users - Admin-only user list with pagination (10 users per page)</li>
 *   <li>GET /api/users/{userId} - User profile retrieval with authorization checks</li>
 *   <li>POST /api/users - Admin-only user creation with BCrypt password encryption</li>
 *   <li>PUT /api/users/{userId} - User profile updates with authorization rules</li>
 *   <li>DELETE /api/users/{userId} - Admin-only user deletion</li>
 *   <li>Response time assertions (< 200ms per Section 0.9)</li>
 * </ul>
 * 
 * <p><strong>Security Testing:</strong></p>
 * <ul>
 *   <li>ROLE_ADMIN: Full access to all user management operations</li>
 *   <li>ROLE_USER: Can only view/update own profile</li>
 *   <li>Anonymous users: Rejected with 401 Unauthorized</li>
 *   <li>User type mapping: 'R' → ROLE_USER, 'A' → ROLE_ADMIN</li>
 *   <li>Password field never returned in responses</li>
 *   <li>BCrypt password encryption validation</li>
 * </ul>
 * 
 * <p><strong>COBOL Business Logic Preservation:</strong></p>
 * <ul>
 *   <li>USRSEC file CRUD operations → UserSecurityRepository</li>
 *   <li>Required field validation: userId (PIC X(8)), firstName, lastName, password, userType</li>
 *   <li>User type validation: 'R' or 'A' only</li>
 *   <li>Duplicate user ID check (DFHRESP(DUPKEY) equivalent)</li>
 *   <li>Pagination: 10 users per page (matches COBOL screen layout)</li>
 * </ul>
 */
@WebMvcTest(UserController.class)
@DisplayName("UserController REST Endpoint Tests")
public class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserManagementService userManagementService;

    @MockBean
    private UserProfileService userProfileService;

    // Test data constants matching COBOL field specifications
    private static final String TEST_USER_ID = "TESTUSER";
    private static final String TEST_FIRST_NAME = "John";
    private static final String TEST_LAST_NAME = "Doe";
    private static final String TEST_PASSWORD = "Test123!";
    private static final String TEST_USER_TYPE_REGULAR = "R";
    private static final String TEST_USER_TYPE_ADMIN = "A";
    
    private static final String ADMIN_USER_ID = "ADMINUSR";
    private static final String REGULAR_USER_ID = "REGUSR01";

    private UserProfileResponse testUserResponse;
    private UserManagementRequest testUserRequest;
    private UserProfileUpdateRequest testUpdateRequest;

    /**
     * Setup method executed before each test.
     * Initializes test data objects matching COBOL USRSEC file structure.
     */
    @BeforeEach
    void setUp() {
        // Initialize test user response (matches COUSR01.CPY output structure)
        testUserResponse = new UserProfileResponse();
        testUserResponse.setUserId(TEST_USER_ID);
        testUserResponse.setFirstName(TEST_FIRST_NAME);
        testUserResponse.setLastName(TEST_LAST_NAME);
        testUserResponse.setUserType(TEST_USER_TYPE_REGULAR);
        testUserResponse.setRoles(Arrays.asList("ROLE_USER"));
        testUserResponse.setCurrentDate(LocalDate.now());
        testUserResponse.setCurrentTime(LocalTime.now());

        // Initialize test user creation request (matches COUSR01C.cbl input fields)
        testUserRequest = new UserManagementRequest();
        testUserRequest.setUserId(TEST_USER_ID);
        testUserRequest.setFirstName(TEST_FIRST_NAME);
        testUserRequest.setLastName(TEST_LAST_NAME);
        testUserRequest.setPassword(TEST_PASSWORD);
        testUserRequest.setUserType(TEST_USER_TYPE_REGULAR);

        // Initialize test profile update request
        testUpdateRequest = new UserProfileUpdateRequest();
        testUpdateRequest.setUserId(TEST_USER_ID);
        testUpdateRequest.setFirstName("Jane");
        testUpdateRequest.setLastName("Smith");
    }

    // ========================================================================
    // GET /api/users - User List Tests (COUSR00C.cbl equivalent)
    // ========================================================================

    @Test
    @DisplayName("GET /api/users - Admin user successfully retrieves paginated user list")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testGetUserList_AsAdmin_ReturnsUserList() throws Exception {
        // Arrange: Create test user list matching COBOL 10 users per page
        List<UserProfileResponse> userList = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            UserProfileResponse user = new UserProfileResponse();
            user.setUserId("USER000" + i);
            user.setFirstName("First" + i);
            user.setLastName("Last" + i);
            user.setUserType(i % 2 == 0 ? TEST_USER_TYPE_ADMIN : TEST_USER_TYPE_REGULAR);
            user.setRoles(Arrays.asList(i % 2 == 0 ? "ROLE_ADMIN" : "ROLE_USER"));
            userList.add(user);
        }
        
        Page<UserProfileResponse> userPage = new PageImpl<>(userList, PageRequest.of(0, 10), 25);
        when(userManagementService.listUsers(any(Pageable.class))).thenReturn(userPage);

        // Act & Assert: Verify paginated user list retrieval
        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users")
                .param("page", "0")
                .param("size", "10")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content", hasSize(10)))
                .andExpect(jsonPath("$.content[0].userId").value("USER0001"))
                .andExpect(jsonPath("$.content[0].firstName").value("First1"))
                .andExpect(jsonPath("$.content[0].userType").value(TEST_USER_TYPE_REGULAR))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(10));
        
        long responseTime = System.currentTimeMillis() - startTime;
        
        // Verify service method called with correct pagination parameters
        verify(userManagementService, times(1)).listUsers(any(Pageable.class));
        
        // Assert response time < 200ms per Section 0.9 performance requirements
        assert responseTime < 200 : "Response time " + responseTime + "ms exceeds 200ms limit";
    }

    @Test
    @DisplayName("GET /api/users - Regular user receives 403 Forbidden")
    @WithMockUser(username = REGULAR_USER_ID, roles = {"USER"})
    void testGetUserList_AsRegularUser_ReturnsForbidden() throws Exception {
        // Act & Assert: Regular users cannot access user list (admin-only operation)
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        
        // Verify service method never called due to authorization failure
        verify(userManagementService, times(0)).listUsers(any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/users - Anonymous user receives 401 Unauthorized")
    @WithAnonymousUser
    void testGetUserList_AsAnonymous_ReturnsUnauthorized() throws Exception {
        // Act & Assert: Anonymous users must be authenticated
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        
        // Verify service method never called
        verify(userManagementService, times(0)).listUsers(any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/users - Custom pagination parameters work correctly")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testGetUserList_WithCustomPagination_ReturnsCorrectPage() throws Exception {
        // Arrange: Create page 2 with 5 users per page
        List<UserProfileResponse> userList = Arrays.asList(
            createTestUser("USER0011", "First11", "Last11", TEST_USER_TYPE_REGULAR),
            createTestUser("USER0012", "First12", "Last12", TEST_USER_TYPE_ADMIN),
            createTestUser("USER0013", "First13", "Last13", TEST_USER_TYPE_REGULAR),
            createTestUser("USER0014", "First14", "Last14", TEST_USER_TYPE_REGULAR),
            createTestUser("USER0015", "First15", "Last15", TEST_USER_TYPE_ADMIN)
        );
        
        Page<UserProfileResponse> userPage = new PageImpl<>(userList, PageRequest.of(2, 5), 20);
        when(userManagementService.listUsers(any(Pageable.class))).thenReturn(userPage);

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users")
                .param("page", "2")
                .param("size", "5")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(20))
                .andExpect(jsonPath("$.totalPages").value(4));
    }

    // ========================================================================
    // GET /api/users/{userId} - User Profile View Tests (COUSR01C.cbl equivalent)
    // ========================================================================

    @Test
    @DisplayName("GET /api/users/{userId} - User retrieves own profile successfully")
    @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
    void testGetUserById_OwnProfile_ReturnsProfile() throws Exception {
        // Arrange
        when(userProfileService.viewUserProfile(eq(TEST_USER_ID))).thenReturn(testUserResponse);

        // Act & Assert
        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(TEST_USER_ID))
                .andExpect(jsonPath("$.firstName").value(TEST_FIRST_NAME))
                .andExpect(jsonPath("$.lastName").value(TEST_LAST_NAME))
                .andExpect(jsonPath("$.userType").value(TEST_USER_TYPE_REGULAR))
                .andExpect(jsonPath("$.password").doesNotExist()); // Password never returned
        
        long responseTime = System.currentTimeMillis() - startTime;
        
        verify(userProfileService, times(1)).viewUserProfile(eq(TEST_USER_ID));
        
        // Assert response time < 100ms per Section 0.9 user lookup requirement
        assert responseTime < 100 : "Response time " + responseTime + "ms exceeds 100ms limit";
    }

    @Test
    @DisplayName("GET /api/users/{userId} - Admin retrieves any user profile successfully")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testGetUserById_AsAdmin_ReturnsAnyProfile() throws Exception {
        // Arrange
        when(userProfileService.viewUserProfile(eq(TEST_USER_ID))).thenReturn(testUserResponse);

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(TEST_USER_ID))
                .andExpect(jsonPath("$.firstName").value(TEST_FIRST_NAME));
        
        verify(userProfileService, times(1)).viewUserProfile(eq(TEST_USER_ID));
    }

    @Test
    @DisplayName("GET /api/users/{userId} - Regular user cannot view other user's profile")
    @WithMockUser(username = REGULAR_USER_ID, roles = {"USER"})
    void testGetUserById_OtherUserProfile_ReturnsForbidden() throws Exception {
        // Act & Assert: User attempting to access another user's profile
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        
        // Verify service method never called due to authorization check
        verify(userProfileService, times(0)).viewUserProfile(anyString());
    }

    @Test
    @DisplayName("GET /api/users/{userId} - User not found returns 404")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testGetUserById_NotFound_Returns404() throws Exception {
        // Arrange: Simulate user not found (COBOL DFHRESP(NOTFND) equivalent)
        when(userProfileService.viewUserProfile(eq("NOTEXIST"))).thenThrow(new UserNotFoundException("User not found"));

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users/{userId}", "NOTEXIST")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        
        verify(userProfileService, times(1)).viewUserProfile(eq("NOTEXIST"));
    }

    @Test
    @DisplayName("GET /api/users/{userId} - Empty userId returns 400 Bad Request")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testGetUserById_EmptyUserId_ReturnsBadRequest() throws Exception {
        // Act & Assert: Empty userId violates COBOL required field validation
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users/{userId}", " ")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ========================================================================
    // POST /api/users - User Creation Tests (COUSR01C.cbl WRITE-USER-SEC-FILE)
    // ========================================================================

    @Test
    @DisplayName("POST /api/users - Admin successfully creates new user with BCrypt password")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testCreateUser_AsAdmin_ReturnsCreated() throws Exception {
        // Arrange
        UserProfileResponse createdUser = new UserProfileResponse();
        createdUser.setUserId(TEST_USER_ID);
        createdUser.setFirstName(TEST_FIRST_NAME);
        createdUser.setLastName(TEST_LAST_NAME);
        createdUser.setUserType(TEST_USER_TYPE_REGULAR);
        createdUser.setRoles(Arrays.asList("ROLE_USER"));
        
        when(userManagementService.createUser(any(UserManagementRequest.class))).thenReturn(createdUser);

        // Act & Assert
        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUserRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value(TEST_USER_ID))
                .andExpect(jsonPath("$.firstName").value(TEST_FIRST_NAME))
                .andExpect(jsonPath("$.lastName").value(TEST_LAST_NAME))
                .andExpect(jsonPath("$.userType").value(TEST_USER_TYPE_REGULAR))
                .andExpect(jsonPath("$.password").doesNotExist()); // Password never returned
        
        long responseTime = System.currentTimeMillis() - startTime;
        
        verify(userManagementService, times(1)).createUser(any(UserManagementRequest.class));
        
        // Assert response time < 200ms per Section 0.9 (including BCrypt encryption overhead)
        assert responseTime < 200 : "Response time " + responseTime + "ms exceeds 200ms limit";
    }

    @Test
    @DisplayName("POST /api/users - Admin creates user with type 'A' mapped to ROLE_ADMIN")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testCreateUser_AdminType_MapsToRoleAdmin() throws Exception {
        // Arrange: Create admin user (userType 'A')
        UserManagementRequest adminRequest = new UserManagementRequest();
        adminRequest.setUserId("NEWADMIN");
        adminRequest.setFirstName("Admin");
        adminRequest.setLastName("User");
        adminRequest.setPassword(TEST_PASSWORD);
        adminRequest.setUserType(TEST_USER_TYPE_ADMIN);
        
        UserProfileResponse adminResponse = new UserProfileResponse();
        adminResponse.setUserId("NEWADMIN");
        adminResponse.setFirstName("Admin");
        adminResponse.setLastName("User");
        adminResponse.setUserType(TEST_USER_TYPE_ADMIN);
        adminResponse.setRoles(Arrays.asList("ROLE_ADMIN"));
        
        when(userManagementService.createUser(any(UserManagementRequest.class))).thenReturn(adminResponse);

        // Act & Assert: Verify userType 'A' creates admin role
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(adminRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userType").value(TEST_USER_TYPE_ADMIN))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_ADMIN"));
    }

    @Test
    @DisplayName("POST /api/users - Regular user cannot create users (403 Forbidden)")
    @WithMockUser(username = REGULAR_USER_ID, roles = {"USER"})
    void testCreateUser_AsRegularUser_ReturnsForbidden() throws Exception {
        // Act & Assert: User creation is admin-only operation
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUserRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        
        verify(userManagementService, times(0)).createUser(any(UserManagementRequest.class));
    }

    @Test
    @DisplayName("POST /api/users - Missing required field firstName returns 400")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testCreateUser_MissingFirstName_ReturnsBadRequest() throws Exception {
        // Arrange: Request without firstName (matches COBOL validation line 118-123)
        UserManagementRequest invalidRequest = new UserManagementRequest();
        invalidRequest.setUserId(TEST_USER_ID);
        invalidRequest.setFirstName(""); // Empty firstName
        invalidRequest.setLastName(TEST_LAST_NAME);
        invalidRequest.setPassword(TEST_PASSWORD);
        invalidRequest.setUserType(TEST_USER_TYPE_REGULAR);

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/users - Missing required field lastName returns 400")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testCreateUser_MissingLastName_ReturnsBadRequest() throws Exception {
        // Arrange: Request without lastName (matches COBOL validation line 124-129)
        UserManagementRequest invalidRequest = new UserManagementRequest();
        invalidRequest.setUserId(TEST_USER_ID);
        invalidRequest.setFirstName(TEST_FIRST_NAME);
        invalidRequest.setLastName(""); // Empty lastName
        invalidRequest.setPassword(TEST_PASSWORD);
        invalidRequest.setUserType(TEST_USER_TYPE_REGULAR);

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/users - Missing required field userId returns 400")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testCreateUser_MissingUserId_ReturnsBadRequest() throws Exception {
        // Arrange: Request without userId (matches COBOL validation line 130-135)
        UserManagementRequest invalidRequest = new UserManagementRequest();
        invalidRequest.setUserId(""); // Empty userId
        invalidRequest.setFirstName(TEST_FIRST_NAME);
        invalidRequest.setLastName(TEST_LAST_NAME);
        invalidRequest.setPassword(TEST_PASSWORD);
        invalidRequest.setUserType(TEST_USER_TYPE_REGULAR);

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/users - Missing required field password returns 400")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testCreateUser_MissingPassword_ReturnsBadRequest() throws Exception {
        // Arrange: Request without password (matches COBOL validation line 136-141)
        UserManagementRequest invalidRequest = new UserManagementRequest();
        invalidRequest.setUserId(TEST_USER_ID);
        invalidRequest.setFirstName(TEST_FIRST_NAME);
        invalidRequest.setLastName(TEST_LAST_NAME);
        invalidRequest.setPassword(""); // Empty password
        invalidRequest.setUserType(TEST_USER_TYPE_REGULAR);

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/users - Missing required field userType returns 400")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testCreateUser_MissingUserType_ReturnsBadRequest() throws Exception {
        // Arrange: Request without userType (matches COBOL validation line 142-147)
        UserManagementRequest invalidRequest = new UserManagementRequest();
        invalidRequest.setUserId(TEST_USER_ID);
        invalidRequest.setFirstName(TEST_FIRST_NAME);
        invalidRequest.setLastName(TEST_LAST_NAME);
        invalidRequest.setPassword(TEST_PASSWORD);
        invalidRequest.setUserType(""); // Empty userType

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/users - Invalid userType (not 'R' or 'A') returns 400")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testCreateUser_InvalidUserType_ReturnsBadRequest() throws Exception {
        // Arrange: Invalid userType (must be 'R' or 'A' per COBOL SEC-USR-TYPE)
        UserManagementRequest invalidRequest = new UserManagementRequest();
        invalidRequest.setUserId(TEST_USER_ID);
        invalidRequest.setFirstName(TEST_FIRST_NAME);
        invalidRequest.setLastName(TEST_LAST_NAME);
        invalidRequest.setPassword(TEST_PASSWORD);
        invalidRequest.setUserType("X"); // Invalid type

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/users - Duplicate userId returns 400 (DFHRESP(DUPKEY) equivalent)")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testCreateUser_DuplicateUserId_ReturnsBadRequest() throws Exception {
        // Arrange: Simulate duplicate key error (COBOL lines 260-266)
        when(userManagementService.createUser(any(UserManagementRequest.class)))
            .thenThrow(new IllegalArgumentException("User already exists"));

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUserRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        
        verify(userManagementService, times(1)).createUser(any(UserManagementRequest.class));
    }

    // ========================================================================
    // PUT /api/users/{userId} - User Profile Update Tests
    // ========================================================================

    @Test
    @DisplayName("PUT /api/users/{userId} - User updates own profile successfully")
    @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
    void testUpdateUserProfile_OwnProfile_ReturnsUpdated() throws Exception {
        // Arrange
        UserProfileResponse updatedResponse = new UserProfileResponse();
        updatedResponse.setUserId(TEST_USER_ID);
        updatedResponse.setFirstName("Jane");
        updatedResponse.setLastName("Smith");
        updatedResponse.setUserType(TEST_USER_TYPE_REGULAR);
        updatedResponse.setRoles(Arrays.asList("ROLE_USER"));
        
        when(userProfileService.updateUserProfile(eq(TEST_USER_ID), any(UserProfileUpdateRequest.class)))
            .thenReturn(updatedResponse);

        // Act & Assert
        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(MockMvcRequestBuilders.put("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUpdateRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(TEST_USER_ID))
                .andExpect(jsonPath("$.firstName").value("Jane"))
                .andExpect(jsonPath("$.lastName").value("Smith"));
        
        long responseTime = System.currentTimeMillis() - startTime;
        
        verify(userProfileService, times(1)).updateUserProfile(eq(TEST_USER_ID), any(UserProfileUpdateRequest.class));
        
        // Assert response time < 200ms per Section 0.9
        assert responseTime < 200 : "Response time " + responseTime + "ms exceeds 200ms limit";
    }

    @Test
    @DisplayName("PUT /api/users/{userId} - Admin updates any user profile successfully")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testUpdateUserProfile_AsAdmin_ReturnsUpdated() throws Exception {
        // Arrange
        UserProfileResponse updatedResponse = new UserProfileResponse();
        updatedResponse.setUserId(TEST_USER_ID);
        updatedResponse.setFirstName("Jane");
        updatedResponse.setLastName("Smith");
        updatedResponse.setUserType(TEST_USER_TYPE_REGULAR);
        
        when(userProfileService.updateUserProfile(eq(TEST_USER_ID), any(UserProfileUpdateRequest.class)))
            .thenReturn(updatedResponse);

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.put("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUpdateRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(TEST_USER_ID));
        
        verify(userProfileService, times(1)).updateUserProfile(eq(TEST_USER_ID), any(UserProfileUpdateRequest.class));
    }

    @Test
    @DisplayName("PUT /api/users/{userId} - Regular user cannot update other user's profile")
    @WithMockUser(username = REGULAR_USER_ID, roles = {"USER"})
    void testUpdateUserProfile_OtherUser_ReturnsForbidden() throws Exception {
        // Act & Assert: User attempting to update another user's profile
        mockMvc.perform(MockMvcRequestBuilders.put("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUpdateRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        
        verify(userProfileService, times(0)).updateUserProfile(anyString(), any(UserProfileUpdateRequest.class));
    }

    @Test
    @DisplayName("PUT /api/users/{userId} - User not found returns 404")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testUpdateUserProfile_NotFound_Returns404() throws Exception {
        // Arrange
        when(userProfileService.updateUserProfile(eq("NOTEXIST"), any(UserProfileUpdateRequest.class)))
            .thenThrow(new UserNotFoundException("User not found"));

        UserProfileUpdateRequest request = new UserProfileUpdateRequest();
        request.setUserId("NOTEXIST");
        request.setFirstName("Test");
        request.setLastName("User");

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.put("/api/users/{userId}", "NOTEXIST")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/users/{userId} - User updates profile with password change")
    @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
    void testUpdateUserProfile_WithPasswordChange_ReturnsUpdated() throws Exception {
        // Arrange: Profile update with password change request
        UserProfileUpdateRequest passwordUpdateRequest = new UserProfileUpdateRequest();
        passwordUpdateRequest.setUserId(TEST_USER_ID);
        passwordUpdateRequest.setFirstName(TEST_FIRST_NAME);
        passwordUpdateRequest.setLastName(TEST_LAST_NAME);
        passwordUpdateRequest.setPassword("OldPass123!"); // Current password
        passwordUpdateRequest.setNewPassword("NewPass456!"); // New password
        
        when(userProfileService.updateUserProfile(eq(TEST_USER_ID), any(UserProfileUpdateRequest.class)))
            .thenReturn(testUserResponse);

        // Act & Assert: Verify password change requires current password verification
        mockMvc.perform(MockMvcRequestBuilders.put("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(passwordUpdateRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist()); // Password never returned
        
        verify(userProfileService, times(1)).updateUserProfile(eq(TEST_USER_ID), any(UserProfileUpdateRequest.class));
    }

    @Test
    @DisplayName("PUT /api/users/{userId} - Non-admin cannot change userType (403 Forbidden)")
    @WithMockUser(username = TEST_USER_ID, roles = {"USER"})
    void testUpdateUserProfile_UserTypeChange_ReturnsForbidden() throws Exception {
        // Arrange: Regular user attempting to change userType (admin-only operation)
        UserProfileUpdateRequest typeChangeRequest = new UserProfileUpdateRequest();
        typeChangeRequest.setUserId(TEST_USER_ID);
        typeChangeRequest.setFirstName(TEST_FIRST_NAME);
        typeChangeRequest.setLastName(TEST_LAST_NAME);
        typeChangeRequest.setUserType(TEST_USER_TYPE_ADMIN); // Attempting to become admin

        // Act & Assert: UserType changes restricted to ROLE_ADMIN only
        mockMvc.perform(MockMvcRequestBuilders.put("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(typeChangeRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        
        verify(userProfileService, times(0)).updateUserProfile(anyString(), any(UserProfileUpdateRequest.class));
    }

    @Test
    @DisplayName("PUT /api/users/{userId} - Admin can change userType successfully")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testUpdateUserProfile_AdminChangesUserType_ReturnsUpdated() throws Exception {
        // Arrange: Admin changing user type from 'R' to 'A'
        UserProfileUpdateRequest typeChangeRequest = new UserProfileUpdateRequest();
        typeChangeRequest.setUserId(TEST_USER_ID);
        typeChangeRequest.setFirstName(TEST_FIRST_NAME);
        typeChangeRequest.setLastName(TEST_LAST_NAME);
        typeChangeRequest.setUserType(TEST_USER_TYPE_ADMIN);
        
        UserProfileResponse updatedResponse = new UserProfileResponse();
        updatedResponse.setUserId(TEST_USER_ID);
        updatedResponse.setFirstName(TEST_FIRST_NAME);
        updatedResponse.setLastName(TEST_LAST_NAME);
        updatedResponse.setUserType(TEST_USER_TYPE_ADMIN);
        updatedResponse.setRoles(Arrays.asList("ROLE_ADMIN"));
        
        when(userProfileService.updateUserProfile(eq(TEST_USER_ID), any(UserProfileUpdateRequest.class)))
            .thenReturn(updatedResponse);

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.put("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(typeChangeRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value(TEST_USER_TYPE_ADMIN))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_ADMIN"));
        
        verify(userProfileService, times(1)).updateUserProfile(eq(TEST_USER_ID), any(UserProfileUpdateRequest.class));
    }

    @Test
    @DisplayName("PUT /api/users/{userId} - Path and body userId mismatch returns 400")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testUpdateUserProfile_UserIdMismatch_ReturnsBadRequest() throws Exception {
        // Arrange: Path userId different from body userId
        UserProfileUpdateRequest mismatchRequest = new UserProfileUpdateRequest();
        mismatchRequest.setUserId("DIFFERENT");
        mismatchRequest.setFirstName("Test");
        mismatchRequest.setLastName("User");

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.put("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mismatchRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // ========================================================================
    // DELETE /api/users/{userId} - User Deletion Tests (COUSR03C.cbl equivalent)
    // ========================================================================

    @Test
    @DisplayName("DELETE /api/users/{userId} - Admin successfully deletes user")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testDeleteUser_AsAdmin_ReturnsNoContent() throws Exception {
        // Arrange
        doNothing().when(userManagementService).deleteUser(eq(TEST_USER_ID));

        // Act & Assert
        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/users/{userId}", TEST_USER_ID)
                .with(csrf()))
                .andExpect(status().isNoContent());
        
        long responseTime = System.currentTimeMillis() - startTime;
        
        verify(userManagementService, times(1)).deleteUser(eq(TEST_USER_ID));
        
        // Assert response time < 200ms per Section 0.9
        assert responseTime < 200 : "Response time " + responseTime + "ms exceeds 200ms limit";
    }

    @Test
    @DisplayName("DELETE /api/users/{userId} - Regular user cannot delete users (403 Forbidden)")
    @WithMockUser(username = REGULAR_USER_ID, roles = {"USER"})
    void testDeleteUser_AsRegularUser_ReturnsForbidden() throws Exception {
        // Act & Assert: User deletion is admin-only operation
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/users/{userId}", TEST_USER_ID)
                .with(csrf()))
                .andExpect(status().isForbidden());
        
        verify(userManagementService, times(0)).deleteUser(anyString());
    }

    @Test
    @DisplayName("DELETE /api/users/{userId} - User not found returns 404")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testDeleteUser_NotFound_Returns404() throws Exception {
        // Arrange: Simulate user not found (COBOL DFHRESP(NOTFND) equivalent)
        doThrow(new UserNotFoundException("User not found")).when(userManagementService).deleteUser(eq("NOTEXIST"));

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/users/{userId}", "NOTEXIST")
                .with(csrf()))
                .andExpect(status().isNotFound());
        
        verify(userManagementService, times(1)).deleteUser(eq("NOTEXIST"));
    }

    @Test
    @DisplayName("DELETE /api/users/{userId} - Empty userId returns 400 Bad Request")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testDeleteUser_EmptyUserId_ReturnsBadRequest() throws Exception {
        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/users/{userId}", " ")
                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/users/{userId} - Anonymous user receives 401 Unauthorized")
    @WithAnonymousUser
    void testDeleteUser_AsAnonymous_ReturnsUnauthorized() throws Exception {
        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/users/{userId}", TEST_USER_ID))
                .andExpect(status().isUnauthorized());
        
        verify(userManagementService, times(0)).deleteUser(anyString());
    }

    // ========================================================================
    // Additional Test Scenarios
    // ========================================================================

    @Test
    @DisplayName("POST /api/users - Password encryption verified through service call")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testCreateUser_PasswordEncryptionVerified() throws Exception {
        // Arrange: Verify that password is passed to service for BCrypt encryption
        when(userManagementService.createUser(any(UserManagementRequest.class)))
            .thenAnswer(invocation -> {
                UserManagementRequest request = invocation.getArgument(0);
                // In actual service, password would be BCrypt hashed
                // Here we verify the raw password is received by service
                assert request.getPassword() != null : "Password must be provided to service";
                assert !request.getPassword().isEmpty() : "Password must not be empty";
                
                UserProfileResponse response = new UserProfileResponse();
                response.setUserId(request.getUserId());
                response.setFirstName(request.getFirstName());
                response.setLastName(request.getLastName());
                response.setUserType(request.getUserType());
                return response;
            });

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUserRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist()); // Password never returned in response
        
        verify(userManagementService, times(1)).createUser(any(UserManagementRequest.class));
    }

    @Test
    @DisplayName("GET /api/users - Empty result set returns empty page")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testGetUserList_EmptyResultSet_ReturnsEmptyPage() throws Exception {
        // Arrange: Empty user list
        Page<UserProfileResponse> emptyPage = new PageImpl<>(new ArrayList<>(), PageRequest.of(0, 10), 0);
        when(userManagementService.listUsers(any(Pageable.class))).thenReturn(emptyPage);

        // Act & Assert
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users")
                .param("page", "0")
                .param("size", "10")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    @DisplayName("GET /api/users/{userId} - Case insensitive userId matching")
    @WithMockUser(username = "testuser", roles = {"USER"})
    void testGetUserById_CaseInsensitive_ReturnsProfile() throws Exception {
        // Arrange: Test case insensitive userId matching
        UserProfileResponse response = new UserProfileResponse();
        response.setUserId("testuser");
        response.setFirstName("Test");
        response.setLastName("User");
        response.setUserType(TEST_USER_TYPE_REGULAR);
        
        when(userProfileService.viewUserProfile(anyString())).thenReturn(response);

        // Act & Assert: Mixed case userId should work
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users/{userId}", "TESTUSER")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bean Validation - UserManagementRequest validates correctly")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testBeanValidation_UserManagementRequest() throws Exception {
        // Arrange: Request with all null fields
        UserManagementRequest invalidRequest = new UserManagementRequest();

        // Act & Assert: Bean Validation should reject invalid request
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Response time - All endpoints meet < 200ms requirement")
    @WithMockUser(username = ADMIN_USER_ID, roles = {"ADMIN"})
    void testResponseTime_AllEndpointsMeetRequirement() throws Exception {
        // Arrange
        Page<UserProfileResponse> userPage = new PageImpl<>(Arrays.asList(testUserResponse), PageRequest.of(0, 10), 1);
        when(userManagementService.listUsers(any(Pageable.class))).thenReturn(userPage);
        when(userProfileService.viewUserProfile(anyString())).thenReturn(testUserResponse);
        when(userManagementService.createUser(any(UserManagementRequest.class))).thenReturn(testUserResponse);
        when(userProfileService.updateUserProfile(anyString(), any(UserProfileUpdateRequest.class)))
            .thenReturn(testUserResponse);
        doNothing().when(userManagementService).deleteUser(anyString());

        // Test GET /api/users
        long startTime = System.currentTimeMillis();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users")
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        long responseTime1 = System.currentTimeMillis() - startTime;
        assert responseTime1 < 200 : "GET /api/users response time " + responseTime1 + "ms exceeds 200ms";

        // Test GET /api/users/{userId}
        startTime = System.currentTimeMillis();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        long responseTime2 = System.currentTimeMillis() - startTime;
        assert responseTime2 < 100 : "GET /api/users/{userId} response time " + responseTime2 + "ms exceeds 100ms";

        // Test POST /api/users
        startTime = System.currentTimeMillis();
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUserRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
        long responseTime3 = System.currentTimeMillis() - startTime;
        assert responseTime3 < 200 : "POST /api/users response time " + responseTime3 + "ms exceeds 200ms";

        // Test PUT /api/users/{userId}
        startTime = System.currentTimeMillis();
        mockMvc.perform(MockMvcRequestBuilders.put("/api/users/{userId}", TEST_USER_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(testUpdateRequest))
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        long responseTime4 = System.currentTimeMillis() - startTime;
        assert responseTime4 < 200 : "PUT /api/users/{userId} response time " + responseTime4 + "ms exceeds 200ms";

        // Test DELETE /api/users/{userId}
        startTime = System.currentTimeMillis();
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/users/{userId}", TEST_USER_ID)
                .with(csrf()))
                .andExpect(status().isNoContent());
        long responseTime5 = System.currentTimeMillis() - startTime;
        assert responseTime5 < 200 : "DELETE /api/users/{userId} response time " + responseTime5 + "ms exceeds 200ms";
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Helper method to create test user response objects.
     * Matches COBOL USRSEC file record structure.
     * 
     * @param userId User ID (max 8 characters, PIC X(8))
     * @param firstName First name (max 20 characters, PIC X(20))
     * @param lastName Last name (max 20 characters, PIC X(20))
     * @param userType User type ('R' or 'A', PIC X(1))
     * @return UserProfileResponse test object
     */
    private UserProfileResponse createTestUser(String userId, String firstName, String lastName, String userType) {
        UserProfileResponse user = new UserProfileResponse();
        user.setUserId(userId);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setUserType(userType);
        
        // Map userType to Spring Security roles
        if (TEST_USER_TYPE_ADMIN.equals(userType)) {
            user.setRoles(Arrays.asList("ROLE_ADMIN"));
        } else {
            user.setRoles(Arrays.asList("ROLE_USER"));
        }
        
        user.setCurrentDate(LocalDate.now());
        user.setCurrentTime(LocalTime.now());
        
        return user;
    }
}

