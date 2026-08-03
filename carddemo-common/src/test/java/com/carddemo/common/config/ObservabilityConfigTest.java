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
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Verify the cross-cutting instrumentation contract of
 *   {@link ObservabilityConfig}, which realizes the Observability rule (AAP 0.7.5)
 *   and the p95-under-200 ms latency objective (AAP 0.7.1) for every CardDemo
 *   service and the API gateway. The class had no test references at all, so a
 *   regression that dropped the per-service ``application`` tag or the explicit
 *   200 ms service-level-objective bucket would have silently broken every
 *   Prometheus/Grafana query and trace filter without failing the build.
 * :output: Unit assertions over real Micrometer registries: the meter common tag,
 *   the observation low-cardinality key-value, and the ``http.server.requests``
 *   distribution configuration (including the pass-through behaviour for every
 *   other meter).
 */
class ObservabilityConfigTest {

    /** :purpose: The meter whose distribution carries the latency objective. */
    private static final String HTTP_SERVER_REQUESTS = "http.server.requests";

    /** :purpose: 200 ms expressed in nanoseconds — the AAP 0.7.1 online-latency SLO. */
    private static final double SLO_NANOS = 200_000_000.0d;

    private final ObservabilityConfig config = new ObservabilityConfig();

    /**
     * :purpose: Scenarios for the meter-registry common tag that makes every metric
     *   attributable to the emitting service.
     */
    @Nested
    @DisplayName("commonMetricsTags")
    class CommonMetricsTags {

        /**
         * :purpose: Every meter registered after the customizer runs carries the
         *   ``application`` tag set to ``spring.application.name``, which is what makes
         *   ``sum by (application)`` queries possible across the nine services.
         */
        @Test
        @DisplayName("adds the application common tag to every meter in the registry")
        void addsApplicationCommonTagToEveryMeter() {
            MeterRegistryCustomizer<MeterRegistry> customizer =
                    config.commonMetricsTags("account-service");
            MeterRegistry registry = new SimpleMeterRegistry();

            customizer.customize(registry);
            registry.counter("carddemo.accounts.viewed").increment();
            Timer.builder(HTTP_SERVER_REQUESTS).register(registry);

            for (Meter meter : registry.getMeters()) {
                List<Tag> tags = meter.getId().getTags();
                assertThat(tags)
                        .as("common tags on %s", meter.getId().getName())
                        .contains(Tag.of("application", "account-service"));
            }
        }

        /**
         * :purpose: The tag value is whatever the caller supplies, so each service
         *   publishes its own name rather than a shared literal.
         */
        @Test
        @DisplayName("uses the supplied application name verbatim")
        void usesSuppliedApplicationNameVerbatim() {
            MeterRegistry registry = new SimpleMeterRegistry();
            config.commonMetricsTags("api-gateway").customize(registry);

            registry.counter("carddemo.menu.selected").increment();

            assertThat(registry.getMeters().getFirst().getId().getTag("application"))
                    .isEqualTo("api-gateway");
        }
    }

    /**
     * :purpose: Scenarios for the observation filter that stamps the service name onto
     *   every observation and therefore onto every emitted span.
     */
    @Nested
    @DisplayName("commonObservationTags")
    class CommonObservationTags {

        /**
         * :purpose: The filter contributes ``application`` as a LOW-cardinality
         *   key-value, which is the only cardinality class safe to attach to every
         *   span and metric name.
         */
        @Test
        @DisplayName("adds application as a low-cardinality key-value to every observation")
        void addsApplicationLowCardinalityKeyValue() {
            ObservationFilter filter = config.commonObservationTags("card-service");
            ObservationRegistry registry = ObservationRegistry.create();
            // A registry with no handler is a no-op registry: createNotStarted would
            // return Observation.NOOP and no filter would ever run. Register a handler
            // that accepts every context so the real filter chain is exercised.
            registry.observationConfig()
                    .observationHandler(observationContext -> true)
                    .observationFilter(filter);

            Observation.Context context = new Observation.Context();
            Observation.createNotStarted("card.lookup", () -> context, registry)
                    .observe(() -> { });

            KeyValues lowCardinality = context.getLowCardinalityKeyValues();
            assertThat(lowCardinality).contains(KeyValue.of("application", "card-service"));
        }

        /**
         * :purpose: The filter never discards the key-values an instrumentation library
         *   already contributed; it only adds to them.
         */
        @Test
        @DisplayName("preserves key-values contributed by other instrumentation")
        void preservesExistingKeyValues() {
            ObservationFilter filter = config.commonObservationTags("transaction-service");
            Observation.Context context = new Observation.Context();
            context.addLowCardinalityKeyValue(KeyValue.of("outcome", "SUCCESS"));

            Observation.Context filtered = filter.map(context);

            assertThat(filtered.getLowCardinalityKeyValues()).contains(
                    KeyValue.of("outcome", "SUCCESS"),
                    KeyValue.of("application", "transaction-service"));
        }
    }

    /**
     * :purpose: Scenarios for the latency-objective histogram on the server request
     *   timer, which is what makes the AAP 0.7.1 p95 objective computable from a
     *   Prometheus scrape.
     */
    @Nested
    @DisplayName("httpServerRequestsHistogram")
    class HttpServerRequestsHistogram {

        /**
         * :purpose: The ``http.server.requests`` timer publishes a percentile histogram
         *   and an explicit 200 ms objective boundary, so Prometheus emits the
         *   ``http_server_requests_seconds_bucket`` series including ``le="0.2"``.
         */
        @Test
        @DisplayName("publishes a percentile histogram and the 200ms SLO for http.server.requests")
        void configuresHistogramAndSloForServerRequests() {
            MeterFilter filter = config.httpServerRequestsHistogram();
            MeterRegistry registry = new SimpleMeterRegistry();
            Meter.Id id = Timer.builder(HTTP_SERVER_REQUESTS).register(registry).getId();

            DistributionStatisticConfig configured =
                    filter.configure(id, DistributionStatisticConfig.DEFAULT);

            assertThat(configured.isPercentileHistogram()).isTrue();
            assertThat(configured.getServiceLevelObjectiveBoundaries()).containsExactly(SLO_NANOS);
        }

        /**
         * :purpose: The filter is scoped strictly to the request timer. Any other meter
         *   — including a timer with a different name and a non-timer meter that happens
         *   to share the name — is returned untouched, so no unrelated metric inherits an
         *   expensive histogram.
         */
        @Test
        @DisplayName("leaves every other meter's distribution config untouched")
        void leavesOtherMetersUntouched() {
            MeterFilter filter = config.httpServerRequestsHistogram();
            MeterRegistry registry = new SimpleMeterRegistry();
            Meter.Id otherTimer = Timer.builder("carddemo.batch.step").register(registry).getId();
            Meter.Id counterWithSameName =
                    registry.counter(HTTP_SERVER_REQUESTS, "kind", "counter").getId();

            assertThat(filter.configure(otherTimer, DistributionStatisticConfig.DEFAULT))
                    .isSameAs(DistributionStatisticConfig.DEFAULT);
            assertThat(filter.configure(counterWithSameName, DistributionStatisticConfig.DEFAULT))
                    .isSameAs(DistributionStatisticConfig.DEFAULT);
        }

        /**
         * :purpose: The filter MERGES onto the incoming configuration rather than
         *   replacing it, so registry- or property-supplied settings such as
         *   ``expiry`` survive alongside the SLO boundary.
         */
        @Test
        @DisplayName("merges onto the incoming configuration instead of replacing it")
        void mergesOntoIncomingConfiguration() {
            MeterFilter filter = config.httpServerRequestsHistogram();
            MeterRegistry registry = new SimpleMeterRegistry();
            Meter.Id id = Timer.builder(HTTP_SERVER_REQUESTS).register(registry).getId();
            DistributionStatisticConfig incoming = DistributionStatisticConfig.builder()
                    .percentiles(0.95d)
                    .build()
                    .merge(DistributionStatisticConfig.DEFAULT);

            DistributionStatisticConfig configured = filter.configure(id, incoming);

            assertThat(configured.getPercentiles()).containsExactly(0.95d);
            assertThat(configured.isPercentileHistogram()).isTrue();
            assertThat(configured.getServiceLevelObjectiveBoundaries()).containsExactly(SLO_NANOS);
        }
    }
}
