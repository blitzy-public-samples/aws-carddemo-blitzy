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
 * service, or an integration test), that value is honored verbatim so a single logical operation
 * keeps one identifier end to end. Otherwise a fresh {@link UUID} is generated. The resolved
 * identifier is always echoed back on the response under the same header so that clients and tests
 * can capture it.</p>
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
     * Resolves the correlation ID for the current request, publishes it to both the logging context
     * and the response, invokes the remainder of the filter chain, and finally clears the
     * correlation ID from the {@link MDC} so it does not leak onto a pooled thread.
     *
     * @param request     the current HTTP request; its {@value #CORRELATION_ID_HEADER} header is
     *                    honored when present and non-blank
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
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }
}
