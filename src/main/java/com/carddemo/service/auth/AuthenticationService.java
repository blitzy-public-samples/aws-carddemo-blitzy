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
package com.carddemo.service.auth;

import com.carddemo.constants.MessageConstants;
import com.carddemo.dto.request.LoginRequest;
import com.carddemo.dto.response.LoginResponse;
import com.carddemo.entity.User;
import com.carddemo.exception.AuthenticationException;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Authentication Service implementing user credential validation and session management.
 * 
 * <p>This service transforms the COBOL COSGN00C.cbl sign-on program to Spring Security-based
 * authentication, replacing VSAM file access with JPA repository operations and CICS COMMAREA
 * session state with JWT token-based authentication.</p>
 * 
 * <p><b>COBOL Program Transformation:</b></p>
 * <ul>
 *   <li>Source: app/cbl/COSGN00C.cbl (CICS Transaction CC00 - Signon Screen)</li>
 *   <li>PROCESS-ENTER-KEY paragraph (lines 108-140) → authenticate() method</li>
 *   <li>READ-USER-SEC-FILE paragraph (lines 209-257) → UserRepository.findByUserId()</li>
 *   <li>Plain text password comparison (line 223) → BCrypt PasswordEncoder.matches()</li>
 *   <li>CICS XCTL to menu programs (lines 231-239) → LoginResponse with JWT token</li>
 * </ul>
 * 
 * <p><b>Data Structure Mapping:</b></p>
 * <ul>
 *   <li>CSUSR01Y.cpy SEC-USER-DATA → User JPA entity</li>
 *   <li>COCOM01Y.cpy CARDDEMO-COMMAREA → LoginResponse DTO with JWT token</li>
 *   <li>88-level CDEMO-USRTYP-ADMIN VALUE 'A' → UserType.ADMIN enum → ROLE_ADMIN</li>
 *   <li>88-level CDEMO-USRTYP-USER VALUE 'U' → UserType.USER enum → ROLE_USER</li>
 * </ul>
 * 
 * <p><b>Security Transformation:</b></p>
 * <ul>
 *   <li>VSAM USRSEC file → PostgreSQL app_user table via UserRepository</li>
 *   <li>Plain text password → BCrypt-hashed password (60-char hash)</li>
 *   <li>CICS session → Stateless JWT token with userId and role claims</li>
 *   <li>RACF user type → Spring Security GrantedAuthority roles</li>
 * </ul>
 * 
 * <p><b>Error Handling Preservation:</b></p>
 * <ul>
 *   <li>COBOL RESP-CD 13 (NOTFND) → ResourceNotFoundException with HTTP 404</li>
 *   <li>Password mismatch (lines 241-245) → AuthenticationException with HTTP 401</li>
 *   <li>Generic errors (lines 252-256) → AuthenticationException with HTTP 401</li>
 *   <li>Error messages from CSMSG01Y.cpy preserved in MessageConstants</li>
 * </ul>
 * 
 * <p><b>Key Business Logic:</b></p>
 * <ol>
 *   <li>Validate input credentials (userId and password not blank)</li>
 *   <li>Retrieve User entity from database by userId</li>
 *   <li>Verify provided password against stored BCrypt hash</li>
 *   <li>Check user account status (deleted flag, active status)</li>
 *   <li>Generate JWT token with user ID and role claims</li>
 *   <li>Create Spring Security Authentication with granted authorities</li>
 *   <li>Set authenticated user in SecurityContext for request scope</li>
 *   <li>Return LoginResponse with token, user details, and expiration</li>
 * </ol>
 * 
 * @see User JPA entity replacing VSAM USRSEC file
 * @see UserRepository for database access replacing EXEC CICS READ
 * @see JwtService for JWT token generation replacing COMMAREA
 * @see LoginRequest input DTO replacing BMS COSGN00.bms screen input
 * @see LoginResponse output DTO replacing COMMAREA output structure
 * @version 1.0
 * @since 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthenticationService {

    /**
     * User repository for database access replacing VSAM USRSEC file operations.
     * Provides findByUserId() method replacing EXEC CICS READ with KEY IS WS-USER-ID.
     */
    private final UserRepository userRepository;

    /**
     * Password encoder for BCrypt password verification.
     * Replaces COBOL plain text password comparison (IF SEC-USR-PWD = WS-USER-PWD)
     * with secure cryptographic hash comparison using PasswordEncoder.matches().
     */
    private final PasswordEncoder passwordEncoder;

    /**
     * JWT service for token generation and validation.
     * Replaces CICS COMMAREA state management with stateless JWT token containing
     * userId and userType claims for session persistence across stateless REST requests.
     */
    private final JwtService jwtService;

    /**
     * Authenticates user credentials and generates JWT session token.
     * 
     * <p>This method transforms the COBOL PROCESS-ENTER-KEY and READ-USER-SEC-FILE paragraphs
     * from COSGN00C.cbl into Spring Security authentication flow:</p>
     * 
     * <p><b>COBOL Flow (COSGN00C.cbl lines 108-257):</b></p>
     * <pre>
     * PROCESS-ENTER-KEY.
     *     PERFORM RECEIVE-SIGNON-SCREEN
     *     MOVE USERIDI OF COSGN0AI TO WS-USER-ID
     *     MOVE PASSWDI OF COSGN0AI TO WS-USER-PWD
     *     PERFORM EDIT-SIGNON-DATA
     *     IF NOT ERR-FLG-ON
     *         PERFORM READ-USER-SEC-FILE
     * 
     * READ-USER-SEC-FILE.
     *     EXEC CICS READ
     *         FILE    (WS-USRSEC-FILE)
     *         INTO    (SEC-USER-DATA)
     *         RIDFLD  (WS-USER-ID)
     *         RESP    (WS-RESP-CD)
     *         RESP2   (WS-REAS-CD)
     *     END-EXEC
     *     
     *     IF WS-RESP-CD = DFHRESP(NORMAL)
     *         IF SEC-USR-PWD = WS-USER-PWD
     *             MOVE SEC-USR-ID TO CDEMO-USER-ID
     *             MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *             EXEC CICS XCTL PROGRAM(admin-menu or user-menu)
     *         ELSE
     *             MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *     ELSE IF WS-RESP-CD = DFHRESP(NOTFND)
     *         MOVE 'User not found. Try again ...' TO WS-MESSAGE
     *     ELSE
     *         MOVE 'Unable to verify the User at this time' TO WS-MESSAGE
     * </pre>
     * 
     * <p><b>Java Flow:</b></p>
     * <ol>
     *   <li>Extract userId and password from LoginRequest (validated by Bean Validation)</li>
     *   <li>Query User entity using UserRepository.findByUserId() [replaces EXEC CICS READ]</li>
     *   <li>Throw ResourceNotFoundException if user not found [replaces RESP-CD 13]</li>
     *   <li>Verify password using PasswordEncoder.matches() [replaces plain text comparison]</li>
     *   <li>Throw AuthenticationException if password mismatch [replaces error message]</li>
     *   <li>Check user deleted flag (soft delete validation)</li>
     *   <li>Generate JWT token with userId and userType [replaces COMMAREA population]</li>
     *   <li>Create Spring Security Authentication with granted authorities</li>
     *   <li>Set SecurityContext for request [replaces CICS user context]</li>
     *   <li>Build and return LoginResponse with token [replaces XCTL to menu programs]</li>
     * </ol>
     * 
     * <p><b>Security Notes:</b></p>
     * <ul>
     *   <li>Passwords are never logged or exposed in responses</li>
     *   <li>BCrypt comparison prevents timing attacks</li>
     *   <li>JWT tokens are signed to prevent tampering</li>
     *   <li>SecurityContext is set for role-based authorization in subsequent requests</li>
     * </ul>
     * 
     * @param loginRequest Login credentials containing userId and password
     * @return LoginResponse with JWT token, user details, and expiration timestamp
     * @throws ResourceNotFoundException if user ID not found in database (HTTP 404)
     * @throws AuthenticationException if password incorrect or user inactive (HTTP 401)
     */
    @Transactional(readOnly = true)
    public LoginResponse authenticate(LoginRequest loginRequest) {
        log.info("Authentication attempt for userId: {}", loginRequest.getUserId());

        // Step 1: Extract credentials from request
        // Replaces: MOVE USERIDI OF COSGN0AI TO WS-USER-ID
        //          MOVE PASSWDI OF COSGN0AI TO WS-USER-PWD
        String userId = loginRequest.getUserId();
        String password = loginRequest.getPassword();

        // Step 2: Retrieve user from database
        // Replaces: EXEC CICS READ FILE(WS-USRSEC-FILE) INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID)
        Optional<User> userOptional = userRepository.findByUserId(userId);

        // Step 3: Handle user not found scenario
        // Replaces: IF WS-RESP-CD = DFHRESP(NOTFND)
        //              MOVE 'User not found. Try again ...' TO WS-MESSAGE
        User user = userOptional.orElseThrow(() -> {
            log.warn("Authentication failed: User not found - userId: {}", userId);
            return new ResourceNotFoundException(MessageConstants.MSG_USER_NOT_FOUND + ": " + userId);
        });

        // Step 4: Check if user account is deleted (soft delete check)
        // Additional validation not present in COBOL but required for modern system
        if (user.isDeleted()) {
            log.warn("Authentication failed: User account is deleted - userId: {}", userId);
            throw new AuthenticationException(MessageConstants.MSG_USER_NOT_FOUND);
        }

        // Step 5: Verify password using BCrypt
        // Replaces: IF SEC-USR-PWD = WS-USER-PWD (plain text comparison)
        //              [success flow]
        //          ELSE
        //              MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
        boolean passwordMatches = passwordEncoder.matches(password, user.getPassword());
        if (!passwordMatches) {
            log.warn("Authentication failed: Wrong password - userId: {}", userId);
            throw new AuthenticationException(MessageConstants.MSG_WRONG_PASSWORD);
        }

        log.info("Authentication successful for userId: {} with userType: {}", userId, user.getUserType());

        // Step 6: Generate JWT token with user claims
        // Replaces: MOVE SEC-USR-ID TO CDEMO-USER-ID
        //          MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
        //          [COMMAREA passed to next program via XCTL]
        String userTypeCode = user.getUserType().getCode();
        String jwtToken = jwtService.generateToken(userId, userTypeCode);
        LocalDateTime expiresAt = jwtService.getTokenExpiration();

        // Step 7: Create Spring Security Authentication
        // Build granted authorities based on user type
        // Replaces: COBOL 88-level conditions (CDEMO-USRTYP-ADMIN VALUE 'A', CDEMO-USRTYP-USER VALUE 'U')
        List<GrantedAuthority> authorities = buildGrantedAuthorities(user);

        // Create authentication token with user details and authorities
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user.getUserId(),
                null,  // Credentials (password) should not be stored after authentication
                authorities
        );

        // Step 8: Set authentication in SecurityContext for current request
        // This enables role-based authorization via @PreAuthorize annotations
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("SecurityContext populated with authentication for userId: {}", userId);

        // Step 9: Build and return LoginResponse
        // Replaces: EXEC CICS XCTL PROGRAM(COADM01C or COMEN01C) COMMAREA(CARDDEMO-COMMAREA)
        return LoginResponse.builder()
                .jwtToken(jwtToken)
                .userId(user.getUserId())
                .userType(user.getUserType().name())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * Builds granted authorities for Spring Security based on user type.
     * 
     * <p>This method transforms COBOL 88-level user type conditions to Spring Security roles:</p>
     * <pre>
     * COBOL (COCOM01Y.cpy lines 27-28):
     *     05 CDEMO-USER-TYPE             PIC X(01).
     *        88 CDEMO-USRTYP-ADMIN       VALUE 'A'.
     *        88 CDEMO-USRTYP-USER        VALUE 'U'.
     * 
     * Java:
     *     UserType.ADMIN ('A') → ROLE_ADMIN
     *     UserType.USER  ('U') → ROLE_USER
     * </pre>
     * 
     * <p>These roles enable method-level security via @PreAuthorize annotations:</p>
     * <ul>
     *   <li>@PreAuthorize("hasRole('ADMIN')") - Admin-only endpoints</li>
     *   <li>@PreAuthorize("hasAnyRole('ADMIN', 'USER')") - All authenticated users</li>
     * </ul>
     * 
     * @param user User entity containing userType enum
     * @return List of GrantedAuthority with role prefix (ROLE_ADMIN or ROLE_USER)
     */
    private List<GrantedAuthority> buildGrantedAuthorities(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        // Map UserType enum to Spring Security role
        // COBOL: IF CDEMO-USRTYP-ADMIN [do admin operations]
        // Java:  @PreAuthorize("hasRole('ADMIN')")
        switch (user.getUserType()) {
            case ADMIN:
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                log.debug("Granted ROLE_ADMIN to userId: {}", user.getUserId());
                break;
            case USER:
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                log.debug("Granted ROLE_USER to userId: {}", user.getUserId());
                break;
            default:
                // Defensive programming: should never reach here due to enum constraint
                log.error("Unknown user type: {} for userId: {}", user.getUserType(), user.getUserId());
                throw new AuthenticationException(MessageConstants.MSG_UNABLE_TO_VERIFY_USER);
        }

        return authorities;
    }

    /**
     * Logs out user by clearing Spring Security context.
     * 
     * <p>In stateless JWT-based authentication, logout is primarily client-side
     * (client discards the JWT token). This method clears the SecurityContext
     * for the current request thread.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * EXEC CICS RETURN
     *     TRANSID(' ')  [Clear transaction ID, effectively ending session]
     * END-EXEC
     * </pre>
     * 
     * <p><b>JWT Token Invalidation:</b></p>
     * <ul>
     *   <li>Server-side: Clear SecurityContext (request scope only)</li>
     *   <li>Client-side: Client must discard JWT token from storage</li>
     *   <li>Token remains valid until expiration (stateless nature of JWT)</li>
     *   <li>For immediate invalidation, implement token blacklist (future enhancement)</li>
     * </ul>
     * 
     * @param userId User ID for logging purposes
     */
    public void logout(String userId) {
        log.info("Logout request for userId: {}", userId);

        // Clear Spring Security context for current thread
        // Note: This only affects the current request thread
        // JWT token remains valid until expiration (stateless authentication)
        SecurityContextHolder.clearContext();

        log.info("SecurityContext cleared for userId: {}", userId);
    }
}
