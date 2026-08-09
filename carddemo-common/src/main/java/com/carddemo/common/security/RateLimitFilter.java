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
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * :purpose: Bound the request rate a single caller may sustain, so credential
 *     guessing and endpoint probing cannot run at full speed (CWE-307). It is the
 *     modern counterpart of the RACF throttling that protected the legacy ``USRSEC``
 *     sign-on, and it complements the per-account lockout applied inside the
 *     authentication service.
 * :output: The original response while the client is within budget, or a
 *     ``429 Too Many Requests`` carrying ``Retry-After`` and the shared ``ErrorResponse``
 *     envelope once the budget is exhausted, so a throttled caller is told that waiting is
 *     the remedy instead of receiving a zero-byte response it cannot act on.
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
 * :note: The counted identity is selectable. An address-keyed budget is a SHARED budget:
 *     every caller behind one NAT or load-balancer address divides it, so a 150-user
 *     population from a single source received a fraction of the budget each. An
 *     already-signed-on caller can therefore be counted individually instead; see the
 *     three-argument constructor.
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

    /**
     * :purpose: Prefix distinguishing a per-caller counting key from a per-address one, so
     *     the two key spaces can never collide inside the one counter map.
     */
    private static final String CALLER_KEY_PREFIX = "caller:";

    /**
     * The identity a budget is counted against.
     *
     * :purpose: Let a chain stack complementary budgets — one for signed-on callers and one
     *     for the anonymous traffic that has no caller identity yet — without either
     *     counting the other's requests.
     */
    public enum CountedIdentity {

        /**
         * :purpose: Count EVERY matching request against its source address. The only
         *     honest identity where no caller is authenticated, which is why the sign-on
         *     prefix uses it; note that it is a SHARED budget for everyone behind one NAT
         *     or load-balancer address.
         */
        SOURCE_ADDRESS,

        /**
         * :purpose: Count only requests that carry an established session, each against its
         *     own session. Gives every signed-on caller the full budget, so a population
         *     sharing one source address is not divided by its own size.
         */
        SIGNED_ON_CALLER,

        /**
         * :purpose: Count only requests that carry NO established session, against their
         *     source address. The complement of {@link #SIGNED_ON_CALLER}: together the two
         *     cover every request exactly once.
         */
        ANONYMOUS_SOURCE_ADDRESS
    }

    private final int maxRequestsPerWindow;
    private final String pathPrefix;
    private final CountedIdentity countedIdentity;
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private volatile long windowStartedAtMillis = System.currentTimeMillis();

    /**
     * :purpose: Create an address-keyed filter that counts every matching request.
     * :param maxRequestsPerWindow: requests a single client address may issue per
     *     window; values below one disable throttling.
     * :param pathPrefix: request-path prefix the limit applies to, or ``null``/empty to
     *     apply it to every request.
     */
    public RateLimitFilter(int maxRequestsPerWindow, String pathPrefix) {
        this(maxRequestsPerWindow, pathPrefix, CountedIdentity.SOURCE_ADDRESS);
    }

    /**
     * :purpose: Create the filter over an explicit counted identity.
     * :param maxRequestsPerWindow: requests one counted identity may issue per window;
     *     values below one disable throttling.
     * :param pathPrefix: request-path prefix the limit applies to, or ``null``/empty to
     *     apply it to every request.
     * :param countedIdentity: which identity the budget is counted against.
     * :raises NullPointerException: when ``countedIdentity`` is ``null``.
     */
    public RateLimitFilter(int maxRequestsPerWindow, String pathPrefix, CountedIdentity countedIdentity) {
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.pathPrefix = pathPrefix == null ? "" : pathPrefix;
        this.countedIdentity = java.util.Objects.requireNonNull(countedIdentity, "countedIdentity");
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
        String clientKey = maxRequestsPerWindow < 1 || !appliesTo(request) ? null : countingKey(request);
        if (clientKey == null) {
            filterChain.doFilter(request, response);
            return;
        }
        rollWindowIfElapsed();
        AtomicInteger counter = counters.computeIfAbsent(clientKey, key -> new AtomicInteger());
        if (counter.incrementAndGet() > maxRequestsPerWindow) {
            // The source address, never the counting key: a per-caller key is derived from a
            // session identifier, which is a bearer credential and must not reach a log file.
            SecurityAuditLogger.rateLimited(clientKey(request), request);
            if (!response.isCommitted()) {
                // Retry-After is set BEFORE the body is written, because the writer commits
                // the response and a header set afterwards would never reach the caller. This
                // filter short-circuits ahead of the whole security chain, so HeaderWriterFilter
                // never runs and the writer supplies the cache directive and sniffing guard.
                response.setHeader("Retry-After", Long.toString(remainingWindowSeconds()));
                RefusalEnvelopeWriter.writeOutsideSecurityChain(request, response,
                        HttpStatus.TOO_MANY_REQUESTS,
                        RefusalEnvelopeWriter.CODE_RATE_LIMITED,
                        RefusalEnvelopeWriter.MSG_RATE_LIMITED);
            }
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * :purpose: Scope this instance's "already filtered" request marker to its path prefix
     *     AND its counted identity, so stacking a broad budget, a prefix-specific budget and
     *     complementary caller/anonymous budgets in one chain leaves every one of them
     *     enforced.
     * :returns: the request-attribute name unique to this instance's scope.
     * :note: The counted identity is part of the name because two instances can legitimately
     *     share a path prefix — the per-caller and anonymous-address budgets both apply to
     *     ``/``. Without it the first to run marks the request filtered and the second
     *     silently skips itself, leaving one of the two budgets unenforced.
     */
    @Override
    protected String getAlreadyFilteredAttributeName() {
        return RateLimitFilter.class.getName() + ":" + (pathPrefix.isEmpty() ? "/" : pathPrefix)
                + ":" + countedIdentity.name() + ".FILTERED";
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

    /**
     * :purpose: Derive the key this request is COUNTED against, or report that this instance
     *     does not count it at all.
     * :param request: the current HTTP request.
     * :returns: the counting key, or ``null`` when the request falls outside this instance's
     *     counted identity (a signed-on request under an anonymous-only budget, or an
     *     anonymous request under a caller budget).
     * :note: An existing session is read with ``getSession(false)``: this filter must never
     *     mint one, both because an anonymous request is deliberately session-free in this
     *     deployment and because minting here would hand every caller its own budget for
     *     free. The session identifier is hashed rather than used directly so the counter
     *     map holds no bearer credential in a form that could be read back out of a heap
     *     dump or a diagnostic dump of the map.
     */
    private String countingKey(HttpServletRequest request) {
        if (countedIdentity == CountedIdentity.SOURCE_ADDRESS) {
            return clientKey(request);
        }
        HttpSession session = request.getSession(false);
        String sessionId = session == null ? null : session.getId();
        boolean signedOn = sessionId != null && !sessionId.isBlank();
        if (countedIdentity == CountedIdentity.SIGNED_ON_CALLER) {
            return signedOn ? CALLER_KEY_PREFIX + opaqueDigest(sessionId) : null;
        }
        return signedOn ? null : clientKey(request);
    }

    /**
     * :purpose: Reduce a session identifier to a short, non-reversible key.
     * :param value: the session identifier.
     * :returns: the first sixteen hex characters of the value's SHA-256 digest, or the
     *     value's identity hash when the digest algorithm is unavailable.
     * :note: Truncation is safe here: a collision merges two callers' budgets, which at
     *     worst applies a shared limit to two sessions and can never grant more than the
     *     configured budget.
     */
    private static String opaqueDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256; this branch keeps the filter functional rather than
            // failing a request if a hardened security provider ever removes it.
            return Integer.toHexString(value.hashCode());
        }
    }
}
