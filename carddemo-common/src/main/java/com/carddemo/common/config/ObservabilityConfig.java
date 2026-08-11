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

import com.carddemo.common.security.SensitiveDataMasker;
import io.micrometer.common.KeyValue;
import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.observation.ObservationFilter;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * :purpose: Shared observability configuration that realizes the CardDemo Observability rule for
 *           every microservice and the API gateway. It tags all Micrometer meters and every
 *           observation (and therefore every OpenTelemetry span emitted through the tracing bridge)
 *           with the service name, so Prometheus/Grafana metrics and distributed traces can be
 *           filtered per service.
 * :note: Health, readiness, and liveness are supplied by Spring Boot Actuator (this module depends
 *        on ``spring-boot-starter-actuator``) and are enabled per service through ``application.yml``
 *        and the Kubernetes probes; no health bean is declared here. This library ships no
 *        ``META-INF`` auto-configuration import file, so a service activates this configuration by
 *        either ``@Import(ObservabilityConfig.class)`` or by broadening component scanning to
 *        ``com.carddemo`` or ``com.carddemo.common``.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    /**
     * :purpose: Meter name of the Spring server request timer whose latency distribution backs the
     *           online-latency SLO.
     */
    private static final String HTTP_SERVER_REQUESTS_METER = "http.server.requests";

    /**
     * :purpose: Online-latency service-level objective from AAP 0.7.1 (p95 under 200 ms); published
     *           as a histogram bucket boundary so Prometheus exposes an ``le="0.2"`` bucket.
     */
    private static final Duration LATENCY_SLO = Duration.ofMillis(200);

    /**
     * :purpose: Carry the correlation id of the submitting thread onto the worker thread of
     *           every task run through a Spring-managed executor, so ``@Async`` methods and
     *           asynchronously launched batch jobs log under the same correlation id as the
     *           request that triggered them. Spring Boot's task-executor builder composites
     *           every {@link org.springframework.core.task.TaskDecorator} bean, so this
     *           decorator and {@link #contextPropagatingTaskDecorator()} both apply to the
     *           auto-configured ``applicationTaskExecutor`` without per-service wiring.
     * :returns: the shared correlation-id propagating task decorator.
     */
    @Bean
    CorrelationIdTaskDecorator correlationIdTaskDecorator() {
        return new CorrelationIdTaskDecorator();
    }

    /**
     * :purpose: Tag every meter with the service name so per-service metrics are queryable at
     *           ``/actuator/prometheus``.
     * :param applicationName: the configured ``spring.application.name`` (default ``carddemo``).
     * :returns: a customizer that adds the ``application`` common tag to every meter registry.
     */
    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonMetricsTags(
            @Value("${spring.application.name:carddemo}") String applicationName) {
        return registry -> registry.config().commonTags("application", applicationName);
    }

    /**
     * :purpose: Stamp every observation and trace with the service name so traces can be filtered
     *           across service boundaries.
     * :param applicationName: the configured ``spring.application.name`` (default ``carddemo``).
     * :returns: a filter that adds the ``application`` low-cardinality key-value to every observation.
     */
    @Bean
    ObservationFilter commonObservationTags(
            @Value("${spring.application.name:carddemo}") String applicationName) {
        return context -> context.addLowCardinalityKeyValue(KeyValue.of("application", applicationName));
    }

    /**
     * :purpose: Mask card numbers in every HIGH-cardinality observation value, so no span
     *     attribute exported to the tracing backend carries a primary account number. The servlet
     *     convention's only high-cardinality key is ``http.url``, whose value is the request URI —
     *     for a card screen that URI contains the PAN. Applying {@link SensitiveDataMasker} here
     *     masks it exactly as the log stream, the audit log and the error envelope already mask
     *     it, so every channel redacts identically.
     * :note: LOW-cardinality values are deliberately left untouched: they are the Micrometer
     *     meter tags, and the request-timer's ``uri`` is already the templated route
     *     (``/cards/{cardNumber}``), so the metric label set and its cardinality contract are
     *     unchanged. The span name is the contextual name, also templated.
     * :note: Filters run after the convention has contributed its key values and before any
     *     handler records the span, so the replacement is what reaches the exporter.
     *     ``addHighCardinalityKeyValue`` replaces a value stored under the same key, and
     *     ``getHighCardinalityKeyValues`` returns a snapshot, so replacing while iterating is
     *     safe.
     * :returns: a filter that redacts PAN-shaped digit runs from high-cardinality observation
     *     values.
     */
    @Bean
    ObservationFilter sensitiveTraceAttributeMask() {
        return context -> {
            for (KeyValue keyValue : context.getHighCardinalityKeyValues()) {
                String value = keyValue.getValue();
                String masked = SensitiveDataMasker.maskPan(value);
                if (!masked.equals(value)) {
                    context.addHighCardinalityKeyValue(KeyValue.of(keyValue.getKey(), masked));
                }
            }
            return context;
        };
    }

    /**
     * :purpose: Publish a percentile histogram and an explicit 200 ms service-level-objective
     *           boundary for the ``http.server.requests`` timer on every service and the API
     *           gateway, so the online-latency SLO (AAP 0.7.1) is computable at
     *           ``/actuator/prometheus``: ``histogram_quantile`` needs the emitted
     *           ``http_server_requests_seconds_bucket`` series and the SLO-compliance ratio needs
     *           an ``le="0.2"`` bucket. Only the request-timer distribution is affected; every
     *           other meter is returned unchanged.
     * :returns: a meter filter that merges histogram publication and the 200 ms SLO boundary into
     *           the distribution config of the ``http.server.requests`` timer.
     */
    @Bean
    MeterFilter httpServerRequestsHistogram() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (id.getType() == Meter.Type.TIMER && HTTP_SERVER_REQUESTS_METER.equals(id.getName())) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .serviceLevelObjectives((double) LATENCY_SLO.toNanos())
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    /**
     * :purpose: Carry the ambient observation/trace scope (and every other registered
     *           ``ThreadLocalAccessor`` context, including the correlation id) across thread
     *           boundaries, so work handed to an executor keeps the trace of the request that
     *           submitted it. It is composited with {@link #correlationIdTaskDecorator()} onto the
     *           auto-configured application task executor, which is what backs ``@Async`` — this is
     *           how the asynchronous report launch in reporting-service stays attached to its
     *           caller's trace instead of starting an orphan with no ``traceId``/``spanId``.
     * :note: Executors built by hand (for example the batch ``TaskExecutorJobOperator``) are not
     *        reached by that auto-configuration and set this decorator on themselves.
     * :returns: a context-propagating task decorator shared by every service.
     */
    @Bean
    TaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

    /**
     * :purpose: Register {@link CorrelationIdThreadLocalAccessor} in Micrometer's global
     *     ``ContextRegistry`` so the business correlation id travels with the trace context across
     *     every thread boundary crossed by {@link #contextPropagatingTaskDecorator()} — ``@Async``
     *     report launches and Spring Batch worker threads included.
     * :note: The decorator alone only propagates contexts that have a registered accessor.
     *     Without this registration the MDC's ``correlationId`` (a plain ``ThreadLocal``) was lost
     *     the moment work left the request thread, so asynchronous and batch log records could not
     *     be correlated back to the request that launched them.
     * :note: Declared here rather than in {@link WebObservabilityConfig} because the
     *     correlation id is not web-specific: a non-web module that imports this configuration
     *     gets the same cross-thread propagation. ``registerThreadLocalAccessor`` replaces any
     *     accessor already registered under the same key, so repeated context refreshes in a test
     *     JVM are idempotent.
     */
    @PostConstruct
    void registerCorrelationIdContextAccessor() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new CorrelationIdThreadLocalAccessor());
    }
}
