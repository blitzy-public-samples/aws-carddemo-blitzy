package com.carddemo.authorization.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.TestIdentityPasswords;
import com.carddemo.authorization.entity.AuthorizationDecisionEntity;
import com.carddemo.authorization.entity.CardCrossReferenceEntity;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.entity.ProcessedEventEntity.ProcessedEventId;
import com.carddemo.authorization.entity.ProcessedEventEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the four native statements of the authorization service against the migrated schema in a
 * PostgreSQL container, then reads the result back.
 *
 * <p>A native statement is not checked by the persistence layer at start-up. {@code ddl-auto:
 * validate} matches mapped attributes against columns and reads no {@code @Query} text, so a
 * misspelled column or a dialect that rejects a clause surfaces only when the statement runs. Every
 * statement asserted here is one the mapping model cannot check:
 * {@link ProcessedEventRepository#claimEvent}, {@link CardCrossReferenceRepository#refreshObservation},
 * {@link AccountCreditSnapshotRepository#applyStateChange} and
 * {@link OutboxEventRepository#claimDueRows}.
 *
 * <p>Two schema changes are read back here as well. {@code outbox_event.aggregate_id} holds either
 * key form, from {@code XREF-ACCT-ID PIC 9(11)} at app/cpy/CVACT03Y.cpy:L7 and {@code TRAN-ID PIC
 * X(16)} at app/cpy/CVTRA05Y.cpy:L5, and {@code authorization_decision} holds one row per outcome.
 *
 * <p>The connection string carries {@code currentSchema}, as
 * {@code src/main/resources/application.yml} carries it. A native statement resolves an unqualified
 * table name against the connection search path, which {@code hibernate.default_schema} does not
 * set, so without it every statement below fails to find its table while every mapped query works.
 *
 * <p>The four values in the annotation below stand in for the credential variables the shipped
 * configuration leaves undefined, and each is inert. The broker address points where nothing
 * listens: no test here publishes or consumes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "ACQUIRER_PASSWORD_HASH=" + TestIdentityPasswords.ACQUIRER_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "carddemo.outbox.relay.fixed-delay-ms=3600000"
        })
@DisplayName("The native statements of the authorization service, against the migrated schema")
class NativeStatementIT {

    /** Rows a conditional native update writes when its guard rejects the row. */
    private static final int NO_ROW_WRITTEN_LOCAL = 0;

    /** Image tag of the database container. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** Database name, login name and password of the container, one value for all three. */
    private static final String CONTAINER_CREDENTIAL = "carddemo";

    /** Host and port the broker client is pointed at, where nothing listens. */
    private static final String UNREACHABLE_BROKER = "localhost:1";

    /** Schema Flyway migrates, and the one the connection search path names. */
    private static final String MIGRATED_SCHEMA = "authorization_service";

    /** Batch bound one purge statement is given, larger than any fixture the class stores. */
    private static final int PURGE_BATCH = 1000;

    /** Seconds a lock assertion waits for the thread holding a row, generous for a container. */
    private static final long LOCK_WAIT_SECONDS = 10L;

    /**
     * The lock-wait bound the contended decision applies, short enough to keep the assertion quick.
     *
     * <p>The shipped value is three seconds and lives in {@code carddemo.decision.lock-wait-ms}. This
     * assertion measures that the bound is applied at all, not what a deployment configures.
     */
    private static final String SHORT_LOCK_BOUND = "250ms";

    /** Topic the claimed markers of this class record. */
    private static final String CONSUMED_TOPIC = "account.state-changed";

    /**
     * The other topic this service reads, and the one that makes the marker key's second half
     * observable.
     *
     * <p>{@code messaging/CardUpdatedConsumer} subscribes to it, while
     * {@code messaging/AccountStateChangedConsumer} subscribes to {@link #CONSUMED_TOPIC}. Two
     * producing services assign the event identifiers on those two topics independently, which is
     * why {@code src/main/resources/db/migration/V6__processed_event_topic_key.sql} keys the marker
     * on the event and the topic together.
     */
    private static final String OTHER_CONSUMED_TOPIC = "card.updated";

    /** One seeded card and the account it belongs to, from app/data/ASCII/cardxref.txt. */
    private static final String SEEDED_CARD = "0500024453765740";

    /**
     * The authenticated request identity every row records, at the width
     * {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L18} declares.
     */
    private static final String ACTOR = "OPERATR1";

    /**
     * The processing moment a caller declared, at the width {@code TRAN-PROC-TS PIC X(26)} holds. The
     * value is the one record one of {@code app/data/ASCII/dailytran.txt} carries.
     */
    private static final String DECLARED_PROCESSING_TIMESTAMP = "2022-06-10-19.27.53.410000";
    private static final String SEEDED_ACCOUNT = "00000000050";

    /**
     * A second account identifier, for the claim assertions that need two aggregates.
     *
     * <p>{@code claimDueRows} returns the due head row of each aggregate, so a batch of more than one
     * row needs more than one account. Neither of these two identifiers is referenced by any other
     * table: {@code outbox_event.aggregate_id} carries no foreign key, because the row outlives the
     * account state it reports.
     */
    private static final String SECOND_ACCOUNT = "00000000051";

    /** A third account identifier, for the assertion that the row limit bounds a batch. */
    private static final String THIRD_ACCOUNT = "00000000052";

    /**
     * The suffix pattern {@code CardUpdated.visibleDigitsSuffix()} builds from the four digits
     * {@link #SEEDED_CARD} leaves visible in its masked form.
     */
    private static final String SEEDED_VISIBLE_DIGITS_SUFFIX = "%5740";

    /** An account no fixture carries, used to prove the snapshot statement inserts. */
    private static final String UNSEEN_ACCOUNT = "00000099999";

    /** A moment every stamp below is measured from. */
    private static final Instant BASE_MOMENT = Instant.parse("2026-02-01T00:00:00Z");

    private static final PostgreSQLContainer POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(CONTAINER_CREDENTIAL)
                .withUsername(CONTAINER_CREDENTIAL)
                .withPassword(CONTAINER_CREDENTIAL);
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", NativeStatementIT::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> UNREACHABLE_BROKER);
    }

    /**
     * Returns the container connection string with {@code currentSchema} appended.
     *
     * <p>{@link PostgreSQLContainer#getJdbcUrl()} already carries one query parameter, so a second
     * {@code ?} would fold this setting into that parameter's value and lose it.
     *
     * @return the connection string every native statement here resolves its tables through
     */
    private static String migratedSchemaUrl() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "currentSchema=" + MIGRATED_SCHEMA;
    }

    @Autowired
    private ProcessedEventRepository markers;

    @Autowired
    private CardCrossReferenceRepository crossReferences;

    @Autowired
    private AccountCreditSnapshotRepository snapshots;

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private AuthorizationDecisionRepository decisions;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Empties what a test wrote, so no row reaches the next test. The seeded rows stay. */
    @AfterEach
    void clearWrittenRows() {
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM authorization_decision");
        jdbcTemplate.update("DELETE FROM account_credit_snapshot WHERE account_id = ?",
                UNSEEN_ACCOUNT);
        jdbcTemplate.update("UPDATE card_xref SET source_event_id = NULL, "
                + "source_occurred_at = NULL, observed_at = ?",
                java.sql.Timestamp.from(BASE_MOMENT));
        jdbcTemplate.update("UPDATE account_credit_snapshot SET source_event_id = NULL, "
                + "source_occurred_at = NULL, observed_at = ?, pending_cycle_credit = 0, "
                + "pending_cycle_debit = 0, pending_expires_at = NULL",
                java.sql.Timestamp.from(BASE_MOMENT));
    }

    @Nested
    @DisplayName("claimEvent, the marker claim both projection consumers run")
    class MarkerClaim {

        @Test
        @DisplayName("the first delivery takes the claim and a second reads zero")
        void oneDeliveryTakesTheClaim() {
            UUID eventId = UUID.randomUUID();

            int first = transactionTemplate.execute(status ->
                    markers.claimEvent(eventId, BASE_MOMENT, CONSUMED_TOPIC));
            int second = transactionTemplate.execute(status ->
                    markers.claimEvent(eventId, BASE_MOMENT.plusSeconds(1), CONSUMED_TOPIC));

            assertEquals(1, first, "the first delivery inserts one marker");
            assertEquals(ProcessedEventRepository.ALREADY_CLAIMED, second,
                    "the second delivery of one event writes nothing and is told so");
            assertEquals(1, markerCount(eventId), "one marker exists, not two");
        }

        @Test
        @DisplayName("the claim records the topic the delivery arrived on")
        void theClaimRecordsItsTopic() {
            UUID eventId = UUID.randomUUID();

            transactionTemplate.execute(status ->
                    markers.claimEvent(eventId, BASE_MOMENT, CONSUMED_TOPIC));

            assertEquals(CONSUMED_TOPIC, jdbcTemplate.queryForObject(
                    "SELECT consumed_topic FROM processed_event WHERE event_id = ?",
                    String.class, eventId),
                    "the marker names the topic, so a redelivery can be traced to its stream");
        }

        /**
         * Asserts a claim naming no topic is refused by the schema.
         *
         * <p>{@code consumed_topic} became a key column in
         * {@code src/main/resources/db/migration/V6__processed_event_topic_key.sql}, and a key column
         * holds no null. A caller with no {@code RECEIVED_TOPIC} header records
         * {@link ProcessedEventEntity#NO_CONSUMED_TOPIC} instead, which both listeners of this
         * service do through their own {@code recordedTopic} helper. This test holds the schema half
         * of that contract: were the column still nullable, a null-topic marker would key a row the
         * composite guard could never find again.
         */
        @Test
        @DisplayName("a claim recording no topic is refused, because the topic is half of the key")
        void aClaimRecordingNoTopicIsAccepted() {
            UUID eventId = UUID.randomUUID();

            assertThrows(DataIntegrityViolationException.class,
                    () -> transactionTemplate.execute(status ->
                            markers.claimEvent(eventId, BASE_MOMENT, null)),
                    "consumed_topic is NOT NULL since V6__processed_event_topic_key.sql");
        }

        /**
         * Asserts one identifier is claimable once per topic rather than once per service.
         *
         * <p>This is the defect {@code V6__processed_event_topic_key.sql} removes, at the statement
         * that carries the guard. Both claims below name one event identifier and two different
         * topics, and both must succeed: they are two different events that happen to share an
         * identifier, because the card service and the account service assign theirs independently.
         * Under {@code ON CONFLICT (event_id)} the second returned zero, and the consumer reading
         * that zero concluded it had already handled the event and applied nothing at all.
         */
        @Test
        @DisplayName("one identifier is claimable once per topic, not once per service")
        void oneIdentifierIsClaimableOncePerTopic() {
            UUID eventId = UUID.randomUUID();

            int onAccountStateTopic = transactionTemplate.execute(status ->
                    markers.claimEvent(eventId, BASE_MOMENT, CONSUMED_TOPIC));
            int onCardTopic = transactionTemplate.execute(status ->
                    markers.claimEvent(eventId, BASE_MOMENT, OTHER_CONSUMED_TOPIC));
            int repeatOnCardTopic = transactionTemplate.execute(status ->
                    markers.claimEvent(eventId, BASE_MOMENT.plusSeconds(1),
                            OTHER_CONSUMED_TOPIC));

            assertAll("one identifier across two topics",
                    () -> assertEquals(1, onAccountStateTopic,
                            "the first delivery claims its own topic"),
                    () -> assertEquals(1, onCardTopic,
                            "a different event sharing an identifier claims its own topic, which "
                                    + "the narrow key refused"),
                    () -> assertEquals(ProcessedEventRepository.ALREADY_CLAIMED, repeatOnCardTopic,
                            "a redelivery on one topic is still suppressed"),
                    () -> assertEquals(2, markerCount(eventId),
                            "each topic carries its own marker"),
                    () -> assertTrue(markers.existsById(
                                    new ProcessedEventId(eventId, CONSUMED_TOPIC)),
                            "the account-state marker is found by its whole key"),
                    () -> assertTrue(markers.existsById(
                                    new ProcessedEventId(eventId, OTHER_CONSUMED_TOPIC)),
                            "the card marker is found by its whole key"));
        }

        private int markerCount(UUID eventId) {
            return jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM processed_event WHERE event_id = ?",
                    Integer.class, eventId);
        }
    }

    @Nested
    @DisplayName("refreshObservation, the stamp the CardUpdated consumer moves")
    class ObservationRefresh {

        @Test
        @DisplayName("the four visible digits and the account select the seeded row")
        void theVisibleDigitsSelectTheRow() {
            UUID eventId = UUID.randomUUID();
            Instant occurredAt = BASE_MOMENT.plus(Duration.ofHours(1));

            int written = transactionTemplate.execute(status ->
                    crossReferences.refreshObservation(SEEDED_ACCOUNT,
                            SEEDED_VISIBLE_DIGITS_SUFFIX,
                            eventId, occurredAt, occurredAt.plusSeconds(2)));

            assertEquals(1, written, "one row carries that account and those four digits");
            Optional<CardCrossReferenceEntity> row = crossReferences.findByCardNumber(SEEDED_CARD);
            assertTrue(row.isPresent(), "the seeded row is still present");
            assertEquals(occurredAt, row.get().getSourceOccurredAt(),
                    "the stamp names when the event occurred");
        }

        @Test
        @DisplayName("a reordered delivery does not move the stamp backwards")
        void aReorderedDeliveryIsDiscarded() {
            Instant later = BASE_MOMENT.plus(Duration.ofHours(2));
            Instant earlier = BASE_MOMENT.plus(Duration.ofHours(1));
            transactionTemplate.execute(status ->
                    crossReferences.refreshObservation(SEEDED_ACCOUNT,
                            SEEDED_VISIBLE_DIGITS_SUFFIX,
                            UUID.randomUUID(), later, later));

            int written = transactionTemplate.execute(status ->
                    crossReferences.refreshObservation(SEEDED_ACCOUNT,
                            SEEDED_VISIBLE_DIGITS_SUFFIX,
                            UUID.randomUUID(), earlier, earlier));

            assertEquals(NO_ROW_WRITTEN_LOCAL, written,
                    "an event that occurred before the one recorded writes nothing");
            assertEquals(later,
                    crossReferences.findByCardNumber(SEEDED_CARD).orElseThrow()
                            .getSourceOccurredAt(),
                    "the newer stamp survives the older delivery");
        }

        @Test
        @DisplayName("an account holding no such card writes nothing")
        void anAccountHoldingNoSuchCardWritesNothing() {
            int written = transactionTemplate.execute(status ->
                    crossReferences.refreshObservation(SEEDED_ACCOUNT, "%0000",
                            UUID.randomUUID(), BASE_MOMENT.plusSeconds(60),
                            BASE_MOMENT.plusSeconds(60)));

            assertEquals(NO_ROW_WRITTEN_LOCAL, written,
                    "no row matches, and the caller is told rather than left to assume");
        }
    }

    @Nested
    @DisplayName("applyStateChange, the snapshot the AccountStateChanged consumer maintains")
    class SnapshotApplication {

        @Test
        @DisplayName("a seeded row takes the change, and the two cycle accumulators arrive")
        void aSeededRowTakesTheChange() {
            Instant occurredAt = BASE_MOMENT.plus(Duration.ofHours(3));

            int written = transactionTemplate.execute(status ->
                    snapshots.applyStateChange(SEEDED_ACCOUNT, new BigDecimal("5000.00"),
                            "2099-12-31", new BigDecimal("120.00"), new BigDecimal("340.00"),
                            UUID.randomUUID(), occurredAt, occurredAt));

            assertEquals(1, written, "the seeded snapshot row takes the change");
            assertEquals(new BigDecimal("120.00"), jdbcTemplate.queryForObject(
                    "SELECT current_cycle_credit FROM account_credit_snapshot "
                            + "WHERE account_id = ?", BigDecimal.class, SEEDED_ACCOUNT),
                    "the credit-limit rule of app/cbl/CBTRN02C.cbl:L403-L407 reads this column");
            assertEquals(new BigDecimal("340.00"), jdbcTemplate.queryForObject(
                    "SELECT current_cycle_debit FROM account_credit_snapshot "
                            + "WHERE account_id = ?", BigDecimal.class, SEEDED_ACCOUNT),
                    "and this one, so an argument transposed between them changes a decision");
        }

        @Test
        @DisplayName("an account this service never saw is inserted")
        void anUnseenAccountIsInserted() {
            int written = transactionTemplate.execute(status ->
                    snapshots.applyStateChange(UNSEEN_ACCOUNT, new BigDecimal("1.00"),
                            "2099-12-31", BigDecimal.ZERO, BigDecimal.ZERO,
                            UUID.randomUUID(), BASE_MOMENT, BASE_MOMENT));

            assertEquals(1, written, "the statement inserts on a key it does not hold");
            assertTrue(snapshots.findByAccountId(UNSEEN_ACCOUNT).isPresent(),
                    "the row is readable through the mapped finder");
        }

        @Test
        @DisplayName("a reordered delivery does not move the snapshot backwards")
        void aReorderedDeliveryIsDiscarded() {
            Instant later = BASE_MOMENT.plus(Duration.ofHours(5));
            Instant earlier = BASE_MOMENT.plus(Duration.ofHours(4));
            transactionTemplate.execute(status ->
                    snapshots.applyStateChange(SEEDED_ACCOUNT, new BigDecimal("7000.00"),
                            "2099-12-31", new BigDecimal("10.00"), new BigDecimal("20.00"),
                            UUID.randomUUID(), later, later));

            int written = transactionTemplate.execute(status ->
                    snapshots.applyStateChange(SEEDED_ACCOUNT, new BigDecimal("1.00"),
                            "2000-01-01", BigDecimal.ZERO, BigDecimal.ZERO,
                            UUID.randomUUID(), earlier, earlier));

            assertEquals(AccountCreditSnapshotRepository.NO_ROW_WRITTEN, written,
                    "an event that occurred before the one recorded writes nothing");
            assertEquals(new BigDecimal("7000.00"), jdbcTemplate.queryForObject(
                    "SELECT credit_limit FROM account_credit_snapshot WHERE account_id = ?",
                    BigDecimal.class, SEEDED_ACCOUNT),
                    "the newer limit survives the older delivery");
        }
    }

    /**
     * The reservation an approval writes, and the release the authoritative refresh performs.
     *
     * <p>Neither statement is checked by the persistence layer. The release arms are three-branch
     * {@code CASE} expressions inside an {@code ON CONFLICT DO UPDATE} clause, comparing the stored row
     * against {@code EXCLUDED}, and an arm that computed the wrong sign would leave a reservation
     * standing or clear one that is still outstanding. Either shows up as a decision, not as an error,
     * which is why every arm is read back here against a real database.
     *
     * <p>The behaviour under test is the target-side stand-in for the source's own timing. Paragraph
     * {@code 2700-UPDATE-ACCOUNT} at {@code app/cbl/CBTRN02C.cbl:L545-L560} rewrites the account record
     * in the same sequential loop that validates the next record, so
     * {@code app/cbl/CBTRN02C.cbl:L403-L405} always reads accumulators carrying every earlier approval
     * of the run.
     */
    @Nested
    @DisplayName("reserveCycleExposure, and the release applyStateChange performs")
    class CycleExposureReservationStatements {

        /** A reservation expiry far enough ahead that no test here reaches it. */
        private final Instant farAhead = BASE_MOMENT.plus(Duration.ofHours(1));

        @Test
        @DisplayName("a reservation is written on the locked row and read back whole")
        void aReservationIsWrittenAndReadBack() {
            int written = transactionTemplate.execute(status -> snapshots.reserveCycleExposure(
                    SEEDED_ACCOUNT, new BigDecimal("60.00"), new BigDecimal("0.00"), farAhead));

            assertAll(
                    () -> assertEquals(1, written, "the locked row takes the reservation"),
                    () -> assertEquals(new BigDecimal("60.00"), reservedCredit(SEEDED_ACCOUNT),
                            "the credit-limit rule adds this to current_cycle_credit"),
                    () -> assertEquals(new BigDecimal("0.00"), reservedDebit(SEEDED_ACCOUNT),
                            "an amount of zero or more reserves nothing against the debit arm"),
                    () -> assertNotNull(jdbcTemplate.queryForObject(
                            "SELECT pending_expires_at FROM account_credit_snapshot "
                                    + "WHERE account_id = ?", Instant.class, SEEDED_ACCOUNT),
                            "a reservation without an expiry would hold its exposure for ever"));
        }

        @Test
        @DisplayName("an account carrying no row reserves nothing and says so")
        void anAccountCarryingNoRowReservesNothing() {
            int written = transactionTemplate.execute(status -> snapshots.reserveCycleExposure(
                    UNSEEN_ACCOUNT, new BigDecimal("1.00"), new BigDecimal("0.00"), farAhead));

            assertEquals(AccountCreditSnapshotRepository.NO_ROW_WRITTEN, written,
                    "the statement writes and never inserts, because a decision reserves only "
                            + "against a row it has already read under lock");
        }

        @Test
        @DisplayName("the reported posting releases exactly what it accounts for")
        void theReportedPostingReleasesWhatItAccountsFor() {
            reserve(new BigDecimal("150.00"), new BigDecimal("0.00"));
            Instant occurredAt = BASE_MOMENT.plus(Duration.ofHours(2));

            transactionTemplate.execute(status -> snapshots.applyStateChange(SEEDED_ACCOUNT,
                    new BigDecimal("5000.00"), "2099-12-31", new BigDecimal("100.00"),
                    new BigDecimal("0.00"), UUID.randomUUID(), occurredAt, occurredAt));

            assertEquals(new BigDecimal("50.00"), reservedCredit(SEEDED_ACCOUNT),
                    "an event raising the accumulator by 100.00 accounts for 100.00 of the 150.00 "
                            + "reserved, and the approval still in flight keeps the rest");
        }

        @Test
        @DisplayName("a posting larger than the reservation releases everything and goes no further")
        void aLargerPostingClampsAtZero() {
            reserve(new BigDecimal("40.00"), new BigDecimal("0.00"));
            Instant occurredAt = BASE_MOMENT.plus(Duration.ofHours(2));

            transactionTemplate.execute(status -> snapshots.applyStateChange(SEEDED_ACCOUNT,
                    new BigDecimal("5000.00"), "2099-12-31", new BigDecimal("900.00"),
                    new BigDecimal("0.00"), UUID.randomUUID(), occurredAt, occurredAt));

            assertEquals(new BigDecimal("0.00"), reservedCredit(SEEDED_ACCOUNT),
                    "the reservation clamps at zero rather than turning negative, which its check "
                            + "constraint refuses");
        }

        @Test
        @DisplayName("a cycle close clears the reservation instead of inflating it")
        void aCycleCloseClearsTheReservation() {
            Instant firstReport = BASE_MOMENT.plus(Duration.ofHours(2));
            transactionTemplate.execute(status -> snapshots.applyStateChange(SEEDED_ACCOUNT,
                    new BigDecimal("5000.00"), "2099-12-31", new BigDecimal("500.00"),
                    new BigDecimal("-300.00"), UUID.randomUUID(), firstReport, firstReport));
            reserve(new BigDecimal("70.00"), new BigDecimal("-20.00"));

            Instant closed = BASE_MOMENT.plus(Duration.ofHours(3));
            transactionTemplate.execute(status -> snapshots.applyStateChange(SEEDED_ACCOUNT,
                    new BigDecimal("5000.00"), "2099-12-31", new BigDecimal("0.00"),
                    new BigDecimal("0.00"), UUID.randomUUID(), closed, closed));

            assertAll(
                    () -> assertEquals(new BigDecimal("0.00"), reservedCredit(SEEDED_ACCOUNT),
                            "app/cbl/CBACT04C.cbl:L353-L354 zeroes both accumulators, and exposure "
                                    + "from the closed cycle must not be carried into the new one"),
                    () -> assertEquals(new BigDecimal("0.00"), reservedDebit(SEEDED_ACCOUNT),
                            "the debit arm is cleared on the same condition"));
        }

        @Test
        @DisplayName("a refund reservation is released as the debit accumulator moves further"
                + " negative")
        void aRefundReservationIsReleasedAsTheDebitMoves() {
            reserve(new BigDecimal("0.00"), new BigDecimal("-90.00"));
            Instant occurredAt = BASE_MOMENT.plus(Duration.ofHours(2));

            transactionTemplate.execute(status -> snapshots.applyStateChange(SEEDED_ACCOUNT,
                    new BigDecimal("5000.00"), "2099-12-31", new BigDecimal("0.00"),
                    new BigDecimal("-30.00"), UUID.randomUUID(), occurredAt, occurredAt));

            assertEquals(new BigDecimal("-60.00"), reservedDebit(SEEDED_ACCOUNT),
                    "app/cbl/CBTRN02C.cbl:L551 adds a negative amount, so the accumulator moves "
                            + "further negative as a refund posts and the reservation moves toward "
                            + "zero by the same amount");
        }

        @Test
        @DisplayName("the check constraints hold the sign convention of both reserved figures")
        void theCheckConstraintsHoldTheSignConvention() {
            assertAll(
                    () -> assertThrows(DataIntegrityViolationException.class,
                            () -> jdbcTemplate.update("UPDATE account_credit_snapshot "
                                            + "SET pending_cycle_credit = -1.00 "
                                            + "WHERE account_id = ?", SEEDED_ACCOUNT),
                            "app/cbl/CBTRN02C.cbl:L549 adds an amount of zero or more, so the "
                                    + "reserved credit is never negative"),
                    () -> assertThrows(DataIntegrityViolationException.class,
                            () -> jdbcTemplate.update("UPDATE account_credit_snapshot "
                                            + "SET pending_cycle_debit = 1.00 "
                                            + "WHERE account_id = ?", SEEDED_ACCOUNT),
                            "app/cbl/CBTRN02C.cbl:L551 adds a negative amount, so the reserved "
                                    + "debit is never positive"));
        }

        /**
         * Asserts one account is decided one decision at a time, and that the wait is bounded.
         *
         * <p>This is the whole of the concurrency guarantee. Two calls for one account that both read
         * the accumulators before either reserved would both approve against the same exposure, which
         * is what {@code app/cbl/CBTRN02C.cbl} never does because one batch program reads and rewrites
         * one record in one loop. The lock makes the second call wait, and the bound makes it give up
         * rather than hold its request open for as long as the first call takes.
         *
         * @throws Exception when the holding thread cannot be joined
         */
        @Test
        @DisplayName("one account is decided one decision at a time, under a bounded wait")
        void oneAccountIsDecidedOneDecisionAtATime() throws Exception {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch releaseHolder = new CountDownLatch(1);
            ExecutorService holder = Executors.newSingleThreadExecutor();
            try {
                Future<?> holding = holder.submit(() -> transactionTemplate.execute(status -> {
                    snapshots.findForUpdateByAccountId(SEEDED_ACCOUNT);
                    held.countDown();
                    awaitQuietly(releaseHolder);
                    return null;
                }));

                assertTrue(held.await(LOCK_WAIT_SECONDS, TimeUnit.SECONDS),
                        "the first decision took its row lock");
                assertThrows(PessimisticLockingFailureException.class,
                        () -> transactionTemplate.execute(status -> {
                            snapshots.applyLockWaitBound(SHORT_LOCK_BOUND);
                            return snapshots.findForUpdateByAccountId(SEEDED_ACCOUNT);
                        }),
                        "a second decision for the same account waits, and the transaction-local "
                                + "lock_timeout is what stops it waiting without limit");

                releaseHolder.countDown();
                holding.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            } finally {
                releaseHolder.countDown();
                holder.shutdownNow();
            }

            assertTrue(Boolean.TRUE.equals(transactionTemplate.execute(status ->
                            snapshots.findForUpdateByAccountId(SEEDED_ACCOUNT).isPresent())),
                    "the lock is released with the transaction that took it");
        }

        /**
         * Writes one reservation on the seeded row.
         *
         * @param credit the reserved cycle credit, zero or more
         * @param debit  the reserved cycle debit, zero or less
         */
        private void reserve(BigDecimal credit, BigDecimal debit) {
            transactionTemplate.execute(status -> snapshots.reserveCycleExposure(SEEDED_ACCOUNT,
                    credit, debit, farAhead));
        }

        /**
         * Reads the reserved cycle credit of one account.
         *
         * @param accountId the eleven-digit key
         * @return the stored figure
         */
        private BigDecimal reservedCredit(String accountId) {
            return jdbcTemplate.queryForObject("SELECT pending_cycle_credit FROM "
                    + "account_credit_snapshot WHERE account_id = ?", BigDecimal.class, accountId);
        }

        /**
         * Reads the reserved cycle debit of one account.
         *
         * @param accountId the eleven-digit key
         * @return the stored figure
         */
        private BigDecimal reservedDebit(String accountId) {
            return jdbcTemplate.queryForObject("SELECT pending_cycle_debit FROM "
                    + "account_credit_snapshot WHERE account_id = ?", BigDecimal.class, accountId);
        }
    }

    /**
     * Waits on one latch, treating an interruption as a reason to stop waiting.
     *
     * @param latch the latch to wait on
     */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Nested
    @DisplayName("claimDueRows, the relay claim, and the key forms the column holds")
    class RelayClaim {

        /**
         * Asserts a due row is claimed and an undue row is left alone.
         *
         * <p>The two rows belong to different accounts on purpose. The claim returns the head row of
         * each account, and the head is decided before dueness is considered, so an undue row would
         * withhold a later row of its own account rather than stand beside it. That case is asserted
         * separately below; this one measures dueness alone.
         */
        @Test
        @DisplayName("a due row is claimed and an undue row is left alone")
        void aDueRowIsClaimedAndAnUndueRowIsLeft() {
            UUID due = storeRow(SEEDED_ACCOUNT, BASE_MOMENT);
            storeRow(SECOND_ACCOUNT, BASE_MOMENT.plus(Duration.ofHours(4)));

            List<OutboxEventEntity> claimed = transactionTemplate.execute(status ->
                    outbox.claimDueRows(BASE_MOMENT.plusSeconds(1), Limit.of(100)));

            assertNotNull(claimed, "the statement returns a list");
            assertEquals(1, claimed.size(), "only the row whose next attempt has arrived is due");
            assertEquals(due, claimed.get(0).getEventId(), "the due row is the one returned");
        }

        /**
         * Asserts a row waiting out its backoff withholds the later rows of its own account.
         *
         * <p>This is the ordering guarantee stated as a fact about the query. The earlier row is
         * unpublished and merely waiting, so publishing the later row now would put that account's
         * events on its partition in the wrong order, and the account identifier is the message key.
         * The account waits; no other account does.
         */
        @Test
        @DisplayName("an undue earlier row withholds the later rows of its own account")
        void anUndueEarlierRowWithholdsTheLaterRowsOfItsAccount() {
            storeRow(SEEDED_ACCOUNT, BASE_MOMENT.plus(Duration.ofHours(4)), BASE_MOMENT);
            storeRow(SEEDED_ACCOUNT, BASE_MOMENT, BASE_MOMENT.plusSeconds(1));
            UUID otherAccount = storeRow(SECOND_ACCOUNT, BASE_MOMENT);

            List<OutboxEventEntity> claimed = transactionTemplate.execute(status ->
                    outbox.claimDueRows(BASE_MOMENT.plusSeconds(30), Limit.of(100)));

            assertNotNull(claimed, "the statement returns a list");
            assertEquals(1, claimed.size(),
                    "the waiting head withholds its own account and withholds no other");
            assertEquals(otherAccount, claimed.get(0).getEventId(),
                    "and the row claimed belongs to the account that was not waiting");
        }

        @Test
        @DisplayName("the claim honours its row limit")
        void theClaimHonoursItsLimit() {
            storeRow(SEEDED_ACCOUNT, BASE_MOMENT);
            storeRow(SECOND_ACCOUNT, BASE_MOMENT.plusSeconds(1));
            storeRow(THIRD_ACCOUNT, BASE_MOMENT.plusSeconds(2));

            List<OutboxEventEntity> claimed = transactionTemplate.execute(status ->
                    outbox.claimDueRows(BASE_MOMENT.plusSeconds(30), Limit.of(2)));

            assertEquals(2, claimed.size(), "the limit bounds one sweep");
        }

        /**
         * Asserts the claim returns one row per account, however many of that account are due.
         *
         * <p>This is the property {@code outbox/OutboxRelay} depends on, and it lives in a
         * {@code @Query} the mapping model cannot check. The relay writes each row's outcome in a
         * transaction of its own, so two rows of one account in one batch would let an older row fail
         * while a newer one succeeded, and the retry of the older row would then reach the topic
         * behind it. The account identifier is the Kafka message key, so that reordering would be
         * visible to every consumer of the account.
         *
         * <p>The three rows share one {@code created_at}, so the tie is broken by {@code event_id} and
         * exactly one of them is the head. Which one is not asserted; that there is one is.
         */
        @Test
        @DisplayName("the claim returns one row per account, however many of that account are due")
        void theClaimReturnsOneRowPerAccount() {
            storeRow(SEEDED_ACCOUNT, BASE_MOMENT);
            storeRow(SEEDED_ACCOUNT, BASE_MOMENT.plusSeconds(1));
            storeRow(SEEDED_ACCOUNT, BASE_MOMENT.plusSeconds(2));
            storeRow(SECOND_ACCOUNT, BASE_MOMENT);

            List<OutboxEventEntity> claimed = transactionTemplate.execute(status ->
                    outbox.claimDueRows(BASE_MOMENT.plusSeconds(30), Limit.of(100)));

            assertNotNull(claimed, "the statement returns a list");
            assertEquals(2, claimed.size(),
                    "four due rows across two accounts claim one head row each");
            assertEquals(Set.of(SEEDED_ACCOUNT, SECOND_ACCOUNT),
                    claimed.stream().map(OutboxEventEntity::getAggregateId)
                            .collect(java.util.stream.Collectors.toSet()),
                    "and one of the two heads belongs to each account");
        }

        @Test
        @DisplayName("the widened key column holds an account key and a transaction key")
        void theKeyColumnHoldsBothForms() {
            UUID accountKeyed = storeRow(SEEDED_ACCOUNT, BASE_MOMENT);
            UUID transactionKeyed = storeRow("TRAN000000000123", BASE_MOMENT);

            List<OutboxEventEntity> claimed = transactionTemplate.execute(status ->
                    outbox.claimDueRows(BASE_MOMENT.plusSeconds(1), Limit.of(100)));

            assertEquals(2, claimed.size(), "both rows are stored and both are claimable");
            assertTrue(claimed.stream().anyMatch(row -> row.getEventId().equals(accountKeyed)),
                    "an eleven-digit account key is held");
            assertTrue(claimed.stream().anyMatch(row -> row.getEventId().equals(transactionKeyed)),
                    "a sixteen-character transaction key is held");
        }

        @Test
        @DisplayName("the check constraint refuses a key matching neither form")
        void theCheckConstraintRefusesAnyOtherKey() {
            UUID eventId = UUID.randomUUID();

            assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                    "INSERT INTO outbox_event (event_id, event_type, aggregate_id, payload, "
                            + "created_at, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?)",
                    eventId, "TransactionDeclined", "0000000005", "{}",
                    java.sql.Timestamp.from(BASE_MOMENT), java.sql.Timestamp.from(BASE_MOMENT)),
                    "a ten-digit key matches neither form and the database refuses it");
        }

        /**
         * Stores one unpublished row created at {@link #BASE_MOMENT}.
         *
         * @param aggregateId the message key, either permitted form
         * @param dueAt       when the row becomes claimable
         * @return the identifier of the stored row
         */
        private UUID storeRow(String aggregateId, Instant dueAt) {
            return storeRow(aggregateId, dueAt, BASE_MOMENT);
        }

        /**
         * Stores one unpublished row with an explicit creation moment.
         *
         * <p>The creation moment decides which row of an account is its head, so an assertion about
         * head selection has to set it rather than share one value.
         *
         * @param aggregateId the message key, either permitted form
         * @param dueAt       when the row becomes claimable
         * @param createdAt   when the row was written, which orders it within its account
         * @return the identifier of the stored row
         */
        private UUID storeRow(String aggregateId, Instant dueAt, Instant createdAt) {
            UUID eventId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO outbox_event (event_id, event_type, aggregate_id, payload, "
                            + "created_at, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?)",
                    eventId, "TransactionDeclined", aggregateId, "{}",
                    java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(dueAt));
            return eventId;
        }
    }

    @Nested
    @DisplayName("authorization_decision, the table every outcome reaches")
    class DecisionTable {

        @Test
        @DisplayName("an approval is stored and read back")
        void anApprovalIsStored() {
            AuthorizationDecisionEntity approved = AuthorizationDecisionEntity.approved(
                    "TRAN000000000001", ACTOR, SEEDED_ACCOUNT, "************5740",
                    "72e0699beda9afd3f6677b683462371d1648c559acbb5e14a6022d76293dbf5b", new BigDecimal("12.34"),
                    BASE_MOMENT, UUID.randomUUID(), DECLARED_PROCESSING_TIMESTAMP);

            transactionTemplate.execute(status -> decisions.save(approved));

            AuthorizationDecisionEntity stored =
                    decisions.findByTransactionId("TRAN000000000001").orElseThrow();
            assertTrue(stored.isApproved(), "the outcome is an approval");
            assertNull(stored.getDeclineReasonCode(), "an approval carries no reject code");
            assertEquals(new BigDecimal("12.34"), stored.getAmount(),
                    "the amount keeps the two decimal places of DALYTRAN-AMT PIC S9(09)V99");
        }

        @Test
        @DisplayName("a decline is stored carrying its reject code")
        void aDeclineIsStored() {
            AuthorizationDecisionEntity declined = AuthorizationDecisionEntity.declined(
                    "TRAN000000000002", ACTOR, SEEDED_ACCOUNT, "************5740",
                    "72e0699beda9afd3f6677b683462371d1648c559acbb5e14a6022d76293dbf5b", new BigDecimal("99.99"),
                    "0102", "OVERLIMIT TRANSACTION", BASE_MOMENT, UUID.randomUUID(),
                    DECLARED_PROCESSING_TIMESTAMP);

            transactionTemplate.execute(status -> decisions.save(declined));

            AuthorizationDecisionEntity stored =
                    decisions.findByTransactionId("TRAN000000000002").orElseThrow();
            assertFalse(stored.isApproved(), "the outcome is a decline");
            assertEquals("0102", stored.getDeclineReasonCode(),
                    "the reject code of app/cbl/CBTRN02C.cbl:L403-L413 is stored");
            assertEquals("OVERLIMIT TRANSACTION", stored.getDeclineReasonDescription(),
                    "the source description is stored as written");
        }

        @Test
        @DisplayName("a decline resolving no account is stored without one")
        void aDeclineResolvingNoAccountIsStored() {
            AuthorizationDecisionEntity unresolved = AuthorizationDecisionEntity.declined(
                    "TRAN000000000003", ACTOR, null, "****************",
                    "72e0699beda9afd3f6677b683462371d1648c559acbb5e14a6022d76293dbf5b", new BigDecimal("5.00"),
                    "0100", "INVALID CARD NUMBER FOUND", BASE_MOMENT, UUID.randomUUID(),
                    DECLARED_PROCESSING_TIMESTAMP);

            transactionTemplate.execute(status -> decisions.save(unresolved));

            AuthorizationDecisionEntity stored =
                    decisions.findByTransactionId("TRAN000000000003").orElseThrow();
            assertNull(stored.getAccountId(),
                    "reason 0100 resolved no cross-reference row, so it names no account");
            assertEquals("0100", stored.getDeclineReasonCode(),
                    "the reject code of app/cbl/CBTRN02C.cbl:L385-L387 is stored");
        }

        @Test
        @DisplayName("one transaction reaching the table twice is refused")
        void oneTransactionIsStoredOnce() {
            AuthorizationDecisionEntity first = AuthorizationDecisionEntity.approved(
                    "TRAN000000000004", ACTOR, SEEDED_ACCOUNT, "************5740",
                    "72e0699beda9afd3f6677b683462371d1648c559acbb5e14a6022d76293dbf5b", new BigDecimal("1.00"),
                    BASE_MOMENT, UUID.randomUUID(), DECLARED_PROCESSING_TIMESTAMP);
            transactionTemplate.execute(status -> decisions.save(first));

            AuthorizationDecisionEntity second = AuthorizationDecisionEntity.approved(
                    "TRAN000000000004", ACTOR, SEEDED_ACCOUNT, "************5740",
                    "72e0699beda9afd3f6677b683462371d1648c559acbb5e14a6022d76293dbf5b", new BigDecimal("2.00"),
                    BASE_MOMENT.plusSeconds(1), UUID.randomUUID(), DECLARED_PROCESSING_TIMESTAMP);

            assertThrows(DataIntegrityViolationException.class,
                    () -> transactionTemplate.execute(status -> decisions.save(second)),
                    "the entity is insert-only, so a second row on one key reaches the key and "
                            + "fails, as app/cbl/CBTRN02C.cbl:L566 fails its status test");
        }
    }

    @Nested
    @DisplayName("The three bounded retention deletes outbox/RetentionSweeper runs")
    class RetentionPurge {

        @Test
        @DisplayName("a published row past the horizon goes and an unpublished one stays")
        void onlyPublishedRowsPastTheHorizonGo() {
            UUID expired = storePublishedRow(BASE_MOMENT.minus(Duration.ofDays(30)));
            UUID recent = storePublishedRow(BASE_MOMENT.minus(Duration.ofHours(1)));
            UUID unpublished = storeUnpublishedRow();

            int removed = transactionTemplate.execute(status ->
                    outbox.deletePublishedBefore(BASE_MOMENT.minus(Duration.ofDays(7)),
                            PURGE_BATCH));

            assertEquals(1, removed, "one row is past the horizon");
            assertFalse(outbox.findById(expired).isPresent(), "the expired row is gone");
            assertTrue(outbox.findById(recent).isPresent(), "a row inside the horizon stays");
            assertTrue(outbox.findById(unpublished).isPresent(),
                    "the relay has not published this row, so retention must not touch it");
        }

        @Test
        @DisplayName("the delete honours its row limit, so one statement cannot lock the table")
        void theDeleteHonoursItsLimit() {
            for (int row = 0; row < 5; row++) {
                storePublishedRow(BASE_MOMENT.minus(Duration.ofDays(30 + row)));
            }

            int removed = transactionTemplate.execute(status ->
                    outbox.deletePublishedBefore(BASE_MOMENT.minus(Duration.ofDays(7)), 2));

            assertEquals(2, removed, "the limit bounds one statement");
            assertEquals(3, outbox.count(), "the rest wait for the next statement");
        }

        @Test
        @DisplayName("a marker past the horizon goes and a newer one stays")
        void onlyMarkersPastTheHorizonGo() {
            UUID expired = UUID.randomUUID();
            UUID recent = UUID.randomUUID();
            transactionTemplate.execute(status -> markers.claimEvent(expired,
                    BASE_MOMENT.minus(Duration.ofDays(30)), CONSUMED_TOPIC));
            transactionTemplate.execute(status -> markers.claimEvent(recent,
                    BASE_MOMENT.minus(Duration.ofHours(1)), CONSUMED_TOPIC));

            int removed = transactionTemplate.execute(status ->
                    markers.deleteMarkersProcessedBefore(
                            BASE_MOMENT.minus(Duration.ofDays(7)), 1000));

            assertEquals(1, removed, "one marker is past the horizon");
            assertFalse(markers.existsById(new ProcessedEventId(expired, CONSUMED_TOPIC)),
                    "the expired marker is gone");
            assertTrue(markers.existsById(new ProcessedEventId(recent, CONSUMED_TOPIC)),
                    "a marker inside the horizon stays, so its redelivery is still refused");
        }

        @Test
        @DisplayName("a decision past the horizon goes and a newer one stays")
        void onlyDecisionsPastTheHorizonGo() {
            storeDecision("TRAN000000000101", BASE_MOMENT.minus(Duration.ofDays(500)));
            storeDecision("TRAN000000000102", BASE_MOMENT.minus(Duration.ofDays(10)));

            int removed = transactionTemplate.execute(status ->
                    decisions.deleteDecidedBefore(
                            BASE_MOMENT.minus(Duration.ofDays(400)), 1000));

            assertEquals(1, removed, "one decision is past the horizon");
            assertFalse(decisions.findByTransactionId("TRAN000000000101").isPresent(),
                    "the expired decision is gone");
            assertTrue(decisions.findByTransactionId("TRAN000000000102").isPresent(),
                    "a decision inside the horizon stays");
        }

        @Test
        @DisplayName("a horizon no row precedes removes nothing and says so")
        void aHorizonNoRowPrecedesRemovesNothing() {
            storePublishedRow(BASE_MOMENT.minus(Duration.ofHours(1)));

            int removed = transactionTemplate.execute(status ->
                    outbox.deletePublishedBefore(BASE_MOMENT.minus(Duration.ofDays(7)),
                            PURGE_BATCH));

            assertEquals(0, removed,
                    "the sweeper reads this as the table holding no more expired rows");
        }

        private UUID storePublishedRow(Instant publishedAt) {
            UUID eventId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO outbox_event (event_id, event_type, aggregate_id, payload, "
                            + "created_at, next_attempt_at, published, published_at, relay_state) "
                            + "VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, 'PUBLISHED')",
                    eventId, "TransactionAuthorized", SEEDED_ACCOUNT, "{}",
                    java.sql.Timestamp.from(publishedAt), java.sql.Timestamp.from(publishedAt),
                    java.sql.Timestamp.from(publishedAt));
            return eventId;
        }

        private UUID storeUnpublishedRow() {
            UUID eventId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO outbox_event (event_id, event_type, aggregate_id, payload, "
                            + "created_at, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?)",
                    eventId, "TransactionAuthorized", SEEDED_ACCOUNT, "{}",
                    java.sql.Timestamp.from(BASE_MOMENT.minus(Duration.ofDays(30))),
                    java.sql.Timestamp.from(BASE_MOMENT.minus(Duration.ofDays(30))));
            return eventId;
        }

        private void storeDecision(String transactionId, Instant decidedAt) {
            transactionTemplate.execute(status -> decisions.save(
                    AuthorizationDecisionEntity.approved(transactionId, ACTOR, SEEDED_ACCOUNT,
                            "************5740", "72e0699beda9afd3f6677b683462371d1648c559acbb5e14a6022d76293dbf5b",
                            new BigDecimal("1.00"), decidedAt, UUID.randomUUID(),
                            DECLARED_PROCESSING_TIMESTAMP)));
        }
    }
}
