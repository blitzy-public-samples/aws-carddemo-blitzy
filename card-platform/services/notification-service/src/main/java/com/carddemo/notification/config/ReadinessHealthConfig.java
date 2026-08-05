package com.carddemo.notification.config;

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

    @Bean
    public HealthIndicator listenersHealthIndicator(
            ObjectProvider<KafkaListenerEndpointRegistry> registries) {
        return () -> listenersHealth(registries.getIfAvailable(), 2);
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

    static Health listenersHealth(KafkaListenerEndpointRegistry registry, int expected) {
        Collection<MessageListenerContainer> containers = registry == null
                ? java.util.List.of()
                : registry.getListenerContainers();
        long running = containers.stream().filter(MessageListenerContainer::isRunning).count();
        return containers.size() == expected && running == expected
                ? Health.up().withDetails(Map.of("expected", expected, "running", running)).build()
                : Health.down().withDetails(
                        Map.of("expected", expected, "running", running)).build();
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}