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
package com.carddemo.gateway.config;

import com.carddemo.common.config.CorrelationIdContext;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Verifies the api-gateway declarative downstream route table
 *     (``spring.cloud.gateway.server.webmvc.routes`` in ``application.yml``)
 *     binds the eight expected service routes to their base URIs, leaves the
 *     locally-served ``/menu`` and ``/admin`` paths unrouted, and that
 *     {@link GatewayRoutesConfig} registers the correlation-id propagation
 *     servlet filter as a single ``FilterRegistrationBean`` ordered just after
 *     the shared correlation-id seeding filter over every request path.
 * :note: Servlet Web MVC only; the route table is asserted by binding the YAML
 *     document, and the filter bean is asserted both through a minimal
 *     application context and by direct construction. No web server, database,
 *     Redis, or full Spring Boot context is started.
 */
@DisplayName("GatewayRoutesConfig")
class GatewayRoutesConfigTest {

    /**
     * :purpose: Configuration-property prefix of the servlet Web MVC gateway
     *     route table in ``application.yml``.
     */
    private static final String ROUTES_PREFIX = "spring.cloud.gateway.server.webmvc.routes";

    /**
     * :purpose: Marker key present only in the profile-scoped YAML document,
     *     used to isolate and bind the default (non-profile) document.
     */
    private static final String PROFILE_ACTIVATION_KEY = "spring.config.activate.on-profile";

    /**
     * :purpose: Header carrying the business correlation id that the gateway
     *     propagation filter mirrors onto the proxied downstream request.
     */
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * :purpose: Clear any correlation id left on the current (pooled) thread
     *     before each test so a case never inherits state from a prior test.
     */
    @BeforeEach
    void clearCorrelationIdBefore() {
        CorrelationIdContext.clear();
    }

    /**
     * :purpose: Clear the correlation id after each test so no id leaks onto the
     *     pooled thread for subsequent tests.
     */
    @AfterEach
    void clearCorrelationIdAfter() {
        CorrelationIdContext.clear();
    }

    /**
     * :purpose: Locate the default (non-profile) YAML document within
     *     ``application.yml`` by selecting the document that does not carry the
     *     ``spring.config.activate.on-profile`` key, independent of document order.
     * :returns: the property source backing the default YAML document.
     */
    private PropertySource<?> defaultDocument() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("gateway-application", new ClassPathResource("application.yml"));
        return documents.stream()
                .filter(document -> document.getProperty(PROFILE_ACTIVATION_KEY) == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No default (non-profile) document found in application.yml"));
    }

    /**
     * :purpose: Bind the default document's route table to a list of
     *     {@link RouteBinding}, resolving ``${*:default}`` placeholders against
     *     only the YAML source so the localhost defaults apply regardless of any
     *     ambient environment variables.
     * :returns: the eight bound route definitions.
     */
    private List<RouteBinding> loadDefaultRoutes() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(defaultDocument());
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind(ROUTES_PREFIX, Bindable.listOf(RouteBinding.class)).get();
    }

    /**
     * :purpose: Assert that a route with the given id is present and binds the
     *     expected single ``Path`` predicate and base URI.
     * :param byId: routes indexed by their id.
     * :param id: the route id under assertion.
     * :param path: the expected ``Path`` predicate suffix (e.g. ``/auth/**``).
     * :param uri: the expected downstream base URI.
     */
    private void assertRoute(Map<String, RouteBinding> byId, String id, String path, String uri) {
        RouteBinding route = byId.get(id);
        assertThat(route).as("route %s should be present", id).isNotNull();
        assertThat(route.uri()).isEqualTo(uri);
        assertThat(route.predicates()).containsExactly("Path=" + path);
    }

    /**
     * :purpose: Confirm the route table exposes exactly the eight downstream
     *     service ids and no others.
     */
    @Test
    @DisplayName("route table exposes exactly the eight downstream service ids")
    void routeTableExposesEightDownstreamServices() throws IOException {
        assertThat(loadDefaultRoutes()).extracting(RouteBinding::id)
                .containsExactlyInAnyOrder("auth-service", "user-service", "account-service",
                        "card-service", "transaction-service", "billpay-service",
                        "reporting-service", "batch-service");
    }

    /**
     * :purpose: Confirm each route binds its expected single ``Path`` predicate
     *     and its localhost base URI resolved from the placeholder default.
     */
    @Test
    @DisplayName("each route binds its expected Path predicate and localhost base URI")
    void eachRouteBindsExpectedPathPredicateAndBaseUri() throws IOException {
        Map<String, RouteBinding> byId = loadDefaultRoutes().stream()
                .collect(Collectors.toMap(RouteBinding::id, route -> route));
        assertRoute(byId, "auth-service", "/auth/**", "http://localhost:8081");
        assertRoute(byId, "user-service", "/users/**", "http://localhost:8082");
        assertRoute(byId, "account-service", "/accounts/**", "http://localhost:8083");
        assertRoute(byId, "card-service", "/cards/**", "http://localhost:8084");
        assertRoute(byId, "transaction-service", "/transactions/**", "http://localhost:8085");
        assertRoute(byId, "billpay-service", "/billpay/**", "http://localhost:8086");
        assertRoute(byId, "reporting-service", "/reports/**", "http://localhost:8087");
        assertRoute(byId, "batch-service", "/batch/**", "http://localhost:8088");
    }

    /**
     * :purpose: Confirm the locally-served ``/menu`` and ``/admin`` paths are not
     *     declared as downstream routes.
     */
    @Test
    @DisplayName("locally-served /menu and /admin paths are not routed downstream")
    void menuAndAdminPathsAreNotRoutedDownstream() throws IOException {
        assertThat(loadDefaultRoutes())
                .flatExtracting(RouteBinding::predicates)
                .noneMatch(predicate -> predicate.contains("/menu") || predicate.contains("/admin"))
                .doesNotContain("Path=/menu/**", "Path=/admin/**");
    }

    /**
     * :purpose: Confirm {@link GatewayRoutesConfig} contributes a single
     *     ``FilterRegistrationBean`` wrapping an {@link OncePerRequestFilter},
     *     ordered at {@link Ordered#HIGHEST_PRECEDENCE} + 1 and mapped over
     *     ``/*``, when loaded into a minimal application context.
     */
    @Test
    @DisplayName("correlation-id propagation filter is registered once at HIGHEST_PRECEDENCE + 1 over /*")
    void correlationIdPropagationFilterRegisteredViaContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(GatewayRoutesConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
                    assertThat(registration.getUrlPatterns()).contains("/*");
                    assertThat(registration.getFilter()).isInstanceOf(OncePerRequestFilter.class);
                });
    }

    /**
     * :purpose: Confirm the correlation-id propagation ``FilterRegistrationBean``
     *     built directly from the configuration exposes the expected order,
     *     url pattern, and filter type.
     */
    @Test
    @DisplayName("correlation-id propagation filter bean is built with the expected order, url pattern, and type")
    void correlationIdPropagationFilterBuiltDirectly() {
        FilterRegistrationBean<OncePerRequestFilter> registration =
                new GatewayRoutesConfig().correlationIdPropagationFilter();
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(registration.getUrlPatterns()).contains("/*");
        assertThat(registration.getFilter()).isInstanceOf(OncePerRequestFilter.class);
    }

    /**
     * :purpose: Confirm the filter mirrors the active correlation id onto the
     *     request forwarded downstream as the ``X-Correlation-Id`` header.
     */
    @Test
    @DisplayName("filter mirrors the active correlation id onto the proxied downstream request header")
    void filterPropagatesCorrelationIdOntoDownstreamRequestHeader() throws Exception {
        OncePerRequestFilter filter = new GatewayRoutesConfig().correlationIdPropagationFilter().getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        try {
            CorrelationIdContext.setCorrelationId("test-cid");
            filter.doFilter(request, response, chain);
            HttpServletRequest proxied = (HttpServletRequest) chain.getRequest();
            assertThat(proxied.getHeader(CORRELATION_ID_HEADER)).isEqualTo("test-cid");
        } finally {
            CorrelationIdContext.clear();
        }
    }

    /**
     * :purpose: Confirm the filter forwards the original request unchanged (no
     *     correlation header injected) when no correlation id is present.
     */
    @Test
    @DisplayName("filter forwards the original request unchanged when no correlation id is present")
    void filterLeavesRequestUnwrappedWhenNoCorrelationIdPresent() throws Exception {
        OncePerRequestFilter filter = new GatewayRoutesConfig().correlationIdPropagationFilter().getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(((HttpServletRequest) chain.getRequest()).getHeader(CORRELATION_ID_HEADER)).isNull();
    }

    /**
     * :purpose: Minimal binding view of one YAML gateway route entry (id, base
     *     URI, and its list of predicate expressions).
     * :param id: the route id.
     * :param uri: the downstream base URI.
     * :param predicates: the route's predicate expressions.
     */
    record RouteBinding(String id, String uri, List<String> predicates) {
    }
}
