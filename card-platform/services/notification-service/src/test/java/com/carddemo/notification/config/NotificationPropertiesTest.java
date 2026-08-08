package com.carddemo.notification.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the shipped {@code application.yml} into {@link NotificationProperties} and reads the result back.
 *
 * <p>This class has no COBOL ancestor. Each test builds a context holding one properties
 * bean and no auto-configuration, so no database and no message broker has to run.
 *
 * <p>The first test is the reachability check: a key that no component reads binds to nothing, and
 * this test fails when a key is renamed on one side only. The remaining tests are the fail-fast
 * checks: an invalid value stops the context rather than reaching the broker. Each of those asserts
 * the component path Spring reports, which is the camel-case form of the key.
 */
@DisplayName("NotificationProperties, the bound carddemo block of the notification service")
class NotificationPropertiesTest {

    /** Registers the properties bean and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationProperties.class)
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
            NotificationProperties properties = context.getBean(NotificationProperties.class);

            assertThat(properties.kafka().groups().transactionPosted())
                    .isEqualTo("notification-posted");
            assertThat(properties.kafka().groups().fraudAssessed()).isEqualTo("notification-fraud");
            assertThat(properties.kafka().topics().transactionPosted())
                    .isEqualTo("transaction.posted");
            assertThat(properties.kafka().topics().fraudAssessed()).isEqualTo("fraud.assessed");
            assertThat(properties.kafka().topics().deadLetter())
                    .isEqualTo("carddemo.dead-letter");
            assertThat(properties.kafka().topics().deadLetterSuffix()).isEqualTo(".DLT");
            assertThat(properties.consumer().retry().maxAttempts()).isEqualTo(3);
            assertThat(properties.consumer().retry().backoffMs()).isEqualTo(1000L);
            assertThat(properties.history().statementRetentionDays()).isEqualTo(400);
            assertThat(properties.history().logRetentionDays()).isEqualTo(90);
            assertThat(properties.history().sweepIntervalMs()).isEqualTo(3_600_000L);
            assertThat(properties.processedEvent().markerRetentionHours())
                    .as("the marker horizon outlasts broker retention rather than equalling it")
                    .isEqualTo(720);
            assertThat(properties.processedEvent().brokerRetentionHours()).isEqualTo(168);
        });
    }

    @Test
    @DisplayName("a blank consumer group stops start-up")
    void aBlankConsumerGroupStopsStartUp() {
        shipped.withPropertyValues("carddemo.kafka.groups.transaction-posted=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("kafka.groups.transactionPosted");
                });
    }

    @Test
    @DisplayName("a blank dead-letter topic stops start-up")
    void aBlankDeadLetterTopicStopsStartUp() {
        shipped.withPropertyValues("carddemo.kafka.topics.dead-letter=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("kafka.topics.deadLetter");
                });
    }

    @Test
    @DisplayName("a blank dead-letter suffix stops start-up")
    void aBlankDeadLetterSuffixStopsStartUp() {
        shipped.withPropertyValues("carddemo.kafka.topics.dead-letter-suffix=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("kafka.topics.deadLetterSuffix");
                });
    }

    @Test
    @DisplayName("a retry count under one stops start-up")
    void aRetryCountUnderOneStopsStartUp() {
        shipped.withPropertyValues("carddemo.consumer.retry.max-attempts=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("consumer.retry.maxAttempts");
                });
    }

    @Test
    @DisplayName("a statement retention under one stops start-up")
    void aStatementRetentionUnderOneStopsStartUp() {
        shipped.withPropertyValues("carddemo.history.statement-retention-days=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("history.statementRetentionDays");
                });
    }

    @Test
    @DisplayName("a marker retention under one stops start-up")
    void aMarkerRetentionUnderOneStopsStartUp() {
        shipped.withPropertyValues("carddemo.processed-event.marker-retention-hours=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("processedEvent.markerRetentionHours");
                });
    }

    @Test
    @DisplayName("the history record carries three horizons and no page size")
    void theHistoryRecordCarriesThreeHorizonsAndNoPageSize() {
        NotificationProperties.History history =
                new NotificationProperties.History(400, 90, 3_600_000L);

        assertThat(history.statementRetentionDays()).isEqualTo(400);
        assertThat(history.logRetentionDays()).isEqualTo(90);
        assertThat(history.sweepIntervalMs()).isEqualTo(3_600_000L);
        assertThat(NotificationProperties.History.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("statementRetentionDays", "logRetentionDays", "sweepIntervalMs");
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
                    assertThat(context.getBean(NotificationProperties.class).processedEvent()
                            .markerRetentionHours()).isEqualTo(336);
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
            assertThat(context).hasSingleBean(NotificationProperties.class);
            assertThat(NotificationProperties.class.isRecord())
                    .withFailMessage("NotificationProperties must stay a record, so no component can change a "
                            + "value the constraints already accepted")
                    .isTrue();
        });
    }
}
