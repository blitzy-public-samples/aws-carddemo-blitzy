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
package com.carddemo.card.config;

import com.carddemo.card.CardServiceApplication;
import com.carddemo.common.testsupport.MigratedSchemaContainer;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.carddemo.common.security.ManagementSecurityConfig.MONITORING_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Runtime verification of the observability surface required by AAP 0.7.5 for a
 *   CardDemo business service — the shape shared by account-service, card-service,
 *   transaction-service, billpay-service, reporting-service and batch-service. Two gates
 *   apply: the exposure list
 *   (``management.endpoints.web.exposure.include: health,info,prometheus``) decides what is
 *   published at all, and the security chains decide who may read it — the probes and
 *   ``/actuator/info`` are open so a Kubernetes probe and a container healthcheck need no
 *   credential, while the scrape endpoint is served only to the dedicated ``monitoring``
 *   principal. This class pins both: each endpoint's observed status for the caller that
 *   reaches it, and an unexposed endpoint being genuinely absent rather than merely closed.
 *   It also asserts the correlation id and real span-to-MDC correlation.
 * :output: A Failsafe (``*IT``) test that boots card-service on a random port against the
 *   shared migrated ``postgres:18`` container and asserts each endpoint's observed status and
 *   payload over real HTTP, plus the correlation-id header, the committed production sampling
 *   probability and span-to-MDC correlation.
 * :note: The JDK ``HttpClient`` is used rather than ``TestRestTemplate`` so a non-2xx status
 *   is observed as data instead of being raised as an exception, and so the test needs no
 *   additional Boot test-client module on the classpath.
 */
@SpringBootTest(
        classes = CardServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.tracing.sampling.probability=1.0",
                "carddemo.monitoring.password=" + ObservabilityEndpointsIT.MONITORING_PASSWORD})
@ActiveProfiles("test")
class ObservabilityEndpointsIT {

    /**
     * :purpose: Password for the dedicated ``monitoring`` principal that guards the scrape
     *     endpoint. Left unset the service generates a random one, which no test could
     *     present; a deployment supplies it through ``MONITORING_PASSWORD``.
     */
    static final String MONITORING_PASSWORD = "qa-observability-monitoring";

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

    /** :purpose: The committed web exposure list; nothing outside it may be reachable. */
    private static final String COMMITTED_EXPOSURE = "health,info,prometheus";

    /** :purpose: Request and response header carrying the business correlation id. */
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** :purpose: Per-request timeout; every asserted endpoint answers immediately. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    /**
     * :purpose: Bind the datasource to the shared, already-migrated ``postgres:18`` container
     *   whose schema comes exclusively from the committed Flyway migrations, so the context
     *   boots under the ``test`` profile's ``ddl-auto: validate``.
     * :param registry: the Spring dynamic property registry.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private Tracer tracer;

    /**
     * :purpose: The Kubernetes readiness probe answers ``200`` with ``UP``, so a rolling
     *   deployment can gate traffic on it.
     * :raises Exception: if the HTTP exchange fails.
     */
    @Test
    @DisplayName("/actuator/health/readiness -> 200 UP")
    void readinessProbeIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /**
     * :purpose: The Kubernetes liveness probe answers ``200`` with ``UP``, so the
     *   orchestrator never restarts a healthy instance.
     * :raises Exception: if the HTTP exchange fails.
     */
    @Test
    @DisplayName("/actuator/health/liveness -> 200 UP")
    void livenessProbeIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health/liveness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    /**
     * :purpose: The aggregate health endpoint reports ``UP`` — which, on a service that owns
     *   a datasource, also proves the database health contributor is reachable — while
     *   withholding component detail from an unauthenticated caller
     *   (``show-details: when-authorized``).
     * :raises Exception: if the HTTP exchange fails.
     */
    @Test
    @DisplayName("/actuator/health -> 200 UP with no component detail for an anonymous caller")
    void aggregateHealthIsUpWithoutDisclosingDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(response.body()).doesNotContain("\"components\"");
    }

    /**
     * :purpose: The Prometheus scrape endpoint carries the cross-cutting instrumentation
     *   ``com.carddemo.common.config.ObservabilityConfig`` contributes: the per-service
     *   ``application`` common tag and the explicit 200 ms latency-objective bucket
     *   (AAP 0.7.1) on ``http.server.requests``, plus the JVM and datasource meters a
     *   Grafana dashboard needs.
     * :raises Exception: if the HTTP exchange fails.
     */
    @Test
    @DisplayName("/actuator/prometheus -> 200 with the application tag and the 200ms SLO bucket")
    void prometheusEndpointCarriesCommonTagsAndLatencySlo() throws Exception {
        // Generate at least one server request so the http.server.requests timer exists.
        get("/actuator/health/liveness");

        // The scrape carries per-endpoint traffic detail, so it is served only to the
        // dedicated monitoring principal; observability/prometheus.yml scrapes with basic
        // auth for exactly this reason. An uncredentialed scrape is challenged.
        assertThat(get("/actuator/prometheus").statusCode())
                .as("uncredentialed scrape").isEqualTo(401);

        HttpResponse<String> response = get("/actuator/prometheus",
                "Authorization", basicAuth(MONITORING_USERNAME, MONITORING_PASSWORD));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("application=\"card-service\"");
        assertThat(response.body()).contains("http_server_requests_seconds_bucket");
        assertThat(response.body()).contains("le=\"0.2\"");
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }

    /**
     * :purpose: A management endpoint OUTSIDE the committed exposure list is not merely
     *   protected, it is not published at all, so an operator cannot read environment
     *   properties or the bean graph off a running service. On a chain with no security
     *   configuration the exposure list is the only gate, which makes this assertion the
     *   substantive one.
     * :raises Exception: if an HTTP exchange fails.
     */
    @Test
    @DisplayName("unexposed management endpoints -> absent to an authorized caller, closed to everyone else")
    void unexposedManagementEndpointsAreAbsent() throws Exception {
        // /actuator/metrics/** is guarded by the management chain, so the authorized
        // monitoring principal is the caller that can observe publication at all: a 404
        // proves the endpoint is genuinely absent rather than merely protected.
        assertThat(get("/actuator/metrics",
                "Authorization", basicAuth(MONITORING_USERNAME, MONITORING_PASSWORD)).statusCode())
                .as("authorized /actuator/metrics").isEqualTo(404);

        // Everything outside the exposure list and outside the management matcher is closed
        // by the service chain before dispatch, so an anonymous caller cannot even learn
        // whether it exists.
        for (String path : new String[]{"/actuator/env", "/actuator/beans", "/actuator/metrics"}) {
            assertThat(get(path).statusCode()).as("anonymous %s", path).isEqualTo(401);
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
     * :purpose: The committed exposure list is exactly ``health,info,prometheus`` — the
     *   minimum the Kubernetes probes and the Prometheus scrape need — so a future edit that
     *   published a sensitive endpoint fails here.
     */
    @Test
    @DisplayName("the committed exposure list is the minimal health,info,prometheus")
    void committedExposureListIsMinimal() {
        assertThat(committedProperty("management.endpoints.web.exposure.include"))
                .isEqualTo(COMMITTED_EXPOSURE);
    }

    /**
     * :purpose: The correlation filter is in the chain: an inbound ``X-Correlation-Id`` is
     *   echoed back verbatim and a request without one receives a generated id, so the
     *   ``correlationId`` MDC key rendered by ``logback-spring.xml`` is never empty.
     * :raises Exception: if an HTTP exchange fails.
     */
    @Test
    @DisplayName("the correlation id is echoed on the response, inbound or generated")
    void correlationIdIsEchoedOnEveryResponse() throws Exception {
        String supplied = "qa-observability-card-0001";

        HttpResponse<String> echoed =
                get("/actuator/health/liveness", CORRELATION_ID_HEADER, supplied);
        assertThat(echoed.headers().firstValue(CORRELATION_ID_HEADER)).contains(supplied);

        HttpResponse<String> generated = get("/actuator/health/liveness");
        assertThat(generated.headers().firstValue(CORRELATION_ID_HEADER))
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
