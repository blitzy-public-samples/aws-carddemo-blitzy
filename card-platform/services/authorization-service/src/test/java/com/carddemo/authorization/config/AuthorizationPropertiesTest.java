package com.carddemo.authorization.config;

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
            assertThat(properties.retention().sweepIntervalMs()).isEqualTo(3_600_000L);
            assertThat(properties.replica().lagCeiling())
                    .as("a replica stream is authorized against only while it is fully caught up")
                    .isZero();
            assertThat(properties.outbox().relay().maxDurationMs()).isEqualTo(5_000L);
            assertThat(properties.outbox().relay().publishTimeout())
                    .as("the send bound the publisher is built with, not a second binding of it")
                    .isEqualTo(Duration.ofSeconds(10L));
        });
    }

    /**
     * Asserts one producer send resolves inside the relay pass that issued it.
     *
     * <p>Four values decide that, and a change to any one of them can break it silently. The relay
     * awaits each send against one deadline shared by the whole pass; the producer closes a send after
     * {@code max.block.ms + delivery.timeout.ms}. While the producer window was the larger of the two,
     * the relay could stop waiting on a send the broker went on to deliver, and the next pass then
     * published a second copy of the same event. A duplicate is survivable — every consumer records
     * the event identifiers it has processed — but it is avoidable here, and the accompanying relay
     * state was simply wrong.
     *
     * <p>The second assertion is a construction-time rule of the producer rather than a choice of
     * this platform: a delivery timeout below {@code linger.ms + request.timeout.ms} is refused
     * outright, so a start-up failure is the alternative to this test.
     */
    @Test
    @DisplayName("one producer send resolves inside the relay pass that issued it")
    void oneSendResolvesInsideTheRelayPassThatIssuedIt() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            AuthorizationProperties properties = context.getBean(AuthorizationProperties.class);
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
     * Reads one millisecond setting, failing rather than defaulting when the key is absent.
     *
     * @param value the configured text
     * @return the value in milliseconds
     */
    private static long milliseconds(String value) {
        assertThat(value).as("a producer timing key the relay budget depends on").isNotNull();
        return Long.parseLong(value.trim());
    }

    /**
     * Asserts this record binds no supporting-service address, because the service reads no other
     * service.
     *
     * <p>A configured supporting-service address with no reader is worse than an absent one: it tells
     * a reader the call exists.
     */
    @Test
    @DisplayName("binds no supporting-service address, because no such call is made")
    void bindsNoSupportingServiceAddress() {
        assertThat(AuthorizationProperties.class.getRecordComponents())
                .as("the components of the bound carddemo block")
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder(
                        "kafka", "outbox", "retention", "replica", "decision");
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
     * Asserts a publish timeout that gives a send no time at all stops start-up.
     *
     * <p>This value used to reach the publisher through a constructor {@code @Value}, a second binding
     * of the same property that met no constraint declared on the record. Only {@code null} was
     * refused, so zero and a negative duration both started the service and then failed every send the
     * moment it was issued: each event stayed unpublished, each sweep claimed the same rows again, and
     * the only evidence was one timeout per attempt. It is a component of the bound record now, and the
     * publisher is built from that record, so a refused value is a start-up failure naming the
     * property.
     */
    @Test
    @DisplayName("a relay publish timeout of zero stops start-up")
    void aNonPositiveRelayPublishTimeoutStopsStartUp() {
        shipped.withPropertyValues("carddemo.outbox.relay.publish-timeout=PT0S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("publishTimeout");
                });
        shipped.withPropertyValues("carddemo.outbox.relay.publish-timeout=PT-5S")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("publishTimeout");
                });
    }

    /**
     * Asserts a negative replica lag ceiling stops start-up.
     *
     * <p>A ceiling counts records waiting, so a negative one describes nothing and would make the
     * comparison always true, silently authorizing against a stream with any backlog at all. Failing
     * at start-up names the property; failing at the first request would look like a broken replica.
     *
     * <p>Zero is valid and is the shipped value: it requires a consumer that is fully caught up.
     */
    @Test
    @DisplayName("a negative replica lag ceiling stops start-up")
    void aNegativeReplicaLagCeilingStopsStartUp() {
        shipped.withPropertyValues("carddemo.replica.lag-ceiling=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("lagCeiling");
                });
    }

    /**
     * A duplicate-delivery claim carries no configurable horizon, and this asserts the absence.
     *
     * <p>A claim is permanent, so this record carries no {@code processedEvent} component and no
     * nested {@code ProcessedEvent} type. Any horizon would remove a claim while the effects it guards
     * stand: a decision row kept for audit, and two replica tables kept for as long as the service
     * runs.
     *
     * <p>The withdrawn key is set here as well. It binds nothing, which is what makes the
     * withdrawal a property of the code rather than of the shipped configuration file.
     */
    @Test
    @DisplayName("no configured value can expire a duplicate-delivery claim")
    void noConfiguredValueCanExpireADuplicateDeliveryClaim() {
        assertThat(Arrays.stream(AuthorizationProperties.class.getRecordComponents())
                .map(RecordComponent::getName))
                .as("a component here would be a horizon a deployment could shorten")
                .doesNotContain("processedEvent");
        assertThat(Arrays.stream(AuthorizationProperties.class.getDeclaredClasses())
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
            assertThat(context).hasSingleBean(AuthorizationProperties.class);
            assertThat(AuthorizationProperties.class.isRecord())
                    .withFailMessage("AuthorizationProperties must stay a record, so no component "
                            + "can change a value the constraints already accepted")
                    .isTrue();
        });
    }
}
