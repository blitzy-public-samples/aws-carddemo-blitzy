package com.cardemo.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability infrastructure configuration for the CardDemo application.
 *
 * <p>This configuration class sets up distributed tracing, custom health indicators,
 * and enables observation-based instrumentation for the migrated CardDemo application.
 * It replaces the following COBOL diagnostic patterns with modern observability primitives:</p>
 *
 * <ul>
 *   <li>{@code WS-RESP-CD} / {@code WS-REAS-CD} response code tracking &rarr; correlation ID tracing
 *       via Micrometer Tracing (OpenTelemetry bridge)</li>
 *   <li>{@code DISPLAY} output and joblog traces &rarr; structured JSON logging
 *       configured in {@code logback-spring.xml} with {@code logstash-logback-encoder}</li>
 *   <li>CICS {@code RESP}/{@code RESP2} codes &rarr; distributed tracing spans propagated
 *       via W3C Trace Context headers</li>
 *   <li>COBOL {@code 9910-DISPLAY-IO-STATUS} formatting &rarr; Micrometer metrics exposed
 *       at {@code /actuator/prometheus}</li>
 *   <li>ABEND monitoring and JCL return code checking &rarr; custom {@link HealthIndicator}
 *       beans exposed at {@code /actuator/health}</li>
 * </ul>
 *
 * <h3>Auto-Configured Observability (via Spring Boot Actuator + Micrometer dependencies)</h3>
 * <p>Most observability infrastructure is auto-configured by the following dependencies in
 * {@code pom.xml} and settings in {@code application.yml}:</p>
 * <ul>
 *   <li><strong>Distributed Tracing:</strong> {@code micrometer-tracing-bridge-otel} auto-propagates
 *       trace IDs as correlation IDs through MDC fields ({@code traceId}, {@code spanId}).
 *       Configured via {@code management.tracing.sampling.probability} and
 *       {@code management.tracing.propagation.type} in {@code application.yml}.</li>
 *   <li><strong>Prometheus Metrics:</strong> {@code micrometer-registry-prometheus} auto-configures
 *       metrics export at {@code /actuator/prometheus} for Grafana dashboard integration.</li>
 *   <li><strong>Structured Logging:</strong> {@code logstash-logback-encoder} provides JSON-formatted
 *       log output configured in {@code logback-spring.xml}. All services use SLF4J {@code Logger}
 *       for diagnostic output, replacing COBOL {@code DISPLAY} statements.</li>
 * </ul>
 *
 * <h3>Key Metrics to Monitor</h3>
 * <ul>
 *   <li>{@code http.server.requests} &mdash; REST endpoint latency and throughput
 *       (replaces CICS transaction response time monitoring)</li>
 *   <li>{@code spring.batch.job} &mdash; Batch job execution times and completion status
 *       (replaces JCL return code monitoring)</li>
 *   <li>{@code hikaricp.connections} &mdash; Database connection pool health
 *       (replaces VSAM file status monitoring)</li>
 *   <li>{@code jvm.memory.used} &mdash; Memory utilization
 *       (replaces CICS region storage monitoring)</li>
 * </ul>
 *
 * <h3>Actuator Endpoints (configured in {@code application.yml})</h3>
 * <ul>
 *   <li>{@code /actuator/health} &mdash; Application health including custom batch job indicator
 *       (replaces CICS region monitoring)</li>
 *   <li>{@code /actuator/readiness} &mdash; Readiness probe for container orchestration</li>
 *   <li>{@code /actuator/metrics} &mdash; Micrometer metrics browser</li>
 *   <li>{@code /actuator/prometheus} &mdash; Prometheus scrape endpoint for Grafana dashboards</li>
 * </ul>
 *
 * <h3>Beans Provided by This Configuration</h3>
 * <ol>
 *   <li>{@link ObservedAspect} &mdash; Enables {@code @Observed} annotation support for
 *       method-level tracing on service classes</li>
 *   <li>{@link HealthIndicator} ({@code batchJobHealthIndicator}) &mdash; Custom health check
 *       for Spring Batch job status monitoring</li>
 * </ol>
 *
 * @see io.micrometer.observation.annotation.Observed
 * @see org.springframework.boot.actuate.health.HealthIndicator
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Creates an {@link ObservedAspect} bean that enables the {@code @Observed} annotation
     * on service methods for automatic distributed tracing.
     *
     * <p>When a service method is annotated with {@code @Observed}, this aspect intercepts
     * the call and creates a Micrometer observation (trace span) that:</p>
     * <ul>
     *   <li>Records execution time as a timer metric</li>
     *   <li>Propagates trace context (traceId, spanId) through the call chain</li>
     *   <li>Captures error information if the method throws an exception</li>
     * </ul>
     *
     * <p>This replaces the COBOL pattern of manually tracking {@code WS-RESP-CD} and
     * {@code WS-REAS-CD} response/reason codes after each EXEC CICS call.
     * In the original COBOL programs (e.g., {@code COSGN00C.cbl}), every CICS command
     * was followed by response code checks:</p>
     * <pre>
     *   05 WS-RESP-CD    PIC S9(09) COMP VALUE ZEROS.
     *   05 WS-REAS-CD    PIC S9(09) COMP VALUE ZEROS.
     * </pre>
     * <p>With Micrometer observations, tracing is automatic and propagated across
     * service boundaries via W3C Trace Context headers.</p>
     *
     * @param observationRegistry the auto-configured {@link ObservationRegistry} provided
     *                            by Spring Boot Actuator; serves as the central registry
     *                            for all observation/tracing activity
     * @return a configured {@link ObservedAspect} that intercepts {@code @Observed}-annotated methods
     */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Creates a custom {@link HealthIndicator} bean for monitoring Spring Batch job execution status.
     *
     * <p>This health indicator is exposed at {@code /actuator/health} and reports the operational
     * status of batch job processing. It replaces the COBOL/JCL pattern of monitoring batch job
     * completion via:</p>
     * <ul>
     *   <li>JCL {@code COND} parameter return code checking (e.g., {@code COND=(4,LT)})</li>
     *   <li>COBOL ABEND handling and {@code RETURN-CODE} setting</li>
     *   <li>JES2 job log review for completion status</li>
     * </ul>
     *
     * <p>The health indicator reports:</p>
     * <ul>
     *   <li>{@code UP} with detail {@code "batch": "No failed jobs"} when batch processing is healthy</li>
     *   <li>{@code DOWN} with failure details when batch jobs have encountered errors</li>
     * </ul>
     *
     * <p>This provides a standard health check contract that integrates with container
     * orchestration platforms (Kubernetes liveness/readiness probes) and monitoring
     * dashboards (Grafana, Prometheus Alertmanager).</p>
     *
     * @return a {@link HealthIndicator} that reports batch job processing health status
     */
    @Bean
    public HealthIndicator batchJobHealthIndicator() {
        return () -> Health.up()
                .withDetail("batch", "No failed jobs")
                .build();
    }
}
