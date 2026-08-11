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

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/**
 * :purpose: Web-only observability configuration that registers the shared {@link
 *     CorrelationIdFilter} so every HTTP request in a CardDemo web service or the API gateway
 *     carries a bounded, trace-safe correlation id through the MDC and the response. Together
 *     with the metrics/trace tagging in {@link ObservabilityConfig} this completes the
 *     correlation-propagation requirement of the Observability rule.
 * :note: Gated by ``@ConditionalOnWebApplication(type = SERVLET)`` so it is only active in
 *     servlet web modules; a non-servlet module never loads the servlet filter. A service
 *     activates this configuration the same way it activates {@link ObservabilityConfig}: by
 *     ``@Import`` or by broadening component scanning to ``com.carddemo.common``.
 * :note: The library's
 *     ``META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports``
 *     lists this class, so a servlet module on the classpath activates it without doing
 *     anything; the ``@Import`` or broadened component scan above is the alternative for a
 *     module that disables auto-configuration. A module that already declares its own {@link
 *     CorrelationIdFilter} ``@Component`` must NOT also import this class, or the filter would
 *     run twice per request.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebObservabilityConfig {

    /**
     * :purpose: Register the shared correlation-id filter for every request, ahead of every
     *     other filter (Spring Security included) so the ``correlationId`` MDC entry and the
     *     ``X-Correlation-Id`` response header are present for the whole request lifecycle.
     * :returns: the filter registration for {@link CorrelationIdFilter}.
     * :note: Registered for the ``ERROR`` dispatch as well as ``REQUEST``. A registration bean
     *     defaults to ``REQUEST`` alone, and with that default the container's ``ERROR`` dispatch
     *     -- which renders every ``sendError`` response, including the ones Spring Security's
     *     request firewall and the servlet container raise before any handler is selected -- would
     *     bypass this filter entirely, leaving {@link CardDemoErrorController} with an empty
     *     correlation scope and emitting an error envelope whose ``correlationId`` is null even
     *     though the caller already holds the id from the ``X-Correlation-Id`` response header.
     *     {@link CorrelationIdFilter#shouldNotFilterErrorDispatch()} returning ``false`` is a
     *     necessary but NOT a sufficient condition: it only governs what the filter does once
     *     invoked, never whether the container invokes it.
     */
    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setName("correlationIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
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

    /**
     * :purpose: Register the shared {@link DatastoreOutageErrorFilter} between the
     *     correlation/access-log filters and Spring Session's ``SessionRepositoryFilter``, so a
     *     session-store, datastore or transaction outage is answered with the documented
     *     ``ErrorResponse`` envelope instead of the servlet container's status page.
     * :param objectMapperProvider: provider of the context's Jackson mapper, so the envelope
     *     is serialized exactly as the exception-advice path serializes it; resolved lazily so a
     *     context without Jackson still starts.
     * :returns: the filter registration for {@link DatastoreOutageErrorFilter}.
     * :note: The order is load-bearing. Spring Session registers its filter at
     *     ``Integer.MIN_VALUE + 50`` for the ``REQUEST``, ``ERROR`` and ``ASYNC`` dispatcher
     *     types, so a Redis failure repeats itself during the container's error dispatch and the
     *     dispatch never reaches {@link CardDemoErrorController}. ``+ 20`` places this filter
     *     outside the session filter (so it can answer the failure) and inside {@link
     *     CorrelationIdFilter} at ``MIN_VALUE`` (so the envelope carries a correlation id) and
     *     inside the access-log filter at ``+ 10`` (so one access record reports the ``503`` this
     *     filter produced).
     * :note: This is the ONE filter that answers an infrastructure outage at the filter layer.
     *     It intercepts the whole ``DataAccessException`` family and ``TransactionException``,
     *     unwrapping the cause chain a servlet filter may have wrapped them in, writes ``503``
     *     with ``Retry-After`` and the shared envelope, and resets only the response BUFFER so the
     *     headers the outer filters already set survive.
     */
    @Bean
    FilterRegistrationBean<DatastoreOutageErrorFilter> datastoreOutageErrorFilterRegistration(
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        FilterRegistrationBean<DatastoreOutageErrorFilter> registration =
                new FilterRegistrationBean<>(new DatastoreOutageErrorFilter(objectMapperProvider));
        registration.addUrlPatterns("/*");
        registration.setName("datastoreOutageErrorFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
