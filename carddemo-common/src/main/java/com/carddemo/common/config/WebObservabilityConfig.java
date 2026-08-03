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
 * :purpose: Register the shared CardDemo web observability filters — the correlation-id filter and
 *           the access-log filter — once, for every service, and make the correlation id
 *           propagate across thread boundaries.
 * :output: An auto-configuration that contributes {@link CorrelationIdFilter} at the highest
 *          precedence and {@link RequestLoggingFilter} immediately after it. Cross-thread
 *          propagation of the same id is registered by {@link ObservabilityConfig}, which is not
 *          web-specific.
 * :note: This class is listed in
 *        ``META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports``, so
 *        every service on the classpath of ``carddemo-common`` gets IDENTICAL correlation-id
 *        behaviour with no per-service wiring. Previously the library shipped no import file and no
 *        application class imported this configuration, so the shared filter was dead code: five
 *        services carried divergent local copies that ignored the documented W3C ``traceparent``
 *        fallback, and the remaining four emitted no correlation id at all. Registering it here is
 *        what makes the behaviour uniform.
 * :note: Filter ORDER matters and is deliberate. The correlation-id filter must run first so the
 *        MDC is populated before anything else logs; the access-log filter runs immediately inside
 *        it so its records — including the one it writes for an escaping exception — are still
 *        within the MDC scope.
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
