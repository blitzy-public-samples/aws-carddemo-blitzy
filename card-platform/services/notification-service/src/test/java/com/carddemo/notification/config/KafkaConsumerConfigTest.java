package com.carddemo.notification.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.notification.messaging.DeadLetterMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Security contract for the notification dead-letter route.
 *
 * <p>A refused record is attacker-controlled. Its key, value and arbitrary headers therefore
 * cannot cross the validation boundary a second time. Only broker coordinates and scrubbed,
 * fixed-width diagnostics may reach the per-source dead-letter topic.</p>
 */
final class KafkaConsumerConfigTest {

    private static final String SOURCE_TOPIC = "transaction.posted";
    private static final String FALLBACK_TOPIC = "carddemo.dead-letter";
    private static final String SUFFIX = ".DLT";
    private static final String REFUSED_KEY = "4859452612877065";

    /** The two retry values the shipped {@code application.yml} binds. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MS = 1000L;

    private static final byte[] REFUSED_VALUE =
            "{\"cardNumber\":\"4859452612877065\",\"cvv\":\"123\"}"
                    .getBytes(StandardCharsets.UTF_8);

    @Test
    void aDeadLetterCarriesNoRefusedKeyValueOrForeignHeader() {
        ConsumerRecord<String, byte[]> refused =
                new ConsumerRecord<>(SOURCE_TOPIC, 2, 41L, REFUSED_KEY, REFUSED_VALUE);
        refused.headers().add("producer-secret", REFUSED_VALUE);
        refused.headers().add(KafkaConsumerConfig.HEADER_MESSAGE, REFUSED_VALUE);

        DeadLetterMetadata metadata = KafkaConsumerConfig.metadataOf(
                new IllegalArgumentException("refused 4859452612877065 at /maskedCardNumber"));
        Headers assembled = new RecordHeaders(refused.headers());
        assembled.add(KafkaConsumerConfig.HEADER_ABEND_CODE, bytes(metadata.abendCode()));
        assembled.add(KafkaConsumerConfig.HEADER_CULPRIT, bytes(metadata.culprit()));
        assembled.add(KafkaConsumerConfig.HEADER_REASON, bytes(metadata.reason()));
        assembled.add(KafkaConsumerConfig.HEADER_MESSAGE, bytes(metadata.message()));
        assembled.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, bytes(metadata.message()));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, bytes(SOURCE_TOPIC));

        String destination = KafkaConsumerConfig.resolveDeadLetterDestination(
                refused, FALLBACK_TOPIC, SUFFIX);
        ProducerRecord<Object, Object> sanitized =
                KafkaConsumerConfig.sanitizedDeadLetterRecord(
                        refused, new TopicPartition(destination, -1), assembled);

        assertEquals("transaction.posted.DLT", sanitized.topic());
        assertEquals("transaction.posted-2-41", sanitized.key());
        assertNotEquals(REFUSED_KEY, sanitized.key());

        byte[] outgoingValue = (byte[]) sanitized.value();
        assertEquals(DeadLetterMetadata.RECORD_LENGTH, outgoingValue.length);
        assertFalse(Arrays.equals(REFUSED_VALUE, outgoingValue));
        String diagnostic = new String(outgoingValue, StandardCharsets.UTF_8);
        assertFalse(diagnostic.contains(REFUSED_KEY));
        assertFalse(diagnostic.contains("123"));
        assertTrue(diagnostic.contains(KafkaConsumerConfig.SAFE_FAILURE_MESSAGE));

        Set<String> outgoingHeaders = StreamSupport.stream(
                        sanitized.headers().spliterator(), false)
                .map(header -> header.key())
                .collect(Collectors.toUnmodifiableSet());
        assertFalse(outgoingHeaders.contains("producer-secret"));
        assertTrue(KafkaConsumerConfig.ALLOWED_HEADERS.containsAll(outgoingHeaders));
        assertEquals(Set.of(
                KafkaConsumerConfig.HEADER_ABEND_CODE,
                KafkaConsumerConfig.HEADER_CULPRIT,
                KafkaConsumerConfig.HEADER_REASON,
                KafkaConsumerConfig.HEADER_MESSAGE,
                KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                KafkaHeaders.DLT_ORIGINAL_TOPIC), outgoingHeaders);
        assertEquals(1, StreamSupport.stream(
                        sanitized.headers().headers(KafkaConsumerConfig.HEADER_MESSAGE)
                                .spliterator(), false)
                .count(), "a producer cannot preserve a spoofed copy of an allowed header");
    }

    @Test
    void eachSourceTopicRoutesToItsOwnDeadLetterTopic() {
        ConsumerRecord<String, byte[]> posted =
                new ConsumerRecord<>("transaction.posted", 0, 1L, "key", new byte[0]);
        ConsumerRecord<String, byte[]> assessed =
                new ConsumerRecord<>("fraud.assessed", 0, 1L, "key", new byte[0]);

        assertEquals("transaction.posted.DLT",
                KafkaConsumerConfig.resolveDeadLetterDestination(
                        posted, FALLBACK_TOPIC, SUFFIX));
        assertEquals("fraud.assessed.DLT",
                KafkaConsumerConfig.resolveDeadLetterDestination(
                        assessed, FALLBACK_TOPIC, SUFFIX));
        assertEquals(FALLBACK_TOPIC,
                KafkaConsumerConfig.resolveDeadLetterDestination(
                        null, FALLBACK_TOPIC, SUFFIX));
    }

    /**
     * Asserts the dead-letter route is terminal: the offset of a record it published is committed.
     *
     * <p>Every container of this service acknowledges by hand, so nothing acknowledges a record its
     * listener never accepted. Left at its default this setting committed nothing, and the next
     * start-up or the next partition assignment read the same refused record again and published a
     * second dead letter naming the same coordinates, while the group kept a lag it could not clear.
     *
     * <p>The setting is read from the field, which the framework exposes through a protected
     * accessor, and the commit itself is asserted on the consumer the container hands the handler.
     */
    @Test
    @DisplayName("a record the route published has its offset committed, so it is read once")
    void aDeadLetteredRecordHasItsOffsetCommitted() {
        DefaultErrorHandler errorHandler = terminalOnFirstFailureErrorHandler();
        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, byte[]> refused =
                new ConsumerRecord<>(SOURCE_TOPIC, 1, 4L, REFUSED_KEY, REFUSED_VALUE);

        assertEquals(Boolean.TRUE,
                ReflectionTestUtils.getField(errorHandler, "commitRecovered"),
                "commitRecovered, which the container applies under MANUAL_IMMEDIATE");
        assertTrue(errorHandler.isAckAfterHandle(),
                "the container resolves the record once the handler returns");
        assertTrue(errorHandler.seeksAfterHandling(),
                "the container hands a record to handleRemaining, which is the path asserted below");

        errorHandler.handleRemaining(new IllegalStateException("the alert was not written"),
                List.of(refused), consumer, manualImmediateContainer());

        verify(consumer).commitSync(
                eq(Map.of(new TopicPartition(SOURCE_TOPIC, 1), new OffsetAndMetadata(5L))),
                nullable(Duration.class));
    }

    /**
     * A container acknowledging by hand and at once, which is what
     * {@code spring.kafka.listener.ack-mode} names in the shipped file.
     */
    private static MessageListenerContainer manualImmediateContainer() {
        ContainerProperties containerProperties = new ContainerProperties(SOURCE_TOPIC);
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(containerProperties);
        return container;
    }

    /** The shipped handler with one delivery, so the first failure spends the backoff at once. */
    private static DefaultErrorHandler terminalOnFirstFailureErrorHandler() {
        return new KafkaConsumerConfig().notificationConsumerErrorHandler(
                mock(DeadLetterPublishingRecoverer.class),
                new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()),
                1, 0L);
    }

    /**
     * Asserts the dead-letter route tags a database outage as a persistence failure.
     *
     * <p>The recoverer is the only place a terminal outcome is counted, so a mislabelled tag here
     * leaves the registered {@code persistence} series at zero for the most common database failure
     * there is. A paused or unreachable database raises {@code CannotCreateTransactionException} from
     * the connection pool, which is a transaction fault and not a data-access fault, and the earlier
     * test for the latter alone dead-lettered it as {@code unknown}.
     */
    @Test
    @DisplayName("a database outage is dead-lettered as a persistence failure, not as unknown")
    void aDatabaseOutageIsDeadLetteredAsAPersistenceFailure() {
        assertEquals(ObservabilityConfig.NotificationMetrics.FAILURE_PERSISTENCE,
                KafkaConsumerConfig.failureKindOf(new IllegalStateException(
                        "the alert was not written",
                        new CannotCreateTransactionException("could not open a connection"))),
                "a pool that cannot hand out a connection is a database failure");
        assertEquals(ObservabilityConfig.NotificationMetrics.FAILURE_PERSISTENCE,
                KafkaConsumerConfig.failureKindOf(
                        new QueryTimeoutException("the statement timed out")),
                "a statement the database refused is a database failure");
        assertEquals(ObservabilityConfig.NotificationMetrics.UNKNOWN,
                KafkaConsumerConfig.failureKindOf(
                        new IllegalArgumentException("the key names another aggregate")),
                "a refused delivery names no integration, so the fallback still applies");
        assertEquals(ObservabilityConfig.NotificationMetrics.FAILURE_SCHEMA_VALIDATION,
                KafkaConsumerConfig.failureKindOf(new SerializationException("schema violation")),
                "a schema violation keeps its own tag ahead of every other test");
    }

    /**
     * Asserts a refused payload still reaches the route on its first delivery. Both classifications
     * are read from the same handler the bean method builds, and reading one removes it, so this
     * instance is discarded with the test.
     */
    @Test
    @DisplayName("a refused payload and a schema violation stay unretryable beside that setting")
    void aRefusedPayloadStaysUnretryable() {
        DefaultErrorHandler errorHandler = shippedErrorHandler();

        assertEquals(Boolean.FALSE,
                errorHandler.removeClassification(DeserializationException.class),
                "a payload the deserializer refused is not retryable");
        assertEquals(Boolean.FALSE,
                errorHandler.removeClassification(SerializationException.class),
                "a payload the schema refused is not retryable");
    }

    /** Builds the error handler bean from the values the shipped configuration binds. */
    private static DefaultErrorHandler shippedErrorHandler() {
        return new KafkaConsumerConfig().notificationConsumerErrorHandler(
                mock(DeadLetterPublishingRecoverer.class),
                new ObservabilityConfig().notificationMetrics(new SimpleMeterRegistry()),
                MAX_ATTEMPTS, BACKOFF_MS);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
