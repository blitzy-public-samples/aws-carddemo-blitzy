package com.carddemo.ledger.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.outbox.OutboxRelay;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs {@link AccountBalanceProjectionRepository#applyStateChange} against a real database.
 *
 * <p>This class exists because that statement is native, and nothing else would have run it before a
 * deployment did. Spring Data derives and checks a method-name query at start-up and Hibernate
 * validates every mapped column against the migrated schema, but a native statement is passed through
 * to the driver as text. Its {@code ON CONFLICT} target, its excluded-row references and the
 * comparison that makes it monotonic are all invisible to both checks, so a defect in any of them
 * would have surfaced on the first account change reaching a running service and nowhere earlier.
 *
 * <p>The three properties asserted below are the three the statement exists to hold. An absent row is
 * inserted, which is the projection bootstrap an account opened after
 * {@code app/data/ASCII/acctdata.txt} was loaded depends on. A present row is replaced, which is what
 * reproduces the rewrite at {@code app/cbl/COACTUPC.cbl:L3964-L3974} and the cycle close at
 * {@code app/cbl/CBACT04C.cbl:L353-L354}. And a change that did not occur after the change the row
 * already carries is discarded, because this table is a copy and a redelivery arriving behind a newer
 * one must not move it backwards.
 *
 * <p>Flyway owns the schema, so the migration under test is the shipped one. No broker is reached: a
 * stand-in replaces the producer template and the relay.
 */
@Testcontainers
@SpringBootTest(properties = {
    // The two listeners of this service must not retry an absent broker.
    "spring.kafka.listener.auto-startup=false",
    "TOPIC_DEAD_LETTER_SUFFIX=.DLT",
    // The four credentials application.yml leaves without a default. Each value below is a
    // generated fake this repository states nowhere else, and config/SecurityConfig refuses a
    // blank, published or unprefixed one at start-up.
    "KAFKA_SASL_PASSWORD=a-generated-broker-value-for-the-projection-test",
    "ADMIN_PASSWORD_HASH={noop}a-generated-admin-value-for-the-projection-test",
    "USER_PASSWORD_HASH={noop}a-generated-user-value-for-the-projection-test",
    "MONITORING_PASSWORD_HASH={noop}a-generated-monitoring-value-for-the-projection-test",
})
@DisplayName("The projection upsert of AccountBalanceProjectionRepository, on a real database")
class AccountBalanceProjectionRepositoryTest {

    /** Login the container creates, and the schema owner Flyway migrates under. */
    private static final String DATABASE_LOGIN = "carddemo_ledger_svc";

    /** A generated value for this run, matching no provider credential shape. */
    private static final String DATABASE_SECRET = "a-generated-database-value-for-the-projection";

    /** An account no row of {@code V2__seed.sql} carries, so its row starts absent. */
    private static final String NEW_ACCOUNT = "00000000099";

    /** The instant the first change below reports. */
    private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");

    /** An instant after {@link #EARLIER}, for the change that must win. */
    private static final Instant LATER = Instant.parse("2026-02-01T00:00:00Z");

    /** The image tag the compose stack pins. */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("carddemo_ledger")
            .withUsername(DATABASE_LOGIN)
            .withPassword(DATABASE_SECRET);

    static {
        POSTGRES.start();
    }

    /** Replaces the producer template, so the context starts with no broker reachable. */
    @MockitoBean
    private org.springframework.kafka.core.KafkaTemplate<String, Object> producerTemplate;

    /** Prevents the scheduled publisher from running beside these assertions. */
    @MockitoBean
    private OutboxRelay relay;

    private AccountBalanceProjectionRepository projections;
    private JdbcTemplate database;

    /**
     * Runs one modifying statement in its own committed transaction.
     *
     * <p>{@code applyStateChange} carries {@code @Modifying} and no transaction of its own, so it is
     * called inside a transaction in production and must be here too. Committing rather than rolling
     * back also means every read below opens a fresh persistence context: the statement is native, so
     * a session that had already loaded the row would not learn of the change from it.
     */
    private TransactionTemplate boundary;

    /** The schema Flyway migrates into, which is also the one the shipped URL selects. */
    private static final String SCHEMA = "ledger_service";

    /**
     * Points the context at the container, selecting the schema the shipped URL selects.
     *
     * <p>{@code currentSchema} is not decoration. {@code application.yml} carries it on
     * {@code spring.datasource.url}, and the statement under test is native, so it is the only thing
     * that resolves its unqualified table name. {@code hibernate.default_schema} covers the
     * entity-mapped queries and does not reach a native one, so a URL without this parameter fails
     * the native statement alone while every other query keeps working. Dropping it here would have
     * tested a configuration this service never runs under.
     */
    @DynamicPropertySource
    static void containerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> POSTGRES.getJdbcUrl() + "&currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Resolves the store and clears the one account these tests write. */
    @BeforeEach
    void resolveBeansAndClearAccount(ApplicationContext context) {
        projections = context.getBean(AccountBalanceProjectionRepository.class);
        database = context.getBean(JdbcTemplate.class);
        boundary = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        database.update("DELETE FROM ledger_service.account_balance_projection WHERE account_id = ?",
                NEW_ACCOUNT);
    }

    /**
     * Applies one change through the statement under test.
     *
     * @param balance     the balance the change reports
     * @param cycleCredit the cycle-credit accumulator the change reports
     * @param cycleDebit  the cycle-debit accumulator the change reports
     * @param occurredAt  the producer's clock reading
     * @return the row count the statement answered
     */
    private int apply(String balance, String cycleCredit, String cycleDebit, Instant occurredAt) {
        return boundary.execute(status -> projections.applyStateChange(NEW_ACCOUNT,
                new BigDecimal(balance), new BigDecimal(cycleCredit), new BigDecimal(cycleDebit),
                UUID.randomUUID(), occurredAt));
    }

    @Nested
    @DisplayName("Bootstrap, the gap an account opened after deployment fell into")
    class Bootstrap {

        @Test
        @DisplayName("an absent row is inserted, so a new account gets its first row")
        void anAbsentRowIsInserted() {
            assertTrue(projections.findById(NEW_ACCOUNT).isEmpty(),
                    "this account carries no seeded row, which is the condition under test");

            int applied = apply("193.00", "10.00", "-4.00", EARLIER);

            AccountBalanceProjectionEntity stored = projections.findById(NEW_ACCOUNT).orElseThrow();
            assertAll(
                    () -> assertEquals(1, applied, "the insert path reports one row"),
                    () -> assertEquals(new BigDecimal("193.00"), stored.getCurrentBalance(),
                            "ACCT-CURR-BAL reached the column at its declared scale"),
                    () -> assertEquals(new BigDecimal("10.00"), stored.getCycleCredit(),
                            "ACCT-CURR-CYC-CREDIT reached the column"),
                    () -> assertEquals(new BigDecimal("-4.00"), stored.getCycleDebit(),
                            "a negative accumulator keeps its sign"),
                    () -> assertNotNull(stored.getSourceEventId(), "provenance is recorded"),
                    () -> assertEquals(EARLIER, stored.getSourceOccurredAt(),
                            "the producer's clock is stored, not this service's"));
        }
    }

    @Nested
    @DisplayName("Replacement and ordering over a row that already exists")
    class ReplacementAndOrdering {

        @Test
        @DisplayName("a later change replaces all three values rather than adding to them")
        void aLaterChangeReplacesTheValues() {
            apply("193.00", "10.00", "-4.00", EARLIER);

            int applied = apply("50.00", "0.00", "0.00", LATER);

            AccountBalanceProjectionEntity stored = projections.findById(NEW_ACCOUNT).orElseThrow();
            assertAll(
                    () -> assertEquals(1, applied, "the conflict path reports one row"),
                    () -> assertEquals(new BigDecimal("50.00"), stored.getCurrentBalance(),
                            "the balance is replaced: 50.00 and not 243.00, because the account "
                                    + "service rewrites the record it owns"),
                    () -> assertEquals(new BigDecimal("0.00"), stored.getCycleCredit(),
                            "a cycle close zeroes the accumulator rather than adding zero"),
                    () -> assertEquals(new BigDecimal("0.00"), stored.getCycleDebit(),
                            "and the same for the debit accumulator"),
                    () -> assertEquals(LATER, stored.getSourceOccurredAt(),
                            "the row now carries the later clock reading"));
        }

        @Test
        @DisplayName("a change behind the stored one is discarded and reports no row")
        void anEarlierChangeIsDiscarded() {
            apply("50.00", "0.00", "0.00", LATER);

            int applied = apply("193.00", "10.00", "-4.00", EARLIER);

            AccountBalanceProjectionEntity stored = projections.findById(NEW_ACCOUNT).orElseThrow();
            assertAll(
                    () -> assertEquals(0, applied,
                            "the guard discards it, which is what the consumer reads as a "
                                    + "redelivery arriving behind a newer change"),
                    () -> assertEquals(new BigDecimal("50.00"), stored.getCurrentBalance(),
                            "the newer balance stands"),
                    () -> assertEquals(LATER, stored.getSourceOccurredAt(),
                            "and so does the newer clock reading"));
        }

        @Test
        @DisplayName("a change at the same instant is discarded, so a redelivery is a no-op")
        void aChangeAtTheSameInstantIsDiscarded() {
            apply("50.00", "0.00", "0.00", LATER);

            int applied = apply("999.00", "1.00", "1.00", LATER);

            assertAll(
                    () -> assertEquals(0, applied,
                            "the comparison is strictly-after, so an equal instant does not win"),
                    () -> assertEquals(new BigDecimal("50.00"),
                            projections.findById(NEW_ACCOUNT).orElseThrow().getCurrentBalance(),
                            "which makes a redelivery of one change harmless"));
        }

        @Test
        @DisplayName("a row the posting path created carries no provenance and accepts any change")
        void aRowWithNoProvenanceAcceptsAnyChange() {
            database.update("""
                    INSERT INTO ledger_service.account_balance_projection
                        (account_id, current_balance, cycle_credit, cycle_debit)
                    VALUES (?, ?, ?, ?)
                    """, NEW_ACCOUNT, new BigDecimal("7.00"), new BigDecimal("0.00"),
                    new BigDecimal("0.00"));

            int applied = apply("50.00", "0.00", "0.00", EARLIER);

            assertAll(
                    () -> assertEquals(1, applied,
                            "a row carrying no clock reading has nothing to compare against, so "
                                    + "the first change to reach it wins and the projection "
                                    + "converges"),
                    () -> assertEquals(EARLIER,
                            projections.findById(NEW_ACCOUNT).orElseThrow().getSourceOccurredAt(),
                            "and the row now carries provenance"));
        }
    }

    @Nested
    @DisplayName("The provenance constraint the migration declares")
    class Provenance {

        @Test
        @DisplayName("the two provenance columns are written together or not at all")
        void theTwoProvenanceColumnsMoveTogether() {
            apply("193.00", "10.00", "-4.00", EARLIER);
            AccountBalanceProjectionEntity stored = projections.findById(NEW_ACCOUNT).orElseThrow();

            assertEquals(stored.getSourceEventId() == null, stored.getSourceOccurredAt() == null,
                    "ck_account_balance_projection_provenance holds: a row carries both or "
                            + "neither, so no row records a clock reading no event explains");
        }

        @Test
        @DisplayName("a seeded row starts with neither, which the constraint permits")
        void aSeededRowStartsWithNeither() {
            Optional<AccountBalanceProjectionEntity> seeded =
                    projections.findById("00000000001");

            assertTrue(seeded.isPresent(), "V2__seed.sql loads this account");
            assertAll(
                    () -> assertEquals(null, seeded.get().getSourceEventId(),
                            "the seed loads the dataset directly and names no event"),
                    () -> assertEquals(null, seeded.get().getSourceOccurredAt(),
                            "so the first change to arrive wins, and the copy converges"));
        }
    }
}
