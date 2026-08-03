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

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static com.carddemo.common.security.ManagementSecurityConfig.MONITORING_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Runtime verification of the observability surface the AAP (0.7.5) requires and
 *   the Explainability decision log describes: Actuator liveness/readiness probes, the
 *   Prometheus scrape endpoint including the shared tags and latency histogram contributed
 *   by ``com.carddemo.common.config.ObservabilityConfig``, the credential-protected status
 *   of the remaining management endpoints, and distributed tracing with correlation
 *   identifiers reaching the logging MDC. The probes worked at runtime but no test asserted
 *   any of it, so a regression that silently disabled a probe, dropped the ``application``
 *   tag or turned tracing into a no-op would not have been caught.
 * :output: A Failsafe (``*IT``) test that boots the gateway on a random port against a real
 *   ``redis:8`` and asserts each endpoint's status and payload, the configured trace
 *   sampling probability, and that a sampled span publishes ``traceId``/``spanId`` into the
 *   MDC for the duration of its scope and clears them afterwards.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "carddemo.monitoring.password=" + ObservabilityEndpointsIT.MONITORING_PASSWORD)
@AutoConfigureTestRestTemplate
@Testcontainers
class ObservabilityEndpointsIT {

    /** :purpose: 32 lower-case hex characters — the W3C trace-context trace id shape. */
    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";

    /** :purpose: 16 lower-case hex characters — the W3C trace-context span id shape. */
    private static final String SPAN_ID_PATTERN = "^[0-9a-f]{16}$";

    /** :purpose: Real Redis: the gateway's session repository needs a live connection. */
    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);

    /**
     * :purpose: Bind the gateway's Redis connection to the container.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private Tracer tracer;

    /**
     * :purpose: The Kubernetes readiness probe answers ``200`` with ``UP`` without
     *   credentials, so a rolling deployment can gate traffic on it.
     */
    @Test
    @DisplayName("/actuator/health/readiness -> 200 UP unauthenticated")
    void readinessProbeIsUpAndPublic() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health/readiness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    /**
     * :purpose: The Kubernetes liveness probe answers ``200`` with ``UP`` without
     *   credentials, so the orchestrator never restarts a healthy instance.
     */
    @Test
    @DisplayName("/actuator/health/liveness -> 200 UP unauthenticated")
    void livenessProbeIsUpAndPublic() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health/liveness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    /**
     * :purpose: The aggregate health endpoint is reachable and reports ``UP``, and its
     *   details stay hidden from an unauthenticated caller (``show-details:
     *   when-authorized``) so component names and versions are not disclosed.
     */
    @Test
    @DisplayName("/actuator/health -> 200 UP with no details for an unauthenticated caller")
    void aggregateHealthIsUpWithoutDisclosingDetails() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).doesNotContain("\"components\"");
    }

    /** :purpose: Password for the dedicated ``monitoring`` principal guarding the scrape. */
    static final String MONITORING_PASSWORD = "qa-observability-monitoring";

    /**
     * :purpose: The Prometheus scrape endpoint is exposed and carries the cross-cutting
     *   instrumentation ``ObservabilityConfig`` contributes: the ``application`` common tag
     *   on every meter and the 200 ms service-level objective bucket on
     *   ``http.server.requests``. Both are asserted on real scraped output. The endpoint
     *   itself is served only to the dedicated ``monitoring`` principal, which is why
     *   ``observability/prometheus.yml`` scrapes with basic auth.
     */
    @Test
    @DisplayName("/actuator/prometheus exposes the application tag and the 200ms SLO bucket")
    void prometheusEndpointCarriesCommonTagsAndLatencySlo() {
        // Generate at least one server request so the http.server.requests timer exists.
        restTemplate.getForEntity("/actuator/health/liveness", String.class);

        assertThat(restTemplate.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .as("uncredentialed scrape")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> response = restTemplate
                .withBasicAuth(MONITORING_USERNAME, MONITORING_PASSWORD)
                .getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("application=\"api-gateway\"");
        assertThat(body).contains("http_server_requests_seconds");
        // The SLO is published as an explicit histogram bucket at 0.2 seconds.
        assertThat(body).contains("http_server_requests_seconds_bucket");
        assertThat(body).contains("le=\"0.2\"");
    }

    /**
     * :purpose: Management endpoints that are not part of the public probe surface stay
     *   closed to an unauthenticated caller. The gateway's filter chain installs an
     *   ``HttpStatusEntryPoint(UNAUTHORIZED)``, so its authoritative rejection status is
     *   ``401``, the same challenge the service chains answer with: ``403`` is reserved for
     *   a principal that is authenticated but lacks the required authority.
     */
    @Test
    @DisplayName("non-probe management endpoints -> 401 on the gateway chain")
    void nonProbeManagementEndpointsAreClosed() {
        for (String path : new String[]{"/actuator/metrics", "/actuator/env", "/actuator/beans"}) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);
            assertThat(response.getStatusCode())
                    .as("unauthenticated %s", path)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * :purpose: The gateway samples every trace, as the decision log records (``1.0`` for the
     *   gateway, ``0.1`` for the services), so a request entering the system always produces a
     *   trace that can be followed across service boundaries.
     */
    @Test
    @DisplayName("the gateway trace sampling probability is the decision-logged 1.0")
    void traceSamplingProbabilityIsFullyOn() {
        assertThat(environment.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("1.0");
    }

    /**
     * :purpose: Tracing is really wired rather than a no-op: a span opened on the shared
     *   {@link Tracer} carries a W3C-shaped trace and span id, publishes both into the SLF4J
     *   MDC for the duration of its scope — which is what makes the structured JSON logs
     *   correlatable across services — and clears them when the scope closes so identifiers
     *   never leak onto an unrelated thread.
     */
    @Test
    @DisplayName("a sampled span publishes traceId/spanId into the MDC and clears them after")
    void sampledSpanPublishesTraceIdentifiersIntoTheMdc() {
        assertThat(tracer).isNotNull();
        Span span = tracer.nextSpan().name("qa-observability-verification").start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            String traceId = span.context().traceId();
            String spanId = span.context().spanId();
            assertThat(traceId).matches(TRACE_ID_PATTERN);
            assertThat(spanId).matches(SPAN_ID_PATTERN);
            assertThat(MDC.get("traceId")).isEqualTo(traceId);
            assertThat(MDC.get("spanId")).isEqualTo(spanId);
        } finally {
            span.end();
        }
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
    }
}
