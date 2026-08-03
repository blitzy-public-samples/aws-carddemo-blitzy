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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * :purpose: Bound the request rate a single client address may sustain, so credential
 *     guessing and endpoint probing cannot run at full speed (CWE-307). It is the
 *     modern counterpart of the RACF throttling that protected the legacy ``USRSEC``
 *     sign-on, and it complements the per-account lockout applied inside the
 *     authentication service.
 * :output: The original response while the client is within budget, or an empty
 *     ``429 Too Many Requests`` carrying ``Retry-After`` once the budget is exhausted.
 * :note: The counter is a fixed one-minute window held in memory per instance. That is
 *     deliberate: it adds no infrastructure dependency, cannot fail open when a store
 *     is unavailable, and each replica enforces the budget for the traffic it actually
 *     serves. The map is bounded by {@link #MAX_TRACKED_CLIENTS}; when the bound is
 *     reached the window is reset rather than allowed to grow without limit.
 * :note: Several instances may be stacked in one chain to apply a broad budget and a
 *     tighter budget for a sensitive prefix at the same time. Each therefore scopes its
 *     own {@code OncePerRequestFilter} marker by path prefix
 *     ({@link #getAlreadyFilteredAttributeName()}); without that scoping the first
 *     instance to run would mark the request as already filtered and every later
 *     instance would silently skip itself, leaving the tighter budget unenforced.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * :purpose: Length of the fixed counting window.
     */
    public static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * :purpose: Upper bound on distinct client keys tracked at once, so the counter
     *     cannot itself become a memory-exhaustion vector.
     */
    public static final int MAX_TRACKED_CLIENTS = 10_000;

    private final int maxRequestsPerWindow;
    private final String pathPrefix;
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private volatile long windowStartedAtMillis = System.currentTimeMillis();

    /**
     * :purpose: Create the filter.
     * :param maxRequestsPerWindow: requests a single client address may issue per
     *     window; values below one disable throttling.
     * :param pathPrefix: request-path prefix the limit applies to, or ``null``/empty to
     *     apply it to every request.
     */
    public RateLimitFilter(int maxRequestsPerWindow, String pathPrefix) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.pathPrefix = pathPrefix == null ? "" : pathPrefix;
    }

    /**
     * :purpose: Count the request against the caller's budget and reject it once the
     *     budget is exhausted.
     * :param request: the current HTTP request.
     * :param response: the current HTTP response.
     * :param filterChain: the remainder of the filter chain.
     * :raises ServletException: if a downstream filter or the servlet fails.
     * :raises IOException: if request or response I/O fails.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (maxRequestsPerWindow < 1 || !appliesTo(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        rollWindowIfElapsed();
        String clientKey = clientKey(request);
        AtomicInteger counter = counters.computeIfAbsent(clientKey, key -> new AtomicInteger());
        if (counter.incrementAndGet() > maxRequestsPerWindow) {
            SecurityAuditLogger.rateLimited(clientKey, request);
            if (!response.isCommitted()) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", Long.toString(remainingWindowSeconds()));
            }
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * :purpose: Scope this instance's "already filtered" request marker to its path
     *     prefix, so stacking a broad budget and a prefix-specific budget in one chain
     *     leaves both of them enforced.
     * :returns: the request-attribute name unique to this instance's scope.
     */
    @Override
    protected String getAlreadyFilteredAttributeName() {
        return RateLimitFilter.class.getName() + ":" + (pathPrefix.isEmpty() ? "/" : pathPrefix) + ".FILTERED";
    }

    /**
     * :purpose: Decide whether the configured prefix covers this request.
     * :param request: the current HTTP request.
     * :returns: ``true`` when the limit applies.
     */
    private boolean appliesTo(HttpServletRequest request) {
        if (pathPrefix.isEmpty()) {
            return true;
        }
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(pathPrefix);
    }

    /**
     * :purpose: Start a new counting window once the current one has elapsed, or when
     *     the tracked-client bound is reached.
     */
    private void rollWindowIfElapsed() {
        long now = System.currentTimeMillis();
        if (now - windowStartedAtMillis >= WINDOW.toMillis() || counters.size() >= MAX_TRACKED_CLIENTS) {
            synchronized (this) {
                if (now - windowStartedAtMillis >= WINDOW.toMillis() || counters.size() >= MAX_TRACKED_CLIENTS) {
                    counters.clear();
                    windowStartedAtMillis = now;
                }
            }
        }
    }

    /**
     * :purpose: Seconds remaining in the current window, for the ``Retry-After`` header.
     * :returns: a value between one and the window length in seconds.
     */
    private long remainingWindowSeconds() {
        long elapsed = System.currentTimeMillis() - windowStartedAtMillis;
        long remaining = (WINDOW.toMillis() - elapsed) / 1000L;
        return Math.max(1L, remaining);
    }

    /**
     * :purpose: Identify the caller for counting purposes.
     * :param request: the current HTTP request.
     * :returns: the remote address, or ``unknown`` when the container reports none.
     *     Forwarded headers are deliberately NOT trusted: they are attacker-controlled
     *     unless a validated proxy chain is configured.
     */
    private String clientKey(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;
    }
}
