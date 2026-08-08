package com.carddemo.authorization.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.events.serde.EventContracts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
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
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.test.util.ReflectionTestUtils;

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
 *
 * <p>A three-digit sentinel is short enough to appear by coincidence in the two members the envelope
 * generates for itself, so the scan for it runs over {@link #recordDerivedText()} rather than over
 * the whole envelope. {@code eventId} is a random universally unique identifier and
 * {@code occurredAt} is the moment of the run, and neither carries a value the refused record
 * supplied.
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
     * The aggregate identifier every diagnostic of this service declares and is keyed on.
     *
     * <p>Eleven zeros. {@code schemas/dead-letter-v1.json} requires an account-shaped value and a
     * record this service could not read carries no account it can be trusted to name, so the sentinel
     * says exactly that: this is not an account the platform seeds or issues. It matches the value
     * {@code config/KafkaConsumerConfig} declares, and no producer can influence it.
     */
    private static final String UNRESOLVED_ACCOUNT_KEY = "00000000000";

    /**
     * A sentinel standing in for a full Primary Account Number.
     *
     * <p>Sixteen digits, so it is the width {@code app/cpy/CVACT02Y.cpy:L5} declares, and a value no
     * fixture holds, so finding it in the output can only mean the refused bytes were copied.
     */
    private static final String SENTINEL_PAN = "9999888877776666";

    /**
     * A sentinel standing in for the three-digit verification value, at the declared width.
     *
     * <p>Three digits is the width {@code app/cpy/CVACT02Y.cpy:L7} declares, and a sentinel that
     * narrow occurs inside a random identifier often enough to matter: the envelope carries a
     * generated {@code eventId} of thirty-two hexadecimal characters and a generated
     * {@code occurredAt}, and a run of three digits matching this one appears there by chance in
     * roughly one run in a hundred. The assertions below therefore search the text this service
     * copies rather than the text it generates, which is what {@link #producerInfluencedText()}
     * separates. Widening the sentinel instead would have made it stop standing for a value of the
     * declared width.</p>
     */
    private static final String SENTINEL_CVV = "731";

    /** Members of one envelope that a run generates, which no scan for a record value reads. */
    private static final List<String> GENERATED_MEMBERS = List.of("eventId", "occurredAt");

    /** Reader of the published envelope, used only to drop the generated members before a scan. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        assertFalse(recordDerivedText().contains(SENTINEL_CVV),
                "the verification value was republished");
        assertFalse(producerInfluencedText().contains(SENTINEL_CVV),
                "the verification value reached the dead-letter topic: " + value);
    }

    /**
     * Asserts the key a producer chose is replaced by the aggregate the envelope declares.
     *
     * <p>Two properties are held here at once, and a review found the second one broken.
     *
     * <p>The first is sanitization. A producer controls the key, so a key holding a card number is a
     * key this service must not carry forward, and the sentinel {@value #UNRESOLVED_ACCOUNT_KEY} is a
     * value no producer can influence: eleven zeros are not an account this platform seeds or issues.
     *
     * <p>The second is agreement with the contract. {@code schemas/dead-letter-v1.json} describes
     * {@code aggregateId} as the Kafka message key of the envelope, and the key used to be the
     * coordinates of the refused record — {@code topic-partition-offset} — so every diagnostic
     * contradicted the payload it carried and scattered one shared topic across as many partitions as
     * there were source offsets. The coordinates are not lost by the change: they are three declared
     * fields of the same document, asserted in {@link #oneGovernedEnvelopeTravelsInstead()}.
     */
    @Test
    void theProducerKeyIsReplacedByTheDeclaredAggregate() {
        recover(refusedByTheDeserializer());

        assertEquals(UNRESOLVED_ACCOUNT_KEY, published.key(),
                "the key is the aggregateId the envelope declares, so the record and its payload"
                        + " name one aggregate");
        assertFalse(String.valueOf(published.key()).contains(SENTINEL_PAN),
                "the producer key reached the dead-letter topic");
        assertTrue(valueText().contains("\"aggregateId\":\"" + UNRESOLVED_ACCOUNT_KEY + "\""),
                "and the payload declares the same value the record is keyed on");
    }

    /**
     * Asserts two refusals from different offsets carry one key, so the shared topic stays ordered.
     *
     * <p>This is the consequence of the defect rather than the defect itself. Keyed on coordinates,
     * two diagnostics from one source topic landed on two partitions, so an operator replaying the
     * dead-letter topic read them in no defined order. One key form puts every diagnostic of this
     * service in publish order on one partition.
     */
    @Test
    void twoRefusalsFromDifferentOffsetsCarryOneKey() {
        recover(refusedByTheDeserializer());
        Object firstKey = published.key();

        recover(refusedByTheDeserializer(SOURCE_OFFSET + 1));

        assertEquals(firstKey, published.key(),
                "two refusals carry one key, so they stay on one partition and in publish order");
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
        assertFalse(recordDerivedText().contains(SENTINEL_CVV),
                "the verification value was republished");
        assertFalse(producerInfluencedText().contains(SENTINEL_CVV),
                "the verification value was republished: " + value);
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
     * Asserts a dead-lettered record commits the offset that follows it, so the route is terminal.
     *
     * <p>Both replica listeners acknowledge by hand, so nothing acknowledges a record whose
     * listener never accepted it. Without {@code setCommitRecovered(true)} the offset of a
     * record the route had already published stayed uncommitted, and the next start-up or the
     * next partition assignment read that record again and published a second envelope naming
     * the same coordinates: one refused record, two dead letters, and the replica stage of
     * {@code carddemo.authorization.failures} counting one record more than once. The other
     * four services of this platform set it, so this assertion is what keeps the sixth from
     * being the exception again.
     *
     * <p>The commit is asserted rather than the setting alone, because the container applies the
     * setting only under acknowledgement mode {@code MANUAL_IMMEDIATE}: under {@code MANUAL} it
     * reports the setting as ignored and commits nothing. The container here declares the mode the
     * shipped {@code spring.kafka.listener.ack-mode} names, so the assertion covers the setting and
     * the mode it depends on together.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aDeadLetteredRecordCommitsTheOffsetThatFollowsIt() {
        // One delivery, so the first handling exhausts the budget and reaches the route.
        org.springframework.kafka.listener.DefaultErrorHandler singleDelivery =
                new KafkaConsumerConfig().replicaConsumerErrorHandler(mock(KafkaTemplate.class),
                        DEAD_LETTER_TOPIC, new SimpleMeterRegistry(), 1, 0L);
        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, String> refused = new ConsumerRecord<>(SOURCE_TOPIC,
                SOURCE_PARTITION, SOURCE_OFFSET, SENTINEL_PAN, null);

        assertEquals(Boolean.TRUE,
                ReflectionTestUtils.getField(singleDelivery, "commitRecovered"),
                "the route is not terminal, so a dead-lettered record is read again");
        assertTrue(singleDelivery.isAckAfterHandle(),
                "a handled record has to be acknowledged for the commit to mean anything");

        singleDelivery.handleRemaining(new IllegalStateException("replica store unavailable"),
                List.of(refused), consumer, manualImmediateContainer());

        verify(consumer).commitSync(
                eq(Map.of(new TopicPartition(SOURCE_TOPIC, SOURCE_PARTITION),
                        new OffsetAndMetadata(SOURCE_OFFSET + 1))),
                nullable(Duration.class));
    }

    /**
     * Builds a container declaring the acknowledgement mode the shipped configuration names.
     *
     * @return a container whose properties carry {@code MANUAL_IMMEDIATE}
     */
    private static MessageListenerContainer manualImmediateContainer() {
        ContainerProperties properties = new ContainerProperties(SOURCE_TOPIC);
        properties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(properties);
        return container;
    }

    /**
     * Builds a record the deserializer refused, carrying the sentinels in every place a control
     * would have seen them.
     *
     * @return the refused record, with a producer-chosen key and header
     */
    private static ConsumerRecord<?, ?> refusedByTheDeserializer() {
        return refusedByTheDeserializer(SOURCE_OFFSET);
    }

    /**
     * Builds a refused record at one named offset.
     *
     * @param offset the offset the record arrived at, which two diagnostics differ by
     * @return the refused record
     */
    private static ConsumerRecord<?, ?> refusedByTheDeserializer(long offset) {
        ConsumerRecord<String, String> failed = new ConsumerRecord<>(SOURCE_TOPIC, SOURCE_PARTITION,
                offset, 0L, TimestampType.CREATE_TIME, 0, 0, SENTINEL_PAN, null,
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

    /**
     * Returns the published envelope with the two values this service generates removed.
     *
     * <p>{@code eventId} and {@code occurredAt} are produced here, from a random identifier and a
     * clock, so neither can carry anything a producer sent and neither is evidence of a leak.
     * Everything else in the envelope either names the refused record's coordinates or is fixed
     * text, so a sentinel found in what remains was copied. Removing the two is what lets a
     * three-digit sentinel be searched for at all without a random identifier failing the run.</p>
     *
     * @return the published value as text, with the generated identifier and timestamp blanked
     */
    private String producerInfluencedText() {
        return valueText()
                .replaceAll("\"eventId\"\\s*:\\s*\"[^\"]*\"", "\"eventId\":\"\"")
                .replaceAll("\"occurredAt\"\\s*:\\s*\"[^\"]*\"", "\"occurredAt\":\"\"");
    }

    /** @return the published value rendered as text */
    /**
     * Renders the published envelope without the two members a run generates.
     *
     * <p>{@code eventId} matches the universally-unique-identifier pattern of
     * {@code schemas/dead-letter-v1.json} and {@code occurredAt} matches its date-time pattern.
     * Both change on every run and neither is read from the refused record. A random identifier
     * carries a three-character sentinel roughly once in a hundred renderings, and a scan that read
     * one would report a leak no code performed.
     *
     * @return the same envelope without those two members
     */
    private String recordDerivedText() {
        ObjectNode scanned = (ObjectNode) MAPPER.readTree(valueText());
        scanned.remove(GENERATED_MEMBERS);
        assertFalse(scanned.isEmpty(), "the envelope carries members beyond the generated two");
        return scanned.toString();
    }

    private String valueText() {
        assertNotNull(published, "nothing was published");
        Object value = published.value();
        assertTrue(value instanceof byte[], "the envelope is published as bytes");
        return new String((byte[]) value, StandardCharsets.UTF_8);
    }
}
