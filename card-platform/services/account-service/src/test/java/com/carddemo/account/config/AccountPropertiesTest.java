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
}
