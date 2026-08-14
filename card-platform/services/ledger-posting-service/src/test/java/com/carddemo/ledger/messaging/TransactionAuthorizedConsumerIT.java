package com.carddemo.ledger.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.ledger.LedgerApplication;
import com.carddemo.ledger.LedgerServiceDatabase;
import com.carddemo.ledger.TestIdentityPasswords;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.entity.ProcessedEventEntity.ProcessedEventId;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import com.carddemo.ledger.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;
import org.springframework.test.context.NestedTestConfiguration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the ledger listener's broker and database guarantees in the real application context.
 *
 * <p>The listener replaces {@code app/jcl/POSTTRAN.jcl:L23} and the abend contract at
 * {@code app/cbl/CBTRN02C.cbl:L707-L711}.
 * <p>The {@code processed_event} guard is ADDITIVE beside the write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579}.
 * <p>The dead-letter topic route is ADDITIVE beside {@code app/jcl/POSTTRAN.jcl:L34-L38}.
 * <p>Processing latency is ADDITIVE beside the counters at
 * {@code app/cbl/CBTRN02C.cbl:L206}, {@code :L214}, and {@code :L227-L230}.
 * <p>The amount follows {@code app/cpy/CVTRA06Y.cpy:L10}. Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Testcontainers
@SpringBootTest(classes = LedgerApplication.class, properties = {
        "KAFKA_SASL_PASSWORD=inert-test-broker-value",
        "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
        "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
        "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
        "spring.jpa.hibernate.ddl-auto=validate"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Ledger authorization listener runtime guarantees")
public class TransactionAuthorizedConsumerIT {

    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";
    private static final String SERVICE_SCHEMA = "ledger_service";
    private static final String BROKER_SECURITY_PROTOCOL = "PLAINTEXT";
    private static final String LISTENER_FACTORY_BEAN = "kafkaListenerContainerFactory";

    private static final String BOOTSTRAP_SERVERS_PROPERTY = "spring.kafka.bootstrap-servers";
    private static final String AUTHORIZED_TOPIC_PROPERTY =
            "carddemo.kafka.topics.transaction-authorized";
    private static final String DEAD_LETTER_SUFFIX_PROPERTY =
            "carddemo.kafka.topics.dead-letter-suffix";
    private static final String CONSUMER_GROUP_PROPERTY = "spring.kafka.consumer.group-id";
    private static final String RETRY_ATTEMPTS_PROPERTY =
            "carddemo.consumer.retry.max-attempts";
    private static final String RETRY_BACKOFF_PROPERTY =
            "carddemo.consumer.retry.backoff-ms";

    private static final String TRANSACTION_TABLE = SERVICE_SCHEMA + ".transaction";
    private static final String CATEGORY_BALANCE_TABLE =
            SERVICE_SCHEMA + ".transaction_category_balance";
    private static final String ACCOUNT_BALANCE_TABLE =
            SERVICE_SCHEMA + ".account_balance_projection";
    private static final String PROCESSED_EVENT_TABLE = SERVICE_SCHEMA + ".processed_event";
    private static final String OUTBOX_EVENT_TABLE = SERVICE_SCHEMA + ".outbox_event";

    private static final String EVENTS_CONSUMED_METER = "carddemo.ledger.events.consumed";
    private static final String TRANSACTION_OUTCOME_METER =
            "carddemo.ledger.transactions.processed";
    private static final String FAILURE_METER = "carddemo.ledger.failures";
    private static final String DEAD_LETTER_METER = "carddemo.ledger.dead.letters";
    private static final String OUTCOME_TAG = "outcome";
    private static final String FAILURE_STAGE_TAG = "stage";
    private static final String FAILURE_KIND_TAG = "failure.kind";
    private static final String REJECTED_OUTCOME = "rejected";
    private static final String DUPLICATE_OUTCOME = "duplicate";
    private static final String PROCESS_STAGE = "process";
    private static final String DESERIALIZE_STAGE = "deserialize";
    private static final String PUBLISHED_OUTCOME = "published";
    private static final String FAILED_OUTCOME = "failed";
    private static final String PROCESSING_KIND = "processing";
    private static final String SCHEMA_VALIDATION_KIND = "schema_validation";

    private static final String ACCOUNT_ID = "00000000007";
    private static final String MISSING_ACCOUNT_ID = "00000000099";
    private static final String TRANSACTION_TYPE_CODE = "01";
    private static final String MERCHANT_CATEGORY_CODE = "0001";
    private static final String MASKED_CARD_NUMBER = "************7065";
    private static final String SYNTHETIC_CARD_TOKEN =
            "a".repeat(TransactionAuthorized.CARD_TOKEN_LENGTH);
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";
    private static final BigDecimal OPENING_BALANCE = new BigDecimal("193.00");
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    private static final int TOPIC_PARTITIONS = 3;
    private static final long ONE_ROW = 1L;
    private static final int ONE_RECORD = 1;
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final Duration FETCH_TIMEOUT = Duration.ofMillis(200);
    private static final Duration SILENCE_WINDOW = Duration.ofSeconds(2);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{12,}");
    private static final Pattern WARNING_OR_ERROR = Pattern.compile(
            "(?i)(\"level\"\\s*:\\s*\"(?:warn|error)\"|\\b(?:warn|error)\\b)");

    private static final FixtureTransaction FIXTURE = new FixtureTransaction(
            "0000000000" + "683580",
            new BigDecimal("504.77"),
            "POS TERM  ",
            "Purchase at Abshire-Lowe",
            "800000000",
            "Abshire-Lowe",
            "North Enoshaven",
            "72112     ");

    private static final String AUTHORIZED_EVENT_TEMPLATE = """
            {
              "eventId": "%s",
              "eventType": "%s",
              "schemaVersion": %d,
              "occurredAt": "%s",
              "aggregateId": "%s",
              "transactionId": "%s",
              "accountId": "%s",
              "transactionTypeCode": "%s",
              "merchantCategoryCode": "%s",
              "source": "%s",
              "description": "%s",
              "amount": "%s",
              "merchantId": "%s",
              "merchantName": "%s",
              "merchantCity": "%s",
              "merchantZip": "%s",
              "maskedCardNumber": "%s",
              "cardToken": "%s",
              "authorizedAt": "%s",
              "currency": "%s"
            }""";

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link LedgerServiceDatabase} owns it and hands this class a database of its own inside it.
     * Nothing here starts or stops a container.
     */
    private static final PostgreSQLContainer POSTGRES = LedgerServiceDatabase.container();

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", Boolean.TRUE.toString())
            .withEnv("KAFKA_NUM_PARTITIONS", Integer.toString(TOPIC_PARTITIONS));

    private final ApplicationContext context;
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final ProcessedEventRepository processedEvents;
    private final AccountBalanceProjectionRepository accountBalances;

    @Autowired
    TransactionAuthorizedConsumerIT(ApplicationContext context, Environment environment,
            JdbcTemplate jdbc, MeterRegistry meters,
            KafkaListenerEndpointRegistry listenerRegistry,
            ProcessedEventRepository processedEvents,
            AccountBalanceProjectionRepository accountBalances) {
        this.context = context;
        this.environment = environment;
        this.jdbc = jdbc;
        this.meters = meters;
        this.listenerRegistry = listenerRegistry;
        this.processedEvents = processedEvents;
        this.accountBalances = accountBalances;
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                TransactionAuthorizedConsumerIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(BOOTSTRAP_SERVERS_PROPERTY, KAFKA::getBootstrapServers);
        registry.add("spring.kafka.security.protocol", () -> BROKER_SECURITY_PROTOCOL);
    }

    private static String jdbcUrlOnServiceSchema() {
        return LedgerServiceDatabase.urlFor(TransactionAuthorizedConsumerIT.class);
    }

    @BeforeEach
    void resetState() {
        String boundBroker = environment.getProperty(BOOTSTRAP_SERVERS_PROPERTY);
        if (!KAFKA.getBootstrapServers().equals(boundBroker)) {
            return;
        }

        Awaitility.await("outbox claims finish before cleanup")
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> rowCount(OUTBOX_EVENT_TABLE
                        + " WHERE relay_state = 'CLAIMED'") == 0L);

        jdbc.update("DELETE FROM " + PROCESSED_EVENT_TABLE);
        jdbc.update("DELETE FROM " + TRANSACTION_TABLE);
        jdbc.update("DELETE FROM " + OUTBOX_EVENT_TABLE);
        jdbc.update("DELETE FROM " + CATEGORY_BALANCE_TABLE + " WHERE account_id = ?",
                MISSING_ACCOUNT_ID);
        jdbc.update("DELETE FROM " + ACCOUNT_BALANCE_TABLE + " WHERE account_id = ?",
                MISSING_ACCOUNT_ID);

        accountBalances.save(new AccountBalanceProjectionEntity(
                ACCOUNT_ID, OPENING_BALANCE, ZERO_AMOUNT, ZERO_AMOUNT));
        jdbc.update("""
                INSERT INTO %s (account_id, type_code, category_code, category_balance)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (account_id, type_code, category_code)
                DO UPDATE SET category_balance = EXCLUDED.category_balance
                """.formatted(CATEGORY_BALANCE_TABLE),
                ACCOUNT_ID, TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE, ZERO_AMOUNT);
    }

    /**
     * Checks the application factory, the bound consumer group, and the source account key.
     * Source: {@code app/cpy/CVACT03Y.cpy:L7} and {@code app/jcl/POSTTRAN.jcl:L23}.
     */
    @Nested
    @DisplayName("Container binding and source key")
    class ContainerBinding {

        /** Asserts the listener and key at {@code app/cpy/CVACT03Y.cpy:L7}. */
        @Test
        @DisplayName("the application factory runs the bound group and preserves the account key")
        void applicationFactoryRunsBoundGroupAndPreservesAccountKey() {
            MessageListenerContainer listener = transactionListener();
            String expectedGroup = requiredProperty(CONSUMER_GROUP_PROPERTY);
            String authorizedTopic = authorizedTopic();

            assertAll("listener binding",
                    () -> assertTrue(context.containsBean(LISTENER_FACTORY_BEAN),
                            LISTENER_FACTORY_BEAN + " is present"),
                    () -> assertTrue(listener.isRunning(), "the transaction listener is running"),
                    () -> assertEquals(expectedGroup, listener.getGroupId(),
                            "the listener group comes from " + CONSUMER_GROUP_PROPERTY),
                    () -> assertTrue(listener.getContainerProperties().getAckMode()
                                    .name().startsWith("MANUAL"),
                            "the listener waits for an explicit acknowledgement"));

            UUID eventId = UUID.randomUUID();
            try (KafkaConsumer<String, String> probe = assignedToEndOf(authorizedTopic);
                    KafkaProducer<String, String> producer = newProducer()) {
                publish(producer, authorizedTopic, ACCOUNT_ID,
                        authorizedEvent(eventId, ACCOUNT_ID));
                ConsumerRecord<String, String> record = awaitRecord(
                        probe, carryingEventId(eventId), "authorized event arrives");
                awaitApplied(eventId);

                assertAll("source message key",
                        () -> assertEquals(ACCOUNT_ID, record.key(),
                                "the record key keeps the account identifier"),
                        () -> assertEquals(ACCOUNT_ID.length(), record.key().length(),
                                "the record key keeps its fixed width"),
                        () -> assertTrue(record.key().startsWith("0"),
                                "the record key keeps its leading zero"));
            }
        }
    }

    /**
     * Checks the ADDITIVE marker against the source's write-only duplicate baseline.
     * Source: {@code app/cbl/CBTRN02C.cbl:L562-L579} and counters at {@code :L206-L230}.
     */
    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("Duplicate delivery")
    class DuplicateSuppression {

        /** Asserts replay safety against {@code app/cbl/CBTRN02C.cbl:L562-L579}. */
        @Test
        @DisplayName("a repeated event keeps rows and balance fixed without failure")
        void repeatedEventKeepsRowsAndBalanceFixed(CapturedOutput output) {
            UUID eventId = UUID.randomUUID();
            String authorizedTopic = authorizedTopic();
            String deadLetterTopic = deadLetterTopic();

            Counter declineCounter =
                    counter(TRANSACTION_OUTCOME_METER, OUTCOME_TAG, REJECTED_OUTCOME);
            Counter failureCounter = counter(FAILURE_METER, FAILURE_STAGE_TAG, PROCESS_STAGE);
            long failuresBefore = counterValue(failureCounter);
            long declinesBefore = counterValue(declineCounter);

            try (KafkaConsumer<String, String> deadLetters = assignedToEndOf(deadLetterTopic);
                    KafkaProducer<String, String> producer = newProducer()) {
                String document = authorizedEvent(eventId, ACCOUNT_ID);
                publish(producer, authorizedTopic, ACCOUNT_ID, document);
                awaitApplied(eventId);

                BalanceSnapshot balanceAfterFirst = balanceSnapshot(ACCOUNT_ID);
                long transactionsAfterFirst = transactionCount(FIXTURE.transactionId());
                long markersAfterFirst = markerCount(eventId);
                long duplicatesBefore = counterValue(
                        counter(TRANSACTION_OUTCOME_METER, OUTCOME_TAG, DUPLICATE_OUTCOME));

                publish(producer, authorizedTopic, ACCOUNT_ID, document);
                Awaitility.await("duplicate marker suppresses the repeated event")
                        .atMost(ARRIVAL_TIMEOUT)
                        .pollInterval(POLL_INTERVAL)
                        .until(() -> counterValue(counter(
                                TRANSACTION_OUTCOME_METER, OUTCOME_TAG, DUPLICATE_OUTCOME))
                                == duplicatesBefore + 1L);

                assertAll("duplicate invariants",
                        () -> assertEquals(balanceAfterFirst, balanceSnapshot(ACCOUNT_ID),
                                "the balance projection stays fixed"),
                        () -> assertEquals(transactionsAfterFirst,
                                transactionCount(FIXTURE.transactionId()),
                                "the transaction row count stays fixed"),
                        () -> assertEquals(markersAfterFirst, markerCount(eventId),
                                "the marker row count stays fixed"),
                        () -> assertEquals(ONE_ROW, transactionsAfterFirst,
                                "the first record wrote one transaction"),
                        () -> assertEquals(ONE_ROW, markersAfterFirst,
                                "the first record wrote one marker"),
                        () -> assertEquals(failuresBefore, counterValue(failureCounter),
                                "the process failure counter stays fixed"),
                        () -> assertEquals(declinesBefore, counterValue(declineCounter),
                                "the decline counter stays fixed"),
                        () -> assertNotEquals(declineCounter.getId(), failureCounter.getId(),
                                "declines and failures remain separate meter series"));

                assertNoRecord(deadLetters, "the duplicate reaches no dead-letter topic record");
                assertNoWarningOrError(output, eventId);
            }
        }

        /**
         * Asserts a claim written long ago still refuses its redelivery, because nothing expires one.
         *
         * <p>This service once deleted a claim 720 hours after it was written, checked at start-up
         * against twice the broker's own log retention. That relationship bounds how long the broker
         * can redeliver a record and nothing else. A record archived, restored from a backup or
         * deliberately replayed after the horizon reached a guard that had forgotten it, and the
         * posting applied a second time: a transaction row, a category balance and the account
         * balance projection all moved again.
         *
         * <p>The claim below is stamped two thousand days back, which is further past that horizon
         * than any demo volume would ever reach. The redelivery still finds it and still changes
         * nothing, which is the property {@code V11__processed_event_claims_are_permanent.sql} states
         * in the catalogue.
         */
        @Test
        @DisplayName("a claim written two thousand days ago still refuses its redelivery")
        void anAncientClaimStillRefusesItsRedelivery() {
            UUID eventId = UUID.randomUUID();
            Instant longBefore = Instant.now().minus(Duration.ofDays(2000))
                    .truncatedTo(ChronoUnit.MILLIS);
            jdbc.update("INSERT INTO " + PROCESSED_EVENT_TABLE
                            + " (event_id, processed_at, consumed_topic) VALUES (?, ?, ?)",
                    eventId, OffsetDateTime.ofInstant(longBefore, ZoneOffset.UTC),
                    authorizedTopic());

            BalanceSnapshot balanceBefore = balanceSnapshot(ACCOUNT_ID);
            BigDecimal categoryBefore = categoryBalance(ACCOUNT_ID);
            long duplicatesBefore = counterValue(
                    counter(TRANSACTION_OUTCOME_METER, OUTCOME_TAG, DUPLICATE_OUTCOME));

            try (KafkaProducer<String, String> producer = newProducer()) {
                publish(producer, authorizedTopic(), ACCOUNT_ID,
                        authorizedEvent(eventId, ACCOUNT_ID));
                Awaitility.await("the ancient claim suppresses the delivery")
                        .atMost(ARRIVAL_TIMEOUT)
                        .pollInterval(POLL_INTERVAL)
                        .until(() -> counterValue(counter(
                                TRANSACTION_OUTCOME_METER, OUTCOME_TAG, DUPLICATE_OUTCOME))
                                == duplicatesBefore + 1L);
            }

            assertAll("a claim outlives every horizon this service ever carried",
                    () -> assertEquals(0L, transactionCount(FIXTURE.transactionId()),
                            "no transaction row is written"),
                    () -> assertEquals(balanceBefore, balanceSnapshot(ACCOUNT_ID),
                            "the balance projection stays fixed"),
                    () -> assertEquals(categoryBefore, categoryBalance(ACCOUNT_ID),
                            "the category balance stays fixed"),
                    () -> assertEquals(ONE_ROW, markerCount(eventId),
                            "the ancient claim is still the only claim for the event"),
                    () -> assertEquals(longBefore, claimedAt(eventId),
                            "nothing rewrote the claim, so its own stamp is untouched"));
        }
    }

    /**
     * Checks the unguarded source update as a retryable store failure.
     * Source: {@code app/cbl/CBTRN02C.cbl:L545-L560}, {@code :L556}, and {@code :L707-L711}.
     */
    @Nested
    @DisplayName("Retry and dead-letter route")
    class RetryThenDeadLetter {

        /** Asserts retry and ADDITIVE routing beside {@code app/jcl/POSTTRAN.jcl:L34-L38}. */
        @Test
        @DisplayName("a missing account retries to the configured total then reaches the dead-letter topic")
        void missingAccountRetriesThenReachesDeadLetterTopic() {
            UUID eventId = UUID.randomUUID();
            int expectedAttempts = expectedAttempts();
            Duration retryBackoff = retryBackoff();
            long consumedBefore = counterValue(counter(EVENTS_CONSUMED_METER));
            long failuresBefore =
                    counterValue(counter(FAILURE_METER, FAILURE_STAGE_TAG, PROCESS_STAGE));
            long publishedBefore = deadLetters(PUBLISHED_OUTCOME);
            long refusedBefore = deadLetters(FAILED_OUTCOME);
            long attributedBefore = deadLetters(PUBLISHED_OUTCOME, PROCESSING_KIND);

            try (KafkaConsumer<String, String> deadLetters = assignedToEndOf(deadLetterTopic());
                    KafkaProducer<String, String> producer = newProducer()) {
                Instant startedAt = Instant.now();
                publish(producer, authorizedTopic(), MISSING_ACCOUNT_ID,
                        authorizedEvent(eventId, MISSING_ACCOUNT_ID));
                ConsumerRecord<String, String> deadLetter = awaitRecord(
                        deadLetters, record -> true, "retryable failure reaches dead-letter topic");
                Duration elapsed = Duration.between(startedAt, Instant.now());

                awaitDeadLetters(PUBLISHED_OUTCOME, publishedBefore + 1L);
                Awaitility.await("configured attempts finish")
                        .atMost(ARRIVAL_TIMEOUT)
                        .pollInterval(POLL_INTERVAL)
                        .until(() -> counterValue(counter(EVENTS_CONSUMED_METER))
                                == consumedBefore + expectedAttempts);

                Duration minimumElapsed =
                        retryBackoff.multipliedBy(expectedAttempts - 1L);
                assertAll("retry route",
                        () -> assertEquals((long) expectedAttempts,
                                counterValue(counter(EVENTS_CONSUMED_METER)) - consumedBefore,
                                "the listener saw the configured total attempt count"),
                        () -> assertEquals((long) expectedAttempts,
                                counterValue(counter(
                                        FAILURE_METER, FAILURE_STAGE_TAG, PROCESS_STAGE))
                                        - failuresBefore,
                                "each failed attempt reached the process failure counter"),
                        () -> assertEquals(publishedBefore + 1L,
                                deadLetters(PUBLISHED_OUTCOME),
                                "one terminal diagnostic reached the broker"),
                        () -> assertEquals(attributedBefore + 1L,
                                deadLetters(PUBLISHED_OUTCOME, PROCESSING_KIND),
                                "and it is filed under what it failed at, so the terminal series"
                                        + " separates a listener that raised from a payload no"
                                        + " deserializer would read"),
                        () -> assertEquals(refusedBefore,
                                deadLetters(FAILED_OUTCOME),
                                "the dead-letter publication failure counter stays fixed"),
                        () -> assertTrue(elapsed.compareTo(minimumElapsed) >= 0,
                                "elapsed time covers each configured retry interval"));

                assertNoLongDigitRun(deadLetter);
                assertNoRecord(deadLetters, "the failed source record creates one diagnostic");
            }
        }

        /** Asserts no partial row from {@code app/cbl/CBTRN02C.cbl:L545-L560}. */
        @Test
        @DisplayName("a failed posting leaves no partial transaction state")
        void failedPostingLeavesNoPartialTransactionState() {
            UUID eventId = UUID.randomUUID();
            long publishedBefore = deadLetters(PUBLISHED_OUTCOME);

            try (KafkaConsumer<String, String> deadLetters = assignedToEndOf(deadLetterTopic());
                    KafkaProducer<String, String> producer = newProducer()) {
                publish(producer, authorizedTopic(), MISSING_ACCOUNT_ID,
                        authorizedEvent(eventId, MISSING_ACCOUNT_ID));
                ConsumerRecord<String, String> deadLetter = awaitRecord(
                        deadLetters, record -> true, "failed posting reaches dead-letter topic");
                awaitDeadLetters(PUBLISHED_OUTCOME, publishedBefore + 1L);

                assertAll("rolled-back posting",
                        () -> assertEquals(0L, transactionCount(FIXTURE.transactionId()),
                                "no transaction row survives"),
                        () -> assertEquals(0L, categoryCount(MISSING_ACCOUNT_ID),
                                "no category-balance row survives"),
                        () -> assertEquals(0L, markerCount(eventId),
                                "no processed-event marker survives"),
                        () -> assertEquals(0L, outboxCount(MISSING_ACCOUNT_ID),
                                "no outbox row survives"),
                        () -> assertTrue(accountBalances.findById(MISSING_ACCOUNT_ID).isEmpty(),
                                "the absent balance projection remains absent"));

                assertNoLongDigitRun(deadLetter);
            }
        }
    }

    /**
     * Checks the schema refusal before the listener can enter the posting path.
     * Source: {@code app/cbl/CBTRN02C.cbl:L714-L727} and {@code :L707-L711}.
     */
    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("Schema refusal")
    class SchemaRefusal {

        /** Asserts refusal beside {@code app/cbl/CBTRN02C.cbl:L714-L727}. */
        @Test
        @DisplayName("a schema refusal reaches the dead-letter topic before the listener runs")
        void schemaRefusalRunsNoBusinessPath(CapturedOutput output) {
            UUID eventId = UUID.randomUUID();
            BalanceSnapshot balanceBefore = balanceSnapshot(ACCOUNT_ID);
            BigDecimal categoryBefore = categoryBalance(ACCOUNT_ID);
            long consumedBefore = counterValue(counter(EVENTS_CONSUMED_METER));
            long failuresBefore =
                    counterValue(counter(FAILURE_METER, FAILURE_STAGE_TAG, PROCESS_STAGE));
            long refusalsBefore =
                    counterValue(counter(FAILURE_METER, FAILURE_STAGE_TAG, DESERIALIZE_STAGE));
            long publishedBefore = deadLetters(PUBLISHED_OUTCOME);
            long attributedBefore = deadLetters(PUBLISHED_OUTCOME, SCHEMA_VALIDATION_KIND);

            try (KafkaConsumer<String, String> deadLetters = assignedToEndOf(deadLetterTopic());
                    KafkaProducer<String, String> producer = newProducer()) {
                publish(producer, authorizedTopic(), ACCOUNT_ID,
                        schemaViolatingEvent(eventId, ACCOUNT_ID));
                ConsumerRecord<String, String> deadLetter = awaitRecord(
                        deadLetters, record -> true, "schema refusal reaches dead-letter topic");
                awaitDeadLetters(PUBLISHED_OUTCOME, publishedBefore + 1L);

                assertAll("schema refusal",
                        () -> assertEquals(consumedBefore,
                                counterValue(counter(EVENTS_CONSUMED_METER)),
                                "the listener consumed-event counter stays fixed"),
                        () -> assertEquals(failuresBefore,
                                counterValue(counter(
                                        FAILURE_METER, FAILURE_STAGE_TAG, PROCESS_STAGE)),
                                "the process failure counter stays fixed"),
                        () -> assertEquals(refusalsBefore + 1L,
                                counterValue(counter(
                                        FAILURE_METER, FAILURE_STAGE_TAG, DESERIALIZE_STAGE)),
                                "and the refusal is counted at the stage it happened at, which is"
                                        + " the only stage a refused payload ever reaches"),
                        () -> assertEquals(attributedBefore + 1L,
                                deadLetters(PUBLISHED_OUTCOME, SCHEMA_VALIDATION_KIND),
                                "the terminal record names the schema control that refused it"),
                        () -> assertEquals(0L, transactionCount(FIXTURE.transactionId()),
                                "no transaction row is written"),
                        () -> assertEquals(0L, markerCount(eventId),
                                "no processed-event marker is written"),
                        () -> assertEquals(0L, outboxCount(ACCOUNT_ID),
                                "no outbox row is written"),
                        () -> assertEquals(categoryBefore, categoryBalance(ACCOUNT_ID),
                                "the category balance stays fixed"),
                        () -> assertEquals(balanceBefore, balanceSnapshot(ACCOUNT_ID),
                                "the account balance stays fixed"));

                assertNoLongDigitRun(deadLetter);
                assertNoRecord(deadLetters, "one schema refusal creates one diagnostic");
                assertNoRetryLog(output);
            }
        }
    }

    /**
     * Checks startup and the local health endpoint while no broker answers.
     * Source: {@code app/jcl/POSTTRAN.jcl:L23} and {@code app/cbl/CBTRN02C.cbl:L707-L711}.
     */
    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)
    @Testcontainers
    @SpringBootTest(classes = LedgerApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "KAFKA_SASL_PASSWORD=inert-test-broker-value",
                    "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                    "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                    "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                    "spring.kafka.security.protocol=PLAINTEXT",
                    "spring.jpa.hibernate.ddl-auto=validate",
                    "management.server.port=${server.port}"
            })
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    @DisplayName("Broker-unreachable startup")
    class BrokerUnreachableStartup {

        private static final String UNREACHABLE_BROKER = "192.0.2.1:9092";

        /**
         * The one container the module fork runs, which this group reads a login from.
         *
         * <p>Only the broker is unreachable in this group. The database is the module's shared
         * container, and {@link LedgerServiceDatabase} hands this group a database of its own inside
         * it.
         */
        private static final PostgreSQLContainer UNREACHABLE_POSTGRES =
                LedgerServiceDatabase.container();

        @Autowired
        private ConfigurableApplicationContext brokerlessContext;

        @Autowired
        private Environment brokerlessEnvironment;

        @LocalServerPort
        private int serverPort;

        @DynamicPropertySource
        static void brokerlessProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url",
                    BrokerUnreachableStartup::brokerlessJdbcUrl);
            registry.add("spring.datasource.username", UNREACHABLE_POSTGRES::getUsername);
            registry.add("spring.datasource.password", UNREACHABLE_POSTGRES::getPassword);
            registry.add(BOOTSTRAP_SERVERS_PROPERTY, () -> UNREACHABLE_BROKER);
        }

        private static String brokerlessJdbcUrl() {
            return LedgerServiceDatabase.urlFor(BrokerUnreachableStartup.class);
        }

        /** Asserts startup replacing {@code app/cbl/CBTRN02C.cbl:L707-L711}. */
        @Test
        @DisplayName("the health endpoint responds while the broker address is unreachable")
        void healthEndpointRespondsWithBrokerUnreachable() {
            HttpResponse<String> response = healthResponse(serverPort);

            assertAll("broker-unreachable context",
                    () -> assertTrue(brokerlessContext.isActive(),
                            "the application context is active"),
                    () -> assertTrue(brokerlessContext.isRunning(),
                            "the application context is running"),
                    () -> assertEquals(UNREACHABLE_BROKER,
                            brokerlessEnvironment.getProperty(BOOTSTRAP_SERVERS_PROPERTY),
                            "the context uses the reserved broker address"),
                    () -> assertTrue(response.statusCode() >= 200
                                    && response.statusCode() < 600,
                            "the health endpoint returned an HTTP status"),
                    () -> assertFalse(response.body().isBlank(),
                            "the health endpoint returned a body"),
                    () -> assertTrue(response.body().contains("\"status\""),
                            "the health response carries its status"));
        }
    }

    private MessageListenerContainer transactionListener() {
        String group = requiredProperty(CONSUMER_GROUP_PROPERTY);
        String topic = authorizedTopic();
        List<MessageListenerContainer> matching = listenerRegistry.getListenerContainers().stream()
                .filter(container -> group.equals(container.getGroupId()))
                .filter(container -> readsTopic(container, topic))
                .toList();
        assertEquals(ONE_RECORD, matching.size(),
                () -> "one listener reads " + topic + " under the bound group");
        return matching.getFirst();
    }

    private static boolean readsTopic(MessageListenerContainer container, String topic) {
        String[] topics = container.getContainerProperties().getTopics();
        return topics != null && Arrays.asList(topics).contains(topic);
    }

    private String authorizedTopic() {
        return requiredProperty(AUTHORIZED_TOPIC_PROPERTY);
    }

    private String deadLetterTopic() {
        return authorizedTopic() + requiredProperty(DEAD_LETTER_SUFFIX_PROPERTY);
    }

    private String requiredProperty(String name) {
        String value = environment.getRequiredProperty(name);
        assertFalse(value.isBlank(), name + " carries a value");
        return value;
    }

    private int expectedAttempts() {
        Integer attempts = environment.getRequiredProperty(RETRY_ATTEMPTS_PROPERTY, Integer.class);
        assertNotNull(attempts, RETRY_ATTEMPTS_PROPERTY + " resolves");
        assertTrue(attempts > 1, RETRY_ATTEMPTS_PROPERTY + " includes a retry");
        return attempts;
    }

    private Duration retryBackoff() {
        Long backoff = environment.getRequiredProperty(RETRY_BACKOFF_PROPERTY, Long.class);
        assertNotNull(backoff, RETRY_BACKOFF_PROPERTY + " resolves");
        assertTrue(backoff > 0L, RETRY_BACKOFF_PROPERTY + " is positive");
        return Duration.ofMillis(backoff);
    }

    private void awaitApplied(UUID eventId) {
        ProcessedEventId markerKey = new ProcessedEventId(eventId, authorizedTopic());
        Awaitility.await("transaction and marker commit")
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> transactionCount(FIXTURE.transactionId()) == ONE_ROW
                        && processedEvents.existsById(markerKey));
        assertAll("committed source event",
                () -> assertEquals(ONE_ROW, transactionCount(FIXTURE.transactionId()),
                        "one transaction row exists"),
                () -> assertTrue(processedEvents.existsById(markerKey),
                        "the processed-event marker exists, keyed on the event and the topic the"
                                + " delivery arrived on"));
    }

    private long transactionCount(String transactionId) {
        return rowCount(TRANSACTION_TABLE + " WHERE transaction_id = ?", transactionId);
    }

    private long markerCount(UUID eventId) {
        return rowCount(PROCESSED_EVENT_TABLE + " WHERE event_id = ?", eventId);
    }

    /**
     * Reads back the instant a claim records, so a test can prove nothing rewrote it.
     *
     * @param eventId the claimed event identifier
     * @return the stamp the claim row carries
     */
    private Instant claimedAt(UUID eventId) {
        OffsetDateTime stamp = jdbc.queryForObject("SELECT processed_at FROM "
                + PROCESSED_EVENT_TABLE + " WHERE event_id = ?", OffsetDateTime.class, eventId);
        return Objects.requireNonNull(stamp, "claim stamp").toInstant();
    }

    private long categoryCount(String accountId) {
        return rowCount(CATEGORY_BALANCE_TABLE
                        + " WHERE account_id = ? AND type_code = ? AND category_code = ?",
                accountId, TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE);
    }

    private long outboxCount(String accountId) {
        return rowCount(OUTBOX_EVENT_TABLE + " WHERE aggregate_id = ?", accountId);
    }

    private long rowCount(String tableAndPredicate, Object... arguments) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM " + tableAndPredicate, Long.class, arguments);
        return count == null ? 0L : count;
    }

    private BigDecimal categoryBalance(String accountId) {
        BigDecimal balance = jdbc.queryForObject(
                "SELECT category_balance FROM " + CATEGORY_BALANCE_TABLE
                        + " WHERE account_id = ? AND type_code = ? AND category_code = ?",
                BigDecimal.class, accountId, TRANSACTION_TYPE_CODE, MERCHANT_CATEGORY_CODE);
        return Objects.requireNonNull(balance, "category balance");
    }

    private BalanceSnapshot balanceSnapshot(String accountId) {
        AccountBalanceProjectionEntity row = accountBalances.findById(accountId)
                .orElseThrow(() -> new AssertionError(
                        "account balance projection is absent for " + accountId));
        return new BalanceSnapshot(
                row.getCurrentBalance(),
                row.getCycleCredit(),
                row.getCycleDebit(),
                row.getSourceEventId(),
                row.getSourceOccurredAt());
    }

    private Counter counter(String name) {
        return meters.get(name).counter();
    }

    private Counter counter(String name, String tag, String value) {
        return meters.get(name).tag(tag, value).counter();
    }

    private static long counterValue(Counter counter) {
        return Math.round(counter.count());
    }

    private void awaitCounter(String name, String tag, String value, long expected) {
        Awaitility.await(name + " reaches " + expected)
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> counterValue(counter(name, tag, value)) == expected);
    }

    /**
     * Totals one outcome of the terminal dead-letter counter across every failure kind.
     *
     * <p>That counter carries a failure kind beside the outcome, so one outcome names a series per
     * kind rather than a single series. A reader that asked for one series would read whichever
     * kind it happened to match, which is why every total here is a sum.
     *
     * @param outcome the terminal outcome to total
     * @return the number of records counted under that outcome, whatever they failed at
     */
    private long deadLetters(String outcome) {
        double total = 0.0D;
        for (Counter series : meters.find(DEAD_LETTER_METER).tag(OUTCOME_TAG, outcome).counters()) {
            total += series.count();
        }
        return Math.round(total);
    }

    /**
     * Reads one outcome of the terminal dead-letter counter for one failure kind.
     *
     * @param outcome the terminal outcome
     * @param kind    the value of the failure kind tag
     * @return the count that series carries, or zero where it is absent
     */
    private long deadLetters(String outcome, String kind) {
        Counter series = meters.find(DEAD_LETTER_METER)
                .tag(OUTCOME_TAG, outcome)
                .tag(FAILURE_KIND_TAG, kind)
                .counter();
        return series == null ? 0L : Math.round(series.count());
    }

    /** Waits until one outcome of the terminal counter, summed over every kind, reads a value. */
    private void awaitDeadLetters(String outcome, long expected) {
        Awaitility.await(DEAD_LETTER_METER + " " + outcome + " reaches " + expected)
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> deadLetters(outcome) == expected);
    }

    private static String authorizedEvent(UUID eventId, String accountId) {
        return AUTHORIZED_EVENT_TEMPLATE.formatted(
                eventId,
                TransactionAuthorized.EVENT_TYPE,
                TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION,
                Instant.now(),
                accountId,
                FIXTURE.transactionId(),
                accountId,
                TRANSACTION_TYPE_CODE,
                MERCHANT_CATEGORY_CODE,
                FIXTURE.source(),
                FIXTURE.description(),
                FIXTURE.amount().toPlainString(),
                FIXTURE.merchantId(),
                FIXTURE.merchantName(),
                FIXTURE.merchantCity(),
                FIXTURE.merchantZip(),
                MASKED_CARD_NUMBER,
                SYNTHETIC_CARD_TOKEN,
                AUTHORIZED_AT,
                TransactionAuthorized.CURRENCY);
    }

    private static String schemaViolatingEvent(UUID eventId, String accountId) {
        String versionTwo = authorizedEvent(eventId, accountId);
        String tokenProperty = "  \"cardToken\": \"" + SYNTHETIC_CARD_TOKEN + "\",\n";
        String versionTwoMarker = "\"schemaVersion\": "
                + TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION;
        String versionOneMarker = "\"schemaVersion\": " + EventEnvelope.SCHEMA_VERSION;
        String valid = versionTwo
                .replace(tokenProperty, "")
                .replace(versionTwoMarker, versionOneMarker);
        String quotedAmount = "\"amount\": \"" + FIXTURE.amount().toPlainString() + "\"";
        String numericAmount = "\"amount\": " + FIXTURE.amount().toPlainString();
        String malformed = valid.replace(quotedAmount, numericAmount);
        assertAll("schema-invalid document",
                () -> assertFalse(valid.contains("\"cardToken\""),
                        "the version-one document carries nineteen properties"),
                () -> assertNotEquals(valid, malformed,
                        "the malformed document changes the amount type"));
        return malformed;
    }

    private static Predicate<ConsumerRecord<String, String>> carryingEventId(UUID eventId) {
        String fragment = "\"eventId\": \"" + eventId + "\"";
        return record -> record.value() != null && record.value().contains(fragment);
    }

    private static void publish(KafkaProducer<String, String> producer, String topic,
            String accountId, String document) {
        try {
            Future<RecordMetadata> pending =
                    producer.send(new ProducerRecord<>(topic, accountId, document));
            producer.flush();
            RecordMetadata metadata =
                    pending.get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(metadata, "the broker returned record coordinates");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("publishing to " + topic + " was interrupted", interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("the broker refused a record on " + topic, failed);
        } catch (TimeoutException timedOut) {
            throw new AssertionError("the broker did not accept a record within "
                    + ARRIVAL_TIMEOUT, timedOut);
        }
    }

    private static KafkaProducer<String, String> newProducer() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ProducerConfig.ACKS_CONFIG, "all");
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE);
        settings.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, ARRIVAL_TIMEOUT.toMillis());
        return new KafkaProducer<>(settings, new StringSerializer(), new StringSerializer());
    }

    private static KafkaConsumer<String, String> newConsumer() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, "ledger-consumer-it-" + UUID.randomUUID());
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE);
        settings.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                Math.toIntExact(ARRIVAL_TIMEOUT.toMillis()));
        return new KafkaConsumer<>(settings, new StringDeserializer(), new StringDeserializer());
    }

    private static KafkaConsumer<String, String> assignedToEndOf(String topic) {
        KafkaConsumer<String, String> consumer = newConsumer();
        Awaitility.await(topic + " exposes partitions")
                .pollInSameThread()
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> !consumer.partitionsFor(topic).isEmpty());
        List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
        assertFalse(partitions.isEmpty(), topic + " has partitions");
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    private static ConsumerRecord<String, String> awaitRecord(
            KafkaConsumer<String, String> consumer,
            Predicate<ConsumerRecord<String, String>> wanted,
            String subject) {
        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        Awaitility.await(subject)
                .pollInSameThread()
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    for (ConsumerRecord<String, String> record : consumer.poll(FETCH_TIMEOUT)) {
                        if (wanted.test(record)) {
                            matched.add(record);
                        }
                    }
                    return !matched.isEmpty();
                });
        assertEquals(ONE_RECORD, matched.size(), subject);
        return matched.getFirst();
    }

    private static void assertNoRecord(KafkaConsumer<String, String> consumer, String subject) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        for (ConsumerRecord<String, String> record : consumer.poll(SILENCE_WINDOW)) {
            records.add(record);
        }
        assertTrue(records.isEmpty(), subject);
    }

    private static void assertNoLongDigitRun(ConsumerRecord<String, String> record) {
        List<String> values = new ArrayList<>();
        values.add(record.topic());
        values.add(record.key());
        values.add(record.value());
        for (Header header : record.headers()) {
            values.add(header.key());
            if (header.value() != null) {
                values.add(new String(header.value(), StandardCharsets.UTF_8));
            }
        }
        long violations = values.stream()
                .filter(Objects::nonNull)
                .filter(value -> LONG_DIGIT_RUN.matcher(value).find())
                .count();
        assertEquals(0L, violations,
                "the dead-letter payload and metadata carry no long digit run");
    }

    private static void assertNoWarningOrError(CapturedOutput output, UUID eventId) {
        long elevatedLines = output.getAll().lines()
                .filter(line -> line.contains(eventId.toString()))
                .filter(line -> WARNING_OR_ERROR.matcher(line).find())
                .count();
        assertEquals(0L, elevatedLines,
                "the duplicate event appears in no warning or error line");
    }

    private static void assertNoRetryLog(CapturedOutput output) {
        long retryLines = output.getAll().lines()
                .filter(line -> line.contains("Record in retry and not yet recovered"))
                .count();
        assertEquals(0L, retryLines, "the schema refusal enters no retry interval");
    }

    private static HttpResponse<String> healthResponse(int serverPort) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HEALTH_TIMEOUT)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + serverPort + "/actuator/health"))
                .timeout(HEALTH_TIMEOUT)
                .GET()
                .build();
        try {
            return client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("the health request was interrupted", interrupted);
        } catch (IOException failed) {
            throw new AssertionError("the health endpoint did not respond", failed);
        }
    }

    private record BalanceSnapshot(
            BigDecimal currentBalance,
            BigDecimal cycleCredit,
            BigDecimal cycleDebit,
            UUID sourceEventId,
            Instant sourceOccurredAt) {
    }

    private record FixtureTransaction(
            String transactionId,
            BigDecimal amount,
            String source,
            String description,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip) {
    }
}
