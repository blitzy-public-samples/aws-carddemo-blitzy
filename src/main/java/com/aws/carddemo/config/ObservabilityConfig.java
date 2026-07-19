package com.aws.carddemo.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability wiring for the CardDemo migration (Actuator health/readiness + Micrometer
 * Tracing/Brave correlation IDs + Prometheus metrics).
 *
 * <p>AWS CardDemo was originally an IBM z/OS COBOL/CICS/VSAM/JCL/BMS system. This class is
 * <em>net-new infrastructure</em> with no legacy COBOL counterpart; it implements the Observability
 * rule so that the migrated Java 25 + Spring Boot replacement ships production observability from
 * the first commit rather than bolting it on later. Migration rationale is recorded in
 * {@code docs/decision-log.md}, not in code comments.</p>
 *
 * <p>Division of responsibility &mdash; almost the entire observability surface is contributed by
 * Spring Boot Actuator and Micrometer auto-configuration and therefore needs <strong>no</strong>
 * beans here:</p>
 * <ul>
 *   <li><strong>Health / readiness:</strong> the {@code /actuator/health} endpoint together with its
 *       liveness and readiness probe groups is auto-configured; the database, disk-space and ping
 *       health indicators are contributed automatically, so no {@code HealthIndicator} bean is
 *       declared.</li>
 *   <li><strong>Distributed tracing and correlation IDs:</strong> the
 *       {@code micrometer-tracing-bridge-brave} dependency auto-configures a tracer and injects
 *       {@code traceId} and {@code spanId} into the SLF4J MDC. No bean is required; the companion
 *       {@code src/main/resources/logback-spring.xml} (owned by the resources agent) renders those
 *       MDC values as structured-log correlation IDs across service boundaries.</li>
 *   <li><strong>Metrics scrape surface:</strong> {@code micrometer-registry-prometheus}
 *       auto-configures the {@code /actuator/prometheus} endpoint that Prometheus scrapes and that
 *       {@code observability/grafana-dashboard.json} visualizes; the {@link MeterRegistry},
 *       {@code PrometheusMeterRegistry} and {@code Clock} are all auto-configured and are
 *       intentionally not re-declared here.</li>
 * </ul>
 *
 * <p>The single programmatic customization this class adds is a common {@code "application"} meter
 * tag (see {@link #commonTagsCustomizer()}) that stamps every emitted metric with the application
 * identity, enabling the bundled Grafana dashboard to filter and group panels by application.</p>
 *
 * <p><strong>No custom AspectJ aspect beans are declared here (review finding F15).</strong> An
 * earlier revision claimed AspectJ was absent from the build; that rationale was incorrect.
 * {@code org.aspectj:aspectjweaver} <em>is</em> on the classpath &mdash; it is pulled in transitively
 * through {@code org.springframework:spring-aspects} (a dependency of Spring Data JPA) &mdash; so
 * Boot's AOP classpath condition is satisfied. This class nevertheless does not register Micrometer's
 * {@code TimedAspect} (backing {@code @Timed}) or {@code ObservedAspect} (backing {@code @Observed})
 * beans, for a deliberate <em>design</em> reason rather than a classpath one: this migration does not
 * annotate any business method with {@code @Timed} or {@code @Observed}, so those aspects would have
 * nothing to advise. Observability parity is achieved entirely through the auto-configured HTTP
 * ({@code http.server.requests}), JVM, HikariCP and Spring Batch meters (plus the common
 * {@code application} tag added below), which keeps observability a purely non-functional concern
 * with no feature expansion. Because the weaver is present, Boot's own auto-configuration may itself
 * contribute these aspect beans; this class simply neither requires nor re-declares them, avoiding a
 * duplicate-bean conflict. This decision and its rationale are recorded in
 * {@code docs/decision-log.md}.</p>
 *
 * <p><strong>Required declarative configuration</strong> (owned by the resources agent in
 * {@code src/main/resources/application.yml}; deliberately not created by this class):</p>
 * <pre>
 * spring:
 *   application:
 *     name: carddemo
 * management:
 *   endpoints:
 *     web:
 *       exposure:
 *         include: health,info,prometheus
 *   endpoint:
 *     health:
 *       probes:
 *         enabled: true
 *       show-details: when_authorized
 *   tracing:
 *     sampling:
 *       probability: 1.0
 * </pre>
 * <p>Exposing only {@code health,info,prometheus} avoids publishing management internals;
 * {@code show-details: when_authorized} (or {@code never}) prevents leaking health details to
 * anonymous callers; full trace sampling ({@code probability: 1.0}) suits local validation and
 * should be lowered in production. The common {@code application} meter tag is supplied by exactly
 * one authority &mdash; the programmatic {@link #commonTagsCustomizer()} bean below. The equivalent
 * declarative property {@code management.metrics.tags.application} is intentionally NOT also set in
 * {@code application.yml} (review finding F28), so there are never two mechanisms that can drift.</p>
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Logical application identity used as the value of the common {@code "application"} meter tag.
     *
     * <p>A non-secret application identity; no credentials, tokens or URLs are ever held here,
     * honoring the no-hardcoded-credentials rule. Declared {@code final} and populated through
     * constructor injection.</p>
     */
    private final String applicationName;

    /**
     * Creates the observability configuration with the injected application identity.
     *
     * <p>Uses <strong>constructor injection</strong> rather than field {@code @Value} injection, per
     * the project's constructor-based dependency-injection standard (review finding F21): the
     * {@code final} field makes the collaborator explicit, the configuration immutable, and the bean
     * trivially unit-testable without a Spring context. The value is bound from the non-secret
     * {@code spring.application.name} property with a safe default of {@code carddemo}, so the common
     * tag is always present even before {@code application.yml} sets the name.</p>
     *
     * @param applicationName the logical application identity used as the common {@code application}
     *                        meter-tag value; defaults to {@code carddemo} when the property is unset
     */
    public ObservabilityConfig(
            @Value("${spring.application.name:carddemo}") String applicationName) {
        this.applicationName = applicationName;
    }

    /**
     * Registers a common {@code "application"} tag on every meter of the auto-configured
     * {@link MeterRegistry} (including the Prometheus registry).
     *
     * <p>Micrometer applies a registry's common tags to all metrics it emits, so this customizer
     * causes each sample exported at {@code /actuator/prometheus} to carry the label
     * {@code application} set to the configured {@link #applicationName}. The bundled dashboard
     * {@code observability/grafana-dashboard.json} declares an {@code application} template variable
     * (Prometheus {@code label_values(application)}) and filters its panels on it, so this tag key
     * must remain {@code "application"} to keep the dashboard aligned.</p>
     *
     * <p>A common tag is preferred over per-meter tagging because it is applied uniformly without
     * touching business code, keeping observability a purely non-functional concern with no feature
     * expansion. Additional static tags (for example {@code "tier"} / {@code "backend"}) could be
     * appended here should the dashboard require them; only {@code "application"} is added because
     * that is the sole label the dashboard currently consumes.</p>
     *
     * @return a {@link MeterRegistryCustomizer} that adds the common {@code application} tag to the
     *         auto-configured meter registry
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer() {
        return registry -> registry.config().commonTags("application", applicationName);
    }
}
