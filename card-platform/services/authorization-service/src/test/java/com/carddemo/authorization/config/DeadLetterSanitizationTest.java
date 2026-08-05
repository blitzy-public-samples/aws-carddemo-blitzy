package com.carddemo.authorization.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.events.serde.EventContracts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.Deserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * Asserts nothing a refused record carried reaches the dead-letter topic.
 *
 * <p>The framework recoverer republishes the refused key and the refused value, and where a
 * deserializer refused the record that value is {@link DeserializationException#getData()}: the exact
 * bytes a schema control rejected. Those are the bytes most likely to hold a Primary Account Number a
 * producer put in the wrong field or the three-digit verification value {@code app/cpy/CVACT02Y.cpy:L7}
 * declares, so republishing them moves the value that failed a control past the control and onto a
 * topic with a different set of readers. That is CWE-200 and CWE-532, and the override this class
 * exercises is what closes it.
 *
 * <p>Every assertion here is negative in the same way: a sentinel value goes into the refused record
 * and the test proves that value is absent from the key, the value and every header of what the
 * recoverer publishes. A sentinel rather than a realistic value, because a test that searches for a
 * realistic Primary Account Number can pass by coincidence when a digit sequence appears in a
 * timestamp.
 */
@DisplayName("Dead-letter sanitization, the authorization service")
class DeadLetterSanitizationTest {

    /** The topic the recoverer is configured to publish to. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The topic the refused record arrived on. */
    private static final String SOURCE_TOPIC = "account.state-changed";

    /** The partition the refused record arrived on. */
    private static final int SOURCE_PARTITION = 2;

    /** The offset the refused record arrived at. */
    private static final long SOURCE_OFFSET = 4_815_162_342L;

    /**
     * A sentinel standing in for a full Primary Account Number.
     *
     * <p>Sixteen digits, so it is the width {@code app/cpy/CVACT02Y.cpy:L5} declares, and a value no
     * fixture holds, so finding it in the output can only mean the refused bytes were copied.
     */
    private static final String SENTINEL_PAN = "9999888877776666";

    /** A sentinel standing in for the three-digit verification value, at the declared width. */
    private static final String SENTINEL_CVV = "731";

    /** A sentinel standing in for a header a producer chose. */
    private static final String SENTINEL_HEADER_VALUE = "producer-chose-this-" + SENTINEL_PAN;

    /** The record the recoverer publishes, captured from the template. */
    private ProducerRecord<Object, Object> published;

    /** The recoverer under test, reached through the public error-handler bean. */
    private org.springframework.kafka.listener.DefaultErrorHandler errorHandler;

    /** Builds the error handler over a template that captures rather than sends. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            published = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new SendResult<>(published,
                    new RecordMetadata(new TopicPartition(DEAD_LETTER_TOPIC, 0), 0L, 0, 0L, 0, 0)));
        });

        errorHandler = new KafkaConsumerConfig().replicaConsumerErrorHandler(template,
                DEAD_LETTER_TOPIC, new SimpleMeterRegistry(), 3, 10L);
    }

    /**
     * Asserts the bytes a deserializer refused are not republished.
     *
     * <p>This is the case the override exists for. The refused bytes reach the recoverer through the
     * exception rather than through the record, so a recoverer that reads
     * {@link DeserializationException#getData()} publishes exactly what a control rejected.
     */
    @Test
    void theRefusedBytesAreNotRepublished() {
        recover(refusedByTheDeserializer());

        String value = valueText();
        assertFalse(value.contains(SENTINEL_PAN),
                "the refused bytes reached the dead-letter topic: " + value);
        assertFalse(value.contains(SENTINEL_CVV),
                "the verification value reached the dead-letter topic: " + value);
    }

    /**
     * Asserts the key a producer chose is not republished.
     *
     * <p>A producer controls the key, so a key holding a card number is a key this service must not
     * carry forward. The coordinates of the record replace it, and they name the record inside a
     * topic that already carries its own access controls.
     */
    @Test
    void theProducerKeyIsReplacedByTheRecordCoordinates() {
        recover(refusedByTheDeserializer());

        assertEquals(SOURCE_TOPIC + "-" + SOURCE_PARTITION + "-" + SOURCE_OFFSET,
                published.key(), "the key names the refused record rather than its subject");
        assertFalse(String.valueOf(published.key()).contains(SENTINEL_PAN),
                "the producer key reached the dead-letter topic");
    }

    /**
     * Asserts a header a producer chose is dropped with the key that carried it.
     *
     * <p>Headers travel with a record and a producer sets them, so copying the inbound header set
     * would reintroduce through a header exactly what the key and the value no longer carry.
     */
    @Test
    void aProducerChosenHeaderIsDropped() {
        recover(refusedByTheDeserializer());

        for (Header header : published.headers()) {
            String rendered = header.value() == null
                    ? "" : new String(header.value(), StandardCharsets.UTF_8);
            assertFalse(rendered.contains(SENTINEL_PAN),
                    "header " + header.key() + " carried the refused value forward");
        }
        assertNull(published.headers().lastHeader("x-producer-note"),
                "a header a producer chose is not carried forward");
    }

    /**
     * Asserts the message text of the failure is not attached.
     *
     * <p>A deserialization failure names the value it refused inside its own message, so the message
     * is a second copy of the payload and is excluded for the same reason the payload is.
     */
    @Test
    void theFailureMessageIsNotAttached() {
        recover(refusedByTheDeserializer());

        for (Header header : published.headers()) {
            String rendered = header.value() == null
                    ? "" : new String(header.value(), StandardCharsets.UTF_8);
            assertFalse(rendered.contains("cardNumber"),
                    "header " + header.key() + " quoted the refused document");
        }
        assertFalse(valueText().contains("cardNumber"),
                "the published envelope quoted the refused document");
    }

    /**
     * Asserts what is published is one envelope its own contract accepts.
     *
     * <p>Withholding the payload is only half of the requirement. What travels instead has to be
     * readable by whatever inspects the dead-letter topic, which means it has to satisfy
     * {@code schemas/dead-letter-v1.json} rather than being an opaque diagnostic.
     */
    @Test
    void oneGovernedEnvelopeTravelsInstead() {
        recover(refusedByTheDeserializer());

        assertEquals(DEAD_LETTER_TOPIC, published.topic(),
                "the envelope reaches the topic its contract is bound to");
        String value = valueText();
        List<String> violations = EventContracts.violationsOf(EventContracts.DEAD_LETTER, value);
        assertEquals(List.of(), violations,
                "the published envelope breaks its own contract: " + violations);
        assertTrue(value.contains(SOURCE_TOPIC), "the envelope names the source topic");
        assertTrue(value.contains(String.valueOf(SOURCE_OFFSET)),
                "the envelope names the source offset");
    }

    /**
     * Asserts a failure raised after deserialization is sanitized on the same terms.
     *
     * <p>A record that deserialized and then could not be applied still holds its value in the
     * record itself, so the inherited behaviour would serialize that value onto the topic. The
     * override reads neither the key nor the value, so the path is identical.
     */
    @Test
    void aRecordThatDeserializedIsSanitizedOnTheSameTerms() {
        ConsumerRecord<?, ?> failed = new ConsumerRecord<>(SOURCE_TOPIC, SOURCE_PARTITION,
                SOURCE_OFFSET, SENTINEL_PAN,
                "{\"cardNumber\":\"" + SENTINEL_PAN + "\",\"cvv\":\"" + SENTINEL_CVV + "\"}");

        recover(failed, new IllegalStateException("account " + SENTINEL_PAN + " is not present"));

        String value = valueText();
        assertFalse(value.contains(SENTINEL_PAN), "the applied-record value was republished");
        assertFalse(value.contains(SENTINEL_CVV), "the verification value was republished");
        assertEquals(List.of(), EventContracts.violationsOf(EventContracts.DEAD_LETTER, value),
                "the envelope satisfies its contract on this path too");
    }

    /**
     * Asserts a delivery-attempt header a producer chose cannot block the route.
     *
     * <p>The contract bounds the attempt count, so a value outside the bound would fail the
     * envelope's own validation and leave the record with nowhere to go. That would turn a header a
     * producer controls into a way of blocking a partition, so the count is clamped into the range
     * rather than trusted.
     */
    @Test
    void aHostileDeliveryAttemptHeaderCannotBlockTheRoute() {
        ConsumerRecord<?, ?> failed = refusedByTheDeserializer();
        failed.headers().add(org.springframework.kafka.support.KafkaHeaders.DELIVERY_ATTEMPT,
                java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(Integer.MAX_VALUE).array());

        recover(failed);

        assertEquals(List.of(), EventContracts.violationsOf(EventContracts.DEAD_LETTER, valueText()),
                "an out-of-range attempt count left the envelope unpublishable");
    }

    /**
     * Builds a record the deserializer refused, carrying the sentinels in every place a control
     * would have seen them.
     *
     * @return the refused record, with a producer-chosen key and header
     */
    private static ConsumerRecord<?, ?> refusedByTheDeserializer() {
        ConsumerRecord<String, String> failed = new ConsumerRecord<>(SOURCE_TOPIC, SOURCE_PARTITION,
                SOURCE_OFFSET, 0L, TimestampType.CREATE_TIME, 0, 0, SENTINEL_PAN, null,
                new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
        failed.headers().add("x-producer-note",
                SENTINEL_HEADER_VALUE.getBytes(StandardCharsets.UTF_8));
        return failed;
    }

    /**
     * Runs the recoverer over a record the deserializer refused.
     *
     * @param failed the refused record
     */
    private void recover(ConsumerRecord<?, ?> failed) {
        byte[] refusedBytes = ("{\"cardNumber\":\"" + SENTINEL_PAN + "\",\"cvv\":\""
                + SENTINEL_CVV + "\"}").getBytes(StandardCharsets.UTF_8);
        Deserializer<?> refusing = mock(Deserializer.class);
        recover(failed, new DeserializationException(
                "value refused: " + new String(refusedBytes, StandardCharsets.UTF_8),
                refusedBytes, false, new IllegalArgumentException(refusing.getClass().getName())));
    }

    /**
     * Runs the recoverer over one record and one failure, exhausting the attempt policy first.
     *
     * @param failed  the record no attempt could apply
     * @param failure the failure every attempt raised
     */
    private void recover(ConsumerRecord<?, ?> failed, Exception failure) {
        for (int attempt = 0; attempt < 4 && published == null; attempt++) {
            try {
                errorHandler.handleRemaining(failure, List.of(failed), null, null);
            } catch (RuntimeException stillRetryable) {
                if (published != null) {
                    break;
                }
            }
        }
        assertNotNull(published, "the recoverer published nothing");
    }

    /** @return the published value rendered as text */
    private String valueText() {
        assertNotNull(published, "nothing was published");
        Object value = published.value();
        assertTrue(value instanceof byte[], "the envelope is published as bytes");
        return new String((byte[]) value, StandardCharsets.UTF_8);
    }
}
