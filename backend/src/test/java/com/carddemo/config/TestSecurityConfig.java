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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

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
 * </ul>
 * 
 * <p>This configuration is automatically detected by Spring component scanning when
 * the "integration-test" profile is active, making the PasswordEncoder bean available
 * to ALL integration tests without requiring explicit @Import annotations.
 * 
 * @author CardDemo Migration Team
 * @version 1.0.0
 * @since 2024-01-01
 */
@Configuration
@Profile("integration-test")
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
}
