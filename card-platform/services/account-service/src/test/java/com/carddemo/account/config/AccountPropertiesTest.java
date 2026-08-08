package com.carddemo.account.config;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the shipped {@code application.yml} into {@link AccountProperties} and reads the result back.
 *
 * <p>This class has no COBOL ancestor. Each test builds a context holding one properties
 * bean and no auto-configuration, so no database and no message broker has to run.
 *
 * <p>The first test is the reachability check: a key that no component reads binds to nothing, and
 * this test fails when a key is renamed on one side only. The remaining tests are the fail-fast
 * checks: an invalid value stops the context rather than reaching the broker. Each of those asserts
 * the component path Spring reports, which is the camel-case form of the key.
 */
@DisplayName("AccountProperties, the bound carddemo block of the account service")
class AccountPropertiesTest {

    /** Registers the properties bean and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AccountProperties.class)
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
            AccountProperties properties = context.getBean(AccountProperties.class);

            assertThat(properties.api().maxRequestBodyBytes()).isEqualTo(65536L);
            assertThat(properties.kafka().topics().accountStateChanged())
                    .isEqualTo("account.state-changed");
            assertThat(properties.kafka().topics().customerContextChanged())
                    .isEqualTo("customer.context-changed");
            assertThat(properties.kafka().topics().transactionPosted())
                    .as("topic the posted-transaction listener reads")
                    .isEqualTo("transaction.posted");
            assertThat(properties.kafka().groups().transactionPosted())
                    .as("group that listener joins, which is this service's own so the notification "
                            + "service reading the same topic still receives every record")
                    .isEqualTo("account-posted");
            assertThat(properties.consumer().retry().maxAttempts())
                    .as("deliveries of one record, counting the first")
                    .isEqualTo(3);
            assertThat(properties.consumer().retry().backoffMs())
                    .as("milliseconds between two deliveries")
                    .isEqualTo(1_000L);
            assertThat(properties.outbox().relay().fixedDelayMs()).isEqualTo(500L);
            assertThat(properties.outbox().relay().batchSize()).isEqualTo(100);
            assertThat(properties.outbox().relay().claimTimeout()).isEqualTo(Duration.ofMinutes(2L));
            assertThat(properties.outbox().publishedRetentionHours()).isEqualTo(168L);
            assertThat(properties.processedEvent().markerRetentionHours())
                    .as("the marker horizon outlasts broker retention rather than equalling it")
                    .isEqualTo(720L);
            assertThat(properties.processedEvent().brokerRetentionHours()).isEqualTo(168L);
        });
    }

    @Test
    @DisplayName("a blank topic name stops start-up")
    void aBlankTopicNameStopsStartUp() {
        shipped.withPropertyValues("carddemo.kafka.topics.account-state-changed=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("kafka.topics.accountStateChanged");
                });
    }

    @Test
    @DisplayName("a request body ceiling of zero stops start-up")
    void aNonPositiveRequestBodyCeilingStopsStartUp() {
        shipped.withPropertyValues("carddemo.api.max-request-body-bytes=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("api.maxRequestBodyBytes");
                });
    }

    @Test
    @DisplayName("a relay delay of zero stops start-up")
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
    void aNonPositiveClaimTimeoutStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.claim-timeout=PT0S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("outbox.relay.claim-timeout");
                });
    }

    @Test
    @DisplayName("a published-row retention horizon of zero stops start-up")
    void aNonPositivePublishedRetentionStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.published-retention-hours=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("outbox.publishedRetentionHours");
                });
    }


    @Test
    @DisplayName("a marker horizon that does not outlast broker retention stops start-up")
    void aMarkerHorizonUnderTheMarginStopsStartUp() {
        shipped.withPropertyValues("carddemo.processed-event.marker-retention-hours=168")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("markerRetentionHours")
                            .hasStackTraceContaining("brokerRetentionHours");
                });
    }

    @Test
    @DisplayName("a marker horizon at exactly the margin starts")
    void aMarkerHorizonAtExactlyTheMarginStarts() {
        shipped.withPropertyValues("carddemo.processed-event.marker-retention-hours=336")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AccountProperties.class).processedEvent()
                            .markerRetentionHours()).isEqualTo(336L);
                });
    }

    @Test
    @DisplayName("raising broker retention without raising the marker horizon stops start-up")
    void raisingBrokerRetentionAloneStopsStartUp() {
        shipped.withPropertyValues("carddemo.processed-event.broker-retention-hours=720")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("markerRetentionHours must be at")
                            .hasStackTraceContaining("least 2 times");
                });
    }

    @Test
    @DisplayName("the bound record is the only properties bean and it is immutable")
    void theBoundRecordIsTheOnlyPropertiesBean() {
        shipped.run(context -> {
            assertThat(context).hasSingleBean(AccountProperties.class);
            assertThat(AccountProperties.class.isRecord())
                    .withFailMessage("AccountProperties must stay a record, so no component can change a "
                            + "value the constraints already accepted")
                    .isTrue();
        });
    }

    /**
     * Holds the producer window inside the relay pass that issued the send.
     *
     * <p>{@code outbox/OutboxRelay} bounds a whole pass with
     * {@code carddemo.outbox.relay.max-duration-ms} and stops watching a send when that bound
     * lapses. Stopping the watch does not recall a record already on the wire, so if the producer
     * may keep delivering after the pass ended, the row is swept again and the same event reaches
     * the topic twice. The remedy is arithmetic rather than code: the broker has to give up first.
     *
     * <p>Kafka also refuses a producer whose {@code delivery.timeout.ms} is below
     * {@code linger.ms} plus {@code request.timeout.ms}, at construction, so both relationships are
     * asserted here rather than discovered at start-up.
     */
    @Test
    @DisplayName("one send resolves inside the relay pass that issued it")
    void oneSendResolvesInsideTheRelayPassThatIssuedIt() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            AccountProperties properties = context.getBean(AccountProperties.class);
            long maxBlock = milliseconds(context.getEnvironment()
                    .getProperty("spring.kafka.producer.properties.max.block.ms"));
            long deliveryTimeout = milliseconds(context.getEnvironment()
                    .getProperty("spring.kafka.producer.properties.delivery.timeout.ms"));
            long requestTimeout = milliseconds(context.getEnvironment()
                    .getProperty("spring.kafka.producer.properties.request.timeout.ms"));
            long linger = milliseconds(context.getEnvironment()
                    .getProperty("spring.kafka.producer.properties.linger.ms"));
            long passBudget = properties.outbox().relay().maxDurationMs();

            assertThat(maxBlock + deliveryTimeout)
                    .as("max.block.ms plus delivery.timeout.ms against "
                            + "carddemo.outbox.relay.max-duration-ms, which is %d. A send the "
                            + "relay stops watching can still be delivered, and the next pass then "
                            + "publishes a second copy of the same event", passBudget)
                    .isLessThan(passBudget);
            assertThat(deliveryTimeout)
                    .as("delivery.timeout.ms against linger.ms plus request.timeout.ms, which is "
                            + "%d. The producer refuses that combination at construction",
                            linger + requestTimeout)
                    .isGreaterThanOrEqualTo(linger + requestTimeout);
        });
    }

    /**
     * Reads one producer timing value the pass budget depends on.
     *
     * @param value the configured value, in milliseconds
     * @return that value as a number
     */
    private static long milliseconds(String value) {
        assertThat(value).as("a producer timing key the pass budget depends on").isNotNull();
        return Long.parseLong(value.trim());
    }
}
