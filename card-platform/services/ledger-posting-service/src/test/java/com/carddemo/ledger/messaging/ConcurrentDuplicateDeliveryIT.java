package com.carddemo.ledger.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.ledger.LedgerServiceDatabase;
import com.carddemo.ledger.TestIdentityPasswords;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.ledger.LedgerApplication;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Delivers one event to the ledger listener twice at the same instant, against a real PostgreSQL
 * server, and reads back what the database holds afterwards.
 *
 * <p><b>A concurrent delivery needs its own test.</b> {@link TransactionAuthorizedConsumerIT}
 * publishes the same record twice in sequence and proves the marker suppresses the second. That
 * proves the guard reads a marker another delivery already committed. It cannot prove the case the
 * guard is actually exposed to: two deliveries whose guard reads both happen before either commit.
 * The listener checks {@code processed_event} and then writes it — a check followed by an act — so
 * under a genuine race both checks can answer "absent" and both deliveries can attempt the posting.
 * What stops a double posting then is not the check. It is the primary key of
 * {@code processed_event}, whose two columns {@code V5__processed_event_topic_key.sql} declares, and
 * the transaction that writes the posting and the marker together.
 *
 * <p><b>What the source did with a duplicate.</b> Nothing.
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} writes the transaction record and answers a non-normal file
 * status by displaying it and calling the abend routine, so a replayed daily feed drove the posting
 * program to {@code CEE3ABD} at {@code app/cbl/CBTRN02C.cbl:L707-L711}. There is no duplicate
 * detection anywhere in the source, and neither the marker nor the enclosing transaction has an
 * ancestor there. Both are ADDITIVE, and AAP 0.1.1 requires them.
 *
 * <p><b>What is asserted.</b> One business effect, whichever delivery wins: one
 * {@code transaction} row, one marker for the event on the topic it arrived on, one balance
 * movement, and one {@code outbox_event} row, so one downstream event follows a doubled delivery.
 * The delivery that loses the race either finds the marker and posts nothing, or is refused by the
 * key and raises — and a delivery that raises must leave the offset uncommitted, because Kafka
 * redelivers it and the redelivery is what finds the marker. A third, later delivery is then made to
 * confirm the marker is what suppresses it.
 *
 * <p><b>No broker takes part.</b> The listener container is stopped before start-up and the broker
 * address points where nothing listens, so the two deliveries are made by calling the listener
 * method, which is what the container would call. That is the only way to hold two deliveries at a
 * barrier and release them together.
 */
@SpringBootTest(classes = LedgerApplication.class, properties = {
        "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
        "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
        "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
        "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
        "spring.kafka.bootstrap-servers=" + ConcurrentDuplicateDeliveryIT.UNREACHABLE_BROKER,
        "spring.kafka.listener.auto-startup=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "carddemo.outbox.relay.fixed-delay-ms=3600000",
        "carddemo.retention.sweep-interval-ms=3600000"
})
@DisplayName("Two deliveries of one event, released together against PostgreSQL")
class ConcurrentDuplicateDeliveryIT {

    /** Host and port the broker client is pointed at, where nothing listens. */
    static final String UNREACHABLE_BROKER = "localhost:1";

    /** The topic the delivery reports arriving on, which is half of the marker key. */
    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    /** The seeded account both deliveries name. Row 7 of {@code app/data/ASCII/acctdata.txt}. */
    private static final String ACCOUNT_ID = "00000000007";

    /** Seeded balance of that account, which {@code V2__seed.sql} loads. */
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("193.00");

    /** The transaction identifier both deliveries carry. */
    private static final String TRANSACTION_ID = "0000000000683581";

    /** The amount both deliveries carry, which may be applied exactly once. */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** Transaction type code of the event, which selects the cycle-credit accumulator. */
    private static final String TRANSACTION_TYPE_CODE = "01";

    /** Merchant category code of the event, half of the category-balance key. */
    private static final String MERCHANT_CATEGORY_CODE = "0001";

    /** Capture source of the event, at the width {@code DALYTRAN-SOURCE PIC X(10)} declares. */
    private static final String SOURCE = "POS TERM  ";

    /** Description of the event. */
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";

    /** Merchant identifier of the event. */
    private static final String MERCHANT_ID = "800000000";

    /** Merchant name of the event. */
    private static final String MERCHANT_NAME = "Abshire-Lowe";

    /** Merchant town of the event. */
    private static final String MERCHANT_CITY = "North Enoshaven";

    /** Merchant postal code of the event, at the width the layout declares. */
    private static final String MERCHANT_ZIP = "72112     ";

    /** Masked card number of the event: no full card number reaches an event. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** A card token of the declared length, synthetic and matching no live token. */
    private static final String CARD_TOKEN =
            "a".repeat(TransactionAuthorized.CARD_TOKEN_LENGTH);

    /** Capture stamp of the event, at the two significant fractional digits the source holds. */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /** Deliveries released together. */
    private static final int CONCURRENT_DELIVERIES = 2;

    /** Longest the barrier waits for its other party, and each delivery for its answer. */
    private static final Duration RACE_TIMEOUT = Duration.ofSeconds(30L);

    /** Rows one committed delivery leaves behind. */
    private static final long ONE_ROW = 1L;

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link LedgerServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    static final PostgreSQLContainer POSTGRES = LedgerServiceDatabase.container();

    /** The listener both deliveries call, the one the container would call. */
    private final TransactionAuthorizedConsumer consumer;

    /** Reads rows back outside every delivery. */
    private final JdbcTemplate jdbc;

    /** Runs the two deliveries. */
    private ExecutorService deliveries;

    @Autowired
    ConcurrentDuplicateDeliveryIT(TransactionAuthorizedConsumer consumer, JdbcTemplate jdbc) {
        this.consumer = consumer;
        this.jdbc = jdbc;
    }

    /** Points the datasource at the container, on the schema the migrations own. */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                ConcurrentDuplicateDeliveryIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** @return the container URL with the service schema selected */
    private static String jdbcUrlOnServiceSchema() {
        return LedgerServiceDatabase.urlFor(ConcurrentDuplicateDeliveryIT.class);
    }

    /** Returns the schema to the seeded state and starts the pool the deliveries run on. */
    @BeforeEach
    void restoreSeededState() {
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM transaction");
        jdbc.update("DELETE FROM rejected_transaction");
        jdbc.update("DELETE FROM transaction_category_balance WHERE account_id = ?", ACCOUNT_ID);
        jdbc.update("UPDATE account_balance_projection SET current_balance = ?,"
                        + " cycle_credit = 0.00, cycle_debit = 0.00 WHERE account_id = ?",
                OPENING_BALANCE, ACCOUNT_ID);
        deliveries = Executors.newFixedThreadPool(CONCURRENT_DELIVERIES);
    }

    /** Stops the pool. */
    @AfterEach
    void stopDeliveries() {
        deliveries.shutdownNow();
    }

    @Test
    @DisplayName("one posting, one marker, one balance movement and one outbox row survive the race")
    void oneOfTwoSimultaneousDeliveriesPosts() throws Exception {
        UUID eventId = UUID.randomUUID();
        TransactionAuthorized event = anEvent(eventId);
        CyclicBarrier released = new CyclicBarrier(CONCURRENT_DELIVERIES);
        RecordingAcknowledgment first = new RecordingAcknowledgment();
        RecordingAcknowledgment second = new RecordingAcknowledgment();

        List<Outcome> outcomes = raceTwoDeliveries(event, released, first, second);

        long applied = outcomes.stream().filter(Outcome::applied).count();
        long refused = outcomes.stream().filter(Outcome::refused).count();
        assertEquals(CONCURRENT_DELIVERIES, applied + refused,
                "each delivery either returned or raised: " + outcomes);
        assertTrue(applied >= 1L,
                "one of the two deliveries has to post, or the event was lost: " + outcomes);

        assertAll("one business effect",
                () -> assertEquals(ONE_ROW, transactionRows(),
                        "one transaction row, whichever delivery won"),
                () -> assertEquals(ONE_ROW, markerRows(eventId),
                        "one marker for the event on the topic it arrived on"),
                () -> assertEquals(OPENING_BALANCE.add(AMOUNT), storedBalance(),
                        "the amount was applied exactly once"),
                () -> assertEquals(AMOUNT, storedCycleCredit(),
                        "the cycle credit accumulator moved once, as"
                                + " app/cbl/CBTRN02C.cbl:L547-L551 moves it for a positive amount"),
                () -> assertEquals(ONE_ROW, categoryBalanceRows(),
                        "one category-balance row for the key the event names"),
                () -> assertEquals(AMOUNT, storedCategoryBalance(),
                        "the category balance carries the amount once, from"
                                + " app/cbl/CBTRN02C.cbl:L508"),
                () -> assertEquals(ONE_ROW, outboxRows(),
                        "one outbox row, so a doubled delivery produces one downstream event"),
                () -> assertEquals(0L, rejectedRows(),
                        "the ledger posts what the authorization approved and rejects nothing"));
    }

    @Test
    @DisplayName("the delivery that loses the race commits no offset unless it posted or skipped")
    void theLosingDeliveryLeavesItsOffsetUncommittedWhenItIsRefused() throws Exception {
        UUID eventId = UUID.randomUUID();
        TransactionAuthorized event = anEvent(eventId);
        CyclicBarrier released = new CyclicBarrier(CONCURRENT_DELIVERIES);
        RecordingAcknowledgment first = new RecordingAcknowledgment();
        RecordingAcknowledgment second = new RecordingAcknowledgment();

        List<Outcome> outcomes = raceTwoDeliveries(event, released, first, second);

        for (Outcome outcome : outcomes) {
            if (outcome.applied()) {
                assertTrue(outcome.acknowledgment().acknowledged(),
                        "a delivery that returned committed its offset: " + outcome);
            } else {
                assertFalse(outcome.acknowledgment().acknowledged(),
                        "a delivery that raised left its offset uncommitted, which is what makes"
                                + " Kafka redeliver it: " + outcome);
                assertNotNull(outcome.failure(), "a refused delivery carries its failure");
            }
        }
        assertEquals(ONE_ROW, markerRows(eventId), "one marker survived the race");
    }

    @Test
    @DisplayName("a later delivery of the same event finds the marker and changes nothing")
    void aLaterDeliveryOfTheSameEventChangesNothing() throws Exception {
        UUID eventId = UUID.randomUUID();
        TransactionAuthorized event = anEvent(eventId);
        CyclicBarrier released = new CyclicBarrier(CONCURRENT_DELIVERIES);
        raceTwoDeliveries(event, released, new RecordingAcknowledgment(),
                new RecordingAcknowledgment());

        BigDecimal balanceAfterRace = storedBalance();
        RecordingAcknowledgment third = new RecordingAcknowledgment();
        consumer.onTransactionAuthorized(event, ACCOUNT_ID, AUTHORIZED_TOPIC, third);

        assertAll("the third delivery is suppressed by the marker the race left",
                () -> assertTrue(third.acknowledged(),
                        "a delivery the marker suppresses commits its offset, because there is"
                                + " nothing to retry"),
                () -> assertEquals(balanceAfterRace, storedBalance(), "the balance stays fixed"),
                () -> assertEquals(ONE_ROW, transactionRows(), "the transaction count stays fixed"),
                () -> assertEquals(ONE_ROW, markerRows(eventId), "the marker count stays fixed"),
                () -> assertEquals(ONE_ROW, outboxRows(), "no second downstream event follows"));
    }

    @Test
    @DisplayName("the marker key, and not the guard, is what refuses the second write")
    void theMarkerKeyRefusesASecondWriteForTheSameEventAndTopic() throws Exception {
        UUID eventId = UUID.randomUUID();
        TransactionAuthorized event = anEvent(eventId);
        consumer.onTransactionAuthorized(event, ACCOUNT_ID, AUTHORIZED_TOPIC,
                new RecordingAcknowledgment());

        DataIntegrityViolationException refused = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO processed_event (event_id, processed_at,"
                                + " consumed_topic) VALUES (?, ?, ?)",
                        eventId, java.sql.Timestamp.from(Instant.now()), AUTHORIZED_TOPIC),
                "pk_processed_event covers (event_id, consumed_topic) after"
                        + " V5__processed_event_topic_key.sql, so a second marker for one event on"
                        + " one topic cannot be stored. That refusal is what protects the invariant"
                        + " when two guards both read an absent marker.");

        assertTrue(String.valueOf(refused.getMessage()).contains("pk_processed_event"),
                "the refusal names the key that produced it: " + refused.getMessage());
        assertEquals(ONE_ROW, markerRows(eventId), "the second marker was not stored");
        assertEquals(ONE_ROW, jdbc.queryForObject("SELECT count(*) FROM processed_event"
                        + " WHERE event_id = ? AND consumed_topic = ?", Long.class,
                eventId, AUTHORIZED_TOPIC),
                "one marker names the event and the topic it arrived on");
    }

    /**
     * Releases two deliveries of one event together and collects what each did.
     *
     * <p>Both tasks wait at the barrier, so neither guard read can precede the other's start. The
     * barrier is released when the second task reaches it, which is as close to simultaneous as a
     * test can arrange.
     *
     * @param event    the event both deliveries carry
     * @param released the barrier both wait at
     * @param first    the offset of the first delivery
     * @param second   the offset of the second delivery
     * @return one outcome per delivery, in submission order
     * @throws Exception when a delivery cannot be waited on
     */
    private List<Outcome> raceTwoDeliveries(TransactionAuthorized event, CyclicBarrier released,
            RecordingAcknowledgment first, RecordingAcknowledgment second) throws Exception {

        List<Future<Outcome>> futures = new ArrayList<>();
        for (RecordingAcknowledgment acknowledgment : List.of(first, second)) {
            futures.add(deliveries.submit(deliver(event, released, acknowledgment)));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            try {
                outcomes.add(future.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            } catch (ExecutionException unexpected) {
                throw new IllegalStateException("a delivery task itself failed",
                        unexpected.getCause());
            }
        }
        return List.copyOf(outcomes);
    }

    /**
     * Builds one delivery that waits at the barrier before it calls the listener.
     *
     * @param event          the event to deliver
     * @param released       the barrier to wait at
     * @param acknowledgment the offset of this delivery
     * @return the task
     */
    private Callable<Outcome> deliver(TransactionAuthorized event, CyclicBarrier released,
            RecordingAcknowledgment acknowledgment) {
        return () -> {
            released.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            try {
                consumer.onTransactionAuthorized(event, ACCOUNT_ID, AUTHORIZED_TOPIC,
                        acknowledgment);
                return new Outcome(acknowledgment, null);
            } catch (RuntimeException refused) {
                return new Outcome(acknowledgment, refused);
            }
        };
    }

    /**
     * Builds the event both deliveries carry.
     *
     * @param eventId the identifier both deliveries hold, which makes them duplicates
     * @return the event
     */
    private static TransactionAuthorized anEvent(UUID eventId) {
        return new TransactionAuthorized(eventId, TransactionAuthorized.EVENT_TYPE,
                TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION, Instant.parse(
                        "2022-06-10T19:27:53Z"), ACCOUNT_ID, TRANSACTION_ID, TRANSACTION_TYPE_CODE,
                MERCHANT_CATEGORY_CODE, SOURCE, DESCRIPTION, AMOUNT, MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, MASKED_CARD_NUMBER, CARD_TOKEN, AUTHORIZED_AT,
                ACCOUNT_ID, TransactionAuthorized.CURRENCY);
    }

    /** @return how many transaction rows carry the transaction identifier of the event */
    private long transactionRows() {
        return count("SELECT count(*) FROM transaction WHERE transaction_id = ?", TRANSACTION_ID);
    }

    /** @return how many markers carry one event identifier */
    private long markerRows(UUID eventId) {
        return count("SELECT count(*) FROM processed_event WHERE event_id = ?", eventId);
    }

    /** @return how many category-balance rows the event's key holds */
    private long categoryBalanceRows() {
        return count("SELECT count(*) FROM transaction_category_balance WHERE account_id = ?"
                        + " AND type_code = ? AND category_code = ?",
                ACCOUNT_ID, TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE);
    }

    /** @return how many outbox rows the deliveries left */
    private long outboxRows() {
        return count("SELECT count(*) FROM outbox_event");
    }

    /** @return how many rejected rows the deliveries left */
    private long rejectedRows() {
        return count("SELECT count(*) FROM rejected_transaction");
    }

    /** @return the stored balance of the account both deliveries name */
    private BigDecimal storedBalance() {
        return amount("SELECT current_balance FROM account_balance_projection"
                + " WHERE account_id = ?");
    }

    /** @return the stored cycle credit accumulator of that account */
    private BigDecimal storedCycleCredit() {
        return amount("SELECT cycle_credit FROM account_balance_projection WHERE account_id = ?");
    }

    /** @return the stored category balance of the key the event names */
    private BigDecimal storedCategoryBalance() {
        return jdbc.queryForObject("SELECT category_balance FROM transaction_category_balance"
                        + " WHERE account_id = ? AND type_code = ? AND category_code = ?",
                BigDecimal.class, ACCOUNT_ID, TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE);
    }

    /**
     * Reads one amount of the account both deliveries name.
     *
     * @param sql the statement, taking the account identifier
     * @return the amount, at the scale the column holds
     */
    private BigDecimal amount(String sql) {
        return jdbc.queryForObject(sql, BigDecimal.class, ACCOUNT_ID);
    }

    /**
     * Counts rows.
     *
     * @param sql       the counting statement
     * @param arguments its arguments
     * @return the count
     */
    private long count(String sql, Object... arguments) {
        Long counted = jdbc.queryForObject(sql, Long.class, arguments);
        return counted == null ? -1L : counted;
    }

    /**
     * What one delivery did.
     *
     * @param acknowledgment the offset of that delivery
     * @param failure        the failure it raised, or {@code null} when it returned
     */
    private record Outcome(RecordingAcknowledgment acknowledgment, RuntimeException failure) {

        /** @return whether this delivery returned, having posted or skipped */
        boolean applied() {
            return failure == null;
        }

        /** @return whether this delivery raised */
        boolean refused() {
            return failure != null;
        }

        @Override
        public String toString() {
            return "Outcome[acknowledged=" + acknowledgment.acknowledged() + ", failure="
                    + (failure == null ? "none" : failure.getClass().getSimpleName()) + "]";
        }
    }

    /** An offset that records whether it was committed. */
    private static final class RecordingAcknowledgment implements Acknowledgment {

        /** Set when the listener commits this offset. */
        private volatile boolean acknowledged;

        @Override
        public void acknowledge() {
            acknowledged = true;
        }

        /** @return whether the listener committed this offset */
        boolean acknowledged() {
            return acknowledged;
        }
    }
}
