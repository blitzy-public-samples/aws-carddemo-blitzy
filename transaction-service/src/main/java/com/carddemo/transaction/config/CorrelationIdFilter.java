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
package com.carddemo.transaction.config;

import com.carddemo.common.config.CorrelationIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * :purpose: Establishes a business correlation id for every inbound HTTP request
 *     and propagates it through the SLF4J MDC so it appears in the transaction-service
 *     structured logs. The inbound ``X-Correlation-Id`` header is used when present
 *     and non-blank; otherwise a new id is generated. The resolved id is echoed on
 *     the response and removed from the MDC after the request completes.
 * :note: Ordered first in the filter chain so the correlation id is available to all
 *     downstream logging; the MDC is always cleared in a ``finally`` block to prevent
 *     ids leaking across pooled request threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * :purpose: HTTP header carrying the business correlation id, read from the
     *     request and written back on the response.
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * :purpose: Bind a correlation id to the current request thread's MDC, invoke the
     *     remaining filter chain, then clear the MDC.
     * :param request: the inbound HTTP request; a non-blank ``X-Correlation-Id`` header
     *     supplies the correlation id when present.
     * :param response: the HTTP response; the resolved correlation id is written to its
     *     ``X-Correlation-Id`` header.
     * :param filterChain: the remaining filter chain to execute.
     * :raises ServletException: if the downstream chain raises a servlet error.
     * :raises IOException: if the downstream chain raises an I/O error.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String inboundCorrelationId = request.getHeader(CORRELATION_ID_HEADER);
        if (inboundCorrelationId != null && !inboundCorrelationId.isBlank()) {
            CorrelationIdContext.setCorrelationId(inboundCorrelationId);
        }
        String correlationId = CorrelationIdContext.getOrCreateCorrelationId();
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationIdContext.clear();
        }
    }
}
