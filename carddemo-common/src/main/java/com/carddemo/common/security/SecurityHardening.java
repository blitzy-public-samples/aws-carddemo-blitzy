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
     * :purpose: Request header a TLS-terminating edge (ingress, load balancer, or the SPA's
     *     nginx) sets to report the scheme the CLIENT used, which is the only way a service
     *     behind that edge can know the request arrived over TLS.
     */
    private static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

    /**
     * :purpose: The value of {@link #FORWARDED_PROTO_HEADER} that means the client hop was TLS.
     */
    private static final String HTTPS_SCHEME = "https";

    /**
     * :purpose: ``max-age`` published with ``Strict-Transport-Security``, in seconds: one year,
     *     the value the HSTS preload requirements state.
     */
    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

    /**
     * :purpose: Decide whether a response may assert HSTS. Spring Security's default matcher
     *     is ``request.isSecure()`` alone, which is FALSE for every request in this topology
     *     because TLS is terminated at the edge and the hop into the service is plain HTTP --
     *     so no surface emitted the header at all, and AAP 0.6.7 ("all traffic uses TLS")
     *     had no enforcement on the wire. This matcher additionally accepts a request whose
     *     edge reported ``X-Forwarded-Proto: https``.
     * :note: Consulting the forwarded header HERE, in the one header writer that needs it,
     *     is deliberate and is NOT the same as enabling ``server.forward-headers-strategy``.
     *     That property installs ``ForwardedHeaderFilter``, which also rewrites
     *     ``getRemoteAddr()`` from ``X-Forwarded-For`` -- a header an internet client controls
     *     -- and the gateway's rate limiter and the security audit trail both read the remote
     *     address. Enabling it would hand an attacker a per-request rate-limit key and would
     *     write spoofed source addresses into the audit log. Nothing but the HSTS decision
     *     trusts this header, and an asserted HSTS policy is not something an attacker can
     *     turn against another caller: a response's headers are derived from that request's
     *     own headers.
     */
    private static final org.springframework.security.web.util.matcher.RequestMatcher HSTS_MATCHER =
            request -> request.isSecure() || forwardedOverHttps(request);

    /**
     * :purpose: Report whether the edge said the CLIENT hop used TLS.
     * :param request: the current request.
     * :returns: ``true`` when ``X-Forwarded-Proto`` names ``https`` for the client hop.
     * :note: Only the FIRST value is read. Each proxy appends its own hop, and the api-gateway
     *     appends too (``x-forwarded-request-headers-filter.protoAppend`` defaults to on), so a
     *     request that entered over TLS and was proxied twice arrives as ``https,http``. The
     *     leftmost entry is the client hop by convention, and comparing the whole header would
     *     have made the header silent for exactly the multi-hop topology it exists to serve.
     */
    private static boolean forwardedOverHttps(jakarta.servlet.http.HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_PROTO_HEADER);
        if (forwarded == null) {
            return false;
        }
        int separator = forwarded.indexOf(',');
        String clientHop = separator < 0 ? forwarded : forwarded.substring(0, separator);
        return HTTPS_SCHEME.equalsIgnoreCase(clientHop.trim());
    }

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
            .headers(SecurityHardening::applyResponseHeaders)
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new AuditingAuthenticationEntryPoint())
                .accessDeniedHandler(new AuditingAccessDeniedHandler()));
        return http;
    }

    /**
     * :purpose: Write the hardened response headers. Extracted so that a chain which is NOT
     *     the business API chain -- the management/telemetry chain, which has its own
     *     ``securityMatcher`` and therefore its own header configurer -- emits the IDENTICAL
     *     set. It previously did not: the ``/actuator/prometheus`` ``401`` challenge carried
     *     ``X-Content-Type-Options``, ``X-Frame-Options`` and ``Cache-Control`` but no CSP,
     *     no ``Referrer-Policy`` and no ``Permissions-Policy``, so one surface of every
     *     service answered with a weaker header set than the rest.
     * :param headers: the header configurer of the chain being hardened.
     * :returns: nothing; the configurer is mutated in place.
     */
    public static void applyResponseHeaders(
            org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<HttpSecurity> headers) {
        headers
            .contentSecurityPolicy(csp -> csp.policyDirectives(API_CONTENT_SECURITY_POLICY))
            .referrerPolicy(referrer -> referrer.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                            .ReferrerPolicy.NO_REFERRER))
            .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
            // HSTS is asserted whenever the edge reports the client hop was TLS. Spring
            // Security's default matcher (isSecure() only) never matched behind a
            // terminating proxy, so the header was absent everywhere.
            .httpStrictTransportSecurity(hsts -> hsts
                    .requestMatcher(HSTS_MATCHER)
                    .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)
                    .includeSubDomains(true))
            .cacheControl(cacheControl -> {
                // Spring Security's default no-store/no-cache directives are kept:
                // API payloads can carry account, card, and customer data.
            });
    }
}
