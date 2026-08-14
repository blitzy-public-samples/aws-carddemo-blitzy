package com.carddemo.fraud.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.correlation.EventCorrelation;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.config.ObservabilityConfig.FraudMeters;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.messaging.DeadLetterMetadata;
import com.carddemo.fraud.messaging.EventPublisherPort;
import com.carddemo.fraud.messaging.KafkaEventPublisher;
import com.carddemo.fraud.messaging.TransactionAuthorizedConsumer;
import com.carddemo.fraud.repository.OutboxEventRepository;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the schedule metadata, the store contract, the message key, the topic each event
 * reaches, and the routing of every failure for {@link OutboxRelay}.
 *
 * <p>The relay and these assertions have no Common Business-Oriented Language (COBOL) source.
 * They are net new; no COBOL ancestor.
 *
 * <p>Fixed event values, a resolved send result and an in-memory store keep every test here
 * independent of a broker, a database, a container and a network. No test starts an application
 * context and no test waits on a clock.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Outbox relay")
public class OutboxRelayTest {

    /** The account identifier every row carries, and therefore every message key. */
    private static final String ACCOUNT_IDENTIFIER = "00000000007";

    /** The transaction identifier every payload carries. */
    private static final String TRANSACTION_IDENTIFIER = "0000000000683580";

    /** The one topic both assessment outcomes reach. */
    private static final String ASSESSED_TOPIC = "fraud.assessed";

    /** The topic a row no tick can publish reaches. */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

    /** Topic name used to show which configured value the relay sends against. */
    private static final String SENTINEL_TOPIC = "sentinel.relay.topic";

    /** The one topic this service reads, needed to build the bound settings. */
    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    /** Suffix appended to a source topic on the dead-letter route. */
    private static final String DEAD_LETTER_SUFFIX = ".DLT";

    /** Rows one tick claims, matching the shipped default. */
    private static final int DEFAULT_BATCH_SIZE = 100;

    /** Milliseconds between the end of one tick and the start of the next. */
    private static final long FIXED_DELAY_MS = 500L;

    /** Name this relay instance writes into the claim column. */
    private static final String RELAY_INSTANCE = "fraud-relay-1";

    /** Row A, carrying a flagged assessment. */
    private static final UUID FLAGGED_EVENT_IDENTIFIER =
            UUID.fromString("7c3a5b2e-4d16-4f8a-b0c5-2e7d6a4f8b31");

    /** Row B, carrying a cleared assessment. */
    private static final UUID CLEARED_EVENT_IDENTIFIER =
            UUID.fromString("2f8b6d40-5c71-4a23-8e6f-3b5d7c2a4e68");

    /** Row C, the third row of a batch. */
    private static final UUID THIRD_EVENT_IDENTIFIER =
            UUID.fromString("5a1e8c37-6b24-4d0f-9a83-7c4b2e6d8f50");

    /** Row D, naming an event type this service does not publish. */
    private static final UUID UNKNOWN_TYPE_EVENT_IDENTIFIER =
            UUID.fromString("3d7f2a64-8e15-4c0b-a726-5f8c3b1d9e40");

    /** Publish time both payloads carry. */
    private static final Instant ENVELOPE_INSTANT = Instant.parse("2022-06-10T19:27:53Z");

    /** Assessment time both payloads carry. */
    private static final Instant ASSESSMENT_INSTANT = Instant.parse("2022-06-10T19:27:54Z");

    /** Write time every row carries, which is also when the row first falls due. */
    private static final Instant ROW_CREATED_AT = Instant.parse("2022-06-10T19:27:55Z");

    /** The score the flagged payload carries. */
    private static final int RISK_SCORE = 72;

    /** An event type outside the two this service publishes. */
    private static final String UNKNOWN_EVENT_TYPE = "FraudReviewed";

    /** One flat JavaScript Object Notation (JSON) object holding ten properties. */
    private static final String FLAGGED_PAYLOAD = "{"
            + "\"eventId\":\"7c3a5b2e-4d16-4f8a-b0c5-2e7d6a4f8b31\","
            + "\"eventType\":\"FraudFlagged\","
            + "\"schemaVersion\":1,"
            + "\"occurredAt\":\"2022-06-10T19:27:53Z\","
            + "\"aggregateId\":\"00000000007\","
            + "\"transactionId\":\"0000000000683580\","
            + "\"accountId\":\"00000000007\","
            + "\"riskScore\":72,"
            + "\"triggeredRules\":[\"VELOCITY\",\"AMOUNT_ANOMALY\"],"
            + "\"assessedAt\":\"2022-06-10T19:27:54Z\""
            + "}";

    /** One flat JSON object holding eight properties. */
    private static final String CLEARED_PAYLOAD = "{"
            + "\"eventId\":\"2f8b6d40-5c71-4a23-8e6f-3b5d7c2a4e68\","
            + "\"eventType\":\"FraudCleared\","
            + "\"schemaVersion\":1,"
            + "\"occurredAt\":\"2022-06-10T19:27:53Z\","
            + "\"aggregateId\":\"00000000007\","
            + "\"transactionId\":\"0000000000683580\","
            + "\"accountId\":\"00000000007\","
            + "\"assessedAt\":\"2022-06-10T19:27:54Z\""
            + "}";

    /** A payload no mapper can read, one opening brace and nothing else. */
    private static final String UNREADABLE_PAYLOAD = "{";

    /** The ten property names the flagged contract requires. */
    private static final Set<String> FLAGGED_PROPERTY_NAMES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
            "transactionId", "accountId", "riskScore", "triggeredRules", "assessedAt");

    /** The eight property names the cleared contract requires. */
    private static final Set<String> CLEARED_PROPERTY_NAMES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
            "transactionId", "accountId", "assessedAt");

    /** Property key path the relay binds and the shipped settings declare. */
    private static final String PLURAL_TOPIC_KEY = "carddemo.kafka.topics.fraud-assessed";

    /** Property key path neither the relay nor the shipped settings names. */
    private static final String SINGULAR_TOPIC_KEY = "carddemo.kafka.topic.fraud-assessed";

    /** Property key path of the dead-letter topic. */
    private static final String DEAD_LETTER_TOPIC_KEY = "carddemo.kafka.topics.dead-letter";

    /** Property key path of the tick delay. */
    private static final String FIXED_DELAY_KEY = "carddemo.outbox.relay.fixed-delay-ms";

    /** Property key path of the batch size. */
    private static final String BATCH_SIZE_KEY = "carddemo.outbox.relay.batch-size";

    /** The placeholder the scheduled method declares. */
    private static final String FIXED_DELAY_PLACEHOLDER =
            "${carddemo.outbox.relay.fixed-delay-ms:500}";

    /** The placeholder the batch size resolves through. */
    private static final String BATCH_SIZE_PLACEHOLDER =
            "${carddemo.outbox.relay.batch-size:100}";

    /** Module-relative path of the relay source. */
    private static final String RELAY_SOURCE =
            "src/main/java/com/carddemo/fraud/outbox/OutboxRelay.java";

    /** Module-relative path of the store source. */
    private static final String REPOSITORY_SOURCE =
            "src/main/java/com/carddemo/fraud/repository/OutboxEventRepository.java";

    /** Module-relative path of the listener source. */
    private static final String CONSUMER_SOURCE =
            "src/main/java/com/carddemo/fraud/messaging/TransactionAuthorizedConsumer.java";

    /** Module-relative path of the one class in this service that names a broker template. */
    private static final String PUBLISHER_SOURCE =
            "src/main/java/com/carddemo/fraud/messaging/KafkaEventPublisher.java";

    /** Eleven decimal digits, the shape every message key holds. */
    private static final Pattern ELEVEN_DIGITS = Pattern.compile("^[0-9]{11}$");

    /** What a correlation or causation header value renders as, and nothing else. */
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** Twelve or more consecutive digits, which no diagnostic value may carry. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{12,}");

    /** Two hyphenated upper-case words, the shape of a record-layout field name. */
    private static final Pattern LAYOUT_FIELD_NAME = Pattern.compile("[A-Z]{2,}-[A-Z]{2,}");

    /** Tokens the relay source carries nowhere, each split so no scan matches this file. */
    private static final List<String> ABSENT_RELAY_TOKENS = List.of(
            "REQUIRES" + "_NEW",
            "New" + "Topic",
            "Kafka" + "Admin",
            "Kafka" + "TransactionManager",
            "transactional" + ".id",
            "executeIn" + "Transaction",
            "setTransactionId" + "Prefix",
            "Class" + ".forName",
            "jakarta.transaction" + ".Transactional");

    /** Reads a stored payload back into a property map, so a count can be taken. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Rows the stubbed store holds, so a re-read inside a later transaction finds them. */
    private final Map<UUID, OutboxEventEntity> stored = new HashMap<>();

    private OutboxEventRepository outboxEvents;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private KafkaEventPublisher publisher;
    private FraudMeters meters;
    private TransactionTemplate transactionTemplate;
    private AtomicBoolean insideTransaction;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpCollaborators() {
        outboxEvents = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        // The relay reaches the broker through messaging/EventPublisherPort, and the shipped
        // adapter is what these cases run: publisher below is the real
        // messaging/KafkaEventPublisher over this mocked template, so every verify(kafkaTemplate)
        // below still measures what actually reaches a broker client, and the adapter's own
        // checks — the eleven-digit message key, and the two producer settings it reads at
        // construction — are exercised on the same path rather than in isolation.
        ProducerFactory<String, Object> producerFactory = mock(ProducerFactory.class);
        when(kafkaTemplate.getProducerFactory()).thenReturn(producerFactory);
        when(producerFactory.getConfigurationProperties())
                .thenReturn(Map.of(ProducerConfig.ACKS_CONFIG, "all",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true"));
        publisher = new KafkaEventPublisher(kafkaTemplate);
        meters = mock(FraudMeters.class);
        transactionTemplate = mock(TransactionTemplate.class);
        insideTransaction = new AtomicBoolean();

        stored.clear();
        when(outboxEvents.save(any(OutboxEventEntity.class)))
                .thenAnswer(call -> {
                    OutboxEventEntity saved = call.getArgument(0);
                    stored.put(saved.getEventId(), saved);
                    return saved;
                });
        // The relay records each outcome in a transaction of its own and re-reads the row inside it,
        // because the claim has committed by then and saving the copy the claim loaded would write
        // pre-claim state back over it. A store keyed by identifier answers that read with the row
        // the claim saved, so these cases keep asserting against the instance they created.
        when(outboxEvents.findById(any(UUID.class)))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of());
        when(outboxEvents.findByDeadLetterStateOrderByLastAttemptAtAsc(any(), any()))
                .thenReturn(List.of());
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of());
        when(transactionTemplate.execute(any())).thenAnswer(call -> {
            TransactionCallback<?> callback = call.getArgument(0);
            insideTransaction.set(true);
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } finally {
                insideTransaction.set(false);
            }
        });
    }

    /** A relay reaching the configured assessed topic and the shipped dead-letter topic. */
    private OutboxRelay relay() {
        return relayPublishingTo(ASSESSED_TOPIC);
    }

    /** A relay reaching {@code assessedTopic}, built with the shipped batch size. */
    private OutboxRelay relayPublishingTo(String assessedTopic) {
        return new OutboxRelay(outboxEvents, publisher, meters, assessedTopic,
                DEAD_LETTER_TOPIC, transactionTemplate, properties(DEFAULT_BATCH_SIZE));
    }

    /** Stubs the claim query to answer with {@code rows} once. */
    private void dueRows(OutboxEventEntity... rows) {
        when(outboxEvents.claimDueRows(any(), any())).thenReturn(List.of(rows));
    }

    /**
     * Stubs the claim query against {@code rows} held in memory, answering the shipped predicate:
     * pending rows already due, longest-waiting first, at most as many as the bound allows.
     */
    private void dueRowsFrom(List<OutboxEventEntity> rows) {
        when(outboxEvents.claimDueRows(any(), any())).thenAnswer(call -> {
            Instant now = call.getArgument(0);
            Limit bound = call.getArgument(1);
            return rows.stream()
                    .filter(row -> row.getRelayState() == OutboxEventEntity.RelayState.PENDING)
                    .filter(row -> !row.getNextAttemptAt().isAfter(now))
                    .sorted(Comparator.comparing(OutboxEventEntity::getNextAttemptAt)
                            .thenComparing(OutboxEventEntity::getEventId))
                    .limit(bound.max())
                    .toList();
        });
    }

    /**
     * Matches any record the relay sends.
     *
     * <p>The relay reaches the broker through the single-argument {@code send} overload so each
     * record can carry the correlation headers a consumer reads. Every matcher below therefore
     * inspects one {@link ProducerRecord} rather than three separate arguments.
     *
     * @return the matcher
     */
    private static ProducerRecord<String, Object> anyRecord() {
        return ArgumentMatchers.any();
    }

    /**
     * Matches a record sent to one topic, whatever its key.
     *
     * @param topic the topic the record has to name
     * @return the matcher
     */
    private static ProducerRecord<String, Object> recordOn(String topic) {
        return argThat((ProducerRecord<String, Object> record) ->
                record != null && topic.equals(record.topic()));
    }

    /**
     * Matches a record sent to one topic under one key.
     *
     * @param topic the topic the record has to name
     * @param key the key the record has to carry
     * @return the matcher
     */
    private static ProducerRecord<String, Object> recordFor(String topic, String key) {
        return argThat((ProducerRecord<String, Object> record) ->
                record != null && topic.equals(record.topic()) && key.equals(record.key()));
    }

    /**
     * Matches a record carrying one key, whatever its topic.
     *
     * @param key the key the record has to carry
     * @return the matcher
     */
    private static ProducerRecord<String, Object> recordKeyed(String key) {
        return argThat((ProducerRecord<String, Object> record) ->
                record != null && key.equals(record.key()));
    }

    /**
     * Captures the records this relay sent, in the order it sent them.
     *
     * @param expected how many sends the pass is expected to have made
     * @return those records
     */
    @SuppressWarnings("unchecked")
    private List<ProducerRecord<String, Object>> sentRecords(int expected) {
        ArgumentCaptor<ProducerRecord<String, Object>> sent =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(expected)).send(sent.capture());
        return sent.getAllValues();
    }

    /**
     * Captures the one record this relay sent to a topic.
     *
     * @param topic the topic to select on
     * @return that record
     */
    @SuppressWarnings("unchecked")
    private ProducerRecord<String, Object> sentRecordOn(String topic) {
        ArgumentCaptor<ProducerRecord<String, Object>> sent =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, atLeastOnce()).send(sent.capture());
        List<ProducerRecord<String, Object>> matching = sent.getAllValues().stream()
                .filter(record -> topic.equals(record.topic()))
                .toList();
        assertThat(matching).as("records sent to " + topic).hasSize(1);
        return matching.getFirst();
    }

    /**
     * Lists the header names one record carries, in the order it carries them.
     *
     * @param record the record to read
     * @return those names
     */
    private static List<String> headerNamesOf(ProducerRecord<String, Object> record) {
        List<String> names = new ArrayList<>();
        record.headers().forEach(header -> names.add(header.key()));
        return names;
    }

    /**
     * Reads one header off one record as text.
     *
     * @param record the record to read
     * @param name the header to read
     * @return the rendered value, or {@code null} when the record carries no such header
     */
    private static String headerValueOf(ProducerRecord<String, Object> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Stubs every send to answer with a resolved result. */
    private void everySendSucceeds() {
        when(kafkaTemplate.send(anyRecord()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    /** Stubs sends to {@code topic} to answer with a result already carrying {@code cause}. */
    private void sendFails(String topic, Throwable cause) {
        when(kafkaTemplate.send(recordOn(topic)))
                .thenReturn(CompletableFuture.failedFuture(cause));
    }

    /** Stubs sends to the dead-letter topic alone to answer with a resolved result. */
    private void deadLetterSendSucceeds() {
        when(kafkaTemplate.send(recordOn(DEAD_LETTER_TOPIC)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private static OutboxEventEntity row(UUID eventIdentifier, String eventType, String payload) {
        return new OutboxEventEntity(
                eventIdentifier, eventType, ACCOUNT_IDENTIFIER, payload, ROW_CREATED_AT);
    }

    private static OutboxEventEntity flaggedRow() {
        return row(FLAGGED_EVENT_IDENTIFIER, FraudFlagged.EVENT_TYPE, FLAGGED_PAYLOAD);
    }

    private static OutboxEventEntity clearedRow() {
        return row(CLEARED_EVENT_IDENTIFIER, FraudCleared.EVENT_TYPE, CLEARED_PAYLOAD);
    }

    private static OutboxEventEntity thirdClearedRow() {
        return row(THIRD_EVENT_IDENTIFIER, FraudCleared.EVENT_TYPE,
                CLEARED_PAYLOAD.replace(CLEARED_EVENT_IDENTIFIER.toString(),
                        THIRD_EVENT_IDENTIFIER.toString()));
    }

    private static OutboxEventEntity unknownTypeRow() {
        return row(UNKNOWN_TYPE_EVENT_IDENTIFIER, UNKNOWN_EVENT_TYPE, CLEARED_PAYLOAD);
    }

    private static OutboxEventEntity unreadablePayloadRow() {
        return row(FLAGGED_EVENT_IDENTIFIER, FraudFlagged.EVENT_TYPE, UNREADABLE_PAYLOAD);
    }

    /** The bound settings a relay reads, carrying the shipped defaults. */
    private static FraudProperties properties(int batchSize) {
        return new FraudProperties(
                new FraudProperties.Kafka(new FraudProperties.Kafka.Topics(
                        AUTHORIZED_TOPIC, ASSESSED_TOPIC, DEAD_LETTER_TOPIC, DEAD_LETTER_SUFFIX)),
                new FraudProperties.Consumer(new FraudProperties.Consumer.Retry(3, 1000L)),
                new FraudProperties.Outbox(new FraudProperties.Outbox.Relay(
                        FIXED_DELAY_MS, batchSize, RELAY_INSTANCE,
                        Duration.ofSeconds(30L), 5000L), 168L),
                new FraudProperties.Retention(3_600_000L, 90, 7),
                new FraudProperties.Fraud(new FraudProperties.Fraud.Risk(
                        50, 60, 5, new BigDecimal("500.00"))));
    }

    private static Method tickMethod() throws NoSuchMethodException {
        return OutboxRelay.class.getDeclaredMethod("publishPendingEvents");
    }

    private static Field relayField(String name) throws NoSuchFieldException {
        return OutboxRelay.class.getDeclaredField(name);
    }

    private static List<String> simpleNamesOf(Annotation[] annotations) {
        return Arrays.stream(annotations)
                .map(annotation -> annotation.annotationType().getSimpleName())
                .sorted()
                .toList();
    }

    /**
     * Reads one module source file, walking up at most five parent directories.
     *
     * @param moduleRelativePath the path of the file inside this module
     * @return the file text
     * @throws AssertionError if no candidate path holds the file
     */
    private static String sourceTextOf(String moduleRelativePath) throws IOException {
        Path base = Path.of("").toAbsolutePath().normalize();
        List<Path> relativeCandidates = List.of(
                Path.of(moduleRelativePath),
                Path.of("services/fraud-detection-service").resolve(moduleRelativePath));
        List<Path> attempted = new ArrayList<>();

        for (int depth = 0; depth <= 5 && base != null; depth++) {
            for (Path relative : relativeCandidates) {
                Path candidate = base.resolve(relative).normalize();
                attempted.add(candidate);
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            }
            base = base.getParent();
        }
        throw new AssertionError(
                moduleRelativePath + " was not found; candidate paths: " + attempted);
    }

    /** The shipped settings, read from the classpath so the file under test is the shipped one. */
    private static Map<String, Object> shippedSettings() throws IOException {
        try (InputStream stream = new ClassPathResource("application.yml").getInputStream()) {
            return new Yaml().load(stream);
        }
    }

    /**
     * Walks a dotted path through the shipped settings.
     *
     * @param dottedPath the property key path
     * @return the value the path holds
     * @throws AssertionError if any segment is missing
     */
    private static String settingAt(String dottedPath) throws IOException {
        Object current = shippedSettings();
        StringBuilder walked = new StringBuilder();
        for (String segment : dottedPath.split("\\.")) {
            walked.append(walked.isEmpty() ? "" : ".").append(segment);
            assertThat(current)
                    .as("the shipped settings hold a block at " + walked)
                    .isInstanceOf(Map.class);
            current = ((Map<?, ?>) current).get(segment);
            assertThat(current).as("the shipped settings declare " + walked).isNotNull();
        }
        return String.valueOf(current);
    }

    /**
     * Reports whether the shipped settings declare a dotted path.
     *
     * @param dottedPath the property key path
     * @return true when every segment resolves
     */
    private static boolean settingsDeclare(String dottedPath) throws IOException {
        Object current = shippedSettings();
        for (String segment : dottedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> block)) {
                return false;
            }
            current = block.get(segment);
        }
        return current != null;
    }

    /**
     * Returns the value a placeholder falls back on, which is the text after its first colon.
     *
     * @param placeholder a value of the form dollar-brace name colon default brace
     * @return the fallback value
     */
    private static String fallbackOf(String placeholder) {
        assertThat(placeholder).startsWith("${").endsWith("}");
        String inside = placeholder.substring(2, placeholder.length() - 1);
        int firstColon = inside.indexOf(':');
        assertThat(firstColon).as("placeholder " + placeholder + " carries a default")
                .isGreaterThan(0);
        return inside.substring(firstColon + 1);
    }

    /** Composes the placeholder form a key and a default resolve to. */
    private static String placeholderOf(String key, String fallback) {
        return "${" + key + ":" + fallback + "}";
    }

    @Nested
    @DisplayName("Schedule and transaction metadata")
    class ScheduleAndTransactionMetadata {

        @Test
        @DisplayName("the tick declares the fixed-delay property placeholder")
        void tickDeclaresTheFixedDelayPropertyPlaceholder() throws Exception {
            Scheduled scheduled = tickMethod().getAnnotation(Scheduled.class);

            assertThat(scheduled).as("the schedule on the tick").isNotNull();
            assertThat(scheduled.fixedDelayString())
                    .as("the declared delay placeholder")
                    .isEqualTo(FIXED_DELAY_PLACEHOLDER);
        }

        @Test
        @DisplayName("the shipped settings supply the delay and the batch size the relay reads")
        void shippedSettingsSupplyTheDelayAndTheBatchSizeTheRelayReads() throws Exception {
            String declaredDelay = fallbackOf(settingAt(FIXED_DELAY_KEY));
            String declaredBatchSize = fallbackOf(settingAt(BATCH_SIZE_KEY));

            assertThat(placeholderOf(FIXED_DELAY_KEY, declaredDelay))
                    .isEqualTo(FIXED_DELAY_PLACEHOLDER);
            assertThat(placeholderOf(BATCH_SIZE_KEY, declaredBatchSize))
                    .isEqualTo(BATCH_SIZE_PLACEHOLDER);
            assertThat(Long.parseLong(declaredDelay)).isEqualTo(FIXED_DELAY_MS);
            assertThat(Integer.parseInt(declaredBatchSize)).isEqualTo(DEFAULT_BATCH_SIZE);
        }

        @Test
        @DisplayName("the tick is public, answers with nothing and takes no argument")
        void tickIsPublicAnswersWithNothingAndTakesNoArgument() throws Exception {
            Method tick = tickMethod();

            assertThat(Modifier.isPublic(tick.getModifiers()))
                    .as("the tick public modifier").isTrue();
            assertThat(tick.getReturnType()).as("the tick return type").isEqualTo(void.class);
            assertThat(tick.getParameterCount()).as("the tick parameter count").isZero();
        }

        @Test
        @DisplayName("the tick and its class stay open to a proxy")
        void tickAndItsClassStayOpenToAProxy() throws Exception {
            Method tick = tickMethod();

            assertThat(Modifier.isStatic(tick.getModifiers()))
                    .as("the tick static modifier").isFalse();
            assertThat(Modifier.isFinal(tick.getModifiers()))
                    .as("the tick final modifier").isFalse();
            assertThat(Modifier.isFinal(OutboxRelay.class.getModifiers()))
                    .as("the relay class final modifier").isFalse();
        }

        @Test
        @DisplayName("the tick carries the schedule and no transaction annotation")
        void tickCarriesTheScheduleAndNoTransactionAnnotation() throws Exception {
            assertThat(simpleNamesOf(tickMethod().getAnnotations()))
                    .as("annotations on the tick")
                    .containsExactly("Scheduled");
        }

        @Test
        @DisplayName("the relay carries the component stereotype and neither enabling annotation")
        void relayCarriesTheComponentStereotypeAndNeitherEnablingAnnotation() {
            List<String> present = simpleNamesOf(OutboxRelay.class.getAnnotations());

            assertThat(present).as("annotations on the relay").containsExactly("Component");
            assertThat(present).doesNotContain("EnableScheduling", "EnableKafka");
        }

        /**
         * Holds the transaction shape of one tick: a claim, then one short transaction per outcome,
         * and no send inside either.
         *
         * <p>One transaction around the whole tick is what this asserted before, and it is the shape
         * a performance review rejected: a batch of rows waiting on a broker inside one transaction
         * holds a database connection and every row lock it took for the sum of those waits, and
         * every later event of every account waits behind it. Two here is the claim and the one
         * failure record of the single row this tick holds; a wider batch adds one short transaction
         * per row and none of them spans a send.
         */
        @Test
        @DisplayName("one tick claims in one transaction, records each outcome in another, and "
                + "counts outside both")
        void oneTickClaimsInOneTransactionAndRecordsEachOutcomeInAnother() {
            dueRows(clearedRow());
            doAnswer(call -> {
                assertThat(insideTransaction.get())
                        .as("a count taken while a transaction is open").isFalse();
                return null;
            }).when(meters).recordPublishFailure();
            doAnswer(call -> {
                assertThat(insideTransaction.get())
                        .as("a send issued while a transaction is open").isFalse();
                return CompletableFuture.failedFuture(
                        new TimeoutException("the broker did not answer"));
            }).when(kafkaTemplate).send(recordOn(ASSESSED_TOPIC));

            relay().publishPendingEvents();

            verify(transactionTemplate, times(2)).execute(any());
            verify(meters).recordPublishFailure();
        }

        @Test
        @DisplayName("the relay holds a transaction boundary and a final batch size")
        void relayHoldsATransactionBoundaryAndAFinalBatchSize() throws Exception {
            Field boundary = relayField("transactionTemplate");
            Field batchSize = relayField("batchSize");

            assertThat(boundary.getType()).isEqualTo(TransactionTemplate.class);
            assertThat(Modifier.isPrivate(batchSize.getModifiers()))
                    .as("the batch size private modifier").isTrue();
            assertThat(Modifier.isFinal(batchSize.getModifiers()))
                    .as("the batch size final modifier").isTrue();
            assertThat(batchSize.getType()).as("the batch size type").isEqualTo(int.class);
        }

        @Test
        @DisplayName("the relay declares no bean and holds no broker client")
        void relayDeclaresNoBeanAndHoldsNoBrokerClient() {
            List<String> beanMethods = Arrays.stream(OutboxRelay.class.getDeclaredMethods())
                    .filter(method -> simpleNamesOf(method.getAnnotations()).contains("Bean"))
                    .map(Method::getName)
                    .toList();
            List<String> clientFields = Arrays.stream(OutboxRelay.class.getDeclaredFields())
                    .filter(field -> field.getType().getName().startsWith("org.apache.kafka"))
                    .map(Field::getName)
                    .toList();

            assertThat(beanMethods).as("bean declarations on the relay").isEmpty();
            assertThat(clientFields).as("broker client members of the relay").isEmpty();
        }

        @Test
        @DisplayName("the relay source carries none of the nine absent tokens")
        void relaySourceCarriesNoneOfTheNineAbsentTokens() throws Exception {
            String source = sourceTextOf(RELAY_SOURCE);

            assertThat(ABSENT_RELAY_TOKENS).allSatisfy(token ->
                    assertThat(source).as("the relay source carries " + token)
                            .doesNotContain(token));
        }

        @Test
        @DisplayName("the event type map holds two unmodifiable entries named for the two records")
        @SuppressWarnings("unchecked")
        void eventTypeMapHoldsTwoUnmodifiableEntriesNamedForTheTwoRecords() throws Exception {
            Field mapField = relayField("recordTypesByEventType");
            mapField.setAccessible(true);
            Map<String, Class<?>> types = (Map<String, Class<?>>) mapField.get(relay());

            assertThat(Modifier.isPrivate(mapField.getModifiers()))
                    .as("the map private modifier").isTrue();
            assertThat(Modifier.isFinal(mapField.getModifiers()))
                    .as("the map final modifier").isTrue();
            assertThat(Map.class.isAssignableFrom(mapField.getType()))
                    .as("the map declared type").isTrue();
            assertThat(types).hasSize(2)
                    .containsEntry(FraudFlagged.EVENT_TYPE, FraudFlagged.class)
                    .containsEntry(FraudCleared.EVENT_TYPE, FraudCleared.class);
            assertThat(types.keySet()).containsExactlyInAnyOrder(
                    FraudFlagged.class.getSimpleName(), FraudCleared.class.getSimpleName());
            assertThatThrownBy(types::clear).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Store contract")
    class StoreContract {

        @Test
        @DisplayName("the store declares eight methods and no name carries a digit")
        void storeDeclaresEightMethodsAndNoNameCarriesADigit() {
            List<String> names = Arrays.stream(OutboxEventRepository.class.getDeclaredMethods())
                    .map(Method::getName)
                    .sorted()
                    .toList();

            assertThat(names).containsExactly(
                    "claimDueRows",
                    "countDueBefore",
                    "deletePublishedBefore",
                    "existsByRelayState",
                    "findByDeadLetterStateOrderByLastAttemptAtAsc",
                    "findByPublishedFalseOrderByCreatedAtAsc",
                    "findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc",
                    "findEarliestDueBefore");
            assertThat(names).allSatisfy(name -> assertThat(name)
                    .as("a declared name carrying a digit").doesNotMatch(".*[0-9].*"));
        }

        @Test
        @DisplayName("the unpublished finder takes a page request and answers with a list")
        void unpublishedFinderTakesAPageRequestAndAnswersWithAList() throws Exception {
            Method finder = OutboxEventRepository.class.getDeclaredMethod(
                    "findByPublishedFalseOrderByCreatedAtAsc", Pageable.class);

            assertThat(finder.getParameterTypes()).containsExactly(Pageable.class);
            assertThat(finder.getReturnType()).isEqualTo(List.class);
        }

        @Test
        @DisplayName("the claim query takes an instant and a row bound and nothing else")
        void claimQueryTakesAnInstantAndARowBoundAndNothingElse() throws Exception {
            Method claim = OutboxEventRepository.class.getDeclaredMethod(
                    "claimDueRows", Instant.class, Limit.class);

            assertThat(claim.getParameterTypes()).containsExactly(Instant.class, Limit.class);
            assertThat(claim.getReturnType()).isEqualTo(List.class);
        }

        @Test
        @DisplayName("the claim query names the order the relay depends on")
        void claimQueryNamesTheOrderTheRelayDependsOn() throws Exception {
            assertThat(sourceTextOf(REPOSITORY_SOURCE))
                    .contains("ORDER BY row.nextAttemptAt ASC, row.eventId ASC");
        }

        @Test
        @DisplayName("the store carries the repository stereotype and no transaction annotation")
        void storeCarriesTheRepositoryStereotypeAndNoTransactionAnnotation() {
            List<String> methodAnnotations =
                    Arrays.stream(OutboxEventRepository.class.getDeclaredMethods())
                            .flatMap(method -> simpleNamesOf(method.getAnnotations()).stream())
                            .toList();

            assertThat(simpleNamesOf(OutboxEventRepository.class.getAnnotations()))
                    .as("annotations on the store").containsExactly("Repository");
            assertThat(OutboxEventRepository.class.getInterfaces()).contains(JpaRepository.class);
            assertThat(methodAnnotations)
                    .as("annotations on the declared methods").doesNotContain("Transactional");
        }

        @Test
        @DisplayName("the store source names no entity manager")
        void storeSourceNamesNoEntityManager() throws Exception {
            assertThat(sourceTextOf(REPOSITORY_SOURCE)).doesNotContain("EntityManager");
        }

        @Test
        @DisplayName("one tick claims rows up to the configured bound")
        void oneTickClaimsRowsUpToTheConfiguredBound() {
            ArgumentCaptor<Limit> bounds = ArgumentCaptor.forClass(Limit.class);

            relay().publishPendingEvents();

            verify(outboxEvents).claimDueRows(any(), bounds.capture());
            assertThat(bounds.getValue().max()).isEqualTo(DEFAULT_BATCH_SIZE);
        }

        /**
         * Holds the store surface one tick touches: three reads, one re-read per outcome, and two
         * writes per row.
         *
         * <p>Two writes rather than one, and the second read, are both consequences of the claim
         * committing before any send starts. The claim writes {@code CLAIMED} and commits, so the
         * recovery it exists for survives a process death; the mark then re-reads the row in a
         * transaction of its own and writes the publication. Saving the copy the claim loaded instead
         * would write pre-claim state back over the committed claim.
         */
        @Test
        @DisplayName("one tick reads, claims and marks each row, and touches nothing else")
        void oneTickReadsClaimsAndMarksEachRowAndTouchesNothingElse() {
            OutboxEventEntity first = flaggedRow();
            OutboxEventEntity second = clearedRow();
            dueRows(first, second);
            everySendSucceeds();

            relay().publishPendingEvents();

            verify(outboxEvents).findByDeadLetterStateOrderByLastAttemptAtAsc(
                    eq(OutboxEventEntity.DeadLetterState.REQUIRED), any());
            verify(outboxEvents).findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                    eq(OutboxEventEntity.RelayState.CLAIMED), any(), any());
            // Two claims, not one: a tick repeats its claim until nothing more is due, and the
            // second call is the one that finds the store empty. Recovery and the owed-diagnostic
            // read run once each, on the first cycle alone.
            verify(outboxEvents, times(2)).claimDueRows(any(), any());
            verify(outboxEvents, times(1)).findById(first.getEventId());
            verify(outboxEvents, times(1)).findById(second.getEventId());
            verify(outboxEvents, times(2)).save(first);
            verify(outboxEvents, times(2)).save(second);
            verify(outboxEvents, never()).findByPublishedFalseOrderByCreatedAtAsc(any());
            verifyNoMoreInteractions(outboxEvents);
        }
    }

    @Nested
    @DisplayName("Message key")
    class MessageKey {

        @Test
        @DisplayName("every published record keys on the eleven-digit account identifier")
        void everyPublishedRecordKeysOnTheElevenDigitAccountIdentifier() {
            dueRows(flaggedRow(), clearedRow());
            everySendSucceeds();

            relay().publishPendingEvents();

            List<String> sentKeys = sentRecords(2).stream().map(ProducerRecord::key).toList();
            assertThat(sentKeys).containsExactly(ACCOUNT_IDENTIFIER, ACCOUNT_IDENTIFIER);
            assertThat(sentKeys).allSatisfy(key -> {
                assertThat(key).isNotNull().hasSize(11).matches(ELEVEN_DIGITS);
                assertThat(key.charAt(0)).as("the leading character of the key").isEqualTo('0');
                assertThat(key).as("the key after a numeric round trip")
                        .isNotEqualTo(String.valueOf(Long.parseLong(key)));
            });
        }

        @Test
        @DisplayName("a dead-letter record keys on the same account identifier")
        void deadLetterRecordKeysOnTheSameAccountIdentifier() {
            dueRows(unknownTypeRow());
            deadLetterSendSucceeds();

            relay().publishPendingEvents();

            assertThat(sentRecordOn(DEAD_LETTER_TOPIC).key()).isEqualTo(ACCOUNT_IDENTIFIER);
        }

        @Test
        @DisplayName("the relay sends through the producer-record overload alone")
        void relaySendsThroughTheProducerRecordOverloadAlone() {
            dueRows(flaggedRow());
            everySendSucceeds();

            relay().publishPendingEvents();

            verify(kafkaTemplate).send(recordFor(ASSESSED_TOPIC, ACCOUNT_IDENTIFIER));
            // messaging/KafkaEventPublisher reads the producer settings once at construction, so
            // the template answers this before any send is made.
            verify(kafkaTemplate, atLeastOnce()).getProducerFactory();
            verify(kafkaTemplate, never()).send(anyString(), any());
            verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
            verifyNoMoreInteractions(kafkaTemplate);
        }

        @Test
        @DisplayName("a row that started its own trace carries the correlation header alone")
        void rowThatStartedItsOwnTraceCarriesTheCorrelationHeaderAlone() {
            OutboxEventEntity row = flaggedRow();
            dueRows(row);
            everySendSucceeds();

            relay().publishPendingEvents();

            ProducerRecord<String, Object> sent = sentRecordOn(ASSESSED_TOPIC);
            assertThat(headerNamesOf(sent))
                    .containsExactly(EventCorrelation.CORRELATION_ID_HEADER);
            assertThat(headerValueOf(sent, EventCorrelation.CORRELATION_ID_HEADER))
                    .as("a root row adopts its own event identifier, so the record is joinable")
                    .isEqualTo(row.getEventId().toString());
        }

        @Test
        @DisplayName("a row recording both identifiers carries both correlation headers")
        void rowRecordingBothIdentifiersCarriesBothCorrelationHeaders() {
            UUID correlationId = UUID.randomUUID();
            UUID causingEventId = UUID.randomUUID();
            OutboxEventEntity row = flaggedRow();
            row.recordCorrelation(correlationId, causingEventId);
            dueRows(row);
            everySendSucceeds();

            relay().publishPendingEvents();

            ProducerRecord<String, Object> sent = sentRecordOn(ASSESSED_TOPIC);
            assertThat(headerNamesOf(sent)).containsExactlyInAnyOrder(
                    EventCorrelation.CORRELATION_ID_HEADER, EventCorrelation.CAUSATION_ID_HEADER);
            assertThat(headerValueOf(sent, EventCorrelation.CORRELATION_ID_HEADER))
                    .isEqualTo(correlationId.toString());
            assertThat(headerValueOf(sent, EventCorrelation.CAUSATION_ID_HEADER))
                    .as("the causing event is what the row was written under")
                    .isEqualTo(causingEventId.toString());
        }

        @Test
        @DisplayName("every header name is one of the two the contract declares")
        void everyHeaderNameIsOneOfTheTwoTheContractDeclares() {
            UUID correlationId = UUID.randomUUID();
            UUID causationId = UUID.randomUUID();
            OutboxEventEntity row = flaggedRow();
            row.recordCorrelation(correlationId, causationId);
            dueRows(row);
            everySendSucceeds();

            relay().publishPendingEvents();

            ProducerRecord<String, Object> sent = sentRecordOn(ASSESSED_TOPIC);
            assertThat(headerNamesOf(sent))
                    .as("a header the contract does not declare could carry anything")
                    .isSubsetOf(EventCorrelation.CORRELATION_ID_HEADER,
                            EventCorrelation.CAUSATION_ID_HEADER);
            // Compared against the two identifiers the row recorded rather than searched for a
            // payload substring. A random identifier's hexadecimal digits contain a two-digit risk
            // score often enough that a substring check fails on the value being correct, so the
            // exact comparison is both stronger and the only one that is stable.
            assertThat(headerValueOf(sent, EventCorrelation.CORRELATION_ID_HEADER))
                    .as("a header carries an identifier alone, never a payload property")
                    .matches(UUID_TEXT)
                    .isEqualTo(correlationId.toString());
            assertThat(headerValueOf(sent, EventCorrelation.CAUSATION_ID_HEADER))
                    .matches(UUID_TEXT)
                    .isEqualTo(causationId.toString());
        }
    }

    @Nested
    @DisplayName("Topic resolution")
    class TopicResolution {

        @Test
        @DisplayName("the relay binds carddemo.kafka.topics.fraud-assessed and never "
                + "carddemo.kafka.topic.fraud-assessed")
        void relayBindsThePluralKeyPathAndNeverTheSingularOne() throws Exception {
            String source = sourceTextOf(RELAY_SOURCE);

            assertThat(source).as("key path " + PLURAL_TOPIC_KEY).contains(PLURAL_TOPIC_KEY);
            assertThat(source).as("key path " + SINGULAR_TOPIC_KEY)
                    .doesNotContain(SINGULAR_TOPIC_KEY);
            assertThat(source).as("the binding of " + PLURAL_TOPIC_KEY)
                    .contains(placeholderOf(PLURAL_TOPIC_KEY, ASSESSED_TOPIC));
        }

        @Test
        @DisplayName("the shipped settings declare carddemo.kafka.topics.fraud-assessed and never "
                + "carddemo.kafka.topic.fraud-assessed")
        void shippedSettingsDeclareThePluralKeyPathAndNeverTheSingularOne() throws Exception {
            assertThat(settingsDeclare(PLURAL_TOPIC_KEY))
                    .as("the shipped settings declare " + PLURAL_TOPIC_KEY).isTrue();
            assertThat(settingsDeclare(SINGULAR_TOPIC_KEY))
                    .as("the shipped settings declare " + SINGULAR_TOPIC_KEY).isFalse();
        }

        @Test
        @DisplayName("the relay default and the shipped default both resolve to fraud.assessed")
        void relayDefaultAndShippedDefaultBothResolveToTheAssessedTopic() throws Exception {
            String shipped = fallbackOf(settingAt(PLURAL_TOPIC_KEY));

            assertThat(shipped).as("the shipped default of " + PLURAL_TOPIC_KEY)
                    .isEqualTo(ASSESSED_TOPIC);
            assertThat(sourceTextOf(RELAY_SOURCE))
                    .as("the relay default of " + PLURAL_TOPIC_KEY)
                    .contains(placeholderOf(PLURAL_TOPIC_KEY, shipped));
        }

        @Test
        @DisplayName("both sides resolve the dead-letter topic to carddemo.dead-letter")
        void bothSidesResolveTheDeadLetterTopicToTheSameName() throws Exception {
            String shipped = fallbackOf(settingAt(DEAD_LETTER_TOPIC_KEY));

            assertThat(shipped).as("the shipped default of " + DEAD_LETTER_TOPIC_KEY)
                    .isEqualTo(DEAD_LETTER_TOPIC);
            assertThat(sourceTextOf(RELAY_SOURCE))
                    .contains(placeholderOf(DEAD_LETTER_TOPIC_KEY, shipped));
        }

        @Test
        @DisplayName("a relay built with the sentinel topic sends against the sentinel topic")
        void relayBuiltWithTheSentinelTopicSendsAgainstTheSentinelTopic() {
            dueRows(flaggedRow());
            everySendSucceeds();

            relayPublishingTo(SENTINEL_TOPIC).publishPendingEvents();

            verify(kafkaTemplate).send(recordFor(SENTINEL_TOPIC, ACCOUNT_IDENTIFIER));
            verify(kafkaTemplate, never()).send(recordOn(ASSESSED_TOPIC));
        }

        @Test
        @DisplayName("a relay built with the assessed topic sends against the assessed topic")
        void relayBuiltWithTheAssessedTopicSendsAgainstTheAssessedTopic() {
            dueRows(flaggedRow());
            everySendSucceeds();

            relayPublishingTo(ASSESSED_TOPIC).publishPendingEvents();

            verify(kafkaTemplate).send(recordFor(ASSESSED_TOPIC, ACCOUNT_IDENTIFIER));
            verify(kafkaTemplate, never()).send(recordOn(SENTINEL_TOPIC));
        }

        @Test
        @DisplayName("the stored event type selects the record type on one shared topic")
        void storedEventTypeSelectsTheRecordTypeOnOneSharedTopic() {
            dueRows(flaggedRow(), clearedRow());
            everySendSucceeds();

            relay().publishPendingEvents();

            List<ProducerRecord<String, Object>> sent = sentRecords(2);
            assertThat(sent.stream().map(ProducerRecord::topic).toList())
                    .containsExactly(ASSESSED_TOPIC, ASSESSED_TOPIC);
            assertThat(sent.get(0).value()).isInstanceOf(FraudFlagged.class);
            assertThat(sent.get(1).value()).isInstanceOf(FraudCleared.class);
        }
    }

    @Nested
    @DisplayName("Success path")
    class SuccessPath {

        @Test
        @DisplayName("the stored payloads carry the ten and the eight contract properties")
        void storedPayloadsCarryTheTenAndTheEightContractProperties() {
            Map<?, ?> flagged = MAPPER.readValue(FLAGGED_PAYLOAD, Map.class);
            Map<?, ?> cleared = MAPPER.readValue(CLEARED_PAYLOAD, Map.class);

            assertThat(flagged).hasSize(10);
            assertThat(flagged.keySet()).isEqualTo(FLAGGED_PROPERTY_NAMES);
            assertThat(cleared).hasSize(8);
            assertThat(cleared.keySet()).isEqualTo(CLEARED_PROPERTY_NAMES);
        }

        @Test
        @DisplayName("one unpublished row produces one record read back from its payload")
        void oneUnpublishedRowProducesOneRecordReadBackFromItsPayload() {
            dueRows(flaggedRow());
            everySendSucceeds();

            relay().publishPendingEvents();

            ProducerRecord<String, Object> sent = sentRecordOn(ASSESSED_TOPIC);
            assertThat(sent.key()).isEqualTo(ACCOUNT_IDENTIFIER);
            assertThat(sent.value()).isInstanceOf(FraudFlagged.class);
            FraudFlagged published = (FraudFlagged) sent.value();
            assertThat(published.eventId()).isEqualTo(FLAGGED_EVENT_IDENTIFIER);
            assertThat(published.eventType()).isEqualTo(FraudFlagged.EVENT_TYPE);
            assertThat(published.aggregateId()).isEqualTo(ACCOUNT_IDENTIFIER);
            assertThat(published.transactionId()).isEqualTo(TRANSACTION_IDENTIFIER);
            assertThat(published.riskScore()).isEqualTo(RISK_SCORE);
            assertThat(published.triggeredRules()).containsExactly("VELOCITY", "AMOUNT_ANOMALY");
            assertThat(published.occurredAt()).isEqualTo(ENVELOPE_INSTANT);
            assertThat(published.assessedAt()).isEqualTo(ASSESSMENT_INSTANT);
        }

        @Test
        @DisplayName("the send result resolves before the row is marked")
        void sendResultResolvesBeforeTheRowIsMarked() {
            OutboxEventEntity row = flaggedRow();
            dueRows(row);
            when(kafkaTemplate.send(recordFor(ASSESSED_TOPIC, ACCOUNT_IDENTIFIER)))
                    .thenAnswer(call -> {
                        assertThat(row.isPublished()).as("the row at send time").isFalse();
                        assertThat(row.getRelayState())
                                .isEqualTo(OutboxEventEntity.RelayState.CLAIMED);
                        assertThat(row.getClaimedBy()).isEqualTo(RELAY_INSTANCE);
                        return CompletableFuture.completedFuture(null);
                    });

            relay().publishPendingEvents();

            assertThat(row.isPublished()).isTrue();
            assertThat(row.getPublishedAt()).isNotNull();
            assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PUBLISHED);
            assertThat(row.getClaimedBy()).isNull();
        }

        @Test
        @DisplayName("the row the store answered with is the row the relay saves")
        void rowTheStoreAnsweredWithIsTheRowTheRelaySaves() {
            OutboxEventEntity row = flaggedRow();
            dueRows(row);
            everySendSucceeds();
            ArgumentCaptor<OutboxEventEntity> saved =
                    ArgumentCaptor.forClass(OutboxEventEntity.class);

            relay().publishPendingEvents();

            // Twice: the claim commits before the send starts, and the mark then re-reads the row
            // and writes the publication. Both writes carry the instance the store answered with.
            verify(outboxEvents, times(2)).save(saved.capture());
            assertThat(saved.getAllValues()).allMatch(candidate -> candidate == row);
            assertThat(saved.getValue().getAttemptCount()).isZero();
            assertThat(saved.getValue().getLastError()).isNull();
        }

        @Test
        @DisplayName("no row is ever removed")
        void noRowIsEverRemoved() {
            dueRows(flaggedRow());
            everySendSucceeds();

            relay().publishPendingEvents();

            verify(outboxEvents, never()).delete(any());
            verify(outboxEvents, never()).deleteById(any());
            verify(outboxEvents, never()).deleteAll();
            verify(outboxEvents, never()).deleteAllInBatch();
        }

        @Test
        @DisplayName("two rows in one tick are claimed and marked through one save each")
        void twoRowsInOneTickAreClaimedAndMarkedThroughOneSaveEach() {
            OutboxEventEntity first = flaggedRow();
            OutboxEventEntity second = clearedRow();
            dueRows(first, second);
            everySendSucceeds();

            relay().publishPendingEvents();

            assertThat(first.isPublished()).isTrue();
            assertThat(first.getPublishedAt()).isNotNull();
            assertThat(second.isPublished()).isTrue();
            assertThat(second.getPublishedAt()).isNotNull();
            // Two writes per row, in this order: the claim, which commits before any send starts,
            // then the mark once the broker acknowledged that row. Neither row is written more than
            // once by either step, so no row is claimed twice or marked twice.
            verify(outboxEvents, times(2)).save(first);
            verify(outboxEvents, times(2)).save(second);
        }
    }

    @Nested
    @DisplayName("Transient failure")
    class TransientFailure {

        private Logger relayLogger;
        private Level originalLevel;
        private ListAppender<ILoggingEvent> appender;

        @BeforeEach
        void attachAppender() {
            relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
            originalLevel = relayLogger.getLevel();
            appender = new ListAppender<>();
            appender.setContext(relayLogger.getLoggerContext());
            appender.start();
            relayLogger.setLevel(Level.TRACE);
            relayLogger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            relayLogger.detachAppender(appender);
            appender.stop();
            relayLogger.setLevel(originalLevel);
        }

        @Test
        @DisplayName("a refused send leaves the row unpublished and due again")
        void refusedSendLeavesTheRowUnpublishedAndDueAgain() {
            OutboxEventEntity row = clearedRow();
            dueRows(row);
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));

            relay().publishPendingEvents();

            assertThat(row.isPublished()).isFalse();
            assertThat(row.getPublishedAt()).isNull();
            assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
            assertThat(row.getAttemptCount()).isEqualTo(1);
            assertThat(row.getNextAttemptAt()).isAfter(row.getLastAttemptAt());
        }

        @Test
        @DisplayName("a refused send routes nothing to the dead-letter topic")
        void refusedSendRoutesNothingToTheDeadLetterTopic() {
            dueRows(clearedRow());
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));

            relay().publishPendingEvents();

            verify(kafkaTemplate, never()).send(recordOn(DEAD_LETTER_TOPIC));
            verify(meters, never()).recordDeadLetterPublished();
            verify(meters, never()).recordDeadLetterFailure();
        }

        /**
         * Asserts a refused send backs off the row it names and no other row of the tick.
         *
         * <p>This asserted the opposite before: the tick returned at the first refused row, leaving
         * every row behind it unattempted. One unreachable partition therefore stopped publication
         * for every account, which a performance review raised — and the claim already prevents the
         * case that behaviour existed for. {@code claimDueRows} answers with the due head row of
         * each aggregate and never two rows of one account, so a tick's rows name distinct accounts
         * and a stalled account's later events wait behind its own unpublished head rather than
         * behind another account's.
         *
         * <p>Both rows here are refused, so each records exactly one attempt of its own. A row whose
         * attempt is recorded twice, or not at all, fails this.
         */
        @Test
        @DisplayName("a refused send backs off its own row and no other row of the tick")
        void refusedSendBacksOffItsOwnRowAndNoOtherRowOfTheTick() {
            OutboxEventEntity failing = clearedRow();
            OutboxEventEntity alongside = thirdClearedRow();
            dueRows(failing, alongside);
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));

            relay().publishPendingEvents();

            verify(kafkaTemplate, times(2)).send(recordOn(ASSESSED_TOPIC));
            assertThat(failing.getAttemptCount()).isEqualTo(1);
            assertThat(failing.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
            assertThat(alongside.getAttemptCount()).isEqualTo(1);
            assertThat(alongside.isPublished()).isFalse();
            assertThat(alongside.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
            verify(meters, times(2)).recordPublishFailure();
        }

        @Test
        @DisplayName("the next tick attempts the same row again")
        void nextTickAttemptsTheSameRowAgain() {
            OutboxEventEntity row = clearedRow();
            dueRows(row);
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));
            OutboxRelay relay = relay();

            relay.publishPendingEvents();
            relay.publishPendingEvents();

            verify(kafkaTemplate, times(2))
                    .send(recordFor(ASSESSED_TOPIC, ACCOUNT_IDENTIFIER));
            assertThat(row.getAttemptCount()).isEqualTo(2);
            assertThat(row.isPublished()).isFalse();
        }

        /**
         * Asserts the relay counts a refused send and does not name it a second time.
         *
         * <p>This test required the relay to write that line, and the same send was already reported
         * by {@code config/SafeProducerListener}, which every template of this service installs. Two
         * lines for one attempt made an operator counting reports count each one twice, so the relay's
         * line was removed and this test now holds the absence.
         *
         * <p>What the line carried is not lost. The listener reports the destination and the failure
         * type, and {@code config/SafeProducerListenerTest} holds it to naming no key and no payload.
         * The relay still reports what it alone knows, which is that a row was abandoned, and it still
         * counts this refusal.
         */
        @Test
        @DisplayName("a refused send is counted here and named only by the producer listener")
        void refusedSendIsCountedHereAndNamedOnlyByTheProducerListener() {
            dueRows(clearedRow());
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));

            relay().publishPendingEvents();

            List<String> namingTheSend = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains(TimeoutException.class.getSimpleName()))
                    .toList();
            assertThat(namingTheSend)
                    .as("the relay must not name a send the producer listener already reported")
                    .isEmpty();
            verify(meters).recordPublishFailure();
        }
    }

    @Nested
    @DisplayName("Stranded claim recovery")
    class StrandedClaimRecovery {

        @Test
        @DisplayName("a claim left behind by a stopped instance becomes due again")
        void claimLeftBehindByAStoppedInstanceBecomesDueAgain() {
            OutboxEventEntity row = clearedRow();
            row.claim("stopped-instance", ROW_CREATED_AT);
            when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                    eq(OutboxEventEntity.RelayState.CLAIMED), any(), any()))
                    .thenReturn(List.of(row));
            dueRows(row);
            everySendSucceeds();

            relay().publishPendingEvents();

            assertThat(row.isPublished()).isTrue();
            assertThat(row.getAttemptCount()).isEqualTo(1);
            assertThat(row.getClaimedBy()).isNull();
            verify(kafkaTemplate).send(recordFor(ASSESSED_TOPIC, ACCOUNT_IDENTIFIER));
            verify(meters).recordPublishFailure();
        }
    }

    @Nested
    @DisplayName("Permanent failure and the dead-letter diagnostic")
    class PermanentFailure {

        @Test
        @DisplayName("the diagnostic names four values in the declared order within four widths")
        void diagnosticNamesFourValuesInTheDeclaredOrderWithinFourWidths() {
            List<String> metadataComponents =
                    Arrays.stream(DeadLetterMetadata.class.getRecordComponents())
                            .map(RecordComponent::getName).toList();
            List<String> envelopeComponents =
                    Arrays.stream(DeadLetterEnvelope.class.getRecordComponents())
                            .map(RecordComponent::getName).toList();

            assertThat(metadataComponents)
                    .containsExactly("abendCode", "culprit", "reason", "message");
            assertThat(envelopeComponents.subList(1, 5))
                    .containsExactly("abendCode", "culprit", "reason", "message");
            assertThat(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH).isEqualTo(4);
            assertThat(DeadLetterMetadata.CULPRIT_MAX_LENGTH).isEqualTo(8);
            assertThat(DeadLetterMetadata.REASON_MAX_LENGTH).isEqualTo(50);
            assertThat(DeadLetterMetadata.MESSAGE_MAX_LENGTH).isEqualTo(72);
        }

        @Test
        @DisplayName("a row naming an unpublished event type reaches the dead-letter topic alone")
        void rowNamingAnUnpublishedEventTypeReachesTheDeadLetterTopicAlone() {
            OutboxEventEntity row = unknownTypeRow();
            dueRows(row);
            deadLetterSendSucceeds();

            relay().publishPendingEvents();

            verify(kafkaTemplate, never()).send(recordOn(ASSESSED_TOPIC));
            assertDiagnosticHolds(capturedDiagnostic(), CLEARED_PAYLOAD);
            assertRowIsTerminal(row);
        }

        @Test
        @DisplayName("a row carrying an unreadable payload reaches the dead-letter topic alone")
        void rowCarryingAnUnreadablePayloadReachesTheDeadLetterTopicAlone() {
            OutboxEventEntity row = unreadablePayloadRow();
            dueRows(row);
            deadLetterSendSucceeds();

            relay().publishPendingEvents();

            verify(kafkaTemplate, never()).send(recordOn(ASSESSED_TOPIC));
            assertDiagnosticHolds(capturedDiagnostic(), UNREADABLE_PAYLOAD);
            assertRowIsTerminal(row);
        }

        @Test
        @DisplayName("a send the contract refuses reaches the dead-letter topic after one attempt")
        void sendTheContractRefusesReachesTheDeadLetterTopicAfterOneAttempt() {
            OutboxEventEntity row = flaggedRow();
            dueRows(row);
            sendFails(ASSESSED_TOPIC,
                    new SerializationException("the event broke its schema document"));
            deadLetterSendSucceeds();

            relay().publishPendingEvents();

            verify(kafkaTemplate, times(1))
                    .send(recordFor(ASSESSED_TOPIC, ACCOUNT_IDENTIFIER));
            assertDiagnosticHolds(capturedDiagnostic(), FLAGGED_PAYLOAD);
            assertRowIsTerminal(row);
        }

        @Test
        @DisplayName("a dead-lettered row is counted once and removed never")
        void deadLetteredRowIsCountedOnceAndRemovedNever() {
            dueRows(unknownTypeRow());
            deadLetterSendSucceeds();

            relay().publishPendingEvents();

            verify(meters).recordDeadLetterPublished();
            verify(meters, never()).recordDeadLetterFailure();
            verify(meters).recordPublishFailure();
            verify(outboxEvents, never()).delete(any());
            verify(outboxEvents, never()).deleteById(any());
            verify(outboxEvents, never()).deleteAll();
            verify(outboxEvents, never()).deleteAllInBatch();
        }

        @Test
        @DisplayName("a refused diagnostic leaves the row open for a later tick")
        void refusedDiagnosticLeavesTheRowOpenForALaterTick() {
            OutboxEventEntity row = unknownTypeRow();
            dueRows(row);
            sendFails(DEAD_LETTER_TOPIC, new TimeoutException("the broker did not answer"));

            relay().publishPendingEvents();

            assertThat(row.isPublished()).isFalse();
            assertThat(row.getPublishedAt()).isNull();
            verify(meters).recordDeadLetterFailure();
            verify(meters, never()).recordDeadLetterPublished();
        }

        private DeadLetterEnvelope capturedDiagnostic() {
            ProducerRecord<String, Object> sent = sentRecordOn(DEAD_LETTER_TOPIC);
            assertThat(sent.key()).isEqualTo(ACCOUNT_IDENTIFIER);
            assertThat(sent.value()).isInstanceOf(DeadLetterEnvelope.class);
            return (DeadLetterEnvelope) sent.value();
        }

        private void assertDiagnosticHolds(DeadLetterEnvelope diagnostic, String payload) {
            assertThat(diagnostic.abendCode()).isNotBlank()
                    .hasSizeLessThanOrEqualTo(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH);
            assertThat(diagnostic.culprit()).isNotBlank()
                    .hasSizeLessThanOrEqualTo(DeadLetterMetadata.CULPRIT_MAX_LENGTH);
            assertThat(diagnostic.reason()).isNotBlank()
                    .hasSizeLessThanOrEqualTo(DeadLetterMetadata.REASON_MAX_LENGTH);
            assertThat(diagnostic.message()).isNotBlank()
                    .hasSizeLessThanOrEqualTo(DeadLetterMetadata.MESSAGE_MAX_LENGTH);
            assertThat(diagnostic.terminal()).isTrue();
            for (String text : List.of(diagnostic.reason(), diagnostic.message())) {
                assertThat(text)
                        .doesNotContain(payload)
                        .doesNotContain(ACCOUNT_IDENTIFIER)
                        .doesNotContain(TRANSACTION_IDENTIFIER);
                assertThat(text.toLowerCase(Locale.ROOT))
                        .as("a card verification value named in a diagnostic")
                        .doesNotContain("cvv");
                assertThat(LONG_DIGIT_RUN.matcher(text).find())
                        .as("a long digit run in " + text).isFalse();
                assertThat(LAYOUT_FIELD_NAME.matcher(text).find())
                        .as("a record-layout field name in " + text).isFalse();
            }
        }

        /**
         * Asserts a row the relay gave up on is recorded as abandoned, never as published.
         *
         * <p>Closing such a row with {@code markPublished} would leave an event that reached no
         * consumer marked {@link OutboxEventEntity.RelayState#PUBLISHED}: indistinguishable in the
         * table, in the retention sweep and in every metric from an assessment the broker
         * acknowledged. The only thing published would be the diagnostic saying it was not.
         *
         * <p>What the row carries is the truth in three parts. {@code ABANDONED} is terminal, and
         * {@code claimDueRows} never returns it, so the row is closed exactly as before.
         * {@code published} stays false and {@code published_at} stays absent, because nothing was
         * published. {@code dead_letter_state} reads {@code PUBLISHED} with a moment beside it, which
         * records that the diagnostic — and only the diagnostic — reached the broker.
         *
         * @param row the row the relay gave up on
         */
        private void assertRowIsTerminal(OutboxEventEntity row) {
            assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
            assertThat(row.isPublished())
                    .as("an event that reached no consumer is not published")
                    .isFalse();
            assertThat(row.getPublishedAt()).isNull();
            assertThat(row.getDeadLetterState())
                    .as("the diagnostic reached the broker, so the obligation is discharged")
                    .isEqualTo(OutboxEventEntity.DeadLetterState.PUBLISHED);
            assertThat(row.getDeadLetterPublishedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Partial batch")
    class PartialBatch {

        @Test
        @DisplayName("a failing middle row leaves the rows around it published")
        void failingMiddleRowLeavesTheRowsAroundItPublished() {
            OutboxEventEntity first = flaggedRow();
            OutboxEventEntity middle = unknownTypeRow();
            OutboxEventEntity last = thirdClearedRow();
            dueRows(first, middle, last);
            everySendSucceeds();

            relay().publishPendingEvents();

            List<ProducerRecord<String, Object>> sent = sentRecords(3);
            assertThat(sent.stream().map(ProducerRecord::key).toList())
                    .containsOnly(ACCOUNT_IDENTIFIER);
            // The two assessments precede the diagnostic rather than bracketing it, because a tick
            // issues the sends of one window before it waits for any of them: the middle row issues
            // no send at all, and its diagnostic is published while its outcome is recorded, after
            // both assessments are already on the wire. Which topics carry what is the property
            // under test; the interleaving was an artefact of sending one row at a time.
            assertThat(sent.stream().map(ProducerRecord::topic).toList())
                    .containsExactlyInAnyOrder(ASSESSED_TOPIC, ASSESSED_TOPIC, DEAD_LETTER_TOPIC);
            assertThat(sent.getLast().topic())
                    .as("the diagnostic follows the sends of its window")
                    .isEqualTo(DEAD_LETTER_TOPIC);
            assertThat(first.isPublished()).isTrue();
            assertThat(middle.isPublished())
                    .as("the middle row's event reached no consumer, so it is abandoned rather than"
                            + " published; only its diagnostic was sent")
                    .isFalse();
            assertThat(middle.getRelayState())
                    .isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
            assertThat(middle.getDeadLetterState())
                    .isEqualTo(OutboxEventEntity.DeadLetterState.PUBLISHED);
            assertThat(last.isPublished()).isTrue();
            // Two writes for a published row: the claim, which commits before any send starts, then
            // the mark once the broker acknowledged it.
            verify(outboxEvents, times(2)).save(first);
            // Three for the abandoned row, in this order: the claim, the abandonment with its
            // obligation, then the discharge once the broker acknowledged the diagnostic. One write
            // cannot express the last two, because a diagnostic the broker refuses has to leave the
            // obligation standing for a later pass to offer again.
            verify(outboxEvents, times(3)).save(middle);
            verify(outboxEvents, times(2)).save(last);
        }
    }

    @Nested
    @DisplayName("Two ticks and no repeat")
    class TwoTicksAndNoRepeat {

        @Test
        @DisplayName("a second tick over the same store sends nothing further")
        void secondTickOverTheSameStoreSendsNothingFurther() {
            OutboxEventEntity row = flaggedRow();
            dueRowsFrom(List.of(row));
            everySendSucceeds();
            OutboxRelay relay = relay();

            relay.publishPendingEvents();
            assertThat(row.isPublished()).isTrue();
            relay.publishPendingEvents();

            verify(kafkaTemplate, times(1)).send(anyRecord());
            // The claim and the mark of the one tick that had work; the second tick claims nothing,
            // so it writes nothing. Three claim calls: the first tick claims the row, claims again to
            // see whether anything followed it, and the second tick claims once and finds nothing.
            verify(outboxEvents, times(2)).save(row);
            verify(outboxEvents, times(3)).claimDueRows(any(), any());
        }

        @Test
        @DisplayName("a tick claims no more rows than the configured bound")
        void tickClaimsNoMoreRowsThanTheConfiguredBound() {
            OutboxEventEntity first = flaggedRow();
            OutboxEventEntity second = clearedRow();
            dueRowsFrom(List.of(first, second));
            everySendSucceeds();
            OutboxRelay bounded = new OutboxRelay(outboxEvents, publisher, meters,
                    ASSESSED_TOPIC, DEAD_LETTER_TOPIC, transactionTemplate, properties(1));

            bounded.publishPendingEvents();

            verify(kafkaTemplate, times(1)).send(anyRecord());

            bounded.publishPendingEvents();

            verify(kafkaTemplate, times(2)).send(anyRecord());
            assertThat(first.isPublished()).isTrue();
            assertThat(second.isPublished()).isTrue();
        }
    }

    @Nested
    @DisplayName("The row that runs out of attempts")
    class AbandonedRow {

        /**
         * Fails the assessed topic on every attempt and lets the dead-letter topic answer.
         *
         * <p>The relay abandons a row only when its recorded failure reaches the attempt ceiling, so
         * a test that needs an abandonment has to drive the row to the ceiling rather than assert on
         * one refused send.
         */
        private void everyAssessedSendFails() {
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));
            deadLetterSendSucceeds();
        }

        /**
         * Drives one row through the ceiling by giving it a tick per attempt.
         *
         * <p>The claim query is stubbed unconditionally rather than through the backoff-aware
         * stand-in, because the backoff is not what is under test here: a tick that honoured it
         * would place the next attempt in the future and one tick would produce one attempt for
         * ever. The relay claims each row itself, so nothing here touches the row's state.
         *
         * <p>The loop runs exactly {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} times, which is
         * the attempt that abandons the row. An eleventh tick would try to claim a terminal row and
         * the entity refuses that, which is the guard rather than a limitation of this helper.
         *
         * @param row the row every tick refuses to publish
         */
        private void spendEveryAttempt(OutboxEventEntity row) {
            dueRows(row);
            OutboxRelay relay = relay();
            for (int attempt = 0; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
                relay.publishPendingEvents();
            }
        }

        @Test
        @DisplayName("the abandonment and the diagnostic obligation are one write")
        void theAbandonmentAndTheObligationAreOneWrite() {
            OutboxEventEntity row = clearedRow();
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));
            sendFails(DEAD_LETTER_TOPIC, new TimeoutException("the broker did not answer"));

            spendEveryAttempt(row);

            assertThat(row.getRelayState())
                    .as("the row must be abandoned once its attempts are spent")
                    .isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
            assertThat(row.owesDeadLetter())
                    .as("an abandoned row nobody has named owes a diagnostic")
                    .isTrue();
            assertThat(row.getDeadLetterState())
                    .isEqualTo(OutboxEventEntity.DeadLetterState.REQUIRED);
            assertThat(row.getDeadLetterPublishedAt())
                    .as("no acknowledgement has arrived, so no moment is recorded")
                    .isNull();
        }

        @Test
        @DisplayName("the abandoning tick names the row and clears the obligation")
        void theAbandoningTickNamesTheRowAndClearsTheObligation() {
            OutboxEventEntity row = clearedRow();
            everyAssessedSendFails();

            spendEveryAttempt(row);

            assertThat(row.getRelayState())
                    .isEqualTo(OutboxEventEntity.RelayState.ABANDONED);
            assertThat(row.owesDeadLetter())
                    .as("the broker acknowledged the diagnostic, so nothing is owed")
                    .isFalse();
            assertThat(row.getDeadLetterState())
                    .isEqualTo(OutboxEventEntity.DeadLetterState.PUBLISHED);
            assertThat(row.getDeadLetterPublishedAt()).isNotNull();
            verify(kafkaTemplate, times(1)).send(recordOn(DEAD_LETTER_TOPIC));
        }

        @Test
        @DisplayName("an abandoned row is never marked published, because nothing was delivered")
        void anAbandonedRowIsNeverMarkedPublished() {
            OutboxEventEntity row = clearedRow();
            everyAssessedSendFails();

            spendEveryAttempt(row);

            assertThat(row.isPublished())
                    .as("the assessment reached no consumer, so the row is not published")
                    .isFalse();
            assertThat(row.getPublishedAt()).isNull();
        }

        @Test
        @DisplayName("an obligation an earlier pass left owing is offered again at the next head")
        void anObligationLeftOwingIsOfferedAgainAtTheNextHead() {
            OutboxEventEntity row = clearedRow();
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));
            sendFails(DEAD_LETTER_TOPIC, new TimeoutException("the broker did not answer"));
            spendEveryAttempt(row);
            assertThat(row.owesDeadLetter()).isTrue();

            // The broker recovers, and the row is no longer claimable: it is ABANDONED, so only the
            // owed-diagnostic read at the head of a pass can reach it.
            // doReturn, not when: a when(...) call invokes the method on the mock, and the answer
            // already in place for the claim query reads its arguments, which are null under a
            // matcher.
            doReturn(List.of()).when(outboxEvents).claimDueRows(any(), any());
            doReturn(List.of(row)).when(outboxEvents)
                    .findByDeadLetterStateOrderByLastAttemptAtAsc(
                            eq(OutboxEventEntity.DeadLetterState.REQUIRED), any());
            deadLetterSendSucceeds();

            relay().publishPendingEvents();

            assertThat(row.owesDeadLetter())
                    .as("the later pass named the row, so the obligation is discharged")
                    .isFalse();
            assertThat(row.getDeadLetterState())
                    .isEqualTo(OutboxEventEntity.DeadLetterState.PUBLISHED);
            assertThat(row.getDeadLetterPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("only an acknowledgement clears the obligation, never the attempt")
        void onlyAnAcknowledgementClearsTheObligation() {
            OutboxEventEntity row = clearedRow();
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));
            sendFails(DEAD_LETTER_TOPIC, new TimeoutException("the broker did not answer"));
            spendEveryAttempt(row);
            doReturn(List.of()).when(outboxEvents).claimDueRows(any(), any());
            doReturn(List.of(row)).when(outboxEvents)
                    .findByDeadLetterStateOrderByLastAttemptAtAsc(
                            eq(OutboxEventEntity.DeadLetterState.REQUIRED), any());

            relay().publishPendingEvents();
            relay().publishPendingEvents();

            assertThat(row.owesDeadLetter())
                    .as("two refused offers must leave the obligation standing")
                    .isTrue();
            assertThat(row.getDeadLetterState())
                    .isEqualTo(OutboxEventEntity.DeadLetterState.REQUIRED);
        }

        @Test
        @DisplayName("the owed read is ordered by the attempt that has gone unnamed longest")
        void theOwedReadIsOrderedByTheLongestUnnamedAttempt() {
            relay().publishPendingEvents();

            ArgumentCaptor<Limit> bound = ArgumentCaptor.forClass(Limit.class);
            verify(outboxEvents).findByDeadLetterStateOrderByLastAttemptAtAsc(
                    eq(OutboxEventEntity.DeadLetterState.REQUIRED), bound.capture());
            assertThat(bound.getValue().max())
                    .as("the owed read is bounded by the same batch size as the claim")
                    .isEqualTo(DEFAULT_BATCH_SIZE);
        }

        @Test
        @DisplayName("an abandonment counts one row, not one attempt per row")
        void anAbandonmentCountsOneRowNotOneAttemptPerRow() {
            OutboxEventEntity row = clearedRow();
            everyAssessedSendFails();

            spendEveryAttempt(row);

            verify(meters, times(1)).recordOutboxAbandoned();
            verify(meters, times(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS))
                    .recordPublishFailure();
            verify(meters, times(1)).recordDeadLetterPublished();
            verify(meters, never()).recordDeadLetterFailure();
        }

        @Test
        @DisplayName("an unnamed abandonment counts the refusal and still counts the row")
        void anUnnamedAbandonmentCountsTheRefusalAndStillCountsTheRow() {
            OutboxEventEntity row = clearedRow();
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));
            sendFails(DEAD_LETTER_TOPIC, new TimeoutException("the broker did not answer"));

            spendEveryAttempt(row);

            verify(meters, times(1)).recordOutboxAbandoned();
            verify(meters, times(1)).recordDeadLetterFailure();
            verify(meters, never()).recordDeadLetterPublished();
        }

        @Test
        @DisplayName("the diagnostic keys on the account identifier and quotes no payload value")
        void theDiagnosticKeysOnTheAccountIdentifierAndQuotesNoPayloadValue() {
            OutboxEventEntity row = clearedRow();
            everyAssessedSendFails();

            spendEveryAttempt(row);

            ProducerRecord<String, Object> sent = sentRecordOn(DEAD_LETTER_TOPIC);
            assertThat(sent.key()).isEqualTo(ACCOUNT_IDENTIFIER);
            assertThat(String.valueOf(sent.value()))
                    .as("a diagnostic must carry no property of the payload it names")
                    .doesNotContain(TRANSACTION_IDENTIFIER);
        }

        @Test
        @DisplayName("a healthy relay pays for one indexed read of no owed rows")
        void aHealthyRelayPaysForOneIndexedReadOfNoOwedRows() {
            dueRows(flaggedRow());
            everySendSucceeds();

            relay().publishPendingEvents();

            verify(outboxEvents, times(1)).findByDeadLetterStateOrderByLastAttemptAtAsc(
                    eq(OutboxEventEntity.DeadLetterState.REQUIRED), any());
            verify(kafkaTemplate, never()).send(recordOn(DEAD_LETTER_TOPIC));
            verify(meters, never()).recordOutboxAbandoned();
        }
    }

    /**
     * The row exposes no setter, so every state change it can undergo is one of a closed set of named
     * transitions. Enumerating that set here is what keeps it closed: an eighth mutator arriving
     * without a decision behind it fails this test rather than reaching the table.
     */
    @Nested
    @DisplayName("Row mutator surface")
    class RowMutatorSurface {

        @Test
        @DisplayName("the row declares no setter and seven named mutators")
        void rowDeclaresNoSetterAndSevenNamedMutators() {
            List<String> setters = Arrays.stream(OutboxEventEntity.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("set"))
                    .sorted()
                    .toList();
            List<String> mutators = Arrays.stream(OutboxEventEntity.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> method.getReturnType() == void.class)
                    .map(Method::getName)
                    .sorted()
                    .toList();

            assertThat(setters).as("setters on the row").isEmpty();
            assertThat(mutators).containsExactly(
                    "abandon",
                    "claim",
                    "markDeadLetterPublished",
                    "markPublished",
                    "recordCorrelation",
                    "recordFailure",
                    "releaseUnattemptedClaim");
        }

        @Test
        @DisplayName("abandon is the transition a permanent failure takes, and it is not markPublished")
        void abandonIsTheTransitionAPermanentFailureTakesAndItIsNotMarkPublished() {
            OutboxEventEntity row = flaggedRow();
            Instant when = Instant.parse("2025-02-01T10:15:30Z");

            row.abandon("SerializationException", when);

            assertAll(
                    () -> assertThat(row.getRelayState())
                            .as("a row given up on is terminal, and not PUBLISHED")
                            .isEqualTo(OutboxEventEntity.RelayState.ABANDONED),
                    () -> assertFalse(row.isPublished(), "nothing reached a consumer"),
                    () -> assertThat(row.getPublishedAt()).as("published_at").isNull(),
                    () -> assertThat(row.getDeadLetterState())
                            .as("the obligation the same write records")
                            .isEqualTo(OutboxEventEntity.DeadLetterState.REQUIRED),
                    () -> assertThat(row.getAttemptCount()).as("the attempt that failed").isEqualTo(1),
                    () -> assertThat(row.getLastAttemptAt()).isEqualTo(when),
                    () -> assertThat(row.getNextAttemptAt()).as("no future retry is advertised")
                            .isEqualTo(when),
                    () -> assertThat(row.getClaimedBy()).as("the claim is released").isNull(),
                    () -> assertThat(row.getClaimedAt()).isNull());
        }

        @Test
        @DisplayName("a row already given up on takes no further attempt")
        void aRowAlreadyGivenUpOnTakesNoFurtherAttempt() {
            OutboxEventEntity row = flaggedRow();
            row.abandon("SerializationException", Instant.parse("2025-02-01T10:15:30Z"));

            assertThatThrownBy(() -> row.abandon("again", Instant.parse("2025-02-01T10:16:30Z")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ABANDONED");
        }
    }

    @Nested
    @DisplayName("The listener publishes nothing")
    class ListenerPublishesNothing {

        @Test
        @DisplayName("the listener holds no broker member and declares no send method")
        void listenerHoldsNoBrokerMemberAndDeclaresNoSendMethod() {
            List<String> brokerFields =
                    Arrays.stream(TransactionAuthorizedConsumer.class.getDeclaredFields())
                            .filter(field -> isBrokerType(field.getType()))
                            .map(Field::getName)
                            .toList();
            List<String> brokerParameters = new ArrayList<>();
            for (Constructor<?> constructor
                    : TransactionAuthorizedConsumer.class.getDeclaredConstructors()) {
                Arrays.stream(constructor.getParameterTypes())
                        .filter(ListenerPublishesNothing::isBrokerType)
                        .map(Class::getName)
                        .forEach(brokerParameters::add);
            }
            List<String> senders =
                    Arrays.stream(TransactionAuthorizedConsumer.class.getDeclaredMethods())
                            .map(Method::getName)
                            .filter("send"::equals)
                            .toList();

            assertThat(brokerFields).as("broker members of the listener").isEmpty();
            assertThat(brokerParameters).as("broker constructor arguments").isEmpty();
            assertThat(senders).as("send methods on the listener").isEmpty();
        }

        @Test
        @DisplayName("the listener source names no broker template and no send call")
        void listenerSourceNamesNoBrokerTemplateAndNoSendCall() throws Exception {
            String source = sourceTextOf(CONSUMER_SOURCE);

            assertThat(source).doesNotContain("KafkaTemplate");
            assertThat(source).doesNotContain(".send(");
        }

        /**
         * Holds the relay to the seam as well as the listener.
         *
         * <p>The relay reached the broker through {@code KafkaTemplate} directly, so the broker choice
         * was a compile-time dependency of the one component that decides what gets published. It now
         * depends on {@code messaging/EventPublisherPort}, which is the one swappable seam this platform
         * declares, and the adapter behind it is the only class here that names a broker type.
         *
         * <p>Asserted over the declared members rather than the source text, because a field whose type
         * is imported and a field whose type is fully qualified read differently in text and identically
         * to the container.
         */
        @Test
        @DisplayName("the relay holds the publisher port and no broker member at all")
        void relayHoldsThePublisherPortAndNoBrokerMemberAtAll() {
            List<String> brokerFields = Arrays.stream(OutboxRelay.class.getDeclaredFields())
                    .filter(field -> isBrokerType(field.getType()))
                    .map(Field::getName)
                    .toList();
            List<String> brokerParameters = new ArrayList<>();
            for (Constructor<?> constructor : OutboxRelay.class.getDeclaredConstructors()) {
                Arrays.stream(constructor.getParameterTypes())
                        .filter(ListenerPublishesNothing::isBrokerType)
                        .map(Class::getName)
                        .forEach(brokerParameters::add);
            }
            List<String> ports = Arrays.stream(OutboxRelay.class.getDeclaredFields())
                    .map(Field::getType)
                    .map(Class::getName)
                    .filter(EventPublisherPort.class.getName()::equals)
                    .toList();

            assertThat(brokerFields).as("broker members of the relay").isEmpty();
            assertThat(brokerParameters).as("broker constructor arguments of the relay").isEmpty();
            assertThat(ports).as("the seam the relay publishes through").hasSize(1);
        }

        /**
         * The adapter is the only class in this service that may name a broker client or template, and it
         * is required to pin the two settings a terminal diagnostic depends on.
         *
         * <p>The relay is read for its imports rather than its whole text, because its field comment
         * names the broker client it must not hold. Naming a dependency in prose is not holding one,
         * and the assertion above this one is what proves the structure.
         *
         * <p>One broker-package import is admitted and it is not a transport dependency:
         * {@code org.apache.kafka.common.errors.SerializationException} is the failure
         * {@code libs/event-contracts}' own {@code JsonSchemaValidatingSerializer} raises for a payload
         * its schema document refuses, and that classification is what tells the relay a failure is
         * permanent. Any adapter behind the port writes through the same serializer, so the type travels
         * with the contract library rather than with the choice of broker. What may not appear is a
         * client, a producer or a template.
         */
        @Test
        @DisplayName("the relay imports no broker client or template, and the adapter checks the producer")
        void relayImportsNoBrokerClientOrTemplateAndTheAdapterChecksTheProducer() throws Exception {
            List<String> transportImports = sourceTextOf(RELAY_SOURCE).lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import org.springframework.kafka")
                            || line.startsWith("import org.apache.kafka.clients")
                            || line.startsWith("import org.apache.kafka.common.serialization"))
                    .toList();
            List<String> otherBrokerImports = sourceTextOf(RELAY_SOURCE).lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import org.apache.kafka"))
                    .filter(line -> !transportImports.contains(line))
                    .toList();

            assertThat(transportImports)
                    .as("the relay reaches the broker through the port alone")
                    .isEmpty();
            assertThat(otherBrokerImports)
                    .as("the one admitted broker import, which the contract library raises")
                    .containsExactly("import org.apache.kafka.common.errors.SerializationException;");
            assertThat(sourceTextOf(PUBLISHER_SOURCE))
                    .as("the adapter refuses a producer that could lose an acknowledged send")
                    .contains("ACKS_CONFIG")
                    .contains("ENABLE_IDEMPOTENCE_CONFIG");
        }

        private static boolean isBrokerType(Class<?> type) {
            String name = type.getName();
            return name.startsWith("org.springframework.kafka")
                    || name.startsWith("org.apache.kafka");
        }
    }

    /**
     * Holds the pass budget to its stated meaning: {@code carddemo.outbox.relay.max-duration-ms} bounds
     * the whole tick, so a send is waited out against what is left of that budget and not against the
     * budget again.
     *
     * <p>Waiting the full value per send made the setting a per-send ceiling wearing the name of a
     * wall-time limit: a send admitted a millisecond before the deadline kept the scheduled thread for
     * another whole window, and a tick could run for close to twice its stated bound. The account
     * service's relay already waited only the remainder, so the two now read one setting the same way.
     *
     * <p>The cost is the one this platform already carries everywhere. A send abandoned with the budget
     * spent may still reach the broker, and the next tick offers the row again, so one event can be
     * published twice — which is what every consumer's processed-event marker exists to absorb, and
     * what {@code card-platform/docs/event-flow.md} states about publication being at least once.
     */
    @Nested
    @DisplayName("Pass budget")
    class PassBudget {

        /**
         * Asserts one send that outlives the pass budget ends the pass inside its stated bound.
         *
         * <p>{@code carddemo.outbox.relay.max-duration-ms} is a wall-time limit on the whole tick, so
         * a broker that accepts a send and answers late costs one pass and nothing more. Waiting the
         * configured value per send instead made the setting a per-send ceiling wearing the name of a
         * limit on the tick, and a pass could run for close to twice its stated bound.
         *
         * <p>One row, and a send that takes four times the budget. Two rows would prove less than
         * they appear to now that a window issues its sends together and waits for them in turn: two
         * sends of 400 milliseconds against a 500 millisecond budget both resolve inside it, so the
         * budget is never reached and the assertion would pass on either implementation. The
         * remainder arithmetic per send is read directly, and far more precisely, by
         * {@link #eachSendIsGrantedTheRemainderOfThePass()} below.
         *
         * <p>The cost is the one this platform already carries everywhere. A send abandoned with the
         * budget spent may still reach the broker, and the next tick offers the row again, so one
         * event can be published twice — which is what every consumer's processed-event marker exists
         * to absorb, and what {@code card-platform/docs/event-flow.md} states about publication being
         * at least once.
         */
        @Test
        @DisplayName("a send that outlives the pass budget ends the pass inside its stated bound")
        void aSendThatOutlivesThePassBudgetEndsThePassInsideItsStatedBound() {
            long passBudgetMs = 500L;
            long sendMs = 2_000L;
            OutboxEventEntity stalled = clearedRow();
            OutboxRelay relayUnderBudget = new OutboxRelay(outboxEvents, publisher, meters,
                    ASSESSED_TOPIC, DEAD_LETTER_TOPIC, transactionTemplate,
                    propertiesWithPassBudget(passBudgetMs));
            dueRows(stalled);
            when(kafkaTemplate.send(recordFor(ASSESSED_TOPIC, ACCOUNT_IDENTIFIER)))
                    .thenAnswer(call -> CompletableFuture.supplyAsync(
                            () -> acknowledgeAfter(sendMs)));

            long startedAt = System.nanoTime();
            relayUnderBudget.publishPendingEvents();
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            // The send is waited for what the pass has left and no longer, so the tick ends near its
            // 500 millisecond bound rather than at the 2000 the broker took. The row becomes due
            // again, and a consumer marker absorbs the copy a late delivery of this send would add.
            assertAll("the tick honoured its own wall-time bound",
                    () -> assertFalse(stalled.isPublished(),
                            "the send outlived the budget the tick had, so its row stays "
                                    + "unpublished and the next tick offers it again"),
                    () -> assertThat(stalled.getAttemptCount()).isEqualTo(1),
                    () -> assertTrue(elapsedMs < sendMs,
                            () -> "the tick ran " + elapsedMs + " ms against a stated bound of "
                                    + passBudgetMs + " ms, so it waited out the whole send"));
            verify(kafkaTemplate).send(recordFor(ASSESSED_TOPIC, ACCOUNT_IDENTIFIER));
            verify(meters).recordPublishFailure();
        }

        /**
         * Asserts each send is granted what the pass has left, never the whole budget again.
         *
         * <p>The assertion above reads the wall clock, which is what a slow send needs. This one
         * reads the timeout the relay asked for instead, so it needs no delay at all: each send is
         * already acknowledged and the port it travels through records the value it was granted.
         *
         * <p>{@code sendWithinDeadline} computes {@code deadline - System.nanoTime()} per send, so a
         * later send in the same pass is always granted strictly less than an earlier one. A relay
         * that passed the configured value to every send would grant the same figure twice, which is
         * the defect this discriminates: it would let a pass admitted a moment before its deadline
         * run for another whole window.
         *
         * <p>The recording sits at {@code messaging/EventPublisherPort}, which is the type the relay
         * holds, and not inside the future the mocked template answers with. The shipped adapter
         * composes that future with {@code thenApply}, and composition builds a <em>new</em>
         * {@link CompletableFuture} rather than returning the one it was given, so a recording
         * subclass handed to the template is never the object the relay waits on. Decorating the port
         * records the wait the relay actually issues, and the decorator still delegates to the real
         * adapter so the send reaches the mocked template exactly as it does in production.
         */
        @Test
        @DisplayName("each send is granted the remainder of the pass, never the whole budget")
        void eachSendIsGrantedTheRemainderOfThePass() {
            long passBudgetMs = 500L;
            long wholeBudgetNanos = TimeUnit.MILLISECONDS.toNanos(passBudgetMs);
            List<Long> grantedNanos = new CopyOnWriteArrayList<>();
            OutboxEventEntity first = clearedRow();
            OutboxEventEntity second = thirdClearedRow();
            OutboxRelay relayUnderBudget = new OutboxRelay(outboxEvents,
                    recordingPublisher(publisher, grantedNanos), meters,
                    ASSESSED_TOPIC, DEAD_LETTER_TOPIC, transactionTemplate,
                    propertiesWithPassBudget(passBudgetMs));
            dueRows(first, second);
            everySendSucceeds();

            relayUnderBudget.publishPendingEvents();

            assertAll("the timeout each send was granted",
                    () -> assertThat(grantedNanos)
                            .as("each of the two sends was waited on exactly once")
                            .hasSize(2),
                    () -> assertThat(grantedNanos.getFirst())
                            .as("the first send is granted what the pass has left, which is under"
                                    + " the whole budget by the time the claim took")
                            .isLessThan(wholeBudgetNanos),
                    () -> assertThat(grantedNanos.get(1))
                            .as("the second is granted strictly less again, so the deadline is"
                                    + " shared by the pass rather than restarted per send")
                            .isLessThan(grantedNanos.getFirst()),
                    () -> assertTrue(first.isPublished() && second.isPublished(),
                            "both sends resolved inside the pass, so both rows are published"));
            verify(kafkaTemplate, times(2))
                    .send(recordFor(ASSESSED_TOPIC, ACCOUNT_IDENTIFIER));
        }
    }

    /** Sleeps for the broker's simulated acknowledgement delay, then answers with no result. */
    private static Object acknowledgeAfter(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the simulated acknowledgement was interrupted",
                    interrupted);
        }
        return null;
    }

    /**
     * Wraps one publisher so the timeout the relay grants each send is recorded.
     *
     * <p>Every send still travels through {@code delegate}, which is the shipped
     * {@code messaging/KafkaEventPublisher} over the mocked template, so the topic, the key and the
     * payload that reach the broker client are the ones production sends. What this adds is a stage
     * the relay's wait is measurable through: {@link CompletableFuture#toCompletableFuture()} answers
     * with the instance itself, so overriding {@code get(long, TimeUnit)} here observes the exact
     * bound {@code sendWithinDeadline} asks for.
     *
     * <p>The returned stage resolves the way the delegate's does, and it carries the delegate's
     * failure unchanged, so a decorated send is indistinguishable from an undecorated one apart from
     * the recording.
     *
     * @param delegate     the publisher every send is issued through
     * @param grantedNanos collects one entry per wait, in nanoseconds
     * @return the recording publisher the relay under test holds
     */
    private static EventPublisherPort recordingPublisher(EventPublisherPort delegate,
            List<Long> grantedNanos) {
        return (topic, aggregateId, event) -> {
            CompletionStage<Void> issued = delegate.publish(topic, aggregateId, event);
            CompletableFuture<Void> recorded = new CompletableFuture<>() {
                @Override
                public Void get(long timeout, TimeUnit unit) throws InterruptedException,
                        ExecutionException, java.util.concurrent.TimeoutException {
                    grantedNanos.add(unit.toNanos(timeout));
                    return super.get(timeout, unit);
                }
            };
            issued.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    recorded.complete(null);
                } else {
                    recorded.completeExceptionally(failure);
                }
            });
            return recorded;
        };
    }

    /** Builds the shipped settings with one chosen relay pass budget in milliseconds. */
    private static FraudProperties propertiesWithPassBudget(long passBudgetMs) {
        FraudProperties shipped = properties(DEFAULT_BATCH_SIZE);
        FraudProperties.Outbox.Relay relay = shipped.outbox().relay();
        return new FraudProperties(shipped.kafka(), shipped.consumer(),
                new FraudProperties.Outbox(new FraudProperties.Outbox.Relay(relay.fixedDelayMs(),
                        relay.batchSize(), relay.instanceId(), relay.claimTimeout(), passBudgetMs),
                        shipped.outbox().publishedRetentionHours()),
                shipped.retention(), shipped.fraud());
    }
}
