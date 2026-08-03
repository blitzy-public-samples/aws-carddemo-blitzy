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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Verify the registration contract of {@link WebObservabilityConfig}, the
 *   servlet half of the Observability rule (AAP 0.7.5). The class had no test
 *   references, and because ``carddemo-common`` ships no auto-configuration import
 *   file its activation is entirely up to each application class — so the two
 *   properties that matter are asserted here: that importing it really registers the
 *   shared {@link CorrelationIdFilter} for every URL at the highest precedence, and
 *   that it stays inactive outside a servlet application.
 * :output: ``ApplicationContextRunner``-based assertions over the registered
 *   ``FilterRegistrationBean``: filter type, URL mapping, registration name and order,
 *   the companion request-logging registration, plus the
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

    private final WebApplicationContextRunner servletRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(WebObservabilityConfig.class);

    private final ApplicationContextRunner nonWebRunner = new ApplicationContextRunner()
            .withUserConfiguration(WebObservabilityConfig.class);

    /**
     * :purpose: In a servlet application the configuration contributes the correlation-id
     *   registration wrapping the SHARED filter from ``carddemo-common`` rather than some
     *   other filter type, alongside the request-logging registration.
     */
    @Test
    @DisplayName("registers the shared CorrelationIdFilter in a servlet application")
    void registersSharedCorrelationIdFilterInServletApplication() {
        servletRunner.run(context -> {
            assertThat(context.getBeansOfType(FilterRegistrationBean.class))
                    .containsOnlyKeys(CORRELATION_REGISTRATION, REQUEST_LOGGING_REGISTRATION);
            assertThat(correlationRegistration(context).getFilter())
                    .isInstanceOf(CorrelationIdFilter.class);
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
        });
    }

    /**
     * :purpose: Outside a servlet application the ``@ConditionalOnWebApplication`` gate
     *   keeps the servlet filter out of the context entirely, so a non-servlet module can
     *   import the class without pulling in servlet infrastructure.
     */
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

    @Test
    @DisplayName("contributes nothing outside a servlet application")
    void contributesNothingOutsideServletApplication() {
        nonWebRunner.run(context -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
    }
}
