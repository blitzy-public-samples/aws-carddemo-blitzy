package com.carddemo.fraud.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.KafkaConsumerConfig;
import com.carddemo.fraud.config.ObservabilityConfig;
import com.carddemo.fraud.domain.RiskScoringService;
import com.carddemo.fraud.domain.RiskScoringService.RiskAssessment;
import com.carddemo.fraud.outbox.OutboxWriter;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.carddemo.fraud.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Asserts where a failed delivery lands, how many attempts it takes, and which headers travel with
 * it to the dead-letter topic.
 *
 * <p>The source repository is written in Common Business Oriented Language (COBOL). The fraud
 * detection service is net new; no COBOL ancestor exists.
 *
 * <p>A refused JavaScript Object Notation (JSON) document reaches the dead-letter topic of the
 * topic it arrived on, and the listener method stays uninvoked. A retryable infrastructure failure
 * reaches the listener three times in total, acknowledges nothing, and then reaches the same
 * dead-letter topic. A valid document consumed afterwards is assessed and acknowledged.
 *
 * <p>The broker is the embedded one spring-kafka-test 4.1.0 supplies, and no container starts.
 * {@code config/KafkaConsumerConfig} carries every bean the route needs, and this class declares
 * none of them.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        classes = {
                KafkaConsumerConfig.class,
                ObservabilityConfig.class,
                DeadLetterRoutingTest.ListenerUnderTest.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.security.protocol=PLAINTEXT",
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password"
        })
@EmbeddedKafka(
        count = 1,
        partitions = 1,
        bootstrapServersProperty = EmbeddedKafkaBroker.SPRING_EMBEDDED_KAFKA_BROKERS,
        topics = {
                DeadLetterRoutingTest.INBOUND_TOPIC,
                DeadLetterRoutingTest.SOURCE_DEAD_LETTER_TOPIC,
                DeadLetterRoutingTest.FALLBACK_DEAD_LETTER_TOPIC
        })
@DisplayName("Dead-letter routing of the fraud detection listener container")
class DeadLetterRoutingTest {

    /** The one topic the fraud listener reads. */
    static final String INBOUND_TOPIC = "transaction.authorized";

    /** Suffix appended to a source topic to name its dead-letter topic. */
    static final String DEAD_LETTER_SUFFIX = ".DLT";

    /** The dead-letter topic belonging to {@link #INBOUND_TOPIC}. */
    static final String SOURCE_DEAD_LETTER_TOPIC = INBOUND_TOPIC + DEAD_LETTER_SUFFIX;

    /** The dead-letter topic a record with no source topic reaches. */
    static final String FALLBACK_DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** The transaction identifier, sixteen characters, assembled from two literals. */
    private static final String TRANSACTION_ID = "0000000000" + "683580";

    /** The account identifier, eleven characters, and the key of every record published here. */
    private static final String ACCOUNT_ID = "00000000007";

    /** An account identifier of nine characters, which the account pattern refuses. */
    private static final String SHORT_ACCOUNT_ID = "000000007";

    /** The moment a stubbed assessment reports. */
    private static final Instant ASSESSED_AT = Instant.parse("2022-06-10T19:27:53.412Z");

    /** Consumer group of the probe that reads the dead-letter topics. */
    private static final String PROBE_GROUP = "dead-letter-routing-probe";

    /** Ceiling on every wait here. It bounds a test method and measures nothing. */
    private static final Duration AWAIT_CEILING = Duration.ofSeconds(60);

    /** How long one probe read blocks for. */
    private static final Duration PROBE_READ = Duration.ofMillis(200);

    /** How long the follow-up probe read blocks for while no further record is expected. */
    private static final Duration FOLLOW_UP_READ = Duration.ofMillis(500);

    /** Only partition of every topic the embedded broker creates here. */
    private static final int ONLY_PARTITION = 0;

    /** Attempts one delivery takes: the first attempt and two retries. */
    private static final int TOTAL_ATTEMPTS = 3;

    /**
     * A document every property of the contract accepts.
     *
     * <p>The wire form is flat: five envelope properties beside fourteen payload properties, and no
     * {@code envelope} key.
     */
    private static final String VALID_DOCUMENT = """
            {
              "eventId": "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418",
              "eventType": "TransactionAuthorized",
              "schemaVersion": 1,
              "occurredAt": "2022-06-10T19:27:53.412Z",
              "aggregateId": "%s",
              "transactionId": "%s",
              "accountId": "%s",
              "transactionTypeCode": "01",
              "merchantCategoryCode": "0001",
              "source": "POS TERM",
              "description": "Purchase at Abshire-Lowe",
              "amount": "504.77",
              "merchantId": "800000000",
              "merchantName": "Abshire-Lowe",
              "merchantCity": "North Enoshaven",
              "merchantZip": "72112",
              "maskedCardNumber": "************7065",
              "authorizedAt": "2022-06-10 19:27:53.000000",
              "currency": "USD"
            }
            """.formatted(ACCOUNT_ID, TRANSACTION_ID, ACCOUNT_ID);

    /** The valid document with one property the closed object declares nowhere. */
    private static final String UNKNOWN_PROPERTY_DOCUMENT = """
            {
              "eventId": "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418",
              "eventType": "TransactionAuthorized",
              "schemaVersion": 1,
              "occurredAt": "2022-06-10T19:27:53.412Z",
              "aggregateId": "%s",
              "transactionId": "%s",
              "accountId": "%s",
              "transactionTypeCode": "01",
              "merchantCategoryCode": "0001",
              "source": "POS TERM",
              "description": "Purchase at Abshire-Lowe",
              "amount": "504.77",
              "merchantId": "800000000",
              "merchantName": "Abshire-Lowe",
              "merchantCity": "North Enoshaven",
              "merchantZip": "72112",
              "maskedCardNumber": "************7065",
              "authorizedAt": "2022-06-10 19:27:53.000000",
              "currency": "USD",
              "replayOf": "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418"
            }
            """.formatted(ACCOUNT_ID, TRANSACTION_ID, ACCOUNT_ID);

    /** The payload at the top level beside an {@code envelope} object holding the envelope. */
    private static final String NESTED_ENVELOPE_DOCUMENT = """
            {
              "envelope": {
                "eventId": "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418",
                "eventType": "TransactionAuthorized",
                "schemaVersion": 1,
                "occurredAt": "2022-06-10T19:27:53.412Z",
                "aggregateId": "%s"
              },
              "transactionId": "%s",
              "accountId": "%s",
              "transactionTypeCode": "01",
              "merchantCategoryCode": "0001",
              "source": "POS TERM",
              "description": "Purchase at Abshire-Lowe",
              "amount": "504.77",
              "merchantId": "800000000",
              "merchantName": "Abshire-Lowe",
              "merchantCity": "North Enoshaven",
              "merchantZip": "72112",
              "maskedCardNumber": "************7065",
              "authorizedAt": "2022-06-10 19:27:53.000000",
              "currency": "USD"
            }
            """.formatted(ACCOUNT_ID, TRANSACTION_ID, ACCOUNT_ID);

    /** The valid document carrying an aggregate identifier of nine characters. */
    private static final String PATTERN_VIOLATION_DOCUMENT = """
            {
              "eventId": "3f1d9c62-8b4e-4a17-9f0c-2d6a5e73b418",
              "eventType": "TransactionAuthorized",
              "schemaVersion": 1,
              "occurredAt": "2022-06-10T19:27:53.412Z",
              "aggregateId": "%s",
              "transactionId": "%s",
              "accountId": "%s",
              "transactionTypeCode": "01",
              "merchantCategoryCode": "0001",
              "source": "POS TERM",
              "description": "Purchase at Abshire-Lowe",
              "amount": "504.77",
              "merchantId": "800000000",
              "merchantName": "Abshire-Lowe",
              "merchantCity": "North Enoshaven",
              "merchantZip": "72112",
              "maskedCardNumber": "************7065",
              "authorizedAt": "2022-06-10 19:27:53.000000",
              "currency": "USD"
            }
            """.formatted(SHORT_ACCOUNT_ID, TRANSACTION_ID, SHORT_ACCOUNT_ID);

    /** The embedded broker every producer and probe of this class connects to. */
    @Autowired
    private EmbeddedKafkaBroker broker;

    /** The listener bean, which counts the acknowledgements the container hands it. */
    @Autowired
    private AcknowledgementRecordingConsumer consumer;

    /** Scoring double, so a delivery either scores or fails on demand. */
    @MockitoBean
    private RiskScoringService riskScoring;

    /** Assessment store double. */
    @MockitoBean
    private FraudAssessmentRepository assessments;

    /** Marker store double, whose claim decides whether a delivery proceeds. */
    @MockitoBean
    private ProcessedEventRepository processedEvents;

    /** Outbox writer double, so no transaction and no database are required. */
    @MockitoBean
    private OutboxWriter outboxWriter;

    /** Clears the acknowledgement count the previous test method left behind. */
    @BeforeEach
    void forgetEarlierAcknowledgements() {
        consumer.forgetAcknowledgements();
    }

    @Test
    @DisplayName("A document carrying an undeclared property reaches the dead-letter topic and no "
            + "listener runs")
    void undeclaredPropertyReachesTheDeadLetterTopic() {
        try (KafkaConsumer<String, byte[]> probe = deadLetterProbe()) {
            publishToInboundTopic(UNKNOWN_PROPERTY_DOCUMENT);

            assertOneRefusedDelivery(readOneDeadLetterRecord(probe));
        }
    }

    @Test
    @DisplayName("A document nesting its envelope reaches the dead-letter topic and no listener runs")
    void nestedEnvelopeReachesTheDeadLetterTopic() {
        try (KafkaConsumer<String, byte[]> probe = deadLetterProbe()) {
            publishToInboundTopic(NESTED_ENVELOPE_DOCUMENT);

            assertOneRefusedDelivery(readOneDeadLetterRecord(probe));
        }
    }

    @Test
    @DisplayName("A document breaking the account pattern reaches the dead-letter topic and no "
            + "listener runs")
    void accountPatternViolationReachesTheDeadLetterTopic() {
        try (KafkaConsumer<String, byte[]> probe = deadLetterProbe()) {
            publishToInboundTopic(PATTERN_VIOLATION_DOCUMENT);

            assertOneRefusedDelivery(readOneDeadLetterRecord(probe));
        }
    }

    @Test
    @DisplayName("A retryable failure takes three attempts in total, acknowledges nothing, and "
            + "reaches the dead-letter topic")
    void aRetryableFailureTakesThreeAttemptsThenReachesTheDeadLetterTopic() {
        when(processedEvents.claimEvent(any(), any(), any())).thenReturn(1);
        when(riskScoring.assess(any()))
                .thenThrow(new QueryTimeoutException("the assessment store did not answer"));

        try (KafkaConsumer<String, byte[]> probe = deadLetterProbe()) {
            publishToInboundTopic(VALID_DOCUMENT);
            ConsumerRecord<String, byte[]> deadLetter = readOneDeadLetterRecord(probe);

            verify(riskScoring, times(TOTAL_ATTEMPTS)).assess(any());
            assertThat(consumer.acknowledgements())
                    .as("acknowledgements while every attempt failed")
                    .isZero();
            assertThat(deadLetter.topic())
                    .as("dead-letter topic the exhausted delivery reached")
                    .isEqualTo(SOURCE_DEAD_LETTER_TOPIC);
            assertThat(deadLetter.value())
                    .as("bytes delivered to the dead-letter topic")
                    .isNotNull()
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("A valid document consumed after a refused one is assessed and acknowledged once")
    void aValidDocumentAfterARefusedOneIsAssessedAndAcknowledged() {
        when(processedEvents.claimEvent(any(), any(), any())).thenReturn(1);
        when(riskScoring.assess(any())).thenReturn(clearedAssessment());

        try (KafkaConsumer<String, byte[]> probe = deadLetterProbe()) {
            publishToInboundTopic(UNKNOWN_PROPERTY_DOCUMENT);
            readOneDeadLetterRecord(probe);

            publishToInboundTopic(VALID_DOCUMENT);
            await().atMost(AWAIT_CEILING)
                    .pollDelay(Duration.ZERO)
                    .untilAsserted(() -> verify(outboxWriter).write(any()));

            assertThat(consumer.acknowledgements())
                    .as("acknowledgements after one assessed delivery")
                    .isEqualTo(1);
            assertThat(readRecords(probe, FOLLOW_UP_READ))
                    .as("dead-letter records for the assessed delivery")
                    .isEmpty();
        }
    }

    /**
     * Holds one dead-letter record to the route a refused document takes.
     *
     * @param deadLetter the record the probe read from a dead-letter topic
     */
    private void assertOneRefusedDelivery(ConsumerRecord<String, byte[]> deadLetter) {
        assertThat(deadLetter.topic())
                .as("dead-letter topic the refused document reached")
                .isEqualTo(SOURCE_DEAD_LETTER_TOPIC);
        assertThat(deadLetter.key())
                .as("key of the dead-letter record")
                .isNotNull();
        assertThat(deadLetter.value())
                .as("bytes delivered to the dead-letter topic")
                .isNotNull()
                .isNotEmpty();
        assertThat(headerText(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC))
                .as("original topic header")
                .isEqualTo(INBOUND_TOPIC);
        assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION))
                .as("original partition header")
                .isNotNull();
        assertThat(deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET))
                .as("original offset header")
                .isNotNull();

        verifyNoInteractions(riskScoring, outboxWriter);
        assertThat(consumer.acknowledgements())
                .as("acknowledgements for a document the listener never received")
                .isZero();
    }

    /** One assessment below the flag threshold, naming no rule. */
    private static RiskAssessment clearedAssessment() {
        return new RiskAssessment(TRANSACTION_ID, ACCOUNT_ID, 0, false, List.of(), ASSESSED_AT);
    }

    /**
     * Reads one header of a dead-letter record as text.
     *
     * @param record the dead-letter record
     * @param name   the header name
     * @return the header value as text, or {@code null} where the header is absent
     */
    private static String headerText(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Publishes one document to the inbound topic under the account identifier as key.
     *
     * @param document the JSON text to publish
     */
    private void publishToInboundTopic(String document) {
        Map<String, Object> settings =
                new HashMap<>(KafkaTestUtils.producerProps(broker.getBrokersAsString()));
        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(settings)) {
            producer.send(new ProducerRecord<>(INBOUND_TOPIC, ACCOUNT_ID, document));
            producer.flush();
        }
    }

    /**
     * Builds a probe positioned at the end of both dead-letter topics, so it reads only what
     * follows.
     *
     * @return the probe, which the caller closes
     */
    private KafkaConsumer<String, byte[]> deadLetterProbe() {
        Map<String, Object> settings = new HashMap<>(
                KafkaTestUtils.consumerProps(broker.getBrokersAsString(), PROBE_GROUP, false));
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        List<TopicPartition> partitions = List.of(
                new TopicPartition(SOURCE_DEAD_LETTER_TOPIC, ONLY_PARTITION),
                new TopicPartition(FALLBACK_DEAD_LETTER_TOPIC, ONLY_PARTITION));

        KafkaConsumer<String, byte[]> probe = new KafkaConsumer<>(settings);
        probe.assign(partitions);
        probe.seekToEnd(partitions);
        partitions.forEach(probe::position);
        return probe;
    }

    /**
     * Waits for one dead-letter record, then confirms no second record follows it.
     *
     * @param probe the probe reading both dead-letter topics
     * @return the one record read
     */
    private ConsumerRecord<String, byte[]> readOneDeadLetterRecord(
            KafkaConsumer<String, byte[]> probe) {
        List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
        await().atMost(AWAIT_CEILING)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    received.addAll(readRecords(probe, PROBE_READ));
                    return !received.isEmpty();
                });
        received.addAll(readRecords(probe, FOLLOW_UP_READ));

        assertThat(received).as("dead-letter records for one refused delivery").hasSize(1);
        return received.get(0);
    }

    /**
     * Reads whatever the probe holds within one bounded read.
     *
     * @param probe the probe reading both dead-letter topics
     * @param read  how long the read blocks for
     * @return the records read, which may be empty
     */
    private static List<ConsumerRecord<String, byte[]>> readRecords(
            KafkaConsumer<String, byte[]> probe, Duration read) {
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        probe.poll(read).forEach(records::add);
        return records;
    }

    /**
     * Wires the real listener, the real meters and the four doubles the listener takes.
     *
     * <p>Listener annotation processing is enabled here, and the container factory, the error
     * handler, the backoff and the raw-byte dead-letter template all arrive from
     * {@code config/KafkaConsumerConfig}.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @EnableConfigurationProperties(KafkaProperties.class)
    static class ListenerUnderTest {

        /** The registry the fraud meters record against. */
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        /**
         * Registers the listener, whose annotation names the topic, the group and the factory.
         *
         * @param riskScoring     scoring double
         * @param assessments     assessment store double
         * @param processedEvents marker store double
         * @param outboxWriter    outbox writer double
         * @param meters          the recording surface the listener and the route share
         * @param self            provider of this bean, through which the business method is called
         * @return the listener bean
         */
        @Bean
        AcknowledgementRecordingConsumer transactionAuthorizedConsumer(
                RiskScoringService riskScoring, FraudAssessmentRepository assessments,
                ProcessedEventRepository processedEvents, OutboxWriter outboxWriter,
                ObservabilityConfig.FraudMeters meters,
                ObjectProvider<TransactionAuthorizedConsumer> self) {
            return new AcknowledgementRecordingConsumer(riskScoring, assessments, processedEvents,
                    outboxWriter, meters, self);
        }
    }

    /**
     * The production listener, counting each acknowledgement the container hands it.
     *
     * <p>The overriding method adds the count and delegates; the topic, the consumer group and the
     * container factory stay those of the production annotation.
     */
    static class AcknowledgementRecordingConsumer extends TransactionAuthorizedConsumer {

        /** Acknowledgements the container has taken since the last reset. */
        private final AtomicInteger acknowledgements = new AtomicInteger();

        /** Passes every collaborator straight to the production constructor. */
        AcknowledgementRecordingConsumer(RiskScoringService riskScoring,
                FraudAssessmentRepository assessments, ProcessedEventRepository processedEvents,
                OutboxWriter outboxWriter, ObservabilityConfig.FraudMeters meters,
                ObjectProvider<TransactionAuthorizedConsumer> self) {
            super(riskScoring, assessments, processedEvents, outboxWriter, meters, self);
        }

        @Override
        public void onTransactionAuthorized(
                ConsumerRecord<String, TransactionAuthorized> consumerRecord,
                Acknowledgment acknowledgment) {
            super.onTransactionAuthorized(consumerRecord, () -> {
                acknowledgements.incrementAndGet();
                acknowledgment.acknowledge();
            });
        }

        /**
         * Reports how many acknowledgements the container has taken.
         *
         * @return the count since the last reset
         */
        int acknowledgements() {
            return acknowledgements.get();
        }

        /** Sets the acknowledgement count back to zero. */
        void forgetAcknowledgements() {
            acknowledgements.set(0);
        }
    }

}
