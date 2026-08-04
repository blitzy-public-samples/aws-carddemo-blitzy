package com.carddemo.authorization.config;

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
 * <p>ADDITIVE. This class has no COBOL ancestor. Each test builds a context holding one properties
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
            assertThat(properties.outbox().relay().fixedDelayMs()).isEqualTo(500L);
            assertThat(properties.outbox().relay().batchSize()).isEqualTo(100);
            assertThat(properties.services().account().baseUrl())
                    .isEqualTo("http://account-service:8080");
            assertThat(properties.services().card().baseUrl())
                    .isEqualTo("http://card-service:8080");
        });
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
    @DisplayName("a supporting-service address with no scheme stops start-up")
    void aSupportingServiceAddressWithNoSchemeStopsStartUp() {
        shipped.withPropertyValues("carddemo.services.account.base-url=account-service:8080")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("services.account.baseUrl");
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
