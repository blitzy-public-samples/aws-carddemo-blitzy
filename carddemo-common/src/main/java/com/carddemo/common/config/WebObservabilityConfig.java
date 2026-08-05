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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * :purpose: Web-only observability configuration that registers the shared
 *           {@link CorrelationIdFilter} so every HTTP request in a CardDemo web
 *           service or the API gateway carries a bounded, trace-safe correlation
 *           id through the MDC and the response. Together with the metrics/trace
 *           tagging in {@link ObservabilityConfig} this completes the
 *           correlation-propagation requirement of the Observability rule.
 * :note: Gated by ``@ConditionalOnWebApplication(type = SERVLET)`` so it is only
 *        active in servlet web modules; a non-servlet module never loads the
 *        servlet filter. A service activates this configuration the same way it
 *        activates {@link ObservabilityConfig}: by ``@Import`` or by broadening
 *        component scanning to ``com.carddemo.common``.
 * :note: This library ships no ``META-INF`` auto-configuration import file, so the
 *        activation above is MANDATORY and is not applied automatically. A servlet
 *        module that neither imports this class nor declares its own
 *        {@link CorrelationIdFilter} ``@Component`` registers no correlation filter
 *        at all, and the ``correlationId`` MDC key rendered by its
 *        ``logback-spring.xml`` pattern stays permanently empty. Conversely, a module
 *        that already declares its own filter component must NOT import this class,
 *        or the filter would run twice per request.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebObservabilityConfig {

    /**
     * :purpose: Register the shared correlation-id filter for every request, ahead of every other
     *           filter (Spring Security included) so the ``correlationId`` MDC entry and the
     *           ``X-Correlation-Id`` response header are present for the whole request lifecycle.
     * :returns: the filter registration for {@link CorrelationIdFilter}.
     */
    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setName("correlationIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * :purpose: Register the shared access-log filter inside the correlation-id filter, so every
     *           request produces exactly one observable record carrying the correlation and trace
     *           ids.
     * :returns: the filter registration for {@link RequestLoggingFilter}.
     * :note: Ordered at ``HIGHEST_PRECEDENCE + 10`` rather than ``+ 1`` so it never ties with the
     *        api-gateway's own downstream correlation-propagation filter (registered at ``+ 1``);
     *        equal order values leave relative filter position undefined.
     */
    @Bean
    FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilterRegistration() {
        FilterRegistrationBean<RequestLoggingFilter> registration =
                new FilterRegistrationBean<>(new RequestLoggingFilter());
        registration.addUrlPatterns("/*");
        registration.setName("requestLoggingFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
