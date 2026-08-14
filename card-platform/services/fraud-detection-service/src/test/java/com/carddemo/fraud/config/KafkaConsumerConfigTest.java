package com.carddemo.fraud.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.fraud.messaging.DeadLetterMetadata;
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
 * Verifies that the dead-letter route carries sanitized diagnostics and broker coordinates only.
 */
@DisplayName("Fraud Kafka consumer dead-letter sanitization")
class KafkaConsumerConfigTest {

    private static final String SOURCE_TOPIC = "transaction.authorized";
    private static final String FULL_CARD_NUMBER = "4859452612877065";
    private static final String REFUSED_JSON =
            "{\"cardNumber\":\"" + FULL_CARD_NUMBER + "\",\"cvv\":\"123\"}";

    @Test
    @DisplayName("refused key, payload and producer headers never reach the dead-letter record")
    void refusedDataNeverReachesTheDeadLetterRecord() {
        ConsumerRecord<String, byte[]> refused =
                new ConsumerRecord<>(SOURCE_TOPIC, 2, 41L, FULL_CARD_NUMBER,
                        REFUSED_JSON.getBytes(StandardCharsets.UTF_8));
        refused.headers().add("producer-secret", utf8(FULL_CARD_NUMBER));

        Headers assembled = new RecordHeaders();
        assembled.add(KafkaConsumerConfig.HEADER_ABEND_CODE, utf8("0999"));
        assembled.add(KafkaConsumerConfig.HEADER_CULPRIT, utf8("FRAUDSVC"));
        assembled.add(KafkaConsumerConfig.HEADER_REASON, utf8("SerializationException"));
        assembled.add(KafkaConsumerConfig.HEADER_MESSAGE, utf8(FULL_CARD_NUMBER));
        assembled.add(KafkaConsumerConfig.HEADER_MESSAGE,
                utf8(KafkaConsumerConfig.SAFE_FAILURE_MESSAGE));
        assembled.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
                utf8(KafkaConsumerConfig.SAFE_FAILURE_MESSAGE));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, utf8(SOURCE_TOPIC));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, utf8("2"));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, utf8("41"));
        assembled.add(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, utf8("0"));
        assembled.add("producer-secret", utf8(FULL_CARD_NUMBER));

        ProducerRecord<Object, Object> outgoing =
                KafkaConsumerConfig.sanitizedDeadLetterRecord(
                        refused, new TopicPartition(SOURCE_TOPIC + ".DLT", -1), assembled);

        byte[] value = (byte[]) outgoing.value();
        String text = new String(value, StandardCharsets.UTF_8);
        Set<String> headerNames = new HashSet<>();
        outgoing.headers().forEach(header -> headerNames.add(header.key()));

        assertEquals(SOURCE_TOPIC + ".DLT", outgoing.topic(), "destination");
        assertNull(outgoing.partition(), "broker-selected partition");
        assertEquals(SOURCE_TOPIC + "-2-41", outgoing.key(), "coordinate key");
        assertEquals(DeadLetterMetadata.RECORD_LENGTH, value.length, "diagnostic width");
        assertTrue(text.contains(KafkaConsumerConfig.SAFE_FAILURE_MESSAGE), "safe message");
        assertFalse(text.contains(FULL_CARD_NUMBER), "card number in value");
        assertEquals(KafkaConsumerConfig.ALLOWED_HEADERS, headerNames, "header allowlist");
        assertNull(outgoing.headers().lastHeader("producer-secret"), "producer header");
        assertEquals(1, count(outgoing.headers(), KafkaConsumerConfig.HEADER_MESSAGE),
                "one canonical message header");
    }

    @Test
    @DisplayName("the source topic selects its own dead-letter topic")
    void sourceTopicSelectsItsDeadLetterTopic() {
        ConsumerRecord<String, byte[]> authorized =
                new ConsumerRecord<>("transaction.authorized", 0, 0L, null, null);
        ConsumerRecord<String, byte[]> another =
                new ConsumerRecord<>("another.topic", 0, 0L, null, null);

        assertEquals("transaction.authorized.DLT",
                KafkaConsumerConfig.resolveDeadLetterDestination(
                        authorized, "carddemo.dead-letter", ".DLT"));
        assertEquals("another.topic.DLT",
                KafkaConsumerConfig.resolveDeadLetterDestination(
                        another, "carddemo.dead-letter", ".DLT"));
        assertEquals("carddemo.dead-letter",
                KafkaConsumerConfig.resolveDeadLetterDestination(
                        null, "carddemo.dead-letter", ".DLT"));
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static int count(Headers headers, String name) {
        int count = 0;
        for (Header ignored : headers.headers(name)) {
            count++;
        }
        return count;
    }
}
