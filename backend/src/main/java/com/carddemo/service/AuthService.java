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
import lombok.extern.slf4j.Slf4j;
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
 * - Extracts authentication logic from COSGN00C.cbl PROCEDURE DIVISION (lines 108-140, 209-257)
 * - COBOL EXEC CICS READ FILE('USRSEC') → UserSecurityRepository.findById()
 * - COBOL plain-text password comparison (SEC-USR-PWD = WS-USER-PWD line 223) → BCrypt password validation
 * - COBOL EXEC CICS XCTL PROGRAM (lines 231-239) → JWT token generation for stateless authentication
 * - COBOL RACF security → Spring Security with JWT token-based authentication
 * 
 * <p>COBOL Authentication Flow (COSGN00C.cbl lines 108-140, 209-257):
 * <pre>
 * PROCESS-ENTER-KEY.
 *     WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
 *         MOVE 'Please enter User ID ...' TO WS-MESSAGE          [line 118-122]
 *     WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
 *         MOVE 'Please enter Password ...' TO WS-MESSAGE         [line 123-127]
 *     
 *     MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID [line 132-134]
 *     PERFORM READ-USER-SEC-FILE                                  [line 139]
 * 
 * READ-USER-SEC-FILE.
 *     EXEC CICS READ
 *          DATASET   (WS-USRSEC-FILE)
 *          INTO      (SEC-USER-DATA)
 *          RIDFLD    (WS-USER-ID)
 *     END-EXEC.
 * 
 *     EVALUATE WS-RESP-CD
 *         WHEN 0
 *             IF SEC-USR-PWD = WS-USER-PWD                        [line 223]
 *                 MOVE WS-USER-ID   TO CDEMO-USER-ID              [line 226]
 *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE            [line 227]
 *                 IF CDEMO-USRTYP-ADMIN                           [line 230]
 *                      EXEC CICS XCTL PROGRAM ('COADM01C') END-EXEC [line 231-234]
 *                 ELSE
 *                      EXEC CICS XCTL PROGRAM ('COMEN01C') END-EXEC [line 236-239]
 *                 END-IF
 *             ELSE
 *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE [line 242]
 *         WHEN 13
 *             MOVE 'User not found. Try again ...' TO WS-MESSAGE    [line 249]
 * </pre>
 * 
 * <p>Java Authentication Flow (Implemented Here):
 * <pre>
 * 1. Validate userId using ValidationService.validateUserId()
 * 2. Validate password using ValidationService.validatePassword()
 * 3. Convert userId to uppercase (FUNCTION UPPER-CASE)
 * 4. Call UserSecurityRepository.findById(userId.toUpperCase())
 * 5. If user not found: throw BusinessException("User not found. Try again ...")
 * 6. Call BCryptPasswordEncoder.matches(password, userPwdHash)
 * 7. If password doesn't match: throw BusinessException("Wrong Password. Try again ...")
 * 8. Extract userType from SEC-USR-TYPE field
 * 9. Convert userType to role: 'A' → "ROLE_ADMIN", 'U' → "ROLE_USER"
 * 10. Generate JWT token with JwtTokenProvider.generateToken(userId, userType)
 * 11. Log authentication event (auditLog)
 * 12. Return AuthResponse with token, expiresIn, userId, userType
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
@Slf4j
@Service
public class AuthService {

    private final UserSecurityRepository userSecurityRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final ValidationService validationService;

    /**
     * Constructor for dependency injection.
     * 
     * Per Agent Action Plan requirements: Use constructor injection for all dependencies.
     * 
     * @param userSecurityRepository JPA repository for user_security table access
     * @param jwtTokenProvider JWT token generator and validator
     * @param passwordEncoder Password encoder (BCrypt) for secure password validation
     * @param validationService Centralized validation service for field validation
     */
    public AuthService(
            UserSecurityRepository userSecurityRepository,
            JwtTokenProvider jwtTokenProvider,
            PasswordEncoder passwordEncoder,
            ValidationService validationService) {
        this.userSecurityRepository = userSecurityRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.validationService = validationService;
    }

    /**
     * Authenticates a user with userId and password credentials.
     * 
     * <p>This method implements the complete authentication flow from COBOL program COSGN00C.cbl
     * PROCESS-ENTER-KEY paragraph (lines 108-140) and READ-USER-SEC-FILE paragraph (lines 209-257).
     * 
     * <p><b>COBOL Logic Flow:</b>
     * <ol>
     *   <li>Validate userId not empty (line 118-122): IF USERIDI = SPACES OR LOW-VALUES</li>
     *   <li>Validate password not empty (line 123-127): IF PASSWDI = SPACES OR LOW-VALUES</li>
     *   <li>Convert userId to uppercase (line 132-134): MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID</li>
     *   <li>Read user security file (line 209-219): EXEC CICS READ FILE('USRSEC') RIDFLD(WS-USER-ID)</li>
     *   <li>Check user exists (line 247-250): WHEN 13 MOVE 'User not found. Try again ...'</li>
     *   <li>Validate password (line 223): IF SEC-USR-PWD = WS-USER-PWD</li>
     *   <li>On password mismatch (line 242): MOVE 'Wrong Password. Try again ...'</li>
     *   <li>Extract user type (line 227): MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE</li>
     *   <li>Transfer control based on role (lines 230-239): IF CDEMO-USRTYP-ADMIN EXEC CICS XCTL...</li>
     * </ol>
     * 
     * <p><b>Java Implementation Steps:</b>
     * <ol>
     *   <li>Validate userId using ValidationService.validateUserId() (replaces lines 118-122)</li>
     *   <li>Validate password using ValidationService.validatePassword() (replaces lines 123-127)</li>
     *   <li>Convert userId to uppercase (replaces line 132-134)</li>
     *   <li>Query user_security table via UserSecurityRepository.findById() (replaces lines 211-219)</li>
     *   <li>If user not found: throw BusinessException("User not found. Try again ...") (replaces lines 247-250)</li>
     *   <li>Validate password using BCryptPasswordEncoder.matches() (replaces line 223 plain-text comparison)</li>
     *   <li>If password mismatch: throw BusinessException("Wrong Password. Try again ...") (replaces lines 241-245)</li>
     *   <li>Extract userType from SEC-USR-TYPE field (replaces line 227)</li>
     *   <li>Convert userType to Spring Security role: 'A' → "ROLE_ADMIN", 'U' → "ROLE_USER" (replaces lines 230-239)</li>
     *   <li>Generate JWT token with JwtTokenProvider.generateToken() (replaces EXEC CICS XCTL with COMMAREA)</li>
     *   <li>Log authentication event for audit trail</li>
     *   <li>Return AuthResponse with token, expiresIn, userId, userType</li>
     * </ol>
     * 
     * <p><b>Performance Requirements (Section 0.7.7):</b>
     * <ul>
     *   <li>Method MUST complete in sub-200ms response time</li>
     *   <li>Database query (findById) MUST execute in sub-10ms (B-tree index on user_id)</li>
     *   <li>BCrypt password validation typically takes 50-100ms (intentional slowdown for security)</li>
     * </ul>
     * 
     * <p><b>Security Requirements (Section 0.7.9):</b>
     * <ul>
     *   <li>Password is NEVER logged or stored in plain text</li>
     *   <li>BCrypt password hashing with strength 10 (default)</li>
     *   <li>JWT token contains userId and userType for authorization</li>
     *   <li>Token expiration set to 1 hour (matching CICS session timeout)</li>
     *   <li>Audit logging for successful logins, failed attempts</li>
     * </ul>
     * 
     * @param userId the user identifier (8 characters max, from USERIDI field)
     * @param password the user password (from PASSWDI field)
     * @return AuthResponse containing JWT token, expiration time, userId, and userType
     * @throws BusinessException with message "User not found. Try again ..." if user doesn't exist (COBOL line 249)
     * @throws BusinessException with message "Wrong Password. Try again ..." if password doesn't match (COBOL line 242)
     */
    @Transactional(readOnly = true)
    public AuthResponse authenticate(String userId, String password) {
        log.debug("Starting authentication process for user");
        
        // Step 1: Validate userId (COBOL lines 118-122)
        validationService.validateUserId(userId);
        
        // Step 2: Validate password (COBOL lines 123-127)
        validationService.validatePassword(password);
        
        // Step 3: Convert userId to uppercase (COBOL lines 132-134: MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID)
        String normalizedUserId = userId.trim().toUpperCase();
        
        log.debug("Authenticating user: {}", normalizedUserId);
        
        // Step 4: Read user security record (COBOL lines 211-219: EXEC CICS READ FILE('USRSEC') RIDFLD(WS-USER-ID))
        Optional<UserSecurity> userOpt = userSecurityRepository.findById(normalizedUserId);
        
        // Step 5: Check if user exists (COBOL lines 247-250: WHEN 13 MOVE 'User not found...')
        if (userOpt.isEmpty()) {
            log.warn("Authentication failed: User not found: {}", normalizedUserId);
            // Throw BusinessException with exact COBOL error message from line 249
            throw new BusinessException("User not found. Try again ...");
        }
        
        UserSecurity user = userOpt.get();
        
        // Step 6: Validate password using BCrypt (COBOL line 223: IF SEC-USR-PWD = WS-USER-PWD)
        // Note: BCrypt replaces plain-text password comparison per Section 0.7.9
        boolean passwordMatches = passwordEncoder.matches(password, user.getUserPwdHash());
        
        // Step 7: Handle password mismatch (COBOL lines 241-245)
        if (!passwordMatches) {
            log.warn("Authentication failed: Invalid password for user: {}", normalizedUserId);
            // Throw BusinessException with exact COBOL error message from line 242
            throw new BusinessException("Wrong Password. Try again ...");
        }
        
        log.info("User authenticated successfully: {}", normalizedUserId);
        
        // Step 8: Extract user type (COBOL line 227: MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE)
        String userType = user.getUserType();
        
        // Step 9: Convert userType to Spring Security role (COBOL lines 230-239: IF CDEMO-USRTYP-ADMIN)
        // COBOL 88-level condition: CDEMO-USRTYP-ADMIN VALUE 'A'
        String role = "A".equals(userType) ? "ROLE_ADMIN" : "ROLE_USER";
        
        log.debug("User type: {}, Assigned role: {}", userType, role);
        
        // Step 10: Generate JWT token (replaces EXEC CICS XCTL PROGRAM with COMMAREA)
        // COBOL lines 231-239: EXEC CICS XCTL PROGRAM('COADM01C' or 'COMEN01C') COMMAREA(CARDDEMO-COMMAREA)
        String token = jwtTokenProvider.generateToken(normalizedUserId, userType);
        
        // Step 11: Audit logging (replaces RACF audit trail per Section 0.7.9)
        auditLog(normalizedUserId, "LOGIN_SUCCESS");
        
        // Step 12: Return authentication response (replaces EXEC CICS RETURN with COMMAREA)
        long expiresIn = jwtTokenProvider.getExpirationTime() / 1000; // Convert ms to seconds
        
        return new AuthResponse(token, expiresIn, normalizedUserId, userType);
    }

    /**
     * Logs out the current user by invalidating the JWT token.
     * 
     * <p>This method implements the logout functionality from COBOL program COMEN01C.cbl
     * PF3 key handling (DFHPF3 represents the F3 function key for logout):
     * <pre>
     * WHEN DFHPF3
     *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *     PERFORM SEND-PLAIN-TEXT
     *     EXEC CICS RETURN END-EXEC
     * </pre>
     * 
     * <p>In the JWT stateless authentication model, logout is primarily a client-side operation
     * where the client deletes the JWT token from local storage. This method logs the logout event
     * for audit trail purposes, maintaining identical audit capabilities to COBOL RACF logging.
     * 
     * <p><b>Token Invalidation Strategies:</b>
     * <ul>
     *   <li><b>Client-side deletion (current):</b> Client discards token, no server-side state needed</li>
     *   <li><b>Token blacklist (future enhancement):</b> Add token to Redis cache until expiration time</li>
     *   <li><b>Short token expiration (current):</b> 1-hour tokens minimize need for server-side invalidation</li>
     * </ul>
     * 
     * <p><b>Audit Logging:</b> Per Section 0.7.9 security requirements, logout events are logged
     * to maintain audit trail equivalent to RACF audit logging on the mainframe.
     * 
     * @param token the JWT token to invalidate (used for audit logging, actual invalidation is client-side)
     */
    public void logout(String token) {
        log.info("User logout requested");
        
        // Extract userId from token for audit logging
        // In production, could extract userId from token claims if needed
        // For now, log generic logout event
        
        // Audit logging for logout event
        auditLog("UNKNOWN", "LOGOUT");
        
        // Future enhancement: Add token to blacklist in Redis cache
        // For now, logout is handled client-side by discarding the token
        // The token will expire naturally after 1 hour (jwtExpiration)
        
        log.debug("Logout completed successfully");
    }
    
    /**
     * Audit log for authentication events.
     * 
     * <p>This method provides audit logging functionality equivalent to RACF audit trail
     * from the mainframe environment. Per Section 0.7.9 security requirements, all
     * authentication events must be logged:
     * <ul>
     *   <li>User login/logout</li>
     *   <li>Failed authentication attempts</li>
     *   <li>Authorization failures</li>
     * </ul>
     * 
     * <p>In production, this would integrate with enterprise logging systems (ELK Stack,
     * Splunk) and potentially write to a dedicated audit_log table in PostgreSQL for
     * compliance and security monitoring.
     * 
     * @param userId the user identifier for the audit event
     * @param event the type of authentication event (LOGIN_SUCCESS, LOGIN_FAILURE, LOGOUT)
     */
    private void auditLog(String userId, String event) {
        // Log authentication event with structured logging
        log.info("AUDIT: userId={}, event={}, timestamp={}", 
                userId, event, LocalDateTime.now());
        
        // Future enhancement: Persist to audit_log table in PostgreSQL
        // INSERT INTO audit_log (user_id, event_type, event_timestamp, ip_address)
        // VALUES (userId, event, CURRENT_TIMESTAMP, clientIpAddress)
    }
}
