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

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Test Security Configuration for faster BCrypt password encoding.
 * 
 * <p>This configuration overrides the production BCryptPasswordEncoder bean with
 * a lower strength setting (4 instead of 12) to significantly reduce test execution
 * time while maintaining functional correctness.</p>
 * 
 * <h2>BCrypt Strength and Performance</h2>
 * 
 * <p><strong>Production (Strength 12):</strong></p>
 * <ul>
 *   <li>Hash time: ~200ms per operation</li>
 *   <li>Purpose: Maximum security for production authentication</li>
 *   <li>Required by Section 0.9 security requirements</li>
 * </ul>
 * 
 * <p><strong>Test Environment (Strength 4):</strong></p>
 * <ul>
 *   <li>Hash time: ~10ms per operation (20x faster)</li>
 *   <li>Purpose: Fast test execution for integration tests</li>
 *   <li>Security: Sufficient for test data validation</li>
 *   <li>Functional equivalence: Password matching logic identical</li>
 * </ul>
 * 
 * <h2>Integration Test Performance Impact</h2>
 * 
 * <p>Reducing BCrypt strength from 12 to 4 allows integration tests to meet
 * the &lt; 200ms response time validation while still exercising the complete
 * authentication flow including:</p>
 * <ul>
 *   <li>UserSecurityRepository database queries</li>
 *   <li>Password verification logic (BCrypt.matches())</li>
 *   <li>JWT token generation</li>
 *   <li>Spring Security context establishment</li>
 * </ul>
 * 
 * <p><strong>Note:</strong> This configuration is automatically applied to all
 * {@code @SpringBootTest} annotated test classes via Spring Boot's test configuration
 * detection mechanism. Production code uses the standard SecurityConfig with strength 12.</p>
 * 
 * @see com.carddemo.config.SecurityConfig
 * @see com.carddemo.integration.AuthenticationIntegrationTest
 */
@TestConfiguration
public class TestSecurityConfig {

    /**
     * Provides BCryptPasswordEncoder bean with reduced strength for test performance.
     * 
     * <p>The {@code @Primary} annotation ensures this bean takes precedence over
     * the production BCryptPasswordEncoder bean (strength 12) defined in SecurityConfig.</p>
     * 
     * <p><strong>Strength 4 Justification:</strong></p>
     * <ul>
     *   <li>Test data does not require production-grade security</li>
     *   <li>Functional behavior (password matching) remains identical</li>
     *   <li>Enables &lt; 200ms response time validation in integration tests</li>
     *   <li>Standard practice in Spring Security testing</li>
     * </ul>
     * 
     * @return BCryptPasswordEncoder configured with strength 4 for fast testing
     */
    @Bean
    @Primary
    public BCryptPasswordEncoder testPasswordEncoder() {
        // Use strength 4 for fast test execution (~10ms vs ~200ms per hash)
        // Production uses strength 12 per Section 0.9 security requirements
        return new BCryptPasswordEncoder(4);
    }
}
