package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.messaging.EventPublisherPort;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers the publisher {@code config/KafkaProducerConfig} builds: what it checks before a send, what
 * bounds the send, and what it does not count.
 *
 * <p>No COBOL ancestor. The nearest source construct is the one asynchronous handoff of the whole
 * CardDemo source, {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at
 * {@code app/cbl/CORPT00C.cbl:L517-L518}. That write returns as soon as the queue accepts the record
 * and the program never learns whether the job ran.
 *
 * <p>Two properties are held here.
 *
 * <p><b>The configured wait bounds the send that is actually used.</b>
 * {@code carddemo.outbox.relay.publish-timeout} named a bound that only an unused private method
 * applied, so the active path had no per-send bound at all and the property read as a setting with
 * no effect. It now bounds the stage every caller receives.
 *
 * <p><b>The publisher counts nothing.</b> A failed publish was counted here and counted again by the
 * relay's sweep result, so {@code carddemo.account.publish.failed} reported one refusal twice. The
 * relay is the single owner, because it alone sees a stored event type with no configured topic, an
 * expired sweep deadline and a claim recovered from an instance that died mid-attempt.
 */
@DisplayName("The account event publisher: its checks, its bound, and what it does not count")
class KafkaEventPublisherTest {

    /** Destination of an account state event, as the shipped file names it. */
    private static final String STATE_TOPIC = "account.state-changed";

    /** A topic no account event belongs on. */
    private static final String FOREIGN_TOPIC = "transaction.authorized";

    /** Account identifier every event here carries, and every message key. */
    private static final String ACCOUNT_ID = "00000000001";

    /** A per-send bound short enough to elapse inside a test and long enough not to race it. */
    private static final Duration SHORT_BOUND = Duration.ofMillis(150L);

    /** How long a test waits on a stage the bound should already have failed. */
    private static final Duration PATIENCE = Duration.ofSeconds(5L);

    @Test
    @DisplayName("a send the broker never acknowledges fails on the configured bound")
    void aSendTheBrokerNeverAcknowledgesFailsOnTheConfiguredBound() throws Exception {
        KafkaTemplate<String, String> template = neverAcknowledging();
        EventPublisherPort publisher = publisher(template, SHORT_BOUND);
        long startedAt = System.nanoTime();

        CompletionStage<Void> publication = publisher.publish(STATE_TOPIC, ACCOUNT_ID, payload());

        assertThatThrownBy(() -> publication.toCompletableFuture()
                .get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS))
                .as("the stage every caller receives carries the bound the property names")
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .as("the bound elapsed rather than the test's patience")
                .isLessThan(PATIENCE);
    }

    @Test
    @DisplayName("a send the broker acknowledges completes the stage, and carries the topic, the "
            + "account identifier and the payload it was given")
    void aSendTheBrokerAcknowledgesCompletesTheStage() throws Exception {
        KafkaTemplate<String, String> template = acknowledging();
        EventPublisherPort publisher = publisher(template, SHORT_BOUND);
        String event = payload();

        CompletableFuture<Void> publication =
                publisher.publish(STATE_TOPIC, ACCOUNT_ID, event).toCompletableFuture();

        publication.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(publication)
                .as("the stage a caller receives completes on an acknowledged send, and the relay "
                        + "marks the row published only when it does")
                .isCompleted();
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.captor();
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic())
                .as("the topic the record reached. Awaiting the stage alone proved only that a "
                        + "mocked future was already complete, and would have passed on a send to "
                        + "the wrong topic or on no send at all")
                .isEqualTo(STATE_TOPIC);
        assertThat(sent.getValue().key())
                .as("the message key, which has to be the account identifier so every event for "
                        + "one account lands on one partition and stays ordered")
                .isEqualTo(ACCOUNT_ID);
        assertThat(sent.getValue().value())
                .as("the record value, which has to be the payload the outbox row stored, "
                        + "unaltered")
                .isEqualTo(event);
    }

    @Test
    @DisplayName("a key that is not an account identifier throws before any send starts")
    void aKeyThatIsNotAnAccountIdentifierThrowsBeforeAnySendStarts() {
        KafkaTemplate<String, String> template = neverAcknowledging();
        EventPublisherPort publisher = publisher(template, SHORT_BOUND);

        assertThatThrownBy(() -> publisher.publish(STATE_TOPIC, "1", payload()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(EventPublisherPort.AGGREGATE_ID_PATTERN);
    }

    @Test
    @DisplayName("an event type that does not belong on the topic throws before any send starts")
    void anEventTypeThatDoesNotBelongOnTheTopicThrowsBeforeAnySendStarts() {
        KafkaTemplate<String, String> template = neverAcknowledging();
        EventPublisherPort publisher = publisher(template, SHORT_BOUND);

        assertThatThrownBy(() -> publisher.publish(FOREIGN_TOPIC, ACCOUNT_ID, payload()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AccountStateChanged.EVENT_TYPE);
    }

    @Test
    @DisplayName("the publisher holds no meter, so one refused publish is counted once")
    void thePublisherHoldsNoMeter() {
        Class<?> publisherClass = publisher(acknowledging(), SHORT_BOUND).getClass();

        assertThat(publisherClass.getDeclaredFields())
                .as("a meter field here is the double count returning")
                .noneMatch(field -> field.getType().getName()
                        .contains(ObservabilityConfig.AccountMeters.class.getSimpleName()));
        assertThat(fieldTypes(publisherClass))
                .doesNotContain(ObservabilityConfig.AccountMeters.class);

        Method factory = factoryMethod();
        assertThat(factory.getParameterTypes())
                .as("the factory takes the template alone")
                .containsExactly(KafkaTemplate.class);
    }

    /** Returns the declared field types of one class. */
    private static Class<?>[] fieldTypes(Class<?> type) {
        Field[] fields = type.getDeclaredFields();
        Class<?>[] types = new Class<?>[fields.length];
        for (int index = 0; index < fields.length; index++) {
            types[index] = fields[index].getType();
        }
        return types;
    }

    /** Returns the bean factory method that builds the publisher. */
    private static Method factoryMethod() {
        try {
            return KafkaProducerConfig.class.getMethod("accountEventPublisher",
                    KafkaTemplate.class);
        } catch (NoSuchMethodException absent) {
            throw new AssertionError("KafkaProducerConfig must publish one publisher bean", absent);
        }
    }

    /** Builds the publisher over the supplied template and per-send bound. */
    private static EventPublisherPort publisher(KafkaTemplate<String, String> template,
            Duration bound) {
        return new KafkaProducerConfig(properties(bound)).accountEventPublisher(template);
    }

    /** A template whose every send stays outstanding for ever. */
    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> neverAcknowledging() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(new CompletableFuture<SendResult<String, String>>());
        return template;
    }

    /** A template whose every send is acknowledged at once. */
    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> acknowledging() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        return template;
    }

    /** One well-formed account state event as the wire carries it. */
    private static String payload() {
        ObjectMapper mapper = new KafkaProducerConfig(properties(SHORT_BOUND))
                .accountEventObjectMapper();
        return mapper.writeValueAsString(new AccountStateChanged(
                UUID.fromString("11111111-2222-4333-8444-555555555555"),
                AccountStateChanged.EVENT_TYPE, AccountStateChanged.SCHEMA_VERSION,
                Instant.parse("2024-05-01T00:00:00Z"), ACCOUNT_ID, ACCOUNT_ID,
                AccountStateChanged.ChangeKind.ACCOUNT_UPDATED, new BigDecimal("120.45"),
                new BigDecimal("5000.00"), new BigDecimal("10.00"), new BigDecimal("20.00"),
                "2025-12-31"));
    }

    /**
     * Returns the bound settings block, with the supplied per-send wait.
     *
     * @param publishTimeout the value {@code carddemo.outbox.relay.publish-timeout} carries
     * @return the settings the configuration class reads
     */
    private static AccountProperties properties(Duration publishTimeout) {
        return new AccountProperties(
                new AccountProperties.Api(65536L),
                new AccountProperties.Kafka(new AccountProperties.Kafka.Topics(STATE_TOPIC,
                        "customer.context-changed", "transaction.posted",
                        "carddemo.dead-letter"),
                        new AccountProperties.Kafka.Groups("account-posted")),
                new AccountProperties.Consumer(new AccountProperties.Consumer.Retry(3, 1_000L)),
                new AccountProperties.Outbox(
                        new AccountProperties.Outbox.Relay(500L, 100, "publisher-test",
                                Duration.ofSeconds(30L), 5_000L, publishTimeout), 168L),
                new AccountProperties.Retention(3_600_000L),
                new AccountProperties.Write(3_000L));
    }
}
