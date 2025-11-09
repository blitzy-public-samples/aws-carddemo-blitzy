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

package com.carddemo.security;

import com.carddemo.service.auth.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * JWT Authentication Filter for CardDemo Application
 * 
 * <p>Spring Security OncePerRequestFilter implementation that intercepts every HTTP request to
 * extract JWT tokens from Authorization header, validate token signature and expiration, parse
 * user claims, and populate Spring SecurityContext with authenticated user details enabling
 * stateless authentication for REST API endpoints.</p>
 * 
 * <h2>COBOL Origin - CICS COMMAREA Session State Transformation</h2>
 * <p>This filter replaces CICS pseudo-conversational session management with JWT token-based
 * stateless authentication. In the original mainframe application, user context was propagated
 * across program control transfers via CICS COMMAREA structure defined in COCOM01Y.cpy.</p>
 * 
 * <h3>COBOL Authentication Flow (COSGN00C.cbl lines 209-257)</h3>
 * <pre>
 * READ-USER-SEC-FILE.
 *   EXEC CICS READ
 *     DATASET   (WS-USRSEC-FILE)        -- VSAM KSDS file 'USRSEC'
 *     INTO      (SEC-USER-DATA)         -- CSUSR01Y.cpy structure
 *     RIDFLD    (WS-USER-ID)            -- Primary key lookup by user ID
 *     RESP      (WS-RESP-CD)            -- Response code (0=success, 13=notfound)
 *   END-EXEC.
 *   
 *   IF WS-RESP-CD = 0 AND SEC-USR-PWD = WS-USER-PWD
 *     MOVE WS-USER-ID   TO CDEMO-USER-ID      -- Store in COMMAREA
 *     MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE    -- 'A' or 'U'
 *     
 *     IF CDEMO-USRTYP-ADMIN                   -- 88-level: VALUE 'A'
 *       EXEC CICS XCTL PROGRAM('COADM01C')    -- Admin menu
 *         COMMAREA(CARDDEMO-COMMAREA)         -- Pass context
 *       END-EXEC
 *     ELSE                                     -- CDEMO-USRTYP-USER: VALUE 'U'
 *       EXEC CICS XCTL PROGRAM('COMEN01C')    -- User menu
 *         COMMAREA(CARDDEMO-COMMAREA)         -- Pass context
 *       END-EXEC
 *     END-IF
 *   END-IF
 * </pre>
 * 
 * <h3>COMMAREA Structure (COCOM01Y.cpy lines 19-29)</h3>
 * <pre>
 * 01 CARDDEMO-COMMAREA.
 *    05 CDEMO-GENERAL-INFO.
 *       10 CDEMO-FROM-TRANID             PIC X(04).
 *       10 CDEMO-FROM-PROGRAM            PIC X(08).
 *       10 CDEMO-TO-TRANID               PIC X(04).
 *       10 CDEMO-TO-PROGRAM              PIC X(08).
 *       10 CDEMO-USER-ID                 PIC X(08).    -- User identity
 *       10 CDEMO-USER-TYPE               PIC X(01).    -- Role indicator
 *          88 CDEMO-USRTYP-ADMIN         VALUE 'A'.    -- Admin role
 *          88 CDEMO-USRTYP-USER          VALUE 'U'.    -- Regular user role
 *       10 CDEMO-PGM-CONTEXT             PIC 9(01).
 * </pre>
 * 
 * <h2>JWT Token-Based Authentication Transformation</h2>
 * <p>Instead of storing user context in server-side CICS COMMAREA and propagating it via
 * EXEC CICS XCTL, this filter implements stateless authentication where:</p>
 * <ul>
 *   <li><b>CDEMO-USER-ID</b> → JWT "sub" (subject) claim containing user ID</li>
 *   <li><b>CDEMO-USER-TYPE</b> → JWT "userType" claim with 'A' or 'U' code</li>
 *   <li><b>COMMAREA propagation</b> → JWT token in Authorization header for each request</li>
 *   <li><b>Session validation</b> → Cryptographic signature verification + expiration check</li>
 * </ul>
 * 
 * <h2>Filter Processing Flow</h2>
 * <p>The doFilterInternal method executes for every HTTP request with the following steps:</p>
 * <ol>
 *   <li><b>Token Extraction:</b> Read Authorization header, validate Bearer prefix, extract JWT</li>
 *   <li><b>Token Validation:</b> Invoke JwtService.validateToken() for signature and expiration</li>
 *   <li><b>Claim Extraction:</b> Parse userId and userType from JWT claims</li>
 *   <li><b>User Loading:</b> Call CustomUserDetailsService.loadUserByUsername() for full user details</li>
 *   <li><b>Authentication Setup:</b> Create UsernamePasswordAuthenticationToken with UserDetails</li>
 *   <li><b>SecurityContext Population:</b> Set authentication in SecurityContextHolder</li>
 *   <li><b>Filter Chain Continue:</b> Invoke filterChain.doFilter() for downstream processing</li>
 * </ol>
 * 
 * <h2>Security Features</h2>
 * <ul>
 *   <li><b>Stateless Authentication:</b> No server-side session storage required</li>
 *   <li><b>Horizontal Scalability:</b> Tokens contain all auth context, enabling load balancing</li>
 *   <li><b>Cryptographic Security:</b> HMAC-SHA512 signature prevents token tampering</li>
 *   <li><b>Automatic Expiration:</b> 24-hour token lifetime enforces re-authentication</li>
 *   <li><b>Role-Based Access:</b> GrantedAuthority collection enables @PreAuthorize checks</li>
 * </ul>
 * 
 * <h2>Integration Points</h2>
 * <ul>
 *   <li><b>SecurityConfig:</b> Registers this filter before UsernamePasswordAuthenticationFilter</li>
 *   <li><b>JwtService:</b> Provides token validation and claim extraction</li>
 *   <li><b>CustomUserDetailsService:</b> Loads full UserDetails from database</li>
 *   <li><b>JwtAuthenticationEntryPoint:</b> Handles authentication failures (401 responses)</li>
 * </ul>
 * 
 * <h2>Error Handling Strategy</h2>
 * <p>Token validation failures (invalid signature, expired, malformed, missing) are logged but
 * do NOT throw exceptions. Instead, the request proceeds unauthenticated, allowing downstream
 * filters and ExceptionTranslationFilter to invoke JwtAuthenticationEntryPoint for proper
 * 401 HTTP response generation.</p>
 * 
 * <h2>Thread Safety</h2>
 * <p>This filter is thread-safe as a Spring singleton. Each doFilterInternal invocation operates
 * on its own request, response, and SecurityContext instances, with no shared mutable state.</p>
 * 
 * @see com.carddemo.service.auth.JwtService
 * @see com.carddemo.security.CustomUserDetailsService
 * @see com.carddemo.security.SecurityConstants
 * @see com.carddemo.security.JwtAuthenticationEntryPoint
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * JWT service for token validation and claim extraction.
     * Injected via constructor by Lombok @RequiredArgsConstructor.
     */
    private final JwtService jwtService;

    /**
     * Spring Security UserDetailsService for loading user credentials from database.
     * Injected via constructor by Lombok @RequiredArgsConstructor.
     */
    private final CustomUserDetailsService customUserDetailsService;

    /**
     * Filter internal processing method executed once per HTTP request.
     * 
     * <p>This method implements the complete JWT authentication flow, replacing CICS COMMAREA
     * validation with stateless token verification. It extracts the JWT token from the
     * Authorization header, validates it, loads user details, and populates the Spring
     * SecurityContext for downstream authorization checks.</p>
     * 
     * <h3>Processing Steps</h3>
     * <ol>
     *   <li><b>Extract Token:</b> Call extractJwtFromRequest() to get Bearer token from header</li>
     *   <li><b>Validate Token:</b> If token present, invoke jwtService.validateToken()</li>
     *   <li><b>Parse Claims:</b> Extract userId via jwtService.extractUserId()</li>
     *   <li><b>Load User:</b> Call customUserDetailsService.loadUserByUsername(userId)</li>
     *   <li><b>Create Authentication:</b> Build UsernamePasswordAuthenticationToken with UserDetails</li>
     *   <li><b>Set Details:</b> Attach request metadata via WebAuthenticationDetailsSource</li>
     *   <li><b>Populate Context:</b> Set authentication in SecurityContextHolder</li>
     *   <li><b>Continue Chain:</b> Always invoke filterChain.doFilter() whether auth succeeded or not</li>
     * </ol>
     * 
     * <h3>COBOL Equivalent</h3>
     * <p>This method replicates the CICS automatic session context validation that occurs on
     * every EXEC CICS XCTL or EXEC CICS RETURN TRANSID. In COBOL programs, accessing
     * CDEMO-USER-ID and CDEMO-USER-TYPE from LINKAGE SECTION DFHCOMMAREA always provides
     * valid user context. This filter ensures SecurityContextHolder.getContext().getAuthentication()
     * provides equivalent context for Java controllers and services.</p>
     * 
     * <h3>Error Handling</h3>
     * <p>Token validation failures are logged at WARN level but do NOT prevent the filter
     * chain from continuing. Unauthenticated requests proceed to controllers where
     * @PreAuthorize annotations or Spring Security's automatic protection will trigger
     * 401 responses via JwtAuthenticationEntryPoint.</p>
     * 
     * @param request HTTP request containing potential Authorization header with JWT token
     * @param response HTTP response (passed through unchanged)
     * @param filterChain Spring Security filter chain for invoking downstream filters
     * @throws ServletException if servlet-level error occurs during filter processing
     * @throws IOException if I/O error occurs reading request or writing response
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        
        try {
            log.debug("Processing authentication filter for request: {} {}", 
                    request.getMethod(), request.getRequestURI());
            
            // Step 1: Extract JWT token from Authorization header
            Optional<String> jwtToken = extractJwtFromRequest(request);
            
            if (jwtToken.isPresent()) {
                String token = jwtToken.get();
                log.debug("JWT token extracted from request");
                
                // Step 2: Validate token signature and expiration
                if (jwtService.validateToken(token)) {
                    log.debug("JWT token validation successful");
                    
                    try {
                        // Step 3: Extract user ID from token claims
                        String userId = jwtService.extractUserId(token);
                        log.debug("Extracted userId from token: {}", userId);
                        
                        // Step 4: Load full user details from database
                        // This replicates the CICS READ of USRSEC file in COSGN00C.cbl
                        UserDetails userDetails = customUserDetailsService.loadUserByUsername(userId);
                        log.debug("UserDetails loaded for userId: {}", userId);
                        
                        // Step 5: Create authentication token with user details and authorities
                        // Equivalent to setting CDEMO-USER-ID and CDEMO-USER-TYPE in COMMAREA
                        UsernamePasswordAuthenticationToken authenticationToken = 
                                new UsernamePasswordAuthenticationToken(
                                        userDetails,
                                        null,  // No credentials needed for token-based auth
                                        userDetails.getAuthorities()  // ROLE_ADMIN or ROLE_USER
                                );
                        
                        // Step 6: Set request details (IP address, session ID) for audit logging
                        authenticationToken.setDetails(
                                new WebAuthenticationDetailsSource().buildDetails(request)
                        );
                        
                        // Step 7: Populate Spring Security context with authenticated user
                        // This makes user context available to downstream components via
                        // SecurityContextHolder.getContext().getAuthentication() calls,
                        // equivalent to COBOL programs accessing CDEMO-USER-ID and 
                        // CDEMO-USER-TYPE from LINKAGE SECTION DFHCOMMAREA
                        SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                        
                        log.info("Authentication successful for user: {} with authorities: {}", 
                                userId, userDetails.getAuthorities());
                        
                    } catch (Exception e) {
                        // Log claim extraction or user loading errors but continue without auth
                        log.warn("Failed to process JWT token claims or load user: {}", 
                                e.getMessage());
                        log.debug("Token processing exception details", e);
                    }
                    
                } else {
                    // Token validation failed (invalid signature, expired, malformed)
                    log.warn("JWT token validation failed for request: {} {}", 
                            request.getMethod(), request.getRequestURI());
                }
                
            } else {
                // No token present in request - this is expected for public endpoints
                log.debug("No JWT token found in Authorization header for request: {} {}", 
                        request.getMethod(), request.getRequestURI());
            }
            
        } catch (Exception e) {
            // Catch any unexpected exceptions to prevent filter chain interruption
            log.error("Unexpected error in JWT authentication filter: {}", e.getMessage());
            log.debug("JWT filter exception details", e);
        }
        
        // Step 8: Always continue the filter chain whether authentication succeeded or not
        // Unauthenticated requests will be handled by Spring Security's authorization layer
        // and JwtAuthenticationEntryPoint will generate 401 responses if needed
        filterChain.doFilter(request, response);
    }

    /**
     * Extract JWT token from HTTP Authorization header.
     * 
     * <p>Reads the Authorization header, validates it starts with "Bearer " prefix per
     * RFC 6750 OAuth 2.0 Bearer Token Usage specification, and extracts the raw JWT
     * token string.</p>
     * 
     * <h3>Expected Header Format</h3>
     * <pre>
     * Authorization: Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJ1c2VyMTIzIiwidXNlclR5cGUi...
     * </pre>
     * 
     * <h3>COBOL Equivalent</h3>
     * <p>This method replaces the implicit CICS session context availability. In COBOL
     * programs, the COMMAREA is automatically available in LINKAGE SECTION. This method
     * makes the JWT token (containing equivalent session context) available for validation.</p>
     * 
     * <h3>Validation Rules</h3>
     * <ul>
     *   <li>Authorization header must be present</li>
     *   <li>Header value must start with SecurityConstants.TOKEN_PREFIX ("Bearer ")</li>
     *   <li>Token string after prefix must not be empty or whitespace-only</li>
     * </ul>
     * 
     * @param request HTTP request containing potential Authorization header
     * @return Optional containing JWT token string if valid Bearer token present, empty otherwise
     */
    private Optional<String> extractJwtFromRequest(HttpServletRequest request) {
        try {
            // Read Authorization header (SecurityConstants.HEADER_STRING = "Authorization")
            String bearerToken = request.getHeader(SecurityConstants.HEADER_STRING);
            
            log.debug("Authorization header value present: {}", bearerToken != null);
            
            // Validate header exists and starts with Bearer prefix
            if (StringUtils.hasText(bearerToken) && 
                    bearerToken.startsWith(SecurityConstants.TOKEN_PREFIX)) {
                
                // Extract token by removing "Bearer " prefix (SecurityConstants.TOKEN_PREFIX)
                String token = bearerToken.substring(SecurityConstants.TOKEN_PREFIX.length());
                
                // Validate extracted token is not empty
                if (StringUtils.hasText(token)) {
                    log.debug("Successfully extracted JWT token from Authorization header");
                    return Optional.of(token);
                } else {
                    log.debug("Token string is empty after removing Bearer prefix");
                    return Optional.empty();
                }
            }
            
            log.debug("Authorization header missing or does not contain Bearer token");
            return Optional.empty();
            
        } catch (Exception e) {
            log.warn("Error extracting JWT token from request header: {}", e.getMessage());
            log.debug("Token extraction exception details", e);
            return Optional.empty();
        }
    }
}
