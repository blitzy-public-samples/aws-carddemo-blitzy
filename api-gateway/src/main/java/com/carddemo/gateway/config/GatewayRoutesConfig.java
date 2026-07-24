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
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * :purpose: Contribute a single global servlet gateway filter that copies the
 *     canonical business correlation id onto the outbound (proxied) downstream
 *     request as the ``X-Correlation-Id`` header, so log correlation and
 *     distributed tracing span the whole gateway call fan-out (Observability
 *     rule, AAP 0.7.5).
 * :note: This class contributes FILTERS ONLY. The downstream route table is
 *     declared authoritatively in ``application.yml`` under
 *     ``spring.cloud.gateway.server.webmvc.routes`` and is deliberately NOT
 *     re-declared here; re-declaring a route would create a duplicate.
 * :note: Only the business ``X-Correlation-Id`` header is propagated. The
 *     distributed trace context is instrumented and propagated automatically by
 *     Micrometer Tracing (OpenTelemetry bridge) on the gateway's proxy client
 *     and is therefore not handled here.
 */
@Configuration
public class GatewayRoutesConfig {

    /**
     * :purpose: Request header carrying the business correlation id, mirrored
     *     onto proxied downstream requests. Kept identical to
     *     ``CorrelationIdFilter.CORRELATION_ID_HEADER`` for wire consistency.
     */
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * :purpose: Register the correlation-id propagation filter immediately after
     *     the shared ``CorrelationIdFilter`` (which seeds the id at
     *     {@link Ordered#HIGHEST_PRECEDENCE}) so the id is already present in the
     *     MDC when this filter wraps the request, while the wrapper is still in
     *     place before the dispatcher routes the request downstream.
     * :returns: the servlet ``FilterRegistrationBean`` mapping the propagation
     *     filter to every request path (``/*``).
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> correlationIdPropagationFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {
                String correlationId = CorrelationIdContext.getCorrelationId();
                if (correlationId != null && !correlationId.isBlank()) {
                    filterChain.doFilter(new CorrelationIdHeaderRequestWrapper(request, correlationId), response);
                } else {
                    filterChain.doFilter(request, response);
                }
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns("/*");
        registration.setName("correlationIdPropagationFilter");
        return registration;
    }

    /**
     * :purpose: Expose a fixed ``X-Correlation-Id`` header (the canonical id
     *     resolved upstream) to the Spring Cloud Gateway proxying layer, which
     *     builds the downstream ``ServerRequest`` from this servlet request,
     *     while leaving every other request header untouched.
     */
    private static final class CorrelationIdHeaderRequestWrapper extends HttpServletRequestWrapper {

        private final String correlationId;

        /**
         * :purpose: Wrap the incoming request, fixing the correlation-id header
         *     to the supplied canonical value.
         * :param request: the request being proxied downstream.
         * :param correlationId: the canonical correlation id to expose.
         */
        CorrelationIdHeaderRequestWrapper(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        /**
         * :purpose: Return the canonical correlation id for the correlation
         *     header (case-insensitive), delegating all other headers.
         * :param name: the requested header name.
         * :returns: the canonical correlation id for ``X-Correlation-Id``;
         *     otherwise the wrapped request's value.
         */
        @Override
        public String getHeader(String name) {
            if (CORRELATION_ID_HEADER.equalsIgnoreCase(name)) {
                return correlationId;
            }
            return super.getHeader(name);
        }

        /**
         * :purpose: Return the canonical correlation id as the sole value for the
         *     correlation header (case-insensitive), delegating all other headers.
         * :param name: the requested header name.
         * :returns: a single-element enumeration with the canonical correlation
         *     id for ``X-Correlation-Id``; otherwise the wrapped request's values.
         */
        @Override
        public Enumeration<String> getHeaders(String name) {
            if (CORRELATION_ID_HEADER.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(correlationId));
            }
            return super.getHeaders(name);
        }

        /**
         * :purpose: Enumerate the wrapped request's header names, guaranteeing the
         *     correlation header appears exactly once.
         * :returns: the wrapped header names plus ``X-Correlation-Id``,
         *     order-preserving and de-duplicated.
         */
        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original.hasMoreElements()) {
                names.add(original.nextElement());
            }
            names.add(CORRELATION_ID_HEADER);
            return Collections.enumeration(names);
        }
    }
}
