package com.carddemo.account.config;

import com.carddemo.account.config.ObservabilityConfig.AccountMeters;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Asserts the delivery-attempt policy and the dead-letter route of the one listener this service
 * runs.
 *
 * <p>ADDITIVE IN FULL. {@code app/cbl/CBTRN02C.cbl:L707-L711} answers a fault by moving 999 into an
 * abend code and calling the language-environment abend service, on the first failure, with no
 * cleanup and no second attempt. Nothing here has a source ancestor.
 *
 * <p>Three properties are held.
 *
 * <ul>
 *   <li>A record is taken as many times as the configured policy allows and then routed once. A
 *       policy that never gives up blocks its partition for ever; one that gives up immediately turns
 *       a restarting database into a lost posting.</li>
 *   <li>A refusal a redelivery cannot change is not retried. Bytes a schema control rejected are the
 *       same bytes on the next attempt, so retrying them spends the whole policy to reach the
 *       conclusion the first attempt already reached.</li>
 *   <li>Nothing the refused record carried is republished. The framework recoverer sends the refused
 *       key and value onward, and where a deserializer refused the record that value is the exact
 *       bytes a control rejected — the bytes most likely to hold a Primary Account Number a producer
 *       put in the wrong field, or the three-digit verification value
 *       {@code app/cpy/CVACT02Y.cpy:L7} declares. Republishing them moves a value past the control
 *       that stopped it and onto a topic with a different set of readers. That is CWE-200 and
 *       CWE-532.</li>
 * </ul>
 *
 * <p>The sanitization assertions use sentinel values rather than realistic ones, because a test that
 * searches for a realistic Primary Account Number can pass by coincidence when a digit run appears in
 * a timestamp.
 *
 * <p>No application context, database or broker starts here.
 */
@DisplayName("KafkaConsumerConfig, the delivery-attempt policy and dead-letter route of the account "
        + "service")
class KafkaConsumerConfigTest {

    /** The topic the recoverer is configured to publish to, shared by every service. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The topic the refused record arrived on. */
    private static final String SOURCE_TOPIC = "transaction.posted";

    /** The partition the refused record arrived on. */
    private static final int SOURCE_PARTITION = 2;

    /** The offset the refused record arrived at. */
    private static final long SOURCE_OFFSET = 4_117L;

    /** Deliveries of one record the policy under test allows, counting the first. */
    private static final int MAX_ATTEMPTS = 3;

    /** Milliseconds between two deliveries, kept short so the test does not wait. */
    private static final long BACKOFF_MS = 1L;

    /** A digit run no timestamp or offset can produce, standing in for a card number. */
    private static final String SENTINEL_PAN = "4747474747474747";

    /** A second digit run, standing in for the three-digit verification value. */
    private static final String SENTINEL_CVV = "919";

    /** Publications the template captured, one per routed record. */
    private List<ProducerRecord<String, byte[]>> published;

    /** The registry the meters register against. */
    private MeterRegistry registry;

    /** The handler under test. */
    private DefaultErrorHandler errorHandler;

    /**
     * The container the handler reads its acknowledgement mode from.
     *
     * <p>It cannot be absent here. {@code setCommitRecovered(true)} on the shipped handler is what
     * commits the offset of a routed record, and reaching that decision means reading the container's
     * properties. A handler that left the flag off would never look.
     */
    private MessageListenerContainer container;

    /** The consumer the handler commits a routed offset through. */
    private Consumer<?, ?> consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void buildHandler() {
        published = new java.util.ArrayList<>();
        KafkaTemplate<String, byte[]> template = mock(KafkaTemplate.class);
        when(template.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, byte[]> record = invocation.getArgument(0);
            published.add(record);
            return CompletableFuture.completedFuture(new SendResult<>(record,
                    new RecordMetadata(new TopicPartition(DEAD_LETTER_TOPIC, 0), 0L, 0, 0L, 0, 0)));
        });

        registry = new SimpleMeterRegistry();
        AccountMeters meters = new ObservabilityConfig().accountMeters(registry);
        errorHandler = new KafkaConsumerConfig().postedTransactionErrorHandler(template,
                DEAD_LETTER_TOPIC, meters, MAX_ATTEMPTS, BACKOFF_MS);

        ContainerProperties containerProperties = new ContainerProperties(SOURCE_TOPIC);
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(containerProperties);
        consumer = mock(Consumer.class);
    }

    @Test
    @DisplayName("a record is taken the configured number of times and then routed once")
    void aRecordIsTakenTheConfiguredNumberOfTimesAndThenRoutedOnce() {
        ConsumerRecord<String, String> record = deliveredRecord();
        RuntimeException transient_ = new IllegalStateException("the database was restarting");

        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            int taken = attempt;
            assertThatThrownBy(() -> handle(record, transient_))
                    .as("attempt %d of %d must be rethrown, so the container takes the record again",
                            taken, MAX_ATTEMPTS)
                    .isNotNull();
            assertThat(published)
                    .as("nothing may be routed while attempts remain")
                    .isEmpty();
        }

        handle(record, transient_);

        assertThat(published)
                .as("the spent record is routed exactly once")
                .hasSize(1);
        assertThat(published.getFirst().topic()).isEqualTo(DEAD_LETTER_TOPIC);
        assertThat(counter("carddemo.account.dead.letters.published"))
                .as("routed records counted")
                .isEqualTo(1.0D);
    }

    @Test
    @DisplayName("a refusal a redelivery cannot change is routed on the first attempt")
    void aRefusalARedeliveryCannotChangeIsRoutedOnTheFirstAttempt() {
        handle(deliveredRecord(), deserializationFailure());

        assertThat(published)
                .as("bytes a schema control rejected are the same bytes next time, so spending the "
                        + "policy on them reaches a conclusion already reached")
                .hasSize(1);
    }

    @Test
    @DisplayName("the refused bytes and the producer's key are not republished")
    void theRefusedBytesAndTheProducersKeyAreNotRepublished() {
        handle(deliveredRecord(), deserializationFailure());

        String value = new String(published.getFirst().value(), StandardCharsets.UTF_8);
        assertThat(value)
                .as("the refused bytes reached a topic with a different set of readers")
                .doesNotContain(SENTINEL_PAN)
                .doesNotContain(SENTINEL_CVV);
        assertThat(published.getFirst().key())
                .as("a key is producer-controlled, so it can hold anything a payload can")
                .doesNotContain(SENTINEL_PAN);
    }

    @Test
    @DisplayName("a producer-chosen header is dropped and the governed envelope travels instead")
    void aProducerChosenHeaderIsDroppedAndTheGovernedEnvelopeTravelsInstead() {
        handle(deliveredRecord(), deserializationFailure());

        ProducerRecord<String, byte[]> routed = published.getFirst();
        for (Header header : routed.headers()) {
            String text = header.value() == null
                    ? ""
                    : new String(header.value(), StandardCharsets.UTF_8);
            assertThat(text)
                    .as("header %s carried a refused value onward", header.key())
                    .doesNotContain(SENTINEL_PAN)
                    .doesNotContain(SENTINEL_CVV);
        }

        String value = new String(routed.value(), StandardCharsets.UTF_8);
        assertThat(value)
                .as("the diagnostic must name the coordinates that identify the refused record, "
                        + "since a failure nothing identifies cannot be investigated")
                .contains(SOURCE_TOPIC)
                .contains(String.valueOf(SOURCE_PARTITION))
                .contains(String.valueOf(SOURCE_OFFSET));
    }

    @Test
    @DisplayName("the route publishes to the one shared dead-letter topic, not a derived one")
    void theRoutePublishesToTheOneSharedDeadLetterTopic() {
        handle(deliveredRecord(), deserializationFailure());

        assertThat(published.getFirst().topic())
                .as("this service routes to the shared topic, so no per-source destination has to be "
                        + "created or granted")
                .isEqualTo(DEAD_LETTER_TOPIC)
                .isNotEqualTo(SOURCE_TOPIC + ".DLT");
    }

    /**
     * Hands one refused record and its failure to the handler, as the container does per delivery.
     *
     * <p>The handler rethrows while attempts remain and routes the record once they are spent, so a
     * caller counting invocations is counting deliveries.
     *
     * @param record  the delivery the listener refused
     * @param failure the failure the listener raised
     */
    private void handle(ConsumerRecord<String, String> record, Exception failure) {
        errorHandler.handleRemaining(failure, List.of(record), consumer, container);
    }

    /**
     * Builds one delivery carrying a sentinel in both its key and its value.
     *
     * @return the record every case here refuses
     */
    private static ConsumerRecord<String, String> deliveredRecord() {
        return new ConsumerRecord<>(SOURCE_TOPIC, SOURCE_PARTITION, SOURCE_OFFSET,
                0L, TimestampType.CREATE_TIME, 0, 0, SENTINEL_PAN,
                "{\"cardNumber\":\"" + SENTINEL_PAN + "\",\"cvv\":\"" + SENTINEL_CVV + "\"}",
                new org.apache.kafka.common.header.internals.RecordHeaders(), java.util.Optional.empty());
    }

    /**
     * Builds the failure a schema-validating deserializer raises, carrying the refused bytes.
     *
     * <p>The bytes reach the recoverer through the exception rather than through the record, which is
     * why a recoverer that reads them republishes exactly what a control rejected.
     *
     * @return the failure the shipped deserializer raises on a payload no schema accepts
     */
    private static DeserializationException deserializationFailure() {
        byte[] refused = ("{\"cardNumber\":\"" + SENTINEL_PAN + "\",\"cvv\":\"" + SENTINEL_CVV
                + "\"}").getBytes(StandardCharsets.UTF_8);
        return new DeserializationException("no schema accepts this payload", refused, false,
                new IllegalArgumentException("schema validation failed"));
    }

    /**
     * Reads one untagged counter back from the registry.
     *
     * @param name the meter name
     * @return the count the meter carries
     */
    private double counter(String name) {
        return registry.get(name).counter().count();
    }
}
