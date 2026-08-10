package com.carddemo.fraud.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.fraud.FraudServiceDatabase;
import com.carddemo.fraud.TestIdentityPasswords;
import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.entity.ProcessedEventEntity;
import com.carddemo.fraud.entity.VelocityWindowEntity;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.carddemo.fraud.repository.ProcessedEventRepository;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Matches the four Jakarta Persistence entity mappings against the schema Flyway migrates, then
 * asserts the physical columns of {@code fraud_assessment}, {@code velocity_window} and
 * {@code processed_event}.
 *
 * <p>The fraud detection service is net new; no COBOL ancestor exists, and no Common Business
 * Oriented Language program in the source scores risk.
 *
 * <p>One PostgreSQL container carries the schema. The four values in the annotation below stand in
 * for the credential variables the shipped configuration leaves undefined, and each is inert. The
 * physical contract of the outbox table belongs to {@code OutboxAtomicityIT}, which no assertion
 * here repeats. Every catalogue query names its schema, which a {@link JdbcTemplate} does not
 * inherit from the persistence layer.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH
        })
@DisplayName("Entity mappings and physical columns of the migrated fraud schema")
class EntitySchemaValidationIT {

    /** Host and port the broker client is pointed at, where nothing listens. */
    private static final String UNREACHABLE_BROKER = "localhost:1";

    /** Property naming the schema the persistence layer places every entity in. */
    private static final String SCHEMA_PROPERTY = "spring.jpa.properties.hibernate.default_schema";

    /** Property naming the schema Flyway creates and migrates. */
    private static final String FLYWAY_SCHEMAS_PROPERTY = "spring.flyway.schemas";

    /** Property naming what the persistence layer does with the schema at start-up. */
    private static final String DDL_AUTO_PROPERTY = "spring.jpa.hibernate.ddl-auto";

    /** Value {@link #DDL_AUTO_PROPERTY} resolves to. */
    private static final String SCHEMA_VALIDATION = "validate";

    private static final String FRAUD_ASSESSMENT = "fraud_assessment";
    private static final String VELOCITY_WINDOW = "velocity_window";
    private static final String PROCESSED_EVENT = "processed_event";
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    private static final String PK_FRAUD_ASSESSMENT = "pk_fraud_assessment";
    private static final String PK_VELOCITY_WINDOW = "pk_velocity_window";
    private static final String PK_PROCESSED_EVENT = "pk_processed_event";
    private static final String IX_FRAUD_ASSESSMENT_ASSESSED_AT = "ix_fraud_assessment_assessed_at";

    /**
     * The index the collection route walks its page from, over the account, the assessment time
     * descending and the primary key descending.
     *
     * <p>{@code V9__fraud_assessment_account_cursor_index.sql} added it and dropped the two indexes
     * that carried only a leading part of it. Both were prefixes of this one, so it answers every
     * query either answered, and each cost a write on every insert into a table that takes one per
     * authorized transaction.
     */
    private static final String IX_FRAUD_ASSESSMENT_ACCOUNT_CURSOR =
            "ix_fraud_assessment_account_cursor";
    private static final String IX_VELOCITY_WINDOW_START = "ix_velocity_window_start";
    private static final String IX_PROCESSED_EVENT_PROCESSED_AT = "ix_processed_event_processed_at";

    private static final String COLUMN_TRANSACTION_ID = "transaction_id";
    private static final String COLUMN_ACCOUNT_ID = "account_id";
    private static final String COLUMN_RISK_SCORE = "risk_score";
    private static final String COLUMN_FLAGGED = "flagged";
    private static final String COLUMN_TRIGGERED_RULES = "triggered_rules";
    private static final String COLUMN_ASSESSED_AT = "assessed_at";
    private static final String COLUMN_WINDOW_START = "window_start";
    private static final String COLUMN_AUTHORIZATION_COUNT = "authorization_count";
    private static final String COLUMN_TOTAL_AMOUNT = "total_amount";
    private static final String COLUMN_UPDATED_AT = "updated_at";
    private static final String COLUMN_EVENT_ID = "event_id";
    private static final String COLUMN_PROCESSED_AT = "processed_at";
    private static final String COLUMN_CONSUMED_TOPIC = "consumed_topic";

    private static final String TYPE_CHARACTER = "character";
    private static final String TYPE_CHARACTER_VARYING = "character varying";
    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_NUMERIC = "numeric";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_UUID = "uuid";
    private static final String TYPE_TIMESTAMP_WITH_TIME_ZONE = "timestamp with time zone";

    private static final int TRANSACTION_ID_WIDTH = 16;
    private static final int ACCOUNT_ID_WIDTH = 11;
    private static final int TRIGGERED_RULES_WIDTH = 64;
    private static final int CONSUMED_TOPIC_WIDTH = 128;
    // The accumulator width V3__velocity_total_headroom.sql leaves on the column, and the width
    // VelocityWindowEntity declares. It is not the width of one transaction amount.
    private static final int TOTAL_AMOUNT_PRECISION = 15;
    private static final int TOTAL_AMOUNT_SCALE = 2;

    /** Column names {@code processed_event} carries, in declaration order. */
    private static final List<String> PROCESSED_EVENT_COLUMNS =
            List.of(COLUMN_EVENT_ID, COLUMN_PROCESSED_AT, COLUMN_CONSUMED_TOPIC);

    /** Column names {@code velocity_window} carries, in declaration order. */
    private static final List<String> VELOCITY_WINDOW_COLUMNS =
            List.of(COLUMN_ACCOUNT_ID, COLUMN_WINDOW_START, COLUMN_AUTHORIZATION_COUNT,
                    COLUMN_TOTAL_AMOUNT, COLUMN_UPDATED_AT);

    /** Column names {@code fraud_assessment} carries, in declaration order. */
    private static final List<String> FRAUD_ASSESSMENT_COLUMNS =
            List.of(COLUMN_TRANSACTION_ID, COLUMN_ACCOUNT_ID, COLUMN_RISK_SCORE, COLUMN_FLAGGED,
                    COLUMN_TRIGGERED_RULES, COLUMN_ASSESSED_AT);

    /** Names {@code processed_event} does not carry. */
    private static final List<String> PROCESSED_EVENT_ABSENT_COLUMNS =
            List.of("event_type", "payload", "retry_count", "attempt_count", "consumer_group",
                    "status", "error_message");

    /** Names {@code velocity_window} does not carry. */
    private static final List<String> VELOCITY_WINDOW_ABSENT_COLUMNS =
            List.of("window_end", "expires_at", "time_to_live", "ttl_seconds", "declined_count",
                    "flagged_count");

    /** Names {@code fraud_assessment} does not carry. */
    private static final List<String> FRAUD_ASSESSMENT_ABSENT_COLUMNS =
            List.of("merchant_category", "masked_card_number", "amount", "currency",
                    "decline_reason", "model_version", "rule_count", "notes");

    /** The three rule identifiers the schema and the published contract permit. */
    private static final String VELOCITY = "VELOCITY";
    private static final String AMOUNT_ANOMALY = "AMOUNT_ANOMALY";
    private static final String MERCHANT_CATEGORY = "MERCHANT_CATEGORY";

    /**
     * Stored form of an assessment that triggered no rule, an empty JavaScript Object Notation
     * (JSON) array.
     */
    private static final String NO_RULES_STORED = "[]";

    /** Widest legitimate stored rule list, which holds all three identifiers. */
    private static final String WIDEST_RULES_STORED =
            "[\"VELOCITY\",\"AMOUNT_ANOMALY\",\"MERCHANT_CATEGORY\"]";

    /** Character count of {@link #WIDEST_RULES_STORED}. */
    private static final int WIDEST_RULES_LENGTH = 49;

    /** Account the fixture rows belong to, eleven digits with leading zeros kept. */
    private static final String ACCOUNT_ID = "00000000007";

    /**
     * Transaction the fixture rows assess. The transaction identifier field is alphanumeric, so the
     * column is fixed-width character and carries no digits constraint.
     */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** Identifier of the one marker row a test writes. */
    private static final UUID MARKER_EVENT_ID =
            UUID.fromString("2f8a1b74-5c6d-4e3f-8a2b-7c4d5e6f7a8b");

    /** Schema Flyway migrates into, which every unqualified statement resolves against. */
    private static final String MIGRATED_SCHEMA = "fraud_service";

    /**
     * The account the paging test writes its tied pair under, eleven digits.
     *
     * <p>No other test in this class writes an assessment for it, and the {@code @AfterEach} of this
     * class empties the table either way.
     */
    private static final String TIED_ACCOUNT_ID = "00000000042";

    /**
     * The one instant both rows of the paging test record.
     *
     * <p>Column {@code assessed_at} is {@code TIMESTAMP(6)}, so two values are tied only when they
     * agree to the microsecond. A literal shared by both rows is that tie, held deliberately rather
     * than waited for.
     */
    private static final Instant TIED_ASSESSED_AT = Instant.parse("2026-04-01T09:15:30.123456Z");

    /** Score the paging rows carry. Any value inside the permitted range would do. */
    private static final int TIED_RISK_SCORE = 10;

    /** Topic the claimed markers of this class record. */
    private static final String CONSUMED_TOPIC = "transaction.authorized";

    /**
     * Topic the marker rows written directly by a statement in this class record.
     *
     * <p>{@code consumed_topic} is half of the primary key since
     * {@code src/main/resources/db/migration/V4__processed_event_topic_key.sql}, so a statement that
     * writes a marker has to name it.
     */
    private static final String MARKER_CONSUMED_TOPIC = CONSUMED_TOPIC;

    /**
     * A second topic name, which is what makes the second half of the key observable.
     *
     * <p>This service reads one topic today. The name below stands in for a topic a second listener
     * would read, and it is a topic two other services of this platform already read, so nothing
     * about it is invented.
     */
    private static final String OTHER_CONSUMED_TOPIC = "account.state-changed";

    /** What the marker statement reports when this caller took the event. */
    private static final int CLAIMED = 1;

    /** What the marker statement reports when the event was already taken. */
    private static final int ALREADY_CLAIMED = 0;

    /** Rows the window statement writes on either of its arms. */
    private static final int ONE_ROW = 1;

    private static final Instant MARKER_PROCESSED_AT = Instant.parse("2026-02-14T08:45:30Z");
    /** Inclusive lower bound of a fixed-width bucket, which is keyed by its start instant. */
    private static final Instant FIRST_WINDOW_START = Instant.parse("2026-02-14T08:00:00Z");

    /** Inclusive lower bound of a later bucket of the same account. */
    private static final Instant SECOND_WINDOW_START = Instant.parse("2026-02-14T09:00:00Z");
    private static final Instant WINDOW_UPDATED_AT = Instant.parse("2026-02-14T09:15:45Z");
    private static final Instant ASSESSED_AT = Instant.parse("2026-02-14T08:46:15Z");

    private static final BigDecimal STORED_TOTAL = new BigDecimal("504.77");
    private static final BigDecimal ADDED_TOTAL = new BigDecimal("100.00");
    private static final BigDecimal RAISED_TOTAL = new BigDecimal("604.77");

    /** Authorizations the fixture window counts. */
    private static final int WINDOW_COUNT = 3;

    /** Score the assessment row written without the entity carries. */
    private static final int RAW_RISK_SCORE = 7;

    /** Score an assessment carries alongside a flagged verdict. */
    private static final int FLAGGED_RISK_SCORE = 65;

    /** Score an assessment carries alongside a cleared verdict. */
    private static final int CLEARED_RISK_SCORE = 12;

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link FraudServiceDatabase} owns it and hands this class a database of its own inside
     * it. Nothing here starts or stops a container.
     */
    private static final PostgreSQLContainer POSTGRES = FraudServiceDatabase.container();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FraudAssessmentRepository assessments;

    @Autowired
    private ProcessedEventRepository markers;

    @Autowired
    private VelocityWindowRepository windows;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Points the datasource at the container and the broker client at a port with no listener.
     *
     * @param registry registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EntitySchemaValidationIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> UNREACHABLE_BROKER);
    }

    /** Returns the container URL with the service schema on the connection search path. */
    private static String jdbcUrlOnServiceSchema() {
        return FraudServiceDatabase.urlFor(EntitySchemaValidationIT.class);
    }

    /** Empties the three tables a test writes to, so no row reaches the next test. */
    @AfterEach
    void emptyWrittenTables() {
        jdbc.update("DELETE FROM " + qualified(FRAUD_ASSESSMENT));
        jdbc.update("DELETE FROM " + qualified(VELOCITY_WINDOW));
        jdbc.update("DELETE FROM " + qualified(PROCESSED_EVENT));
    }

    /**
     * A refresh under schema validation matches all four entity mappings against the migrated
     * schema, the outbox entity included.
     */
    @Test
    @DisplayName("the context refreshes with schema validation on, which matches all four entity "
            + "mappings against the migrated schema")
    void contextRefreshesWithSchemaValidationOn() {
        assertAll(
                () -> assertEquals(SCHEMA_VALIDATION, environment.getProperty(DDL_AUTO_PROPERTY),
                        DDL_AUTO_PROPERTY),
                () -> assertTrue(context instanceof ConfigurableApplicationContext refreshed
                                && refreshed.isActive(),
                        "the context is not active"),
                () -> assertNotNull(context.getBean(FraudAssessmentRepository.class),
                        "the assessment repository is absent from the context"));
    }

    @Test
    @DisplayName("Flyway created the one schema both properties name, and applied versions 1, 3, "
            + "4, 5, 6, 7, 8 and 9")
    void flywayCreatedTheSchemaAndAppliedItsEightMigrations() {
        String schema = schema();
        Integer schemaRows = jdbc.queryForObject(
                "SELECT count(*) FROM pg_namespace WHERE nspname = ?", Integer.class, schema);
        Boolean versionOneApplied = jdbc.queryForObject(
                "SELECT success FROM " + qualified(FLYWAY_HISTORY) + " WHERE version = '1'",
                Boolean.class);
        Integer versionTwoRows = jdbc.queryForObject(
                "SELECT count(*) FROM " + qualified(FLYWAY_HISTORY) + " WHERE version = '2'",
                Integer.class);
        Boolean versionThreeApplied = jdbc.queryForObject(
                "SELECT success FROM " + qualified(FLYWAY_HISTORY) + " WHERE version = '3'",
                Boolean.class);
        Boolean versionFourApplied = jdbc.queryForObject(
                "SELECT success FROM " + qualified(FLYWAY_HISTORY) + " WHERE version = '4'",
                Boolean.class);
        Boolean versionFiveApplied = jdbc.queryForObject(
                "SELECT success FROM " + qualified(FLYWAY_HISTORY) + " WHERE version = '5'",
                Boolean.class);
        Boolean versionSixApplied = jdbc.queryForObject(
                "SELECT success FROM " + qualified(FLYWAY_HISTORY) + " WHERE version = '6'",
                Boolean.class);
        Boolean versionSevenApplied = jdbc.queryForObject(
                "SELECT success FROM " + qualified(FLYWAY_HISTORY) + " WHERE version = '7'",
                Boolean.class);
        Boolean versionEightApplied = jdbc.queryForObject(
                "SELECT success FROM " + qualified(FLYWAY_HISTORY) + " WHERE version = '8'",
                Boolean.class);
        Boolean versionNineApplied = jdbc.queryForObject(
                "SELECT success FROM " + qualified(FLYWAY_HISTORY) + " WHERE version = '9'",
                Boolean.class);
        Integer versionRows = jdbc.queryForObject(
                "SELECT count(*) FROM " + qualified(FLYWAY_HISTORY) + " WHERE version IS NOT NULL",
                Integer.class);
        assertAll(
                () -> assertFalse(schema.isBlank(), SCHEMA_PROPERTY + " resolves to nothing"),
                () -> assertEquals(schema, environment.getProperty(FLYWAY_SCHEMAS_PROPERTY),
                        FLYWAY_SCHEMAS_PROPERTY + " names a schema the entities are not placed in"),
                () -> assertEquals(Integer.valueOf(1), schemaRows,
                        () -> "the database holds no schema named " + schema),
                () -> assertEquals(Boolean.TRUE, versionOneApplied, "migration version 1"),
                () -> assertEquals(Integer.valueOf(0), versionTwoRows,
                        "a version 2 migration row exists, and no seed migration ships"),
                () -> assertEquals(Boolean.TRUE, versionThreeApplied, "migration version 3"),
                () -> assertEquals(Boolean.TRUE, versionFourApplied, "migration version 4, "
                        + "V4__processed_event_topic_key.sql"),
                () -> assertEquals(Boolean.TRUE, versionFiveApplied, "migration version 5, "
                        + "V5__outbox_dead_letter_state.sql"),
                () -> assertEquals(Boolean.TRUE, versionSixApplied, "migration version 6, "
                        + "V6__assessment_paging_tiebreaker.sql"),
                () -> assertEquals(Boolean.TRUE, versionSevenApplied, "migration version 7, "
                        + "V7__outbox_correlation.sql"),
                () -> assertEquals(Boolean.TRUE, versionEightApplied, "migration version 8, "
                        + "V8__outbox_aggregate_head_index.sql, which indexes the account head the "
                        + "relay claims"),
                () -> assertEquals(Boolean.TRUE, versionNineApplied, "migration version 9, "
                        + "V9__fraud_assessment_account_cursor_index.sql, which indexes the page the "
                        + "collection route walks and drops the two prefixes of that index"),
                () -> assertEquals(Integer.valueOf(8), versionRows,
                        "the eight versioned migrations this service ships"));
    }

    @Test
    @DisplayName("no entity names a schema on its table annotation")
    void noEntityNamesASchemaOnItsTableAnnotation() {
        List<Class<?>> entities = List.of(FraudAssessmentEntity.class, VelocityWindowEntity.class,
                ProcessedEventEntity.class, OutboxEventEntity.class);
        List<Executable> checks = new ArrayList<>();
        for (Class<?> entity : entities) {
            Table table = entity.getAnnotation(Table.class);
            checks.add(() -> assertNotNull(table,
                    () -> entity.getSimpleName() + " carries no table annotation"));
            checks.add(() -> assertEquals("", table.schema(),
                    () -> entity.getSimpleName() + " names a schema"));
        }
        assertAll(checks);
    }

    @Test
    @DisplayName("processed_event carries its three columns and none of the seven names it "
            + "excludes")
    void processedEventCarriesItsColumnsAndNoOther() {
        List<String> present = columnNames(PROCESSED_EVENT);
        assertEquals(PROCESSED_EVENT_COLUMNS, present, PROCESSED_EVENT + " columns");
        assertAll(absenceChecks(PROCESSED_EVENT, present, PROCESSED_EVENT_ABSENT_COLUMNS));
    }

    /**
     * Asserts both key columns are supplied rather than generated, and that the primary key names
     * them in that order.
     *
     * <p>{@code src/main/resources/db/migration/V4__processed_event_topic_key.sql} widened the key
     * from {@code event_id} alone, because a delivery is identified by its event and the stream it
     * arrived on. {@code event_id} leads, so the index the key builds still serves a lookup naming
     * the event alone.
     */
    @Test
    @DisplayName("processed_event keys on a supplied identifier and a supplied topic: uuid and "
            + "varchar, both NOT NULL, neither defaulted nor generated")
    void processedEventKeyIsASuppliedIdentifier() {
        Map<String, Object> eventId = column(PROCESSED_EVENT, COLUMN_EVENT_ID);
        Map<String, Object> processedAt = column(PROCESSED_EVENT, COLUMN_PROCESSED_AT);
        Map<String, Object> consumedTopic = column(PROCESSED_EVENT, COLUMN_CONSUMED_TOPIC);
        assertAll(
                () -> assertEquals(TYPE_UUID, eventId.get("data_type"), COLUMN_EVENT_ID),
                () -> assertEquals("NO", eventId.get("is_nullable"), COLUMN_EVENT_ID),
                () -> assertNull(eventId.get("column_default"),
                        COLUMN_EVENT_ID + " carries a default"),
                () -> assertEquals("NO", eventId.get("is_identity"),
                        COLUMN_EVENT_ID + " generates its own value"),
                () -> assertEquals(List.of(COLUMN_EVENT_ID, COLUMN_CONSUMED_TOPIC),
                        keyColumns(PK_PROCESSED_EVENT), PK_PROCESSED_EVENT),
                () -> assertEquals(TYPE_TIMESTAMP_WITH_TIME_ZONE, processedAt.get("data_type"),
                        COLUMN_PROCESSED_AT),
                () -> assertEquals("NO", processedAt.get("is_nullable"), COLUMN_PROCESSED_AT),
                () -> assertEquals(TYPE_CHARACTER_VARYING, consumedTopic.get("data_type"),
                        COLUMN_CONSUMED_TOPIC),
                () -> assertEquals(Integer.valueOf(CONSUMED_TOPIC_WIDTH),
                        consumedTopic.get("character_maximum_length"), COLUMN_CONSUMED_TOPIC),
                () -> assertEquals("NO", consumedTopic.get("is_nullable"),
                        COLUMN_CONSUMED_TOPIC + " is half of the key, and a key column holds no "
                                + "null"),
                () -> assertNull(consumedTopic.get("column_default"),
                        COLUMN_CONSUMED_TOPIC + " carries a default"));
    }

    @Test
    @DisplayName("processed_event carries its primary-key index and the range index its purge "
            + "reads, and no other")
    void processedEventCarriesItsTwoIndexesAndNoOther() {
        assertEquals(List.of(IX_PROCESSED_EVENT_PROCESSED_AT, PK_PROCESSED_EVENT),
                indexNames(PROCESSED_EVENT), PROCESSED_EVENT + " indexes");
    }

    @Test
    @DisplayName("processed_event returns the identifier, the topic and the instant a marker row "
            + "carries")
    void processedEventRoundTripsOneMarker() {
        jdbc.update("INSERT INTO " + qualified(PROCESSED_EVENT)
                        + " (" + COLUMN_EVENT_ID + ", " + COLUMN_PROCESSED_AT + ", "
                        + COLUMN_CONSUMED_TOPIC + ") VALUES (?, ?, ?)",
                MARKER_EVENT_ID, atUtc(MARKER_PROCESSED_AT), MARKER_CONSUMED_TOPIC);
        UUID storedId = jdbc.queryForObject("SELECT " + COLUMN_EVENT_ID + " FROM "
                + qualified(PROCESSED_EVENT), UUID.class);
        Instant storedInstant = instant("SELECT " + COLUMN_PROCESSED_AT + " FROM "
                + qualified(PROCESSED_EVENT));
        String storedTopic = jdbc.queryForObject("SELECT " + COLUMN_CONSUMED_TOPIC + " FROM "
                + qualified(PROCESSED_EVENT), String.class);
        assertAll(
                () -> assertEquals(MARKER_EVENT_ID, storedId, COLUMN_EVENT_ID),
                () -> assertEquals(MARKER_PROCESSED_AT, storedInstant, COLUMN_PROCESSED_AT),
                () -> assertEquals(MARKER_CONSUMED_TOPIC, storedTopic, COLUMN_CONSUMED_TOPIC));
    }

    /**
     * Asserts one identifier is storable once per topic rather than once per schema.
     *
     * <p>This is the property {@code V4__processed_event_topic_key.sql} exists for, read at the
     * table. Two producing services assign event identifiers independently, so one identifier can
     * arrive on two topics carrying two different events. Under the narrow key the second insert was
     * refused, and the consumer reading that refusal concluded the event was already handled and
     * applied nothing at all.
     */
    @Test
    @DisplayName("processed_event accepts one identifier once per topic and refuses a repeat on "
            + "one topic")
    void processedEventKeysOnTheEventAndTheTopicTogether() {
        String sql = "INSERT INTO " + qualified(PROCESSED_EVENT)
                + " (" + COLUMN_EVENT_ID + ", " + COLUMN_PROCESSED_AT + ", "
                + COLUMN_CONSUMED_TOPIC + ") VALUES (?, ?, ?)";
        jdbc.update(sql, MARKER_EVENT_ID, atUtc(MARKER_PROCESSED_AT), MARKER_CONSUMED_TOPIC);
        jdbc.update(sql, MARKER_EVENT_ID, atUtc(MARKER_PROCESSED_AT), OTHER_CONSUMED_TOPIC);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM " + qualified(PROCESSED_EVENT), Integer.class);

        assertAll(
                () -> assertEquals(Integer.valueOf(2), rows,
                        "each topic carries its own marker for the shared identifier"),
                () -> assertThrows(DataIntegrityViolationException.class,
                        () -> jdbc.update(sql, MARKER_EVENT_ID, atUtc(MARKER_PROCESSED_AT),
                                MARKER_CONSUMED_TOPIC),
                        "a redelivery on one topic is still refused by " + PK_PROCESSED_EVENT),
                () -> assertThrows(DataIntegrityViolationException.class,
                        () -> jdbc.update("INSERT INTO " + qualified(PROCESSED_EVENT)
                                        + " (" + COLUMN_EVENT_ID + ", " + COLUMN_PROCESSED_AT
                                        + ", " + COLUMN_CONSUMED_TOPIC + ") VALUES (?, ?, ?)",
                                MARKER_EVENT_ID, atUtc(MARKER_PROCESSED_AT), "   "),
                        "a blank topic names no topic, and "
                                + "ck_processed_event_consumed_topic refuses one"));
    }

    @Test
    @DisplayName("velocity_window carries its five columns and none of the names it excludes")
    void velocityWindowCarriesItsFiveColumnsAndNoOther() {
        List<String> present = columnNames(VELOCITY_WINDOW);
        assertEquals(VELOCITY_WINDOW_COLUMNS, present, VELOCITY_WINDOW + " columns");
        assertAll(absenceChecks(VELOCITY_WINDOW, present, VELOCITY_WINDOW_ABSENT_COLUMNS));
    }

    @Test
    @DisplayName("velocity_window holds an eleven-character account, two instants, an integer "
            + "count and a numeric total of precision 15 and scale 2")
    void velocityWindowColumnsCarryTheirTypesAndWidths() {
        Map<String, Object> accountId = column(VELOCITY_WINDOW, COLUMN_ACCOUNT_ID);
        Map<String, Object> windowStart = column(VELOCITY_WINDOW, COLUMN_WINDOW_START);
        Map<String, Object> count = column(VELOCITY_WINDOW, COLUMN_AUTHORIZATION_COUNT);
        Map<String, Object> total = column(VELOCITY_WINDOW, COLUMN_TOTAL_AMOUNT);
        Map<String, Object> updatedAt = column(VELOCITY_WINDOW, COLUMN_UPDATED_AT);
        assertAll(
                () -> assertEquals(TYPE_CHARACTER, accountId.get("data_type"), COLUMN_ACCOUNT_ID),
                () -> assertEquals(Integer.valueOf(ACCOUNT_ID_WIDTH),
                        accountId.get("character_maximum_length"), COLUMN_ACCOUNT_ID),
                () -> assertEquals("NO", accountId.get("is_nullable"), COLUMN_ACCOUNT_ID),
                () -> assertEquals(TYPE_TIMESTAMP_WITH_TIME_ZONE, windowStart.get("data_type"),
                        COLUMN_WINDOW_START),
                () -> assertEquals(TYPE_INTEGER, count.get("data_type"),
                        COLUMN_AUTHORIZATION_COUNT),
                () -> assertEquals(TYPE_NUMERIC, total.get("data_type"), COLUMN_TOTAL_AMOUNT),
                () -> assertEquals(Integer.valueOf(TOTAL_AMOUNT_PRECISION),
                        total.get("numeric_precision"), COLUMN_TOTAL_AMOUNT),
                () -> assertEquals(Integer.valueOf(TOTAL_AMOUNT_SCALE),
                        total.get("numeric_scale"), COLUMN_TOTAL_AMOUNT),
                () -> assertEquals(TYPE_TIMESTAMP_WITH_TIME_ZONE, updatedAt.get("data_type"),
                        COLUMN_UPDATED_AT));
    }

    @Test
    @DisplayName("pk_velocity_window orders account_id first and window_start second")
    void velocityWindowKeyOrdersTheAccountBeforeTheWindowStart() {
        assertEquals(List.of(COLUMN_ACCOUNT_ID, COLUMN_WINDOW_START),
                keyColumns(PK_VELOCITY_WINDOW),
                PK_VELOCITY_WINDOW + " column order by ordinal position");
    }

    @Test
    @DisplayName("velocity_window carries its primary-key index and the range index its purge "
            + "reads, and no other")
    void velocityWindowCarriesItsTwoIndexesAndNoOther() {
        assertEquals(List.of(IX_VELOCITY_WINDOW_START, PK_VELOCITY_WINDOW),
                indexNames(VELOCITY_WINDOW), VELOCITY_WINDOW + " indexes");
    }

    @Test
    @DisplayName("the window entity declares precision 15 and scale 2 on its money column")
    void windowEntityDeclaresThePrecisionAndScaleOfItsMoneyColumn() {
        Column money = moneyColumn();
        assertAll(
                () -> assertEquals(TOTAL_AMOUNT_PRECISION, money.precision(),
                        COLUMN_TOTAL_AMOUNT + " mapping precision"),
                () -> assertEquals(TOTAL_AMOUNT_SCALE, money.scale(),
                        COLUMN_TOTAL_AMOUNT + " mapping scale"));
    }

    @Test
    @DisplayName("the window entity nests the key class its identifier-class annotation names")
    void windowEntityNestsItsKeyClass() {
        Class<?> discovered = nestedKeyClass();
        IdClass declared = VelocityWindowEntity.class.getAnnotation(IdClass.class);
        assertAll(
                () -> assertEquals(VelocityWindowEntity.class, discovered.getEnclosingClass(),
                        "the key class is nested elsewhere"),
                () -> assertNotNull(declared, "the window entity names no identifier class"),
                () -> assertEquals(discovered, declared.value(),
                        "the identifier class annotation names another type"));
    }

    @Test
    @DisplayName("the marker statement claims one event once and reports the second delivery")
    void theMarkerStatementClaimsOneEventOnce() {
        Integer first = transactionTemplate.execute(status ->
                markers.claimEvent(MARKER_EVENT_ID, MARKER_PROCESSED_AT, CONSUMED_TOPIC));
        Integer second = transactionTemplate.execute(status ->
                markers.claimEvent(MARKER_EVENT_ID, MARKER_PROCESSED_AT, CONSUMED_TOPIC));
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + qualified(PROCESSED_EVENT)
                + " WHERE event_id = ?", Integer.class, MARKER_EVENT_ID);

        assertAll(
                () -> assertEquals(Integer.valueOf(CLAIMED), first,
                        "the first claim of an event reports no insert"),
                () -> assertEquals(Integer.valueOf(ALREADY_CLAIMED), second,
                        "the second claim of one event reports an insert"),
                () -> assertEquals(Integer.valueOf(1), rows,
                        "one event left another row count than one in " + PROCESSED_EVENT));
    }

    @Test
    @DisplayName("the window statement opens a window at the amount, then raises count and total")
    void theWindowStatementOpensThenRaisesOneWindow() {
        Integer opened = transactionTemplate.execute(status -> windows.addAuthorization(
                ACCOUNT_ID, FIRST_WINDOW_START, STORED_TOTAL, WINDOW_UPDATED_AT));
        Integer raised = transactionTemplate.execute(status -> windows.addAuthorization(
                ACCOUNT_ID, FIRST_WINDOW_START, ADDED_TOTAL, WINDOW_UPDATED_AT));
        Map<String, Object> stored = jdbc.queryForMap("SELECT " + COLUMN_AUTHORIZATION_COUNT + ", "
                + COLUMN_TOTAL_AMOUNT + " FROM " + qualified(VELOCITY_WINDOW) + " WHERE "
                + COLUMN_ACCOUNT_ID + " = ? AND " + COLUMN_WINDOW_START + " = ?",
                ACCOUNT_ID, atUtc(FIRST_WINDOW_START));
        BigDecimal total = (BigDecimal) stored.get(COLUMN_TOTAL_AMOUNT);

        assertAll(
                () -> assertEquals(Integer.valueOf(ONE_ROW), opened,
                        "the insert arm reported another row count"),
                () -> assertEquals(Integer.valueOf(ONE_ROW), raised,
                        "the conflict arm reported another row count"),
                () -> assertEquals(Integer.valueOf(2), stored.get(COLUMN_AUTHORIZATION_COUNT),
                        "two authorizations left another count"),
                () -> assertEquals(TOTAL_AMOUNT_SCALE, total.scale(),
                        COLUMN_TOTAL_AMOUNT + " scale"),
                () -> assertEquals(0, RAISED_TOTAL.compareTo(total),
                        "the two amounts summed to another total"));
    }

    @Test
    @DisplayName("a second assessment of one transaction is refused rather than replacing the first")
    void aSecondAssessmentOfOneTransactionIsRefused() {
        saveAssessment(TRANSACTION_ID, CLEARED_RISK_SCORE, List.of());

        assertThrows(DataIntegrityViolationException.class,
                () -> saveAssessment(TRANSACTION_ID, FLAGGED_RISK_SCORE, List.of(VELOCITY)),
                "a second verdict for one transaction reached the table");
    }

    @Test
    @DisplayName("velocity_window keeps two windows of one account and returns 504.77 at scale 2")
    void velocityWindowKeepsTwoWindowsOfOneAccount() {
        insertWindow(FIRST_WINDOW_START, STORED_TOTAL);
        insertWindow(SECOND_WINDOW_START, STORED_TOTAL);
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + qualified(VELOCITY_WINDOW)
                + " WHERE " + COLUMN_ACCOUNT_ID + " = ?", Integer.class, ACCOUNT_ID);
        BigDecimal first = totalAt(FIRST_WINDOW_START);
        BigDecimal second = totalAt(SECOND_WINDOW_START);
        assertAll(
                () -> assertEquals(Integer.valueOf(2), rows,
                        "two windows of one account do not coexist under the composite key"),
                () -> assertEquals(TOTAL_AMOUNT_SCALE, first.scale(),
                        COLUMN_TOTAL_AMOUNT + " scale"),
                () -> assertEquals(0, STORED_TOTAL.compareTo(first), COLUMN_TOTAL_AMOUNT),
                () -> assertEquals(0, STORED_TOTAL.compareTo(second), COLUMN_TOTAL_AMOUNT));
    }

    @Test
    @DisplayName("adding 100.00 to a stored 504.77 gives exactly 604.77 at scale 2")
    void velocityWindowTotalRisesAtScaleTwo() {
        insertWindow(FIRST_WINDOW_START, STORED_TOTAL);
        jdbc.update("UPDATE " + qualified(VELOCITY_WINDOW) + " SET " + COLUMN_TOTAL_AMOUNT + " = "
                        + COLUMN_TOTAL_AMOUNT + " + ? WHERE " + COLUMN_ACCOUNT_ID + " = ?",
                ADDED_TOTAL, ACCOUNT_ID);
        BigDecimal stored = totalAt(FIRST_WINDOW_START);
        BigDecimal computed = STORED_TOTAL.add(ADDED_TOTAL);
        assertAll(
                () -> assertEquals(TOTAL_AMOUNT_SCALE, stored.scale(),
                        COLUMN_TOTAL_AMOUNT + " scale"),
                () -> assertEquals(0, RAISED_TOTAL.compareTo(stored), COLUMN_TOTAL_AMOUNT),
                () -> assertEquals(TOTAL_AMOUNT_SCALE, computed.scale(), "the computed scale"),
                () -> assertEquals(0, RAISED_TOTAL.compareTo(computed), "the computed total"));
    }

    @Test
    @DisplayName("velocity_window refuses a negative total because refunds are stored by magnitude")
    void velocityWindowRefusesANegativeTotal() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertWindow(FIRST_WINDOW_START, new BigDecimal("-0.01")),
                "the database accepted a refund that could lower the velocity total");
    }

    @Test
    @DisplayName("the velocity upsert keeps every concurrent count and amount increment")
    void velocityUpsertKeepsConcurrentIncrements() throws Exception {
        int workers = 8;
        BigDecimal perAuthorization = new BigDecimal("10.00");
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> updates = new ArrayList<>();
        String searchPath = schema();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < workers; index++) {
                updates.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    Integer changed = new TransactionTemplate(transactionManager).execute(status -> {
                        jdbc.execute("SET LOCAL search_path TO " + searchPath);
                        return windows.addAuthorization(
                                ACCOUNT_ID, FIRST_WINDOW_START, perAuthorization, WINDOW_UPDATED_AT);
                    });
                    return changed == null ? 0 : changed;
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not reach the start gate");
            start.countDown();
            for (Future<Integer> update : updates) {
                assertEquals(Integer.valueOf(1), update.get(10, TimeUnit.SECONDS),
                        "one atomic upsert changed an unexpected row count");
            }
        }

        Integer count = jdbc.queryForObject("SELECT " + COLUMN_AUTHORIZATION_COUNT + " FROM "
                        + qualified(VELOCITY_WINDOW) + " WHERE " + COLUMN_ACCOUNT_ID + " = ?"
                        + " AND " + COLUMN_WINDOW_START + " = ?",
                Integer.class, ACCOUNT_ID, atUtc(FIRST_WINDOW_START));
        BigDecimal total = totalAt(FIRST_WINDOW_START);
        assertAll(
                () -> assertEquals(Integer.valueOf(workers), count, "authorization count"),
                () -> assertEquals(0, new BigDecimal("80.00").compareTo(total),
                        "amount magnitude total"));
    }

    @Test
    @DisplayName("fraud_assessment carries its six columns and none of the names it excludes")
    void fraudAssessmentCarriesItsSixColumnsAndNoOther() {
        List<String> present = columnNames(FRAUD_ASSESSMENT);
        assertEquals(FRAUD_ASSESSMENT_COLUMNS, present, FRAUD_ASSESSMENT + " columns");
        assertAll(absenceChecks(FRAUD_ASSESSMENT, present, FRAUD_ASSESSMENT_ABSENT_COLUMNS));
    }

    @Test
    @DisplayName("fraud_assessment holds a sixteen-character key, an eleven-character account, an "
            + "integer score, a boolean verdict and a sixty-four-character rule list")
    void fraudAssessmentColumnsCarryTheirTypesAndWidths() {
        Map<String, Object> transactionId = column(FRAUD_ASSESSMENT, COLUMN_TRANSACTION_ID);
        Map<String, Object> accountId = column(FRAUD_ASSESSMENT, COLUMN_ACCOUNT_ID);
        Map<String, Object> riskScore = column(FRAUD_ASSESSMENT, COLUMN_RISK_SCORE);
        Map<String, Object> flagged = column(FRAUD_ASSESSMENT, COLUMN_FLAGGED);
        Map<String, Object> rules = column(FRAUD_ASSESSMENT, COLUMN_TRIGGERED_RULES);
        Map<String, Object> assessedAt = column(FRAUD_ASSESSMENT, COLUMN_ASSESSED_AT);
        assertAll(
                () -> assertEquals(TYPE_CHARACTER, transactionId.get("data_type"),
                        COLUMN_TRANSACTION_ID),
                () -> assertEquals(Integer.valueOf(TRANSACTION_ID_WIDTH),
                        transactionId.get("character_maximum_length"), COLUMN_TRANSACTION_ID),
                () -> assertEquals("NO", transactionId.get("is_nullable"), COLUMN_TRANSACTION_ID),
                () -> assertEquals(List.of(COLUMN_TRANSACTION_ID), keyColumns(PK_FRAUD_ASSESSMENT),
                        PK_FRAUD_ASSESSMENT),
                () -> assertEquals(TYPE_CHARACTER, accountId.get("data_type"), COLUMN_ACCOUNT_ID),
                () -> assertEquals(Integer.valueOf(ACCOUNT_ID_WIDTH),
                        accountId.get("character_maximum_length"), COLUMN_ACCOUNT_ID),
                () -> assertEquals("NO", accountId.get("is_nullable"), COLUMN_ACCOUNT_ID),
                () -> assertEquals(TYPE_INTEGER, riskScore.get("data_type"), COLUMN_RISK_SCORE),
                () -> assertEquals("NO", riskScore.get("is_nullable"), COLUMN_RISK_SCORE),
                () -> assertEquals(TYPE_BOOLEAN, flagged.get("data_type"), COLUMN_FLAGGED),
                () -> assertEquals("NO", flagged.get("is_nullable"), COLUMN_FLAGGED),
                () -> assertEquals(TYPE_CHARACTER_VARYING, rules.get("data_type"),
                        COLUMN_TRIGGERED_RULES),
                () -> assertEquals(Integer.valueOf(TRIGGERED_RULES_WIDTH),
                        rules.get("character_maximum_length"), COLUMN_TRIGGERED_RULES),
                () -> assertEquals("NO", rules.get("is_nullable"), COLUMN_TRIGGERED_RULES),
                () -> assertEquals(TYPE_TIMESTAMP_WITH_TIME_ZONE, assessedAt.get("data_type"),
                        COLUMN_ASSESSED_AT),
                () -> assertEquals("NO", assessedAt.get("is_nullable"), COLUMN_ASSESSED_AT));
    }

    @Test
    @DisplayName("fraud_assessment carries its primary-key index, the cursor index the collection "
            + "route walks and the range index its purge reads, and no other")
    void fraudAssessmentCarriesItsThreeIndexesAndNoOther() {
        assertAll(
                () -> assertEquals(List.of(IX_FRAUD_ASSESSMENT_ACCOUNT_CURSOR,
                                IX_FRAUD_ASSESSMENT_ASSESSED_AT, PK_FRAUD_ASSESSMENT),
                        indexNames(FRAUD_ASSESSMENT), FRAUD_ASSESSMENT + " indexes"),
                // The three columns and both descending orders are asserted against the definition
                // PostgreSQL reports, not against the migration text. A page walks this order from a
                // named position, so an index missing the trailing key would leave ties unordered and
                // a boundary inside a group of equal assessment times would repeat one row and skip
                // another.
                () -> assertTrue(indexDefinition(IX_FRAUD_ASSESSMENT_ACCOUNT_CURSOR)
                                .contains("(" + COLUMN_ACCOUNT_ID + ", " + COLUMN_ASSESSED_AT
                                        + " DESC, " + COLUMN_TRANSACTION_ID + " DESC)"),
                        () -> IX_FRAUD_ASSESSMENT_ACCOUNT_CURSOR
                                + " covers other columns or another order: "
                                + indexDefinition(IX_FRAUD_ASSESSMENT_ACCOUNT_CURSOR)),
                // A withdrawn index must be gone from the live schema, not merely absent from the
                // entity: an index the entity stopped declaring would otherwise go on costing writes.
                () -> assertEquals(List.of(), indexNames(FRAUD_ASSESSMENT).stream()
                                .filter(name -> "ix_fraud_assessment_account".equals(name)
                                        || "ix_fraud_assessment_account_assessed_at".equals(name))
                                .toList(),
                        "an index V9 dropped is still present in the migrated schema"));
    }

    /**
     * Two assessments of one account sharing one assessment instant fall on one page each, in a
     * deterministic order.
     *
     * <p>The order the collection route applies is {@code assessed_at} descending. That column is a
     * timestamp this service stamps, and two authorizations of one account arriving inside the same
     * microsecond share it — one burst on one account produces exactly that. A sort with ties is not
     * a total order, so the database is free to return a tied pair either way round, and a caller
     * paging by offset then reads one row twice and never sees the other: page one takes the first row
     * of one ordering and page two takes the second row of another.
     *
     * <p>The primary key breaks the tie. This test pages one row at a time over a tied pair, which is
     * the smallest page that can expose the defect, and asserts three things: the higher transaction
     * identifier comes first, the two pages hold different rows, and the two rows together are the two
     * that were written. A finder ordering on the instant alone fails the second assertion as soon as
     * the planner returns the pair in the order it inserted them.
     */
    @Test
    @DisplayName("two assessments sharing one instant page one each, ordered by the key that breaks "
            + "the tie")
    void tiedAssessmentInstantsPageDeterministically() {
        String earlierKey = "TIEDCASE00000001";
        String laterKey = "TIEDCASE00000002";
        storeAssessment(earlierKey, TIED_ACCOUNT_ID, TIED_ASSESSED_AT);
        storeAssessment(laterKey, TIED_ACCOUNT_ID, TIED_ASSESSED_AT);

        List<String> firstPage = newestAssessmentKeys(TIED_ACCOUNT_ID, 1);
        List<String> secondPage =
                assessmentKeysAfter(TIED_ACCOUNT_ID, TIED_ASSESSED_AT, firstPage.getFirst(), 1);

        assertAll("a tied pair has one total order",
                () -> assertEquals(List.of(laterKey), firstPage,
                        "the higher transaction identifier is the head of a tied pair"),
                () -> assertEquals(List.of(earlierKey), secondPage,
                        "the second page continues where the first stopped, so no row is repeated "
                                + "and none is skipped"),
                () -> assertEquals(List.of(laterKey, earlierKey),
                        newestAssessmentKeys(TIED_ACCOUNT_ID, 2),
                        "one page holding both rows carries the same order the two pages did"));
    }

    /**
     * Writes one cleared assessment through the repository under test.
     *
     * @param transactionId the sixteen-character primary key
     * @param accountId     the eleven-digit account the assessment belongs to
     * @param assessedAt    the instant the assessment records
     */
    private void storeAssessment(String transactionId, String accountId, Instant assessedAt) {
        transactionTemplate.executeWithoutResult(status -> assessments.save(
                new FraudAssessmentEntity(transactionId, accountId, TIED_RISK_SCORE, false,
                        List.of(), assessedAt)));
    }

    /**
     * Reads the newest page of assessment keys for an account, through the finder the route calls.
     *
     * @param accountId the account to read
     * @param size      the rows one page carries
     * @return the transaction identifiers the page holds, in the order returned
     */
    private List<String> newestAssessmentKeys(String accountId, int size) {
        return transactionTemplate.execute(status -> assessments
                .findByAccountIdOrderByAssessedAtDescTransactionIdDesc(accountId, Limit.of(size))
                .stream()
                .map(FraudAssessmentEntity::getTransactionId)
                .toList());
    }

    /**
     * Reads the page following one named row, through the cursor finder the route calls.
     *
     * <p>The position is a pair rather than a row number, so the boundary is exact where a page
     * falls inside a group of equal assessment times.
     *
     * @param accountId     the account to read
     * @param assessedAt    the assessment time the previous page ended on
     * @param transactionId the identifier the previous page ended on
     * @param size          the rows one page carries
     * @return the transaction identifiers the page holds, in the order returned
     */
    private List<String> assessmentKeysAfter(String accountId, Instant assessedAt,
            String transactionId, int size) {
        return transactionTemplate.execute(status -> assessments
                .findPageAfter(accountId, assessedAt, transactionId, Limit.of(size))
                .stream()
                .map(FraudAssessmentEntity::getTransactionId)
                .toList());
    }

    @Test
    @DisplayName("fraud_assessment returns both identifiers character for character with their "
            + "leading zeros")
    void fraudAssessmentKeepsIdentifiersCharacterForCharacter() {
        FraudAssessmentEntity saved =
                saveAssessment(TRANSACTION_ID, CLEARED_RISK_SCORE, List.of());
        Map<String, Object> stored = jdbc.queryForMap("SELECT " + COLUMN_TRANSACTION_ID + ", "
                + COLUMN_ACCOUNT_ID + " FROM " + qualified(FRAUD_ASSESSMENT));
        assertAll(
                () -> assertEquals(TRANSACTION_ID, stored.get(COLUMN_TRANSACTION_ID),
                        COLUMN_TRANSACTION_ID),
                () -> assertEquals(ACCOUNT_ID, stored.get(COLUMN_ACCOUNT_ID), COLUMN_ACCOUNT_ID),
                () -> assertEquals(TRANSACTION_ID_WIDTH,
                        ((String) stored.get(COLUMN_TRANSACTION_ID)).length(),
                        COLUMN_TRANSACTION_ID + " length"),
                () -> assertEquals(ACCOUNT_ID_WIDTH,
                        ((String) stored.get(COLUMN_ACCOUNT_ID)).length(),
                        COLUMN_ACCOUNT_ID + " length"),
                () -> assertEquals(TRANSACTION_ID, saved.getTransactionId(),
                        "the saved entity key"));
    }

    @Test
    @DisplayName("the rule-list converter is nested in the assessment entity, applies through an "
            + "explicit annotation and is not applied automatically")
    void ruleListConverterIsNestedAndNotAppliedAutomatically() {
        Class<?> converter = nestedConverterClass();
        Converter marker = converter.getAnnotation(Converter.class);
        Field converted = convertedField();
        Column column = converted.getAnnotation(Column.class);
        assertAll(
                () -> assertEquals(FraudAssessmentEntity.class, converter.getEnclosingClass(),
                        "the converter is nested elsewhere"),
                () -> assertTrue(AttributeConverter.class.isAssignableFrom(converter),
                        "the converter implements no attribute-converter contract"),
                () -> assertTrue(marker == null || !marker.autoApply(),
                        "the converter applies automatically"),
                () -> assertEquals(converter, converted.getAnnotation(Convert.class).converter(),
                        "the converted field names another converter"),
                () -> assertNotNull(column, "the converted field carries no column annotation"),
                () -> assertEquals(COLUMN_TRIGGERED_RULES, column.name(),
                        "the converted field maps another column"));
    }

    @Test
    @DisplayName("triggered_rules stores the identifiers in evaluation order, unsorted, and "
            + "returns that order")
    void triggeredRulesKeepEvaluationOrder() {
        List<String> evaluated = List.of(MERCHANT_CATEGORY, VELOCITY);
        saveAssessment(TRANSACTION_ID, FLAGGED_RISK_SCORE, evaluated);
        String stored = storedRules(TRANSACTION_ID);
        List<String> read = reloadRules(TRANSACTION_ID);
        assertAll(
                () -> assertEquals("[\"" + MERCHANT_CATEGORY + "\",\"" + VELOCITY + "\"]", stored,
                        COLUMN_TRIGGERED_RULES),
                () -> assertFalse(stored.indexOf(VELOCITY) < stored.indexOf(MERCHANT_CATEGORY),
                        "the stored identifiers are sorted"),
                () -> assertEquals(evaluated, read, "the order read back"));
    }

    @Test
    @DisplayName("triggered_rules refuses a repeated identifier")
    void triggeredRulesRefuseARepeatedIdentifier() {
        String repeated = "[\"" + VELOCITY + "\",\"" + VELOCITY + "\"]";
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> insertAssessment(TRANSACTION_ID, repeated, true));
        assertEquals(0, assessments.count(), "the invalid row reached the table");
    }

    @Test
    @DisplayName("triggered_rules stores an empty array for no rule and returns an empty list")
    void triggeredRulesStoreAnEmptyArrayForNoRule() {
        saveAssessment(TRANSACTION_ID, CLEARED_RISK_SCORE, List.of());
        String stored = storedRules(TRANSACTION_ID);
        List<String> read = reloadRules(TRANSACTION_ID);
        assertAll(
                () -> assertNotNull(stored, COLUMN_TRIGGERED_RULES + " holds no value"),
                () -> assertEquals(NO_RULES_STORED, stored, COLUMN_TRIGGERED_RULES),
                () -> assertNotNull(read, "the list read back holds nothing at all"),
                () -> assertEquals(List.of(), read, "the list read back"));
    }

    /**
     * The database half of the flag threshold. {@code RiskScoringService} flags on the score reaching
     * {@code carddemo.fraud.risk.flag-threshold}, so a rule worth less than the threshold triggering
     * alone produces exactly this row. While the constraint was a biconditional this insert failed,
     * which meant applying the threshold at all would have failed here rather than in a rule.
     */
    @Test
    @DisplayName("fraud_assessment accepts a rule list with no flag, which is the below-threshold row")
    void fraudAssessmentAcceptsRulesWithoutAFlag() {
        assertDoesNotThrow(() -> insertAssessment(TRANSACTION_ID, "[\"" + VELOCITY + "\"]", false),
                "a rule that triggered below the threshold has to be storable, or the threshold "
                        + "cannot be applied");
        assertEquals("[\"" + VELOCITY + "\"]", storedRules(TRANSACTION_ID),
                COLUMN_TRIGGERED_RULES);
    }

    /**
     * The half of the old constraint that is still true, and is still enforced. Every rule scores
     * above zero and the threshold is at least one, so a score that reached it must name a rule.
     */
    @Test
    @DisplayName("fraud_assessment still refuses a flag that names no rule")
    void fraudAssessmentStillRefusesAFlagWithNoRule() {
        DataIntegrityViolationException refused =
                assertThrows(DataIntegrityViolationException.class,
                        () -> insertAssessment(TRANSACTION_ID, NO_RULES_STORED, true),
                        "a flagged row naming no rule states a conclusion with no reason behind it");
        assertTrue(refused.getMessage().contains("ck_fraud_assessment_verdict"),
                "the failure names the constraint: " + refused.getMessage());
    }

    @Test
    @DisplayName("triggered_rules stores all three identifiers in 49 of its 64 characters and "
            + "returns them unchanged")
    void triggeredRulesStoreTheWidestValueWithinItsColumn() {
        List<String> evaluated = List.of(VELOCITY, AMOUNT_ANOMALY, MERCHANT_CATEGORY);
        saveAssessment(TRANSACTION_ID, FLAGGED_RISK_SCORE, evaluated);
        String stored = storedRules(TRANSACTION_ID);
        assertAll(
                () -> assertEquals(WIDEST_RULES_STORED, stored, COLUMN_TRIGGERED_RULES),
                () -> assertEquals(WIDEST_RULES_LENGTH, stored.length(),
                        COLUMN_TRIGGERED_RULES + " length"),
                () -> assertTrue(stored.length() <= TRIGGERED_RULES_WIDTH,
                        () -> COLUMN_TRIGGERED_RULES + " holds " + TRIGGERED_RULES_WIDTH
                                + " characters and the stored value holds " + stored.length()),
                () -> assertEquals(evaluated, reloadRules(TRANSACTION_ID),
                        "the identifiers read back"));
    }

    /**
     * Returns the schema the persistence layer places every entity in.
     *
     * @return the resolved schema name
     */
    private String schema() {
        return environment.getProperty(SCHEMA_PROPERTY, "");
    }

    /**
     * Prefixes one table name with the schema.
     *
     * @param table unqualified table name
     * @return the schema-qualified name
     */
    private String qualified(String table) {
        return schema() + "." + table;
    }

    /**
     * Reads the column names of one table in declaration order.
     *
     * @param table unqualified table name
     * @return the column names, ordered by ordinal position
     */
    private List<String> columnNames(String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position",
                String.class, schema(), table);
    }

    /**
     * Reads the catalogue row describing one column.
     *
     * @param table  unqualified table name
     * @param column column name
     * @return type, width, precision, scale, nullability, default and identity of the column
     */
    private Map<String, Object> column(String table, String column) {
        return jdbc.queryForMap("SELECT data_type, character_maximum_length, numeric_precision,"
                + " numeric_scale, is_nullable, column_default, is_identity"
                + " FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                schema(), table, column);
    }

    /**
     * Reads the index names of one table, sorted so the comparison holds under any collation.
     *
     * @param table unqualified table name
     * @return the index names in ascending order
     */
    private List<String> indexNames(String table) {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes"
                        + " WHERE schemaname = ? AND tablename = ?",
                        String.class, schema(), table)
                .stream().sorted().toList();
    }

    /**
     * Reads the definition of one index.
     *
     * @param indexName index name
     * @return the statement the catalogue holds for the index
     */
    private String indexDefinition(String indexName) {
        return jdbc.queryForObject("SELECT indexdef FROM pg_indexes"
                + " WHERE schemaname = ? AND indexname = ?", String.class, schema(), indexName);
    }

    /**
     * Reads the columns of one key constraint in ordinal-position order.
     *
     * @param constraintName primary-key constraint name
     * @return the key columns, first part first
     */
    private List<String> keyColumns(String constraintName) {
        return jdbc.queryForList("SELECT kcu.column_name"
                + " FROM information_schema.table_constraints tc"
                + " JOIN information_schema.key_column_usage kcu"
                + " ON kcu.constraint_schema = tc.constraint_schema"
                + " AND kcu.constraint_name = tc.constraint_name"
                + " WHERE tc.constraint_schema = ? AND tc.constraint_name = ?"
                + " AND tc.constraint_type = 'PRIMARY KEY'"
                + " ORDER BY kcu.ordinal_position", String.class, schema(), constraintName);
    }

    /**
     * Builds one check per name a table must not carry.
     *
     * @param table   unqualified table name, named in the failure text
     * @param present the column names the table carries
     * @param absent  the column names the table excludes
     * @return one check per excluded name
     */
    private static List<Executable> absenceChecks(String table, List<String> present,
            List<String> absent) {
        List<Executable> checks = new ArrayList<>(absent.size());
        for (String name : absent) {
            checks.add(() -> assertFalse(present.contains(name),
                    () -> table + " carries the column " + name));
        }
        return checks;
    }

    /**
     * Reads one timestamp column as an instant.
     *
     * @param sql  a query returning one row and one timestamp column
     * @param args the query arguments
     * @return the stored instant
     */
    private Instant instant(String sql, Object... args) {
        OffsetDateTime stored = jdbc.queryForObject(sql, OffsetDateTime.class, args);
        return stored == null ? null : stored.toInstant();
    }

    /**
     * Writes one window row for the fixture account.
     *
     * @param windowStart inclusive lower bound of the bucket, the second key part
     * @param total       amount total the row holds
     */
    private void insertWindow(Instant windowStart, BigDecimal total) {
        jdbc.update("INSERT INTO " + qualified(VELOCITY_WINDOW) + " (" + COLUMN_ACCOUNT_ID + ", "
                        + COLUMN_WINDOW_START + ", " + COLUMN_AUTHORIZATION_COUNT + ", "
                        + COLUMN_TOTAL_AMOUNT + ", " + COLUMN_UPDATED_AT + ")"
                        + " VALUES (?, ?, ?, ?, ?)",
                ACCOUNT_ID, atUtc(windowStart), WINDOW_COUNT, total, atUtc(WINDOW_UPDATED_AT));
    }

    /**
     * Reads the total of one window row of the fixture account.
     *
     * @param windowStart inclusive lower bound of the bucket
     * @return the stored total
     */
    private BigDecimal totalAt(Instant windowStart) {
        return jdbc.queryForObject("SELECT " + COLUMN_TOTAL_AMOUNT + " FROM "
                        + qualified(VELOCITY_WINDOW) + " WHERE " + COLUMN_ACCOUNT_ID + " = ?"
                        + " AND " + COLUMN_WINDOW_START + " = ?",
                BigDecimal.class, ACCOUNT_ID, atUtc(windowStart));
    }

    /**
     * Saves one assessment through the repository and flushes it.
     *
     * @param transactionId  key of the row, sixteen characters
     * @param riskScore      score the row holds
     * @param triggeredRules rule identifiers in evaluation order, empty for a cleared verdict
     * @return the saved row
     */
    private FraudAssessmentEntity saveAssessment(String transactionId, int riskScore,
            List<String> triggeredRules) {
        return assessments.saveAndFlush(new FraudAssessmentEntity(transactionId, ACCOUNT_ID,
                riskScore, !triggeredRules.isEmpty(), triggeredRules, ASSESSED_AT));
    }

    /**
     * Writes one assessment row with a stored rule-list value the entity does not build.
     *
     * @param transactionId key of the row, sixteen characters
     * @param storedRules   value the rule-list column takes verbatim
     * @param flagged       threshold-based verdict the row carries
     */
    private void insertAssessment(String transactionId, String storedRules, boolean flagged) {
        jdbc.update("INSERT INTO " + qualified(FRAUD_ASSESSMENT) + " (" + COLUMN_TRANSACTION_ID
                        + ", " + COLUMN_ACCOUNT_ID + ", " + COLUMN_RISK_SCORE + ", "
                        + COLUMN_FLAGGED + ", " + COLUMN_TRIGGERED_RULES + ", "
                        + COLUMN_ASSESSED_AT + ") VALUES (?, ?, ?, ?, ?, ?)",
                transactionId, ACCOUNT_ID, RAW_RISK_SCORE, flagged, storedRules,
                atUtc(ASSESSED_AT));
    }

    /**
     * Reads the rule-list column of one assessment row without the converter.
     *
     * @param transactionId key of the row
     * @return the stored value
     */
    private String storedRules(String transactionId) {
        return jdbc.queryForObject("SELECT " + COLUMN_TRIGGERED_RULES + " FROM "
                        + qualified(FRAUD_ASSESSMENT) + " WHERE " + COLUMN_TRANSACTION_ID + " = ?",
                String.class, transactionId);
    }

    /**
     * Reads one assessment row back through the repository, so the converter runs.
     *
     * @param transactionId key of the row
     * @return the rule identifiers the converter returns
     */
    private List<String> reloadRules(String transactionId) {
        return assessments.findById(transactionId)
                .orElseThrow(() ->
                        new AssertionError(FRAUD_ASSESSMENT + " holds no row for the key"))
                .getTriggeredRules();
    }

    /**
     * Returns the column mapping of the money field of the window entity.
     *
     * @return the column annotation naming {@code total_amount}
     */
    private static Column moneyColumn() {
        for (Field field : VelocityWindowEntity.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null && COLUMN_TOTAL_AMOUNT.equals(column.name())) {
                return column;
            }
        }
        throw new AssertionError("the window entity maps no " + COLUMN_TOTAL_AMOUNT + " column");
    }

    /**
     * Finds the one nested key class of the window entity.
     *
     * @return the nested class a persistence identifier class implements
     */
    private static Class<?> nestedKeyClass() {
        List<Class<?>> nested = new ArrayList<>();
        for (Class<?> candidate : VelocityWindowEntity.class.getDeclaredClasses()) {
            if (Serializable.class.isAssignableFrom(candidate)) {
                nested.add(candidate);
            }
        }
        assertEquals(1, nested.size(),
                () -> "nested serializable classes of the window entity: " + nested);
        return nested.get(0);
    }

    /**
     * Finds the one nested converter class of the assessment entity.
     *
     * @return the nested class implementing the Jakarta Persistence attribute-converter contract
     */
    private static Class<?> nestedConverterClass() {
        List<Class<?>> nested = new ArrayList<>();
        for (Class<?> candidate : FraudAssessmentEntity.class.getDeclaredClasses()) {
            if (AttributeConverter.class.isAssignableFrom(candidate)) {
                nested.add(candidate);
            }
        }
        assertEquals(1, nested.size(),
                () -> "nested attribute converters of the assessment entity: " + nested);
        return nested.get(0);
    }

    /**
     * Finds the one field of the assessment entity that names a converter.
     *
     * @return the field carrying an explicit convert annotation
     */
    private static Field convertedField() {
        List<Field> converted = new ArrayList<>();
        for (Field field : FraudAssessmentEntity.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(Convert.class)) {
                converted.add(field);
            }
        }
        assertEquals(1, converted.size(),
                () -> "fields of the assessment entity naming a converter: " + converted);
        return converted.get(0);
    }


    /** A moment far enough back that every retention horizon below follows it. */
    private static final Instant RETENTION_EXPIRED_AT = Instant.parse("2020-01-01T00:00:00Z");

    /** The horizon the retention checks apply, which every expired row above precedes. */
    private static final Instant RETENTION_HORIZON = Instant.parse("2021-01-01T00:00:00Z");

    /** Rows the bounded retention delete removes per statement when a check wants them all. */
    private static final int RETENTION_BATCH_SIZE = 1000;

    /**
     * Runs the bounded marker delete against the migrated schema, then reads the result back.
     *
     * <p>{@code ddl-auto: validate} reads no {@code @Query} text, so a native statement is unchecked
     * until it runs. {@code outbox/RetentionSweeper} owns this call.
     */
    @Test
    @DisplayName("The marker retention delete takes the expired marker and keeps the newer one")
    void markerRetentionDeleteTakesOnlyExpiredMarkers() {
        UUID expired = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
        UUID recent = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
        jdbc.update("DELETE FROM " + qualified(PROCESSED_EVENT));
        transactionTemplate.executeWithoutResult(status -> {
            markers.claimEvent(expired, RETENTION_EXPIRED_AT, CONSUMED_TOPIC);
            markers.claimEvent(recent, RETENTION_HORIZON.plusSeconds(60), CONSUMED_TOPIC);
        });
        assertEquals(2L, markerRowsCarrying(expired) + markerRowsCarrying(recent),
                "both markers are present before the delete, so the counts below read the delete "
                        + "and not the set-up");

        int removed = transactionTemplate.execute(status ->
                markers.deleteMarkersProcessedBefore(RETENTION_HORIZON, RETENTION_BATCH_SIZE));

        assertAll("the bounded marker delete",
                () -> assertEquals(1, removed, "one marker precedes the horizon"),
                () -> assertEquals(0L, markerRowsCarrying(expired), "the expired marker is gone"),
                () -> assertEquals(1L, markerRowsCarrying(recent),
                        "a marker inside the horizon stays, so its redelivery is still refused"));
    }

    /**
     * Runs the bounded outbox delete against the migrated schema.
     *
     * <p>The rows are written through the driver: the physical contract of the outbox table belongs
     * to {@code OutboxAtomicityIT}, and no assertion here repeats it.
     */
    @Test
    @DisplayName("The outbox retention delete takes a published row past the horizon and never an "
            + "unpublished one")
    void outboxRetentionDeleteTakesOnlyPublishedRowsPastTheHorizon() {
        UUID expired = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
        UUID recent = UUID.fromString("00000000-0000-4000-8000-0000000000c2");
        UUID unpublished = UUID.fromString("00000000-0000-4000-8000-0000000000c3");
        jdbc.update("DELETE FROM " + qualified("outbox_event"));
        storePublishedOutboxRow(expired, RETENTION_EXPIRED_AT);
        storePublishedOutboxRow(recent, RETENTION_HORIZON.plusSeconds(60));
        storeUnpublishedOutboxRow(unpublished);

        int removed = transactionTemplate.execute(status ->
                context.getBean(com.carddemo.fraud.repository.OutboxEventRepository.class)
                        .deletePublishedBefore(RETENTION_HORIZON, RETENTION_BATCH_SIZE));

        assertAll("the bounded outbox delete",
                () -> assertEquals(1, removed, "one published row precedes the horizon"),
                () -> assertEquals(0L, outboxRowsCarrying(expired), "the expired row is gone"),
                () -> assertEquals(1L, outboxRowsCarrying(recent),
                        "a published row inside the horizon stays"),
                () -> assertEquals(1L, outboxRowsCarrying(unpublished),
                        "the relay has not published this row, so retention must not take it"));
        jdbc.update("DELETE FROM " + qualified("outbox_event"));
    }

    /**
     * Writes one published outbox row through the driver.
     *
     * @param eventId     the row key
     * @param publishedAt the publication instant, which the row also records as its creation
     */
    private void storePublishedOutboxRow(UUID eventId, Instant publishedAt) {
        jdbc.update("INSERT INTO " + qualified("outbox_event")
                        + " (event_id, event_type, aggregate_id, payload, created_at, "
                        + "next_attempt_at, published, published_at, relay_state) "
                        + "VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, 'PUBLISHED')",
                eventId, "FraudFlagged", ACCOUNT_ID, "{}", atUtc(publishedAt), atUtc(publishedAt),
                atUtc(publishedAt));
    }

    /**
     * Writes one unpublished outbox row through the driver.
     *
     * @param eventId the row key
     */
    private void storeUnpublishedOutboxRow(UUID eventId) {
        jdbc.update("INSERT INTO " + qualified("outbox_event")
                        + " (event_id, event_type, aggregate_id, payload, created_at, "
                        + "next_attempt_at) VALUES (?, ?, ?, ?, ?, ?)",
                eventId, "FraudFlagged", ACCOUNT_ID, "{}", atUtc(RETENTION_EXPIRED_AT),
                atUtc(RETENTION_EXPIRED_AT));
    }

    /**
     * Counts marker rows carrying one identifier, read through the driver so no persistence-context
     * copy can answer instead of the table.
     *
     * @param eventId the identifier to count
     * @return 1 when the marker is present, and 0 when it is not
     */
    private long markerRowsCarrying(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + qualified(PROCESSED_EVENT)
                + " WHERE event_id = ?", Long.class, eventId);
    }

    /**
     * Counts outbox rows carrying one identifier.
     *
     * @param eventId the identifier to count
     * @return 1 when the row is present, and 0 when it is not
     */
    private long outboxRowsCarrying(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + qualified("outbox_event")
                + " WHERE event_id = ?", Long.class, eventId);
    }

    /**
     * Runs the bounded velocity delete against the migrated schema. The statement names a composite
     * key, which is the shape most likely to be rejected by a dialect.
     */
    @Test
    @DisplayName("The velocity retention delete takes the expired window and honours its limit")
    void velocityRetentionDeleteTakesExpiredWindowsWithinItsLimit() {
        jdbc.update("DELETE FROM " + qualified(VELOCITY_WINDOW));
        transactionTemplate.executeWithoutResult(status -> {
            windows.save(new VelocityWindowEntity(ACCOUNT_ID, RETENTION_EXPIRED_AT, 1,
                    new BigDecimal("10.00"), RETENTION_EXPIRED_AT));
            windows.save(new VelocityWindowEntity(ACCOUNT_ID, RETENTION_EXPIRED_AT.plusSeconds(1), 1,
                    new BigDecimal("10.00"), RETENTION_EXPIRED_AT.plusSeconds(1)));
            windows.save(new VelocityWindowEntity(ACCOUNT_ID, RETENTION_HORIZON.plusSeconds(60), 1,
                    new BigDecimal("10.00"), RETENTION_HORIZON.plusSeconds(60)));
        });

        int firstStatement = transactionTemplate.execute(status ->
                windows.deleteWindowsStartedBefore(RETENTION_HORIZON, 1));
        int secondStatement = transactionTemplate.execute(status ->
                windows.deleteWindowsStartedBefore(RETENTION_HORIZON, RETENTION_BATCH_SIZE));
        int thirdStatement = transactionTemplate.execute(status ->
                windows.deleteWindowsStartedBefore(RETENTION_HORIZON, RETENTION_BATCH_SIZE));

        assertEquals(1, firstStatement, "the limit bounds one statement");
        assertEquals(1, secondStatement, "the remaining expired window follows");
        assertEquals(0, thirdStatement,
                "the sweeper stops on a count below the batch size, and the live window stays");
        assertEquals(1L, windows.count(), "the window inside the horizon is untouched");
    }

    /**
     * Places one instant at Coordinated Universal Time, the form the driver maps onto the column.
     *
     * @param instant the instant to send
     * @return the same instant at a zero offset
     */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
