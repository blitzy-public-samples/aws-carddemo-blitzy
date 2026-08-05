package com.carddemo.fraud.config;

import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.repository.OutboxEventRepository;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
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

    @Bean(destroyMethod = "close")
    @Lazy
    public Admin readinessKafkaAdmin(ObjectProvider<KafkaAdmin> kafkaAdmins) {
        KafkaAdmin kafkaAdmin = kafkaAdmins.getIfAvailable();
        if (kafkaAdmin == null) {
            throw new IllegalStateException("KafkaAdmin is unavailable");
        }
        return Admin.create(kafkaAdmin.getConfigurationProperties());
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
    public HealthIndicator outboxHealthIndicator(OutboxEventRepository outboxEvents) {
        return () -> outboxEvents.existsByRelayState(OutboxEventEntity.RelayState.ABANDONED)
                ? Health.down().withDetail("state", "abandoned-row").build()
                : Health.up().build();
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