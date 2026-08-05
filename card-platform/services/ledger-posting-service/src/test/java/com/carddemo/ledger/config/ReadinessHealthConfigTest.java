package com.carddemo.ledger.config;

import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.repository.OutboxEventRepository;

import java.util.List;
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

/** Verifies that ledger readiness reports real dependency, listener and relay state. */
@DisplayName("Ledger readiness health")
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

    @Test
    @DisplayName("requires both listeners to be running")
    void requiresBothListenersToBeRunning() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock();
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer transaction = mock(MessageListenerContainer.class);
        MessageListenerContainer accountState = mock(MessageListenerContainer.class);
        when(transaction.isRunning()).thenReturn(true);
        when(accountState.isRunning()).thenReturn(true, false);
        when(registry.getListenerContainers()).thenReturn(List.of(transaction, accountState));
        when(provider.getIfAvailable()).thenReturn(registry);
        HealthIndicator indicator = config.listenersHealthIndicator(provider);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    /**
     * Asserts a registered listener that is not running holds readiness down.
     *
     * <p>This is the condition the probe exists to catch. The expected number of listeners is read
     * from the registry rather than written into the class, so what readiness can still tell an
     * operator is whether the listeners that registered are actually consuming. A listener registered
     * and stopped means this instance either posts nothing or lets its replica of the account record
     * fall behind without limit, and it must not receive traffic.
     */
    @Test
    @DisplayName("stays down while a registered listener is not running")
    void staysDownWhileARegisteredListenerIsNotRunning() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock();
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer posting = mock(MessageListenerContainer.class);
        MessageListenerContainer accountState = mock(MessageListenerContainer.class);
        when(posting.isRunning()).thenReturn(true);
        when(accountState.isRunning()).thenReturn(false);
        when(registry.getListenerContainers()).thenReturn(List.of(posting, accountState));
        when(provider.getIfAvailable()).thenReturn(registry);

        Health health = config.listenersHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("registered", 2)
                .containsEntry("running", 1L);
    }

    /**
     * Asserts a listener added later needs no edit to this class.
     *
     * <p>A literal expected count is what made the notification service permanently unready: the
     * number named there was two while three listeners were declared. This service reached two
     * listeners the same way, by gaining the account-state listener. Readiness now counts what
     * registered, so a third listener reports ready the moment it runs, and the inventory of listeners
     * the platform should declare is held at build time by the equivalence contract suite instead.
     */
    @Test
    @DisplayName("a listener added later reports ready without an edit here")
    void aListenerAddedLaterNeedsNoEditHere() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock();
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer posting = mock(MessageListenerContainer.class);
        MessageListenerContainer accountState = mock(MessageListenerContainer.class);
        MessageListenerContainer addedLater = mock(MessageListenerContainer.class);
        when(posting.isRunning()).thenReturn(true);
        when(accountState.isRunning()).thenReturn(true);
        when(addedLater.isRunning()).thenReturn(true);
        when(registry.getListenerContainers())
                .thenReturn(List.of(posting, accountState, addedLater));
        when(provider.getIfAvailable()).thenReturn(registry);

        Health health = config.listenersHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("registered", 3);
    }

    /**
     * Asserts an absent registry is not mistaken for a service with no listeners.
     *
     * <p>The registry appears while the context is still refreshing. A poll arriving before it exists
     * has learned nothing, and reporting ready then would route traffic to an instance whose listeners
     * have not been created yet.
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
}