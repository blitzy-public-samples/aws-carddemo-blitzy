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
package com.aws.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import com.aws.carddemo.observability.CorrelationIdFilter;

import io.micrometer.observation.Observation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

/**
 * Unit tests for {@link ObservabilityConfig#isObservable(String, Observation.Context)}, the
 * infrastructure-endpoint observation predicate (QA findings F-OBS-1).
 *
 * <p>Two branches are exercised:</p>
 * <ul>
 *   <li>For an HTTP server observation ({@link ServerRequestObservationContext}) the request URI is
 *       read directly from the carrier: business paths are observed, the {@code /actuator},
 *       {@code /swagger-ui}, and {@code /v3/api-docs} infrastructure prefixes are suppressed, and a
 *       {@code null} URI is treated as observable.</li>
 *   <li>For any other observation context &mdash; standing in for Spring Security's
 *       {@code spring.security.filterchains} observation, whose context carries no request URI
 *       &mdash; the predicate consults the request path captured for the current thread by
 *       {@link CorrelationIdFilter}. During an infrastructure request the security observation is
 *       therefore suppressed (so the frequent Prometheus scrape does not create an orphan
 *       filter-chain root trace), during a business request it is observed, and on a non-request
 *       (batch) thread &mdash; where no path is captured &mdash; it is observed.</li>
 * </ul>
 */
class ObservabilityConfigTest {

    /** Observation name Spring Security uses for its filter-chain observation. */
    private static final String SECURITY_OBSERVATION = "spring.security.filterchains";

    /** Observation name Spring Boot uses for the HTTP server observation. */
    private static final String HTTP_OBSERVATION = "http.server.requests";

    private final ObservabilityConfig config = new ObservabilityConfig();

    /**
     * Guards against thread-local leakage: {@link CorrelationIdFilter} always clears the captured
     * request path in its {@code finally} block, so after every scenario the current thread must
     * report no in-flight request path.
     */
    @AfterEach
    void requestPathIsNotLeaked() {
        assertThat(CorrelationIdFilter.currentRequestPath()).isNull();
    }

    private static ServerRequestObservationContext serverContext(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP server observation: URI read directly from the carrier request.
    // ---------------------------------------------------------------------------------------------

    @Test
    void businessHttpRequestIsObservable() {
        assertThat(config.isObservable(HTTP_OBSERVATION, serverContext("/api/v1/accounts/00000000001")))
                .isTrue();
    }

    @Test
    void actuatorHttpRequestIsSuppressed() {
        assertThat(config.isObservable(HTTP_OBSERVATION, serverContext("/actuator/prometheus")))
                .isFalse();
    }

    @Test
    void swaggerHttpRequestIsSuppressed() {
        assertThat(config.isObservable(HTTP_OBSERVATION, serverContext("/swagger-ui/index.html")))
                .isFalse();
    }

    @Test
    void apiDocsHttpRequestIsSuppressed() {
        assertThat(config.isObservable(HTTP_OBSERVATION, serverContext("/v3/api-docs")))
                .isFalse();
    }

    @Test
    void nullUriHttpRequestIsObservable() {
        assertThat(config.isObservable(HTTP_OBSERVATION, serverContext(null))).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // Non-HTTP context (Spring Security filter-chain observation): uses the thread-captured path.
    // ---------------------------------------------------------------------------------------------

    @Test
    void securityFilterChainDuringActuatorRequestIsSuppressed() throws Exception {
        assertThat(observeSecurityChainDuringRequest("/actuator/prometheus")).isFalse();
    }

    @Test
    void securityFilterChainDuringBusinessRequestIsObservable() throws Exception {
        assertThat(observeSecurityChainDuringRequest("/api/v1/cards")).isTrue();
    }

    @Test
    void nonHttpContextOnNonRequestThreadIsObservable() {
        // No filter has run on this thread, so no request path is captured (the batch-worker case).
        assertThat(CorrelationIdFilter.currentRequestPath()).isNull();
        assertThat(config.isObservable("spring.batch.job", new Observation.Context())).isTrue();
    }

    /**
     * Drives {@link CorrelationIdFilter} for a request at {@code requestUri} and, while the request
     * is in flight (after the filter captures the path, before it clears it), evaluates the
     * predicate for a non-HTTP-server observation context &mdash; exactly as Spring Security's
     * filter-chain observation is started mid-request.
     *
     * @param requestUri the request URI to simulate
     * @return whether a non-HTTP observation started during that request would be observed
     */
    private boolean observeSecurityChainDuringRequest(String requestUri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(requestUri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean observable = new AtomicBoolean();
        FilterChain chain = (req, res) ->
                observable.set(config.isObservable(SECURITY_OBSERVATION, new Observation.Context()));
        new CorrelationIdFilter().doFilter(request, response, chain);
        return observable.get();
    }
}
