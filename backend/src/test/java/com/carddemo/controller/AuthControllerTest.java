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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.carddemo.controller;

import com.carddemo.exception.BusinessException;
import com.carddemo.model.dto.AuthRequest;
import com.carddemo.model.dto.AuthResponse;
import com.carddemo.security.JwtAuthenticationFilter;
import com.carddemo.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JUnit 5 test class for AuthController REST endpoints.
 * 
 * <p>Tests authentication functionality converted from COBOL program COSGN00C.cbl
 * signon screen authentication logic to REST API endpoints.
 * 
 * <p><b>Conversion Source:</b>
 * <ul>
 *   <li>COBOL Program: COSGN00C.cbl (lines 72-257)</li>
 *   <li>BMS Map: COSGN00.bms (signon screen definition)</li>
 *   <li>Copybook: CSUSR01Y.cpy (user security record layout)</li>
 * </ul>
 * 
 * <p><b>COBOL Authentication Logic Tested:</b>
 * <pre>
 * PROCESS-ENTER-KEY. (lines 108-140)
 *     WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
 *         MOVE 'Please enter User ID ...' TO WS-MESSAGE          [lines 118-122]
 *     WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
 *         MOVE 'Please enter Password ...' TO WS-MESSAGE         [lines 123-127]
 * 
 * READ-USER-SEC-FILE. (lines 209-257)
 *     WHEN 0
 *         IF SEC-USR-PWD = WS-USER-PWD                           [line 223]
 *             MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE               [line 227]
 *             IF CDEMO-USRTYP-ADMIN                              [line 230]
 *                 EXEC CICS XCTL PROGRAM('COADM01C') END-EXEC    [lines 231-234]
 *             ELSE
 *                 EXEC CICS XCTL PROGRAM('COMEN01C') END-EXEC    [lines 236-239]
 *         ELSE
 *             MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE [line 242]
 *     WHEN 13
 *         MOVE 'User not found. Try again ...' TO WS-MESSAGE     [line 249]
 * </pre>
 * 
 * <p><b>Test Coverage:</b>
 * <ul>
 *   <li>POST /api/auth/login - Successful authentication with JWT token</li>
 *   <li>POST /api/auth/login - Empty userId validation (COBOL lines 118-122)</li>
 *   <li>POST /api/auth/login - Empty password validation (COBOL lines 123-127)</li>
 *   <li>POST /api/auth/login - User not found error (COBOL line 249 RESP 13)</li>
 *   <li>POST /api/auth/login - Wrong password error (COBOL line 242)</li>
 *   <li>POST /api/auth/login - Admin user type determination (COBOL lines 230-234)</li>
 *   <li>POST /api/auth/login - Regular user type determination (COBOL lines 236-239)</li>
 *   <li>POST /api/auth/logout - Successful logout</li>
 * </ul>
 * 
 * <p><b>Testing Strategy:</b>
 * <ul>
 *   <li>Uses @WebMvcTest for Spring MVC test slice (controller layer only)</li>
 *   <li>MockMvc for HTTP request/response testing without full HTTP server</li>
 *   <li>@MockBean for AuthService to isolate controller testing</li>
 *   <li>Mockito for stubbing service behavior and verifying interactions</li>
 *   <li>ObjectMapper for JSON serialization/deserialization</li>
 *   <li>Hamcrest matchers for expressive assertions</li>
 * </ul>
 * 
 * <p><b>Validation Approach:</b>
 * <ul>
 *   <li>Test HTTP status codes (200 OK, 400 Bad Request, 401 Unauthorized, 204 No Content)</li>
 *   <li>Test JSON response structure and field values</li>
 *   <li>Test exact COBOL error messages are preserved</li>
 *   <li>Test JWT token generation and presence in response</li>
 *   <li>Test user type determination (Admin vs Regular user)</li>
 *   <li>Verify service method invocations using Mockito.verify()</li>
 * </ul>
 * 
 * <p><b>Performance Requirements (Section 0.7.7):</b>
 * <ul>
 *   <li>Authentication requests MUST complete in sub-200ms response time</li>
 *   <li>These are unit tests focusing on controller logic only</li>
 *   <li>Performance testing is conducted separately with load testing tools</li>
 * </ul>
 * 
 * @see com.carddemo.controller.AuthController
 * @see com.carddemo.service.AuthService
 * @see com.carddemo.model.dto.AuthRequest
 * @see com.carddemo.model.dto.AuthResponse
 * @author CardDemo Migration Team
 * @version 1.0.0
 * @since 2024-01-01
 */
@WebMvcTest(controllers = AuthController.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        ))
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    /**
     * MockMvc instance for performing HTTP requests against AuthController.
     * 
     * Injected by Spring Test framework, configured with @WebMvcTest annotation.
     * Provides fluent API for HTTP request construction and response assertions.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * ObjectMapper for JSON serialization/deserialization.
     * 
     * Used to convert AuthRequest DTOs to JSON strings for request bodies
     * and parse JSON responses to objects for assertions.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mocked AuthService for isolated controller testing.
     * 
     * Uses @MockBean to replace actual AuthService bean in application context.
     * Behavior is stubbed using Mockito.when() in each test method.
     */
    @MockBean
    private AuthService authService;

    /**
     * Test data: Valid authentication request for regular user.
     * Initialized in @BeforeEach setup method.
     */
    private AuthRequest validUserRequest;

    /**
     * Test data: Valid authentication request for admin user.
     * Initialized in @BeforeEach setup method.
     */
    private AuthRequest validAdminRequest;

    /**
     * Test data: Expected authentication response for regular user.
     * Initialized in @BeforeEach setup method.
     */
    private AuthResponse expectedUserResponse;

    /**
     * Test data: Expected authentication response for admin user.
     * Initialized in @BeforeEach setup method.
     */
    private AuthResponse expectedAdminResponse;

    /**
     * Setup method executed before each test.
     * 
     * Initializes test data including AuthRequest objects and expected AuthResponse values.
     * Creates consistent test fixtures to ensure reproducible test execution.
     */
    @BeforeEach
    void setUp() {
        // Initialize valid authentication request for regular user
        // Maps to COBOL USERIDI and PASSWDI fields from COSGN00.bms
        validUserRequest = AuthRequest.builder()
                .userId("B0001")
                .password("PASS1234")
                .build();

        // Initialize valid authentication request for admin user
        // Admin user identification based on COBOL CDEMO-USRTYP-ADMIN check (line 230)
        validAdminRequest = AuthRequest.builder()
                .userId("ADMIN001")
                .password("ADMINPAS")
                .build();

        // Initialize expected authentication response for regular user
        // UserType "U" corresponds to COBOL SEC-USR-TYPE (line 227)
        // Regular users route to COMEN01C program (lines 236-239)
        expectedUserResponse = new AuthResponse(
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJCMDAwMSJ9.test",
                3600L,
                "B0001",
                "U"
        );

        // Initialize expected authentication response for admin user
        // UserType "A" corresponds to COBOL CDEMO-USRTYP-ADMIN (line 230)
        // Admin users route to COADM01C program (lines 231-234)
        expectedAdminResponse = new AuthResponse(
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJBRE1JTjAwMSJ9.admin",
                3600L,
                "ADMIN001",
                "A"
        );
    }

    /**
     * Test successful login with valid credentials for regular user.
     * 
     * <p>Tests the complete COBOL authentication flow from COSGN00C.cbl:
     * <ul>
     *   <li>EXEC CICS RECEIVE MAP('COSGN0A') (lines 110-115) → POST /api/auth/login JSON</li>
     *   <li>User ID and password validation passes (lines 118-127)</li>
     *   <li>EXEC CICS READ FILE('USRSEC') RIDFLD(WS-USER-ID) (lines 211-219) → AuthService.authenticate()</li>
     *   <li>Password matches: IF SEC-USR-PWD = WS-USER-PWD (line 223) → BCrypt validation</li>
     *   <li>User type is 'U': ELSE branch (lines 236-239) → userType in AuthResponse</li>
     *   <li>EXEC CICS XCTL PROGRAM('COMEN01C') (lines 236-239) → JWT token in response</li>
     * </ul>
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 200 OK status</li>
     *   <li>JSON response with token, expiresIn, userId, userType fields</li>
     *   <li>JWT token is not null</li>
     *   <li>ExpiresIn is 3600 seconds (1 hour)</li>
     *   <li>UserId is "B0001"</li>
     *   <li>UserType is "U" (regular user)</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginSuccess_RegularUser() throws Exception {
        // Arrange: Stub AuthService.authenticate() to return expected response
        when(authService.authenticate(anyString(), anyString()))
                .thenReturn(expectedUserResponse);

        // Act & Assert: Perform POST /api/auth/login and validate response
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUserRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token", is(notNullValue())))
                .andExpect(jsonPath("$.token", is(expectedUserResponse.token())))
                .andExpect(jsonPath("$.expiresIn", is(3600)))
                .andExpect(jsonPath("$.userId", is("B0001")))
                .andExpect(jsonPath("$.userType", is("U")));

        // Verify: AuthService.authenticate() was called once with correct parameters
        verify(authService, times(1)).authenticate("B0001", "PASS1234");
    }

    /**
     * Test successful login with valid credentials for admin user.
     * 
     * <p>Tests the COBOL admin user determination logic from COSGN00C.cbl:
     * <ul>
     *   <li>EXEC CICS READ FILE('USRSEC') succeeds (lines 211-219)</li>
     *   <li>Password matches: IF SEC-USR-PWD = WS-USER-PWD (line 223)</li>
     *   <li>MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE (line 227)</li>
     *   <li>IF CDEMO-USRTYP-ADMIN (line 230) → userType "A"</li>
     *   <li>EXEC CICS XCTL PROGRAM('COADM01C') (lines 231-234) → Admin JWT token</li>
     * </ul>
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 200 OK status</li>
     *   <li>JSON response with admin-specific userType</li>
     *   <li>UserType is "A" (admin user)</li>
     *   <li>JWT token contains admin role for authorization</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginSuccess_AdminUser() throws Exception {
        // Arrange: Stub AuthService.authenticate() to return admin response
        when(authService.authenticate(anyString(), anyString()))
                .thenReturn(expectedAdminResponse);

        // Act & Assert: Perform POST /api/auth/login and validate admin response
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validAdminRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token", is(notNullValue())))
                .andExpect(jsonPath("$.token", is(expectedAdminResponse.token())))
                .andExpect(jsonPath("$.expiresIn", is(3600)))
                .andExpect(jsonPath("$.userId", is("ADMIN001")))
                .andExpect(jsonPath("$.userType", is("A")));

        // Verify: AuthService.authenticate() was called once
        verify(authService, times(1)).authenticate("ADMIN001", "ADMINPAS");
    }

    /**
     * Test login failure with empty userId.
     * 
     * <p>Tests the COBOL userId validation logic from COSGN00C.cbl lines 118-122:
     * <pre>
     * WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
     *     MOVE 'Y'      TO WS-ERR-FLG
     *     MOVE 'Please enter User ID ...' TO WS-MESSAGE
     *     MOVE -1       TO USERIDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>In Java/Spring Boot, this validation is handled by Bean Validation
     * @NotBlank annotation on AuthRequest.userId field, which triggers
     * HTTP 400 Bad Request before the controller method is invoked.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 400 Bad Request status</li>
     *   <li>JSON error response with validation message</li>
     *   <li>Error message: "User ID is required" (Bean Validation message)</li>
     *   <li>AuthService.authenticate() is NOT called (validation fails first)</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginFailure_EmptyUserId() throws Exception {
        // Arrange: Create request with empty userId
        AuthRequest invalidRequest = AuthRequest.builder()
                .userId("")
                .password("PASS1234")
                .build();

        // Act & Assert: Perform POST /api/auth/login and validate validation error
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("validation failed")));

        // Verify: AuthService.authenticate() was NOT called (validation failed)
        verify(authService, times(0)).authenticate(anyString(), anyString());
    }

    /**
     * Test login failure with null userId.
     * 
     * <p>Tests the COBOL userId validation logic from COSGN00C.cbl lines 118-122
     * for null/spaces case (LOW-VALUES condition in COBOL).
     * 
     * <p>Bean Validation @NotBlank handles both null and empty string validation.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 400 Bad Request status</li>
     *   <li>JSON error response with validation message</li>
     *   <li>Error message indicates userId is required</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginFailure_NullUserId() throws Exception {
        // Arrange: Create request with null userId
        AuthRequest invalidRequest = AuthRequest.builder()
                .userId(null)
                .password("PASS1234")
                .build();

        // Act & Assert: Perform POST /api/auth/login and validate validation error
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("validation failed")));

        // Verify: AuthService.authenticate() was NOT called
        verify(authService, times(0)).authenticate(anyString(), anyString());
    }

    /**
     * Test login failure with empty password.
     * 
     * <p>Tests the COBOL password validation logic from COSGN00C.cbl lines 123-127:
     * <pre>
     * WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
     *     MOVE 'Y'      TO WS-ERR-FLG
     *     MOVE 'Please enter Password ...' TO WS-MESSAGE
     *     MOVE -1       TO PASSWDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>In Java/Spring Boot, this validation is handled by Bean Validation
     * @NotBlank annotation on AuthRequest.password field.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 400 Bad Request status</li>
     *   <li>JSON error response with validation message</li>
     *   <li>Error message: "Password is required" (Bean Validation message)</li>
     *   <li>AuthService.authenticate() is NOT called (validation fails first)</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginFailure_EmptyPassword() throws Exception {
        // Arrange: Create request with empty password
        AuthRequest invalidRequest = AuthRequest.builder()
                .userId("B0001")
                .password("")
                .build();

        // Act & Assert: Perform POST /api/auth/login and validate validation error
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("validation failed")));

        // Verify: AuthService.authenticate() was NOT called
        verify(authService, times(0)).authenticate(anyString(), anyString());
    }

    /**
     * Test login failure with null password.
     * 
     * <p>Tests the COBOL password validation logic from COSGN00C.cbl lines 123-127
     * for null/spaces case (LOW-VALUES condition in COBOL).
     * 
     * <p>Bean Validation @NotBlank handles both null and empty string validation.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 400 Bad Request status</li>
     *   <li>JSON error response with validation message</li>
     *   <li>Error message indicates password is required</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginFailure_NullPassword() throws Exception {
        // Arrange: Create request with null password
        AuthRequest invalidRequest = AuthRequest.builder()
                .userId("B0001")
                .password(null)
                .build();

        // Act & Assert: Perform POST /api/auth/login and validate validation error
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("validation failed")));

        // Verify: AuthService.authenticate() was NOT called
        verify(authService, times(0)).authenticate(anyString(), anyString());
    }

    /**
     * Test login failure with user not found.
     * 
     * <p>Tests the COBOL user not found logic from COSGN00C.cbl lines 247-251:
     * <pre>
     * WHEN 13
     *     MOVE 'Y'      TO WS-ERR-FLG
     *     MOVE 'User not found. Try again ...' TO WS-MESSAGE
     *     MOVE -1       TO USERIDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>In COBOL, RESP code 13 indicates record not found from EXEC CICS READ.
     * In Java/Spring Boot, AuthService throws BusinessException with exact
     * COBOL error message when UserSecurityRepository.findById() returns empty.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 401 Unauthorized status</li>
     *   <li>JSON error response with exact COBOL message</li>
     *   <li>Error message: "User not found. Try again ..." (exact COBOL text from line 249)</li>
     *   <li>Error details: Same message for consistency</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginFailure_UserNotFound() throws Exception {
        // Arrange: Stub AuthService.authenticate() to throw BusinessException
        // with exact COBOL error message from line 249
        when(authService.authenticate(anyString(), anyString()))
                .thenThrow(new BusinessException("User not found. Try again ..."));

        // Act & Assert: Perform POST /api/auth/login and validate error response
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUserRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", is("User not found. Try again ...")))
                .andExpect(jsonPath("$.details", is("User not found. Try again ...")))
                .andExpect(jsonPath("$.path", is("/api/auth/login")));

        // Verify: AuthService.authenticate() was called once
        verify(authService, times(1)).authenticate("B0001", "PASS1234");
    }

    /**
     * Test login failure with wrong password.
     * 
     * <p>Tests the COBOL wrong password logic from COSGN00C.cbl lines 241-246:
     * <pre>
     * ELSE
     *     MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *     MOVE -1       TO PASSWDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * END-IF
     * </pre>
     * 
     * <p>In COBOL, this else branch executes when SEC-USR-PWD ≠ WS-USER-PWD (line 223).
     * In Java/Spring Boot, AuthService throws BusinessException with exact COBOL
     * error message when BCrypt password validation fails.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 401 Unauthorized status</li>
     *   <li>JSON error response with exact COBOL message</li>
     *   <li>Error message: "Wrong Password. Try again ..." (exact COBOL text from line 242)</li>
     *   <li>Error details: Same message for consistency</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginFailure_WrongPassword() throws Exception {
        // Arrange: Stub AuthService.authenticate() to throw BusinessException
        // with exact COBOL error message from line 242
        when(authService.authenticate(anyString(), anyString()))
                .thenThrow(new BusinessException("Wrong Password. Try again ..."));

        // Act & Assert: Perform POST /api/auth/login and validate error response
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUserRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")))
                .andExpect(jsonPath("$.message", is("Wrong Password. Try again ...")))
                .andExpect(jsonPath("$.details", is("Wrong Password. Try again ...")))
                .andExpect(jsonPath("$.path", is("/api/auth/login")));

        // Verify: AuthService.authenticate() was called once
        verify(authService, times(1)).authenticate("B0001", "PASS1234");
    }

    /**
     * Test successful logout.
     * 
     * <p>Tests the logout functionality corresponding to COBOL COMEN01C.cbl
     * PF3 key handling (F3 function key for logout):
     * <pre>
     * WHEN DFHPF3
     *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *     PERFORM SEND-PLAIN-TEXT
     *     EXEC CICS RETURN END-EXEC
     * </pre>
     * 
     * <p>In JWT stateless authentication, logout is primarily client-side
     * (discard token). This endpoint provides audit logging for logout events
     * to maintain RACF-equivalent audit capabilities (Section 0.7.9).
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 204 No Content status</li>
     *   <li>No response body (successful logout confirmation)</li>
     *   <li>AuthService.logout() is called for audit logging</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLogoutSuccess() throws Exception {
        // Arrange: Prepare JWT token in Authorization header format
        String token = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test.token";

        // Act & Assert: Perform POST /api/auth/logout and validate response
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", token))
                .andExpect(status().isNoContent());

        // Verify: AuthService.logout() was called once with extracted JWT token
        // Note: Controller extracts JWT by removing "Bearer " prefix
        verify(authService, times(1))
                .logout("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test.token");
    }

    /**
     * Test logout with null Authorization header.
     * 
     * <p>Tests logout behavior when Authorization header is missing or null.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 204 No Content status (graceful handling)</li>
     *   <li>AuthService.logout(null) is called for audit logging</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLogoutWithNullAuthorizationHeader() throws Exception {
        // Act & Assert: Perform POST /api/auth/logout without Authorization header
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());

        // Verify: AuthService.logout() was called with null
        verify(authService, times(1)).logout(null);
    }

    /**
     * Test logout with invalid Authorization header format.
     * 
     * <p>Tests logout behavior when Authorization header does not start with "Bearer ".
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 204 No Content status (graceful handling)</li>
     *   <li>AuthService.logout(null) is called since token extraction fails</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLogoutWithInvalidAuthorizationFormat() throws Exception {
        // Arrange: Authorization header without "Bearer " prefix
        String invalidToken = "InvalidFormatToken";

        // Act & Assert: Perform POST /api/auth/logout with invalid format
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", invalidToken))
                .andExpect(status().isNoContent());

        // Verify: AuthService.logout() was called with null
        // (Controller doesn't extract token if format is invalid)
        verify(authService, times(1)).logout(null);
    }

    /**
     * Test login with valid credentials but maximum field lengths.
     * 
     * <p>Tests COBOL field length constraints from CSUSR01Y.cpy:
     * <ul>
     *   <li>SEC-USR-ID PIC X(08) → userId max 8 characters</li>
     *   <li>SEC-USR-PWD PIC X(08) → password max 8 characters</li>
     * </ul>
     * 
     * <p>Bean Validation @Size annotations enforce these COBOL constraints.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 200 OK status (field lengths are valid)</li>
     *   <li>JWT token is generated successfully</li>
     *   <li>Response contains expected user information</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginSuccess_MaximumFieldLengths() throws Exception {
        // Arrange: Create request with maximum 8-character userId and password
        AuthRequest maxLengthRequest = AuthRequest.builder()
                .userId("ABCDEFGH")  // 8 characters (COBOL PIC X(08))
                .password("12345678")  // 8 characters (COBOL PIC X(08))
                .build();

        AuthResponse maxLengthResponse = new AuthResponse(
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.maxlen.token",
                3600L,
                "ABCDEFGH",
                "U"
        );

        when(authService.authenticate(anyString(), anyString()))
                .thenReturn(maxLengthResponse);

        // Act & Assert: Perform POST /api/auth/login and validate success
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(maxLengthRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token", is(notNullValue())))
                .andExpect(jsonPath("$.userId", is("ABCDEFGH")))
                .andExpect(jsonPath("$.userType", is("U")));

        // Verify: AuthService.authenticate() was called with max-length fields
        verify(authService, times(1)).authenticate("ABCDEFGH", "12345678");
    }

    /**
     * Test login failure with userId exceeding maximum length.
     * 
     * <p>Tests COBOL field length constraint validation from CSUSR01Y.cpy:
     * <ul>
     *   <li>SEC-USR-ID PIC X(08) → userId max 8 characters</li>
     * </ul>
     * 
     * <p>Bean Validation @Size(max = 8) enforces COBOL PIC X(08) constraint.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 400 Bad Request status</li>
     *   <li>JSON error response with validation message</li>
     *   <li>Error message indicates userId exceeds maximum length</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginFailure_UserIdExceedsMaxLength() throws Exception {
        // Arrange: Create request with userId exceeding 8 characters
        AuthRequest invalidRequest = AuthRequest.builder()
                .userId("ABCDEFGHI")  // 9 characters (exceeds COBOL PIC X(08))
                .password("PASS1234")
                .build();

        // Act & Assert: Perform POST /api/auth/login and validate validation error
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("validation failed")));

        // Verify: AuthService.authenticate() was NOT called
        verify(authService, times(0)).authenticate(anyString(), anyString());
    }

    /**
     * Test login failure with password exceeding maximum length.
     * 
     * <p>Tests COBOL field length constraint validation from CSUSR01Y.cpy:
     * <ul>
     *   <li>SEC-USR-PWD PIC X(08) → password max 8 characters</li>
     * </ul>
     * 
     * <p>Bean Validation @Size(max = 8) enforces COBOL PIC X(08) constraint.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 400 Bad Request status</li>
     *   <li>JSON error response with validation message</li>
     *   <li>Error message indicates password exceeds maximum length</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginFailure_PasswordExceedsMaxLength() throws Exception {
        // Arrange: Create request with password exceeding 8 characters
        AuthRequest invalidRequest = AuthRequest.builder()
                .userId("B0001")
                .password("PASS12345")  // 9 characters (exceeds COBOL PIC X(08))
                .build();

        // Act & Assert: Perform POST /api/auth/login and validate validation error
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", containsString("validation failed")));

        // Verify: AuthService.authenticate() was NOT called
        verify(authService, times(0)).authenticate(anyString(), anyString());
    }

    /**
     * Test login with operator user type.
     * 
     * <p>Tests COBOL user type determination for operator users.
     * While COSGN00C.cbl only shows Admin/User branching (lines 230-239),
     * the CSUSR01Y.cpy copybook includes 'O' for Operator user type.
     * 
     * <p><b>Expected Behavior:</b>
     * <ul>
     *   <li>HTTP 200 OK status</li>
     *   <li>JWT token is generated successfully</li>
     *   <li>UserType is "O" (operator)</li>
     * </ul>
     * 
     * @throws Exception if MockMvc request fails
     */
    @Test
    void testLoginSuccess_OperatorUser() throws Exception {
        // Arrange: Create request for operator user
        AuthRequest operatorRequest = AuthRequest.builder()
                .userId("OPER0001")
                .password("OPERPASS")
                .build();

        AuthResponse operatorResponse = new AuthResponse(
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.operator.token",
                3600L,
                "OPER0001",
                "O"
        );

        when(authService.authenticate(anyString(), anyString()))
                .thenReturn(operatorResponse);

        // Act & Assert: Perform POST /api/auth/login and validate operator response
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(operatorRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token", is(notNullValue())))
                .andExpect(jsonPath("$.userId", is("OPER0001")))
                .andExpect(jsonPath("$.userType", is("O")));

        // Verify: AuthService.authenticate() was called once
        verify(authService, times(1)).authenticate("OPER0001", "OPERPASS");
    }
}
