package com.carddemo.fraud.outbox;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudFlagged;
import com.carddemo.fraud.FraudApplication;
import com.carddemo.fraud.entity.FraudAssessmentEntity;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.repository.FraudAssessmentRepository;
import com.carddemo.fraud.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that one fraud assessment row and its {@code outbox_event} row commit together or not at
 * all. A scheduled relay marks the committed row published and leaves the row in place.
 * {@code outbox_event} carries the fourteen columns, four indexes and primary key the migration
 * declares.
 *
 * <p>The fraud detection service is net new; no COBOL ancestor exists, and no Common Business
 * Oriented Language program under {@code app/cbl/} scores risk. One PostgreSQL container carries
 * the migrated schema and one in-process broker accepts what the relay sends. The four credential
 * values below stand in, inert, for variables the shipped configuration leaves undefined.
 *
 * <p>The test framework rolls nothing back here. Every write runs through a
 * {@link TransactionTemplate} and every read taken after a commit through a {@link JdbcTemplate}.
 * The publish path is drawn in {@code card-platform/docs/event-flow.md}.
 */
@SpringBootTest(
        classes = FraudApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "spring.kafka.security.protocol=PLAINTEXT",
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
                "USER_PASSWORD_HASH={noop}not-a-real-user-password",
                "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password"
        })
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"transaction.authorized", "fraud.assessed", "carddemo.dead-letter"})
@DisplayName("Fraud assessment and outbox rows commit together, publish once and keep their shape")
class OutboxAtomicityIT {

    /** Image tag of the database container. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** Database name, login name and password of the container, one value for all three. */
    private static final String CONTAINER_CREDENTIAL = "carddemo";

    /** Schema Flyway migrates into, and the schema every catalogue query below names. */
    private static final String MIGRATED_SCHEMA = "fraud_service";

    /** Property naming what the persistence layer does with the schema at start-up. */
    private static final String DDL_AUTO_PROPERTY = "spring.jpa.hibernate.ddl-auto";

    /** Value {@link #DDL_AUTO_PROPERTY} resolves to. */
    private static final String SCHEMA_VALIDATION = "validate";

    private static final String OUTBOX_EVENT = "outbox_event";
    private static final String FRAUD_ASSESSMENT = "fraud_assessment";

    private static final String PK_OUTBOX_EVENT = "pk_outbox_event";
    private static final String IX_OUTBOX_EVENT_PENDING = "ix_outbox_event_pending";
    private static final String IX_OUTBOX_EVENT_CLAIMABLE = "ix_outbox_event_claimable";
    private static final String IX_OUTBOX_EVENT_PUBLISHED_AT = "ix_outbox_event_published_at";

    private static final String EVENT_ID = "event_id";
    private static final String EVENT_TYPE = "event_type";
    private static final String AGGREGATE_ID = "aggregate_id";
    private static final String PAYLOAD = "payload";
    private static final String PUBLISHED = "published";
    private static final String CREATED_AT = "created_at";
    private static final String RELAY_STATE = "relay_state";
    private static final String ATTEMPT_COUNT = "attempt_count";
    private static final String NEXT_ATTEMPT_AT = "next_attempt_at";
    private static final String LAST_ATTEMPT_AT = "last_attempt_at";
    private static final String LAST_ERROR = "last_error";
    private static final String CLAIMED_BY = "claimed_by";
    private static final String CLAIMED_AT = "claimed_at";
    private static final String PUBLISHED_AT = "published_at";
    private static final String TRANSACTION_ID = "transaction_id";

    private static final String TYPE_UUID = "uuid";
    private static final String TYPE_CHARACTER = "character";
    private static final String TYPE_CHARACTER_VARYING = "character varying";
    private static final String TYPE_TEXT = "text";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_TIMESTAMP_WITH_TIME_ZONE = "timestamp with time zone";

    /** Fractional-second digits every instant column of the migration declares. */
    private static final Integer TIMESTAMP_PRECISION = 6;

    /** What the catalogue reports for a column that accepts no null. */
    private static final String NOT_NULLABLE = "NO";

    /** What the catalogue reports for a column that accepts a null. */
    private static final String NULLABLE = "YES";

    /** Default the catalogue reports for {@link #PUBLISHED}. */
    private static final String FALSE_DEFAULT = "false";

    /** Default the catalogue reports for {@link #ATTEMPT_COUNT}. */
    private static final String ZERO_DEFAULT = "0";

    /** Default the catalogue reports for {@link #RELAY_STATE}. */
    private static final String PENDING_DEFAULT =
            "'" + OutboxEventEntity.RelayState.PENDING + "'::" + TYPE_CHARACTER_VARYING;

    /** Attempts a row records once one publish has succeeded. */
    private static final Integer NO_FAILED_ATTEMPT = 0;

    /** Rows the catalogue reports for one table this schema holds. */
    private static final long ONE_TABLE = 1L;

    /** Rows one committed transaction leaves behind under one key. */
    private static final long ONE_ROW = 1L;

    /** Rows one rolled-back transaction leaves behind under one key. */
    private static final long NO_ROW = 0L;

    /** Names {@code outbox_event} does not carry, each named in its own failure message. */
    private static final List<String> ABSENT_COLUMNS = List.of("status", "retry_count",
            "error_message", "topic", "partition", "offset", "headers", "correlation_id",
            "schema_version", "aggregate_type");

    /** Account every row below belongs to, eleven digits with leading zeros kept. */
    private static final String ACCOUNT_ID = "00000000007";

    /** Transaction the committed assessment covers, sixteen characters. */
    private static final String COMMITTED_TRANSACTION_ID = "0000000000683580";

    /** Transaction the rolled-back assessment covers, sixteen characters. */
    private static final String ROLLED_BACK_TRANSACTION_ID = "0000000000683581";

    /** Transaction the relayed assessment covers, sixteen characters. */
    private static final String RELAYED_TRANSACTION_ID = "0000000000683582";

    /** Instant every event below carries as its publish time. */
    private static final Instant OCCURRED_AT = Instant.parse("2022-06-10T19:27:53Z");

    /** Instant the rules finished, one second after {@link #OCCURRED_AT}. */
    private static final Instant ASSESSED_AT = Instant.parse("2022-06-10T19:27:54Z");

    /** Score every assessment below carries, a whole number inside the nought-to-hundred range. */
    private static final int RISK_SCORE = 72;

    /** Rules every assessment below names, in evaluation order. */
    private static final List<String> TRIGGERED_RULES =
            List.of(FraudFlagged.VELOCITY_RULE, FraudFlagged.AMOUNT_ANOMALY_RULE);

    /**
     * Universally Unique Identifier (UUID) of the event the commit test writes. One test method
     * writes one identifier, so every assertion below reads its own row by primary key.
     */
    private static final UUID COMMITTED_EVENT_ID =
            UUID.fromString("7c3a5b2e-4d16-4f8a-b0c5-2e7d6a4f8b31");

    /** Identifier of the event the rollback test writes. */
    private static final UUID ROLLED_BACK_EVENT_ID =
            UUID.fromString("2f8b6d40-5c71-4a23-8e6f-3b5d7c2a4e68");

    /** Identifier of the event the relay test writes. */
    private static final UUID RELAYED_EVENT_ID =
            UUID.fromString("5a1e8c37-6b24-4d0f-9a83-7c4b2e6d8f50");

    /** Longest this class waits for the relay to mark one row. */
    private static final Duration RELAY_WAIT_TIMEOUT = Duration.ofSeconds(30);

    /** Gap between two reads of the row the relay is expected to mark. */
    private static final Duration RELAY_POLL_INTERVAL = Duration.ofMillis(250);

    /** The one database container every test method in this class shares. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(CONTAINER_CREDENTIAL)
            .withUsername(CONTAINER_CREDENTIAL)
            .withPassword(CONTAINER_CREDENTIAL);

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private FraudAssessmentRepository assessments;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    /**
     * Points the datasource at the container and overrides nothing else about it.
     *
     * @param registry registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OutboxAtomicityIT::jdbcUrlOnMigratedSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container connection string with the migrated schema on its search path.
     *
     * @return the Java Database Connectivity (JDBC) URL every bean of this context connects through
     */
    private static String jdbcUrlOnMigratedSchema() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + MIGRATED_SCHEMA;
    }

    /**
     * The read taken inside the callback sees an unpublished row, which no relay tick on another
     * thread can reach while that transaction is open.
     */
    @Test
    @DisplayName("one commit leaves the assessment row and its unpublished outbox row")
    void bothRowsCommitTogether() {
        OutboxEventEntity written = transactions.execute(status -> {
            assessments.save(assessment(COMMITTED_TRANSACTION_ID));
            OutboxEventEntity row =
                    writer.write(flagged(COMMITTED_EVENT_ID, COMMITTED_TRANSACTION_ID));
            outboxEvents.flush();

            Map<String, Object> pending = outboxRow(COMMITTED_EVENT_ID);
            assertNotNull(pending, "the flush placed no row in " + OUTBOX_EVENT);
            assertAll("the row the open transaction reads back",
                    () -> assertEquals(Boolean.FALSE, pending.get(PUBLISHED),
                            "the writer published a row it had only inserted"),
                    () -> assertNull(pending.get(PUBLISHED_AT),
                            "an unpublished row carries a publication instant"));
            return row;
        });

        assertNotNull(written, "the writer answered with no row");
        Map<String, Object> committed = outboxRow(COMMITTED_EVENT_ID);
        assertNotNull(committed, OUTBOX_EVENT + " holds no row for the committed event");
        assertEquals(ONE_ROW, assessmentRowCount(COMMITTED_TRANSACTION_ID),
                FRAUD_ASSESSMENT + " holds no row for the committed transaction");
        assertAll("the outbox row one commit left",
                () -> assertEquals(COMMITTED_EVENT_ID, committed.get(EVENT_ID), EVENT_ID),
                () -> assertEquals(FraudFlagged.EVENT_TYPE, committed.get(EVENT_TYPE), EVENT_TYPE),
                () -> assertEquals(ACCOUNT_ID, committed.get(AGGREGATE_ID),
                        AGGREGATE_ID + " dropped a leading zero"),
                () -> assertEquals(written.getPayload(), committed.get(PAYLOAD), PAYLOAD));

        Instant createdAt = instantColumn(COMMITTED_EVENT_ID, CREATED_AT);
        assertNotNull(createdAt, CREATED_AT + " is absent");
        assertAll("the instant the row records for itself",
                () -> assertFalse(OCCURRED_AT.equals(createdAt),
                        CREATED_AT + " repeats the publish instant of the envelope"),
                () -> assertFalse(ASSESSED_AT.equals(createdAt),
                        CREATED_AT + " repeats the instant the rules finished"));
    }

    /**
     * The flush inside the callback sends both inserts to the database, and the failure thrown
     * after it leaves neither row behind.
     */
    @Test
    @DisplayName("a failure forced inside the transaction leaves neither row")
    void forcedFailureLeavesNeitherRow() {
        assertThrows(ForcedFailure.class, () -> transactions.executeWithoutResult(status -> {
            assessments.save(assessment(ROLLED_BACK_TRANSACTION_ID));
            writer.write(flagged(ROLLED_BACK_EVENT_ID, ROLLED_BACK_TRANSACTION_ID));
            outboxEvents.flush();
            throw new ForcedFailure();
        }));

        assertAll("what the rolled-back transaction left behind",
                () -> assertEquals(NO_ROW, assessmentRowCount(ROLLED_BACK_TRANSACTION_ID),
                        "an assessment row survived the rollback"),
                () -> assertEquals(NO_ROW, outboxRowCount(ROLLED_BACK_EVENT_ID),
                        "an " + OUTBOX_EVENT + " row survived the rollback"));
    }

    /**
     * One live scheduler tick publishes the committed row and marks it. A scheduler that never
     * runs leaves the row unpublished and fails the wait below.
     */
    @Test
    @DisplayName("the relay marks the row published, keeps it, and leaves its other columns alone")
    void relayFiresAndMarksPublishedWithoutDeletingTheRow() {
        OutboxEventEntity written = transactions.execute(status -> {
            assessments.save(assessment(RELAYED_TRANSACTION_ID));
            OutboxEventEntity row = writer.write(flagged(RELAYED_EVENT_ID, RELAYED_TRANSACTION_ID));
            outboxEvents.flush();
            return row;
        });
        assertNotNull(written, "the writer answered with no row");

        Awaitility.await("the relay marks the outbox row published")
                .atMost(RELAY_WAIT_TIMEOUT)
                .pollInterval(RELAY_POLL_INTERVAL)
                .until(() -> publishedFlagOf(RELAYED_EVENT_ID));

        Map<String, Object> row = outboxRow(RELAYED_EVENT_ID);
        assertNotNull(row, OUTBOX_EVENT + " no longer holds the published row");
        Instant createdAt = instantColumn(RELAYED_EVENT_ID, CREATED_AT);
        Instant publishedAt = instantColumn(RELAYED_EVENT_ID, PUBLISHED_AT);
        Instant nextAttemptAt = instantColumn(RELAYED_EVENT_ID, NEXT_ATTEMPT_AT);
        assertNotNull(createdAt, CREATED_AT + " is absent");
        assertNotNull(publishedAt, PUBLISHED_AT + " is absent on a published row");

        assertAll("the terminal state of the published row",
                () -> assertEquals(Boolean.TRUE, row.get(PUBLISHED), PUBLISHED),
                () -> assertEquals(OutboxEventEntity.RelayState.PUBLISHED.name(),
                        row.get(RELAY_STATE), RELAY_STATE),
                () -> assertFalse(publishedAt.isBefore(createdAt),
                        PUBLISHED_AT + " precedes " + CREATED_AT));

        assertAll("the columns one publish left alone",
                () -> assertEquals(RELAYED_EVENT_ID, row.get(EVENT_ID), EVENT_ID),
                () -> assertEquals(FraudFlagged.EVENT_TYPE, row.get(EVENT_TYPE), EVENT_TYPE),
                () -> assertEquals(ACCOUNT_ID, row.get(AGGREGATE_ID), AGGREGATE_ID),
                () -> assertEquals(written.getPayload(), row.get(PAYLOAD), PAYLOAD),
                () -> assertEquals(createdAt, nextAttemptAt,
                        NEXT_ATTEMPT_AT + " no longer matches " + CREATED_AT),
                () -> assertEquals(NO_FAILED_ATTEMPT, row.get(ATTEMPT_COUNT),
                        ATTEMPT_COUNT + " records an attempt that failed"),
                () -> assertNull(row.get(LAST_ATTEMPT_AT),
                        LAST_ATTEMPT_AT + " records an attempt that failed"),
                () -> assertNull(row.get(LAST_ERROR), LAST_ERROR + " records a failure"),
                () -> assertNull(row.get(CLAIMED_BY), CLAIMED_BY + " still holds a claim"),
                () -> assertNull(row.get(CLAIMED_AT), CLAIMED_AT + " still holds a claim"));
    }

    /**
     * Flyway owns the schema and the persistence layer only validates against it, so a column the
     * migration does not declare stops start-up and never fails compilation. The widths below come
     * from the entity, which schema validation does not compare.
     */
    @Test
    @DisplayName("outbox_event carries the fourteen columns, four indexes and primary key the "
            + "migration declares")
    void physicalOutboxEventSchemaContract() {
        Map<String, ColumnFact> declared = declaredColumns();
        Map<String, ColumnFact> migrated = migratedColumns(OUTBOX_EVENT);

        assertEquals(declared.keySet(), migrated.keySet(),
                OUTBOX_EVENT + " does not carry the column names the migration declares");
        assertEquals(declared, migrated, OUTBOX_EVENT + " does not carry the types, widths, "
                + "nullability and defaults the migration declares");
        assertAll("names " + OUTBOX_EVENT + " must not carry",
                ABSENT_COLUMNS.stream().<Executable>map(name -> () -> assertFalse(
                        migrated.containsKey(name),
                        () -> OUTBOX_EVENT + " carries a column named " + name)));

        Map<String, String> indexes = indexDefinitions(OUTBOX_EVENT);
        assertEquals(Set.of(PK_OUTBOX_EVENT, IX_OUTBOX_EVENT_PENDING, IX_OUTBOX_EVENT_CLAIMABLE,
                        IX_OUTBOX_EVENT_PUBLISHED_AT), indexes.keySet(),
                OUTBOX_EVENT + " does not carry the four indexes the migration declares");
        String pending = indexes.get(IX_OUTBOX_EVENT_PENDING);
        assertNotNull(pending, MIGRATED_SCHEMA + " holds no " + IX_OUTBOX_EVENT_PENDING);
        assertAll("the index the relay reads its batch from",
                () -> assertTrue(pending.contains(CREATED_AT),
                        IX_OUTBOX_EVENT_PENDING + " does not order on " + CREATED_AT),
                () -> assertTrue(pending.contains(EVENT_ID),
                        IX_OUTBOX_EVENT_PENDING + " does not break a tie on " + EVENT_ID),
                () -> assertTrue(pending.contains(PUBLISHED),
                        IX_OUTBOX_EVENT_PENDING + " does not restrict on " + PUBLISHED));

        assertEquals(List.of(new KeyColumn(PK_OUTBOX_EVENT, EVENT_ID)),
                primaryKeyColumns(OUTBOX_EVENT),
                OUTBOX_EVENT + " does not key on " + EVENT_ID + " alone");
        assertAll("what the column contract above rests on",
                () -> assertEquals(SCHEMA_VALIDATION, environment.getProperty(DDL_AUTO_PROPERTY),
                        DDL_AUTO_PROPERTY),
                () -> assertEquals(ONE_TABLE, tableCount(FRAUD_ASSESSMENT),
                        MIGRATED_SCHEMA + " holds no table named " + FRAUD_ASSESSMENT));
    }

    /**
     * Builds one flagged assessment for the fixed account, score and rule list.
     *
     * @param transactionId key of the row, sixteen characters
     * @return the assessment one test method saves
     */
    private static FraudAssessmentEntity assessment(String transactionId) {
        return new FraudAssessmentEntity(transactionId, ACCOUNT_ID, RISK_SCORE, true,
                TRIGGERED_RULES, ASSESSED_AT);
    }

    /**
     * Builds one event through the canonical record constructor, which takes the fixed
     * identifier and the two fixed instants below.
     *
     * @param eventId       identifier the outbox row takes as its primary key
     * @param transactionId transaction the rules assessed, sixteen characters
     * @return the event one test method hands to the writer
     */
    private static FraudFlagged flagged(UUID eventId, String transactionId) {
        return new FraudFlagged(eventId, FraudFlagged.EVENT_TYPE, EventEnvelope.SCHEMA_VERSION,
                OCCURRED_AT, ACCOUNT_ID, transactionId, RISK_SCORE, TRIGGERED_RULES, ASSESSED_AT,
                ACCOUNT_ID);
    }

    /**
     * Reads every column of one outbox row.
     *
     * @param eventId primary key of the row
     * @return the row as column name to value, or null when the table holds none
     */
    private Map<String, Object> outboxRow(UUID eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM "
                + qualified(OUTBOX_EVENT) + " WHERE " + EVENT_ID + " = ?", eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Reads one instant column of one outbox row.
     *
     * @param eventId primary key of the row
     * @param column  name of the column to read
     * @return the instant the column holds, or null when the column is empty
     */
    private Instant instantColumn(UUID eventId, String column) {
        OffsetDateTime value = jdbc.queryForObject("SELECT " + column + " FROM "
                        + qualified(OUTBOX_EVENT) + " WHERE " + EVENT_ID + " = ?",
                OffsetDateTime.class, eventId);
        return value == null ? null : value.toInstant();
    }

    /**
     * Reports whether one outbox row is present and published.
     *
     * @param eventId primary key of the row
     * @return true once the row carries a publication flag of true
     */
    private boolean publishedFlagOf(UUID eventId) {
        List<Boolean> flags = jdbc.queryForList("SELECT " + PUBLISHED + " FROM "
                + qualified(OUTBOX_EVENT) + " WHERE " + EVENT_ID + " = ?", Boolean.class, eventId);
        return flags.size() == 1 && Boolean.TRUE.equals(flags.get(0));
    }

    /**
     * Counts outbox rows carrying one identifier.
     *
     * @param eventId primary key to count
     * @return 1 when the row is present, and 0 when it is not
     */
    private long outboxRowCount(UUID eventId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + qualified(OUTBOX_EVENT)
                + " WHERE " + EVENT_ID + " = ?", Long.class, eventId);
        return count == null ? NO_ROW : count;
    }

    /**
     * Counts assessment rows carrying one transaction identifier.
     *
     * @param transactionId primary key to count
     * @return 1 when the row is present, and 0 when it is not
     */
    private long assessmentRowCount(String transactionId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + qualified(FRAUD_ASSESSMENT)
                + " WHERE " + TRANSACTION_ID + " = ?", Long.class, transactionId);
        return count == null ? NO_ROW : count;
    }

    /**
     * Counts tables of the migrated schema carrying one name.
     *
     * @param table name to count
     * @return 1 when the table is present, and 0 when it is not
     */
    private long tableCount(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema = ? AND table_name = ?", Long.class,
                MIGRATED_SCHEMA, table);
        return count == null ? NO_ROW : count;
    }

    /**
     * Reads the catalogue facts of every column of one table, in declaration order.
     *
     * @param table name of the table to read
     * @return column name to the type, width, precision, nullability and default it carries
     */
    private Map<String, ColumnFact> migratedColumns(String table) {
        Map<String, ColumnFact> facts = new LinkedHashMap<>();
        for (Map<String, Object> column : jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length, datetime_precision,"
                        + " is_nullable, column_default FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = ?"
                        + " ORDER BY ordinal_position", MIGRATED_SCHEMA, table)) {
            facts.put((String) column.get("column_name"), new ColumnFact(
                    (String) column.get("data_type"),
                    (Integer) column.get("character_maximum_length"),
                    (Integer) column.get("datetime_precision"),
                    (String) column.get("is_nullable"),
                    (String) column.get("column_default")));
        }
        return facts;
    }

    /**
     * Returns the fourteen columns {@code V1__schema.sql} declares for {@code outbox_event}, in
     * declaration order.
     *
     * @return column name to the type, width, precision, nullability and default declared for it
     */
    private static Map<String, ColumnFact> declaredColumns() {
        Map<String, ColumnFact> declared = new LinkedHashMap<>();
        declared.put(EVENT_ID, new ColumnFact(TYPE_UUID, null, null, NOT_NULLABLE, null));
        declared.put(EVENT_TYPE, new ColumnFact(TYPE_CHARACTER_VARYING,
                OutboxEventEntity.EVENT_TYPE_MAX_LENGTH, null, NOT_NULLABLE, null));
        declared.put(AGGREGATE_ID, new ColumnFact(TYPE_CHARACTER,
                OutboxEventEntity.AGGREGATE_ID_LENGTH, null, NOT_NULLABLE, null));
        declared.put(PAYLOAD, new ColumnFact(TYPE_TEXT, null, null, NOT_NULLABLE, null));
        declared.put(PUBLISHED,
                new ColumnFact(TYPE_BOOLEAN, null, null, NOT_NULLABLE, FALSE_DEFAULT));
        declared.put(CREATED_AT, new ColumnFact(TYPE_TIMESTAMP_WITH_TIME_ZONE, null,
                TIMESTAMP_PRECISION, NOT_NULLABLE, null));
        declared.put(RELAY_STATE, new ColumnFact(TYPE_CHARACTER_VARYING,
                OutboxEventEntity.RELAY_STATE_MAX_LENGTH, null, NOT_NULLABLE, PENDING_DEFAULT));
        declared.put(ATTEMPT_COUNT,
                new ColumnFact(TYPE_INTEGER, null, null, NOT_NULLABLE, ZERO_DEFAULT));
        declared.put(NEXT_ATTEMPT_AT, new ColumnFact(TYPE_TIMESTAMP_WITH_TIME_ZONE, null,
                TIMESTAMP_PRECISION, NOT_NULLABLE, null));
        declared.put(LAST_ATTEMPT_AT, new ColumnFact(TYPE_TIMESTAMP_WITH_TIME_ZONE, null,
                TIMESTAMP_PRECISION, NULLABLE, null));
        declared.put(LAST_ERROR, new ColumnFact(TYPE_CHARACTER_VARYING,
                OutboxEventEntity.LAST_ERROR_MAX_LENGTH, null, NULLABLE, null));
        declared.put(CLAIMED_BY, new ColumnFact(TYPE_CHARACTER_VARYING,
                OutboxEventEntity.CLAIMED_BY_MAX_LENGTH, null, NULLABLE, null));
        declared.put(CLAIMED_AT, new ColumnFact(TYPE_TIMESTAMP_WITH_TIME_ZONE, null,
                TIMESTAMP_PRECISION, NULLABLE, null));
        declared.put(PUBLISHED_AT, new ColumnFact(TYPE_TIMESTAMP_WITH_TIME_ZONE, null,
                TIMESTAMP_PRECISION, NULLABLE, null));
        return declared;
    }

    /**
     * Reads the name and the definition of every index of one table.
     *
     * @param table name of the table to read
     * @return index name to the statement the catalogue reports for it
     */
    private Map<String, String> indexDefinitions(String table) {
        Map<String, String> definitions = new LinkedHashMap<>();
        for (Map<String, Object> index : jdbc.queryForList("SELECT indexname, indexdef"
                        + " FROM pg_indexes WHERE schemaname = ? AND tablename = ?"
                        + " ORDER BY indexname", MIGRATED_SCHEMA, table)) {
            definitions.put((String) index.get("indexname"), (String) index.get("indexdef"));
        }
        return definitions;
    }

    /**
     * Reads the primary-key columns of one table, in key order.
     *
     * @param table name of the table to read
     * @return one entry per key column, each naming the constraint that carries it
     */
    private List<KeyColumn> primaryKeyColumns(String table) {
        return jdbc.queryForList("SELECT keys.constraint_name, keys.column_name"
                        + " FROM information_schema.table_constraints AS constraints"
                        + " JOIN information_schema.key_column_usage AS keys"
                        + " ON keys.constraint_name = constraints.constraint_name"
                        + " AND keys.table_schema = constraints.table_schema"
                        + " WHERE constraints.table_schema = ? AND constraints.table_name = ?"
                        + " AND constraints.constraint_type = 'PRIMARY KEY'"
                        + " ORDER BY keys.ordinal_position", MIGRATED_SCHEMA, table)
                .stream()
                .map(key -> new KeyColumn((String) key.get("constraint_name"),
                        (String) key.get("column_name")))
                .toList();
    }

    /**
     * Places one table name in the migrated schema.
     *
     * @param table unqualified table name
     * @return the schema-qualified name, which a {@link JdbcTemplate} does not resolve on its own
     */
    private static String qualified(String table) {
        return MIGRATED_SCHEMA + "." + table;
    }

    /**
     * One column of the catalogue, as five facts.
     *
     * @param dataType           type the catalogue reports
     * @param maxLength          character width, or null for a type that carries none
     * @param datetimePrecision  fractional-second digits, or null for a type that carries none
     * @param nullable           {@code YES} when the column accepts a null, {@code NO} when not
     * @param columnDefault      default the catalogue reports, or null when the column has none
     */
    private record ColumnFact(String dataType, Integer maxLength, Integer datetimePrecision,
            String nullable, String columnDefault) {
    }

    /**
     * One primary-key column and the constraint that carries it.
     *
     * @param constraintName name of the key constraint
     * @param columnName     name of the column the constraint keys on
     */
    private record KeyColumn(String constraintName, String columnName) {
    }

    /** The failure one test method throws inside a transaction so that transaction rolls back. */
    private static final class ForcedFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}


