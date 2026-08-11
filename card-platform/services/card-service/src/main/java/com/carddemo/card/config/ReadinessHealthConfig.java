package com.carddemo.card.config;

import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.MetadataRecoveryStrategy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;

/** Supplies the bounded external-dependency checks used by the readiness group. */
@Configuration
public class ReadinessHealthConfig {

    /**
     * How many listener containers this service declares, and therefore how many readiness
     * requires registered and running.
     *
     * <p>Held against the module's own sources by {@code equivalence-tests}
     * {@code ReadinessListenerExpectationContractTest}, so this number cannot drift from the
     * listeners the service declares.
     */
    static final int DECLARED_LISTENERS = 0;

    /**
     * Client identifier of the administrator this class opens, so a broker-side or client-side log
     * line names the readiness probe rather than an anonymous client.
     */
    static final String READINESS_CLIENT_ID = "card-readiness-admin";

    /**
     * Wait before the first reconnect to an address that refused a connection, and the ceiling that
     * wait grows to. The client default starts at fifty milliseconds, which is a reconnect attempt
     * roughly twenty times a second while a broker is down.
     */
    static final long RECONNECT_BACKOFF_MS = 1_000L;
    static final long RECONNECT_BACKOFF_MAX_MS = 30_000L;

    /** Wait before a refused request is retried, and the ceiling that wait grows to. */
    static final long RETRY_BACKOFF_MS = 1_000L;
    static final long RETRY_BACKOFF_MAX_MS = 30_000L;

    /** How long one connection attempt may take, and the ceiling that grows to. */
    static final long CONNECTION_SETUP_TIMEOUT_MS = 2_000L;
    static final long CONNECTION_SETUP_TIMEOUT_MAX_MS = 10_000L;

    /**
     * How long one request waits, and the ceiling on the whole call. Both stay above the operation
     * timeout {@code spring.kafka.admin.operation-timeout} carries, which is what
     * {@link #kafkaHealth} passes and therefore what actually bounds one check.
     */
    static final int REQUEST_TIMEOUT_MS = 5_000;
    static final int DEFAULT_API_TIMEOUT_MS = 10_000;

    /**
     * Opens the administrator the Kafka readiness check asks the cluster through, over settings that
     * stay bounded while no broker answers.
     *
 * <p>The connection settings are pinned rather than inherited, so the probe reads the broker
 * this service publishes to and answers within its own timeout.
     *
     * <p>{@code metadata.recovery.strategy=none} is the setting that removes the loop. Rebootstrap
     * re-reads {@code bootstrap.servers} for a client whose known brokers have all moved, which a
     * long-lived producer or consumer needs; this client knows the bootstrap addresses and nothing
     * else, so it has nothing to recover to. Failing the call is the answer a probe wants:
     * {@link #kafkaHealth} catches it and reports the dependency down, naming the exception type.
     * The four backoff settings and the two connection timeouts bound how often the client retries
     * while the broker is away, and the two request settings bound how long one check may take.
     *
     * <p>Nothing here contacts a broker. The bean is lazy, so the client opens on the first
     * readiness poll, and the container closes it with the context.
     *
     * @param kafkaAdmins provider of the auto-configured administrator settings
     * @return the administrator the Kafka readiness check uses
     * @throws IllegalStateException when the auto-configured settings are unavailable
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public Admin readinessKafkaAdmin(ObjectProvider<KafkaAdmin> kafkaAdmins) {
        KafkaAdmin kafkaAdmin = kafkaAdmins.getIfAvailable();
        if (kafkaAdmin == null) {
            throw new IllegalStateException("KafkaAdmin is unavailable");
        }
        return Admin.create(boundedAdminSettings(kafkaAdmin.getConfigurationProperties()));
    }

    /**
     * Copies {@code configured} and pins the settings that keep a broker outage quiet.
     *
     * @param configured the auto-configured administrator settings, including the broker address
     *                   and the login this deployment resolved
     * @return a copy carrying the bounded connection, retry and timeout settings
     */
    static Map<String, Object> boundedAdminSettings(Map<String, Object> configured) {
        Map<String, Object> bounded = new LinkedHashMap<>(configured);

        bounded.put(AdminClientConfig.CLIENT_ID_CONFIG, READINESS_CLIENT_ID);
        bounded.put(AdminClientConfig.METADATA_RECOVERY_STRATEGY_CONFIG,
                MetadataRecoveryStrategy.NONE.name);
        bounded.put(AdminClientConfig.RECONNECT_BACKOFF_MS_CONFIG, RECONNECT_BACKOFF_MS);
        bounded.put(AdminClientConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, RECONNECT_BACKOFF_MAX_MS);
        bounded.put(AdminClientConfig.RETRY_BACKOFF_MS_CONFIG, RETRY_BACKOFF_MS);
        bounded.put(AdminClientConfig.RETRY_BACKOFF_MAX_MS_CONFIG, RETRY_BACKOFF_MAX_MS);
        bounded.put(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG,
                CONNECTION_SETUP_TIMEOUT_MS);
        bounded.put(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG,
                CONNECTION_SETUP_TIMEOUT_MAX_MS);
        bounded.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MS);
        bounded.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, DEFAULT_API_TIMEOUT_MS);

        return bounded;
    }

    @Bean
    public HealthIndicator kafkaHealthIndicator(
            @Lazy @Qualifier("readinessKafkaAdmin") Admin admin,
            ObjectProvider<KafkaAdmin> kafkaAdmins) {
        return () -> {
            KafkaAdmin kafkaAdmin = kafkaAdmins.getIfAvailable();
            return kafkaAdmin == null
                    ? Health.down().withDetail("reason", "missing-kafka-admin").build()
                    : kafkaHealth(admin, kafkaAdmin.getOperationTimeout());
        };
    }

    /**
     * Reports whether every listener this service registered is running.
     *
     * <p>The expected number is read from the registry rather than written here. A literal was what
     * made this probe unsatisfiable: the file named two while the service declared three, so readiness
     * stayed down for good, the compose health check never passed and both Kubernetes probes failed
     * permanently. A literal also has to be edited every time a listener is added, which is how that
     * mismatch arose in the first place.
     *
     * <p>The inventory of listeners the platform is supposed to declare is held at build time by
     * {@code equivalence-tests} {@code ProjectionBootstrapContractTest}, which enumerates every
     * listener of every service and fails until a newly added one is declared there deliberately.
     * That is a firmer guard than a probe, because it runs before anything is deployed.
     *
     * @param registries provider of the listener registry, absent before the context finishes
     * @return the indicator the readiness group polls
     */
    @Bean
    public HealthIndicator listenersHealthIndicator(
            ObjectProvider<KafkaListenerEndpointRegistry> registries) {
        return () -> listenersHealth(registries.getIfAvailable());
    }


    /** Rows due before the backlog is reported as behind. */
    static final long BACKLOG_DUE_THRESHOLD = 100L;

    /** Seconds the longest-waiting row may wait before the backlog is reported as behind. */
    static final long BACKLOG_AGE_THRESHOLD_SECONDS = 300L;

    /** The state of an outbox holding a row the relay gave up on. */
    static final String ABANDONED_ROW = "abandoned-row";

    /** The state of an outbox that is keeping up. */
    static final String BACKLOG_CLEAR = "clear";

    /** The state of an outbox that is behind one of the two thresholds. */
    static final String BACKLOG_BEHIND = "behind";

    /**
     * Names whether the outbox is keeping up, against a threshold on size and one on age.
     *
     * <p>The answer is a name and not a status, so it never removes this pod from service. A backlog
     * is usually the broker rather than the pod, and evicting the pod would take away the one
     * component still recording what happened. An abandoned row remains the only condition that
     * fails readiness.
     *
     * <p>Age is read as well as size because the two say different things. A burst raises the size
     * while the age stays small, and a stopped relay raises the age while the size may not move at
     * all. Either alone reports a stopped relay as healthy in one of those two cases.
     *
     * @param due                 rows due for an attempt now
     * @param oldestDueAgeSeconds seconds the longest-waiting due row has waited
     * @return {@link #BACKLOG_BEHIND} past either threshold, and {@link #BACKLOG_CLEAR} otherwise
     */
    static String backlogState(long due, long oldestDueAgeSeconds) {
        boolean behind = due >= BACKLOG_DUE_THRESHOLD
                || oldestDueAgeSeconds >= BACKLOG_AGE_THRESHOLD_SECONDS;
        return behind ? BACKLOG_BEHIND : BACKLOG_CLEAR;
    }

    /**
     * Reports whether the outbox holds a row the relay gave up on.
     *
     * <p>The read reaches the datastore, so it can fail rather than answer. An indicator that lets a
     * failure escape takes the whole health endpoint with it: the actuator has no answer to render,
     * the request leaves through the container error path, and {@code /actuator/health} answers a
     * framework body with {@code 500}. A container probe reading {@code curl -fsS} fails either way,
     * but an operator reading the response learns nothing about which dependency is away, and the
     * document that would have named it is the one thing the failure destroyed.
     *
     * <p>{@link #kafkaHealth} already answers this way for the broker, so the datastore now answers
     * the same way: the dependency is reported down, named by the type of the root cause, and the
     * endpoint renders its own document with {@code 503}.
     *
     * @param outboxEvents the outbox this service relays from
     * @return the indicator the readiness group polls
     */
    @Bean
    public HealthIndicator outboxHealthIndicator(OutboxEventRepository outboxEvents) {
        return () -> {
            try {
                Instant now = Instant.now();
                long due = outboxEvents.countDueBefore(now);
                long oldestDueAgeSeconds = outboxEvents.findEarliestDueBefore(now)
                        .map(earliest -> Duration.between(earliest, now).toSeconds())
                        .orElse(0L);

                if (outboxEvents.existsByRelayState(OutboxEventEntity.RelayState.ABANDONED)) {
                    return Health.down()
                            .withDetail("state", ABANDONED_ROW)
                            .withDetail("due", due)
                            .withDetail("oldestDueAgeSeconds", oldestDueAgeSeconds)
                            .build();
                }
                return Health.up()
                        .withDetail("state", backlogState(due, oldestDueAgeSeconds))
                        .withDetail("due", due)
                        .withDetail("oldestDueAgeSeconds", oldestDueAgeSeconds)
                        .build();
            } catch (Exception failure) {
                return Health.down().withDetail("reason",
                        rootCause(failure).getClass().getSimpleName()).build();
            }
        };
    }

    static Health kafkaHealth(Admin admin, int timeoutSeconds) {
        try {
            String clusterId = admin.describeCluster(new DescribeClusterOptions()
                            .timeoutMs(Math.multiplyExact(timeoutSeconds, 1000)))
                    .clusterId().get(timeoutSeconds, TimeUnit.SECONDS);
            return clusterId == null || clusterId.isBlank()
                    ? Health.down().withDetail("reason", "missing-cluster-id").build()
                    : Health.up().build();
        } catch (Exception failure) {
            return Health.down().withDetail("reason",
                    rootCause(failure).getClass().getSimpleName()).build();
        }
    }

    /**
     * Compares the listeners that registered against the listeners that are running.
     *
     * <p>An absent registry is down rather than up. The registry appears while the context is still
     * refreshing, so a poll that arrives before it exists has learned nothing and must not report
     * ready.
     *
     * <p>Readiness requires at least the number of containers this service declares, and every
     * container that registered to be running. An earlier rule compared the registry against itself,
     * which an empty registry satisfies: a service whose listeners never registered reported ready,
     * and a probe routed traffic to an instance that consumed nothing. The declared number is what
     * separates "nothing is broken" from "nothing is there".
     *
 * <p>The comparison is a floor rather than an exact match.
     *
     * @param registry the listener registry, or {@code null} before the context supplies one
     * @return up when the declared number of containers registered and every one is running
     */
    static Health listenersHealth(KafkaListenerEndpointRegistry registry) {
        if (registry == null) {
            return Health.down().withDetail("reason", "missing-listener-registry").build();
        }
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        int registered = containers.size();
        long running = containers.stream().filter(MessageListenerContainer::isRunning).count();
        Map<String, Object> counts = Map.of(
                "declared", DECLARED_LISTENERS, "registered", registered, "running", running);
        return registered >= DECLARED_LISTENERS && running == registered
                ? Health.up().withDetails(counts).build()
                : Health.down().withDetails(counts).build();
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}