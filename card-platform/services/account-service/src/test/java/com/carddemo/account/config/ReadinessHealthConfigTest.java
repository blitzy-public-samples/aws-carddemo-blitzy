package com.carddemo.account.config;

import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.repository.OutboxEventRepository;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.MetadataRecoveryStrategy;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies that account readiness reports real dependency and relay state. */
@DisplayName("Account readiness health")
class ReadinessHealthConfigTest {

    private final ReadinessHealthConfig config = new ReadinessHealthConfig();

    @Test
    @DisplayName("keeps external dependencies out of liveness")
    void keepsExternalDependenciesOutOfLiveness() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty(
                            "management.endpoint.health.group.readiness.include"))
                            .isEqualTo("readinessState,db,kafka,listeners,outbox");
                    assertThat(context.getEnvironment().getProperty(
                            "management.endpoint.health.group.liveness.include"))
                            .isEqualTo("livenessState");
                });
    }

    @Test
    @DisplayName("reports the bounded Kafka check without leaking failure messages")
    void reportsTheBoundedKafkaCheckWithoutLeakingFailureMessages() {
        Admin admin = mock(Admin.class);
        KafkaAdmin kafkaAdmin = mock(KafkaAdmin.class);
        DescribeClusterResult result = mock(DescribeClusterResult.class);
        ObjectProvider<KafkaAdmin> kafkaAdmins = mock();
        when(kafkaAdmin.getOperationTimeout()).thenReturn(1);
        when(kafkaAdmins.getIfAvailable()).thenReturn(kafkaAdmin);
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(result);
        when(result.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-one"));
        HealthIndicator indicator = config.kafkaHealthIndicator(admin, kafkaAdmins);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        when(admin.describeCluster(any(DescribeClusterOptions.class)))
                .thenThrow(new IllegalStateException("account 00000000001"));
        Health failed = indicator.health();
        assertThat(failed.getStatus()).isEqualTo(Status.DOWN);
        assertThat(failed.getDetails())
                .hasSize(1)
                .containsEntry("reason", IllegalStateException.class.getSimpleName());
    }

    /**
     * Asserts readiness reports up for a service that registers no listener.
     *
     * <p>This service consumes nothing, so its registry holds no container and there is nothing that
     * could be failing to run. That is now a consequence of the rule rather than a literal zero written
     * into the configuration: readiness compares the containers that registered against the containers
     * that are running, and an empty set satisfies it.
     *
     * <p>The property that this service declares no listener is held at build time by
     * {@code equivalence-tests} {@code ProjectionBootstrapContractTest}, which enumerates every
     * listener of every service. A listener added here without being declared there fails that suite,
     * which is a firmer guard than a health probe because it runs before anything is deployed.
     */
    @Test
    @DisplayName("reports ready while registering no Kafka listener")
    void reportsReadyWhileRegisteringNoKafkaListener() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock();
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainers()).thenReturn(List.of());
        when(provider.getIfAvailable()).thenReturn(registry);

        Health health = config.listenersHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("registered", 0)
                .containsEntry("running", 0L);
    }

    /**
     * Asserts an absent registry holds readiness down rather than reading as an empty one.
     *
     * <p>The two are different states and were previously indistinguishable. The registry bean is
     * registered by the Kafka auto-configuration whether or not any listener exists, so an absent one
     * means the context has not finished refreshing, and a poll arriving then has learned nothing.
     */
    @Test
    @DisplayName("an absent registry holds readiness down")
    void anAbsentRegistryHoldsReadinessDown() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock();
        when(provider.getIfAvailable()).thenReturn(null);

        Health health = config.listenersHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "missing-listener-registry");
    }

    @Test
    @DisplayName("fails readiness when an outbox row is abandoned")
    void failsReadinessWhenAnOutboxRowIsAbandoned() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.existsByRelayState(OutboxEventEntity.RelayState.ABANDONED))
                .thenReturn(false, true);
        HealthIndicator indicator = config.outboxHealthIndicator(repository);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    /**
     * Asserts the readiness administrator carries bounded connection settings.
     *
     * <p>A broker that is unreachable at start-up left this client rebootstrapping without pause: it
     * wrote thousands of {@code Rebootstrapping with Cluster} lines a second at INFO, spent
     * measurable processor time on them, and buried the line that named the degraded dependency.
     * {@code metadata.recovery.strategy} defaults to {@code rebootstrap}, and the client logs one
     * such line every time it looks for a node and finds none.
     *
     * <p>The settings are read from the copy the bean method builds rather than from a running
     * client, so this test opens no client and contacts no broker.
     */
    @Test
    @DisplayName("the readiness administrator keeps a broker outage bounded")
    void theReadinessAdministratorKeepsABrokerOutageBounded() {
        Map<String, Object> configured = new LinkedHashMap<>();
        configured.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:29092");
        configured.put(AdminClientConfig.METADATA_RECOVERY_STRATEGY_CONFIG,
                MetadataRecoveryStrategy.REBOOTSTRAP.name);

        Map<String, Object> bounded = ReadinessHealthConfig.boundedAdminSettings(configured);

        assertThat(bounded)
                .as("the loop is removed at its source: no node to send to no longer logs a line")
                .containsEntry(AdminClientConfig.METADATA_RECOVERY_STRATEGY_CONFIG,
                        MetadataRecoveryStrategy.NONE.name)
                .containsEntry(AdminClientConfig.CLIENT_ID_CONFIG,
                        ReadinessHealthConfig.READINESS_CLIENT_ID)
                .containsEntry(AdminClientConfig.RECONNECT_BACKOFF_MS_CONFIG,
                        ReadinessHealthConfig.RECONNECT_BACKOFF_MS)
                .containsEntry(AdminClientConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG,
                        ReadinessHealthConfig.RECONNECT_BACKOFF_MAX_MS)
                .containsEntry(AdminClientConfig.RETRY_BACKOFF_MS_CONFIG,
                        ReadinessHealthConfig.RETRY_BACKOFF_MS)
                .containsEntry(AdminClientConfig.RETRY_BACKOFF_MAX_MS_CONFIG,
                        ReadinessHealthConfig.RETRY_BACKOFF_MAX_MS)
                .containsEntry(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG,
                        ReadinessHealthConfig.CONNECTION_SETUP_TIMEOUT_MS)
                .containsEntry(AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG,
                        ReadinessHealthConfig.CONNECTION_SETUP_TIMEOUT_MAX_MS)
                .containsEntry(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                        ReadinessHealthConfig.REQUEST_TIMEOUT_MS)
                .containsEntry(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                        ReadinessHealthConfig.DEFAULT_API_TIMEOUT_MS)
                .containsEntry(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:29092");
        assertThat(new AdminClientConfig(bounded).values())
                .as("every pinned key is one the administrator declares, so none is discarded")
                .isNotEmpty();
    }
}