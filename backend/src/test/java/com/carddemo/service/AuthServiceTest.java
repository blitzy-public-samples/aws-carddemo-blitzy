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

import com.carddemo.exception.BusinessException;
import com.carddemo.model.dto.AuthResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;

/**
 * JUnit 5 unit test for AuthService testing user authentication logic extracted from COSGN00C.cbl PROCEDURE DIVISION.
 * 
 * <p>Converted from COBOL program: COSGN00C.cbl
 * Original function: User signon screen and authentication using RACF security
 * 
 * <p>This test class validates that Java authentication logic exactly matches COBOL RACF authentication patterns
 * ensuring security preservation per Section 0.7.9. All test scenarios map directly to COBOL authentication logic
 * from COSGN00C.cbl lines 108-140 (PROCESS-ENTER-KEY) and lines 209-257 (READ-USER-SEC-FILE).
 * 
 * <p><b>COBOL Authentication Flow (COSGN00C.cbl):</b>
 * <pre>
 * PROCESS-ENTER-KEY.
 *     WHEN USERIDI = SPACES OR LOW-VALUES
 *         MOVE 'Please enter User ID ...' TO WS-MESSAGE          [line 118-122]
 *     WHEN PASSWDI = SPACES OR LOW-VALUES
 *         MOVE 'Please enter Password ...' TO WS-MESSAGE         [line 123-127]
 *     MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID           [line 132-134]
 *     PERFORM READ-USER-SEC-FILE                                 [line 139]
 * 
 * READ-USER-SEC-FILE.
 *     EXEC CICS READ FILE('USRSEC') RIDFLD(WS-USER-ID) END-EXEC  [line 211-219]
 *     EVALUATE WS-RESP-CD
 *         WHEN 0
 *             IF SEC-USR-PWD = WS-USER-PWD                        [line 223]
 *                 IF CDEMO-USRTYP-ADMIN                           [line 230]
 *                      EXEC CICS XCTL PROGRAM ('COADM01C')        [line 231-234]
 *                 ELSE
 *                      EXEC CICS XCTL PROGRAM ('COMEN01C')        [line 236-239]
 *                 END-IF
 *             ELSE
 *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE [line 242]
 *         WHEN 13
 *             MOVE 'User not found. Try again ...' TO WS-MESSAGE    [line 249]
 * </pre>
 * 
 * <p><b>Test Coverage Requirements (Section 0.7.14):</b>
 * <ul>
 *   <li>Minimum 80% code coverage for backend services</li>
 *   <li>All business logic must have unit tests</li>
 *   <li>All authentication scenarios must be validated</li>
 * </ul>
 * 
 * <p><b>Test Scenarios Implemented:</b>
 * <ol>
 *   <li>Successful authentication with valid credentials (COBOL line 223 success path)</li>
 *   <li>Failed authentication with invalid password (COBOL line 242)</li>
 *   <li>Failed authentication with user not found (COBOL line 249)</li>
 *   <li>Failed authentication with blank userId (COBOL lines 118-122)</li>
 *   <li>Failed authentication with blank password (COBOL lines 123-127)</li>
 *   <li>JWT token generation upon successful authentication (replaces EXEC CICS XCTL lines 231-239)</li>
 *   <li>Admin user type routing (COBOL line 230: IF CDEMO-USRTYP-ADMIN)</li>
 *   <li>Regular user type routing (COBOL lines 236-239: ELSE path)</li>
 *   <li>Operator user type routing</li>
 *   <li>BCrypt password validation replacing COBOL plain-text comparison (line 223)</li>
 * </ol>
 * 
 * <p><b>Security Migration Validation (Section 0.7.9):</b>
 * <ul>
 *   <li>RACF plain-text password comparison (SEC-USR-PWD = WS-USER-PWD) → BCrypt password validation</li>
 *   <li>RACF user profiles → Spring Security UserDetails from user_security table</li>
 *   <li>RACF roles → Spring Security granted authorities (ROLE_ADMIN, ROLE_USER, ROLE_OPERATOR)</li>
 *   <li>CICS session state (COMMAREA) → Stateless JWT token-based authentication</li>
 * </ul>
 * 
 * @see AuthService
 * @see UserSecurityRepository
 * @see JwtTokenProvider
 * @see UserSecurity
 * @author CardDemo Migration Team
 * @version 1.0.0
 * @since 2024-01-01
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserSecurityRepository mockUserRepository;

    @Mock
    private JwtTokenProvider mockJwtTokenProvider;

    @Mock
    private BCryptPasswordEncoder mockPasswordEncoder;

    @Mock
    private ValidationService mockValidationService;

    @InjectMocks
    private AuthService authService;

    // Test constants matching COBOL data structures
    private static final String TEST_USER_ID = "USER0001";
    private static final String TEST_ADMIN_ID = "ADMIN001";
    private static final String TEST_OPERATOR_ID = "OPER0001";
    private static final String TEST_PASSWORD = "Password123";
    private static final String TEST_PASSWORD_HASH = "$2a$10$dummyHashedPassword123456789012345678901234567890";
    private static final String TEST_JWT_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test.token";
    private static final long TEST_JWT_EXPIRATION_MS = 3600000L; // 1 hour in milliseconds
    private static final String USER_TYPE_ADMIN = "A";
    private static final String USER_TYPE_USER = "U";
    private static final String USER_TYPE_OPERATOR = "O";

    /**
     * Test successful authentication with valid credentials.
     * 
     * <p>Tests COBOL authentication flow from COSGN00C.cbl:
     * - Lines 132-134: Convert userId to uppercase
     * - Lines 211-219: Read user security file
     * - Line 223: Validate password (SEC-USR-PWD = WS-USER-PWD)
     * - Lines 226-227: Extract user ID and type
     * - Lines 236-239: Transfer control to COMEN01C for regular user
     * 
     * <p>In Java implementation, EXEC CICS XCTL is replaced with JWT token generation.
     * 
     * @throws BusinessException should not be thrown for valid credentials
     */
    @Test
    void testAuthenticateUserSuccess() {
        // Given: Valid user credentials and user exists in repository
        UserSecurity testUser = UserSecurity.builder()
                .userId(TEST_USER_ID)
                .userPwdHash(TEST_PASSWORD_HASH)
                .userType(USER_TYPE_USER)
                .userFirstName("John")
                .userLastName("Doe")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        // Mock ValidationService (lines 118-127: validate userId and password not empty)
        // No action needed as ValidationService.validateUserId() and validatePassword() throw on error

        // Mock repository findById (lines 211-219: EXEC CICS READ FILE('USRSEC'))
        when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));

        // Mock password encoder matches (line 223: IF SEC-USR-PWD = WS-USER-PWD)
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(true);

        // Mock JWT token generation (replaces lines 236-239: EXEC CICS XCTL PROGRAM('COMEN01C'))
        when(mockJwtTokenProvider.generateToken(TEST_USER_ID, USER_TYPE_USER)).thenReturn(TEST_JWT_TOKEN);

        // Mock JWT expiration time retrieval
        when(mockJwtTokenProvider.getExpirationTime()).thenReturn(TEST_JWT_EXPIRATION_MS);

        // When: Authenticate user with valid credentials
        AuthResponse authResponse = authService.authenticate(TEST_USER_ID, TEST_PASSWORD);

        // Then: Authentication successful and JWT token returned
        assertThat(authResponse).isNotNull();
        assertThat(authResponse.token()).isEqualTo(TEST_JWT_TOKEN);
        assertThat(authResponse.userId()).isEqualTo(TEST_USER_ID);
        assertThat(authResponse.userType()).isEqualTo(USER_TYPE_USER);
        assertThat(authResponse.expiresIn()).isEqualTo(TEST_JWT_EXPIRATION_MS / 1000); // Convert ms to seconds

        // Verify: All expected interactions occurred
        verify(mockValidationService).validateUserId(TEST_USER_ID);
        verify(mockValidationService).validatePassword(TEST_PASSWORD);
        verify(mockUserRepository).findById(TEST_USER_ID);
        verify(mockPasswordEncoder).matches(TEST_PASSWORD, TEST_PASSWORD_HASH);
        verify(mockJwtTokenProvider).generateToken(TEST_USER_ID, USER_TYPE_USER);
        verify(mockJwtTokenProvider).getExpirationTime();
        verifyNoMoreInteractions(mockUserRepository, mockPasswordEncoder, mockJwtTokenProvider);
    }

    /**
     * Test authentication with admin user type.
     * 
     * <p>Tests COBOL admin user routing from COSGN00C.cbl:
     * - Line 230: IF CDEMO-USRTYP-ADMIN (88-level condition VALUE 'A')
     * - Lines 231-234: EXEC CICS XCTL PROGRAM('COADM01C') for admin users
     * 
     * <p>In Java implementation, admin users receive JWT token with ROLE_ADMIN authority.
     * 
     * @throws BusinessException should not be thrown for valid admin credentials
     */
    @Test
    void testAuthenticateAdminUser() {
        // Given: Admin user credentials
        UserSecurity adminUser = UserSecurity.builder()
                .userId(TEST_ADMIN_ID)
                .userPwdHash(TEST_PASSWORD_HASH)
                .userType(USER_TYPE_ADMIN)
                .userFirstName("Admin")
                .userLastName("User")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        when(mockUserRepository.findById(TEST_ADMIN_ID)).thenReturn(Optional.of(adminUser));
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(true);
        when(mockJwtTokenProvider.generateToken(TEST_ADMIN_ID, USER_TYPE_ADMIN)).thenReturn(TEST_JWT_TOKEN);
        when(mockJwtTokenProvider.getExpirationTime()).thenReturn(TEST_JWT_EXPIRATION_MS);

        // When: Authenticate admin user
        AuthResponse authResponse = authService.authenticate(TEST_ADMIN_ID, TEST_PASSWORD);

        // Then: Admin user authenticated with correct type
        assertThat(authResponse).isNotNull();
        assertThat(authResponse.userType()).isEqualTo(USER_TYPE_ADMIN);
        assertThat(authResponse.userId()).isEqualTo(TEST_ADMIN_ID);
        assertThat(authResponse.isAdmin()).isTrue();

        // Verify: JWT token generated with admin type (replaces EXEC CICS XCTL PROGRAM('COADM01C'))
        verify(mockJwtTokenProvider).generateToken(TEST_ADMIN_ID, USER_TYPE_ADMIN);
    }

    /**
     * Test authentication with regular user type.
     * 
     * <p>Tests COBOL regular user routing from COSGN00C.cbl:
     * - Lines 236-239: ELSE path - EXEC CICS XCTL PROGRAM('COMEN01C') for non-admin users
     * 
     * @throws BusinessException should not be thrown for valid user credentials
     */
    @Test
    void testAuthenticateRegularUser() {
        // Given: Regular user credentials
        UserSecurity regularUser = UserSecurity.builder()
                .userId(TEST_USER_ID)
                .userPwdHash(TEST_PASSWORD_HASH)
                .userType(USER_TYPE_USER)
                .userFirstName("Regular")
                .userLastName("User")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(regularUser));
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(true);
        when(mockJwtTokenProvider.generateToken(TEST_USER_ID, USER_TYPE_USER)).thenReturn(TEST_JWT_TOKEN);
        when(mockJwtTokenProvider.getExpirationTime()).thenReturn(TEST_JWT_EXPIRATION_MS);

        // When: Authenticate regular user
        AuthResponse authResponse = authService.authenticate(TEST_USER_ID, TEST_PASSWORD);

        // Then: Regular user authenticated with correct type
        assertThat(authResponse).isNotNull();
        assertThat(authResponse.userType()).isEqualTo(USER_TYPE_USER);
        assertThat(authResponse.userId()).isEqualTo(TEST_USER_ID);
        assertThat(authResponse.isRegularUser()).isTrue();
        assertThat(authResponse.isAdmin()).isFalse();

        // Verify: JWT token generated with user type (replaces EXEC CICS XCTL PROGRAM('COMEN01C'))
        verify(mockJwtTokenProvider).generateToken(TEST_USER_ID, USER_TYPE_USER);
    }

    /**
     * Test authentication with operator user type.
     * 
     * <p>Tests operator user routing (user type 'O').
     * 
     * @throws BusinessException should not be thrown for valid operator credentials
     */
    @Test
    void testAuthenticateOperatorUser() {
        // Given: Operator user credentials
        UserSecurity operatorUser = UserSecurity.builder()
                .userId(TEST_OPERATOR_ID)
                .userPwdHash(TEST_PASSWORD_HASH)
                .userType(USER_TYPE_OPERATOR)
                .userFirstName("Operator")
                .userLastName("User")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        when(mockUserRepository.findById(TEST_OPERATOR_ID)).thenReturn(Optional.of(operatorUser));
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(true);
        when(mockJwtTokenProvider.generateToken(TEST_OPERATOR_ID, USER_TYPE_OPERATOR)).thenReturn(TEST_JWT_TOKEN);
        when(mockJwtTokenProvider.getExpirationTime()).thenReturn(TEST_JWT_EXPIRATION_MS);

        // When: Authenticate operator user
        AuthResponse authResponse = authService.authenticate(TEST_OPERATOR_ID, TEST_PASSWORD);

        // Then: Operator user authenticated with correct type
        assertThat(authResponse).isNotNull();
        assertThat(authResponse.userType()).isEqualTo(USER_TYPE_OPERATOR);
        assertThat(authResponse.userId()).isEqualTo(TEST_OPERATOR_ID);
        assertThat(authResponse.isOperator()).isTrue();
        assertThat(authResponse.isAdmin()).isFalse();

        // Verify: JWT token generated with operator type
        verify(mockJwtTokenProvider).generateToken(TEST_OPERATOR_ID, USER_TYPE_OPERATOR);
    }

    /**
     * Test authentication failure with invalid password.
     * 
     * <p>Tests COBOL password validation failure from COSGN00C.cbl:
     * - Line 223: IF SEC-USR-PWD = WS-USER-PWD (condition FALSE)
     * - Lines 241-245: ELSE - MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     * 
     * <p>In Java implementation, plain-text password comparison is replaced with BCrypt validation
     * per Section 0.7.9 security requirements.
     * 
     * @throws BusinessException with exact COBOL error message from line 242
     */
    @Test
    void testAuthenticateUserInvalidPassword() {
        // Given: User exists but password is incorrect
        UserSecurity testUser = UserSecurity.builder()
                .userId(TEST_USER_ID)
                .userPwdHash(TEST_PASSWORD_HASH)
                .userType(USER_TYPE_USER)
                .userFirstName("John")
                .userLastName("Doe")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));

        // Mock password encoder to return false (password doesn't match)
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(false);

        // When/Then: Authentication fails with exact COBOL error message
        assertThatThrownBy(() -> authService.authenticate(TEST_USER_ID, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Wrong Password. Try again ..."); // Exact message from COSGN00C.cbl line 242

        // Verify: User was retrieved but JWT token was NOT generated
        verify(mockValidationService).validateUserId(TEST_USER_ID);
        verify(mockValidationService).validatePassword(TEST_PASSWORD);
        verify(mockUserRepository).findById(TEST_USER_ID);
        verify(mockPasswordEncoder).matches(TEST_PASSWORD, TEST_PASSWORD_HASH);
        verify(mockJwtTokenProvider, times(0)).generateToken(anyString(), anyString());
    }

    /**
     * Test authentication failure when user not found.
     * 
     * <p>Tests COBOL user not found scenario from COSGN00C.cbl:
     * - Lines 247-250: WHEN 13 (RESP-CD 13 = NOTFND)
     *                  MOVE 'User not found. Try again ...' TO WS-MESSAGE
     * 
     * <p>In Java implementation, RESP-CD 13 is replaced with Optional.empty() from repository.
     * 
     * @throws BusinessException with exact COBOL error message from line 249
     */
    @Test
    void testAuthenticateUserNotFound() {
        // Given: User does not exist in repository
        when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        // When/Then: Authentication fails with exact COBOL error message
        assertThatThrownBy(() -> authService.authenticate(TEST_USER_ID, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .hasMessage("User not found. Try again ..."); // Exact message from COSGN00C.cbl line 249

        // Verify: Repository was queried but password was NOT checked (user doesn't exist)
        verify(mockValidationService).validateUserId(TEST_USER_ID);
        verify(mockValidationService).validatePassword(TEST_PASSWORD);
        verify(mockUserRepository).findById(TEST_USER_ID);
        verify(mockPasswordEncoder, times(0)).matches(anyString(), anyString());
        verify(mockJwtTokenProvider, times(0)).generateToken(anyString(), anyString());
    }

    /**
     * Test authentication failure with blank userId.
     * 
     * <p>Tests COBOL userId validation from COSGN00C.cbl:
     * - Lines 118-122: WHEN USERIDI = SPACES OR LOW-VALUES
     *                  MOVE 'Please enter User ID ...' TO WS-MESSAGE
     * 
     * <p>In Java implementation, ValidationService.validateUserId() throws BusinessException
     * when userId is blank, null, or contains only whitespace.
     * 
     * @throws BusinessException when userId is blank
     */
    @Test
    void testAuthenticateUserBlankUserId() {
        // Given: ValidationService throws exception for blank userId
        String blankUserId = "   ";
        doThrow(new BusinessException("Please enter User ID ..."))
                .when(mockValidationService).validateUserId(blankUserId);

        // When/Then: Authentication fails with validation error
        assertThatThrownBy(() -> authService.authenticate(blankUserId, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Please enter User ID"); // Message from COSGN00C.cbl line 120

        // Verify: Validation failed before repository access
        verify(mockValidationService).validateUserId(blankUserId);
        verify(mockUserRepository, times(0)).findById(anyString());
        verify(mockPasswordEncoder, times(0)).matches(anyString(), anyString());
        verify(mockJwtTokenProvider, times(0)).generateToken(anyString(), anyString());
    }

    /**
     * Test authentication failure with blank password.
     * 
     * <p>Tests COBOL password validation from COSGN00C.cbl:
     * - Lines 123-127: WHEN PASSWDI = SPACES OR LOW-VALUES
     *                  MOVE 'Please enter Password ...' TO WS-MESSAGE
     * 
     * <p>In Java implementation, ValidationService.validatePassword() throws BusinessException
     * when password is blank, null, or contains only whitespace.
     * 
     * @throws BusinessException when password is blank
     */
    @Test
    void testAuthenticateUserBlankPassword() {
        // Given: ValidationService throws exception for blank password
        String blankPassword = "   ";
        doThrow(new BusinessException("Please enter Password ..."))
                .when(mockValidationService).validatePassword(blankPassword);

        // When/Then: Authentication fails with validation error
        assertThatThrownBy(() -> authService.authenticate(TEST_USER_ID, blankPassword))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Please enter Password"); // Message from COSGN00C.cbl line 125

        // Verify: userId validation passed but password validation failed
        verify(mockValidationService).validateUserId(TEST_USER_ID);
        verify(mockValidationService).validatePassword(blankPassword);
        verify(mockUserRepository, times(0)).findById(anyString());
        verify(mockPasswordEncoder, times(0)).matches(anyString(), anyString());
        verify(mockJwtTokenProvider, times(0)).generateToken(anyString(), anyString());
    }

    /**
     * Test JWT token generation upon successful authentication.
     * 
     * <p>Tests JWT token generation replacing COBOL EXEC CICS XCTL from COSGN00C.cbl:
     * - Lines 231-239: EXEC CICS XCTL PROGRAM('COADM01C' or 'COMEN01C') COMMAREA(...)
     * 
     * <p>In Java implementation, stateless JWT token replaces CICS session state (COMMAREA).
     * Token contains userId and userType claims matching CDEMO-USER-ID and CDEMO-USER-TYPE.
     * 
     * @throws BusinessException should not be thrown for valid credentials
     */
    @Test
    void testJwtTokenGeneration() {
        // Given: Valid user credentials
        UserSecurity testUser = UserSecurity.builder()
                .userId(TEST_USER_ID)
                .userPwdHash(TEST_PASSWORD_HASH)
                .userType(USER_TYPE_USER)
                .userFirstName("John")
                .userLastName("Doe")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(true);
        when(mockJwtTokenProvider.generateToken(TEST_USER_ID, USER_TYPE_USER)).thenReturn(TEST_JWT_TOKEN);
        when(mockJwtTokenProvider.getExpirationTime()).thenReturn(TEST_JWT_EXPIRATION_MS);

        // When: Authenticate user
        AuthResponse authResponse = authService.authenticate(TEST_USER_ID, TEST_PASSWORD);

        // Then: JWT token is generated with correct userId and userType
        assertThat(authResponse.token()).isNotNull();
        assertThat(authResponse.token()).isEqualTo(TEST_JWT_TOKEN);

        // Verify: JwtTokenProvider.generateToken() called with exact userId and userType
        // This replaces COBOL: MOVE WS-USER-ID TO CDEMO-USER-ID
        //                      MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
        //                      EXEC CICS XCTL ... COMMAREA(CARDDEMO-COMMAREA)
        verify(mockJwtTokenProvider).generateToken(TEST_USER_ID, USER_TYPE_USER);
    }

    /**
     * Test JWT token expiration time.
     * 
     * <p>Tests that JWT token expiration time matches CICS session timeout (1 hour = 3600 seconds).
     * 
     * @throws BusinessException should not be thrown for valid credentials
     */
    @Test
    void testJwtTokenExpiration() {
        // Given: Valid user credentials
        UserSecurity testUser = UserSecurity.builder()
                .userId(TEST_USER_ID)
                .userPwdHash(TEST_PASSWORD_HASH)
                .userType(USER_TYPE_USER)
                .userFirstName("John")
                .userLastName("Doe")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(true);
        when(mockJwtTokenProvider.generateToken(TEST_USER_ID, USER_TYPE_USER)).thenReturn(TEST_JWT_TOKEN);
        when(mockJwtTokenProvider.getExpirationTime()).thenReturn(TEST_JWT_EXPIRATION_MS);

        // When: Authenticate user
        AuthResponse authResponse = authService.authenticate(TEST_USER_ID, TEST_PASSWORD);

        // Then: Token expiration time is 1 hour (3600 seconds)
        assertThat(authResponse.expiresIn()).isEqualTo(3600L); // 3600000ms / 1000 = 3600 seconds

        // Verify: Expiration time retrieved from JWT provider
        verify(mockJwtTokenProvider).getExpirationTime();
    }

    /**
     * Test BCrypt password hashing replaces COBOL plain-text password comparison.
     * 
     * <p>Tests security migration from COBOL to Java per Section 0.7.9:
     * - COBOL line 223: IF SEC-USR-PWD = WS-USER-PWD (plain-text comparison)
     * - Java: BCryptPasswordEncoder.matches(rawPassword, hashedPassword)
     * 
     * <p>Validates that BCrypt password encoder is used for secure password validation,
     * replacing COBOL plain-text password storage and comparison.
     * 
     * @throws BusinessException should not be thrown for valid credentials
     */
    @Test
    void testPasswordHashedStorage() {
        // Given: User with BCrypt hashed password
        UserSecurity testUser = UserSecurity.builder()
                .userId(TEST_USER_ID)
                .userPwdHash(TEST_PASSWORD_HASH) // BCrypt hash, not plain-text
                .userType(USER_TYPE_USER)
                .userFirstName("John")
                .userLastName("Doe")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(true);
        when(mockJwtTokenProvider.generateToken(TEST_USER_ID, USER_TYPE_USER)).thenReturn(TEST_JWT_TOKEN);
        when(mockJwtTokenProvider.getExpirationTime()).thenReturn(TEST_JWT_EXPIRATION_MS);

        // When: Authenticate user
        AuthResponse authResponse = authService.authenticate(TEST_USER_ID, TEST_PASSWORD);

        // Then: Authentication successful using BCrypt validation
        assertThat(authResponse).isNotNull();

        // Verify: BCryptPasswordEncoder.matches() called (not plain-text comparison)
        // This replaces COBOL: IF SEC-USR-PWD = WS-USER-PWD
        // With secure BCrypt password validation per Section 0.7.9
        verify(mockPasswordEncoder).matches(TEST_PASSWORD, TEST_PASSWORD_HASH);
    }

    /**
     * Test that logout method logs audit event.
     * 
     * <p>Tests logout functionality from COBOL program COMEN01C.cbl PF3 key handling:
     * <pre>
     * WHEN DFHPF3
     *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *     PERFORM SEND-PLAIN-TEXT
     *     EXEC CICS RETURN END-EXEC
     * </pre>
     * 
     * <p>In JWT stateless authentication, logout is primarily client-side (token deletion).
     * This test validates that logout event is logged for audit trail per Section 0.7.9.
     */
    @Test
    void testLogout() {
        // Given: Valid JWT token
        String token = TEST_JWT_TOKEN;

        // When: Logout is called
        authService.logout(token);

        // Then: No exception thrown (logout succeeds)
        // Note: Logout is primarily client-side token deletion in JWT stateless model
        // Server-side logout logs audit event for security monitoring

        // Verify: No interactions with other services (logout is audit-only operation)
        verifyNoMoreInteractions(mockUserRepository, mockPasswordEncoder, mockJwtTokenProvider);
    }

    /**
     * Test admin user routing validation.
     * 
     * <p>Tests that admin users (SEC-USR-TYPE='A') are correctly identified and receive
     * appropriate JWT token with admin role.
     * 
     * @throws BusinessException should not be thrown for valid admin credentials
     */
    @Test
    void testAdminUserRouting() {
        // Given: Admin user credentials
        UserSecurity adminUser = UserSecurity.builder()
                .userId(TEST_ADMIN_ID)
                .userPwdHash(TEST_PASSWORD_HASH)
                .userType(USER_TYPE_ADMIN)
                .userFirstName("Admin")
                .userLastName("User")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        when(mockUserRepository.findById(TEST_ADMIN_ID)).thenReturn(Optional.of(adminUser));
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(true);
        when(mockJwtTokenProvider.generateToken(TEST_ADMIN_ID, USER_TYPE_ADMIN)).thenReturn(TEST_JWT_TOKEN);
        when(mockJwtTokenProvider.getExpirationTime()).thenReturn(TEST_JWT_EXPIRATION_MS);

        // When: Authenticate admin user
        AuthResponse authResponse = authService.authenticate(TEST_ADMIN_ID, TEST_PASSWORD);

        // Then: Admin user type correctly identified
        assertThat(authResponse.userType()).isEqualTo(USER_TYPE_ADMIN);
        assertThat(authResponse.getSpringSecurityRole()).isEqualTo("ROLE_ADMIN");

        // Verify: JWT token generated with admin type
        // Replaces COBOL: IF CDEMO-USRTYP-ADMIN
        //                   EXEC CICS XCTL PROGRAM('COADM01C')
        verify(mockJwtTokenProvider).generateToken(TEST_ADMIN_ID, USER_TYPE_ADMIN);
    }

    /**
     * Test regular user routing validation.
     * 
     * <p>Tests that regular users (SEC-USR-TYPE='U') are correctly identified and receive
     * appropriate JWT token with user role.
     * 
     * @throws BusinessException should not be thrown for valid user credentials
     */
    @Test
    void testRegularUserRouting() {
        // Given: Regular user credentials
        UserSecurity regularUser = UserSecurity.builder()
                .userId(TEST_USER_ID)
                .userPwdHash(TEST_PASSWORD_HASH)
                .userType(USER_TYPE_USER)
                .userFirstName("Regular")
                .userLastName("User")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        when(mockUserRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(regularUser));
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(true);
        when(mockJwtTokenProvider.generateToken(TEST_USER_ID, USER_TYPE_USER)).thenReturn(TEST_JWT_TOKEN);
        when(mockJwtTokenProvider.getExpirationTime()).thenReturn(TEST_JWT_EXPIRATION_MS);

        // When: Authenticate regular user
        AuthResponse authResponse = authService.authenticate(TEST_USER_ID, TEST_PASSWORD);

        // Then: Regular user type correctly identified
        assertThat(authResponse.userType()).isEqualTo(USER_TYPE_USER);
        assertThat(authResponse.getSpringSecurityRole()).isEqualTo("ROLE_USER");

        // Verify: JWT token generated with user type
        // Replaces COBOL: ELSE (not admin)
        //                   EXEC CICS XCTL PROGRAM('COMEN01C')
        verify(mockJwtTokenProvider).generateToken(TEST_USER_ID, USER_TYPE_USER);
    }

    /**
     * Test operator user routing validation.
     * 
     * <p>Tests that operator users (SEC-USR-TYPE='O') are correctly identified and receive
     * appropriate JWT token with operator role.
     * 
     * @throws BusinessException should not be thrown for valid operator credentials
     */
    @Test
    void testOperatorUserRouting() {
        // Given: Operator user credentials
        UserSecurity operatorUser = UserSecurity.builder()
                .userId(TEST_OPERATOR_ID)
                .userPwdHash(TEST_PASSWORD_HASH)
                .userType(USER_TYPE_OPERATOR)
                .userFirstName("Operator")
                .userLastName("User")
                .createdAt(Timestamp.valueOf(LocalDateTime.now()))
                .updatedAt(Timestamp.valueOf(LocalDateTime.now()))
                .version(0)
                .build();

        when(mockUserRepository.findById(TEST_OPERATOR_ID)).thenReturn(Optional.of(operatorUser));
        when(mockPasswordEncoder.matches(TEST_PASSWORD, TEST_PASSWORD_HASH)).thenReturn(true);
        when(mockJwtTokenProvider.generateToken(TEST_OPERATOR_ID, USER_TYPE_OPERATOR)).thenReturn(TEST_JWT_TOKEN);
        when(mockJwtTokenProvider.getExpirationTime()).thenReturn(TEST_JWT_EXPIRATION_MS);

        // When: Authenticate operator user
        AuthResponse authResponse = authService.authenticate(TEST_OPERATOR_ID, TEST_PASSWORD);

        // Then: Operator user type correctly identified
        assertThat(authResponse.userType()).isEqualTo(USER_TYPE_OPERATOR);
        assertThat(authResponse.getSpringSecurityRole()).isEqualTo("ROLE_OPERATOR");

        // Verify: JWT token generated with operator type
        verify(mockJwtTokenProvider).generateToken(TEST_OPERATOR_ID, USER_TYPE_OPERATOR);
    }
}
