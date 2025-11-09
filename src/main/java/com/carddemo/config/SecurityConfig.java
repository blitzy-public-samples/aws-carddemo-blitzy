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

package com.carddemo.config;

import com.carddemo.security.CustomUserDetailsService;
import com.carddemo.security.JwtAuthenticationEntryPoint;
import com.carddemo.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security Configuration for CardDemo Application
 * 
 * <p>This configuration class replaces RACF security controls from the mainframe COBOL/CICS
 * environment with Spring Security authentication and authorization. It transforms the security
 * model from the USRSEC VSAM file to database-backed JWT token authentication while preserving
 * the exact two-tier role hierarchy (Admin/User) defined in the original system.</p>
 * 
 * <h2>COBOL Security Model Transformation</h2>
 * 
 * <h3>Original RACF USRSEC File Structure (CSUSR01Y.cpy)</h3>
 * <pre>
 * 01 SEC-USER-DATA.
 *    05 SEC-USR-ID                 PIC X(08).     -- User identifier (primary key)
 *    05 SEC-USR-FNAME              PIC X(20).     -- First name
 *    05 SEC-USR-LNAME              PIC X(20).     -- Last name
 *    05 SEC-USR-PWD                PIC X(08).     -- Plain text password (8 chars)
 *    05 SEC-USR-TYPE               PIC X(01).     -- User role indicator
 *    05 SEC-USR-FILLER             PIC X(23).     -- Reserved space
 * </pre>
 * 
 * <h3>COBOL User Type Definitions (COCOM01Y.cpy lines 27-28)</h3>
 * <pre>
 * 10 CDEMO-USER-TYPE               PIC X(01).
 *    88 CDEMO-USRTYP-ADMIN         VALUE 'A'.     -- Administrative user
 *    88 CDEMO-USRTYP-USER          VALUE 'U'.     -- Regular user
 * </pre>
 * 
 * <h3>COBOL Authentication Flow (COSGN00C.cbl lines 209-257)</h3>
 * <pre>
 * READ-USER-SEC-FILE.
 *   EXEC CICS READ
 *     DATASET   (WS-USRSEC-FILE)        -- VSAM KSDS 'USRSEC'
 *     INTO      (SEC-USER-DATA)         -- CSUSR01Y structure
 *     RIDFLD    (WS-USER-ID)            -- Key lookup
 *     RESP      (WS-RESP-CD)
 *   END-EXEC.
 *   
 *   IF WS-RESP-CD = 0 AND SEC-USR-PWD = WS-USER-PWD
 *     IF CDEMO-USRTYP-ADMIN
 *       EXEC CICS XCTL PROGRAM('COADM01C')    -- Admin menu
 *     ELSE
 *       EXEC CICS XCTL PROGRAM('COMEN01C')    -- User menu
 *   END-IF
 * </pre>
 * 
 * <h2>Spring Security Implementation</h2>
 * 
 * <p>This configuration implements the following transformations:</p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL/CICS Component</th>
 *     <th>Spring Security Equivalent</th>
 *     <th>Implementation Details</th>
 *   </tr>
 *   <tr>
 *     <td>USRSEC VSAM file</td>
 *     <td>PostgreSQL User table</td>
 *     <td>UserRepository with Spring Data JPA</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-ID (PIC X(08))</td>
 *     <td>User.userId (String)</td>
 *     <td>Primary key, username for authentication</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-PWD plain text</td>
 *     <td>BCrypt encoded password</td>
 *     <td>BCryptPasswordEncoder with strength 10</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-TYPE 'A'</td>
 *     <td>ROLE_ADMIN</td>
 *     <td>GrantedAuthority for admin access</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-TYPE 'U'</td>
 *     <td>ROLE_USER</td>
 *     <td>GrantedAuthority for regular user access</td>
 *   </tr>
 *   <tr>
 *     <td>CICS COMMAREA</td>
 *     <td>JWT token claims</td>
 *     <td>Stateless authentication with token payload</td>
 *   </tr>
 *   <tr>
 *     <td>CICS transaction authentication</td>
 *     <td>HTTP request authentication</td>
 *     <td>JWT filter validates every REST API request</td>
 *   </tr>
 *   <tr>
 *     <td>Program-level RACF access</td>
 *     <td>@PreAuthorize annotations</td>
 *     <td>Method-level security on service classes</td>
 *   </tr>
 * </table>
 * 
 * <h2>Security Architecture</h2>
 * 
 * <p>The security filter chain implements the following request processing flow:</p>
 * <ol>
 *   <li><b>CORS Preflight</b> - Handle cross-origin requests from React frontend</li>
 *   <li><b>JWT Extraction</b> - JwtAuthenticationFilter extracts Bearer token from Authorization header</li>
 *   <li><b>Token Validation</b> - Verify JWT signature, expiration, and claims integrity</li>
 *   <li><b>User Loading</b> - CustomUserDetailsService retrieves user from database</li>
 *   <li><b>Authorization Check</b> - Verify user has required role (ROLE_ADMIN or ROLE_USER)</li>
 *   <li><b>Security Context</b> - Populate SecurityContextHolder for downstream access control</li>
 *   <li><b>Request Processing</b> - Forward to controller method if authorized</li>
 *   <li><b>Exception Handling</b> - JwtAuthenticationEntryPoint returns 401 for auth failures</li>
 * </ol>
 * 
 * <h2>Authorization Rules Mapping</h2>
 * 
 * <p>Endpoint authorization rules match CICS transaction-level access controls:</p>
 * <pre>
 * Public Endpoints (no authentication required):
 *   - POST /api/auth/login       -- COSGN00C.cbl sign-on transaction (CC00)
 *   - POST /api/auth/logout      -- Session termination
 *   - GET  /actuator/health      -- Health check endpoint
 * 
 * Authenticated Endpoints (ROLE_USER or ROLE_ADMIN):
 *   - GET    /api/menu              -- COMEN01C.cbl main menu (CM00)
 *   - GET    /api/accounts/**       -- COACTVWC.cbl account view (CAVW)
 *   - PUT    /api/accounts/**       -- COACTUPC.cbl account update (CAUP)
 *   - GET    /api/cards/**          -- COCRDLIC.cbl card list (CCLI)
 *   - PUT    /api/cards/**          -- COCRDUPC.cbl card update (CCUP)
 *   - GET    /api/transactions/**   -- COTRN00C.cbl transaction list (CT00)
 *   - POST   /api/transactions      -- COTRN02C.cbl transaction add (CT02)
 *   - GET    /api/reports/**        -- CORPT00C.cbl reports (CR00)
 *   - POST   /api/billing/payment   -- COBIL00C.cbl bill payment (CB00)
 * 
 * Admin-Only Endpoints (ROLE_ADMIN required):
 *   - GET    /api/admin/menu        -- COADM01C.cbl admin menu (CA00)
 *   - GET    /api/admin/users       -- COUSR00C.cbl user list (CU00)
 *   - POST   /api/admin/users       -- COUSR01C.cbl user create (CU01)
 *   - PUT    /api/admin/users/**    -- COUSR02C.cbl user update (CU02)
 *   - DELETE /api/admin/users/**    -- COUSR03C.cbl user delete (CU03)
 * </pre>
 * 
 * <h2>Security Enhancements Over Mainframe</h2>
 * <ul>
 *   <li><b>Password Security:</b> BCrypt hashing replaces plain text storage (SEC-USR-PWD)</li>
 *   <li><b>Stateless Authentication:</b> JWT tokens replace CICS COMMAREA session state</li>
 *   <li><b>Token Expiration:</b> Automatic session timeout after 24 hours (configurable)</li>
 *   <li><b>CORS Protection:</b> Controlled cross-origin access from React frontend</li>
 *   <li><b>CSRF Protection:</b> Disabled for stateless REST API (JWT provides protection)</li>
 *   <li><b>Method Security:</b> @PreAuthorize enables fine-grained authorization</li>
 *   <li><b>Audit Logging:</b> Authentication events logged for security monitoring</li>
 *   <li><b>Encryption:</b> HTTPS for all communication (TLS/SSL termination at ingress)</li>
 * </ul>
 * 
 * <h2>Configuration Properties</h2>
 * <pre>
 * # JWT Configuration
 * jwt.secret=${JWT_SECRET:defaultSecretKeyForDevelopmentOnly}
 * jwt.expiration=86400000  # 24 hours in milliseconds
 * 
 * # CORS Configuration
 * cors.allowed-origins=${CORS_ORIGINS:http://localhost:3000}
 * cors.allowed-methods=GET,POST,PUT,DELETE,OPTIONS
 * cors.allowed-headers=*
 * cors.allow-credentials=true
 * cors.max-age=3600
 * </pre>
 * 
 * @see com.carddemo.security.JwtAuthenticationFilter
 * @see com.carddemo.security.JwtAuthenticationEntryPoint
 * @see com.carddemo.security.CustomUserDetailsService
 * @see com.carddemo.entity.User
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Custom UserDetailsService implementation that loads user credentials from PostgreSQL
     * User entity, replacing VSAM USRSEC file reads from COBOL COSGN00C.cbl.
     */
    private final CustomUserDetailsService userDetailsService;
    
    /**
     * JWT authentication filter that intercepts HTTP requests to extract and validate
     * JWT tokens from Authorization headers, replacing CICS COMMAREA session management.
     */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    
    /**
     * Authentication entry point that handles authentication failures with HTTP 401
     * responses, replacing COBOL BMS error screens from COSGN00C.cbl lines 241-256.
     */
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    
    /**
     * Allowed origins for CORS configuration, typically the React frontend URL.
     * Externalized to application.properties to support different environments.
     * Default: http://localhost:3000 for local development.
     */
    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String allowedOrigins;

    /**
     * Configures the Spring Security filter chain for HTTP requests.
     * 
     * <p>This method defines the complete security configuration including:</p>
     * <ul>
     *   <li>CSRF protection (disabled for stateless REST API)</li>
     *   <li>CORS configuration for cross-origin requests</li>
     *   <li>Stateless session management (JWT-based authentication)</li>
     *   <li>Authorization rules for public and protected endpoints</li>
     *   <li>JWT authentication filter registration</li>
     *   <li>Exception handling for authentication failures</li>
     * </ul>
     * 
     * <p>The filter chain replaces CICS transaction-level security with HTTP endpoint
     * security, mapping RACF program access controls to Spring Security authorization
     * rules using @PreAuthorize annotations and role-based access control.</p>
     * 
     * <h3>Security Filter Order</h3>
     * <ol>
     *   <li>CorsFilter - Handle CORS preflight requests</li>
     *   <li>JwtAuthenticationFilter - Extract and validate JWT tokens (custom)</li>
     *   <li>UsernamePasswordAuthenticationFilter - Standard Spring Security filter</li>
     *   <li>ExceptionTranslationFilter - Handle security exceptions</li>
     *   <li>FilterSecurityInterceptor - Enforce authorization rules</li>
     * </ol>
     * 
     * <h3>Authorization Strategy</h3>
     * <p>The configuration uses a whitelist approach where:</p>
     * <ul>
     *   <li>Public endpoints explicitly permit all access (authentication endpoints, health checks)</li>
     *   <li>Admin endpoints explicitly require ROLE_ADMIN authority</li>
     *   <li>All other /api/** endpoints require authentication (any valid role)</li>
     *   <li>Method-level security via @PreAuthorize provides fine-grained control</li>
     * </ul>
     * 
     * @param http HttpSecurity builder for configuring web-based security
     * @return SecurityFilterChain configured with JWT authentication and authorization rules
     * @throws Exception if security configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF protection for stateless REST API
            // JWT tokens provide CSRF protection through token validation
            // This matches CICS stateless transaction model where each request is independent
            .csrf(AbstractHttpConfigurer::disable)
            
            // Configure CORS to allow cross-origin requests from React frontend
            // Replaces mainframe terminal-based access with web browser access
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // Configure stateless session management
            // JWT tokens replace CICS COMMAREA for maintaining user context across requests
            // No server-side session storage required (stateless authentication)
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            
            // Configure authorization rules for HTTP endpoints
            // Maps CICS transaction-level access controls to REST API endpoints
            .authorizeHttpRequests(authz -> authz
                // Public endpoints - no authentication required
                // POST /api/auth/login - COSGN00C.cbl sign-on transaction (CC00)
                // POST /api/auth/logout - Session termination
                .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                
                // Actuator health check endpoint - for Kubernetes liveness/readiness probes
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                
                // Swagger/OpenAPI documentation endpoints - for API documentation
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                
                // Admin-only endpoints - require ROLE_ADMIN authority
                // Maps to CDEMO-USRTYP-ADMIN (VALUE 'A') from COCOM01Y.cpy
                // COADM01C.cbl admin menu (CA00)
                // COUSR00C.cbl - COUSR03C.cbl user management (CU00-CU03)
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                
                // All other /api/** endpoints require authentication
                // User must have either ROLE_ADMIN or ROLE_USER authority
                // Maps to authenticated CICS transactions (COMEN01C.cbl main menu and beyond)
                .requestMatchers("/api/**").authenticated()
                
                // Deny all other requests by default
                .anyRequest().denyAll()
            )
            
            // Configure exception handling for authentication failures
            // Returns HTTP 401 Unauthorized with JSON error message
            // Replaces COBOL BMS error screens from COSGN00C.cbl lines 241-256
            .exceptionHandling(exception -> 
                exception.authenticationEntryPoint(jwtAuthenticationEntryPoint)
            )
            
            // Register JWT authentication filter before UsernamePasswordAuthenticationFilter
            // This ensures JWT tokens are validated before standard form-based authentication
            // Filter extracts Bearer token from Authorization header and populates SecurityContext
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }

    /**
     * Creates and configures the BCrypt password encoder bean.
     * 
     * <p>BCrypt is a password hashing function designed for secure password storage
     * with adaptive complexity. It replaces the plain text password storage from the
     * mainframe USRSEC file (SEC-USR-PWD field in CSUSR01Y.cpy).</p>
     * 
     * <h3>BCrypt Configuration</h3>
     * <ul>
     *   <li><b>Strength:</b> 10 (2^10 = 1024 rounds of key expansion)</li>
     *   <li><b>Algorithm:</b> Blowfish-based adaptive hash function</li>
     *   <li><b>Salt:</b> Automatically generated per password (29-character random salt)</li>
     *   <li><b>Output:</b> 60-character hash string (includes algorithm, cost, salt, and hash)</li>
     * </ul>
     * 
     * <h3>Security Improvements Over Mainframe</h3>
     * <ul>
     *   <li><b>No Plain Text Storage:</b> Passwords are irreversibly hashed</li>
     *   <li><b>Rainbow Table Resistance:</b> Per-password salts prevent precomputed attacks</li>
     *   <li><b>Adaptive Cost:</b> Strength parameter can be increased as hardware improves</li>
     *   <li><b>Slow Hashing:</b> Deliberate computational cost thwarts brute force attacks</li>
     * </ul>
     * 
     * <h3>COBOL Password Comparison (COSGN00C.cbl line 236)</h3>
     * <pre>
     * IF SEC-USR-PWD = WS-USER-PWD    -- Plain text comparison (insecure)
     * </pre>
     * 
     * <h3>Spring Security BCrypt Comparison</h3>
     * <pre>
     * passwordEncoder.matches(rawPassword, encodedPassword)  -- Secure BCrypt verification
     * </pre>
     * 
     * @return PasswordEncoder configured with BCrypt algorithm and strength 10
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt strength of 10 provides good balance between security and performance
        // Each increment doubles the computation time, providing future-proof security
        // as hardware improves over time
        return new BCryptPasswordEncoder(10);
    }

    /**
     * Creates and configures the AuthenticationManager bean.
     * 
     * <p>AuthenticationManager is the central Spring Security interface for processing
     * authentication requests. It replaces the CICS READ operation against USRSEC file
     * (COSGN00C.cbl lines 209-229) with database-backed authentication using JPA.</p>
     * 
     * <h3>Authentication Flow</h3>
     * <ol>
     *   <li>AuthenticationService receives login request with userId and password</li>
     *   <li>AuthenticationManager.authenticate() is called with credentials</li>
     *   <li>DaoAuthenticationProvider retrieves user via CustomUserDetailsService</li>
     *   <li>PasswordEncoder verifies submitted password against stored BCrypt hash</li>
     *   <li>If successful, Authentication object contains user details and authorities</li>
     *   <li>JWT token is generated containing user ID and role claims</li>
     * </ol>
     * 
     * <h3>COBOL Authentication Flow Comparison</h3>
     * <pre>
     * COBOL (COSGN00C.cbl lines 209-257):
     *   1. EXEC CICS READ DATASET('USRSEC') INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID)
     *   2. IF WS-RESP-CD = 0 (user found)
     *   3. IF SEC-USR-PWD = WS-USER-PWD (plain text comparison)
     *   4. MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE ('A' or 'U')
     *   5. EXEC CICS XCTL to admin or user menu
     * 
     * Spring Security:
     *   1. UserRepository.findByUserId(username) - JPA database query
     *   2. Optional.orElseThrow() if user not found
     *   3. passwordEncoder.matches(rawPassword, encodedPassword) - BCrypt verification
     *   4. Convert UserType enum to GrantedAuthority (ROLE_ADMIN or ROLE_USER)
     *   5. Return JWT token with claims
     * </pre>
     * 
     * @param authConfiguration Spring Security authentication configuration
     * @return AuthenticationManager for processing authentication requests
     * @throws Exception if authentication manager cannot be created
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authConfiguration) throws Exception {
        return authConfiguration.getAuthenticationManager();
    }

    /**
     * Creates and configures the DaoAuthenticationProvider bean.
     * 
     * <p>DaoAuthenticationProvider is a Spring Security authentication provider that
     * authenticates against UserDetailsService and validates passwords using PasswordEncoder.
     * It provides the bridge between Spring Security and the custom user database.</p>
     * 
     * <h3>Provider Configuration</h3>
     * <ul>
     *   <li><b>UserDetailsService:</b> CustomUserDetailsService loads users from PostgreSQL</li>
     *   <li><b>PasswordEncoder:</b> BCryptPasswordEncoder verifies password hashes</li>
     *   <li><b>Hide User Not Found:</b> false (return specific error for security audit)</li>
     * </ul>
     * 
     * @return DaoAuthenticationProvider configured with UserDetailsService and PasswordEncoder
     */
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        
        // Set custom UserDetailsService that loads users from PostgreSQL User entity
        // Replaces VSAM KSDS USRSEC file reads from COSGN00C.cbl
        authProvider.setUserDetailsService(userDetailsService);
        
        // Set BCrypt password encoder for secure password verification
        // Replaces plain text password comparison (SEC-USR-PWD = WS-USER-PWD)
        authProvider.setPasswordEncoder(passwordEncoder());
        
        // Return specific error message if user not found (for audit logging)
        // Matches COBOL behavior: WHEN 13 'User not found. Try again ...'
        authProvider.setHideUserNotFoundExceptions(false);
        
        return authProvider;
    }

    /**
     * Configures CORS (Cross-Origin Resource Sharing) settings for the application.
     * 
     * <p>CORS configuration enables the React frontend application running on a different
     * origin (domain, protocol, or port) to make requests to the Spring Boot backend API.
     * This is necessary because web browsers enforce the Same-Origin Policy for security.</p>
     * 
     * <h3>Mainframe Terminal vs Modern Web Architecture</h3>
     * <p>In the original mainframe environment, 3270 terminal screens were served directly
     * from the CICS region, eliminating cross-origin concerns. The modern architecture
     * separates frontend (React on port 3000) from backend (Spring Boot on port 8080),
     * requiring CORS configuration.</p>
     * 
     * <h3>CORS Configuration Details</h3>
     * <ul>
     *   <li><b>Allowed Origins:</b> React development server (http://localhost:3000) and
     *       production frontend URLs configured via application.properties</li>
     *   <li><b>Allowed Methods:</b> GET, POST, PUT, DELETE, OPTIONS for full REST API support</li>
     *   <li><b>Allowed Headers:</b> All headers including Authorization for JWT tokens</li>
     *   <li><b>Exposed Headers:</b> Authorization header for response tokens</li>
     *   <li><b>Allow Credentials:</b> true to support cookies and authentication headers</li>
     *   <li><b>Max Age:</b> 3600 seconds (1 hour) to cache preflight responses</li>
     * </ul>
     * 
     * <h3>Security Considerations</h3>
     * <ul>
     *   <li>Allowed origins are explicitly configured, not using wildcard (*)</li>
     *   <li>Credentials are allowed only for trusted origins</li>
     *   <li>Preflight caching reduces overhead for complex requests</li>
     *   <li>CORS works in conjunction with JWT authentication for comprehensive security</li>
     * </ul>
     * 
     * @return CorsConfigurationSource with configured CORS policies
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Set allowed origins from application.properties
        // Supports multiple origins separated by commas
        // Example: http://localhost:3000,https://carddemo.example.com
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        
        // Allow standard HTTP methods for REST API operations
        // GET: Read operations (view accounts, cards, transactions)
        // POST: Create operations (add transaction, create user, login)
        // PUT: Update operations (update account, update card)
        // DELETE: Delete operations (delete user)
        // OPTIONS: Preflight requests for CORS validation
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        
        // Allow all headers including Authorization header for JWT tokens
        // This is necessary for Bearer token authentication
        configuration.setAllowedHeaders(Arrays.asList("*"));
        
        // Expose Authorization header in responses
        // Frontend can read JWT tokens from response headers
        configuration.setExposedHeaders(Arrays.asList("Authorization"));
        
        // Allow credentials (cookies, authorization headers) to be sent with requests
        // Required for JWT authentication via Authorization header
        configuration.setAllowCredentials(true);
        
        // Cache preflight responses for 1 hour to reduce overhead
        // Browser won't send preflight OPTIONS request for cached duration
        configuration.setMaxAge(3600L);
        
        // Register CORS configuration for all API endpoints
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/actuator/**", configuration);
        
        return source;
    }
}
