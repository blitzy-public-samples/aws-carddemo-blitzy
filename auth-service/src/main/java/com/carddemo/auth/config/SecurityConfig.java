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

import com.carddemo.common.security.SessionRegistryConfig;
import com.carddemo.common.security.SecurityAuditConfig;
import com.carddemo.common.security.ManagementSecurityConfig;
import com.carddemo.auth.security.UserDetailsServiceImpl;
import com.carddemo.common.security.SecurityHardening;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * :purpose: Declares the HTTP security policy for the authentication
 *     microservice, replacing the CICS/RACF transaction-security model
 *     (transaction ``CC00`` -> program ``COSGN00C``, file ``USRSEC``). The
 *     sign-on endpoint and the Actuator health/probe endpoints are public;
 *     ``/actuator/prometheus`` is restricted to the dedicated ``monitoring``
 *     principal by the shared ``ManagementSecurityConfig`` chain, and every other
 *     route requires an authenticated principal bearing a ``ROLE_ADMIN`` or
 *     ``ROLE_USER`` authority. The security context is stateless; cross-request
 *     session state is held externally in Spring Session (Redis), not in the
 *     security context, and the shared ``SecurityHardening`` helper rebuilds the
 *     principal from that session on every request.
 */
@Import({
        ManagementSecurityConfig.class,
        SecurityAuditConfig.class,
        SessionRegistryConfig.class
})
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * :purpose: Builds the stateless REST security filter chain: applies the shared
     *     hardening (no persisted security context, no saved-request cache, hardened
     *     response headers, ``401`` entry point, session-derived principal), permits
     *     the public sign-on and Actuator probe endpoints plus the container ``ERROR``
     *     dispatch, and requires authentication for every other request.
     * :param http: the Spring Security ``HttpSecurity`` builder.
     * :returns: the configured ``SecurityFilterChain``.
     * :raises Exception: if the filter chain cannot be built.
     * :note: Credential guessing is bounded here by the account-level lockout in
     *     ``LoginAttemptService`` (the RACF revoke-after-N equivalent), which is keyed
     *     on the security-user id and is therefore unaffected by network topology.
     *     Source-address throttling is applied at the api-gateway instead, because
     *     every request reaching this service carries the gateway's address and an
     *     address-keyed budget here would throttle all users collectively.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        SecurityHardening.apply(http)
            // CSRF is not applied to the sign-on chain: the endpoint is
            // unauthenticated, carries no ambient authority, and is the one request a
            // fresh client makes before it can hold a token. CSRF for authenticated,
            // state-changing traffic is enforced at the api-gateway, the only
            // browser-facing surface. See docs/decision-log.md.
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize
                // The container ERROR dispatch must not be re-authorized: doing so
                // turned a genuine 400/405/415/500 into an empty 403 and hid the real
                // failure from operators.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Permitted for every method, not just POST: only POST is mapped, so
                // an unsupported method must reach the dispatcher and be answered with
                // 405 (plus an Allow header) instead of being masked as an
                // authorization failure.
                .requestMatchers("/auth/signon").permitAll()
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info").permitAll()
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
    public AuthenticationManager authenticationManager(UserDetailsServiceImpl userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider(userDetailsService);
        authenticationProvider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(authenticationProvider);
    }
}
