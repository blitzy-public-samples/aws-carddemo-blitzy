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
package com.carddemo.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * :purpose: Declares the HTTP security policy for the authentication
 *     microservice, replacing the CICS/RACF transaction-security model
 *     (transaction ``CC00`` -> program ``COSGN00C``, file ``USRSEC``). The
 *     sign-on endpoint and the Actuator health/probe and Prometheus
 *     endpoints are public; every other route requires an authenticated
 *     principal bearing a ``ROLE_ADMIN`` or ``ROLE_USER`` authority. The
 *     security context is stateless; cross-request session state is held
 *     externally in Spring Session (Redis), not in the security context.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * :purpose: Builds the stateless REST security filter chain: permits the
     *     public sign-on and Actuator probe/metrics endpoints and requires
     *     authentication for all other requests.
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
                .requestMatchers(HttpMethod.POST, "/auth/signon").permitAll()
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/prometheus").permitAll()
                .anyRequest().authenticated())
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }

    /**
     * :purpose: Assembles the ``AuthenticationManager`` from the shared
     *     ``UserDetailsService`` and ``PasswordEncoder`` beans, reproducing the
     *     ``USRSEC`` credential verification of ``COSGN00C`` with a one-way
     *     encoder in place of the legacy plaintext comparison.
     * :param userDetailsService: loads the security user and role authority.
     * :param passwordEncoder: verifies the raw password against the stored hash.
     * :returns: a ``ProviderManager`` backed by a ``DaoAuthenticationProvider``.
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider(userDetailsService);
        authenticationProvider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(authenticationProvider);
    }
}
