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
package com.carddemo.common.security;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * :purpose: Protect the Micrometer/Prometheus telemetry endpoints of every CardDemo
 *     service with a dedicated, least-privilege ``monitoring`` principal, so metrics
 *     are never anonymously readable (CWE-200) while the Prometheus scrape configured
 *     in ``observability/prometheus.yml`` (HTTP basic, ``username: monitoring``,
 *     password from a mounted secret file) keeps working.
 * :output: A highest-precedence {@link SecurityFilterChain} covering
 *     ``/actuator/prometheus`` and ``/actuator/metrics/**`` that requires the
 *     ``ROLE_MONITORING`` authority over HTTP basic, plus the read-only in-memory
 *     {@link UserDetailsService} that backs that principal.
 * :note: Declaring this {@link UserDetailsService} also stops Spring Boot's
 *     ``UserDetailsServiceAutoConfiguration`` from creating an undocumented ``user``
 *     principal and printing its generated password to the log (CWE-798 / CWE-532).
 * :note: The credential is supplied by the environment
 *     (``carddemo.monitoring.password`` / ``MONITORING_PASSWORD``) and is never
 *     committed. When no password is configured the principal is created with a
 *     random, unguessable credential, so the endpoints stay closed instead of opening.
 * :note: A service activates this configuration with
 *     ``@Import(ManagementSecurityConfig.class)``, matching the ``@Import`` convention
 *     of the other ``carddemo-common`` configurations. The encoder is created locally
 *     rather than exposed as a bean so services that already publish a
 *     ``PasswordEncoder`` bean are unaffected.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
public class ManagementSecurityConfig {

    /**
     * :purpose: User name of the least-privilege scrape principal; must match
     *     ``basic_auth.username`` in ``observability/prometheus.yml``.
     */
    public static final String MONITORING_USERNAME = "monitoring";

    /**
     * :purpose: Role granted to the scrape principal; it authorizes nothing except the
     *     telemetry endpoints below.
     */
    public static final String MONITORING_ROLE = "MONITORING";

    /**
     * :purpose: Bean name of the in-memory store holding the scrape principal; it is
     *     qualified explicitly because ``auth-service`` also publishes a
     *     database-backed {@link UserDetailsService}.
     */
    public static final String MONITORING_USER_DETAILS_SERVICE = "monitoringUserDetailsService";

    /**
     * :purpose: Telemetry endpoints restricted to the scrape principal.
     */
    private static final String[] METRICS_ENDPOINTS = {"/actuator/prometheus", "/actuator/metrics/**"};

    /**
     * :purpose: Build the read-only in-memory store holding the single ``monitoring``
     *     principal, hashing the configured password with the shared encoding policy.
     * :param rawPassword: the configured scrape password; empty when unset.
     * :returns: an {@link InMemoryUserDetailsManager} containing only the scrape
     *     principal.
     */
    @Bean(MONITORING_USER_DETAILS_SERVICE)
    UserDetailsService monitoringUserDetailsService(
            @Value("${carddemo.monitoring.password:${MONITORING_PASSWORD:}}") String rawPassword) {
        PasswordEncoder encoder = PasswordEncoderFactory.createDelegatingPasswordEncoder();
        String credential = rawPassword == null || rawPassword.isBlank()
                ? UUID.randomUUID().toString()
                : rawPassword;
        return new InMemoryUserDetailsManager(User.withUsername(MONITORING_USERNAME)
                .password(encoder.encode(credential))
                .roles(MONITORING_ROLE)
                .build());
    }

    /**
     * :purpose: Declare the highest-precedence chain that authenticates the Prometheus
     *     scrape with HTTP basic and authorizes only the ``monitoring`` principal.
     * :param http: the Spring Security ``HttpSecurity`` builder.
     * :param monitoringUserDetailsService: store holding the scrape principal.
     * :param applicationEventPublisher: context publisher wired into the locally built
     *     ``ProviderManager`` so a rejected scrape credential still reaches the audit
     *     trail. A manually constructed manager otherwise keeps the no-op publisher
     *     Spring Security defaults to, and a credential probe against the telemetry
     *     endpoint would leave no record at all.
     * :returns: the built telemetry {@link SecurityFilterChain}.
     * :raises Exception: if the filter chain cannot be built.
     */
    @Bean
    @Order(1)
    SecurityFilterChain managementMetricsSecurityFilterChain(
            HttpSecurity http,
            @Qualifier(MONITORING_USER_DETAILS_SERVICE) UserDetailsService monitoringUserDetailsService,
            ApplicationEventPublisher applicationEventPublisher)
            throws Exception {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(monitoringUserDetailsService);
        provider.setPasswordEncoder(PasswordEncoderFactory.createDelegatingPasswordEncoder());
        ProviderManager authenticationManager = new ProviderManager(provider);
        authenticationManager.setAuthenticationEventPublisher(
                new DefaultAuthenticationEventPublisher(applicationEventPublisher));
        http
            .securityMatcher(METRICS_ENDPOINTS)
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole(MONITORING_ROLE))
            .authenticationManager(authenticationManager)
            .httpBasic(Customizer.withDefaults())
            .formLogin(formLogin -> formLogin.disable())
            .exceptionHandling(exceptions -> exceptions
                .accessDeniedHandler(new AuditingAccessDeniedHandler()));
        return http.build();
    }
}
