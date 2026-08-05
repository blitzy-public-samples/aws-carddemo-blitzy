package com.carddemo.authorization.config;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the shipped {@code application.yml} into {@link AuthorizationProperties} and reads the
 * result back.
 *
 * <p>This class has no COBOL ancestor. Each test builds a context holding one properties
 * bean and no auto-configuration, so no database and no message broker has to run.
 *
 * <p>The first test is the reachability check: a key that no component reads binds to nothing, and
 * this test fails when a key is renamed on one side only. The remaining tests are the fail-fast
 * checks: an invalid value stops the context rather than reaching the broker.
 */
@DisplayName("AuthorizationProperties, the bound carddemo block of the authorization service")
class AuthorizationPropertiesTest {

    /** Registers the properties bean and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthorizationProperties.class)
    static class PropertiesEnabled {
    }

    /** A context whose only property source is the shipped {@code application.yml}. */
    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesEnabled.class);

    @Test
    @DisplayName("every shipped key binds to the default application.yml documents")
    void everyShippedKeyBindsToItsDocumentedDefault() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            AuthorizationProperties properties = context.getBean(AuthorizationProperties.class);

            assertThat(properties.kafka().topics().transactionAuthorized())
                    .isEqualTo("transaction.authorized");
            assertThat(properties.kafka().topics().transactionDeclined())
                    .isEqualTo("transaction.declined");
            assertThat(properties.kafka().topics().accountStateChanged())
                    .isEqualTo("account.state-changed");
            assertThat(properties.kafka().topics().cardUpdated()).isEqualTo("card.updated");
            assertThat(properties.kafka().topics().deadLetter()).isEqualTo("carddemo.dead-letter");
            assertThat(properties.kafka().groups().accountStateChanged())
                    .isEqualTo("authorization-account-state");
            assertThat(properties.kafka().groups().cardUpdated())
                    .isEqualTo("authorization-card-updated");
            assertThat(properties.outbox().relay().fixedDelayMs()).isEqualTo(500L);
            assertThat(properties.outbox().relay().batchSize()).isEqualTo(100);
            assertThat(properties.outbox().relay().instanceId()).isNotBlank();
            assertThat(properties.outbox().relay().claimTimeout())
                    .isEqualTo(java.time.Duration.ofMinutes(2L));
            assertThat(properties.outbox().publishedRetentionHours()).isEqualTo(168L);
            assertThat(properties.processedEvent().markerRetentionHours()).isEqualTo(168L);
            assertThat(properties.retention().sweepIntervalMs()).isEqualTo(3_600_000L);
            assertThat(properties.replica().maxStaleness()).isEqualTo(java.time.Duration.ofDays(1L));
        });
    }

    /**
     * Asserts this record binds no supporting-service address, because the service reads no other
     * service.
     *
     * <p>Two addresses used to be bound here and nothing read either one. A configured value with no
     * reader is worse than an absent one: it tells a reader the call exists.
     */
    @Test
    @DisplayName("binds no supporting-service address, because no such call is made")
    void bindsNoSupportingServiceAddress() {
        assertThat(AuthorizationProperties.class.getRecordComponents())
                .as("the components of the bound carddemo block")
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder(
                        "kafka", "outbox", "processedEvent", "retention", "replica");
    }

    @Test
    @DisplayName("a blank topic name stops start-up and names the property")
    void aBlankTopicNameStopsStartUp() {
        shipped.withPropertyValues("carddemo.kafka.topics.transaction-authorized=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("kafka.topics.transactionAuthorized");
                });
    }

    @Test
    @DisplayName("a relay delay of zero stops start-up and names the property")
    void aNonPositiveRelayDelayStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.fixed-delay-ms=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("outbox.relay.fixedDelayMs");
                });
    }

    @Test
    @DisplayName("a relay claim timeout of zero stops start-up")
    void aNonPositiveRelayClaimTimeoutStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.claim-timeout=PT0S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("claimTimeout");
                });
    }

    /**
     * Asserts a replica window of zero stops start-up.
     *
     * <p>No observation can be newer than the moment it is compared against, so a window of zero would
     * refuse every authorization. Failing at start-up names the property; failing at the first request
     * would look like a broken replica.
     */
    @Test
    @DisplayName("a replica window of zero stops start-up")
    void aReplicaWindowOfZeroStopsStartUp() {
        shipped.withPropertyValues("carddemo.replica.max-staleness=PT0S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("max-staleness");
                });
    }

    @Test
    @DisplayName("the bound record is the only properties bean and it is immutable")
    void theBoundRecordIsTheOnlyPropertiesBean() {
        shipped.run(context -> {
            assertThat(context).hasSingleBean(AuthorizationProperties.class);
            assertThat(AuthorizationProperties.class.isRecord())
                    .withFailMessage("AuthorizationProperties must stay a record, so no component "
                            + "can change a value the constraints already accepted")
                    .isTrue();
        });
    }
}
