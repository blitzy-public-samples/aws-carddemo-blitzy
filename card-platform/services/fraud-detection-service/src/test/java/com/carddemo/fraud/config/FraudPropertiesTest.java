package com.carddemo.fraud.config;

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
 * <p>ADDITIVE. This class has no COBOL ancestor. Each test builds a context holding one properties
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
            assertThat(properties.consumer().retry().maxAttempts()).isEqualTo(3);
            assertThat(properties.consumer().retry().backoffMs()).isEqualTo(1000L);
            assertThat(properties.outbox().relay().fixedDelayMs()).isEqualTo(500L);
            assertThat(properties.outbox().relay().batchSize()).isEqualTo(100);
            assertThat(properties.fraud().risk().flagThreshold()).isEqualTo(50);
            assertThat(properties.fraud().risk().velocityWindowMinutes()).isEqualTo(60);
            assertThat(properties.fraud().risk().velocityCountThreshold()).isEqualTo(5);
            assertThat(properties.fraud().risk().amountAnomalyThreshold())
                    .isEqualTo(new java.math.BigDecimal("500.00"));
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
