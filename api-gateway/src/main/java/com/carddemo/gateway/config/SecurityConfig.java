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
package com.carddemo.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * :purpose: Servlet Spring Security filter chain performing application-layer
 *     route authorization for the API gateway. The legacy CICS resource
 *     definitions set ``RESSEC(NO)`` / ``CMDSEC(NO)`` on every transaction, so
 *     transaction-level security is disabled and gating is enforced in
 *     application logic (AAP 0.6.7); this chain reproduces that model by matching
 *     path prefixes to role authorities. Authentication is delegated to
 *     ``auth-service`` and the authenticated context is restored from the shared
 *     Redis-backed HTTP session, so this chain checks only the resulting
 *     ``ROLE_ADMIN`` / ``ROLE_USER`` authorities and declares no authentication
 *     provider and no gateway routes.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * :purpose: Defines the gateway authorization rules and a ``401`` entry point.
     *     ``/auth/**`` and the actuator health/info/prometheus endpoints are
     *     permitted; ``/admin/**`` and ``/users/**`` require ``ROLE_ADMIN``; the
     *     menu and business route prefixes require ``ROLE_USER`` or ``ROLE_ADMIN``;
     *     every other request must be authenticated. CSRF is disabled and
     *     unauthenticated requests receive HTTP 401 rather than a login redirect.
     * :param http: the Spring Security ``HttpSecurity`` builder.
     * :returns: the built ``SecurityFilterChain``.
     * :throws: Exception propagated by the ``HttpSecurity`` builder.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/admin/**", "/users/**").hasRole("ADMIN")
                .requestMatchers("/menu/**", "/accounts/**", "/cards/**", "/transactions/**",
                        "/billpay/**", "/reports/**", "/batch/**").hasAnyRole("USER", "ADMIN")
                .anyRequest().authenticated())
            .csrf(csrf -> csrf.disable())
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
