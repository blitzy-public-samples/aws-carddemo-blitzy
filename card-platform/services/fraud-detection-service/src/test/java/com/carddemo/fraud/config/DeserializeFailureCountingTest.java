package com.carddemo.fraud.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.carddemo.events.TransactionAuthorized;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * Holds the error handler to counting a deserialization failure, and to counting nothing else.
 *
 * <p>A payload no deserializer could read is raised by the container before any listener runs, so the
 * listener cannot count it. Before this was fixed the only call to the deserialize counter sat in the
 * listener, on the branch that sees a null value — which that failure never reaches. The series
 * registered at start-up and stayed at zero however many malformed records arrived.
 *
 * <p>The second test is the one that keeps the fix honest in the other direction. A failure the
 * listener did reach is already counted there, once per delivery attempt. Counting every recovered
 * record here as well would report one business failure as several, which is the defect this platform
 * was separately told to remove from the notification service. The two classes are disjoint, and the
 * count below proves they stay that way.
 */
@DisplayName("The error handler counts a deserialization failure and nothing else")
class DeserializeFailureCountingTest {

    /** The topic under test, and the one this service consumes. */
    private static final String TOPIC = "transaction.authorized";

    /** Suffix the per-source-topic dead-letter name carries, the value the shipped file binds. */
    private static final String DEAD_LETTER_SUFFIX = ".DLT";

    /** The dead-letter topic the recoverer publishes to. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The failure counter, tagged with the stage that raised it. */
    private static final String FAILURES = "carddemo.fraud.failures";

    @Nested
    @DisplayName("A payload that did not read")
    class PayloadFailure {

        @Test
        @DisplayName("is counted under the deserialize stage")
        void isCountedUnderTheDeserializeStage() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            recoverOnce(registry, deserializationFailure());

            assertThat(stage(registry, "deserialize"))
                    .as("the container raised this before any listener ran, so the recoverer is the "
                            + "only place it can be counted")
                    .isEqualTo(1.0d);
        }

        @Test
        @DisplayName("is not also counted under the process stage")
        void isNotAlsoCountedUnderTheProcessStage() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            recoverOnce(registry, deserializationFailure());

            assertThat(stage(registry, "process"))
                    .as("no listener processed this record, so no processing failed")
                    .isZero();
        }

        @Test
        @DisplayName("is counted even where the failure sits deep in the cause chain")
        void isCountedThroughTheCauseChain() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            recoverOnce(registry, new IllegalStateException("listener invocation failed",
                    deserializationFailure()));

            assertThat(stage(registry, "deserialize"))
                    .as("the container wraps the failure it caught, so the check walks the chain")
                    .isEqualTo(1.0d);
        }
    }

    @Nested
    @DisplayName("A failure the listener reached")
    class ListenerFailure {

        @Test
        @DisplayName("is not counted by the recoverer, because the listener already counted it")
        void isNotCountedByTheRecoverer() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            recoverOnce(registry, new QueryTimeoutException("the write timed out"));

            assertThat(stage(registry, "deserialize"))
                    .as("a database timeout is not a payload that failed to read")
                    .isZero();
            assertThat(stage(registry, "process"))
                    .as("the terminal recovery adds nothing to the per-attempt count the listener "
                            + "keeps, or one failure would report as several")
                    .isZero();
        }
    }

    /**
     * Builds the shipped error handler over {@code registry} and drives one record through its
     * recovery path.
     *
     * <p>One delivery attempt is configured, so the first failure exhausts the backoff and the
     * recoverer runs immediately. The dead-letter template is a mock: what is under test is the count,
     * and a send that never happens still leaves the count where it belongs.
     */
    @SuppressWarnings("unchecked")
    private static void recoverOnce(SimpleMeterRegistry registry, Exception failure) {
        ObservabilityConfig.FraudMeters meters = new ObservabilityConfig().fraudMeters(registry);
        KafkaConsumerConfig config = new KafkaConsumerConfig("kafka:29092", TOPIC,
                "fraud-detection", DEAD_LETTER_TOPIC, DEAD_LETTER_SUFFIX, 1L, 0L);

        DefaultErrorHandler errorHandler = config.transactionAuthorizedErrorHandler(
                mock(KafkaTemplate.class), meters);
        errorHandler.handleOne(failure, record(), mock(Consumer.class),
                mock(MessageListenerContainer.class));
    }

    /** One delivery of the topic under test, carrying a key and no usable value. */
    private static ConsumerRecord<String, TransactionAuthorized> record() {
        return new ConsumerRecord<>(TOPIC, 0, 0L, "00000000011", null);
    }

    /** The failure {@code ErrorHandlingDeserializer} raises for a payload it could not read. */
    private static DeserializationException deserializationFailure() {
        return new DeserializationException("the payload broke its schema document",
                "{\"eventType\":\"TransactionAuthorized\"}".getBytes(StandardCharsets.UTF_8), false,
                new IllegalArgumentException("required property is missing"));
    }

    /** Reads one stage of the failure counter, answering zero where it carries no value. */
    private static double stage(SimpleMeterRegistry registry, String stage) {
        return registry.find(FAILURES).tag("stage", stage).counter() == null ? 0.0d
                : registry.find(FAILURES).tag("stage", stage).counter().count();
    }
}
