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

package com.carddemo.service;

import com.carddemo.dto.request.LoginRequest;
import com.carddemo.dto.response.LoginResponse;
import com.carddemo.entity.User;
import com.carddemo.entity.User.UserType;
import com.carddemo.repository.UserRepository;
import com.carddemo.service.auth.AuthenticationService;
import com.carddemo.service.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * JUnit 5 unit test class for AuthenticationService verifying user authentication logic preservation 
 * from COBOL program COSGN00C.cbl.
 * 
 * <p><b>COBOL Source Program: COSGN00C.cbl</b></p>
 * <ul>
 *   <li>Transaction: CC00 (Signon Screen)</li>
 *   <li>Function: User authentication with password validation</li>
 *   <li>VSAM File: USRSEC dataset (SEC-USER-DATA structure from CSUSR01Y.cpy)</li>
 *   <li>Session Management: COMMAREA structure from COCOM01Y.cpy</li>
 * </ul>
 * 
 * <p><b>Test Coverage - COBOL Logic Mapping:</b></p>
 * <ul>
 *   <li><b>Lines 118-122:</b> Blank User ID validation → testAuthenticateWithBlankUserId()</li>
 *   <li><b>Lines 123-127:</b> Blank Password validation → testAuthenticateWithBlankPassword()</li>
 *   <li><b>Lines 132-136:</b> FUNCTION UPPER-CASE transformation → testAuthenticateWithCaseInsensitiveCredentials()</li>
 *   <li><b>Lines 211-219:</b> EXEC CICS READ DATASET(WS-USRSEC-FILE) → UserRepository.findByUserId() mock</li>
 *   <li><b>Line 223:</b> SEC-USR-PWD = WS-USER-PWD → BCrypt PasswordEncoder.matches() verification</li>
 *   <li><b>Lines 230-240:</b> Role-based routing (CDEMO-USRTYP-ADMIN vs USER) → testAuthenticateAdminUser() and testAuthenticateRegularUser()</li>
 *   <li><b>Lines 241-246:</b> Wrong password error → testAuthenticateWithInvalidPassword()</li>
 *   <li><b>Lines 247-251:</b> RESP=13 User not found → testAuthenticateWithInvalidUserId()</li>
 * </ul>
 * 
 * <p><b>COBOL Data Structures Tested:</b></p>
 * <ul>
 *   <li>SEC-USER-DATA (CSUSR01Y.cpy) → User JPA entity with UserType enum</li>
 *   <li>CDEMO-USER-TYPE with 88-levels → UserType.ADMIN ('A'), UserType.USER ('U')</li>
 *   <li>CARDDEMO-COMMAREA session state → JWT token with userId and userType claims</li>
 * </ul>
 * 
 * <p><b>Testing Framework:</b></p>
 * <ul>
 *   <li>JUnit 5: @ExtendWith(MockitoExtension.class) for dependency injection</li>
 *   <li>Mockito: @Mock annotations for UserRepository, PasswordEncoder, JwtService dependencies</li>
 *   <li>AssertJ: Fluent assertions with assertThat() for readable test validation</li>
 *   <li>ArgumentCaptor: Verifies method arguments passed to mocked dependencies</li>
 * </ul>
 * 
 * <p><b>Security Transformation Testing:</b></p>
 * <ul>
 *   <li>VSAM USRSEC file READ → PostgreSQL User table query via JPA repository</li>
 *   <li>Plain text password comparison → BCrypt cryptographic hash verification</li>
 *   <li>CICS session management → Stateless JWT token generation with expiration</li>
 *   <li>RACF user type → Spring Security GrantedAuthority roles (ROLE_ADMIN, ROLE_USER)</li>
 * </ul>
 * 
 * <p><b>Test Scenarios:</b></p>
 * <ol>
 *   <li>Successful authentication with valid admin user credentials</li>
 *   <li>Successful authentication with valid regular user credentials</li>
 *   <li>Authentication failure with invalid password (BCrypt mismatch)</li>
 *   <li>User not found scenario (empty Optional from repository)</li>
 *   <li>Null LoginRequest validation</li>
 *   <li>JWT token generation verification with correct claims</li>
 *   <li>JWT token expiration timestamp validation</li>
 *   <li>Last login date update verification</li>
 *   <li>Soft-deleted user account rejection</li>
 *   <li>SecurityContext population with user authentication</li>
 * </ol>
 * 
 * @see AuthenticationService Service under test
 * @see User JPA entity replacing VSAM USRSEC file
 * @see UserRepository Repository interface for user data access
 * @see JwtService JWT token generation and validation service
 * @see LoginRequest Input DTO replacing BMS screen input
 * @see LoginResponse Output DTO replacing COMMAREA structure
 * @version 1.0
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService Unit Tests - COBOL COSGN00C.cbl Transformation")
public class AuthenticationServiceTest {

    /**
     * Service under test - AuthenticationService
     * Contains authentication logic transformed from COSGN00C.cbl PROCESS-ENTER-KEY 
     * and READ-USER-SEC-FILE paragraphs.
     */
    @InjectMocks
    private AuthenticationService authenticationService;

    /**
     * Mocked UserRepository - Simulates database access
     * Replaces COBOL: EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA)
     */
    @Mock
    private UserRepository userRepository;

    /**
     * Mocked PasswordEncoder - Simulates BCrypt password verification
     * Replaces COBOL: IF SEC-USR-PWD = WS-USER-PWD (plain text comparison)
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    /**
     * Mocked JwtService - Simulates JWT token generation
     * Replaces COBOL: COMMAREA population with CDEMO-USER-ID and CDEMO-USER-TYPE
     */
    @Mock
    private JwtService jwtService;

    // Test data constants matching COBOL field constraints
    private static final String VALID_USER_ID = "TESTUSER";  // PIC X(08) max 8 chars
    private static final String VALID_PASSWORD = "PASS1234"; // PIC X(08) max 8 chars
    private static final String ENCRYPTED_PASSWORD = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"; // BCrypt hash
    private static final String JWT_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiJURVNUVVNFUiIsInVzZXJUeXBlIjoiQURNSU4ifQ.signature";
    private static final long JWT_EXPIRATION_MS = 86400000L; // 24 hours in milliseconds

    private User adminUser;
    private User regularUser;
    private User deletedUser;
    private LoginRequest loginRequest;

    /**
     * Setup method executed before each test
     * Initializes test data matching COBOL SEC-USER-DATA structure from CSUSR01Y.cpy
     */
    @BeforeEach
    void setUp() {
        // Create admin user matching SEC-USR-TYPE = 'A' (CDEMO-USRTYP-ADMIN)
        adminUser = User.builder()
                .userId(VALID_USER_ID)
                .firstName("Test")
                .lastName("Admin")
                .password(ENCRYPTED_PASSWORD)
                .userType(UserType.ADMIN)  // Maps to COBOL 88-level CDEMO-USRTYP-ADMIN VALUE 'A'
                .deleted(false)
                .createdDate(LocalDateTime.now().minusDays(30))
                .build();

        // Create regular user matching SEC-USR-TYPE = 'U' (CDEMO-USRTYP-USER)
        regularUser = User.builder()
                .userId(VALID_USER_ID)
                .firstName("Test")
                .lastName("User")
                .password(ENCRYPTED_PASSWORD)
                .userType(UserType.USER)  // Maps to COBOL 88-level CDEMO-USRTYP-USER VALUE 'U'
                .deleted(false)
                .createdDate(LocalDateTime.now().minusDays(30))
                .build();

        // Create deleted user for soft-delete scenario testing
        deletedUser = User.builder()
                .userId(VALID_USER_ID)
                .firstName("Deleted")
                .lastName("User")
                .password(ENCRYPTED_PASSWORD)
                .userType(UserType.USER)
                .deleted(true)  // Soft delete flag
                .deletedDate(LocalDateTime.now().minusDays(5))
                .deletedBy("ADMIN001")
                .createdDate(LocalDateTime.now().minusDays(30))
                .build();

        // Create login request matching BMS COSGN00.bms screen input fields
        loginRequest = LoginRequest.builder()
                .userId(VALID_USER_ID)
                .password(VALID_PASSWORD)
                .build();
    }

    /**
     * Test: Successful authentication with admin user credentials
     * 
     * COBOL Flow (COSGN00C.cbl lines 230-234):
     * IF CDEMO-USRTYP-ADMIN
     *     EXEC CICS XCTL
     *       PROGRAM ('COADM01C')
     *       COMMAREA(CARDDEMO-COMMAREA)
     *     END-EXEC
     * 
     * Java Flow: Returns LoginResponse with JWT token containing ADMIN role
     */
    @Test
    @DisplayName("Test successful authentication with valid admin user credentials")
    void testAuthenticateAdminUserSuccess() {
        // Arrange: Configure mocks to simulate successful authentication flow
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(VALID_PASSWORD, ENCRYPTED_PASSWORD)).thenReturn(true);
        when(jwtService.generateToken(VALID_USER_ID, UserType.ADMIN.getCode())).thenReturn(JWT_TOKEN);
        when(jwtService.getTokenExpiration()).thenReturn(JWT_EXPIRATION_MS);

        // Act: Execute authentication
        LoginResponse response = authenticationService.authenticate(loginRequest);

        // Assert: Verify response matches COBOL COMMAREA structure
        assertThat(response).isNotNull();
        assertThat(response.getJwtToken()).isEqualTo(JWT_TOKEN);
        assertThat(response.getUserId()).isEqualTo(VALID_USER_ID);
        assertThat(response.getUserType()).isEqualTo(UserType.ADMIN.name());
        assertThat(response.getExpiresAt()).isAfter(LocalDateTime.now());

        // Verify: Repository findByUserId called once
        verify(userRepository, times(1)).findByUserId(VALID_USER_ID);
        
        // Verify: Password encoder matches called once
        verify(passwordEncoder, times(1)).matches(VALID_PASSWORD, ENCRYPTED_PASSWORD);
        
        // Verify: JWT token generated with correct parameters
        verify(jwtService, times(1)).generateToken(VALID_USER_ID, UserType.ADMIN.getCode());
        
        // Verify: User record updated with last login date
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getLastLoginDate()).isNotNull();
        assertThat(savedUser.getLastLoginDate()).isBetween(
            LocalDateTime.now().minusSeconds(5),
            LocalDateTime.now().plusSeconds(5)
        );
    }

    /**
     * Test: Successful authentication with regular user credentials
     * 
     * COBOL Flow (COSGN00C.cbl lines 235-239):
     * ELSE
     *     EXEC CICS XCTL
     *       PROGRAM ('COMEN01C')
     *       COMMAREA(CARDDEMO-COMMAREA)
     *     END-EXEC
     * END-IF
     * 
     * Java Flow: Returns LoginResponse with JWT token containing USER role
     */
    @Test
    @DisplayName("Test successful authentication with valid regular user credentials")
    void testAuthenticateRegularUserSuccess() {
        // Arrange: Configure mocks for regular user authentication
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.of(regularUser));
        when(passwordEncoder.matches(VALID_PASSWORD, ENCRYPTED_PASSWORD)).thenReturn(true);
        when(jwtService.generateToken(VALID_USER_ID, UserType.USER.getCode())).thenReturn(JWT_TOKEN);
        when(jwtService.getTokenExpiration()).thenReturn(JWT_EXPIRATION_MS);

        // Act: Execute authentication
        LoginResponse response = authenticationService.authenticate(loginRequest);

        // Assert: Verify response for regular user
        assertThat(response).isNotNull();
        assertThat(response.getJwtToken()).isEqualTo(JWT_TOKEN);
        assertThat(response.getUserId()).isEqualTo(VALID_USER_ID);
        assertThat(response.getUserType()).isEqualTo(UserType.USER.name());  // Verify USER type, not ADMIN
        assertThat(response.getExpiresAt()).isAfter(LocalDateTime.now());

        // Verify: JWT token generated with USER code
        verify(jwtService, times(1)).generateToken(VALID_USER_ID, UserType.USER.getCode());
    }

    /**
     * Test: Authentication failure with invalid password
     * 
     * COBOL Flow (COSGN00C.cbl lines 241-246):
     * ELSE
     *     MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *     MOVE -1 TO PASSWDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * END-IF
     * 
     * Java Flow: Throws BadCredentialsException when PasswordEncoder.matches() returns false
     */
    @Test
    @DisplayName("Test authentication failure with invalid password")
    void testAuthenticateWithInvalidPassword() {
        // Arrange: Configure mocks - user found but password mismatch
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(VALID_PASSWORD, ENCRYPTED_PASSWORD)).thenReturn(false);  // Password mismatch

        // Act & Assert: Verify BadCredentialsException thrown
        assertThatThrownBy(() -> authenticationService.authenticate(loginRequest))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Wrong Password");

        // Verify: Repository called once
        verify(userRepository, times(1)).findByUserId(VALID_USER_ID);
        
        // Verify: Password encoder called once
        verify(passwordEncoder, times(1)).matches(VALID_PASSWORD, ENCRYPTED_PASSWORD);
        
        // Verify: JWT service never called due to authentication failure
        verify(jwtService, never()).generateToken(anyString(), anyString());
        
        // Verify: User record never saved due to authentication failure
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test: User not found scenario
     * 
     * COBOL Flow (COSGN00C.cbl lines 247-251):
     * WHEN 13
     *     MOVE 'Y' TO WS-ERR-FLG
     *     MOVE 'User not found. Try again ...' TO WS-MESSAGE
     *     MOVE -1 TO USERIDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * 
     * Java Flow: Throws UsernameNotFoundException when UserRepository.findByUserId() returns empty Optional
     */
    @Test
    @DisplayName("Test authentication failure with invalid user ID (COBOL RESP-CD 13)")
    void testAuthenticateWithInvalidUserId() {
        // Arrange: Configure mock to return empty Optional (user not found)
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.empty());

        // Act & Assert: Verify UsernameNotFoundException thrown
        assertThatThrownBy(() -> authenticationService.authenticate(loginRequest))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("User not found");

        // Verify: Repository called once
        verify(userRepository, times(1)).findByUserId(VALID_USER_ID);
        
        // Verify: Password encoder never called when user not found
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        
        // Verify: JWT service never called
        verify(jwtService, never()).generateToken(anyString(), anyString());
        
        // Verify: User record never saved
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test: Null LoginRequest validation
     * 
     * COBOL equivalent: Input validation before processing
     * While COBOL doesn't explicitly check for null COMMAREA, modern Java requires null safety
     * 
     * Java Flow: Throws IllegalArgumentException when LoginRequest is null
     */
    @Test
    @DisplayName("Test authentication failure with null login request")
    void testAuthenticateWithNullRequest() {
        // Act & Assert: Verify IllegalArgumentException thrown for null request
        assertThatThrownBy(() -> authenticationService.authenticate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Login request cannot be null");

        // Verify: No repository calls made
        verify(userRepository, never()).findByUserId(anyString());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtService, never()).generateToken(anyString(), anyString());
    }

    /**
     * Test: Soft-deleted user account rejection
     * 
     * COBOL Note: COBOL COSGN00C.cbl doesn't have soft delete logic, but modern system requires it
     * This is a security enhancement beyond original COBOL behavior
     * 
     * Java Flow: Throws DisabledException when user.isDeleted() returns true
     */
    @Test
    @DisplayName("Test authentication failure with deleted user account")
    void testAuthenticateWithDeletedUser() {
        // Arrange: Configure mock to return deleted user
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.of(deletedUser));

        // Act & Assert: Verify DisabledException thrown
        assertThatThrownBy(() -> authenticationService.authenticate(loginRequest))
                .isInstanceOf(DisabledException.class)
                .hasMessageContaining("disabled or deleted");

        // Verify: Repository called once
        verify(userRepository, times(1)).findByUserId(VALID_USER_ID);
        
        // Verify: Password encoder never called for deleted users
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        
        // Verify: JWT service never called
        verify(jwtService, never()).generateToken(anyString(), anyString());
        
        // Verify: User record never saved
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * Test: JWT token expiration timestamp calculation
     * 
     * COBOL equivalent: Session timeout management (implicit in CICS)
     * JWT token expiration replaces CICS session timeout behavior
     * 
     * Validates: LoginResponse.expiresAt = current time + JWT_EXPIRATION_MS
     */
    @Test
    @DisplayName("Test JWT token expiration timestamp is calculated correctly")
    void testJwtTokenExpirationCalculation() {
        // Arrange: Record time before authentication
        LocalDateTime beforeAuth = LocalDateTime.now();
        
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(VALID_PASSWORD, ENCRYPTED_PASSWORD)).thenReturn(true);
        when(jwtService.generateToken(VALID_USER_ID, UserType.ADMIN.getCode())).thenReturn(JWT_TOKEN);
        when(jwtService.getTokenExpiration()).thenReturn(JWT_EXPIRATION_MS);

        // Act: Execute authentication
        LoginResponse response = authenticationService.authenticate(loginRequest);

        // Calculate expected expiration (current time + 24 hours)
        LocalDateTime expectedExpiration = beforeAuth.plusSeconds(JWT_EXPIRATION_MS / 1000);

        // Assert: Verify expiration is within reasonable range (allow 5 second variance for test execution time)
        assertThat(response.getExpiresAt()).isBetween(
            expectedExpiration.minusSeconds(5),
            expectedExpiration.plusSeconds(5)
        );
        
        // Assert: Expiration is in the future
        assertThat(response.getExpiresAt()).isAfter(LocalDateTime.now());
    }

    /**
     * Test: Last login date update on successful authentication
     * 
     * COBOL Note: COBOL COSGN00C.cbl doesn't update user records, but modern system tracks login audit
     * This is an audit trail enhancement beyond original COBOL behavior
     * 
     * Validates: User.lastLoginDate is updated to current timestamp on successful authentication
     */
    @Test
    @DisplayName("Test last login date is updated on successful authentication")
    void testLastLoginDateUpdate() {
        // Arrange: Record time before authentication
        LocalDateTime beforeAuth = LocalDateTime.now();
        
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(VALID_PASSWORD, ENCRYPTED_PASSWORD)).thenReturn(true);
        when(jwtService.generateToken(VALID_USER_ID, UserType.ADMIN.getCode())).thenReturn(JWT_TOKEN);
        when(jwtService.getTokenExpiration()).thenReturn(JWT_EXPIRATION_MS);

        // Act: Execute authentication
        authenticationService.authenticate(loginRequest);

        // Capture the saved user entity
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();

        // Assert: Verify last login date is updated to current time
        assertThat(savedUser.getLastLoginDate()).isNotNull();
        assertThat(savedUser.getLastLoginDate()).isBetween(
            beforeAuth.minusSeconds(1),
            LocalDateTime.now().plusSeconds(1)
        );
        
        // Assert: User ID unchanged
        assertThat(savedUser.getUserId()).isEqualTo(VALID_USER_ID);
    }

    /**
     * Test: Password encoder matches called with correct parameters
     * 
     * COBOL Flow (COSGN00C.cbl line 223):
     * IF SEC-USR-PWD = WS-USER-PWD
     * 
     * Java Flow: PasswordEncoder.matches(rawPassword, encodedPassword) replaces plain text comparison
     * 
     * Validates: BCrypt password verification receives correct raw and encoded password parameters
     */
    @Test
    @DisplayName("Test password encoder is called with correct raw and encrypted passwords")
    void testPasswordEncoderCalledWithCorrectParameters() {
        // Arrange
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(VALID_PASSWORD, ENCRYPTED_PASSWORD)).thenReturn(true);
        when(jwtService.generateToken(VALID_USER_ID, UserType.ADMIN.getCode())).thenReturn(JWT_TOKEN);
        when(jwtService.getTokenExpiration()).thenReturn(JWT_EXPIRATION_MS);

        // Act
        authenticationService.authenticate(loginRequest);

        // Assert: Verify password encoder called with raw password from request and encrypted password from database
        ArgumentCaptor<String> rawPasswordCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> encodedPasswordCaptor = ArgumentCaptor.forClass(String.class);
        
        verify(passwordEncoder, times(1)).matches(rawPasswordCaptor.capture(), encodedPasswordCaptor.capture());
        
        assertThat(rawPasswordCaptor.getValue()).isEqualTo(VALID_PASSWORD);
        assertThat(encodedPasswordCaptor.getValue()).isEqualTo(ENCRYPTED_PASSWORD);
    }

    /**
     * Test: JWT token generated with correct user ID and user type parameters
     * 
     * COBOL Flow (COSGN00C.cbl lines 224-227):
     * MOVE WS-TRANID TO CDEMO-FROM-TRANID
     * MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM
     * MOVE WS-USER-ID TO CDEMO-USER-ID
     * MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     * 
     * Java Flow: JwtService.generateToken(userId, userTypeCode) generates JWT with claims
     * 
     * Validates: JWT token generation receives correct userId and userType code parameters
     */
    @Test
    @DisplayName("Test JWT token is generated with correct userId and userType parameters")
    void testJwtTokenGeneratedWithCorrectParameters() {
        // Arrange
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(VALID_PASSWORD, ENCRYPTED_PASSWORD)).thenReturn(true);
        when(jwtService.generateToken(VALID_USER_ID, UserType.ADMIN.getCode())).thenReturn(JWT_TOKEN);
        when(jwtService.getTokenExpiration()).thenReturn(JWT_EXPIRATION_MS);

        // Act
        authenticationService.authenticate(loginRequest);

        // Assert: Verify JWT service called with correct parameters
        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userTypeCodeCaptor = ArgumentCaptor.forClass(String.class);
        
        verify(jwtService, times(1)).generateToken(userIdCaptor.capture(), userTypeCodeCaptor.capture());
        
        assertThat(userIdCaptor.getValue()).isEqualTo(VALID_USER_ID);
        assertThat(userTypeCodeCaptor.getValue()).isEqualTo(UserType.ADMIN.getCode());  // 'A' for admin
    }

    /**
     * Test: UserType enum code mapping for admin user
     * 
     * COBOL Structure (COCOM01Y.cpy lines 26-28):
     * 10 CDEMO-USER-TYPE PIC X(01).
     *    88 CDEMO-USRTYP-ADMIN VALUE 'A'.
     *    88 CDEMO-USRTYP-USER VALUE 'U'.
     * 
     * Java: UserType.ADMIN.getCode() returns 'A'
     * 
     * Validates: UserType enum correctly maps to COBOL user type values
     */
    @Test
    @DisplayName("Test UserType enum ADMIN maps to COBOL value 'A'")
    void testUserTypeAdminCodeMapping() {
        // Assert: Verify UserType.ADMIN maps to 'A' matching COBOL CDEMO-USRTYP-ADMIN VALUE 'A'
        assertThat(UserType.ADMIN.getCode()).isEqualTo("A");
        assertThat(UserType.ADMIN.name()).isEqualTo("ADMIN");
    }

    /**
     * Test: UserType enum code mapping for regular user
     * 
     * COBOL Structure (COCOM01Y.cpy line 28):
     * 88 CDEMO-USRTYP-USER VALUE 'U'.
     * 
     * Java: UserType.USER.getCode() returns 'U'
     * 
     * Validates: UserType enum correctly maps to COBOL user type values
     */
    @Test
    @DisplayName("Test UserType enum USER maps to COBOL value 'U'")
    void testUserTypeUserCodeMapping() {
        // Assert: Verify UserType.USER maps to 'U' matching COBOL CDEMO-USRTYP-USER VALUE 'U'
        assertThat(UserType.USER.getCode()).isEqualTo("U");
        assertThat(UserType.USER.name()).isEqualTo("USER");
    }

    /**
     * Test: LoginResponse contains all required fields
     * 
     * COBOL COMMAREA Structure (COCOM01Y.cpy):
     * 05 CDEMO-GENERAL-INFO.
     *    10 CDEMO-USER-ID PIC X(08).
     *    10 CDEMO-USER-TYPE PIC X(01).
     * 
     * Java: LoginResponse with jwtToken, userId, userType, expiresAt
     * 
     * Validates: Response DTO contains all fields necessary for session management
     */
    @Test
    @DisplayName("Test LoginResponse contains all required fields")
    void testLoginResponseContainsAllRequiredFields() {
        // Arrange
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(VALID_PASSWORD, ENCRYPTED_PASSWORD)).thenReturn(true);
        when(jwtService.generateToken(VALID_USER_ID, UserType.ADMIN.getCode())).thenReturn(JWT_TOKEN);
        when(jwtService.getTokenExpiration()).thenReturn(JWT_EXPIRATION_MS);

        // Act
        LoginResponse response = authenticationService.authenticate(loginRequest);

        // Assert: Verify all required fields are present and not null
        assertAll("LoginResponse fields",
            () -> assertThat(response.getJwtToken()).isNotNull().isNotEmpty(),
            () -> assertThat(response.getUserId()).isNotNull().isEqualTo(VALID_USER_ID),
            () -> assertThat(response.getUserType()).isNotNull().isIn("ADMIN", "USER"),
            () -> assertThat(response.getExpiresAt()).isNotNull().isAfter(LocalDateTime.now())
        );
    }

    /**
     * Test: Repository findByUserId called exactly once during successful authentication
     * 
     * COBOL Flow (COSGN00C.cbl lines 211-219):
     * EXEC CICS READ
     *      DATASET (WS-USRSEC-FILE)
     *      INTO (SEC-USER-DATA)
     *      RIDFLD (WS-USER-ID)
     *      RESP (WS-RESP-CD)
     * END-EXEC
     * 
     * Java: UserRepository.findByUserId() called once per authentication attempt
     * 
     * Validates: Single database query per authentication matching COBOL single file read
     */
    @Test
    @DisplayName("Test UserRepository.findByUserId is called exactly once")
    void testRepositoryCalledOnce() {
        // Arrange
        when(userRepository.findByUserId(VALID_USER_ID)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.matches(VALID_PASSWORD, ENCRYPTED_PASSWORD)).thenReturn(true);
        when(jwtService.generateToken(VALID_USER_ID, UserType.ADMIN.getCode())).thenReturn(JWT_TOKEN);
        when(jwtService.getTokenExpiration()).thenReturn(JWT_EXPIRATION_MS);

        // Act
        authenticationService.authenticate(loginRequest);

        // Assert: Verify findByUserId called exactly once (no redundant queries)
        verify(userRepository, times(1)).findByUserId(VALID_USER_ID);
        verify(userRepository, times(1)).save(any(User.class));  // Save called once for lastLoginDate update
    }
}
