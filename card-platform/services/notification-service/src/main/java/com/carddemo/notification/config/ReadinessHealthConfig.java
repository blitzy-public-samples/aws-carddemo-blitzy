package com.carddemo.notification.config;

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
     * Client identifier of the administrator this class opens, so a broker-side or client-side log
     * line names the readiness probe rather than an anonymous client.
     */
    static final String READINESS_CLIENT_ID = "notification-readiness-admin";

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
     * <p>The connection settings are pinned rather than inherited. A broker that is unreachable when
     * this service starts left the client rebootstrapping in a tight loop: it wrote thousands of
     * {@code Rebootstrapping with Cluster} lines per second at INFO, spent measurable processor time
     * doing it, and buried the one line that named the degraded dependency. The cause is exact.
     * {@code metadata.recovery.strategy} defaults to {@code rebootstrap}, and the client logs one
     * such line every time it looks for a node to send a metadata request to and finds none, with no
     * interval between attempts of its own.
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

    @Bean
    public HealthIndicator outboxHealthIndicator() {
        return () -> Health.up().withDetail("mode", "not-applicable").build();
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
     * <p>A service that declares no listener registers no container, and an empty set has nothing
     * that is not running, so it reports up. That is the correct answer for the two services that
     * only serve requests, and it is now a property of the rule instead of a literal zero.
     *
     * @param registry the listener registry, or {@code null} before the context supplies one
     * @return up when every registered container is running, and down otherwise
     */
    static Health listenersHealth(KafkaListenerEndpointRegistry registry) {
        if (registry == null) {
            return Health.down().withDetail("reason", "missing-listener-registry").build();
        }
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        int registered = containers.size();
        long running = containers.stream().filter(MessageListenerContainer::isRunning).count();
        return running == registered
                ? Health.up().withDetails(
                        Map.of("registered", registered, "running", running)).build()
                : Health.down().withDetails(
                        Map.of("registered", registered, "running", running)).build();
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}