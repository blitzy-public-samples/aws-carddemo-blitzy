package com.carddemo.authorization.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the serializers the shipped {@code application.yml} names can write what
 * {@link KafkaEventPublisher} hands the template.
 *
 * <p>This class has no COBOL ancestor. It exists because a serializer that cannot write
 * the published value fails in the one place nothing watches: the relay logs a warning, leaves the
 * row unpublished, retries on the next sweep and reports the service healthy throughout. A name
 * comparison alone would not have caught it either, because the wrong name reads like the careful
 * choice. So the value serializer the file names is instantiated here and asked to write a
 * {@code String}, which is what {@code EventPublisherPort.publish} declares and what the
 * {@code outbox_event} payload column holds.
 *
 * <p>Nothing connects anywhere. The context holds one properties bean, and the serializer runs in
 * this test's own thread.
 */
@DisplayName("The producer serializers the shipped application.yml names")
class ShippedProducerSerializerTest {

    /**
     * One written event, as the payload column stores it. The content is checked before the row
     * commits by {@code OutboxWriter} and again by {@link KafkaEventPublisher}; what matters here is
     * only that it is text.
     */
    private static final String WRITTEN_EVENT = """
            {"eventId":"11111111-1111-4111-8111-111111111111",\
            "eventType":"TransactionAuthorized","schemaVersion":1,\
            "occurredAt":"2024-01-01T00:00:00.000Z","aggregateId":"00000000011"}""";

    /** The account identifier the publisher sends as the message key. */
    private static final String MESSAGE_KEY = "00000000011";

    /** Registers the Kafka properties bean and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaProperties.class)
    static class KafkaPropertiesEnabled {
    }

    /** A context whose only property source is the shipped {@code application.yml}. */
    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(KafkaPropertiesEnabled.class);

    @Test
    @DisplayName("both serializers the file names take text")
    void bothShippedSerializersTakeText() {
        shipped.run(context -> {
            KafkaProperties bound = context.getBean(KafkaProperties.class);

            assertAll(
                    () -> assertEquals(StringSerializer.class, bound.getProducer().getKeySerializer(),
                            "spring.kafka.producer.key-serializer: the key is an account "
                                    + "identifier as text, so its leading zero survives"),
                    () -> assertEquals(StringSerializer.class,
                            bound.getProducer().getValueSerializer(),
                            "spring.kafka.producer.value-serializer: the value is the written "
                                    + "event the payload column holds, which is text"));
        });
    }

    @Test
    @DisplayName("the value serializer writes one written event unchanged")
    void theValueSerializerWritesOneWrittenEventUnchanged() {
        shipped.run(context -> {
            KafkaProperties bound = context.getBean(KafkaProperties.class);
            Class<?> named = bound.getProducer().getValueSerializer();

            @SuppressWarnings("unchecked")
            Serializer<Object> serializer =
                    (Serializer<Object>) named.getDeclaredConstructor().newInstance();
            serializer.configure(Map.of(), false);

            byte[] written = assertDoesNotThrow(
                    () -> serializer.serialize("transaction.authorized", WRITTEN_EVENT),
                    named.getName() + " cannot write the String that "
                            + "EventPublisherPort.publish declares, so every publish of this "
                            + "service would fail and its outbox would never drain");

            assertArrayEquals(WRITTEN_EVENT.getBytes(StandardCharsets.UTF_8), written,
                    "the broker must receive the written event unchanged, byte for byte");
        });
    }

    @Test
    @DisplayName("the key serializer writes the account identifier unchanged")
    void theKeySerializerWritesTheAccountIdentifierUnchanged() {
        shipped.run(context -> {
            KafkaProperties bound = context.getBean(KafkaProperties.class);
            Class<?> named = bound.getProducer().getKeySerializer();

            @SuppressWarnings("unchecked")
            Serializer<Object> serializer =
                    (Serializer<Object>) named.getDeclaredConstructor().newInstance();
            serializer.configure(Map.of(), true);

            byte[] written = assertDoesNotThrow(
                    () -> serializer.serialize("transaction.authorized", MESSAGE_KEY),
                    named.getName() + " cannot write the message key the publisher sends");

            assertArrayEquals(MESSAGE_KEY.getBytes(StandardCharsets.UTF_8), written,
                    "every event of one account must land on one partition, so the key must "
                            + "reach the broker exactly as the publisher supplied it");
        });
    }
}
