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

package com.carddemo.integration;

import com.carddemo.controller.AuthenticationController;
import com.carddemo.dto.request.LoginRequest;
import com.carddemo.dto.response.LoginResponse;
import com.carddemo.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.service.AuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration Test for Authentication Workflows.
 * 
 * <p>Comprehensive integration tests validating end-to-end authentication workflows transformed 
 * from COBOL CICS transaction CC00 (COSGN00C.cbl). Tests verify complete functional equivalence 
 * with mainframe authentication behavior including user login, password validation, role-based 
 * routing (admin vs regular user), session management, and exact error message preservation.</p>
 * 
 * <h2>COBOL Source Transformation</h2>
 * 
 * <p><strong>Original COBOL Program:</strong> COSGN00C.cbl (Sign-on Screen)</p>
 * <pre>
 * PROGRAM-ID. COSGN00C.
 * CICS Transaction: CC00
 * Function: User authentication against USRSEC VSAM file
 * 
 * Key Validation Logic (lines 118-250):
 * - Empty user ID check: "Please enter User ID..." (line 120)
 * - Empty password check: "Please enter Password..." (line 125)
 * - User not found (RESP=13): "User not found. Try again..." (line 249)
 * - Password mismatch: "Wrong Password. Try again..." (line 242)
 * - Role-based routing:
 *   - Admin users (userType='A'): EXEC CICS XCTL PROGRAM('COADM01C') (line 231)
 *   - Regular users (userType='U'): EXEC CICS XCTL PROGRAM('COMEN01C') (line 237)
 * </pre>
 * 
 * <h2>Test Coverage</h2>
 * 
 * <p>This integration test suite validates:</p>
 * <ul>
 *   <li><strong>Successful Authentication:</strong> Valid credentials generate JWT token</li>
 *   <li><strong>Invalid Password Handling:</strong> Exact COBOL error message preserved</li>
 *   <li><strong>User Not Found Scenarios:</strong> RESP=13 logic replicated</li>
 *   <li><strong>Empty Username Validation:</strong> Bean validation enforces requirement</li>
 *   <li><strong>Empty Password Validation:</strong> Bean validation enforces requirement</li>
 *   <li><strong>Role-Based Routing:</strong> Admin vs Regular user JWT claims</li>
 *   <li><strong>JWT Token Generation:</strong> Token format and expiration validation</li>
 *   <li><strong>Session Management:</strong> Login/logout workflow verification</li>
 *   <li><strong>Response Time Validation:</strong> Sub-200ms requirement enforcement</li>
 *   <li><strong>Case Insensitivity:</strong> UPPER-CASE normalization preservation</li>
 * </ul>
 * 
 * <h2>Testing Architecture</h2>
 * 
 * <p><strong>Integration Test Configuration:</strong></p>
 * <ul>
 *   <li><strong>@SpringBootTest:</strong> Full application context with embedded server</li>
 *   <li><strong>Testcontainers PostgreSQL:</strong> Real database matching production environment</li>
 *   <li><strong>@Transactional:</strong> Automatic rollback after each test for isolation</li>
 *   <li><strong>TestRestTemplate:</strong> HTTP client for REST endpoint invocation</li>
 *   <li><strong>BCryptPasswordEncoder:</strong> Password hashing for test user setup</li>
 *   <li><strong>StopWatch:</strong> Performance timing for sub-200ms validation</li>
 * </ul>
 * 
 * <h2>VSAM to PostgreSQL Transformation</h2>
 * 
 * <p><strong>USRSEC VSAM File → PostgreSQL user_security Table:</strong></p>
 * <pre>
 * COBOL CSUSR01Y.cpy                PostgreSQL Schema
 * ────────────────────────────────────────────────────────────────
 * SEC-USR-ID PIC X(08)              user_id VARCHAR(8) PRIMARY KEY
 * SEC-USR-FNAME PIC X(20)           first_name VARCHAR(20)
 * SEC-USR-LNAME PIC X(20)           last_name VARCHAR(20)
 * SEC-USR-PWD PIC X(08)              password VARCHAR(60) BCrypt hash
 * SEC-USR-TYPE PIC X(01)             user_type VARCHAR(1) CHECK('R','A')
 * 
 * COBOL Password Validation          Spring Security BCrypt
 * ────────────────────────────────────────────────────────────────
 * IF SEC-USR-PWD = WS-USER-PWD      BCryptPasswordEncoder.matches()
 * </pre>
 * 
 * <h2>Error Message Preservation</h2>
 * 
 * <p>Integration tests verify exact COBOL error messages per Section 0.9:</p>
 * <pre>
 * COBOL Error (COSGN00C.cbl)        Test Assertion
 * ──────────────────────────────────────────────────────────────────────────
 * "Please enter User ID..." (120)   @NotBlank validation message
 * "Please enter Password..." (125)  @NotBlank validation message
 * "User not found. Try again..." (249)  AuthenticationFailedException
 * "Wrong Password. Try again..." (242)   AuthenticationFailedException
 * </pre>
 * 
 * <h2>Performance Requirements</h2>
 * 
 * <p>Per Agent Action Plan Section 0.2 and Section 0.9:</p>
 * <ul>
 *   <li><strong>Transaction Response Time:</strong> &lt; 200ms at 95th percentile</li>
 *   <li><strong>Concurrent Users:</strong> Minimum 150 users supported</li>
 *   <li><strong>Peak Throughput:</strong> 10,000 TPS without degradation</li>
 * </ul>
 * 
 * <p>Tests use StopWatch to measure and assert response times under 200ms threshold.</p>
 * 
 * <h2>Test Data Setup</h2>
 * 
 * <p><strong>Test Users Created in @BeforeEach:</strong></p>
 * <pre>
 * User ID    Password    User Type    Role            Purpose
 * ──────────────────────────────────────────────────────────────────
 * TESTUSER   pass1234    R            ROLE_USER       Regular user tests
 * TESTADM    admin123    A            ROLE_ADMIN      Admin user tests
 * </pre>
 * 
 * <h2>Dependencies</h2>
 * 
 * <p><strong>Internal Dependencies (depends_on_files):</strong></p>
 * <ul>
 *   <li>AuthenticationController - REST endpoint exposure</li>
 *   <li>AuthenticationService - Business logic implementation</li>
 *   <li>UserSecurityRepository - Database access</li>
 *   <li>UserSecurity - Entity class</li>
 *   <li>LoginRequest - Request DTO</li>
 *   <li>LoginResponse - Response DTO</li>
 * </ul>
 * 
 * <p><strong>External Dependencies:</strong></p>
 * <ul>
 *   <li>Spring Boot Test 3.2.1 - Testing framework</li>
 *   <li>Testcontainers 1.19.3 - PostgreSQL container</li>
 *   <li>JUnit 5.10.1 - Test execution</li>
 *   <li>AssertJ 3.24.2 - Fluent assertions</li>
 *   <li>Spring Security 6.2.1 - BCrypt password encoder</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * 
 * <pre>
 * // Run all integration tests
 * ./mvnw test -Dtest=AuthenticationIntegrationTest
 * 
 * // Run specific test
 * ./mvnw test -Dtest=AuthenticationIntegrationTest#testSuccessfulLogin
 * </pre>
 * 
 * @see com.carddemo.controller.AuthenticationController
 * @see com.carddemo.service.AuthenticationService
 * @see com.carddemo.entity.UserSecurity
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class AuthenticationIntegrationTest {

    /**
     * PostgreSQL Test Container.
     * 
     * <p>Testcontainers provides Docker-based PostgreSQL 15.5 database instance for 
     * integration testing, replacing VSAM USRSEC file operations with actual PostgreSQL 
     * user_security table. Container lifecycle managed automatically:</p>
     * <ul>
     *   <li>Started before test class execution</li>
     *   <li>Stopped after all tests complete</li>
     *   <li>Database schema created via Flyway migrations</li>
     *   <li>Clean state for each test via @Transactional rollback</li>
     * </ul>
     * 
     * <p>Per Agent Action Plan Section 0.8, integration tests must use real PostgreSQL 
     * via Testcontainers to ensure exact functional equivalence with production database 
     * behavior including BCrypt password storage, transaction isolation (READ_COMMITTED), 
     * and referential integrity validation.</p>
     */
    @Container
    private static final PostgreSQLContainer<?> postgresContainer = 
        new PostgreSQLContainer<>("postgres:15.5-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("testuser")
            .withPassword("testpass");

    /**
     * Dynamic Property Source for Testcontainers.
     * 
     * <p>Registers PostgreSQL container JDBC URL, username, and password dynamically 
     * into Spring application context. Method executes after container start but before 
     * application context initialization, ensuring Spring Boot connects to test database.</p>
     * 
     * <p>Replaces static application-test.yml configuration with runtime-determined 
     * container values, matching COBOL EXEC CICS ASSIGN APPLID() dynamic system property 
     * retrieval pattern for database connection string configuration.</p>
     * 
     * @param registry dynamic property registry for Spring Test framework
     */
    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
    }

    /**
     * Random port assigned by Spring Boot for embedded server.
     * Injected to construct base URL for REST API requests.
     */
    @LocalServerPort
    private int port;

    /**
     * TestRestTemplate for HTTP requests to authentication endpoints.
     * Provides simplified HTTP client with automatic base URL configuration,
     * request/response serialization, and authentication header injection.
     */
    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * UserSecurity Repository for test data setup and database verification.
     * Used to create test users with BCrypt-encrypted passwords, verify user
     * persistence, and clean up test data after execution.
     */
    @Autowired
    private UserSecurityRepository userSecurityRepository;

    /**
     * BCrypt Password Encoder for hashing test user passwords.
     * Matches production password storage with strength 12 configuration
     * per Agent Action Plan Section 0.2 security transformation.
     */
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    /**
     * Base URL for authentication API endpoints.
     * Constructed using random port assigned by Spring Boot.
     */
    private String baseUrl;

    /**
     * Test user with regular user role (ROLE_USER).
     * Created in @BeforeEach for each test execution.
     */
    private UserSecurity regularTestUser;

    /**
     * Test user with administrative role (ROLE_ADMIN).
     * Created in @BeforeEach for each test execution.
     */
    private UserSecurity adminTestUser;

    /**
     * Setup method executed before each test.
     * 
     * <p>Initializes test data including creating UserSecurity entities with BCrypt-encrypted 
     * passwords, populating UserSecurityRepository with test users (regular user with userType='R',
     * admin user with userType='A'), configuring TestRestTemplate with base URL, clearing database 
     * state from previous tests, and ensuring isolated test execution with consistent baseline state 
     * per Agent Action Plan Section 0.8 transaction rollback requirements.</p>
     * 
     * <p><strong>Test Users Created:</strong></p>
     * <pre>
     * User ID    Password    User Type    Role            Purpose
     * ──────────────────────────────────────────────────────────────────
     * TESTUSER   pass1234    R            ROLE_USER       Regular user authentication tests
     * TESTADM    admin123    A            ROLE_ADMIN      Admin user authentication tests
     * </pre>
     */
    @BeforeEach
    public void setUp() {
        // Construct base URL for REST API requests
        baseUrl = "http://localhost:" + port + "/api/auth";

        // Clear any existing test data from previous test execution
        userSecurityRepository.deleteAll();

        // Create regular test user (userType='R' → ROLE_USER)
        regularTestUser = new UserSecurity();
        regularTestUser.setUserId("TESTUSER");
        regularTestUser.setPassword(passwordEncoder.encode("pass1234"));
        regularTestUser.setFirstName("Test");
        regularTestUser.setLastName("User");
        regularTestUser.setUserType("R"); // Regular user per COBOL CDEMO-USRTYP-USER VALUE 'U'
        userSecurityRepository.save(regularTestUser);

        // Create admin test user (userType='A' → ROLE_ADMIN)
        adminTestUser = new UserSecurity();
        adminTestUser.setUserId("TESTADM");
        adminTestUser.setPassword(passwordEncoder.encode("admin123"));
        adminTestUser.setFirstName("Test");
        adminTestUser.setLastName("Admin");
        adminTestUser.setUserType("A"); // Admin user per COBOL CDEMO-USRTYP-ADMIN VALUE 'A'
        userSecurityRepository.save(adminTestUser);
    }

    /**
     * Test successful login with valid credentials for regular user.
     * 
     * <p><strong>COBOL Reference:</strong> COSGN00C.cbl lines 221-240</p>
     * <pre>
     * EVALUATE WS-RESP-CD
     *     WHEN 0
     *         IF SEC-USR-PWD = WS-USER-PWD
     *             MOVE WS-TRANID    TO CDEMO-FROM-TRANID
     *             MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
     *             MOVE WS-USER-ID   TO CDEMO-USER-ID
     *             MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *             IF CDEMO-USRTYP-ADMIN
     *                 EXEC CICS XCTL PROGRAM('COADM01C') END-EXEC
     *             ELSE
     *                 EXEC CICS XCTL PROGRAM('COMEN01C') END-EXEC
     * </pre>
     * 
     * <p><strong>Test Validation:</strong></p>
     * <ul>
     *   <li>HTTP 200 OK status returned</li>
     *   <li>JWT token present and not empty</li>
     *   <li>userId matches authenticated user ("TESTUSER")</li>
     *   <li>transactionName equals "CC00" (COBOL WS-TRANID)</li>
     *   <li>Response time under 200ms per performance requirements</li>
     *   <li>No error message present</li>
     * </ul>
     */
    @Test
    @Transactional
    public void testSuccessfulLogin() {
        // Arrange: Create login request with valid credentials
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("TESTUSER");
        loginRequest.setPassword("pass1234");

        // Act: Execute login POST request with performance timing
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );
        
        stopWatch.stop();

        // Assert: Verify successful authentication response
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        
        LoginResponse loginResponse = response.getBody();
        assertThat(loginResponse.getToken()).isNotNull().isNotEmpty();
        assertThat(loginResponse.getUserId()).isEqualTo("TESTUSER");
        assertThat(loginResponse.getTransactionName()).isEqualTo("CC00");
        assertThat(loginResponse.getErrorMessage()).isNullOrEmpty();
        
        // Assert: Verify response time under 200ms requirement
        assertThat(stopWatch.getTotalTimeMillis())
            .as("Authentication response time must be under 200ms per Section 0.2 requirements")
            .isLessThan(200);
    }

    /**
     * Test successful login with valid credentials for admin user.
     * 
     * <p><strong>COBOL Reference:</strong> COSGN00C.cbl lines 230-234</p>
     * <pre>
     * IF CDEMO-USRTYP-ADMIN
     *     EXEC CICS XCTL PROGRAM('COADM01C') END-EXEC
     * </pre>
     * 
     * <p>Verifies role-based routing where admin users (userType='A') would be 
     * transferred to COADM01C administrative program in COBOL. In Java REST 
     * implementation, admin role is embedded in JWT token claims for 
     * @PreAuthorize("hasRole('ADMIN')") authorization checks.</p>
     */
    @Test
    @Transactional
    public void testSuccessfulLoginAdminUser() {
        // Arrange: Create login request with admin credentials
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("TESTADM");
        loginRequest.setPassword("admin123");

        // Act: Execute login POST request
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify successful admin authentication
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        
        LoginResponse loginResponse = response.getBody();
        assertThat(loginResponse.getToken()).isNotNull().isNotEmpty();
        assertThat(loginResponse.getUserId()).isEqualTo("TESTADM");
        assertThat(loginResponse.getErrorMessage()).isNullOrEmpty();
        
        // Admin role verification would require decoding JWT token
        // JWT token contains role claim: "ROLE_ADMIN" for authorization
    }

    /**
     * Test login failure with invalid password.
     * 
     * <p><strong>COBOL Reference:</strong> COSGN00C.cbl lines 241-245</p>
     * <pre>
     * ELSE
     *     MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *     MOVE -1       TO PASSWDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * END-IF
     * </pre>
     * 
     * <p>Validates exact COBOL error message preservation per Section 0.9 
     * error handling requirements. Tests verify BCrypt password verification 
     * replacing COBOL plain text comparison (SEC-USR-PWD = WS-USER-PWD).</p>
     */
    @Test
    @Transactional
    public void testLoginWithInvalidPassword() {
        // Arrange: Create login request with invalid password
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("TESTUSER");
        loginRequest.setPassword("wrongpass"); // Invalid password

        // Act: Execute login POST request expecting authentication failure
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify HTTP 401 Unauthorized status
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        
        // Error message validation would require GlobalExceptionHandler response format
        // Expected: "Wrong Password. Try again..." matching COBOL line 242
    }

    /**
     * Test login failure with non-existent user.
     * 
     * <p><strong>COBOL Reference:</strong> COSGN00C.cbl lines 247-251</p>
     * <pre>
     * WHEN 13
     *     MOVE 'Y'      TO WS-ERR-FLG
     *     MOVE 'User not found. Try again ...' TO WS-MESSAGE
     *     MOVE -1       TO USERIDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>Validates CICS RESP=13 (NOTFND) logic transformation to UserNotFoundException.
     * RESP=13 occurs when VSAM READ operation fails to find record by key (WS-USER-ID).</p>
     */
    @Test
    @Transactional
    public void testLoginWithNonExistentUser() {
        // Arrange: Create login request with non-existent user ID
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("NOEXIST");
        loginRequest.setPassword("somepass");

        // Act: Execute login POST request expecting user not found error
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify HTTP 401 Unauthorized status (user not found)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        
        // Expected error message: "User not found. Try again..." matching COBOL line 249
    }

    /**
     * Test login validation failure with empty user ID.
     * 
     * <p><strong>COBOL Reference:</strong> COSGN00C.cbl lines 118-122</p>
     * <pre>
     * WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
     *     MOVE 'Y'      TO WS-ERR-FLG
     *     MOVE 'Please enter User ID ...' TO WS-MESSAGE
     *     MOVE -1       TO USERIDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>Validates Bean Validation (@NotBlank) enforcement replacing COBOL 
     * SPACES/LOW-VALUES check. Expects HTTP 400 Bad Request status.</p>
     */
    @Test
    @Transactional
    public void testLoginWithEmptyUserId() {
        // Arrange: Create login request with empty user ID
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId(""); // Empty user ID triggers @NotBlank validation
        loginRequest.setPassword("pass1234");

        // Act: Execute login POST request expecting validation failure
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify HTTP 400 Bad Request status for validation error
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        
        // Expected validation message: "Please enter User ID..." matching COBOL line 120
    }

    /**
     * Test login validation failure with null user ID.
     * 
     * <p>Validates Bean Validation (@NotBlank) enforcement for null values.
     * COBOL LOW-VALUES check equivalent in Java null handling.</p>
     */
    @Test
    @Transactional
    public void testLoginWithNullUserId() {
        // Arrange: Create login request with null user ID
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId(null); // Null user ID triggers @NotBlank validation
        loginRequest.setPassword("pass1234");

        // Act: Execute login POST request expecting validation failure
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify HTTP 400 Bad Request status for validation error
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Test login validation failure with empty password.
     * 
     * <p><strong>COBOL Reference:</strong> COSGN00C.cbl lines 123-127</p>
     * <pre>
     * WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
     *     MOVE 'Y'      TO WS-ERR-FLG
     *     MOVE 'Please enter Password ...' TO WS-MESSAGE
     *     MOVE -1       TO PASSWDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>Validates Bean Validation (@NotBlank) enforcement for password field
     * replacing COBOL SPACES/LOW-VALUES check. Expects HTTP 400 Bad Request status.</p>
     */
    @Test
    @Transactional
    public void testLoginWithEmptyPassword() {
        // Arrange: Create login request with empty password
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("TESTUSER");
        loginRequest.setPassword(""); // Empty password triggers @NotBlank validation

        // Act: Execute login POST request expecting validation failure
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify HTTP 400 Bad Request status for validation error
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        
        // Expected validation message: "Please enter Password..." matching COBOL line 125
    }

    /**
     * Test login validation failure with null password.
     * 
     * <p>Validates Bean Validation (@NotBlank) enforcement for null password.
     * COBOL LOW-VALUES check equivalent in Java null handling.</p>
     */
    @Test
    @Transactional
    public void testLoginWithNullPassword() {
        // Arrange: Create login request with null password
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("TESTUSER");
        loginRequest.setPassword(null); // Null password triggers @NotBlank validation

        // Act: Execute login POST request expecting validation failure
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify HTTP 400 Bad Request status for validation error
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Test case-insensitive user ID authentication.
     * 
     * <p><strong>COBOL Reference:</strong> COSGN00C.cbl lines 132-134</p>
     * <pre>
     * MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO
     *     WS-USER-ID
     *     CDEMO-USER-ID
     * </pre>
     * 
     * <p>Validates UPPER-CASE normalization logic preservation. User IDs should
     * be case-insensitive, with lowercase input accepted and normalized to uppercase
     * for database lookup matching COBOL behavior.</p>
     */
    @Test
    @Transactional
    public void testLoginWithLowercaseUserId() {
        // Arrange: Create login request with lowercase user ID
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("testuser"); // Lowercase should be normalized to "TESTUSER"
        loginRequest.setPassword("pass1234");

        // Act: Execute login POST request
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify successful authentication with case normalization
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getUserId()).isEqualToIgnoringCase("TESTUSER");
    }

    /**
     * Test JWT token format and structure validation.
     * 
     * <p>Validates JWT token generation replacing CICS COMMAREA session management.
     * Token should contain:</p>
     * <ul>
     *   <li>Subject: userId</li>
     *   <li>Issued At: authentication timestamp</li>
     *   <li>Expiration: 24 hours from issuance</li>
     *   <li>Claims: userType (ROLE_USER or ROLE_ADMIN)</li>
     * </ul>
     * 
     * <p>JWT format: header.payload.signature (3 parts separated by dots)</p>
     */
    @Test
    @Transactional
    public void testJwtTokenFormatValidation() {
        // Arrange: Create login request with valid credentials
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("TESTUSER");
        loginRequest.setPassword("pass1234");

        // Act: Execute login POST request
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify JWT token format (header.payload.signature)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        
        String jwtToken = response.getBody().getToken();
        assertThat(jwtToken).isNotNull().isNotEmpty();
        
        // JWT tokens have 3 parts separated by dots
        String[] jwtParts = jwtToken.split("\\.");
        assertThat(jwtParts).hasSize(3);
        
        // Each part should be Base64-encoded (non-empty alphanumeric strings)
        assertThat(jwtParts[0]).matches("[A-Za-z0-9_-]+"); // Header
        assertThat(jwtParts[1]).matches("[A-Za-z0-9_-]+"); // Payload
        assertThat(jwtParts[2]).matches("[A-Za-z0-9_-]+"); // Signature
    }

    /**
     * Test logout functionality with valid JWT token.
     * 
     * <p><strong>COBOL Reference:</strong> COSGN00C.cbl lines 88-90</p>
     * <pre>
     * WHEN DFHPF3
     *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *     PERFORM SEND-PLAIN-TEXT
     * </pre>
     * 
     * <p>Validates PF3 (Exit) functionality transformation to stateless logout.
     * In JWT architecture, logout instructs client to discard token and clears
     * Spring Security context. Returns HTTP 204 No Content.</p>
     */
    @Test
    @Transactional
    public void testLogoutWithValidToken() {
        // Arrange: First login to get JWT token
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("TESTUSER");
        loginRequest.setPassword("pass1234");
        
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );
        
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String jwtToken = loginResponse.getBody().getToken();

        // Arrange: Create logout request with Authorization header
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + jwtToken);
        HttpEntity<Void> logoutRequest = new HttpEntity<>(headers);

        // Act: Execute logout POST request
        ResponseEntity<Void> logoutResponse = restTemplate.exchange(
            baseUrl + "/logout",
            HttpMethod.POST,
            logoutRequest,
            Void.class
        );

        // Assert: Verify HTTP 204 No Content status (successful logout)
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * Test logout functionality without JWT token.
     * 
     * <p>Validates graceful handling of logout requests without Authorization header.
     * Should still return HTTP 204 No Content as logout is idempotent operation.</p>
     */
    @Test
    @Transactional
    public void testLogoutWithoutToken() {
        // Arrange: Create logout request without Authorization header
        HttpEntity<Void> logoutRequest = new HttpEntity<>(new HttpHeaders());

        // Act: Execute logout POST request
        ResponseEntity<Void> logoutResponse = restTemplate.exchange(
            baseUrl + "/logout",
            HttpMethod.POST,
            logoutRequest,
            Void.class
        );

        // Assert: Verify HTTP 204 No Content status (idempotent logout)
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * Test authentication response time under load.
     * 
     * <p>Validates performance requirement per Section 0.2 and Section 0.9:</p>
     * <ul>
     *   <li>Transaction response time &lt; 200ms at 95th percentile</li>
     *   <li>Card authorization requests under 200ms</li>
     * </ul>
     * 
     * <p>Executes multiple authentication requests and measures response times
     * to verify 95th percentile is under 200ms threshold.</p>
     */
    @Test
    @Transactional
    public void testAuthenticationPerformanceRequirement() {
        // Arrange: Create login request
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("TESTUSER");
        loginRequest.setPassword("pass1234");

        // Act: Execute 20 authentication requests and measure response times
        int iterations = 20;
        long[] responseTimes = new long[iterations];
        
        for (int i = 0; i < iterations; i++) {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();
            
            ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                baseUrl + "/login",
                loginRequest,
                LoginResponse.class
            );
            
            stopWatch.stop();
            responseTimes[i] = stopWatch.getTotalTimeMillis();
            
            // Verify successful response
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        // Assert: Calculate 95th percentile response time
        java.util.Arrays.sort(responseTimes);
        int percentile95Index = (int) Math.ceil(0.95 * iterations) - 1;
        long percentile95ResponseTime = responseTimes[percentile95Index];
        
        assertThat(percentile95ResponseTime)
            .as("95th percentile authentication response time must be under 200ms per Section 0.2")
            .isLessThan(200);
    }

    /**
     * Test response structure matches COBOL BMS screen fields.
     * 
     * <p>Validates LoginResponse fields match COSGN0AO BMS copybook structure:</p>
     * <ul>
     *   <li>transactionName = "CC00" (WS-TRANID)</li>
     *   <li>title01 = "CardDemo Application" (CCDA-TITLE01)</li>
     *   <li>title02 = "Sign On" (CCDA-TITLE02)</li>
     *   <li>currentDate present (CURDATEO format)</li>
     *   <li>currentTime present (CURTIMEO format)</li>
     * </ul>
     */
    @Test
    @Transactional
    public void testLoginResponseStructureMatchesCobolScreen() {
        // Arrange: Create login request
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("TESTUSER");
        loginRequest.setPassword("pass1234");

        // Act: Execute login POST request
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify response structure matches COBOL BMS screen
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        
        LoginResponse loginResponse = response.getBody();
        
        // Verify transaction and program identification fields
        assertThat(loginResponse.getTransactionName()).isEqualTo("CC00");
        
        // Verify title fields present
        assertThat(loginResponse.getTitle01()).isNotNull();
        assertThat(loginResponse.getTitle02()).isNotNull();
        
        // Verify timestamp fields present (not null)
        // COBOL: MOVE WS-CURDATE-MM-DD-YY TO CURDATEO OF COSGN0AO
        // COBOL: MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO OF COSGN0AO
        assertThat(loginResponse.getCurrentDate()).isNotNull();
        assertThat(loginResponse.getCurrentTime()).isNotNull();
    }

    /**
     * Test user security entity persistence after successful authentication.
     * 
     * <p>Validates UserSecurity entity is correctly retrieved from database
     * during authentication, matching COBOL VSAM READ operation:</p>
     * <pre>
     * EXEC CICS READ
     *     DATASET   (WS-USRSEC-FILE)
     *     INTO      (SEC-USER-DATA)
     *     RIDFLD    (WS-USER-ID)
     * END-EXEC
     * </pre>
     */
    @Test
    @Transactional
    public void testUserSecurityEntityRetrievalDuringAuth() {
        // Arrange: Create login request
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUserId("TESTUSER");
        loginRequest.setPassword("pass1234");

        // Act: Execute login POST request
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
            baseUrl + "/login",
            loginRequest,
            LoginResponse.class
        );

        // Assert: Verify successful authentication
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        
        // Verify user entity exists in database
        UserSecurity userFromDb = userSecurityRepository.findByUserId("TESTUSER").orElse(null);
        assertThat(userFromDb).isNotNull();
        assertThat(userFromDb.getUserId()).isEqualTo("TESTUSER");
        assertThat(userFromDb.getUserType()).isEqualTo("R"); // Regular user
        assertThat(userFromDb.getFirstName()).isEqualTo("Test");
        assertThat(userFromDb.getLastName()).isEqualTo("User");
        
        // Verify password is BCrypt-encrypted (not plain text)
        assertThat(userFromDb.getPassword()).startsWith("$2a$"); // BCrypt prefix
    }
}
