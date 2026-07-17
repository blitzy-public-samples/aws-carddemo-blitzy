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
     * Resolves the correlation ID for the current request, publishes it to both the logging context
     * and the response, invokes the remainder of the filter chain, and finally clears the
     * correlation ID from the {@link MDC} so it does not leak onto a pooled thread.
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
        String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
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
}
