package com.carddemo.authorization.outbox;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.AuthorizationApplication;
import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.messaging.DeadLetterMetadata;
import com.carddemo.authorization.messaging.EventPublisherPort;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.serde.EventContracts;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Asserts the authorization outbox against a live PostgreSQL container and the migrated schema.
 *
 * <p>The subjects are {@link OutboxWriter}, which stores one event inside the transaction that
 * holds the decision, and {@link OutboxRelay}, which sends stored rows in a transaction of its own.
 * These two classes play the halves of the one asynchronous handoff the source holds. Paragraph
 * {@code WIRTE-JOBSUB-TDQ} at {@code app/cbl/CORPT00C.cbl:L515-L523} writes one record to a
 * Customer Information Control System (CICS) transient data queue. A separate job reads that
 * record later.
 *
 * <p>Atomicity is an additive deviation. Paragraph {@code 2000-POST-TRANSACTION} opens at
 * {@code app/cbl/CBTRN02C.cbl:L424}, stamps its processing timestamp at
 * {@code app/cbl/CBTRN02C.cbl:L437-L438} and then runs three writes under no condition at
 * {@code app/cbl/CBTRN02C.cbl:L440-L442}, with no rollback behind them. Each of the eight
 * {@code DEFINE FILE} blocks of {@code app/csd/CARDDEMO.CSD} carries {@code RECOVERY(NONE)} and
 * {@code JOURNAL(NO)}, so the transaction monitor offered none either.
 *
 * <p>Duplicate detection is an additive deviation too. The write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} answers a repeated key by displaying a message, formatting
 * the file status at {@code app/cbl/CBTRN02C.cbl:L714-L727} and reaching the four-statement abend
 * routine at {@code app/cbl/CBTRN02C.cbl:L707-L711}. The branch at
 * {@code app/cbl/CBTRN02C.cbl:L556} sets a reject reason nothing ever reads, and its condition
 * becomes an observable fault here.
 *
 * <p>A clean machine needs only Docker. The container starts inside this class, so no host database
 * takes part, and the broker seam is a recording implementation of
 * {@link EventPublisherPort} that reaches no network.
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
                "spring.kafka.listener.auto-startup=false",
                "carddemo.outbox.relay.fixed-delay-ms=3600000"
        })
@ContextConfiguration(classes = OutboxRelayTest.RecordingPublisherConfiguration.class)
@DisplayName("The authorization outbox: one row per call, sent after that row has committed")
class OutboxRelayTest {

    /** Image tag of the database container, matching the shipped compose stack. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** Database name, login name and password of the container, one value for all three. */
    private static final String CONTAINER_CREDENTIAL = "carddemo";

    /** Schema Flyway migrates, and the one the connection search path names. */
    private static final String MIGRATED_SCHEMA = "authorization_service";

    /** Host and port the broker client is pointed at, where nothing listens. */
    private static final String UNREACHABLE_BROKER = "localhost:1";

    /**
     * The request identity each decision row records, at the width {@code SEC-USR-ID PIC X(08)}
     * declares.
     */
    private static final String ACTOR = "OPERATR1";

    /** Account of the approving pair, eleven digits opening with a zero. */
    private static final String APPROVING_ACCOUNT_ID = "00000000077";

    /** Account of the declining pair, eleven digits opening with a zero. */
    private static final String DECLINING_ACCOUNT_ID = "00000000078";

    /** Card the approving pair resolves through, sixteen digits. */
    private static final String APPROVING_CARD_NUMBER = syntheticCardNumber(1L);

    /** Card the declining pair resolves through, sixteen digits. */
    private static final String DECLINING_CARD_NUMBER = syntheticCardNumber(2L);

    /** Customer both synthetic pairs name, nine digits. */
    private static final String SYNTHETIC_CUSTOMER_ID = "000000999";

    /** Credit limit that admits {@link #AMOUNT}. */
    private static final String GENEROUS_CREDIT_LIMIT = "500000.00";

    /** Credit limit that {@link #AMOUNT} exceeds, so reject code {@code 0102} stands. */
    private static final String TIGHT_CREDIT_LIMIT = "1.00";

    /** Expiry far enough ahead that reject code {@code 0103} stands down. */
    private static final String FAR_FUTURE_EXPIRY = "2099-12-31";

    /** Cycle accumulator value both synthetic accounts carry. */
    private static final String NO_CYCLE_MOVEMENT = "0.00";

    /** The amount each call carries, at the two fractional digits its Picture clause fixes. */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /**
     * Wire form of {@code DALYTRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA06Y.cpy:L10}: nine
     * integer digits at most, an optional sign, and two fractional digits.
     */
    private static final Pattern TRANSACTION_AMOUNT =
            Pattern.compile(TransactionAuthorized.AMOUNT_PATTERN);

    /**
     * Wire form of {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}: ten
     * integer digits at most. The ledger posting service publishes that field and this one does
     * not, so the two patterns stay separate and are never swapped.
     */
    private static final Pattern BALANCE_AMOUNT = Pattern.compile("^-?\\d{1,10}\\.\\d{2}$");

    /** Wire form of a masked card number: twelve mask characters and four digits. */
    private static final Pattern MASKED_CARD_NUMBER =
            Pattern.compile(TransactionAuthorized.MASKED_CARD_NUMBER_PATTERN);

    /** The twenty top-level property names {@code TransactionAuthorized} declares. */
    private static final Set<String> APPROVAL_PROPERTIES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
            "transactionId", "transactionTypeCode", "merchantCategoryCode", "source",
            "description", "amount", "merchantId", "merchantName", "merchantCity", "merchantZip",
            "maskedCardNumber", "cardToken", "authorizedAt", "accountId", "currency");

    /** The eleven top-level names an account-keyed {@code TransactionDeclined} declares. */
    private static final Set<String> DECLINE_PROPERTIES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
            "transactionId", "accountId", "declineReasonCode", "declineReasonDescription",
            "amount", "maskedCardNumber");

    /** A property name no document declares, used to prove the publish gate refuses one. */
    private static final String UNDECLARED_PROPERTY = "unexpectedProperty";

    /** Payload the recording publisher is told to refuse. */
    private static final String REFUSED_PAYLOAD = "{\"seq\":1}";

    /** Payload the recording publisher accepts, stored behind {@link #REFUSED_PAYLOAD}. */
    private static final String ACCEPTED_PAYLOAD = "{\"seq\":2}";

    /** Longest an assertion waits for the sweep thread. */
    private static final Duration LONGEST_SWEEP_WAIT = Duration.ofSeconds(30L);

    /** How often an awaiting assertion re-reads the row. */
    private static final Duration SWEEP_POLL = Duration.ofMillis(20L);

    /** Seconds the scheduler stop waits for a task it already started. */
    private static final int SCHEDULER_STOP_WAIT_SECONDS = 30;

    /** Reads a stored payload back. Jackson 3, as the writer writes it. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final PostgreSQLContainer POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(CONTAINER_CREDENTIAL)
                .withUsername(CONTAINER_CREDENTIAL)
                .withPassword(CONTAINER_CREDENTIAL);
        POSTGRES.start();
    }

    /**
     * Points the datasource at the container and the broker client at nothing.
     *
     * @param registry the registry the framework supplies
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OutboxRelayTest::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> UNREACHABLE_BROKER);
    }

    /**
     * Returns the container connection string with {@code currentSchema} appended.
     *
     * <p>{@link PostgreSQLContainer#getJdbcUrl()} already carries one query parameter, so a second
     * question mark would fold this setting into that parameter's value and lose it.
     *
     * @return the connection string each unqualified statement here resolves its tables through
     */
    private static String migratedSchemaUrl() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "currentSchema=" + MIGRATED_SCHEMA;
    }

    /**
     * Builds a sixteen-digit card number this repository does not carry.
     *
     * <p>The four leading digits are {@code 9999}, and none of the fifty records of
     * {@code app/data/ASCII/carddata.txt} opens with them. The value is derived, so no card number
     * this repository holds reaches this source file as a literal.
     *
     * @param serial the trailing serial, at most twelve digits
     * @return sixteen digits, opening with {@code 9999}
     */
    private static String syntheticCardNumber(long serial) {
        return "9999" + String.format("%012d", serial);
    }

    /** Supplies the recording seam and a scheduler this class can stop. */
    @TestConfiguration
    static class RecordingPublisherConfiguration {

        /**
         * Replaces the broker adapter for the whole context.
         *
         * @return the recording seam each sweep in this class sends through
         */
        @Bean
        @Primary
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }

        /**
         * Supplies the scheduler each scheduled method of the service runs on.
         *
         * <p>The class stops it before the first assertion, so each sweep measured here is one this
         * class invoked. Waiting for a running task on shutdown makes that stop a barrier.
         *
         * @return the scheduler, holding one thread
         */
        @Bean
        @Primary
        ThreadPoolTaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("outbox-schedule-");
            scheduler.setWaitForTasksToCompleteOnShutdown(true);
            scheduler.setAwaitTerminationSeconds(SCHEDULER_STOP_WAIT_SECONDS);
            return scheduler;
        }
    }

    /**
     * Records what the relay sent, and refuses a nominated payload.
     *
     * <p>The sweep runs on a thread of its own in one assertion below, so both collections are
     * concurrent.
     */
    static final class RecordingPublisher implements EventPublisherPort {

        /**
         * One send the relay performed.
         *
         * @param topic      the topic the event type selected
         * @param key        the message key, which is the aggregate identifier of the row
         * @param payload    the stored payload, forwarded unread
         * @param threadName the thread the send ran on
         */
        record Sent(String topic, String key, String payload, String threadName) {
        }

        /** What this seam accepted, in the order it accepted it. */
        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        /** Payloads this seam answers with a fault. */
        private final Set<String> refused = ConcurrentHashMap.newKeySet();

        @Override
        public void publish(String topic, String aggregateId, String payload) {
            if (refused.contains(payload)) {
                throw new IllegalStateException("the broker refused this record");
            }
            sent.add(new Sent(topic, aggregateId, payload, Thread.currentThread().getName()));
        }

        /**
         * Tells this seam to answer one payload with a fault.
         *
         * @param payload the payload to refuse
         */
        void refuse(String payload) {
            refused.add(payload);
        }

        /** Forgets what was sent and what was refused. */
        void forget() {
            sent.clear();
            refused.clear();
        }

        /**
         * Returns what this seam accepted.
         *
         * @return each send, in order
         */
        List<Sent> all() {
            return List.copyOf(sent);
        }

        /**
         * Returns what this seam accepted under one message key.
         *
         * @param key the message key to filter on
         * @return each send under that key, in order
         */
        List<Sent> under(String key) {
            return sent.stream().filter(one -> one.key().equals(key)).toList();
        }

        /**
         * Returns the topics this seam accepted under one message key.
         *
         * @param key the message key to filter on
         * @return each topic, in send order
         */
        List<String> topicsUnder(String key) {
            return under(key).stream().map(Sent::topic).toList();
        }

        /**
         * Returns the payloads this seam accepted under one message key.
         *
         * @param key the message key to filter on
         * @return each payload, in send order
         */
        List<String> payloadsUnder(String key) {
            return under(key).stream().map(Sent::payload).toList();
        }
    }

    /** Opens the one transaction a decision commits in, and writes the event beside it. */
    @Autowired
    private AuthorizationService authorizations;

    /** Stores one event, joining the transaction it finds. */
    @Autowired
    private OutboxWriter outboxWriter;

    /** Sends stored rows, in a transaction of its own. */
    @Autowired
    private OutboxRelay relay;

    /** Reads stored rows back through the mapped model. */
    @Autowired
    private OutboxEventRepository outboxEvents;

    /** Opens a transaction the writer under test joins. */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** Reads committed state, outside the mapped model and outside any transaction. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The recording seam this context supplies in place of the broker adapter. */
    @Autowired
    private RecordingPublisher publisher;

    /** The bound settings, read for the configured topic names. */
    @Autowired
    private AuthorizationProperties properties;

    /** The producer factory {@code config/KafkaProducerConfig} builds. */
    @Autowired
    private ProducerFactory<String, String> authorizationEventProducerFactory;

    /** The scheduler the service runs its scheduled methods on. */
    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    /**
     * Stops the schedule, forgets what an earlier assertion sent, then stores the replica pairs.
     *
     * <p>Stopping the schedule first makes each sweep this class measures one it invoked. One sweep
     * skips a row another sweep holds locked, so an unstopped schedule leaves a measured count
     * unsettled.
     */
    @BeforeEach
    void stopTheScheduleAndStoreReplicaPairs() {
        taskScheduler.shutdown();
        publisher.forget();
        storeReplicaPair(APPROVING_CARD_NUMBER, APPROVING_ACCOUNT_ID, GENEROUS_CREDIT_LIMIT);
        storeReplicaPair(DECLINING_CARD_NUMBER, DECLINING_ACCOUNT_ID, TIGHT_CREDIT_LIMIT);
    }

    /** Empties what one assertion wrote, so no row reaches the next. The seeded rows stay. */
    @AfterEach
    void clearWrittenRows() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM authorization_decision");
        jdbcTemplate.update("DELETE FROM unresolved_card_attempt");
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM card_xref WHERE card_number IN (?, ?)",
                APPROVING_CARD_NUMBER, DECLINING_CARD_NUMBER);
        jdbcTemplate.update("DELETE FROM account_credit_snapshot WHERE account_id IN (?, ?)",
                APPROVING_ACCOUNT_ID, DECLINING_ACCOUNT_ID);
    }

    /**
     * Stores one cross-reference row and the credit projection its account keys.
     *
     * <p>Both rows are synthetic and belong to {@code src/test} alone. Neither
     * {@code db/migration/V2__seed.sql} nor any file under {@code app/} is touched to reach a
     * decline: reject code {@code 0102} follows from the limit this method stores.
     *
     * @param cardNumber  the sixteen-digit lookup key
     * @param accountId   the eleven-digit account the card resolves to
     * @param creditLimit the limit the credit-limit rule compares against
     */
    private void storeReplicaPair(String cardNumber, String accountId, String creditLimit) {
        jdbcTemplate.update("INSERT INTO card_xref (card_number, customer_id, account_id, "
                + "observed_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                cardNumber, SYNTHETIC_CUSTOMER_ID, accountId);
        jdbcTemplate.update("INSERT INTO account_credit_snapshot (account_id, credit_limit, "
                + "account_expiration_date, current_cycle_credit, current_cycle_debit, "
                + "observed_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                accountId, new BigDecimal(creditLimit), FAR_FUTURE_EXPIRY,
                new BigDecimal(NO_CYCLE_MOVEMENT), new BigDecimal(NO_CYCLE_MOVEMENT));
    }

    /**
     * Builds one authorization request carrying a card number and an amount.
     *
     * <p>The transaction identifier is absent: the request contract refuses one, and
     * {@code domain/TransactionIdentifierSource} allocates it from the database sequence. The
     * capture moment is the current second, so it sits inside the window
     * {@code domain/OriginTimestampWindow} holds.
     *
     * @param cardNumber the sixteen-digit card the cross-reference resolves
     * @param amount     the amount the credit-limit rule compares
     * @return the request, valid against each bean constraint the record declares
     */
    private static AuthorizationRequest request(String cardNumber, BigDecimal amount) {
        return new AuthorizationRequest(null, "01", "0001", "POS TERM",
                "Purchase at Abshire-Lowe", amount.toPlainString(), "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", cardNumber, originTimestamp(), processingTimestamp(),
                null);
    }

    /**
     * Returns the current second in the capture-moment form.
     *
     * <p>Position eleven holds a space, as it does in all three hundred records of
     * {@code app/data/ASCII/dailytran.txt}.
     *
     * @return twenty-six characters: a dated first ten, a space, a clock time, and six zeros
     */
    private static String originTimestamp() {
        String moment = currentSecond();
        return moment.substring(0, 10) + " " + moment.substring(11, 19) + ".000000";
    }

    /**
     * Returns the current second in the processing form.
     *
     * <p>Position eleven holds a third dash, matching the redefinition at
     * {@code app/cbl/CBTRN02C.cbl:L160-L174}, and the last four characters are the zeros
     * {@code app/cbl/CBTRN02C.cbl:L701} fills.
     *
     * @return twenty-six characters in the processing form
     */
    private static String processingTimestamp() {
        String moment = currentSecond();
        return moment.substring(0, 10) + "-" + moment.substring(11, 13) + "."
                + moment.substring(14, 16) + "." + moment.substring(17, 19) + ".000000";
    }

    /**
     * Returns the current moment truncated to the second, in its own textual form.
     *
     * @return the truncated moment, rendered as text
     */
    private static String currentSecond() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * Builds one approval event on the approving account.
     *
     * @param amount the amount the event carries, at any scale
     * @return the event, valid against each check its record declares
     */
    private static TransactionAuthorized approval(BigDecimal amount) {
        return TransactionAuthorized.of(APPROVING_ACCOUNT_ID, "0000001000000001", "01", "0001",
                "POS TERM", "Purchase at Abshire-Lowe", amount, "800000000", "Abshire-Lowe",
                "North Enoshaven", "72112", maskedApprovingCard(), "0".repeat(64),
                originTimestamp());
    }

    /**
     * Returns the approving card masked to its last four digits.
     *
     * @return twelve mask characters and four digits
     */
    private static String maskedApprovingCard() {
        return "*".repeat(12) + APPROVING_CARD_NUMBER.substring(12);
    }

    /**
     * Stores one unpublished row under the approving account, due at a pinned moment.
     *
     * @param dueAt   the moment the row becomes claimable, which is also its creation moment
     * @param payload the payload the relay forwards unread
     * @return the stored row
     */
    private OutboxEventEntity storeRow(Instant dueAt, String payload) {
        return outboxEvents.save(new OutboxEventEntity(UUID.randomUUID(),
                TransactionAuthorized.EVENT_TYPE, APPROVING_ACCOUNT_ID, payload, dueAt));
    }

    /**
     * Returns the single stored row under one message key.
     *
     * @param aggregateId the message key to filter on
     * @return the one row that key holds
     */
    private OutboxEventEntity onlyRowUnder(String aggregateId) {
        List<OutboxEventEntity> rows = outboxEvents.findAll().stream()
                .filter(row -> row.getAggregateId().equals(aggregateId))
                .toList();
        assertEquals(1, rows.size(), "one authorization call stores one row under its key");
        return rows.getFirst();
    }

    /**
     * Counts the stored rows under one message key, reading committed state.
     *
     * @param aggregateId the message key to count
     * @return the number of rows that key holds
     */
    private int rowsUnder(String aggregateId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class,
                aggregateId);
    }

    /**
     * Counts the stored rows carrying one event identifier, reading committed state.
     *
     * @param eventId the primary key to count
     * @return one when the row committed, zero when it did not
     */
    private int rowsFor(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_id = ?", Integer.class, eventId);
    }

    /**
     * Reads one column of one stored row, outside the mapped model and outside any transaction.
     *
     * @param <T>     the column type
     * @param column  the column name
     * @param type    the type the column reads back as
     * @param eventId the row to read
     * @return the committed value
     */
    private <T> T columnOf(String column, Class<T> type, UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM outbox_event WHERE event_id = ?", type, eventId);
    }

    /**
     * Reads the stored payload of one row as a parsed document.
     *
     * @param eventId the row to read
     * @return the payload the column holds
     */
    private JsonNode payloadOf(UUID eventId) {
        return MAPPER.readTree(columnOf("payload", String.class, eventId));
    }

    /**
     * Returns the top-level property names of one document, ordered so a failure reads plainly.
     *
     * @param document the parsed payload
     * @return each top-level property name
     */
    private static Set<String> propertyNamesOf(JsonNode document) {
        return new TreeSet<>(document.propertyNames());
    }

    /**
     * Returns the configured topic names.
     *
     * @return the bound topics of this service
     */
    private AuthorizationProperties.Kafka.Topics topics() {
        return properties.kafka().topics();
    }

    /**
     * Returns a moment safely in the past, so a row stored at it is claimable at once.
     *
     * @param minutes how far back to reach
     * @return the moment
     */
    private static Instant minutesAgo(long minutes) {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofMinutes(minutes));
    }

    /** One call, one event row, and one transaction carrying both it and the decision. */
    @Nested
    @DisplayName("one authorization call and the single row it commits")
    class OneCallOneRow {

        /** Asserts an approved call commits one event row beside one decision row. */
        @Test
        @DisplayName("an approved call stores one event row and one decision row")
        void anApprovedCallStoresOneEventRowAndOneDecisionRow() {
            AuthorizationService.Outcome outcome =
                    authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);

            assertTrue(outcome.approved(), "each rule accepted the call");
            assertEquals(0, new BigDecimal(APPROVING_ACCOUNT_ID).compareTo(outcome.accountId()),
                    "the resolved account is compared by value, and it carries no scale");
            assertEquals(1, rowsUnder(APPROVING_ACCOUNT_ID), "one call commits one event row");
            assertEquals(1, (int) jdbcTemplate.queryForObject("SELECT count(*) FROM "
                            + "authorization_decision WHERE transaction_id = ?", Integer.class,
                    outcome.transactionId()),
                    "the decision row committed with the event row, so both are readable");
        }

        /**
         * Asserts a declined call commits one row carrying its four-character reject code.
         *
         * <p>A decline is ordinary traffic: {@code app/cbl/CBTRN02C.cbl:L229-L230} moves 4 into the
         * return code once the reject count rises above zero, and the batch job then ends normally.
         * The code and its text hold the widths of {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
         * {@code app/cbl/CBTRN02C.cbl:L181} and
         * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at {@code app/cbl/CBTRN02C.cbl:L182}.
         */
        @Test
        @DisplayName("a declined call stores one row carrying its zero-padded reject code")
        void aDeclinedCallStoresOneRowCarryingItsRejectCode() {
            AuthorizationService.Outcome outcome =
                    authorizations.authorize(request(DECLINING_CARD_NUMBER, AMOUNT), ACTOR);

            assertFalse(outcome.approved(), "the amount exceeds the limit this account carries");
            assertEquals(DeclineReason.OVER_CREDIT_LIMIT, outcome.declineReason().orElseThrow(),
                    "reject code 0102 is the one app/cbl/CBTRN02C.cbl:L410 assigns");
            assertEquals(1, rowsUnder(DECLINING_ACCOUNT_ID), "a decline commits one event row too");

            JsonNode payload = payloadOf(onlyRowUnder(DECLINING_ACCOUNT_ID).getEventId());
            assertTrue(payload.path("declineReasonCode").isString(),
                    "the reject code travels as text and never as a bare integer");
            assertEquals(DeclineReason.OVER_CREDIT_LIMIT.code(),
                    payload.path("declineReasonCode").asString(),
                    "four characters, zero padded, from PIC 9(04)");
            assertTrue(payload.path("declineReasonDescription").asString().length()
                            <= TransactionDeclined.DESCRIPTION_MAX_LENGTH,
                    "the text fits the seventy-six characters PIC X(76) declares");
        }

        /** Asserts the event row commits when the transaction its caller opened commits. */
        @Test
        @DisplayName("the event row commits with the transaction its caller opened")
        void theEventRowCommitsWithTheTransactionItsCallerOpened() {
            UUID eventId = transactionTemplate.execute(status ->
                    outboxWriter.writeAuthorized(approval(AMOUNT)).getEventId());

            assertEquals(1, rowsFor(eventId), "the committed transaction carried the row with it");
        }

        /** Asserts a caller that rolls back leaves no event row behind. */
        @Test
        @DisplayName("a caller that rolls back leaves no event row behind")
        void aCallerThatRollsBackLeavesNoEventRow() {
            UUID eventId = transactionTemplate.execute(status -> {
                UUID written = outboxWriter.writeAuthorized(approval(AMOUNT)).getEventId();
                status.setRollbackOnly();
                return written;
            });

            assertEquals(0, rowsFor(eventId),
                    "the row shares the fate of the decision it describes");
            assertEquals(0, rowsUnder(APPROVING_ACCOUNT_ID), "the key holds nothing either");
        }

        /**
         * Asserts each write operation joins the transaction it finds.
         *
         * @throws NoSuchMethodException never, since both methods are declared
         */
        @Test
        @DisplayName("each write operation joins the transaction it finds")
        void eachWriteOperationJoinsTheTransactionItFinds() throws NoSuchMethodException {
            Transactional approval = OutboxWriter.class
                    .getMethod("writeAuthorized", TransactionAuthorized.class)
                    .getAnnotation(Transactional.class);
            Transactional decline = OutboxWriter.class
                    .getMethod("writeDeclined", TransactionDeclined.class)
                    .getAnnotation(Transactional.class);

            for (Transactional annotation : List.of(approval, decline)) {
                assertEquals(Propagation.REQUIRED, annotation.propagation(),
                        "the write joins the caller's transaction and opens none of its own");
                assertFalse(annotation.readOnly(), "the operation writes a row");
            }
        }

        /**
         * Asserts a producer call writes no duplicate marker.
         *
         * <p>The marker guards an event a consumer receives, and this call sits on the produce
         * side. The source detects no duplicate at all: the write at
         * {@code app/cbl/CBTRN02C.cbl:L562-L579} answers a repeated key with an abend.
         */
        @Test
        @DisplayName("a producer call writes no duplicate marker")
        void aProducerCallWritesNoDuplicateMarker() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);

            assertEquals(0, (int) jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM processed_event", Integer.class),
                    "the marker table stays empty across a call that publishes");
        }
    }

    /** What the sweep sends, when it sends it, and what it leaves alone. */
    @Nested
    @DisplayName("the sweep, which sends what the call committed")
    class TheSweep {

        /** Asserts request handling sends nothing and leaves the committed row unsent. */
        @Test
        @DisplayName("request handling sends nothing and leaves the row unsent")
        void requestHandlingSendsNothing() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);

            assertEquals(List.of(), publisher.all(), "no send happened while the call was handled");
            assertEquals(Boolean.FALSE,
                    columnOf("published", Boolean.class,
                            onlyRowUnder(APPROVING_ACCOUNT_ID).getEventId()),
                    "the committed row is work still owed");
        }

        /**
         * Asserts a sweep on another thread sends the committed row and marks it sent.
         *
         * <p>The mark is read back outside the mapped model, so what the assertion sees is the
         * value the sweep's own transaction committed.
         */
        @Test
        @DisplayName("a sweep on another thread sends the row and marks it sent")
        void aSweepOnAnotherThreadSendsTheRowAndMarksItSent() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);
            UUID eventId = onlyRowUnder(APPROVING_ACCOUNT_ID).getEventId();
            String callingThread = Thread.currentThread().getName();

            Thread.ofPlatform().name("outbox-sweep").start(relay::publishPendingEvents);

            await().atMost(LONGEST_SWEEP_WAIT).pollInterval(SWEEP_POLL).untilAsserted(() ->
                    assertEquals(Boolean.TRUE, columnOf("published", Boolean.class, eventId),
                            "the sweep marks the row sent once the broker accepted it"));
            assertEquals(1, publisher.under(APPROVING_ACCOUNT_ID).size(),
                    "one row reaches the broker once");
            assertNotEquals(callingThread,
                    publisher.under(APPROVING_ACCOUNT_ID).getFirst().threadName(),
                    "the send ran off the thread that authorized");
            assertNotNull(columnOf("published_at", Instant.class, eventId),
                    "the sent row records when it was sent");
        }

        /**
         * Asserts the message key is the account the cross-reference resolved.
         *
         * <p>Keying each event of one account on that value holds those events on one partition and
         * in send order. The balance a downstream consumer maintains depends on that order.
         */
        @Test
        @DisplayName("the message key is the account the cross-reference resolved")
        void theMessageKeyIsTheResolvedAccount() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);

            relay.publishPendingEvents();

            RecordingPublisher.Sent sent = publisher.under(APPROVING_ACCOUNT_ID).getFirst();
            assertEquals(APPROVING_ACCOUNT_ID, sent.key(), "the key names the account");
            assertEquals(sent.key(), MAPPER.readTree(sent.payload()).path("aggregateId").asString(),
                    "the key and the aggregate identifier inside the payload hold one value");
        }

        /** Asserts each event type reaches its own topic and none reaches the dead-letter topic. */
        @Test
        @DisplayName("each event type reaches its own topic and none reaches the dead-letter topic")
        void eachEventTypeReachesItsOwnTopic() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);
            authorizations.authorize(request(DECLINING_CARD_NUMBER, AMOUNT), ACTOR);

            relay.publishPendingEvents();

            assertEquals(List.of(topics().transactionAuthorized()),
                    publisher.topicsUnder(APPROVING_ACCOUNT_ID), "an approval reaches its topic");
            assertEquals(List.of(topics().transactionDeclined()),
                    publisher.topicsUnder(DECLINING_ACCOUNT_ID), "a decline reaches its topic");
            assertFalse(publisher.all().stream()
                            .anyMatch(sent -> sent.topic().equals(topics().deadLetter())),
                    "a decline is a committed outcome, so it never reaches the dead-letter topic");
        }

        /** Asserts rows of one account reach the broker oldest first. */
        @Test
        @DisplayName("rows of one account reach the broker oldest first")
        void rowsOfOneAccountReachTheBrokerOldestFirst() {
            storeRow(minutesAgo(2L), REFUSED_PAYLOAD);
            storeRow(minutesAgo(1L), ACCEPTED_PAYLOAD);

            relay.publishPendingEvents();

            assertEquals(List.of(REFUSED_PAYLOAD, ACCEPTED_PAYLOAD),
                    publisher.payloadsUnder(APPROVING_ACCOUNT_ID),
                    "the older row reaches the broker first, so one account keeps its order");
        }

        /**
         * Asserts a refused send leaves its row unsent and ends the sweep there.
         *
         * <p>The row records the class of the fault. The source formats a file status at
         * {@code app/cbl/CBTRN02C.cbl:L714-L727} and then reaches the abend routine at
         * {@code app/cbl/CBTRN02C.cbl:L707-L711}, which performs no cleanup. Here the fault is
         * bounded to one row and one sweep.
         */
        @Test
        @DisplayName("a refused send leaves its row unsent and ends the sweep there")
        void aRefusedSendLeavesItsRowUnsentAndEndsTheSweep() {
            OutboxEventEntity first = storeRow(minutesAgo(2L), REFUSED_PAYLOAD);
            OutboxEventEntity behind = storeRow(minutesAgo(1L), ACCEPTED_PAYLOAD);
            publisher.refuse(REFUSED_PAYLOAD);

            relay.publishPendingEvents();

            assertEquals(List.of(), publisher.all(), "nothing reached a topic");
            assertEquals(Boolean.FALSE, columnOf("published", Boolean.class, first.getEventId()),
                    "the refused row stays unsent");
            assertEquals(1, (int) columnOf("attempt_count", Integer.class, first.getEventId()),
                    "the refused attempt is counted once");
            assertEquals(IllegalStateException.class.getSimpleName(),
                    columnOf("last_error", String.class, first.getEventId()),
                    "the row names the class of the fault, and carries no payload value");
            assertEquals(OutboxEventEntity.RelayState.PENDING.name(),
                    columnOf("relay_state", String.class, first.getEventId()),
                    "the refused row releases its claim for a later sweep");
            assertEquals(Boolean.FALSE, columnOf("published", Boolean.class, behind.getEventId()),
                    "the sweep ends at the refusal, so the row behind it waits its turn");
            assertEquals(0, (int) columnOf("attempt_count", Integer.class, behind.getEventId()),
                    "the row behind it was not attempted");
        }

        /** Asserts a row whose event type has no configured topic reaches no topic at all. */
        @Test
        @DisplayName("a row whose event type has no configured topic reaches no topic")
        void aRowWithNoConfiguredTopicReachesNoTopic() {
            OutboxEventEntity foreign = outboxEvents.save(new OutboxEventEntity(UUID.randomUUID(),
                    EventContracts.TRANSACTION_POSTED, APPROVING_ACCOUNT_ID, ACCEPTED_PAYLOAD,
                    minutesAgo(1L)));

            relay.publishPendingEvents();

            assertEquals(List.of(), publisher.all(), "the row reaches no topic");
            assertEquals(Boolean.FALSE, columnOf("published", Boolean.class, foreign.getEventId()),
                    "the row stays unsent");
            assertEquals(1, (int) columnOf("attempt_count", Integer.class, foreign.getEventId()),
                    "the unpublishable row counts one attempt, so it cannot loop without bound");
        }

        /**
         * Asserts a claim a stopped relay left behind is returned and then sent.
         *
         * <p>The claim is older than the timeout the settings hold, so the first sweep returns it
         * and counts the attempt. A sweep then sends it, and the interval between sweeps is the
         * whole of the retry.
         */
        @Test
        @DisplayName("a claim a stopped relay left behind is returned and then sent")
        void aStrandedClaimIsReturnedAndThenSent() {
            OutboxEventEntity stranded = storeRow(minutesAgo(10L), ACCEPTED_PAYLOAD);
            jdbcTemplate.update("UPDATE outbox_event SET relay_state = 'CLAIMED', claimed_by = ?, "
                    + "claimed_at = ? WHERE event_id = ?", "stopped-instance",
                    java.sql.Timestamp.from(minutesAgo(5L)), stranded.getEventId());

            relay.publishPendingEvents();

            assertEquals(1, (int) columnOf("attempt_count", Integer.class, stranded.getEventId()),
                    "returning the expired claim counts one attempt");
            assertNotEquals(OutboxEventEntity.RelayState.CLAIMED.name(),
                    columnOf("relay_state", String.class, stranded.getEventId()),
                    "the expired claim no longer holds the row");

            relay.publishPendingEvents();

            assertEquals(Boolean.TRUE, columnOf("published", Boolean.class, stranded.getEventId()),
                    "the returned row reaches its topic");
            assertEquals(1, (int) columnOf("attempt_count", Integer.class, stranded.getEventId()),
                    "sending the row counts no further attempt");
            assertEquals(1, publisher.under(APPROVING_ACCOUNT_ID).size(), "one send, not two");
        }

        /**
         * Asserts the sweep reads its interval from configuration and opens its own boundary.
         *
         * @throws NoSuchMethodException never, since the method is declared
         */
        @Test
        @DisplayName("the sweep interval is bound from configuration, not compiled in")
        void theSweepIntervalIsBoundFromConfiguration() throws NoSuchMethodException {
            Scheduled scheduled = OutboxRelay.class.getDeclaredMethod("publishPendingEvents")
                    .getAnnotation(Scheduled.class);

            assertNotNull(scheduled, "the sweep runs on a schedule");
            assertEquals("${carddemo.outbox.relay.fixed-delay-ms}", scheduled.fixedDelayString(),
                    "a deployment moves the interval without a recompilation");
            assertFalse(OutboxRelay.class.getDeclaredMethod("publishPendingEvents")
                            .isAnnotationPresent(Transactional.class),
                    "the sweep opens its boundary itself, so no counter joins that transaction");
            assertTrue(AuthorizationApplication.class.isAnnotationPresent(EnableScheduling.class),
                    "the application enables the schedule this sweep needs");
        }
    }

    /** The document the payload column holds, read back from the row a call committed. */
    @Nested
    @DisplayName("the document the payload column holds")
    class ThePersistedPayload {

        /**
         * Asserts an approval payload holds exactly the properties its contract declares.
         *
         * <p>A closed inventory settles several absences at once. The three-digit verification code
         * of {@code app/cpy/CVACT02Y.cpy:L7} reaches no payload. Neither cycle accumulator of
         * {@code app/cpy/CVACT01Y.cpy:L13-L14} reaches one. Nor does the
         * three-hundred-and-fifty-byte reject block of {@code app/cbl/CBTRN02C.cbl:L446-L465},
         * which the ledger posting service owns.
         */
        @Test
        @DisplayName("an approval payload holds exactly the properties its contract declares")
        void anApprovalPayloadHoldsExactlyItsDeclaredProperties() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);

            JsonNode payload = payloadOf(onlyRowUnder(APPROVING_ACCOUNT_ID).getEventId());

            assertEquals(new TreeSet<>(APPROVAL_PROPERTIES), propertyNamesOf(payload),
                    "the column holds the declared set and nothing beside it");
            assertEquals(APPROVAL_PROPERTIES.size(), payload.size(),
                    "fifteen payload properties beside the five the carrier adds");
        }

        /** Asserts a decline payload holds exactly the eleven properties its contract declares. */
        @Test
        @DisplayName("a decline payload holds exactly the eleven properties its contract declares")
        void aDeclinePayloadHoldsExactlyItsDeclaredProperties() {
            authorizations.authorize(request(DECLINING_CARD_NUMBER, AMOUNT), ACTOR);

            JsonNode payload = payloadOf(onlyRowUnder(DECLINING_ACCOUNT_ID).getEventId());

            assertEquals(new TreeSet<>(DECLINE_PROPERTIES), propertyNamesOf(payload),
                    "the column holds the declared set and nothing beside it");
            assertEquals(DECLINE_PROPERTIES.size(), payload.size(),
                    "six payload properties beside the five the carrier adds");
        }

        /**
         * Asserts the stored document is flat, with each carrier property at the top level.
         *
         * <p>A payload holding one nested object under a wrapper key would carry six properties and
         * fail its document on the {@code required} array, so it would never reach a topic.
         */
        @Test
        @DisplayName("the stored document is flat, with each carrier property at the top level")
        void theStoredDocumentIsFlat() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);

            JsonNode payload = payloadOf(onlyRowUnder(APPROVING_ACCOUNT_ID).getEventId());

            for (JsonNode value : payload.values()) {
                assertFalse(value.isObject(), "no property holds a nested object");
            }
            for (String carried : List.of("eventId", "eventType", "schemaVersion", "occurredAt",
                    "aggregateId")) {
                assertFalse(payload.path(carried).isMissingNode(),
                        "the carrier property " + carried + " sits at the top level");
            }
        }

        /**
         * Asserts money travels as a decimal string truncated toward zero, on both signs.
         *
         * <p>The {@code ROUNDED} phrase appears zero times across the twenty-eight programs of
         * {@code app/cbl/}, so each arithmetic store truncates. Half-up rounding carries
         * {@code 504.779} up, and truncation toward zero holds a refund at the same magnitude.
         */
        @Test
        @DisplayName("money travels as a decimal string truncated toward zero, on both signs")
        void moneyTravelsAsADecimalStringTruncatedTowardZero() {
            UUID positive = outboxWriter.writeAuthorized(approval(new BigDecimal("504.779")))
                    .getEventId();
            UUID negative = outboxWriter.writeAuthorized(approval(new BigDecimal("-504.779")))
                    .getEventId();

            JsonNode up = payloadOf(positive);
            JsonNode down = payloadOf(negative);

            assertTrue(up.path("amount").isString(), "money is text and never a bare number");
            assertEquals("504.77", up.path("amount").asString(), "truncated toward zero");
            assertEquals("-504.77", down.path("amount").asString(), "toward zero on a refund too");
            assertTrue(TRANSACTION_AMOUNT.matcher(down.path("amount").asString()).matches(),
                    "the leading sign is part of the pattern, so a refund validates");
            assertEquals(PicClause.DALYTRAN_AMT_SCALE,
                    fractionalDigitsOf(up.path("amount").asString()),
                    "the scale comes from DALYTRAN-AMT PIC S9(09)V99");
        }

        /**
         * Asserts the two amount patterns stay apart, and the narrower one governs this event.
         *
         * <p>{@code DALYTRAN-AMT PIC S9(09)V99} admits nine integer digits and
         * {@code ACCT-CURR-BAL PIC S9(10)V99} admits ten, so a value only the wider clause admits
         * is refused before an event exists.
         */
        @Test
        @DisplayName("a ten-integer-digit amount is refused by the clause this event carries")
        void aTenIntegerDigitAmountIsRefused() {
            String tenIntegerDigits = "1234567890.12";

            assertTrue(BALANCE_AMOUNT.matcher(tenIntegerDigits).matches(),
                    "the balance clause admits ten integer digits");
            assertFalse(TRANSACTION_AMOUNT.matcher(tenIntegerDigits).matches(),
                    "the amount clause admits nine");
            assertThrows(IllegalArgumentException.class,
                    () -> approval(new BigDecimal(tenIntegerDigits)),
                    "the event refuses the wider value, so no row is stored for it");
        }

        /**
         * Asserts the lookup ran on the full card number and only the masked form travels.
         *
         * <p>The cross-reference read at {@code app/cbl/CBTRN02C.cbl:L382-L383} keys on all sixteen
         * characters, and the resolved account below is the proof that the unmasked value reached
         * the lookup. The source masks nothing: the card number occupies its full sixteen
         * characters unprotected on the card detail map at {@code app/bms/COCRDSL.bms:L96} with
         * {@code LENGTH=16} at {@code app/bms/COCRDSL.bms:L99}.
         */
        @Test
        @DisplayName("the lookup ran on the full card number and only the masked form travels")
        void theLookupRanUnmaskedAndOnlyTheMaskedFormTravels() {
            AuthorizationService.Outcome outcome =
                    authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);

            assertEquals(0, new BigDecimal(APPROVING_ACCOUNT_ID).compareTo(outcome.accountId()),
                    "the full card number resolved an account, so the lookup key was unmasked");
            assertEquals(APPROVING_CARD_NUMBER, jdbcTemplate.queryForObject(
                    "SELECT card_number FROM card_xref WHERE account_id = ?", String.class,
                    APPROVING_ACCOUNT_ID),
                    "the lookup table holds the unmasked value the read keys on");

            UUID eventId = onlyRowUnder(APPROVING_ACCOUNT_ID).getEventId();
            String stored = columnOf("payload", String.class, eventId);
            String masked = MAPPER.readTree(stored).path("maskedCardNumber").asString();

            assertTrue(MASKED_CARD_NUMBER.matcher(masked).matches(),
                    "twelve mask characters and four digits");
            assertEquals(APPROVING_CARD_NUMBER.substring(12), masked.substring(12),
                    "the last four digits survive the masking");
            assertFalse(stored.contains(APPROVING_CARD_NUMBER),
                    "the full Primary Account Number reaches no payload");
        }

        /**
         * Asserts the stored document passes its own contract and the gate refuses anything else.
         *
         * <p>The gate runs before the row is saved, so a payload it refuses never reaches the
         * column, where it would stall the sweep.
         */
        @Test
        @DisplayName("the stored document passes its contract and the gate refuses anything else")
        void theStoredDocumentPassesItsContractAndTheGateRefusesAnythingElse() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);
            String stored = columnOf("payload", String.class,
                    onlyRowUnder(APPROVING_ACCOUNT_ID).getEventId());

            assertEquals(List.of(),
                    EventContracts.violationsOf(TransactionAuthorized.EVENT_TYPE, stored),
                    "the column holds a document the broker can be handed as it stands");

            ObjectNode widened = (ObjectNode) MAPPER.readTree(stored);
            widened.put(UNDECLARED_PROPERTY, 1);

            assertFalse(EventContracts
                            .violationsOf(TransactionAuthorized.EVENT_TYPE, widened.toString())
                            .isEmpty(),
                    "one undeclared property fails the document, so the gate is a working filter");
        }

        /**
         * Counts the fractional digits of a decimal string.
         *
         * @param amount the serialized amount
         * @return the digits after the decimal point
         */
        private int fractionalDigitsOf(String amount) {
            return amount.length() - amount.indexOf('.') - 1;
        }
    }

    /** The migrated columns the sweep reads, and the schema it resolves them through. */
    @Nested
    @DisplayName("the migrated columns the sweep reads")
    class ThePersistedRow {

        /**
         * Asserts the message-key column and the replica account column carry different types.
         *
         * <p>The key column holds either key form and is therefore variable-length text of sixteen.
         * The replica column holds one form alone, from {@code XREF-ACCT-ID PIC 9(11)} at
         * {@code app/cpy/CVACT03Y.cpy:L5-L8}, and is fixed-length text of eleven. The two are not
         * unified.
         */
        @Test
        @DisplayName("the message-key column and the replica account column are different types")
        void theKeyColumnAndTheReplicaAccountColumnAreDifferentTypes() {
            assertEquals("character varying", dataTypeOf("outbox_event", "aggregate_id"),
                    "the key column is variable-length text");
            assertEquals(OutboxEventEntity.TRANSACTION_KEY_LENGTH,
                    maxLengthOf("outbox_event", "aggregate_id"),
                    "sixteen characters, the wider of the two key forms");
            assertEquals("character", dataTypeOf("card_xref", "account_id"),
                    "the replica column is fixed-length text");
            assertEquals(OutboxEventEntity.AGGREGATE_ID_LENGTH,
                    maxLengthOf("card_xref", "account_id"),
                    "eleven characters, from KEYS(16 0) and the alternate index of "
                            + "app/jcl/XREFFILE.jcl:L43 and app/jcl/XREFFILE.jcl:L74");
            assertNotEquals(dataTypeOf("outbox_event", "aggregate_id"),
                    dataTypeOf("card_xref", "account_id"),
                    "one is derived from a Picture clause and one from the carrier contract");
        }

        /** Asserts the columns one sweep reads and writes carry the types it expects. */
        @Test
        @DisplayName("the columns one sweep reads and writes carry the types it expects")
        void theColumnsOneSweepTouchesCarryTheirTypes() {
            assertEquals("uuid", dataTypeOf("outbox_event", "event_id"),
                    "the primary key is the identifier a consumer deduplicates on");
            assertEquals("text", dataTypeOf("outbox_event", "payload"),
                    "the payload is text the sweep forwards unread");
            assertEquals("boolean", dataTypeOf("outbox_event", "published"),
                    "the sent flag is a boolean the sweep sets once");
            assertEquals("timestamp with time zone", dataTypeOf("outbox_event", "created_at"),
                    "the creation moment carries a zone, so the ordering holds across zones");
            assertEquals("timestamp with time zone", dataTypeOf("outbox_event", "next_attempt_at"),
                    "the due moment carries a zone too");
        }

        /**
         * Asserts the tables this service owns resolve unqualified through the configured schema.
         *
         * <p>A native statement reads no mapping model, so it resolves an unqualified name through
         * the connection search path. The datasource carries {@code currentSchema} for that reason,
         * and the migrated schema is named so no reserved word needs quoting.
         */
        @Test
        @DisplayName("the tables this service owns resolve unqualified through its schema")
        void theOwnedTablesResolveUnqualified() {
            assertEquals(MIGRATED_SCHEMA,
                    jdbcTemplate.queryForObject("SELECT current_schema()", String.class),
                    "the connection resolves unqualified names through the migrated schema");
            assertTrue(new TreeSet<>(jdbcTemplate.queryForList("SELECT table_name FROM "
                                    + "information_schema.tables WHERE ? = table_schema AND "
                                    + "table_type = 'BASE TABLE'", String.class, MIGRATED_SCHEMA))
                            .containsAll(Set.of("card_xref", "account_credit_snapshot",
                                    "outbox_event", "processed_event")),
                    "the four tables the outbox path touches are present");
            assertNotNull(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event",
                    Long.class), "an unqualified name resolves without a schema prefix");
        }

        /**
         * Asserts an eleven-digit key keeps its leading zero from the column to the message.
         *
         * <p>The key travels as text and nothing pads, so account seventy-seven renders as eleven
         * characters opening with zeros. The cross-reference fixture measures thirty-six characters
         * per record against the fifty its copybook declares. The fourteen-byte {@code FILLER} at
         * {@code app/cpy/CVACT03Y.cpy:L8} is physically absent, so a reader of that fixture has to
         * tolerate the narrower width.
         */
        @Test
        @DisplayName("an eleven-digit key keeps its leading zero from the column to the message")
        void anElevenDigitKeyKeepsItsLeadingZero() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), ACTOR);

            relay.publishPendingEvents();

            String key = publisher.under(APPROVING_ACCOUNT_ID).getFirst().key();
            assertEquals(OutboxEventEntity.AGGREGATE_ID_LENGTH, key.length(),
                    "eleven characters, the width KEYS(11,25) declares at "
                            + "app/jcl/XREFFILE.jcl:L74, indexed NONUNIQUEKEY at "
                            + "app/jcl/XREFFILE.jcl:L75");
            assertTrue(key.startsWith("0"), "the leading zero belongs to the value");
            assertEquals(APPROVING_ACCOUNT_ID, key, "no digit is lost between column and message");
        }

        /**
         * Reads the declared type of one column of the migrated schema.
         *
         * @param table  the table name
         * @param column the column name
         * @return the type name the catalogue reports
         */
        private String dataTypeOf(String table, String column) {
            return jdbcTemplate.queryForObject("SELECT data_type FROM information_schema.columns "
                            + "WHERE ? = table_schema AND table_name = ? AND column_name = ?",
                    String.class, MIGRATED_SCHEMA, table, column);
        }

        /**
         * Reads the declared character length of one column of the migrated schema.
         *
         * @param table  the table name
         * @param column the column name
         * @return the length the catalogue reports
         */
        private int maxLengthOf(String table, String column) {
            return jdbcTemplate.queryForObject("SELECT character_maximum_length FROM "
                            + "information_schema.columns WHERE ? = table_schema AND "
                            + "table_name = ? AND column_name = ?",
                    Integer.class, MIGRATED_SCHEMA, table, column);
        }
    }

    /** The producer settings the send runs under, and the fault description it would carry. */
    @Nested
    @DisplayName("the producer settings and the fault description")
    class TheProducerSettings {

        /**
         * Asserts the producer is idempotent and waits for each replica.
         *
         * <p>The settings are read from the factory the configuration built, so no broker takes
         * part. A repeated send from the client is then invisible to a consumer, and a stored
         * record survives the loss of its leader.
         */
        @Test
        @DisplayName("the producer is idempotent and waits for acknowledgement from all replicas")
        void theProducerIsIdempotentAndWaitsForAllReplicas() {
            Map<String, Object> settings =
                    authorizationEventProducerFactory.getConfigurationProperties();

            assertEquals(Boolean.TRUE, settings.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG),
                    "the client suppresses its own repeated sends");
            assertEquals("all", settings.get(ProducerConfig.ACKS_CONFIG),
                    "a send is acknowledged only once each replica holds the record");
        }

        /** Asserts the dead-letter topic is neither of the two topics this service publishes on. */
        @Test
        @DisplayName("the dead-letter topic is neither topic this service publishes on")
        void theDeadLetterTopicIsNeitherPublishTopic() {
            AuthorizationProperties.Kafka.Topics configured = topics();

            assertNotEquals(configured.deadLetter(), configured.transactionAuthorized(),
                    "an approval has its own topic");
            assertNotEquals(configured.deadLetter(), configured.transactionDeclined(),
                    "a decline has its own topic");
        }

        /**
         * Asserts a publish fault describes itself inside the abend-record widths.
         *
         * <p>The four widths come from {@code ABEND-CODE PIC X(4)} at
         * {@code app/cpy/CSMSG02Y.cpy:L22}, {@code ABEND-CULPRIT PIC X(8)} at
         * {@code app/cpy/CSMSG02Y.cpy:L24}, {@code ABEND-REASON PIC X(50)} at
         * {@code app/cpy/CSMSG02Y.cpy:L26} and {@code ABEND-MSG PIC X(72)} at
         * {@code app/cpy/CSMSG02Y.cpy:L28}. The culprit of a refused send is longer than its width
         * and keeps its leading characters.
         */
        @Test
        @DisplayName("a publish fault describes itself inside the abend-record widths")
        void aPublishFaultDescribesItselfInsideTheAbendRecordWidths() {
            DeadLetterMetadata described = DeadLetterMetadata.fromFailure("0999",
                    new IllegalStateException("the broker refused this record"),
                    "the outbox row stays unsent", "the next sweep claims the row again");

            assertTrue(described.abendCode().length() <= DeadLetterMetadata.ABEND_CODE_MAX_LENGTH,
                    "the code fits PIC X(4)");
            assertEquals(IllegalStateException.class.getSimpleName()
                            .substring(0, DeadLetterMetadata.CULPRIT_MAX_LENGTH),
                    described.culprit(),
                    "a longer culprit keeps its leading characters and fits PIC X(8)");
            assertTrue(described.reason().length() <= DeadLetterMetadata.REASON_MAX_LENGTH,
                    "a reason fits PIC X(50)");
            assertTrue(described.message().length() <= DeadLetterMetadata.MESSAGE_MAX_LENGTH,
                    "the message fits PIC X(72)");
        }
    }
}
