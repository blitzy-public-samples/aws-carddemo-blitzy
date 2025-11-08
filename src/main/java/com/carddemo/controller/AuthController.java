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

import com.carddemo.dto.request.LoginRequest;
import com.carddemo.dto.response.LoginResponse;
import com.carddemo.service.auth.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API Controller for Authentication Operations.
 * 
 * <p>This controller transforms the CICS COBOL program COSGN00C.cbl (CC00 signon transaction)
 * into modern Spring Boot REST endpoints, replacing 3270 terminal screen authentication with
 * stateless JWT-based authentication for web and mobile clients.</p>
 * 
 * <h2>COBOL Program Transformation</h2>
 * <ul>
 *   <li><b>Source Program:</b> app/cbl/COSGN00C.cbl</li>
 *   <li><b>CICS Transaction:</b> CC00 (Signon Screen)</li>
 *   <li><b>BMS Mapset:</b> COSGN00M.bms (COSGN0A map)</li>
 *   <li><b>COMMAREA Structure:</b> COCOM01Y.cpy (session state)</li>
 *   <li><b>User Security File:</b> CSUSR01Y.cpy (VSAM USRSEC file)</li>
 * </ul>
 * 
 * <h2>Key Transformations</h2>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Pattern</th>
 *     <th>Spring Boot Equivalent</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS SEND MAP('COSGN0A')</td>
 *     <td>GET /api/auth/login (returns login form)</td>
 *     <td>Display replaced by React component</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS RECEIVE MAP('COSGN0A')</td>
 *     <td>POST /api/auth/login with @RequestBody</td>
 *     <td>Form submission replaced by JSON POST</td>
 *   </tr>
 *   <tr>
 *     <td>PROCESS-ENTER-KEY paragraph</td>
 *     <td>login() method with Bean Validation</td>
 *     <td>Validation logic extracted to @Valid annotations</td>
 *   </tr>
 *   <tr>
 *     <td>READ-USER-SEC-FILE paragraph</td>
 *     <td>AuthenticationService.authenticate()</td>
 *     <td>VSAM READ replaced by JPA repository query</td>
 *   </tr>
 *   <tr>
 *     <td>IF SEC-USR-PWD = WS-USER-PWD</td>
 *     <td>PasswordEncoder.matches()</td>
 *     <td>Plain text comparison replaced by BCrypt</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS XCTL PROGRAM('COADM01C' or 'COMEN01C')</td>
 *     <td>LoginResponse with JWT token</td>
 *     <td>Program control transfer replaced by stateless token</td>
 *   </tr>
 *   <tr>
 *     <td>COMMAREA state preservation</td>
 *     <td>JWT token with userId and userType claims</td>
 *     <td>Server-side session replaced by client-side token</td>
 *   </tr>
 * </table>
 * 
 * <h2>Authentication Flow</h2>
 * <ol>
 *   <li>Client sends POST /api/auth/login with userId and password</li>
 *   <li>Spring @Valid annotation triggers Bean Validation on LoginRequest</li>
 *   <li>Controller delegates to AuthenticationService.authenticate()</li>
 *   <li>Service queries User entity from PostgreSQL (replaces VSAM USRSEC file)</li>
 *   <li>Service verifies password using BCrypt (replaces plain text comparison)</li>
 *   <li>Service generates JWT token with userId and userType claims</li>
 *   <li>Service sets Spring Security context with granted authorities</li>
 *   <li>Controller returns LoginResponse with HTTP 200 OK</li>
 *   <li>Client stores JWT token and includes in Authorization header for subsequent requests</li>
 *   <li>JwtAuthenticationFilter validates token on protected endpoint access</li>
 * </ol>
 * 
 * <h2>Error Handling</h2>
 * <p>All exceptions are caught by GlobalExceptionHandler and converted to standardized error responses:</p>
 * <ul>
 *   <li><b>MethodArgumentNotValidException:</b> HTTP 400 Bad Request (validation failures)</li>
 *   <li><b>UsernameNotFoundException:</b> HTTP 401 Unauthorized (user not found)</li>
 *   <li><b>BadCredentialsException:</b> HTTP 401 Unauthorized (wrong password)</li>
 *   <li><b>DisabledException:</b> HTTP 401 Unauthorized (account deleted/disabled)</li>
 *   <li><b>AuthenticationException:</b> HTTP 401 Unauthorized (generic auth failure)</li>
 * </ul>
 * 
 * <h2>Security Considerations</h2>
 * <ul>
 *   <li>This is the ONLY controller endpoint accessible without JWT authentication</li>
 *   <li>SecurityConfig allows permitAll() for /api/auth/** paths</li>
 *   <li>All other endpoints require valid JWT token in Authorization header</li>
 *   <li>Passwords are transmitted over HTTPS only (plain text over encrypted channel)</li>
 *   <li>Passwords are never logged or exposed in responses</li>
 *   <li>JWT tokens are signed to prevent tampering</li>
 *   <li>Token expiration is enforced (default 24 hours)</li>
 *   <li>SecurityContext is set for role-based authorization in subsequent requests</li>
 * </ul>
 * 
 * <h2>COBOL Error Message Mapping</h2>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Error (COSGN00C.cbl)</th>
 *     <th>Line</th>
 *     <th>Spring Boot Response</th>
 *   </tr>
 *   <tr>
 *     <td>"Please enter User ID ..."</td>
 *     <td>120</td>
 *     <td>HTTP 400: "User ID is required and cannot be blank"</td>
 *   </tr>
 *   <tr>
 *     <td>"Please enter Password ..."</td>
 *     <td>125</td>
 *     <td>HTTP 400: "Password is required and cannot be blank"</td>
 *   </tr>
 *   <tr>
 *     <td>"User not found. Try again ..."</td>
 *     <td>249</td>
 *     <td>HTTP 401: "User not found: {userId}"</td>
 *   </tr>
 *   <tr>
 *     <td>"Wrong Password. Try again ..."</td>
 *     <td>242</td>
 *     <td>HTTP 401: "Wrong password"</td>
 *   </tr>
 *   <tr>
 *     <td>"Unable to verify the User ..."</td>
 *     <td>254</td>
 *     <td>HTTP 401: "Unable to verify user"</td>
 *   </tr>
 * </table>
 * 
 * <h2>API Documentation</h2>
 * <p>This controller includes comprehensive OpenAPI 3.0 annotations for Swagger UI documentation.
 * Access the interactive API documentation at: <code>http://localhost:8080/swagger-ui.html</code></p>
 * 
 * @see AuthenticationService for authentication business logic
 * @see LoginRequest input DTO replacing BMS COSGN00 screen input
 * @see LoginResponse output DTO replacing COMMAREA output structure
 * @see com.carddemo.security.JwtAuthenticationFilter for token validation
 * @see com.carddemo.config.SecurityConfig for security configuration
 * @see com.carddemo.exception.GlobalExceptionHandler for error handling
 * 
 * @version 1.0
 * @since 1.0
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(
    name = "Authentication", 
    description = "User authentication and session management endpoints replacing CICS CC00 signon transaction"
)
public class AuthController {

    /**
     * Authentication service for credential validation and JWT token generation.
     * 
     * <p>Injected via constructor using Lombok @RequiredArgsConstructor annotation.
     * This follows Spring Boot best practices for dependency injection, enabling
     * immutability and easier unit testing compared to field injection.</p>
     * 
     * <p><b>COBOL Equivalent:</b> PERFORM READ-USER-SEC-FILE paragraph (lines 209-257)</p>
     */
    private final AuthenticationService authenticationService;

    /**
     * Authenticates user credentials and returns JWT token for stateless session management.
     * 
     * <p>This endpoint replaces the CICS CC00 transaction processing from COSGN00C.cbl,
     * transforming the mainframe 3270 terminal signon screen into a modern REST API
     * endpoint for web and mobile client authentication.</p>
     * 
     * <h3>COBOL Flow (COSGN00C.cbl lines 108-257)</h3>
     * <pre>
     * PROCESS-ENTER-KEY.
     *     EXEC CICS RECEIVE MAP('COSGN0A') MAPSET('COSGN00')
     *     MOVE USERIDI OF COSGN0AI TO WS-USER-ID
     *     MOVE PASSWDI OF COSGN0AI TO WS-USER-PWD
     *     
     *     IF USERIDI = SPACES OR LOW-VALUES
     *         MOVE 'Please enter User ID ...' TO WS-MESSAGE
     *     ELSE IF PASSWDI = SPACES OR LOW-VALUES
     *         MOVE 'Please enter Password ...' TO WS-MESSAGE
     *     ELSE
     *         PERFORM READ-USER-SEC-FILE
     * 
     * READ-USER-SEC-FILE.
     *     EXEC CICS READ FILE('USRSEC') INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID)
     *     IF RESP-CD = DFHRESP(NORMAL)
     *         IF SEC-USR-PWD = WS-USER-PWD
     *             MOVE SEC-USR-ID TO CDEMO-USER-ID
     *             MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *             IF CDEMO-USRTYP-ADMIN
     *                 EXEC CICS XCTL PROGRAM('COADM01C') COMMAREA(CARDDEMO-COMMAREA)
     *             ELSE
     *                 EXEC CICS XCTL PROGRAM('COMEN01C') COMMAREA(CARDDEMO-COMMAREA)
     *         ELSE
     *             MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *     ELSE IF RESP-CD = DFHRESP(NOTFND)
     *         MOVE 'User not found. Try again ...' TO WS-MESSAGE
     * </pre>
     * 
     * <h3>Spring Boot Flow</h3>
     * <ol>
     *   <li>Client sends POST request with JSON body containing userId and password</li>
     *   <li>@Valid annotation triggers Jakarta Bean Validation on LoginRequest:
     *       <ul>
     *         <li>@NotBlank ensures userId is not empty (replaces COBOL line 118 check)</li>
     *         <li>@NotBlank ensures password is not empty (replaces COBOL line 123 check)</li>
     *         <li>@Size(min=1, max=8) enforces COBOL PIC X(08) field length</li>
     *       </ul>
     *   </li>
     *   <li>If validation fails, MethodArgumentNotValidException thrown → HTTP 400 Bad Request</li>
     *   <li>If validation passes, authenticationService.authenticate() is invoked</li>
     *   <li>Service queries User entity from PostgreSQL (replaces VSAM USRSEC file read)</li>
     *   <li>Service verifies password using BCrypt (replaces plain text comparison)</li>
     *   <li>Service generates JWT token with userId and userType claims (replaces COMMAREA)</li>
     *   <li>Service updates User.lastLoginDate for audit trail</li>
     *   <li>Service sets Spring Security context with granted authorities</li>
     *   <li>Controller returns LoginResponse with HTTP 200 OK</li>
     * </ol>
     * 
     * <h3>Request Body Example</h3>
     * <pre>
     * POST /api/auth/login
     * Content-Type: application/json
     * 
     * {
     *   "userId": "USER0001",
     *   "password": "pass1234"
     * }
     * </pre>
     * 
     * <h3>Success Response Example (HTTP 200 OK)</h3>
     * <pre>
     * {
     *   "jwtToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJVU0VSMDAwMSIsInVzZXJUeXBlIjoiQURNSU4iLCJpYXQiOjE2MzQ1NjAwMDAsImV4cCI6MTYzNDY0NjQwMH0.abcdef123456...",
     *   "userId": "USER0001",
     *   "userType": "ADMIN",
     *   "expiresAt": "2024-01-16T12:00:00"
     * }
     * </pre>
     * 
     * <h3>Error Response Example (HTTP 401 Unauthorized)</h3>
     * <pre>
     * {
     *   "timestamp": "2024-01-15T12:00:00.000+00:00",
     *   "status": 401,
     *   "error": "Unauthorized",
     *   "message": "Wrong password",
     *   "path": "/api/auth/login"
     * }
     * </pre>
     * 
     * <h3>Validation Error Response Example (HTTP 400 Bad Request)</h3>
     * <pre>
     * {
     *   "timestamp": "2024-01-15T12:00:00.000+00:00",
     *   "status": 400,
     *   "error": "Bad Request",
     *   "message": "Validation failed for object='loginRequest'",
     *   "path": "/api/auth/login",
     *   "fieldErrors": [
     *     {
     *       "field": "userId",
     *       "message": "User ID is required and cannot be blank"
     *     }
     *   ]
     * }
     * </pre>
     * 
     * <h3>Client Integration Pattern</h3>
     * <pre>
     * // JavaScript/React example
     * async function login(userId, password) {
     *   const response = await fetch('/api/auth/login', {
     *     method: 'POST',
     *     headers: { 'Content-Type': 'application/json' },
     *     body: JSON.stringify({ userId, password })
     *   });
     *   
     *   if (response.ok) {
     *     const data = await response.json();
     *     localStorage.setItem('jwtToken', data.jwtToken);
     *     axios.defaults.headers.common['Authorization'] = `Bearer ${data.jwtToken}`;
     *     return data;
     *   } else {
     *     const error = await response.json();
     *     throw new Error(error.message);
     *   }
     * }
     * </pre>
     * 
     * @param loginRequest Login credentials containing userId and password (validated by @Valid)
     * @return ResponseEntity containing LoginResponse with JWT token and user details
     * @throws MethodArgumentNotValidException if loginRequest validation fails (HTTP 400)
     * @throws UsernameNotFoundException if user not found in database (HTTP 401)
     * @throws BadCredentialsException if password verification fails (HTTP 401)
     * @throws DisabledException if user account is deleted or disabled (HTTP 401)
     */
    @PostMapping("/login")
    @Operation(
        summary = "Authenticate user and generate JWT token",
        description = "Validates user credentials against PostgreSQL database (migrated from VSAM USRSEC file) " +
                     "and returns JWT token for stateless session management. Replaces CICS CC00 signon transaction " +
                     "from COSGN00C.cbl with modern REST API authentication supporting web and mobile clients."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Authentication successful - JWT token generated",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = LoginResponse.class),
                examples = @ExampleObject(
                    name = "Successful Authentication",
                    summary = "Admin user login successful",
                    value = """
                        {
                          "jwtToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJVU0VSMDAwMSIsInVzZXJUeXBlIjoiQURNSU4iLCJpYXQiOjE2MzQ1NjAwMDAsImV4cCI6MTYzNDY0NjQwMH0.abcdef123456",
                          "userId": "USER0001",
                          "userType": "ADMIN",
                          "expiresAt": "2024-01-16T12:00:00"
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Bad Request - Validation failure on input fields",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    name = "Validation Error",
                    summary = "Empty userId or password",
                    value = """
                        {
                          "timestamp": "2024-01-15T12:00:00.000+00:00",
                          "status": 400,
                          "error": "Bad Request",
                          "message": "Validation failed for object='loginRequest'",
                          "path": "/api/auth/login",
                          "fieldErrors": [
                            {
                              "field": "userId",
                              "message": "User ID is required and cannot be blank"
                            }
                          ]
                        }
                        """
                )
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Invalid credentials, user not found, or account disabled",
            content = @Content(
                mediaType = "application/json",
                examples = {
                    @ExampleObject(
                        name = "User Not Found",
                        summary = "User ID does not exist in database",
                        value = """
                            {
                              "timestamp": "2024-01-15T12:00:00.000+00:00",
                              "status": 401,
                              "error": "Unauthorized",
                              "message": "User not found: USER9999",
                              "path": "/api/auth/login"
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "Wrong Password",
                        summary = "Password verification failed",
                        value = """
                            {
                              "timestamp": "2024-01-15T12:00:00.000+00:00",
                              "status": 401,
                              "error": "Unauthorized",
                              "message": "Wrong password",
                              "path": "/api/auth/login"
                            }
                            """
                    ),
                    @ExampleObject(
                        name = "Account Disabled",
                        summary = "User account is deleted or disabled",
                        value = """
                            {
                              "timestamp": "2024-01-15T12:00:00.000+00:00",
                              "status": 401,
                              "error": "Unauthorized",
                              "message": "User account is disabled or deleted",
                              "path": "/api/auth/login"
                            }
                            """
                    )
                }
            )
        )
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        // Log authentication attempt (userId only, never log password)
        // Replaces COBOL: MOVE WS-USER-ID TO log output
        log.info("Authentication request received for userId: {}", loginRequest.getUserId());

        // Delegate authentication to service layer
        // This encapsulates all business logic from COBOL READ-USER-SEC-FILE paragraph
        // including database query, password verification, JWT generation, and SecurityContext setup
        LoginResponse loginResponse = authenticationService.authenticate(loginRequest);

        // Log successful authentication (userId and userType, but not JWT token)
        log.info("Authentication successful for userId: {} with userType: {}", 
                 loginResponse.getUserId(), 
                 loginResponse.getUserType());

        // Return HTTP 200 OK with LoginResponse containing JWT token
        // Replaces COBOL: EXEC CICS XCTL PROGRAM('COADM01C' or 'COMEN01C') COMMAREA(CARDDEMO-COMMAREA)
        // Client will use JWT token in Authorization header for subsequent requests instead of
        // server-side CICS XCTL program control transfer
        return ResponseEntity.ok(loginResponse);
    }

    /**
     * Logs out the currently authenticated user by clearing Spring Security context.
     * 
     * <p>In stateless JWT-based authentication, logout is primarily a client-side operation
     * where the client discards the JWT token from storage (localStorage, sessionStorage, or memory).
     * This endpoint clears the server-side SecurityContext for the current request thread.</p>
     * 
     * <h3>COBOL Equivalent</h3>
     * <pre>
     * EXEC CICS RETURN
     *     TRANSID(' ')  [Clear transaction ID, effectively ending CICS session]
     * END-EXEC
     * </pre>
     * 
     * <h3>Logout Flow</h3>
     * <ol>
     *   <li>Client sends POST /api/auth/logout with Authorization header containing JWT token</li>
     *   <li>JwtAuthenticationFilter validates token and sets SecurityContext (pre-controller)</li>
     *   <li>Controller extracts authenticated userId from SecurityContext</li>
     *   <li>Controller delegates to authenticationService.logout(userId)</li>
     *   <li>Service clears SecurityContext for current request thread</li>
     *   <li>Controller returns HTTP 204 No Content (successful logout)</li>
     *   <li>Client removes JWT token from storage and redirects to login page</li>
     * </ol>
     * 
     * <h3>Important Notes</h3>
     * <ul>
     *   <li><b>Stateless Nature:</b> JWT tokens remain valid until expiration even after logout.
     *       For immediate token invalidation, implement a token blacklist using Redis cache
     *       with TTL matching token expiration time (future enhancement).</li>
     *   <li><b>Client Responsibility:</b> Client MUST remove JWT token from storage after receiving
     *       HTTP 204 response. Keeping the token allows continued API access until expiration.</li>
     *   <li><b>SecurityContext Scope:</b> SecurityContextHolder.clearContext() only affects the
     *       current request thread, not other active requests with the same token.</li>
     * </ul>
     * 
     * <h3>Request Example</h3>
     * <pre>
     * POST /api/auth/logout
     * Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     * </pre>
     * 
     * <h3>Success Response (HTTP 204 No Content)</h3>
     * <pre>
     * [Empty response body]
     * </pre>
     * 
     * <h3>Error Response Example (HTTP 401 Unauthorized - Invalid Token)</h3>
     * <pre>
     * {
     *   "timestamp": "2024-01-15T12:00:00.000+00:00",
     *   "status": 401,
     *   "error": "Unauthorized",
     *   "message": "Invalid or expired JWT token",
     *   "path": "/api/auth/logout"
     * }
     * </pre>
     * 
     * <h3>Client Integration Pattern</h3>
     * <pre>
     * // JavaScript/React example
     * async function logout() {
     *   const token = localStorage.getItem('jwtToken');
     *   
     *   try {
     *     await fetch('/api/auth/logout', {
     *       method: 'POST',
     *       headers: { 
     *         'Authorization': `Bearer ${token}`
     *       }
     *     });
     *   } finally {
     *     // Always remove token regardless of server response
     *     // (network errors, server unavailable, etc.)
     *     localStorage.removeItem('jwtToken');
     *     delete axios.defaults.headers.common['Authorization'];
     *     window.location.href = '/login';
     *   }
     * }
     * </pre>
     * 
     * @return ResponseEntity with HTTP 204 No Content (successful logout, no response body)
     * @throws AuthenticationException if SecurityContext authentication is null (should not occur
     *         as JwtAuthenticationFilter validates token before reaching controller)
     */
    @PostMapping("/logout")
    @Operation(
        summary = "Log out authenticated user",
        description = "Clears Spring Security context for the current user session. In stateless JWT authentication, " +
                     "this primarily notifies the server of logout intent while the client is responsible for " +
                     "discarding the JWT token from storage. For immediate token invalidation, consider implementing " +
                     "a token blacklist using Redis cache with TTL matching token expiration."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "204",
            description = "Logout successful - SecurityContext cleared (client must discard JWT token)",
            content = @Content(mediaType = "application/json")
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Invalid or expired JWT token in Authorization header",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    name = "Invalid Token",
                    summary = "JWT token validation failed",
                    value = """
                        {
                          "timestamp": "2024-01-15T12:00:00.000+00:00",
                          "status": 401,
                          "error": "Unauthorized",
                          "message": "Invalid or expired JWT token",
                          "path": "/api/auth/logout"
                        }
                        """
                )
            )
        )
    })
    public ResponseEntity<Void> logout() {
        // Extract authenticated user from SecurityContext
        // SecurityContext is populated by JwtAuthenticationFilter before reaching this controller
        // Replaces COBOL: CDEMO-USER-ID from COMMAREA
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        String userId = null;
        if (authentication != null && authentication.isAuthenticated()) {
            userId = authentication.getName();  // Principal (userId) from JWT token
            log.info("Logout request received for userId: {}", userId);
        } else {
            // Defensive programming: Should not reach here as JwtAuthenticationFilter validates token
            // If authentication is null, token validation failed before reaching controller
            log.warn("Logout request received but no authenticated user found in SecurityContext");
        }

        // Delegate to service to clear SecurityContext
        // Service performs: SecurityContextHolder.clearContext()
        // Replaces COBOL: EXEC CICS RETURN (ending CICS transaction session)
        authenticationService.logout(userId);

        log.info("Logout successful for userId: {}", userId);

        // Return HTTP 204 No Content (successful operation, no response body)
        // Client must remove JWT token from storage to complete logout
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
