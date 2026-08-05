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
package com.carddemo.transaction.config;

import com.carddemo.common.security.ManagementSecurityConfig;
import com.carddemo.common.security.SecurityAuditConfig;
import com.carddemo.common.security.SecurityHardening;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * :purpose: Declares the HTTP security policy for the Transaction microservice
 *     (re-platforming COBOL ``COTRN00C`` / ``COTRN01C`` / ``COTRN02C``, CICS transactions ``CT00``, ``CT01`` and ``CT02``). Authentication is performed once by ``auth-service``; the shared
 *     ``SessionContextAuthenticationFilter`` installed by {@link SecurityHardening}
 *     rebuilds the authenticated principal and its ``ROLE_ADMIN`` / ``ROLE_USER``
 *     authority from the shared, Redis-backed ``SessionContext`` (the CICS COMMAREA
 *     replacement) on every request, so this service enforces authorization itself.
 * :note: Defence in depth: the api-gateway is NOT a trust boundary. A request that
 *     reaches this service directly over the internal network - a published port, a
 *     sidecar, a compromised pod, or an unapplied NetworkPolicy - is still refused
 *     without a valid signed-on session, so no unauthenticated caller can read
 *     customer data or mutate financial state.
 * :note: The anonymous surface is deliberately limited to the Actuator health status
 *     and the Kubernetes liveness/readiness probes (health detail still requires an
 *     authorized principal through ``show-details: when-authorized``).
 *     ``/actuator/prometheus`` and ``/actuator/metrics/**`` are restricted to the
 *     dedicated ``monitoring`` principal by the imported
 *     {@link ManagementSecurityConfig} chain.
 * :note: CSRF is enforced once, at the api-gateway (the only browser-facing surface);
 *     enforcing it again here would demand a token this service never issues. The
 *     single coherent model is recorded in docs/decision-log.md.
 */
@Configuration
@EnableWebSecurity
@Import({ManagementSecurityConfig.class, SecurityAuditConfig.class})
public class SecurityConfig {

    /**
     * :purpose: Builds the stateless REST security filter chain: applies the shared
     *     hardening (session-derived principal, no persisted security context, no
     *     saved-request cache, hardened response headers, ``401`` entry point,
     *     audited denials), permits the anonymous health endpoints and the container
     *     ``ERROR`` dispatch, and requires a ``ROLE_USER`` or ``ROLE_ADMIN`` authority for every other request.
     * :param http: the Spring Security ``HttpSecurity`` builder.
     * :returns: the configured ``SecurityFilterChain``.
     * :raises Exception: if the filter chain cannot be built.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        SecurityHardening.apply(http)
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize
                // The container ERROR dispatch must not be re-authorized: doing so
                // masks a genuine 4xx/5xx as an authorization failure.
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info").permitAll()
                .anyRequest().hasAnyRole("USER", "ADMIN"))
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }
}
