package com.carddemo.notification.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.notification.NotificationServiceDatabase;
import com.carddemo.notification.TestIdentityPasswords;
import com.carddemo.notification.messaging.DeadLetterMetadata;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves a nested-envelope record reaches its source-specific dead-letter topic before a listener
 * writes a statement row. It also proves retryable recovery starts after the configured attempt
 * budget ends.
 *
 * <p>The JavaScript Object Notation (JSON) document hides required envelope fields under one nested
 * object. The broker reports the schema failure during polling.
 *
 * <p>Metadata fields follow {@code app/cpy/CSMSG02Y.cpy:L21-L28}. Failure handling maps from
 * {@code app/cbl/CBTRN02C.cbl:L707-L727}. Statement context comes from
 * {@code app/cbl/CBSTM03A.CBL}.
 *
 * <p>Decision log: {@code card-platform/docs/decision-log.md}.
 *
 * <p>Flag register: {@code card-platform/docs/business-rule-flags.md}.
 *
 * <p>Traceability: {@code card-platform/docs/traceability-matrix.md}.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "KAFKA_SASL_PASSWORD=a-generated-broker-value-for-this-test",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "management.server.port=${server.port}"
        })
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Dead-letter routing preserves fixed diagnostics and the configured attempt budget")
class DeadLetterRoutingIT {

    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";

    private static final String SERVICE_SCHEMA = "notification_service";
    private static final String BROKER_SECURITY_PROTOCOL = "PLAINTEXT";

    /** Source topic, source-specific dead-letter topic, and the listener group under test. */
    private static final String SOURCE_TOPIC = "transaction.posted";
    private static final String DEAD_LETTER_TOPIC = SOURCE_TOPIC + ".DLT";
    private static final String POSTED_GROUP = "notification-posted";

    /** Configuration keys that define the delivery budget. */
    private static final String MAX_ATTEMPTS_PROPERTY =
            "carddemo.consumer.retry.max-attempts";
    private static final String BACKOFF_PROPERTY = "carddemo.consumer.retry.backoff-ms";

    /** One query over the table a posted-transaction listener writes. */
    private static final String STATEMENT_COUNT_QUERY =
            "SELECT count(*) FROM " + SERVICE_SCHEMA + ".statement_transaction";

    /** Polling bounds for broker assignment, record arrival, and duplicate detection. */
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration FETCH_TIMEOUT = Duration.ofMillis(200);
    private static final Duration SILENCE_WINDOW = Duration.ofSeconds(1);
    private static final Duration CLIENT_CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /** Detects a full sixteen-digit card number on any observed surface. */
    private static final Pattern SIXTEEN_DIGITS = Pattern.compile("[0-9]{16}");

    /** Extracts object member names from the fixed test document. */
    private static final Pattern PROPERTY_NAME =
            Pattern.compile("\"([A-Za-z][A-Za-z0-9]*)\"\\s*:");

    /** Properties declared by the posted-transaction schema. */
    private static final Set<String> SCHEMA_PROPERTIES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
            "transactionId", "accountId", "newBalance", "postedAt", "amount",
            "maskedCardNumber", "extensions");

    /** Widths of the four metadata headers attached by the production recoverer. */
    private static final Map<String, Integer> METADATA_WIDTHS = Map.of(
            KafkaConsumerConfig.HEADER_ABEND_CODE, DeadLetterMetadata.ABEND_CODE_MAX_LENGTH,
            KafkaConsumerConfig.HEADER_CULPRIT, DeadLetterMetadata.CULPRIT_MAX_LENGTH,
            KafkaConsumerConfig.HEADER_REASON, DeadLetterMetadata.REASON_MAX_LENGTH,
            KafkaConsumerConfig.HEADER_MESSAGE, DeadLetterMetadata.MESSAGE_MAX_LENGTH);

    /**
     * An envelope-wrapped document. Its top level omits every required envelope property.
     * The card-number field holds a masked value.
     */
    private static final String INVALID_PAYLOAD = """
            {"envelope":{"eventId":"764c8a16-3818-4e45-915c-12b18fbe09d4",\
            "eventType":"TransactionPosted","schemaVersion":1,\
            "occurredAt":"2026-08-06T12:00:00Z","aggregateId":"00000000007"},\
            "transactionId":"TRN0000000683580","accountId":"00000000007",\
            "newBalance":"697.77","postedAt":"2022-07-19-23.16.01.470000",\
            "amount":"504.77","maskedCardNumber":"************7065"}""";

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link NotificationServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    private static final PostgreSQLContainer POSTGRES = NotificationServiceDatabase.container();

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    private final DataSource dataSource;
    private final Environment environment;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final DefaultErrorHandler errorHandler;

    /**
     * Receives the running application's data source, configuration, listener registry, and error
     * handler.
     */
    @Autowired
    DeadLetterRoutingIT(DataSource dataSource, Environment environment,
            KafkaListenerEndpointRegistry listenerRegistry, DefaultErrorHandler errorHandler) {
        this.dataSource = dataSource;
        this.environment = environment;
        this.listenerRegistry = listenerRegistry;
        this.errorHandler = errorHandler;
    }

    /** Binds the production clients to the disposable database and broker. */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DeadLetterRoutingIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.security.protocol", () -> BROKER_SECURITY_PROTOCOL);
    }

    /** Creates the source and destination topics through a local administration client. */
    @BeforeAll
    static void createTopics() {
        Map<String, Object> settings =
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(settings)) {
            createTopic(admin, SOURCE_TOPIC);
            createTopic(admin, DEAD_LETTER_TOPIC);
        }
    }

    /**
     * Asserts a schema failure reaches one source-specific dead-letter topic entry on its first
     * pass. The listener leaves the statement table empty.
     */
    @Test
    @DisplayName("a nested envelope reaches one dead-letter record on its first pass")
    void schemaViolationRoutesOnFirstPass() {
        awaitPostedListenerAssignment();
        byte[] refused = bytes(INVALID_PAYLOAD);

        try (KafkaConsumer<String, byte[]> reader = deadLetterReader()) {
            awaitReaderAssignment(reader);
            RecordMetadata sent = publish(refused);
            Instant acknowledged = Instant.now();
            String expectedKey = coordinates(sent);
            ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(reader, expectedKey,
                    ARRIVAL_TIMEOUT, "the dead-letter record of a nested envelope");
            Duration elapsed = Duration.between(acknowledged, Instant.now());
            Duration budget = configuredRetryBudget();

            assertAll("the first-pass route",
                    () -> assertEquals(DEAD_LETTER_TOPIC, deadLetter.topic(),
                            () -> "the record landed on " + deadLetter.topic()),
                    () -> assertEquals(expectedKey, deadLetter.key(),
                            "the diagnostic record key"),
                    () -> assertFalse(Arrays.equals(refused, deadLetter.value()),
                            "the refused document reached the destination"),
                    () -> assertEquals(DeadLetterMetadata.RECORD_LENGTH,
                            deadLetter.value().length, "the diagnostic record width"),
                    () -> assertEquals(SerializationException.class.getSimpleName(),
                            trimmedHeader(deadLetter, KafkaConsumerConfig.HEADER_REASON),
                            "the schema failure type"),
                    () -> assertEquals(0L, statementRowCount(), "statement rows"),
                    () -> assertTrue(elapsed.compareTo(budget) < 0,
                            () -> "first-pass recovery took " + elapsed.toMillis()
                                    + " milliseconds; the ceiling comes from "
                                    + MAX_ATTEMPTS_PROPERTY + " and " + BACKOFF_PROPERTY));

            assertNoRecord(reader, record -> expectedKey.equals(record.key()), SILENCE_WINDOW,
                    "a duplicate dead-letter record");
        }
    }

    /**
     * Asserts the four metadata headers keep their copybook widths and form the diagnostic record.
     */
    @Test
    @DisplayName("the four metadata headers retain their declared widths")
    void metadataHeadersKeepTheirDeclaredWidths() {
        ConsumerRecord<String, byte[]> deadLetter = routeInvalidPayload();

        assertMetadata(deadLetter, SerializationException.class.getSimpleName());
    }

    /**
     * Asserts no Primary Account Number reaches a header, log line, refused document, or diagnostic
     * record. Every document member except the wrapper is declared by the event schema.
     */
    @Test
    @DisplayName("the failure route exposes no sixteen-digit card number")
    void failureRouteExposesNoCardNumber(CapturedOutput output) {
        ConsumerRecord<String, byte[]> deadLetter = routeInvalidPayload();
        Set<String> documentProperties = propertiesOf(INVALID_PAYLOAD);
        Set<String> undeclared = new LinkedHashSet<>(documentProperties);
        undeclared.removeAll(SCHEMA_PROPERTIES);

        for (Header header : deadLetter.headers()) {
            assertNoSixteenDigits(header.value() == null
                    ? "" : new String(header.value(), StandardCharsets.UTF_8),
                    "header " + header.key());
        }

        assertAll("the observed failure surfaces",
                () -> assertNoSixteenDigits(output.getAll(), "captured output"),
                () -> assertNoSixteenDigits(INVALID_PAYLOAD, "the refused document"),
                () -> assertNoSixteenDigits(
                        new String(deadLetter.value(), StandardCharsets.UTF_8),
                        "the diagnostic record"),
                () -> assertEquals(Set.of("envelope"), undeclared,
                        "document members absent from the event schema"),
                () -> assertTrue(documentProperties.containsAll(
                                Set.of("eventId", "eventType", "schemaVersion", "occurredAt",
                                        "aggregateId", "maskedCardNumber")),
                        "the nested envelope and masked-card members"));
    }

    /**
     * Asserts a retryable listener failure publishes nothing before the configured call budget
     * ends. The final call publishes one record with the fixed metadata contract.
     */
    @Test
    @DisplayName("a retryable failure reaches recovery after the configured attempt budget")
    void retryableFailureWaitsForConfiguredAttemptBudget() {
        int attempts = configuredAttempts();
        long backoffMs = configuredBackoffMs();
        assertTrue(attempts > 1, "the retry proof needs a delivery and at least one retry");

        ConsumerRecord<String, byte[]> failing =
                new ConsumerRecord<>(SOURCE_TOPIC, 17, 7919L, "retry-route",
                        bytes("retryable listener input"));
        ListenerExecutionFailedException failure = new ListenerExecutionFailedException(
                "listener failed", new IllegalStateException("temporary store failure"));
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = manualImmediateContainer();
        String expectedKey = coordinates(failing);
        Duration expectedWait =
                Duration.ofMillis(Math.multiplyExact(backoffMs, attempts - 1L));

        try (KafkaConsumer<String, byte[]> reader = deadLetterReader()) {
            awaitReaderAssignment(reader);
            Instant started = Instant.now();
            try {
                for (int call = 1; call < attempts; call++) {
                    int currentCall = call;
                    assertFalse(errorHandler.handleOne(failure, failing, consumer, container),
                            () -> "call " + currentCall
                                    + " recovered before the configured budget ended");
                    assertNoRecord(reader, record -> expectedKey.equals(record.key()),
                            SILENCE_WINDOW, "recovery before call " + attempts);
                }

                assertTrue(errorHandler.handleOne(failure, failing, consumer, container),
                        "the final configured call did not recover the record");
                ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(reader, expectedKey,
                        ARRIVAL_TIMEOUT, "the dead-letter record after the attempt budget");
                Duration elapsed = Duration.between(started, Instant.now());

                assertAll("the spent retry budget",
                        () -> assertEquals(DEAD_LETTER_TOPIC, deadLetter.topic(),
                                "the retryable failure destination"),
                        () -> assertEquals(expectedKey, deadLetter.key(),
                                "the retryable failure key"),
                        () -> assertTrue(elapsed.compareTo(expectedWait) >= 0,
                                () -> attempts + " calls with a " + backoffMs
                                        + " millisecond interval took " + elapsed.toMillis()
                                        + " milliseconds"),
                        () -> assertMetadata(deadLetter,
                                IllegalStateException.class.getSimpleName()));
                assertNoRecord(reader, record -> expectedKey.equals(record.key()), SILENCE_WINDOW,
                        "a duplicate retryable dead-letter record");
            } finally {
                errorHandler.clearThreadState();
            }
        }
    }

    /** Returns the database address with the service schema on its search path. */
    private static String jdbcUrlOnServiceSchema() {
        return NotificationServiceDatabase.urlFor(DeadLetterRoutingIT.class);
    }

    /** Creates one single-partition topic and accepts an existing topic as complete. */
    private static void createTopic(Admin admin, String topic) {
        try {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all()
                    .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Creating topic " + topic + " was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            if (!(failed.getCause() instanceof TopicExistsException)) {
                throw new AssertionError("Creating topic " + topic + " failed.", failed);
            }
        } catch (TimeoutException timedOut) {
            throw new AssertionError("Creating topic " + topic + " did not finish.", timedOut);
        }
    }

    /** Publishes the refused bytes and returns their broker coordinates. */
    private static RecordMetadata publish(byte[] payload) {
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerSettings(),
                new StringSerializer(), new ByteArraySerializer());
        try {
            return producer.send(new ProducerRecord<>(SOURCE_TOPIC, "00000000007", payload))
                    .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Publishing the refused document was interrupted.",
                    interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("Publishing the refused document failed.", failed);
        } catch (TimeoutException timedOut) {
            throw new AssertionError("Publishing the refused document did not finish.", timedOut);
        } finally {
            producer.close(CLIENT_CLOSE_TIMEOUT);
        }
    }

    /** Returns settings for a local byte-valued producer. */
    private static Map<String, Object> producerSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ProducerConfig.ACKS_CONFIG, "all");
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE);
        return settings;
    }

    /** Opens a local byte-valued reader in a unique group. */
    private static KafkaConsumer<String, byte[]> deadLetterReader() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, "dead-letter-routing-" + UUID.randomUUID());
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE);
        KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(settings,
                new StringDeserializer(), new ByteArrayDeserializer());
        reader.subscribe(List.of(DEAD_LETTER_TOPIC));
        return reader;
    }

    /** Waits for the posted-transaction listener to hold its topic partition. */
    private void awaitPostedListenerAssignment() {
        List<MessageListenerContainer> matched = listenerRegistry.getListenerContainers().stream()
                .filter(container -> POSTED_GROUP.equals(container.getGroupId()))
                .toList();
        assertEquals(1, matched.size(), "posted-transaction listener containers");
        MessageListenerContainer container = matched.getFirst();

        Awaitility.await("the posted-transaction listener assignment")
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    Collection<TopicPartition> assigned = container.getAssignedPartitions();
                    return container.isRunning() && assigned != null && !assigned.isEmpty();
                });
    }

    /** Completes the local reader's group assignment before a record is published. */
    private static void awaitReaderAssignment(KafkaConsumer<String, byte[]> reader) {
        Awaitility.await("the dead-letter reader assignment")
                .pollInSameThread()
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    reader.poll(FETCH_TIMEOUT);
                    return !reader.assignment().isEmpty();
                });
    }

    /** Routes one invalid document and returns its matching diagnostic record. */
    private ConsumerRecord<String, byte[]> routeInvalidPayload() {
        awaitPostedListenerAssignment();
        try (KafkaConsumer<String, byte[]> reader = deadLetterReader()) {
            awaitReaderAssignment(reader);
            RecordMetadata sent = publish(bytes(INVALID_PAYLOAD));
            return awaitDeadLetter(reader, coordinates(sent), ARRIVAL_TIMEOUT,
                    "the diagnostic record of a nested envelope");
        }
    }

    /** Waits for one record carrying the supplied diagnostic key. */
    private static ConsumerRecord<String, byte[]> awaitDeadLetter(
            KafkaConsumer<String, byte[]> reader, String key, Duration timeout, String subject) {
        List<ConsumerRecord<String, byte[]>> matched = new ArrayList<>();
        Awaitility.await(subject)
                .pollInSameThread()
                .atMost(timeout)
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    drain(reader, record -> key.equals(record.key()), matched);
                    return !matched.isEmpty();
                });
        assertEquals(1, matched.size(), subject);
        return matched.getFirst();
    }

    /** Asserts no matching record appears throughout the supplied window. */
    private static void assertNoRecord(KafkaConsumer<String, byte[]> reader,
            Predicate<ConsumerRecord<String, byte[]>> wanted, Duration window, String subject) {
        List<ConsumerRecord<String, byte[]>> matched = new ArrayList<>();
        Awaitility.await(subject)
                .pollInSameThread()
                .during(window)
                .atMost(window.plus(ARRIVAL_TIMEOUT))
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    drain(reader, wanted, matched);
                    return matched.isEmpty();
                });
    }

    /** Fetches once and keeps every matching record. */
    private static void drain(KafkaConsumer<String, byte[]> reader,
            Predicate<ConsumerRecord<String, byte[]>> wanted,
            List<ConsumerRecord<String, byte[]>> matched) {
        for (ConsumerRecord<String, byte[]> record : reader.poll(FETCH_TIMEOUT)) {
            if (wanted.test(record)) {
                matched.add(record);
            }
        }
    }

    /** Asserts the four fixed-width metadata fields on one diagnostic record. */
    private static void assertMetadata(ConsumerRecord<String, byte[]> record,
            String expectedFailureType) {
        Map<String, String> headers = metadataHeaders(record);
        String fixedRecord = headers.get(KafkaConsumerConfig.HEADER_ABEND_CODE)
                + headers.get(KafkaConsumerConfig.HEADER_CULPRIT)
                + headers.get(KafkaConsumerConfig.HEADER_REASON)
                + headers.get(KafkaConsumerConfig.HEADER_MESSAGE);

        assertAll("the fixed-width metadata",
                () -> assertEquals(METADATA_WIDTHS.size(), metadataHeaderCount(record),
                        "metadata header count"),
                () -> assertEquals(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH,
                        headers.get(KafkaConsumerConfig.HEADER_ABEND_CODE).length(),
                        "failure-code width"),
                () -> assertEquals(DeadLetterMetadata.CULPRIT_MAX_LENGTH,
                        headers.get(KafkaConsumerConfig.HEADER_CULPRIT).length(),
                        "culprit width"),
                () -> assertEquals(DeadLetterMetadata.REASON_MAX_LENGTH,
                        headers.get(KafkaConsumerConfig.HEADER_REASON).length(),
                        "failure-type width"),
                () -> assertEquals(DeadLetterMetadata.MESSAGE_MAX_LENGTH,
                        headers.get(KafkaConsumerConfig.HEADER_MESSAGE).length(),
                        "safe-message width"),
                () -> assertTrue(headers.values().stream().noneMatch(String::isEmpty),
                        "an empty metadata header"),
                () -> assertEquals(DeadLetterMetadata.RECORD_LENGTH,
                        headers.values().stream().mapToInt(String::length).sum(),
                        "the total metadata width"),
                () -> assertEquals(KafkaConsumerConfig.ABEND_CODE,
                        headers.get(KafkaConsumerConfig.HEADER_ABEND_CODE).stripTrailing(),
                        "the failure code"),
                () -> assertEquals(KafkaConsumerConfig.SERVICE_CULPRIT,
                        headers.get(KafkaConsumerConfig.HEADER_CULPRIT).stripTrailing(),
                        "the culprit"),
                () -> assertEquals(expectedFailureType,
                        headers.get(KafkaConsumerConfig.HEADER_REASON).stripTrailing(),
                        "the failure type"),
                () -> assertEquals(KafkaConsumerConfig.SAFE_FAILURE_MESSAGE,
                        headers.get(KafkaConsumerConfig.HEADER_MESSAGE).stripTrailing(),
                        "the safe message"),
                () -> assertArrayEquals(fixedRecord.getBytes(StandardCharsets.UTF_8),
                        record.value(), "the diagnostic record"));
    }

    /** Reads each metadata header and asserts it occurs once with a value. */
    private static Map<String, String> metadataHeaders(ConsumerRecord<String, byte[]> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : METADATA_WIDTHS.keySet()) {
            List<Header> matches = new ArrayList<>();
            record.headers().headers(name).forEach(matches::add);
            assertEquals(1, matches.size(), () -> "header occurrences for " + name);
            Header header = matches.getFirst();
            assertNotNull(header.value(), () -> "header value for " + name);
            headers.put(name, new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    /** Counts headers belonging to the four-field metadata set. */
    private static long metadataHeaderCount(ConsumerRecord<String, byte[]> record) {
        long count = 0L;
        for (Header header : record.headers()) {
            if (METADATA_WIDTHS.containsKey(header.key())) {
                count++;
            }
        }
        return count;
    }

    /** Reads one padded header with its trailing spaces removed. */
    private static String trimmedHeader(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertNotNull(header, () -> "missing header " + name);
        assertNotNull(header.value(), () -> "null header " + name);
        return new String(header.value(), StandardCharsets.UTF_8).stripTrailing();
    }

    /** Returns the configured total time ceiling for first-pass recovery. */
    private Duration configuredRetryBudget() {
        return Duration.ofMillis(Math.multiplyExact((long) configuredAttempts(),
                configuredBackoffMs()));
    }

    /** Returns the configured delivery count. */
    private int configuredAttempts() {
        return environment.getRequiredProperty(MAX_ATTEMPTS_PROPERTY, Integer.class);
    }

    /** Returns the configured wait between delivery calls. */
    private long configuredBackoffMs() {
        return environment.getRequiredProperty(BACKOFF_PROPERTY, Long.class);
    }

    /** Supplies manual-immediate acknowledgement settings to the real handler. */
    private static MessageListenerContainer manualImmediateContainer() {
        ContainerProperties properties = new ContainerProperties(SOURCE_TOPIC);
        properties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(properties);
        return container;
    }

    /** Counts rows in the migrated statement table. */
    private long statementRowCount() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(STATEMENT_COUNT_QUERY)) {
            assertTrue(rows.next(), "the statement count query returned no row");
            return rows.getLong(1);
        } catch (SQLException failed) {
            throw new AssertionError("Counting statement rows failed.", failed);
        }
    }

    /** Returns broker coordinates in the form used by the production recoverer. */
    private static String coordinates(RecordMetadata metadata) {
        return metadata.topic() + "-" + metadata.partition() + "-" + metadata.offset();
    }

    /** Returns broker coordinates for a synthetic consumer record. */
    private static String coordinates(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "-" + record.offset();
    }

    /** Extracts every object member name from one document. */
    private static Set<String> propertiesOf(String document) {
        Matcher matcher = PROPERTY_NAME.matcher(document);
        Set<String> properties = new LinkedHashSet<>();
        while (matcher.find()) {
            properties.add(matcher.group(1));
        }
        return Set.copyOf(properties);
    }

    /** Asserts one text carries no sixteen-digit run. */
    private static void assertNoSixteenDigits(String text, String subject) {
        assertFalse(SIXTEEN_DIGITS.matcher(text).find(),
                () -> subject + " carries sixteen consecutive digits");
    }

    /** Encodes one text for a byte-valued Kafka record. */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
