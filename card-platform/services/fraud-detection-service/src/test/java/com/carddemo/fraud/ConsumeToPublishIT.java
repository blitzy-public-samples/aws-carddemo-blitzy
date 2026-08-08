package com.carddemo.fraud;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.fraud.config.KafkaProducerConfig;
import com.carddemo.fraud.outbox.OutboxRelay;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asserts that one consumed authorization event leaves one assessment row, one published outbox row
 * and one event on the assessed topic.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. Searching {@code app/cbl/} for {@code fraud},
 * {@code velocit}, {@code risk} and {@code scoring} matches zero of its 28 programs.
 *
 * <p>One PostgreSQL container and one Kafka Raft (KRaft) broker container carry the run. Every
 * document travels as JavaScript Object Notation (JSON) text through clients this class builds, so
 * no assertion reads an event record type. The four values in the {@code @SpringBootTest} annotation
 * stand in for credential variables the shipped configuration leaves undefined, and each is inert.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Testcontainers
@SpringBootTest(properties = {
        "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
        "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
        "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
        "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("One consumed authorization event publishes one assessment event")
public class ConsumeToPublishIT {

    /** Image tag of the database container. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** Image tag of the broker container, matching the pinned client library. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";

    /** Database name, login name and password of the database container, one value for all three. */
    private static final String CONTAINER_CREDENTIAL = "carddemo";

    /** Schema the shipped configuration migrates and the persistence layer qualifies entities with. */
    private static final String SERVICE_SCHEMA = "fraud_service";

    /** Security protocol the broker container's listener accepts, and the fifth property override. */
    private static final String BROKER_SECURITY_PROTOCOL = "PLAINTEXT";

    /** Topic the service reads. */
    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    /** Topic both assessment outcomes are published to. */
    private static final String ASSESSED_TOPIC = "fraud.assessed";

    /** Consumer group this service reads under. */
    private static final String FRAUD_CONSUMER_GROUP = "fraud-detection";

    /** Consumer group the ledger posting service reads the same topic under. */
    private static final String LEDGER_CONSUMER_GROUP = "ledger-posting";

    /** Bean name of the template the outbox relay publishes through. */
    private static final String OUTBOX_TEMPLATE_BEAN = "fraudEventKafkaTemplate";

    /** Bean name of the template the dead-letter route publishes through. */
    private static final String DEAD_LETTER_TEMPLATE_BEAN = "deadLetterKafkaTemplate";

    /** Property naming the topic both assessment outcomes are published to. */
    private static final String ASSESSED_TOPIC_PROPERTY = "carddemo.kafka.topics.fraud-assessed";

    /** Singular spelling of the property above, which this platform binds nowhere. */
    private static final String SINGULAR_ASSESSED_TOPIC_PROPERTY =
            "carddemo.kafka.topic.fraud-assessed";

    /** Property naming the consumer group the listener joins. */
    private static final String CONSUMER_GROUP_PROPERTY = "spring.kafka.consumer.group-id";

    /** Partition count both topics are created with. */
    private static final int TOPIC_PARTITIONS = 3;

    /** Replication factor both topics are created with. */
    private static final short TOPIC_REPLICATION_FACTOR = 1;

    /** Value {@code eventType} carries on an inbound authorization event. */
    private static final String AUTHORIZED_EVENT_TYPE = "TransactionAuthorized";

    /** Value {@code eventType} carries on a flagged assessment event. */
    private static final String FLAGGED_EVENT_TYPE = "FraudFlagged";

    /** Value {@code eventType} carries on a cleared assessment event. */
    private static final String CLEARED_EVENT_TYPE = "FraudCleared";

    /** Value {@code schemaVersion} carries on every event of version one. */
    private static final int EVENT_SCHEMA_VERSION = 1;

    /** Property count a flagged assessment event carries on the wire. */
    private static final int FLAGGED_PROPERTY_COUNT = 10;

    /** Property count a cleared assessment event carries on the wire. */
    private static final int CLEARED_PROPERTY_COUNT = 8;

    /** The two values {@code eventType} takes on the assessed topic. */
    private static final Set<String> ASSESSMENT_EVENT_TYPES =
            Set.of(FLAGGED_EVENT_TYPE, CLEARED_EVENT_TYPE);

    /** Row count every count query in this class expects. */
    private static final long ONE_ROW = 1L;

    /** Record count every arrival assertion in this class expects. */
    private static final int ONE_RECORD = 1;

    /** Upper bound on a first-arrival wait, covering the fixed relay delay and a broker round trip. */
    private static final Duration ARRIVAL_TIMEOUT = Duration.ofSeconds(30);

    /** Interval between two evaluations of a bounded wait. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    /** Timeout of one broker fetch inside a bounded wait. */
    private static final Duration FETCH_TIMEOUT = Duration.ofMillis(200);

    /** Window a further record is looked for in, after the expected record arrived. */
    private static final Duration SILENCE_WINDOW = Duration.ofSeconds(2);

    /** Upper bound on topic creation. */
    private static final Duration TOPIC_CREATION_TIMEOUT = Duration.ofSeconds(30);

    /** Account identifier every document in this class carries, eleven characters. */
    private static final String ACCOUNT_ID = "00000000007";

    /** Length of an account identifier, which matches the pattern {@code ^[0-9]{11}$}. */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /** Card number of that account with its leading twelve digits replaced by asterisks. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** Transaction type code of both fixture transactions this class publishes. */
    private static final String TRANSACTION_TYPE_CODE = "01";

    /** Merchant category code, the one value the daily transaction fixture carries. */
    private static final String MERCHANT_CATEGORY_CODE = "0001";

    /** Merchant identifier, the one value the daily transaction fixture carries. */
    private static final String MERCHANT_ID = "800000000";

    /** Capture channel of both fixture transactions this class publishes. */
    private static final String CAPTURE_SOURCE = "POS TERM";

    /** Authorization timestamp, the one value all 300 fixture records carry, twenty-six characters. */
    private static final String AUTHORIZED_AT = "2022-06-10 19:27:53.000000";

    /** Currency of the amount, additive: no source transaction record declares one. */
    private static final String CURRENCY = "USD";

    /** Table holding one risk verdict per transaction. */
    private static final String FRAUD_ASSESSMENT_TABLE = SERVICE_SCHEMA + ".fraud_assessment";

    /** Table holding the rolling per-account count and amount. */
    private static final String VELOCITY_WINDOW_TABLE = SERVICE_SCHEMA + ".velocity_window";

    /** Table holding one assessment event awaiting publication. */
    private static final String OUTBOX_EVENT_TABLE = SERVICE_SCHEMA + ".outbox_event";

    /** Table holding one duplicate-delivery marker per consumed event. */
    private static final String PROCESSED_EVENT_TABLE = SERVICE_SCHEMA + ".processed_event";

    /** Statements emptying every table this service owns, run before each test. */
    private static final List<String> EMPTY_OWNED_TABLES = List.of(
            "DELETE FROM " + FRAUD_ASSESSMENT_TABLE,
            "DELETE FROM " + VELOCITY_WINDOW_TABLE,
            "DELETE FROM " + OUTBOX_EVENT_TABLE,
            "DELETE FROM " + PROCESSED_EVENT_TABLE);

    /** Counts the verdicts stored for one transaction identifier. */
    private static final String ASSESSMENT_COUNT_SQL =
            "SELECT count(*) FROM " + FRAUD_ASSESSMENT_TABLE + " WHERE transaction_id = ?";

    /** Counts the outbox rows stored for one account identifier. */
    private static final String OUTBOX_COUNT_SQL =
            "SELECT count(*) FROM " + OUTBOX_EVENT_TABLE + " WHERE aggregate_id = ?";

    /** Counts the outbox rows of one account that carry a publication and its timestamp. */
    private static final String PUBLISHED_OUTBOX_COUNT_SQL =
            "SELECT count(*) FROM " + OUTBOX_EVENT_TABLE + " WHERE aggregate_id = ?"
                    + " AND published = TRUE AND published_at IS NOT NULL";

    /** Property carrying the identifier a consumer deduplicates on. */
    private static final String EVENT_ID_PROPERTY = "eventId";

    /** Property carrying the routing discriminator. */
    private static final String EVENT_TYPE_PROPERTY = "eventType";

    /** Property carrying the contract version. */
    private static final String SCHEMA_VERSION_PROPERTY = "schemaVersion";

    /** Property carrying the account identifier, which is also the message key. */
    private static final String AGGREGATE_ID_PROPERTY = "aggregateId";

    /** Property carrying the assessed transaction. */
    private static final String TRANSACTION_ID_PROPERTY = "transactionId";

    /** Property name a nested wire form would carry, and no schema of this platform declares. */
    private static final String ENVELOPE_PROPERTY = "envelope";

    /** Reads a published document into a tree. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The nineteen properties {@code transaction-authorized-v1.json} requires, in schema order. */
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
              "authorizedAt": "%s",
              "currency": "%s"
            }""";

    /** The first daily transaction of this account, sixteen characters with a leading zero. */
    private static final FixtureTransaction FIRST_TRANSACTION = new FixtureTransaction(
            "0000000000" + "683580", "504.77", "Purchase at Abshire-Lowe", "Abshire-Lowe",
            "North Enoshaven", "72112");

    /** A later daily transaction of the same account, sixteen characters with a leading zero. */
    private static final FixtureTransaction SECOND_TRANSACTION = new FixtureTransaction(
            "00000005" + "02617711", "955.11", "Purchase at Klocko LLC", "Klocko LLC",
            "Winonaland", "07626");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(CONTAINER_CREDENTIAL)
            .withUsername(CONTAINER_CREDENTIAL)
            .withPassword(CONTAINER_CREDENTIAL);

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    private final ApplicationContext context;
    private final Environment environment;
    private final JdbcTemplate jdbc;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final KafkaProducerConfig producerConfig;

    /**
     * Takes the running context and the four beans every assertion below reads.
     *
     * @param context the context {@code FraudApplication} configures
     */
    ConsumeToPublishIT(ApplicationContext context) {
        this.context = context;
        this.environment = context.getEnvironment();
        this.jdbc = context.getBean(JdbcTemplate.class);
        this.listenerRegistry = context.getBean(KafkaListenerEndpointRegistry.class);
        this.producerConfig = context.getBean(KafkaProducerConfig.class);
        startedContext = context;
    }

    /** The context of the most recent test instance, for {@link #stopBackgroundWork()}. */
    private static ApplicationContext startedContext;

    /**
     * Stops the relay tick and the listeners while both containers are still up.
     *
     * <p>An {@code @AfterAll} method runs before the extension callback that stops {@link #POSTGRES}
     * and {@link #KAFKA}, which is the only window in which this can be done. Without it the relay
     * keeps ticking every half second into a database that has gone, and the build log carries a
     * closed-connection stack trace under {@code Unexpected error occurred in scheduled task} on a
     * run where every assertion passed.
     */
    @AfterAll
    static void stopBackgroundWork() {
        ScheduledWorkShutdown.stopBefore(startedContext);
    }

    /**
     * Points the datasource and the broker client at the two containers.
     *
     * <p>The fifth entry names the protocol the broker container's listener accepts. Every other
     * setting the service reads resolves from its shipped defaults.
     *
     * @param registry the registry the test context reads these values from
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ConsumeToPublishIT::jdbcUrlOnServiceSchema);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.security.protocol", () -> BROKER_SECURITY_PROTOCOL);
    }

    /** Returns the container connection string with the service schema on its search path. */
    private static String jdbcUrlOnServiceSchema() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SERVICE_SCHEMA;
    }

    /**
     * Creates both topics with three partitions before the listener subscribes.
     *
     * <p>A rerun against a warm broker finds both topics present and continues.
     */
    @BeforeAll
    static void createTopics() {
        Map<String, Object> settings =
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(settings)) {
            List<NewTopic> topics = List.of(
                    new NewTopic(AUTHORIZED_TOPIC, TOPIC_PARTITIONS, TOPIC_REPLICATION_FACTOR),
                    new NewTopic(ASSESSED_TOPIC, TOPIC_PARTITIONS, TOPIC_REPLICATION_FACTOR));
            admin.createTopics(topics).all()
                    .get(TOPIC_CREATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Topic creation was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            if (!(failed.getCause() instanceof TopicExistsException)) {
                throw new AssertionError("Topic creation did not succeed.", failed);
            }
        } catch (TimeoutException timedOut) {
            throw new AssertionError(
                    "Topic creation did not finish within " + TOPIC_CREATION_TIMEOUT, timedOut);
        }
    }

    /** Empties the four tables this service owns, leaving each test an empty schema to count over. */
    @BeforeEach
    void emptyOwnedTables() {
        EMPTY_OWNED_TABLES.forEach(jdbc::update);
    }

    /**
     * Asserts one verdict row, one outbox row that reaches publication, and one assessed event.
     *
     * <p>The assessed event carries the account identifier as its key, holds one flat object of
     * either eight or ten properties, and no second event follows it.
     */
    @Test
    @DisplayName("one consumed event leaves one verdict row, one published outbox row and one event")
    void oneConsumedEventPublishesOneAssessmentEvent() {
        String transactionId = FIRST_TRANSACTION.transactionId();

        try (KafkaConsumer<String, String> assessed = assignedToEndOf(ASSESSED_TOPIC);
                KafkaProducer<String, String> producer = newProducer()) {
            publish(producer, authorizedEvent(UUID.randomUUID(), FIRST_TRANSACTION));

            awaitOneRow(ASSESSMENT_COUNT_SQL, transactionId,
                    "the verdict row of transaction " + transactionId);
            awaitOneRow(OUTBOX_COUNT_SQL, ACCOUNT_ID, "the outbox row of account " + ACCOUNT_ID);
            awaitOneRow(PUBLISHED_OUTBOX_COUNT_SQL, ACCOUNT_ID,
                    "the outbox row of account " + ACCOUNT_ID + " marked published with a timestamp");

            Predicate<ConsumerRecord<String, String>> wanted = carryingTransactionId(transactionId);
            List<ConsumerRecord<String, String>> arrivals =
                    awaitRecords(assessed, wanted, "the assessment event of " + transactionId);
            assertAssessmentEvent(arrivals.getFirst(), transactionId);
            assertNoFurtherRecord(assessed, wanted, arrivals,
                    "a second assessment event of " + transactionId);
        }
    }

    /**
     * Asserts a consumer in the ledger posting group receives the same event the service assessed.
     *
     * <p>Two consumer groups each take the one event, and the service still writes its verdict row
     * and publishes its assessment event.
     */
    @Test
    @DisplayName("the ledger posting group receives the same event the fraud service assesses")
    void theLedgerPostingGroupReceivesTheSameEvent() {
        UUID eventId = UUID.randomUUID();
        String transactionId = SECOND_TRANSACTION.transactionId();

        try (KafkaConsumer<String, String> ledger =
                        subscribedTo(AUTHORIZED_TOPIC, LEDGER_CONSUMER_GROUP);
                KafkaConsumer<String, String> assessed = assignedToEndOf(ASSESSED_TOPIC);
                KafkaProducer<String, String> producer = newProducer()) {
            publish(producer, authorizedEvent(eventId, SECOND_TRANSACTION));

            awaitRecords(ledger, carryingEventId(eventId),
                    "event " + eventId + " read under group " + LEDGER_CONSUMER_GROUP);
            awaitOneRow(ASSESSMENT_COUNT_SQL, transactionId,
                    "the verdict row of transaction " + transactionId);
            awaitRecords(assessed, carryingTransactionId(transactionId),
                    "the assessment event of " + transactionId);
        }
    }

    /**
     * Asserts the context registers one listener container on the authorized topic.
     *
     * <p>The container joins the fraud detection group, and it binds neither the assessed topic nor
     * the group the ledger posting service reads under.
     */
    @Test
    @DisplayName("one listener container reads transaction.authorized under the fraud-detection group")
    void oneListenerContainerReadsTheAuthorizedTopic() {
        MessageListenerContainer container = onlyListenerContainer();
        String[] boundTopics = container.getContainerProperties().getTopics();
        assertNotNull(boundTopics, "the listener container binds no topic list");
        Set<String> topics = new LinkedHashSet<>(Arrays.asList(boundTopics));

        assertAll("the listener binding",
                () -> assertEquals(Set.of(AUTHORIZED_TOPIC), topics,
                        () -> "the container binds " + topics),
                () -> assertFalse(topics.contains(ASSESSED_TOPIC),
                        "the container reads the topic this service publishes"),
                () -> assertEquals(FRAUD_CONSUMER_GROUP, container.getGroupId(),
                        () -> "the container joined group " + container.getGroupId()),
                () -> assertNotEquals(LEDGER_CONSUMER_GROUP, container.getGroupId(),
                        "the container shares the ledger posting group"),
                () -> assertEquals(FRAUD_CONSUMER_GROUP,
                        environment.getProperty(CONSUMER_GROUP_PROPERTY),
                        () -> CONSUMER_GROUP_PROPERTY + " resolved to "
                                + environment.getProperty(CONSUMER_GROUP_PROPERTY)),
                () -> assertTrue(container.isRunning(), "the container is not running"));
    }

    /**
     * Asserts two events of one account produce two assessment events on one partition.
     *
     * <p>Both keys hold the eleven-character account identifier with its leading zero, and the
     * assessed topic carries three partitions.
     */
    @Test
    @DisplayName("two events for one account produce two assessment events on one partition")
    void twoEventsForOneAccountShareOnePartition() {
        String firstId = FIRST_TRANSACTION.transactionId();
        String secondId = SECOND_TRANSACTION.transactionId();

        try (KafkaConsumer<String, String> assessed = assignedToEndOf(ASSESSED_TOPIC);
                KafkaProducer<String, String> producer = newProducer()) {
            assertEquals(TOPIC_PARTITIONS, assessed.assignment().size(),
                    () -> ASSESSED_TOPIC + " carries " + assessed.assignment().size() + " partitions");

            publish(producer, authorizedEvent(UUID.randomUUID(), FIRST_TRANSACTION));
            publish(producer, authorizedEvent(UUID.randomUUID(), SECOND_TRANSACTION));

            ConsumerRecord<String, String> first = awaitRecords(assessed,
                    carryingTransactionId(firstId), "the assessment event of " + firstId).getFirst();
            ConsumerRecord<String, String> second = awaitRecords(assessed,
                    carryingTransactionId(secondId), "the assessment event of " + secondId)
                    .getFirst();

            assertAll("the two assessment events of account " + ACCOUNT_ID,
                    () -> assertEquals(first.partition(), second.partition(),
                            () -> "the two events landed on partitions " + first.partition()
                                    + " and " + second.partition()),
                    () -> assertKeyKeepsAccountIdentifier(first),
                    () -> assertKeyKeepsAccountIdentifier(second));
        }
    }

    /**
     * Asserts the context holds exactly two Kafka templates, under the two names their configuration
     * classes declare.
     *
     * <p>Two candidates make an unqualified injection ambiguous, and the outbox relay resolves.
     */
    @Test
    @DisplayName("the context holds two Kafka templates under their declared bean names")
    void theContextHoldsTwoNamedKafkaTemplates() {
        String[] names = context.getBeanNamesForType(KafkaTemplate.class);
        Set<String> declared = new LinkedHashSet<>(Arrays.asList(names));

        assertAll("the Kafka templates of this context",
                () -> assertEquals(2, names.length,
                        () -> "the context holds these Kafka templates: " + Arrays.toString(names)),
                () -> assertEquals(Set.of(OUTBOX_TEMPLATE_BEAN, DEAD_LETTER_TEMPLATE_BEAN), declared,
                        () -> "the context holds these Kafka templates: " + Arrays.toString(names)),
                () -> assertThrows(NoUniqueBeanDefinitionException.class,
                        () -> context.getBean(KafkaTemplate.class),
                        "an unqualified Kafka template injection resolved against "
                                + Arrays.toString(names)
                                + ", so no injection point has to name the template it takes"),
                () -> assertNotNull(context.getBean(OutboxRelay.class),
                        "OutboxRelay took no template by qualifier out of "
                                + Arrays.toString(names)));
    }

    /**
     * Asserts one resolved property value names the topic every assessment event arrives on.
     *
     * <p>The plural property, the producer configuration and the arriving record hold one topic
     * name, and the singular spelling of that property resolves to nothing.
     */
    @Test
    @DisplayName("one resolved property value names the topic every assessment event arrives on")
    void oneResolvedTopicNameCarriesEveryAssessmentEvent() {
        String boundTopic = environment.getProperty(ASSESSED_TOPIC_PROPERTY);
        String producerTopic = producerConfig.fraudAssessedTopic();
        String transactionId = FIRST_TRANSACTION.transactionId();

        try (KafkaConsumer<String, String> assessed = assignedToEndOf(ASSESSED_TOPIC);
                KafkaProducer<String, String> producer = newProducer()) {
            publish(producer, authorizedEvent(UUID.randomUUID(), FIRST_TRANSACTION));
            ConsumerRecord<String, String> arrival = awaitRecords(assessed,
                    carryingTransactionId(transactionId),
                    "the assessment event of " + transactionId).getFirst();

            assertAll("the assessed topic name",
                    () -> assertEquals(ASSESSED_TOPIC, boundTopic,
                            () -> ASSESSED_TOPIC_PROPERTY + " resolved to " + boundTopic),
                    () -> assertEquals(boundTopic, producerTopic,
                            () -> "the producer configuration resolved " + producerTopic),
                    () -> assertEquals(boundTopic, arrival.topic(),
                            () -> "the event arrived on " + arrival.topic()),
                    () -> assertNull(environment.getProperty(SINGULAR_ASSESSED_TOPIC_PROPERTY),
                            () -> SINGULAR_ASSESSED_TOPIC_PROPERTY + " resolved to "
                                    + environment.getProperty(SINGULAR_ASSESSED_TOPIC_PROPERTY)));
        }
    }

    /**
     * Asserts one arriving record carries a flat assessment event of the expected shape.
     *
     * @param delivery      the record read from the assessed topic
     * @param transactionId the transaction the event reports on
     */
    private static void assertAssessmentEvent(ConsumerRecord<String, String> delivery,
            String transactionId) {
        JsonNode event = JSON.readTree(delivery.value());
        String eventType = event.path(EVENT_TYPE_PROPERTY).asString(null);
        JsonNode schemaVersion = event.path(SCHEMA_VERSION_PROPERTY);

        assertAll("the assessment event on " + delivery.topic(),
                () -> assertEquals(ACCOUNT_ID, delivery.key(),
                        () -> "the record key holds " + delivery.key()),
                () -> assertTrue(event.isObject(), "the event is not one object"),
                () -> assertFalse(event.has(ENVELOPE_PROPERTY),
                        "the event nests its envelope under a property of its own"),
                () -> assertTrue(ASSESSMENT_EVENT_TYPES.contains(eventType),
                        () -> EVENT_TYPE_PROPERTY + " holds " + eventType),
                () -> assertEquals(requiredPropertyCount(eventType), event.size(),
                        () -> "the event carries " + event.propertyNames()),
                () -> assertTrue(schemaVersion.isIntegralNumber(),
                        () -> SCHEMA_VERSION_PROPERTY + " is not an integer"),
                () -> assertEquals(EVENT_SCHEMA_VERSION, schemaVersion.intValue(),
                        () -> SCHEMA_VERSION_PROPERTY + " holds " + schemaVersion.intValue()),
                () -> assertEquals(transactionId,
                        event.path(TRANSACTION_ID_PROPERTY).asString(null),
                        () -> TRANSACTION_ID_PROPERTY + " names another transaction"),
                () -> assertTrue(event.path(AGGREGATE_ID_PROPERTY).isString(),
                        () -> AGGREGATE_ID_PROPERTY + " is not text"),
                () -> assertEquals(ACCOUNT_ID, event.path(AGGREGATE_ID_PROPERTY).asString(null),
                        () -> AGGREGATE_ID_PROPERTY + " names another account"));
    }

    /**
     * Asserts the record key and the payload account identifier hold one eleven-character text value
     * with its leading zero in place.
     *
     * @param delivery the record read from the assessed topic
     */
    private static void assertKeyKeepsAccountIdentifier(ConsumerRecord<String, String> delivery) {
        String key = delivery.key();
        JsonNode accountId = JSON.readTree(delivery.value()).path(AGGREGATE_ID_PROPERTY);
        assertAll("the key of the record on partition " + delivery.partition(),
                () -> assertEquals(ACCOUNT_ID, key, () -> "the key holds " + key),
                () -> assertEquals(ACCOUNT_ID_LENGTH, key.length(),
                        () -> "the key holds " + key.length() + " characters"),
                () -> assertTrue(key.startsWith("0"), "the key lost its leading zero"),
                () -> assertTrue(accountId.isString(),
                        () -> AGGREGATE_ID_PROPERTY + " is not text"),
                () -> assertEquals(key, accountId.asString(null),
                        () -> AGGREGATE_ID_PROPERTY + " holds " + accountId.asString(null)));
    }

    /**
     * Returns the property count the schema of one assessment event type requires.
     *
     * @param eventType the routing discriminator the event carries
     * @return ten for a flagged event and eight for a cleared one
     */
    private static int requiredPropertyCount(String eventType) {
        return FLAGGED_EVENT_TYPE.equals(eventType)
                ? FLAGGED_PROPERTY_COUNT
                : CLEARED_PROPERTY_COUNT;
    }

    /**
     * Returns the one listener container the context registers.
     *
     * @return the container the listener of this service runs in
     */
    private MessageListenerContainer onlyListenerContainer() {
        Collection<MessageListenerContainer> containers = listenerRegistry.getListenerContainers();
        assertEquals(1, containers.size(),
                () -> "the context registers these listener containers: "
                        + containers.stream().map(MessageListenerContainer::getListenerId).toList());
        return containers.iterator().next();
    }

    /**
     * Waits for one row to satisfy a count query, then asserts the count is one.
     *
     * @param sql      the count query to run
     * @param argument the value bound to its one parameter
     * @param subject  what the count reports on, named in a failure
     */
    private void awaitOneRow(String sql, Object argument, String subject) {
        Awaitility.await(subject)
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> rowCount(sql, argument) == ONE_ROW);
        assertEquals(ONE_ROW, rowCount(sql, argument), subject);
    }

    /**
     * Runs one count query and returns what it counted.
     *
     * @param sql      the count query to run
     * @param argument the value bound to its one parameter
     * @return the row count, zero where the query returned nothing
     */
    private long rowCount(String sql, Object argument) {
        Long count = jdbc.queryForObject(sql, Long.class, argument);
        return count == null ? 0L : count;
    }

    /**
     * Waits for one record to match, then asserts one matched.
     *
     * @param consumer the consumer positioned before the record was published
     * @param wanted   the test one record has to pass
     * @param subject  what the record reports on, named in a failure
     * @return the matched records, in arrival order, for a later assertion
     */
    private static List<ConsumerRecord<String, String>> awaitRecords(
            KafkaConsumer<String, String> consumer,
            Predicate<ConsumerRecord<String, String>> wanted, String subject) {
        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        Awaitility.await(subject)
                .pollInSameThread()
                .atMost(ARRIVAL_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .pollDelay(Duration.ZERO)
                .until(() -> {
                    drain(consumer, wanted, matched, FETCH_TIMEOUT);
                    return matched.size() >= ONE_RECORD;
                });
        assertEquals(ONE_RECORD, matched.size(), subject);
        return matched;
    }

    /**
     * Asserts no further record matches within a bounded fetch.
     *
     * @param consumer the consumer that read the expected record
     * @param wanted   the test one record has to pass
     * @param matched  the records matched so far, extended by this fetch
     * @param subject  what the absent record would report on, named in a failure
     */
    private static void assertNoFurtherRecord(KafkaConsumer<String, String> consumer,
            Predicate<ConsumerRecord<String, String>> wanted,
            List<ConsumerRecord<String, String>> matched, String subject) {
        drain(consumer, wanted, matched, SILENCE_WINDOW);
        assertEquals(ONE_RECORD, matched.size(), subject);
    }

    /**
     * Fetches once and adds every record that passes the test.
     *
     * @param consumer the consumer to fetch from
     * @param wanted   the test one record has to pass
     * @param matched  the list every passing record is added to
     * @param timeout  how long the fetch waits for the broker
     */
    private static void drain(KafkaConsumer<String, String> consumer,
            Predicate<ConsumerRecord<String, String>> wanted,
            List<ConsumerRecord<String, String>> matched, Duration timeout) {
        for (ConsumerRecord<String, String> delivery : consumer.poll(timeout)) {
            if (wanted.test(delivery)) {
                matched.add(delivery);
            }
        }
    }

    /**
     * Returns a test matching the record that carries one event identifier.
     *
     * @param eventId the identifier the published document carries
     * @return the test one record has to pass
     */
    private static Predicate<ConsumerRecord<String, String>> carryingEventId(UUID eventId) {
        return delivery -> eventId.toString().equals(propertyOf(delivery, EVENT_ID_PROPERTY));
    }

    /**
     * Returns a test matching the record that reports on one transaction.
     *
     * @param transactionId the transaction the document names
     * @return the test one record has to pass
     */
    private static Predicate<ConsumerRecord<String, String>> carryingTransactionId(
            String transactionId) {
        return delivery -> transactionId.equals(propertyOf(delivery, TRANSACTION_ID_PROPERTY));
    }

    /**
     * Reads one text property out of a record value.
     *
     * @param delivery the record to read
     * @param property the property to read
     * @return the property value, or {@code null} where the document carries none
     */
    private static String propertyOf(ConsumerRecord<String, String> delivery, String property) {
        return JSON.readTree(delivery.value()).path(property).asString(null);
    }

    /**
     * Builds one authorization document carrying the nineteen required properties.
     *
     * @param eventId the identifier a consumer deduplicates on
     * @param fixture the daily transaction the document reports
     * @return the document to publish
     */
    private static String authorizedEvent(UUID eventId, FixtureTransaction fixture) {
        return AUTHORIZED_EVENT_TEMPLATE.formatted(
                eventId,
                AUTHORIZED_EVENT_TYPE,
                EVENT_SCHEMA_VERSION,
                Instant.now(),
                ACCOUNT_ID,
                fixture.transactionId(),
                ACCOUNT_ID,
                TRANSACTION_TYPE_CODE,
                MERCHANT_CATEGORY_CODE,
                CAPTURE_SOURCE,
                fixture.description(),
                fixture.amount(),
                MERCHANT_ID,
                fixture.merchantName(),
                fixture.merchantCity(),
                fixture.merchantZip(),
                MASKED_CARD_NUMBER,
                AUTHORIZED_AT,
                CURRENCY);
    }

    /**
     * Publishes one document to the authorized topic, keyed on the account identifier, and waits for
     * the broker to accept it.
     *
     * @param producer the client to publish through
     * @param document the document to publish
     */
    private static void publish(KafkaProducer<String, String> producer, String document) {
        try {
            Future<RecordMetadata> pending =
                    producer.send(new ProducerRecord<>(AUTHORIZED_TOPIC, ACCOUNT_ID, document));
            producer.flush();
            RecordMetadata metadata = pending.get(ARRIVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(metadata, "the broker returned no coordinates for the record");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Publishing to " + AUTHORIZED_TOPIC + " was interrupted.",
                    interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("The broker refused the record on " + AUTHORIZED_TOPIC + ".",
                    failed);
        } catch (TimeoutException timedOut) {
            throw new AssertionError("The broker did not accept the record within "
                    + ARRIVAL_TIMEOUT + ".", timedOut);
        }
    }

    /**
     * Builds one producer against the broker container.
     *
     * @return an idempotent producer of text keys and text values
     */
    private static KafkaProducer<String, String> newProducer() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ProducerConfig.ACKS_CONFIG, "all");
        settings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, Boolean.TRUE);
        return new KafkaProducer<>(settings, new StringSerializer(), new StringSerializer());
    }

    /**
     * Builds one consumer against the broker container.
     *
     * @param groupId the consumer group the client reads under
     * @return a consumer of text keys and text values that commits no offset
     */
    private static KafkaConsumer<String, String> newConsumer(String groupId) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE);
        return new KafkaConsumer<>(settings, new StringDeserializer(), new StringDeserializer());
    }

    /**
     * Builds one consumer holding every partition of a topic at its current end.
     *
     * @param topic the topic to read
     * @return a consumer that reads only what arrives after this call
     */
    private static KafkaConsumer<String, String> assignedToEndOf(String topic) {
        KafkaConsumer<String, String> consumer = newConsumer(freshGroup());
        List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(partition -> new TopicPartition(partition.topic(), partition.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    /**
     * Builds one consumer subscribed to a topic under a named group.
     *
     * @param topic   the topic to read
     * @param groupId the consumer group the client joins
     * @return a subscribed consumer
     */
    private static KafkaConsumer<String, String> subscribedTo(String topic, String groupId) {
        KafkaConsumer<String, String> consumer = newConsumer(groupId);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    /**
     * Returns a consumer group name no other client of this run holds.
     *
     * @return a group name carrying a fresh identifier
     */
    private static String freshGroup() {
        return "consume-to-publish-" + UUID.randomUUID();
    }

    /**
     * One daily transaction of the account under test, reduced to the properties that differ between
     * two records of that account.
     *
     * @param transactionId sixteen characters, leading zeros included
     * @param amount        a decimal string carrying two fractional digits
     * @param description   the transaction description
     * @param merchantName  the merchant name
     * @param merchantCity  the merchant city
     * @param merchantZip   the merchant postal code
     */
    private record FixtureTransaction(String transactionId, String amount, String description,
            String merchantName, String merchantCity, String merchantZip) {
    }
}
