/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Servlet filter that assigns a correlation ID to every HTTP request and exposes it via the SLF4J
 * {@link MDC} (under the key {@value #CORRELATION_ID_MDC_KEY}) for structured logging and
 * log/trace correlation.
 *
 * <p>This is the web-side half of the application's observability story: it guarantees that every
 * inbound request carries a stable, unique identifier attached to the logging context for the
 * lifetime of that request. The companion {@code logback-spring.xml} renders the value through the
 * pattern token {@code %X{correlationId:-}}, so every log line emitted while handling the request
 * &mdash; from the Spring Security chain, controllers, services, and repositories &mdash; is
 * automatically tagged with the same identifier. The batch layer writes the identical MDC key on
 * its job/step boundaries, which is how a single correlation ID propagates across both the service
 * and the batch execution surfaces.</p>
 *
 * <p><strong>Header contract.</strong> If the inbound request already carries an
 * {@value #CORRELATION_ID_HEADER} header (for example, set by an upstream gateway, a calling
 * service, or an integration test) <em>and that value is safe</em>, it is honored so a single
 * logical operation keeps one identifier end to end. Otherwise &mdash; when the header is absent,
 * blank, or fails validation &mdash; a fresh {@link UUID} is generated. The resolved identifier is
 * always echoed back on the response under the same header so that clients and tests can capture
 * it.</p>
 *
 * <p><strong>Sanitization.</strong> A supplied correlation ID is only honored when it matches a
 * conservative allow-list &mdash; {@value #CORRELATION_ID_MAX_LENGTH} characters at most, drawn
 * solely from ASCII letters, digits, and the separators {@code . _ -} (regex
 * {@code ^[A-Za-z0-9._-]{1,64}$}). Any value that is too long or contains any other character
 * (whitespace, brackets, quotes, control characters, or non-ASCII code points) is rejected and a
 * fresh {@link UUID} is substituted. This keeps the identifier a bounded, opaque token everywhere
 * it flows &mdash; the SLF4J {@link MDC}, every log line, the echoed response header, the
 * {@code ProblemDetail} body, and the trace/span attribute &mdash; so an untrusted client cannot
 * inject markup, delimiters, oversize payloads, or forged log fields through this header. A
 * UUID produced by {@link UUID#randomUUID()} always satisfies the allow-list.</p>
 *
 * <p><strong>Ordering.</strong> The filter is registered at {@link Ordered#HIGHEST_PRECEDENCE} so
 * it runs before the Spring Security filter chain and before Spring Boot's HTTP server observation
 * filter. This ensures the correlation ID is present in the {@link MDC} for all downstream logging
 * and is available when the server-side observation computes its key values.</p>
 *
 * <p><strong>Thread-context hygiene.</strong> The MDC entry is always removed in a
 * {@code finally} block once the request completes, preventing the identifier from leaking onto a
 * pooled request thread and contaminating a later, unrelated request. Only the correlation-ID key
 * is removed; {@link MDC#clear()} is deliberately not used, because the tracing infrastructure
 * (Micrometer) manages the {@code traceId}/{@code spanId} entries on the same thread and must not
 * be disturbed.</p>
 *
 * <p><strong>Security.</strong> This filter handles only an opaque correlation-ID string. It never
 * reads, copies, or logs request bodies, sensitive headers, credentials, or card data.</p>
 *
 * <p>The filter is intentionally free of injected collaborators: it depends only on the Servlet
 * API and the SLF4J {@link MDC}, so it always functions correctly even when distributed tracing is
 * disabled (for example, in web-slice tests).</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * Name of the HTTP header used both to receive an inbound correlation ID and to echo the
     * resolved identifier back on the response.
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * SLF4J {@link MDC} key under which the correlation ID is stored. This value must remain
     * exactly {@code "correlationId"} because {@code logback-spring.xml} references it as
     * {@code %X{correlationId:-}} and the batch-side listener writes the same key; changing it
     * would break the documented logging/correlation contract.
     */
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * Maximum number of characters accepted from an inbound correlation ID. Values longer than
     * this are rejected and replaced with a generated {@link UUID}. Sixty-four comfortably
     * accommodates a canonical 36-character UUID plus any short upstream prefix while bounding the
     * amount of client-controlled text that can enter logs, headers, and span attributes.
     */
    public static final int CORRELATION_ID_MAX_LENGTH = 64;

    /**
     * Allow-list pattern an inbound correlation ID must fully match to be honored: 1&ndash;{@value
     * #CORRELATION_ID_MAX_LENGTH} characters drawn only from ASCII letters, digits, and the
     * separators {@code .}, {@code _}, and {@code -}. This excludes whitespace, brackets, quotes,
     * control characters, and all non-ASCII code points, so the resolved identifier is always a
     * bounded, opaque token that is safe to place in the {@link MDC}, log lines, the response
     * header, the {@code ProblemDetail} body, and trace/span attributes. A value that does not
     * match is discarded in favor of a generated {@link UUID} (which always matches).
     */
    private static final Pattern CORRELATION_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]{1," + CORRELATION_ID_MAX_LENGTH + "}$");

    /**
     * Holds the request URI of the in-flight HTTP request for the current thread so the
     * application's observation predicate ({@code ObservabilityConfig#isObservable}) can exclude
     * infrastructure endpoints even for observations whose own context does not carry a URI &mdash;
     * most notably Spring Security's {@code spring.security.filterchains} observation, whose
     * {@code FilterChainObservationContext} is package-private and exposes only filter metadata
     * (filter name, chain position/size), never the request path.
     *
     * <p>Because this filter runs at {@link Ordered#HIGHEST_PRECEDENCE} &mdash; before both the
     * Spring Security filter chain and Spring Boot's server observation filter &mdash; the value is
     * populated before any downstream observation is started, and observations are started
     * synchronously on the request thread, so a {@link ThreadLocal} is the correct carrier. It is
     * always removed in the {@code finally} block below to prevent leakage onto a pooled request
     * thread. On batch (non-request) threads it is never set and therefore reads {@code null}, so
     * batch observations remain unaffected.</p>
     */
    private static final ThreadLocal<String> REQUEST_PATH = new ThreadLocal<>();

    /**
     * Resolves the correlation ID for the current dispatch, publishes it to both the logging context
     * and the response, captures the request path for the observation predicate, invokes the
     * remainder of the filter chain, and finally clears the per-thread state so it does not leak onto
     * a pooled thread.
     *
     * <p>This runs on the initial dispatch and on container {@code ERROR} dispatches (see
     * {@link #shouldNotFilterErrorDispatch()}). The correlation ID is resolved via
     * {@link #resolveCorrelationId(HttpServletRequest, HttpServletResponse)}, which reuses an ID
     * already assigned on a prior dispatch of the same request so one logical request keeps a single
     * identifier. The captured request path is the {@link #effectiveRequestPath(HttpServletRequest)
     * effective request path}, which prefers the original request URI over a container error-page
     * path so the infrastructure-endpoint exclusion holds across the whole request lifecycle.</p>
     *
     * @param request     the current HTTP request; its {@value #CORRELATION_ID_HEADER} header is
     *                    honored only when present and matching the {@link #CORRELATION_ID_PATTERN}
     *                    allow-list, otherwise a fresh {@link UUID} is generated
     * @param response    the current HTTP response; the resolved identifier is echoed on its
     *                    {@value #CORRELATION_ID_HEADER} header before the chain runs
     * @param filterChain the remainder of the filter chain to execute
     * @throws ServletException if the downstream filter chain raises a servlet error
     * @throws IOException      if the downstream filter chain raises an I/O error
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request, response);
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        REQUEST_PATH.set(effectiveRequestPath(request));
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
            REQUEST_PATH.remove();
        }
    }

    /**
     * Runs this filter on container {@code ERROR} dispatches as well as the initial dispatch
     * (overriding the {@link OncePerRequestFilter} default, which skips error dispatches).
     *
     * <p>A Spring Security filter chain re-runs on the error dispatch, starting a fresh
     * {@code spring.security.filterchains} observation. Because that observation's context carries
     * no request URI, the application observation predicate relies on the request path this filter
     * captures in {@link #REQUEST_PATH}. If the filter did not run on the error dispatch, that path
     * would be {@code null} there and an infrastructure request that errors &mdash; for example an
     * unauthenticated {@code /actuator/metrics} returning {@code 401} &mdash; would emit an
     * unsuppressable orphan {@code security filterchain} root trace, the very trace noise
     * {@code ObservabilityConfig} exists to prevent (QA finding F-OBS-1). Running here also means
     * the correlation ID is present in the MDC for any logging performed during error handling.</p>
     *
     * @return {@code false} so the filter participates in {@code ERROR} dispatches
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * Resolves the effective correlation ID from the raw inbound header value, enforcing the
     * sanitization contract documented on this class.
     *
     * <p>The supplied value is honored only when it is non-blank <em>and</em> fully matches the
     * {@link #CORRELATION_ID_PATTERN} allow-list (bounded length, ASCII letters/digits and
     * {@code . _ -} only). In every other case &mdash; the header is missing, blank, too long, or
     * contains any disallowed character (whitespace, markup, quotes, control characters, or
     * non-ASCII code points) &mdash; a fresh {@link UUID} is generated instead. This guarantees the
     * returned identifier is always a bounded, opaque token, so an untrusted client cannot inject
     * markup, delimiters, oversize payloads, or forged log fields through this header.</p>
     *
     * @param headerValue the raw {@value #CORRELATION_ID_HEADER} request-header value, which may be
     *                    {@code null}, blank, or arbitrary untrusted text
     * @return a validated inbound correlation ID, or a newly generated {@link UUID} string when the
     *         input is absent or fails validation; never {@code null} or blank
     */
    private static String resolveCorrelationId(String headerValue) {
        if (StringUtils.hasText(headerValue) && CORRELATION_ID_PATTERN.matcher(headerValue).matches()) {
            return headerValue;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Resolves the correlation ID for the current dispatch, keeping a single identifier stable
     * across all dispatches of one logical request.
     *
     * <p>Because this filter also runs on container {@code ERROR} dispatches (see
     * {@link #shouldNotFilterErrorDispatch()}), a correlation ID may already have been assigned and
     * echoed on the {@value #CORRELATION_ID_HEADER} response header during the initial dispatch. When
     * that already-assigned value is present and still satisfies the {@link #CORRELATION_ID_PATTERN}
     * allow-list it is reused, so the initial dispatch and its error dispatch share one ID; otherwise
     * the ID is resolved from the inbound request header via {@link #resolveCorrelationId(String)}
     * (generating a fresh {@link UUID} when the header is absent or invalid).</p>
     *
     * @param request  the current HTTP request, whose {@value #CORRELATION_ID_HEADER} header is the
     *                 fallback source of the identifier
     * @param response the current HTTP response, inspected for an ID this filter assigned on a prior
     *                 dispatch of the same request
     * @return the correlation ID to use for this dispatch; never {@code null} or blank
     */
    private static String resolveCorrelationId(HttpServletRequest request, HttpServletResponse response) {
        String alreadyAssigned = response.getHeader(CORRELATION_ID_HEADER);
        if (StringUtils.hasText(alreadyAssigned) && CORRELATION_ID_PATTERN.matcher(alreadyAssigned).matches()) {
            return alreadyAssigned;
        }
        return resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
    }

    /**
     * Returns the request URI to record for observation-exclusion purposes, preferring the original
     * request URI over a container-supplied error-page path.
     *
     * <p>On a container {@code ERROR} dispatch the live request URI is the error page (for example
     * {@code /error}); the URI of the request that actually failed is preserved under
     * {@link RequestDispatcher#ERROR_REQUEST_URI}. Preferring that original URI ensures the
     * infrastructure-endpoint exclusion (for example {@code /actuator/**}) applies across the whole
     * request lifecycle &mdash; including the error dispatch &mdash; rather than being defeated by the
     * error-page path. On an ordinary dispatch the attribute is absent and the live request URI is
     * used.</p>
     *
     * @param request the current HTTP request
     * @return the original request URI when handling an error dispatch, otherwise the current
     *         request URI
     */
    private static String effectiveRequestPath(HttpServletRequest request) {
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (original instanceof String uri && StringUtils.hasText(uri)) {
            return uri;
        }
        return request.getRequestURI();
    }

    /**
     * Returns the request URI of the HTTP request currently being handled on the calling thread, or
     * {@code null} when the thread is not handling an HTTP request (for example, a batch worker
     * thread, on which this value is never set).
     *
     * <p>Exposed for the application observation predicate
     * ({@code ObservabilityConfig#isObservable}) so it can apply the same infrastructure-endpoint
     * exclusion (see {@code ObservabilityConfig#NON_OBSERVED_PREFIXES}) to observations whose own
     * context does not carry a request URI &mdash; specifically Spring Security's
     * {@code spring.security.filterchains} observation. The value is managed entirely by
     * {@link #doFilterInternal(HttpServletRequest, HttpServletResponse, FilterChain)}: it is set at
     * request entry and removed when the request completes, so it is only ever non-{@code null}
     * while an HTTP request is actively being processed on this thread.</p>
     *
     * @return the in-flight request URI for the current thread, or {@code null} if the thread is not
     *         currently handling an HTTP request
     */
    public static String currentRequestPath() {
        return REQUEST_PATH.get();
    }
}
