package com.carddemo.fraud.config;

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
 * Binds the shipped {@code application.yml} into {@link FraudProperties} and reads the result back.
 *
 * <p>This class has no COBOL ancestor. Each test builds a context holding one properties
 * bean and no auto-configuration, so no database and no message broker has to run.
 *
 * <p>The first test is the reachability check: a key that no component reads binds to nothing, and
 * this test fails when a key is renamed on one side only. The remaining tests are the fail-fast
 * checks: an invalid value stops the context rather than reaching the broker. Each of those asserts
 * the component path Spring reports, which is the camel-case form of the key.
 */
@DisplayName("FraudProperties, the bound carddemo block of the fraud detection service")
class FraudPropertiesTest {

    /** Registers the properties bean and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FraudProperties.class)
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
            FraudProperties properties = context.getBean(FraudProperties.class);

            assertThat(properties.kafka().topics().transactionAuthorized())
                    .isEqualTo("transaction.authorized");
            assertThat(properties.kafka().topics().fraudAssessed()).isEqualTo("fraud.assessed");
            assertThat(properties.kafka().topics().deadLetter())
                    .isEqualTo("carddemo.dead-letter");
            assertThat(properties.kafka().topics().deadLetterSuffix()).isEqualTo(".DLT");
            assertThat(properties.consumer().retry().maxAttempts()).isEqualTo(3);
            assertThat(properties.consumer().retry().backoffMs()).isEqualTo(1000L);
            assertThat(properties.outbox().relay().fixedDelayMs()).isEqualTo(500L);
            assertThat(properties.outbox().relay().batchSize()).isEqualTo(100);
            assertThat(properties.outbox().relay().maxDurationMs()).isEqualTo(5000L);
            assertThat(properties.outbox().relay().claimTimeout())
                    .as("the claim horizon every relay on this platform ships, and the value "
                            + "card-platform/.env.example, the Compose file and the Kubernetes "
                            + "ConfigMap all carry. This service shipped PT30S, so a run that set "
                            + "no variable recovered a claim four times sooner here")
                    .isEqualTo(Duration.ofMinutes(2L));
            assertThat(properties.fraud().risk().flagThreshold()).isEqualTo(50);
            assertThat(properties.fraud().risk().velocityWindowMinutes()).isEqualTo(60);
            assertThat(properties.fraud().risk().velocityCountThreshold()).isEqualTo(5);
            assertThat(properties.fraud().risk().amountAnomalyThreshold())
                    .isEqualTo(new java.math.BigDecimal("500.00"));
            assertThat(properties.retention().sweepIntervalMs()).isEqualTo(3_600_000L);
            assertThat(properties.retention().assessmentRetentionDays())
                    .as("the horizon COMMENT ON TABLE fraud_assessment declares")
                    .isEqualTo(90);
            assertThat(properties.retention().velocityRetentionDays())
                    .as("the horizon COMMENT ON TABLE velocity_window declares")
                    .isEqualTo(7);
        });
    }

    @Test
    @DisplayName("an assessment horizon of zero stops start-up")
    void anAssessmentHorizonOfZeroStopsStartUp() {
        shipped.withPropertyValues("carddemo.retention.assessment-retention-days=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("assessmentRetentionDays");
                });
    }

    /**
     * Asserts a velocity horizon of zero stops start-up, and names which guard refuses it.
     *
     * <p>Two guards cover this value and they do not fire in the same order. {@code @Positive} on the
     * component would name {@code velocityRetentionDays}, but bean validation runs after the record
     * is instantiated, and the cross-record guard in the {@code FraudProperties} constructor runs
     * during instantiation. Zero fails that guard first, so the refusal an operator reads names the
     * two configuration keys rather than a component of a record. Asserting the earlier message is
     * what keeps this test measuring the behaviour instead of the annotation.
     */
    @Test
    @DisplayName("a velocity horizon of zero stops start-up")
    void aVelocityHorizonOfZeroStopsStartUp() {
        shipped.withPropertyValues("carddemo.retention.velocity-retention-days=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("carddemo.retention.velocity-retention-days")
                            .hasStackTraceContaining(
                                    "carddemo.fraud.risk.velocity-window-minutes");
                });
    }

    @Test
    @DisplayName("a risk threshold above one hundred stops start-up")
    void aRiskThresholdAboveOneHundredStopsStartUp() {
        shipped.withPropertyValues("carddemo.fraud.risk.flag-threshold=101")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("fraud.risk.flagThreshold");
                });
    }

    /**
     * A threshold of zero would flag a transaction no rule objected to. The published contract
     * requires a flagged event to name at least one rule and {@code fraud_assessment} refuses a
     * flagged row that names none, so zero produces an event and a row that both fail further down.
     * Refusing it here names the property instead.
     */
    @Test
    @DisplayName("a risk threshold of zero stops start-up")
    void aRiskThresholdOfZeroStopsStartUp() {
        shipped.withPropertyValues("carddemo.fraud.risk.flag-threshold=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("fraud.risk.flagThreshold");
                });
    }

    @Test
    @DisplayName("a risk threshold of one is accepted, since one rule alone can then flag")
    void aRiskThresholdOfOneIsAccepted() {
        shipped.withPropertyValues("carddemo.fraud.risk.flag-threshold=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FraudProperties.class).fraud().risk().flagThreshold())
                            .isEqualTo(1);
                });
    }

    @Test
    @DisplayName("a risk threshold of zero stops start-up")
    void aZeroRiskThresholdStopsStartUp() {
        shipped.withPropertyValues("carddemo.fraud.risk.flag-threshold=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("fraud.risk.flagThreshold");
                });
    }

    @Test
    @DisplayName("a velocity window of zero minutes stops start-up")
    void aZeroVelocityWindowStopsStartUp() {
        shipped.withPropertyValues("carddemo.fraud.risk.velocity-window-minutes=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("fraud.risk.velocityWindowMinutes");
                });
    }

    @Test
    @DisplayName("a negative amount threshold stops start-up")
    void aNegativeAmountThresholdStopsStartUp() {
        shipped.withPropertyValues("carddemo.fraud.risk.amount-anomaly-threshold=-0.01")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("fraud.risk.amountAnomalyThreshold");
                });
    }

    @Test
    @DisplayName("a blank assessment topic name stops start-up")
    void aBlankAssessmentTopicStopsStartUp() {
        shipped.withPropertyValues("carddemo.kafka.topics.fraud-assessed=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("kafka.topics.fraudAssessed");
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
    @DisplayName("an outbox pass duration below one millisecond stops start-up")
    void aZeroOutboxPassDurationStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.max-duration-ms=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("outbox.relay.maxDurationMs");
                });
    }

    @Test
    @DisplayName("an outbox pass duration above five minutes stops start-up")
    void anExcessiveOutboxPassDurationStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.max-duration-ms=300001")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("outbox.relay.maxDurationMs");
                });
    }


    /**
     * Asserts a claim timeout that cannot protect an active claim stops start-up.
     *
     * <p>{@code @NotNull} caught an absent value and nothing else here, so zero and a negative
     * duration both bound cleanly and every claim was stranded the moment it was taken: the next
     * sweep recovered the batch this sweep was still publishing, which is the double publish the
     * claim exists to prevent. The other four relays refused it, so one variable set to zero
     * switched the guarantee off in this service alone and nothing said so.
     */
    @Test
    @DisplayName("a relay claim timeout of zero stops start-up")
    void aNonPositiveRelayClaimTimeoutStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.claim-timeout=PT0S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("claimTimeout");
                });
        shipped.withPropertyValues("carddemo.outbox.relay.claim-timeout=PT-1M")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("claimTimeout");
                });
    }

    /**
     * A duplicate-delivery claim carries no configurable horizon, and this asserts the absence.
     *
     * <p>A claim is permanent, so this record carries no {@code processedEvent} component and no
     * nested {@code ProcessedEvent} type. A marker horizon of 720 hours would remove a claim while
     * the effects it guards outlive it: a risk assessment kept for ninety days and the velocity
     * buckets it counted into.
     *
     * <p>The withdrawn key is set here as well. It binds nothing, which is what makes the
     * withdrawal a property of the code rather than of the shipped configuration file.
     */
    @Test
    @DisplayName("no configured value can expire a duplicate-delivery claim")
    void noConfiguredValueCanExpireADuplicateDeliveryClaim() {
        assertThat(Arrays.stream(FraudProperties.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("a component here would be a horizon a deployment could shorten")
                .doesNotContain("processedEvent");
        assertThat(Arrays.stream(FraudProperties.class.getDeclaredClasses())
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
            assertThat(context).hasSingleBean(FraudProperties.class);
            assertThat(FraudProperties.class.isRecord())
                    .withFailMessage("FraudProperties must stay a record, so no component can change a "
                            + "value the constraints already accepted")
                    .isTrue();
        });
    }
}
