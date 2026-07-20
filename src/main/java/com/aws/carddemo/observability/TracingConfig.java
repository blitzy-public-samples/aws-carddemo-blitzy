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
package com.aws.carddemo.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

/**
 * Tracing configuration that attaches the request correlation ID to HTTP server spans for
 * log/trace correlation.
 *
 * <p>The COBOL/CICS/VSAM CardDemo application shipped with no observability stack, so the Java
 * migration introduces framework-native observability. This class is the deliberately narrow
 * "tracing wiring" corner of that stack: its sole responsibility is to enrich every HTTP server
 * <em>span</em> (trace) with the request's {@code correlationId}, so that an operator viewing a
 * trace in Tempo/Grafana can cross-reference the structured logs (which already carry the same
 * identifier via {@code logback-spring.xml}'s {@code %X{correlationId:-}} pattern) by that single
 * shared key.</p>
 *
 * <h2>Ownership boundaries (what this class does NOT do)</h2>
 * <p>To keep each deliverable independently reviewable and to avoid double-registration or
 * conflicts with Spring Boot auto-configuration, this class restates none of the transport,
 * sampler, endpoint, or registry configuration:</p>
 * <ul>
 *   <li><strong>OTLP transport, sampling, and tracer/exporter beans</strong> are owned by
 *       {@code application.yml} (for example {@code management.otlp.tracing.endpoint} and
 *       {@code management.tracing.sampling.probability}) and instantiated by Spring Boot
 *       auto-configuration from the {@code micrometer-tracing-bridge-otel} and
 *       {@code opentelemetry-exporter-otlp} dependencies. None are redefined here.</li>
 *   <li><strong>Actuator endpoints, health/readiness probes, and the Prometheus registry</strong>
 *       are owned by {@code application.yml} ({@code management.endpoints.web.exposure.include}).
 *       This class provides the tracing wiring, not the Actuator endpoints themselves.</li>
 *   <li><strong>The observation predicate</strong> that excludes infrastructure endpoints, plus any
 *       application-wide observation policy, is owned by
 *       {@code com.aws.carddemo.config.ObservabilityConfig}.</li>
 *   <li><strong>The MDC correlation ID and the {@code X-Correlation-Id} header</strong> are owned by
 *       the sibling {@link CorrelationIdFilter}, a self-registering {@code @Component}; it is not
 *       re-registered here.</li>
 * </ul>
 *
 * <h2>Mechanism</h2>
 * <p>Spring Boot instruments HTTP server requests with the {@code http.server.requests} observation
 * via {@code ServerHttpObservationFilter} and auto-configures a
 * {@link DefaultServerRequestObservationConvention} <em>only if no user-provided
 * {@link ServerRequestObservationConvention} bean exists</em>. This class contributes exactly one
 * such bean, {@link CorrelationIdObservationConvention}, which extends the default convention
 * (preserving every standard tag: {@code method}, {@code uri}, {@code status}, {@code outcome},
 * {@code exception}) and appends the {@code correlationId} as a <strong>high-cardinality</strong>
 * key value.</p>
 *
 * <p>The high-cardinality classification is essential: high-cardinality key values are attached to
 * the <em>span (trace)</em> but are deliberately <em>not</em> added to the
 * {@code http.server.requests} <em>metric</em>. Because each correlation ID is unique per request,
 * adding it to metrics would cause an unbounded time-series (cardinality) explosion in Prometheus;
 * keeping it high-cardinality ensures it enriches traces only.</p>
 *
 * <h2>Why reading the MDC here is reliable</h2>
 * <p>{@link CorrelationIdFilter} is registered at
 * {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE HIGHEST_PRECEDENCE}, so it runs
 * <em>outside</em> {@code ServerHttpObservationFilter}. The correlation ID is therefore placed in
 * the {@link MDC} before the server observation starts and is still present when the observation
 * stops and computes its key values (both happen inside the correlation filter's try-block, before
 * its {@code finally} clears the MDC). For synchronous servlet requests, reading the MDC in this
 * convention is thus dependable, and the key is single-sourced from
 * {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY} so the web filter, the logback pattern, the
 * batch listener, and this span tag all use the identical {@code correlationId} key.</p>
 *
 * <h2>Security</h2>
 * <p>Only the opaque {@code correlationId} string is ever added to a span. Card verification values,
 * passwords, and any other sensitive data are never placed into tags, spans, or logs.</p>
 *
 * @see CorrelationIdFilter
 * @see DefaultServerRequestObservationConvention
 * @see ServerRequestObservationConvention
 */
@Configuration
public class TracingConfig {

    /**
     * Contributes the CardDemo {@link ServerRequestObservationConvention} that enriches HTTP server
     * spans with the request correlation ID.
     *
     * <p>Providing this bean replaces Spring Boot's auto-configured default convention (registered
     * only via {@code @ConditionalOnMissingBean}). The returned {@link CorrelationIdObservationConvention}
     * extends {@link DefaultServerRequestObservationConvention}, so all standard request tags are
     * preserved and only the {@code correlationId} high-cardinality value is added on top.</p>
     *
     * <p>The bean is declared with the {@link ServerRequestObservationConvention} interface return
     * type so that it satisfies the exact type Spring Boot's observation auto-configuration looks
     * for when deciding whether to back off from its own default.</p>
     *
     * @return the correlation-ID-aware server request observation convention; never {@code null}
     */
    @Bean
    ServerRequestObservationConvention correlationIdServerRequestObservationConvention() {
        return new CorrelationIdObservationConvention();
    }

    /**
     * A {@link DefaultServerRequestObservationConvention} that additionally attaches the request
     * {@code correlationId} (read from the SLF4J {@link MDC}) to the HTTP server span as a
     * high-cardinality key value.
     *
     * <p>The class is {@code final} because it is not designed for further extension, and its single
     * override calls {@code super} first so that every default tag continues to be produced. Only
     * {@link #getHighCardinalityKeyValues(ServerRequestObservationContext)} is overridden;
     * {@code getLowCardinalityKeyValues} is intentionally left untouched so the unique correlation ID
     * is never promoted onto the {@code http.server.requests} metric.</p>
     */
    private static final class CorrelationIdObservationConvention
            extends DefaultServerRequestObservationConvention {

        /**
         * Returns the default high-cardinality key values for the request, plus the request
         * {@code correlationId} when one is present in the {@link MDC}.
         *
         * <p>The correlation-ID key is single-sourced from
         * {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY} to guarantee it matches the key used by
         * the web filter, the logback pattern, and the batch listener. When the MDC value is absent
         * or blank (for example, on a request path that bypasses {@link CorrelationIdFilter}), the
         * unmodified default key values are returned so behavior degrades cleanly.</p>
         *
         * @param context the server request observation context supplied by Spring; must not be
         *                {@code null}
         * @return the default key values, with the {@code correlationId} appended when available
         */
        @Override
        public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
            KeyValues values = super.getHighCardinalityKeyValues(context);
            String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                return values.and(KeyValue.of(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationId));
            }
            return values;
        }
    }
}
