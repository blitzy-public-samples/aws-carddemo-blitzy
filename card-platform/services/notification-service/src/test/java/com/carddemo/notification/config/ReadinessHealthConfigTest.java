package com.carddemo.notification.config;

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

/** Verifies that notification readiness reports real dependency and listener state. */
@DisplayName("Notification readiness health")
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

    /**
     * Asserts readiness reports up once every declared listener is running.
     *
     * <p>This is the regression this class previously encoded backwards. The indicator named two
     * listeners while the service declared three, so readiness could never report up: the compose
     * health check never passed and both Kubernetes probes failed for good. The test did not catch it
     * because it registered two containers as well, matching the literal instead of the service.
     *
     * <p>The four listeners are {@code TransactionAuthorizedConsumer},
     * {@code TransactionPostedConsumer}, {@code FraudFlaggedConsumer} and
     * {@code CustomerContextChangedConsumer}. Readiness now counts what registered, so the number
     * here follows the service rather than leading it: the fourth listener was added for the
     * authorization fan-out without this class needing an edit.
     */
    @Test
    @DisplayName("reports ready once all four declared listeners are running")
    void reportsReadyOnceAllFourDeclaredListenersAreRunning() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock();
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer authorized = mock(MessageListenerContainer.class);
        MessageListenerContainer posted = mock(MessageListenerContainer.class);
        MessageListenerContainer flagged = mock(MessageListenerContainer.class);
        MessageListenerContainer customerContext = mock(MessageListenerContainer.class);
        when(authorized.isRunning()).thenReturn(true);
        when(posted.isRunning()).thenReturn(true);
        when(flagged.isRunning()).thenReturn(true);
        when(customerContext.isRunning()).thenReturn(true);
        when(registry.getListenerContainers())
                .thenReturn(List.of(authorized, posted, flagged, customerContext));
        when(provider.getIfAvailable()).thenReturn(registry);

        Health health = config.listenersHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("registered", 4)
                .containsEntry("running", 4L);
    }

    /**
     * Asserts one stopped listener holds readiness down.
     *
     * <p>A stopped customer-context listener means this instance stops learning cardholder details
     * while continuing to render alerts, so it must not receive traffic.
     */
    @Test
    @DisplayName("stays down while one declared listener is stopped")
    void staysDownWhileOneDeclaredListenerIsStopped() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock();
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer authorized = mock(MessageListenerContainer.class);
        MessageListenerContainer posted = mock(MessageListenerContainer.class);
        MessageListenerContainer flagged = mock(MessageListenerContainer.class);
        MessageListenerContainer customerContext = mock(MessageListenerContainer.class);
        when(authorized.isRunning()).thenReturn(true);
        when(posted.isRunning()).thenReturn(true);
        when(flagged.isRunning()).thenReturn(true);
        when(customerContext.isRunning()).thenReturn(false);
        when(registry.getListenerContainers())
                .thenReturn(List.of(authorized, posted, flagged, customerContext));
        when(provider.getIfAvailable()).thenReturn(registry);

        Health health = config.listenersHealthIndicator(provider).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("registered", 4)
                .containsEntry("running", 3L);
    }

    /**
     * Asserts an absent registry is not mistaken for a service with no listeners.
     *
     * <p>The registry appears while the context is still refreshing, and a poll arriving before it
     * exists has learned nothing. This service declares four listeners, so reporting ready then
     * would route traffic to an instance that renders no alert at all.
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
    @DisplayName("marks outbox readiness not applicable for this non-producer")
    void marksOutboxReadinessNotApplicableForThisNonProducer() {
        Health health = config.outboxHealthIndicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).hasSize(1).containsEntry("mode", "not-applicable");
    }
}