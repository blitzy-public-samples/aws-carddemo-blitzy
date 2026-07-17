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

import java.util.Set;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Application-level <em>observation policy</em> home for the CardDemo migration.
 *
 * <p>The COBOL/CICS/VSAM CardDemo application shipped with no observability stack, so the
 * migration introduces framework-native observability (structured logging with correlation IDs,
 * distributed tracing, a metrics endpoint, and health/readiness) mandated by the project's
 * Observability rule. Responsibility for that stack is split across three deliberately narrow,
 * non-overlapping units; this class is the "policy" corner of that split and is intentionally
 * minimal.</p>
 *
 * <h2>Ownership boundaries (what this class does NOT do)</h2>
 * <p>To keep every deliverable independently reviewable and to avoid double-registration or
 * conflicts with Spring Boot auto-configuration, this class restates none of the transport,
 * sampler, or registry configuration:</p>
 * <ul>
 *   <li><strong>Transport / sampler / registries / service name</strong> are owned by
 *       {@code application.yml} plus Boot auto-configuration: the OTLP tracing endpoint
 *       ({@code management.otlp.tracing.endpoint}), full sampling
 *       ({@code management.tracing.sampling.probability=1.0}), the exposed Actuator endpoints
 *       ({@code management.endpoints.web.exposure.include=health,info,metrics,prometheus}), the
 *       liveness/readiness probes ({@code management.endpoint.health.probes.enabled=true}), the
 *       common metrics tag ({@code management.metrics.tags.application}), and the OpenTelemetry
 *       {@code service.name} (derived from {@code spring.application.name=carddemo}). This class
 *       redefines none of them.</li>
 *   <li><strong>Span-level correlation-ID tagging</strong> is owned by the sibling
 *       {@code com.aws.carddemo.observability.TracingConfig}, which contributes a
 *       {@code ServerRequestObservationConvention} that appends the {@code correlationId} (from the
 *       SLF4J MDC) to server spans.</li>
 *   <li><strong>The MDC correlation ID and the {@code X-Correlation-Id} header</strong> are owned
 *       by {@code com.aws.carddemo.observability.CorrelationIdFilter}, a self-registering
 *       {@code @Component}; it is not re-registered here.</li>
 * </ul>
 * <p>The high-level observability beans that {@code TracingConfig} defers here (an
 * {@code ObservedAspect}, an OpenTelemetry {@code Resource}/service-name bean, and a
 * {@code MeterRegistryCustomizer} for common tags) are intentionally <strong>not</strong> defined:
 * the {@code ObservedAspect} would require an AspectJ/AOP dependency that is not on the classpath,
 * while the service-name {@code Resource} and the common metrics tag are already provided by
 * auto-configuration and {@code application.yml} respectively, so adding them would risk a
 * conflicting or duplicated registration.</p>
 *
 * <h2>The single responsibility of this class</h2>
 * <p>It registers exactly one {@link ObservationRegistryCustomizer} that installs an observation
 * predicate excluding infrastructure endpoints from observations. With full sampling enabled
 * (probability {@code 1.0}) every request is traced; without this filter the Prometheus scrape of
 * {@code /actuator/prometheus} (every few seconds) and Swagger UI polling would dominate the trace
 * stream and inflate metric cardinality. Filtering these prefixes keeps traces and metrics focused
 * on the business REST controllers and batch jobs.</p>
 *
 * @see ObservationRegistryCustomizer
 * @see ServerRequestObservationContext
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Request-URI prefixes for infrastructure endpoints that must be excluded from observations.
     *
     * <p>These mirror the paths configured in {@code application.yml}: the Actuator base path
     * ({@code /actuator}, covering {@code /actuator/health}, {@code /actuator/prometheus},
     * {@code /actuator/metrics}, and the liveness/readiness probes) and the springdoc paths
     * ({@code /v3/api-docs} from {@code springdoc.api-docs.path} and {@code /swagger-ui} covering
     * both {@code /swagger-ui.html} and the {@code /swagger-ui/**} static resources). The set is
     * immutable ({@link Set#of(Object[])}).</p>
     */
    static final Set<String> NON_OBSERVED_PREFIXES = Set.of("/actuator", "/swagger-ui", "/v3/api-docs");

    /**
     * Registers the CardDemo observation predicate on the application's {@link ObservationRegistry}.
     *
     * <p>Spring Boot applies every {@link ObservationRegistryCustomizer} bean to the
     * auto-configured {@code ObservationRegistry} during context startup. This customizer installs
     * {@link #isObservable(String, Observation.Context)} as the registry's observation predicate so
     * that observations for infrastructure endpoints are never started, keeping traces (Tempo) and
     * metrics (Prometheus) focused on business traffic. It deliberately customizes only the
     * predicate and does not create or replace the registry itself.</p>
     *
     * @return a customizer that installs the infrastructure-endpoint observation predicate; never
     *         {@code null}
     */
    @Bean
    ObservationRegistryCustomizer<ObservationRegistry> cardDemoObservationFilter() {
        return registry -> registry.observationConfig().observationPredicate(this::isObservable);
    }

    /**
     * Decides whether an observation should be recorded, excluding infrastructure endpoints.
     *
     * <p>This method has the exact shape of Micrometer's
     * {@code io.micrometer.observation.ObservationPredicate} functional interface
     * ({@code boolean test(String, Observation.Context)}), so it can be supplied as the registry
     * predicate via a method reference. It is package-private to remain directly unit-testable
     * without starting a Spring context.</p>
     *
     * <p>For HTTP server observations the carrier request URI is inspected: the observation is
     * suppressed when the URI starts with any {@link #NON_OBSERVED_PREFIXES infrastructure prefix}.
     * A {@code null} URI (defensive; not expected for a real request) is treated as observable. All
     * non-HTTP contexts &mdash; for example custom or batch observations &mdash; are always
     * observed, because this filter is scoped strictly to infrastructure HTTP endpoints.</p>
     *
     * @param name    the observation name supplied by Micrometer; not used by this predicate but
     *                required by the {@code ObservationPredicate} contract
     * @param context the observation context; inspected only when it is a
     *                {@link ServerRequestObservationContext}
     * @return {@code false} to suppress observations for infrastructure HTTP endpoints;
     *         {@code true} otherwise
     */
    boolean isObservable(String name, Observation.Context context) {
        if (context instanceof ServerRequestObservationContext ctx) {
            String uri = ctx.getCarrier().getRequestURI();
            return uri == null || NON_OBSERVED_PREFIXES.stream().noneMatch(uri::startsWith);
        }
        return true;
    }
}
