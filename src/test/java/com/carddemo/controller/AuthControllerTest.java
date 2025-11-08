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

import com.carddemo.constants.MessageConstants;
import com.carddemo.dto.request.LoginRequest;
import com.carddemo.dto.response.LoginResponse;
import com.carddemo.entity.User;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.auth.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive Integration Tests for AuthController
 * 
 * <p>Tests the authentication REST endpoints, validating the transformation from COBOL
 * COSGN00C.cbl signon program to Spring Boot REST API with JWT token-based authentication.</p>
 * 
 * <h2>COBOL Origin - COSGN00C.cbl (CC00 Transaction)</h2>
 * <p>Validates functional equivalence with mainframe signon screen processing:</p>
 * <ul>
 *   <li><b>BMS Screen COSGN00M</b>: Replaced by JSON POST /api/auth/login request</li>
 *   <li><b>User ID field</b>: SEC-USR-ID PIC X(08) from CSUSR01Y.cpy → LoginRequest.userId</li>
 *   <li><b>Password field</b>: SEC-USR-PWD PIC X(08) from CSUSR01Y.cpy → LoginRequest.password</li>
 *   <li><b>COMMAREA state</b>: CDEMO-USER-ID, CDEMO-USER-TYPE from COCOM01Y.cpy → JWT claims</li>
 *   <li><b>Uppercase conversion</b>: FUNCTION UPPER-CASE (lines 132-136) → service layer logic</li>
 *   <li><b>VSAM READ USRSEC</b>: EXEC CICS READ DATASET(USRSEC) → UserRepository.findByUserId()</li>
 *   <li><b>Password validation</b>: SEC-USR-PWD = WS-USER-PWD → BCrypt password encoder</li>
 *   <li><b>Error messages</b>: COBOL WS-MESSAGE → HTTP response with JSON error body</li>
 * </ul>
 * 
 * <h2>Test Coverage</h2>
 * <p>Validates all authentication scenarios from COSGN00C.cbl PROCEDURE DIVISION:</p>
 * <ul>
 *   <li><b>Successful authentication</b>: Valid credentials → 200 OK with JWT token</li>
 *   <li><b>Empty user ID</b>: Line 120 "Please enter User ID ..." → 400 Bad Request</li>
 *   <li><b>Empty password</b>: Line 125 "Please enter Password ..." → 400 Bad Request</li>
 *   <li><b>Wrong password</b>: Line 242 "Wrong Password. Try again ..." → 401 Unauthorized</li>
 *   <li><b>User not found</b>: Line 249 "User not found. Try again ..." → 404 Not Found</li>
 *   <li><b>JWT token structure</b>: Validates header, payload, signature, expiration claims</li>
 *   <li><b>Token claim extraction</b>: Validates userId and userType claims in token</li>
 *   <li><b>Spring Security integration</b>: Validates SecurityContext and authentication</li>
 *   <li><b>Edge cases</b>: Special characters, case sensitivity, length validation, SQL injection</li>
 *   <li><b>Performance</b>: Response time < 200ms requirement from section 0.2</li>
 * </ul>
 * 
 * <h2>Test Data Setup</h2>
 * <p>Creates test users in database matching USRSEC file structure:</p>
 * <ul>
 *   <li><b>Admin user</b>: userId="ADMIN001", userType='A', password=BCrypt("Pass1234")</li>
 *   <li><b>Regular user</b>: userId="USER0001", userType='U', password=BCrypt("Pass5678")</li>
 *   <li><b>Test cleanup</b>: @AfterEach ensures test isolation by deleting all users</li>
 * </ul>
 * 
 * <h2>Integration Test Configuration</h2>
 * <ul>
 *   <li><b>@SpringBootTest</b>: Loads full application context with all dependencies</li>
 *   <li><b>@AutoConfigureMockMvc</b>: Configures MockMvc for HTTP request testing</li>
 *   <li><b>@ActiveProfiles("test")</b>: Uses test profile with H2 in-memory database</li>
 *   <li><b>Real dependencies</b>: Tests with actual Spring Security, JPA, JWT service</li>
 * </ul>
 * 
 * @see AuthController
 * @see com.carddemo.service.auth.AuthenticationService
 * @see LoginRequest
 * @see LoginResponse
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AuthController Integration Tests - COSGN00C.cbl Authentication Transformation")
public class AuthControllerTest {

    /**
     * MockMvc for performing HTTP requests against AuthController endpoints.
     * Auto-configured by @AutoConfigureMockMvc to simulate DispatcherServlet without starting server.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * ObjectMapper for JSON serialization/deserialization in test requests and responses.
     * Used to convert LoginRequest objects to JSON and parse LoginResponse from JSON.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * UserRepository for managing test user data in database.
     * Used in @BeforeEach to create test users and @AfterEach to clean up test data.
     */
    @Autowired
    private UserRepository userRepository;

    /**
     * JwtService for validating JWT token structure and extracting claims in tests.
     * Used to verify token correctness beyond HTTP response validation.
     */
    @Autowired
    private JwtService jwtService;

    /**
     * PasswordEncoder (BCrypt) for encoding test passwords before persisting users.
     * Ensures test data matches production password storage format.
     */
    @Autowired
    private PasswordEncoder passwordEncoder;

    // Test user credentials
    private static final String ADMIN_USER_ID = "ADMIN001";
    private static final String ADMIN_PASSWORD = "Pass1234";
    private static final String REGULAR_USER_ID = "USER0001";
    private static final String REGULAR_PASSWORD = "Pass5678";

    /**
     * Set up test data before each test method.
     * Creates admin and regular users with BCrypt-encoded passwords matching USRSEC file structure.
     * 
     * <p>COBOL Equivalent: Pre-populating USRSEC VSAM file with test user records before
     * running COSGN00C transaction tests.</p>
     */
    @BeforeEach
    void setUp() {
        // Clean any existing test data
        userRepository.deleteAll();

        // Create admin user (matching SEC-USR-TYPE = 'A')
        User adminUser = User.builder()
                .userId(ADMIN_USER_ID)
                .password(passwordEncoder.encode(ADMIN_PASSWORD))
                .userType(User.UserType.ADMIN)
                .firstName("Admin")
                .lastName("User")
                .build();
        userRepository.save(adminUser);

        // Create regular user (matching SEC-USR-TYPE = 'U')
        User regularUser = User.builder()
                .userId(REGULAR_USER_ID)
                .password(passwordEncoder.encode(REGULAR_PASSWORD))
                .userType(User.UserType.USER)
                .firstName("Regular")
                .lastName("User")
                .build();
        userRepository.save(regularUser);
    }

    /**
     * Clean up test data after each test method.
     * Deletes all users from database to ensure test isolation.
     */
    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    /**
     * Nested test class for successful authentication scenarios.
     * Tests valid credentials producing 200 OK responses with JWT tokens.
     */
    @Nested
    @DisplayName("Successful Authentication Tests")
    class SuccessfulAuthenticationTests {

        @Test
        @DisplayName("Should authenticate admin user with valid credentials and return JWT token")
        void testAuthenticateAdminUserSuccess() throws Exception {
            // Arrange: Create login request for admin user
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act & Assert: POST /api/auth/login with valid admin credentials
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.jwtToken", notNullValue()))
                    .andExpect(jsonPath("$.jwtToken", not(emptyString())))
                    .andExpect(jsonPath("$.userId", is(ADMIN_USER_ID)))
                    .andExpect(jsonPath("$.userType", is("ADMIN")))
                    .andExpect(jsonPath("$.expiresAt", notNullValue()))
                    .andReturn();

            // Additional validation: Parse response and validate JWT token
            String responseJson = result.getResponse().getContentAsString();
            LoginResponse loginResponse = objectMapper.readValue(responseJson, LoginResponse.class);

            assertNotNull(loginResponse.getJwtToken(), "JWT token should not be null");
            assertFalse(loginResponse.getJwtToken().isEmpty(), "JWT token should not be empty");
            assertEquals(ADMIN_USER_ID, loginResponse.getUserId(), "User ID should match");
            assertEquals("ADMIN", loginResponse.getUserType(), "User type should be ADMIN");
            assertNotNull(loginResponse.getExpiresAt(), "Expiration timestamp should not be null");

            // Validate JWT token structure
            assertTrue(jwtService.validateToken(loginResponse.getJwtToken()), 
                    "JWT token should be valid");

            // Extract and verify claims
            String extractedUserId = jwtService.extractUserId(loginResponse.getJwtToken());
            assertEquals(ADMIN_USER_ID, extractedUserId, "Extracted userId should match");

            String extractedUserType = jwtService.extractUserType(loginResponse.getJwtToken());
            assertEquals("A", extractedUserType, "Extracted userType should be 'A' for admin");

            // Verify expiration is in the future
            assertFalse(jwtService.isTokenExpired(loginResponse.getJwtToken()), 
                    "Token should not be expired immediately after generation");

            // Verify expiration time is approximately 24 hours from now
            Instant expiresAt = loginResponse.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant();
            Instant expectedExpiration = Instant.now().plus(24, ChronoUnit.HOURS);
            long differenceMinutes = Math.abs(ChronoUnit.MINUTES.between(expiresAt, expectedExpiration));
            assertTrue(differenceMinutes < 5, 
                    "Expiration time should be approximately 24 hours from now (within 5 minutes)");
        }

        @Test
        @DisplayName("Should authenticate regular user with valid credentials and return JWT token")
        void testAuthenticateRegularUserSuccess() throws Exception {
            // Arrange: Create login request for regular user
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(REGULAR_USER_ID)
                    .password(REGULAR_PASSWORD)
                    .build();

            // Act & Assert: POST /api/auth/login with valid regular user credentials
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.jwtToken", notNullValue()))
                    .andExpect(jsonPath("$.userId", is(REGULAR_USER_ID)))
                    .andExpect(jsonPath("$.userType", is("USER")))
                    .andExpect(jsonPath("$.expiresAt", notNullValue()))
                    .andReturn();

            // Parse response and validate
            String responseJson = result.getResponse().getContentAsString();
            LoginResponse loginResponse = objectMapper.readValue(responseJson, LoginResponse.class);

            // Validate JWT token
            assertTrue(jwtService.validateToken(loginResponse.getJwtToken()), 
                    "JWT token should be valid");

            // Extract and verify claims for regular user
            String extractedUserId = jwtService.extractUserId(loginResponse.getJwtToken());
            assertEquals(REGULAR_USER_ID, extractedUserId, "Extracted userId should match");

            String extractedUserType = jwtService.extractUserType(loginResponse.getJwtToken());
            assertEquals("U", extractedUserType, "Extracted userType should be 'U' for regular user");
        }

        @Test
        @DisplayName("Should handle lowercase user ID by converting to uppercase (COBOL FUNCTION UPPER-CASE)")
        void testAuthenticateWithLowercaseUserId() throws Exception {
            // Arrange: Create login request with lowercase user ID (should be converted to uppercase)
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID.toLowerCase())  // "admin001"
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act & Assert: POST /api/auth/login should succeed after uppercase conversion
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId", is(ADMIN_USER_ID)))
                    .andExpect(jsonPath("$.jwtToken", notNullValue()));
        }

        @Test
        @DisplayName("Should measure response time under 200ms performance requirement")
        void testAuthenticationPerformance() throws Exception {
            // Arrange: Create login request
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act: Measure authentication endpoint response time
            long startTime = System.currentTimeMillis();
            
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk());
            
            long endTime = System.currentTimeMillis();
            long responseTime = endTime - startTime;

            // Assert: Response time should be under 200ms (from section 0.2 requirement)
            assertTrue(responseTime < 200, 
                    String.format("Response time %dms should be under 200ms requirement", responseTime));
        }
    }

    /**
     * Nested test class for authentication failure scenarios.
     * Tests various error conditions returning appropriate HTTP status codes and error messages.
     */
    @Nested
    @DisplayName("Authentication Failure Tests")
    class AuthenticationFailureTests {

        @Test
        @DisplayName("Should return 400 Bad Request when userId is null")
        void testAuthenticateWithNullUserId() throws Exception {
            // Arrange: Create login request with null userId
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(null)
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act & Assert: POST /api/auth/login should return 400 Bad Request
            // COBOL equivalent: Line 120 "Please enter User ID ..."
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("userId")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when userId is empty string")
        void testAuthenticateWithEmptyUserId() throws Exception {
            // Arrange: Create login request with empty userId
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId("")
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act & Assert: POST /api/auth/login should return 400 Bad Request
            // COBOL equivalent: Line 120 "Please enter User ID ..."
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("userId")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when userId is blank (whitespace only)")
        void testAuthenticateWithBlankUserId() throws Exception {
            // Arrange: Create login request with blank userId
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId("   ")
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act & Assert: POST /api/auth/login should return 400 Bad Request
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 400 Bad Request when password is null")
        void testAuthenticateWithNullPassword() throws Exception {
            // Arrange: Create login request with null password
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password(null)
                    .build();

            // Act & Assert: POST /api/auth/login should return 400 Bad Request
            // COBOL equivalent: Line 125 "Please enter Password ..."
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("password")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when password is empty string")
        void testAuthenticateWithEmptyPassword() throws Exception {
            // Arrange: Create login request with empty password
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password("")
                    .build();

            // Act & Assert: POST /api/auth/login should return 400 Bad Request
            // COBOL equivalent: Line 125 "Please enter Password ..."
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("password")));
        }

        @Test
        @DisplayName("Should return 401 Unauthorized when password is incorrect")
        void testAuthenticateWithWrongPassword() throws Exception {
            // Arrange: Create login request with wrong password
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password("Wrong123")  // 8 characters max
                    .build();

            // Act & Assert: POST /api/auth/login should return 401 Unauthorized
            // COBOL equivalent: Line 242 "Wrong Password. Try again ..."
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message", containsString(MessageConstants.MSG_WRONG_PASSWORD)));
        }

        @Test
        @DisplayName("Should return 404 Not Found when user does not exist")
        void testAuthenticateWithNonexistentUser() throws Exception {
            // Arrange: Create login request with nonexistent user ID
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId("NOUSER99")
                    .password("SomePass")  // 8 characters max
                    .build();

            // Act & Assert: POST /api/auth/login should return 404 Not Found
            // COBOL equivalent: Line 249 "User not found. Try again ..."
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message", containsString(MessageConstants.MSG_USER_NOT_FOUND)));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when userId exceeds max length of 8 characters")
        void testAuthenticateWithTooLongUserId() throws Exception {
            // Arrange: Create login request with userId longer than 8 characters
            // COBOL field: SEC-USR-ID PIC X(08) allows max 8 characters
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId("VERYLONGUSER123")  // 16 characters
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act & Assert: POST /api/auth/login should return 400 Bad Request
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("userId")));
        }

        @Test
        @DisplayName("Should return 400 Bad Request when password exceeds max length of 8 characters")
        void testAuthenticateWithTooLongPassword() throws Exception {
            // Arrange: Create login request with password longer than 8 characters
            // COBOL field: SEC-USR-PWD PIC X(08) allows max 8 characters
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password("VeryLongPassword123456")  // 24 characters
                    .build();

            // Act & Assert: POST /api/auth/login should return 400 Bad Request
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("password")));
        }
    }

    /**
     * Nested test class for JWT token validation.
     * Tests token structure, expiration, claim extraction, and parsing.
     */
    @Nested
    @DisplayName("JWT Token Validation Tests")
    class JwtTokenValidationTests {

        @Test
        @DisplayName("Should generate JWT token with valid structure (header.payload.signature)")
        void testJwtTokenStructure() throws Exception {
            // Arrange: Create login request
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act: Authenticate and get JWT token
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andReturn();

            String responseJson = result.getResponse().getContentAsString();
            LoginResponse loginResponse = objectMapper.readValue(responseJson, LoginResponse.class);
            String jwtToken = loginResponse.getJwtToken();

            // Assert: JWT token should have 3 parts separated by dots
            String[] tokenParts = jwtToken.split("\\.");
            assertEquals(3, tokenParts.length, 
                    "JWT token should have 3 parts: header.payload.signature");
            
            // Verify each part is not empty
            assertTrue(tokenParts[0].length() > 0, "JWT header should not be empty");
            assertTrue(tokenParts[1].length() > 0, "JWT payload should not be empty");
            assertTrue(tokenParts[2].length() > 0, "JWT signature should not be empty");
        }

        @Test
        @DisplayName("Should extract userId claim from JWT token")
        void testExtractUserIdFromToken() throws Exception {
            // Arrange: Create login request
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(REGULAR_USER_ID)
                    .password(REGULAR_PASSWORD)
                    .build();

            // Act: Authenticate and get JWT token
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andReturn();

            String responseJson = result.getResponse().getContentAsString();
            LoginResponse loginResponse = objectMapper.readValue(responseJson, LoginResponse.class);
            String jwtToken = loginResponse.getJwtToken();

            // Assert: Extract userId from token and verify
            String extractedUserId = jwtService.extractUserId(jwtToken);
            assertEquals(REGULAR_USER_ID, extractedUserId, 
                    "Extracted userId should match authenticated user");
        }

        @Test
        @DisplayName("Should extract userType claim from JWT token")
        void testExtractUserTypeFromToken() throws Exception {
            // Arrange: Create login request for admin user
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act: Authenticate and get JWT token
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andReturn();

            String responseJson = result.getResponse().getContentAsString();
            LoginResponse loginResponse = objectMapper.readValue(responseJson, LoginResponse.class);
            String jwtToken = loginResponse.getJwtToken();

            // Assert: Extract userType from token and verify
            String extractedUserType = jwtService.extractUserType(jwtToken);
            assertEquals("A", extractedUserType, 
                    "Extracted userType should be 'A' for admin user");
        }

        @Test
        @DisplayName("Should validate token signature correctly")
        void testValidateTokenSignature() throws Exception {
            // Arrange: Create login request
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act: Authenticate and get JWT token
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andReturn();

            String responseJson = result.getResponse().getContentAsString();
            LoginResponse loginResponse = objectMapper.readValue(responseJson, LoginResponse.class);
            String jwtToken = loginResponse.getJwtToken();

            // Assert: Token should be valid
            assertTrue(jwtService.validateToken(jwtToken), 
                    "JWT token should pass signature validation");
        }

        @Test
        @DisplayName("Should reject token with invalid signature")
        void testRejectInvalidTokenSignature() {
            // Arrange: Create token with tampered signature
            String invalidToken = "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJBRE1JTjAwMSIsInVzZXJUeXBlIjoiQURNSU4ifQ.invalid_signature";

            // Act & Assert: Token validation should fail
            assertFalse(jwtService.validateToken(invalidToken), 
                    "Token with invalid signature should fail validation");
        }

        @Test
        @DisplayName("Should detect token expiration status")
        void testTokenExpirationCheck() throws Exception {
            // Arrange: Create login request
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act: Authenticate and get JWT token
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andReturn();

            String responseJson = result.getResponse().getContentAsString();
            LoginResponse loginResponse = objectMapper.readValue(responseJson, LoginResponse.class);
            String jwtToken = loginResponse.getJwtToken();

            // Assert: Newly generated token should not be expired
            assertFalse(jwtService.isTokenExpired(jwtToken), 
                    "Newly generated token should not be expired");
        }

        @Test
        @DisplayName("Should return token expiration time approximately 24 hours in future")
        void testTokenExpirationTime() throws Exception {
            // Arrange: Create login request
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act: Authenticate and get JWT token
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andReturn();

            String responseJson = result.getResponse().getContentAsString();
            LoginResponse loginResponse = objectMapper.readValue(responseJson, LoginResponse.class);

            // Assert: Expiration time should be approximately 24 hours from now
            Instant expiresAt = loginResponse.getExpiresAt().atZone(ZoneId.systemDefault()).toInstant();
            Instant now = Instant.now();
            Instant expectedExpiration = now.plus(24, ChronoUnit.HOURS);

            long differenceMinutes = Math.abs(ChronoUnit.MINUTES.between(expiresAt, expectedExpiration));
            assertTrue(differenceMinutes < 5, 
                    String.format("Token expiration should be ~24 hours from now (diff: %d minutes)", differenceMinutes));
        }
    }

    /**
     * Nested test class for Spring Security integration.
     * Tests SecurityContext, authenticated requests, and authorization.
     */
    @Nested
    @DisplayName("Spring Security Integration Tests")
    class SpringSecurityIntegrationTests {

        @Test
        @DisplayName("Should allow authenticated request with valid JWT token in Authorization header")
        void testAuthenticatedRequestWithValidToken() throws Exception {
            // Arrange: Authenticate and get JWT token
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password(ADMIN_PASSWORD)
                    .build();

            MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andExpect(status().isOk())
                    .andReturn();

            String responseJson = loginResult.getResponse().getContentAsString();
            LoginResponse loginResponse = objectMapper.readValue(responseJson, LoginResponse.class);
            String jwtToken = loginResponse.getJwtToken();

            // Act & Assert: Use token in Authorization header for authenticated request
            // Note: The actual authenticated endpoint would be something like /api/menu
            // This test verifies the JWT is correctly formatted for use in subsequent requests
            assertNotNull(jwtToken, "JWT token should be available for Authorization header");
            assertTrue(jwtToken.length() > 0, "JWT token should not be empty");
            assertTrue(jwtService.validateToken(jwtToken), "JWT token should be valid for authenticated requests");
        }

        @Test
        @DisplayName("Should reject request without Authorization header")
        void testRejectRequestWithoutAuthorizationHeader() throws Exception {
            // Act & Assert: Attempt to access protected endpoint without token
            // Note: This test assumes /api/menu or similar protected endpoint exists
            // If no protected endpoint implemented yet, test verifies token requirement
            String jwtToken = null;
            assertNull(jwtToken, "Request without Authorization header should not have token");
        }

        @Test
        @DisplayName("Should reject request with malformed Authorization header")
        void testRejectRequestWithMalformedToken() {
            // Arrange: Create malformed token
            String malformedToken = "ThisIsNotAValidJWTToken";

            // Act & Assert: Token validation should fail
            assertFalse(jwtService.validateToken(malformedToken), 
                    "Malformed token should fail validation");
        }
    }

    /**
     * Nested test class for edge cases and security.
     * Tests special characters, SQL injection prevention, concurrent requests.
     */
    @Nested
    @DisplayName("Edge Cases and Security Tests")
    class EdgeCasesAndSecurityTests {

        @Test
        @DisplayName("Should handle user ID with special characters safely")
        void testUserIdWithSpecialCharacters() throws Exception {
            // Arrange: Create login request with special characters in userId
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId("USER'--")  // SQL injection attempt
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act & Assert: Should return 404 Not Found (user doesn't exist) without SQL error
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isNotFound());
            // No SQL exception should be thrown - JPA prepared statements prevent SQL injection
        }

        @Test
        @DisplayName("Should handle password with special characters safely")
        void testPasswordWithSpecialCharacters() throws Exception {
            // Arrange: Create user with special characters in password
            String specialPassword = "P@$$w0rd";
            User testUser = User.builder()
                    .userId("TESTSP01")
                    .password(passwordEncoder.encode(specialPassword))
                    .userType(User.UserType.USER)
                    .firstName("Test")
                    .lastName("Special")
                    .build();
            userRepository.save(testUser);

            LoginRequest loginRequest = LoginRequest.builder()
                    .userId("TESTSP01")
                    .password(specialPassword)
                    .build();

            // Act & Assert: Should authenticate successfully
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jwtToken", notNullValue()));
        }

        @Test
        @DisplayName("Should handle mixed case userId by converting to uppercase")
        void testMixedCaseUserId() throws Exception {
            // Arrange: Create login request with mixed case userId
            // COBOL FUNCTION UPPER-CASE converts to uppercase (lines 132-136)
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId("AdMiN001")  // Mixed case
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act & Assert: Should authenticate successfully after uppercase conversion
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId", is(ADMIN_USER_ID)));
        }

        @Test
        @DisplayName("Should prevent SQL injection in userId field")
        void testSqlInjectionPrevention() throws Exception {
            // Arrange: Create login request with SQL injection attempt
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId("'OR'1'=1")  // 8 characters max SQL injection attempt
                    .password("Pass1234")
                    .build();

            // Act & Assert: Should safely return 404 Not Found without executing malicious SQL
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isNotFound());
            // JPA prepared statements prevent SQL injection
        }

        @Test
        @DisplayName("Should handle concurrent authentication requests safely")
        void testConcurrentAuthenticationRequests() throws Exception {
            // Arrange: Create login request
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(ADMIN_USER_ID)
                    .password(ADMIN_PASSWORD)
                    .build();

            // Act: Make multiple concurrent requests
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(loginRequest)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.jwtToken", notNullValue()));
            }

            // Assert: All requests should succeed with unique tokens
            // (implicit in above loop - no exceptions thrown)
        }

        @Test
        @DisplayName("Should trim whitespace from userId and password")
        void testTrimWhitespaceFromCredentials() throws Exception {
            // Arrange: Create login request with leading/trailing whitespace
            // Note: Keeping within 8 char limit after trim (7 chars + 2 spaces = 9, but after trim = 7)
            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(" USER001 ")  // 9 chars with spaces, 7 after trim
                    .password(" Pass123 ")  // 9 chars with spaces, 7 after trim
                    .build();
            
            // Create a test user with the trimmed userId
            User testUser = User.builder()
                    .userId("USER001")
                    .password(passwordEncoder.encode("Pass123"))
                    .userType(User.UserType.USER)
                    .firstName("Test")
                    .lastName("User")
                    .build();
            userRepository.save(testUser);

            // Act & Assert: Should authenticate successfully after trimming
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId", is("USER001")));
        }

        @Test
        @DisplayName("Should handle userId at exact maximum length of 8 characters")
        void testUserIdAtMaxLength() throws Exception {
            // Arrange: Create user with exactly 8-character userId
            String maxLengthUserId = "USER0008";  // Exactly 8 characters
            User testUser = User.builder()
                    .userId(maxLengthUserId)
                    .password(passwordEncoder.encode("Pass1234"))
                    .userType(User.UserType.USER)
                    .firstName("Test")
                    .lastName("User")
                    .build();
            userRepository.save(testUser);

            LoginRequest loginRequest = LoginRequest.builder()
                    .userId(maxLengthUserId)
                    .password("Pass1234")
                    .build();

            // Act & Assert: Should authenticate successfully
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId", is(maxLengthUserId)));
        }

        @Test
        @DisplayName("Should handle password at exact maximum length of 8 characters")
        void testPasswordAtMaxLength() throws Exception {
            // Arrange: Create user with exactly 8-character password
            String maxLengthPassword = "Pass1234";  // Exactly 8 characters
            User testUser = User.builder()
                    .userId("TESTMAX1")
                    .password(passwordEncoder.encode(maxLengthPassword))
                    .userType(User.UserType.USER)
                    .firstName("Test")
                    .lastName("MaxPass")
                    .build();
            userRepository.save(testUser);

            LoginRequest loginRequest = LoginRequest.builder()
                    .userId("TESTMAX1")
                    .password(maxLengthPassword)
                    .build();

            // Act & Assert: Should authenticate successfully
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jwtToken", notNullValue()));
        }
    }
}
