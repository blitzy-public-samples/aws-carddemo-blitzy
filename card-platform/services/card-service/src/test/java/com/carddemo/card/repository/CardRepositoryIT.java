package com.carddemo.card.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.card.TestIdentityPasswords;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.domain.CardQueryService.CardListRow;
import com.carddemo.card.domain.CardQueryService;
import com.carddemo.card.domain.CardTokenReconciler;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.entity.ProcessedEventEntity;
import com.carddemo.cobol.PanMasker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
 * <p>DEVIATION, list return type: {@link CardRepository#findByAccountId(String, Limit)} returns a
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
                "carddemo.outbox.relay.fixed-delay-ms=3600000",
                "carddemo.retention.sweep-interval-ms=3600000",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH
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

    /**
     * The schema Flyway creates, from {@code spring.jpa.properties.hibernate.default_schema} and
     * {@code spring.flyway.schemas} in {@code src/main/resources/application.yml}.
     */
    private static final String MIGRATED_SCHEMA = "card_service";

    /**
     * Rows one screen of the card list holds, from
     * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
     * {@code app/cbl/COCRDLIC.cbl:L177-L178}.
     */
    private static final int SCREEN_PAGE_SIZE = 7;

    /**
     * Rows one account read returns at most, the ceiling
     * {@code domain/CardQueryService} applies to the same read.
     */
    private static final Limit ACCOUNT_READ_LIMIT = Limit.of(100);

    /** Account identifier of seeded row one, eleven characters wide. */
    private static final String ROW_1_ACCOUNT_ID = "00000000050";

    /** Row count {@code V2__seed.sql} loads into {@code card}. */
    private static final int SEEDED_ROW_COUNT = 50;

    /**
     * The migration versions Flyway applies, in the order it applies them.
     *
     * <p>Version 3 is {@code V3__processed_event_topic_key.sql}, which re-keys
     * {@code processed_event} on the event and the topic together. Version 4 is
     * {@code V4__subject_request_posture.sql} and carries no data-definition statement: it re-issues
     * the {@code card_xref} table comment, which used to say an erasure request had to reach the
     * row while no export or erasure workflow exists anywhere on this platform to send one. The
     * correction is a migration rather than an edit to {@code V1} because {@code V1} has run, and a
     * comment-only migration adds a history row and changes no table, so every other assertion in
     * this class reads exactly as it did at version 3.
     */
    private static final List<String> MIGRATION_VERSIONS = List.of("1", "2", "3", "4");

    /**
     * Card number of the row a test inserts to place a second card on one account.
     */
    private static final String INSERTED_DUPLICATE_CARD_NUMBER =
            syntheticCardNumber(900_001L);

    /** Card numbers of the six rows a test inserts to fill one account to seven cards. */
    private static final List<String> INSERTED_FILLER_CARD_NUMBERS = List.of(
            syntheticCardNumber(900_011L),
            syntheticCardNumber(900_012L),
            syntheticCardNumber(900_013L),
            syntheticCardNumber(900_014L),
            syntheticCardNumber(900_015L),
            syntheticCardNumber(900_016L));

    /**
     * Card verification value every inserted row carries. The value is three digits, which
     * {@code ck_card_verification_value_digits} requires, and plainly synthetic.
     */
    private static final String SYNTHETIC_CARD_VERIFICATION_VALUE = "000";

    /** Embossed name every inserted row carries. */
    private static final String SYNTHETIC_EMBOSSED_NAME = "Integration Test Row";

    /** Expiration date every inserted row carries. */
    private static final LocalDate SYNTHETIC_EXPIRATION_DATE = LocalDate.of(2027, 12, 31);

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

    /**
     * Reads the key columns of one named index, in key order.
     *
     * <p>{@code unnest(i.indkey) WITH ORDINALITY} is what makes the returned order the key order.
     * A join on {@code attnum = ANY(i.indkey)} returns the same column names in table order, which
     * says nothing about which column leads, and a composite index only serves a leading-column
     * lookup when the leading column is the one this platform intends.
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
     * <p>The uniform resource locator carries {@code currentSchema}, which
     * {@link #migratedSchemaUrl()} appends. {@code hibernate.default_schema} qualifies a mapped
     * query alone, so a native statement such as
     * {@link OutboxEventRepository#claimDueRows(java.time.Instant, int)} resolves its unqualified
     * table name against the connection search path instead.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CardRepositoryIT::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Returns the container uniform resource locator with {@code currentSchema} appended.
     *
     * <p>Testcontainers already appends one query parameter of its own, so the separator is
     * {@code &} whenever a {@code ?} is present and {@code ?} otherwise.
     *
     * @return the connection uniform resource locator whose search path holds
     *         {@value #MIGRATED_SCHEMA}
     */
    private static String migratedSchemaUrl() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "currentSchema=" + MIGRATED_SCHEMA;
    }

    /** The repository under test. */
    @Autowired
    private CardRepository cardRepository;

    /** Runs the production paging policy over the real repository. */
    @Autowired
    private CardQueryService cardQueryService;

    /** Reads the system catalog and the Flyway history straight from the migrated database. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Answers which database the running context reached. */
    @Autowired
    private DataSource dataSource;

    /** The outbox table the relay claims from, read directly by the claim assertions. */
    @Autowired
    private OutboxEventRepository outboxEventRepository;

    /** The duplicate-delivery marker table, read directly by the retention assertions. */
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    /** Forces a pending insert to the database, so a constraint answers where a test asserts. */
    @PersistenceContext
    private EntityManager entityManager;

    /** Opens the two independent transactions the bounded-wait test contends between. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The start-up runner that brings a stored card token onto the configured key. */
    @Autowired
    private CardTokenReconciler cardTokenReconciler;

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
     * Resolves a card-number filter through the primary key and applies the other two tests.
     *
     * <p>{@code card_number} is the primary key, from {@code KEYS(16 0)} at
     * {@code app/jcl/CARDFILE.jcl:L54}, so this filter selects at most one row.
     * {@code domain/CardQueryService} intersects that row with the account test at
     * {@code app/cbl/COCRDLIC.cbl:L1386} and the browse bound, and this helper does the same.
     *
     * @param cardNumber    the sixteen-character filter
     * @param accountId     the account filter, or {@code null} to apply none
     * @param cursor        the exclusive browse bound, or {@code null} for the first or last page
     * @param cursorIsUpper {@code true} when the bound is an upper one, as a backward page carries
     * @return the matching row, or an empty list
     */
    private List<CardEntity> oneCard(String cardNumber, String accountId, String cursor,
            boolean cursorIsUpper) {
        return cardRepository.findByCardNumber(cardNumber)
                .filter(card -> accountId == null || accountId.equals(card.getAccountId()))
                .filter(card -> cursor == null || (cursorIsUpper
                        ? card.getCardNumber().compareTo(cursor) < 0
                        : card.getCardNumber().compareTo(cursor) > 0))
                .map(List::of)
                .orElseGet(List::of);
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
        Limit limit = Limit.of(pageSize + 1);
        List<CardEntity> fetched;

        if (cardNumber != null) {
            fetched = oneCard(cardNumber, accountId, afterCardNumber, false);
        } else if (accountId == null) {
            fetched = afterCardNumber == null
                    ? cardRepository.findFirstPage(limit)
                    : cardRepository.findPageAfter(afterCardNumber, limit);
        } else {
            fetched = afterCardNumber == null
                    ? cardRepository.findFirstPageForAccount(accountId, limit)
                    : cardRepository.findPageAfterForAccount(accountId, afterCardNumber, limit);
        }
        boolean nextPageExists = fetched.size() > pageSize;
        List<CardEntity> displayed = nextPageExists ? fetched.subList(0, pageSize) : fetched;
        return new CardPage(List.copyOf(displayed), nextPageExists);
    }

    /**
     * Fetches one page backward and returns it in ascending card-number order.
     *
     * <p>{@link CardRepository#findLastPage(Limit)} and its cursor form order rows
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
        Limit limit = Limit.of(pageSize + 1);
        List<CardEntity> fetched;

        if (cardNumber != null) {
            fetched = oneCard(cardNumber, accountId, beforeCardNumber, true);
        } else if (accountId == null) {
            fetched = beforeCardNumber == null
                    ? cardRepository.findLastPage(limit)
                    : cardRepository.findPageBefore(beforeCardNumber, limit);
        } else {
            fetched = beforeCardNumber == null
                    ? cardRepository.findLastPageForAccount(accountId, limit)
                    : cardRepository.findPageBeforeForAccount(accountId, beforeCardNumber, limit);
        }
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

    /** Returns the card numbers of service list rows, preserving their order. */
    private static List<String> serviceCardNumbers(List<CardListRow> rows) {
        return rows.stream().map(CardListRow::cardNumber).toList();
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
     * Returns the fifty seeded card numbers in key order without committing them in test source.
     */
    private List<String> orderedSeededCardNumbers() {
        return jdbcTemplate.queryForList(
                "SELECT card_number FROM " + schema + ".card ORDER BY card_number",
                String.class);
    }

    /** Returns the account identifier stored on one card. */
    private String accountIdOf(String cardNumber) {
        return cardRepository.findByCardNumber(cardNumber).orElseThrow().getAccountId();
    }

    /** Builds a clearly synthetic sixteen-digit test value. */
    private static String syntheticCardNumber(long serial) {
        return "9999" + String.format(Locale.ROOT, "%012d", serial);
    }

    /** Compares card-number text without adding either value to a failed assertion. */
    private static void assertSameCardNumber(String expected, String actual, String message) {
        assertTrue(expected.equals(actual), message);
    }

    /** Compares ordered card-number collections without adding their values to a failure. */
    private static void assertSameCardNumbers(
            Iterable<String> expected, Iterable<String> actual, String message) {
        assertTrue(expected.equals(actual), message);
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
    @DisplayName("The container serves the context, and Flyway applied all migrations")
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
         * Asserts the Flyway history carries versions 1 through 3, all successful.
         *
         * <p>A schema name is an identifier, so it joins the statement text rather than arriving
         * as a bind value. The value comes from
         * {@code src/main/resources/application.yml} and never from a caller.
         */
        @Test
        @DisplayName("the Flyway history carries every shipped version, all successful")
        void flywayAppliedAllMigrations() {
            List<String> versions = jdbcTemplate.queryForList(
                    "SELECT version FROM " + schema + ".flyway_schema_history"
                            + " WHERE success = TRUE AND version IS NOT NULL"
                            + " ORDER BY installed_rank",
                    String.class);
            assertEquals(MIGRATION_VERSIONS, versions,
                    "Flyway applied a different set of migrations than the shipped scripts");
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
     * Proves the card token is one value, derived the same way in SQL and in Java, and unique.
     *
     * <p>{@code card_token} is an addition with no field in {@code app/cpy/CVACT02Y.cpy} behind it.
     * One piece of code derives it, {@link PanMasker#cardToken(String)}, and it reaches a row by two
     * routes: {@link com.carddemo.card.entity.CardEntity} applies it in its constructor to every row
     * this service writes, and {@code V2__seed.sql} carries the result as a checked-in literal on
     * each of its fifty rows. A literal and a derivation can drift, and a drift would split the
     * identity: a cursor issued for a seeded row would resolve, one issued for an inserted row would
     * not, or the reverse.
     *
     * <p>This group is the only place the literal and the derivation meet, so it is the only place
     * the agreement can be proven. It runs against a real PostgreSQL because the literal is loaded by
     * a migration.
     */
    @Nested
    @Transactional
    @DisplayName("The card token agrees between SQL and Java, and reaches one row")
    class CardTokenIdentity {

        /**
         * Asserts every seeded row carries the token the Java helper derives from its card number.
         *
         * <p>All fifty rows are compared rather than one. The expression in {@code V2__seed.sql}
         * applies {@code btrim} to a {@code CHAR(16)} column, and a row whose card number differed
         * in padding from the rest would diverge on that row alone.
         */
        @Test
        @DisplayName("every seeded card_token equals PanMasker.cardToken of its card number")
        void everySeededTokenMatchesTheJavaDerivation() {
            List<CardEntity> rows = cardRepository.findAll();
            List<String> diverged = new ArrayList<>();
            for (CardEntity row : rows) {
                String expected = PanMasker.cardToken(row.getCardNumber());
                if (!expected.equals(row.getCardToken())) {
                    diverged.add(PanMasker.maskCardNumber(row.getCardNumber()));
                }
            }
            assertAll(
                    () -> assertEquals(SEEDED_ROW_COUNT, rows.size(),
                            "the seed loaded fifty rows for this comparison"),
                    () -> assertEquals(List.of(), diverged,
                            "the SQL expression in V2__seed.sql and PanMasker.cardToken must "
                                    + "derive one value. These masked card numbers diverged: "
                                    + diverged));
        }

        /**
         * Asserts a row this service inserts carries the same derivation, and reads back by token.
         *
         * <p>The insert goes through the entity constructor, which is the Java side of the
         * derivation, and the flush drives it to the database before the lookup runs. The lookup is
         * the translation a paging cursor depends on.
         */
        /**
         * Asserts the reconciler rewrites a stored token that belongs to another key, and only that
         * row.
         *
         * <p>The condition is the one a deployment starts in. A seeded literal is derived under the
         * build-scope key {@code card-platform/pom.xml} supplies, and a deployment generates a key of
         * its own, so every seeded row arrives carrying a token the running service would not derive.
         * This test creates that condition by writing a token-shaped value no key produces, then runs
         * the reconciliation the start-up runner runs.
         *
         * <p>The second run is the point of the second assertion. A reconciliation that rewrote rows
         * it had already corrected would rewrite fifty rows on every start-up of every instance.
         */
        @Test
        @DisplayName("the reconciler rewrites a token from another key, and rewrites nothing twice")
        void theReconcilerRewritesATokenFromAnotherKey() {
            String cardNumber = seededCardNumbers().get(0);
            String derived = PanMasker.cardToken(cardNumber);
            String foreign = "0".repeat(PanMasker.CARD_TOKEN_LENGTH);
            jdbcTemplate.update("UPDATE card SET card_token = ? WHERE card_number = ?",
                    foreign, cardNumber);
            entityManager.clear();

            int firstRun = cardTokenReconciler.reconcile();
            entityManager.clear();
            int secondRun = cardTokenReconciler.reconcile();
            entityManager.clear();

            assertAll(
                    () -> assertEquals(1, firstRun,
                            "one row carried a token from another key, so one row is rewritten"),
                    () -> assertEquals(0, secondRun,
                            "a second run finds every token already derived and writes nothing"),
                    () -> assertEquals(derived, jdbcTemplate.queryForObject(
                                    "SELECT card_token FROM card WHERE card_number = ?",
                                    String.class, cardNumber),
                            "the rewritten row carries the token the configured key derives"),
                    () -> assertEquals(SEEDED_ROW_COUNT, cardRepository.count(),
                            "the reconciliation rewrites rows and inserts or deletes none"));
        }

        /**
         * Asserts the reconciliation is wired to run at start-up rather than on demand.
         *
         * <p>Spring Boot invokes every {@link ApplicationRunner} after the context has refreshed,
         * which is after Flyway has loaded the seed, and before it publishes the ready event that
         * turns the readiness probe to accepting traffic. That ordering is what stops a request
         * reading a token the reconciliation is about to change, and the bean's type is what puts it
         * in that position.
         */
        @Test
        @DisplayName("the reconciliation runs as a start-up runner and not on request")
        void theReconciliationRunsAsAStartUpRunner() {
            assertInstanceOf(ApplicationRunner.class, cardTokenReconciler,
                    "the reconciliation has to run before the service accepts traffic, and an"
                            + " application runner is what Spring Boot invokes in that window");
        }

        /** The card numbers the seed loaded, in primary-key order. */
        private List<String> seededCardNumbers() {
            return cardRepository.findAll().stream().map(CardEntity::getCardNumber).sorted().toList();
        }

        @Test
        @DisplayName("an inserted row carries the derived token and findByCardToken reaches it")
        void anInsertedRowIsReachableByItsToken() {
            CardEntity saved = insertCard(INSERTED_DUPLICATE_CARD_NUMBER, ROW_1_ACCOUNT_ID);
            String token = PanMasker.cardToken(INSERTED_DUPLICATE_CARD_NUMBER);

            assertAll(
                    () -> assertEquals(token, saved.getCardToken(),
                            "the constructor derived the token the helper produces"),
                    () -> assertEquals(INSERTED_DUPLICATE_CARD_NUMBER,
                            cardRepository.findByCardToken(token)
                                    .map(CardEntity::getCardNumber)
                                    .orElse(null),
                            "findByCardToken must reach the row the token names, which is how a "
                                    + "paging cursor becomes a browse position"),
                    () -> assertTrue(cardRepository.findByCardToken(
                                    PanMasker.cardToken("0000000000000001")).isEmpty(),
                            "a token naming no row yields an empty Optional and throws nothing"));
        }

        /**
         * Asserts {@code uq_card_card_token} exists, keys on {@code card_token} and is unique.
         *
         * <p>Uniqueness is what makes one cursor reach one browse position. Without it a token
         * could name two rows and the browse would have no defined place to resume from.
         */
        @Test
        @DisplayName("uq_card_card_token keys on card_token and is unique")
        void theCardTokenIndexIsUnique() {
            List<Boolean> uniqueFlags = jdbcTemplate.queryForList(
                    INDEX_IS_UNIQUE_SQL, Boolean.class, schema, "card", "uq_card_card_token");
            List<String> columns = jdbcTemplate.queryForList(
                    INDEX_COLUMNS_SQL, String.class, schema, "card", "uq_card_card_token");
            assertAll(
                    () -> assertEquals(List.of(Boolean.TRUE), uniqueFlags,
                            "one card reaches one token, so the constraint must be unique"),
                    () -> assertEquals(List.of("card_token"), columns,
                            "the constraint keys on the card token alone"));
        }

        /**
         * Asserts the check constraint refuses a token that is not lower-case hexadecimal.
         *
         * <p>The column is the storage end of the same shape
         * {@link PanMasker#CARD_TOKEN_PATTERN} declares for the cursor. A card number written here
         * would fail on width and again on character class, which is the property that keeps a
         * Primary Account Number out of the column that a cursor is read from.
         */
        @Test
        @DisplayName("ck_card_card_token_hex refuses a card number in the token column")
        void theCheckConstraintRefusesACardNumber() {
            String insert = "INSERT INTO " + schema + ".card (card_number, account_id, "
                    + "card_verification_value, embossed_name, expiration_date, active_status, "
                    + "card_token) VALUES (?, ?, ?, ?, ?, ?, ?)";

            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbcTemplate.update(insert,
                            "8888888888888888", ROW_1_ACCOUNT_ID,
                            SYNTHETIC_CARD_VERIFICATION_VALUE, SYNTHETIC_EMBOSSED_NAME,
                            SYNTHETIC_EXPIRATION_DATE, ACTIVE_STATUS_YES,
                            "8888888888888888"),
                    "ck_card_card_token_hex must refuse a value that is not sixty-four lower-case "
                            + "hexadecimal characters");
        }
    }

    /**
     * Proves the account index admits a repeated value, in the catalog and against a real insert.
     *
     * <p>A catalog check alone would prove little here. The fixture places one card on each of its
     * fifty accounts, so {@link CardRepository#findByAccountId(String, Limit)} returns a single element on
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
                    () -> assertEquals(List.of("account_id", "card_number"), columns,
                            "KEYS(11 16) at app/jcl/CARDFILE.jcl:L85 keys the index on the "
                                    + "account identifier, and card_number follows it so one "
                                    + "index serves the scoped read and the keyset walk"));
        }

        /**
         * Asserts a second card inserts onto one account, and that both rows come back in a list.
         *
         * <p>A unique index would refuse the insert, and the flush drives it to the database
         * before the assertion runs. The pair of counts is what gives the list return type of
         * {@link CardRepository#findByAccountId(String, Limit)} its meaning, and what makes the CICS
         * response {@code DUPREC} an ordinary success. The two read paths accept that response at
         * {@code app/cbl/COCRDLIC.cbl:L1156-L1158} going forward and at
         * {@code app/cbl/COCRDLIC.cbl:L1332-L1334} going backward.
         */
        @Test
        @DisplayName("a second card on one account inserts, and findByAccountId returns both")
        void oneAccountCarriesTwoCards() {
            String seededCardNumber = orderedSeededCardNumbers().getFirst();
            String accountId = accountIdOf(seededCardNumber);
            List<String> before = cardNumbersOf(cardRepository.findByAccountId(accountId, ACCOUNT_READ_LIMIT));
            insertCard(INSERTED_DUPLICATE_CARD_NUMBER, accountId);
            List<String> after = cardNumbersOf(cardRepository.findByAccountId(accountId, ACCOUNT_READ_LIMIT));
            assertAll(
                    () -> assertSameCardNumbers(List.of(seededCardNumber), before,
                            "the fixture places exactly one card on each of its fifty accounts"),
                    () -> assertEquals(2, after.size(),
                            "a unique index on account_id would have refused the second card"),
                    () -> assertSameCardNumbers(
                            Set.of(seededCardNumber, INSERTED_DUPLICATE_CARD_NUMBER),
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
            List<String> seeded = orderedSeededCardNumbers();
            String lastDisplayed = seeded.get(6);
            String lookahead = seeded.get(7);
            String afterLookahead = seeded.get(8);
            CardPage fromLastDisplayed = forwardPage(lastDisplayed, null, null, 7);
            CardPage fromLookahead = forwardPage(lookahead, null, null, 7);
            assertAll(
                    () -> assertSameCardNumber(lookahead,
                            fromLastDisplayed.displayed().getFirst().getCardNumber(),
                            "the page after the last displayed row must open on the next row"),
                    () -> assertSameCardNumber(afterLookahead,
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
            List<String> seeded = orderedSeededCardNumbers();
            CardPage page = forwardPage(seeded.get(42), null, null, 7);
            assertAll(
                    () -> assertEquals(7, page.displayed().size(),
                            "seven rows follow seeded row 43"),
                    () -> assertFalse(page.nextPageExists(),
                            "no eighth row follows, so the lookahead must clear the flag"),
                    () -> assertSameCardNumber(seeded.getLast(),
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
            List<String> seeded = orderedSeededCardNumbers();
            CardPage page = forwardPage(seeded.get(41), null, null, 7);
            assertAll(
                    () -> assertEquals(7, page.displayed().size(),
                            "the caller displays seven of the eight rows it fetched"),
                    () -> assertTrue(page.nextPageExists(),
                            "an eighth row followed, so the lookahead must set the flag"),
                    () -> assertSameCardNumber(seeded.get(42),
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
            List<String> seeded = orderedSeededCardNumbers();
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
                    () -> assertSameCardNumbers(everyCardNumber(), new LinkedHashSet<>(walked),
                            "the union of every page must equal every row in the table"),
                    () -> assertSameCardNumbers(seeded.subList(0, 7),
                            cardNumbersOf(pages.getFirst().displayed()),
                            "the first page must hold the seven lowest card numbers in key order"),
                    () -> assertSameCardNumbers(List.of(seeded.getLast()),
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
            List<String> seeded = orderedSeededCardNumbers();
            Limit limit = Limit.of(8);
            List<CardEntity> fetched = cardRepository.findFirstPage(limit);
            assertAll(
                    () -> assertEquals(8, limit.max(),
                            "the caller hands the repository a row limit of eight"),
                    () -> assertTrue(limit.isLimited(),
                            "an unlimited row limit would fetch the whole table"),
                    () -> assertEquals(8, fetched.size(),
                            "the repository must return the eighth row itself, not a count of it"),
                    () -> assertSameCardNumbers(seeded.subList(0, 7),
                            cardNumbersOf(fetched.subList(0, 7)),
                            "the first seven rows are the page the caller displays"),
                    () -> assertSameCardNumber(seeded.get(7), fetched.get(7).getCardNumber(),
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
                    .map(max -> cardRepository.findFirstPage(Limit.of(max))
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
            String accountId = accountIdOf(orderedSeededCardNumbers().getFirst());
            insertCard(INSERTED_DUPLICATE_CARD_NUMBER, accountId);
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
                    () -> assertSameCardNumbers(everyCardNumber(), new LinkedHashSet<>(walked),
                            "the union of every page must equal every row in the table"),
                    () -> assertTrue(walked.contains(INSERTED_DUPLICATE_CARD_NUMBER),
                            "the walk must reach the second card of that account"));
        }
    }

    /**
     * Runs the production query service over the migrated PostgreSQL repository.
     */
    @Nested
    @Transactional
    @DisplayName("CardQueryService over the real card repository")
    class QueryServicePaging {

        @Test
        @DisplayName("page sizes 0, 1, 7, 100 and 101 enforce the service bounds")
        void pageSizeBoundsRunThroughTheProductionService() {
            CardQueryService.CardPage one =
                    cardQueryService.listForward(null, 1, null, null);
            CardQueryService.CardPage seven =
                    cardQueryService.listForward(null, 7, null, null);
            CardQueryService.CardPage hundred =
                    cardQueryService.listForward(null, 100, null, null);

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> cardQueryService.listForward(null, 0, null, null)),
                    () -> assertEquals(1, one.rows().size(), "page size one"),
                    () -> assertTrue(one.nextPageExists(), "one row has a lookahead row"),
                    () -> assertEquals(7, seven.rows().size(), "page size seven"),
                    () -> assertTrue(seven.nextPageExists(), "seven rows have a lookahead row"),
                    () -> assertEquals(SEEDED_ROW_COUNT, hundred.rows().size(),
                            "page size one hundred returns the fifty rows present"),
                    () -> assertFalse(hundred.nextPageExists(),
                            "no row follows the fifty rows present"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> cardQueryService.listForward(null, 101, null, null)));
        }

        @Test
        @DisplayName("lookahead, cursors and backward paging preserve every displayed row")
        void cursorsAndLookaheadRunThroughTheProductionService() {
            List<String> seeded = orderedSeededCardNumbers();
            CardQueryService.CardPage first =
                    cardQueryService.listForward(null, 7, null, null);
            CardQueryService.CardPage second =
                    cardQueryService.listForward(first.lastCardToken(), 7, null, null);
            CardQueryService.CardPage back =
                    cardQueryService.listBackward(second.firstCardToken(), 7, null, null);

            assertAll(
                    () -> assertSameCardNumbers(
                            seeded.subList(0, 7), serviceCardNumbers(first.rows()),
                            "the first service page maps the first seven repository rows"),
                    () -> assertTrue(first.nextPageExists(),
                            "the eighth repository row sets the service lookahead flag"),
                    () -> assertSameCardNumber(
                            PanMasker.cardToken(seeded.getFirst()), first.firstCardToken(),
                            "the first cursor names the first displayed row, as a card token"),
                    () -> assertSameCardNumber(
                            PanMasker.cardToken(seeded.get(6)), first.lastCardToken(),
                            "the forward cursor names the last displayed row, as a card token"),
                    () -> assertSameCardNumber(
                            PanMasker.cardToken(seeded.get(7)), second.firstCardToken(),
                            "the next page starts on the row after the cursor"),
                    () -> assertSameCardNumbers(
                            serviceCardNumbers(first.rows()), serviceCardNumbers(back.rows()),
                            "a forward and backward round trip returns the same page"));
        }

        @Test
        @DisplayName("the service maps card number, account identifier and active status")
        void responseRowsMapTheThreeSourceFields() {
            String cardNumber = orderedSeededCardNumbers().getFirst();
            CardEntity stored = cardRepository.findByCardNumber(cardNumber).orElseThrow();
            CardListRow row = cardQueryService.listForward(null, 1, null, null).rows().getFirst();

            assertAll(
                    () -> assertSameCardNumber(
                            stored.getCardNumber(), row.cardNumber(),
                            "the list row maps CARD-NUM"),
                    () -> assertTrue(stored.getAccountId().equals(row.accountId()),
                            "the list row maps CARD-ACCT-ID"),
                    () -> assertTrue(stored.getActiveStatus().equals(row.activeStatus()),
                            "the list row maps CARD-ACTIVE-STATUS"));
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
         * <p>{@link CardRepository#findLastPage(Limit)} declares
         * {@code ORDER BY c.cardNumber DESC}. The source reverses that order for display by filling
         * its screen array from the high index down at
         * {@code app/cbl/COCRDLIC.cbl:L1338-L1346}.
         */
        @Test
        @DisplayName("the last page comes back descending, opening on the highest card number")
        void theLastPageOrdersDescending() {
            List<String> seeded = orderedSeededCardNumbers();
            List<String> numbers = cardNumbersOf(
                    cardRepository.findLastPage(Limit.of(SCREEN_PAGE_SIZE)));
            List<String> sortedDescending = new ArrayList<>(numbers);
            sortedDescending.sort(Comparator.reverseOrder());
            assertAll(
                    () -> assertEquals(7, numbers.size(),
                            "a row limit of seven must return seven of the fifty rows"),
                    () -> assertSameCardNumber(seeded.getLast(), numbers.getFirst(),
                            "a null cursor asks for the last page, which opens on the highest key"),
                    () -> assertSameCardNumbers(sortedDescending, numbers,
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
            List<String> seeded = orderedSeededCardNumbers();
            CardPage first = forwardPage(null, null, null, 7);
            CardPage second =
                    forwardPage(first.displayed().getLast().getCardNumber(), null, null, 7);
            CardPage backToFirst = backwardPage(
                    second.displayed().getFirst().getCardNumber(), null, null, 7);
            assertAll(
                    () -> assertSameCardNumbers(seeded.subList(0, 7),
                            cardNumbersOf(first.displayed()),
                            "the first page must hold the seven lowest card numbers"),
                    () -> assertSameCardNumber(seeded.get(7),
                            second.displayed().getFirst().getCardNumber(),
                            "the second page must open on row eight"),
                    () -> assertSameCardNumbers(seeded.subList(0, 7),
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
            List<String> seeded = orderedSeededCardNumbers();
            CardPage page = forwardPage(null, null, null, 7);
            assertAll(
                    () -> assertSameCardNumbers(
                            seeded.subList(0, 7), cardNumbersOf(page.displayed()),
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
            String cardNumber = orderedSeededCardNumbers().getFirst();
            String accountId = accountIdOf(cardNumber);
            CardPage page = forwardPage(null, accountId, null, 7);
            List<String> accountIds = page.displayed().stream()
                    .map(CardEntity::getAccountId).distinct().toList();
            assertAll(
                    () -> assertSameCardNumbers(List.of(cardNumber),
                            cardNumbersOf(page.displayed()),
                            "the fixture places one card on that account"),
                    () -> assertTrue(List.of(accountId).equals(accountIds),
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
            String cardNumber = orderedSeededCardNumbers().get(1);
            CardPage present = forwardPage(null, null, cardNumber, 7);
            CardPage absent = forwardPage(null, null, INSERTED_DUPLICATE_CARD_NUMBER, 7);
            assertAll(
                    () -> assertSameCardNumbers(List.of(cardNumber),
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
            List<String> seeded = orderedSeededCardNumbers();
            String firstCardNumber = seeded.getFirst();
            String secondCardNumber = seeded.get(1);
            String accountId = accountIdOf(firstCardNumber);
            CardPage matching = forwardPage(null, accountId, firstCardNumber, 7);
            CardPage crossed = forwardPage(null, accountId, secondCardNumber, 7);
            assertAll(
                    () -> assertSameCardNumbers(List.of(firstCardNumber),
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
            String accountId = accountIdOf(orderedSeededCardNumbers().getFirst());
            INSERTED_FILLER_CARD_NUMBERS.forEach(number -> insertCard(number, accountId));
            CardPage filteredPage = forwardPage(null, accountId, null, 7);
            List<String> filtered = cardNumbersOf(filteredPage.displayed());
            List<String> unfiltered = cardNumbersOf(forwardPage(null, null, null, 7).displayed());
            long matchesInsideTheUnfilteredPage =
                    unfiltered.stream().filter(filtered::contains).count();
            List<String> accountIds = filteredPage.displayed().stream()
                    .map(CardEntity::getAccountId).distinct().toList();
            assertAll(
                    () -> assertEquals(7, filtered.size(),
                            "seven rows match the filter, so the page fills with matching rows"),
                    () -> assertTrue(List.of(accountId).equals(accountIds),
                            "every row on the filtered page must carry that account identifier"),
                    () -> assertFalse(filteredPage.nextPageExists(),
                            "seven rows match and no eighth follows, so the flag must clear"),
                    () -> assertEquals(1L, matchesInsideTheUnfilteredPage,
                            "the first seven rows in key order hold one matching row, so a filter "
                                    + "applied after the fetch would return one row"));
        }
    }

    /**
     * The native claim statement of {@link OutboxEventRepository}, run against the migrated schema.
     *
     * <p>ADDITIVE. No CardDemo program relays an event, so no source paragraph corresponds.
     *
     * <p>{@code claimDueRows} is the one statement of this service written as native Structured
     * Query Language (SQL), because {@code FOR UPDATE SKIP LOCKED} has no Java Persistence Query
     * Language form. A native statement resolves an unqualified table name against the connection
     * search path rather than through {@code hibernate.default_schema}, so these tests are what
     * prove {@code currentSchema} reaches the connection. Every assertion below fails with
     * {@code relation "outbox_event" does not exist} if it does not.
     *
     * <p>Each test opens its own transaction and rolls it back, which is also what the row lock
     * {@code FOR UPDATE} takes needs.
     */
    @Nested
    @Transactional
    @DisplayName("The native outbox claim statement, over the migrated schema")
    class OutboxClaim {

        /** Event type every row below carries, from {@code messaging/CardUpdated}. */
        private static final String EVENT_TYPE = "CardUpdated";

        /** Account identifier every row below carries as its message key, eleven digits. */
        private static final String AGGREGATE_ID = "00000000011";

        /** One short payload. The column holds 8192 octets and no assertion reads this text. */
        private static final String PAYLOAD = "{\"eventType\":\"CardUpdated\"}";

        /** The instance name a claim records, from {@code carddemo.outbox.relay.instance-id}. */
        private static final String INSTANCE = "card-relay-under-test";

        /**
         * Writes one row due at {@code dueAt} and forces it to the database.
         *
         * <p>The returned instance is the one {@code save} answers with, which is the managed copy.
         * An entity carrying an assigned identifier is not new, so the store merges it and the
         * argument stays detached; a mutation applied to the argument would reach no column.
         *
         * @param dueAt when the row becomes claimable, which the constructor also uses as
         *              {@code created_at}
         * @return the managed row
         */
        private OutboxEventEntity storeRowDueAt(Instant dueAt) {
            OutboxEventEntity stored = outboxEventRepository.save(new OutboxEventEntity(
                    UUID.randomUUID(), EVENT_TYPE, AGGREGATE_ID, PAYLOAD, dueAt));
            entityManager.flush();
            return stored;
        }

        @Test
        @DisplayName("the statement resolves outbox_event and returns the longest-waiting rows")
        void theStatementResolvesTheTableAndOrdersByDueTime() {
            Instant now = Instant.parse("2024-03-01T12:00:00Z");
            OutboxEventEntity oldest = storeRowDueAt(now.minusSeconds(300));
            OutboxEventEntity middle = storeRowDueAt(now.minusSeconds(200));
            storeRowDueAt(now.minusSeconds(100));

            List<UUID> claimed = outboxEventRepository.claimDueRows(now, Limit.of(2)).stream()
                    .map(OutboxEventEntity::getEventId).toList();

            assertEquals(List.of(oldest.getEventId(), middle.getEventId()), claimed,
                    "the statement returns the two longest-waiting rows, in that order, which is "
                            + "also proof that the unqualified table name resolved");
        }

        @Test
        @DisplayName("a row not yet due and a claimed row are both left alone")
        void aRowNotYetDueAndAClaimedRowAreBothLeftAlone() {
            Instant now = Instant.parse("2024-03-01T12:00:00Z");
            OutboxEventEntity due = storeRowDueAt(now.minusSeconds(60));
            storeRowDueAt(now.plusSeconds(60));

            due.claim(INSTANCE, now);
            entityManager.flush();

            assertTrue(outboxEventRepository.claimDueRows(now, Limit.of(10)).isEmpty(),
                    "the filter reads PENDING and next_attempt_at <= now, so a claimed row and a "
                            + "row awaiting its backoff are both excluded");
        }

        @Test
        @DisplayName("a claim held past its timeout is found, and recovery makes the row due")
        void aStrandedClaimIsFoundAndRecovered() {
            Instant now = Instant.parse("2024-03-01T12:00:00Z");
            OutboxEventEntity stranded = storeRowDueAt(now.minusSeconds(600));
            stranded.claim(INSTANCE, now.minusSeconds(300));
            entityManager.flush();

            List<OutboxEventEntity> found =
                    outboxEventRepository.findByRelayStateAndClaimedAtBeforeOrderByClaimedAtAsc(
                            OutboxEventEntity.RelayState.CLAIMED, now.minusSeconds(120),
                            Limit.of(10));
            found.forEach(row -> row.recordFailure("claim expired", now, now));
            entityManager.flush();

            assertAll(
                    () -> assertEquals(List.of(stranded.getEventId()),
                            found.stream().map(OutboxEventEntity::getEventId).toList(),
                            "the finder returns the claim held past the cutoff"),
                    () -> assertEquals(OutboxEventEntity.RelayState.PENDING,
                            stranded.getRelayState(), "a recovered row returns to PENDING"),
                    () -> assertEquals(1, stranded.getAttemptCount(),
                            "recovering a stranded claim counts one failed attempt"),
                    () -> assertEquals(List.of(stranded.getEventId()),
                            outboxEventRepository.claimDueRows(now, Limit.of(10)).stream()
                                    .map(OutboxEventEntity::getEventId).toList(),
                            "the recovered row is claimable again"));
        }

        /**
         * Runs the bounded retention delete and reads the result back.
         *
         * <p>{@code ddl-auto: validate} reads no {@code @Query} text, so a native statement is
         * unchecked until it runs. {@code domain/RetentionSweep} owns this call.
         */
        @Test
        @DisplayName("the retention delete takes a published row past the horizon and never an "
                + "unpublished one")
        void theRetentionDeleteTakesOnlyPublishedRowsPastTheHorizon() {
            Instant horizon = Instant.parse("2024-03-01T12:00:00Z");
            OutboxEventEntity expired = storeRowDueAt(horizon.minusSeconds(3600));
            expired.markPublished(horizon.minusSeconds(3600));
            OutboxEventEntity recent = storeRowDueAt(horizon.plusSeconds(3600));
            recent.markPublished(horizon.plusSeconds(3600));
            OutboxEventEntity unpublished = storeRowDueAt(horizon.minusSeconds(3600));
            entityManager.flush();

            int removed = outboxEventRepository.deletePublishedBefore(horizon, 1000);
            entityManager.flush();
            entityManager.clear();

            assertAll("the bounded retention delete",
                    () -> assertEquals(1, removed, "one published row precedes the horizon"),
                    () -> assertTrue(outboxEventRepository.findById(expired.getEventId()).isEmpty(),
                            "the expired row is gone"),
                    () -> assertTrue(outboxEventRepository.findById(recent.getEventId()).isPresent(),
                            "a published row inside the horizon stays"),
                    () -> assertTrue(
                            outboxEventRepository.findById(unpublished.getEventId()).isPresent(),
                            "the relay has not published this row, so retention must not take it"));
        }

        @Test
        @DisplayName("the retention delete honours its row limit and reports zero when none is due")
        void theRetentionDeleteHonoursItsLimit() {
            Instant horizon = Instant.parse("2024-03-01T12:00:00Z");
            for (int row = 0; row < 3; row++) {
                OutboxEventEntity expired = storeRowDueAt(horizon.minusSeconds(3600 + row));
                expired.markPublished(horizon.minusSeconds(3600 + row));
            }
            entityManager.flush();

            int firstStatement = outboxEventRepository.deletePublishedBefore(horizon, 2);
            entityManager.flush();
            int secondStatement = outboxEventRepository.deletePublishedBefore(horizon, 2);
            entityManager.flush();
            int thirdStatement = outboxEventRepository.deletePublishedBefore(horizon, 2);

            assertAll("the statement allowance of one sweep",
                    () -> assertEquals(2, firstStatement, "the limit bounds one statement"),
                    () -> assertEquals(1, secondStatement, "the remainder follows"),
                    () -> assertEquals(0, thirdStatement,
                            "the sweeper stops on a count below the batch size"));
        }

        /**
         * Runs the bounded marker retention delete over rows written directly.
         *
         * <p>This service registers no listener, so its marker repository declares no claim and no
         * save: nothing in the service writes a marker. The table exists because the shape is
         * uniform across the six schemas, and the retention delete has to work wherever the table
         * does, so the rows here are written through the driver.
         */
        @Test
        @DisplayName("the marker retention delete takes the expired marker and keeps the newer one")
        void theMarkerRetentionDeleteTakesOnlyExpiredMarkers() {
            UUID expired = UUID.randomUUID();
            UUID recent = UUID.randomUUID();
            storeMarker(expired, Instant.parse("2020-01-01T00:00:00Z"));
            storeMarker(recent, Instant.parse("2030-01-01T00:00:00Z"));

            int removed = processedEventRepository.deleteMarkersProcessedBefore(
                    Instant.parse("2021-01-01T00:00:00Z"), 1000);

            assertAll("the bounded marker delete",
                    () -> assertEquals(1, removed, "one marker precedes the horizon"),
                    () -> assertFalse(processedEventRepository.existsByEventIdOnAnyTopic(expired),
                            "the expired marker is gone"),
                    () -> assertTrue(processedEventRepository.existsByEventIdOnAnyTopic(recent),
                            "a marker inside the horizon stays"));
        }

        /**
         * Writes one marker through the driver.
         *
         * @param eventId     the marker key
         * @param processedAt when the marker records the delivery as handled
         */
        private void storeMarker(UUID eventId, Instant processedAt) {
            jdbcTemplate.update(
                    "INSERT INTO processed_event (event_id, processed_at, consumed_topic)"
                            + " VALUES (?, ?, ?)",
                    eventId, java.sql.Timestamp.from(processedAt),
                    ProcessedEventEntity.NO_CONSUMED_TOPIC);
        }
    }

    @Nested
    @DisplayName("The transaction-local lock wait bound, over the migrated schema")
    class BoundedLockWait {

        /** How long the second reader below is allowed to wait, short so the test is quick. */
        private static final String TEST_BOUND = "250ms";

        /** Ceiling the measured wait must stay under for the bound to have been honoured. */
        private static final long GENEROUS_CEILING_MS = 5_000L;

        /**
         * A bounded wait gives up on a held card lock instead of waiting for it.
         *
         * <p>Without a bound PostgreSQL waits, and it waits for as long as the other writer holds
         * the row. That is why {@code COULD-NOT-LOCK-FOR-UPDATE} was unreachable through
         * contention: the source set it whenever its {@code READ UPDATE} came back with anything
         * other than {@code DFHRESP(NORMAL)}, at {@code app/cbl/COCRDUPC.cbl:L1445-L1446}, and a
         * wait that never ends comes back with nothing at all.
         *
         * <p>{@link CardRepository#applyLockWaitBound(String)} bounds it. The bound is
         * transaction-local, so the first transaction below is unaffected and only the second gives
         * up. {@code src/main/resources/application.yml} ships three seconds through
         * {@code carddemo.write.lock-wait-ms}.
         *
         * <p>The give-up arrives as a {@link CannotAcquireLockException}, which is a
         * {@code PessimisticLockingFailureException}, and that is the type
         * {@code domain/CardUpdateService} turns into {@code CardUpdateService.LockNotTaken} and
         * then into {@link CardValidationMessages#COULD_NOT_LOCK_FOR_UPDATE}. The elapsed time is
         * asserted too, because a bound that is set but not honoured would still raise eventually
         * and the assertion on the type alone would pass on a wait of any length.
         *
         * <p>The give-up leaves its transaction unusable, so it is carried out through the
         * transaction boundary rather than caught inside it. {@code domain/CardUpdateService} does
         * the same, and for the same reason: returning normally across a rollback-only transaction
         * answers with a rollback report instead of the outcome.
         *
         * @throws Exception when a worker cannot be run
         */
        @Test
        @DisplayName("a bounded wait gives up on a held card lock instead of waiting for it")
        void aBoundedWaitGivesUpOnAHeldCardLock() throws Exception {
            String heldCardNumber = cardRepository.findFirstPage(Limit.of(1)).getFirst()
                    .getCardNumber();
            CountDownLatch firstLocked = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);

            try {
                Future<?> first = workers.submit(() -> inNewTransaction(() -> {
                    cardRepository.findForUpdateByCardNumber(heldCardNumber).orElseThrow();
                    firstLocked.countDown();
                    awaitLatch(releaseFirst, "the held card lock was not released");
                    return null;
                }));
                assertTrue(firstLocked.await(5, TimeUnit.SECONDS),
                        "the first transaction never took the card lock");

                Future<?> second = workers.submit(() -> inNewTransaction(() -> {
                    cardRepository.applyLockWaitBound(TEST_BOUND);
                    return cardRepository.findForUpdateByCardNumber(heldCardNumber);
                }));

                long startedAt = System.nanoTime();
                ExecutionException thrown = assertThrows(ExecutionException.class,
                        () -> second.get(10, TimeUnit.SECONDS),
                        "the bounded read returned a row it could not have locked");
                long waitedMs = (System.nanoTime() - startedAt) / 1_000_000L;

                assertAll(
                        () -> assertInstanceOf(CannotAcquireLockException.class,
                                rootCauseOf(thrown),
                                "the give-up is the type domain/CardUpdateService maps onto "
                                        + CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE),
                        () -> assertTrue(waitedMs < GENEROUS_CEILING_MS,
                                "the bound was honoured, so the wait ended near it rather than at "
                                        + "the release of the other writer, but it took "
                                        + waitedMs + "ms"));

                releaseFirst.countDown();
                first.get(5, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
                workers.shutdownNow();
            }
        }

        /**
         * Runs one callback inside a transaction of its own.
         *
         * <p>This nested class carries no {@code @Transactional} annotation, unlike every other
         * nested class in this file. Two transactions have to exist at once for one to contend with
         * the other, and a test-managed transaction would hold both callbacks.
         *
         * @param callback the work to run
         * @param <T>      what the work answers
         * @return whatever the callback answered
         */
        private <T> T inNewTransaction(Supplier<T> callback) {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return transaction.execute(status -> callback.get());
        }

        /** Waits for one test latch and preserves interruption. */
        private void awaitLatch(CountDownLatch latch, String failureMessage) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError(failureMessage);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("the card lock wait was interrupted", interrupted);
            }
        }

        /**
         * Walks a throwable to the first cause the datastore layer produced.
         *
         * @param thrown the throwable a worker reported
         * @return the deepest cause carrying a distinct type from the datastore layer
         */
        private Throwable rootCauseOf(Throwable thrown) {
            Throwable walked = thrown;
            while (walked.getCause() != null && !(walked instanceof DataAccessException)) {
                walked = walked.getCause();
            }
            return walked;
        }
    }
}
