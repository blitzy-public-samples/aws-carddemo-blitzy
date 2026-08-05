package com.carddemo.notification.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.notification.messaging.DeadLetterMetadata;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

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

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
