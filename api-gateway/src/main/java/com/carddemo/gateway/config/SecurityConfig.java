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

import com.carddemo.common.security.SecurityAuditConfig;
import com.carddemo.common.security.ManagementSecurityConfig;
import com.carddemo.common.security.RateLimitFilter;
import com.carddemo.common.security.SecurityHardening;
import com.carddemo.common.security.SessionIndexLogoutHandler;
import com.carddemo.common.security.SessionPrincipalIndex;
import com.carddemo.common.security.SessionRegistryConfig;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.request.async.WebAsyncManagerIntegrationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * :purpose: Servlet Spring Security filter chain performing application-layer
 *     route authorization for the API gateway, the single browser-facing surface of
 *     CardDemo. The legacy CICS resource definitions set ``RESSEC(NO)`` /
 *     ``CMDSEC(NO)`` on every transaction, so transaction-level security is disabled
 *     and gating is enforced in application logic (AAP 0.6.7); this chain reproduces
 *     that model by matching path prefixes to role authorities. Authentication is
 *     performed by ``auth-service``; the authenticated principal is rebuilt on every
 *     request from the shared Redis-backed ``SessionContext`` by the
 *     ``SessionContextAuthenticationFilter`` installed by
 *     {@link SecurityHardening}, so this chain declares only the authority rules and
 *     the browser-facing protections.
 * :note: CSRF is enforced here with the cookie double-submit pattern
 *     (``XSRF-TOKEN`` cookie echoed as the ``X-XSRF-TOKEN`` header, the axios
 *     default). ``/auth/**`` is exempt because it is unauthenticated and carries
 *     explicit credentials rather than ambient authority: a fresh client cannot yet
 *     hold a token, sign-on rotates the session id so login CSRF cannot fix a
 *     session, and requiring a token there would turn a genuine ``405`` or ``415``
 *     into a misleading ``403``. Because CSRF is enabled, Spring Security's logout
 *     matcher accepts ``POST /logout`` only, which closes the cross-site
 *     forced-logout vector.
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
     * :purpose: Name of the CSRF cookie the SPA echoes back as a request header; the
     *     axios default cookie name, so the client needs no bespoke handling.
     */
    public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";

    /**
     * :purpose: Name of the CSRF request header the SPA sends; the axios default.
     */
    public static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    /**
     * :purpose: ``SameSite`` attribute written on the CSRF cookie, matching the session
     *     cookie's policy so neither is ever attached to a cross-site request.
     */
    public static final String CSRF_COOKIE_SAME_SITE = "Strict";

    /**
     * :purpose: Defines the gateway authorization rules, the browser CSRF protection,
     *     and a ``401`` entry point. ``/auth/**``, ``/csrf`` and the Actuator
     *     health/info endpoints are permitted (``/actuator/prometheus`` is restricted
     *     to the ``monitoring`` principal by the shared management chain);
     *     ``/admin/**`` and ``/users/**`` require ``ROLE_ADMIN``; the menu and business
     *     route prefixes require ``ROLE_USER`` or ``ROLE_ADMIN``; every other request
     *     must be authenticated.
     * :param http: the Spring Security ``HttpSecurity`` builder.
     * :param requestsPerMinute: per-source-address request budget for the public edge.
     * :param signonRequestsPerMinute: per-source-address budget for ``/auth/**``.
     * :param cookieSecure: whether cookies are marked ``Secure``; bound to the same
     *     ``server.servlet.session.cookie.secure`` switch as the session cookie so the
     *     CSRF cookie can never be laxer than the credential it protects.
     * :param sessionPrincipalIndex: principal-to-session index the logout chain de-indexes,
     *     so the index lists only sessions that can still authorize.
     * :returns: the built ``SecurityFilterChain``.
     * :throws: Exception propagated by the ``HttpSecurity`` builder.
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Value("${carddemo.rate-limit.gateway-requests-per-minute:600}") int requestsPerMinute,
            @Value("${carddemo.rate-limit.signon-requests-per-minute:60}") int signonRequestsPerMinute,
            @Value("${server.servlet.session.cookie.secure:true}") boolean cookieSecure,
            SessionPrincipalIndex sessionPrincipalIndex)
            throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieName(CSRF_COOKIE_NAME);
        csrfTokenRepository.setHeaderName(CSRF_HEADER_NAME);
        // The token cookie is deliberately script-readable (double submit needs it), but it
        // otherwise carries the same transport policy as the session cookie: SameSite=Strict,
        // and Secure gated by the same CARDDEMO_COOKIE_SECURE switch, so a plain-HTTP local run
        // can be exercised while every deployed profile stays HTTPS-only.
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie
                .sameSite(CSRF_COOKIE_SAME_SITE)
                .secure(cookieSecure));

        SecurityHardening.apply(http)
            .authorizeHttpRequests(auth -> auth
                // The container ERROR/ASYNC dispatches must not be re-authorized: doing so
                // masks a genuine 4xx/5xx as an authorization failure. Spring Security
                // filters ALL dispatcher types by default, so an anonymous request that
                // failed with a 500 was re-evaluated on its way to /error, denied, and
                // answered with an EMPTY 403 - the real status and the error body were lost.
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                .requestMatchers("/auth/**", "/csrf").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/admin/**", "/users/**").hasRole("ADMIN")
                // The CREASTMT / CBSTM03A statement job stream had no online transaction: it
                // was submitted by an operator, so its route carries the administrator
                // authority while CORPT00's own POST /reports stays open to a signed-on user.
                .requestMatchers(HttpMethod.POST, "/reports/statements").hasRole("ADMIN")
                .requestMatchers("/menu/**", "/accounts/**", "/cards/**", "/transactions/**",
                        "/billpay/**", "/reports/**", "/batch/**").hasAnyRole("USER", "ADMIN")
                .anyRequest().authenticated())
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(
                        PathPatternRequestMatcher.withDefaults().matcher("/auth/**")))
            .addFilterAfter(new CsrfCookieMaterializingFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
            // Two source-address budgets are applied at the network edge, the only
            // place the client address is authentic: a global budget that bounds a
            // 401/403 storm, and a much tighter sign-on budget that bounds credential
            // stuffing. The account-level lockout in auth-service remains the
            // topology-independent brute-force control.
            .addFilterBefore(new RateLimitFilter(requestsPerMinute, "/"),
                    WebAsyncManagerIntegrationFilter.class)
            .addFilterBefore(new RateLimitFilter(signonRequestsPerMinute, "/auth/"),
                    WebAsyncManagerIntegrationFilter.class)
            .logout(logout -> logout
                .logoutUrl("/logout")
                // De-index the session before it is invalidated, so a later administrator
                // revocation acts on live sessions only and reports a truthful count.
                .addLogoutHandler(new SessionIndexLogoutHandler(sessionPrincipalIndex))
                .clearAuthentication(true)
                .invalidateHttpSession(true)
                .deleteCookies(CSRF_COOKIE_NAME)
                .logoutSuccessHandler((request, response, authentication) ->
                        response.setStatus(org.springframework.http.HttpStatus.NO_CONTENT.value())));
        return http.build();
    }
}
