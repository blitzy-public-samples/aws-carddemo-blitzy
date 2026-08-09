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
package com.carddemo.auth.config;

import com.carddemo.auth.AbstractIntegrationTest;
import com.carddemo.auth.AuthServiceApplication;
import com.carddemo.common.config.CorrelationIdFilter;

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

import static com.carddemo.common.security.ManagementSecurityConfig.MONITORING_USERNAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Runtime verification of the auth-service observability surface required by
 *   AAP 0.7.5 — Actuator liveness/readiness probes, the Prometheus scrape endpoint
 *   carrying the shared instrumentation from
 *   ``com.carddemo.common.config.ObservabilityConfig``, the rejection status of every
 *   other management endpoint under this service's own filter chain, the correlation id
 *   installed by ``com.carddemo.common.config.WebObservabilityConfig``, and distributed
 *   tracing publishing W3C identifiers into the logging MDC. None of it was asserted
 *   anywhere, so a regression that disabled a probe, closed the scrape endpoint or turned
 *   tracing into a no-op could not fail the build.
 * :output: A Failsafe (``*IT``) test that boots auth-service on a random port against the
 *   shared migrated ``postgres:18`` and the ``redis:8`` container of
 *   {@link AbstractIntegrationTest}, then asserts each endpoint's observed status and
 *   payload over real HTTP, the correlation-id response header, the committed production
 *   sampling probability, and span-to-MDC correlation.
 * :note: The ``test`` profile pins sampling to ``0.0`` to keep the suite hermetic, so this
 *   context re-raises it to ``1.0`` in order to exercise real span creation. The committed
 *   PRODUCTION default is asserted separately by reading the packaged ``application.yml``.
 * :note: The configuration class is named EXPLICITLY. Spring Boot would otherwise search
 *   this package for a ``@SpringBootConfiguration`` and find
 *   ``SecurityConfigTest.SliceConfig``, a deliberately minimal web slice that carries
 *   neither the datasource nor the observability imports this test must exercise.
 * :note: The JDK ``HttpClient`` is used rather than ``TestRestTemplate`` so the test needs
 *   no additional Boot test-client module on the classpath, and so a non-2xx status is
 *   observed as data instead of being raised as an exception.
 */
@SpringBootTest(
        classes = AuthServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.tracing.sampling.probability=1.0",
                "carddemo.monitoring.password=" + ObservabilityEndpointsIT.MONITORING_PASSWORD})
class ObservabilityEndpointsIT extends AbstractIntegrationTest {

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
     * :purpose: The Kubernetes readiness probe answers ``200`` with ``UP`` and without
     *   credentials, because ``/actuator/health/**`` is permitted by this service's chain.
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
     * :purpose: The Kubernetes liveness probe answers ``200`` with ``UP`` and without
     *   credentials, so the orchestrator never restarts a healthy instance.
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
     * :purpose: The aggregate health endpoint is explicitly permitted on the auth chain and
     *   reports ``UP`` while withholding component detail from an unauthenticated caller
     *   (``show-details: when-authorized``), so datastore internals are not disclosed.
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
     * :purpose: The Prometheus scrape endpoint is permitted on the auth chain and carries the
     *   cross-cutting instrumentation ``ObservabilityConfig`` contributes: the per-service
     *   ``application`` common tag and the explicit 200 ms latency-objective bucket
     *   (AAP 0.7.1) on ``http.server.requests``.
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
        assertThat(response.body()).contains("application=\"auth-service\"");
        assertThat(response.body()).contains("http_server_requests_seconds_bucket");
        assertThat(response.body()).contains("le=\"0.2\"");
    }

    /**
     * :purpose: ``/actuator/info`` joins the probes on the permitted surface, so a container
     *   healthcheck and an operator can read it without a credential and it discloses only
     *   build metadata. Everything outside that surface stays closed, and an anonymous caller
     *   is CHALLENGED (``401``) rather than told it is forbidden: ``403`` is reserved for a
     *   principal that is authenticated but lacks the required authority.
     * :note: The refusal carries the shared ``ErrorResponse`` envelope, the same one every other
     *   failure carries — a status, a machine-readable code and one fixed operator sentence. It
     *   discloses nothing about the endpoint it protected: no environment property, no bean name,
     *   no metric, no exception and no framework internal. That is asserted directly below rather
     *   than approximated by requiring an empty body, which is what this case previously did.
     * :raises Exception: if an HTTP exchange fails.
     */
    @Test
    @DisplayName("info is permitted; non-permitted management endpoints -> 401 in the shared envelope")
    void nonPermittedManagementEndpointsAreForbidden() throws Exception {
        assertThat(get("/actuator/info").statusCode())
                .as("anonymous /actuator/info").isNotIn(401, 403);

        for (String path : new String[]{"/actuator/metrics", "/actuator/env", "/actuator/beans"}) {
            assertThat(get(path).statusCode()).as("unauthenticated %s", path).isEqualTo(401);
        }
        // /actuator/metrics is matched by the management chain instead, which challenges with
        // HTTP Basic, so its response legitimately carries the challenge rather than the envelope.
        for (String path : new String[]{"/actuator/env", "/actuator/beans"}) {
            assertThat(get(path).body()).as("body of %s", path)
                    .contains("\"status\":401")
                    .contains("\"errorCode\":\"AUTHENTICATION_REQUIRED\"")
                    .contains("\"message\":\"Your session has ended. Please sign on again.\"")
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
     * :purpose: The shared correlation filter registered by ``WebObservabilityConfig`` is
     *   actually in the chain: an inbound ``X-Correlation-Id`` is echoed back verbatim, and a
     *   request without one still receives a generated id. Without the filter the
     *   ``correlationId`` MDC key rendered by ``logback-spring.xml`` would always be empty.
     * :raises Exception: if an HTTP exchange fails.
     */
    @Test
    @DisplayName("the correlation id is echoed on the response, inbound or generated")
    void correlationIdIsEchoedOnEveryResponse() throws Exception {
        String supplied = "qa-observability-auth-0001";

        HttpResponse<String> echoed = get("/actuator/health/liveness",
                CorrelationIdFilter.CORRELATION_ID_HEADER, supplied);
        assertThat(echoed.headers().firstValue(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .contains(supplied);

        HttpResponse<String> generated = get("/actuator/health/liveness");
        assertThat(generated.headers().firstValue(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isPresent()
                .get()
                .asString()
                .isNotBlank();
    }

    /**
     * :purpose: The COMMITTED production configuration samples 10% of traces for a business
     *   service, as the decision log records, and keeps the value environment-overridable.
     *   The running context deliberately overrides it, so the committed value is asserted
     *   from the packaged ``application.yml`` rather than from the live ``Environment``.
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
     *   the SLF4J MDC for the duration of its scope — which is what makes the structured logs
     *   correlatable across service boundaries — and clears them afterwards so identifiers
     *   never leak onto a pooled thread.
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
