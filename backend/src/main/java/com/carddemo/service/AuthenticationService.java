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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Authentication Service for CardDemo application.
 * 
 * <p>Foundational service class transformed from COSGN00C.cbl CICS signon program,
 * providing core authentication capabilities including username/password validation,
 * BCrypt password verification, JWT token generation, user session context establishment,
 * and role-based authorization setup.</p>
 * 
 * <h2>COBOL Source Transformation</h2>
 * 
 * <p><strong>Original COBOL Program:</strong> COSGN00C.cbl (Sign-on Screen)</p>
 * <pre>
 * PROGRAM-ID. COSGN00C.
 * * CICS Transaction: CC00 (Sign-on)
 * * Function: Validates user credentials against USRSEC file
 * * Success: XCTL to COMEN01C (Regular User) or COADM01C (Admin)
 * * Failure: Display error message and re-display sign-on screen
 * </pre>
 * 
 * <p><strong>Key COBOL Logic Preserved:</strong></p>
 * <ul>
 *   <li>Username/password validation (lines 118-130): Implemented in authenticate()</li>
 *   <li>VSAM USRSEC file read (lines 211-219): Replaced with userSecurityRepository.findByUserId()</li>
 *   <li>Password comparison (line 223): Plain text → BCrypt verification</li>
 *   <li>User type checking (line 230): CDEMO-USRTYP-ADMIN → Spring Security roles</li>
 *   <li>Error messages (lines 242, 249, 254): Preserved exactly for UX consistency</li>
 * </ul>
 * 
 * <h2>Spring Security Integration</h2>
 * 
 * <p>This service integrates with Spring Security's authentication framework by
 * delegating to the AuthenticationManager. The CustomUserDetailsService handles
 * loading user details, while this service orchestrates the complete authentication
 * flow including JWT token generation.</p>
 * 
 * <h2>JWT Token-Based Authentication</h2>
 * 
 * <p>Replaces CICS COMMAREA-based session management with stateless JWT tokens:</p>
 * <pre>
 * COBOL COMMAREA Fields         →  JWT Token Claims
 * ────────────────────────────────────────────────────
 * CDEMO-USER-ID (PIC X(08))     →  "sub": "USER0001"
 * CDEMO-USER-TYPE (PIC X(01))   →  "roles": "ROLE_USER" or "ROLE_ADMIN"
 * Token Expiration (implicit)   →  "exp": timestamp + 24 hours
 * </pre>
 * 
 * <h2>Authentication Flow</h2>
 * 
 * <ol>
 *   <li><strong>Client Request:</strong> POST /api/auth/login with userId and password</li>
 *   <li><strong>Validation:</strong> Bean validation checks @NotBlank and @Size constraints</li>
 *   <li><strong>User Lookup:</strong> userSecurityRepository.findByUserId() (VSAM READ equivalent)</li>
 *   <li><strong>Password Verification:</strong> BCrypt password encoder matches plain password with hash</li>
 *   <li><strong>Authentication:</strong> AuthenticationManager authenticates with Spring Security</li>
 *   <li><strong>Token Generation:</strong> JwtTokenProvider creates JWT with user context</li>
 *   <li><strong>Response:</strong> LoginResponse with JWT token and user details</li>
 * </ol>
 * 
 * <h2>Security Enhancements from COBOL</h2>
 * 
 * <ul>
 *   <li><strong>Password Encryption:</strong> BCrypt hashing replaces COBOL plain text (PIC X(08))</li>
 *   <li><strong>Brute Force Protection:</strong> Spring Security rate limiting (configurable)</li>
 *   <li><strong>Audit Logging:</strong> Comprehensive authentication attempt logging</li>
 *   <li><strong>Session Management:</strong> Stateless JWT replaces CICS pseudo-conversational</li>
 *   <li><strong>Token Expiration:</strong> 24-hour JWT expiration with refresh capability</li>
 * </ul>
 * 
 * <h2>Error Handling Preservation</h2>
 * 
 * <p>COBOL error messages maintained for UX consistency:</p>
 * <pre>
 * COBOL (COSGN00C.cbl)                    Java Exception
 * ────────────────────────────────────────────────────────────────────
 * "User not found. Try again..."         → UserNotFoundException
 * "Wrong Password. Try again..."         → AuthenticationFailedException (INVALID_PASSWORD)
 * "Unable to verify the User..."         → AuthenticationFailedException (generic)
 * "Please enter User ID..."              → Bean Validation (@NotBlank)
 * "Please enter Password..."             → Bean Validation (@NotBlank)
 * </pre>
 * 
 * <h2>Usage in Application Architecture</h2>
 * 
 * <p>This foundational service is used by:</p>
 * <ul>
 *   <li><strong>AuthenticationController:</strong> Login endpoint (/api/auth/login)</li>
 *   <li><strong>SecurityConfig:</strong> Authentication provider configuration</li>
 *   <li><strong>All Protected Services:</strong> @PreAuthorize method security depends on authentication</li>
 * </ul>
 * 
 * <h2>Thread Safety and Transactions</h2>
 * 
 * <p>Annotated with {@code @Service} for Spring dependency injection. Authentication reads
 * are marked {@code @Transactional(readOnly=true)} for optimal database performance.
 * Service is thread-safe with no mutable state.</p>
 * 
 * @see com.carddemo.controller.AuthenticationController
 * @see com.carddemo.security.JwtTokenProvider
 * @see com.carddemo.entity.UserSecurity
 * @see com.carddemo.repository.UserSecurityRepository
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Service
public class AuthenticationService {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationService.class);
    
    /**
     * User security repository for USRSEC file operations.
     * 
     * <p>Replaces COBOL VSAM file operations:</p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (WS-USRSEC-FILE)
     *      INTO      (SEC-USER-DATA)
     *      RIDFLD    (WS-USER-ID)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * </pre>
     */
    private final UserSecurityRepository userSecurityRepository;
    
    /**
     * Password encoder for BCrypt password hashing and verification.
     * 
     * <p>Replaces COBOL plain text password comparison:</p>
     * <pre>
     * IF SEC-USR-PWD = WS-USER-PWD
     *     ... authentication success ...
     * ELSE
     *     MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     * END-IF
     * </pre>
     * 
     * <p>BCrypt Configuration: Strength 12 per Section 0.9 security requirements</p>
     */
    private final PasswordEncoder passwordEncoder;
    
    /**
     * JWT token provider for token generation and validation.
     * 
     * <p>Generates JWT tokens replacing CICS COMMAREA state management:</p>
     * <pre>
     * COBOL:
     * MOVE WS-USER-ID   TO CDEMO-USER-ID
     * MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     * 
     * Java:
     * String token = jwtTokenProvider.generateToken(authentication);
     * // Token contains userId (subject) and roles (claims)
     * </pre>
     */
    private final JwtTokenProvider jwtTokenProvider;
    
    /**
     * Spring Security authentication manager for credential verification.
     * 
     * <p>Orchestrates the complete authentication process including
     * password verification, user details loading, and security context establishment.</p>
     */
    @Autowired(required = false)
    private AuthenticationManager authenticationManager;
    
    /**
     * Constructs the AuthenticationService with required dependencies.
     * 
     * @param userSecurityRepository repository for user data access
     * @param passwordEncoder BCrypt password encoder
     * @param jwtTokenProvider JWT token generation service
     */
    @Autowired
    public AuthenticationService(
            UserSecurityRepository userSecurityRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider) {
        this.userSecurityRepository = userSecurityRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        
        logger.info("AuthenticationService initialized - replacing COBOL COSGN00C.cbl functionality");
    }
    

    /**
     * Authenticates user credentials and generates JWT token.
     * 
     * <p>This method replaces the complete authentication logic from COSGN00C.cbl,
     * including username/password validation, USRSEC file lookup, password verification,
     * user type checking, and session context establishment.</p>
     * 
     * <h3>COBOL Authentication Flow (COSGN00C.cbl):</h3>
     * <pre>
     * PROCESS-ENTER-KEY.
     *     * Validate username entered (lines 118-122)
     *     IF USERIDI = SPACES OR LOW-VALUES
     *         MOVE 'Please enter User ID ...' TO WS-MESSAGE
     *         PERFORM SEND-SIGNON-SCREEN
     *     END-IF
     *     
     *     * Validate password entered (lines 123-127)
     *     IF PASSWDI = SPACES OR LOW-VALUES
     *         MOVE 'Please enter Password ...' TO WS-MESSAGE
     *         PERFORM SEND-SIGNON-SCREEN
     *     END-IF
     *     
     *     * Read USRSEC file (lines 211-219)
     *     EXEC CICS READ DATASET(USRSEC) INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID) END-EXEC
     *     
     *     * Check password (line 223)
     *     IF SEC-USR-PWD = WS-USER-PWD
     *         * Success: Setup COMMAREA and transfer control
     *         MOVE WS-USER-ID   TO CDEMO-USER-ID
     *         MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *         IF CDEMO-USRTYP-ADMIN
     *             EXEC CICS XCTL PROGRAM('COADM01C') COMMAREA(...) END-EXEC
     *         ELSE
     *             EXEC CICS XCTL PROGRAM('COMEN01C') COMMAREA(...) END-EXEC
     *         END-IF
     *     ELSE
     *         MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *     END-IF
     * </pre>
     * 
     * <h3>Java Authentication Flow:</h3>
     * <ol>
     *   <li><strong>Input Validation:</strong> Bean Validation handles @NotBlank checks</li>
     *   <li><strong>Username Normalization:</strong> Convert to uppercase (COBOL UPPER-CASE function)</li>
     *   <li><strong>User Lookup:</strong> UserSecurityRepository.findByUserId() (VSAM READ)</li>
     *   <li><strong>Password Verification:</strong> BCrypt passwordEncoder.matches() (plain text comparison)</li>
     *   <li><strong>Authentication:</strong> AuthenticationManager with UsernamePasswordAuthenticationToken</li>
     *   <li><strong>JWT Generation:</strong> JwtTokenProvider creates token with userId and roles</li>
     *   <li><strong>Response Building:</strong> Populate LoginResponse with token and user context</li>
     * </ol>
     * 
     * <h3>Security Context Establishment:</h3>
     * <p>Replaces CICS COMMAREA state passing:</p>
     * <pre>
     * COBOL COMMAREA:
     * 05 CDEMO-USER-ID    PIC X(08).
     * 05 CDEMO-USER-TYPE  PIC X(01).
     * 
     * JWT Token Claims:
     * {
     *   "sub": "USER0001",           // CDEMO-USER-ID
     *   "roles": "ROLE_USER",        // CDEMO-USER-TYPE mapped
     *   "exp": timestamp + 86400000  // 24 hours
     * }
     * </pre>
     * 
     * <h3>Error Message Preservation:</h3>
     * <p>COBOL error messages maintained exactly for UX consistency:</p>
     * <ul>
     *   <li>"User not found. Try again ..." → UserNotFoundException</li>
     *   <li>"Wrong Password. Try again ..." → AuthenticationFailedException(INVALID_PASSWORD)</li>
     *   <li>"Unable to verify the User ..." → AuthenticationFailedException(generic)</li>
     * </ul>
     * 
     * @param loginRequest authentication credentials (userId and password)
     * @return LoginResponse containing JWT token and user session context
     * @throws UserNotFoundException if user ID does not exist (COBOL RESP 13)
     * @throws AuthenticationFailedException if password verification fails or other auth error
     * @throws IllegalArgumentException if loginRequest is null
     */
    @Transactional(readOnly = true)
    public LoginResponse authenticate(LoginRequest loginRequest) {
        // Validate input
        if (loginRequest == null) {
            logger.error("Authentication failed: LoginRequest is null");
            throw new IllegalArgumentException("LoginRequest cannot be null");
        }
        
        String userId = loginRequest.getUserId();
        String password = loginRequest.getPassword();
        
        // Bean Validation already ensures @NotBlank, but defensive check
        if (userId == null || userId.trim().isEmpty()) {
            logger.error("Authentication failed: userId is null or empty");
            throw new AuthenticationFailedException(
                "Please enter User ID ...",
                userId,
                AuthenticationFailedException.AuthFailureReason.USER_NOT_FOUND
            );
        }
        
        if (password == null || password.trim().isEmpty()) {
            logger.error("Authentication failed: password is null or empty for user: {}", userId);
            throw new AuthenticationFailedException(
                "Please enter Password ...",
                userId,
                AuthenticationFailedException.AuthFailureReason.INVALID_PASSWORD
            );
        }
        
        // Normalize username to uppercase (COBOL UPPER-CASE function)
        // COBOL: MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID
        String normalizedUserId = userId.trim().toUpperCase();
        
        logger.info("Authentication attempt for userId: {}", normalizedUserId);
        
        try {
            // User lookup: EXEC CICS READ DATASET(USRSEC) RIDFLD(WS-USER-ID)
            Optional<UserSecurity> userOptional = userSecurityRepository.findByUserId(normalizedUserId);
            
            if (userOptional.isEmpty()) {
                // COBOL: WHEN 13 (NOTFND) - "User not found. Try again ..."
                logger.warn("Authentication failed: User not found for userId: {}", normalizedUserId);
                throw new UserNotFoundException(
                    "User not found. Try again ...",
                    normalizedUserId
                );
            }
            
            UserSecurity user = userOptional.get();
            
            // Password verification: BCrypt replacement for COBOL plain text comparison
            // COBOL: IF SEC-USR-PWD = WS-USER-PWD
            boolean passwordMatches = passwordEncoder.matches(password, user.getPassword());
            
            if (!passwordMatches) {
                // COBOL: "Wrong Password. Try again ..."
                logger.warn("Authentication failed: Invalid password for userId: {}", normalizedUserId);
                throw new AuthenticationFailedException(
                    "Wrong Password. Try again ...",
                    normalizedUserId,
                    AuthenticationFailedException.AuthFailureReason.INVALID_PASSWORD
                );
            }
            
            // Spring Security authentication
            Authentication authentication;
            if (authenticationManager != null) {
                // Use AuthenticationManager if available (production configuration)
                UsernamePasswordAuthenticationToken authToken = 
                    new UsernamePasswordAuthenticationToken(normalizedUserId, password);
                authentication = authenticationManager.authenticate(authToken);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                // Fallback for testing or when AuthenticationManager not configured
                UsernamePasswordAuthenticationToken authToken = 
                    new UsernamePasswordAuthenticationToken(
                        user, 
                        password, 
                        user.getAuthorities()
                    );
                authentication = authToken;
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            
            // Generate JWT token replacing CICS COMMAREA state management
            // COBOL: MOVE WS-USER-ID TO CDEMO-USER-ID, MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
            String jwtToken = jwtTokenProvider.generateToken(authentication);
            
            logger.info("Authentication successful for userId: {} with userType: {}", 
                       user.getUserId(), user.getUserType());
            
            // Build response with token and user context
            LoginResponse response = buildLoginResponse(user, jwtToken);
            
            // Log successful authentication for audit trail
            logger.info("JWT token generated successfully for userId: {}, token expiration: 24 hours", 
                       user.getUserId());
            
            return response;
            
        } catch (UserNotFoundException ex) {
            // Re-throw UserNotFoundException with preserved message
            logger.error("Authentication failed: User not found - {}", ex.getMessage());
            throw ex;
            
        } catch (AuthenticationFailedException ex) {
            // Re-throw AuthenticationFailedException with preserved message
            logger.error("Authentication failed: {} - Reason: {}", ex.getMessage(), ex.getReason());
            throw ex;
            
        } catch (Exception ex) {
            // Catch any other exception and map to generic authentication failure
            // COBOL: WHEN OTHER - "Unable to verify the User ..."
            logger.error("Authentication failed: Unexpected error for userId: {}", normalizedUserId, ex);
            throw new AuthenticationFailedException(
                "Unable to verify the User ...",
                normalizedUserId,
                null,
                ex
            );
        }
    }
    
    /**
     * Builds LoginResponse from authenticated user and JWT token.
     * 
     * <p>Populates response DTO with user context matching COBOL BMS screen output
     * fields from COSGN0AO structure.</p>
     * 
     * <h3>COBOL Screen Fields Mapping:</h3>
     * <pre>
     * COBOL COSGN0AO Structure     →  LoginResponse Fields
     * ────────────────────────────────────────────────────
     * TRNNAMEO (PIC X(4))          →  transactionName = "CC00"
     * TITLE01O (PIC X(40))         →  title01 = "CardDemo Application"
     * TITLE02O (PIC X(40))         →  title02 = "Sign On"
     * CURDATEO (PIC X(8))          →  currentDate (LocalDate)
     * CURTIMEO (PIC X(9))          →  currentTime (LocalTime)
     * PGMNAMEO (PIC X(8))          →  programName = "COSGN00C"
     * APPLIDO (PIC X(8))           →  applicationId
     * SYSIDO (PIC X(8))            →  systemId
     * USERIDO (PIC X(8))           →  userId
     * (NEW)                        →  token (JWT)
     * </pre>
     * 
     * @param user authenticated UserSecurity entity
     * @param jwtToken generated JWT token string
     * @return populated LoginResponse DTO
     */
    private LoginResponse buildLoginResponse(UserSecurity user, String jwtToken) {
        LoginResponse response = new LoginResponse();
        
        // JWT token (NEW field not in COBOL)
        response.setToken(jwtToken);
        
        // User context from USRSEC entity
        response.setUserId(user.getUserId());
        
        // Transaction and program context (COBOL program metadata)
        response.setTransactionName("CC00");  // CICS transaction ID
        response.setProgramName("COSGN00C");  // COBOL program name
        
        // Screen titles (COBOL COTTL01Y copybook constants)
        response.setTitle01("CardDemo Application");
        response.setTitle02("Sign On");
        
        // Current date and time (COBOL FUNCTION CURRENT-DATE)
        response.setCurrentDate(LocalDate.now());
        response.setCurrentTime(LocalTime.now());
        
        // Application and system identifiers (CICS ASSIGN commands)
        response.setApplicationId("CARDEMO");
        response.setSystemId("JAVA");
        
        // No error message on successful authentication
        response.setErrorMessage(null);
        
        return response;
    }
    
    /**
     * Logs out the currently authenticated user.
     * 
     * <p>In a stateless JWT architecture, logout primarily serves to clear the
     * client-side token storage. The token remains valid until expiration (24 hours)
     * unless additional token blacklisting is implemented via Redis.</p>
     * 
     * <h3>COBOL Equivalent:</h3>
     * <pre>
     * CICS Transaction PF3 (Exit/Return):
     * WHEN DFHPF3
     *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *     PERFORM SEND-PLAIN-TEXT
     *     EXEC CICS RETURN END-EXEC
     * END-EVALUATE
     * </pre>
     * 
     * <h3>Logout Actions:</h3>
     * <ul>
     *   <li>Clear Spring Security context</li>
     *   <li>Log logout event for audit trail</li>
     *   <li>(Optional) Add token to Redis blacklist for immediate invalidation</li>
     *   <li>Client should discard token from localStorage/sessionStorage</li>
     * </ul>
     * 
     * @param token the JWT token to invalidate (optional, for blacklisting)
     */
    public void logout(String token) {
        try {
            // Get current username from security context if available
            String username = SecurityContextHolder.getContext().getAuthentication() != null ?
                SecurityContextHolder.getContext().getAuthentication().getName() : "unknown";
            
            logger.info("User logout initiated for: {}", username);
            
            // Clear Spring Security context
            SecurityContextHolder.clearContext();
            
            // Optional: Add token to Redis blacklist for immediate invalidation
            // This would require RedisTemplate and token blacklist service
            // For now, token remains valid until expiration
            if (token != null && !token.trim().isEmpty()) {
                logger.debug("Token invalidation requested (token will expire naturally in 24 hours)");
                // TODO: Implement Redis token blacklist if required
                // redisTokenBlacklistService.addToBlacklist(token, JWT_EXPIRATION_MS);
            }
            
            logger.info("User logout completed successfully for: {}", username);
            
        } catch (Exception ex) {
            logger.error("Error during logout process", ex);
            // Don't throw exception - logout should always succeed
            // Even if there are errors, clear the context
            SecurityContextHolder.clearContext();
        }
    }
}
