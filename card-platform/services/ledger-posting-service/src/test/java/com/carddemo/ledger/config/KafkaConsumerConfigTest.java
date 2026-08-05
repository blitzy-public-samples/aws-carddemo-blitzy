package com.carddemo.ledger.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.ledger.messaging.DeadLetterMetadata;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Verifies that ledger dead letters retain broker coordinates and no refused or producer data.
 */
@DisplayName("Ledger Kafka consumer dead-letter sanitization")
class KafkaConsumerConfigTest {

    private static final String SOURCE_TOPIC = "transaction.authorized";
    private static final String FULL_CARD_NUMBER = "4859452612877065";
    private static final String REFUSED_JSON =
            "{\"cardNumber\":\"" + FULL_CARD_NUMBER + "\",\"cvv\":\"123\"}";

    private static final Set<String> EXPECTED_HEADERS = Set.of(
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
        assembled.add(KafkaConsumerConfig.HEADER_REASON, utf8(FULL_CARD_NUMBER));
        assembled.add(KafkaConsumerConfig.HEADER_REASON, utf8("PayloadRejectedException"));
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
