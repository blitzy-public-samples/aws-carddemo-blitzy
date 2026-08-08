package com.carddemo.card.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the shipped {@code application.yml} into {@link CardProperties} and reads the result back.
 *
 * <p>This class has no COBOL ancestor. Each test builds a context holding one properties
 * bean and no auto-configuration, so no database and no message broker has to run.
 *
 * <p>The first test is the reachability check: a key that no component reads binds to nothing, and
 * this test fails when a key is renamed on one side only. The remaining tests are the fail-fast
 * checks: an invalid value stops the context rather than reaching the broker. Each of those asserts
 * the component path Spring reports, which is the camel-case form of the key.
 */
@DisplayName("CardProperties, the bound carddemo block of the card service")
class CardPropertiesTest {

    /** Registers the properties bean and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CardProperties.class)
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
            CardProperties properties = context.getBean(CardProperties.class);

            assertThat(properties.api().maxRequestBodyBytes()).isEqualTo(65536L);
            assertThat(properties.kafka().topics().cardUpdated()).isEqualTo("card.updated");
            assertThat(properties.kafka().topics().deadLetter()).isEqualTo("carddemo.dead-letter");
            assertThat(properties.outbox().relay().fixedDelayMs()).isEqualTo(500L);
            assertThat(properties.outbox().relay().batchSize()).isEqualTo(100);
            assertThat(properties.outbox().publishedRetentionHours()).isEqualTo(168L);
            assertThat(properties.processedEvent().markerRetentionHours())
                    .as("the marker horizon outlasts broker retention rather than equalling it")
                    .isEqualTo(720L);
            assertThat(properties.processedEvent().brokerRetentionHours()).isEqualTo(168L);
            assertThat(properties.retention().sweepIntervalMs()).isEqualTo(3_600_000L);
            assertThat(properties.outbox().relay().maxDurationMs()).isEqualTo(5_000L);
        });
    }

    @Test
    @DisplayName("a blank topic name stops start-up")
    void aBlankTopicNameStopsStartUp() {
        shipped.withPropertyValues("carddemo.kafka.topics.card-updated=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("kafka.topics.cardUpdated");
                });
    }

    @Test
    @DisplayName("a blank dead-letter topic name stops start-up")
    void aBlankDeadLetterTopicNameStopsStartUp() {
        shipped.withPropertyValues("carddemo.kafka.topics.dead-letter=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("kafka.topics.deadLetter");
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
    @DisplayName("a relay batch size of zero stops start-up")
    void aZeroRelayBatchSizeStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.batch-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("outbox.relay.batchSize");
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
                    assertThat(context.getBean(CardProperties.class).processedEvent()
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
            assertThat(context).hasSingleBean(CardProperties.class);
            assertThat(CardProperties.class.isRecord())
                    .withFailMessage("CardProperties must stay a record, so no component can change a "
                            + "value the constraints already accepted")
                    .isTrue();
        });
    }

    /**
     * Asserts one producer send resolves inside the sweep that issued it.
     *
     * <p>Four values decide that, and a change to any one of them can break it silently. The relay
     * awaits each send against one deadline shared by the whole sweep; the producer closes a send
     * after {@code max.block.ms + delivery.timeout.ms}. While the producer window was the larger
     * of the two, two minutes against a ten-second wait, the relay could stop waiting on a send
     * the broker went on to deliver, and the next sweep then published a second copy of the same
     * event.
     * A duplicate is survivable, because every consumer records the event identifiers it has
     * processed, but it is avoidable here and the relay state that came with it was simply wrong.
     *
     * <p>The second assertion is a construction-time rule of the producer rather than a choice of
     * this platform: a delivery timeout below {@code linger.ms + request.timeout.ms} is refused
     * outright, so a start-up failure is the alternative to this test.
     */
    @Test
    @DisplayName("one producer send resolves inside the sweep that issued it")
    void oneSendResolvesInsideTheSweepThatIssuedIt() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            CardProperties properties = context.getBean(CardProperties.class);
            long maxBlock = milliseconds(context.getEnvironment()
                    .getProperty("spring.kafka.producer.properties.max.block.ms"));
            long deliveryTimeout = milliseconds(context.getEnvironment()
                    .getProperty("spring.kafka.producer.properties.delivery.timeout.ms"));
            long requestTimeout = milliseconds(context.getEnvironment()
                    .getProperty("spring.kafka.producer.properties.request.timeout.ms"));
            long linger = milliseconds(context.getEnvironment()
                    .getProperty("spring.kafka.producer.properties.linger.ms"));
            long sweepBudget = properties.outbox().relay().maxDurationMs();

            assertThat(maxBlock + deliveryTimeout)
                    .as("max.block.ms plus delivery.timeout.ms against "
                            + "carddemo.outbox.relay.max-duration-ms, which is %d. A send the "
                            + "relay abandons can still be delivered, and the next sweep then "
                            + "publishes a second copy of the same event", sweepBudget)
                    .isLessThan(sweepBudget);
            assertThat(deliveryTimeout)
                    .as("delivery.timeout.ms against linger.ms plus request.timeout.ms, which is "
                            + "%d. The producer refuses that combination at construction",
                            linger + requestTimeout)
                    .isGreaterThanOrEqualTo(linger + requestTimeout);
        });
    }

    /**
     * Asserts a sweep deadline above the ceiling stops start-up.
     *
     * <p>The ceiling exists so a misconfiguration cannot turn the bound off. A sweep permitted to
     * spend five minutes waiting for acknowledgements is a stalled relay holding the one thread
     * every
     * later card event waits behind.
     */
    @Test
    @DisplayName("a sweep deadline above the five-minute ceiling stops start-up")
    void aSweepDeadlineAboveTheCeilingStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.max-duration-ms="
                        + (CardProperties.MAX_PASS_DURATION_MS + 1L))
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * Asserts a sweep deadline of zero stops start-up.
     *
     * <p>Zero would abandon every send before it was issued, so no event would ever be published
     * and
     * every row would be retried for ever.
     */
    @Test
    @DisplayName("a sweep deadline of zero stops start-up")
    void aZeroSweepDeadlineStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.max-duration-ms=0")
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * Reads one millisecond setting, failing rather than defaulting when the key is absent.
     *
     * @param value the configured text
     * @return the value in milliseconds
     */
    private static long milliseconds(String value) {
        assertThat(value).as("a producer timing key the sweep budget depends on").isNotNull();
        return Long.parseLong(value.trim());
    }
}
