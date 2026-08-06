package com.carddemo.fraud.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.events.TransactionAuthorized;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Holds the error handler to counting a deserialization failure, to counting every record it gives up
 * on, and to counting nothing else.
 *
 * <p>A payload no deserializer could read is raised by the container before any listener runs, so the
 * listener cannot count it. Before this was fixed the only call to the deserialize counter sat in the
 * listener, on the branch that sees a null value — which that failure never reaches. The series
 * registered at start-up and stayed at zero however many malformed records arrived.
 *
 * <p>The second test is the one that keeps the fix honest in the other direction. A failure the
 * listener did reach is already counted there, once per delivery attempt. Counting every recovered
 * record on the ATTEMPT series here as well would report one business failure as several, which is the
 * defect this platform was separately told to remove from the notification service. The two classes are
 * disjoint, and the count below proves they stay that way.
 *
 * <p>The terminal series is different, and the last nest is about that. The container calls a recoverer
 * only once the backoff is spent, so every record reaching it is one this service will not attempt
 * again. That is counted once per record under {@code carddemo.fraud.dead.letters}, whatever the cause,
 * because a retry storm and a permanent loss are the two readings an operator has to be able to tell
 * apart.
 *
 * <p>The refusal case asserts the count rather than a propagated failure, and that is deliberate. The
 * wrapper does rethrow, so the container is told the record was not recovered, but
 * {@code FailedRecordTracker} inside {@code DefaultErrorHandler} logs that failure and returns rather
 * than letting it out of {@code handleOne}. The count is therefore the only observable evidence, which
 * is exactly the gap the terminal series was added to close.
 */
@DisplayName("The error handler counts a deserialization failure, every record it gives up on, and "
        + "nothing else")
class DeserializeFailureCountingTest {

    /** The topic under test, and the one this service consumes. */
    private static final String TOPIC = "transaction.authorized";

    /** Suffix the per-source-topic dead-letter name carries, the value the shipped file binds. */
    private static final String DEAD_LETTER_SUFFIX = ".DLT";

    /** The dead-letter topic the recoverer publishes to. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The failure counter, tagged with the stage that raised it. One increment is one attempt. */
    private static final String FAILURES = "carddemo.fraud.failures";

    /** The terminal counter, tagged with what became of the diagnostic. One increment is one record. */
    private static final String DEAD_LETTERS = "carddemo.fraud.dead.letters";

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

    @Nested
    @DisplayName("Every record the container gives up on")
    class TerminalRecord {

        @Test
        @DisplayName("is counted once as dead-lettered, whatever the cause")
        void isCountedOnceAsDeadLettered() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            recoverOnce(registry, new QueryTimeoutException("the write timed out"));

            assertThat(deadLetters(registry, "published"))
                    .as("the backoff is spent by the time a recoverer runs, so this is the one "
                            + "moment a record can be counted as permanently given up on")
                    .isEqualTo(1.0d);
            assertThat(deadLetters(registry, "failed")).isZero();
        }

        @Test
        @DisplayName("is counted as dead-lettered for a payload failure too")
        void isCountedForAPayloadFailureToo() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            recoverOnce(registry, deserializationFailure());

            assertThat(deadLetters(registry, "published"))
                    .as("a cause and an outcome are different axes: the deserialize stage says why, "
                            + "and the terminal series says the record is gone")
                    .isEqualTo(1.0d);
        }

        @Test
        @DisplayName("counts as refused when the dead-letter send itself fails")
        void countsAsRefusedWhenTheSendFails() {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            recoverOnce(registry, new QueryTimeoutException("the write timed out"), true);

            assertThat(deadLetters(registry, "failed"))
                    .as("nothing names this record on any topic, which is the reading that matters "
                            + "and the reading a log line alone does not give")
                    .isEqualTo(1.0d);
            assertThat(deadLetters(registry, "published"))
                    .as("a diagnostic the broker refused is not a diagnostic an operator can find")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("The offset of a record the route published")
    class RecoveredOffset {

        /**
         * Asserts the dead-letter route is terminal.
         *
         * <p>The container acknowledges by hand, so nothing acknowledges a record its listener never
         * accepted. Left at its default this setting committed nothing, so the next start-up or the
         * next partition assignment read the same refused record again and published a second
         * diagnostic naming the same coordinates.
         */
        @Test
        @DisplayName("is committed, so a restart or a rebalance does not read it again")
        void isCommittedSoARestartDoesNotReadItAgain() {
            DefaultErrorHandler errorHandler = shippedErrorHandler();
            Consumer<?, ?> consumer = mock(Consumer.class);

            assertThat(ReflectionTestUtils.getField(errorHandler, "commitRecovered"))
                    .as("commitRecovered, which the container applies under MANUAL_IMMEDIATE")
                    .isEqualTo(Boolean.TRUE);
            assertThat(errorHandler.seeksAfterHandling())
                    .as("the container hands a record to handleRemaining, the path asserted below")
                    .isTrue();

            errorHandler.handleRemaining(new QueryTimeoutException("the write timed out"),
                    List.of(record()), consumer, manualImmediateContainer());

            verify(consumer).commitSync(
                    eq(Map.of(new TopicPartition(TOPIC, 0), new OffsetAndMetadata(1L))),
                    nullable(Duration.class));
        }

        /** The shipped handler with one delivery, so the first failure spends the backoff at once. */
        @SuppressWarnings("unchecked")
        private DefaultErrorHandler shippedErrorHandler() {
            KafkaConsumerConfig config = new KafkaConsumerConfig("kafka:29092", TOPIC,
                    "fraud-detection", DEAD_LETTER_TOPIC, DEAD_LETTER_SUFFIX, 1L, 0L);
            return config.transactionAuthorizedErrorHandler(mock(KafkaTemplate.class),
                    new ObservabilityConfig().fraudMeters(new SimpleMeterRegistry()));
        }

        /**
         * A container acknowledging by hand and at once, which is the mode
         * {@code transactionAuthorizedListenerContainerFactory} sets on every container it builds.
         */
        private MessageListenerContainer manualImmediateContainer() {
            ContainerProperties containerProperties = new ContainerProperties(TOPIC);
            containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
            MessageListenerContainer container = mock(MessageListenerContainer.class);
            when(container.getContainerProperties()).thenReturn(containerProperties);
            return container;
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
    private static void recoverOnce(SimpleMeterRegistry registry, Exception failure) {
        recoverOnce(registry, failure, false);
    }

    /**
     * Drives one record through the recovery path, optionally with a broker that refuses the send.
     *
     * @param registry     the registry every count lands in
     * @param failure      the failure the container recovered from
     * @param sendIsRefused whether the dead-letter template throws when asked to send
     */
    @SuppressWarnings("unchecked")
    private static void recoverOnce(SimpleMeterRegistry registry, Exception failure,
            boolean sendIsRefused) {
        ObservabilityConfig.FraudMeters meters = new ObservabilityConfig().fraudMeters(registry);
        KafkaConsumerConfig config = new KafkaConsumerConfig("kafka:29092", TOPIC,
                "fraud-detection", DEAD_LETTER_TOPIC, DEAD_LETTER_SUFFIX, 1L, 0L);

        KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
        if (sendIsRefused) {
            when(template.send(any(ProducerRecord.class)))
                    .thenThrow(new KafkaException("the broker refused the diagnostic"));
        }

        DefaultErrorHandler errorHandler =
                config.transactionAuthorizedErrorHandler(template, meters);
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

    /** Reads one outcome of the terminal counter, answering zero where it carries no value. */
    private static double deadLetters(SimpleMeterRegistry registry, String outcome) {
        return registry.find(DEAD_LETTERS).tag("outcome", outcome).counter() == null ? 0.0d
                : registry.find(DEAD_LETTERS).tag("outcome", outcome).counter().count();
    }
}
