/*
 * SecurityConfig.java
 *
 * Spring Security configuration for CardDemo REST API
 * Replaces RACF (Resource Access Control Facility) mainframe security
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
 * Converted from: RACF security definitions and CICS transaction security
 * Original COBOL security: COSGN00C.cbl authentication logic (lines 209-257)
 * Original COBOL user record: CSUSR01Y.cpy SEC-USER-DATA structure (lines 17-23)
 * Original COBOL COMMAREA: COCOM01Y.cpy user validation
 *
 * Conversion Notes:
 * - RACF resource profiles → Spring Security role-based access control with @PreAuthorize
 * - RACF user profiles (USRSEC VSAM file) → Spring Security UserDetails (user_security PostgreSQL table)
 * - RACF user types (SEC-USR-TYPE 'A'/'U'/'O') → Spring Security GrantedAuthority (ROLE_ADMIN/ROLE_USER/ROLE_OPERATOR)
 * - CICS transaction-level security → JWT authentication filter chain with stateless sessions
 * - EXEC CICS HANDLE CONDITION → Spring Security exception handling (401/403 responses)
 * - CICS pseudo-conversational mode → Stateless JWT token (no server-side session state)
 * - COBOL plain-text password (SEC-USR-PWD PIC X(08)) → BCrypt password hash with strength 10
 * - EXEC CICS ABEND for authentication failures → HTTP 401 Unauthorized JSON error response
 *
 * RACF to Spring Security Role Mapping (from COSGN00C.cbl lines 227-240):
 * - COBOL: IF CDEMO-USRTYP-ADMIN (SEC-USR-TYPE = 'A') EXEC CICS XCTL PROGRAM('COADM01C')
 *   Java:  @PreAuthorize("hasRole('ADMIN')") on /api/users/**, /api/admin/**
 * - COBOL: ELSE EXEC CICS XCTL PROGRAM('COMEN01C') (SEC-USR-TYPE = 'U')
 *   Java:  @PreAuthorize("hasRole('USER')") on /api/accounts/**, /api/cards/**, /api/transactions/**
 * - COBOL: (SEC-USR-TYPE = 'O' for operators)
 *   Java:  @PreAuthorize("hasRole('OPERATOR')") on /api/batch/**
 */
package com.carddemo.config;

import com.carddemo.security.CustomUserDetailsService;
import com.carddemo.security.JwtAuthenticationFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
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
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Security configuration replacing RACF mainframe security system.
 * 
 * <p>This configuration establishes the security framework for the CardDemo REST API,
 * replacing the IBM RACF (Resource Access Control Facility) security system used in
 * the mainframe environment. Key transformations:</p>
 * 
 * <table border="1">
 *   <caption><b>COBOL RACF Security to Spring Security Migration</b></caption>
 *   <tr>
 *     <th>COBOL/RACF Component</th>
 *     <th>Spring Security Equivalent</th>
 *     <th>Implementation</th>
 *   </tr>
 *   <tr>
 *     <td>RACF User Profiles (USRSEC VSAM)</td>
 *     <td>Spring Security UserDetails</td>
 *     <td>PostgreSQL user_security table + CustomUserDetailsService</td>
 *   </tr>
 *   <tr>
 *     <td>RACF Resource Profiles</td>
 *     <td>Role-Based Access Control (RBAC)</td>
 *     <td>@PreAuthorize annotations on controller methods</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-TYPE 'A' (Admin)</td>
 *     <td>ROLE_ADMIN</td>
 *     <td>Access to /api/users/**, /api/admin/**</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-TYPE 'U' (User)</td>
 *     <td>ROLE_USER</td>
 *     <td>Access to /api/accounts/**, /api/cards/**, /api/transactions/**</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-TYPE 'O' (Operator)</td>
 *     <td>ROLE_OPERATOR</td>
 *     <td>Access to /api/batch/** for batch job control</td>
 *   </tr>
 *   <tr>
 *     <td>CICS Transaction Security</td>
 *     <td>JWT Filter Chain</td>
 *     <td>Stateless authentication using JWT tokens</td>
 *   </tr>
 *   <tr>
 *     <td>CICS COMMAREA User Validation</td>
 *     <td>SecurityContextHolder</td>
 *     <td>Request-scoped authentication context</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS ABEND (Auth Failure)</td>
 *     <td>HTTP 401 Unauthorized</td>
 *     <td>JSON error response via authenticationEntryPoint</td>
 *   </tr>
 *   <tr>
 *     <td>Plain-text Password (SEC-USR-PWD)</td>
 *     <td>BCrypt Password Hash</td>
 *     <td>BCryptPasswordEncoder with strength 10</td>
 *   </tr>
 * </table>
 * 
 * <p><b>Security Configuration Features:</b></p>
 * <ul>
 *   <li><b>CORS Support:</b> Configured for React frontend (localhost:3000 dev, production domain prod)</li>
 *   <li><b>Stateless Sessions:</b> SessionCreationPolicy.STATELESS with JWT tokens</li>
 *   <li><b>CSRF Disabled:</b> Not needed for stateless JWT authentication</li>
 *   <li><b>Role-Based Authorization:</b> Endpoint access controlled by user roles</li>
 *   <li><b>Exception Handling:</b> Custom 401/403 responses with JSON error details</li>
 *   <li><b>Public Endpoints:</b> /api/auth/login, /api/health, /actuator/**, /swagger-ui/**</li>
 * </ul>
 * 
 * <p><b>Authentication Flow (replaces COSGN00C.cbl authentication):</b></p>
 * <ol>
 *   <li>User submits credentials to /api/auth/login (replaces COSGN00C signon screen)</li>
 *   <li>AuthenticationManager validates credentials via CustomUserDetailsService</li>
 *   <li>CustomUserDetailsService loads user from user_security table (replaces VSAM USRSEC READ)</li>
 *   <li>BCryptPasswordEncoder verifies password (replaces SEC-USR-PWD plain-text comparison)</li>
 *   <li>JWT token generated with userId and role claims (replaces COMMAREA population)</li>
 *   <li>Subsequent requests include JWT in Authorization header</li>
 *   <li>JwtAuthenticationFilter validates token and populates SecurityContextHolder</li>
 *   <li>SecurityFilterChain enforces role-based access to endpoints</li>
 * </ol>
 * 
 * @see JwtAuthenticationFilter
 * @see CustomUserDetailsService
 * @see org.springframework.security.config.annotation.web.builders.HttpSecurity
 * @see org.springframework.security.web.SecurityFilterChain
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /**
     * Logger for security configuration events and authentication/authorization outcomes.
     */
    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * JWT authentication filter for validating JWT tokens on each HTTP request.
     * Replaces CICS transaction-level security enforcement.
     * Injected via constructor dependency injection.
     */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Custom UserDetailsService for loading user credentials from PostgreSQL user_security table.
     * Replaces VSAM USRSEC file access (EXEC CICS READ DATASET('USRSEC')).
     * Injected via constructor dependency injection.
     */
    private final CustomUserDetailsService customUserDetailsService;

    /**
     * CORS allowed origins for React frontend.
     * Injected from application.yml property: cors.allowed-origins
     * Development: http://localhost:3000, http://localhost:5173 (Vite dev server)
     * Production: Production domain from environment-specific configuration
     */
    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private List<String> corsAllowedOrigins;

    /**
     * Constructor for dependency injection.
     * 
     * <p>Spring automatically injects required dependencies at application startup.
     * Constructor injection ensures immutability and thread-safety.</p>
     * 
     * @param jwtAuthenticationFilter JWT authentication filter for token validation
     * @param customUserDetailsService Custom UserDetailsService for database user lookup
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                         CustomUserDetailsService customUserDetailsService) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.customUserDetailsService = customUserDetailsService;
        logger.info("SecurityConfig initialized with JWT authentication and custom UserDetailsService");
    }

    /**
     * Configures HTTP security filter chain for REST API endpoints.
     * 
     * <p>This method establishes the complete security filter chain that processes all HTTP requests,
     * replacing RACF resource profile definitions and CICS transaction security checks.</p>
     * 
     * <p><b>COBOL Transaction Security Equivalent (COSGN00C.cbl lines 209-257):</b></p>
     * <pre>
     * READ-USER-SEC-FILE.
     *     EXEC CICS READ
     *          DATASET   (WS-USRSEC-FILE)           ← VSAM USRSEC file access
     *          INTO      (SEC-USER-DATA)            ← CSUSR01Y.cpy structure
     *          RIDFLD    (WS-USER-ID)               ← User ID key lookup
     *     END-EXEC.
     *     EVALUATE WS-RESP-CD
     *         WHEN 0
     *             IF SEC-USR-PWD = WS-USER-PWD      ← Plain-text password comparison (line 223)
     *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE  ← User type assignment (line 227)
     *                 IF CDEMO-USRTYP-ADMIN         ← SEC-USR-TYPE = 'A' (line 230)
     *                      EXEC CICS XCTL PROGRAM ('COADM01C') ← Admin menu routing
     *                 ELSE                          ← SEC-USR-TYPE = 'U'
     *                      EXEC CICS XCTL PROGRAM ('COMEN01C') ← User menu routing
     *                 END-IF
     *             ELSE
     *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE ← Auth failure (line 242)
     *             END-IF
     *         WHEN 13
     *             MOVE 'User not found. Try again ...' TO WS-MESSAGE ← User not found (line 249)
     *     END-EVALUATE.
     * </pre>
     * 
     * @param http HttpSecurity builder for configuring security rules
     * @return SecurityFilterChain the configured security filter chain bean
     * @throws Exception if security configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        logger.info("Configuring HTTP security filter chain with role-based access control and CORS support");

        http
            // Disable CSRF for stateless REST API (JWT tokens prevent CSRF attacks)
            .csrf(AbstractHttpConfigurer::disable)
            
            // Configure CORS for React frontend (localhost:3000 dev, production domain prod)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // Configure authorization rules matching RACF resource profiles
            .authorizeHttpRequests(authorize -> authorize
                // Public endpoints - permitAll (replaces COSGN00C.cbl unauthenticated access)
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/auth/logout").permitAll()
                .requestMatchers("/api/health").permitAll()
                .requestMatchers("/v3/api-docs/**").permitAll()
                .requestMatchers("/swagger-ui/**").permitAll()
                .requestMatchers("/swagger-ui.html").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                
                // Admin-only endpoints - ROLE_ADMIN required (SEC-USR-TYPE = 'A')
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                
                // User endpoints - ROLE_USER required (SEC-USR-TYPE = 'U')
                .requestMatchers("/api/accounts/**").hasRole("USER")
                .requestMatchers("/api/cards/**").hasRole("USER")
                .requestMatchers("/api/transactions/**").hasRole("USER")
                
                // Operator endpoints - ROLE_OPERATOR required (SEC-USR-TYPE = 'O')
                .requestMatchers("/api/batch/**").hasRole("OPERATOR")
                
                // All other /api/** endpoints require authentication
                .requestMatchers("/api/**").authenticated()
                
                // Permit all other requests (static resources)
                .anyRequest().permitAll()
            )
            
            // Configure stateless session management (replaces CICS pseudo-conversational mode)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            
            // Configure exception handling (replaces COBOL error messages)
            .exceptionHandling(exception -> exception
                // 401 Unauthorized - replaces COBOL 'User not found. Try again ...'
                .authenticationEntryPoint((request, response, authException) -> {
                    logger.warn("Authentication failed for request: {} {}. Returning 401 Unauthorized. Error: {}", 
                              request.getMethod(), request.getRequestURI(), authException.getMessage());
                    sendJsonErrorResponse(response, HttpStatus.UNAUTHORIZED, 
                                        "Unauthorized", 
                                        "Authentication is required to access this resource. " +
                                        "Please provide a valid JWT token in the Authorization header.");
                })
                
                // 403 Forbidden - replaces COBOL 'Access denied' (when user lacks required role)
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    logger.warn("Access denied for request: {} {}. User lacks required role. Returning 403 Forbidden. Error: {}", 
                              request.getMethod(), request.getRequestURI(), accessDeniedException.getMessage());
                    sendJsonErrorResponse(response, HttpStatus.FORBIDDEN, 
                                        "Forbidden", 
                                        "Access denied. You do not have the required role to access this resource. " +
                                        "Contact your administrator to request appropriate permissions.");
                })
            )
            
            // Register JWT authentication filter (replaces CICS COMMAREA validation)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        logger.info("HTTP security filter chain configured successfully with CORS origins: {}", corsAllowedOrigins);
        return http.build();
    }

    /**
     * Configures CORS (Cross-Origin Resource Sharing) for React frontend.
     * 
     * <p>CORS configuration allows React SPA running on different origin to make API calls to Spring Boot backend.</p>
     * 
     * @return UrlBasedCorsConfigurationSource CORS configuration source applied to all endpoints
     */
    private UrlBasedCorsConfigurationSource corsConfigurationSource() {
        logger.debug("Configuring CORS for React frontend with allowed origins: {}", corsAllowedOrigins);
        
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Set allowed origins from application.yml property
        configuration.setAllowedOrigins(corsAllowedOrigins);
        
        // Allow standard REST HTTP methods
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        
        // Allow necessary headers for JWT authentication and JSON content
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        
        // Expose Authorization header so frontend can read JWT token from response
        configuration.setExposedHeaders(List.of("Authorization"));
        
        // Allow credentials (cookies, Authorization header) to be sent with requests
        configuration.setAllowCredentials(true);
        
        // Cache preflight OPTIONS requests for 1 hour to reduce latency
        configuration.setMaxAge(3600L);
        
        // Apply CORS configuration to all endpoints (/**)
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        logger.info("CORS configuration registered for all endpoints with origins: {}", corsAllowedOrigins);
        return source;
    }

    /**
     * Provides BCrypt password encoder bean for secure password hashing.
     * 
     * <p>BCrypt password encoder replaces COBOL plain-text password validation from COSGN00C.cbl line 223:
     * {@code IF SEC-USR-PWD = WS-USER-PWD}.</p>
     * 
     * @return PasswordEncoder BCrypt password encoder instance with strength 10
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        logger.info("Configuring BCrypt password encoder with strength 10 (replaces COBOL plain-text SEC-USR-PWD)");
        // Strength 10 provides good balance between security and performance
        // 2^10 = 1024 iterations takes ~100ms per password verification
        return new BCryptPasswordEncoder(10);
    }

    /**
     * Provides AuthenticationManager bean for coordinating authentication process.
     * 
     * <p>AuthenticationManager uses DaoAuthenticationProvider with CustomUserDetailsService and
     * BCryptPasswordEncoder to authenticate users against PostgreSQL user_security table.</p>
     * 
     * @return AuthenticationManager authentication manager instance for processing login requests
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        logger.info("Configuring AuthenticationManager with DaoAuthenticationProvider and CustomUserDetailsService");
        
        // Create DaoAuthenticationProvider for database-backed authentication
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        
        // Set CustomUserDetailsService for loading users from PostgreSQL user_security table
        authProvider.setUserDetailsService(customUserDetailsService);
        
        // Set BCrypt password encoder for secure password verification
        authProvider.setPasswordEncoder(passwordEncoder());
        
        // Create ProviderManager with DaoAuthenticationProvider
        ProviderManager providerManager = new ProviderManager(List.of(authProvider));
        
        logger.info("AuthenticationManager configured successfully with DaoAuthenticationProvider");
        return providerManager;
    }

    /**
     * Sends JSON error response for authentication and authorization failures.
     * 
     * <p>This utility method generates consistent JSON error responses for HTTP 401 Unauthorized
     * and HTTP 403 Forbidden scenarios.</p>
     * 
     * @param response HttpServletResponse to write JSON error to
     * @param status HTTP status code (401 Unauthorized or 403 Forbidden)
     * @param error Error type string (e.g., "Unauthorized", "Forbidden")
     * @param message Detailed error message for client
     * @throws IOException if writing response fails
     */
    private void sendJsonErrorResponse(HttpServletResponse response, 
                                      HttpStatus status, 
                                      String error, 
                                      String message) throws IOException {
        // Set HTTP status code (401 or 403)
        response.setStatus(status.value());
        
        // Set response content type to JSON (UTF-8 encoding)
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        
        // Build JSON error response with timestamp, status, error type, and message
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
        errorResponse.put("status", status.value());
        errorResponse.put("error", error);
        errorResponse.put("message", message);
        
        // Write JSON error response to HTTP response body
        ObjectMapper objectMapper = new ObjectMapper();
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
        response.getWriter().flush();
        
        logger.debug("Sent JSON error response: status={}, error={}, message={}", status.value(), error, message);
    }
}
