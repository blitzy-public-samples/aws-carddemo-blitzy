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

import com.carddemo.model.dto.AuthRequest;
import com.carddemo.model.dto.AuthResponse;
import com.carddemo.model.dto.ErrorResponse;
import com.carddemo.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

/**
 * REST API controller for authentication endpoints.
 * 
 * <p>Converted from COBOL program: COSGN00C.cbl
 * Original function: User signon screen and authentication
 * 
 * <p>Conversion notes:
 * - Converts CICS signon program COSGN00C.cbl to REST API endpoints
 * - COBOL EXEC CICS SEND MAP('COSGN0A') → REST API returns JSON response
 * - COBOL EXEC CICS RECEIVE MAP('COSGN0A') → REST API accepts JSON request body
 * - COBOL EXEC CICS READ FILE('USRSEC') → AuthService calls UserSecurityRepository
 * - COBOL plain-text password comparison → BCrypt password validation in AuthService
 * - COBOL EXEC CICS XCTL PROGRAM → JWT token returned in JSON response
 * 
 * <p>COBOL Screen Flow (COSGN00C.cbl):
 * <pre>
 * 1. Display COSGN0A BMS map (signon screen with userId and password fields)
 * 2. User enters credentials and presses ENTER
 * 3. EXEC CICS RECEIVE MAP('COSGN0A') receives input
 * 4. Validate fields: userId and password not empty
 * 5. PERFORM READ-USER-SEC-FILE to query USRSEC VSAM file
 * 6. Compare SEC-USR-PWD with entered password
 * 7. If match: EXEC CICS XCTL to COADM01C (admin) or COMEN01C (user menu)
 * 8. If no match: Display error "Wrong Password. Try again ..."
 * 9. If user not found: Display error "User not found. Try again ..."
 * </pre>
 * 
 * <p>Java REST API Flow (Implemented Here):
 * <pre>
 * 1. POST /api/auth/login receives JSON body {userId: "...", password: "..."}
 * 2. @Valid annotation triggers Bean Validation for @NotBlank constraints
 * 3. AuthController calls AuthService.authenticate(authRequest)
 * 4. AuthService queries user_security table and validates BCrypt password
 * 5. If valid: Generate JWT token and return AuthResponse (200 OK)
 * 6. If invalid password: Throw BadCredentialsException → ErrorResponse (401 Unauthorized)
 * 7. If user not found: Throw UsernameNotFoundException → ErrorResponse (401 Unauthorized)
 * 8. POST /api/auth/logout invalidates token (optional, client-side deletion)
 * </pre>
 * 
 * <p>API Endpoints:
 * <ul>
 *   <li><b>POST /api/auth/login</b> - User authentication with credentials</li>
 *   <li><b>POST /api/auth/logout</b> - User logout (optional, JWT token invalidation)</li>
 * </ul>
 * 
 * <p>Security Requirements (Section 0.7.9):
 * - Password is NEVER logged or exposed in error messages
 * - Both "user not found" and "wrong password" return same 401 Unauthorized (prevents user enumeration)
 * - JWT token contains userId and userType for authorization
 * - Token expiration set to 1 hour (matching CICS session timeout)
 * 
 * <p>Performance Requirements (Section 0.7.7):
 * - Authentication requests MUST complete in sub-200ms response time
 * - Database query MUST execute in sub-10ms
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
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "User authentication and session management endpoints")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    /**
     * Constructor for dependency injection.
     * 
     * @param authService authentication service for user login/logout operations
     */
    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * User login endpoint.
     * 
     * <p>Authenticates user with userId and password credentials, returning JWT token
     * for subsequent authenticated API requests.
     * 
     * <p>COBOL Equivalent (COSGN00C.cbl):
     * <pre>
     * PROCESS-ENTER-KEY.
     *     EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00') END-EXEC.
     *     MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID.
     *     MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO WS-USER-PWD.
     *     PERFORM READ-USER-SEC-FILE.
     * 
     * READ-USER-SEC-FILE.
     *     EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID) INTO(SEC-USER-DATA) END-EXEC.
     *     IF SEC-USR-PWD = WS-USER-PWD
     *         MOVE WS-USER-ID TO CDEMO-USER-ID
     *         MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *         EXEC CICS XCTL PROGRAM(...) COMMAREA(CARDDEMO-COMMAREA) END-EXEC.
     * </pre>
     * 
     * <p>Request Body Example:
     * <pre>
     * {
     *   "userId": "ADMIN001",
     *   "password": "Admin123"
     * }
     * </pre>
     * 
     * <p>Success Response Example (200 OK):
     * <pre>
     * {
     *   "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
     *   "expiresIn": 3600,
     *   "userId": "ADMIN001",
     *   "userType": "A"
     * }
     * </pre>
     * 
     * <p>Error Response Examples:
     * 
     * <p>400 Bad Request (validation failure):
     * <pre>
     * {
     *   "timestamp": "2024-01-15T10:30:00.000Z",
     *   "status": 400,
     *   "error": "Bad Request",
     *   "message": "User ID is required",
     *   "path": "/api/auth/login"
     * }
     * </pre>
     * 
     * <p>401 Unauthorized (invalid credentials):
     * <pre>
     * {
     *   "timestamp": "2024-01-15T10:30:00.000Z",
     *   "status": 401,
     *   "error": "Unauthorized",
     *   "message": "Invalid credentials",
     *   "path": "/api/auth/login"
     * }
     * </pre>
     * 
     * @param authRequest authentication request containing userId and password (validated by @Valid)
     * @return ResponseEntity with AuthResponse (200 OK) or ErrorResponse (401/400)
     */
    @PostMapping("/login")
    @Operation(
        summary = "User login",
        description = "Authenticates user with userId and password, returns JWT token for authenticated API requests. " +
                     "Converted from COBOL program COSGN00C.cbl signon screen processing."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Authentication successful, JWT token generated",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AuthResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request - missing or invalid userId/password",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Authentication failed - invalid credentials",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = ErrorResponse.class)
            )
        )
    })
    public ResponseEntity<?> login(@Valid @RequestBody AuthRequest authRequest) {
        try {
            logger.debug("Login request received for user: {}", authRequest.getUserId());

            // Call AuthService to authenticate user and generate JWT token
            AuthResponse authResponse = authService.authenticate(authRequest);

            logger.info("User login successful: {}", authRequest.getUserId());

            return ResponseEntity.ok(authResponse);

        } catch (UsernameNotFoundException e) {
            // User not found in database (COBOL: RESP=13)
            // Return generic error message to prevent user enumeration
            logger.warn("Login failed - user not found: {}", authRequest.getUserId());
            
            ErrorResponse errorResponse = ErrorResponse.unauthorized(
                    "Invalid credentials",
                    "User ID or password is incorrect",
                    "/api/auth/login"
            );

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);

        } catch (BadCredentialsException e) {
            // Password does not match (COBOL: SEC-USR-PWD != WS-USER-PWD)
            // Return generic error message to prevent user enumeration
            logger.warn("Login failed - invalid password for user: {}", authRequest.getUserId());
            
            ErrorResponse errorResponse = ErrorResponse.unauthorized(
                    "Invalid credentials",
                    "User ID or password is incorrect",
                    "/api/auth/login"
            );

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);

        } catch (IllegalArgumentException e) {
            // Invalid input parameters (null or empty values)
            logger.error("Login failed - invalid request: {}", e.getMessage());
            
            ErrorResponse errorResponse = ErrorResponse.badRequest(
                    e.getMessage(),
                    "Request validation failed",
                    "/api/auth/login"
            );

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);

        } catch (Exception e) {
            // Unexpected error
            logger.error("Login failed - unexpected error: {}", e.getMessage(), e);
            
            ErrorResponse errorResponse = ErrorResponse.internalError(
                    "An unexpected error occurred during authentication",
                    e.getMessage(),
                    "/api/auth/login"
            );

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * User logout endpoint.
     * 
     * <p>Logs out the current user by invalidating the JWT token (optional implementation).
     * 
     * <p>COBOL Equivalent (COMEN01C.cbl PF3 key handling):
     * <pre>
     * WHEN DFHPF3
     *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *     PERFORM SEND-PLAIN-TEXT
     *     EXEC CICS RETURN END-EXEC
     * </pre>
     * 
     * <p>In JWT stateless authentication, logout is primarily handled client-side by
     * discarding the token. This endpoint can be extended to implement server-side
     * token blacklisting if needed (e.g., storing revoked tokens in Redis cache).
     * 
     * <p>Success Response Example (200 OK):
     * <pre>
     * {
     *   "message": "Logout successful. Thank you for using CardDemo."
     * }
     * </pre>
     * 
     * @param authorization Authorization header containing "Bearer <token>" (optional)
     * @return ResponseEntity with success message (200 OK)
     */
    @PostMapping("/logout")
    @Operation(
        summary = "User logout",
        description = "Logs out the current user, invalidating the JWT token. " +
                     "Converted from COBOL program COMEN01C.cbl PF3 key (logout) handling."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Logout successful",
            content = @Content(mediaType = "application/json")
        )
    })
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            // Extract token from Authorization header (if present)
            String token = null;
            if (authorization != null && authorization.startsWith("Bearer ")) {
                token = authorization.substring(7);
            }

            logger.debug("Logout request received");

            // Call AuthService to handle logout (optional token blacklisting)
            String message = authService.logout(token);

            logger.info("User logout successful");

            return ResponseEntity.ok(new LogoutResponse(message));

        } catch (Exception e) {
            logger.error("Logout failed - unexpected error: {}", e.getMessage(), e);
            
            ErrorResponse errorResponse = ErrorResponse.internalError(
                    "An unexpected error occurred during logout",
                    e.getMessage(),
                    "/api/auth/logout"
            );

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Simple logout response DTO.
     * Inline record for logout success message.
     */
    private record LogoutResponse(String message) {}
}
