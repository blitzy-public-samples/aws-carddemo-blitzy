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

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * :purpose: Web-only observability configuration that registers the shared
 *           {@link CorrelationIdFilter} so every HTTP request in a CardDemo web
 *           service or the API gateway carries a bounded, trace-safe correlation
 *           id through the MDC and the response. Together with the metrics/trace
 *           tagging in {@link ObservabilityConfig} this completes the
 *           correlation-propagation requirement of the Observability rule.
 * :note: Gated by ``@ConditionalOnWebApplication(type = SERVLET)`` so it is only
 *        active in servlet web modules; the non-web batch service never loads the
 *        servlet filter. A service activates this configuration the same way it
 *        activates {@link ObservabilityConfig}: by ``@Import`` or by broadening
 *        component scanning to ``com.carddemo.common``.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebObservabilityConfig {

    /**
     * :purpose: Register {@link CorrelationIdFilter} for every request URL at the
     *           highest precedence so the correlation id is established before any
     *           other filter (including security and tracing filters) emits a log
     *           line.
     * :returns: a filter registration mapping the correlation filter to ``/*``
     *           with ``Ordered.HIGHEST_PRECEDENCE``.
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
}
