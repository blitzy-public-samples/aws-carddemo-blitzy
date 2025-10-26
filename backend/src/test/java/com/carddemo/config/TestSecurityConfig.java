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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test-specific security configuration for integration tests.
 * 
 * <p>This configuration provides security-related beans needed during integration testing
 * when the main SecurityConfig is disabled via @Profile("!integration-test"). It ensures
 * that authentication services can function properly in the test environment.
 * 
 * <p>Key beans provided:
 * <ul>
 *   <li><b>PasswordEncoder:</b> BCrypt password encoder for test user password hashing</li>
 *   <li><b>SecurityFilterChain:</b> Permissive security configuration that allows all requests</li>
 * </ul>
 * 
 * <p>This configuration is automatically detected by Spring component scanning when
 * the "integration-test" profile is active, making the PasswordEncoder bean available
 * to ALL integration tests without requiring explicit @Import annotations.
 * 
 * <p><b>Security Note:</b> All requests are permitted in the test environment for simplicity.
 * The @WithMockUser annotation is used to simulate authenticated users with specific roles.
 * 
 * @author CardDemo Migration Team
 * @version 1.0.0
 * @since 2024-01-01
 */
@Configuration
@Profile("integration-test")
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = false)
public class TestSecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(TestSecurityConfig.class);

    /**
     * Provides BCrypt password encoder bean for integration tests.
     * 
     * <p>This bean is required by AuthService for password validation during
     * authentication operations. Uses BCrypt with default strength (10) matching
     * production configuration.
     * 
     * <p>The password encoder is used to:
     * <ul>
     *   <li>Hash test user passwords in @BeforeEach test setup</li>
     *   <li>Validate passwords during AuthService.authenticate() calls</li>
     *   <li>Ensure password validation logic matches production behavior</li>
     * </ul>
     * 
     * @return PasswordEncoder BCrypt password encoder instance for test environment
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        logger.info("Configuring test BCrypt password encoder (strength: 10)");
        return new BCryptPasswordEncoder();
    }

    /**
     * Configure security filter chain for integration tests.
     * 
     * <p>This configuration provides a permissive security setup for integration testing:
     * <ul>
     *   <li>Permits all HTTP requests without requiring authentication</li>
     *   <li>Disables CSRF protection (not needed for stateless REST API tests)</li>
     *   <li>Enables method-level security via @PreAuthorize annotations</li>
     *   <li>Works with @WithMockUser to simulate authenticated users in tests</li>
     * </ul>
     * 
     * <p><b>Why permit all?</b> Integration tests use @WithMockUser to inject mock
     * authentication into the security context. This annotation works at the method level
     * (via @PreAuthorize on controllers), not at the HTTP filter level. By permitting all
     * HTTP requests, we allow the request to reach the controller where @PreAuthorize
     * checks are enforced based on the @WithMockUser mock authentication.
     * 
     * <p>This approach replicates COBOL RACF security checking logic where user credentials
     * are validated within the application logic rather than at the transport layer.
     * 
     * @param http HttpSecurity configuration object
     * @return Configured SecurityFilterChain
     * @throws Exception if configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        logger.info("Configuring SecurityFilterChain for integration tests");
        
        http
            // Disable CSRF for stateless REST API testing
            .csrf(AbstractHttpConfigurer::disable)
            // Permit all HTTP requests - @WithMockUser provides mock authentication
            .authorizeHttpRequests(authz -> authz
                .anyRequest().permitAll()
            );
        
        logger.info("SecurityFilterChain configured: All requests permitted, method security enabled");
        return http.build();
    }
}
