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
package com.carddemo.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * :purpose: Servlet filter that establishes a bounded, trace-safe correlation id
 *     in the SLF4J MDC for the whole lifetime of every HTTP request so that all
 *     structured log lines for that request carry the same ``%X{correlationId}``,
 *     and echoes the resolved id back on the response so callers and downstream
 *     services can propagate it. Realizes the correlation-propagation half of the
 *     CardDemo Observability rule.
 * :note: The id is resolved with the following precedence: an inbound
 *     ``X-Correlation-Id`` header; otherwise the 32-hex trace-id parsed from a
 *     W3C ``traceparent`` header; otherwise a freshly generated id. Every
 *     candidate is passed through {@link CorrelationIdContext#sanitize} so
 *     CR/LF injection and unbounded high-cardinality tokens cannot reach the log
 *     stream or the response header.
 * :note: The MDC key is always cleared in a ``finally`` block so correlation ids
 *     never leak across requests served by a pooled container thread. This class
 *     is registered as a bean only in web applications by
 *     {@link WebObservabilityConfig}; non-web modules (for example the batch
 *     service) never load it.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * :purpose: Request and response header carrying the business correlation id.
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * :purpose: W3C Trace Context header used as a fallback source for the
     *     correlation id when no ``X-Correlation-Id`` header is present.
     */
    public static final String TRACEPARENT_HEADER = "traceparent";

    /**
     * :purpose: Resolve, install, expose, and finally clear the request
     *     correlation id around the remainder of the filter chain.
     * :param request: the current HTTP request, inspected for correlation
     *     headers.
     * :param response: the current HTTP response, stamped with the resolved
     *     correlation id.
     * :param filterChain: the remaining chain to execute within the correlation
     *     scope.
     * :raises ServletException: if a downstream filter or the servlet fails.
     * :raises IOException: if request or response I/O fails.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        CorrelationIdContext.setCorrelationId(correlationId);
        // Read back the value actually stored (post-sanitization) so the header
        // and the MDC always agree.
        String stored = CorrelationIdContext.getCorrelationId();
        if (stored != null) {
            response.setHeader(CORRELATION_ID_HEADER, stored);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationIdContext.clear();
        }
    }

    /**
     * :purpose: Determine the correlation id for a request using the documented
     *     header precedence, always returning a usable value.
     * :param request: the current HTTP request.
     * :returns: the sanitized inbound ``X-Correlation-Id``; otherwise the
     *     sanitized W3C trace-id from ``traceparent``; otherwise a freshly
     *     generated correlation id.
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        String fromHeader = CorrelationIdContext.sanitize(request.getHeader(CORRELATION_ID_HEADER));
        if (fromHeader != null) {
            return fromHeader;
        }
        String fromTrace = CorrelationIdContext.sanitize(extractTraceId(request.getHeader(TRACEPARENT_HEADER)));
        if (fromTrace != null) {
            return fromTrace;
        }
        return CorrelationIdContext.generateCorrelationId();
    }

    /**
     * :purpose: Extract the trace-id field from a W3C ``traceparent`` header.
     * :param traceparent: the raw header value, expected in the form
     *     ``version-traceId-parentId-flags`` (for example
     *     ``00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01``).
     * :returns: the second, trace-id field when the header has at least the
     *     version and trace-id segments and the trace-id is not the all-zero
     *     invalid value; otherwise ``null``.
     */
    private String extractTraceId(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length < 2) {
            return null;
        }
        String traceId = parts[1];
        if (traceId.isBlank() || traceId.chars().allMatch(c -> c == '0')) {
            return null;
        }
        return traceId;
    }
}
