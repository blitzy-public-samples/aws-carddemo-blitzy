/*
 * JwtAuthenticationFilter.java
 *
 * JWT authentication filter for stateless REST API security in CardDemo application
 * Replaces CICS transaction-level security and COMMAREA user validation
 *
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
 *
 * Converted from COBOL programs: COSGN00C.cbl
 * Original COBOL COMMAREA: COCOM01Y.cpy (lines 25-28)
 * Original COBOL validation logic:
 *   - COSGN00C.cbl lines 80-96: CICS transaction entry validation
 *   - COSGN00C.cbl lines 221-240: User authentication and COMMAREA population
 *   - CDEMO-USER-ID validation (COCOM01Y.cpy line 25)
 *   - CDEMO-USER-TYPE validation (COCOM01Y.cpy line 26)
 *
 * Conversion Notes:
 * - CICS transaction-level security (EXEC CICS HANDLE CONDITION)
 *   replaced with Spring Security filter chain authentication
 * - COBOL COMMAREA user context (passed between transactions via EXEC CICS RETURN)
 *   replaced with stateless JWT token authentication
 * - RACF security checks (mainframe Resource Access Control Facility)
 *   replaced with JWT signature verification and Spring Security authorization
 * - Each CICS program entry validated COMMAREA user ID and type; this filter
 *   validates JWT token and populates SecurityContextHolder on each HTTP request
 * - Public endpoints (/api/auth/login, /api/health) skip filter execution
 *   matching COBOL logic where COSGN00C allows unauthenticated access
 */
package com.carddemo.security;

import com.carddemo.security.JwtTokenProvider;
import com.carddemo.security.SecurityRoles;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT authentication filter implementing stateless authentication for REST API endpoints.
 * 
 * <p>This filter replaces the CICS transaction-level security enforcement used in the mainframe
 * environment. In the original COBOL system, each CICS transaction entry point validated that
 * the COMMAREA structure contained valid user authentication state (CDEMO-USER-ID and
 * CDEMO-USER-TYPE). This filter provides equivalent functionality by extracting, validating,
 * and populating Spring Security authentication context from JWT tokens on each HTTP request.</p>
 * 
 * <p><b>CICS Transaction Security to JWT Filter Migration:</b></p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL CICS Security</th>
 *     <th>JWT Filter Equivalent</th>
 *     <th>Validation Point</th>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS RECEIVE COMMAREA</td>
 *     <td>Extract JWT from Authorization header</td>
 *     <td>Request entry</td>
 *   </tr>
 *   <tr>
 *     <td>IF CDEMO-USER-ID = SPACES</td>
 *     <td>validateToken() returns false</td>
 *     <td>User identity check</td>
 *   </tr>
 *   <tr>
 *     <td>IF CDEMO-USER-TYPE = SPACES</td>
 *     <td>extractUserType() returns null</td>
 *     <td>User authorization check</td>
 *   </tr>
 *   <tr>
 *     <td>MOVE USER-ID TO WS-CURRENT-USER</td>
 *     <td>SecurityContextHolder.setAuthentication()</td>
 *     <td>Context population</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS ABEND if invalid user</td>
 *     <td>Continue without authentication (SecurityConfig denies access)</td>
 *     <td>Invalid authentication handling</td>
 *   </tr>
 * </table>
 * 
 * <p><b>Filter Execution Flow:</b></p>
 * <ol>
 *   <li><b>Request Interception:</b> Filter executes once per HTTP request before reaching controllers</li>
 *   <li><b>Public Endpoint Check:</b> Skip authentication for /api/auth/login and /api/health endpoints</li>
 *   <li><b>Token Extraction:</b> Extract JWT token from Authorization header (Bearer scheme)</li>
 *   <li><b>Token Validation:</b> Verify token signature and expiration using JwtTokenProvider</li>
 *   <li><b>User ID Extraction:</b> Extract CDEMO-USER-ID equivalent from token subject claim</li>
 *   <li><b>User Type Extraction:</b> Extract CDEMO-USER-TYPE equivalent from token role claim</li>
 *   <li><b>Authority Conversion:</b> Convert user type to Spring Security GrantedAuthority</li>
 *   <li><b>Authentication Creation:</b> Create UsernamePasswordAuthenticationToken with user ID and authorities</li>
 *   <li><b>Context Population:</b> Set authentication in SecurityContextHolder for request scope</li>
 *   <li><b>Filter Chain Continuation:</b> Pass request to next filter or controller</li>
 * </ol>
 * 
 * <p><b>Exception Handling Strategy:</b></p>
 * <p>Unlike COBOL EXEC CICS ABEND for authentication failures, this filter uses graceful degradation:
 * <ul>
 *   <li>Invalid tokens: Log error and proceed without authentication</li>
 *   <li>Expired tokens: Log warning and proceed without authentication</li>
 *   <li>Missing tokens: Silently proceed (may be public endpoint)</li>
 *   <li>SecurityConfig enforces access denial for protected endpoints without authentication</li>
 * </ul>
 * This approach separates authentication (filter) from authorization (SecurityConfig), enabling
 * centralized security policy management and proper HTTP 401/403 status code responses.</p>
 * 
 * <p><b>Security Context Lifetime:</b></p>
 * <p>SecurityContextHolder authentication is request-scoped (thread-local storage). Each HTTP
 * request executes this filter, validates JWT, and populates fresh authentication context.
 * Context is cleared automatically after response completion. This matches COBOL COMMAREA
 * behavior where each CICS transaction received COMMAREA from caller and validated it.</p>
 * 
 * <p><b>Public Endpoints:</b></p>
 * <p>The following endpoints bypass JWT authentication (equivalent to COSGN00C allowing
 * unauthenticated access for login screen):
 * <ul>
 *   <li>/api/auth/login - User authentication endpoint (replaces COSGN00C signon screen)</li>
 *   <li>/api/health - System health check endpoint (for monitoring and load balancers)</li>
 * </ul>
 * All other endpoints require valid JWT token matching COBOL requirement for authenticated
 * COMMAREA on transaction entry.</p>
 * 
 * <p><b>Usage Example:</b></p>
 * <pre>
 * // Client sends HTTP request with JWT token:
 * GET /api/accounts/123456789
 * Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
 * 
 * // Filter extracts and validates token:
 * 1. Extract "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." from Authorization header
 * 2. Remove "Bearer " prefix to get token string
 * 3. Validate token signature and expiration
 * 4. Extract userId = "USER0001" from subject claim
 * 5. Extract role = "ROLE_ADMIN" from role claim
 * 6. Create authentication with userId and [SimpleGrantedAuthority("ROLE_ADMIN")]
 * 7. Set SecurityContextHolder.getContext().setAuthentication(authentication)
 * 8. Continue to AccountController.getAccount(123456789)
 * 
 * // Controller accesses authenticated user:
 * Authentication auth = SecurityContextHolder.getContext().getAuthentication();
 * String userId = auth.getName();  // "USER0001" (replaces CDEMO-USER-ID)
 * Collection authorities = auth.getAuthorities();  // [ROLE_ADMIN] (replaces CDEMO-USER-TYPE check)
 * </pre>
 * 
 * @see JwtTokenProvider
 * @see SecurityRoles
 * @see org.springframework.security.core.context.SecurityContextHolder
 * @see org.springframework.web.filter.OncePerRequestFilter
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Logger for authentication events and security violations.
     * Logs token validation failures, authentication errors, and filter execution flow.
     */
    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /**
     * HTTP header name for authorization token.
     * Standard HTTP Authorization header containing JWT token in Bearer scheme.
     */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * JWT token prefix in Authorization header.
     * Standard Bearer authentication scheme prefix as defined in RFC 6750.
     * Example: "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * JWT token provider for token validation and claim extraction.
     * Injected via constructor by Spring dependency injection.
     * Provides methods to validate tokens, extract user ID, and extract user type.
     */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Constructor for dependency injection.
     * 
     * <p>Spring automatically injects JwtTokenProvider bean instance at application startup.
     * Constructor injection ensures immutability and thread-safety of the filter instance.</p>
     * 
     * @param jwtTokenProvider the JWT token provider for validation and claim extraction.
     *                         Must not be null (enforced by Spring container).
     */
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
        logger.info("JwtAuthenticationFilter initialized successfully. Ready to intercept HTTP requests.");
    }

    /**
     * Performs JWT authentication on each HTTP request.
     * 
     * <p>This method is invoked once per request by Spring Security filter chain. It replaces
     * the COBOL pattern where each CICS transaction entry point validated COMMAREA contents.
     * The method extracts JWT token from Authorization header, validates token integrity and
     * expiration, extracts user claims, and populates Spring Security authentication context.</p>
     * 
     * <p><b>COBOL CICS Transaction Entry Equivalent:</b></p>
     * <pre>
     * COBOL (each CICS program entry - e.g., COACTUPC.cbl):
     *   PROCEDURE DIVISION.
     *   MAIN-PARA.
     *       EXEC CICS RECEIVE COMMAREA(...) END-EXEC.
     *       IF CDEMO-USER-ID = SPACES OR CDEMO-USER-TYPE = SPACES
     *          MOVE 'User not authenticated' TO WS-ERROR-MSG
     *          PERFORM SEND-ERROR-SCREEN
     *          EXEC CICS RETURN END-EXEC
     *       END-IF.
     *       MOVE CDEMO-USER-ID TO WS-CURRENT-USER-ID.
     *       IF CDEMO-USRTYP-ADMIN
     *          PERFORM ADMIN-PROCESSING
     *       ELSE IF CDEMO-USRTYP-USER
     *          PERFORM USER-PROCESSING
     *       END-IF.
     * 
     * Java (this method):
     *   1. Extract JWT token from Authorization header
     *   2. Validate token signature and expiration (replaces COMMAREA validation)
     *   3. Extract userId from subject claim (replaces CDEMO-USER-ID)
     *   4. Extract userType from role claim (replaces CDEMO-USER-TYPE)
     *   5. Create Spring Security authentication (replaces MOVE to WS fields)
     *   6. Set SecurityContextHolder (makes user available to controllers)
     * </pre>
     * 
     * <p><b>Filter Execution Logic:</b></p>
     * <ol>
     *   <li><b>Public Endpoint Check:</b> If request URI is /api/auth/login or /api/health,
     *       skip authentication and continue filter chain (matches COSGN00C allowing unauthenticated
     *       access for login screen)</li>
     *   <li><b>Token Extraction:</b> Get Authorization header value, check for "Bearer " prefix,
     *       extract token string</li>
     *   <li><b>Token Validation:</b> Call jwtTokenProvider.validateToken() to verify signature
     *       and expiration (equivalent to validating CDEMO-USER-ID not SPACES)</li>
     *   <li><b>Claim Extraction:</b> Extract userId (subject claim) and userType (role claim)
     *       from validated token (equivalent to reading CDEMO-USER-ID and CDEMO-USER-TYPE)</li>
     *   <li><b>Authority Creation:</b> Convert userType to SimpleGrantedAuthority using
     *       SecurityRoles constants (equivalent to checking CDEMO-USRTYP-ADMIN condition)</li>
     *   <li><b>Authentication Creation:</b> Create UsernamePasswordAuthenticationToken with
     *       userId as principal, null credentials (stateless), and authorities collection</li>
     *   <li><b>Context Population:</b> Set authentication in SecurityContextHolder making user
     *       identity and authorities available to downstream controllers and services</li>
     *   <li><b>Filter Chain Continuation:</b> Call filterChain.doFilter() to proceed to next
     *       filter or controller endpoint</li>
     * </ol>
     * 
     * <p><b>Exception Handling:</b></p>
     * <p>All JWT validation exceptions are caught and logged. The method proceeds without
     * setting authentication, allowing SecurityConfig to enforce access denial with proper
     * HTTP 401 Unauthorized response. Exceptions handled:
     * <ul>
     *   <li><b>SignatureException:</b> Token signature verification failed (tampered token)</li>
     *   <li><b>ExpiredJwtException:</b> Token expiration time passed (session timeout)</li>
     *   <li><b>UnsupportedJwtException:</b> Token format not supported</li>
     *   <li><b>IllegalArgumentException:</b> Token string invalid or malformed</li>
     *   <li><b>Generic Exception:</b> Any other token processing error</li>
     * </ul>
     * This graceful error handling matches COBOL pattern where invalid COMMAREA results in
     * error screen display rather than transaction ABEND.</p>
     * 
     * <p><b>Security Context Scope:</b></p>
     * <p>Authentication set in SecurityContextHolder is request-scoped. Spring Security
     * automatically clears context after response completion. This matches COBOL COMMAREA
     * lifetime where each transaction received fresh COMMAREA from caller.</p>
     * 
     * @param request the HTTP servlet request being filtered. Contains Authorization header
     *                with JWT token. Must not be null.
     * @param response the HTTP servlet response. Passed through to next filter. Must not be null.
     * @param filterChain the filter chain for continuing request processing after authentication.
     *                    Must not be null.
     * @throws ServletException if servlet-specific error occurs during filtering
     * @throws IOException if I/O error occurs during request/response processing
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                   HttpServletResponse response, 
                                   FilterChain filterChain) 
            throws ServletException, IOException {
        
        String requestUri = request.getRequestURI();
        logger.debug("JWT authentication filter processing request: {} {}", 
                    request.getMethod(), requestUri);

        try {
            // Check if request is for public endpoint (skip authentication)
            // Equivalent to COSGN00C allowing unauthenticated access for login screen
            if (isPublicEndpoint(requestUri)) {
                logger.debug("Public endpoint accessed: {}. Skipping JWT authentication.", requestUri);
                filterChain.doFilter(request, response);
                return;
            }

            // Extract JWT token from Authorization header
            // Replaces EXEC CICS RECEIVE COMMAREA
            String jwtToken = extractJwtFromRequest(request);

            // Validate and process JWT token if present
            if (jwtToken != null) {
                // Validate token signature and expiration
                // Equivalent to checking if CDEMO-USER-ID = SPACES
                if (jwtTokenProvider.validateToken(jwtToken)) {
                    
                    // Extract user ID from token subject claim
                    // Replaces reading CDEMO-USER-ID from COMMAREA
                    String userId = jwtTokenProvider.extractUserId(jwtToken);
                    
                    // Extract user role from token role claim
                    // Replaces reading CDEMO-USER-TYPE from COMMAREA
                    String userRole = jwtTokenProvider.extractUserType(jwtToken);
                    
                    logger.debug("JWT token validated successfully. User ID: {}, Role: {}", 
                               userId, userRole);

                    // Create Spring Security granted authority from user role
                    // Equivalent to checking CDEMO-USRTYP-ADMIN or CDEMO-USRTYP-USER conditions
                    SimpleGrantedAuthority authority = new SimpleGrantedAuthority(userRole);

                    // Create authentication token with user ID and authorities
                    // Replaces MOVE CDEMO-USER-ID TO WS-CURRENT-USER-ID
                    UsernamePasswordAuthenticationToken authentication = 
                        new UsernamePasswordAuthenticationToken(
                            userId,                                 // Principal (user ID)
                            null,                                   // Credentials (null for stateless)
                            Collections.singletonList(authority)    // Authorities (user role)
                        );

                    // Set authentication details from request (IP address, session ID, etc.)
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // Populate Spring Security context with authentication
                    // Makes authenticated user available to controllers via SecurityContextHolder
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    logger.info("User '{}' authenticated successfully with role '{}' for request: {} {}", 
                              userId, userRole, request.getMethod(), requestUri);

                } else {
                    // Token validation failed (invalid signature or expired)
                    // Equivalent to CDEMO-USER-ID = SPACES validation failure
                    logger.warn("JWT token validation failed for request: {} {}. " +
                              "Proceeding without authentication.", 
                              request.getMethod(), requestUri);
                }
            } else {
                // No JWT token found in request
                // May be valid for public endpoints or will be denied by SecurityConfig
                logger.debug("No JWT token found in Authorization header for request: {} {}. " +
                           "Proceeding without authentication.", 
                           request.getMethod(), requestUri);
            }

        } catch (ExpiredJwtException e) {
            // Token has expired (session timeout)
            // Equivalent to CICS session timeout in COBOL system
            logger.warn("Expired JWT token for request: {} {}. User session has timed out. Error: {}", 
                       request.getMethod(), requestUri, e.getMessage());
            
        } catch (SignatureException e) {
            // Token signature verification failed (tampered or invalid token)
            // Security violation - log with elevated severity
            logger.error("JWT signature verification failed for request: {} {}. " +
                       "Possible token tampering detected. Error: {}", 
                       request.getMethod(), requestUri, e.getMessage());
            
        } catch (UnsupportedJwtException e) {
            // Token format not supported
            logger.error("Unsupported JWT token format for request: {} {}. Error: {}", 
                       request.getMethod(), requestUri, e.getMessage());
            
        } catch (IllegalArgumentException e) {
            // Token string is invalid or malformed
            logger.error("Invalid JWT token argument for request: {} {}. Error: {}", 
                       request.getMethod(), requestUri, e.getMessage());
            
        } catch (Exception e) {
            // Catch-all for any other token processing errors
            logger.error("Unexpected error processing JWT token for request: {} {}. Error: {}", 
                       request.getMethod(), requestUri, e.getMessage(), e);
        }

        // Continue filter chain regardless of authentication success
        // SecurityConfig will enforce access denial for protected endpoints
        // This matches COBOL pattern where invalid COMMAREA results in error screen
        // rather than transaction ABEND
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts JWT token from Authorization header of HTTP request.
     * 
     * <p>This method retrieves the Authorization header value and extracts the JWT token string
     * by removing the "Bearer " prefix. This matches the COBOL pattern of extracting user
     * credentials from CICS COMMAREA structure.</p>
     * 
     * <p><b>Expected Header Format:</b></p>
     * <pre>
     * Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJVU0VSMDAwMSIsInJvbGUiOiJST0xFX0FETUlOIn0.signature
     * </pre>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (extracting user from COMMAREA):
     *   EXEC CICS RECEIVE COMMAREA(CARDDEMO-COMMAREA) END-EXEC.
     *   MOVE CDEMO-USER-ID TO WS-CURRENT-USER-ID.
     * 
     * Java (this method):
     *   String token = extractJwtFromRequest(request);
     * </pre>
     * 
     * <p><b>Extraction Logic:</b></p>
     * <ol>
     *   <li>Retrieve Authorization header value from HTTP request</li>
     *   <li>Check if header value is not null and not empty/whitespace-only</li>
     *   <li>Check if header value starts with "Bearer " prefix (case-sensitive per RFC 6750)</li>
     *   <li>Extract token string by removing "Bearer " prefix (7 characters)</li>
     *   <li>Return token string or null if header is missing/invalid</li>
     * </ol>
     * 
     * <p><b>Return Value:</b></p>
     * <ul>
     *   <li>Valid token string if Authorization header present with Bearer scheme</li>
     *   <li>null if Authorization header missing, empty, or doesn't use Bearer scheme</li>
     * </ul>
     * 
     * @param request the HTTP servlet request containing Authorization header.
     *                Must not be null.
     * @return the JWT token string without "Bearer " prefix, or null if header invalid/missing
     */
    private String extractJwtFromRequest(HttpServletRequest request) {
        // Retrieve Authorization header value
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);

        // Validate header is present and contains Bearer token
        // StringUtils.hasText() checks for null, empty, and whitespace-only strings
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            // Extract token by removing "Bearer " prefix (7 characters)
            String token = bearerToken.substring(BEARER_PREFIX.length());
            logger.debug("JWT token extracted successfully from Authorization header");
            return token;
        }

        // Authorization header missing, empty, or not using Bearer scheme
        logger.debug("No Bearer token found in Authorization header");
        return null;
    }

    /**
     * Determines if the request URI corresponds to a public endpoint that does not require authentication.
     * 
     * <p>This method identifies endpoints that should bypass JWT authentication, equivalent to
     * the COBOL COSGN00C signon screen which allows unauthenticated access for login.</p>
     * 
     * <p><b>Public Endpoints:</b></p>
     * <ul>
     *   <li><b>/api/auth/login:</b> User authentication endpoint (replaces COSGN00C.cbl signon screen).
     *       Users must access this endpoint without JWT token to obtain initial token after
     *       successful authentication.</li>
     *   <li><b>/api/health:</b> System health check endpoint for monitoring and load balancers.
     *       Must remain accessible without authentication for operational monitoring.</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (COSGN00C.cbl allows unauthenticated access):
     *   MAIN-PARA.
     *       IF EIBCALEN = 0
     *          MOVE LOW-VALUES TO COSGN0AO
     *          PERFORM SEND-SIGNON-SCREEN
     *       END-IF.
     * 
     * Java (this method):
     *   if (isPublicEndpoint("/api/auth/login")) {
     *       // Skip JWT authentication
     *   }
     * </pre>
     * 
     * <p><b>Security Consideration:</b></p>
     * <p>Public endpoints must be carefully controlled to prevent unauthorized access.
     * Only authentication and health check endpoints should be public. All business
     * logic endpoints (accounts, cards, transactions) must require authentication
     * matching COBOL requirement for valid COMMAREA on transaction entry.</p>
     * 
     * @param requestUri the request URI path to check. Must not be null.
     * @return true if the URI is a public endpoint that bypasses authentication, false otherwise
     */
    private boolean isPublicEndpoint(String requestUri) {
        // Check for login endpoint (replaces COSGN00C unauthenticated access)
        if (requestUri.equals("/api/auth/login")) {
            return true;
        }

        // Check for health check endpoint (monitoring and load balancer access)
        if (requestUri.equals("/api/health")) {
            return true;
        }

        // All other endpoints require JWT authentication
        // Matches COBOL requirement for authenticated COMMAREA on transaction entry
        return false;
    }
}
