package com.carddemo.ledger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.ledger.config.ObservabilityConfig.LedgerMeters;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * Holds the error handler to counting a refused payload and to naming what every record it gave up on
 * failed at.
 *
 * <p>Two series were registered and unreachable before this. {@code carddemo.ledger.failures} with
 * {@code stage=deserialize} had its only call site on a listener branch that a refused payload never
 * reaches, because the container raises that failure before any listener runs: the series read zero
 * however many payloads a schema control rejected, and an alerting rule written against the
 * documented metric contract could not fire. And {@code carddemo.ledger.dead.letters} carried the
 * outcome of the diagnostic alone, so a rising count named no fault to act on — a schema violation, a
 * database outage and a business refusal all read the same.
 *
 * <p>Both are counted in the recoverer, and the recoverer is the right place for exactly one reason:
 * the container calls it only once the backoff is spent, so every record reaching it is one this
 * service will not attempt again. A refused payload is registered as not retryable, so it reaches the
 * recoverer on its first delivery and is counted once.
 *
 * <p>The second nest keeps the fix honest in the other direction. A failure the listener did reach is
 * already counted there, once per attempt, under {@code stage=process}. Counting it again on the
 * attempt series here would report one business failure as several.
 */
@DisplayName("The ledger error handler counts a refused payload and names what each dead letter "
        + "failed at")
class DeadLetterFailureAttributionTest {

    /** The topic under test, and the one the posting listener consumes. */
    private static final String SOURCE_TOPIC = "transaction.authorized";

    /** The attempt series, tagged by the stage that raised. One increment is one attempt. */
    private static final String FAILURES = "carddemo.ledger.failures";

    /** The terminal series, tagged by outcome and by kind. One increment is one record. */
    private static final String DEAD_LETTERS = "carddemo.ledger.dead.letters";

    @Nested
    @DisplayName("A payload no deserializer could read")
    class RefusedPayload {

        @Test
        @DisplayName("is counted under the deserialize stage, which the listener cannot reach")
        void isCountedUnderTheDeserializeStage() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, deserializationFailure());

            assertEquals(1.0d, counter(registry, FAILURES, "stage", "deserialize"),
                    "the container raises this failure before any listener runs, so the recoverer"
                            + " is the one place it can be counted");
        }

        @Test
        @DisplayName("is not also counted under the process stage")
        void isNotAlsoCountedUnderTheProcessStage() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, deserializationFailure());

            assertEquals(0.0d, counter(registry, FAILURES, "stage", "process"),
                    "the two classes of failure stay disjoint");
        }

        @Test
        @DisplayName("names deserialization on the terminal series")
        void namesDeserializationOnTheTerminalSeries() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, deserializationFailure());

            assertEquals(1.0d, deadLetters(registry, LedgerMeters.DEAD_LETTER_PUBLISHED,
                            LedgerMeters.FAILURE_DESERIALIZATION),
                    "the record is spent, and the reading says what it failed at");
        }

        @Test
        @DisplayName("names schema validation when a schema control rejected the payload")
        void namesSchemaValidationForASchemaViolation() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, new DeserializationException("the payload broke its document",
                    "{}".getBytes(StandardCharsets.UTF_8), false,
                    new SerializationException("required property is missing")));

            assertEquals(1.0d, deadLetters(registry, LedgerMeters.DEAD_LETTER_PUBLISHED,
                            LedgerMeters.FAILURE_SCHEMA_VALIDATION),
                    "a schema violation arrives as a serialization failure wrapped in a"
                            + " deserialization failure, and the more specific kind wins");
            assertEquals(1.0d, counter(registry, FAILURES, "stage", "deserialize"),
                    "and the refusal is counted on the attempt series too");
        }
    }

    @Nested
    @DisplayName("A failure the listener reached")
    class ProcessingFailure {

        @Test
        @DisplayName("names processing on the terminal series")
        void namesProcessingOnTheTerminalSeries() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, new ListenerExecutionFailedException("listener failed",
                    new IllegalArgumentException("the message key names another account")));

            assertEquals(1.0d, deadLetters(registry, LedgerMeters.DEAD_LETTER_PUBLISHED,
                            LedgerMeters.FAILURE_PROCESSING),
                    "the listener ran and refused, which is a different fault from a refused"
                            + " payload");
        }

        @Test
        @DisplayName("is not counted on the deserialize stage by the recoverer")
        void isNotCountedOnTheDeserializeStage() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, new ListenerExecutionFailedException("listener failed",
                    new QueryTimeoutException("the write timed out")));

            assertEquals(0.0d, counter(registry, FAILURES, "stage", "deserialize"),
                    "the listener already counted this one, once per attempt");
        }

        @Test
        @DisplayName("names the unknown kind when the chain names none of the three")
        void namesTheUnknownKindForAnUnclassifiedChain() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, new IllegalStateException("the container gave up"));

            assertEquals(1.0d, deadLetters(registry, LedgerMeters.DEAD_LETTER_PUBLISHED,
                            LedgerMeters.FAILURE_UNKNOWN),
                    "an unclassified chain still lands on a registered series rather than nowhere");
        }
    }

    @Nested
    @DisplayName("The series this surface registers")
    class RegisteredSeries {

        @Test
        @DisplayName("every kind of every outcome reads zero before the first dead letter")
        void everyKindReadsZeroAtStartUp() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new ObservabilityConfig().ledgerMeters(registry);

            for (String outcome : Set.of(LedgerMeters.DEAD_LETTER_PUBLISHED,
                    LedgerMeters.DEAD_LETTER_FAILED)) {
                for (String kind : LedgerMeters.FAILURE_KINDS) {
                    assertEquals(0.0d, deadLetters(registry, outcome, kind),
                            "an alerting rule can only fire against a series that exists: "
                                    + outcome + "/" + kind);
                }
            }
        }

        @Test
        @DisplayName("every series of the terminal name carries both tag keys")
        void everySeriesCarriesBothTagKeys() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new ObservabilityConfig().ledgerMeters(registry);

            registry.getMeters().stream()
                    .filter(meter -> DEAD_LETTERS.equals(meter.getId().getName()))
                    .forEach(meter -> {
                        Set<String> keys = meter.getId().getTags().stream()
                                .map(Tag::getKey)
                                .collect(Collectors.toSet());
                        assertTrue(keys.containsAll(Set.of(LedgerMeters.TAG_OUTCOME,
                                        LedgerMeters.TAG_FAILURE_KIND)),
                                "a Prometheus registry admits one tag-key set per meter name, so"
                                        + " both keys sit on every series: " + keys);
                    });
        }

        @Test
        @DisplayName("a kind no series carries is counted as unknown rather than lost")
        void anUnregisteredKindIsCountedAsUnknown() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            LedgerMeters meters = new ObservabilityConfig().ledgerMeters(registry);

            meters.recordDeadLetterPublished("invented_kind");
            meters.recordDeadLetterFailure(null);

            assertEquals(1.0d, deadLetters(registry, LedgerMeters.DEAD_LETTER_PUBLISHED,
                            LedgerMeters.FAILURE_UNKNOWN),
                    "a failure count is not the place to lose a count");
            assertEquals(1.0d, deadLetters(registry, LedgerMeters.DEAD_LETTER_FAILED,
                            LedgerMeters.FAILURE_UNKNOWN),
                    "and the same holds for a refused diagnostic");
        }
    }

    @Nested
    @DisplayName("A dead-letter send the broker refused")
    class RefusedDiagnostic {

        @Test
        @DisplayName("is counted as failed under the kind that named the original fault")
        void isCountedAsFailedUnderTheSameKind() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, deserializationFailure(), true);

            assertEquals(1.0d, deadLetters(registry, LedgerMeters.DEAD_LETTER_FAILED,
                            LedgerMeters.FAILURE_DESERIALIZATION),
                    "the record is spent and no topic names it, and the reading still says what it"
                            + " failed at");
            assertEquals(0.0d, deadLetters(registry, LedgerMeters.DEAD_LETTER_PUBLISHED,
                            LedgerMeters.FAILURE_DESERIALIZATION),
                    "the count follows the send, so a refused send is not reported as published");
        }
    }

    /** Reads one counter of {@code name} separated by one tag. */
    private static double counter(SimpleMeterRegistry registry, String name, String tagKey,
            String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).counter().count();
    }

    /** Reads one counter of the terminal series, separated by outcome and kind. */
    private static double deadLetters(SimpleMeterRegistry registry, String outcome, String kind) {
        return registry.get(DEAD_LETTERS)
                .tag(LedgerMeters.TAG_OUTCOME, outcome)
                .tag(LedgerMeters.TAG_FAILURE_KIND, kind)
                .counter()
                .count();
    }

    /** Drives one record through the shipped recovery path. */
    private static void recoverOnce(SimpleMeterRegistry registry, Exception failure) {
        recoverOnce(registry, failure, false);
    }

    /**
     * Drives one record through the shipped recovery path, optionally with a refusing broker.
     *
     * <p>One delivery attempt is configured, so the first failure spends the backoff and the
     * recoverer runs at once. The template is a mock, because the count is what is under test.
     *
     * @param registry      the registry every count lands in
     * @param failure       the failure the container recovered from
     * @param sendIsRefused whether the dead-letter template throws when asked to send
     */
    @SuppressWarnings("unchecked")
    private static void recoverOnce(SimpleMeterRegistry registry, Exception failure,
            boolean sendIsRefused) {
        LedgerProperties properties = new LedgerProperties(
                new LedgerProperties.Kafka(new LedgerProperties.Kafka.Topics(SOURCE_TOPIC,
                        "transaction.declined", "account.state-changed", "transaction.posted",
                        "carddemo.dead-letter", ".DLT")),
                new LedgerProperties.Consumer(new LedgerProperties.Consumer.Retry(1, 0L)),
                new LedgerProperties.Outbox(new LedgerProperties.Outbox.Relay(1000L, 100,
                        "ledger-relay", Duration.ofMinutes(2L), 5_000L), 168L),
                new LedgerProperties.Retention(3_600_000L, 90));

        KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
        if (sendIsRefused) {
            when(template.send(any(ProducerRecord.class)))
                    .thenThrow(new KafkaException("the broker refused the diagnostic"));
        }

        DefaultErrorHandler errorHandler = new KafkaConsumerConfig().ledgerConsumerErrorHandler(
                template, properties, new ObservabilityConfig().ledgerMeters(registry));

        errorHandler.handleOne(failure, record(), mock(Consumer.class),
                mock(MessageListenerContainer.class));
    }

    /** One delivery of the topic under test, carrying a key and no usable value. */
    private static ConsumerRecord<String, Object> record() {
        return new ConsumerRecord<>(SOURCE_TOPIC, 0, 0L, "00000000011", null);
    }

    /** The failure {@code ErrorHandlingDeserializer} raises for a payload it could not read. */
    private static DeserializationException deserializationFailure() {
        return new DeserializationException("the payload did not read",
                "{\"eventType\":\"TransactionAuthorized\"}".getBytes(StandardCharsets.UTF_8), false,
                new IllegalArgumentException("the payload is not an object"));
    }
}
