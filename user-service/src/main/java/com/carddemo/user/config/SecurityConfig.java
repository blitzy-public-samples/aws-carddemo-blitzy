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
package com.carddemo.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * :purpose: Declares the HTTP security policy for the User Management
 *     microservice, re-platforming the administrator-only CICS/RACF
 *     transaction-security model of the legacy user programs
 *     ``COUSR00C``-``COUSR03C`` (transactions ``CU00``-``CU03``). Every
 *     user-CRUD route requires an authenticated principal bearing the
 *     ``ROLE_ADMIN`` authority; the Actuator health/probe and Prometheus
 *     endpoints are public. The security context is stateless; cross-request
 *     session state is held externally in Spring Session (Redis). This
 *     service performs no sign-on (that is the auth-service); it consumes the
 *     already-authenticated session and role. It also supplies the BCrypt
 *     password encoder used by the user service to hash credentials at rest.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * :purpose: Builds the stateless REST security filter chain: permits the
     *     public Actuator probe/metrics endpoints and requires the
     *     ``ROLE_ADMIN`` authority for every other request, because all
     *     user-CRUD functions are administrator-only.
     * :param http: the Spring Security ``HttpSecurity`` builder.
     * :returns: the configured ``SecurityFilterChain``.
     * :raises Exception: if the filter chain cannot be built.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/prometheus",
                    "/actuator/info").permitAll()
                .anyRequest().hasRole("ADMIN"))
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }

    /**
     * :purpose: Supplies the BCrypt password encoder used to hash security-user
     *     credentials at rest, replacing the legacy plaintext comparison of
     *     ``COSGN00C``. The user service injects this encoder to hash a
     *     password on user creation and to re-hash it on password change.
     * :returns: a ``BCryptPasswordEncoder`` instance.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
