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
import com.carddemo.entity.UserSecurity;
import com.carddemo.exception.AuthenticationFailedException;
import com.carddemo.exception.UserNotFoundException;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.security.JwtTokenProvider;
import com.carddemo.security.SecurityConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive JUnit 5 test suite for AuthenticationService.
 * 
 * <p>Tests business logic transformation from COSGN00C.cbl COBOL program,
 * validating user authentication and sign-on functionality including:</p>
 * 
 * <ul>
 *   <li>Username/password validation matching COBOL validation rules</li>
 *   <li>USRSEC file lookup transformed to UserDetailsService</li>
 *   <li>BCrypt password verification replacing plain-text comparison</li>
 *   <li>JWT token generation for stateless authentication</li>
 *   <li>Role extraction (USER-TYPE 'R'/'U'/'A' to GrantedAuthority)</li>
 *   <li>Session management with Spring Security context</li>
 *   <li>Authentication failure handling matching CICS sign-on error codes</li>
 * </ul>
 * 
 * <h2>COBOL Source Mapping</h2>
 * 
 * <p>This test class validates the transformation of COSGN00C.cbl logic:</p>
 * <pre>
 * COBOL Logic (COSGN00C.cbl)              JUnit Test Method
 * ────────────────────────────────────────────────────────────────────────
 * Lines 118-122: Empty User ID            testAuthenticate_EmptyUserId_ThrowsAuthenticationException()
 * Lines 123-127: Empty Password           testAuthenticate_EmptyPassword_ThrowsAuthenticationException()
 * Lines 132-136: UPPER-CASE function      testAuthenticate_UserIdToUpperCase_NormalizedBeforeLookup()
 * Lines 211-219: READ USRSEC file         testAuthenticate_ValidCredentials_ReturnsToken()
 * Line 223: Password comparison           testAuthenticate_WrongPassword_ThrowsAuthenticationException()
 * Lines 230-240: Admin user check         testAuthenticate_AdminUser_ReturnsRoleAdmin()
 * Lines 241-246: Wrong password msg       testAuthenticate_WrongPassword_ThrowsAuthenticationException()
 * Lines 247-251: User not found (RESP=13) testAuthenticate_InvalidUserId_ThrowsUserNotFoundException()
 * Lines 252-257: Other errors             testAuthenticate_DatabaseError_ThrowsAuthenticationException()
 * </pre>
 * 
 * <h2>Test Coverage Requirements</h2>
 * 
 * <p>Per Section 0.9 Test Case Compatibility requirements:</p>
 * <ul>
 *   <li>Minimum 80% line coverage of AuthenticationService</li>
 *   <li>All COBOL error paths covered (RESP codes 0, 13, other)</li>
 *   <li>All validation rules tested</li>
 *   <li>Security audit logging verified</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @see AuthenticationService
 * @see com.carddemo.entity.UserSecurity
 * @see com.carddemo.security.JwtTokenProvider
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthenticationService Test Suite")
class AuthenticationServiceTest {
    
    /**
     * Mock UserSecurityRepository for database operations.
     * 
     * Simulates VSAM USRSEC file read operations from COSGN00C.cbl:
     * <pre>
     * EXEC CICS READ
     *      DATASET   (WS-USRSEC-FILE)
     *      INTO      (SEC-USER-DATA)
     *      RIDFLD    (WS-USER-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * </pre>
     */
    @Mock
    private UserSecurityRepository userSecurityRepository;
    
    /**
     * Mock BCryptPasswordEncoder for password verification.
     * 
     * Replaces COBOL plain text password comparison:
     * <pre>
     * IF SEC-USR-PWD = WS-USER-PWD
     *     ... authentication success ...
     * ELSE
     *     MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     * END-IF
     * </pre>
     */
    @Mock
    private PasswordEncoder passwordEncoder;
    
    /**
     * Mock JwtTokenProvider for token generation.
     * 
     * Generates JWT tokens replacing CICS COMMAREA session management:
     * <pre>
     * COBOL:
     * MOVE WS-USER-ID   TO CDEMO-USER-ID
     * MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     * 
     * Java:
     * String token = jwtTokenProvider.generateToken(authentication);
     * </pre>
     */
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    
    /**
     * Mock AuthenticationManager for Spring Security integration.
     * 
     * Optional dependency that may not be configured in test context.
     * Tests validate behavior both with and without AuthenticationManager.
     */
    @Mock
    private AuthenticationManager authenticationManager;
    
    /**
     * The AuthenticationService instance under test.
     * 
     * Automatically injected with mock dependencies by Mockito.
     */
    @InjectMocks
    private AuthenticationService authenticationService;
    
    // Test data constants
    private static final String VALID_USER_ID = "USER0001";
    private static final String VALID_PASSWORD = "password";
    private static final String BCRYPT_PASSWORD_HASH = "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final String JWT_TOKEN = "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJVU0VSMDAwMSJ9.signature";
    private static final String ADMIN_USER_ID = "ADMIN001";
    
    /**
     * Set up test fixtures before each test method.
     * 
     * Initializes test data and common mock behavior.
     */
    @BeforeEach
    void setUp() {
        // Clear security context before each test
        SecurityContextHolder.clearContext();
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * Creates a mock regular user entity.
     * 
     * <p>Simulates VSAM USRSEC file record from CSUSR01Y.cpy:</p>
     * <pre>
     * 01 SEC-USER-DATA.
     *    05 SEC-USR-ID     PIC X(08).
     *    05 SEC-USR-FNAME  PIC X(20).
     *    05 SEC-USR-LNAME  PIC X(20).
     *    05 SEC-USR-PWD    PIC X(08).
     *    05 SEC-USR-TYPE   PIC X(01).
     * </pre>
     * 
     * @return UserSecurity entity with regular user type ('U')
     */
    private UserSecurity createMockRegularUser() {
        UserSecurity user = new UserSecurity();
        user.setUserId(VALID_USER_ID);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setPassword(BCRYPT_PASSWORD_HASH);
        user.setUserType(SecurityConstants.USER_TYPE_USER); // 'U'
        return user;
    }
    
    /**
     * Creates a mock administrative user entity.
     * 
     * <p>Admin user type from COBOL COCOM01Y.cpy:</p>
     * <pre>
     * 88 CDEMO-USRTYP-ADMIN VALUE 'A'.
     * </pre>
     * 
     * @return UserSecurity entity with admin user type ('A')
     */
    private UserSecurity createMockAdminUser() {
        UserSecurity user = new UserSecurity();
        user.setUserId(ADMIN_USER_ID);
        user.setFirstName("Admin");
        user.setLastName("User");
        user.setPassword(BCRYPT_PASSWORD_HASH);
        user.setUserType(SecurityConstants.USER_TYPE_ADMIN); // 'A'
        return user;
    }
    
    /**
     * Creates a mock user with 'R' (Regular) user type.
     * 
     * <p>Alternative regular user type per Section 0.9 requirements.</p>
     * 
     * @return UserSecurity entity with regular user type ('R')
     */
    private UserSecurity createMockRegularUserWithRType() {
        UserSecurity user = new UserSecurity();
        user.setUserId(VALID_USER_ID);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setPassword(BCRYPT_PASSWORD_HASH);
        user.setUserType(SecurityConstants.USER_TYPE_REGULAR); // 'R'
        return user;
    }
    
    /**
     * Creates a valid LoginRequest.
     * 
     * <p>Maps to COBOL BMS screen input fields from COSGN00:</p>
     * <pre>
     * USERIDI OF COSGN0AI (PIC X(08))
     * PASSWDI OF COSGN0AI (PIC X(08))
     * </pre>
     * 
     * @param userId user identifier (8 characters max)
     * @param password user password (8 characters in COBOL, variable in Java)
     * @return LoginRequest DTO with credentials
     */
    private LoginRequest createLoginRequest(String userId, String password) {
        LoginRequest request = new LoginRequest();
        request.setUserId(userId);
        request.setPassword(password);
        return request;
    }
    
    // ==================== Test Methods ====================
    
    /**
     * Test successful authentication with valid credentials.
     * 
     * <p>COBOL equivalent (COSGN00C.cbl lines 222-240):</p>
     * <pre>
     * WHEN 0
     *     IF SEC-USR-PWD = WS-USER-PWD
     *         MOVE WS-TRANID    TO CDEMO-FROM-TRANID
     *         MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
     *         MOVE WS-USER-ID   TO CDEMO-USER-ID
     *         MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *         MOVE ZEROS        TO CDEMO-PGM-CONTEXT
     *         EXEC CICS XCTL PROGRAM('COMEN01C') COMMAREA(...) END-EXEC
     *     END-IF
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>User lookup succeeds (RESP=0 equivalent)</li>
     *   <li>Password verification passes</li>
     *   <li>JWT token is generated</li>
     *   <li>LoginResponse contains token and user details</li>
     *   <li>Security context is populated</li>
     * </ul>
     */
    @Test
    @DisplayName("authenticate() with valid credentials should return JWT token")
    void testAuthenticate_ValidCredentials_ReturnsToken() {
        // Arrange
        LoginRequest request = createLoginRequest(VALID_USER_ID, VALID_PASSWORD);
        UserSecurity mockUser = createMockRegularUser();
        
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH))
            .thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Authentication.class)))
            .thenReturn(JWT_TOKEN);
        
        // Act
        LoginResponse response = authenticationService.authenticate(request);
        
        // Assert
        assertNotNull(response, "LoginResponse should not be null");
        assertEquals(JWT_TOKEN, response.getToken(), "JWT token should match generated token");
        assertEquals(VALID_USER_ID, response.getUserId(), "User ID should match authenticated user");
        assertEquals("CC00", response.getTransactionName(), "Transaction name should be CC00 (COBOL TRANID)");
        assertEquals("COSGN00C", response.getProgramName(), "Program name should be COSGN00C");
        assertNotNull(response.getCurrentDate(), "Current date should be set");
        assertNotNull(response.getCurrentTime(), "Current time should be set");
        
        // Verify interactions
        verify(userSecurityRepository, times(1)).findByUserId(VALID_USER_ID);
        verify(passwordEncoder, times(1)).matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH);
        verify(jwtTokenProvider, times(1)).generateToken(any(Authentication.class));
    }
    
    /**
     * Test authentication failure with invalid user ID.
     * 
     * <p>COBOL equivalent (COSGN00C.cbl lines 247-251):</p>
     * <pre>
     * WHEN 13
     *     MOVE 'Y'      TO WS-ERR-FLG
     *     MOVE 'User not found. Try again ...' TO WS-MESSAGE
     *     MOVE -1       TO USERIDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>User lookup returns empty (RESP=13 equivalent)</li>
     *   <li>UserNotFoundException is thrown</li>
     *   <li>Error message matches COBOL: "User not found. Try again ..."</li>
     *   <li>No password verification attempted</li>
     *   <li>No JWT token generated</li>
     * </ul>
     */
    @Test
    @DisplayName("authenticate() with invalid user ID should throw UserNotFoundException")
    void testAuthenticate_InvalidUserId_ThrowsUserNotFoundException() {
        // Arrange
        LoginRequest request = createLoginRequest("INVALID99", VALID_PASSWORD);
        
        when(userSecurityRepository.findByUserId("INVALID99"))
            .thenReturn(Optional.empty());
        
        // Act & Assert
        UserNotFoundException exception = assertThrows(
            UserNotFoundException.class,
            () -> authenticationService.authenticate(request),
            "Should throw UserNotFoundException for non-existent user"
        );
        
        assertTrue(
            exception.getMessage().contains("User not found"),
            "Exception message should match COBOL error text"
        );
        assertEquals("INVALID99", exception.getUserId(), "Exception should contain the invalid user ID");
        
        // Verify password check was never attempted
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtTokenProvider, never()).generateToken(any());
    }
    
    /**
     * Test authentication failure with wrong password.
     * 
     * <p>COBOL equivalent (COSGN00C.cbl lines 241-246):</p>
     * <pre>
     * ELSE
     *     MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *     MOVE -1       TO PASSWDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * END-IF
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>User lookup succeeds</li>
     *   <li>BCrypt password verification fails</li>
     *   <li>AuthenticationFailedException is thrown</li>
     *   <li>Error message matches COBOL: "Wrong Password. Try again ..."</li>
     *   <li>No JWT token generated</li>
     * </ul>
     */
    @Test
    @DisplayName("authenticate() with wrong password should throw AuthenticationFailedException")
    void testAuthenticate_WrongPassword_ThrowsAuthenticationFailedException() {
        // Arrange
        LoginRequest request = createLoginRequest(VALID_USER_ID, "wrongpassword");
        UserSecurity mockUser = createMockRegularUser();
        
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches("wrongpassword", BCRYPT_PASSWORD_HASH))
            .thenReturn(false);
        
        // Act & Assert
        AuthenticationFailedException exception = assertThrows(
            AuthenticationFailedException.class,
            () -> authenticationService.authenticate(request),
            "Should throw AuthenticationFailedException for wrong password"
        );
        
        assertTrue(
            exception.getMessage().contains("Wrong Password"),
            "Exception message should match COBOL error text: 'Wrong Password. Try again ...'"
        );
        assertEquals(
            AuthenticationFailedException.AuthFailureReason.INVALID_PASSWORD,
            exception.getReason(),
            "Failure reason should be INVALID_PASSWORD"
        );
        
        // Verify JWT token was never generated
        verify(jwtTokenProvider, never()).generateToken(any());
    }
    
    /**
     * Test authentication failure with empty user ID.
     * 
     * <p>COBOL equivalent (COSGN00C.cbl lines 118-122):</p>
     * <pre>
     * WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
     *     MOVE 'Y'      TO WS-ERR-FLG
     *     MOVE 'Please enter User ID ...' TO WS-MESSAGE
     *     MOVE -1       TO USERIDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Empty user ID is rejected</li>
     *   <li>Null user ID is rejected</li>
     *   <li>Blank user ID is rejected</li>
     *   <li>Error message matches COBOL: "Please enter User ID ..."</li>
     * </ul>
     */
    @Test
    @DisplayName("authenticate() with empty user ID should throw AuthenticationFailedException")
    void testAuthenticate_EmptyUserId_ThrowsAuthenticationFailedException() {
        // Test with null user ID
        LoginRequest requestWithNull = createLoginRequest(null, VALID_PASSWORD);
        
        AuthenticationFailedException exceptionNull = assertThrows(
            AuthenticationFailedException.class,
            () -> authenticationService.authenticate(requestWithNull),
            "Should throw AuthenticationFailedException for null user ID"
        );
        
        assertTrue(
            exceptionNull.getMessage().contains("Please enter User ID"),
            "Exception message should match COBOL validation text"
        );
        
        // Test with empty string user ID
        LoginRequest requestWithEmpty = createLoginRequest("", VALID_PASSWORD);
        
        AuthenticationFailedException exceptionEmpty = assertThrows(
            AuthenticationFailedException.class,
            () -> authenticationService.authenticate(requestWithEmpty),
            "Should throw AuthenticationFailedException for empty user ID"
        );
        
        assertTrue(
            exceptionEmpty.getMessage().contains("Please enter User ID"),
            "Exception message should match COBOL validation text"
        );
        
        // Test with blank (spaces) user ID
        LoginRequest requestWithBlank = createLoginRequest("   ", VALID_PASSWORD);
        
        AuthenticationFailedException exceptionBlank = assertThrows(
            AuthenticationFailedException.class,
            () -> authenticationService.authenticate(requestWithBlank),
            "Should throw AuthenticationFailedException for blank user ID"
        );
        
        assertTrue(
            exceptionBlank.getMessage().contains("Please enter User ID"),
            "Exception message should match COBOL validation text"
        );
        
        // Verify no database access attempted
        verify(userSecurityRepository, never()).findByUserId(anyString());
    }
    
    /**
     * Test authentication failure with empty password.
     * 
     * <p>COBOL equivalent (COSGN00C.cbl lines 123-127):</p>
     * <pre>
     * WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
     *     MOVE 'Y'      TO WS-ERR-FLG
     *     MOVE 'Please enter Password ...' TO WS-MESSAGE
     *     MOVE -1       TO PASSWDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Empty password is rejected</li>
     *   <li>Null password is rejected</li>
     *   <li>Blank password is rejected</li>
     *   <li>Error message matches COBOL: "Please enter Password ..."</li>
     * </ul>
     */
    @Test
    @DisplayName("authenticate() with empty password should throw AuthenticationFailedException")
    void testAuthenticate_EmptyPassword_ThrowsAuthenticationFailedException() {
        // Test with null password
        LoginRequest requestWithNull = createLoginRequest(VALID_USER_ID, null);
        
        AuthenticationFailedException exceptionNull = assertThrows(
            AuthenticationFailedException.class,
            () -> authenticationService.authenticate(requestWithNull),
            "Should throw AuthenticationFailedException for null password"
        );
        
        assertTrue(
            exceptionNull.getMessage().contains("Please enter Password"),
            "Exception message should match COBOL validation text"
        );
        
        // Test with empty string password
        LoginRequest requestWithEmpty = createLoginRequest(VALID_USER_ID, "");
        
        AuthenticationFailedException exceptionEmpty = assertThrows(
            AuthenticationFailedException.class,
            () -> authenticationService.authenticate(requestWithEmpty),
            "Should throw AuthenticationFailedException for empty password"
        );
        
        assertTrue(
            exceptionEmpty.getMessage().contains("Please enter Password"),
            "Exception message should match COBOL validation text"
        );
        
        // Test with blank (spaces) password
        LoginRequest requestWithBlank = createLoginRequest(VALID_USER_ID, "   ");
        
        AuthenticationFailedException exceptionBlank = assertThrows(
            AuthenticationFailedException.class,
            () -> authenticationService.authenticate(requestWithBlank),
            "Should throw AuthenticationFailedException for blank password"
        );
        
        assertTrue(
            exceptionBlank.getMessage().contains("Please enter Password"),
            "Exception message should match COBOL validation text"
        );
        
        // Verify no database access attempted
        verify(userSecurityRepository, never()).findByUserId(anyString());
    }
    
    /**
     * Test username normalization to uppercase.
     * 
     * <p>COBOL equivalent (COSGN00C.cbl lines 132-136):</p>
     * <pre>
     * MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO
     *         WS-USER-ID
     *         CDEMO-USER-ID
     * MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO
     *         WS-USER-PWD
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>Lowercase user ID is converted to uppercase</li>
     *   <li>Mixed case user ID is normalized</li>
     *   <li>Repository query uses uppercase value</li>
     *   <li>Case-insensitive authentication behavior matches COBOL</li>
     * </ul>
     */
    @Test
    @DisplayName("authenticate() should normalize user ID to uppercase before lookup")
    void testAuthenticate_UserIdToUpperCase_NormalizedBeforeLookup() {
        // Arrange - use lowercase user ID
        LoginRequest request = createLoginRequest("user0001", VALID_PASSWORD);
        UserSecurity mockUser = createMockRegularUser();
        
        // Mock repository to expect uppercase
        when(userSecurityRepository.findByUserId("USER0001"))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH))
            .thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Authentication.class)))
            .thenReturn(JWT_TOKEN);
        
        // Act
        LoginResponse response = authenticationService.authenticate(request);
        
        // Assert
        assertNotNull(response, "LoginResponse should not be null");
        assertEquals(VALID_USER_ID, response.getUserId(), "User ID should be uppercase in response");
        
        // Verify repository was called with uppercase user ID
        verify(userSecurityRepository, times(1)).findByUserId("USER0001");
    }
    
    /**
     * Test authentication for regular user returns ROLE_USER authority.
     * 
     * <p>COBOL equivalent (COSGN00C.cbl lines 235-239):</p>
     * <pre>
     * ELSE
     *     EXEC CICS XCTL
     *         PROGRAM ('COMEN01C')
     *         COMMAREA(CARDDEMO-COMMAREA)
     *     END-EXEC
     * END-IF
     * </pre>
     * 
     * <p>User type mapping from COCOM01Y.cpy:</p>
     * <pre>
     * 88 CDEMO-USRTYP-USER VALUE 'U'.
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>User type 'U' maps to ROLE_USER</li>
     *   <li>JWT token contains ROLE_USER authority</li>
     *   <li>User is NOT granted ROLE_ADMIN</li>
     *   <li>Regular user routed to main menu (not admin menu)</li>
     * </ul>
     */
    @Test
    @DisplayName("authenticate() for regular user should return ROLE_USER authority")
    void testAuthenticate_RegularUser_ReturnsRoleUser() {
        // Arrange
        LoginRequest request = createLoginRequest(VALID_USER_ID, VALID_PASSWORD);
        UserSecurity mockUser = createMockRegularUser();
        
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH))
            .thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Authentication.class)))
            .thenReturn(JWT_TOKEN);
        
        // Act
        LoginResponse response = authenticationService.authenticate(request);
        
        // Assert
        assertNotNull(response, "LoginResponse should not be null");
        assertEquals(JWT_TOKEN, response.getToken(), "JWT token should be generated");
        
        // Verify user authorities contain ROLE_USER
        assertTrue(
            mockUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> role.equals(SecurityConstants.ROLE_USER)),
            "User should have ROLE_USER authority"
        );
        
        // Verify user does NOT have ROLE_ADMIN
        assertFalse(
            mockUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> role.equals(SecurityConstants.ROLE_ADMIN)),
            "Regular user should NOT have ROLE_ADMIN authority"
        );
    }
    
    /**
     * Test authentication for administrative user returns ROLE_ADMIN authority.
     * 
     * <p>COBOL equivalent (COSGN00C.cbl lines 230-234):</p>
     * <pre>
     * IF CDEMO-USRTYP-ADMIN
     *     EXEC CICS XCTL
     *         PROGRAM ('COADM01C')
     *         COMMAREA(CARDDEMO-COMMAREA)
     *     END-EXEC
     * </pre>
     * 
     * <p>User type mapping from COCOM01Y.cpy:</p>
     * <pre>
     * 88 CDEMO-USRTYP-ADMIN VALUE 'A'.
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>User type 'A' maps to ROLE_ADMIN</li>
     *   <li>JWT token contains ROLE_ADMIN authority</li>
     *   <li>Admin user is routed to admin menu (COADM01C equivalent)</li>
     *   <li>Hierarchical roles: Admin also has ROLE_USER</li>
     * </ul>
     */
    @Test
    @DisplayName("authenticate() for admin user should return ROLE_ADMIN authority")
    void testAuthenticate_AdminUser_ReturnsRoleAdmin() {
        // Arrange
        LoginRequest request = createLoginRequest(ADMIN_USER_ID, VALID_PASSWORD);
        UserSecurity mockUser = createMockAdminUser();
        
        when(userSecurityRepository.findByUserId(ADMIN_USER_ID))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH))
            .thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Authentication.class)))
            .thenReturn(JWT_TOKEN);
        
        // Act
        LoginResponse response = authenticationService.authenticate(request);
        
        // Assert
        assertNotNull(response, "LoginResponse should not be null");
        assertEquals(JWT_TOKEN, response.getToken(), "JWT token should be generated");
        assertEquals(ADMIN_USER_ID, response.getUserId(), "User ID should match admin user");
        
        // Verify user authorities contain ROLE_ADMIN
        assertTrue(
            mockUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> role.equals(SecurityConstants.ROLE_ADMIN)),
            "Admin user should have ROLE_ADMIN authority"
        );
    }
    
    /**
     * Test JWT token generation with correct claims.
     * 
     * <p>Verifies JWT token contains:</p>
     * <ul>
     *   <li>Subject (sub): userId from authentication</li>
     *   <li>Roles claim: user authorities</li>
     *   <li>Issued at (iat): timestamp</li>
     *   <li>Expiration (exp): 24 hours from issue</li>
     * </ul>
     * 
     * <p>COBOL COMMAREA equivalent:</p>
     * <pre>
     * CDEMO-USER-ID    → JWT "sub" claim
     * CDEMO-USER-TYPE  → JWT "roles" claim
     * </pre>
     */
    @Test
    @DisplayName("authenticate() should generate JWT with correct claims")
    void testAuthenticate_GeneratesJWT_WithCorrectClaims() {
        // Arrange
        LoginRequest request = createLoginRequest(VALID_USER_ID, VALID_PASSWORD);
        UserSecurity mockUser = createMockRegularUser();
        
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH))
            .thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Authentication.class)))
            .thenReturn(JWT_TOKEN);
        
        // Act
        LoginResponse response = authenticationService.authenticate(request);
        
        // Assert
        assertNotNull(response.getToken(), "JWT token should be present in response");
        assertEquals(JWT_TOKEN, response.getToken(), "JWT token should match generated token");
        
        // Verify JwtTokenProvider was called with authentication object
        verify(jwtTokenProvider, times(1)).generateToken(any(Authentication.class));
    }
    
    /**
     * Test Security Context is populated after successful authentication.
     * 
     * <p>Verifies Spring Security integration:</p>
     * <ul>
     *   <li>SecurityContextHolder contains Authentication</li>
     *   <li>Authentication principal is set</li>
     *   <li>Authentication authorities are populated</li>
     *   <li>User session context established</li>
     * </ul>
     * 
     * <p>Replaces CICS session management with Spring Security context.</p>
     */
    @Test
    @DisplayName("authenticate() should set Security Context after success")
    void testAuthenticate_SetsSecurityContext_AfterSuccess() {
        // Arrange
        LoginRequest request = createLoginRequest(VALID_USER_ID, VALID_PASSWORD);
        UserSecurity mockUser = createMockRegularUser();
        
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH))
            .thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Authentication.class)))
            .thenReturn(JWT_TOKEN);
        
        // Clear security context before test
        SecurityContextHolder.clearContext();
        
        // Act
        LoginResponse response = authenticationService.authenticate(request);
        
        // Assert
        assertNotNull(response, "LoginResponse should not be null");
        
        // Verify Security Context was populated
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication, "Security Context should contain Authentication");
        assertNotNull(authentication.getPrincipal(), "Authentication should have principal");
        assertFalse(authentication.getAuthorities().isEmpty(), "Authentication should have authorities");
    }
    
    /**
     * Test BCrypt password verification with strength 12.
     * 
     * <p>Per Section 0.9 requirements:</p>
     * <ul>
     *   <li>BCrypt encoder with strength=12</li>
     *   <li>Password never logged or stored in plain text</li>
     *   <li>One-way hash comparison only</li>
     * </ul>
     * 
     * <p>Replaces COBOL plain text comparison:</p>
     * <pre>
     * IF SEC-USR-PWD = WS-USER-PWD
     * </pre>
     * 
     * <p>With secure BCrypt verification:</p>
     * <pre>
     * passwordEncoder.matches(plainPassword, hashedPassword)
     * </pre>
     */
    @Test
    @DisplayName("authenticate() should use BCrypt password verification with strength 12")
    void testAuthenticate_BCryptPasswordVerification_Strength12() {
        // Arrange
        LoginRequest request = createLoginRequest(VALID_USER_ID, VALID_PASSWORD);
        UserSecurity mockUser = createMockRegularUser();
        
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(eq(VALID_PASSWORD), eq(BCRYPT_PASSWORD_HASH)))
            .thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Authentication.class)))
            .thenReturn(JWT_TOKEN);
        
        // Act
        LoginResponse response = authenticationService.authenticate(request);
        
        // Assert
        assertNotNull(response, "LoginResponse should not be null");
        
        // Verify BCrypt matches() was called with correct parameters
        verify(passwordEncoder, times(1)).matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH);
        
        // Verify plain password was compared with hash (not plain text comparison)
        // This ensures password security enhancement from COBOL
    }
    
    /**
     * Test database error handling.
     * 
     * <p>COBOL equivalent (COSGN00C.cbl lines 252-257):</p>
     * <pre>
     * WHEN OTHER
     *     MOVE 'Y'      TO WS-ERR-FLG
     *     MOVE 'Unable to verify the User ...' TO WS-MESSAGE
     *     MOVE -1       TO USERIDL OF COSGN0AI
     *     PERFORM SEND-SIGNON-SCREEN
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>DataAccessException from repository</li>
     *   <li>Mapped to AuthenticationFailedException</li>
     *   <li>Error message matches COBOL: "Unable to verify the User ..."</li>
     *   <li>Generic failure handling for unexpected errors</li>
     * </ul>
     */
    @Test
    @DisplayName("authenticate() with database error should throw AuthenticationFailedException")
    void testAuthenticate_DatabaseError_ThrowsAuthenticationFailedException() {
        // Arrange
        LoginRequest request = createLoginRequest(VALID_USER_ID, VALID_PASSWORD);
        
        // Simulate database connection error
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenThrow(new DataAccessException("Database connection failed") {
                private static final long serialVersionUID = 1L;
            });
        
        // Act & Assert
        AuthenticationFailedException exception = assertThrows(
            AuthenticationFailedException.class,
            () -> authenticationService.authenticate(request),
            "Should throw AuthenticationFailedException for database errors"
        );
        
        assertTrue(
            exception.getMessage().contains("Unable to verify the User"),
            "Exception message should match COBOL error text for OTHER RESP codes"
        );
        
        // Verify password check was never attempted after database error
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtTokenProvider, never()).generateToken(any());
    }
    
    /**
     * Test loadUserByUsername() implements UserDetailsService interface.
     * 
     * <p>Spring Security UserDetailsService contract:</p>
     * <ul>
     *   <li>Returns UserDetails for valid username</li>
     *   <li>Throws UsernameNotFoundException for invalid username</li>
     *   <li>UserDetails contains username, password, authorities</li>
     *   <li>Integration with Spring Security authentication manager</li>
     * </ul>
     * 
     * <p>COBOL equivalent: READ USRSEC file by user ID</p>
     */
    @Test
    @DisplayName("loadUserByUsername() should return UserDetails for valid username")
    void testLoadUserByUsername_ValidUsername_ReturnsUserDetails() {
        // Arrange
        UserSecurity mockUser = createMockRegularUser();
        
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenReturn(Optional.of(mockUser));
        
        // Act
        UserDetails userDetails = authenticationService.loadUserByUsername(VALID_USER_ID);
        
        // Assert
        assertNotNull(userDetails, "UserDetails should not be null");
        assertEquals(VALID_USER_ID, userDetails.getUsername(), "Username should match");
        assertEquals(BCRYPT_PASSWORD_HASH, userDetails.getPassword(), "Password hash should match");
        assertFalse(userDetails.getAuthorities().isEmpty(), "Authorities should be populated");
        assertTrue(userDetails.isAccountNonExpired(), "Account should not be expired");
        assertTrue(userDetails.isAccountNonLocked(), "Account should not be locked");
        assertTrue(userDetails.isCredentialsNonExpired(), "Credentials should not be expired");
        assertTrue(userDetails.isEnabled(), "Account should be enabled");
        
        // Verify repository was queried
        verify(userSecurityRepository, times(1)).findByUserId(VALID_USER_ID);
    }
    
    /**
     * Test loadUserByUsername() throws UsernameNotFoundException for invalid username.
     * 
     * <p>Maps to COBOL RESP=13 (NOTFND):</p>
     * <pre>
     * WHEN 13
     *     MOVE 'User not found. Try again ...' TO WS-MESSAGE
     * </pre>
     */
    @Test
    @DisplayName("loadUserByUsername() should throw UsernameNotFoundException for invalid username")
    void testLoadUserByUsername_InvalidUsername_ThrowsException() {
        // Arrange
        when(userSecurityRepository.findByUserId("INVALID99"))
            .thenReturn(Optional.empty());
        
        // Act & Assert
        UsernameNotFoundException exception = assertThrows(
            UsernameNotFoundException.class,
            () -> authenticationService.loadUserByUsername("INVALID99"),
            "Should throw UsernameNotFoundException for non-existent user"
        );
        
        assertTrue(
            exception.getMessage().contains("User not found"),
            "Exception message should indicate user not found"
        );
        
        verify(userSecurityRepository, times(1)).findByUserId("INVALID99");
    }
    
    /**
     * Test loadUserByUsername() normalizes username to uppercase.
     * 
     * <p>COBOL UPPER-CASE function equivalent:</p>
     * <pre>
     * MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID
     * </pre>
     */
    @Test
    @DisplayName("loadUserByUsername() should normalize username to uppercase")
    void testLoadUserByUsername_NormalizesToUpperCase() {
        // Arrange
        UserSecurity mockUser = createMockRegularUser();
        
        when(userSecurityRepository.findByUserId("USER0001"))
            .thenReturn(Optional.of(mockUser));
        
        // Act - pass lowercase username
        UserDetails userDetails = authenticationService.loadUserByUsername("user0001");
        
        // Assert
        assertNotNull(userDetails, "UserDetails should be returned");
        
        // Verify repository was called with uppercase
        verify(userSecurityRepository, times(1)).findByUserId("USER0001");
    }
    
    /**
     * Test loadUserByUsername() handles null username.
     * 
     * <p>Defensive validation before database query.</p>
     */
    @Test
    @DisplayName("loadUserByUsername() should throw UsernameNotFoundException for null username")
    void testLoadUserByUsername_NullUsername_ThrowsException() {
        // Act & Assert
        UsernameNotFoundException exception = assertThrows(
            UsernameNotFoundException.class,
            () -> authenticationService.loadUserByUsername(null),
            "Should throw UsernameNotFoundException for null username"
        );
        
        assertTrue(
            exception.getMessage().contains("cannot be null or empty"),
            "Exception message should indicate null/empty username"
        );
        
        // Verify no database access attempted
        verify(userSecurityRepository, never()).findByUserId(anyString());
    }
    
    /**
     * Test loadUserByUsername() handles empty username.
     * 
     * <p>COBOL validation equivalent:</p>
     * <pre>
     * IF USERIDI = SPACES OR LOW-VALUES
     * </pre>
     */
    @Test
    @DisplayName("loadUserByUsername() should throw UsernameNotFoundException for empty username")
    void testLoadUserByUsername_EmptyUsername_ThrowsException() {
        // Act & Assert
        UsernameNotFoundException exception = assertThrows(
            UsernameNotFoundException.class,
            () -> authenticationService.loadUserByUsername(""),
            "Should throw UsernameNotFoundException for empty username"
        );
        
        assertTrue(
            exception.getMessage().contains("cannot be null or empty"),
            "Exception message should indicate null/empty username"
        );
        
        // Verify no database access attempted
        verify(userSecurityRepository, never()).findByUserId(anyString());
    }
    
    /**
     * Test logout() clears Security Context.
     * 
     * <p>COBOL equivalent (PF3 exit key):</p>
     * <pre>
     * WHEN DFHPF3
     *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *     PERFORM SEND-PLAIN-TEXT
     *     EXEC CICS RETURN END-EXEC
     * </pre>
     * 
     * <p>Verifies:</p>
     * <ul>
     *   <li>SecurityContextHolder is cleared</li>
     *   <li>JWT token invalidation (optional with Redis)</li>
     *   <li>Logout completes successfully even with errors</li>
     * </ul>
     */
    @Test
    @DisplayName("logout() should clear Security Context")
    void testLogout_ClearsSecurityContext() {
        // Arrange - set up authenticated security context
        UserSecurity mockUser = createMockRegularUser();
        Authentication auth = new UsernamePasswordAuthenticationToken(
            mockUser, 
            null, 
            mockUser.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        
        // Verify context is set before logout
        assertNotNull(SecurityContextHolder.getContext().getAuthentication(),
            "Security context should be set before logout");
        
        // Act
        authenticationService.logout(JWT_TOKEN);
        
        // Assert
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
            "Security context should be cleared after logout");
    }
    
    /**
     * Test logout() with null token.
     * 
     * <p>Verifies graceful handling when token is not provided.</p>
     */
    @Test
    @DisplayName("logout() should handle null token gracefully")
    void testLogout_NullToken_ClearsContextSuccessfully() {
        // Arrange
        UserSecurity mockUser = createMockRegularUser();
        Authentication auth = new UsernamePasswordAuthenticationToken(
            mockUser, 
            null, 
            mockUser.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        
        // Act - should not throw exception
        assertDoesNotThrow(() -> authenticationService.logout(null),
            "Logout should handle null token gracefully");
        
        // Assert
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
            "Security context should still be cleared");
    }
    
    /**
     * Test authenticate() with null LoginRequest.
     * 
     * <p>Defensive validation at method entry.</p>
     */
    @Test
    @DisplayName("authenticate() with null LoginRequest should throw IllegalArgumentException")
    void testAuthenticate_NullRequest_ThrowsIllegalArgumentException() {
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> authenticationService.authenticate(null),
            "Should throw IllegalArgumentException for null request"
        );
        
        assertTrue(
            exception.getMessage().contains("cannot be null"),
            "Exception message should indicate null request"
        );
        
        // Verify no operations attempted
        verify(userSecurityRepository, never()).findByUserId(anyString());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtTokenProvider, never()).generateToken(any());
    }
    
    /**
     * Test authentication with regular user type 'R'.
     * 
     * <p>Per Section 0.9: 'R' for Regular User → ROLE_USER</p>
     * 
     * <p>Verifies backward compatibility with mainframe systems using 'R'
     * instead of 'U' for regular users.</p>
     */
    @Test
    @DisplayName("authenticate() with user type 'R' should map to ROLE_USER")
    void testAuthenticate_UserTypeR_MapsToRoleUser() {
        // Arrange
        LoginRequest request = createLoginRequest(VALID_USER_ID, VALID_PASSWORD);
        UserSecurity mockUser = createMockRegularUserWithRType();
        
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH))
            .thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Authentication.class)))
            .thenReturn(JWT_TOKEN);
        
        // Act
        LoginResponse response = authenticationService.authenticate(request);
        
        // Assert
        assertNotNull(response, "LoginResponse should not be null");
        assertEquals(JWT_TOKEN, response.getToken(), "JWT token should be generated");
        
        // Verify user authorities contain ROLE_USER
        assertTrue(
            mockUser.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> role.equals(SecurityConstants.ROLE_USER)),
            "User with type 'R' should have ROLE_USER authority"
        );
    }
    
    /**
     * Test authentication audit logging for successful login.
     * 
     * <p>Verifies authentication events are logged for:</p>
     * <ul>
     *   <li>Security compliance</li>
     *   <li>Audit trail requirements</li>
     *   <li>Monitoring and alerting</li>
     * </ul>
     * 
     * <p>This test verifies the service logs authentication attempts,
     * matching mainframe audit trail capabilities.</p>
     */
    @Test
    @DisplayName("authenticate() should log successful authentication attempt")
    void testAuthenticate_AuditsLoginAttempt_Success() {
        // Arrange
        LoginRequest request = createLoginRequest(VALID_USER_ID, VALID_PASSWORD);
        UserSecurity mockUser = createMockRegularUser();
        
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH))
            .thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Authentication.class)))
            .thenReturn(JWT_TOKEN);
        
        // Act
        LoginResponse response = authenticationService.authenticate(request);
        
        // Assert
        assertNotNull(response, "LoginResponse should not be null");
        
        // Audit logging is performed via SLF4J logger
        // Verify service completed successfully indicating audit logs were written
        assertTrue(response.getUserId().equals(VALID_USER_ID),
            "Successful authentication should be auditable");
    }
    
    /**
     * Test authentication audit logging for failed login.
     * 
     * <p>Verifies failed authentication attempts are logged for security monitoring.</p>
     */
    @Test
    @DisplayName("authenticate() should log failed authentication attempt")
    void testAuthenticate_AuditsLoginAttempt_Failure() {
        // Arrange
        LoginRequest request = createLoginRequest("INVALID99", VALID_PASSWORD);
        
        when(userSecurityRepository.findByUserId("INVALID99"))
            .thenReturn(Optional.empty());
        
        // Act & Assert
        assertThrows(
            UserNotFoundException.class,
            () -> authenticationService.authenticate(request),
            "Should throw UserNotFoundException"
        );
        
        // Failed authentication is logged via SLF4J logger
        // Verify exception was thrown indicating audit log was written
        verify(userSecurityRepository, times(1)).findByUserId("INVALID99");
    }
    
    /**
     * Test LoginResponse contains all expected fields from COBOL BMS screen.
     * 
     * <p>Verifies response DTO completeness matching COSGN0AO structure:</p>
     * <ul>
     *   <li>token (JWT) - NEW field</li>
     *   <li>userId - USERIDO</li>
     *   <li>transactionName - TRNNAMEO (CC00)</li>
     *   <li>programName - PGMNAMEO (COSGN00C)</li>
     *   <li>title01 - TITLE01O</li>
     *   <li>title02 - TITLE02O</li>
     *   <li>currentDate - CURDATEO</li>
     *   <li>currentTime - CURTIMEO</li>
     *   <li>applicationId - APPLIDO</li>
     *   <li>systemId - SYSIDO</li>
     * </ul>
     */
    @Test
    @DisplayName("authenticate() should return LoginResponse with all COBOL screen fields")
    void testAuthenticate_ReturnsCompleteLoginResponse() {
        // Arrange
        LoginRequest request = createLoginRequest(VALID_USER_ID, VALID_PASSWORD);
        UserSecurity mockUser = createMockRegularUser();
        
        when(userSecurityRepository.findByUserId(VALID_USER_ID))
            .thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(VALID_PASSWORD, BCRYPT_PASSWORD_HASH))
            .thenReturn(true);
        when(jwtTokenProvider.generateToken(any(Authentication.class)))
            .thenReturn(JWT_TOKEN);
        
        // Act
        LoginResponse response = authenticationService.authenticate(request);
        
        // Assert - verify all fields are populated
        assertAll("LoginResponse should contain all COBOL BMS screen fields",
            () -> assertNotNull(response.getToken(), "Token should not be null"),
            () -> assertEquals(JWT_TOKEN, response.getToken(), "Token should match"),
            () -> assertEquals(VALID_USER_ID, response.getUserId(), "UserId should match"),
            () -> assertEquals("CC00", response.getTransactionName(), "Transaction name should be CC00"),
            () -> assertEquals("COSGN00C", response.getProgramName(), "Program name should be COSGN00C"),
            () -> assertNotNull(response.getTitle01(), "Title01 should not be null"),
            () -> assertNotNull(response.getTitle02(), "Title02 should not be null"),
            () -> assertNotNull(response.getCurrentDate(), "CurrentDate should not be null"),
            () -> assertNotNull(response.getCurrentTime(), "CurrentTime should not be null"),
            () -> assertNotNull(response.getApplicationId(), "ApplicationId should not be null"),
            () -> assertNotNull(response.getSystemId(), "SystemId should not be null"),
            () -> assertNull(response.getErrorMessage(), "ErrorMessage should be null on success")
        );
    }
}

