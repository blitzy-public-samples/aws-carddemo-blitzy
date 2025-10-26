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
 * Original COBOL security: COSGN00C.cbl authentication logic
 * Original COBOL COMMAREA: COCOM01Y.cpy user validation
 *
 * Conversion Notes:
 * - RACF resource profiles → Spring Security role-based access control
 * - RACF user profiles → Spring Security UserDetails (user_security table)
 * - CICS transaction-level security → JWT authentication filter chain
 * - EXEC CICS HANDLE CONDITION → Spring Security exception handling
 * - This configuration manually registers JwtAuthenticationFilter to prevent
 *   auto-registration conflicts with Spring MVC handler mappings
 */
package com.carddemo.config;

import com.carddemo.security.JwtAuthenticationFilter;
import com.carddemo.security.JwtTokenProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
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

/**
 * Spring Security configuration replacing RACF mainframe security system.
 * 
 * <p>This configuration establishes the security framework for the CardDemo REST API,
 * replacing the IBM RACF (Resource Access Control Facility) security system used in
 * the mainframe environment. Key transformations:</p>
 * 
 * <ul>
 *   <li><b>RACF User Profiles → Spring Security UserDetails:</b> User credentials stored
 *       in PostgreSQL user_security table, loaded via CustomUserDetailsService</li>
 *   <li><b>RACF Resource Profiles → Role-Based Access Control:</b> @PreAuthorize annotations
 *       on controller methods enforce role-based permissions</li>
 *   <li><b>CICS Transaction Security → JWT Filter Chain:</b> Stateless authentication using
 *       JWT tokens validated on each request</li>
 *   <li><b>RACF Access Rules → SecurityFilterChain:</b> HTTP security rules define public
 *       and protected endpoints with proper authorization requirements</li>
 * </ul>
 * 
 * <p><b>CRITICAL FIX - Handler Mapping Conflict Resolution:</b></p>
 * <p>JwtAuthenticationFilter is manually registered in this configuration rather than
 * using @Component annotation. This prevents Spring Boot auto-registration that causes
 * handler mapping conflicts where SimpleUrlHandlerMapping (for static resources) takes
 * priority over RequestMappingHandlerMapping (for REST controllers), resulting in
 * HTTP 500 NoResourceFoundException for API requests.</p>
 * 
 * @see JwtAuthenticationFilter
 * @see JwtTokenProvider
 * @see com.carddemo.security.CustomUserDetailsService
 * @see org.springframework.security.config.annotation.web.builders.HttpSecurity
 */
@Configuration
@Profile("!integration-test")
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /**
     * Logger for security configuration events.
     */
    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * JWT token provider for token operations.
     * Injected via constructor by Spring dependency injection.
     */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Constructor for dependency injection.
     * 
     * @param jwtTokenProvider JWT token provider instance
     */
    public SecurityConfig(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
        logger.info("SecurityConfig initialized with JWT authentication");
    }

    /**
     * Configures HTTP security filter chain for REST API endpoints.
     * 
     * <p>This method establishes the security filter chain that processes all HTTP requests.
     * It replaces RACF resource profile definitions and CICS transaction security checks.</p>
     * 
     * <p><b>Security Configuration:</b></p>
     * <ul>
     *   <li><b>CSRF Disabled:</b> No CSRF protection for stateless REST API</li>
     *   <li><b>Stateless Sessions:</b> No server-side session state (JWT tokens only)</li>
     *   <li><b>Public Endpoints:</b> /api/auth/login and /api/health permit all</li>
     *   <li><b>Protected Endpoints:</b> All other /api/** require authentication</li>
     *   <li><b>JWT Filter:</b> Registered before username/password authentication filter</li>
     * </ul>
     * 
     * @param http HttpSecurity builder for configuring security rules
     * @return SecurityFilterChain the configured security filter chain
     * @throws Exception if security configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        logger.info("Configuring HTTP security filter chain");

        http
            // Disable CSRF for stateless REST API
            .csrf(AbstractHttpConfigurer::disable)
            
            // Configure authorization rules for HTTP requests
            .authorizeHttpRequests(authorize -> authorize
                // Public endpoints (permit all access without authentication)
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/health").permitAll()
                
                // Permit Swagger/OpenAPI documentation endpoints
                .requestMatchers("/v3/api-docs/**").permitAll()
                .requestMatchers("/swagger-ui/**").permitAll()
                .requestMatchers("/swagger-ui.html").permitAll()
                
                // Permit Actuator endpoints for monitoring
                .requestMatchers("/actuator/**").permitAll()
                
                // All other API endpoints require authentication
                .requestMatchers("/api/**").authenticated()
                
                // Permit all other requests (static resources, etc.)
                .anyRequest().permitAll()
            )
            
            // Configure stateless session management (no server-side sessions)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            
            // Register JWT authentication filter before username/password filter
            // CRITICAL: Manual registration prevents auto-registration handler mapping conflicts
            .addFilterBefore(
                new JwtAuthenticationFilter(jwtTokenProvider),
                UsernamePasswordAuthenticationFilter.class
            );

        logger.info("HTTP security filter chain configured successfully");
        return http.build();
    }

    /**
     * Provides BCrypt password encoder bean for password hashing.
     * 
     * <p>BCrypt encoder replaces COBOL plain-text password validation from COSGN00C.cbl.
     * User passwords stored in user_security table are hashed with BCrypt algorithm
     * providing secure password storage compliant with modern security standards.</p>
     * 
     * @return PasswordEncoder BCrypt password encoder instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        logger.info("Configuring BCrypt password encoder (strength: 10)");
        return new BCryptPasswordEncoder();
    }

    /**
     * Provides AuthenticationManager bean for authentication operations.
     * 
     * <p>AuthenticationManager coordinates authentication process using registered
     * authentication providers (primarily UserDetailsService). Required by
     * AuthController for processing login requests.</p>
     * 
     * @param authenticationConfiguration Spring-provided authentication configuration
     * @return AuthenticationManager the authentication manager instance
     * @throws Exception if authentication manager retrieval fails
     */
    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        logger.info("Configuring authentication manager");
        return authenticationConfiguration.getAuthenticationManager();
    }
}
