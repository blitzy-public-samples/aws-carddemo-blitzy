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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import jakarta.servlet.DispatcherType;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Verify the registration contract of {@link WebObservabilityConfig}, the
 *   servlet half of the Observability rule (AAP 0.7.5). The class is listed in the
 *   library's auto-configuration import file, so every servlet module on the classpath
 *   picks up whatever it registers — which makes the exact registered set, and each
 *   registration's order, part of the shared contract: the correlation filter must run
 *   first, the request-logging filter next, and the rejected-request envelope filter
 *   after both yet still well ahead of Spring Security's filter chain.
 * :output: ``ApplicationContextRunner``-based assertions over the registered
 *   ``FilterRegistrationBean`` set: which registrations exist, and per registration the
 *   filter type, URL mapping, name and order, plus the
 *   ``@ConditionalOnWebApplication(SERVLET)`` gate.
 */
class WebObservabilityConfigTest {

    /** :purpose: The single URL pattern the correlation filter must cover. */
    private static final String ALL_URLS = "/*";

    /** :purpose: Registration name asserted so operators can identify the filter. */
    private static final String FILTER_NAME = "correlationIdFilter";

    /** :purpose: Bean name of the correlation-id registration under test. */
    private static final String CORRELATION_REGISTRATION = "correlationIdFilterRegistration";

    /** :purpose: Bean name of the request-logging registration the class also contributes. */
    private static final String REQUEST_LOGGING_REGISTRATION = "requestLoggingFilterRegistration";

    /**
     * :purpose: Bean name of the ONE outage registration the class contributes: the filter that
     *   answers a session-store, datastore or transaction outage escaping the filter chain.
     */
    private static final String DATASTORE_OUTAGE_REGISTRATION = "datastoreOutageErrorFilterRegistration";

    /**
     * :purpose: Order Spring Session registers ``SessionRepositoryFilter`` at
     *   (``SessionRepositoryFilter.DEFAULT_ORDER``). Restated as a literal so the
     *   assertion below does not depend on the session module being on this test's
     *   classpath, and so a future change to that constant fails this test loudly.
     */
    private static final int SESSION_REPOSITORY_FILTER_ORDER = Integer.MIN_VALUE + 50;

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(WebObservabilityConfig.class);

    private final ApplicationContextRunner nonWebRunner = new ApplicationContextRunner()
            .withUserConfiguration(WebObservabilityConfig.class);

    /**
     * :purpose: In a servlet application the configuration contributes the correlation-id
     *   registration wrapping the SHARED filter from ``carddemo-common`` rather than some
     *   other filter type, alongside the request-logging registration.
     *   Pinned as an exact set: the class is auto-configured for every servlet module, so an
     *   unnoticed extra registration would run on every request of every service.
     */
    @Test
    @DisplayName("registers the shared CorrelationIdFilter in a servlet application")
    void registersSharedCorrelationIdFilterInServletApplication() {
        servletRunner.run(context -> {
            assertThat(context.getBeansOfType(FilterRegistrationBean.class))
                    .containsOnlyKeys(CORRELATION_REGISTRATION, REQUEST_LOGGING_REGISTRATION,
                            DATASTORE_OUTAGE_REGISTRATION);
            assertThat(correlationRegistration(context).getFilter())
                    .isInstanceOf(CorrelationIdFilter.class);
        });
    }

    /**
     * :purpose: The datastore-outage filter must be registered for every URL and must sit
     *   INSIDE the correlation and access-log filters (so the MDC ids it renders are
     *   populated) yet OUTSIDE Spring Session's ``SessionRepositoryFilter``, whose order is
     *   ``Integer.MIN_VALUE + 50`` — otherwise a session-store outage escapes to the
     *   servlet container, which answers with its own status page instead of the
     *   documented JSON envelope.
     */
    @Test
    @DisplayName("registers the datastore-outage filter outside the session-store filter")
    void registersDatastoreOutageFilterOutsideSessionFilter() {
        servletRunner.run(context -> {
            FilterRegistrationBean<?> registration =
                    context.getBean(DATASTORE_OUTAGE_REGISTRATION, FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(DatastoreOutageErrorFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactly(ALL_URLS);
            assertThat(registration.getFilterName()).isEqualTo("datastoreOutageErrorFilter");
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 20);
            assertThat(registration.getOrder())
                    .isGreaterThan(context.getBean(REQUEST_LOGGING_REGISTRATION,
                            FilterRegistrationBean.class).getOrder())
                    .isLessThan(SESSION_REPOSITORY_FILTER_ORDER);
        });
    }

    /**
     * :purpose: The filter is mapped to every URL and named, so it covers the actuator
     *   surface and every business endpoint — not just a sub-path.
     */
    @Test
    @DisplayName("maps the filter to /* under a stable registration name")
    void mapsFilterToAllUrls() {
        servletRunner.run(context -> {
            FilterRegistrationBean<?> registration = correlationRegistration(context);
            assertThat(registration.getUrlPatterns()).containsExactly(ALL_URLS);
            assertThat(registration.getFilterName()).isEqualTo(FILTER_NAME);
        });
    }

    /**
     * :purpose: The correlation id must exist before ANY other filter logs, so the
     *   registration runs at the highest precedence — ahead of the security and tracing
     *   filters. A lower order would produce log lines with an empty correlation id at
     *   the start of every request.
     */
    @Test
    @DisplayName("registers at Ordered.HIGHEST_PRECEDENCE")
    void registersAtHighestPrecedence() {
        servletRunner.run(context -> {
            assertThat(correlationRegistration(context).getOrder())
                    .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(context.getBean(REQUEST_LOGGING_REGISTRATION, FilterRegistrationBean.class)
                    .getOrder()).isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
        });
    }

    /**
     * :purpose: The outage filter must sit INSIDE the correlation-id and
     *   access-log filters and OUTSIDE Spring Session's ``SessionRepositoryFilter``
     *   (``Integer.MIN_VALUE + 50``), and must cover the ERROR dispatch. That position is
     *   what lets a Redis outage answer with the documented JSON envelope carrying a
     *   correlation id: the session filter also runs on the ERROR dispatch, so a failure
     *   left to the container repeated itself during the dispatch and Tomcat rendered its
     *   own HTML error page instead.
     */
    @Test
    @DisplayName("registers the outage filter ahead of Spring Session's filter")
    void registersOutageFilterAheadOfSessionFilter() {
        servletRunner.run(context -> {
            FilterRegistrationBean<?> registration =
                    context.getBean(DATASTORE_OUTAGE_REGISTRATION, FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(DatastoreOutageErrorFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactly(ALL_URLS);
            assertThat(registration.getFilterName()).isEqualTo("datastoreOutageErrorFilter");
            assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 20);
            assertThat(registration.getOrder()).isLessThan(SESSION_REPOSITORY_FILTER_ORDER);
        });
    }

    /**
     * :purpose: Select the correlation-id registration by bean name, since the
     *   configuration also contributes the request-logging registration.
     * :param context: the running application context.
     * :returns: the ``correlationIdFilterRegistration`` bean.
     */
    private static FilterRegistrationBean<?> correlationRegistration(
            org.springframework.context.ApplicationContext context) {
        return context.getBean(CORRELATION_REGISTRATION, FilterRegistrationBean.class);
    }

    /**
     * :purpose: Outside a servlet application the ``@ConditionalOnWebApplication`` gate
     *   keeps the servlet filter out of the context entirely, so a non-servlet module can
     *   import the class without pulling in servlet infrastructure.
     */
    @Test
    @DisplayName("contributes nothing outside a servlet application")
    void contributesNothingOutsideServletApplication() {
        nonWebRunner.run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }
    /**
     * :purpose: Guard the correlation id present on container-rendered error bodies. A filter
     *   registration defaults to the ``REQUEST`` dispatch alone; with that default the container's
     *   ``ERROR`` dispatch never invokes the filter, ``CardDemoErrorController`` renders with an
     *   empty correlation scope, and every ``sendError`` response -- including the request-firewall
     *   rejections Spring Security raises before a handler is selected -- carries a null
     *   ``correlationId`` while the caller already holds the real id from the response header.
     *   ``shouldNotFilterErrorDispatch()`` alone cannot fix that: it governs only what the filter
     *   does once invoked, never whether the container invokes it.
     */
    @Test
    @DisplayName("registers for the ERROR dispatch as well as REQUEST")
    void registersForErrorDispatchAsWellAsRequest() {
        servletRunner.run(context -> {
            FilterRegistrationBean<?> registration = correlationRegistration(context);
            assertThat(registration.determineDispatcherTypes())
                    .as("the ERROR dispatch must be filtered so error bodies carry the correlation id")
                    .contains(DispatcherType.ERROR)
                    .contains(DispatcherType.REQUEST);
        });
    }

    /**
     * :purpose: The filter itself must also consent to the error dispatch; the registration and the
     *   filter's own override are both required, so both are asserted.
     */
    @Test
    @DisplayName("the registered filter does not opt out of the error dispatch")
    void registeredFilterDoesNotOptOutOfTheErrorDispatch() {
        servletRunner.run(context -> {
            FilterRegistrationBean<?> registration = correlationRegistration(context);
            assertThat(registration.getFilter()).isInstanceOf(CorrelationIdFilter.class);
            CorrelationIdFilter filter = (CorrelationIdFilter) registration.getFilter();
            Boolean optsOut = ReflectionTestUtils.invokeMethod(filter, "shouldNotFilterErrorDispatch");
            assertThat(optsOut)
                    .as("the filter must consent to the error dispatch it is now registered for")
                    .isFalse();
        });
    }
    /**
     * :purpose: The infrastructure-outage filter must sit INSIDE the access-log filter (+10) so an
     *   outage still yields one access-log record, and OUTSIDE Spring Session's
     *   ``SessionRepositoryFilter`` (``Integer.MIN_VALUE + 50``) so the session load it must
     *   intercept happens within its try block. An order at or beyond the session filter's would
     *   leave the session-store timeout -- the very failure this exists for -- out of reach.
     */
    @Test
    @DisplayName("the outage filter is ordered inside the access log and outside Spring Session")
    void outageFilterIsOrderedInsideAccessLogAndOutsideSpringSession() {
        servletRunner.run(context -> {
            FilterRegistrationBean<?> outage =
                    context.getBean(DATASTORE_OUTAGE_REGISTRATION, FilterRegistrationBean.class);
            assertThat(outage.getFilter()).isInstanceOf(DatastoreOutageErrorFilter.class);
            assertThat(outage.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 20);
            assertThat(outage.getOrder())
                    .as("must run inside the access-log filter so the outage is still logged")
                    .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 10);
            assertThat(outage.getOrder())
                    .as("must run outside Spring Session's filter so the session load is in reach")
                    .isLessThan(Integer.MIN_VALUE + 50);
            assertThat(outage.getUrlPatterns()).containsExactly(ALL_URLS);
        });
    }
}
