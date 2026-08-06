package com.carddemo.fraud.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.config.ObservabilityConfig.FraudMeters;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.messaging.DeadLetterMetadata;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
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

    /** Eleven decimal digits, the shape every message key holds. */
    private static final Pattern ELEVEN_DIGITS = Pattern.compile("^[0-9]{11}$");

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

    private OutboxEventRepository outboxEvents;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private FraudMeters meters;
    private TransactionTemplate transactionTemplate;
    private AtomicBoolean insideTransaction;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpCollaborators() {
        outboxEvents = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        meters = mock(FraudMeters.class);
        transactionTemplate = mock(TransactionTemplate.class);
        insideTransaction = new AtomicBoolean();

        when(outboxEvents.save(any(OutboxEventEntity.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(outboxEvents.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                any(), any(), any())).thenReturn(List.of());
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
        return new OutboxRelay(outboxEvents, kafkaTemplate, meters, assessedTopic,
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

    /** Stubs every send to answer with a resolved result. */
    private void everySendSucceeds() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    /** Stubs sends to {@code topic} to answer with a result already carrying {@code cause}. */
    private void sendFails(String topic, Throwable cause) {
        when(kafkaTemplate.send(eq(topic), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(cause));
    }

    /** Stubs sends to the dead-letter topic alone to answer with a resolved result. */
    private void deadLetterSendSucceeds() {
        when(kafkaTemplate.send(eq(DEAD_LETTER_TOPIC), anyString(), any()))
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
                new FraudProperties.ProcessedEvent(168L),
                new FraudProperties.Retention(3_600_000L),
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

        @Test
        @DisplayName("one tick opens one transaction and counts after it closes")
        void oneTickOpensOneTransactionAndCountsAfterItCloses() {
            dueRows(clearedRow());
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));
            doAnswer(call -> {
                assertThat(insideTransaction.get())
                        .as("a count taken while the transaction is open").isFalse();
                return null;
            }).when(meters).recordPublishFailure();

            relay().publishPendingEvents();

            verify(transactionTemplate, times(1)).execute(any());
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
        @DisplayName("the store declares five methods and no name carries a digit")
        void storeDeclaresFiveMethodsAndNoNameCarriesADigit() {
            List<String> names = Arrays.stream(OutboxEventRepository.class.getDeclaredMethods())
                    .map(Method::getName)
                    .sorted()
                    .toList();

            assertThat(names).containsExactly(
                    "claimDueRows",
                    "deletePublishedBefore",
                    "existsByRelayState",
                    "findByPublishedFalseOrderByCreatedAtAsc",
                    "findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc");
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

        @Test
        @DisplayName("one tick reads, saves once for each row and touches nothing else")
        void oneTickReadsSavesOnceForEachRowAndTouchesNothingElse() {
            OutboxEventEntity first = flaggedRow();
            OutboxEventEntity second = clearedRow();
            dueRows(first, second);
            everySendSucceeds();

            relay().publishPendingEvents();

            verify(outboxEvents).findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                    eq(OutboxEventEntity.RelayState.CLAIMED), any(), any());
            verify(outboxEvents).claimDueRows(any(), any());
            verify(outboxEvents, times(1)).save(first);
            verify(outboxEvents, times(1)).save(second);
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
            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);

            relay().publishPendingEvents();

            verify(kafkaTemplate, times(2)).send(anyString(), keys.capture(), any());
            assertThat(keys.getAllValues())
                    .containsExactly(ACCOUNT_IDENTIFIER, ACCOUNT_IDENTIFIER);
            assertThat(keys.getAllValues()).allSatisfy(key -> {
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
            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);

            relay().publishPendingEvents();

            verify(kafkaTemplate).send(eq(DEAD_LETTER_TOPIC), keys.capture(), any());
            assertThat(keys.getValue()).isEqualTo(ACCOUNT_IDENTIFIER);
        }

        @Test
        @DisplayName("the relay sends through the topic, key and value overload alone")
        void relaySendsThroughTheTopicKeyAndValueOverloadAlone() {
            dueRows(flaggedRow());
            everySendSucceeds();

            relay().publishPendingEvents();

            verify(kafkaTemplate).send(eq(ASSESSED_TOPIC), eq(ACCOUNT_IDENTIFIER), any());
            verify(kafkaTemplate, never()).send(anyString(), any());
            verify(kafkaTemplate, never())
                    .send(ArgumentMatchers.<ProducerRecord<String, Object>>any());
            verifyNoMoreInteractions(kafkaTemplate);
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

            verify(kafkaTemplate).send(eq(SENTINEL_TOPIC), eq(ACCOUNT_IDENTIFIER), any());
            verify(kafkaTemplate, never()).send(eq(ASSESSED_TOPIC), anyString(), any());
        }

        @Test
        @DisplayName("a relay built with the assessed topic sends against the assessed topic")
        void relayBuiltWithTheAssessedTopicSendsAgainstTheAssessedTopic() {
            dueRows(flaggedRow());
            everySendSucceeds();

            relayPublishingTo(ASSESSED_TOPIC).publishPendingEvents();

            verify(kafkaTemplate).send(eq(ASSESSED_TOPIC), eq(ACCOUNT_IDENTIFIER), any());
            verify(kafkaTemplate, never()).send(eq(SENTINEL_TOPIC), anyString(), any());
        }

        @Test
        @DisplayName("the stored event type selects the record type on one shared topic")
        void storedEventTypeSelectsTheRecordTypeOnOneSharedTopic() {
            dueRows(flaggedRow(), clearedRow());
            everySendSucceeds();
            ArgumentCaptor<String> topics = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);

            relay().publishPendingEvents();

            verify(kafkaTemplate, times(2))
                    .send(topics.capture(), anyString(), values.capture());
            assertThat(topics.getAllValues()).containsExactly(ASSESSED_TOPIC, ASSESSED_TOPIC);
            assertThat(values.getAllValues().get(0)).isInstanceOf(FraudFlagged.class);
            assertThat(values.getAllValues().get(1)).isInstanceOf(FraudCleared.class);
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
            ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);

            relay().publishPendingEvents();

            verify(kafkaTemplate)
                    .send(eq(ASSESSED_TOPIC), eq(ACCOUNT_IDENTIFIER), values.capture());
            assertThat(values.getValue()).isInstanceOf(FraudFlagged.class);
            FraudFlagged published = (FraudFlagged) values.getValue();
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
            when(kafkaTemplate.send(eq(ASSESSED_TOPIC), eq(ACCOUNT_IDENTIFIER), any()))
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

            verify(outboxEvents).save(saved.capture());
            assertThat(saved.getValue()).isSameAs(row);
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
        @DisplayName("two rows in one tick are marked through one save each")
        void twoRowsInOneTickAreMarkedThroughOneSaveEach() {
            OutboxEventEntity first = flaggedRow();
            OutboxEventEntity second = clearedRow();
            dueRows(first, second);
            everySendSucceeds();

            relay().publishPendingEvents();

            assertThat(first.isPublished()).isTrue();
            assertThat(first.getPublishedAt()).isNotNull();
            assertThat(second.isPublished()).isTrue();
            assertThat(second.getPublishedAt()).isNotNull();
            verify(outboxEvents, times(1)).save(first);
            verify(outboxEvents, times(1)).save(second);
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

            verify(kafkaTemplate, never()).send(eq(DEAD_LETTER_TOPIC), anyString(), any());
            verify(meters, never()).recordDeadLetterPublished();
            verify(meters, never()).recordDeadLetterFailure();
        }

        @Test
        @DisplayName("a refused send stops the tick at the failing row")
        void refusedSendStopsTheTickAtTheFailingRow() {
            OutboxEventEntity failing = clearedRow();
            OutboxEventEntity behind = thirdClearedRow();
            dueRows(failing, behind);
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));

            relay().publishPendingEvents();

            verify(kafkaTemplate, times(1)).send(eq(ASSESSED_TOPIC), anyString(), any());
            assertThat(failing.getAttemptCount()).isEqualTo(1);
            assertThat(behind.getAttemptCount()).isZero();
            assertThat(behind.isPublished()).isFalse();
            assertThat(behind.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PENDING);
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
                    .send(eq(ASSESSED_TOPIC), eq(ACCOUNT_IDENTIFIER), any());
            assertThat(row.getAttemptCount()).isEqualTo(2);
            assertThat(row.isPublished()).isFalse();
        }

        @Test
        @DisplayName("a refused send is reported with its failure class and no account identifier")
        void refusedSendIsReportedWithItsFailureClassAndNoAccountIdentifier() {
            dueRows(clearedRow());
            sendFails(ASSESSED_TOPIC, new TimeoutException("the broker did not answer"));

            relay().publishPendingEvents();

            List<String> warnings = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0))
                    .contains(FraudCleared.EVENT_TYPE)
                    .contains(TimeoutException.class.getSimpleName())
                    .doesNotContain(ACCOUNT_IDENTIFIER)
                    .doesNotContain(TRANSACTION_IDENTIFIER);
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
            verify(kafkaTemplate).send(eq(ASSESSED_TOPIC), eq(ACCOUNT_IDENTIFIER), any());
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

            verify(kafkaTemplate, never()).send(eq(ASSESSED_TOPIC), anyString(), any());
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

            verify(kafkaTemplate, never()).send(eq(ASSESSED_TOPIC), anyString(), any());
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
                    .send(eq(ASSESSED_TOPIC), eq(ACCOUNT_IDENTIFIER), any());
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
            ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
            verify(kafkaTemplate)
                    .send(eq(DEAD_LETTER_TOPIC), eq(ACCOUNT_IDENTIFIER), values.capture());
            assertThat(values.getValue()).isInstanceOf(DeadLetterEnvelope.class);
            return (DeadLetterEnvelope) values.getValue();
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

        private void assertRowIsTerminal(OutboxEventEntity row) {
            assertThat(row.isPublished()).isTrue();
            assertThat(row.getPublishedAt()).isNotNull();
            assertThat(row.getRelayState()).isEqualTo(OutboxEventEntity.RelayState.PUBLISHED);
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
            ArgumentCaptor<String> topics = ArgumentCaptor.forClass(String.class);

            relay().publishPendingEvents();

            verify(kafkaTemplate, times(3))
                    .send(topics.capture(), eq(ACCOUNT_IDENTIFIER), any());
            assertThat(topics.getAllValues())
                    .containsExactly(ASSESSED_TOPIC, DEAD_LETTER_TOPIC, ASSESSED_TOPIC);
            assertThat(first.isPublished()).isTrue();
            assertThat(middle.isPublished()).isTrue();
            assertThat(middle.getPublishedAt()).isNotNull();
            assertThat(last.isPublished()).isTrue();
            verify(outboxEvents, times(1)).save(first);
            verify(outboxEvents, times(1)).save(middle);
            verify(outboxEvents, times(1)).save(last);
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

            verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
            verify(outboxEvents, times(1)).save(row);
            verify(outboxEvents, times(2)).claimDueRows(any(), any());
        }

        @Test
        @DisplayName("a tick claims no more rows than the configured bound")
        void tickClaimsNoMoreRowsThanTheConfiguredBound() {
            OutboxEventEntity first = flaggedRow();
            OutboxEventEntity second = clearedRow();
            dueRowsFrom(List.of(first, second));
            everySendSucceeds();
            OutboxRelay bounded = new OutboxRelay(outboxEvents, kafkaTemplate, meters,
                    ASSESSED_TOPIC, DEAD_LETTER_TOPIC, transactionTemplate, properties(1));

            bounded.publishPendingEvents();

            verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());

            bounded.publishPendingEvents();

            verify(kafkaTemplate, times(2)).send(anyString(), anyString(), any());
            assertThat(first.isPublished()).isTrue();
            assertThat(second.isPublished()).isTrue();
        }
    }

    @Nested
    @DisplayName("Row mutator surface")
    class RowMutatorSurface {

        @Test
        @DisplayName("the row declares no setter and three named mutators")
        void rowDeclaresNoSetterAndThreeNamedMutators() {
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
            assertThat(mutators).containsExactly("claim", "markPublished", "recordFailure");
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

        private static boolean isBrokerType(Class<?> type) {
            String name = type.getName();
            return name.startsWith("org.springframework.kafka")
                    || name.startsWith("org.apache.kafka");
        }
    }

    /**
     * Holds the finding F-3 outcome: a send the relay issued is waited out inside the pass that
     * issued it, rather than against whatever remains of that pass.
     */
    @Nested
    @DisplayName("Pass budget")
    class PassBudget {

        @Test
        @DisplayName("a send is granted the whole pass budget rather than what is left of it")
        void aSendIsGrantedTheWholePassBudgetRatherThanWhatIsLeftOfIt() {
            long passBudgetMs = 500L;
            long sendMs = 400L;
            OutboxEventEntity first = clearedRow();
            OutboxEventEntity second = thirdClearedRow();
            OutboxRelay relayUnderBudget = new OutboxRelay(outboxEvents, kafkaTemplate, meters,
                    ASSESSED_TOPIC, DEAD_LETTER_TOPIC, transactionTemplate,
                    propertiesWithPassBudget(passBudgetMs));
            dueRows(first, second);
            when(kafkaTemplate.send(eq(ASSESSED_TOPIC), eq(ACCOUNT_IDENTIFIER), any()))
                    .thenAnswer(call -> CompletableFuture.supplyAsync(
                            () -> acknowledgeAfter(sendMs)));

            relayUnderBudget.publishPendingEvents();

            // The first send consumes 400 of the 500 millisecond budget, so the second is issued
            // with 100 left. Waiting only that remainder would abandon a record the producer still
            // holds, which is how one event reached the topic twice.
            assertAll("both sends were waited out",
                    () -> assertTrue(first.isPublished(), "the first row"),
                    () -> assertTrue(second.isPublished(),
                            "the second row's send was abandoned with the pass budget nearly "
                                    + "spent, so its record could still reach the broker while a "
                                    + "later tick published another copy of the same event"),
                    () -> assertThat(second.getAttemptCount()).isZero());
            verify(kafkaTemplate, times(2))
                    .send(eq(ASSESSED_TOPIC), eq(ACCOUNT_IDENTIFIER), any());
            verify(meters, never()).recordPublishFailure();
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

    /** Builds the shipped settings with one chosen relay pass budget in milliseconds. */
    private static FraudProperties propertiesWithPassBudget(long passBudgetMs) {
        FraudProperties shipped = properties(DEFAULT_BATCH_SIZE);
        FraudProperties.Outbox.Relay relay = shipped.outbox().relay();
        return new FraudProperties(shipped.kafka(), shipped.consumer(),
                new FraudProperties.Outbox(new FraudProperties.Outbox.Relay(relay.fixedDelayMs(),
                        relay.batchSize(), relay.instanceId(), relay.claimTimeout(), passBudgetMs),
                        shipped.outbox().publishedRetentionHours()),
                shipped.processedEvent(), shipped.retention(), shipped.fraud());
    }
}
