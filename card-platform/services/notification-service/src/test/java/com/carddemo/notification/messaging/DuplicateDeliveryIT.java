package com.carddemo.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionPosted;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.entity.ProcessedEventEntity;
import com.carddemo.notification.repository.ProcessedEventRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import org.awaitility.Awaitility;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.slf4j.LoggerFactory;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that a second delivery of one event identifier changes no row, for both consumers of this
 * service. A delivery no consumer can apply reaches a dead-letter topic once its attempts run out.
 *
 * <p>One PostgreSQL container and one Kafka Raft (KRaft) broker container carry the run, and the
 * Spring context is the production one. Every valid event travels through the production
 * serializer, so a payload a listener reads is one the JavaScript Object Notation (JSON) schema of
 * its event type accepted.
 *
 * <p>The read model counted over here descends from {@code app/cpy/COSTM01.CPY:L22-L35}, and its
 * key order comes from the sort at {@code app/jcl/CREASTMT.JCL:L53}. The duplicate-delivery
 * guard is additive: {@code app/cbl/CBTRN02C.cbl:L562-L579} writes with no guard and ends the
 * run on the duplicate key a replayed feed produces. Masking the Primary Account Number, holding
 * one local transaction over the row and the marker, and filling the metadata of
 * {@code app/cpy/CSMSG02Y.cpy:L21-L28} are additive on the same measure. The four {@code COPY}
 * statements at {@code app/cbl/CBSTM03A.CBL:L51-L57} do not name that metadata copybook.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@Testcontainers
@SpringBootTest(properties = {
        "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
        "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
        "USER_PASSWORD_HASH={noop}not-a-real-user-password",
        "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password",
        "carddemo.history.statement-retention-days=200000",
        "carddemo.history.log-retention-days=200000",
        "carddemo.processed-event.marker-retention-hours=4800000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("A repeat delivery of one event identifier writes nothing a first delivery did not")
class DuplicateDeliveryIT {

    /**
     * The two image tags, both pinned by Agent Action Plan section 0.5.1. The broker tag matches
     * the client library version this module resolves, so no client meets a broker of another line.
     */
    private static final String POSTGRES_IMAGE = "postgres:18.4";
    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";

    /**
     * The database name, login and password of the disposable container, one value for all three;
     * the protocol its broker listener accepts; and the schema the shipped configuration migrates.
     */
    private static final String CONTAINER_CREDENTIAL = "carddemo";
    private static final String BROKER_SECURITY_PROTOCOL = "PLAINTEXT";
    private static final String SERVICE_SCHEMA = "notification_service";

    /**
     * The two topics these proofs publish to, the suffix that addresses the dead-letter topic of
     * each, and the two dead-letter topics that follow from appending it.
     */
    private static final String POSTED_TOPIC = "transaction.posted";
    private static final String ASSESSED_TOPIC = "fraud.assessed";
    private static final String DEAD_LETTER_SUFFIX = ".DLT";
    private static final String POSTED_DEAD_LETTER_TOPIC = POSTED_TOPIC + DEAD_LETTER_SUFFIX;
    private static final String ASSESSED_DEAD_LETTER_TOPIC = ASSESSED_TOPIC + DEAD_LETTER_SUFFIX;

    /**
     * The topic a record names when its source topic went unreported, which no proof here reaches,
     * and the two properties naming that topic and the suffix above.
     */
    private static final String FALLBACK_DEAD_LETTER_TOPIC = "carddemo.dead-letter";
    private static final String FALLBACK_TOPIC_PROPERTY = "carddemo.kafka.topics.dead-letter";
    private static final String SUFFIX_PROPERTY = "carddemo.kafka.topics.dead-letter-suffix";

    /** The two consumer groups this service reads under, one per topic above. */
    private static final String POSTED_GROUP = "notification-posted";
    private static final String ASSESSED_GROUP = "notification-fraud";

    /** Bean name the annotation-driven listener infrastructure resolves a container factory by. */
    private static final String CONTAINER_FACTORY_BEAN = "kafkaListenerContainerFactory";

    /**
     * The two accounts these proofs name. The seed migration carries a cardholder projection row
     * for the first and none for the second. A render for an account with no row raises the type
     * named below, which one dead-letter header carries as text.
     */
    private static final String ACCOUNT_ID = "00000000007";
    private static final String ACCOUNT_WITHOUT_PROJECTION = "00000000099";
    private static final String MISSING_PROJECTION_TYPE = "CardholderContextMissingException";

    /**
     * The two card identities keying the read model, sixty-four lower-case hexadecimal characters
     * each, so one proof never counts rows another wrote.
     */
    private static final String CARD_TOKEN =
            "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    private static final String OTHER_CARD_TOKEN =
            "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0";

    /**
     * The transaction identifier and the masked card number, sixteen characters each from
     * {@code TRAN-ID PIC X(16)} and {@code TRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L5} and {@code app/cpy/CVTRA05Y.cpy:L15}.
     */
    private static final String TRANSACTION_ID = "0000000000683580";
    private static final String MASKED_CARD_NUMBER = "************7065";

    /**
     * The two monetary values, as decimal strings and never as JSON numbers. The amount takes nine
     * integer digits at most and the balance ten, and the two patterns are not interchangeable.
     */
    private static final String AMOUNT = "504.77";
    private static final String NEW_BALANCE = "697.77";

    /**
     * The two twenty-six character timestamps, neither of them an instant in the form the envelope
     * carries. The posting form runs {@code YYYY-MM-DD-HH.MM.SS.NN0000} and the origin form runs
     * {@code YYYY-MM-DD HH:MM:SS.ffffff}, and the two differ at three positions.
     */
    private static final String POSTED_AT = "2022-07-19-23.16.01.470000";
    private static final String ORIGIN_TIMESTAMP = "2022-07-19 23:16:01.470000";

    /**
     * The eight remaining payload values, each at the width its field declares at
     * {@code app/cpy/COSTM01.CPY:L25-L33}. The list runs over the type and category codes, the
     * capture channel, the description an alert prints, and the four merchant values.
     */
    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0001";
    private static final String CAPTURE_SOURCE = "POS TERM";
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";
    private static final String MERCHANT_ID = "800000000";
    private static final String MERCHANT_NAME = "Abshire-Lowe";
    private static final String MERCHANT_CITY = "North Enoshaven";
    private static final String MERCHANT_ZIP = "72112";

    /** Score a flagged assessment carries, a JSON integer bounded 0 through 100. */
    private static final int RISK_SCORE = 82;

    /** Rules a flagged assessment names, each an identifier the schema enumerates. */
    private static final List<String> TRIGGERED_RULES = List.of("VELOCITY", "AMOUNT_ANOMALY");

    /** Contract version carrying the whole posted transaction record. */
    private static final int DETAIL_SCHEMA_VERSION = TransactionPosted.DETAIL_SCHEMA_VERSION;

    /** Widths the read-model text columns declare, from {@code app/cpy/COSTM01.CPY:L22-L35}. */
    private static final Map<String, Integer> TEXT_COLUMN_WIDTHS = Map.ofEntries(
            Map.entry("card_token", 64), Map.entry("transaction_id", 16),
            Map.entry("masked_card_number", 16), Map.entry("type_code", 2),
            Map.entry("category_code", 4), Map.entry("source", 10),
            Map.entry("description", 100), Map.entry("merchant_id", 9),
            Map.entry("merchant_name", 50), Map.entry("merchant_city", 50),
            Map.entry("merchant_zip", 10), Map.entry("origin_timestamp", 26),
            Map.entry("processing_timestamp", 26));

    /** Column count of {@code statement_transaction}: one per L22 to L35 field, plus the token. */
    private static final int READ_MODEL_COLUMN_COUNT = 14;

    /** The columns {@code processed_event} declares, in the order the migration creates them. */
    private static final List<String> MARKER_COLUMNS =
            List.of("event_id", "processed_at", "consumed_topic");

    /** Columns a group-aware or payload-carrying marker table declares, and none of them exists. */
    private static final Set<String> REFUSED_MARKER_COLUMNS = Set.of("consumer_group", "event_type",
            "aggregate_id", "payload", "attempt_count", "last_error", "status", "created_at");

    /**
     * The four headers one dead letter carries, one per component of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L28}, at widths of four, eight, fifty and seventy-two.
     */
    private static final String HEADER_ABEND_CODE = "carddemo-dl-code";
    private static final String HEADER_CULPRIT = "carddemo-dl-culprit";
    private static final String HEADER_REASON = "carddemo-dl-reason";
    private static final String HEADER_MESSAGE = "carddemo-dl-message";

    /** The two fixed values of those components, and the detail carried in place of a message. */
    private static final String ABEND_CODE = "0999";
    private static final String SERVICE_CULPRIT = "NOTIFSVC";
    private static final String SAFE_FAILURE_MESSAGE =
            "record rejected; inspect broker coordinates";

    /**
     * Widths the four metadata components declare at {@code app/cpy/CSMSG02Y.cpy:L21-L28}, one per
     * header: a value shorter than its width reaches a header padded on the right.
     */
    private static final Map<String, Integer> METADATA_HEADER_WIDTHS = Map.of(
            HEADER_ABEND_CODE, DeadLetterMetadata.ABEND_CODE_MAX_LENGTH,
            HEADER_CULPRIT, DeadLetterMetadata.CULPRIT_MAX_LENGTH,
            HEADER_REASON, DeadLetterMetadata.REASON_MAX_LENGTH,
            HEADER_MESSAGE, DeadLetterMetadata.MESSAGE_MAX_LENGTH);

    /** Header names a dead letter of this service may carry, and it carries no other. */
    private static final Set<String> ALLOWED_DEAD_LETTER_HEADERS = Set.of(
            HEADER_ABEND_CODE, HEADER_CULPRIT, HEADER_REASON, HEADER_MESSAGE,
            KafkaHeaders.DLT_EXCEPTION_MESSAGE, KafkaHeaders.DLT_ORIGINAL_TOPIC,
            KafkaHeaders.DLT_ORIGINAL_PARTITION, KafkaHeaders.DLT_ORIGINAL_OFFSET,
            KafkaHeaders.DLT_ORIGINAL_TIMESTAMP);

    /** A run of twelve or more decimal digits, which is what a leaked card number looks like. */
    private static final Pattern DIGIT_RUN = Pattern.compile("[0-9]{12,}");

    /** Deliveries of one record, counting the first, from {@code carddemo.consumer.retry}. */
    private static final long EXPECTED_DELIVERIES = 3L;

    /** Wait between two deliveries of one record, and the wait every retry adds up to. */
    private static final Duration DELIVERY_WAIT = Duration.ofMillis(1000);
    private static final Duration RETRY_WAIT_TOTAL =
            DELIVERY_WAIT.multipliedBy(EXPECTED_DELIVERIES - 1L);

    /**
     * The four meters these proofs read, each registered by {@code config/ObservabilityConfig}. One
     * counts events a listener took, one counts failures per attempt, one counts records whose
     * attempts ran out, and one counts deliveries a marker suppressed.
     */
    private static final String EVENTS_CONSUMED_METER = "carddemo.notification.events.consumed";
    private static final String FAILURES_METER = "carddemo.notification.failures";
    private static final String DEAD_LETTERED_METER = "carddemo.notification.records.dead.lettered";
    private static final String DUPLICATES_SKIPPED_METER =
            "carddemo.notification.duplicates.skipped";

    /** The two dimensions those meters carry. */
    private static final String EVENT_TYPE_TAG = "event.type";
    private static final String FAILURE_KIND_TAG = "failure.kind";

    /**
     * The five bounds every wait here runs under. Two are timeouts, one for an arrival and one for
     * every delivery attempt of one record. The other three set the interval between two
     * evaluations, the timeout of one broker fetch, and the window an unchanging state is watched
     * over.
     */
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration SPENT_ATTEMPTS_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration FETCH_TIMEOUT = Duration.ofMillis(200);
    private static final Duration SILENCE_WINDOW = Duration.ofSeconds(3);

    /**
     * The four statements over the read model. One counts the rows of one card identity and one
     * totals their amounts as an alert does. The last two read every column of one row, then its
     * amount alone.
     */
    private static final String ROW_COUNT_SQL = "SELECT count(*) FROM " + SERVICE_SCHEMA
            + ".statement_transaction WHERE card_token = ?";
    private static final String CARD_TOTAL_SQL = "SELECT coalesce(sum(amount), 0) FROM "
            + SERVICE_SCHEMA + ".statement_transaction WHERE card_token = ?";
    private static final String ROW_SQL = "SELECT * FROM " + SERVICE_SCHEMA
            + ".statement_transaction WHERE card_token = ? AND transaction_id = ?";
    private static final String AMOUNT_SQL = "SELECT amount FROM " + SERVICE_SCHEMA
            + ".statement_transaction WHERE card_token = ? AND transaction_id = ?";

    /**
     * The four statements over the marker table, the attempt log and the whole read model: two read
     * one marker, one counts every attempt row, and one counts every read-model row.
     */
    private static final String MARKER_COUNT_SQL =
            "SELECT count(*) FROM " + SERVICE_SCHEMA + ".processed_event WHERE event_id = ?";
    private static final String MARKER_TOPIC_SQL =
            "SELECT consumed_topic FROM " + SERVICE_SCHEMA + ".processed_event WHERE event_id = ?";
    private static final String ATTEMPT_COUNT_SQL =
            "SELECT count(*) FROM " + SERVICE_SCHEMA + ".notification_log";
    private static final String EVERY_ROW_COUNT_SQL =
            "SELECT count(*) FROM " + SERVICE_SCHEMA + ".statement_transaction";

    /** Names the columns of one table in the migrated catalogue. */
    private static final String COLUMN_NAMES_SQL = "SELECT column_name FROM"
            + " information_schema.columns WHERE table_schema = ? AND table_name = ?"
            + " ORDER BY ordinal_position";

    /** Statements emptying the three tables a delivery writes, leaving the projection seeded. */
    private static final List<String> EMPTY_WRITTEN_TABLES = List.of(
            "DELETE FROM " + SERVICE_SCHEMA + ".statement_transaction",
            "DELETE FROM " + SERVICE_SCHEMA + ".notification_log",
            "DELETE FROM " + SERVICE_SCHEMA + ".processed_event");

    /**
     * A payload carrying its envelope under a nested object. Every schema of this platform refuses
     * that shape twice over. {@code envelope} is a property none declares, and the five envelope
     * properties it hides are five the required list names.
     */
    private static final String NESTED_ENVELOPE_PAYLOAD = """
            {"envelope":{"eventId":"%s","eventType":"TransactionPosted","schemaVersion":%d,\
            "occurredAt":"%s","aggregateId":"%s"},"transactionId":"%s","accountId":"%s",\
            "newBalance":"%s","postedAt":"%s","amount":"%s","maskedCardNumber":"%s"}""";

    /** A flat payload whose card number carries every digit, which the mask pattern refuses. */
    private static final String UNMASKED_CARD_PAYLOAD = """
            {"eventId":"%s","eventType":"TransactionPosted","schemaVersion":1,\
            "occurredAt":"%s","aggregateId":"%s","transactionId":"%s","accountId":"%s",\
            "newBalance":"%s","postedAt":"%s","amount":"%s","maskedCardNumber":"%s"}""";

    /** The card number of that payload, the one value in this class that is not masked. */
    private static final String UNMASKED_CARD_NUMBER = "4859452612877065";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(CONTAINER_CREDENTIAL)
            .withUsername(CONTAINER_CREDENTIAL)
            .withPassword(CONTAINER_CREDENTIAL);

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    /** Producer factory behind the template below, closed once the class has run. */
    private static DefaultKafkaProducerFactory<String, Object> eventProducers;

    /** Publishes one validated event through the production serializer. */
    private static KafkaTemplate<String, Object> events;

    private final ApplicationContext context;
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final ProcessedEventRepository markers;
    private final MeterRegistry meters;

    /**
     * Takes the running context and the five beans every proof below reads.
     *
     * @param context the context {@code NotificationApplication} configures
     */
    DuplicateDeliveryIT(ApplicationContext context) {
        this.context = context;
        this.environment = context.getEnvironment();
        this.jdbc = context.getBean(JdbcTemplate.class);
        this.listenerRegistry = context.getBean(KafkaListenerEndpointRegistry.class);
        this.markers = context.getBean(ProcessedEventRepository.class);
        this.meters = context.getBean(MeterRegistry.class);
    }

    /**
     * Points the datasource and the broker client at the two containers.
     *
     * @param registry the registry the test context reads these values from
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DuplicateDeliveryIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.security.protocol", () -> BROKER_SECURITY_PROTOCOL);
    }

    /** Returns the container connection string with the service schema on its search path. */
    private static String jdbcUrlOnServiceSchema() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SERVICE_SCHEMA;
    }

    /** Opens the template every valid publish travels through. */
    @BeforeAll
    static void openEventTemplate() {
        eventProducers = new DefaultKafkaProducerFactory<>(producerSettings(),
                new StringSerializer(), new JsonSchemaValidatingSerializer<>());
        events = new KafkaTemplate<>(eventProducers);
    }

    /** Closes that template's producers. */
    @AfterAll
    static void closeEventTemplate() {
        eventProducers.destroy();
    }

    /** Empties the three tables a delivery writes, so each proof starts from an empty count. */
    @BeforeEach
    void emptyWrittenTables() {
        EMPTY_WRITTEN_TABLES.forEach(jdbc::update);
    }

    /**
     * Asserts the bean named {@value #CONTAINER_FACTORY_BEAN} carries both listeners under distinct
     * groups, each acknowledging by hand and reporting a delivery attempt.
     */
    @Test
    @DisplayName("the production container factory carries both listeners under distinct groups")
    void productionContainerFactoryCarriesBothListeners() {
        assertTrue(context.containsBean(CONTAINER_FACTORY_BEAN),
                () -> "the context declares no bean named " + CONTAINER_FACTORY_BEAN);
        awaitAssignment(POSTED_GROUP);
        awaitAssignment(ASSESSED_GROUP);

        ContainerProperties posted = containerOfGroup(POSTED_GROUP).getContainerProperties();
        ContainerProperties assessed = containerOfGroup(ASSESSED_GROUP).getContainerProperties();

        assertAll("the listener binding",
                () -> assertNotEquals(POSTED_GROUP, ASSESSED_GROUP, "the two groups share a name"),
                () -> assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE,
                        posted.getAckMode(),
                        () -> POSTED_GROUP + " acknowledges under " + posted.getAckMode()),
                () -> assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE,
                        assessed.getAckMode(),
                        () -> ASSESSED_GROUP + " acknowledges under " + assessed.getAckMode()),
                () -> assertTrue(posted.isDeliveryAttemptHeader(),
                        POSTED_GROUP + " reports no delivery attempt"),
                () -> assertTrue(assessed.isDeliveryAttemptHeader(),
                        ASSESSED_GROUP + " reports no delivery attempt"),
                () -> assertEquals(POSTED_TOPIC, onlyTopicOf(POSTED_GROUP), "the posted binding"),
                () -> assertEquals(ASSESSED_TOPIC, onlyTopicOf(ASSESSED_GROUP),
                        "the assessment binding"));
    }

    /**
     * Asserts a repeat posted-balance delivery leaves one row, one marker, one attempt row and the
     * same per-card total. A duplicate is no failure, so no dead letter and no error line follow.
     */
    @Test
    @DisplayName("a repeat posted-balance delivery adds no row, no amount and no marker")
    void repeatPostedDeliveryChangesNothing() {
        awaitAssignment(POSTED_GROUP);
        UUID eventId = UUID.randomUUID();
        TransactionPosted event = postedEvent(eventId, CARD_TOKEN, TRANSACTION_ID);
        Logger consumerLog = (Logger) LoggerFactory.getLogger(TransactionPostedConsumer.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();

        try (KafkaConsumer<String, byte[]> deadLetters = deadLetterReader()) {
            RecordMetadata first = publish(POSTED_TOPIC, ACCOUNT_ID, event);
            awaitCount(ROW_COUNT_SQL, 1L, "the read-model row of the card", CARD_TOKEN);
            awaitCount(MARKER_COUNT_SQL, 1L, "the marker of event " + eventId, eventId);
            awaitCount(ATTEMPT_COUNT_SQL, 1L, "the delivery-attempt row");

            BigDecimal total = cardTotal(CARD_TOKEN);
            long suppressed = counterValue(DUPLICATES_SKIPPED_METER, null, null);
            long failures = counterValue(FAILURES_METER, FAILURE_KIND_TAG,
                    NotificationMetrics.FAILURE_RENDERING);
            lines.start();
            consumerLog.addAppender(lines);
            try {
                RecordMetadata repeat = publish(POSTED_TOPIC, ACCOUNT_ID, event);
                awaitCounter(DUPLICATES_SKIPPED_METER, null, null, suppressed + 1L,
                        "the delivery the marker suppressed");
                assertHoldsThroughout("one row, one marker and one attempt row",
                        () -> count(ROW_COUNT_SQL, CARD_TOKEN) == 1L
                                && count(MARKER_COUNT_SQL, eventId) == 1L
                                && count(ATTEMPT_COUNT_SQL) == 1L);

                assertAll("the state a repeat delivery left",
                        () -> assertEquals(0, total.compareTo(cardTotal(CARD_TOKEN)),
                                () -> "the per-card total moved to " + cardTotal(CARD_TOKEN)),
                        () -> assertEquals(0, new BigDecimal(AMOUNT).compareTo(storedAmount()),
                                () -> "the stored amount moved to " + storedAmount()),
                        () -> assertEquals(failures, counterValue(FAILURES_METER, FAILURE_KIND_TAG,
                                NotificationMetrics.FAILURE_RENDERING), "the failure count moved"),
                        () -> assertNoDeadLetter(deadLetters,
                                Set.of(coordinates(first), coordinates(repeat)),
                                "a dead letter of a suppressed duplicate"),
                        () -> assertEquals(List.of(), atOrAbove(lines, Level.WARN),
                                "lines the consumer logged above information level"));
            } finally {
                consumerLog.detachAppender(lines);
                lines.stop();
            }
        }
    }

    /**
     * Asserts a repeat flagged-assessment delivery leaves one marker on its own group and writes no
     * row of the read model, which carries no column an assessment could fill.
     */
    @Test
    @DisplayName("a repeat flagged assessment adds no marker on its own group")
    void repeatFlaggedAssessmentChangesNothing() {
        awaitAssignment(ASSESSED_GROUP);
        UUID eventId = UUID.randomUUID();
        FraudFlagged event = flaggedEvent(eventId);

        try (KafkaConsumer<String, byte[]> deadLetters = deadLetterReader()) {
            RecordMetadata first = publish(ASSESSED_TOPIC, ACCOUNT_ID, event);
            awaitCount(MARKER_COUNT_SQL, 1L, "the marker of event " + eventId, eventId);

            long suppressed = counterValue(DUPLICATES_SKIPPED_METER, null, null);
            RecordMetadata repeat = publish(ASSESSED_TOPIC, ACCOUNT_ID, event);
            awaitCounter(DUPLICATES_SKIPPED_METER, null, null, suppressed + 1L,
                    "the delivery the marker suppressed");
            assertHoldsThroughout("one marker and no read-model row",
                    () -> count(MARKER_COUNT_SQL, eventId) == 1L
                            && count(EVERY_ROW_COUNT_SQL) == 0L);

            assertAll("the state a repeat assessment left",
                    () -> assertEquals(ASSESSED_TOPIC, markerTopic(eventId),
                            "the topic the marker recorded"),
                    () -> assertEquals(0L, count(ATTEMPT_COUNT_SQL),
                            "delivery-attempt rows on the assessment path"),
                    () -> assertNoDeadLetter(deadLetters,
                            Set.of(coordinates(first), coordinates(repeat)),
                            "a dead letter of a suppressed duplicate"));
        }
    }

    /**
     * Asserts a marker written outside either listener suppresses a delivery on both topics, which
     * holds only where the guard reads the event identifier alone.
     */
    @Test
    @DisplayName("one marker table suppresses a delivery on either consumer group")
    void oneMarkerTableServesBothConsumerGroups() {
        awaitAssignment(POSTED_GROUP);
        awaitAssignment(ASSESSED_GROUP);
        UUID postedId = UUID.randomUUID();
        UUID assessedId = UUID.randomUUID();
        markers.save(new ProcessedEventEntity(postedId, Instant.now()));
        markers.save(new ProcessedEventEntity(assessedId, Instant.now()));

        long suppressed = counterValue(DUPLICATES_SKIPPED_METER, null, null);
        publish(POSTED_TOPIC, ACCOUNT_ID, postedEvent(postedId, CARD_TOKEN, TRANSACTION_ID));
        publish(ASSESSED_TOPIC, ACCOUNT_ID, flaggedEvent(assessedId));
        awaitCounter(DUPLICATES_SKIPPED_METER, null, null, suppressed + 2L,
                "the two deliveries the seeded markers suppressed");

        assertHoldsThroughout("no read-model row, no attempt row and two markers",
                () -> count(EVERY_ROW_COUNT_SQL) == 0L && count(ATTEMPT_COUNT_SQL) == 0L
                        && count(MARKER_COUNT_SQL, postedId) == 1L
                        && count(MARKER_COUNT_SQL, assessedId) == 1L);
        assertAll("the marker each seeded identifier still holds",
                () -> assertNull(markerTopic(postedId), "the seeded posted marker names a topic"),
                () -> assertNull(markerTopic(assessedId),
                        "the seeded assessment marker names a topic"));
    }

    /**
     * Asserts {@code processed_event} declares the three columns its migration creates, and no
     * column naming a consumer group, an event type or a payload.
     */
    @Test
    @DisplayName("the marker table declares three columns and no consumer-group discriminator")
    void markerTableCarriesNoGroupDiscriminator() {
        List<String> declared = jdbc.queryForList(COLUMN_NAMES_SQL, String.class, SERVICE_SCHEMA,
                "processed_event");

        assertAll("the columns of processed_event",
                () -> assertEquals(MARKER_COLUMNS, declared, () -> "it declares " + declared),
                () -> assertEquals(MARKER_COLUMNS.size(), declared.size(),
                        () -> "it declares " + declared.size() + " columns"),
                () -> assertTrue(new LinkedHashSet<>(declared).stream()
                        .noneMatch(REFUSED_MARKER_COLUMNS::contains),
                        () -> "it declares one of " + REFUSED_MARKER_COLUMNS));
    }

    /**
     * Asserts a delivery whose render fails leaves neither the row nor the marker. One header names
     * the render fault, which the claim and the row write both precede inside one transaction.
     */
    @Test
    @DisplayName("a failed render leaves no read-model row and no marker")
    void readModelRowAndMarkerRollBackTogether() {
        awaitAssignment(POSTED_GROUP);
        UUID eventId = UUID.randomUUID();
        TransactionPosted event = postedEvent(eventId, ACCOUNT_WITHOUT_PROJECTION, OTHER_CARD_TOKEN,
                TRANSACTION_ID);

        try (KafkaConsumer<String, byte[]> deadLetters = deadLetterReader()) {
            RecordMetadata failing = publish(POSTED_TOPIC, ACCOUNT_WITHOUT_PROJECTION, event);
            ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(deadLetters,
                    coordinates(failing), SPENT_ATTEMPTS_TIMEOUT,
                    "the dead letter of a failed render");

            assertAll("the state a failed render left",
                    () -> assertEquals(0L, count(ROW_COUNT_SQL, OTHER_CARD_TOKEN),
                            "read-model rows of the card"),
                    () -> assertEquals(0L, count(MARKER_COUNT_SQL, eventId),
                            "markers of the event"),
                    () -> assertEquals(0L, count(ATTEMPT_COUNT_SQL), "delivery-attempt rows"),
                    () -> assertEquals(MISSING_PROJECTION_TYPE,
                            trimmedHeader(deadLetter, HEADER_REASON),
                            () -> HEADER_REASON + " holds "
                                    + trimmedHeader(deadLetter, HEADER_REASON)));
        }
    }

    /**
     * Asserts one record takes three deliveries, spaced by the configured wait, then reaches the
     * dead-letter topic of its own source topic carrying the four metadata components.
     */
    @Test
    @DisplayName("three deliveries of one record reach the dead-letter topic of its source topic")
    void spentDeliveryAttemptsReachTheDeadLetterTopic() {
        awaitAssignment(POSTED_GROUP);
        UUID eventId = UUID.randomUUID();
        TransactionPosted event = postedEvent(eventId, ACCOUNT_WITHOUT_PROJECTION, OTHER_CARD_TOKEN,
                TRANSACTION_ID);
        long failed = counterValue(FAILURES_METER, FAILURE_KIND_TAG,
                NotificationMetrics.FAILURE_RENDERING);
        long lettered = counterValue(DEAD_LETTERED_METER, FAILURE_KIND_TAG,
                NotificationMetrics.UNKNOWN);

        try (KafkaConsumer<String, byte[]> deadLetters = deadLetterReader()) {
            Instant published = Instant.now();
            RecordMetadata failing = publish(POSTED_TOPIC, ACCOUNT_WITHOUT_PROJECTION, event);
            ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(deadLetters,
                    coordinates(failing), SPENT_ATTEMPTS_TIMEOUT,
                    "the dead letter of a spent record");
            Duration observed = Duration.between(published, Instant.now());
            awaitCounter(FAILURES_METER, FAILURE_KIND_TAG, NotificationMetrics.FAILURE_RENDERING,
                    failed + EXPECTED_DELIVERIES, "the failure of every delivery of one record");

            assertAll("the dead letter of a spent record",
                    () -> assertEquals(POSTED_DEAD_LETTER_TOPIC, deadLetter.topic(),
                            () -> "it landed on " + deadLetter.topic()),
                    () -> assertEquals(coordinates(failing), deadLetter.key(),
                            () -> "its key holds " + deadLetter.key()),
                    () -> assertEquals(ABEND_CODE, headersOf(deadLetter).get(HEADER_ABEND_CODE),
                            "the code header"),
                    () -> assertEquals(SERVICE_CULPRIT, headersOf(deadLetter).get(HEADER_CULPRIT),
                            "the culprit header"),
                    () -> assertTrue(trimmedHeader(deadLetter, HEADER_REASON).endsWith("Exception"),
                            () -> HEADER_REASON + " holds "
                                    + trimmedHeader(deadLetter, HEADER_REASON)),
                    () -> assertEquals(SAFE_FAILURE_MESSAGE,
                            trimmedHeader(deadLetter, HEADER_MESSAGE), "the message header"),
                    () -> assertEquals(Map.of(), headersOffDeclaredWidth(deadLetter),
                            "headers holding other than their declared width"),
                    () -> assertEquals(DeadLetterMetadata.RECORD_LENGTH, deadLetter.value().length,
                            "the length of the diagnostic record"),
                    () -> assertTrue(ALLOWED_DEAD_LETTER_HEADERS
                                    .containsAll(headersOf(deadLetter).keySet()),
                            () -> "it carries the headers " + headersOf(deadLetter).keySet()),
                    () -> assertCarriesNoDigitRun(deadLetter),
                    () -> assertEquals(lettered + 1L, counterValue(DEAD_LETTERED_METER,
                            FAILURE_KIND_TAG, NotificationMetrics.UNKNOWN),
                            "records counted as dead-lettered"),
                    () -> assertTrue(observed.compareTo(RETRY_WAIT_TOTAL) >= 0,
                            () -> "three deliveries took " + observed.toMillis() + " milliseconds"),
                    () -> assertEquals(FALLBACK_DEAD_LETTER_TOPIC,
                            environment.getProperty(FALLBACK_TOPIC_PROPERTY),
                            "the fallback topic this route did not need"),
                    () -> assertEquals(DEAD_LETTER_SUFFIX, environment.getProperty(SUFFIX_PROPERTY),
                            "the suffix this route appended"));
        }
    }

    /**
     * Asserts a payload carrying a nested envelope reaches the dead-letter topic on its first pass,
     * and that no listener of the posted-balance group took it.
     */
    @Test
    @DisplayName("a nested-envelope payload reaches the dead-letter topic before a listener runs")
    void nestedEnvelopePayloadNeverReachesTheListener() {
        awaitAssignment(POSTED_GROUP);
        UUID eventId = UUID.randomUUID();
        long consumed = counterValue(EVENTS_CONSUMED_METER, EVENT_TYPE_TAG,
                NotificationMetrics.EVENT_TRANSACTION_POSTED);
        long failed = counterValue(FAILURES_METER, FAILURE_KIND_TAG,
                NotificationMetrics.FAILURE_RENDERING);
        long lettered = counterValue(DEAD_LETTERED_METER, FAILURE_KIND_TAG,
                NotificationMetrics.FAILURE_SCHEMA_VALIDATION);

        try (KafkaConsumer<String, byte[]> deadLetters = deadLetterReader()) {
            Instant published = Instant.now();
            RecordMetadata refused =
                    publishBytes(POSTED_TOPIC, ACCOUNT_ID, nestedEnvelopePayload(eventId));
            ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(deadLetters,
                    coordinates(refused), ARRIVAL_TIMEOUT, "the dead letter of a refused payload");
            Duration observed = Duration.between(published, Instant.now());

            assertAll("the refusal of a nested envelope",
                    () -> assertEquals(POSTED_DEAD_LETTER_TOPIC, deadLetter.topic(),
                            () -> "it landed on " + deadLetter.topic()),
                    () -> assertEquals(consumed, counterValue(EVENTS_CONSUMED_METER, EVENT_TYPE_TAG,
                            NotificationMetrics.EVENT_TRANSACTION_POSTED),
                            "a listener took the refused record"),
                    () -> assertEquals(failed, counterValue(FAILURES_METER, FAILURE_KIND_TAG,
                            NotificationMetrics.FAILURE_RENDERING),
                            "a listener failed on the refused record"),
                    () -> assertEquals(lettered + 1L, counterValue(DEAD_LETTERED_METER,
                            FAILURE_KIND_TAG, NotificationMetrics.FAILURE_SCHEMA_VALIDATION),
                            "records counted as refused by a schema"),
                    () -> assertTrue(observed.compareTo(RETRY_WAIT_TOTAL) < 0,
                            () -> "the refusal took " + observed.toMillis() + " milliseconds"),
                    () -> assertEquals(0L, count(EVERY_ROW_COUNT_SQL), "read-model rows"),
                    () -> assertEquals(0L, count(MARKER_COUNT_SQL, eventId),
                            "markers of the event"),
                    () -> assertCarriesNoDigitRun(deadLetter));
        }
    }

    /**
     * Asserts a payload carrying every digit of a card number is refused, and that no digit of it
     * travels in the key, in a header or in the diagnostic record.
     */
    @Test
    @DisplayName("an unmasked card number is refused and no digit of it travels")
    void unmaskedCardNumberNeverTravels() {
        awaitAssignment(POSTED_GROUP);
        UUID eventId = UUID.randomUUID();

        try (KafkaConsumer<String, byte[]> deadLetters = deadLetterReader()) {
            RecordMetadata refused =
                    publishBytes(POSTED_TOPIC, ACCOUNT_ID, unmaskedCardPayload(eventId));
            ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter(deadLetters,
                    coordinates(refused), ARRIVAL_TIMEOUT, "the dead letter of an unmasked number");
            String diagnostic = new String(deadLetter.value(), StandardCharsets.UTF_8);

            assertAll("the refusal of an unmasked card number",
                    () -> assertCarriesNoDigitRun(deadLetter),
                    () -> assertFalse(diagnostic.contains(UNMASKED_CARD_NUMBER),
                            "the diagnostic record carries the card number"),
                    () -> assertFalse(deadLetter.key().contains(UNMASKED_CARD_NUMBER),
                            "the key carries the card number"),
                    () -> assertTrue(headersOf(deadLetter).values().stream()
                            .noneMatch(value -> value.contains(UNMASKED_CARD_NUMBER)),
                            "a header carries the card number"),
                    () -> assertEquals(DeadLetterMetadata.RECORD_LENGTH, diagnostic.length(),
                            "the length of the diagnostic record"),
                    () -> assertEquals(0L, count(EVERY_ROW_COUNT_SQL), "read-model rows"),
                    () -> assertEquals(0L, count(MARKER_COUNT_SQL, eventId),
                            "markers of the event"));
        }
    }

    /**
     * Asserts a cleared assessment claims one marker and writes nothing else, since the read model
     * of {@code app/cpy/COSTM01.CPY:L22-L35} carries no column an assessment fills.
     */
    @Test
    @DisplayName("a cleared assessment claims one marker and writes nothing else")
    void clearedAssessmentClaimsOneMarkerOnly() {
        awaitAssignment(ASSESSED_GROUP);
        UUID eventId = UUID.randomUUID();

        try (KafkaConsumer<String, byte[]> deadLetters = deadLetterReader()) {
            RecordMetadata sent = publish(ASSESSED_TOPIC, ACCOUNT_ID, clearedEvent(eventId));
            awaitCount(MARKER_COUNT_SQL, 1L, "the marker of event " + eventId, eventId);
            assertHoldsThroughout("one marker, no attempt row and no read-model row",
                    () -> count(MARKER_COUNT_SQL, eventId) == 1L
                            && count(ATTEMPT_COUNT_SQL) == 0L
                            && count(EVERY_ROW_COUNT_SQL) == 0L);

            assertAll("the state a cleared assessment left",
                    () -> assertEquals(ASSESSED_TOPIC, markerTopic(eventId),
                            "the topic the marker recorded"),
                    () -> assertNoDeadLetter(deadLetters, Set.of(coordinates(sent)),
                            "a dead letter of a cleared assessment"));
        }
    }

    /**
     * Asserts one delivery fills all fourteen columns of one read-model row, at the widths
     * {@code app/cpy/COSTM01.CPY:L22-L35} declares and under the key order of
     * {@code app/jcl/CREASTMT.JCL:L53}.
     */
    @Test
    @DisplayName("one delivery fills every column of one read-model row")
    void persistedRowCarriesEveryColumn() {
        awaitAssignment(POSTED_GROUP);
        publish(POSTED_TOPIC, ACCOUNT_ID,
                postedEvent(UUID.randomUUID(), CARD_TOKEN, TRANSACTION_ID));
        awaitCount(ROW_COUNT_SQL, 1L, "the read-model row of the card", CARD_TOKEN);
        Map<String, Object> row = jdbc.queryForMap(ROW_SQL, CARD_TOKEN, TRANSACTION_ID);
        BigDecimal amount = (BigDecimal) row.get("amount");

        assertAll("the row one delivery wrote",
                () -> assertEquals(READ_MODEL_COLUMN_COUNT, row.size(),
                        () -> "it holds " + row.size() + " columns"),
                () -> assertEquals(List.of(), nullColumns(row), "columns holding no value"),
                () -> assertEquals(CARD_TOKEN, text(row, "card_token"), "the card identity"),
                () -> assertEquals(TRANSACTION_ID, text(row, "transaction_id"), "the transaction"),
                () -> assertEquals(MASKED_CARD_NUMBER, text(row, "masked_card_number"),
                        "the masked card number"),
                () -> assertEquals(POSTED_AT, text(row, "processing_timestamp"),
                        "all twenty-six characters of the posting timestamp"),
                () -> assertEquals(ORIGIN_TIMESTAMP, text(row, "origin_timestamp"),
                        "all twenty-six characters of the origin timestamp"),
                () -> assertEquals(3, differingPositions(POSTED_AT, ORIGIN_TIMESTAMP),
                        "positions at which the two timestamp layouts differ"),
                () -> assertEquals('.', ORIGIN_TIMESTAMP.charAt(19),
                        "position 20 of the origin form"),
                () -> assertEquals('.', POSTED_AT.charAt(19), "position 20 of the posting form"),
                () -> assertTrue(POSTED_AT.endsWith("0000"),
                        "the four fixed zeros of the posting timestamp"),
                () -> assertEquals(0, new BigDecimal(AMOUNT).compareTo(amount), "the amount"),
                () -> assertEquals(2, amount.scale(),
                        () -> "the amount holds scale " + amount.scale()),
                () -> assertEquals(CATEGORY_CODE, text(row, "category_code"),
                        "the zero-filled category code"),
                () -> assertEquals(MERCHANT_ID, text(row, "merchant_id"),
                        "the zero-filled merchant identifier"),
                () -> assertEquals(CAPTURE_SOURCE, text(row, "source").strip(),
                        "the capture channel"),
                () -> assertEquals(DESCRIPTION, text(row, "description").strip(),
                        "the description"),
                () -> assertEquals(MERCHANT_NAME, text(row, "merchant_name").strip(),
                        "the merchant name"),
                () -> assertEquals(MERCHANT_CITY, text(row, "merchant_city").strip(),
                        "the merchant city"),
                () -> assertEquals(MERCHANT_ZIP, text(row, "merchant_zip").strip(),
                        "the merchant postal code"),
                () -> assertEquals(Map.of(), columnsOffDeclaredWidth(row),
                        "columns holding other than their declared width"),
                () -> assertFalse(row.containsKey("account_id"), "a column names the account"),
                () -> assertFalse(row.containsKey("new_balance"), "a column names the balance"));
    }

    /**
     * Asserts the message key names the account, leading zeros intact, while the row key names the
     * card identity and the transaction of {@code app/jcl/CREASTMT.JCL:L30}.
     */
    @Test
    @DisplayName("the message key names the account and the row key names the card")
    void messageKeyNamesTheAccountAndTheRowKeyNamesTheCard() {
        awaitAssignment(POSTED_GROUP);
        TransactionPosted event = postedEvent(UUID.randomUUID(), CARD_TOKEN, TRANSACTION_ID);

        try (KafkaConsumer<String, byte[]> replay = newReader(List.of(POSTED_TOPIC))) {
            RecordMetadata sent = publish(POSTED_TOPIC, ACCOUNT_ID, event);
            ConsumerRecord<String, byte[]> published = awaitRecord(replay,
                    record -> record.partition() == sent.partition()
                            && record.offset() == sent.offset(),
                    ARRIVAL_TIMEOUT, "the published record read back");
            awaitCount(ROW_COUNT_SQL, 1L, "the read-model row of the card", CARD_TOKEN);
            Map<String, Object> row = jdbc.queryForMap(ROW_SQL, CARD_TOKEN, TRANSACTION_ID);

            assertAll("the two keys of one delivery",
                    () -> assertEquals(ACCOUNT_ID, published.key(),
                            () -> "the message key holds " + published.key()),
                    () -> assertEquals(ACCOUNT_ID.length(), published.key().length(),
                            "the width of the message key"),
                    () -> assertTrue(published.key().startsWith("0"),
                            "the leading zeros of the message key survived"),
                    () -> assertEquals(published.key(), event.aggregateId(),
                            "the key names the aggregate the payload names"),
                    () -> assertEquals(CARD_TOKEN, text(row, "card_token"), "the first key column"),
                    () -> assertEquals(TRANSACTION_ID, text(row, "transaction_id"),
                            "the second key column"),
                    () -> assertNotEquals(ACCOUNT_ID, text(row, "card_token"),
                            "the row is keyed on the account"));
        }
    }

    /** Returns the one listener container joined to {@code groupId}. */
    private MessageListenerContainer containerOfGroup(String groupId) {
        List<MessageListenerContainer> matched = new ArrayList<>();
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            if (groupId.equals(container.getGroupId())) {
                matched.add(container);
            }
        }
        assertEquals(1, matched.size(),
                () -> "containers joined to group " + groupId + ": " + matched.size());
        return matched.getFirst();
    }

    /** Waits until the container of {@code groupId} runs and holds at least one partition. */
    private void awaitAssignment(String groupId) {
        MessageListenerContainer container = containerOfGroup(groupId);
        Awaitility.await("partitions assigned to group " + groupId)
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    Collection<TopicPartition> held = container.getAssignedPartitions();
                    return container.isRunning() && held != null && !held.isEmpty();
                });
    }

    /** Returns the one topic the container of {@code groupId} binds. */
    private String onlyTopicOf(String groupId) {
        String[] bound = containerOfGroup(groupId).getContainerProperties().getTopics();
        assertNotNull(bound, () -> "group " + groupId + " binds no topic list");
        assertEquals(1, bound.length,
                () -> "group " + groupId + " binds " + bound.length + " topics");
        return bound[0];
    }

    /** Builds a posted-balance event for the account the projection carries a row for. */
    private static TransactionPosted postedEvent(UUID eventId, String cardToken,
            String transactionId) {
        return postedEvent(eventId, ACCOUNT_ID, cardToken, transactionId);
    }

    /** Builds a posted-balance event carrying all fifteen payload values. */
    private static TransactionPosted postedEvent(UUID eventId, String accountId, String cardToken,
            String transactionId) {
        return TransactionPosted.of(
                envelopeFor(TransactionPosted.EVENT_TYPE, eventId, accountId,
                        DETAIL_SCHEMA_VERSION),
                transactionId, new BigDecimal(NEW_BALANCE), POSTED_AT, new BigDecimal(AMOUNT),
                MASKED_CARD_NUMBER, cardToken, TYPE_CODE, CATEGORY_CODE, CAPTURE_SOURCE,
                DESCRIPTION, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP,
                ORIGIN_TIMESTAMP);
    }

    /** Builds a flagged assessment, which carries no card number and no monetary value. */
    private static FraudFlagged flaggedEvent(UUID eventId) {
        EventEnvelope envelope = envelopeFor(FraudFlagged.EVENT_TYPE, eventId, ACCOUNT_ID,
                EventEnvelope.SCHEMA_VERSION);
        return new FraudFlagged(envelope.eventId(), envelope.eventType(), envelope.schemaVersion(),
                envelope.occurredAt(), envelope.aggregateId(), TRANSACTION_ID, RISK_SCORE,
                TRIGGERED_RULES, stampedNow(), envelope.aggregateId());
    }

    /** Builds a cleared assessment, which carries three payload values and no rule list. */
    private static FraudCleared clearedEvent(UUID eventId) {
        return new FraudCleared(envelopeFor(FraudCleared.EVENT_TYPE, eventId, ACCOUNT_ID,
                EventEnvelope.SCHEMA_VERSION), TRANSACTION_ID, ACCOUNT_ID, stampedNow());
    }

    /** Builds an envelope carrying a chosen event identifier, so two publishes can share one. */
    private static EventEnvelope envelopeFor(String eventType, UUID eventId, String accountId,
            int schemaVersion) {
        return new EventEnvelope(eventId, eventType, schemaVersion, stampedNow(), accountId);
    }

    /** Returns the current moment at millisecond precision, which every pattern here accepts. */
    private static Instant stampedNow() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** Builds the payload whose envelope sits under a nested object. */
    private static String nestedEnvelopePayload(UUID eventId) {
        return NESTED_ENVELOPE_PAYLOAD.formatted(eventId, DETAIL_SCHEMA_VERSION, stampedNow(),
                ACCOUNT_ID, TRANSACTION_ID, ACCOUNT_ID, NEW_BALANCE, POSTED_AT, AMOUNT,
                MASKED_CARD_NUMBER);
    }

    /** Builds the payload whose card number carries every digit. */
    private static String unmaskedCardPayload(UUID eventId) {
        return UNMASKED_CARD_PAYLOAD.formatted(eventId, stampedNow(), ACCOUNT_ID, TRANSACTION_ID,
                ACCOUNT_ID, NEW_BALANCE, POSTED_AT, AMOUNT, UNMASKED_CARD_NUMBER);
    }

    /** Publishes one validated event under {@code key} and returns where the broker stored it. */
    private static RecordMetadata publish(String topic, String key, Object event) {
        try {
            SendResult<String, Object> result = events.send(topic, key, event)
                    .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(key, result.getProducerRecord().key(), "the key of the published record");
            return result.getRecordMetadata();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Publishing to " + topic + " was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("Publishing to " + topic + " did not succeed.", failed);
        } catch (TimeoutException timedOut) {
            throw new AssertionError("Publishing to " + topic + " did not finish in time.",
                    timedOut);
        }
    }

    /** Publishes raw bytes, which no schema check runs over on the way out. */
    private static RecordMetadata publishBytes(String topic, String key, String payload) {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(producerSettings(),
                new StringSerializer(), new ByteArraySerializer())) {
            return producer.send(new ProducerRecord<>(topic, key,
                            payload.getBytes(StandardCharsets.UTF_8)))
                    .get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Publishing to " + topic + " was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("Publishing to " + topic + " did not succeed.", failed);
        } catch (TimeoutException timedOut) {
            throw new AssertionError("Publishing to " + topic + " did not finish in time.",
                    timedOut);
        }
    }

    /** Returns the producer settings both publishers share. */
    private static Map<String, Object> producerSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ProducerConfig.ACKS_CONFIG, "all");
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE);
        return settings;
    }

    /** Builds one reader over {@code topics}, in a group of its own, from the earliest offset. */
    private static KafkaConsumer<String, byte[]> newReader(List<String> topics) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, "duplicate-delivery-" + UUID.randomUUID());
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE);
        KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(settings,
                new StringDeserializer(), new ByteArrayDeserializer());
        reader.subscribe(topics);
        return reader;
    }

    /** Builds one reader over both dead-letter topics this service can reach. */
    private static KafkaConsumer<String, byte[]> deadLetterReader() {
        return newReader(List.of(POSTED_DEAD_LETTER_TOPIC, ASSESSED_DEAD_LETTER_TOPIC));
    }

    /** Waits for the dead letter whose key holds one record's coordinates. */
    private static ConsumerRecord<String, byte[]> awaitDeadLetter(
            KafkaConsumer<String, byte[]> reader, String key, Duration timeout, String subject) {
        return awaitRecord(reader, record -> key.equals(record.key()), timeout, subject);
    }

    /** Asserts no dead letter naming any of {@code keys} arrives inside the silence window. */
    private static void assertNoDeadLetter(KafkaConsumer<String, byte[]> reader, Set<String> keys,
            String subject) {
        assertNoRecord(reader, record -> keys.contains(record.key()), subject);
    }

    /** Waits for one record to match, then asserts exactly one did. */
    private static ConsumerRecord<String, byte[]> awaitRecord(KafkaConsumer<String, byte[]> reader,
            Predicate<ConsumerRecord<String, byte[]>> wanted, Duration timeout, String subject) {
        List<ConsumerRecord<String, byte[]>> matched = new ArrayList<>();
        Awaitility.await(subject)
                .pollInSameThread()
                .atMost(timeout)
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    drain(reader, wanted, matched);
                    return !matched.isEmpty();
                });
        assertEquals(1, matched.size(), subject);
        return matched.getFirst();
    }

    /** Asserts no matching record arrives throughout the silence window. */
    private static void assertNoRecord(KafkaConsumer<String, byte[]> reader,
            Predicate<ConsumerRecord<String, byte[]>> wanted, String subject) {
        List<ConsumerRecord<String, byte[]>> matched = new ArrayList<>();
        Awaitility.await(subject)
                .pollInSameThread()
                .during(SILENCE_WINDOW)
                .atMost(SILENCE_WINDOW.plus(ARRIVAL_TIMEOUT))
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    drain(reader, wanted, matched);
                    return matched.isEmpty();
                });
    }

    /** Fetches once and keeps every record that matches. */
    private static void drain(KafkaConsumer<String, byte[]> reader,
            Predicate<ConsumerRecord<String, byte[]>> wanted,
            List<ConsumerRecord<String, byte[]>> matched) {
        for (ConsumerRecord<String, byte[]> delivery : reader.poll(FETCH_TIMEOUT)) {
            if (wanted.test(delivery)) {
                matched.add(delivery);
            }
        }
    }

    /** Renders one record's broker coordinates, which the dead-letter route carries as its key. */
    private static String coordinates(RecordMetadata metadata) {
        return metadata.topic() + "-" + metadata.partition() + "-" + metadata.offset();
    }

    /** Reads every header one record carries as text, keyed by header name. */
    private static Map<String, String> headersOf(ConsumerRecord<String, byte[]> record) {
        Map<String, String> read = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            read.put(header.key(), header.value() == null
                    ? "" : new String(header.value(), StandardCharsets.UTF_8));
        }
        return read;
    }

    /** Reads one header as text with its right-hand padding removed. */
    private static String trimmedHeader(ConsumerRecord<String, byte[]> record, String name) {
        String value = headersOf(record).get(name);
        return value == null ? null : value.stripTrailing();
    }

    /** Maps every metadata header holding other than its declared width onto the width it holds. */
    private static Map<String, Integer> headersOffDeclaredWidth(
            ConsumerRecord<String, byte[]> record) {
        Map<String, String> carried = headersOf(record);
        Map<String, Integer> wrong = new LinkedHashMap<>();
        METADATA_HEADER_WIDTHS.forEach((name, width) -> {
            String value = carried.get(name);
            if (value == null || value.length() != width) {
                wrong.put(name, value == null ? 0 : value.length());
            }
        });
        return wrong;
    }

    /** Asserts no key, header value or payload byte of one dead letter holds a long digit run. */
    private static void assertCarriesNoDigitRun(ConsumerRecord<String, byte[]> record) {
        List<String> carried = new ArrayList<>(headersOf(record).values());
        carried.add(record.key() == null ? "" : record.key());
        carried.add(new String(record.value(), StandardCharsets.UTF_8));
        for (String text : carried) {
            assertFalse(DIGIT_RUN.matcher(text).find(),
                    "one dead letter carries a run of twelve or more digits");
        }
    }

    /** Runs one count query and returns what it counted. */
    private long count(String sql, Object... arguments) {
        Long counted = jdbc.queryForObject(sql, Long.class, arguments);
        return counted == null ? 0L : counted;
    }

    /** Waits for a count query to reach {@code expected}, then asserts it holds that value. */
    private void awaitCount(String sql, long expected, String subject, Object... arguments) {
        Awaitility.await(subject)
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> count(sql, arguments) == expected);
        assertEquals(expected, count(sql, arguments), subject);
    }

    /** Totals the amounts one card identity holds, which is the figure an alert reports. */
    private BigDecimal cardTotal(String cardToken) {
        return jdbc.queryForObject(CARD_TOTAL_SQL, BigDecimal.class, cardToken);
    }

    /** Reads the amount stored under the card identity and transaction of the fixture event. */
    private BigDecimal storedAmount() {
        return jdbc.queryForObject(AMOUNT_SQL, BigDecimal.class, CARD_TOKEN, TRANSACTION_ID);
    }

    /** Reads the topic one marker recorded, and {@code null} where it recorded none. */
    private String markerTopic(UUID eventId) {
        return jdbc.queryForObject(MARKER_TOPIC_SQL, String.class, eventId);
    }

    /** Reads one counter, by meter name alone where {@code tagKey} is {@code null}. */
    private long counterValue(String meter, String tagKey, String tagValue) {
        Counter counter = tagKey == null
                ? meters.find(meter).counter()
                : meters.find(meter).tag(tagKey, tagValue).counter();
        assertNotNull(counter, () -> "no counter named " + meter + " is registered");
        return (long) counter.count();
    }

    /** Waits for one counter to reach {@code expected}. */
    private void awaitCounter(String meter, String tagKey, String tagValue, long expected,
            String subject) {
        Awaitility.await(subject)
                .atMost(SPENT_ATTEMPTS_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> counterValue(meter, tagKey, tagValue) == expected);
    }

    /** Asserts one condition holds throughout the silence window. */
    private static void assertHoldsThroughout(String subject, Callable<Boolean> condition) {
        Awaitility.await(subject)
                .during(SILENCE_WINDOW)
                .atMost(SILENCE_WINDOW.plus(ARRIVAL_TIMEOUT))
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(condition);
    }

    /** Names every line one appender captured at or above {@code floor}. */
    private static List<String> atOrAbove(ListAppender<ILoggingEvent> appender, Level floor) {
        List<String> reported = new ArrayList<>();
        for (ILoggingEvent line : appender.list) {
            if (line.getLevel().isGreaterOrEqual(floor)) {
                reported.add(line.getLevel() + " " + line.getFormattedMessage());
            }
        }
        return reported;
    }

    /** Reads one column of one row as text, padding included. */
    private static String text(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }

    /** Names every column of one row holding no value. */
    private static List<String> nullColumns(Map<String, Object> row) {
        List<String> empty = new ArrayList<>();
        row.forEach((column, value) -> {
            if (value == null) {
                empty.add(column);
            }
        });
        return empty;
    }

    /** Maps every text column holding other than its declared width onto the width it holds. */
    private static Map<String, Integer> columnsOffDeclaredWidth(Map<String, Object> row) {
        Map<String, Integer> wrong = new LinkedHashMap<>();
        TEXT_COLUMN_WIDTHS.forEach((column, width) -> {
            String value = text(row, column);
            if (value == null || value.length() != width) {
                wrong.put(column, value == null ? 0 : value.length());
            }
        });
        return wrong;
    }

    /** Counts the positions at which two timestamp layouts differ. */
    private static int differingPositions(String one, String other) {
        int shared = Math.min(one.length(), other.length());
        int differences = Math.abs(one.length() - other.length());
        for (int position = 0; position < shared; position++) {
            if (one.charAt(position) != other.charAt(position)) {
                differences++;
            }
        }
        return differences;
    }
}
