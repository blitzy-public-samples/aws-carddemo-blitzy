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

import com.carddemo.common.security.PasswordEncoderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * :purpose: Declares the HTTP security policy for the User Management
 *     microservice, re-platforming the administrator-only CICS/RACF
 *     transaction-security model of the legacy user programs
 *     ``COUSR00C``-``COUSR03C`` (transactions ``CU00``-``CU03``). Every
 *     user-CRUD route requires an authenticated principal bearing the
 *     ``ROLE_ADMIN`` authority.
 * :note: Session topology (finding CR-08) — this service performs no sign-on;
 *     the ``auth-service`` authenticates and creates the session. The
 *     ``SecurityContext`` is persisted in the HTTP session, which is backed by
 *     Spring Session (Redis), so it is shared across every CardDemo service
 *     (the COMMAREA replacement). The policy is therefore
 *     ``SessionCreationPolicy.NEVER``: this service reuses an existing shared
 *     session to load the authenticated principal and role but never creates a
 *     new one, so an unauthenticated caller cannot obtain a session here. Because
 *     authentication is cookie/session based, CSRF protection is enabled with a
 *     ``CookieCsrfTokenRepository`` so the SPA can echo the ``XSRF-TOKEN`` cookie
 *     as a request header; per-request ``httpBasic`` and ``formLogin`` mechanisms
 *     stay disabled because the principal always arrives via the shared session.
 * :note: Management-endpoint exposure (finding MJ-16) — only the Kubernetes
 *     liveness and readiness probes are anonymous; ``/actuator/prometheus``,
 *     ``/actuator/metrics``, ``/actuator/info`` and any health detail require
 *     ``ROLE_ADMIN`` so telemetry is never anonymously exposed. Prometheus
 *     scrapes with a credential or over an isolated network path (configured in
 *     ``application.yml`` and the Kubernetes ``NetworkPolicy``).
 * :note: This configuration also supplies the shared delegating password encoder
 *     ({@link PasswordEncoderFactory}) used to hash security-user credentials at
 *     rest, replacing the legacy plaintext comparison (finding MJ-18).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * :purpose: Builds the shared-session REST security filter chain: enables
     *     cookie-based CSRF protection for the SPA, reuses (but never creates) the
     *     Redis-backed shared session to load the authenticated principal, permits
     *     only the anonymous Kubernetes liveness/readiness probes, and requires the
     *     ``ROLE_ADMIN`` authority for every other request — including the
     *     remaining management endpoints — because all user-CRUD functions are
     *     administrator-only and telemetry must not be anonymously exposed.
     * :param http: the Spring Security ``HttpSecurity`` builder.
     * :returns: the configured ``SecurityFilterChain``.
     * :raises Exception: if the filter chain cannot be built.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/actuator/health/liveness",
                    "/actuator/health/readiness").permitAll()
                .anyRequest().hasRole("ADMIN"))
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }

    /**
     * :purpose: Supplies the shared delegating password encoder used to hash
     *     security-user credentials at rest, replacing the legacy plaintext
     *     comparison of ``COSGN00C``. The user service injects this encoder to
     *     hash a password on user creation and to re-hash it on password change.
     * :returns: the shared delegating ``{bcrypt}`` encoder from
     *     {@link PasswordEncoderFactory}, matching the encoding policy used by the
     *     auth-service so credentials are portable across services.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactory.createDelegatingPasswordEncoder();
    }
}
