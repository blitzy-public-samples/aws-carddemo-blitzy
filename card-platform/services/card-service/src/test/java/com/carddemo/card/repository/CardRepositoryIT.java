package com.carddemo.card.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.card.entity.CardEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Container-backed integration test for {@link CardRepository} over the migrated {@code card}
 * schema.
 *
 * <p>Three properties reach this class and no other. The migration produces the columns, the
 * primary key and the index the entity mapping validates against. Index
 * {@code idx_card_account_id} carries no unique constraint. Keyset paging over
 * {@code card_number} returns every row exactly once.
 *
 * <p>Provenance. {@code KEYS(16 0)} at {@code app/jcl/CARDFILE.jcl:L54} declares the sixteen-byte
 * primary key of the Virtual Storage Access Method (VSAM) dataset, and
 * {@code RECORDSIZE(150 150)} at {@code app/jcl/CARDFILE.jcl:L55} fixes the record width that
 * {@code app/cpy/CVACT02Y.cpy} describes. {@code KEYS(11 16)} at {@code app/jcl/CARDFILE.jcl:L85}
 * places an eleven-byte alternate-index key at offset 16, where {@code CARD-ACCT-ID} starts.
 * {@code REPRO INFILE(CARDDATA) OUTFILE(CARDVSAM)} at {@code app/jcl/CARDFILE.jcl:L75} loads that
 * dataset in the Job Control Language (JCL) member, and {@code V2__seed.sql} carries the load.
 *
 * <p>Paging provenance. The source pages a Customer Information Control System (CICS) browse, and
 * {@code 9000-READ-FORWARD} fills a page of {@code WS-MAX-SCREEN-LINES} rows, declared
 * {@code VALUE 7} at {@code app/cbl/COCRDLIC.cbl:L177-L178}. The same paragraph then reads one row
 * more at {@code app/cbl/COCRDLIC.cbl:L1197-L1205} and derives the next-page flag at
 * {@code app/cbl/COCRDLIC.cbl:L1191-L1216}. {@code 9500-FILTER-RECORDS} at
 * {@code app/cbl/COCRDLIC.cbl:L1382-L1409} runs ahead of the row counter at
 * {@code app/cbl/COCRDLIC.cbl:L1162-L1163}, so a filtered page holds matching rows.
 *
 * <p>DEVIATION, list return type: {@link CardRepository#findByAccountId(String)} returns a
 * {@link List}. Both alternate indexes are non-unique, at {@code app/jcl/CARDFILE.jcl:L85-L87} and
 * at {@code app/jcl/XREFFILE.jcl:L74-L76}.
 *
 * <p>DEVIATION, exclusive cursor: both page finders exclude the cursor row, and the source browses
 * inclusively. The source also overwrites its saved forward cursor with the keys of the lookahead
 * row at {@code app/cbl/COCRDLIC.cbl:L1212-L1214}.
 *
 * <p>DEVIATION, locator corrections: the forward next-page flag comes from
 * {@code app/cbl/COCRDLIC.cbl:L1191-L1216}, and {@code app/cbl/COCRDLIC.cbl:L1284-L1287} presets
 * the backward counter and the backward flag instead. The exit label of
 * {@code 9100-GETCARD-BYACCTCARD} sits at {@code app/cbl/COCRDSLC.cbl:L775}, and the exit label of
 * {@code 9150-GETCARD-BYACCT} sits at {@code app/cbl/COCRDSLC.cbl:L810}.
 *
 * <p>DEVIATION, dead configuration: {@code CARDAIX} at {@code app/csd/CARDDEMO.CSD:L13-L14} names
 * the path defined at {@code app/jcl/CARDFILE.jcl:L100-L102}. Its only reader,
 * {@code 9150-GETCARD-BYACCT} at {@code app/cbl/COCRDSLC.cbl:L779}, carries no {@code PERFORM}
 * site.
 *
 * <p>How this class runs. One PostgreSQL 18.4 container serves the whole class, on the image tag
 * {@code card-platform/docker-compose.yml} also names. {@code src/main/resources/application.yml}
 * sets {@code spring.flyway.create-schemas: true}, so Flyway creates the schema, applies
 * {@code V1__schema.sql} and loads the fifty rows of {@code V2__seed.sql}. The same file sets
 * {@code spring.jpa.hibernate.ddl-auto: validate}. A mapping that drifts from the migration stops
 * the context, so every test here carries that check by starting.
 *
 * <p>{@link DynamicPropertySource} points three datasource properties at the container.
 * {@code DataJpaTest} and {@code AutoConfigureTestDatabase} appear nowhere here: Spring Boot 4.1.0
 * ships them in {@code spring-boot-data-jpa-test} and {@code spring-boot-jdbc-test}, and
 * {@code card-platform/services/card-service/pom.xml} declares neither artifact.
 * {@code ServiceConnection} is absent for the same reason. Each nested class carries
 * {@link Transactional}, so a row a test writes rolls back and the fifty seeded rows stand for the
 * next test.
 *
 * <p>Running this class takes one explicit command, because
 * {@code card-platform/services/card-service/pom.xml} binds no Failsafe goal and
 * {@code card-platform/pom.xml} carries the plugin under {@code pluginManagement} alone. The
 * command is
 * {@code mvn -pl services/card-service org.apache.maven.plugins:maven-failsafe-plugin:3.5.6:integration-test}.
 * A plain {@code mvn verify} reports success without running one assertion below.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH={noop}not-a-real-admin-password",
                "USER_PASSWORD_HASH={noop}not-a-real-user-password",
                "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password"
        })
@Testcontainers
@DisplayName("CardRepository over the migrated card schema: keys, the account index, and paging")
class CardRepositoryIT {

    /** The image tag {@code card-platform/docker-compose.yml} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /**
     * The database name, the login name and the password of the container, one value for all
     * three. {@code card-platform/.env.example} declares the same value.
     */
    private static final String POSTGRES_CREDENTIAL = "carddemo";

    /** Row count {@code V2__seed.sql} loads into {@code card}. */
    private static final int SEEDED_ROW_COUNT = 50;

    /** The two migration versions Flyway applies, in the order it applies them. */
    private static final List<String> MIGRATION_VERSIONS = List.of("1", "2");

    /** Card number of seeded row one in ascending key order. */
    private static final String ROW_1_CARD_NUMBER = "0500024453765740";

    /** Account identifier of seeded row one, eleven characters wide. */
    private static final String ROW_1_ACCOUNT_ID = "00000000050";

    /** Card number of seeded row two, the mismatched half of the two-filter case. */
    private static final String ROW_2_CARD_NUMBER = "0683586198171516";

    /** The seven card numbers a caller that chooses a page of seven displays first. */
    private static final List<String> FIRST_PAGE_CARD_NUMBERS = List.of(
            "0500024453765740",
            "0683586198171516",
            "0923877193247330",
            "0927987108636232",
            "0982496213629795",
            "1014086565224350",
            "1142167692878931");

    /** Card number of seeded row seven, the last row of that first page. */
    private static final String ROW_7_CARD_NUMBER = "1142167692878931";

    /** Card number of seeded row eight, the row the source lookahead reads. */
    private static final String ROW_8_CARD_NUMBER = "1561409106491600";

    /** Card number of seeded row nine, where an overwritten cursor would start. */
    private static final String ROW_9_CARD_NUMBER = "2745303720002090";

    /** Card number of seeded row 42. Eight rows follow it. */
    private static final String ROW_42_CARD_NUMBER = "8112545834239735";

    /** Card number of seeded row 43. Seven rows follow it. */
    private static final String ROW_43_CARD_NUMBER = "8262593602473076";

    /** Card number of seeded row 50, the highest value the fixture carries. */
    private static final String ROW_50_CARD_NUMBER = "9805583408996588";

    /**
     * Card number of the row a test inserts to place a second card on one account. Every inserted
     * value below sorts above {@link #ROW_50_CARD_NUMBER}, so no inserted row collides with a
     * seeded one and every inserted row lands on the final page.
     */
    private static final String INSERTED_DUPLICATE_CARD_NUMBER = "9999999999999999";

    /** Card numbers of the six rows a test inserts to fill one account to seven cards. */
    private static final List<String> INSERTED_FILLER_CARD_NUMBERS = List.of(
            "9999999999999991",
            "9999999999999992",
            "9999999999999993",
            "9999999999999994",
            "9999999999999995",
            "9999999999999996");

    /**
     * Card verification value every inserted row carries. The value is three digits, which
     * {@code ck_card_verification_value_digits} requires, and plainly synthetic.
     */
    private static final String SYNTHETIC_CARD_VERIFICATION_VALUE = "000";

    /** Embossed name every inserted row carries. */
    private static final String SYNTHETIC_EMBOSSED_NAME = "Integration Test Row";

    /** Expiration date every inserted row carries. */
    private static final LocalDate SYNTHETIC_EXPIRATION_DATE = LocalDate.of(2025, 12, 31);

    /**
     * Active status every inserted row carries, from {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.}
     * at {@code app/cbl/COCRDUPC.cbl:L91}.
     */
    private static final String ACTIVE_STATUS_YES = "Y";

    /**
     * Reads whether one named index of one named table is unique, from the system catalog.
     *
     * <p>The schema arrives as a bind value against {@code pg_namespace}, so no identifier reaches
     * the statement text.
     */
    private static final String INDEX_IS_UNIQUE_SQL = """
            SELECT i.indisunique
              FROM pg_index i
              JOIN pg_class ic ON ic.oid = i.indexrelid
              JOIN pg_class tc ON tc.oid = i.indrelid
              JOIN pg_namespace n ON n.oid = tc.relnamespace
             WHERE n.nspname = ? AND tc.relname = ? AND ic.relname = ?
            """;

    /** Reads the key columns of one named index, in key order. */
    private static final String INDEX_COLUMNS_SQL = """
            SELECT a.attname
              FROM pg_index i
              JOIN pg_class ic ON ic.oid = i.indexrelid
              JOIN pg_class tc ON tc.oid = i.indrelid
              JOIN pg_namespace n ON n.oid = tc.relnamespace
              JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = ANY(i.indkey)
             WHERE n.nspname = ? AND tc.relname = ? AND ic.relname = ?
             ORDER BY a.attnum
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
     * Testcontainers 2.0.5 ships it in.
     * {@code org.testcontainers.containers.PostgreSQLContainer} carries a deprecation on the same
     * artifact. {@link Container} on a static field gives one container per class, and
     * {@link Testcontainers} starts it before the Spring context reads a property below.
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
     * {@code src/main/resources/application.yml} sits on the test classpath and carries every
     * other datasource, Flyway and persistence setting, and no line below repeats one. No line
     * below creates the schema either: {@code spring.flyway.create-schemas} does that.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** The repository under test. */
    @Autowired
    private CardRepository cardRepository;

    /** Reads the system catalog and the Flyway history straight from the migrated database. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Answers which database the running context reached. */
    @Autowired
    private DataSource dataSource;

    /** Forces a pending insert to the database, so a constraint answers where a test asserts. */
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
     * One page as a caller assembles it from a repository result.
     *
     * @param displayed      the rows the caller shows, in ascending card-number order
     * @param nextPageExists whether the repository returned a row beyond that page
     */
    private record CardPage(List<CardEntity> displayed, boolean nextPageExists) {
    }

    /**
     * Fetches one page forward and derives the next-page flag by lookahead.
     *
     * <p>The caller asks for one row more than it displays, which is the extra
     * {@code EXEC CICS READNEXT} at {@code app/cbl/COCRDLIC.cbl:L1197-L1205}. A row beyond the
     * page sets the flag, matching {@code app/cbl/COCRDLIC.cbl:L1207-L1211}, and its absence
     * clears the flag, matching {@code app/cbl/COCRDLIC.cbl:L1215-L1216}. No count query runs.
     *
     * @param afterCardNumber the exclusive cursor, or {@code null} for the first page
     * @param accountId       the account filter, or {@code null} to apply none
     * @param cardNumber      the card-number filter, or {@code null} to apply none
     * @param pageSize        the row count the caller displays, at least one
     * @return the page and its flag
     * @throws IllegalArgumentException if the page size is below one
     */
    private CardPage forwardPage(String afterCardNumber, String accountId, String cardNumber,
            int pageSize) {
        requirePositivePageSize(pageSize);
        List<CardEntity> fetched = cardRepository.findPageForward(
                afterCardNumber, accountId, cardNumber, Limit.of(pageSize + 1));
        boolean nextPageExists = fetched.size() > pageSize;
        List<CardEntity> displayed = nextPageExists ? fetched.subList(0, pageSize) : fetched;
        return new CardPage(List.copyOf(displayed), nextPageExists);
    }

    /**
     * Fetches one page backward and returns it in ascending card-number order.
     *
     * <p>{@link CardRepository#findPageBackward(String, String, String, Limit)} orders rows
     * descending, and this helper reverses them. The source reaches the same order by filling its
     * screen array from the high index down at {@code app/cbl/COCRDLIC.cbl:L1338-L1346}.
     *
     * @param beforeCardNumber the exclusive cursor, or {@code null} for the last page
     * @param accountId        the account filter, or {@code null} to apply none
     * @param cardNumber       the card-number filter, or {@code null} to apply none
     * @param pageSize         the row count the caller displays, at least one
     * @return the page in ascending order, with a flag reporting a further row before it
     * @throws IllegalArgumentException if the page size is below one
     */
    private CardPage backwardPage(String beforeCardNumber, String accountId, String cardNumber,
            int pageSize) {
        requirePositivePageSize(pageSize);
        List<CardEntity> fetched = cardRepository.findPageBackward(
                beforeCardNumber, accountId, cardNumber, Limit.of(pageSize + 1));
        boolean previousPageExists = fetched.size() > pageSize;
        List<CardEntity> descending = previousPageExists ? fetched.subList(0, pageSize) : fetched;
        List<CardEntity> ascending = new ArrayList<>(descending);
        Collections.reverse(ascending);
        return new CardPage(List.copyOf(ascending), previousPageExists);
    }

    /**
     * Walks every page forward from the first, carrying the last displayed row as the cursor.
     *
     * <p>The cursor is the last row the previous page displayed and never the lookahead row. The
     * source moves the lookahead row's keys over its saved cursor at
     * {@code app/cbl/COCRDLIC.cbl:L1212-L1214}, and an exclusive cursor holding that value would
     * skip one row per page.
     *
     * @param accountId  the account filter, or {@code null} to apply none
     * @param cardNumber the card-number filter, or {@code null} to apply none
     * @param pageSize   the row count each page displays, at least one
     * @return every page in order, the last one carrying a cleared flag
     * @throws IllegalStateException if the cursor fails to advance, which would loop forever
     */
    private List<CardPage> walkForward(String accountId, String cardNumber, int pageSize) {
        List<CardPage> pages = new ArrayList<>();
        String cursor = null;
        while (true) {
            CardPage page = forwardPage(cursor, accountId, cardNumber, pageSize);
            pages.add(page);
            if (!page.nextPageExists()) {
                return pages;
            }
            String nextCursor = page.displayed().getLast().getCardNumber();
            if (cursor != null && nextCursor.compareTo(cursor) <= 0) {
                throw new IllegalStateException("the forward cursor failed to advance past "
                        + "page " + pages.size() + ", so the walk would not terminate");
            }
            cursor = nextCursor;
        }
    }

    /**
     * Rejects a page size below one, which would make the walk above loop forever.
     *
     * @param pageSize the page size to check
     * @throws IllegalArgumentException if the page size is below one
     */
    private static void requirePositivePageSize(int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1, found " + pageSize);
        }
    }

    /**
     * Returns the card numbers of a row list, in the order the list holds them.
     *
     * @param rows the rows to read
     * @return the card numbers, in list order
     */
    private static List<String> cardNumbersOf(List<CardEntity> rows) {
        return rows.stream().map(CardEntity::getCardNumber).toList();
    }

    /**
     * Returns the card numbers every page of a walk displayed, pages in walk order.
     *
     * @param pages the pages to read
     * @return the card numbers of every page, concatenated
     */
    private static List<String> cardNumbersOfPages(List<CardPage> pages) {
        return pages.stream().flatMap(page -> page.displayed().stream())
                .map(CardEntity::getCardNumber).toList();
    }

    /**
     * Writes one card row through the inherited save operation and flushes it.
     *
     * <p>The flush drives the insert to the database inside the test transaction, so a unique
     * constraint on {@code account_id} would fail here rather than later.
     *
     * @param cardNumber the card number of the new row, sixteen characters wide
     * @param accountId  the account identifier of the new row, eleven digits
     * @return the managed row the save returned
     */
    private CardEntity insertCard(String cardNumber, String accountId) {
        CardEntity saved = cardRepository.save(new CardEntity(
                cardNumber,
                accountId,
                SYNTHETIC_CARD_VERIFICATION_VALUE,
                SYNTHETIC_EMBOSSED_NAME,
                SYNTHETIC_EXPIRATION_DATE,
                ACTIVE_STATUS_YES));
        entityManager.flush();
        return saved;
    }

    /**
     * Returns the card numbers of every row in the table, read through the inherited find-all.
     *
     * <p>The result is the reference set every union assertion compares against. Find-all and the
     * page finders are separate query paths, so a page finder that drops a row shows up as a
     * difference between the two.
     *
     * @return every card number in the table
     */
    private Set<String> everyCardNumber() {
        return new LinkedHashSet<>(cardNumbersOf(cardRepository.findAll()));
    }

    /**
     * Proves the context reached the container and that Flyway ran both migrations over it.
     *
     * <p>Every other group in this class rests on the three assertions here. A substituted
     * in-memory database, a skipped migration or a partial seed would each leave a paging
     * assertion failing for a reason unrelated to paging.
     */
    @Nested
    @Transactional
    @DisplayName("The container serves the context, and Flyway applied both migrations")
    class ContainerAndMigration {

        /**
         * Asserts the running context reached the PostgreSQL 18.4 container and no other database.
         *
         * <p>The product name rules out a substituted in-memory database. The mapped port rules
         * out the compose instance, which {@code card-platform/docker-compose.yml} publishes on the
         * fixed host port instead. Without both checks a substituted database would satisfy every
         * remaining assertion in this class.
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
                                        + " rather than the PostgreSQL container"),
                        () -> assertTrue(productVersion.startsWith("18."),
                                "expected a PostgreSQL 18 server from image " + POSTGRES_IMAGE
                                        + ", found version " + productVersion),
                        () -> assertTrue(urlCarriesMappedPort,
                                "the connection carries a port other than the container mapped "
                                        + "port " + POSTGRES.getFirstMappedPort()));
            }
        }

        /**
         * Asserts the Flyway history carries version 1 and version 2, both successful.
         *
         * <p>A schema name is an identifier, so it joins the statement text rather than arriving
         * as a bind value. The value comes from
         * {@code src/main/resources/application.yml} and never from a caller.
         */
        @Test
        @DisplayName("the Flyway history carries versions 1 and 2, both successful")
        void flywayAppliedBothMigrations() {
            List<String> versions = jdbcTemplate.queryForList(
                    "SELECT version FROM " + schema + ".flyway_schema_history"
                            + " WHERE success = TRUE AND version IS NOT NULL"
                            + " ORDER BY installed_rank",
                    String.class);
            assertEquals(MIGRATION_VERSIONS, versions,
                    "Flyway applied a different set of migrations than V1__schema.sql and "
                            + "V2__seed.sql");
        }

        /**
         * Asserts the seed loaded exactly fifty card rows.
         *
         * <p>The count is exact rather than merely positive. A seed that loaded one row would pass
         * a positive check and then fail every paging assertion below.
         * {@code REPRO INFILE(CARDDATA) OUTFILE(CARDVSAM)} at {@code app/jcl/CARDFILE.jcl:L75}
         * loads the same fifty records into the Virtual Storage Access Method dataset.
         */
        @Test
        @DisplayName("V2__seed.sql loaded exactly fifty card rows")
        void theSeedLoadedFiftyRows() {
            assertEquals(SEEDED_ROW_COUNT, cardRepository.count(),
                    "fifty rows is what makes a page of seven observable");
        }
    }

    /**
     * Proves the migration produced the primary key the Job Control Language member declares.
     */
    @Nested
    @Transactional
    @DisplayName("Keys, against the dataset definitions in app/jcl/CARDFILE.jcl")
    class Keys {

        /**
         * Asserts {@code card_number} alone carries the primary key.
         *
         * <p>{@code KEYS(16 0)} at {@code app/jcl/CARDFILE.jcl:L54} declares sixteen bytes at
         * offset zero, which is {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}.
         */
        @Test
        @DisplayName("card_number is the primary key, from KEYS(16 0)")
        void cardNumberIsThePrimaryKey() {
            List<String> columns = jdbcTemplate.queryForList(
                    PRIMARY_KEY_COLUMNS_SQL, String.class, schema, "card");
            assertEquals(List.of("card_number"), columns,
                    "constraint pk_card must key on card_number alone");
        }
    }

    /**
     * Proves the account index admits a repeated value, in the catalog and against a real insert.
     *
     * <p>A catalog check alone would prove little here. The fixture places one card on each of its
     * fifty accounts, so {@link CardRepository#findByAccountId(String)} returns a single element on
     * seeded data whether the index is unique or not.
     */
    @Nested
    @Transactional
    @DisplayName("The account index admits a repeated account identifier")
    class AccountIndex {

        /**
         * Asserts {@code idx_card_account_id} exists, keys on {@code account_id}, and is not
         * unique.
         *
         * <p>{@code KEYS(11 16)} at {@code app/jcl/CARDFILE.jcl:L85} places an eleven-byte key at
         * offset 16, and {@code NONUNIQUEKEY} at {@code app/jcl/CARDFILE.jcl:L86} admits a
         * repeated value. An assertion of uniqueness here would contradict the source.
         */
        @Test
        @DisplayName("idx_card_account_id keys on account_id and carries no unique constraint")
        void theAccountIndexIsNotUnique() {
            List<Boolean> uniqueFlags = jdbcTemplate.queryForList(
                    INDEX_IS_UNIQUE_SQL, Boolean.class, schema, "card", "idx_card_account_id");
            List<String> columns = jdbcTemplate.queryForList(
                    INDEX_COLUMNS_SQL, String.class, schema, "card", "idx_card_account_id");
            assertAll(
                    () -> assertEquals(1, uniqueFlags.size(),
                            "V1__schema.sql must declare idx_card_account_id exactly once on card"),
                    () -> assertEquals(List.of(Boolean.FALSE), uniqueFlags,
                            "NONUNIQUEKEY at app/jcl/CARDFILE.jcl:L86 admits a repeated account "
                                    + "identifier, so indisunique must be false"),
                    () -> assertEquals(List.of("account_id"), columns,
                            "KEYS(11 16) at app/jcl/CARDFILE.jcl:L85 keys the index on the "
                                    + "account identifier"));
        }

        /**
         * Asserts a second card inserts onto one account, and that both rows come back in a list.
         *
         * <p>A unique index would refuse the insert, and the flush drives it to the database
         * before the assertion runs. The pair of counts is what gives the list return type of
         * {@link CardRepository#findByAccountId(String)} its meaning, and what makes the CICS
         * response {@code DUPREC} an ordinary success. The two read paths accept that response at
         * {@code app/cbl/COCRDLIC.cbl:L1156-L1158} going forward and at
         * {@code app/cbl/COCRDLIC.cbl:L1332-L1334} going backward.
         */
        @Test
        @DisplayName("a second card on one account inserts, and findByAccountId returns both")
        void oneAccountCarriesTwoCards() {
            List<String> before = cardNumbersOf(cardRepository.findByAccountId(ROW_1_ACCOUNT_ID));
            insertCard(INSERTED_DUPLICATE_CARD_NUMBER, ROW_1_ACCOUNT_ID);
            List<String> after = cardNumbersOf(cardRepository.findByAccountId(ROW_1_ACCOUNT_ID));
            assertAll(
                    () -> assertEquals(List.of(ROW_1_CARD_NUMBER), before,
                            "the fixture places exactly one card on each of its fifty accounts"),
                    () -> assertEquals(2, after.size(),
                            "a unique index on account_id would have refused the second card"),
                    () -> assertEquals(
                            Set.of(ROW_1_CARD_NUMBER, INSERTED_DUPLICATE_CARD_NUMBER),
                            new LinkedHashSet<>(after),
                            "findByAccountId returned rows other than the two on that account"));
        }
    }

    /**
     * Proves keyset paging forward returns every row exactly once and honours the row limit.
     *
     * <p>The caller fetches one row beyond the page and reads the next-page flag from that row,
     * which is the lookahead at {@code app/cbl/COCRDLIC.cbl:L1197-L1216}. No count query takes
     * part.
     */
    @Nested
    @Transactional
    @DisplayName("Keyset paging forward over card_number")
    class ForwardPaging {

        /**
         * Asserts the cursor excludes its own row, and shows what an overwritten cursor costs.
         *
         * <p>The page after the last displayed row of the first page opens on row eight. A cursor
         * carrying the key of the lookahead row opens on row nine instead and drops row eight. The
         * source moves the lookahead row's keys over its saved cursor at
         * {@code app/cbl/COCRDLIC.cbl:L1212-L1214}, and it browses inclusively.
         */
        @Test
        @DisplayName("the cursor is exclusive, so the last displayed row opens the next page")
        void theCursorExcludesItsOwnRow() {
            CardPage fromLastDisplayed = forwardPage(ROW_7_CARD_NUMBER, null, null, 7);
            CardPage fromLookahead = forwardPage(ROW_8_CARD_NUMBER, null, null, 7);
            assertAll(
                    () -> assertEquals(ROW_8_CARD_NUMBER,
                            fromLastDisplayed.displayed().getFirst().getCardNumber(),
                            "the page after the last displayed row must open on the next row"),
                    () -> assertEquals(ROW_9_CARD_NUMBER,
                            fromLookahead.displayed().getFirst().getCardNumber(),
                            "a cursor holding the lookahead row's key skips one row per page"));
        }

        /**
         * Asserts a page with exactly seven rows left returns seven rows and clears the flag.
         *
         * <p>Seven rows follow seeded row 43. The caller asks for eight, receives seven, and reads
         * the missing eighth row as the end of the browse, which
         * {@code app/cbl/COCRDLIC.cbl:L1215-L1216} answers with {@code DFHRESP(ENDFILE)}.
         */
        @Test
        @DisplayName("seven rows remain: seven come back and the flag clears")
        void aFinalPageOfSevenClearsTheFlag() {
            CardPage page = forwardPage(ROW_43_CARD_NUMBER, null, null, 7);
            assertAll(
                    () -> assertEquals(7, page.displayed().size(),
                            "seven rows follow seeded row 43"),
                    () -> assertFalse(page.nextPageExists(),
                            "no eighth row follows, so the lookahead must clear the flag"),
                    () -> assertEquals(ROW_50_CARD_NUMBER,
                            page.displayed().getLast().getCardNumber(),
                            "the page must end on the highest card number the fixture carries"));
        }

        /**
         * Asserts a page with eight rows left shows seven and sets the flag.
         *
         * <p>Eight rows follow seeded row 42. The eighth row is the lookahead, and
         * {@code app/cbl/COCRDLIC.cbl:L1207-L1211} sets the flag on it.
         */
        @Test
        @DisplayName("eight rows remain: seven show and the flag sets")
        void aFullPageWithOneRowBeyondSetsTheFlag() {
            CardPage page = forwardPage(ROW_42_CARD_NUMBER, null, null, 7);
            assertAll(
                    () -> assertEquals(7, page.displayed().size(),
                            "the caller displays seven of the eight rows it fetched"),
                    () -> assertTrue(page.nextPageExists(),
                            "an eighth row followed, so the lookahead must set the flag"),
                    () -> assertEquals(ROW_43_CARD_NUMBER,
                            page.displayed().getFirst().getCardNumber(),
                            "the page must open on the row after the cursor"));
        }

        /**
         * Walks every page and asserts the union holds all fifty rows, none twice and none missing.
         *
         * <p>Fifty rows at seven a page fill eight pages: seven pages of seven, then one page of
         * one. The flag clears on the final page alone. Per-page size and per-page flag would both
         * still look correct if the walk dropped one row per page, so the union assertion is the
         * one that catches it.
         */
        @Test
        @DisplayName("a walk of every page returns all fifty rows once, over eight pages")
        void theWalkCoversEveryRowExactlyOnce() {
            List<CardPage> pages = walkForward(null, null, 7);
            List<String> walked = cardNumbersOfPages(pages);
            List<Integer> sizes = pages.stream().map(page -> page.displayed().size()).toList();
            List<Boolean> flags = pages.stream().map(CardPage::nextPageExists).toList();
            assertAll(
                    () -> assertEquals(8, pages.size(),
                            "fifty rows at seven a page fill eight pages"),
                    () -> assertEquals(List.of(7, 7, 7, 7, 7, 7, 7, 1), sizes,
                            "seven pages of seven must precede a final page of one"),
                    () -> assertEquals(
                            List.of(true, true, true, true, true, true, true, false), flags,
                            "the flag must clear on the final page alone"),
                    () -> assertEquals(SEEDED_ROW_COUNT, walked.size(),
                            "the walk returned a different row count than the table holds"),
                    () -> assertEquals(SEEDED_ROW_COUNT, new LinkedHashSet<>(walked).size(),
                            "the walk returned a row twice"),
                    () -> assertEquals(everyCardNumber(), new LinkedHashSet<>(walked),
                            "the union of every page must equal every row in the table"),
                    () -> assertEquals(FIRST_PAGE_CARD_NUMBERS,
                            cardNumbersOf(pages.getFirst().displayed()),
                            "the first page must hold the seven lowest card numbers in key order"),
                    () -> assertEquals(List.of(ROW_50_CARD_NUMBER),
                            cardNumbersOf(pages.getLast().displayed()),
                            "the final page must hold the fiftieth row alone"));
        }

        /**
         * Asserts a row limit of eight returns eight rows, from which the caller displays seven.
         *
         * <p>The assertion reads the limit the caller hands over and the row count that comes
         * back. A next-page flag derived from a count query would return seven rows here and fail.
         */
        @Test
        @DisplayName("a Limit of eight returns eight rows, and the caller displays seven")
        void aFetchOfEightServesAPageOfSeven() {
            Limit limit = Limit.of(8);
            List<CardEntity> fetched = cardRepository.findPageForward(null, null, null, limit);
            assertAll(
                    () -> assertEquals(8, limit.max(),
                            "the caller hands the repository a row limit of eight"),
                    () -> assertTrue(limit.isLimited(),
                            "an unlimited row limit would fetch the whole table"),
                    () -> assertEquals(8, fetched.size(),
                            "the repository must return the eighth row itself, not a count of it"),
                    () -> assertEquals(FIRST_PAGE_CARD_NUMBERS,
                            cardNumbersOf(fetched.subList(0, 7)),
                            "the first seven rows are the page the caller displays"),
                    () -> assertEquals(ROW_8_CARD_NUMBER, fetched.get(7).getCardNumber(),
                            "the eighth row is the lookahead row and never reaches the display"));
        }

        /**
         * Asserts the repository honours every row limit a caller supplies, exactly.
         *
         * <p>The page size belongs to {@code domain/CardQueryService}, which reads
         * {@code WS-MAX-SCREEN-LINES} at {@code app/cbl/COCRDLIC.cbl:L177-L178}. The repository
         * holds no default and returns the row count its limit names, capped by the table.
         */
        @Test
        @DisplayName("every caller-supplied Limit comes back honoured exactly")
        void everyLimitIsHonouredExactly() {
            List<Integer> requested = List.of(1, 2, 3, 7, 8, 49, 50, 51);
            List<Integer> returned = requested.stream()
                    .map(max -> cardRepository.findPageForward(null, null, null, Limit.of(max))
                            .size())
                    .toList();
            List<Integer> expected = requested.stream()
                    .map(max -> Math.min(max, SEEDED_ROW_COUNT))
                    .toList();
            assertEquals(expected, returned,
                    "the repository must return exactly the row count its Limit names, capped by "
                            + "the fifty rows the table holds");
        }

        /**
         * Asserts the walk still returns every row once after one account carries two cards.
         *
         * <p>The seeded relation is one card per account, so the duplicate condition never arises
         * on its own. Fifty-one rows at seven a page fill eight pages, the last holding two rows.
         * Accepting a repeated key is what {@code app/cbl/COCRDLIC.cbl:L1156-L1158} does with
         * {@code DFHRESP(DUPREC)}.
         */
        @Test
        @DisplayName("a walk after a repeated account identifier still returns every row once")
        void theWalkSurvivesARepeatedAccountIdentifier() {
            insertCard(INSERTED_DUPLICATE_CARD_NUMBER, ROW_1_ACCOUNT_ID);
            int expectedRows = SEEDED_ROW_COUNT + 1;
            List<CardPage> pages = walkForward(null, null, 7);
            List<String> walked = cardNumbersOfPages(pages);
            assertAll(
                    () -> assertEquals(8, pages.size(),
                            "fifty-one rows at seven a page fill eight pages"),
                    () -> assertEquals(2, pages.getLast().displayed().size(),
                            "two rows follow seven full pages"),
                    () -> assertFalse(pages.getLast().nextPageExists(),
                            "the flag must clear on the final page"),
                    () -> assertEquals(expectedRows, walked.size(),
                            "the walk returned a different row count than the table holds"),
                    () -> assertEquals(expectedRows, new LinkedHashSet<>(walked).size(),
                            "the walk returned a row twice"),
                    () -> assertEquals(everyCardNumber(), new LinkedHashSet<>(walked),
                            "the union of every page must equal every row in the table"),
                    () -> assertTrue(walked.contains(INSERTED_DUPLICATE_CARD_NUMBER),
                            "the walk must reach the second card of that account"));
        }
    }

    /**
     * Proves keyset paging backward returns the preceding rows, in descending key order.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:L1284-L1287} presets the backward counter one above the page
     * size and sets the backward flag before reading a row. The backward flag is therefore a preset
     * and not a derived value. The forward flag comes from
     * {@code app/cbl/COCRDLIC.cbl:L1191-L1216} instead.
     */
    @Nested
    @Transactional
    @DisplayName("Keyset paging backward over card_number")
    class BackwardPaging {

        /**
         * Asserts the last page comes back descending and opens on the highest card number.
         *
         * <p>{@link CardRepository#findPageBackward(String, String, String, Limit)} declares
         * {@code ORDER BY c.cardNumber DESC}. The source reverses that order for display by filling
         * its screen array from the high index down at
         * {@code app/cbl/COCRDLIC.cbl:L1338-L1346}.
         */
        @Test
        @DisplayName("the last page comes back descending, opening on the highest card number")
        void theLastPageOrdersDescending() {
            List<String> numbers = cardNumbersOf(
                    cardRepository.findPageBackward(null, null, null, Limit.of(7)));
            List<String> sortedDescending = new ArrayList<>(numbers);
            sortedDescending.sort(Comparator.reverseOrder());
            assertAll(
                    () -> assertEquals(7, numbers.size(),
                            "a row limit of seven must return seven of the fifty rows"),
                    () -> assertEquals(ROW_50_CARD_NUMBER, numbers.getFirst(),
                            "a null cursor asks for the last page, which opens on the highest key"),
                    () -> assertEquals(sortedDescending, numbers,
                            "findPageBackward must order rows by descending card number"));
        }

        /**
         * Asserts a forward step then a backward step lands on the rows the first page showed.
         *
         * <p>The forward cursor is the last row of page one, so page two opens on row eight. Paging
         * backward from row eight returns the seven rows before it, and reversing them gives page
         * one. Exactly seven rows precede row eight, so no further page lies before them.
         */
        @Test
        @DisplayName("a forward then backward round trip lands on the same first page")
        void aRoundTripReturnsToTheFirstPage() {
            CardPage first = forwardPage(null, null, null, 7);
            CardPage second =
                    forwardPage(first.displayed().getLast().getCardNumber(), null, null, 7);
            CardPage backToFirst = backwardPage(
                    second.displayed().getFirst().getCardNumber(), null, null, 7);
            assertAll(
                    () -> assertEquals(FIRST_PAGE_CARD_NUMBERS,
                            cardNumbersOf(first.displayed()),
                            "the first page must hold the seven lowest card numbers"),
                    () -> assertEquals(ROW_8_CARD_NUMBER,
                            second.displayed().getFirst().getCardNumber(),
                            "the second page must open on row eight"),
                    () -> assertEquals(FIRST_PAGE_CARD_NUMBERS,
                            cardNumbersOf(backToFirst.displayed()),
                            "the round trip must land on the rows the first page showed"),
                    () -> assertFalse(backToFirst.nextPageExists(),
                            "exactly seven rows precede row eight, so no page lies before them"));
        }
    }

    /**
     * Proves the database applies both optional filters inside the query predicate.
     *
     * <p>{@code 9500-FILTER-RECORDS} at {@code app/cbl/COCRDLIC.cbl:L1382-L1409} tests the account
     * identifier at {@code app/cbl/COCRDLIC.cbl:L1386} and the card number at
     * {@code app/cbl/COCRDLIC.cbl:L1397}. Either test may be absent, so all four combinations reach
     * the database.
     */
    @Nested
    @Transactional
    @DisplayName("Both optional filters, applied inside the query predicate")
    class PredicateFilters {

        /**
         * Asserts an absent filter pair returns the first seven rows in key order.
         */
        @Test
        @DisplayName("no filter returns the first seven rows in key order")
        void noFilterReturnsTheFirstPage() {
            CardPage page = forwardPage(null, null, null, 7);
            assertAll(
                    () -> assertEquals(FIRST_PAGE_CARD_NUMBERS, cardNumbersOf(page.displayed()),
                            "the first page must hold the seven lowest card numbers in key order"),
                    () -> assertTrue(page.nextPageExists(),
                            "forty-three rows follow the first page"));
        }

        /**
         * Asserts an account filter returns that account's cards and no other row.
         *
         * <p>The filter reproduces {@code CARD-ACCT-ID = CC-ACCT-ID} at
         * {@code app/cbl/COCRDLIC.cbl:L1386}.
         */
        @Test
        @DisplayName("an account filter returns that account's cards and no other")
        void anAccountFilterSelectsOneAccount() {
            CardPage page = forwardPage(null, ROW_1_ACCOUNT_ID, null, 7);
            List<String> accountIds = page.displayed().stream()
                    .map(CardEntity::getAccountId).distinct().toList();
            assertAll(
                    () -> assertEquals(List.of(ROW_1_CARD_NUMBER),
                            cardNumbersOf(page.displayed()),
                            "the fixture places one card on that account"),
                    () -> assertEquals(List.of(ROW_1_ACCOUNT_ID), accountIds,
                            "every returned row must carry the account identifier asked for"),
                    () -> assertFalse(page.nextPageExists(),
                            "one row matches, so no further page follows"));
        }

        /**
         * Asserts a card-number filter returns at most one row.
         *
         * <p>The filter reproduces {@code CARD-NUM = CC-CARD-NUM-N} at
         * {@code app/cbl/COCRDLIC.cbl:L1397}. The primary key admits one row per value, and a
         * value the table does not hold returns none.
         */
        @Test
        @DisplayName("a card-number filter returns at most one row")
        void aCardNumberFilterReturnsAtMostOneRow() {
            CardPage present = forwardPage(null, null, ROW_2_CARD_NUMBER, 7);
            CardPage absent = forwardPage(null, null, INSERTED_DUPLICATE_CARD_NUMBER, 7);
            assertAll(
                    () -> assertEquals(List.of(ROW_2_CARD_NUMBER),
                            cardNumbersOf(present.displayed()),
                            "a seeded card number must return its own row alone"),
                    () -> assertTrue(absent.displayed().isEmpty(),
                            "a card number the table does not hold must return no row"),
                    () -> assertFalse(present.nextPageExists(),
                            "one row matches, so no further page follows"),
                    () -> assertFalse(absent.nextPageExists(),
                            "no row matches, so no further page follows"));
        }

        /**
         * Asserts both filters narrow the result together rather than widen it.
         *
         * <p>A card of one account paired with the account of another intersects in no row, because
         * {@code app/cbl/COCRDLIC.cbl:L1385-L1405} excludes a record that fails either test.
         */
        @Test
        @DisplayName("both filters together intersect rather than widen")
        void bothFiltersIntersect() {
            CardPage matching = forwardPage(null, ROW_1_ACCOUNT_ID, ROW_1_CARD_NUMBER, 7);
            CardPage crossed = forwardPage(null, ROW_1_ACCOUNT_ID, ROW_2_CARD_NUMBER, 7);
            assertAll(
                    () -> assertEquals(List.of(ROW_1_CARD_NUMBER),
                            cardNumbersOf(matching.displayed()),
                            "a matching pair must return the one row both tests admit"),
                    () -> assertTrue(crossed.displayed().isEmpty(),
                            "a card of one account and the account of another share no row"));
        }

        /**
         * Asserts a filtered page fills with seven matching rows rather than seven scanned rows.
         *
         * <p>The test places seven cards on one account, then reads one page of seven with the
         * account filter applied. The first seven rows in key order hold one of those cards, so a
         * filter applied in Java after the fetch would return one row instead of seven. The source
         * reaches the same outcome by filtering ahead of its row counter at
         * {@code app/cbl/COCRDLIC.cbl:L1159-L1163}, where an excluded record consumes no slot.
         */
        @Test
        @DisplayName("a filtered page carries seven matching rows, not seven scanned rows")
        void aFilteredPageFillsWithMatchingRows() {
            INSERTED_FILLER_CARD_NUMBERS.forEach(number -> insertCard(number, ROW_1_ACCOUNT_ID));
            CardPage filteredPage = forwardPage(null, ROW_1_ACCOUNT_ID, null, 7);
            List<String> filtered = cardNumbersOf(filteredPage.displayed());
            List<String> unfiltered = cardNumbersOf(forwardPage(null, null, null, 7).displayed());
            long matchesInsideTheUnfilteredPage =
                    unfiltered.stream().filter(filtered::contains).count();
            List<String> accountIds = filteredPage.displayed().stream()
                    .map(CardEntity::getAccountId).distinct().toList();
            assertAll(
                    () -> assertEquals(7, filtered.size(),
                            "seven rows match the filter, so the page fills with matching rows"),
                    () -> assertEquals(List.of(ROW_1_ACCOUNT_ID), accountIds,
                            "every row on the filtered page must carry that account identifier"),
                    () -> assertFalse(filteredPage.nextPageExists(),
                            "seven rows match and no eighth follows, so the flag must clear"),
                    () -> assertEquals(1L, matchesInsideTheUnfilteredPage,
                            "the first seven rows in key order hold one matching row, so a filter "
                                    + "applied after the fetch would return one row"));
        }
    }
}
