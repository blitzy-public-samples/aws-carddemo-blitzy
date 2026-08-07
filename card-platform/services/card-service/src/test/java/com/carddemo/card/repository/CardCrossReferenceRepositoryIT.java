package com.carddemo.card.repository;

import com.carddemo.card.entity.CardCrossReferenceEntity;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.entity.ProcessedEventEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Container-backed integration test over three repositories of the card service: the
 * {@code card_xref} replica, the outbox table and the duplicate-delivery marker table.
 *
 * <p>Four properties reach this class and no other. A card number and an account identifier each
 * read the replica, and a miss on either answers empty instead of throwing. A repeated account
 * identifier inserts, and the account read then returns both rows. The outbox finder returns
 * unpublished rows oldest first and honours the row limit its caller supplies. The marker table
 * answers one event identifier once and refuses a second row for it.
 *
 * <p>Provenance of the replica. {@code KEYS(16 0)} at {@code app/jcl/XREFFILE.jcl:L43} declares the
 * sixteen-character primary key of the Virtual Storage Access Method (VSAM) dataset, and
 * {@code RECORDSIZE(50 50)} at {@code app/jcl/XREFFILE.jcl:L44} fixes the fifty-byte record
 * {@code app/cpy/CVACT03Y.cpy} describes. {@code KEYS(11,25)} at {@code app/jcl/XREFFILE.jcl:L74}
 * places an eleven-character alternate-index key at offset 25, where {@code XREF-ACCT-ID} starts,
 * and {@code NONUNIQUEKEY} at {@code app/jcl/XREFFILE.jcl:L75} admits a repeated value.
 * {@code REPRO INFILE(XREFDATA) OUTFILE(XREFVSAM)} at {@code app/jcl/XREFFILE.jcl:L64} loads that
 * dataset in the Job Control Language (JCL) member, and {@code V2__seed.sql} carries the same load.
 *
 * <p>Provenance of the outbox. The one asynchronous handoff of the source is a Customer Information
 * Control System (CICS) queue write, {@code EXEC CICS WRITEQ TD QUEUE ('JOBS')} at
 * {@code app/cbl/CORPT00C.cbl:L517-L518}, in paragraph {@code WIRTE-JOBSUB-TDQ} at
 * {@code app/cbl/CORPT00C.cbl:L515}. That statement hands one record to a reader running later,
 * which is the shape the finder below reads. Each of the eight file definitions in
 * {@code app/csd/CARDDEMO.CSD} carries {@code JOURNAL(NO)} at {@code app/csd/CARDDEMO.CSD:L7} and
 * {@code RECOVERY(NONE)} at {@code app/csd/CARDDEMO.CSD:L9}.
 *
 * <p>Provenance of the marker. Paragraph {@code 2900-WRITE-TRANSACTION-FILE} at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} writes one posted transaction, and a repeated key fails
 * the status test at {@code app/cbl/CBTRN02C.cbl:L566} and reaches
 * {@code PERFORM 9999-ABEND-PROGRAM} at {@code app/cbl/CBTRN02C.cbl:L577}. No source program
 * detects a duplicate delivery, so the marker is an addition.
 *
 * <p>DEVIATION, list return type: {@link CardCrossReferenceRepository#findByAccountId(String)}
 * returns a {@link List}. Both alternate indexes of this service are non-unique, at
 * {@code app/jcl/XREFFILE.jcl:L74-L76} and at {@code app/jcl/CARDFILE.jcl:L85-L87}.
 *
 * <p>DEVIATION, argument type: the same finder takes a {@link String}. Column {@code account_id}
 * holds {@code CHAR(11)}, and all fifty records of {@code app/data/ASCII/cardxref.txt} carry
 * leading zeros in that field.
 *
 * <p>DEVIATION, revived path: the by-account read of the source, {@code 9150-GETCARD-BYACCT} at
 * {@code app/cbl/COCRDSLC.cbl:L779}, carries no {@code PERFORM} site anywhere under {@code app/}.
 * The target finder is reachable, because it backs the account filter of the card list.
 *
 * <p>DEVIATION, no message override: the target keeps the first message a validation collects. The
 * by-account branch of the source sets its message with no guard at
 * {@code app/cbl/COCRDSLC.cbl:L799}, and the card-number branch guards the same move at
 * {@code app/cbl/COCRDSLC.cbl:L759-L760}. The move at {@code app/cbl/COCRDSLC.cbl:L771} also
 * carries no guard, sitting outside the one at {@code app/cbl/COCRDSLC.cbl:L764-L766}. This class
 * asserts no message ordering; {@code domain/CardUpdateService} owns that behaviour.
 *
 * <p>DEVIATION, locator corrections: the exit label of {@code 9100-GETCARD-BYACCTCARD} sits at
 * {@code app/cbl/COCRDSLC.cbl:L775}, and the exit label of {@code 9150-GETCARD-BYACCT} sits at
 * {@code app/cbl/COCRDSLC.cbl:L810}.
 *
 * <p>DEVIATION, dead configuration: {@code CARDAIX} at {@code app/csd/CARDDEMO.CSD:L13-L14} names
 * the path defined at {@code app/jcl/CARDFILE.jcl:L100-L102}, and its only reader is the paragraph
 * at {@code app/cbl/COCRDSLC.cbl:L779}. {@code CXACAIX} at {@code app/csd/CARDDEMO.CSD:L63-L65}
 * names the cross-reference path defined at {@code app/jcl/XREFFILE.jcl:L90-L92}.
 *
 * <p>DEVIATION, marker and index shape: {@code processed_event} holds three columns and one
 * retention index, and the unpublished-outbox index is {@code ix_outbox_event_pending} over
 * {@code (created_at, event_id)} where {@code published} is false. Both shapes come from
 * {@code src/main/resources/db/migration/V1__schema.sql}.
 *
 * <p>DEVIATION, test wiring: {@code DataJpaTest}, {@code AutoConfigureTestDatabase} and
 * {@code ServiceConnection} appear nowhere here. Spring Boot 4.1.0 ships them in
 * {@code spring-boot-data-jpa-test}, {@code spring-boot-jdbc-test} and
 * {@code spring-boot-testcontainers}, and
 * {@code card-platform/services/card-service/pom.xml} declares none of the three.
 *
 * <p>How this class runs. One PostgreSQL 18.4 container serves the whole class, on the image tag
 * {@code card-platform/docker-compose.yml} also names. {@code src/main/resources/application.yml}
 * sits on the test classpath and sets {@code spring.flyway.create-schemas: true}, so Flyway creates
 * the schema, applies {@code V1__schema.sql} and loads the rows of {@code V2__seed.sql}. The same
 * file sets {@code spring.jpa.hibernate.ddl-auto: validate}, so a mapping that drifts from the
 * migration stops the context and every test here carries that check by starting.
 *
 * <p>{@link DynamicPropertySource} points three datasource properties at the container. Each
 * nested class carries {@link Transactional}, so a row a test writes rolls back and the fifty
 * seeded replica rows stand for the next test. The outbox relay runs on its own schedule in its own
 * transaction, and an uncommitted row is invisible to it. A fixed delay of one hour on
 * {@code carddemo.outbox.relay.fixed-delay-ms} leaves the relay one sweep, taken at start-up while
 * the outbox is empty.
 *
 * <p>Run this class from {@code card-platform/} with
 * {@code mvn -o -B -pl services/card-service -am verify}. The module activates Failsafe, and its
 * report under {@code target/failsafe-reports} names this class.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
                "USER_PASSWORD_HASH={noop}not-a-real-user-password",
                "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password",
                "carddemo.outbox.relay.fixed-delay-ms=3600000"
        })
@Testcontainers
@DisplayName("The cross-reference replica, the outbox and the marker over the migrated schema")
class CardCrossReferenceRepositoryIT {

    /** The image tag {@code card-platform/docker-compose.yml} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /**
     * The database name, the login name and the password of the container, one value for all
     * three. {@code card-platform/.env.example} declares the same value.
     */
    private static final String POSTGRES_CREDENTIAL = "carddemo";

    /**
     * The schema Flyway creates, from {@code spring.flyway.schemas} and
     * {@code spring.jpa.properties.hibernate.default_schema} in
     * {@code src/main/resources/application.yml}.
     */
    private static final String MIGRATED_SCHEMA = "card_service";

    /** Row count {@code V2__seed.sql} loads into {@code card_xref}. */
    private static final int SEEDED_ROW_COUNT = 50;

    /** Card number of seeded row one, sixteen characters wide. */
    private static final String ROW_1_CARD_NUMBER = "0500024453765740";

    /** Customer identifier of seeded row one, nine characters wide. */
    private static final String ROW_1_CUSTOMER_ID = "000000050";

    /** Account identifier of seeded row one, eleven characters wide. */
    private static final String ROW_1_ACCOUNT_ID = "00000000050";

    /**
     * Card number of the row a test inserts to place a second card on one account. The highest
     * seeded card number is {@code 9805583408996588}, so no seeded row carries this value.
     */
    private static final String INSERTED_CARD_NUMBER = "9999999999999999";

    /** Customer identifier every inserted replica row carries, nine digits. */
    private static final String INSERTED_CUSTOMER_ID = "000000999";

    /** Card number no seeded row carries, used for the by-card miss. */
    private static final String ABSENT_CARD_NUMBER = "7777777777777777";

    /**
     * Account identifier no seeded row carries, used for the by-account miss. The fixture holds
     * the fifty values {@code 00000000001} through {@code 00000000050}.
     */
    private static final String ABSENT_ACCOUNT_ID = "99999999999";

    /** Event type every outbox row below carries, from {@code messaging/CardUpdated}. */
    private static final String EVENT_TYPE = "CardUpdated";

    /** One short payload. The column holds 8192 octets and no assertion reads this text. */
    private static final String PAYLOAD = "{\"eventType\":\"CardUpdated\"}";

    /**
     * The instant every write below is derived from. A fixed value keeps an ordering assertion
     * independent of the clock.
     */
    private static final Instant BASE_INSTANT = Instant.parse("2024-03-01T12:00:00Z");

    /**
     * One of the two topic names the marker tests key on.
     *
     * <p>{@code consumed_topic} is half of the primary key of {@code processed_event} since
     * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql}. The card service
     * reads no topic today, so these two names stand in for the two a consumer added here would
     * read. They are the names two other services of this platform already read, so nothing about
     * them is invented.
     */
    private static final String FIRST_TOPIC = "transaction.authorized";

    /** The second of the two topic names the marker tests key on. See {@link #FIRST_TOPIC}. */
    private static final String SECOND_TOPIC = "account.state-changed";

    /** Reads whether one named index of one named table is unique, from the system catalog. */
    private static final String INDEX_IS_UNIQUE_SQL = """
            SELECT i.indisunique
              FROM pg_index i
              JOIN pg_class ic ON ic.oid = i.indexrelid
              JOIN pg_class tc ON tc.oid = i.indrelid
              JOIN pg_namespace n ON n.oid = tc.relnamespace
             WHERE n.nspname = ? AND tc.relname = ? AND ic.relname = ?
            """;

    /**
     * Reads the key columns of one named index, in key order.
     *
     * <p>{@code unnest(i.indkey) WITH ORDINALITY} is what makes the returned order the key order.
     * A join on {@code attnum = ANY(i.indkey)} returns the same names in table order, which says
     * nothing about which column leads.
     */
    private static final String INDEX_COLUMNS_SQL = """
            SELECT a.attname
              FROM pg_index i
              JOIN pg_class ic ON ic.oid = i.indexrelid
              JOIN pg_class tc ON tc.oid = i.indrelid
              JOIN pg_namespace n ON n.oid = tc.relnamespace
              JOIN unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
              JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = k.attnum
             WHERE n.nspname = ? AND tc.relname = ? AND ic.relname = ?
             ORDER BY k.ord
            """;

    /**
     * Reads the partial predicate of one named index, rendered as text.
     *
     * <p>A full index answers {@code null} here, so the result separates a partial index from a
     * full one.
     */
    private static final String INDEX_PREDICATE_SQL = """
            SELECT pg_get_expr(i.indpred, i.indrelid)
              FROM pg_index i
              JOIN pg_class ic ON ic.oid = i.indexrelid
              JOIN pg_class tc ON tc.oid = i.indrelid
              JOIN pg_namespace n ON n.oid = tc.relnamespace
             WHERE n.nspname = ? AND tc.relname = ? AND ic.relname = ?
            """;

    /** Reads the primary-key columns of one named table, in key order. */
    private static final String PRIMARY_KEY_COLUMNS_SQL = """
            SELECT a.attname
              FROM pg_index i
              JOIN pg_class tc ON tc.oid = i.indrelid
              JOIN pg_namespace n ON n.oid = tc.relnamespace
              JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = ANY(i.indkey)
             WHERE n.nspname = ? AND tc.relname = ? AND i.indisprimary
             ORDER BY a.attnum
            """;

    /**
     * The one container every test in this class shares.
     *
     * <p>The class name comes from {@code org.testcontainers.postgresql}, the package
     * Testcontainers 2.0.5 ships it in. {@link Container} on a static field gives one container
     * per class, and {@link Testcontainers} starts it before the Spring context reads a property
     * below.
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(POSTGRES_CREDENTIAL)
            .withUsername(POSTGRES_CREDENTIAL)
            .withPassword(POSTGRES_CREDENTIAL);

    /**
     * Points the Spring datasource at the running container.
     *
     * <p>Three properties leave here, each as a supplier the context resolves at refresh.
     * {@code src/main/resources/application.yml} carries every other datasource, Flyway and
     * persistence setting, and no line below repeats one. No line below creates the schema either:
     * {@code spring.flyway.create-schemas} does that.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CardCrossReferenceRepositoryIT::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container uniform resource locator with {@code currentSchema} appended.
     *
     * <p>Testcontainers already appends one query parameter of its own, so the separator is
     * {@code &} whenever a {@code ?} is present and {@code ?} otherwise. The search path matters to
     * a native statement, which {@code hibernate.default_schema} never qualifies.
     *
     * @return the connection uniform resource locator whose search path holds
     *         {@value #MIGRATED_SCHEMA}
     */
    private static String migratedSchemaUrl() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "currentSchema=" + MIGRATED_SCHEMA;
    }

    /** The cross-reference replica under test. */
    @Autowired
    private CardCrossReferenceRepository crossReferenceRepository;

    /** The outbox table the relay reads, under test here through its plain finder. */
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    /** The duplicate-delivery marker table under test. */
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    /** Reads the system catalog and row counts straight from the migrated database. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Answers which database the running context reached. */
    @Autowired
    private DataSource dataSource;

    /** Forces a pending write to the database, so a constraint answers where a test asserts. */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The schema Flyway created and Hibernate qualifies with, from
     * {@code spring.jpa.properties.hibernate.default_schema} in
     * {@code src/main/resources/application.yml}.
     */
    @Value("${spring.jpa.properties.hibernate.default_schema}")
    private String schema;

    // ---------------------------------------------------------------------------------------
    // Helpers. Each one answers a single question or performs a single write, and none asserts.
    // ---------------------------------------------------------------------------------------

    /**
     * Returns how many rows one migrated table holds.
     *
     * <p>A schema name and a table name are identifiers, so both join the statement text instead
     * of arriving as bind values. Both come from this class and never from a caller.
     *
     * @param table the unqualified table name
     * @return the row count
     */
    private long rowCount(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + schema + "." + table, Long.class);
        return count == null ? -1L : count;
    }

    /**
     * Returns the uniqueness flag of one named index, as a list so an absent index answers empty.
     *
     * @param table the unqualified table name
     * @param index the index name
     * @return one flag when the index exists, and an empty list otherwise
     */
    private List<Boolean> indexUniqueFlags(String table, String index) {
        return jdbcTemplate.queryForList(INDEX_IS_UNIQUE_SQL, Boolean.class, schema, table, index);
    }

    /**
     * Returns the key columns of one named index, in key order.
     *
     * @param table the unqualified table name
     * @param index the index name
     * @return the key column names, leading column first
     */
    private List<String> indexColumns(String table, String index) {
        return jdbcTemplate.queryForList(INDEX_COLUMNS_SQL, String.class, schema, table, index);
    }

    /**
     * Returns the partial predicate of one named index, rendered as text.
     *
     * @param table the unqualified table name
     * @param index the index name
     * @return the predicate text, or {@code null} for a full index or an absent one
     */
    private String indexPredicate(String table, String index) {
        List<String> predicates = jdbcTemplate.queryForList(
                INDEX_PREDICATE_SQL, String.class, schema, table, index);
        return predicates.size() == 1 ? predicates.getFirst() : null;
    }

    /**
     * Returns the primary-key columns of one migrated table, in key order.
     *
     * @param table the unqualified table name
     * @return the primary-key column names
     */
    private List<String> primaryKeyColumns(String table) {
        return jdbcTemplate.queryForList(PRIMARY_KEY_COLUMNS_SQL, String.class, schema, table);
    }

    /**
     * Writes one replica row and forces it to the database.
     *
     * <p>The returned instance is the one {@code save} answers with, which is the managed copy. An
     * entity carrying an assigned identifier is not new, so the store merges it and the argument
     * stays detached.
     *
     * @param cardNumber the sixteen-character card number, the primary key
     * @param customerId the nine-digit customer identifier
     * @param accountId  the eleven-digit account identifier
     * @return the managed row
     */
    private CardCrossReferenceEntity storeReplicaRow(String cardNumber, String customerId,
            String accountId) {
        CardCrossReferenceEntity stored = crossReferenceRepository.save(
                new CardCrossReferenceEntity(cardNumber, customerId, accountId, BASE_INSTANT));
        entityManager.flush();
        return stored;
    }

    /**
     * Writes one unpublished outbox row created at the given instant, and forces it to the
     * database.
     *
     * @param createdAt when the writer committed the row, which the constructor also uses as the
     *                  first attempt time
     * @return the managed row
     */
    private OutboxEventEntity storeOutboxRow(Instant createdAt) {
        OutboxEventEntity stored = outboxEventRepository.save(new OutboxEventEntity(
                UUID.randomUUID(), EVENT_TYPE, ROW_1_ACCOUNT_ID, PAYLOAD, createdAt));
        entityManager.flush();
        return stored;
    }

    /**
     * Writes one outbox row and marks it published, then forces both writes to the database.
     *
     * @param createdAt when the writer committed the row
     * @return the managed row, in {@link OutboxEventEntity.RelayState#PUBLISHED}
     */
    private OutboxEventEntity storePublishedOutboxRow(Instant createdAt) {
        OutboxEventEntity stored = storeOutboxRow(createdAt);
        stored.markPublished(createdAt.plusSeconds(1));
        entityManager.flush();
        return stored;
    }

    /**
     * Inserts one marker through plain Structured Query Language, bypassing the persistence
     * provider.
     *
     * <p>A repository {@code save} of an entity carrying an assigned identifier merges instead of
     * inserting, and a merge over an existing row updates it. A statement is what puts the primary
     * key of {@code processed_event} in the position of refusing a second row.
     *
     * <p>The topic half of the key takes {@link #FIRST_TOPIC}, so two calls collide on the key.
     *
     * @param eventId     the event identifier, one half of the primary key
     * @param processedAt when the consumer handled the delivery
     */
    private void insertMarker(UUID eventId, Instant processedAt) {
        insertMarker(eventId, processedAt, FIRST_TOPIC);
    }

    /**
     * Inserts one marker for one event on one named topic, through a statement.
     *
     * <p>{@code consumed_topic} is half of the primary key since
     * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql}, so a caller that
     * means to test the key has to choose the topic rather than leave it to a default.
     *
     * @param eventId       the event identifier, one half of the primary key
     * @param processedAt   when the consumer handled the delivery
     * @param consumedTopic the topic the delivery arrived on, the other half of the primary key
     */
    private void insertMarker(UUID eventId, Instant processedAt, String consumedTopic) {
        jdbcTemplate.update(
                "INSERT INTO " + schema
                        + ".processed_event (event_id, processed_at, consumed_topic)"
                        + " VALUES (?, ?, ?)",
                eventId, Timestamp.from(processedAt), consumedTopic);
    }

    /**
     * Returns the event identifiers of a finder result, in the order the finder returned them.
     *
     * @param rows the rows a finder returned
     * @return their event identifiers, in the same order
     */
    private static List<UUID> eventIdsOf(List<OutboxEventEntity> rows) {
        return rows.stream().map(OutboxEventEntity::getEventId).toList();
    }

    /**
     * Returns the card numbers of a replica result, sorted so an unordered query compares
     * predictably.
     *
     * <p>{@link CardCrossReferenceRepository#findByAccountId(String)} declares no ordering, so the
     * database may return rows in any order.
     *
     * @param rows the rows a finder returned
     * @return their card numbers, ascending
     */
    private static List<String> sortedCardNumbersOf(List<CardCrossReferenceEntity> rows) {
        return rows.stream().map(CardCrossReferenceEntity::getCardNumber).sorted().toList();
    }

    /**
     * Proves the context reached the container, and that the migration left the three tables in the
     * state every group below assumes.
     *
     * <p>A substituted in-memory database or a partial seed would leave a later assertion failing
     * for a reason unrelated to the finder under test.
     */
    @Nested
    @Transactional
    @DisplayName("The container serves the context, and the migration left the expected rows")
    class ContainerAndSeed {

        /**
         * Asserts the running context reached the PostgreSQL 18.4 container and no other database.
         *
         * <p>The product name rules out a substituted in-memory database. The mapped port rules out
         * the compose instance, which {@code card-platform/docker-compose.yml} publishes on a fixed
         * host port instead.
         *
         * @throws SQLException if the connection or its metadata cannot be read
         */
        @Test
        @DisplayName("the datasource reaches the container, on the port the container mapped")
        void theDatasourceReachesTheContainer() throws SQLException {
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                String productName = metaData.getDatabaseProductName();
                String productVersion = metaData.getDatabaseProductVersion();
                boolean urlCarriesMappedPort =
                        metaData.getURL().contains(":" + POSTGRES.getFirstMappedPort() + "/");
                assertAll(
                        () -> assertTrue(POSTGRES.isRunning(),
                                "the shared container is not running"),
                        () -> assertEquals("PostgreSQL", productName,
                                "the context reached " + productName
                                        + " instead of the PostgreSQL container"),
                        () -> assertTrue(productVersion.startsWith("18."),
                                "expected a PostgreSQL 18 server from image " + POSTGRES_IMAGE
                                        + ", found version " + productVersion),
                        () -> assertTrue(urlCarriesMappedPort,
                                "the connection carries a port other than the container mapped "
                                        + "port " + POSTGRES.getFirstMappedPort()));
            }
        }

        /**
         * Asserts the seed loaded exactly fifty replica rows, and that seeded row one reads back
         * field for field.
         *
         * <p>The count is exact and not merely positive, so a partial seed fails here.
         * {@code REPRO INFILE(XREFDATA) OUTFILE(XREFVSAM)} at {@code app/jcl/XREFFILE.jcl:L64}
         * loads the same fifty records into the Virtual Storage Access Method dataset. All fifty
         * records of {@code app/data/ASCII/cardxref.txt} are 36 characters wide, against the fifty
         * {@code app/jcl/XREFFILE.jcl:L44} declares.
         */
        @Test
        @DisplayName("V2__seed.sql loaded exactly fifty cross-reference rows")
        void theSeedLoadedFiftyReplicaRows() {
            Optional<CardCrossReferenceEntity> rowOne =
                    crossReferenceRepository.findByCardNumber(ROW_1_CARD_NUMBER);
            assertAll(
                    () -> assertEquals(SEEDED_ROW_COUNT, crossReferenceRepository.count(),
                            "the fixture holds fifty records, one per account"),
                    () -> assertEquals(SEEDED_ROW_COUNT, rowCount("card_xref"),
                            "the table count and the repository count must agree"),
                    () -> assertTrue(rowOne.isPresent(),
                            "seeded row one carries card number " + ROW_1_CARD_NUMBER),
                    () -> assertEquals(ROW_1_CUSTOMER_ID, rowOne.orElseThrow().getCustomerId(),
                            "XREF-CUST-ID PIC 9(09) at app/cpy/CVACT03Y.cpy:L6 keeps its leading "
                                    + "zeros in a CHAR(9) column"),
                    () -> assertEquals(ROW_1_ACCOUNT_ID, rowOne.orElseThrow().getAccountId(),
                            "XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7 keeps its leading "
                                    + "zeros in a CHAR(11) column"));
        }

        /**
         * Asserts the two runtime tables start empty.
         *
         * <p>{@code V2__seed.sql} writes nothing into either. A row in {@code outbox_event} would
         * reach the relay, and a row in {@code processed_event} would suppress a delivery no
         * consumer had handled.
         */
        @Test
        @DisplayName("outbox_event and processed_event carry no seeded row")
        void theRuntimeTablesStartEmpty() {
            assertAll(
                    () -> assertEquals(0L, rowCount("outbox_event"),
                            "the seed must leave the outbox to the writers"),
                    () -> assertEquals(0L, rowCount("processed_event"),
                            "the seed must leave the marker table to the consumers"));
        }

        /**
         * Asserts {@code processed_event} carries the three columns the migration declares and no
         * fourth.
         *
         * <p>{@code V1__schema.sql} declares {@code event_id}, {@code processed_at} and
         * {@code consumed_topic}, and the shape is the whole of the table's contract.
         */
        @Test
        @DisplayName("processed_event holds the identifier, the timestamp and the topic")
        void theMarkerTableHoldsThreeColumns() {
            List<String> columns = jdbcTemplate.queryForList(
                    """
                    SELECT a.attname
                      FROM pg_attribute a
                      JOIN pg_class c ON c.oid = a.attrelid
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = ? AND c.relname = 'processed_event'
                       AND a.attnum > 0 AND NOT a.attisdropped
                     ORDER BY a.attnum
                    """,
                    String.class, schema);
            assertEquals(List.of("event_id", "processed_at", "consumed_topic"), columns,
                    "the marker table holds exactly the three columns V1__schema.sql declares");
        }
    }

    /**
     * Proves the migration produced the keys and the indexes the dataset definitions declare.
     *
     * <p>A catalog check alone proves little about the account index, which is why
     * {@link CrossReferenceReads} inserts a repeated account identifier as well.
     */
    @Nested
    @Transactional
    @DisplayName("Keys and indexes, against the dataset definitions in app/jcl/XREFFILE.jcl")
    class KeysAndIndexes {

        /**
         * Asserts {@code card_number} is the primary key of {@code card_xref}.
         *
         * <p>{@code KEYS(16 0)} at {@code app/jcl/XREFFILE.jcl:L43} declares a sixteen-byte key at
         * offset zero, which is where {@code XREF-CARD-NUM PIC X(16)} sits in
         * {@code app/cpy/CVACT03Y.cpy:L5}.
         */
        @Test
        @DisplayName("card_number is the primary key of card_xref, from KEYS(16 0)")
        void theCardNumberIsThePrimaryKey() {
            assertEquals(List.of("card_number"), primaryKeyColumns("card_xref"),
                    "the primary key must be the card number alone, on one column");
        }

        /**
         * Asserts {@code idx_card_xref_account_id} keys on {@code account_id} and is not unique.
         *
         * <p>{@code KEYS(11,25)} at {@code app/jcl/XREFFILE.jcl:L74} places an eleven-byte key at
         * offset 25, and {@code NONUNIQUEKEY} at {@code app/jcl/XREFFILE.jcl:L75} admits a
         * repeated value. Offset 25 follows the sixteen characters of
         * {@code app/cpy/CVACT03Y.cpy:L5} and the nine of {@code app/cpy/CVACT03Y.cpy:L6}. An
         * assertion of uniqueness here would contradict the source.
         */
        @Test
        @DisplayName("idx_card_xref_account_id keys on account_id and carries no unique constraint")
        void theAccountIndexIsNotUnique() {
            List<Boolean> uniqueFlags = indexUniqueFlags("card_xref", "idx_card_xref_account_id");
            List<String> columns = indexColumns("card_xref", "idx_card_xref_account_id");
            assertAll(
                    () -> assertEquals(1, uniqueFlags.size(),
                            "V1__schema.sql must declare idx_card_xref_account_id exactly once"),
                    () -> assertEquals(List.of(Boolean.FALSE), uniqueFlags,
                            "NONUNIQUEKEY at app/jcl/XREFFILE.jcl:L75 admits a repeated account "
                                    + "identifier, so indisunique must be false"),
                    () -> assertEquals(List.of("account_id"), columns,
                            "KEYS(11,25) at app/jcl/XREFFILE.jcl:L74 keys the alternate index on "
                                    + "the account identifier alone"));
        }

        /**
         * Asserts {@code ix_outbox_event_pending} keys on {@code created_at} then {@code event_id},
         * and covers unpublished rows alone.
         *
         * <p>Column order carries the finder: the index supplies the arrival order the relay reads,
         * and the tie-break on the primary key settles which row comes next. The predicate keeps a
         * published row out of the index.
         */
        @Test
        @DisplayName("ix_outbox_event_pending keys on created_at then event_id, over pending rows")
        void thePendingOutboxIndexLeadsOnArrivalOrder() {
            List<String> columns = indexColumns("outbox_event", "ix_outbox_event_pending");
            String predicate = indexPredicate("outbox_event", "ix_outbox_event_pending");
            boolean predicateNamesTheFlag =
                    predicate != null && predicate.toLowerCase(Locale.ROOT).contains("published");
            assertAll(
                    () -> assertEquals(List.of("created_at", "event_id"), columns,
                            "created_at must lead, so the index serves the ascending read without "
                                    + "a sort"),
                    () -> assertNotNull(predicate,
                            "the index is partial, so pg_get_expr must render a predicate"),
                    () -> assertTrue(predicateNamesTheFlag,
                            "the predicate must name the published flag, found: " + predicate),
                    () -> assertEquals(List.of(Boolean.FALSE),
                            indexUniqueFlags("outbox_event", "ix_outbox_event_pending"),
                            "many rows share one arrival instant, so the index carries no unique "
                                    + "constraint"));
        }

        /**
         * Asserts {@code event_id} and {@code consumed_topic} together are the primary key of
         * {@code processed_event}, in that order.
         *
         * <p>The primary key is the duplicate-delivery guard as well as the key, which is what
         * makes a second insert for one identifier on one topic fail instead of passing.
         *
         * <p>The column order matters. {@code event_id} leads, so the index the key builds serves a
         * lookup that names the event alone, which is the read
         * {@code ProcessedEventRepository.existsByEventIdOnAnyTopic} makes. Leading with the topic
         * would leave that read scanning.
         *
         * <p>{@code src/main/resources/db/migration/V3__processed_event_topic_key.sql} widened the
         * key from {@code event_id} alone, because a delivery is identified by its event and the
         * stream it arrived on: two producing services assign identifiers independently, so the
         * narrow key suppressed a different event that happened to share one.
         */
        @Test
        @DisplayName("event_id and consumed_topic are the primary key of processed_event")
        void theMarkerPrimaryKeyIsTheEventIdentifier() {
            assertEquals(List.of("event_id", "consumed_topic"),
                    primaryKeyColumns("processed_event"),
                    "one identifier reaches at most one marker row per topic");
        }
    }

    /**
     * Proves both replica reads answer on a hit and on a miss, and that a repeated account
     * identifier inserts.
     *
     * <p>The fixture places one card on each of its fifty accounts, so the account read returns a
     * single element on seeded data whether the index is unique or not. An inserted second row is
     * what separates the two cases.
     */
    @Nested
    @Transactional
    @DisplayName("Both replica reads, on a hit and on a miss")
    class CrossReferenceReads {

        /**
         * Asserts a seeded card number reaches its row.
         *
         * <p>Reproduces the keyed read at {@code app/cbl/COTRN02C.cbl:L611-L619}, whose key
         * {@code KEYS(16 0)} at {@code app/jcl/XREFFILE.jcl:L43} declares. The paragraph
         * {@code 9100-GETCARD-BYACCTCARD} at {@code app/cbl/COCRDSLC.cbl:L736} keys its own read on
         * the card number alone: {@code app/cbl/COCRDSLC.cbl:L739} is a comment and
         * {@code app/cbl/COCRDSLC.cbl:L740} moves the card number.
         */
        @Test
        @DisplayName("a seeded card number reaches exactly its own row")
        void aSeededCardNumberReachesItsRow() {
            Optional<CardCrossReferenceEntity> found =
                    crossReferenceRepository.findByCardNumber(ROW_1_CARD_NUMBER);
            assertAll(
                    () -> assertTrue(found.isPresent(), "the seeded card number must reach a row"),
                    () -> assertEquals(ROW_1_CARD_NUMBER, found.orElseThrow().getCardNumber(),
                            "the read must answer with the row it was keyed on"),
                    () -> assertEquals(ROW_1_ACCOUNT_ID, found.orElseThrow().getAccountId(),
                            "row one of the fixture maps card " + ROW_1_CARD_NUMBER
                                    + " onto account " + ROW_1_ACCOUNT_ID));
        }

        /**
         * Asserts an absent card number yields an empty {@link Optional}.
         *
         * <p>A lookup miss is a normal outcome, not an error. The source takes its
         * {@code DFHRESP(NOTFND)} branch at {@code app/cbl/COTRN02C.cbl:L624}, reports
         * {@code Card Number NOT found...} and continues.
         */
        @Test
        @DisplayName("an absent card number yields an empty Optional, never null and never a throw")
        void anAbsentCardNumberYieldsAnEmptyOptional() {
            Optional<CardCrossReferenceEntity> found =
                    crossReferenceRepository.findByCardNumber(ABSENT_CARD_NUMBER);
            assertAll(
                    () -> assertNotNull(found, "the finder must answer with an Optional"),
                    () -> assertTrue(found.isEmpty(),
                            "no seeded row carries card number " + ABSENT_CARD_NUMBER));
        }

        /**
         * Asserts a seeded account reaches its cards.
         *
         * <p>Reproduces the alternate-index read at {@code app/cbl/COTRN02C.cbl:L578-L586}, whose
         * index {@code app/jcl/XREFFILE.jcl:L72-L76} defines. The by-account paragraph of the card
         * programs, {@code 9150-GETCARD-BYACCT} at {@code app/cbl/COCRDSLC.cbl:L779}, is never
         * performed, and the target finder is reachable.
         */
        @Test
        @DisplayName("a seeded account reaches the one card the fixture places on it")
        void aSeededAccountReachesItsCards() {
            List<CardCrossReferenceEntity> found =
                    crossReferenceRepository.findByAccountId(ROW_1_ACCOUNT_ID);
            assertAll(
                    () -> assertEquals(1, found.size(),
                            "the fixture carries fifty distinct account identifiers, one per card"),
                    () -> assertEquals(List.of(ROW_1_CARD_NUMBER), sortedCardNumbersOf(found),
                            "account " + ROW_1_ACCOUNT_ID + " carries card " + ROW_1_CARD_NUMBER));
        }

        /**
         * Asserts an absent account yields an empty {@link List}.
         *
         * <p>The fixture holds the fifty account identifiers {@code 00000000001} through
         * {@code 00000000050}. The source path {@code 9150-GETCARD-BYACCT} at
         * {@code app/cbl/COCRDSLC.cbl:L779} is unreachable, and the target finder backs the account
         * filter of the card list.
         */
        @Test
        @DisplayName("an absent account yields an empty List, never null and never a throw")
        void anAbsentAccountYieldsAnEmptyList() {
            List<CardCrossReferenceEntity> found =
                    crossReferenceRepository.findByAccountId(ABSENT_ACCOUNT_ID);
            assertAll(
                    () -> assertNotNull(found, "the finder must answer with a List"),
                    () -> assertTrue(found.isEmpty(),
                            "no seeded row carries account " + ABSENT_ACCOUNT_ID));
        }

        /**
         * Asserts each miss answers in its own shape, so a caller can tell the two apart.
         *
         * <p>The by-card miss answers an empty {@link Optional} and the by-account miss answers an
         * empty {@link List}. Neither shares an outcome with the other, and neither answers null.
         */
        @Test
        @DisplayName("the two misses answer in their own shapes, each distinguishable")
        void theTwoMissesAnswerInTheirOwnShapes() {
            Optional<CardCrossReferenceEntity> byCard =
                    crossReferenceRepository.findByCardNumber(ABSENT_CARD_NUMBER);
            List<CardCrossReferenceEntity> byAccount =
                    crossReferenceRepository.findByAccountId(ABSENT_ACCOUNT_ID);
            assertAll(
                    () -> assertEquals(Optional.empty(), byCard,
                            "the by-card miss answers an absent Optional"),
                    () -> assertEquals(List.of(), byAccount,
                            "the by-account miss answers an empty List"),
                    () -> assertEquals(1,
                            crossReferenceRepository.findByAccountId(ROW_1_ACCOUNT_ID).size(),
                            "a miss leaves no state behind, so the next read still answers"));
        }

        /**
         * Asserts a second card inserts onto one account, and that the account read returns both.
         *
         * <p>A unique index would refuse the insert, and the flush drives it to the database before
         * the assertion runs. The pair of counts is what gives the list return type of
         * {@link CardCrossReferenceRepository#findByAccountId(String)} its meaning. The same pair
         * is why the Customer Information Control System response {@code DUPREC} on an
         * alternate-index browse is an ordinary success. {@code NONUNIQUEKEY} sits at
         * {@code app/jcl/XREFFILE.jcl:L75}.
         */
        @Test
        @DisplayName("a repeated account identifier inserts, and findByAccountId returns both rows")
        void oneAccountCarriesTwoCards() {
            List<String> before = sortedCardNumbersOf(
                    crossReferenceRepository.findByAccountId(ROW_1_ACCOUNT_ID));

            storeReplicaRow(INSERTED_CARD_NUMBER, INSERTED_CUSTOMER_ID, ROW_1_ACCOUNT_ID);

            List<String> after = sortedCardNumbersOf(
                    crossReferenceRepository.findByAccountId(ROW_1_ACCOUNT_ID));
            assertAll(
                    () -> assertEquals(List.of(ROW_1_CARD_NUMBER), before,
                            "the fixture places exactly one card on each of its fifty accounts"),
                    () -> assertEquals(2, after.size(),
                            "a unique index on account_id would have refused the second row"),
                    () -> assertEquals(List.of(ROW_1_CARD_NUMBER, INSERTED_CARD_NUMBER), after,
                            "the read must answer with both rows of account " + ROW_1_ACCOUNT_ID
                                    + " and no other"),
                    () -> assertEquals(SEEDED_ROW_COUNT + 1, rowCount("card_xref"),
                            "the insert added one row and replaced none"));
        }
    }

    /**
     * Proves the plain outbox finder returns unpublished rows oldest first and honours its limit.
     *
     * <p>{@code outbox/OutboxRelay} polls that finder. Every row a test writes below stays inside
     * the rolled-back transaction of the test, so the relay never sees one.
     */
    @Nested
    @Transactional
    @DisplayName("The plain outbox finder, over the migrated schema")
    class OutboxRelayReads {

        /**
         * Asserts a published row stays out of the result.
         *
         * <p>A published row in the result would make the relay publish it a second time.
         */
        @Test
        @DisplayName("the finder returns the unpublished rows and leaves a published one out")
        void theFinderReturnsUnpublishedRowsOnly() {
            OutboxEventEntity pending = storeOutboxRow(BASE_INSTANT.minusSeconds(300));
            OutboxEventEntity published = storePublishedOutboxRow(BASE_INSTANT.minusSeconds(200));

            List<UUID> returned = eventIdsOf(
                    outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc(Limit.of(10)));
            assertAll(
                    () -> assertEquals(List.of(pending.getEventId()), returned,
                            "only the unpublished row awaits publication"),
                    () -> assertTrue(published.isPublished(),
                            "markPublished sets the flag the finder filters on"),
                    () -> assertEquals(OutboxEventEntity.RelayState.PUBLISHED,
                            published.getRelayState(),
                            "the flag and the relay state move together"));
        }

        /**
         * Asserts the result arrives by {@code created_at} ascending.
         *
         * <p>The three writes below run newest first, so an unordered query cannot pass by
         * accident. Ascending order is the order the writers committed the rows.
         */
        @Test
        @DisplayName("rows written newest first come back oldest first")
        void theFinderOrdersByCreatedAtAscending() {
            OutboxEventEntity newest = storeOutboxRow(BASE_INSTANT.minusSeconds(100));
            OutboxEventEntity oldest = storeOutboxRow(BASE_INSTANT.minusSeconds(300));
            OutboxEventEntity middle = storeOutboxRow(BASE_INSTANT.minusSeconds(200));

            List<UUID> returned = eventIdsOf(
                    outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc(Limit.of(10)));
            assertEquals(
                    List.of(oldest.getEventId(), middle.getEventId(), newest.getEventId()),
                    returned,
                    "ix_outbox_event_pending leads on created_at, and the finder reads it in that "
                            + "order");
        }

        /**
         * Asserts the caller-supplied {@link Limit} is honoured, and takes the oldest rows.
         *
         * <p>{@code carddemo.outbox.relay.batch-size} in
         * {@code src/main/resources/application.yml} holds the value the relay passes. A limit that
         * returned the newest rows would starve the oldest one behind a steady stream of writes.
         */
        @Test
        @DisplayName("a limit of two returns the two oldest rows of three")
        void theFinderHonoursItsLimitAndTakesTheOldest() {
            OutboxEventEntity oldest = storeOutboxRow(BASE_INSTANT.minusSeconds(300));
            OutboxEventEntity middle = storeOutboxRow(BASE_INSTANT.minusSeconds(200));
            storeOutboxRow(BASE_INSTANT.minusSeconds(100));

            List<UUID> returned = eventIdsOf(
                    outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc(Limit.of(2)));
            assertAll(
                    () -> assertEquals(2, returned.size(), "the limit bounds one batch"),
                    () -> assertEquals(List.of(oldest.getEventId(), middle.getEventId()), returned,
                            "the batch takes the two longest-waiting rows"));
        }

        /**
         * Asserts the round trip through {@link OutboxEventEntity#markPublished(Instant)} and the
         * inherited {@code save} removes a row from the finder.
         *
         * <p>The relay performs that pair in a transaction of its own, separate from the
         * transaction of the writer that produced the row.
         */
        @Test
        @DisplayName("a row marked published and saved no longer reaches the finder")
        void markPublishedRemovesTheRowFromTheFinder() {
            OutboxEventEntity first = storeOutboxRow(BASE_INSTANT.minusSeconds(300));
            OutboxEventEntity second = storeOutboxRow(BASE_INSTANT.minusSeconds(200));
            List<UUID> before = eventIdsOf(
                    outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc(Limit.of(10)));

            first.markPublished(BASE_INSTANT);
            outboxEventRepository.save(first);
            entityManager.flush();

            List<UUID> after = eventIdsOf(
                    outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc(Limit.of(10)));
            assertAll(
                    () -> assertEquals(List.of(first.getEventId(), second.getEventId()), before,
                            "both rows await publication before the mark"),
                    () -> assertEquals(List.of(second.getEventId()), after,
                            "the published row leaves the backlog and the other stays"),
                    () -> assertEquals(BASE_INSTANT, first.getPublishedAt(),
                            "the mark records when the publish happened"));
        }

        /**
         * Asserts {@code aggregate_id} carries eleven digits, leading zeros included.
         *
         * <p>{@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} fixes that width,
         * and the column is the Kafka message key. Clearing the persistence context forces a read
         * from the column instead of from memory.
         */
        @Test
        @DisplayName("an eleven-digit aggregate identifier reads back with its leading zeros")
        void anElevenDigitAggregateIdentifierRoundTrips() {
            UUID eventId = storeOutboxRow(BASE_INSTANT.minusSeconds(60)).getEventId();
            entityManager.clear();

            OutboxEventEntity reread = outboxEventRepository.findById(eventId).orElseThrow();
            assertAll(
                    () -> assertEquals(ROW_1_ACCOUNT_ID, reread.getAggregateId(),
                            "a numeric column would return 50 for a stored 00000000050"),
                    () -> assertEquals(OutboxEventEntity.AGGREGATE_ID_LENGTH,
                            reread.getAggregateId().length(),
                            "the column holds exactly eleven characters"),
                    () -> assertEquals(EVENT_TYPE, reread.getEventType(),
                            "the event type reads back unchanged"));
        }

        /**
         * Asserts {@code existsByRelayState} reports a row the relay gave up on.
         *
         * <p>{@code config/ReadinessHealthConfig} reads that answer, and
         * {@link OutboxEventEntity#MAX_DELIVERY_ATTEMPTS} failed attempts is what moves a row to
         * {@link OutboxEventEntity.RelayState#ABANDONED}. An abandoned row also leaves the plain
         * finder, because the relay offers it no further attempt.
         */
        @Test
        @DisplayName("existsByRelayState reports an abandoned row, and none before one exists")
        void existsByRelayStateReportsAnAbandonedRow() {
            OutboxEventEntity row = storeOutboxRow(BASE_INSTANT.minusSeconds(300));
            boolean abandonedBefore = outboxEventRepository.existsByRelayState(
                    OutboxEventEntity.RelayState.ABANDONED);

            for (int attempt = 0; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
                row.recordFailure("broker refused the publish", BASE_INSTANT, BASE_INSTANT);
            }
            entityManager.flush();

            assertAll(
                    () -> assertFalse(abandonedBefore,
                            "a freshly written row is PENDING, so nothing is abandoned yet"),
                    () -> assertEquals(OutboxEventEntity.RelayState.ABANDONED, row.getRelayState(),
                            OutboxEventEntity.MAX_DELIVERY_ATTEMPTS
                                    + " failed attempts abandon a row"),
                    () -> assertTrue(outboxEventRepository.existsByRelayState(
                            OutboxEventEntity.RelayState.ABANDONED),
                            "the readiness check reads this answer"));
        }
    }

    /**
     * Proves the duplicate-delivery guard answers before and after a marker is written, and that
     * the primary key refuses a second marker for one identifier.
     *
     * <p>A consumer writes its marker in the same local transaction as its side effects, so the
     * guard and the effects commit or fail together. The card service consumes no topic today, so
     * nothing calls this interface in production yet.
     */
    @Nested
    @Transactional
    @DisplayName("The duplicate-delivery guard over processed_event")
    class DuplicateDeliveryGuard {

        /**
         * Asserts an identifier no consumer has handled answers {@code false}.
         *
         * <p>A {@code false} answer is what lets the caller apply its side effects.
         */
        @Test
        @DisplayName("an unseen event identifier is not yet processed")
        void anUnseenIdentifierIsNotYetProcessed() {
            assertFalse(processedEventRepository.existsByEventIdOnAnyTopic(UUID.randomUUID()),
                    "the table starts empty, so no identifier is marked");
        }

        /**
         * Asserts a stored marker answers {@code true} for its own identifier and for no other.
         *
         * <p>The write goes through the inherited {@code save}, which is the call a consumer makes.
         * A {@code true} answer is what makes the caller skip its side effects and acknowledge the
         * delivery.
         */
        @Test
        @DisplayName("a stored marker reports its own identifier as processed")
        void aStoredMarkerReportsProcessed() {
            UUID eventId = UUID.randomUUID();
            UUID otherEventId = UUID.randomUUID();

            processedEventRepository.save(
                    new ProcessedEventEntity(eventId, BASE_INSTANT, FIRST_TOPIC));
            entityManager.flush();

            assertAll(
                    () -> assertTrue(processedEventRepository.existsByEventIdOnAnyTopic(eventId),
                            "the marker names the delivery the consumer handled"),
                    () -> assertFalse(processedEventRepository.existsByEventIdOnAnyTopic(otherEventId),
                            "one marker answers for one identifier only"),
                    () -> assertEquals(1L, rowCount("processed_event"),
                            "the save wrote exactly one marker"));
        }

        /**
         * Asserts the primary key refuses a second marker for one identifier on one topic.
         *
         * <p>The refusal is what makes the guard durable, not advisory: two consumers racing
         * one delivery cannot both write a marker. The statement runs last in this test, because
         * PostgreSQL aborts the surrounding transaction once it fails. The source has no such
         * guard, and a repeated key there reaches the abend routine by way of
         * {@code app/cbl/CBTRN02C.cbl:L562-L579}.
         *
         * <p>Both inserts name {@link #FIRST_TOPIC}, so they collide on the whole key. That is the
         * redelivery case, and suppressing it is the purpose of the guard.
         */
        @Test
        @DisplayName("a second marker for one identifier on one topic is refused by the primary key")
        void aSecondMarkerForOneIdentifierIsRefused() {
            UUID eventId = UUID.randomUUID();
            insertMarker(eventId, BASE_INSTANT, FIRST_TOPIC);

            assertTrue(processedEventRepository.existsByEventIdOnAnyTopic(eventId),
                    "the first marker must be present before the second insert is attempted");
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertMarker(eventId, BASE_INSTANT.plusSeconds(1), FIRST_TOPIC),
                    "pk_processed_event must refuse a second row for one event identifier on one"
                            + " topic");
        }

        /**
         * Asserts one identifier is claimable once per topic rather than once per service.
         *
         * <p>This is the property {@code V3__processed_event_topic_key.sql} exists for. Event
         * identifiers are assigned by the service that publishes the event, and different producing
         * services assign them independently, so two different events on two topics may carry one
         * identifier without either producer being at fault. Keyed on the identifier alone, the
         * second insert below was refused, and a consumer reading that refusal concluded it had
         * already handled the event and wrote nothing at all: right for a redelivery, and a silently
         * dropped effect for a different event. Keyed on the identifier and the topic, both rows are
         * accepted and each topic keeps its own guard.
         *
         * <p>The card service reads no topic today, so no listener here can reach the defect. The
         * test holds the property for the first consumer this schema serves, which is the same reason
         * the table is declared at all.
         */
        @Test
        @DisplayName("one identifier is claimable once per topic, not once per service")
        void oneIdentifierIsClaimableOncePerTopic() {
            UUID sharedEventId = UUID.randomUUID();

            insertMarker(sharedEventId, BASE_INSTANT, FIRST_TOPIC);
            insertMarker(sharedEventId, BASE_INSTANT.plusSeconds(1), SECOND_TOPIC);

            assertAll("one identifier on two topics",
                    () -> assertEquals(2L, rowCount("processed_event"),
                            "both deliveries claimed, because a delivery is identified by its event"
                                    + " and the topic it arrived on"),
                    () -> assertTrue(processedEventRepository.existsById(
                                    new ProcessedEventEntity.ProcessedEventId(sharedEventId,
                                            FIRST_TOPIC)),
                            "the first topic carries its own marker"),
                    () -> assertTrue(processedEventRepository.existsById(
                                    new ProcessedEventEntity.ProcessedEventId(sharedEventId,
                                            SECOND_TOPIC)),
                            "the second topic carries its own marker, which the narrow key refused"),
                    () -> assertFalse(processedEventRepository.existsById(
                                    new ProcessedEventEntity.ProcessedEventId(sharedEventId,
                                            ProcessedEventEntity.NO_CONSUMED_TOPIC)),
                            "a topic no delivery named carries no marker"));
        }
    }
}
