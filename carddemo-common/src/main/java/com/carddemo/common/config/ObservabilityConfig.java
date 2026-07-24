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

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.observation.ObservationFilter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
