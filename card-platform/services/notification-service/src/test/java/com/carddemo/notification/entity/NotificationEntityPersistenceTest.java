package com.carddemo.notification.entity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.carddemo.cobol.PanMasker;
import com.carddemo.notification.TestIdentityPasswords;
import com.carddemo.notification.entity.ProcessedEventEntity.ProcessedEventId;
import com.carddemo.notification.entity.StatementTransactionEntity.StatementTransactionId;
import com.carddemo.notification.repository.CardholderContextRepository;
import com.carddemo.notification.repository.NotificationLogRepository;
import com.carddemo.notification.repository.ProcessedEventRepository;
import com.carddemo.notification.repository.StatementTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the three notification entities match the migrated schema, and proves a second delivery of
 * one event updates its read-model row without adding one.
 *
 * <p>The boot is the proof. {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in
 * {@code src/main/resources/application.yml}, and Flyway applies
 * {@code src/main/resources/db/migration/V1__schema.sql}. Flyway creates the schema, then Hibernate
 * validates every Jakarta Persistence (JPA) mapping against the resulting catalogue, so a mapping
 * that drifts from the Data Definition Language (DDL) stops start-up. A JPA {@code String} field
 * maps to {@code varchar} by default while the migration declares {@code CHAR(n)} for eleven
 * {@code statement_transaction} columns, and only a booted context catches that.
 *
 * <p>The sibling tests {@code StatementTransactionEntityTest} and {@code ProcessedEventEntityTest}
 * own the reflection-level assertions. Every assertion here reads the live catalogue through Java
 * Database Connectivity (JDBC), or writes a row and reads it back.
 *
 * <p>Four sources supply the values below, each a Common Business Oriented Language (COBOL) or Job
 * Control Language (JCL) member. The record {@code 01 TRNX-RECORD.} at
 * {@code app/cpy/COSTM01.CPY:L20} gives the columns, and its amount field
 * {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29} gives the scale. The key
 * parameter {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30} gives the composite key. The
 * layout at {@code app/cbl/CBTRN02C.cbl:L159-L174}, filled at
 * {@code app/cbl/CBTRN02C.cbl:L701}, gives the processing-timestamp shape. The duplicate-key abend
 * path at {@code app/cbl/CBTRN02C.cbl:L562-L579} is what the upsert closes.
 *
 * <p>Agent Action Plan section 0.5.1 pins every version this class names: {@code postgres:18.4},
 * Testcontainers 2.0.5, JUnit Jupiter 6.0.3 and Java 25.
 *
 * <p>Design decisions, the additive {@code processed_event} marker and the masked stored card
 * number among them: {@code card-platform/docs/decision-log.md}. Source-to-target
 * mapping, the dropped trailing filler among it:
 * {@code card-platform/docs/traceability-matrix.md}. Flagged source findings:
 * {@code card-platform/docs/business-rule-flags.md}. The masked and the raw card-number
 * form both fitting {@code CHAR(16)}: {@code card-platform/docs/suggested-next-tasks.md}.
 */
@Testcontainers
@SpringBootTest(properties = {
    // The three listeners this module runs would retry an absent broker for the life of the run.
    "spring.kafka.listener.auto-startup=false",
    // One of the four credentials application.yml leaves without a default, so a context can start.
    // config/SecurityConfig refuses a blank or published value at start-up, and SecurityConfigTest
    // asserts that refusal. The value below is a fake this repository states nowhere else. The three
    // identity hashes arrive from card-platform/pom.xml, because the same guard refuses a password
    // that is not the output of an adaptive one-way encoder.
    "KAFKA_SASL_PASSWORD=a-generated-broker-value-for-the-persistence-test",
    "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
    "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
    "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
})
@DisplayName("The notification schema, its four entities, and the read-model upsert")
class NotificationEntityPersistenceTest {

    // ---------------------------------------------------------------------------------------
    // Container. Agent Action Plan section 0.5.1 pins the image tag and Testcontainers 2.0.5.
    // ---------------------------------------------------------------------------------------

    /** Database image this class starts, pinned by Agent Action Plan section 0.5.1. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /**
     * Schema every connection of this context searches, and the default
     * {@code src/main/resources/application.yml} names under
     * {@code spring.jpa.properties.hibernate.default_schema}.
     */
    private static final String MIGRATED_SCHEMA = "notification_service";

    /** Database, login and password of the disposable container. */
    private static final String DATABASE_CREDENTIAL = "carddemo";

    /**
     * One database for every test method in this class.
     *
     * <p>Testcontainers starts the image and Flyway migrates it, so {@code mvn test} needs a
     * running Docker daemon and no hand-made database, schema or environment variable. The field is
     * {@code static}, so one container and one cached Spring context serve every method below.</p>
     */
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(DATABASE_CREDENTIAL)
            .withUsername(DATABASE_CREDENTIAL)
            .withPassword(DATABASE_CREDENTIAL);

    /**
     * Points the datasource at the container.
     *
     * <p>These three keys are the only datasource settings this class supplies.
     * {@code spring.jpa.hibernate.ddl-auto}, every {@code spring.flyway} key and
     * {@code hibernate.default_schema} keep the values {@code application.yml} declares.</p>
     *
     * <p>The uniform resource locator carries {@code currentSchema}, which sets the search path of
     * every connection. {@code hibernate.default_schema} qualifies a mapped query and leaves a
     * native statement unqualified, so the native claim and the native context upsert resolve
     * against the search path alone.</p>
     *
     * @param registry the registry the test context reads the three values from
     */
    @DynamicPropertySource
    static void bindContainerCoordinates(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", NotificationEntityPersistenceTest::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container locator with {@code currentSchema} appended.
     *
     * <p>{@code PostgreSQLContainer.getJdbcUrl()} already carries a query string, so the separator
     * is chosen from what the locator holds.</p>
     *
     * @return the locator every connection of this context opens
     */
    private static String migratedSchemaUrl() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "currentSchema=" + MIGRATED_SCHEMA;
    }

    // ---------------------------------------------------------------------------------------
    // Expected catalogue. Every value comes from V1__schema.sql.
    // ---------------------------------------------------------------------------------------

    /** The read model taken from {@code app/cpy/COSTM01.CPY}. */
    private static final String STATEMENT_TRANSACTION = "statement_transaction";

    /** One row per delivery attempt. Additive: no COBOL program records one. */
    private static final String NOTIFICATION_LOG = "notification_log";

    /** One row per consumed event identifier. Additive: no COBOL program detects a duplicate. */
    private static final String PROCESSED_EVENT = "processed_event";

    /** The account-keyed cardholder projection one alert reports from. */
    private static final String CARDHOLDER_CONTEXT = "cardholder_context";

    /** The four tables {@code V1__schema.sql} declares. */
    private static final Set<String> MIGRATION_TABLES = Set.of(STATEMENT_TRANSACTION,
            NOTIFICATION_LOG, PROCESSED_EVENT, CARDHOLDER_CONTEXT);

    /** Flyway's own bookkeeping table, which the migration does not declare. */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    /** A table this service does not own. It publishes no event, so it declares no relay table. */
    private static final String ABSENT_TABLE = "outbox_event";

    /**
     * Columns {@code statement_transaction} declares.
     *
     * <p>One per field of {@code 01 TRNX-RECORD}, the trailing filler dropped, plus the masked
     * display column: one source field becomes two columns, because a card token identifies the card
     * and a masked card number displays it, and neither value can do both jobs.</p>
     */
    private static final int STATEMENT_TRANSACTION_COLUMNS = 14;

    /**
     * Columns {@code notification_log} declares.
     *
     * <p>Seven since {@code db/migration/V5__rendered_not_delivered.sql} added {@code outcome}, the
     * column that carries in data what the table's prose used to claim: the alert was rendered and
     * was not sent.</p>
     */
    private static final int NOTIFICATION_LOG_COLUMNS = 7;

    /** Columns {@code cardholder_context} declares: the key, ten fields and two instants. */
    private static final int CARDHOLDER_CONTEXT_COLUMNS = 13;

    /** Columns {@code processed_event} declares. */
    private static final int PROCESSED_EVENT_COLUMNS = 3;

    /**
     * Columns in the schema that accept no value.
     *
     * <p>None do. {@code processed_event.consumed_topic} was the one, and
     * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql} made it half of
     * {@code pk_processed_event}, which a key column cannot be null for. Every column of the four
     * migrated tables therefore carries a value, which is what a read model built only from events
     * should look like: a column nothing fills is a column no consumer can bring current.</p>
     */
    private static final Set<String> NULLABLE_COLUMNS = Set.of();

    /** What {@code information_schema} reports for a {@code CHAR(n)} column. */
    private static final String FIXED_CHARACTER = "character";

    /** What {@code information_schema} reports for a {@code VARCHAR(n)} column. */
    private static final String VARYING_CHARACTER = "character varying";

    /** What {@code information_schema} reports for a {@code TIMESTAMP WITH TIME ZONE} column. */
    private static final String TIMESTAMP_WITH_ZONE = "timestamp with time zone";

    /** What {@code information_schema} reports for a {@code UUID} column. */
    private static final String UUID_TYPE = "uuid";

    /** What {@code information_schema} reports for a {@code NUMERIC(p,s)} column. */
    private static final String NUMERIC_TYPE = "numeric";

    /** Characters the transaction half of the composite key holds, and the masked card column. */
    private static final int KEY_PART_WIDTH = 16;

    /** Characters the card half of the composite key holds, from {@code PanMasker.CARD_TOKEN_LENGTH}. */
    private static final int CARD_TOKEN_WIDTH = PanMasker.CARD_TOKEN_LENGTH;

    /** Characters each timestamp column holds. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Total digits {@code amount} holds, from {@code TRNX-AMT PIC S9(09)V99}. */
    private static final int AMOUNT_PRECISION = 11;

    /** Fractional digits {@code amount} holds, from {@code TRNX-AMT PIC S9(09)V99}. */
    private static final int AMOUNT_SCALE = 2;

    /** Total digits an account-balance column holds. No column in this schema is one. */
    private static final int ACCOUNT_BALANCE_PRECISION = 12;

    // ---------------------------------------------------------------------------------------
    // Values written and read back. Every CHAR value fills its declared width, so PostgreSQL
    // pads nothing and every round-trip assertion is a strict equality.
    // ---------------------------------------------------------------------------------------

    /**
     * A card number at its stored width, composed without a committed literal.
     *
     * <p>Its four leading digits are {@code 9999}, and no card of
     * {@code app/data/ASCII/carddata.txt} begins with them.</p>
     */
    private static final String FULL_CARD = "9999" + "000000004021";

    /** A masked card number: twelve mask characters then the last four digits. */
    private static final String MASKED_CARD = PanMasker.maskCardNumber(FULL_CARD);

    /** The token of {@link #FULL_CARD}, which the key column holds. */
    private static final String CARD_TOKEN = PanMasker.cardToken(FULL_CARD);

    /** Batch bound one purge statement is given, larger than any fixture this class writes. */
    private static final int PURGE_BATCH = 1000;

    /**
     * A second card number whose last four digits are the last four of {@link #FULL_CARD}.
     *
     * <p>The two share one masked form and carry two tokens.</p>
     */
    private static final String SAME_TAIL_FULL_CARD = "9999" + "555555554021";

    /** The token of {@link #SAME_TAIL_FULL_CARD}, distinct from {@link #CARD_TOKEN}. */
    private static final String SAME_TAIL_CARD_TOKEN = PanMasker.cardToken(SAME_TAIL_FULL_CARD);

    /** The account identifier the read model keys on, at the width account_id CHAR(11) declares. */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * Key of the row the timestamp assertions write, zero-padded to the declared width.
     *
     * <p>{@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} holds sixteen characters, and
     * a leading zero counts. The value reads 9101 behind twelve zeros.</p>
     */
    private static final String TIMESTAMP_ROW_ID = zeroPadded("9101");

    /** Key of the row the upsert assertions write twice. The value reads 9102 behind zeros. */
    private static final String UPSERT_ROW_ID = zeroPadded("9102");

    /**
     * Transaction identifier both cards of the isolation assertions write under.
     *
     * <p>One identifier for two cards is what makes the card half of the key load-bearing.</p>
     */
    private static final String ISOLATION_ROW_ID = zeroPadded("9103");

    /** A space separates the date from the time, and six fractional digits follow the seconds. */
    private static final String ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /** A dash separates the date from the time, and four zero characters follow the hundredths. */
    private static final String PROCESSING_TIMESTAMP = "2022-06-10-19.27.53.120000";

    /** The horizon the retention checks apply, in the twenty-six character source form. */
    private static final String RETENTION_HORIZON_TEXT = "2025-01-01-00.00.00.000000";

    /** A marker this class writes and expects the retention delete to take. */
    private static final java.util.UUID RETENTION_EXPIRED_EVENT_ID =
            java.util.UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    /** A marker this class writes and expects the retention delete to keep. */
    private static final java.util.UUID RETENTION_RECENT_EVENT_ID =
            java.util.UUID.fromString("00000000-0000-4000-8000-0000000000b2");

    /** Shape of {@link #ORIGIN_TIMESTAMP}. */
    private static final Pattern ORIGIN_SHAPE =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}$");

    /** Shape of {@link #PROCESSING_TIMESTAMP}. */
    private static final Pattern PROCESSING_SHAPE =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0{4}$");

    /** Index of the character that separates the date from the time in both shapes. */
    private static final int SEPARATOR_INDEX = 10;

    /** Type code at its declared two characters. */
    private static final String TYPE_CODE = "01";

    /** Category code at its declared four digits. */
    private static final String CATEGORY_CODE = "0001";

    /** Merchant identifier at its declared nine digits. */
    private static final String MERCHANT_ID = "800000000";

    /** Mail code at its declared ten characters. */
    private static final String MERCHANT_ZIP = "ZIP1234567";

    /** Amount the first delivery carries. */
    private static final BigDecimal FIRST_AMOUNT = new BigDecimal("194.00");

    /** Amount the second delivery carries. */
    private static final BigDecimal SECOND_AMOUNT = new BigDecimal("276.55");

    /** Identifier the claim assertion claims twice. */
    private static final UUID CLAIMED_EVENT_ID =
            UUID.fromString("7a6f0c52-0000-4000-8000-000000009201");

    /** Topic the claimed delivery arrived on. */
    private static final String CLAIMED_TOPIC = "transaction.posted";

    /** A second topic this service reads, for the cross-topic marker assertions. */
    private static final String OTHER_CLAIMED_TOPIC = "fraud.assessed";

    /** Account the context assertion writes, eleven digit characters. */
    private static final String CONTEXT_ACCOUNT_ID = "00000000011";

    /** Family name the first context change carries. */
    private static final String FIRST_LAST_NAME = "Von";

    /** Family name the newer context change carries. */
    private static final String SECOND_LAST_NAME = "Vaughn";

    /** Family name the older context change carries, which no row ever holds. */
    private static final String STALE_LAST_NAME = "Stale";

    /** Description the first delivery carries, before padding. */
    private static final String FIRST_DESCRIPTION = "Purchase";

    /** Description the second delivery carries, before padding. */
    private static final String SECOND_DESCRIPTION = "Purchase corrected";

    // ---------------------------------------------------------------------------------------
    // Catalogue queries.
    // ---------------------------------------------------------------------------------------

    /** Reads the base tables of one schema. */
    private static final String TABLES_SQL = """
            SELECT table_name
              FROM information_schema.tables
             WHERE table_schema = ?
               AND table_type = 'BASE TABLE'
             ORDER BY table_name
            """;

    /**
     * Reads the declared type of every column of one schema.
     *
     * <p>{@link #columnFacts()} keeps the rows of the three migrated tables and drops the rest, so
     * Flyway's own bookkeeping columns reach no assertion.</p>
     */
    private static final String COLUMNS_SQL = """
            SELECT table_name, column_name, data_type, character_maximum_length,
                   numeric_precision, numeric_scale, is_nullable
              FROM information_schema.columns
             WHERE table_schema = ?
             ORDER BY table_name, ordinal_position
            """;

    /** Reads the primary-key constraint name of each table of one schema. */
    private static final String PRIMARY_KEYS_SQL = """
            SELECT table_name, constraint_name
              FROM information_schema.table_constraints
             WHERE table_schema = ?
               AND constraint_type = 'PRIMARY KEY'
             ORDER BY table_name
            """;

    /** Reads the key columns of one table's primary key, in the order the catalogue holds them. */
    private static final String KEY_COLUMN_ORDER_SQL = """
            SELECT kcu.column_name
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_name = tc.constraint_name
               AND kcu.table_schema = tc.table_schema
             WHERE tc.table_schema = ?
               AND tc.table_name = ?
               AND tc.constraint_type = 'PRIMARY KEY'
             ORDER BY kcu.ordinal_position
            """;

    /** Reads every index of one schema. */
    private static final String INDEXES_SQL = """
            SELECT tablename, indexname
              FROM pg_indexes
             WHERE schemaname = ?
             ORDER BY tablename, indexname
            """;

    /**
     * Counts the columns of one schema that carry a card verification value.
     *
     * <p>The pattern matches the three-letter abbreviation with or without separators, and the
     * spelled-out word covers the long form.</p>
     */
    private static final String VERIFICATION_VALUE_SQL = """
            SELECT count(*)
              FROM information_schema.columns
             WHERE table_schema = ?
               AND (lower(column_name) LIKE '%verification%'
                    OR lower(column_name) ~ 'c[_]?v[_]?v')
            """;

    /** Counts the rows of {@code statement_transaction}. */
    private static final String ROW_COUNT_SQL =
            "SELECT count(*) FROM statement_transaction";

    // ---------------------------------------------------------------------------------------
    // Collaborators.
    // ---------------------------------------------------------------------------------------

    /** Supplies the metamodel the mapping assertions read. */
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** Writes and reads rows. The proxy joins whichever transaction is active. */
    @Autowired
    private EntityManager entityManager;

    /** Reaches the catalogue, and counts rows outside a persistence context. */
    @Autowired
    private DataSource dataSource;

    /** Executes bounded retention deletes over the statement read model. */
    @Autowired
    private StatementTransactionRepository statementTransactions;

    /** Executes bounded retention deletes over delivery attempts. */
    @Autowired
    private NotificationLogRepository notificationLogs;

    /** Executes bounded retention deletes over duplicate-delivery markers. */
    @Autowired
    private ProcessedEventRepository processedEvents;

    /** Commits each write of the upsert assertions on its own. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Schema Flyway created and Hibernate validated against.
     *
     * <p>Reading the resolved property keeps every catalogue query below pointed at the schema
     * {@code application.yml} names.</p>
     */
    @Value("${spring.jpa.properties.hibernate.default_schema}")
    private String schema;

    /** Applies one cardholder-context change, or applies nothing older. */
    @Autowired
    private CardholderContextRepository cardholderContexts;

    /** Removes the rows this class writes, so no method depends on running before another. */
    @AfterEach
    void removeWrittenRows() {
        jdbc().sql("DELETE FROM " + schema + ".statement_transaction").update();
        jdbc().sql("DELETE FROM " + schema + ".notification_log").update();
        jdbc().sql("DELETE FROM " + schema + ".processed_event").update();
        jdbc().sql("DELETE FROM " + schema + ".cardholder_context").update();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------

    /** @return a client over the migrated database */
    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /**
     * Qualifies an unqualified table name with the migrated schema.
     *
     * @param sql a statement naming {@code statement_transaction} unqualified
     * @return the same statement naming the table in the migrated schema
     */
    private String qualified(String sql) {
        return sql.replace(STATEMENT_TRANSACTION, schema + "." + STATEMENT_TRANSACTION);
    }

    /**
     * Qualifies one named table with the migrated schema.
     *
     * @param sql   a statement naming {@code table} unqualified
     * @param table the table name to qualify
     * @return the same statement naming that table in the migrated schema
     */
    private String schemaQualified(String sql, String table) {
        return sql.replace(table, schema + "." + table);
    }

    /** @return a template that commits each unit of work on return */
    private TransactionTemplate committedTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    /** @return the base tables of the migrated schema, Flyway's own table excluded */
    private Set<String> migratedTables() {
        Set<String> tables = new TreeSet<>(
                jdbc().sql(TABLES_SQL).param(schema).query(String.class).list());
        tables.remove(FLYWAY_HISTORY);
        return tables;
    }

    /**
     * Reads the declared type of every column the migration created.
     *
     * <p>Flyway's own bookkeeping table lives in the same schema and holds columns that accept no
     * value, so its rows are dropped here and reach no assertion.</p>
     *
     * @return one entry per column of the three migrated tables, keyed {@code table.column}
     */
    private Map<String, ColumnFact> columnFacts() {
        Map<String, ColumnFact> facts = new LinkedHashMap<>();
        for (ColumnFact fact : jdbc().sql(COLUMNS_SQL).param(schema)
                .query((ResultSet row, int number) -> readColumnFact(row)).list()) {
            if (MIGRATION_TABLES.contains(fact.table())) {
                facts.put(fact.table() + "." + fact.column(), fact);
            }
        }
        return facts;
    }

    /**
     * Maps one {@code information_schema.columns} row.
     *
     * @param row the catalogue row
     * @return the declared type of one column
     * @throws SQLException when the row cannot be read
     */
    private static ColumnFact readColumnFact(ResultSet row) throws SQLException {
        return new ColumnFact(
                row.getString("table_name"),
                row.getString("column_name"),
                row.getString("data_type"),
                nullableInt(row, "character_maximum_length"),
                nullableInt(row, "numeric_precision"),
                nullableInt(row, "numeric_scale"),
                "YES".equals(row.getString("is_nullable")));
    }

    /**
     * Reads one integer column, keeping the difference between zero and no value.
     *
     * @param row the catalogue row
     * @param column the column name
     * @return the value, or {@code null} when the catalogue holds none
     * @throws SQLException when the row cannot be read
     */
    private static Integer nullableInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    /** @return the number of rows {@code statement_transaction} holds */
    private long rowCount() {
        return jdbc().sql(qualified(ROW_COUNT_SQL)).query(Long.class).single();
    }


    /**
     * Runs the bounded read-model retention delete and reads the result back.
     *
     * <p>The horizon is text. {@code processing_timestamp} is {@code CHAR(26)}, carrying
     * {@code TRNX-PROC-TS PIC X(26)} of {@code app/cpy/COSTM01.CPY}, and
     * {@code app/cbl/CBTRN02C.cbl:L414-L420} compares such a field as characters. Running the
     * statement here proves the comparison the column's collation applies matches the order
     * {@code ix_statement_transaction_processing_timestamp} holds.
     */
    @Test
    @DisplayName("The read-model retention delete takes the row whose timestamp text precedes the "
            + "horizon and honours its limit")
    void theReadModelRetentionDeleteTakesOnlyRowsPrecedingTheHorizon() {
        committedTransaction().executeWithoutResult(status -> {
            entityManager.merge(rowStampedAt("RETAIN-OLD-00001", "2019-01-01-00.00.00.000000"));
            entityManager.merge(rowStampedAt("RETAIN-OLD-00002", "2019-06-01-00.00.00.000000"));
            entityManager.merge(rowStampedAt("RETAIN-NEW-00001", "2030-01-01-00.00.00.000000"));
        });

        int firstStatement = committedTransaction().execute(status ->
                statementTransactions.deleteProcessedBefore(RETENTION_HORIZON_TEXT, 1));
        int secondStatement = committedTransaction().execute(status ->
                statementTransactions.deleteProcessedBefore(RETENTION_HORIZON_TEXT, 100));
        int thirdStatement = committedTransaction().execute(status ->
                statementTransactions.deleteProcessedBefore(RETENTION_HORIZON_TEXT, 100));

        assertAll("the bounded read-model delete",
                () -> assertEquals(1, firstStatement, "the limit bounds one statement"),
                () -> assertEquals(1, secondStatement, "the remaining expired row follows"),
                () -> assertEquals(0, thirdStatement,
                        "domain/RetentionSweeper stops on a count below the batch size"),
                () -> assertTrue(statementTransactions
                        .findById(new StatementTransactionId(CARD_TOKEN, "RETAIN-NEW-00001"))
                        .isPresent(),
                        "the row whose timestamp text follows the horizon stays"));
    }

    /**
     * Holds that one event identifier is claimable once per topic and refused twice on one topic.
     *
     * <p>Four listener groups share this table and three producing services assign identifiers
     * independently, so the same identifier can reach two topics without either producer being at
     * fault. While {@code claimEvent} conflicted on the identifier alone, the second of the two lost
     * its claim and its listener wrote nothing at all: the right outcome for a redelivery, and a
     * silently dropped read-model row for a different event. Nothing raised, nothing reached a
     * dead-letter topic, and the marker that caused it stays, so no replay could recover the row.
     *
     * <p>Suppression within one topic has to survive the change, because that is what the guard was
     * built for. Both properties are asserted here together.
     */
    @Test
    @DisplayName("claimEvent takes one claim per topic and refuses a redelivery on the same topic")
    void claimEventTakesOneClaimPerTopicAndRefusesARedeliveryOnTheSameTopic() {
        UUID sharedId = UUID.randomUUID();
        Instant first = Instant.parse("2026-03-01T00:00:00Z");
        Instant later = Instant.parse("2026-03-02T00:00:00Z");

        int firstClaim = committedTransaction().execute(status ->
                processedEvents.claimEvent(sharedId, first, CLAIMED_TOPIC));
        int redelivery = committedTransaction().execute(status ->
                processedEvents.claimEvent(sharedId, later, CLAIMED_TOPIC));
        int otherTopic = committedTransaction().execute(status ->
                processedEvents.claimEvent(sharedId, later, OTHER_CLAIMED_TOPIC));

        assertAll("one identifier across two topics",
                () -> assertEquals(1, firstClaim, "the first delivery takes the claim"),
                () -> assertEquals(ProcessedEventRepository.ALREADY_CLAIMED, redelivery,
                        "a redelivery on the same topic writes nothing"),
                () -> assertEquals(1, otherTopic,
                        "the same identifier on another topic is a different delivery"),
                () -> assertTrue(processedEvents.existsById(
                        new ProcessedEventId(sharedId, CLAIMED_TOPIC)),
                        "the first topic holds its marker"),
                () -> assertTrue(processedEvents.existsById(
                        new ProcessedEventId(sharedId, OTHER_CLAIMED_TOPIC)),
                        "and the second topic holds its own"),
                () -> assertEquals(first, processedEvents
                        .findById(new ProcessedEventId(sharedId, CLAIMED_TOPIC)).orElseThrow()
                        .getProcessedAt(),
                        "the refused redelivery left the first instant standing"));
    }

    /**
     * Holds that the retention delete leaves a same-identifier marker whose own instant is newer.
     *
     * <p>The statement matches on both key columns. Matching on the identifier alone would remove
     * the sibling marker on the other topic as well, and that marker guards a delivery the retention
     * rule was not asked to forget: the next redelivery of it would be applied a second time.
     */
    @Test
    @DisplayName("The retention delete leaves a same-identifier marker on another topic")
    void theRetentionDeleteLeavesASameIdentifierMarkerOnAnotherTopic() {
        UUID sharedId = UUID.randomUUID();
        committedTransaction().executeWithoutResult(status -> {
            processedEvents.claimEvent(sharedId, Instant.parse("2020-06-01T00:00:00Z"),
                    CLAIMED_TOPIC);
            processedEvents.claimEvent(sharedId, Instant.parse("2030-06-01T00:00:00Z"),
                    OTHER_CLAIMED_TOPIC);
        });

        int removed = committedTransaction().execute(status -> processedEvents
                .deleteMarkersProcessedBefore(Instant.parse("2025-01-01T00:00:00Z"), PURGE_BATCH));

        assertAll("the retention pass over one identifier on two topics",
                () -> assertEquals(1, removed, "one marker sits behind the horizon"),
                () -> assertFalse(processedEvents.existsById(
                        new ProcessedEventId(sharedId, CLAIMED_TOPIC)),
                        "and it is the one that was removed"),
                () -> assertTrue(processedEvents.existsById(
                        new ProcessedEventId(sharedId, OTHER_CLAIMED_TOPIC)),
                        "the sibling still guards its own delivery"));
    }

    /**
     * Runs the bounded marker retention delete and reads the result back.
     *
     * <p>A marker outliving the broker's retention of its event is what makes a redelivery harmless,
     * so this delete removing the wrong row would reintroduce double processing.
     */
    @Test
    @DisplayName("The marker retention delete takes the expired marker and keeps the newer one")
    void theMarkerRetentionDeleteTakesOnlyExpiredMarkers() {
        Instant expiredAt = Instant.parse("2020-01-01T00:00:00Z");
        Instant recentAt = Instant.parse("2030-01-01T00:00:00Z");
        committedTransaction().executeWithoutResult(status -> {
            processedEvents.claimEvent(RETENTION_EXPIRED_EVENT_ID, expiredAt, CLAIMED_TOPIC);
            processedEvents.claimEvent(RETENTION_RECENT_EVENT_ID, recentAt, CLAIMED_TOPIC);
        });

        int removed = committedTransaction().execute(status ->
                processedEvents.deleteMarkersProcessedBefore(
                        Instant.parse("2021-01-01T00:00:00Z"), 100));

        assertAll("the bounded marker delete",
                () -> assertEquals(1, removed, "one marker precedes the horizon"),
                () -> assertFalse(processedEvents.existsById(
                        new ProcessedEventId(RETENTION_EXPIRED_EVENT_ID, CLAIMED_TOPIC)),
                        "the expired marker is gone"),
                () -> assertTrue(processedEvents.existsById(
                        new ProcessedEventId(RETENTION_RECENT_EVENT_ID, CLAIMED_TOPIC)),
                        "a marker inside the horizon stays, so its redelivery is still refused"));
    }

    /**
     * Builds one read-model row carrying the given processing timestamp text.
     *
     * @param transactionId the second half of the composite key
     * @param stamp         twenty-six characters, in the source's own form
     * @return a row ready to write
     */
    private static StatementTransactionEntity rowStampedAt(String transactionId, String stamp) {
        StatementTransactionEntity built = row(transactionId, "RETENTION", new BigDecimal("1.00"));
        return new StatementTransactionEntity(
                new StatementTransactionId(CARD_TOKEN, transactionId),
                built.getMaskedCardNumber(), built.getTypeCode(),
                built.getCategoryCode(), built.getSource(),
                built.getDescription(), built.getAmount(), built.getMerchantId(),
                built.getMerchantName(), built.getMerchantCity(), built.getMerchantZip(),
                built.getOriginTimestamp(), stamp);
    }

    /**
     * Builds one read-model row filling every column to its declared width.
     *
     * @param transactionId the second half of the composite key
     * @param description the description, padded here to the declared hundred characters
     * @param amount the amount, at scale {@value #AMOUNT_SCALE}
     * @return a row ready to write
     */
    private static StatementTransactionEntity row(String transactionId, String description,
            BigDecimal amount) {
        return rowAt(transactionId, description, amount, PROCESSING_TIMESTAMP);
    }

    /**
     * Builds one read-model row at a caller-selected processing timestamp.
     *
     * @param transactionId the second half of the composite key
     * @param description the description, padded to its declared width
     * @param amount the amount, at scale {@value #AMOUNT_SCALE}
     * @param processingTimestamp the purge key, at its fixed width
     * @return a row ready to write
     */
    private static StatementTransactionEntity rowAt(String transactionId, String description,
            BigDecimal amount, String processingTimestamp) {
        return rowOfCard(CARD_TOKEN, transactionId, description, amount, processingTimestamp);
    }

    /**
     * Builds one read-model row of one card, filling every column to its declared width.
     *
     * @param cardToken the card half of the composite key
     * @param transactionId the second half of the composite key
     * @param description the description, padded here to the declared hundred characters
     * @param amount the amount, at scale {@value #AMOUNT_SCALE}
     * @return a row ready to write
     */
    private static StatementTransactionEntity rowOfCard(String cardToken, String transactionId,
            String description, BigDecimal amount) {
        return rowOfCard(cardToken, transactionId, description, amount, PROCESSING_TIMESTAMP);
    }

    /**
     * Builds one read-model row of one card at a caller-selected processing timestamp.
     *
     * @param cardToken the card half of the composite key
     * @param transactionId the second half of the composite key
     * @param description the description, padded here to the declared hundred characters
     * @param amount the amount, at scale {@value #AMOUNT_SCALE}
     * @param processingTimestamp the purge key, at its fixed width
     * @return a row ready to write
     */
    private static StatementTransactionEntity rowOfCard(String cardToken, String transactionId,
            String description, BigDecimal amount, String processingTimestamp) {
        return new StatementTransactionEntity(
                new StatementTransactionId(cardToken, transactionId),
                MASKED_CARD,
                TYPE_CODE,
                CATEGORY_CODE,
                padded("POS TERM", 10),
                padded(description, 100),
                amount,
                MERCHANT_ID,
                padded("MERCHANT", 50),
                padded("CITY", 50),
                MERCHANT_ZIP,
                ORIGIN_TIMESTAMP,
                processingTimestamp);
    }

    /**
     * Fills a value to the width its column declares.
     *
     * <p>A COBOL {@code PIC X} field is space-padded to its declared width, and PostgreSQL pads a
     * {@code character(n)} value the same way on storage. Writing the full width keeps every
     * round-trip assertion below a strict equality.</p>
     *
     * @param value the value
     * @param width the characters the column holds
     * @return the value at exactly {@code width} characters
     */
    private static String padded(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Fills a transaction identifier to the sixteen characters its column holds.
     *
     * <p>The zeros lead, matching the form {@code requireDigits} produces on
     * {@link StatementTransactionEntity}.</p>
     *
     * @param digits the trailing digits
     * @return the identifier at exactly {@value #KEY_PART_WIDTH} characters
     */
    private static String zeroPadded(String digits) {
        return "0".repeat(KEY_PART_WIDTH - digits.length()) + digits;
    }

    /**
     * The declared type of one column, as {@code information_schema} reports it.
     *
     * @param table the table
     * @param column the column
     * @param dataType the reported type name
     * @param characterWidth the declared character width, or {@code null} for a non-character type
     * @param precision the declared total digits, or {@code null} for a non-numeric type
     * @param scale the declared fractional digits, or {@code null} for a non-numeric type
     * @param nullable whether the column accepts no value
     */
    private record ColumnFact(String table, String column, String dataType,
            Integer characterWidth, Integer precision, Integer scale, boolean nullable) {
    }

    // ---------------------------------------------------------------------------------------
    // Group A. The boot, and the mapping Hibernate validated.
    // ---------------------------------------------------------------------------------------

    /**
     * Reaches the entity manager, which a context that failed validation never does.
     *
     * <p>A failure here means an entity drifted from
     * {@code src/main/resources/db/migration/V1__schema.sql}.</p>
     */
    @Test
    void theContextReachesTheEntityManagerWithTheFourEntitiesMapped() {
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        Set<String> mapped = new TreeSet<>();
        for (EntityType<?> entity : metamodel.getEntities()) {
            mapped.add(entity.getJavaType().getSimpleName());
        }

        assertAll("the mapping Hibernate validated against the migrated schema",
                () -> assertNotNull(entityManager, "the entity manager is reachable"),
                () -> assertNotNull(entityManagerFactory.getMetamodel(), "the metamodel is built"),
                () -> assertEquals(Set.of("StatementTransactionEntity", "NotificationLogEntity",
                                "ProcessedEventEntity", "CardholderContextEntity"), mapped,
                        "the module maps four entities, one per table of V1__schema.sql"),
                () -> assertEquals(4, metamodel.getEntities().size(),
                        "a fifth entity or a missing entity changes this count"));
    }

    /**
     * Asserts the composite key is the nested embeddable, from {@code 05 TRNX-KEY.} at
     * {@code app/cpy/COSTM01.CPY:L21}.
     */
    @Test
    void theStatementRowIsIdentifiedByTheNestedEmbeddableKey() {
        Metamodel metamodel = entityManagerFactory.getMetamodel();
        Set<Class<?>> embeddables = new java.util.HashSet<>();
        for (EmbeddableType<?> embeddable : metamodel.getEmbeddables()) {
            embeddables.add(embeddable.getJavaType());
        }

        assertAll("the composite key of statement_transaction",
                () -> assertTrue(embeddables.contains(StatementTransactionId.class),
                        "the metamodel holds StatementTransactionId as an embeddable, found "
                                + embeddables),
                () -> assertSame(StatementTransactionId.class,
                        metamodel.entity(StatementTransactionEntity.class).getIdType()
                                .getJavaType(),
                        "the identifier type of StatementTransactionEntity is the nested key"));
    }

    // ---------------------------------------------------------------------------------------
    // Group B. The live catalogue Flyway produced.
    // ---------------------------------------------------------------------------------------

    /**
     * Asserts the migrated schema holds the four tables {@code V1__schema.sql} declares, and no
     * relay table.
     *
     * <p>This service consumes three topics and publishes nothing, so it owns no
     * {@value #ABSENT_TABLE} table. Flyway's own bookkeeping table is excluded by name, since the
     * migration does not declare it.</p>
     */
    @Test
    void theSchemaHoldsTheFourMigratedTablesAndNoRelayTable() {
        Set<String> tables = migratedTables();

        assertAll("the tables of schema " + schema,
                () -> assertEquals(new TreeSet<>(MIGRATION_TABLES), tables,
                        "the migration declares exactly four tables"),
                () -> assertFalse(tables.contains(ABSENT_TABLE),
                        "a service that publishes nothing owns no " + ABSENT_TABLE + " table"));
    }

    /**
     * Asserts each table holds the column count its declaration carries, and that one column
     * accepts no value.
     *
     * <p>The fourteen columns of {@value #STATEMENT_TRANSACTION} are the thirteen fields of
     * {@code 01 TRNX-RECORD.} at {@code app/cpy/COSTM01.CPY:L20}. The trailing
     * {@code FILLER PIC X(20)} at {@code app/cpy/COSTM01.CPY:L36} is dropped, and the group
     * {@code 05 TRNX-REST.} at {@code app/cpy/COSTM01.CPY:L24} carries no column of its own.
     *
     * <p>No column across the four tables accepts a null.
     * {@code processed_event.consumed_topic} was the one that did, and
     * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql} made it half of the
     * primary key.</p>
     */
    @Test
    void eachTableHoldsItsDeclaredColumnCountAndNoColumnAcceptsANull() {
        Map<String, ColumnFact> facts = columnFacts();
        Set<String> nullable = new TreeSet<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ColumnFact fact : facts.values()) {
            counts.merge(fact.table(), 1, Integer::sum);
            if (fact.nullable()) {
                nullable.add(fact.table() + "." + fact.column());
            }
        }

        assertAll("column counts of schema " + schema,
                () -> assertEquals(STATEMENT_TRANSACTION_COLUMNS,
                        counts.get(STATEMENT_TRANSACTION),
                        "one column per field of 01 TRNX-RECORD, the trailing filler dropped, plus the"
                        + " masked display column beside the token key"),
                () -> assertEquals(NOTIFICATION_LOG_COLUMNS, counts.get(NOTIFICATION_LOG),
                        NOTIFICATION_LOG + " columns"),
                () -> assertEquals(PROCESSED_EVENT_COLUMNS, counts.get(PROCESSED_EVENT),
                        PROCESSED_EVENT + " columns"),
                () -> assertEquals(CARDHOLDER_CONTEXT_COLUMNS, counts.get(CARDHOLDER_CONTEXT),
                        CARDHOLDER_CONTEXT + " columns"),
                () -> assertEquals(NULLABLE_COLUMNS, nullable,
                        "every column of the four migrated tables carries a value, so no consumer "
                                + "leaves one it cannot bring current"));
    }

    /**
     * Asserts {@code amount} holds nine integer digits and two fractional digits.
     *
     * <p>{@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29} is eleven total digits.
     * An account-balance column holds {@value #ACCOUNT_BALANCE_PRECISION}, and no column in this
     * schema is one.</p>
     */
    @Test
    void theAmountColumnHoldsElevenDigitsAndNotTwelve() {
        ColumnFact amount = columnFacts().get(STATEMENT_TRANSACTION + ".amount");
        assertNotNull(amount, "statement_transaction declares an amount column");

        assertAll("amount, from TRNX-AMT PIC S9(09)V99",
                () -> assertEquals(NUMERIC_TYPE, amount.dataType(), "declared type"),
                () -> assertEquals(AMOUNT_PRECISION, amount.precision(), "total digits"),
                () -> assertEquals(AMOUNT_SCALE, amount.scale(), "fractional digits"),
                () -> assertFalse(Integer.valueOf(ACCOUNT_BALANCE_PRECISION)
                                .equals(amount.precision()),
                        "an account balance holds twelve digits and a transaction amount holds "
                                + AMOUNT_PRECISION));
    }

    /**
     * Asserts both timestamp columns hold twenty-six characters of text and no temporal type.
     *
     * <p>{@code TRNX-ORIG-TS} and {@code TRNX-PROC-TS} at {@code app/cpy/COSTM01.CPY:L34-L35} are
     * both {@code PIC X(26)}, and the source compares such a field as text. The catalogue reports
     * {@value #FIXED_CHARACTER} for a {@code CHAR(n)} column and {@value #VARYING_CHARACTER} for a
     * {@code VARCHAR(n)} column.</p>
     */
    @Test
    void bothTimestampColumnsHoldTwentySixCharactersAndNoTemporalType() {
        Map<String, ColumnFact> facts = columnFacts();
        ColumnFact origin = facts.get(STATEMENT_TRANSACTION + ".origin_timestamp");
        ColumnFact processing = facts.get(STATEMENT_TRANSACTION + ".processing_timestamp");
        assertNotNull(origin, "statement_transaction declares an origin_timestamp column");
        assertNotNull(processing, "statement_transaction declares a processing_timestamp column");

        assertAll("the two 26-character timestamp columns",
                () -> assertEquals(FIXED_CHARACTER, origin.dataType(), "origin_timestamp type"),
                () -> assertEquals(TIMESTAMP_WIDTH, origin.characterWidth(),
                        "origin_timestamp width"),
                () -> assertEquals(FIXED_CHARACTER, processing.dataType(),
                        "processing_timestamp type"),
                () -> assertEquals(TIMESTAMP_WIDTH, processing.characterWidth(),
                        "processing_timestamp width"),
                () -> assertFalse(origin.dataType().contains("timestamp"),
                        "origin_timestamp carries no temporal type"),
                () -> assertFalse(processing.dataType().contains("timestamp"),
                        "processing_timestamp carries no temporal type"));
    }

    /**
     * Asserts the key, display and digit columns hold their declared character widths.
     *
     * <p>{@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23} gives the transaction half of
     * the key and {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22} gives the masked
     * display column. The card half of the key is a card token at
     * {@code PanMasker.CARD_TOKEN_LENGTH} characters, which is wider than the source field it stands
     * in for.
     * {@code TRNX-CAT-CD PIC 9(04)} at {@code app/cpy/COSTM01.CPY:L26} and
     * {@code TRNX-MERCHANT-ID PIC 9(09)} at {@code app/cpy/COSTM01.CPY:L30} are display fields, so
     * both are held as text at their declared digit counts and a leading zero survives.</p>
     */
    @Test
    void theKeyColumnsAndTheDigitColumnsHoldTheirDeclaredWidths() {
        Map<String, ColumnFact> facts = columnFacts();

        assertAll("declared widths of statement_transaction",
                () -> assertCharacterColumn(facts, STATEMENT_TRANSACTION + ".card_token",
                        FIXED_CHARACTER, CARD_TOKEN_WIDTH),
                () -> assertCharacterColumn(facts, STATEMENT_TRANSACTION + ".transaction_id",
                        FIXED_CHARACTER, KEY_PART_WIDTH),
                () -> assertCharacterColumn(facts,
                        STATEMENT_TRANSACTION + ".masked_card_number",
                        FIXED_CHARACTER, KEY_PART_WIDTH),
                () -> assertCharacterColumn(facts, STATEMENT_TRANSACTION + ".category_code",
                        FIXED_CHARACTER, 4),
                () -> assertCharacterColumn(facts, STATEMENT_TRANSACTION + ".merchant_id",
                        FIXED_CHARACTER, 9),
                () -> assertCharacterColumn(facts, STATEMENT_TRANSACTION + ".type_code",
                        FIXED_CHARACTER, 2),
                () -> assertCharacterColumn(facts, STATEMENT_TRANSACTION + ".source",
                        FIXED_CHARACTER, 10),
                () -> assertCharacterColumn(facts, STATEMENT_TRANSACTION + ".description",
                        FIXED_CHARACTER, 100),
                () -> assertCharacterColumn(facts, STATEMENT_TRANSACTION + ".merchant_name",
                        FIXED_CHARACTER, 50),
                () -> assertCharacterColumn(facts, STATEMENT_TRANSACTION + ".merchant_city",
                        FIXED_CHARACTER, 50),
                () -> assertCharacterColumn(facts, STATEMENT_TRANSACTION + ".merchant_zip",
                        FIXED_CHARACTER, 10));
    }

    /**
     * Asserts the two additive tables hold the column types their declarations carry.
     *
     * <p>Neither table has a source ancestor. No COBOL program records a delivery attempt, and none
     * detects a duplicate delivery.</p>
     */
    @Test
    void theTwoAdditiveTablesHoldTheirDeclaredColumnTypes() {
        Map<String, ColumnFact> facts = columnFacts();

        assertAll("notification_log and processed_event",
                () -> assertCharacterColumn(facts, NOTIFICATION_LOG + ".channel",
                        VARYING_CHARACTER, 20),
                () -> assertCharacterColumn(facts, NOTIFICATION_LOG + ".card_token",
                        FIXED_CHARACTER, CARD_TOKEN_WIDTH),
                () -> assertCharacterColumn(facts, NOTIFICATION_LOG + ".transaction_id",
                        FIXED_CHARACTER, KEY_PART_WIDTH),
                () -> assertCharacterColumn(facts, NOTIFICATION_LOG + ".masked_card_number",
                        FIXED_CHARACTER, KEY_PART_WIDTH),
                () -> assertEquals(TIMESTAMP_WITH_ZONE,
                        facts.get(NOTIFICATION_LOG + ".rendered_at").dataType(),
                        "rendered_at holds an instant"),
                () -> assertCharacterColumn(facts, NOTIFICATION_LOG + ".outcome",
                        VARYING_CHARACTER, 20),
                () -> assertEquals(UUID_TYPE, facts.get(NOTIFICATION_LOG + ".id").dataType(),
                        "the rendered-alert identifier"),
                () -> assertEquals(UUID_TYPE, facts.get(PROCESSED_EVENT + ".event_id").dataType(),
                        "the consumed event identifier"),
                () -> assertEquals(TIMESTAMP_WITH_ZONE,
                        facts.get(PROCESSED_EVENT + ".processed_at").dataType(),
                        "processed_at holds an instant"));
    }

    /**
     * Asserts three column names appear nowhere in the schema, and that no card-keyed table
     * carries an account identifier.
     *
     * <p>The trailing {@code FILLER PIC X(20)} at {@code app/cpy/COSTM01.CPY:L36} is dropped. No
     * table stamps its own creation. No column carries a card verification value, and none ever
     * will. {@value #STATEMENT_TRANSACTION} and {@value #NOTIFICATION_LOG} are keyed by card, so
     * neither carries an account identifier; {@value #CARDHOLDER_CONTEXT} is keyed by account and
     * holds one.</p>
     */
    @Test
    void threeColumnNamesAppearNowhereAndNoCardKeyedTableCarriesAnAccount() {
        Set<String> columns = new TreeSet<>();
        Set<String> cardKeyedColumns = new TreeSet<>();
        for (ColumnFact fact : columnFacts().values()) {
            columns.add(fact.column());
            if (STATEMENT_TRANSACTION.equals(fact.table())
                    || NOTIFICATION_LOG.equals(fact.table())) {
                cardKeyedColumns.add(fact.column());
            }
        }
        long verificationValueColumns =
                jdbc().sql(VERIFICATION_VALUE_SQL).param(schema).query(Long.class).single();

        assertAll("columns the schema does not declare",
                () -> assertFalse(columns.contains("filler"),
                        "the trailing 20-byte filler of the source record carries no column"),
                () -> assertFalse(cardKeyedColumns.contains("account_id"),
                        "the read model is keyed by card token and transaction identifier"),
                () -> assertTrue(columns.contains("account_id"),
                        "the cardholder projection is keyed by account identifier"),
                () -> assertFalse(columns.contains("created_at"), "no table stamps its creation"),
                () -> assertEquals(0L, verificationValueColumns,
                        "no column carries a card verification value"));
    }

    /**
     * Asserts one primary key per migrated table, and the exact index set of each.
     *
     * <p>{@value #PROCESSED_EVENT} carries its primary key and the index that serves the marker
     * purge. Flyway's own bookkeeping table is excluded, since the migration does not declare
     * it.</p>
     */
    @Test
    void eachMigratedTableCarriesOnePrimaryKeyAndItsDeclaredIndexes() {
        Map<String, String> primaryKeys = new LinkedHashMap<>();
        jdbc().sql(PRIMARY_KEYS_SQL).param(schema).query((ResultSet row, int number) -> {
            if (MIGRATION_TABLES.contains(row.getString("table_name"))) {
                primaryKeys.put(row.getString("table_name"), row.getString("constraint_name"));
            }
            return row.getString("table_name");
        }).list();

        Map<String, Set<String>> indexes = new LinkedHashMap<>();
        jdbc().sql(INDEXES_SQL).param(schema).query((ResultSet row, int number) -> {
            String table = row.getString("tablename");
            if (MIGRATION_TABLES.contains(table)) {
                indexes.computeIfAbsent(table, key -> new TreeSet<>())
                        .add(row.getString("indexname"));
            }
            return table;
        }).list();

        assertAll("keys and indexes of schema " + schema,
                () -> assertEquals(4, primaryKeys.size(),
                        "one primary key per migrated table, found " + primaryKeys),
                () -> assertEquals(Map.of(
                                STATEMENT_TRANSACTION, "pk_statement_transaction",
                                NOTIFICATION_LOG, "pk_notification_log",
                                PROCESSED_EVENT, "pk_processed_event",
                                CARDHOLDER_CONTEXT, "pk_cardholder_context"), primaryKeys,
                        "each primary key carries the name the migration gives it"),
                () -> assertEquals(Set.of("pk_statement_transaction",
                                "ix_statement_transaction_processing_timestamp"),
                        indexes.get(STATEMENT_TRANSACTION), STATEMENT_TRANSACTION + " indexes"),
                () -> assertEquals(Set.of("pk_notification_log", "ix_notification_log_card_token",
                                "ix_notification_log_rendered_at"),
                        indexes.get(NOTIFICATION_LOG), NOTIFICATION_LOG + " indexes"),
                () -> assertEquals(Set.of("pk_processed_event", "ix_processed_event_processed_at"),
                        indexes.get(PROCESSED_EVENT), PROCESSED_EVENT + " indexes"),
                () -> assertEquals(Set.of("pk_cardholder_context",
                                "ix_cardholder_context_observed_at"),
                        indexes.get(CARDHOLDER_CONTEXT), CARDHOLDER_CONTEXT + " indexes"));
    }

    /**
     * Asserts the composite key orders the card token first and the transaction identifier second.
     *
     * <p>The source uses {@code TRNX-CARD-NUM} then {@code TRNX-ID}. The platform cannot preserve
     * the first half because it transports only a masked, non-unique value. The unique
     * {@code account_id} therefore supplies the identity prefix and {@code transaction_id} keeps
     * the source ordering inside that account.
     *
     * <p>The card half is the token rather than the card number, which is the one departure from the
     * source key: a masked number identifies no single card and a full one belongs in no index.
     *
     * <p>This assertion is the authoritative one for component order. The sibling
     * {@code StatementTransactionEntityTest} reads the declared field order by reflection, which the
     * language specification leaves unspecified, so the catalogue order settles it.</p>
     */
    @Test
    void theCompositeKeyOrdersTheCardTokenBeforeTheTransactionIdentifier() {
        List<String> keyColumns = jdbc().sql(KEY_COLUMN_ORDER_SQL)
                .param(schema).param(STATEMENT_TRANSACTION).query(String.class).list();

        assertEquals(List.of("card_token", "transaction_id"), keyColumns,
                "one composite key over two columns in the order KEYS(32 0) declares");
    }

    /**
     * Asserts one column reports the declared type and width its migration line carries.
     *
     * @param facts every column of the migrated schema
     * @param key the column, written {@code table.column}
     * @param dataType the type the catalogue reports
     * @param width the declared character width
     */
    private static void assertCharacterColumn(Map<String, ColumnFact> facts, String key,
            String dataType, int width) {
        ColumnFact fact = facts.get(key);
        assertNotNull(fact, "the schema declares column " + key);
        assertEquals(dataType, fact.dataType(), key + " type");
        assertEquals(Integer.valueOf(width), fact.characterWidth(), key + " width");
    }

    // ---------------------------------------------------------------------------------------
    // Group C. The two 26-character timestamp shapes, written and read back.
    // ---------------------------------------------------------------------------------------

    /**
     * Asserts both timestamp values survive a write and a read unchanged, and that neither takes the
     * other's shape.
     *
     * <p>{@value #ORIGIN_TIMESTAMP} separates the date from the time with a space, then carries
     * colons and six fractional digits. All 300 records of {@code app/data/ASCII/dailytran.txt}
     * carry that shape.
     *
     * <p>{@value #PROCESSING_TIMESTAMP} separates them with a dash, then carries dots, two
     * hundredths digits and four zero characters. {@code 01 DB2-FORMAT-TS PIC X(26).} at
     * {@code app/cbl/CBTRN02C.cbl:L159} is redefined across {@code app/cbl/CBTRN02C.cbl:L160-L174}
     * with the separator at position eleven and a two-digit hundredths field, and
     * {@code app/cbl/CBTRN02C.cbl:L701} moves four zero characters into the remainder. Precision is
     * hundredths.</p>
     */
    @Test
    void bothTimestampShapesSurviveAWriteAndAReadAndNeitherTakesTheOtherShape() {
        StatementTransactionId key = new StatementTransactionId(CARD_TOKEN, TIMESTAMP_ROW_ID);
        committedTransaction().executeWithoutResult(status ->
                entityManager.persist(row(TIMESTAMP_ROW_ID, FIRST_DESCRIPTION, FIRST_AMOUNT)));

        StatementTransactionEntity stored = committedTransaction().execute(status ->
                entityManager.find(StatementTransactionEntity.class, key));
        assertNotNull(stored, "the written row is readable under its composite key");
        String origin = stored.getOriginTimestamp();
        String processing = stored.getProcessingTimestamp();

        assertAll("the two 26-character shapes",
                () -> assertEquals(ORIGIN_TIMESTAMP, origin,
                        "the origin timestamp reads back character for character"),
                () -> assertEquals(PROCESSING_TIMESTAMP, processing,
                        "the processing timestamp reads back character for character"),
                () -> assertEquals(TIMESTAMP_WIDTH, origin.length(), "origin timestamp length"),
                () -> assertEquals(TIMESTAMP_WIDTH, processing.length(),
                        "processing timestamp length"),
                () -> assertTrue(ORIGIN_SHAPE.matcher(origin).matches(),
                        "the origin timestamp takes the space-separated shape: " + origin),
                () -> assertTrue(PROCESSING_SHAPE.matcher(processing).matches(),
                        "the processing timestamp takes the dash-separated shape: " + processing),
                () -> assertFalse(PROCESSING_SHAPE.matcher(origin).matches(),
                        "the origin timestamp does not take the processing shape"),
                () -> assertFalse(ORIGIN_SHAPE.matcher(processing).matches(),
                        "the processing timestamp does not take the origin shape"),
                () -> assertEquals(' ', origin.charAt(SEPARATOR_INDEX),
                        "a space separates the date from the time in the origin timestamp"),
                () -> assertEquals('-', processing.charAt(SEPARATOR_INDEX),
                        "a dash separates the date from the time in the processing timestamp"));
    }

    /**
     * Asserts the account key, masked display value and amount survive a write and read unchanged.
     *
     * <p>Both the masked form and a full Primary Account Number (PAN) occupy sixteen characters, so
     * the width settles neither. The assertions below hold to what the columns state: a key of
     * thirty-two token characters, a display column of sixteen masked characters, and a read that
     * returns what the write supplied. No full PAN reaches this table.</p>
     */
    @Test
    void theCardNumberAndTheAmountSurviveAWriteAndAReadUnchanged() {
        StatementTransactionId key = new StatementTransactionId(CARD_TOKEN, TIMESTAMP_ROW_ID);
        committedTransaction().executeWithoutResult(status ->
                entityManager.persist(row(TIMESTAMP_ROW_ID, FIRST_DESCRIPTION, FIRST_AMOUNT)));

        StatementTransactionEntity stored = committedTransaction().execute(status ->
                entityManager.find(StatementTransactionEntity.class, key));
        assertNotNull(stored, "the written row is readable under its composite key");

        assertAll("the key halves and the amount",
                () -> assertEquals(CARD_TOKEN, stored.getId().getCardToken(),
                        "the card token reads back character for character"),
                () -> assertEquals(CARD_TOKEN_WIDTH, stored.getId().getCardToken().length(),
                        "card token width"),
                () -> assertEquals(MASKED_CARD, stored.getMaskedCardNumber(),
                        "the masked display card number reads back character for character"),
                () -> assertEquals(TIMESTAMP_ROW_ID, stored.getId().getTransactionId(),
                        "the transaction identifier reads back character for character"),
                () -> assertEquals(KEY_PART_WIDTH, stored.getId().getTransactionId().length(),
                        "transaction identifier width"),
                () -> assertEquals(0, FIRST_AMOUNT.compareTo(stored.getAmount()),
                        "the amount reads back unchanged"),
                () -> assertEquals(AMOUNT_SCALE, stored.getAmount().scale(),
                        "the amount reads back at the scale its column declares"),
                () -> assertEquals(padded(FIRST_DESCRIPTION, 100), stored.getDescription(),
                        "the description reads back at its declared hundred characters"));
    }

    @Test
    void theStatementPurgeDeletesOnlyRowsBeforeItsCutoff() {
        String oldId = zeroPadded("9103");
        String currentId = zeroPadded("9104");
        statementTransactions.saveAll(List.of(
                rowAt(oldId, FIRST_DESCRIPTION, FIRST_AMOUNT,
                        "2022-06-10-19.27.53.120000"),
                rowAt(currentId, SECOND_DESCRIPTION, SECOND_AMOUNT,
                        "2024-06-10-19.27.53.120000")));

        int removed = committedTransaction().execute(status -> statementTransactions
                .deleteProcessedBefore("2023-01-01-00.00.00.000000", PURGE_BATCH));

        assertAll("bounded statement retention delete",
                () -> assertEquals(1, removed, "one old row removed"),
                () -> assertFalse(statementTransactions.existsById(
                        new StatementTransactionId(CARD_TOKEN, oldId))),
                () -> assertTrue(statementTransactions.existsById(
                        new StatementTransactionId(CARD_TOKEN, currentId))));
    }

    @Test
    void theAttemptPurgeDeletesOnlyRowsBeforeItsCutoff() {
        UUID oldId = UUID.fromString("2f158d36-adbb-46b4-91f8-51e8060bef01");
        UUID currentId = UUID.fromString("2f158d36-adbb-46b4-91f8-51e8060bef02");
        notificationLogs.save(new NotificationLogEntity(
                oldId, CARD_TOKEN, MASKED_CARD, TIMESTAMP_ROW_ID, "PLAIN_TEXT",
                Instant.parse("2024-01-01T00:00:00Z")));
        notificationLogs.save(new NotificationLogEntity(
                currentId, CARD_TOKEN, MASKED_CARD, UPSERT_ROW_ID, "PLAIN_TEXT",
                Instant.parse("2026-01-01T00:00:00Z")));

        int removed = committedTransaction().execute(status -> notificationLogs
                .deleteRenderedBefore(Instant.parse("2025-01-01T00:00:00Z"), PURGE_BATCH));

        assertAll("bounded attempt retention delete",
                () -> assertEquals(1, removed, "one old attempt removed"),
                () -> assertEquals(1L, notificationLogs.count(), "one current attempt remains"));
    }

    /**
     * The bound this delete carries is a real ceiling on one statement, not documentation.
     *
     * <p>Three rows sit behind the horizon and the ceiling is two, so exactly two go and the third
     * waits for the next batch. The ceiling is what keeps a first sweep of a long-unswept table from
     * locking every row of it in one statement, and the ordering is what makes the batches converge:
     * taking the oldest first means the row left behind is always nearer the horizon than the rows
     * already removed, so a repeated batch walks forward and never revisits.
     */
    @Test
    void theAttemptPurgeRemovesNoMoreThanItsCeilingAndTakesTheOldestFirst() {
        UUID firstId = UUID.fromString("2f158d36-adbb-46b4-91f8-51e8060bef11");
        UUID secondId = UUID.fromString("2f158d36-adbb-46b4-91f8-51e8060bef12");
        UUID thirdId = UUID.fromString("2f158d36-adbb-46b4-91f8-51e8060bef13");
        notificationLogs.save(new NotificationLogEntity(thirdId, CARD_TOKEN, MASKED_CARD,
                ISOLATION_ROW_ID, "PLAIN_TEXT", Instant.parse("2024-03-01T00:00:00Z")));
        notificationLogs.save(new NotificationLogEntity(firstId, CARD_TOKEN, MASKED_CARD,
                TIMESTAMP_ROW_ID, "PLAIN_TEXT", Instant.parse("2024-01-01T00:00:00Z")));
        notificationLogs.save(new NotificationLogEntity(secondId, CARD_TOKEN, MASKED_CARD,
                UPSERT_ROW_ID, "PLAIN_TEXT", Instant.parse("2024-02-01T00:00:00Z")));

        int removed = committedTransaction().execute(status -> notificationLogs
                .deleteRenderedBefore(Instant.parse("2025-01-01T00:00:00Z"), 2));

        List<UUID> surviving = jdbc()
                .sql("SELECT id FROM " + schema + ".notification_log")
                .query(UUID.class)
                .list();
        int second = committedTransaction().execute(status -> notificationLogs
                .deleteRenderedBefore(Instant.parse("2025-01-01T00:00:00Z"), 2));

        assertAll("the ceiling and the order of one bounded attempt batch",
                () -> assertEquals(2, removed, "the ceiling caps the statement at two rows"),
                () -> assertEquals(List.of(thirdId), surviving,
                        "the two oldest went and the newest of the three stayed"),
                () -> assertEquals(1, second, "the next batch takes the row that waited"),
                () -> assertEquals(0L, notificationLogs.count(),
                        "so repeated batches converge on an empty horizon"));
    }

    @Test
    void theMarkerPurgeDeletesOnlyRowsBeforeItsCutoff() {
        UUID oldId = UUID.fromString("9267c7fd-b346-4701-b613-1bf89b7f43f1");
        UUID currentId = UUID.fromString("9267c7fd-b346-4701-b613-1bf89b7f43f2");
        processedEvents.save(new ProcessedEventEntity(
                oldId, Instant.parse("2024-01-01T00:00:00Z"), CLAIMED_TOPIC));
        processedEvents.save(new ProcessedEventEntity(
                currentId, Instant.parse("2026-01-01T00:00:00Z"), CLAIMED_TOPIC));

        int removed = committedTransaction().execute(status -> processedEvents
                .deleteMarkersProcessedBefore(
                        Instant.parse("2025-01-01T00:00:00Z"), PURGE_BATCH));

        assertAll("bounded marker retention delete",
                () -> assertEquals(1, removed, "one old marker removed"),
                () -> assertFalse(processedEvents.existsById(
                        new ProcessedEventId(oldId, CLAIMED_TOPIC))),
                () -> assertTrue(processedEvents.existsById(
                        new ProcessedEventId(currentId, CLAIMED_TOPIC))));
    }

    // ---------------------------------------------------------------------------------------
    // Group D. Two cards ending in the same four digits.
    // ---------------------------------------------------------------------------------------

    /**
     * Asserts two cards sharing a last four hold two independent rows under one transaction
     * identifier.
     *
     * <p>{@link PanMasker#maskCardNumber(String)} returns one value for both cards, so a masked key
     * would gather both rows under one key and the second write would replace the first. The key
     * carries {@link PanMasker#cardToken(String)}, which is derived per card, so the two rows stand
     * apart. The source key held a full Primary Account Number at
     * {@code TRNX-CARD-NUM PIC X(16)} in {@code app/cpy/COSTM01.CPY:L22}, which distinguished
     * them.</p>
     */
    @Test
    void twoCardsSharingALastFourHoldTwoIndependentRows() {
        StatementTransactionId firstKey =
                new StatementTransactionId(CARD_TOKEN, ISOLATION_ROW_ID);
        StatementTransactionId secondKey =
                new StatementTransactionId(SAME_TAIL_CARD_TOKEN, ISOLATION_ROW_ID);

        committedTransaction().executeWithoutResult(status -> entityManager.persist(
                rowOfCard(CARD_TOKEN, ISOLATION_ROW_ID, FIRST_DESCRIPTION, FIRST_AMOUNT)));
        committedTransaction().executeWithoutResult(status -> entityManager.persist(
                rowOfCard(SAME_TAIL_CARD_TOKEN, ISOLATION_ROW_ID, SECOND_DESCRIPTION,
                        SECOND_AMOUNT)));

        long rows = rowCount();
        StatementTransactionEntity first = committedTransaction().execute(status ->
                entityManager.find(StatementTransactionEntity.class, firstKey));
        StatementTransactionEntity second = committedTransaction().execute(status ->
                entityManager.find(StatementTransactionEntity.class, secondKey));

        assertAll("two cards, one transaction identifier",
                () -> assertEquals(MASKED_CARD, PanMasker.maskCardNumber(SAME_TAIL_FULL_CARD),
                        "the two cards share one masked form, which is why the key is a token"),
                () -> assertNotEquals(CARD_TOKEN, SAME_TAIL_CARD_TOKEN,
                        "the two cards carry two tokens"),
                () -> assertEquals(2L, rows,
                        "one row per card; a masked key collapses both cards into one row"),
                () -> assertNotNull(first, "the first card's row is readable under its own key"),
                () -> assertNotNull(second, "the second card's row is readable under its own key"),
                () -> assertEquals(padded(FIRST_DESCRIPTION, 100), first.getDescription(),
                        "the first row holds what the first card wrote"),
                () -> assertEquals(padded(SECOND_DESCRIPTION, 100), second.getDescription(),
                        "the second row holds what the second card wrote"),
                () -> assertEquals(0, FIRST_AMOUNT.compareTo(first.getAmount()),
                        "the second write left the first amount alone"),
                () -> assertEquals(0, SECOND_AMOUNT.compareTo(second.getAmount()),
                        "the second row holds its own amount"));
    }

    /**
     * Asserts the key column refuses a masked card number and a full one.
     *
     * <p>{@code ck_statement_transaction_card_token} constrains {@code card_token} to 64 lower-case
     * hexadecimal characters, so neither sixteen digits nor twelve asterisks and four digits reach
     * the key. The entity refuses both ahead of the database, and this test drives the entity.</p>
     */
    @Test
    void theKeyRefusesAMaskedCardNumberAndAFullOne() {
        assertAll("values the key column does not hold",
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new StatementTransactionId(MASKED_CARD, ISOLATION_ROW_ID),
                        "a masked card number in the key position"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new StatementTransactionId(FULL_CARD, ISOLATION_ROW_ID),
                        "a full card number in the key position"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new StatementTransactionId(
                                CARD_TOKEN.toUpperCase(Locale.ROOT), ISOLATION_ROW_ID),
                        "a token in upper case"));
    }

    // ---------------------------------------------------------------------------------------
    // Group E. A second delivery of one event.
    // ---------------------------------------------------------------------------------------

    /**
     * Asserts a second delivery of one event updates its read-model row and adds none.
     *
     * <p>Three committed transactions stand in for two consumer invocations and a later read. The
     * source detects no duplicate. The transaction write at {@code app/cbl/CBTRN02C.cbl:L562-L579}
     * meets a duplicate key and reaches the abend routine, and every Customer Information Control
     * System file definition at {@code app/csd/CARDDEMO.CSD:L3-L9} disables recovery and
     * journalling. The composite key at {@code app/jcl/CREASTMT.JCL:L30} is what makes the second
     * write an update here.
     *
     * <p>Acknowledgement, retry and the route to the dead-letter topic sit in the messaging test
     * package. This assertion covers the persistence property alone.</p>
     */
    @Test
    void aSecondDeliveryOfOneEventUpdatesTheRowAndAddsNone() {
        StatementTransactionId key = new StatementTransactionId(CARD_TOKEN, UPSERT_ROW_ID);

        committedTransaction().executeWithoutResult(status ->
                entityManager.persist(row(UPSERT_ROW_ID, FIRST_DESCRIPTION, FIRST_AMOUNT)));
        long afterFirstDelivery = rowCount();

        committedTransaction().executeWithoutResult(status ->
                entityManager.merge(row(UPSERT_ROW_ID, SECOND_DESCRIPTION, SECOND_AMOUNT)));
        long afterSecondDelivery = rowCount();

        StatementTransactionEntity stored = committedTransaction().execute(status ->
                entityManager.find(StatementTransactionEntity.class, key));
        assertNotNull(stored, "the row is readable under the key both deliveries carried");

        assertAll("the second delivery of one event",
                () -> assertEquals(1L, afterFirstDelivery,
                        "the first delivery writes one row"),
                () -> assertEquals(afterFirstDelivery, afterSecondDelivery,
                        "the second delivery adds no row"),
                () -> assertEquals(1L, afterSecondDelivery,
                        "one key holds one row after both deliveries"),
                () -> assertEquals(padded(SECOND_DESCRIPTION, 100), stored.getDescription(),
                        "the row holds the description the second delivery carried"),
                () -> assertEquals(0, SECOND_AMOUNT.compareTo(stored.getAmount()),
                        "the row holds the amount the second delivery carried"),
                () -> assertEquals(AMOUNT_SCALE, stored.getAmount().scale(),
                        "the updated amount holds the scale its column declares"));
    }
}
