package com.carddemo.authorization.outbox;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.AuthorizationApplication;
import com.carddemo.authorization.AuthorizationServiceDatabase;
import com.carddemo.authorization.TestIdentityPasswords;
import com.carddemo.authorization.api.AuthorizationRequest;
import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.config.ObservabilityConfig;
import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.authorization.domain.ReplicaSynchronization;
import com.carddemo.authorization.domain.RequestCaller;
import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.messaging.DeadLetterMetadata;
import com.carddemo.authorization.messaging.EventPublisherPort;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.DeclineReason;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.serde.EventContracts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
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
import org.springframework.transaction.IllegalTransactionStateException;
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
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "ACQUIRER_PASSWORD_HASH=" + TestIdentityPasswords.ACQUIRER_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "spring.kafka.listener.auto-startup=false",
                "carddemo.outbox.relay.fixed-delay-ms=3600000"
        })
@ContextConfiguration(classes = OutboxRelayTest.RecordingPublisherConfiguration.class)
@DisplayName("The authorization outbox: one row per call, sent after that row has committed")
class OutboxRelayTest {

    /** Schema Flyway migrates, and the one the connection search path names. */
    private static final String MIGRATED_SCHEMA = "authorization_service";

    /** Host and port the broker client is pointed at, where nothing listens. */
    private static final String UNREACHABLE_BROKER = "localhost:1";

    /** The request identity each decision row records, carried whole into the audit column. */
    private static final String ACTOR = "OPERATR1";

    /**
     * The caller every decision below presents.
     *
     * <p>It reaches every subject. These tests measure what one call commits and what the relay then
     * publishes, and the synthetic accounts they authorize against belong to no configured ownership
     * scope, so an entitlement refusal would stop the decision before a row was ever written.
     * {@code CallerEntitlementTest} measures the refusal itself.
     */
    private static final RequestCaller CALLER = RequestCaller.administrator(ACTOR);

    /**
     * The dead-letter destination, as {@code application.yml} names it.
     *
     * <p>{@link RecordingPublisher} is static and cannot read the bound settings, so the value is
     * repeated here and {@code theDeadLetterConstantMatchesTheBoundTopic} holds the two in step.
     */
    private static final String DEAD_LETTER_TOPIC = "carddemo.dead-letter";

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

    /**
     * The twenty top-level names an account-keyed {@code TransactionDeclined} declares.
     *
     * <p>The nine descriptive names beyond the eleven of version one carry the values
     * {@code 2500-WRITE-REJECT-REC} at {@code app/cbl/CBTRN02C.cbl:L446-L465} writes inside
     * {@code REJECT-TRAN-DATA PIC X(350)}. The ledger posting service owns the reject row and reads
     * them from here, because a refused transaction exists in no dataset it could read them from.
     */
    private static final Set<String> DECLINE_PROPERTIES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
            "transactionId", "accountId", "declineReasonCode", "declineReasonDescription",
            "amount", "maskedCardNumber", "transactionTypeCode", "merchantCategoryCode", "source",
            "description", "merchantId", "merchantName", "merchantCity", "merchantZip",
            "originTimestamp");

    /** A property name no document declares, used to prove the publish gate refuses one. */
    private static final String UNDECLARED_PROPERTY = "unexpectedProperty";

    /** Payload the recording publisher is told to refuse. */
    private static final String REFUSED_PAYLOAD = "{\"seq\":1}";

    /** Payload the recording publisher accepts, stored behind {@link #REFUSED_PAYLOAD}. */
    private static final String ACCEPTED_PAYLOAD = "{\"seq\":2}";

    /** Payload the recording publisher accepts and never acknowledges. */
    private static final String STALLED_PAYLOAD = "{\"seq\":3}";

    /** What a row records when the whole-pass deadline arrived before its send resolved. */
    private static final String RELAY_DEADLINE_EXCEEDED = "RelayDeadlineExceededException";

    /**
     * A sixteen-character transaction key, the form a decline with an unresolved card is stored under.
     *
     * <p>Width from {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}. Migration
     * {@code V4} admits this form beside the eleven-digit account form.
     */
    private static final String UNRESOLVED_TRANSACTION_KEY = "0000009990000001";

    /**
     * The aggregate identifier a diagnostic declares when its row names no account.
     *
     * <p>Eleven zeros are an account this platform neither seeds nor issues, so the value states the
     * absence rather than attributing the failure to an account.
     */
    private static final String UNRESOLVED_ACCOUNT_SENTINEL = "00000000000";

    /** Longest an assertion waits for the sweep thread. */
    private static final Duration LONGEST_SWEEP_WAIT = Duration.ofSeconds(30L);

    /** How often an awaiting assertion re-reads the row. */
    private static final Duration SWEEP_POLL = Duration.ofMillis(20L);

    /** Seconds the scheduler stop waits for a task it already started. */
    private static final int SCHEDULER_STOP_WAIT_SECONDS = 30;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link AuthorizationServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    private static final PostgreSQLContainer POSTGRES = AuthorizationServiceDatabase.container();

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
     * @return the connection string each unqualified statement here resolves its tables through
     */
    private static String migratedSchemaUrl() {
        return AuthorizationServiceDatabase.urlFor(OutboxRelayTest.class);
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
        return "9999" + String.format(Locale.ROOT, "%012d", serial);
    }

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
         * Declares the replica streams caught up, which this context cannot measure.
         *
         * <p>{@code KafkaReplicaSynchronization} reads the two replica listener containers, and this
         * context starts neither: {@code spring.kafka.listener.auto-startup=false} is set above
         * because {@link #UNREACHABLE_BROKER} is where the client points. A stopped container is a
         * refusal in production and correctly so, since nothing is applying what the owners publish
         * while it is stopped, so without this bean every decision below would refuse before it wrote
         * the row this class exists to measure.
         *
         * <p>Substituting the verdict rather than relaxing the rule is what keeps the two concerns
         * apart. {@code messaging/KafkaReplicaSynchronizationTest} measures the verdict itself,
         * container state by container state, and {@code domain/AuthorizationServiceTest} measures
         * what a refusing verdict does to a decision. This class measures the outbox.
         *
         * @return a verdict reporting a stream with nothing waiting on it
         */
        @Bean
        @Primary
        ReplicaSynchronization synchronizedReplicaStreams() {
            return () -> ReplicaSynchronization.Verdict.synchronizedAt(0L);
        }

        /**
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

        /** Payloads this seam accepts and never resolves, standing in for an unreachable broker. */
        private final Set<String> stalled = ConcurrentHashMap.newKeySet();

        /** Topics this seam refuses whatever payload arrives on them. */
        private final Set<String> refusedTopics = ConcurrentHashMap.newKeySet();

        /** Every diagnostic this seam was offered on the dead-letter topic, refused ones included. */
        private final List<Sent> deadLettersOffered = new CopyOnWriteArrayList<>();

        /**
         * Runs inside the publish call, before the stage is answered.
         *
         * <p>This is how a test observes the state of the world at the moment a send is issued. The
         * relay holds no transaction while it sends, so a read taken here through another connection
         * sees exactly what the claim transaction committed, which is the property that
         * distinguishes a committed claim from an uncommitted one.
         */
        private volatile Consumer<Sent> duringPublish = sent -> { };

        /**
         * Answers one publish call the way a broker client does.
         *
         * <p>A refusal arrives as a <em>failed stage</em> rather than a thrown exception, because
         * that is where a broker reports one: the send is accepted, dispatched, and fails later.
         * {@code OutboxRelay} unwraps the cause and records it, so the reason a row carries is the
         * same either way, and this shape exercises the unwrapping instead of stepping around it.
         *
         * <p>A stalled payload returns a stage that never completes, which is the only way to reach
         * the whole-pass deadline without a real unreachable broker.
         *
         * @param topic       the resolved destination
         * @param aggregateId the message key
         * @param payload     the stored payload, forwarded unread
         * @return a completed, failed or never-completing stage, per this seam's configuration
         */
        @Override
        public CompletionStage<Void> publish(String topic, String aggregateId, String payload) {
            Sent offered = new Sent(topic, aggregateId, payload, Thread.currentThread().getName());
            duringPublish.accept(offered);
            if (topic.equals(DEAD_LETTER_TOPIC)) {
                deadLettersOffered.add(offered);
            }
            if (refused.contains(payload) || refusedTopics.contains(topic)) {
                return CompletableFuture.failedStage(
                        new IllegalStateException("the broker refused this record"));
            }
            if (stalled.contains(payload)) {
                return new CompletableFuture<>();
            }
            sent.add(offered);
            return CompletableFuture.completedStage(null);
        }

        /**
         * Tells this seam to answer one payload with a fault.
         *
         * @param payload the payload to refuse
         */
        void refuse(String payload) {
            refused.add(payload);
        }

        /**
         * Tells this seam to accept one payload and never resolve it.
         *
         * @param payload the payload to leave outstanding
         */
        void stall(String payload) {
            stalled.add(payload);
        }

        /**
         * Tells this seam to refuse every payload that arrives on one topic.
         *
         * @param topic the topic to refuse
         */
        void refuseTopic(String topic) {
            refusedTopics.add(topic);
        }

        /**
         * Tells this seam to accept payloads on one topic again.
         *
         * @param topic the topic to stop refusing
         */
        void acceptTopic(String topic) {
            refusedTopics.remove(topic);
        }

        /**
         * Installs an action this seam runs inside each publish call, before it answers.
         *
         * @param action what to run, given the send being issued
         */
        void duringPublish(Consumer<Sent> action) {
            this.duringPublish = action;
        }

        /**
         * Returns every diagnostic this seam was offered, whether it accepted it or refused it.
         *
         * @return each offer on the dead-letter topic, in order
         */
        List<Sent> deadLettersOffered() {
            return List.copyOf(deadLettersOffered);
        }

        /** Forgets what was sent, what was refused and what was left outstanding. */
        void forget() {
            sent.clear();
            refused.clear();
            stalled.clear();
            refusedTopics.clear();
            deadLettersOffered.clear();
            duringPublish = offered -> { };
        }

        /**
         * @return each send, in order
         */
        List<Sent> all() {
            return List.copyOf(sent);
        }

        /**
         * @param key the message key to filter on
         * @return each send under that key, in order
         */
        List<Sent> under(String key) {
            return sent.stream().filter(one -> one.key().equals(key)).toList();
        }

        /**
         * @param key the message key to filter on
         * @return each topic, in send order
         */
        List<String> topicsUnder(String key) {
            return under(key).stream().map(Sent::topic).toList();
        }

        /**
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

    /** The registry the relay's counters register with, read back by the terminal assertions. */
    @Autowired
    private MeterRegistry meterRegistry;

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
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM card_xref WHERE card_number IN (?, ?)",
                APPROVING_CARD_NUMBER, DECLINING_CARD_NUMBER);
        jdbcTemplate.update("DELETE FROM account_credit_snapshot WHERE account_id IN (?, ?)",
                APPROVING_ACCOUNT_ID, DECLINING_ACCOUNT_ID);
    }

    /**
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
     * capture moment is the current second, which reject reason {@code 0103} at
     * {@code app/cbl/CBTRN02C.cbl:L414-L420} compares against the account expiry.
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
     * @return the truncated moment, rendered as text
     */
    private static String currentSecond() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
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
     * @return twelve mask characters and four digits
     */
    private static String maskedApprovingCard() {
        return "*".repeat(12) + APPROVING_CARD_NUMBER.substring(12);
    }

    /**
     * @param dueAt   the moment the row becomes claimable, which is also its creation moment
     * @param payload the payload the relay forwards unread
     * @return the stored row
     */
    private OutboxEventEntity storeRow(Instant dueAt, String payload) {
        return outboxEvents.save(new OutboxEventEntity(UUID.randomUUID(),
                TransactionAuthorized.EVENT_TYPE, APPROVING_ACCOUNT_ID, payload, dueAt));
    }

    /**
     * Stores one row carrying the transaction key form, as a database that ran before migration
     * {@code V19} holds.
     *
     * <p>No producer writes that form any more: every decision names the account it applies to, and
     * {@code outbox/OutboxWriter} refuses anything else before a row is built. The column still admits
     * it, because the rows already stored carry it and the relay writes a column on every row it
     * claims, retries or abandons -- a CHECK narrowed to the account form would refuse those updates
     * and strand real events. This method writes past the application-level rule to reproduce one of
     * those rows, which is what these assertions are about.
     *
     * @param transactionKey the sixteen-character key the row carries
     * @param payload        the stored event text
     * @param createdAt      the moment the row records
     * @return the stored row
     */
    private OutboxEventEntity storePreMigrationTransactionKeyedRow(String transactionKey,
            String payload, Instant createdAt) {
        return outboxEvents.save(new OutboxEventEntity(UUID.randomUUID(),
                TransactionDeclined.EVENT_TYPE, transactionKey, payload, createdAt));
    }

    /**
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
     * @param document the parsed payload
     * @return each top-level property name
     */
    private static Set<String> propertyNamesOf(JsonNode document) {
        return new TreeSet<>(document.propertyNames());
    }

    /**
     * @return the bound topics of this service
     */
    private AuthorizationProperties.Kafka.Topics topics() {
        return properties.kafka().topics();
    }

    /**
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

        @Test
        @DisplayName("an approved call stores one event row and one decision row")
        void anApprovedCallStoresOneEventRowAndOneDecisionRow() {
            AuthorizationService.Outcome outcome =
                    authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);

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
         * A declined call commits one row carrying its four-character reject code.
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
                    authorizations.authorize(request(DECLINING_CARD_NUMBER, AMOUNT), CALLER);

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

        @Test
        @DisplayName("the event row commits with the transaction its caller opened")
        void theEventRowCommitsWithTheTransactionItsCallerOpened() {
            UUID eventId = transactionTemplate.execute(status ->
                    outboxWriter.writeAuthorized(approval(AMOUNT)).getEventId());

            assertEquals(1, rowsFor(eventId), "the committed transaction carried the row with it");
        }

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
         * Each write operation requires the caller's transaction and starts none.
         *
         * @throws NoSuchMethodException never, since both methods are declared
         */
        @Test
        @DisplayName("each write operation requires the caller's transaction and starts none")
        void eachWriteOperationRequiresTheCallersTransaction() throws NoSuchMethodException {
            Transactional approval = OutboxWriter.class
                    .getMethod("writeAuthorized", TransactionAuthorized.class)
                    .getAnnotation(Transactional.class);
            Transactional decline = OutboxWriter.class
                    .getMethod("writeDeclined", TransactionDeclined.class)
                    .getAnnotation(Transactional.class);

            for (Transactional annotation : List.of(approval, decline)) {
                assertEquals(Propagation.MANDATORY, annotation.propagation(),
                        "the write joins the caller's transaction and opens none of its own");
                assertFalse(annotation.readOnly(), "the operation writes a row");
            }
        }

        /**
         * A write with no transaction in progress is refused rather than committing alone.
         *
         * <p>{@code MANDATORY} is what makes the atomicity of the decision and its event a property
         * the container holds. Under {@code REQUIRED} this call would have committed an outbox row
         * with no decision behind it.
         */
        @Test
        @DisplayName("a write with no transaction in progress is refused")
        void aWriteWithNoTransactionInProgressIsRefused() {
            assertThrows(IllegalTransactionStateException.class,
                    () -> outboxWriter.writeAuthorized(approval(AMOUNT)),
                    "an approval row cannot be committed outside the decision transaction");
            assertThrows(IllegalTransactionStateException.class,
                    () -> outboxWriter.writeDeclined(TransactionDeclined.ofUnresolvedAccount(
                            "0000000000000001", AMOUNT,
                            PanMasker.maskCardNumber(APPROVING_CARD_NUMBER))),
                    "and neither can a decline row");
        }

        /**
         * A producer call writes no duplicate marker.
         *
         * <p>The marker guards an event a consumer receives, and this call sits on the produce
         * side. The source detects no duplicate at all: the write at
         * {@code app/cbl/CBTRN02C.cbl:L562-L579} answers a repeated key with an abend.
         */
        @Test
        @DisplayName("a producer call writes no duplicate marker")
        void aProducerCallWritesNoDuplicateMarker() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);

            assertEquals(0, (int) jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM processed_event", Integer.class),
                    "the marker table stays empty across a call that publishes");
        }
    }

    /** What the sweep sends, when it sends it, and what it leaves alone. */
    @Nested
    @DisplayName("the sweep, which sends what the call committed")
    class TheSweep {

        @Test
        @DisplayName("request handling sends nothing and leaves the row unsent")
        void requestHandlingSendsNothing() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);

            assertEquals(List.of(), publisher.all(), "no send happened while the call was handled");
            assertEquals(Boolean.FALSE,
                    columnOf("published", Boolean.class,
                            onlyRowUnder(APPROVING_ACCOUNT_ID).getEventId()),
                    "the committed row is work still owed");
        }

        /**
         * A sweep on another thread sends the committed row and marks it sent.
         *
         * <p>The mark is read back outside the mapped model, so what the assertion sees is the
         * value the sweep's own transaction committed.
         */
        @Test
        @DisplayName("a sweep on another thread sends the row and marks it sent")
        void aSweepOnAnotherThreadSendsTheRowAndMarksItSent() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);
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
         * The message key is the account the cross-reference resolved.
         *
         * <p>Keying each event of one account on that value holds those events on one partition and
         * in send order. The balance a downstream consumer maintains depends on that order.
         */
        @Test
        @DisplayName("the message key is the account the cross-reference resolved")
        void theMessageKeyIsTheResolvedAccount() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);

            relay.publishPendingEvents();

            RecordingPublisher.Sent sent = publisher.under(APPROVING_ACCOUNT_ID).getFirst();
            assertEquals(APPROVING_ACCOUNT_ID, sent.key(), "the key names the account");
            assertEquals(sent.key(), MAPPER.readTree(sent.payload()).path("aggregateId").asString(),
                    "the key and the aggregate identifier inside the payload hold one value");
        }

        @Test
        @DisplayName("each event type reaches its own topic and none reaches the dead-letter topic")
        void eachEventTypeReachesItsOwnTopic() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);
            authorizations.authorize(request(DECLINING_CARD_NUMBER, AMOUNT), CALLER);

            relay.publishPendingEvents();

            assertEquals(List.of(topics().transactionAuthorized()),
                    publisher.topicsUnder(APPROVING_ACCOUNT_ID), "an approval reaches its topic");
            assertEquals(List.of(topics().transactionDeclined()),
                    publisher.topicsUnder(DECLINING_ACCOUNT_ID), "a decline reaches its topic");
            assertFalse(publisher.all().stream()
                            .anyMatch(sent -> sent.topic().equals(topics().deadLetter())),
                    "a decline is a committed outcome, so it never reaches the dead-letter topic");
        }

        @Test
        @DisplayName("rows of one account reach the broker oldest first")
        void rowsOfOneAccountReachTheBrokerOldestFirst() {
            storeRow(minutesAgo(2L), REFUSED_PAYLOAD);
            storeRow(minutesAgo(1L), ACCEPTED_PAYLOAD);

            relay.publishPendingEvents();
            relay.publishPendingEvents();

            assertEquals(List.of(REFUSED_PAYLOAD, ACCEPTED_PAYLOAD),
                    publisher.payloadsUnder(APPROVING_ACCOUNT_ID),
                    "the older row reaches the broker first, so one account keeps its order");
        }

        /**
         * Asserts a refused send leaves its row unsent and pauses that account for the pass.
         *
         * <p>The row records the class of the fault. The source formats a file status at
         * {@code app/cbl/CBTRN02C.cbl:L714-L727} and then reaches the abend routine at
         * {@code app/cbl/CBTRN02C.cbl:L707-L711}, which performs no cleanup. Here the fault is
         * bounded to one row and one account.
         *
         * <p>The row behind it is untouched because {@code claimDueRows} claims the due head row of
         * each account and both rows here belong to one account. That is the property that keeps
         * per-account order intact through a partial failure: the newer row cannot overtake the
         * older one on the topic, because it was never in the batch that failed.
         */
        @Test
        @DisplayName("a refused send leaves its row unsent and pauses that account for the pass")
        void aRefusedSendLeavesItsRowUnsentAndPausesThatAccount() {
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
                    "the account pauses at the refusal, so the row behind it waits its turn");
            assertEquals(0, (int) columnOf("attempt_count", Integer.class, behind.getEventId()),
                    "the row behind it was never claimed, so it was not attempted");
        }

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
         * A claim a stopped relay left behind is returned and then sent.
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
         * The sweep reads its interval from configuration and opens its own boundary.
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

    /**
     * How one pass is divided into transactions, and what a row nobody can publish leaves behind.
     *
     * <p>Two defects are held closed here, and both were invisible while one transaction spanned the
     * whole pass.
     *
     * <p><b>The claim was rolled back by the death it was meant to survive.</b> Claiming and sending
     * shared one transaction, so a process that died mid-pass rolled its own claim back.
     * {@code recoverStrandedClaims} looks for committed {@code CLAIMED} rows and therefore had
     * nothing to find, and the same transaction held a connection and every row lock of the batch for
     * the sum of its broker waits.
     *
     * <p><b>An abandoned row left no durable record.</b> A row that spent its attempts reached
     * {@link OutboxEventEntity.RelayState#ABANDONED} and nothing else: no diagnostic obligation, no
     * retry of one, and no evidence beyond a readiness probe. The source answers a write it cannot
     * complete by ending the address space at {@code app/cbl/CBTRN02C.cbl:L707-L711}, which leaves an
     * operator a job log. Naming the row on a topic replaces that only if the naming actually
     * happens, which is what these assertions measure.
     */
    @Nested
    @DisplayName("the transactions of one pass, and the terminal path of a row nobody can publish")
    class TheTerminalPath {

        /**
         * Asserts the claim is committed before the first send is issued.
         *
         * <p>The read runs inside the publish call, through {@link JdbcTemplate} on a connection of
         * its own, and no transaction of the relay is open at that moment. A {@code CLAIMED} row
         * visible from there is a row whose claim transaction committed. The same read inside the old
         * single-transaction pass saw {@code PENDING}, because the claim was still uncommitted work
         * of the transaction doing the sending.
         */
        @Test
        @DisplayName("the claim is committed before the first send is issued")
        void theClaimIsCommittedBeforeTheFirstSendIsIssued() {
            OutboxEventEntity row = storeRow(minutesAgo(1L), ACCEPTED_PAYLOAD);
            List<String> observedStates = new CopyOnWriteArrayList<>();
            publisher.duringPublish(sent -> observedStates.add(
                    columnOf("relay_state", String.class, row.getEventId())));

            relay.publishPendingEvents();

            assertEquals(List.of(OutboxEventEntity.RelayState.CLAIMED.name()), observedStates,
                    "the send saw its own row already committed as claimed");
            assertEquals(Boolean.TRUE, columnOf("published", Boolean.class, row.getEventId()),
                    "and the acknowledged row is marked published afterwards");
        }

        /**
         * Asserts one result commits before the next row is attempted.
         *
         * <p>The second row is accepted and never acknowledged, so the pass ends on its deadline. The
         * first row is nonetheless durable, which it could not be if one transaction covered both:
         * that transaction would still be open when the deadline arrived.
         *
         * <p>The two rows belong to different accounts because the claim query returns the due head
         * row of each account, and two rows of one account are never in one batch.
         */
        @Test
        @DisplayName("one result commits before the next row is attempted")
        void oneResultCommitsBeforeTheNextRowIsAttempted() {
            OutboxEventEntity first = storeRow(minutesAgo(2L), ACCEPTED_PAYLOAD);
            OutboxEventEntity second = outboxEvents.save(new OutboxEventEntity(UUID.randomUUID(),
                    TransactionAuthorized.EVENT_TYPE, DECLINING_ACCOUNT_ID, STALLED_PAYLOAD,
                    minutesAgo(1L)));
            publisher.stall(STALLED_PAYLOAD);

            relay.publishPendingEvents();

            assertEquals(Boolean.TRUE, columnOf("published", Boolean.class, first.getEventId()),
                    "the first result is committed, and the pass deadline arrived after it");
            assertEquals(Boolean.FALSE, columnOf("published", Boolean.class, second.getEventId()),
                    "the stalled row is unsent");
            assertEquals(RELAY_DEADLINE_EXCEEDED,
                    columnOf("last_error", String.class, second.getEventId()),
                    "and it names the deadline rather than a broker fault");
        }

        /**
         * Asserts a row that spends its attempts owes a diagnostic until the broker acknowledges one.
         *
         * <p>The obligation is a column rather than one unawaited send, and this is the reason: while
         * the broker refuses, the row is still terminal and the claim query will never return it
         * again, so the obligation is the only thing that brings the relay back to it.
         */
        @Test
        @DisplayName("an abandoned row owes a diagnostic until the broker acknowledges one")
        void anAbandonedRowOwesADiagnosticUntilTheBrokerAcknowledgesIt() {
            OutboxEventEntity row = storeRow(minutesAgo(1L), REFUSED_PAYLOAD);
            publisher.refuse(REFUSED_PAYLOAD);
            publisher.refuseTopic(DEAD_LETTER_TOPIC);

            spendEveryAttempt(row);

            assertEquals(OutboxEventEntity.RelayState.ABANDONED.name(),
                    columnOf("relay_state", String.class, row.getEventId()),
                    "a row that cannot be published is given up on rather than retried for ever");
            assertEquals(OutboxEventEntity.MAX_DELIVERY_ATTEMPTS,
                    (int) columnOf("attempt_count", Integer.class, row.getEventId()),
                    "it is given up on at the declared ceiling");
            assertEquals(OutboxEventEntity.DeadLetterState.REQUIRED.name(),
                    columnOf("dead_letter_state", String.class, row.getEventId()),
                    "a refused diagnostic is still owed, which is the difference between an "
                            + "abandoned event and a lost one");
            assertNull(columnOf("dead_letter_published_at", Instant.class, row.getEventId()),
                    "nothing records an acknowledgement that never arrived");
            assertFalse(publisher.deadLettersOffered().isEmpty(),
                    "the diagnostic was offered, and refused");
        }

        /**
         * Asserts an owed diagnostic is offered again and cleared only on an acknowledgement.
         *
         * <p>The retry is independent of the event: offering the diagnostic again raises no attempt
         * count on the business row, because the business row is already spent.
         */
        @Test
        @DisplayName("an owed diagnostic is offered again and cleared on an acknowledgement")
        void anOwedDiagnosticIsOfferedAgainAndClearedOnAnAcknowledgement() {
            OutboxEventEntity row = storeRow(minutesAgo(1L), REFUSED_PAYLOAD);
            publisher.refuse(REFUSED_PAYLOAD);
            publisher.refuseTopic(DEAD_LETTER_TOPIC);
            spendEveryAttempt(row);
            int attemptsAfterAbandonment =
                    columnOf("attempt_count", Integer.class, row.getEventId());

            publisher.acceptTopic(DEAD_LETTER_TOPIC);
            relay.publishPendingEvents();

            assertEquals(OutboxEventEntity.DeadLetterState.PUBLISHED.name(),
                    columnOf("dead_letter_state", String.class, row.getEventId()),
                    "the obligation is discharged against the acknowledgement and nothing else");
            assertNotNull(columnOf("dead_letter_published_at", Instant.class, row.getEventId()),
                    "and the moment it was discharged is recorded");
            assertEquals(attemptsAfterAbandonment,
                    (int) columnOf("attempt_count", Integer.class, row.getEventId()),
                    "offering a diagnostic is not another attempt on the business event");
            assertEquals(1, publisher.under(row.getAggregateId()).size(),
                    "one diagnostic reached the broker");
            assertEquals(List.of(DEAD_LETTER_TOPIC),
                    publisher.topicsUnder(row.getAggregateId()),
                    "and it reached the dead-letter topic, never a business topic");

            relay.publishPendingEvents();
            relay.publishPendingEvents();

            assertEquals(1, publisher.under(row.getAggregateId()).size(),
                    "an acknowledged diagnostic is never offered again");
        }

        /**
         * Asserts a diagnostic for a transaction-keyed row travels under the unresolved-account
         * sentinel.
         *
         * <p>A row written before migration {@code V19} can carry the sixteen-character transaction
         * identifier that {@code EventEnvelope.UNRESOLVED_AGGREGATE_KEY_PATTERN} still admits on the
         * read side and migration {@code V4} stored. {@code schemas/dead-letter-v1.json} accepts eleven
         * digits and nothing else, so
         * handing that key to the diagnostic would fail the serialize gate on every pass and leave an
         * obligation that could never clear. The sentinel is what keeps it dischargeable, and the row
         * still names itself through {@code failedEventId}.
         */
        @Test
        @DisplayName("a transaction-keyed row names the unresolved-account sentinel on its"
                + " diagnostic")
        void aTransactionKeyedRowNamesTheSentinelOnItsDiagnostic() {
            OutboxEventEntity row = storePreMigrationTransactionKeyedRow(
                    UNRESOLVED_TRANSACTION_KEY, REFUSED_PAYLOAD, minutesAgo(1L));
            publisher.refuse(REFUSED_PAYLOAD);

            spendEveryAttempt(row);

            assertEquals(OutboxEventEntity.DeadLetterState.PUBLISHED.name(),
                    columnOf("dead_letter_state", String.class, row.getEventId()),
                    "the diagnostic was accepted, so the obligation could be discharged at all");
            RecordingPublisher.Sent diagnostic = publisher.deadLettersOffered().getFirst();
            assertEquals(UNRESOLVED_ACCOUNT_SENTINEL, diagnostic.key(),
                    "the message key states the absence of an account");
            assertEquals(UNRESOLVED_ACCOUNT_SENTINEL,
                    MAPPER.readTree(diagnostic.payload()).path("aggregateId").asString(),
                    "and the envelope declares the same value the key carries");
            assertTrue(diagnostic.payload().contains(row.getEventId().toString()),
                    "the row is still named exactly, through failedEventId");
            assertEquals(List.of(), EventContracts.violationsOf(EventContracts.DEAD_LETTER,
                            diagnostic.payload()),
                    "and the diagnostic satisfies the document its own gate validates it against");
        }

        /**
         * Asserts the three terminal counters record a spent row, a refused diagnostic and an
         * acknowledged one apart from each other.
         */
        @Test
        @DisplayName("the terminal counters record each outcome apart")
        void theTerminalCountersRecordEachOutcomeApart() {
            double abandonedBefore = counterValue(ObservabilityConfig.OUTBOX_ABANDONED_COUNTER);
            double failedBefore = diagnosticCount(ObservabilityConfig.DIAGNOSTIC_FAILED);
            double publishedBefore =
                    diagnosticCount(ObservabilityConfig.DIAGNOSTIC_PUBLISHED);
            OutboxEventEntity row = storeRow(minutesAgo(1L), REFUSED_PAYLOAD);
            publisher.refuse(REFUSED_PAYLOAD);
            publisher.refuseTopic(DEAD_LETTER_TOPIC);

            spendEveryAttempt(row);

            assertEquals(1.0D,
                    counterValue(ObservabilityConfig.OUTBOX_ABANDONED_COUNTER) - abandonedBefore,
                    "one row given up on, however many attempts it took");
            assertEquals(1.0D,
                    diagnosticCount(ObservabilityConfig.DIAGNOSTIC_FAILED) - failedBefore,
                    "one diagnostic the broker refused");
            assertEquals(0.0D,
                    diagnosticCount(ObservabilityConfig.DIAGNOSTIC_PUBLISHED)
                            - publishedBefore,
                    "and none acknowledged");

            publisher.acceptTopic(DEAD_LETTER_TOPIC);
            relay.publishPendingEvents();

            assertEquals(1.0D,
                    diagnosticCount(ObservabilityConfig.DIAGNOSTIC_PUBLISHED)
                            - publishedBefore,
                    "the acknowledged diagnostic is counted once");
            assertEquals(1.0D,
                    counterValue(ObservabilityConfig.OUTBOX_ABANDONED_COUNTER) - abandonedBefore,
                    "the row is given up on once, however often its diagnostic is offered");
        }

        /** Asserts the dead-letter constant this class holds is the topic the settings name. */
        @Test
        @DisplayName("the dead-letter constant matches the bound topic")
        void theDeadLetterConstantMatchesTheBoundTopic() {
            assertEquals(topics().deadLetter(), DEAD_LETTER_TOPIC,
                    "a renamed topic must reach the static seam this class installs");
        }

        /**
         * Runs passes until the row is abandoned, returning it to a due state between them.
         *
         * <p>Each recorded failure pushes {@code next_attempt_at} out, so the row would not be
         * claimable again inside one test. Moving that column back is what a passing hour does in a
         * deployment.
         *
         * @param row the row to spend every attempt of
         */
        private void spendEveryAttempt(OutboxEventEntity row) {
            for (int attempt = 0; attempt < OutboxEventEntity.MAX_DELIVERY_ATTEMPTS; attempt++) {
                jdbcTemplate.update("UPDATE outbox_event SET next_attempt_at = ? "
                        + "WHERE event_id = ? AND relay_state = 'PENDING'",
                        Timestamp.from(minutesAgo(1L)), row.getEventId());
                relay.publishPendingEvents();
            }
        }

        /**
         * Reads one counter of this service by name, or zero before it is registered.
         *
         * @param name the full meter name
         * @return the current count
         */
        private double counterValue(String name) {
            Counter counter = meterRegistry.find(name).counter();
            return counter == null ? 0.0D : counter.count();
        }

        /**
         * Reads one terminal-diagnostic series by what became of the diagnostic.
         *
         * <p>The two outcomes share one meter name and are told apart by their tag, so a reader of
         * the series learns whether the row that was lost is named anywhere.
         *
         * @param outcome the tag value, published or failed
         * @return the current count of that series, or zero before it is registered
         */
        private double diagnosticCount(String outcome) {
            Counter counter = meterRegistry.find(ObservabilityConfig.DEAD_LETTERS_COUNTER)
                    .tag(ObservabilityConfig.OUTCOME_OF_DIAGNOSTIC_TAG, outcome).counter();
            return counter == null ? 0.0D : counter.count();
        }
    }


    /**
     * Holds what happens to a row this relay gives up on.
     *
     * <p>A row that spends its attempts is an event no consumer will ever see. Before this behaviour
     * existed the tenth attempt wrote the same warning line as the nine before it, the row stopped
     * being claimed, and nothing on a topic or on the metrics endpoint said an event had been dropped:
     * a permanent loss and a transient retry read identically. The other four services of this platform
     * each publish a governed diagnostic at that moment, and these assertions hold this one to the same
     * contract.
     */
    @Nested
    @DisplayName("the terminal outcome of a row this relay gives up on")
    class TheTerminalOutcome {

        /** The attempt count that leaves one attempt before the row is abandoned. */
        private static final int ONE_ATTEMPT_LEFT = OutboxEventEntity.MAX_DELIVERY_ATTEMPTS - 1;

        /**
         * The aggregate identifier a diagnostic declares when the abandoned row names no account.
         *
         * <p>The value is repeated here rather than read from the relay, because a constant a test
         * reads from the class under test asserts nothing about it. {@code OutboxRelay} keeps the
         * same eleven zeros, and {@code config/KafkaConsumerConfig} makes the same substitution on
         * the consumer side.
         */
        private static final String UNRESOLVED_ACCOUNT_KEY = "00000000000";

        @Test
        @DisplayName("a row the broker acknowledged is counted as published")
        void aRowTheBrokerAcknowledgedIsCountedAsPublished() {
            double before = counter(ObservabilityConfig.EVENTS_PUBLISHED_COUNTER);
            storeRow(minutesAgo(1L), ACCEPTED_PAYLOAD);

            relay.publishPendingEvents();

            assertEquals(before + 1.0d, counter(ObservabilityConfig.EVENTS_PUBLISHED_COUNTER),
                    "the publish-success series counts a row the broker took, which the events-written"
                            + " series does not: that one counts a row written to the outbox");
        }

        @Test
        @DisplayName("a row with no attempt left is abandoned and one dead letter names it")
        void aRowWithNoAttemptLeftIsAbandonedAndNamedOnTheDeadLetterTopic() {
            OutboxEventEntity spent = storeRow(minutesAgo(1L), REFUSED_PAYLOAD);
            jdbcTemplate.update("UPDATE outbox_event SET attempt_count = ? WHERE event_id = ?",
                    ONE_ATTEMPT_LEFT, spent.getEventId());
            publisher.refuse(REFUSED_PAYLOAD);
            double abandonedBefore = counter(ObservabilityConfig.OUTBOX_ABANDONED_COUNTER);
            double namedBefore = deadLetters(ObservabilityConfig.DIAGNOSTIC_PUBLISHED);

            relay.publishPendingEvents();

            List<RecordingPublisher.Sent> diagnostics = publisher.all();
            assertEquals(1, diagnostics.size(), "one diagnostic, and no second copy of the event");
            assertEquals(properties.kafka().topics().deadLetter(), diagnostics.getFirst().topic(),
                    "the diagnostic travels on the dead-letter topic");
            assertEquals(APPROVING_ACCOUNT_ID, diagnostics.getFirst().key(),
                    "the diagnostic is keyed on the account the abandoned row named");
            assertFalse(diagnostics.getFirst().payload().contains(REFUSED_PAYLOAD),
                    "the diagnostic carries no payload of the row it names");
            assertEquals(OutboxEventEntity.RelayState.ABANDONED.name(),
                    columnOf("relay_state", String.class, spent.getEventId()),
                    "the row takes no further attempt");
            assertEquals(abandonedBefore + 1.0d,
                    counter(ObservabilityConfig.OUTBOX_ABANDONED_COUNTER),
                    "an abandoned row is counted once, as a row and not as an attempt");
            assertEquals(namedBefore + 1.0d, deadLetters(ObservabilityConfig.DIAGNOSTIC_PUBLISHED),
                    "the diagnostic the broker took is counted under its own outcome");
        }

        /**
         * Proves a row with no account to name still reaches the dead-letter topic.
         *
         * <p>A row written before migration {@code V19} can carry the sixteen-character transaction
         * identifier, which {@code V4} widened {@code aggregate_id} to hold and which no producer
         * writes any more. {@code schemas/dead-letter-v1.json} keys a diagnostic on
         * eleven account digits only, so handing that key to the diagnostic would fail the serialize
         * gate on every pass and the obligation would never clear.
         *
         * <p>The relay substitutes the eleven-zero sentinel, which is an account this platform
         * neither seeds nor issues, so the diagnostic states the absence rather than attributing the
         * loss to an account. {@code failedEventId} still names the row exactly, and
         * {@code outbox_event.aggregate_id} still holds the transaction identifier for anyone
         * reading the row itself. That is the row of {@code docs/decision-log.md} recording this
         * choice against widening the document and against skipping the diagnostic.
         */
        @Test
        @DisplayName("a row keyed on a transaction is abandoned under the eleven-zero sentinel")
        void aRowKeyedOnATransactionIsAbandonedWithNoDiagnostic() {
            String transactionKey = "TRN0000000000001";
            OutboxEventEntity unresolved = storePreMigrationTransactionKeyedRow(transactionKey,
                    REFUSED_PAYLOAD, minutesAgo(1L));
            jdbcTemplate.update("UPDATE outbox_event SET attempt_count = ? WHERE event_id = ?",
                    ONE_ATTEMPT_LEFT, unresolved.getEventId());
            publisher.refuse(REFUSED_PAYLOAD);
            double abandonedBefore = counter(ObservabilityConfig.OUTBOX_ABANDONED_COUNTER);
            double namedBefore = deadLetters(ObservabilityConfig.DIAGNOSTIC_PUBLISHED);

            relay.publishPendingEvents();

            List<RecordingPublisher.Sent> diagnostics = publisher.all();
            assertEquals(1, diagnostics.size(),
                    "one diagnostic, and no second copy of the event it names");
            assertEquals(properties.kafka().topics().deadLetter(), diagnostics.getFirst().topic(),
                    "the diagnostic travels on the dead-letter topic");
            assertEquals(UNRESOLVED_ACCOUNT_KEY, diagnostics.getFirst().key(),
                    "the row names no account, so the diagnostic travels under the sentinel rather"
                            + " than under a transaction identifier the document would refuse");
            assertFalse(diagnostics.getFirst().payload().contains(transactionKey),
                    "and the sentinel is a substitution, not an addition: the transaction key stays"
                            + " on the row and reaches no diagnostic");
            assertTrue(diagnostics.getFirst().payload()
                            .contains(unresolved.getEventId().toString()),
                    "failedEventId is what names the row exactly, which is what the substitution"
                            + " costs nothing an operator needs");
            assertEquals(OutboxEventEntity.RelayState.ABANDONED.name(),
                    columnOf("relay_state", String.class, unresolved.getEventId()),
                    "the row is terminal, so it cannot loop for ever attempting a diagnostic");
            assertEquals(abandonedBefore + 1.0d,
                    counter(ObservabilityConfig.OUTBOX_ABANDONED_COUNTER),
                    "the abandonment is counted");
            assertEquals(namedBefore + 1.0d, deadLetters(ObservabilityConfig.DIAGNOSTIC_PUBLISHED),
                    "and the diagnostic the broker took is counted under its own outcome, so the"
                            + " obligation this row carried is discharged");
        }

        /**
         * Reads one untagged counter of the relay back.
         *
         * @param name the meter name
         * @return the count the counter carries
         */
        private double counter(String name) {
            return meterRegistry.get(name).counter().count();
        }

        /**
         * Reads one terminal-diagnostic series back.
         *
         * @param outcome what became of the diagnostic
         * @return the count that series carries
         */
        private double deadLetters(String outcome) {
            return meterRegistry.get(ObservabilityConfig.DEAD_LETTERS_COUNTER)
                    .tag(ObservabilityConfig.OUTCOME_OF_DIAGNOSTIC_TAG, outcome).counter().count();
        }
    }

    @Nested
    @DisplayName("the document the payload column holds")
    class ThePersistedPayload {

        /**
         * An approval payload holds exactly the properties its contract declares.
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
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);

            JsonNode payload = payloadOf(onlyRowUnder(APPROVING_ACCOUNT_ID).getEventId());

            assertEquals(new TreeSet<>(APPROVAL_PROPERTIES), propertyNamesOf(payload),
                    "the column holds the declared set and nothing beside it");
            assertEquals(APPROVAL_PROPERTIES.size(), payload.size(),
                    "fifteen payload properties beside the five the carrier adds");
        }

        /** Asserts a decline payload holds exactly the twenty properties its contract declares. */
        @Test
        @DisplayName("a decline payload holds exactly the twenty properties its contract declares")
        void aDeclinePayloadHoldsExactlyItsDeclaredProperties() {
            authorizations.authorize(request(DECLINING_CARD_NUMBER, AMOUNT), CALLER);

            JsonNode payload = payloadOf(onlyRowUnder(DECLINING_ACCOUNT_ID).getEventId());

            assertEquals(new TreeSet<>(DECLINE_PROPERTIES), propertyNamesOf(payload),
                    "the column holds the declared set and nothing beside it");
            assertEquals(DECLINE_PROPERTIES.size(), payload.size(),
                    "fifteen payload properties beside the five the carrier adds");
            assertEquals(TransactionDeclined.TRANSACTION_DETAIL_SCHEMA_VERSION,
                    payload.get("schemaVersion").asInt(0),
                    "a decline of a resolved card carries the detail-bearing contract version");
        }

        /**
         * The stored document is flat, with each carrier property at the top level.
         *
         * <p>A payload holding one nested object under a wrapper key would carry six properties and
         * fail its document on the {@code required} array, so it would never reach a topic.
         */
        @Test
        @DisplayName("the stored document is flat, with each carrier property at the top level")
        void theStoredDocumentIsFlat() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);

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
         * Money travels as a decimal string truncated toward zero, on both signs.
         *
         * <p>The {@code ROUNDED} phrase appears zero times across the twenty-eight programs of
         * {@code app/cbl/}, so each arithmetic store truncates. Half-up rounding carries
         * {@code 504.779} up, and truncation toward zero holds a refund at the same magnitude.
         */
        @Test
        @DisplayName("money travels as a decimal string truncated toward zero, on both signs")
        void moneyTravelsAsADecimalStringTruncatedTowardZero() {
            UUID positive = transactionTemplate.execute(status ->
                    outboxWriter.writeAuthorized(approval(new BigDecimal("504.779"))).getEventId());
            UUID negative = transactionTemplate.execute(status ->
                    outboxWriter.writeAuthorized(approval(new BigDecimal("-504.779"))).getEventId());

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
         * The two amount patterns stay apart, and the narrower one governs this event.
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
         * The lookup ran on the full card number and only the masked form travels.
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
                    authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);

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
         * The stored document passes its own contract and the gate refuses anything else.
         *
         * <p>The gate runs before the row is saved, so a payload it refuses never reaches the
         * column, where it would stall the sweep.
         */
        @Test
        @DisplayName("the stored document passes its contract and the gate refuses anything else")
        void theStoredDocumentPassesItsContractAndTheGateRefusesAnythingElse() {
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);
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
         * The message-key column and the replica account column carry different types.
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
         * The tables this service owns resolve unqualified through the configured schema.
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
         * An eleven-digit key keeps its leading zero from the column to the message.
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
            authorizations.authorize(request(APPROVING_CARD_NUMBER, AMOUNT), CALLER);

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
         * The producer is idempotent and waits for each replica.
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
         * A publish fault describes itself inside the abend-record widths.
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
