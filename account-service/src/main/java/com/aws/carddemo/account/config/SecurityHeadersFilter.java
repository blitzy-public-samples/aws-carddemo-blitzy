/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.account.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that stamps baseline HTTP security-response headers onto every response
 * (finding F-11).
 *
 * <h2>What it sets</h2>
 * <p>Two headers are applied to <em>all</em> responses (API, Actuator health, OpenAPI JSON, and
 * Swagger UI alike), because they are safe and beneficial everywhere and carry no downside for the
 * documentation or health surfaces:</p>
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} &mdash; forbids MIME-type sniffing, so a browser
 *       never re-interprets a JSON error body (or any served asset) as another, potentially
 *       executable, content type (CWE-430/CWE-434 class defenses).</li>
 *   <li>{@code X-Frame-Options: DENY} &mdash; refuses to let any origin embed a response in a
 *       frame/iframe, a clickjacking defense. The service exposes no framed UI (Swagger UI is a
 *       top-level document, not framed), so {@code DENY} is safe and does not impair it.</li>
 * </ul>
 *
 * <p>Three cache-suppression headers are applied <em>only</em> to the account-data API surface
 * ({@value #ACCOUNT_API_PREFIX}{@code /**}), because those responses carry sensitive financial
 * account data (balances, credit limits) that must never be persisted in a shared or browser cache
 * (AAP &sect;0.6.6):</p>
 * <ul>
 *   <li>{@code Cache-Control: no-store} &mdash; the response must not be stored anywhere.</li>
 *   <li>{@code Pragma: no-cache} &mdash; HTTP/1.0 back-compatibility for the same intent.</li>
 *   <li>{@code Expires: 0} &mdash; treats the response as already expired for any HTTP/1.0
 *       intermediary that honours {@code Expires} but not {@code Cache-Control}.</li>
 * </ul>
 * <p>The Swagger UI, OpenAPI document, and Actuator health endpoints are intentionally left
 * cacheable (only the universal {@code nosniff}/{@code DENY} pair is applied to them), so static
 * documentation assets continue to cache normally.</p>
 *
 * <h2>What it deliberately does NOT set</h2>
 * <p><strong>HSTS</strong> ({@code Strict-Transport-Security}) is a TLS-only directive that is
 * meaningful solely on an HTTPS connection. This service is deployed behind a TLS-terminating edge
 * (reverse proxy / API gateway / load balancer per AAP &sect;0.1.1); TLS does not terminate in the
 * JVM, so HSTS is the responsibility of that edge and is intentionally not emitted here to avoid a
 * misleading directive on a plaintext hop.</p>
 * <p>A restrictive <strong>Content-Security-Policy</strong> is likewise not emitted. The only
 * HTML surface this service serves is the bundled Swagger UI, which relies on inline scripts and
 * styles; a strict CSP would break it. CSP for any production browser surface is owned by the same
 * TLS/edge tier. Both HSTS and CSP are therefore documented here as edge-tier concerns rather than
 * silently omitted.</p>
 *
 * <h2>Ordering and error dispatches</h2>
 * <p>The filter is ordered early ({@link Ordered#HIGHEST_PRECEDENCE}&nbsp;+&nbsp;5, ahead of
 * {@link RequestBodySizeLimitFilter} at &nbsp;+&nbsp;10) so the headers are present even when a
 * downstream filter short-circuits with its own response (for example the body-size 400). Because a
 * container {@code sendError} / error dispatch resets the response buffer and headers before
 * forwarding to the error path, {@link #shouldNotFilterErrorDispatch()} is overridden to return
 * {@code false} so this filter runs again on the {@code ERROR} dispatch and re-applies the headers
 * to the final error envelope (including the sanitized JSON produced by {@code ApiErrorController}).
 * On that error dispatch the original request URI is recovered from
 * {@link RequestDispatcher#ERROR_REQUEST_URI} so a failed account request's error response is still
 * recognised as account-data and receives the {@code no-store} directives.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /** {@code X-Content-Type-Options} header name (no Spring {@link HttpHeaders} constant exists). */
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /** {@code X-Content-Type-Options} value forbidding MIME sniffing. */
    private static final String NOSNIFF = "nosniff";

    /** {@code X-Frame-Options} header name (no Spring {@link HttpHeaders} constant exists). */
    private static final String X_FRAME_OPTIONS = "X-Frame-Options";

    /** {@code X-Frame-Options} value refusing all framing. */
    private static final String DENY = "DENY";

    /** {@code Cache-Control} value forbidding any storage of the response. */
    private static final String NO_STORE = "no-store";

    /** {@code Pragma} value (HTTP/1.0 back-compat for {@code no-store}). */
    private static final String NO_CACHE = "no-cache";

    /** {@code Expires} value treating the response as already expired. */
    private static final String EXPIRES_ZERO = "0";

    /**
     * Path prefix of the sensitive account-data API. Responses under this prefix receive the
     * cache-suppression headers in addition to the universal pair.
     */
    private static final String ACCOUNT_API_PREFIX = "/api/v1/accounts";

    /**
     * Applies the security headers, then continues the chain.
     *
     * @param request     the inbound request
     * @param response    the outbound response
     * @param filterChain the remaining filter chain
     * @throws ServletException if a downstream component fails
     * @throws IOException      if an I/O error occurs
     */
    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain)
            throws ServletException, IOException {

        // Universal headers — safe on every surface (API, health, docs).
        response.setHeader(X_CONTENT_TYPE_OPTIONS, NOSNIFF);
        response.setHeader(X_FRAME_OPTIONS, DENY);

        // Cache suppression — only for sensitive account-data responses.
        if (isAccountApi(request)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
            response.setHeader(HttpHeaders.PRAGMA, NO_CACHE);
            response.setHeader(HttpHeaders.EXPIRES, EXPIRES_ZERO);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Determines whether the current request targets the sensitive account-data API, recognising
     * both the initial {@code REQUEST} dispatch and a subsequent {@code ERROR} dispatch.
     *
     * <p>On an {@code ERROR} dispatch the {@link HttpServletRequest#getRequestURI()} reports the
     * error target ({@code /error}) rather than the URI the client actually requested, so the
     * original URI is recovered from {@link RequestDispatcher#ERROR_REQUEST_URI}. This keeps the
     * {@code no-store} directives attached to the error envelope of a failed account request.</p>
     *
     * @param request the current request
     * @return {@code true} when the (original) request path is under {@value #ACCOUNT_API_PREFIX}
     */
    private static boolean isAccountApi(final HttpServletRequest request) {
        final Object errorUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        final String uri = (errorUri instanceof String s && !s.isEmpty())
                ? s
                : request.getRequestURI();
        return uri != null
                && (uri.equals(ACCOUNT_API_PREFIX) || uri.startsWith(ACCOUNT_API_PREFIX + "/"));
    }

    /**
     * Re-run this filter on the container {@code ERROR} dispatch so the security headers survive a
     * {@code sendError}/error-dispatch reset and are present on the final (JSON) error envelope.
     *
     * @return {@code false} — do not skip the error dispatch
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
