package com.carddemo.ledger.config;

import java.time.Duration;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds the shipped {@code application.yml} into {@link LedgerProperties} and reads the result back.
 *
 * <p>This class has no COBOL ancestor. Each test builds a context holding one properties
 * bean and no auto-configuration, so no database and no message broker has to run.
 *
 * <p>The first test is the reachability check: a key that no component reads binds to nothing, and
 * this test fails when a key is renamed on one side only. The remaining tests are the fail-fast
 * checks: an invalid value stops the context rather than reaching the broker. Each of those asserts
 * the component path Spring reports, which is the camel-case form of the key.
 */
@DisplayName("LedgerProperties, the bound carddemo block of the ledger posting service")
class LedgerPropertiesTest {

    /** Registers the properties bean and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LedgerProperties.class)
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
            LedgerProperties properties = context.getBean(LedgerProperties.class);

            assertThat(properties.kafka().topics().transactionAuthorized())
                    .isEqualTo("transaction.authorized");
            assertThat(properties.kafka().topics().transactionPosted())
                    .isEqualTo("transaction.posted");
            assertThat(properties.kafka().topics().transactionDeclined())
                    .isEqualTo("transaction.declined");
            assertThat(properties.kafka().topics().accountStateChanged())
                    .isEqualTo("account.state-changed");
            assertThat(properties.kafka().topics().deadLetter())
                    .isEqualTo("carddemo.dead-letter");
            assertThat(properties.kafka().topics().deadLetterSuffix()).isEqualTo(".DLT");
            assertThat(properties.consumer().retry().maxAttempts()).isEqualTo(3);
            assertThat(properties.consumer().retry().backoffMs()).isEqualTo(1000L);
            assertThat(properties.outbox().relay().fixedDelayMs()).isEqualTo(500L);
            assertThat(properties.outbox().relay().batchSize()).isEqualTo(100);
            assertThat(properties.outbox().relay().instanceId()).isNotBlank();
            assertThat(properties.outbox().relay().claimTimeout())
                    .isEqualTo(java.time.Duration.ofMinutes(2L));
            assertThat(properties.outbox().relay().maxDurationMs()).isEqualTo(5_000L);
            assertThat(properties.outbox().publishedRetentionHours()).isEqualTo(168L);
            assertThat(properties.retention().sweepIntervalMs()).isEqualTo(3_600_000L);
            assertThat(properties.retention().rejectedTransactionRetentionDays())
                    .as("the horizon COMMENT ON TABLE rejected_transaction declares")
                    .isEqualTo(90);
        });
    }

    /**
     * Asserts one broker acknowledgement resolves inside the relay pass that issued it.
     *
     * <p>{@code outbox/OutboxRelay} bounds each pass with
     * {@code carddemo.outbox.relay.max-duration-ms}. A send the pass abandoned can still be delivered
     * by the producer afterwards, and the retry the same pass scheduled then publishes a second copy
     * of one event. The producer takes at most {@code max.block.ms} plus
     * {@code delivery.timeout.ms} to resolve one send, so that sum has to stay below the pass budget.
     *
     * <p>The second relationship is one the producer enforces itself: it refuses to construct when
     * {@code delivery.timeout.ms} is below {@code linger.ms} plus {@code request.timeout.ms}. Failing
     * here rather than at start-up names the two keys that disagree.
     */
    @Test
    @DisplayName("one send resolves inside the relay pass that issued it")
    void oneSendResolvesInsideTheRelayPassThatIssuedIt() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            LedgerProperties properties = context.getBean(LedgerProperties.class);
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
                            + "carddemo.outbox.relay.max-duration-ms, which is %d. A send the relay "
                            + "abandons can still be delivered, and the next pass then publishes a "
                            + "second copy of the same event", passBudget)
                    .isLessThan(passBudget);
            assertThat(deliveryTimeout)
                    .as("delivery.timeout.ms against linger.ms plus request.timeout.ms, which is "
                            + "%d. The producer refuses that combination at construction",
                            linger + requestTimeout)
                    .isGreaterThanOrEqualTo(linger + requestTimeout);
        });
    }

    /**
     * Reads one producer timing key as a whole number of milliseconds.
     *
     * @param value the resolved property value
     * @return the value in milliseconds
     */
    private static long milliseconds(String value) {
        assertThat(value).as("a producer timing key the relay budget depends on").isNotNull();
        return Long.parseLong(value.trim());
    }

    @Test
    @DisplayName("a relay pass budget past the five-minute ceiling stops start-up")
    void aRelayPassBudgetPastTheCeilingStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.max-duration-ms=300001")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("outbox.relay.maxDurationMs");
                });
    }

    @Test
    @DisplayName("a relay pass budget of zero stops start-up")
    void aRelayPassBudgetOfZeroStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.max-duration-ms=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("outbox.relay.maxDurationMs");
                });
    }

    @Test
    @DisplayName("a blank account state-changed topic name stops start-up")
    void aBlankAccountStateChangedTopicStopsStartUp() {
        shipped.withPropertyValues("carddemo.kafka.topics.account-state-changed=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("kafka.topics.accountStateChanged");
                });
    }

    @Test
    @DisplayName("a reject horizon of zero stops start-up")
    void aRejectHorizonOfZeroStopsStartUp() {
        shipped.withPropertyValues("carddemo.retention.rejected-transaction-retention-days=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("rejectedTransactionRetentionDays");
                });
    }

    @Test
    @DisplayName("a blank dead-letter topic name stops start-up")
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
    @DisplayName("a negative retry backoff stops start-up")
    void aNegativeRetryBackoffStopsStartUp() {
        shipped.withPropertyValues("carddemo.consumer.retry.backoff-ms=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("consumer.retry.backoffMs");
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
    @DisplayName("a relay claim timeout of zero stops start-up")
    void aZeroRelayClaimTimeoutStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.claim-timeout=PT0S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("claimTimeout");
                });
    }

    /**
     * A duplicate-delivery claim carries no configurable horizon, and this asserts the absence.
     *
     * <p>A security review found the marker horizon of 720 hours removing a claim while the effects
     * it guards outlive it: a posted transaction, a category balance and a balance projection,
     * none of which expires. A claim is now permanent, so this record carries no {@code
     * processedEvent} component and no nested {@code ProcessedEvent} type.
     *
     * <p>The withdrawn key is set here as well. It binds nothing, which is what makes the
     * withdrawal a property of the code rather than of the shipped configuration file.
     */
    @Test
    @DisplayName("no configured value can expire a duplicate-delivery claim")
    void noConfiguredValueCanExpireADuplicateDeliveryClaim() {
        assertThat(Arrays.stream(LedgerProperties.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("a component here would be a horizon a deployment could shorten")
                .doesNotContain("processedEvent");
        assertThat(Arrays.stream(LedgerProperties.class.getDeclaredClasses())
                .map(Class::getSimpleName))
                .doesNotContain("ProcessedEvent");

        shipped.withPropertyValues("carddemo.processed-event.marker-retention-hours=1")
                .run(context -> assertThat(context)
                        .as("the withdrawn key binds nothing and cannot reintroduce a horizon")
                        .hasNotFailed());
    }

    @Test
    @DisplayName("the bound record is the only properties bean and it is immutable")
    void theBoundRecordIsTheOnlyPropertiesBean() {
        shipped.run(context -> {
            assertThat(context).hasSingleBean(LedgerProperties.class);
            assertThat(LedgerProperties.class.isRecord())
                    .withFailMessage("LedgerProperties must stay a record, so no component can change a "
                            + "value the constraints already accepted")
                    .isTrue();
        });
    }
}
