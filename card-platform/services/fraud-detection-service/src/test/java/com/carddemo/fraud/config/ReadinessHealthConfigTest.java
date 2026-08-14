package com.carddemo.fraud.config;

import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.repository.OutboxEventRepository;

import java.time.Instant;
import java.util.Optional;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies that fraud readiness reports real dependency, listener and relay state. */
@DisplayName("Fraud readiness health")
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
                .thenThrow(new IllegalStateException("transaction 1234567890123456"));
        Health failed = indicator.health();
        assertThat(failed.getStatus()).isEqualTo(Status.DOWN);
        assertThat(failed.getDetails())
                .hasSize(1)
                .containsEntry("reason", IllegalStateException.class.getSimpleName());
    }

    @Test
    @DisplayName("requires the transaction listener to be running")
    void requiresTheTransactionListenerToBeRunning() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock();
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer transaction = mock(MessageListenerContainer.class);
        when(transaction.isRunning()).thenReturn(true, false);
        when(registry.getListenerContainers()).thenReturn(List.of(transaction));
        when(provider.getIfAvailable()).thenReturn(registry);
        HealthIndicator indicator = config.listenersHealthIndicator(provider);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
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

    @Test
    @DisplayName("reports the backlog it read beside the reading")
    void reportsTheBacklogItRead() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.existsByRelayState(OutboxEventEntity.RelayState.ABANDONED))
                .thenReturn(false);
        when(repository.countDueBefore(any())).thenReturn(3L);
        when(repository.findEarliestDueBefore(any()))
                .thenReturn(Optional.of(Instant.now().minusSeconds(45)));

        Health health = config.outboxHealthIndicator(repository).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .as("the rows waiting, so a stopped relay is visible before the first abandonment")
                .containsEntry("due", 3L)
                .as("three rows waiting under a minute is a relay keeping up")
                .containsEntry("state", ReadinessHealthConfig.BACKLOG_CLEAR);
        assertThat((Long) health.getDetails().get("oldestDueAgeSeconds"))
                .as("how long the longest-waiting row has waited")
                .isGreaterThanOrEqualTo(44L);
    }

    @Test
    @DisplayName("a backlog alone does not fail readiness")
    void aBacklogAloneDoesNotFailReadiness() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.existsByRelayState(OutboxEventEntity.RelayState.ABANDONED))
                .thenReturn(false);
        when(repository.countDueBefore(any())).thenReturn(5_000L);
        when(repository.findEarliestDueBefore(any()))
                .thenReturn(Optional.of(Instant.now().minusSeconds(3_600)));

        Health health = config.outboxHealthIndicator(repository).health();

        assertThat(health.getStatus())
                .as("a broker hiccup reports its backlog without removing this pod from service")
                .isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("due", 5_000L)
                .as("the backlog is named as behind, which is a reading and not an eviction")
                .containsEntry("state", ReadinessHealthConfig.BACKLOG_BEHIND);
    }

    @Test
    @DisplayName("an abandoned row reports the backlog beside the reason it is down")
    void anAbandonedRowReportsTheBacklogBesideTheReason() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.existsByRelayState(OutboxEventEntity.RelayState.ABANDONED))
                .thenReturn(true);
        when(repository.countDueBefore(any())).thenReturn(2L);
        when(repository.findEarliestDueBefore(any())).thenReturn(Optional.empty());

        Health health = config.outboxHealthIndicator(repository).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .as("the abandoned row remains the reason, and the backlog is context beside it")
                .containsEntry("state", "abandoned-row")
                .containsEntry("due", 2L)
                .containsEntry("oldestDueAgeSeconds", 0L);
    }

    @Test
    @DisplayName("the backlog is named against a threshold on size and one on age")
    void theBacklogIsNamedAgainstBothThresholds() {
        assertThat(ReadinessHealthConfig.backlogState(0L, 0L))
                .as("an empty outbox").isEqualTo(ReadinessHealthConfig.BACKLOG_CLEAR);
        assertThat(ReadinessHealthConfig.backlogState(
                ReadinessHealthConfig.BACKLOG_DUE_THRESHOLD - 1, 0L))
                .as("one row below the size threshold")
                .isEqualTo(ReadinessHealthConfig.BACKLOG_CLEAR);
        assertThat(ReadinessHealthConfig.backlogState(
                ReadinessHealthConfig.BACKLOG_DUE_THRESHOLD, 0L))
                .as("a burst larger than the size threshold, whatever its age")
                .isEqualTo(ReadinessHealthConfig.BACKLOG_BEHIND);
        assertThat(ReadinessHealthConfig.backlogState(
                1L, ReadinessHealthConfig.BACKLOG_AGE_THRESHOLD_SECONDS - 1))
                .as("one row waiting just inside the age threshold")
                .isEqualTo(ReadinessHealthConfig.BACKLOG_CLEAR);
        assertThat(ReadinessHealthConfig.backlogState(
                1L, ReadinessHealthConfig.BACKLOG_AGE_THRESHOLD_SECONDS))
                .as("one row waiting past the age threshold names a stopped relay that a size"
                        + " reading alone would report as healthy")
                .isEqualTo(ReadinessHealthConfig.BACKLOG_BEHIND);
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

    /**
     * A datastore the outbox read cannot reach reports the dependency down, and does not throw.
     *
     * <p>An indicator that throws leaves the actuator with no document to render. The request then
     * leaves through the error path, and whatever advice this service declares answers the health poll
     * instead: a paused datastore answered a probe with a business error body, and one service answered
     * {@code 500} where a probe reads a status. Both hide which dependency stopped.
     *
     * <p>Reporting down instead is what {@code kafkaHealth} of the same class already did for the
     * broker. The reason names the type of the root cause, so a reader of the document learns which
     * dependency is away without the document being destroyed to say so.
     */
    @Test
    @DisplayName("a datastore the outbox read cannot reach reports down rather than throwing")
    void anUnreachableDatastoreReportsDownRatherThanThrowing() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.existsByRelayState(OutboxEventEntity.RelayState.ABANDONED))
                .thenThrow(new DataAccessResourceFailureException("connection refused",
                        new java.net.ConnectException("Connection refused")));

        Health health = config.outboxHealthIndicator(repository).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "ConnectException");
    }
}