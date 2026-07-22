package com.aws.carddemo.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;

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
 * <p><strong>Cross-layer observations (review finding #51, superseding the earlier F15
 * decision).</strong> {@code org.aspectj:aspectjweaver} is on the classpath &mdash; pulled in
 * transitively through {@code org.springframework:spring-aspects} (a dependency of Spring Data JPA)
 * &mdash; so Spring AOP can advise beans annotated with Micrometer's
 * {@link io.micrometer.observation.annotation.Observed @Observed}. An earlier revision (F15)
 * deliberately declared no aspect and annotated no business method; that left the trace tree with
 * only the auto-configured HTTP ({@code http.server.requests}) and Spring Batch spans, so a
 * representative trace never evidenced the web&rarr;service and service&rarr;repository boundaries
 * the Observability rule requires. That gap is now closed: this class registers a single
 * {@link ObservedAspect} (see {@link #observedAspect(ObservationRegistry)}) and a <em>bounded,
 * low-cardinality</em> set of {@code @Observed} entry points is annotated on representative service
 * methods (sign-on {@code CC00}, account view {@code CAVW}, account update {@code CAUP}) and one
 * representative repository finder ({@code CardXrefRepository#findByXrefAcctId}, the {@code CXACAIX}
 * alternate-index read on the account-view path). The default {@code ObservedAspect} convention
 * contributes only the low-cardinality {@code class} and {@code method} key values &mdash; no account
 * id, card number, customer id, user id or transaction id is ever attached as a tag or span attribute
 * &mdash; so the instrumentation stays low-cardinality and free of sensitive data, adding no business
 * behavior (observability remains a purely non-functional concern). This decision, and the
 * supersession of F15, are recorded in {@code docs/decision-log.md}.</p>
 *
 * <p><strong>Signon counter (review finding P7-OBS-01).</strong> Because signon is
 * controller-managed &mdash; {@code SignonController} calls the {@code AuthenticationManager} directly
 * rather than through Spring Security's form-login filter &mdash; the {@code @Observed} seam on
 * {@code SignonService.mainEntry} is never reached by the web path, so {@code carddemo.signon} never
 * emitted even though the account/card observations did. That real authentication boundary is the
 * {@code ProviderManager} exposed by {@code SecurityConfig}, which is now wired with a
 * {@link org.springframework.security.authentication.DefaultAuthenticationEventPublisher}; the nested
 * {@link SignonMetrics} listener (registered by {@link #signonMetrics(MeterRegistry)}) translates each
 * published success/failure authentication event into the {@code carddemo.signon} counter tagged only
 * {@code outcome=success|failure}. No user identifier is ever attached, so the counter stays
 * low-cardinality and free of sensitive data.</p>
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
     * Registers the Micrometer {@link ObservedAspect} that turns
     * {@link io.micrometer.observation.annotation.Observed @Observed} annotations on Spring-managed
     * service and repository beans into {@link io.micrometer.observation.Observation Observations}
     * (spans plus timer metrics), closing the cross-layer tracing gap in review finding #51.
     *
     * <p>Without this aspect the {@code @Observed} annotations added to the representative sign-on,
     * account-view, account-update and {@code CardXrefRepository#findByXrefAcctId} entry points would
     * advise nothing, and a representative trace would show only the auto-configured
     * {@code http.server.requests} (web) and Spring Batch spans &mdash; never the intervening service
     * and repository boundaries. With the aspect present, each {@code @Observed} method executes
     * inside a child {@link io.micrometer.observation.Observation} of the currently open observation,
     * so one request yields the full {@code http.server.requests → service → repository} span tree
     * the Observability rule requires.</p>
     *
     * <p>The bean is {@link ConditionalOnMissingBean @ConditionalOnMissingBean} so it defers to any
     * {@link ObservedAspect} that Spring Boot's {@code ObservationAutoConfiguration} may already
     * contribute (that auto-configuration is itself {@code @ConditionalOnMissingBean}), guaranteeing
     * exactly one aspect and no duplicate-bean conflict. Declaring it explicitly here keeps the
     * observability wiring visible in one place rather than relying on an implicit auto-configuration.
     * The aspect adds only the default low-cardinality {@code class}/{@code method} key values; no
     * identifier is ever tagged, keeping the observations low-cardinality and free of sensitive
     * attributes.</p>
     *
     * @param observationRegistry the auto-configured registry the aspect starts observations on
     * @return the {@link ObservedAspect} backing {@code @Observed} on service and repository beans
     */
    @Bean
    @ConditionalOnMissingBean(ObservedAspect.class)
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
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

    /**
     * Suppresses the redundant Spring Batch <em>active-job</em> meter registration that otherwise
     * fails on every job launch, eliminating a recurring Prometheus registration WARN (review
     * finding OBS-1).
     *
     * <p><strong>Root cause.</strong> With Boot's batch auto-configuration active (this project
     * deliberately omits {@code @EnableBatchProcessing}; see {@link BatchConfig}), two framework
     * mechanisms independently register an "active job" long-task timer that both mangle to the same
     * Prometheus base name {@code spring_batch_job_active_seconds}:</p>
     * <ul>
     *   <li>the legacy {@code BatchMetrics} long-task timer, whose Prometheus tag keys are
     *       {@code [application, spring_batch_job_active_name]}; and</li>
     *   <li>the newer Micrometer <em>Observation</em> convention meter (Micrometer name
     *       {@code spring.batch.job.active}), whose tag keys are
     *       {@code [application, spring_batch_job_name, spring_batch_job_status]}.</li>
     * </ul>
     * <p>Prometheus requires every meter sharing a name to carry an identical tag-key set, so the
     * second registrant is rejected with
     * {@code "registration has failed: ... already an existing meter named
     * 'spring_batch_job_active_seconds'"} on each run. The failure is cosmetic (jobs still complete
     * and subsequent identical warnings are demoted to debug), but it is genuine recurring log noise.</p>
     *
     * <p><strong>Fix.</strong> This {@link MeterFilter} denies <em>only</em> the redundant
     * Observation-convention active-job meter &mdash; matched precisely by its Micrometer name
     * {@code spring.batch.job.active} <em>and</em> the presence of a {@code spring.batch.job.status}
     * tag that the surviving legacy meter does not carry. It therefore:</p>
     * <ul>
     *   <li>preserves the surviving {@code spring_batch_job_active_seconds_count} series (tag key
     *       {@code spring_batch_job_active_name}) that the bundled dashboard's <em>Active Jobs</em>
     *       panel queries, so no panel is affected; and</li>
     *   <li>leaves the unrelated {@code spring.batch.job} timer untouched &mdash; its
     *       {@code spring_batch_job_seconds_*} series (tag keys {@code spring_batch_job_name} /
     *       {@code spring_batch_job_status}) back the remaining batch panels and share no name with
     *       the denied meter.</li>
     * </ul>
     * <p>The only forfeited signal is the never-consumed per-name/status granularity of the active
     * gauge. Boot binds every {@link MeterFilter} bean to all meter registries before any meter is
     * registered, so this filter takes effect for the batch meters that are created lazily at job
     * launch. Rationale is recorded in {@code docs/decision-log.md}.</p>
     *
     * @return a {@link MeterFilter} that denies the duplicate Spring Batch active-job meter so it
     *         never collides with the surviving {@code spring_batch_job_active_seconds} registration
     */
    @Bean
    public MeterFilter suppressDuplicateBatchActiveJobMeter() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                if ("spring.batch.job.active".equals(id.getName())
                        && id.getTag("spring.batch.job.status") != null) {
                    return MeterFilterReply.DENY;
                }
                return MeterFilterReply.NEUTRAL;
            }
        };
    }

    /**
     * Registers the {@link SignonMetrics} listener that emits the {@code carddemo.signon} counter at the
     * real, controller-reached authentication boundary (review finding P7-OBS-01).
     *
     * <p>Taking {@link MeterRegistry} as a <em>method</em> parameter (rather than a constructor
     * dependency of this {@code @Configuration}) is deliberate: this class also contributes the
     * {@link #commonTagsCustomizer()} and {@link #suppressDuplicateBatchActiveJobMeter()} registry
     * customizers, and the auto-configured {@link MeterRegistry} is only fully post-processed with those
     * customizers <em>after</em> it is built. Because Spring resolves a {@code @Bean} method's parameters
     * lazily at that bean's creation time, {@code signonMetrics} receives the fully-customized registry
     * and no configuration-time dependency cycle is introduced.</p>
     *
     * <p>The returned bean's {@link EventListener}-annotated methods are detected by Spring's event
     * infrastructure exactly as they would be on any component, so no additional registration is
     * required. Rationale is recorded in {@code docs/decision-log.md}.</p>
     *
     * @param meterRegistry the fully-customized auto-configured meter registry the counters register on
     * @return the {@link SignonMetrics} listener bean that increments {@code carddemo.signon}
     */
    @Bean
    public SignonMetrics signonMetrics(MeterRegistry meterRegistry) {
        return new SignonMetrics(meterRegistry);
    }

    /**
     * Application-event listener that emits the {@code carddemo.signon} counter on every authentication
     * decision made at the controller-reached {@code ProviderManager} boundary (review finding
     * P7-OBS-01).
     *
     * <p><strong>Why this exists.</strong> The migration's signon is controller-managed:
     * {@code SignonController} calls {@code AuthenticationManager.authenticate(..)} directly instead of
     * using Spring Security's form-login filter, and the {@code @Observed(name = "carddemo.signon")}
     * annotation sits on {@code SignonService.mainEntry}, which the web path never invokes &mdash; so the
     * intended signon metric was dead and {@code carddemo.signon} never appeared in Prometheus while the
     * account/card observations did. {@code SecurityConfig} now wires a
     * {@link org.springframework.security.authentication.DefaultAuthenticationEventPublisher} into that
     * {@code ProviderManager}, so each authentication publishes a success or failure event; this listener
     * turns those events into the counter.</p>
     *
     * <p><strong>No sensitive data.</strong> The counter carries only the low-cardinality
     * {@code outcome} tag ({@code success} / {@code failure}). No user id, password, account, card,
     * customer or transaction identifier is ever read from the event or attached as a tag, keeping the
     * metric low-cardinality and free of personally-identifying data. Observability adds no business
     * behavior; the listeners never influence the authentication outcome.</p>
     *
     * <p>Both counters share the meter name {@code carddemo.signon} and the single tag key
     * {@code outcome}, so their tag-key sets are identical &mdash; the consistency Prometheus requires of
     * meters that share a name. They are pre-registered at construction so both series are present (at
     * zero) from startup and a before/after scrape cleanly shows the increment.</p>
     */
    public static final class SignonMetrics {

        /** Meter name of the signon counter, exported by Prometheus as {@code carddemo_signon_total}. */
        static final String SIGNON_METER_NAME = "carddemo.signon";

        /**
         * Tag key distinguishing a successful from a failed authentication ({@code success} /
         * {@code failure}).
         */
        static final String OUTCOME_TAG = "outcome";

        /** Counter incremented on a successful authentication ({@code outcome=success}). */
        private final Counter successCounter;

        /** Counter incremented on a failed authentication ({@code outcome=failure}). */
        private final Counter failureCounter;

        /**
         * Pre-registers the success and failure signon counters on the supplied registry so both
         * {@code carddemo.signon} series exist (at zero) from application startup.
         *
         * @param meterRegistry the meter registry the {@code carddemo.signon} counters register on
         */
        public SignonMetrics(MeterRegistry meterRegistry) {
            this.successCounter = Counter.builder(SIGNON_METER_NAME)
                    .tag(OUTCOME_TAG, "success")
                    .description("Signon authentication attempts by outcome (no user identifiers)")
                    .register(meterRegistry);
            this.failureCounter = Counter.builder(SIGNON_METER_NAME)
                    .tag(OUTCOME_TAG, "failure")
                    .description("Signon authentication attempts by outcome (no user identifiers)")
                    .register(meterRegistry);
        }

        /**
         * Increments the {@code carddemo.signon} counter tagged {@code outcome=success} when the
         * controller-reached {@code ProviderManager} authenticates a principal.
         *
         * @param event the published success event; its principal and authorities are intentionally not
         *              read, so no user identifier can leak into a tag
         */
        @EventListener
        public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
            successCounter.increment();
        }

        /**
         * Increments the {@code carddemo.signon} counter tagged {@code outcome=failure} for any
         * authentication failure (a wrong password or an unknown user), which Spring Security publishes as
         * a subclass of {@link AbstractAuthenticationFailureEvent}.
         *
         * @param event the published failure event; its exception and authentication request are
         *              intentionally not read, so neither a user identifier nor a reason string can leak
         *              into a tag
         */
        @EventListener
        public void onAuthenticationFailure(AbstractAuthenticationFailureEvent event) {
            failureCounter.increment();
        }
    }
}
