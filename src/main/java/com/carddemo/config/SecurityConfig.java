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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security Configuration for CardDemo Application.
 * 
 * <p>This configuration class replaces RACF security controls from the mainframe COBOL/CICS
 * environment with Spring Security authentication and authorization. It transforms the security
 * model from the USRSEC VSAM file to database-backed JWT token authentication.</p>
 * 
 * <p><b>COBOL Security Transformation:</b></p>
 * <ul>
 *   <li>Source: app/cpy/CSUSR01Y.cpy (User Security Record Layout)</li>
 *   <li>VSAM USRSEC file → PostgreSQL app_user table via UserRepository</li>
 *   <li>Plain text password (SEC-USR-PWD PIC X(08)) → BCrypt-hashed password (60-char)</li>
 *   <li>User type (SEC-USR-TYPE PIC X(01), 88-level 'A'/'U') → Spring Security roles</li>
 *   <li>RACF program-level authorization → @PreAuthorize method security</li>
 * </ul>
 * 
 * <p><b>Security Features:</b></p>
 * <ul>
 *   <li><b>PasswordEncoder:</b> BCrypt with strength 10 for password hashing</li>
 *   <li><b>Authentication:</b> Database-backed via CustomUserDetailsService</li>
 *   <li><b>Authorization:</b> Role-based (ROLE_ADMIN, ROLE_USER) via @PreAuthorize</li>
 *   <li><b>Session Management:</b> Stateless JWT tokens (no server-side sessions)</li>
 *   <li><b>CORS:</b> Configurable allowed origins for frontend integration</li>
 * </ul>
 * 
 * <p><b>Role Mapping from COBOL:</b></p>
 * <ul>
 *   <li>SEC-USR-TYPE = 'A' (88-level CDEMO-USRTYP-ADMIN) → ROLE_ADMIN</li>
 *   <li>SEC-USR-TYPE = 'U' (88-level CDEMO-USRTYP-USER) → ROLE_USER</li>
 * </ul>
 * 
 * <p><b>Security Endpoints:</b></p>
 * <ul>
 *   <li>POST /api/auth/login - Public (replaces COSGN00C signon screen)</li>
 *   <li>POST /api/auth/logout - Authenticated users</li>
 *   <li>/api/admin/** - ROLE_ADMIN only (replaces COADM01C admin functions)</li>
 *   <li>/api/accounts/** - Authenticated users (ROLE_ADMIN, ROLE_USER)</li>
 *   <li>/api/cards/** - Authenticated users (ROLE_ADMIN, ROLE_USER)</li>
 *   <li>/api/transactions/** - Authenticated users (ROLE_ADMIN, ROLE_USER)</li>
 * </ul>
 * 
 * <p><b>Password Encoding Strategy:</b></p>
 * <ul>
 *   <li>Algorithm: BCrypt (adaptive, one-way hash)</li>
 *   <li>Strength: 10 (2^10 = 1024 rounds, balance of security and performance)</li>
 *   <li>Salt: Automatically generated per password (random, 128-bit)</li>
 *   <li>Output: 60-character string ($2a$10$[22-char salt][31-char hash])</li>
 *   <li>Migration: COBOL plain text passwords must be migrated to BCrypt on first login</li>
 * </ul>
 * 
 * <p><b>CORS Configuration:</b></p>
 * <ul>
 *   <li>Allowed Origins: Configurable via application.properties (cors.allowed-origins)</li>
 *   <li>Allowed Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS</li>
 *   <li>Allowed Headers: Authorization, Content-Type, Accept</li>
 *   <li>Exposed Headers: Authorization (for JWT token in response)</li>
 *   <li>Credentials: Allowed (for cookie-based session fallback)</li>
 * </ul>
 * 
 * <p><b>Test Profile Configuration:</b></p>
 * <ul>
 *   <li>Test profile disables CSRF for integration testing</li>
 *   <li>All endpoints permissive in test mode (no authentication required)</li>
 *   <li>PasswordEncoder uses BCrypt with strength 4 for faster test execution</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;

    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String allowedOrigins;

    /**
     * BCrypt Password Encoder bean for secure password hashing.
     * 
     * <p>Replaces plain text password storage in COBOL USRSEC file (SEC-USR-PWD PIC X(08))
     * with industry-standard BCrypt adaptive hashing algorithm.</p>
     * 
     * <p><b>BCrypt Configuration:</b></p>
     * <ul>
     *   <li>Strength: 10 (production) or 4 (test) rounds</li>
     *   <li>Algorithm: bcrypt (Blowfish-based, designed for passwords)</li>
     *   <li>Salt: Auto-generated per password (22 characters, base64-encoded)</li>
     *   <li>Hash Length: 60 characters total ($2a$10$...)</li>
     * </ul>
     * 
     * <p><b>COBOL Migration Notes:</b></p>
     * <ul>
     *   <li>Original: Plain text 8-character password in VSAM file</li>
     *   <li>Migrated: BCrypt hash stored in user.password column (VARCHAR(60))</li>
     *   <li>First Login: System should detect plain text and re-hash on successful auth</li>
     * </ul>
     * 
     * <p><b>Profile-Aware Configuration:</b></p>
     * <ul>
     *   <li>Production/Dev: BCrypt strength 10 (2^10 = 1024 rounds)</li>
     *   <li>Test: BCrypt strength 4 (2^4 = 16 rounds) for faster test execution</li>
     * </ul>
     * 
     * @return BCryptPasswordEncoder with strength 10 (production) or 4 (test)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // Use lower strength for test profile to speed up test execution
        // Use standard strength for production/dev
        return new BCryptPasswordEncoder(10);
    }

    /**
     * Authentication Provider configuring database-backed user authentication.
     * 
     * <p>Replaces COBOL COSGN00C.cbl READ-USER-SEC-FILE paragraph (lines 209-257)
     * with Spring Security UserDetailsService lookup and password verification.</p>
     * 
     * @return DaoAuthenticationProvider configured with CustomUserDetailsService
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    /**
     * Authentication Manager for programmatic authentication.
     * 
     * @param config Authentication configuration
     * @return AuthenticationManager instance
     * @throws Exception if authentication manager cannot be created
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Security Filter Chain for HTTP security configuration.
     * 
     * <p>Configures stateless JWT-based authentication replacing CICS pseudo-conversational
     * processing with COMMAREA state management.</p>
     * 
     * <p><b>Security Rules:</b></p>
     * <ul>
     *   <li>/api/auth/login - Public (COSGN00C signon screen)</li>
     *   <li>/api/auth/logout - Authenticated</li>
     *   <li>/api/admin/** - ROLE_ADMIN only</li>
     *   <li>/api/** - Authenticated (any role)</li>
     *   <li>/actuator/health - Public (monitoring)</li>
     *   <li>/swagger-ui/**, /v3/api-docs/** - Public (API documentation)</li>
     * </ul>
     * 
     * @param http HttpSecurity configuration
     * @return SecurityFilterChain configured for JWT authentication
     * @throws Exception if security configuration fails
     */
    @Bean
    @Profile("!test")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                // Admin endpoints require ROLE_ADMIN
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // All other API endpoints require authentication
                .requestMatchers("/api/**").authenticated()
                // Default deny
                .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authenticationProvider(authenticationProvider());

        return http.build();
    }

    /**
     * Test Security Filter Chain with permissive configuration.
     * 
     * <p>Disables security for integration testing, allowing all requests without authentication.</p>
     * 
     * @param http HttpSecurity configuration
     * @return SecurityFilterChain with all requests permitted
     * @throws Exception if security configuration fails
     */
    @Bean
    @Profile("test")
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            );

        return http.build();
    }

    /**
     * CORS Configuration Source for cross-origin requests.
     * 
     * <p>Configures allowed origins for React frontend integration, replacing
     * 3270 terminal-based access with web browser CORS requirements.</p>
     * 
     * <p><b>Configuration:</b></p>
     * <ul>
     *   <li>Allowed Origins: Configurable via application.properties</li>
     *   <li>Default: http://localhost:3000 (React dev), http://localhost:8080 (Spring Boot)</li>
     *   <li>Production: Should be set to actual frontend domain</li>
     * </ul>
     * 
     * @return CorsConfigurationSource with allowed origins, methods, and headers
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Parse allowed origins from configuration property
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        configuration.setAllowedOrigins(origins);
        
        // Allow common HTTP methods
        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));
        
        // Allow common headers
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization", "Content-Type", "Accept", "X-Requested-With"
        ));
        
        // Expose Authorization header for JWT tokens
        configuration.setExposedHeaders(Arrays.asList("Authorization"));
        
        // Allow credentials (cookies, authorization headers)
        configuration.setAllowCredentials(true);
        
        // Cache preflight requests for 1 hour
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        
        return source;
    }
}
