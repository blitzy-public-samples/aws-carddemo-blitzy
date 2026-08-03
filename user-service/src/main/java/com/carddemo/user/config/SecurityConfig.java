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

import com.carddemo.common.security.SessionRegistryConfig;
import com.carddemo.common.security.SecurityAuditConfig;
import com.carddemo.common.security.ManagementSecurityConfig;
import com.carddemo.common.security.PasswordEncoderFactory;
import com.carddemo.common.security.SecurityHardening;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * :purpose: Declares the HTTP security policy for the User Management
 *     microservice, re-platforming the administrator-only CICS/RACF
 *     transaction-security model of the legacy user programs
 *     ``COUSR00C``-``COUSR03C`` (transactions ``CU00``-``CU03``). Every
 *     user-CRUD route requires an authenticated principal bearing the
 *     ``ROLE_ADMIN`` authority.
 * :note: Session topology — this service performs no sign-on; the ``auth-service``
 *     authenticates and writes the shared, Redis-backed ``SessionContext`` (the
 *     COMMAREA replacement). The shared ``SessionContextAuthenticationFilter``
 *     installed by {@link SecurityHardening} rebuilds the authenticated principal and
 *     its role from that context on every request, using an EXISTING session only, so
 *     an unauthenticated caller can neither authenticate here nor cause a session to be
 *     created. ``httpBasic`` and ``formLogin`` stay disabled because the principal
 *     always arrives through the shared session.
 * :note: CSRF — this service is never called directly by a browser: the api-gateway is
 *     the only browser-facing surface and enforces cookie double-submit CSRF for every
 *     state-changing request. Enforcing CSRF a second time here would require a token
 *     this service never issues, which is what made every administrator write fail.
 *     The single, coherent model is recorded in docs/decision-log.md.
 * :note: Management-endpoint exposure — the anonymous surface is the health status and
 *     the Kubernetes liveness/readiness probes (status only; detail still requires an
 *     authorized principal via ``show-details: when-authorized``). This matches the
 *     other eight services and lets the Compose/Kubernetes probe on
 *     ``/actuator/health`` succeed. ``/actuator/prometheus`` and
 *     ``/actuator/metrics/**`` are restricted to the dedicated ``monitoring``
 *     principal by the shared ``ManagementSecurityConfig`` chain, and every remaining
 *     management endpoint requires ``ROLE_ADMIN``.
 * :note: This configuration also supplies the shared delegating password encoder
 *     ({@link PasswordEncoderFactory}) used to hash security-user credentials at
 *     rest, replacing the legacy plaintext comparison (finding MJ-18).
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
     * :purpose: Builds the shared-session REST security filter chain: applies the
     *     shared hardening (session-derived principal, no persisted security context,
     *     no saved-request cache, hardened response headers, ``401`` entry point),
     *     permits the anonymous health status and the Kubernetes liveness/readiness
     *     probes plus the container ``ERROR`` dispatch, and requires the ``ROLE_ADMIN``
     *     authority for every other request because all user-CRUD functions are
     *     administrator-only and telemetry must not be anonymously exposed.
     * :param http: the Spring Security ``HttpSecurity`` builder.
     * :returns: the configured ``SecurityFilterChain``.
     * :raises Exception: if the filter chain cannot be built.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        SecurityHardening.apply(http)
            // CSRF is enforced once, at the api-gateway (the only browser-facing
            // surface). See the class note and docs/decision-log.md.
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize
                // The container ERROR dispatch must not be re-authorized: doing so
                // masks a genuine 4xx/5xx as an authorization failure.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // Same probe set as every other service: the aggregate health endpoint, the
                // liveness/readiness groups underneath it, and the build identity. The
                // metrics scrape stays authenticated through ManagementSecurityConfig.
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info").permitAll()
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
