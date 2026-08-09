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
package com.carddemo.user.config;

import com.carddemo.common.config.CorrelationIdFilter;
import com.carddemo.user.AbstractIntegrationTest;
import com.carddemo.user.UserServiceApplication;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static com.carddemo.common.security.ManagementSecurityConfig.MONITORING_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Runtime verification of the user-service observability surface required by
 *   AAP 0.7.5. The user-service filter chain is the STRICTEST in the system — only the
 *   the Kubernetes probes, the aggregate health endpoint and ``/actuator/info`` are
 *   anonymous, the scrape endpoint answers only the dedicated ``monitoring`` principal and
 *   every other request requires ``ROLE_ADMIN`` — so this class pins that exact
 *   contract, together with the correlation id installed by
 *   ``com.carddemo.common.config.WebObservabilityConfig`` and real span-to-MDC correlation.
 *   Nothing about the actuator surface or tracing was asserted anywhere before.
 * :output: A Failsafe (``*IT``) test that boots user-service on a random port against the
 *   shared migrated ``postgres:18`` container of {@link AbstractIntegrationTest} plus its own
 *   ``redis:8`` container, and asserts each endpoint's observed status over real HTTP, the
 *   correlation-id response header, the committed production sampling probability and
 *   span-to-MDC correlation.
 * :note: The Redis container is REQUIRED, not incidental. Spring Boot 4 selects the
 *   Redis-backed session store purely from the presence of ``spring-session-data-redis`` on
 *   the classpath, so the servlet session filter commits every request's session through
 *   Redis exactly as in production; without a reachable store the filter turns each response
 *   into a ``500`` on the way out and the real chain contract could not be observed.
 * :note: The ``test`` profile pins sampling to ``0.0`` to keep the suite hermetic, so this
 *   context re-raises it to ``1.0`` to exercise real span creation; the committed PRODUCTION
 *   default is asserted from the packaged ``application.yml``.
 * :note: The JDK ``HttpClient`` is used rather than ``TestRestTemplate`` so a non-2xx status
 *   is observed as data instead of being raised as an exception, and so the test needs no
 *   additional Boot test-client module on the classpath.
 */
@SpringBootTest(
        classes = UserServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.tracing.sampling.probability=1.0",
                "carddemo.monitoring.password=" + ObservabilityEndpointsIT.MONITORING_PASSWORD})
@Testcontainers
class ObservabilityEndpointsIT extends AbstractIntegrationTest {

    /**
     * :purpose: Password for the dedicated ``monitoring`` principal that guards the scrape
     *     endpoint. Left unset the service generates a random one, which no test could
     *     present; a deployment supplies it through ``MONITORING_PASSWORD``.
     */
    static final String MONITORING_PASSWORD = "qa-observability-monitoring";

    /**
     * :purpose: Real Redis backing the servlet session store, so the production session
     *   filter can commit sessions instead of failing every response.
     */
    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379);

    /**
     * :purpose: Point the Redis connection factory at the container.
     * :param registry: the dynamic property registry supplied by the test context.
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /** :purpose: 32 lower-case hex characters — the W3C trace-context trace id shape. */
    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";

    /** :purpose: 16 lower-case hex characters — the W3C trace-context span id shape. */
    private static final String SPAN_ID_PATTERN = "^[0-9a-f]{16}$";

    /**
     * :purpose: The committed production sampling default for a BUSINESS service, exactly as
     *   the decision log records it ("Trace sampling 0.1 for services, 1.0 for the
     *   api-gateway") and as it must remain environment-overridable.
     */
    private static final String COMMITTED_SAMPLING =
            "${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}";

    /** :purpose: Per-request timeout; every asserted endpoint answers immediately. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private Tracer tracer;

    /**
     * :purpose: The readiness probe is anonymous and answers ``200`` with ``UP``, so a
     *   rolling deployment can gate traffic on it without holding a credential.
     * :raises Exception: if the HTTP exchange fails.
     */
    @Test
    @DisplayName("/actuator/health/readiness -> 200 UP unauthenticated")
    void readinessProbeIsUpAndPublic() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /**
     * :purpose: The liveness probe is anonymous and answers ``200`` with ``UP``, so the
     *   orchestrator never restarts a healthy instance.
     * :raises Exception: if the HTTP exchange fails.
     */
    @Test
    @DisplayName("/actuator/health/liveness -> 200 UP unauthenticated")
    void livenessProbeIsUpAndPublic() throws Exception {
        HttpResponse<String> response = get("/actuator/health/liveness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /**
     * :purpose: The management surface answers on three tiers. The aggregate health endpoint
     *   and ``/actuator/info`` are open, because the container healthcheck and the Kubernetes
     *   probes present no credential — and they disclose nothing, since
     *   ``show-details: when-authorized`` keeps the datastore components out of an anonymous
     *   response. The scrape endpoint carries per-endpoint traffic detail and is served only
     *   to the dedicated ``monitoring`` principal. Everything else is closed before dispatch,
     *   with the anonymous caller challenged (``401``) rather than told it is forbidden.
     * :raises Exception: if an HTTP exchange fails.
     */
    @Test
    @DisplayName("management surface: probes and info open, telemetry credentialed, the rest closed")
    void nonProbeManagementEndpointsAreForbidden() throws Exception {
        for (String open : new String[]{"/actuator/health", "/actuator/info"}) {
            HttpResponse<String> response = get(open);
            assertThat(response.statusCode()).as("anonymous %s", open).isNotIn(401, 403);
        }
        assertThat(get("/actuator/health").body())
                .as("an anonymous health response must not enumerate the datastore components")
                .doesNotContain("\"components\"");

        assertThat(get("/actuator/prometheus").statusCode())
                .as("uncredentialed scrape").isEqualTo(401);
        assertThat(get("/actuator/prometheus",
                "Authorization", basicAuth(MONITORING_USERNAME, MONITORING_PASSWORD)).statusCode())
                .as("credentialed scrape").isEqualTo(200);

        for (String closed : new String[]{"/actuator/env", "/actuator/beans", "/actuator/metrics"}) {
            assertThat(get(closed).statusCode()).as("anonymous %s", closed).isEqualTo(401);
        }
        // The refusal carries the shared ErrorResponse envelope — one fixed operator sentence
        // and a machine-readable code — and discloses nothing about the endpoint it protected.
        for (String closed : new String[]{"/actuator/env", "/actuator/beans"}) {
            assertThat(get(closed).body()).as("body of %s", closed)
                    .contains("\"status\":401")
                    .contains("\"errorCode\":\"AUTHENTICATION_REQUIRED\"")
                    .doesNotContain("Exception", "org.springframework", "spring.datasource",
                            "CARDDEMO_PII_KEY", "requirepass", "dataSource");
        }
    }

    /**
     * :purpose: Compose an HTTP Basic ``Authorization`` header value.
     * :param username: the principal name.
     * :param password: the raw password.
     * :returns: the ``Basic <base64>`` header value.
     */
    private static String basicAuth(String username, String password) {
        return "Basic " + java.util.Base64.getEncoder()
                .encodeToString((username + ":" + password)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * :purpose: Even a rejected request carries the browser-hardening headers, so a closed
     *   management endpoint cannot be framed or content-type sniffed and its response is
     *   never cached.
     * :raises Exception: if the HTTP exchange fails.
     */
    @Test
    @DisplayName("security response headers are present on a rejected management request")
    void securityHeadersArePresentOnRejection() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("Cache-Control"))
                .contains("no-cache, no-store, max-age=0, must-revalidate");
    }

    /**
     * :purpose: The shared correlation filter registered by ``WebObservabilityConfig`` runs
     *   ahead of security, so even an anonymous probe response carries a correlation id: an
     *   inbound ``X-Correlation-Id`` is echoed verbatim and a request without one receives a
     *   generated id. Without the filter the ``correlationId`` MDC key rendered by
     *   ``logback-spring.xml`` would always be empty.
     * :raises Exception: if an HTTP exchange fails.
     */
    @Test
    @DisplayName("the correlation id is echoed on the response, inbound or generated")
    void correlationIdIsEchoedOnEveryResponse() throws Exception {
        String supplied = "qa-observability-user-0001";

        HttpResponse<String> echoed = get("/actuator/health/readiness",
                CorrelationIdFilter.CORRELATION_ID_HEADER, supplied);
        assertThat(echoed.headers().firstValue(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .contains(supplied);

        HttpResponse<String> generated = get("/actuator/health/readiness");
        assertThat(generated.headers().firstValue(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isPresent()
                .get()
                .asString()
                .isNotBlank();
    }

    /**
     * :purpose: The COMMITTED production configuration samples 10% of traces for a business
     *   service, as the decision log records, and keeps the value environment-overridable.
     */
    @Test
    @DisplayName("the committed production sampling probability is the decision-logged 0.1")
    void committedProductionSamplingProbabilityIsTenPercent() {
        assertThat(committedProperty("management.tracing.sampling.probability"))
                .isEqualTo(COMMITTED_SAMPLING);
    }

    /**
     * :purpose: Tracing is really wired rather than a no-op: a span opened on the injected
     *   {@link Tracer} carries W3C-shaped identifiers, publishes ``traceId``/``spanId`` into
     *   the SLF4J MDC for the duration of its scope and clears them afterwards.
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

    /**
     * :purpose: Issue a plain ``GET`` against the running service.
     * :param path: the absolute request path, for example ``/actuator/health``.
     * :returns: the raw response, whatever its status.
     * :raises IOException: if the exchange fails.
     * :raises InterruptedException: if the calling thread is interrupted.
     */
    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * :purpose: Issue a ``GET`` carrying a single request header.
     * :param path: the absolute request path.
     * :param headerName: the header to send.
     * :param headerValue: the header value to send.
     * :returns: the raw response, whatever its status.
     * :raises IOException: if the exchange fails.
     * :raises InterruptedException: if the calling thread is interrupted.
     */
    private HttpResponse<String> get(String path, String headerName, String headerValue)
            throws IOException, InterruptedException {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(REQUEST_TIMEOUT)
                        .header(headerName, headerValue)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * :purpose: Read a raw value out of the module's own committed ``application.yml``,
     *   unresolved and unaffected by the active profile or by test property overrides.
     * :param key: the canonical property name to look up.
     * :returns: the last value declared for the key across the file's YAML documents, or
     *   ``null`` when the key is absent.
     */
    private String committedProperty(String key) {
        List<PropertySource<?>> documents;
        try {
            documents = new YamlPropertySourceLoader()
                    .load("committed-application", new ClassPathResource("application.yml"));
        } catch (IOException ex) {
            throw new IllegalStateException("committed application.yml is not readable", ex);
        }
        String value = null;
        for (PropertySource<?> document : documents) {
            Object candidate = document.getProperty(key);
            if (candidate != null) {
                value = String.valueOf(candidate);
            }
        }
        return value;
    }
}
