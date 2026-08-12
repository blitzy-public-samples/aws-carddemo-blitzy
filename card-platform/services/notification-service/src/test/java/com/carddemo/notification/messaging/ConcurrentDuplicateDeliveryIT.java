package com.carddemo.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.notification.NotificationServiceDatabase;
import com.carddemo.notification.TestIdentityPasswords;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionPosted;
import com.carddemo.notification.NotificationApplication;
import java.math.BigDecimal;
import java.sql.Timestamp;
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
 * Delivers one event to a notification listener twice at the same instant, against a real PostgreSQL
 * server, and reads back what the database holds afterwards.
 *
 * <p><b>A concurrent delivery needs its own test.</b> {@link DuplicateDeliveryIT} publishes the
 * same record twice in sequence and proves the second changes no row. That proves the guard reads a
 * marker an earlier delivery already committed. It cannot reach the case the guard exists for: two
 * deliveries whose guards both run before either commits. Nothing in a sequential test distinguishes
 * a guard that is safe under a race from one that is not, because both pass it.
 *
 * <p><b>What holds the invariant here.</b> Not a read followed by a write.
 * {@code ProcessedEventRepository.claimEvent} is one statement carrying
 * {@code ON CONFLICT (event_id, consumed_topic) DO NOTHING}, and it runs inside the transaction that
 * also carries the read-model row and the rendered-alert row. A second delivery of one event on one
 * topic therefore blocks on the first, then reports {@code ALREADY_CLAIMED} and writes nothing. The
 * consequence, asserted below, is that BOTH deliveries return and BOTH commit their offsets: the
 * loser is refused by the claim rather than by an exception, so nothing is redelivered and nothing
 * reaches a dead-letter topic. That is a different outcome from a check-then-act guard, where the
 * loser meets the primary key and raises.
 *
 * <p><b>The topic is half of the marker key.</b>
 * {@code src/main/resources/db/migration/V3__processed_event_topic_key.sql} re-keys
 * {@code processed_event} on the event and the topic it arrived on, so one identifier reaching two
 * topics is claimed once on each. The last test races a posted delivery against a fraud delivery
 * under one identifier and proves both apply, which is the property that keeps this guard from
 * silently dropping a different event that happens to share an identifier. The two deliveries leave
 * different traces, and that asymmetry is asserted rather than smoothed over: a posted delivery
 * writes a read-model row and an attempt row, while a fraud delivery writes neither, because
 * {@code notification_log.masked_card_number} requires a masked card number and
 * {@code FraudFlagged} carries none. Its marker is written all the same, which is what makes a
 * redelivery of it harmless.
 *
 * <p><b>What the source did with a duplicate.</b> Nothing.
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} writes and answers a non-normal file status by displaying it
 * and calling the abend routine at {@code app/cbl/CBTRN02C.cbl:L707-L711}, so a replayed daily feed
 * ended the run. There is no duplicate detection anywhere in the source: the marker, the enclosing
 * transaction and the dead-letter route are all ADDITIVE, and AAP 0.1.1 requires them.
 *
 * <p><b>No broker takes part.</b> The listener containers are stopped before start-up and the broker
 * address points where nothing listens, so the deliveries are made by calling the listener methods,
 * which is what a container would call. That is the only way to hold two deliveries at a barrier and
 * release them together. A delivery that returns without raising cannot reach a dead-letter topic,
 * because the recoverer runs only for a listener invocation that threw.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(classes = NotificationApplication.class, properties = {
        "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
        "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
        "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
        "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
        "spring.kafka.bootstrap-servers=" + ConcurrentDuplicateDeliveryIT.UNREACHABLE_BROKER,
        "spring.kafka.listener.auto-startup=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "carddemo.history.sweep-interval-ms=3600000",
        "carddemo.history.statement-retention-days=200000",
        "carddemo.history.log-retention-days=200000"
})
@DisplayName("Two deliveries of one event, released together against PostgreSQL")
class ConcurrentDuplicateDeliveryIT {

    /** Host and port the broker client is pointed at, where nothing listens. */
    static final String UNREACHABLE_BROKER = "localhost:1";

    /** The topic a posted delivery reports arriving on, which is half of the marker key. */
    private static final String POSTED_TOPIC = "transaction.posted";

    /** The topic a fraud delivery reports arriving on, the other half of the last proof. */
    private static final String ASSESSED_TOPIC = "fraud.assessed";

    /** The seeded account both deliveries name, which {@code V2__seed.sql} holds a row for. */
    private static final String ACCOUNT_ID = "00000000007";

    /** The card token keying the read model, sixty-four lower-case hexadecimal characters. */
    private static final String CARD_TOKEN =
            "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    /** The transaction identifier every delivery carries, at the width L23 declares. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The masked card number, the display value: no full card number reaches an event. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** The amount the read-model row carries, at two fractional digits. */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** The balance the posted event reports, at two fractional digits. */
    private static final BigDecimal NEW_BALANCE = new BigDecimal("697.77");

    /** The posting stamp, twenty-six characters in the form the source builds. */
    private static final String POSTED_AT = "2022-07-19-23.16.01.470000";

    /** The origin stamp, twenty-six characters in the other form. */
    private static final String ORIGIN_TIMESTAMP = "2022-07-19 23:16:01.470000";

    /** The remaining eight payload values, each at the width {@code COSTM01.CPY} declares. */
    private static final String TYPE_CODE = "01";
    private static final String CATEGORY_CODE = "0001";
    private static final String SOURCE = "POS TERM";
    private static final String DESCRIPTION = "Purchase at Abshire-Lowe";
    private static final String MERCHANT_ID = "800000000";
    private static final String MERCHANT_NAME = "Abshire-Lowe";
    private static final String MERCHANT_CITY = "North Enoshaven";
    private static final String MERCHANT_ZIP = "72112";

    /** Score the flagged assessment of the cross-topic proof carries, bounded 0 through 100. */
    private static final int RISK_SCORE = 82;

    /** Rules that assessment names, each one the schema enumerates. */
    private static final List<String> TRIGGERED_RULES = List.of("VELOCITY", "AMOUNT_ANOMALY");

    /**
     * The moment every envelope reports, and the moment the assessment was made. Both are instants:
     * a fraud assessment carries its own, unlike the twenty-six character stamps of the read model.
     */
    private static final Instant OCCURRED_AT = Instant.parse("2022-07-19T23:16:01Z");
    private static final Instant ASSESSED_AT = Instant.parse("2022-07-19T23:16:02Z");

    /** Deliveries released together. */
    private static final int CONCURRENT_DELIVERIES = 2;

    /** Longest the barrier waits for its other party, and each delivery for its answer. */
    private static final Duration RACE_TIMEOUT = Duration.ofSeconds(30L);

    /** Rows one committed delivery leaves behind. */
    private static final long ONE_ROW = 1L;

    /** Rows two deliveries on two topics leave behind. */
    private static final long TWO_ROWS = 2L;

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link NotificationServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    static final PostgreSQLContainer POSTGRES = NotificationServiceDatabase.container();

    /** The posted listener the deliveries call, the one a container would call. */
    private final TransactionPostedConsumer postedConsumer;

    /** The fraud listener the cross-topic proof calls. */
    private final FraudFlaggedConsumer fraudConsumer;

    /** Reads rows back outside every delivery. */
    private final JdbcTemplate jdbc;

    private ExecutorService deliveries;

    @Autowired
    ConcurrentDuplicateDeliveryIT(TransactionPostedConsumer postedConsumer,
            FraudFlaggedConsumer fraudConsumer, JdbcTemplate jdbc) {
        this.postedConsumer = postedConsumer;
        this.fraudConsumer = fraudConsumer;
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
        return NotificationServiceDatabase.urlFor(ConcurrentDuplicateDeliveryIT.class);
    }

    /** Empties the three tables a delivery writes and starts the pool the deliveries run on. */
    @BeforeEach
    void emptyWhatADeliveryWrites() {
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM notification_log");
        jdbc.update("DELETE FROM statement_transaction");
        deliveries = Executors.newFixedThreadPool(CONCURRENT_DELIVERIES);
    }

    /** Stops the pool. */
    @AfterEach
    void stopDeliveries() {
        deliveries.shutdownNow();
    }

    @Test
    @DisplayName("one read-model row, one marker and one rendered alert survive the race")
    void oneOfTwoSimultaneousDeliveriesWritesTheRowAndTheAttempt() throws Exception {
        TransactionPosted event = aPostedEvent();
        List<Outcome> outcomes = racePostedDeliveries(event);

        assertAll("one business effect",
                () -> assertEquals(ONE_ROW, readModelRows(),
                        "one read-model row under the key the two deliveries share"),
                () -> assertEquals(ONE_ROW, markerRows(event.eventId()),
                        "one marker for the event on the topic it arrived on"),
                () -> assertEquals(ONE_ROW, attemptRows(),
                        "one rendered alert, so one alert was rendered and not two"),
                () -> assertEquals(AMOUNT, storedAmount(),
                        "the row carries the amount the event carried"),
                () -> assertEquals(CONCURRENT_DELIVERIES,
                        outcomes.stream().filter(Outcome::applied).count(),
                        "both deliveries returned: " + outcomes));
    }

    @Test
    @DisplayName("both deliveries commit their offsets, because the claim refuses without raising")
    void bothDeliveriesCommitTheirOffsets() throws Exception {
        TransactionPosted event = aPostedEvent();
        List<Outcome> outcomes = racePostedDeliveries(event);

        for (Outcome outcome : outcomes) {
            assertNull(outcome.failure(),
                    "the claim is one statement, so the delivery it refuses returns rather than"
                            + " raising: " + outcome);
            assertTrue(outcome.acknowledgment().acknowledged(),
                    "a delivery that returned committed its offset: " + outcome);
        }
        assertEquals(ONE_ROW, markerRows(event.eventId()), "one marker survived the race");
        assertEquals(ONE_ROW, attemptRows(), "one alert was rendered");
    }

    @Test
    @DisplayName("a later delivery of the same event finds the marker and changes nothing")
    void aLaterDeliveryOfTheSameEventChangesNothing() throws Exception {
        TransactionPosted event = aPostedEvent();
        racePostedDeliveries(event);

        RecordingAcknowledgment third = new RecordingAcknowledgment();
        postedConsumer.onTransactionPosted(event, third, POSTED_TOPIC, event.aggregateId());

        assertAll("the third delivery is suppressed by the marker the race left",
                () -> assertTrue(third.acknowledged(),
                        "a delivery the marker suppresses commits its offset, because there is"
                                + " nothing to retry"),
                () -> assertEquals(ONE_ROW, readModelRows(), "the row count stays fixed"),
                () -> assertEquals(ONE_ROW, markerRows(event.eventId()),
                        "the marker count stays fixed"),
                () -> assertEquals(ONE_ROW, attemptRows(), "no second alert follows"));
    }

    @Test
    @DisplayName("the marker key, and not the guard, is what refuses a second marker")
    void theMarkerKeyRefusesASecondMarkerForOneEventOnOneTopic() {
        TransactionPosted event = aPostedEvent();
        postedConsumer.onTransactionPosted(event, new RecordingAcknowledgment(), POSTED_TOPIC,
                event.aggregateId());

        DataIntegrityViolationException refused = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO processed_event (event_id, processed_at,"
                                + " consumed_topic) VALUES (?, ?, ?)",
                        event.eventId(), Timestamp.from(Instant.now()), POSTED_TOPIC),
                "pk_processed_event covers (event_id, consumed_topic) after"
                        + " V3__processed_event_topic_key.sql, so a second marker for one event on"
                        + " one topic cannot be stored. That refusal is what the ON CONFLICT clause"
                        + " of the claim reads, and it is what holds when two claims run together.");

        assertTrue(String.valueOf(refused.getMessage()).contains("pk_processed_event"),
                "the refusal names the key that produced it: " + refused.getMessage());
        assertEquals(ONE_ROW, markerRows(event.eventId()), "the second marker was not stored");
    }

    @Test
    @DisplayName("one identifier on two topics is claimed once on each, and both apply")
    void oneIdentifierOnTwoTopicsIsClaimedOnceOnEachTopic() throws Exception {
        UUID shared = UUID.randomUUID();
        TransactionPosted posted = aPostedEvent(shared);
        FraudFlagged flagged = aFlaggedEvent(shared);
        CyclicBarrier released = new CyclicBarrier(CONCURRENT_DELIVERIES);
        RecordingAcknowledgment postedOffset = new RecordingAcknowledgment();
        RecordingAcknowledgment fraudOffset = new RecordingAcknowledgment();

        List<Outcome> outcomes = release(List.of(
                deliverPosted(posted, released, postedOffset),
                deliverFlagged(flagged, released, fraudOffset)));

        assertAll("the topic is half of the marker key",
                () -> assertEquals(TWO_ROWS, markerRows(shared),
                        "one marker per topic, so neither delivery suppressed the other"),
                () -> assertEquals(ONE_ROW, markerRows(shared, POSTED_TOPIC),
                        "one marker names the posted topic"),
                () -> assertEquals(ONE_ROW, markerRows(shared, ASSESSED_TOPIC),
                        "one marker names the assessed topic"),
                () -> assertEquals(ONE_ROW, readModelRows(),
                        "the posted delivery wrote its read-model row"),
                () -> assertEquals(ONE_ROW, attemptRows(),
                        "one attempt row, written by the posted delivery."
                                + " NotificationService.renderFraudAlert writes none, because"
                                + " notification_log.masked_card_number requires a masked card"
                                + " number and FraudFlagged carries none"),
                () -> assertEquals(ONE_ROW, attemptRowsOfThePostedCard(),
                        "the one attempt row names the card and transaction the posted delivery"
                                + " carried, so it is that delivery's attempt"),
                () -> assertTrue(postedOffset.acknowledged(), "the posted offset was committed"),
                () -> assertTrue(fraudOffset.acknowledged(), "the fraud offset was committed"),
                () -> assertEquals(CONCURRENT_DELIVERIES,
                        outcomes.stream().filter(Outcome::applied).count(),
                        "both deliveries returned: " + outcomes));
    }

    /**
     * Releases two posted deliveries of one event together and collects what each did.
     *
     * @param event the event both deliveries carry
     * @return one outcome per delivery, in submission order
     * @throws Exception when a delivery cannot be waited on
     */
    private List<Outcome> racePostedDeliveries(TransactionPosted event) throws Exception {
        CyclicBarrier released = new CyclicBarrier(CONCURRENT_DELIVERIES);
        return release(List.of(
                deliverPosted(event, released, new RecordingAcknowledgment()),
                deliverPosted(event, released, new RecordingAcknowledgment())));
    }

    /**
     * Submits every delivery and waits for all of them.
     *
     * <p>Each task waits at the barrier first, so no guard can run before the other task has
     * started. The barrier releases when the last task reaches it, which is as close to simultaneous
     * as a test can arrange.
     *
     * @param tasks the deliveries to release together
     * @return one outcome per delivery, in submission order
     * @throws Exception when a delivery cannot be waited on
     */
    private List<Outcome> release(List<Callable<Outcome>> tasks) throws Exception {
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Callable<Outcome> task : tasks) {
            futures.add(deliveries.submit(task));
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
     * Builds one posted delivery that waits at the barrier before it calls the listener.
     *
     * @param event          the event to deliver
     * @param released       the barrier to wait at
     * @param acknowledgment the offset of this delivery
     * @return the task
     */
    private Callable<Outcome> deliverPosted(TransactionPosted event, CyclicBarrier released,
            RecordingAcknowledgment acknowledgment) {
        return () -> {
            released.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            try {
                postedConsumer.onTransactionPosted(event, acknowledgment, POSTED_TOPIC,
                        event.aggregateId());
                return new Outcome(acknowledgment, null);
            } catch (RuntimeException refused) {
                return new Outcome(acknowledgment, refused);
            }
        };
    }

    /**
     * Builds one fraud delivery that waits at the same barrier.
     *
     * @param event          the assessment to deliver
     * @param released       the barrier to wait at
     * @param acknowledgment the offset of this delivery
     * @return the task
     */
    private Callable<Outcome> deliverFlagged(FraudFlagged event, CyclicBarrier released,
            RecordingAcknowledgment acknowledgment) {
        return () -> {
            released.await(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            try {
                fraudConsumer.onFraudAssessed(event, event.aggregateId(), acknowledgment,
                        ASSESSED_TOPIC);
                return new Outcome(acknowledgment, null);
            } catch (RuntimeException refused) {
                return new Outcome(acknowledgment, refused);
            }
        };
    }

    /** @return one posted event under a fresh identifier */
    private static TransactionPosted aPostedEvent() {
        return aPostedEvent(UUID.randomUUID());
    }

    /**
     * Builds one posted event under a named identifier, carrying every value a row needs.
     *
     * @param eventId the identifier the deliveries share, which makes them duplicates
     * @return the event
     */
    private static TransactionPosted aPostedEvent(UUID eventId) {
        EventEnvelope envelope = new EventEnvelope(eventId, TransactionPosted.EVENT_TYPE,
                TransactionPosted.TRANSACTION_DETAIL_SCHEMA_VERSION, OCCURRED_AT, ACCOUNT_ID);

        return TransactionPosted.of(envelope, TRANSACTION_ID, NEW_BALANCE, POSTED_AT, AMOUNT,
                MASKED_CARD_NUMBER, CARD_TOKEN, TYPE_CODE, CATEGORY_CODE, SOURCE, DESCRIPTION,
                MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, ORIGIN_TIMESTAMP);
    }

    /**
     * Builds one flagged assessment under a named identifier.
     *
     * @param eventId the identifier it shares with a posted event
     * @return the assessment
     */
    private static FraudFlagged aFlaggedEvent(UUID eventId) {
        return new FraudFlagged(eventId, FraudFlagged.EVENT_TYPE, EventEnvelope.SCHEMA_VERSION,
                OCCURRED_AT, ACCOUNT_ID, TRANSACTION_ID, RISK_SCORE, TRIGGERED_RULES,
                ASSESSED_AT, ACCOUNT_ID);
    }

    /** @return how many read-model rows the key the deliveries share holds */
    private long readModelRows() {
        return count("SELECT count(*) FROM statement_transaction WHERE card_token = ?"
                + " AND transaction_id = ?", CARD_TOKEN, TRANSACTION_ID);
    }

    /** @return how many rendered alerts the deliveries left */
    private long attemptRows() {
        return count("SELECT count(*) FROM notification_log");
    }

    /**
     * Counts the rendered alerts naming the card and transaction a posted delivery carries.
     *
     * @return that count
     */
    private long attemptRowsOfThePostedCard() {
        return count("SELECT count(*) FROM notification_log WHERE card_token = ?"
                + " AND transaction_id = ?", CARD_TOKEN, TRANSACTION_ID);
    }

    /**
     * Counts markers carrying one event identifier, on any topic.
     *
     * @param eventId the identifier
     * @return the count
     */
    private long markerRows(UUID eventId) {
        return count("SELECT count(*) FROM processed_event WHERE event_id = ?", eventId);
    }

    /**
     * Counts markers carrying one event identifier on one topic.
     *
     * @param eventId the identifier
     * @param topic   the topic the delivery reported arriving on
     * @return the count
     */
    private long markerRows(UUID eventId, String topic) {
        return count("SELECT count(*) FROM processed_event WHERE event_id = ?"
                + " AND consumed_topic = ?", eventId, topic);
    }

    /** @return the amount the one read-model row carries */
    private BigDecimal storedAmount() {
        return jdbc.queryForObject("SELECT amount FROM statement_transaction WHERE card_token = ?"
                + " AND transaction_id = ?", BigDecimal.class, CARD_TOKEN, TRANSACTION_ID);
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

        /** @return whether this delivery returned, having applied or been refused by the claim */
        boolean applied() {
            return failure == null;
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
