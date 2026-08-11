package com.carddemo.ledger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.ledger.messaging.DeadLetterMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies that ledger dead letters retain broker coordinates and no refused or producer data.
 */
@DisplayName("Ledger Kafka consumer dead-letter sanitization")
class KafkaConsumerConfigTest {

    private static final String SOURCE_TOPIC = "transaction.authorized";
    private static final String FULL_CARD_NUMBER = "4859452612877065";
    private static final String REFUSED_JSON =
            "{\"cardNumber\":\"" + FULL_CARD_NUMBER + "\",\"cvv\":\"123\"}";

    /**
     * The seven headers a ledger dead letter carries: the four diagnostic components, which the
     * notification and fraud routes also carry, and the three broker coordinates. Nothing a producer
     * chose survives.
     */
    private static final Set<String> EXPECTED_HEADERS = Set.of(
            KafkaConsumerConfig.HEADER_ABEND_CODE,
            KafkaConsumerConfig.HEADER_CULPRIT,
            KafkaConsumerConfig.HEADER_REASON,
            KafkaConsumerConfig.HEADER_MESSAGE,
            KafkaHeaders.DLT_ORIGINAL_TOPIC,
            KafkaHeaders.DLT_ORIGINAL_PARTITION,
            KafkaHeaders.DLT_ORIGINAL_OFFSET,
            KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);

    @Test
    @DisplayName("refused bytes, producer key and arbitrary headers never reach the dead letter")
    void refusedDataNeverReachesTheDeadLetterRecord() {
        ConsumerRecord<String, byte[]> refused =
                new ConsumerRecord<>(SOURCE_TOPIC, 2, 41L, FULL_CARD_NUMBER,
                        REFUSED_JSON.getBytes(StandardCharsets.UTF_8));
        refused.headers().add("producer-secret", utf8(FULL_CARD_NUMBER));

        Headers assembled = new RecordHeaders();
        assembled.add(KafkaConsumerConfig.HEADER_ABEND_CODE, utf8("SPOOF"));
        assembled.add(KafkaConsumerConfig.HEADER_CULPRIT, utf8("SPOOFED"));
        assembled.add(KafkaConsumerConfig.HEADER_MESSAGE, utf8(REFUSED_JSON));
        assembled.add(KafkaConsumerConfig.HEADER_REASON, utf8(FULL_CARD_NUMBER));
        KafkaConsumerConfig.diagnosticHeaders(new IllegalStateException("PayloadRejected",
                        new PayloadRejectedException(REFUSED_JSON)))
                .forEach(header -> assembled.add(header.key(), header.value()));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, utf8("spoofed.topic"));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, utf8(SOURCE_TOPIC));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, intBytes(2));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, longBytes(41L));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, longBytes(0L));
        assembled.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, utf8(REFUSED_JSON));
        assembled.add("producer-secret", utf8(FULL_CARD_NUMBER));

        ProducerRecord<Object, Object> outgoing =
                KafkaConsumerConfig.sanitizedDeadLetterRecord(
                        refused, new TopicPartition(SOURCE_TOPIC + ".DLT", -1), assembled);

        String diagnostic = valueText(outgoing);
        Set<String> headerNames = new HashSet<>();
        outgoing.headers().forEach(header -> headerNames.add(header.key()));

        assertEquals(SOURCE_TOPIC + ".DLT", outgoing.topic(), "destination");
        assertNull(outgoing.partition(), "broker-selected partition");
        assertEquals(SOURCE_TOPIC + "-2-41", outgoing.key(), "coordinate key");
        assertEquals(DeadLetterMetadata.RECORD_LENGTH, diagnostic.length(),
                "the four fixed-width fields of 01 ABEND-DATA and nothing else");
        assertTrue(diagnostic.contains("PayloadRejectedException"), "failure class");
        assertFalse(diagnostic.contains(FULL_CARD_NUMBER), "card number in the diagnostic");
        assertFalse(diagnostic.contains(REFUSED_JSON), "payload in the diagnostic");
        assertEquals(EXPECTED_HEADERS, headerNames, "header allowlist");
        assertEquals(SOURCE_TOPIC,
                text(outgoing.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)),
                "last trusted source-topic header");
        assertNull(outgoing.headers().lastHeader("producer-secret"), "producer header");
        assertNull(outgoing.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                "exception message");
        assertEquals(1, count(outgoing.headers(), KafkaHeaders.DLT_ORIGINAL_TOPIC),
                "one canonical topic header");
        assertEquals("DEAD", text(outgoing.headers().lastHeader(
                KafkaConsumerConfig.HEADER_ABEND_CODE)), "generated abend code, not the spoofed one");
        assertEquals("POSTTRAN", text(outgoing.headers().lastHeader(
                KafkaConsumerConfig.HEADER_CULPRIT)), "generated culprit, not the spoofed one");
        assertEquals("PayloadRejectedException", text(outgoing.headers().lastHeader(
                KafkaConsumerConfig.HEADER_REASON)), "deepest failure type");
        assertFalse(text(outgoing.headers().lastHeader(KafkaConsumerConfig.HEADER_MESSAGE))
                .contains(FULL_CARD_NUMBER), "no refused payload in the message header");
    }

    /** A failure whose type name the diagnostic reports, standing in for a refused payload. */
    private static final class PayloadRejectedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private PayloadRejectedException(String message) {
            super(message);
        }
    }

    @Test
    @DisplayName("each source topic selects a separate dead-letter topic")
    void sourceTopicSelectsItsDeadLetterTopic() {
        ConsumerRecord<String, byte[]> authorized =
                new ConsumerRecord<>("transaction.authorized", 0, 0L, null, null);
        ConsumerRecord<String, byte[]> another =
                new ConsumerRecord<>("another.topic", 0, 0L, null, null);

        assertEquals(new TopicPartition("transaction.authorized.DLT", -1),
                KafkaConsumerConfig.resolveDeadLetterDestination(
                        authorized, "carddemo.dead-letter", ".DLT"));
        assertEquals(new TopicPartition("another.topic.DLT", -1),
                KafkaConsumerConfig.resolveDeadLetterDestination(
                        another, "carddemo.dead-letter", ".DLT"));
    }

    @Test
    @DisplayName("diagnostics take the deepest failure type and never its message")
    void diagnosticsTakeOnlyTheFailureType() {
        IllegalStateException root = new IllegalStateException(REFUSED_JSON);
        RuntimeException wrapper = new RuntimeException(FULL_CARD_NUMBER, root);

        Headers headers = KafkaConsumerConfig.diagnosticHeaders(wrapper);

        assertEquals("IllegalStateException",
                text(headers.lastHeader(KafkaConsumerConfig.HEADER_REASON)));
        assertFalse(text(headers.lastHeader(KafkaConsumerConfig.HEADER_REASON))
                .contains(FULL_CARD_NUMBER));
    }

    /**
     * Asserts the dead-letter route is terminal: the offset of a record it published is committed.
     *
     * <p>Both containers of this service acknowledge by hand, so nothing acknowledges a record its
     * listener never accepted. Left at its default this setting committed nothing, so the next
     * start-up or the next partition assignment read the same refused record again, published a
     * second diagnostic for the same coordinates and counted one record twice under
     * {@code carddemo.ledger.dead.letters}.
     */
    @Test
    @DisplayName("a record the route published has its offset committed, so it is read once")
    void aDeadLetteredRecordHasItsOffsetCommitted() {
        DefaultErrorHandler errorHandler = terminalOnFirstFailureErrorHandler();
        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, Object> refused =
                new ConsumerRecord<>(SOURCE_TOPIC, 1, 4L, "00000000011", REFUSED_JSON);

        assertEquals(Boolean.TRUE,
                ReflectionTestUtils.getField(errorHandler, "commitRecovered"),
                "commitRecovered, which the container applies under MANUAL_IMMEDIATE");
        assertTrue(errorHandler.seeksAfterHandling(),
                "the container hands a record to handleRemaining, which is the path asserted below");

        errorHandler.handleRemaining(new IllegalStateException("the posting did not complete"),
                List.of(refused), consumer, manualImmediateContainer());

        verify(consumer).commitSync(
                eq(Map.of(new TopicPartition(SOURCE_TOPIC, 1), new OffsetAndMetadata(5L))),
                nullable(Duration.class));
    }

    /**
     * A container acknowledging by hand and at once, which is what the shipped
     * {@code spring.kafka.listener.ack-mode} names.
     */
    private static MessageListenerContainer manualImmediateContainer() {
        ContainerProperties containerProperties = new ContainerProperties(SOURCE_TOPIC);
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(containerProperties);
        return container;
    }

    /**
     * The shipped handler with one delivery, so the first failure spends the backoff at once. Every
     * value except the attempt count matches the shipped configuration.
     */
    @SuppressWarnings("unchecked")
    private static DefaultErrorHandler terminalOnFirstFailureErrorHandler() {
        LedgerProperties properties = new LedgerProperties(
                new LedgerProperties.Kafka(new LedgerProperties.Kafka.Topics(SOURCE_TOPIC,
                        "transaction.declined", "account.state-changed", "transaction.posted",
                        "carddemo.dead-letter", ".DLT")),
                new LedgerProperties.Consumer(new LedgerProperties.Consumer.Retry(1, 0L)),
                new LedgerProperties.Outbox(new LedgerProperties.Outbox.Relay(1000L, 100,
                        "ledger-relay", Duration.ofMinutes(2L), 5_000L), 168L),
                new LedgerProperties.Retention(3_600_000L, 90));

        return new KafkaConsumerConfig().ledgerConsumerErrorHandler(mock(KafkaTemplate.class),
                properties, new ObservabilityConfig().ledgerMeters(new SimpleMeterRegistry()));
    }

    /**
     * Reads the outgoing value as the text it holds.
     *
     * <p>The recoverer renders the diagnostic and hands it over as bytes, because the value
     * serializer of its template writes bytes rather than validating an event against a schema. The
     * envelope form of the same four components carries the other case, an outbox row the relay gave
     * up on, and that one is bound to the shared dead-letter topic.
     *
     * @param outgoing the record the recoverer built
     * @return the value decoded as text
     */
    private static String valueText(ProducerRecord<Object, Object> outgoing) {
        assertTrue(outgoing.value() instanceof byte[],
                "the outgoing value is " + outgoing.value().getClass().getName()
                        + ", and a source-specific destination carries bytes");
        return new String((byte[]) outgoing.value(), StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] intBytes(int value) {
        return java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static byte[] longBytes(long value) {
        return java.nio.ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    /**
     * Asserts the bound acknowledgement mode has to be the immediate manual one, not merely a manual
     * one.
     *
     * <p>{@code MANUAL} was admitted here until this contract tightened. Both manual modes leave the
     * acknowledgement to the listener, but the framework commits a recovered offset and honours
     * {@code setCommitRecovered(true)} under {@code MANUAL_IMMEDIATE} alone: under {@code MANUAL} it
     * reports the setting as ignored, so a dead-lettered record keeps its offset and is published
     * again after the next restart or rebalance. Admitting the weaker mode therefore admitted a
     * deployment in which this service's terminal dead-letter route is not terminal.
     */
    @Test
    @DisplayName("the immediate manual acknowledgement mode is accepted and answered with")
    void theImmediateManualModeIsAccepted() {
        assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE,
                KafkaConsumerConfig.requireImmediateManualAcknowledgement(
                        ContainerProperties.AckMode.MANUAL_IMMEDIATE));
    }

    @ParameterizedTest
    @EnumSource(value = ContainerProperties.AckMode.class,
            names = "MANUAL_IMMEDIATE", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every other mode stops start-up, MANUAL among them")
    void everyOtherModeStopsStartUp(ContainerProperties.AckMode mode) {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> KafkaConsumerConfig.requireImmediateManualAcknowledgement(mode));

        assertTrue(refused.getMessage().contains("spring.kafka.listener.ack-mode"),
                "the refusal names the property a deployment has to change: " + refused.getMessage());
        assertTrue(refused.getMessage().contains(mode.name()),
                "the refusal names the mode it found: " + refused.getMessage());
    }

    @Test
    @DisplayName("an absent mode stops start-up, because the framework default acknowledges in batches")
    void anAbsentModeStopsStartUp() {
        assertThrows(IllegalStateException.class,
                () -> KafkaConsumerConfig.requireImmediateManualAcknowledgement(null));
    }

    private static String text(Header header) {
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static int count(Headers headers, String name) {
        int count = 0;
        for (Header ignored : headers.headers(name)) {
            count++;
        }
        return count;
    }
}
