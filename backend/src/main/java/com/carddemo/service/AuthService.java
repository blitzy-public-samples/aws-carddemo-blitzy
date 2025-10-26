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

import com.carddemo.model.dto.AuthRequest;
import com.carddemo.model.dto.AuthResponse;
import com.carddemo.model.entity.UserSecurity;
import com.carddemo.repository.UserSecurityRepository;
import com.carddemo.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Authentication service for user login and authentication operations.
 * 
 * <p>Converted from COBOL program: COSGN00C.cbl
 * Original function: User signon screen and authentication using RACF security
 * 
 * <p>Conversion notes:
 * - Extracts authentication logic from COSGN00C.cbl PROCEDURE DIVISION (lines 209-257)
 * - COBOL EXEC CICS READ FILE('USRSEC') → UserSecurityRepository.findByUserId()
 * - COBOL plain-text password comparison (SEC-USR-PWD = WS-USER-PWD) → BCrypt password validation
 * - COBOL EXEC CICS XCTL PROGRAM → JWT token generation for stateless authentication
 * - COBOL RACF security → Spring Security with JWT token-based authentication
 * 
 * <p>COBOL Authentication Flow (COSGN00C.cbl lines 209-257):
 * <pre>
 * READ-USER-SEC-FILE.
 *     EXEC CICS READ
 *          DATASET   (WS-USRSEC-FILE)
 *          INTO      (SEC-USER-DATA)
 *          RIDFLD    (WS-USER-ID)
 *     END-EXEC.
 * 
 *     EVALUATE WS-RESP-CD
 *         WHEN 0
 *             IF SEC-USR-PWD = WS-USER-PWD
 *                 MOVE WS-USER-ID   TO CDEMO-USER-ID
 *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
 *                 IF CDEMO-USRTYP-ADMIN
 *                      EXEC CICS XCTL PROGRAM ('COADM01C') END-EXEC
 *                 ELSE
 *                      EXEC CICS XCTL PROGRAM ('COMEN01C') END-EXEC
 *                 END-IF
 *             ELSE
 *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
 *         WHEN 13
 *             MOVE 'User not found. Try again ...' TO WS-MESSAGE
 * </pre>
 * 
 * <p>Java Authentication Flow (Implemented Here):
 * <pre>
 * 1. Receive AuthRequest with userId and password
 * 2. Call UserSecurityRepository.findByUserId(userId)
 * 3. If user not found: throw UsernameNotFoundException
 * 4. Call BCryptPasswordEncoder.matches(password, userPwdHash)
 * 5. If password doesn't match: throw BadCredentialsException
 * 6. Generate JWT token with JwtTokenProvider.generateToken(userId, userType)
 * 7. Return AuthResponse with token, expiration, userId, userType
 * </pre>
 * 
 * <p>Security Requirements (Section 0.7.9):
 * - COBOL plain-text passwords → BCrypt hashed passwords
 * - RACF user profiles → Spring Security UserDetails from user_security table
 * - RACF session state → Stateless JWT token-based authentication
 * - RACF roles → Spring Security granted authorities (ROLE_ADMIN, ROLE_USER, ROLE_OPERATOR)
 * 
 * @see com.carddemo.controller.AuthController
 * @see com.carddemo.model.entity.UserSecurity
 * @see com.carddemo.repository.UserSecurityRepository
 * @see com.carddemo.security.JwtTokenProvider
 * @author CardDemo Migration Team
 * @version 1.0.0
 * @since 2024-01-01
 */
@Service
@Transactional
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final UserSecurityRepository userSecurityRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    /**
     * JWT token expiration time in milliseconds.
     * Injected from application.yml property: carddemo.security.jwt.expiration
     * Default value: 3600000ms (1 hour) matching CICS session timeout.
     */
    @Value("${carddemo.security.jwt.expiration:3600000}")
    private long jwtExpiration;

    /**
     * Constructor for dependency injection.
     * 
     * @param userSecurityRepository JPA repository for user_security table access
     * @param jwtTokenProvider JWT token generator and validator
     * @param passwordEncoder Password encoder (BCrypt) for secure password validation
     */
    @Autowired
    public AuthService(
            UserSecurityRepository userSecurityRepository,
            JwtTokenProvider jwtTokenProvider,
            PasswordEncoder passwordEncoder) {
        this.userSecurityRepository = userSecurityRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Authenticates a user with userId and password credentials.
     * 
     * <p>This method implements the complete authentication flow from COBOL program COSGN00C.cbl
     * READ-USER-SEC-FILE paragraph (lines 209-257). It performs the following steps:
     * 
     * <ol>
     *   <li>Validates the AuthRequest contains non-null userId and password</li>
     *   <li>Converts userId to uppercase (matching COBOL FUNCTION UPPER-CASE behavior)</li>
     *   <li>Queries user_security table via UserSecurityRepository.findByUserId()</li>
     *   <li>If user not found (COBOL RESP=13): throws UsernameNotFoundException</li>
     *   <li>Validates password using BCryptPasswordEncoder.matches() (replaces plain-text comparison)</li>
     *   <li>If password mismatch: throws BadCredentialsException</li>
     *   <li>Generates JWT token containing userId and userType (replaces COMMAREA population)</li>
     *   <li>Updates lastLoginTs timestamp in database (audit logging)</li>
     *   <li>Returns AuthResponse with token, expiration, userId, userType</li>
     * </ol>
     * 
     * <p>COBOL Equivalent Logic:
     * <pre>
     * PROCESS-ENTER-KEY.
     *     MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID
     *     PERFORM READ-USER-SEC-FILE.
     * 
     * READ-USER-SEC-FILE.
     *     EXEC CICS READ DATASET(WS-USRSEC-FILE) INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID) END-EXEC.
     *     EVALUATE WS-RESP-CD
     *         WHEN 0
     *             IF SEC-USR-PWD = WS-USER-PWD
     *                 MOVE WS-USER-ID TO CDEMO-USER-ID
     *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *                 EXEC CICS XCTL PROGRAM(...) COMMAREA(CARDDEMO-COMMAREA) END-EXEC
     *             ELSE
     *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *         WHEN 13
     *             MOVE 'User not found. Try again ...' TO WS-MESSAGE
     * </pre>
     * 
     * <p>Performance Requirements (Section 0.7.7):
     * - Method MUST complete in sub-200ms response time
     * - Database query (findByUserId) MUST execute in sub-10ms
     * - BCrypt password validation typically takes 50-100ms (intentional slowdown for security)
     * 
     * <p>Security Requirements (Section 0.7.9):
     * - Password is NEVER logged or stored in plain text
     * - BCrypt password hashing with strength 10 (default)
     * - JWT token contains userId and userType for authorization
     * - Token expiration set to 1 hour (matching CICS session timeout)
     * 
     * @param authRequest authentication request containing userId and password
     * @return AuthResponse containing JWT token, expiration time, userId, and userType
     * @throws UsernameNotFoundException if user does not exist in user_security table (COBOL RESP=13)
     * @throws BadCredentialsException if password does not match stored hash (COBOL password mismatch)
     * @throws IllegalArgumentException if authRequest is null or contains invalid data
     */
    public AuthResponse authenticate(AuthRequest authRequest) {
        // Validate input parameters
        if (authRequest == null) {
            logger.error("Authentication failed: AuthRequest is null");
            throw new IllegalArgumentException("AuthRequest cannot be null");
        }

        String userId = authRequest.getUserId();
        String password = authRequest.getPassword();

        if (userId == null || userId.trim().isEmpty()) {
            logger.error("Authentication failed: User ID is null or empty");
            throw new IllegalArgumentException("User ID is required");
        }

        if (password == null || password.trim().isEmpty()) {
            logger.error("Authentication failed: Password is null or empty");
            throw new IllegalArgumentException("Password is required");
        }

        // Convert userId to uppercase (COBOL: MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID)
        String normalizedUserId = userId.trim().toUpperCase();

        logger.debug("Authenticating user: {}", normalizedUserId);

        // Query user_security table (COBOL: EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID))
        Optional<UserSecurity> userSecurityOpt = userSecurityRepository.findByUserId(normalizedUserId);

        // Check if user exists (COBOL: EVALUATE WS-RESP-CD WHEN 13 ... User not found)
        if (userSecurityOpt.isEmpty()) {
            logger.warn("Authentication failed: User not found: {}", normalizedUserId);
            throw new UsernameNotFoundException("Invalid credentials");
        }

        UserSecurity userSecurity = userSecurityOpt.get();

        // Validate password using BCrypt (COBOL: IF SEC-USR-PWD = WS-USER-PWD)
        boolean passwordMatches = passwordEncoder.matches(password, userSecurity.getUserPwdHash());

        if (!passwordMatches) {
            logger.warn("Authentication failed: Invalid password for user: {}", normalizedUserId);
            throw new BadCredentialsException("Invalid credentials");
        }

        logger.info("User authenticated successfully: {}", normalizedUserId);

        // Generate JWT token (COBOL: MOVE WS-USER-ID TO CDEMO-USER-ID; MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE)
        String jwtToken = jwtTokenProvider.generateToken(
                userSecurity.getUserId(),
                userSecurity.getUserType()
        );

        // Update last login timestamp (audit logging)
        userSecurity.setLastLoginTs(Timestamp.valueOf(LocalDateTime.now()));
        userSecurityRepository.save(userSecurity);

        // Calculate expiration time in seconds (for API response)
        long expiresInSeconds = jwtExpiration / 1000;

        // Return authentication response (COBOL: EXEC CICS RETURN with COMMAREA)
        return new AuthResponse(
                jwtToken,
                expiresInSeconds,
                userSecurity.getUserId(),
                userSecurity.getUserType()
        );
    }

    /**
     * Logs out the current user.
     * 
     * <p>This method implements the logout functionality from COBOL program COMEN01C.cbl
     * PF3 key handling:
     * <pre>
     * WHEN DFHPF3
     *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *     PERFORM SEND-PLAIN-TEXT
     *     EXEC CICS RETURN END-EXEC
     * </pre>
     * 
     * <p>In the JWT stateless authentication model, logout is primarily a client-side operation
     * where the client deletes the JWT token. However, this method can be extended to implement
     * token blacklisting or revocation if needed.
     * 
     * <p>Token Invalidation Strategies:
     * <ul>
     *   <li><b>Client-side deletion (current):</b> Client discards token, no server-side action needed</li>
     *   <li><b>Token blacklist (future):</b> Add token to Redis cache until expiration time</li>
     *   <li><b>Short token expiration (current):</b> 1-hour tokens minimize need for invalidation</li>
     * </ul>
     * 
     * @param token the JWT token to invalidate (optional, for future blacklist implementation)
     * @return success message confirming logout
     */
    public String logout(String token) {
        logger.info("User logout requested");

        // Future enhancement: Add token to blacklist in Redis cache
        // For now, logout is handled client-side by discarding the token

        return "Logout successful. Thank you for using CardDemo.";
    }
}
