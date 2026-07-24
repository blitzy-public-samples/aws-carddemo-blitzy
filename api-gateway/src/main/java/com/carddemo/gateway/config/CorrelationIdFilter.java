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
package com.carddemo.gateway.config;

import com.carddemo.common.config.CorrelationIdContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * :purpose: Servlet filter that establishes the request correlation id in the
 *     SLF4J MDC (via {@link CorrelationIdContext}) for structured logging and
 *     distributed tracing. It honors an inbound ``X-Correlation-Id`` header when
 *     present, otherwise generates and stores a fresh id, echoes the resolved id
 *     back on the response ``X-Correlation-Id`` header, and clears the MDC once
 *     the request completes. Registered at highest precedence so it runs first
 *     as the gateway's tracing entry point, ahead of the Spring Security chain.
 * :note: Micrometer Tracing independently manages ``%X{traceId}`` and
 *     ``%X{spanId}``; this filter does not touch those keys.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * :purpose: Inbound and outbound HTTP header that carries the business
     *     correlation id. Distinct from the MDC key
     *     {@link CorrelationIdContext#CORRELATION_ID_KEY}.
     */
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * :purpose: Resolve the request correlation id, seed it into the MDC, echo it
     *     on the response, and clear the MDC after the chain completes.
     * :param request: the current HTTP request, read for an inbound
     *     ``X-Correlation-Id`` header.
     * :param response: the current HTTP response, stamped with the resolved
     *     ``X-Correlation-Id`` header.
     * :param filterChain: the remaining filter chain executed within the
     *     correlation scope.
     * :raises ServletException: if a downstream filter or the servlet fails.
     * :raises IOException: if request or response I/O fails.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(CORRELATION_ID_HEADER);
        String correlationId;
        if (incoming != null && !incoming.isBlank()) {
            CorrelationIdContext.setCorrelationId(incoming);
            correlationId = incoming;
        } else {
            correlationId = CorrelationIdContext.getOrCreateCorrelationId();
        }
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationIdContext.clear();
        }
    }
}
