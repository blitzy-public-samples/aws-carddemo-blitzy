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

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * :purpose: Single definition of the HTTP-security posture shared by the API gateway
 *     and every CardDemo microservice, so the API surface is hardened identically
 *     everywhere instead of per-service. It applies the stateless security-context
 *     policy, disables the saved-request cache, installs the shared
 *     session-derived authentication filter, writes the hardened response headers,
 *     and turns an unauthenticated request into ``401`` instead of a login redirect
 *     or an empty ``403``.
 * :output: The supplied {@link HttpSecurity} builder, configured in place.
 * :note: This class only configures cross-cutting concerns. Each service still
 *     declares its own authorization rules, because the route-to-role mapping is
 *     service specific (it re-expresses the per-transaction gating of
 *     ``app/csd/CARDDEMO.CSD``).
 */
public final class SecurityHardening {

    /**
     * :purpose: Content-Security-Policy applied to API responses. The API returns
     *     JSON only, so every fetch directive is denied; ``frame-ancestors 'none'``
     *     complements ``X-Frame-Options: DENY``.
     */
    public static final String API_CONTENT_SECURITY_POLICY =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /**
     * :purpose: Referrer-Policy applied to API responses so a resource path (which
     *     can carry an account or card identifier) is never leaked cross-origin.
     */
    public static final String REFERRER_POLICY = "no-referrer";

    /**
     * :purpose: Permissions-Policy applied to API responses; the JSON API needs no
     *     browser feature, so the powerful features are switched off explicitly.
     */
    public static final String PERMISSIONS_POLICY =
            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()";

    /**
     * :purpose: Request-scoped security-context repository shared by every CardDemo
     *     chain. It is stateless and holds nothing between requests, so a single
     *     instance is safe; using one instance guarantees the chain and the
     *     session-derived authentication filter agree on where the context lives.
     */
    private static final SecurityContextRepository REQUEST_CONTEXT_REPOSITORY =
            new RequestAttributeSecurityContextRepository();

    /**
     * :purpose: Prevent instantiation of this stateless helper.
     */
    private SecurityHardening() {
    }

    /**
     * :purpose: Apply the shared hardening to a filter chain: no security-context
     *     session persistence, no saved-request cache (so an unauthenticated request
     *     never mints a Redis session), the shared session-derived authentication
     *     filter, hardened response headers, and a ``401`` entry point.
     * :param http: the ``HttpSecurity`` builder being configured.
     * :returns: the same builder, for fluent chaining.
     * :raises Exception: if a configurer rejects the configuration.
     */
    public static HttpSecurity apply(HttpSecurity http) throws Exception {
        return apply(http, new SessionContextAuthenticationFilter(REQUEST_CONTEXT_REPOSITORY));
    }

    /**
     * :purpose: Apply the shared hardening using a caller-supplied authentication
     *     filter instance (used where the filter is a managed bean).
     * :param http: the ``HttpSecurity`` builder being configured.
     * :param sessionAuthenticationFilter: filter that rebuilds the authentication
     *     from the shared session context.
     * :returns: the same builder, for fluent chaining.
     * :raises Exception: if a configurer rejects the configuration.
     */
    public static HttpSecurity apply(HttpSecurity http,
                                     SessionContextAuthenticationFilter sessionAuthenticationFilter) throws Exception {
        http
            // The authenticated principal is rebuilt per request from the shared
            // Redis-backed SessionContext, so Spring Security itself keeps no
            // session state and creates no session (CWE-770: no anonymous session
            // is ever persisted).
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // The restored context is published to - and therefore discoverable in -
            // this request-scoped repository. That is what tells SessionManagementFilter
            // the context was LOADED for this request instead of produced by an
            // authentication during it; otherwise it applies its session-authentication
            // strategy and changes the session id on every single request, which
            // discards the shared session immediately after sign-on. Session-id
            // rotation happens exactly once, in the sign-on service.
            .securityContext(context -> context.securityContextRepository(REQUEST_CONTEXT_REPOSITORY))
            // A REST API never replays a saved request after login, and persisting
            // SPRING_SECURITY_SAVED_REQUEST both creates sessions for anonymous
            // callers and puts a non-CardDemo type into the shared session payload.
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            // Installed immediately before CsrfFilter, which is where Spring Security's
            // own SecurityContextHolderFilter would have restored a persisted context.
            // Position matters twice over:
            //  - SessionManagementFilter treats any non-anonymous principal it sees as an
            //    authentication that just happened and applies its session-authentication
            //    strategy, which changes the session id on EVERY request and so discards
            //    the shared session immediately after sign-on. Publishing the restored
            //    context to the request-scoped repository above is what suppresses that:
            //    the filter short-circuits when the repository already contains a context
            //    for the request, so rotation happens exactly once, in the sign-on service.
            //  - CsrfFilter runs before AnonymousAuthenticationFilter and denies a write
            //    that carries no token. Restoring the principal BEFORE it is what lets that
            //    denial be classified correctly: an authenticated caller answers 403, while
            //    an unauthenticated one is handed to the 401 entry point. Restoring it later
            //    left every token-less write looking unauthenticated.
            .addFilterBefore(sessionAuthenticationFilter, CsrfFilter.class)
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(API_CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(
                        org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                .ReferrerPolicy.NO_REFERRER))
                .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                .cacheControl(cacheControl -> {
                    // Spring Security's default no-store/no-cache directives are kept:
                    // API payloads can carry account, card, and customer data.
                }))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new AuditingAuthenticationEntryPoint())
                .accessDeniedHandler(new AuditingAccessDeniedHandler()));
        return http;
    }
}
