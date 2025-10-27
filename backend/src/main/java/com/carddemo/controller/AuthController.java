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
import com.carddemo.model.dto.ErrorResponse;
import com.carddemo.service.AuthService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * REST API controller for authentication endpoints.
 * 
 * <p>Converted from COBOL program: COSGN00C.cbl
 * Original function: User signon screen and authentication (lines 72-250)
 * 
 * <p>Conversion notes:
 * - Converts CICS signon program COSGN00C.cbl PROCEDURE DIVISION to REST API endpoints
 * - COBOL EXEC CICS SEND MAP('COSGN0A') → REST API returns JSON response
 * - COBOL EXEC CICS RECEIVE MAP('COSGN0A') → REST API accepts JSON request body with @RequestBody
 * - COBOL EXEC CICS READ FILE('USRSEC') → AuthService calls UserSecurityRepository.findById()
 * - COBOL plain-text password comparison (line 223) → BCrypt password validation in AuthService
 * - COBOL EXEC CICS XCTL PROGRAM (lines 231-239) → JWT token returned in AuthResponse JSON
 * 
 * <p>COBOL Screen Flow (COSGN00C.cbl lines 72-257):
 * <pre>
 * MAIN-PARA.
 *     IF EIBCALEN = 0
 *         MOVE LOW-VALUES TO COSGN0AO
 *         PERFORM SEND-SIGNON-SCREEN                                 [lines 80-83]
 *     ELSE
 *         EVALUATE EIBAID
 *             WHEN DFHENTER
 *                 PERFORM PROCESS-ENTER-KEY                          [lines 86-87]
 * 
 * PROCESS-ENTER-KEY.
 *     EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00') END-EXEC.  [lines 110-115]
 *     WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
 *         MOVE 'Please enter User ID ...' TO WS-MESSAGE              [lines 118-122]
 *     WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
 *         MOVE 'Please enter Password ...' TO WS-MESSAGE             [lines 123-127]
 *     MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID               [lines 132-134]
 *     MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD              [lines 135-136]
 *     PERFORM READ-USER-SEC-FILE                                     [line 139]
 * 
 * READ-USER-SEC-FILE.
 *     EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)
 *          INTO(SEC-USER-DATA) END-EXEC.                             [lines 211-219]
 *     EVALUATE WS-RESP-CD
 *         WHEN 0
 *             IF SEC-USR-PWD = WS-USER-PWD                           [line 223]
 *                 MOVE WS-USER-ID TO CDEMO-USER-ID
 *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE               [lines 226-227]
 *                 IF CDEMO-USRTYP-ADMIN
 *                     EXEC CICS XCTL PROGRAM('COADM01C') COMMAREA(...) [lines 231-234]
 *                 ELSE
 *                     EXEC CICS XCTL PROGRAM('COMEN01C') COMMAREA(...) [lines 236-239]
 *             ELSE
 *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE [line 242]
 *         WHEN 13
 *             MOVE 'User not found. Try again ...' TO WS-MESSAGE     [line 249]
 * </pre>
 * 
 * <p>Java REST API Flow (Implemented Here):
 * <pre>
 * 1. POST /api/auth/login receives JSON body {userId: "...", password: "..."}
 * 2. @Valid annotation triggers Bean Validation for @NotBlank constraints
 * 3. AuthController.login() logs attempt with log.info()
 * 4. AuthController calls AuthService.authenticate(userId, password)
 * 5. AuthService queries user_security table and validates BCrypt password
 * 6. If valid: Generate JWT token and return AuthResponse (200 OK)
 * 7. If invalid: Catch BusinessException with exact COBOL error messages (401 Unauthorized)
 * 8. POST /api/auth/logout calls AuthService.logout(token) and returns 204 No Content
 * </pre>
 * 
 * <p>API Endpoints:
 * <ul>
 *   <li><b>POST /api/auth/login</b> - User authentication with credentials, returns JWT token</li>
 *   <li><b>POST /api/auth/logout</b> - User logout (token invalidation audit logging)</li>
 * </ul>
 * 
 * <p>Security Requirements (Section 0.7.9):
 * - Password is NEVER logged or exposed in error messages
 * - Exact COBOL error messages preserved: "Wrong Password. Try again ..." (line 242)
 *   and "User not found. Try again ..." (line 249)
 * - JWT token contains userId and userType for authorization
 * - Token expiration set to 1 hour (matching CICS session timeout)
 * - Audit logging for all authentication attempts
 * 
 * <p>Performance Requirements (Section 0.7.7):
 * - Authentication requests MUST complete in sub-200ms response time
 * - Database query MUST execute in sub-10ms (B-tree index on user_id)
 * - BCrypt validation takes 50-100ms (intentional for security)
 * 
 * @see com.carddemo.service.AuthService
 * @see com.carddemo.model.dto.AuthRequest
 * @see com.carddemo.model.dto.AuthResponse
 * @see com.carddemo.model.dto.ErrorResponse
 * @author CardDemo Migration Team
 * @version 1.0.0
 * @since 2024-01-01
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    /**
     * Constructor for dependency injection.
     * 
     * Per Agent Action Plan Section 0.4.6: Use constructor injection for all Spring dependencies.
     * 
     * @param authService authentication service for user login/logout operations
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * User login endpoint.
     * 
     * <p>Authenticates user with userId and password credentials, returning JWT token
     * for subsequent authenticated API requests.
     * 
     * <p>This method implements the complete COBOL authentication flow from COSGN00C.cbl
     * PROCESS-ENTER-KEY paragraph (lines 108-140) and READ-USER-SEC-FILE paragraph (lines 209-257).
     * 
     * <p><b>COBOL Logic Implementation:</b>
     * <ol>
     *   <li>Receive map input (lines 110-115): EXEC CICS RECEIVE MAP('COSGN0A') 
     *       → @Valid @RequestBody AuthRequest</li>
     *   <li>Validate userId not empty (lines 118-122): IF USERIDI = SPACES OR LOW-VALUES
     *       → Bean Validation @NotBlank in AuthRequest</li>
     *   <li>Validate password not empty (lines 123-127): IF PASSWDI = SPACES OR LOW-VALUES
     *       → Bean Validation @NotBlank in AuthRequest</li>
     *   <li>Convert userId to uppercase (lines 132-134): MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID
     *       → AuthService.authenticate() handles normalization</li>
     *   <li>Read user security file (lines 211-219): EXEC CICS READ FILE('USRSEC') RIDFLD(WS-USER-ID)
     *       → AuthService calls UserSecurityRepository.findById()</li>
     *   <li>Check user exists (lines 247-250): WHEN 13 MOVE 'User not found. Try again ...'
     *       → AuthService throws BusinessException with exact message</li>
     *   <li>Validate password (line 223): IF SEC-USR-PWD = WS-USER-PWD
     *       → AuthService calls BCryptPasswordEncoder.matches()</li>
     *   <li>On password mismatch (line 242): MOVE 'Wrong Password. Try again ...'
     *       → AuthService throws BusinessException with exact message</li>
     *   <li>Extract user type (line 227): MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *       → AuthService includes userType in AuthResponse</li>
     *   <li>Transfer control based on role (lines 230-239): IF CDEMO-USRTYP-ADMIN EXEC CICS XCTL...
     *       → JWT token contains userType, client routes to appropriate menu</li>
     * </ol>
     * 
     * <p><b>Request Body Example:</b>
     * <pre>
     * {
     *   "userId": "B0001",
     *   "password": "PASS1234"
     * }
     * </pre>
     * 
     * <p><b>Success Response Example (200 OK):</b>
     * <pre>
     * {
     *   "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
     *   "expiresIn": 3600,
     *   "userId": "B0001",
     *   "userType": "U"
     * }
     * </pre>
     * 
     * <p><b>Error Response Examples (401 Unauthorized):</b>
     * <pre>
     * {
     *   "timestamp": "2024-01-15T10:30:00",
     *   "status": 401,
     *   "error": "Unauthorized",
     *   "message": "Wrong Password. Try again ...",
     *   "details": "Wrong Password. Try again ...",
     *   "path": "/api/auth/login"
     * }
     * </pre>
     * 
     * <p><b>Error Handling:</b>
     * <ul>
     *   <li>BusinessException with "User not found. Try again ..." → 401 Unauthorized (COBOL line 249)</li>
     *   <li>BusinessException with "Wrong Password. Try again ..." → 401 Unauthorized (COBOL line 242)</li>
     *   <li>Bean Validation failure (@NotBlank violation) → 400 Bad Request (handled by GlobalExceptionHandler)</li>
     * </ul>
     * 
     * @param authRequest authentication request containing userId and password (validated by @Valid)
     * @return ResponseEntity with AuthResponse (200 OK) on success
     * @throws BusinessException if authentication fails (caught and converted to 401 error response)
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthRequest authRequest) {
        // Step 1: Log authentication attempt (audit trail per Section 0.7.9)
        log.info("Login attempt for user: {}", authRequest.getUserId());
        
        try {
            // Step 2: Call AuthService.authenticate() which implements:
            // - COBOL PROCESS-ENTER-KEY paragraph (lines 108-140)
            // - COBOL READ-USER-SEC-FILE paragraph (lines 209-257)
            // - Returns AuthResponse with JWT token, expiresIn, userId, userType
            AuthResponse authResponse = authService.authenticate(
                authRequest.getUserId(),
                authRequest.getPassword()
            );
            
            // Step 3: Log successful authentication (audit trail)
            log.info("User authenticated successfully: {}", authRequest.getUserId());
            
            // Step 4: Return 200 OK with AuthResponse
            // Replaces COBOL EXEC CICS XCTL PROGRAM with COMMAREA (lines 231-239)
            return ResponseEntity.ok(authResponse);
            
        } catch (BusinessException e) {
            // Step 5: Handle authentication failures from AuthService
            // BusinessException contains exact COBOL error messages:
            // - "User not found. Try again ..." (COBOL line 249)
            // - "Wrong Password. Try again ..." (COBOL line 242)
            
            log.warn("Authentication failed for user: {} - {}", authRequest.getUserId(), e.getMessage());
            
            // Create ErrorResponse with exact COBOL error message
            ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                401,
                "Unauthorized",
                e.getMessage(), // Exact COBOL message: "User not found..." or "Wrong Password..."
                e.getMessage(), // Same message in details field
                "/api/auth/login"
            );
            
            // Return 401 Unauthorized with ErrorResponse
            // Replaces COBOL PERFORM SEND-SIGNON-SCREEN with error message (lines 122, 127, 245, 251)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
        }
    }

    /**
     * User logout endpoint.
     * 
     * <p>Logs out the current user by invalidating the JWT token (audit logging).
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
     * <p>In JWT stateless authentication, logout is primarily handled client-side by
     * discarding the token. This endpoint provides audit logging for logout events
     * to maintain equivalent audit capabilities to COBOL RACF logging (Section 0.7.9).
     * 
     * <p><b>Implementation Notes:</b>
     * <ul>
     *   <li>Token is passed via Authorization header: "Bearer &lt;token&gt;"</li>
     *   <li>AuthService.logout(token) logs the logout event for audit trail</li>
     *   <li>Returns 204 No Content (successful logout with no response body)</li>
     *   <li>Future enhancement: Token blacklist in Redis cache until expiration</li>
     * </ul>
     * 
     * <p><b>Success Response: 204 No Content (no response body)</b>
     * 
     * @param token JWT token from Authorization header (format: "Bearer &lt;token&gt;")
     * @return ResponseEntity with 204 No Content on success
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String token) {
        // Step 1: Log logout attempt (audit trail per Section 0.7.9)
        log.info("Logout request received");
        
        // Step 2: Extract JWT token from Authorization header
        // Expected format: "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
        String jwtToken = null;
        if (token != null && token.startsWith("Bearer ")) {
            jwtToken = token.substring(7); // Remove "Bearer " prefix
        }
        
        // Step 3: Call AuthService.logout() for audit logging
        // AuthService logs the logout event to maintain RACF-equivalent audit trail
        authService.logout(jwtToken);
        
        // Step 4: Log successful logout (audit trail)
        log.info("User logout completed successfully");
        
        // Step 5: Return 204 No Content
        // Replaces COBOL EXEC CICS RETURN (COMEN01C.cbl PF3 handling)
        // No response body needed for logout (client discards token)
        return ResponseEntity.noContent().build();
    }
}
