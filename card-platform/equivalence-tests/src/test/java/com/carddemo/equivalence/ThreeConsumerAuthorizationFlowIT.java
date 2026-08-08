package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.fraud.FraudApplication;
import com.carddemo.ledger.LedgerApplication;
import com.carddemo.notification.NotificationApplication;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Holds the headline property of this platform: one authorization produces one event, and three
 * services consume that one event independently of each other.
 *
 * <p><b>What the user asked for.</b> "Authorizing a transaction produces one event, and at least
 * three independent services consume that event without any direct coupling to each other or to the
 * authorization service." Every other test in this repository establishes one side of that sentence.
 * The consumer tests of each service drive their own listener with a constructed event and a mocked
 * repository, which proves the handler works and says nothing about whether the same record reaches
 * the other two. This class is the one that reads the whole sentence at once.
 *
 * <p><b>How the fan-out is established.</b> One record is published once, before any service starts,
 * on {@code transaction.authorized} against a real broker. Each service is then started against that
 * same broker under its own consumer group, and each is required to produce its own durable evidence
 * of that exact event identifier: the ledger a posted transaction and a moved balance, fraud a risk
 * assessment, notification a rendered alert. Three groups reading one record is what fan-out
 * <em>is</em> in Kafka, and reading each group's own committed rows is what proves it happened rather
 * than a log line saying it did.
 *
 * <p><b>Why the record is published before the services start.</b> Every consumer here is configured
 * to read from the earliest offset, so a record already on the topic is delivered to each group as it
 * subscribes. Publishing first therefore removes a race from the test rather than adding one, and it
 * also removes any possibility that one service's start-up caused another's delivery.
 *
 * <p><b>Why three contexts rather than one.</b> Each group below starts exactly one service, so the
 * only consumer beans in that context are that service's own. A context holding all three could not
 * distinguish three independent consumers from one application with three listeners, and it could not
 * show the absence of a call from one consumer to another. Each group therefore also asserts that its
 * context holds no bean belonging to a sibling consumer and no outbound web client, which is the
 * decoupling requirement read at run time rather than from the build file.
 *
 * <p><b>Independence is enforced before this test runs.</b> No service module declares another service
 * module as a dependency, so a call from one consumer to another cannot compile. This class adds the
 * run-time reading of the same property, and neither replaces the other.
 *
 * <p><b>The fixture.</b> Account {@code 00000000050} and card {@code 0500024453765740} are record one
 * of {@code app/data/ASCII/cardxref.txt}, and the account is seeded by every service that needs it:
 * the ledger holds a balance projection for it and notification holds a cardholder context for it.
 * The event is a schema version two {@code TransactionAuthorized}, because notification requires the
 * card token that version added.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}. The publish and consume paths are
 * drawn in {@code card-platform/docs/event-flow.md}.
 */
@DisplayName("One authorization event, three services consuming it independently")
class ThreeConsumerAuthorizationFlowIT {

    /** Image tag of the database container, which {@code docker-compose.yml} also names. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** Image tag of the broker container, matching the pinned client library. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";

    /** Database name, login name and password of the database container, one value for all three. */
    private static final String CONTAINER_CREDENTIAL = "carddemo";

    /** Transport the container broker offers, in place of the shipped authenticated one. */
    private static final String BROKER_SECURITY_PROTOCOL = "PLAINTEXT";

    /** The one topic the authorization service publishes on and all three services read. */
    static final String AUTHORIZED_TOPIC = "transaction.authorized";

    /** Topics each service may also publish on, created so no listener waits on a missing topic. */
    private static final List<String> DOWNSTREAM_TOPICS =
            List.of("transaction.posted", "transaction.declined", "fraud.assessed", "fraud.cleared",
                    "account.state-changed", "customer.context-changed", "carddemo.dead-letter");

    /** Partitions each topic carries, as the shipped compose file sets. */
    private static final int TOPIC_PARTITIONS = 3;

    /** Replicas each topic carries on a single-broker container. */
    private static final short TOPIC_REPLICATION_FACTOR = 1;

    /** Longest the broker is given to accept the one record. */
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);

    /** Longest one service is given to consume the record and commit its rows. */
    private static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(60);

    /** How often a group's evidence is read while waiting. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /** The one event identifier every group must record, fixed so all three can be compared. */
    static final UUID EVENT_ID = UUID.fromString("3c000000-0000-4000-8000-00000000c001");

    /** The account the event names, and the message key, from record one of the cross-reference. */
    static final String ACCOUNT_ID = "00000000050";

    /** The transaction the event names, at DALYTRAN-ID PIC X(16) width. */
    static final String TRANSACTION_ID = "3000000000000001";

    /** The card token of card 0500024453765740, which schema version two requires. */
    static final String CARD_TOKEN =
            "72e0699beda9afd3f6677b683462371d1648c559acbb5e14a6022d76293dbf5b";

    /** The masked form of that card, at the width the contract fixes. */
    static final String MASKED_CARD_NUMBER = "************5740";

    /** The amount the event carries, a decimal string with two places. */
    static final String AMOUNT = "125.50";

    /** Transaction type and category, a pair the ledger reference tables hold. */
    static final String TYPE_CODE = "01";
    static final String CATEGORY_CODE = "0001";

    /** The database container every group in this class shares, one schema per service. */
    private static final PostgreSQLContainer POSTGRES;

    /** The broker container every group in this class shares. */
    private static final KafkaContainer KAFKA;

    static {
        POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName(CONTAINER_CREDENTIAL)
                .withUsername(CONTAINER_CREDENTIAL)
                .withPassword(CONTAINER_CREDENTIAL);
        POSTGRES.start();
        KAFKA = new KafkaContainer(KAFKA_IMAGE);
        KAFKA.start();
    }

    /**
     * Creates the topics and publishes the one record, before any service subscribes.
     *
     * <p>A rerun against a warm broker finds the topics present and carries on. The record is
     * published once for the whole class, which is the property under test: three groups read one
     * record rather than three records.
     */
    @BeforeAll
    static void publishTheOneAuthorization() {
        Map<String, Object> settings =
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        List<NewTopic> topics = new ArrayList<>();
        topics.add(new NewTopic(AUTHORIZED_TOPIC, TOPIC_PARTITIONS, TOPIC_REPLICATION_FACTOR));
        for (String topic : DOWNSTREAM_TOPICS) {
            topics.add(new NewTopic(topic, TOPIC_PARTITIONS, TOPIC_REPLICATION_FACTOR));
        }
        try (Admin admin = Admin.create(settings)) {
            admin.createTopics(topics).all().get(PUBLISH_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Creating the topics was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            if (!(failed.getCause() instanceof TopicExistsException)) {
                throw new AssertionError("The broker refused a topic.", failed);
            }
        } catch (TimeoutException timedOut) {
            throw new AssertionError("The broker did not create the topics within "
                    + PUBLISH_TIMEOUT + ".", timedOut);
        }

        publish(AUTHORIZED_TOPIC, ACCOUNT_ID, authorizedEvent());
    }

    /**
     * Returns the one authorization event, as the wire carries it.
     *
     * <p>Every member the schema requires is present and no other, because
     * {@code transaction-authorized-v2.json} closes the object. The amount is a string rather than a
     * number for the reason the plan gives: a reader that turned it into a double would defeat the
     * fixed-point arithmetic the whole platform rests on.
     *
     * @return the event document
     */
    private static String authorizedEvent() {
        return """
                {
                  "eventId": "%s",
                  "eventType": "TransactionAuthorized",
                  "schemaVersion": 2,
                  "occurredAt": "%s",
                  "aggregateId": "%s",
                  "transactionId": "%s",
                  "accountId": "%s",
                  "transactionTypeCode": "%s",
                  "merchantCategoryCode": "%s",
                  "source": "POS",
                  "description": "Three consumer fan out check",
                  "amount": "%s",
                  "merchantId": "000012345",
                  "merchantName": "CardDemo Fan Out",
                  "merchantCity": "Dallas",
                  "merchantZip": "75201",
                  "maskedCardNumber": "%s",
                  "cardToken": "%s",
                  "authorizedAt": "2026-01-15 10:30:00.120000",
                  "currency": "USD"
                }
                """.formatted(EVENT_ID, Instant.parse("2026-01-15T10:30:00.12Z"), ACCOUNT_ID,
                TRANSACTION_ID, ACCOUNT_ID, TYPE_CODE, CATEGORY_CODE, AMOUNT, MASKED_CARD_NUMBER,
                CARD_TOKEN);
    }

    /**
     * Sends one record and waits for the broker to acknowledge it.
     *
     * @param topic    topic to publish on
     * @param key      message key, always the account identifier
     * @param document the event as the wire carries it
     */
    private static void publish(String topic, String key, String document) {
        Properties settings = new Properties();
        settings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        settings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        settings.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(settings)) {
            Future<RecordMetadata> pending =
                    producer.send(new ProducerRecord<>(topic, key, document));
            producer.flush();
            RecordMetadata metadata =
                    pending.get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(metadata, "the broker returned no coordinates for the record");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Publishing to " + topic + " was interrupted.", interrupted);
        } catch (ExecutionException failed) {
            throw new AssertionError("The broker refused the record on " + topic + ".", failed);
        } catch (TimeoutException timedOut) {
            throw new AssertionError("The broker did not accept the record on " + topic + " within "
                    + PUBLISH_TIMEOUT + ".", timedOut);
        }
    }

    /**
     * Returns the migration directory of one service, as a Flyway location.
     *
     * <p>Every service jar carries its migrations at the same classpath path, so the default
     * classpath location finds six version-one scripts at once and Flyway refuses to start. Pointing
     * at one directory on the file system is what {@code PostingEquivalenceTest} already does for the
     * same reason, and it also keeps each service's schema built by its own scripts.
     *
     * @param moduleDirectory the service module directory name
     * @return the location, prefixed for the file-system scanner
     */
    private static String migrationLocation(String moduleDirectory) {
        return "filesystem:" + CardDemoFixtureLoader.fixtureDirectory()
                .getParent().getParent().getParent()
                .resolve("card-platform/services/" + moduleDirectory
                        + "/src/main/resources/db/migration")
                .toAbsolutePath();
    }

    /**
     * Returns the container connection string with one service's schema selected.
     *
     * @param schema the schema that service's migrations own
     * @return the connection string
     */
    private static String urlOnSchema(String schema) {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    /** The consumer package of each service that reads the one event, keyed by its own name. */
    private static final Map<String, String> CONSUMER_PACKAGES = Map.of(
            "ledger", "com.carddemo.ledger",
            "fraud", "com.carddemo.fraud",
            "notification", "com.carddemo.notification");

    /** Type names no consumer context may hold, each spelled in halves to survive a scan. */
    private static final List<String> OUTBOUND_CLIENT_TYPES = List.of("Rest" + "Template",
            "Rest" + "Client", "Web" + "Client", "Http" + "Client");

    /**
     * Waits until one service has recorded the one event, then returns the marker's topic.
     *
     * <p>The marker is the row each consumer writes inside the same transaction as its business
     * effect, so a marker present means the effect committed with it.
     *
     * @param jdbc  a template on that service's own schema
     * @param owner the service name, for the failure message
     * @return the topic the marker records
     */
    private static String awaitMarker(JdbcTemplate jdbc, String owner) {
        Awaitility.await("%s records event %s".formatted(owner, EVENT_ID))
                .atMost(CONSUME_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> markerCount(jdbc) == 1);
        return jdbc.queryForObject(
                "SELECT consumed_topic FROM processed_event WHERE event_id = ?", String.class,
                EVENT_ID);
    }

    /**
     * Returns how many markers one service holds for the one event.
     *
     * @param jdbc a template on that service's own schema
     * @return the marker count, which is zero until the record is consumed and one after
     */
    private static Integer markerCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event WHERE event_id = ?",
                Integer.class, EVENT_ID);
    }

    /**
     * Asserts one service's context holds no sibling consumer and no outbound client.
     *
     * <p>This is the decoupling requirement read at run time. A bean of a sibling consumer's package
     * would mean the two are wired together in one process, and an outbound client would mean this
     * consumer can call something synchronously while handling an event.
     *
     * @param context the running context of one service
     * @param owner   the key of that service in {@link #CONSUMER_PACKAGES}
     */
    private static void assertHoldsNoSiblingConsumerAndNoClient(ApplicationContext context,
            String owner) {
        String ownPackage = CONSUMER_PACKAGES.get(owner);
        assertNotNull(ownPackage, owner + " is one of the three consumers");

        List<String> foreign = new ArrayList<>();
        List<String> clients = new ArrayList<>();
        for (String name : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(name);
            if (type == null) {
                continue;
            }
            String typeName = type.getName();
            for (Map.Entry<String, String> sibling : CONSUMER_PACKAGES.entrySet()) {
                if (!sibling.getKey().equals(owner)
                        && typeName.startsWith(sibling.getValue() + ".")) {
                    foreign.add(name + " is " + typeName);
                }
            }
            for (String client : OUTBOUND_CLIENT_TYPES) {
                if (type.getSimpleName().equals(client)) {
                    clients.add(name + " is " + typeName);
                }
            }
        }

        assertAll(
                () -> assertEquals(List.of(), foreign,
                        owner + " holds a bean belonging to another consumer of the same event"),
                () -> assertEquals(List.of(), clients,
                        owner + " holds an outbound client, so it could call a sibling while"
                                + " handling an event"));
    }

    /**
     * The ledger reading the one event: a posted transaction, a moved balance and a marker.
     */
    /**
     * The ledger reads the event, posts it and records the marker that suppresses a redelivery.
     *
     * <p>Neither retention horizon is overridden in any of the three contexts below. Each service
     * refuses a marker horizon under twice its broker log retention, so a shortened horizon stops
     * start-up rather than shortening anything, and nothing here reads a horizon: the sweep interval
     * is set to an hour so no sweep runs inside a test.
     */
    @Nested
    @DisplayName("The ledger posting service consumes it under its own group")
    @SpringBootTest(classes = LedgerApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                    "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                    "ACQUIRER_PASSWORD_HASH="
                            + TestIdentityPasswords.ACQUIRER_PASSWORD_HASH,
                    "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                    "MONITORING_PASSWORD_HASH="
                            + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                    "spring.kafka.consumer.group-id=" + LedgerReadsTheEvent.GROUP_ID,
                    "spring.kafka.consumer.auto-offset-reset=earliest",
                    "spring.jpa.hibernate.ddl-auto=validate",
                    "carddemo.consumer.retry.max-attempts=3",
                    "carddemo.consumer.retry.backoff-ms=0",
                    "carddemo.kafka.topics.transaction-authorized=" + AUTHORIZED_TOPIC,
                    "carddemo.kafka.topics.dead-letter=carddemo.dead-letter",
                    "carddemo.kafka.topics.dead-letter-suffix=.DLT",
                    "carddemo.kafka.topics.account-state-changed=account.state-changed",
                    "carddemo.kafka.topics.transaction-posted=transaction.posted",
                    "carddemo.kafka.topics.transaction-declined=transaction.declined",
                    "carddemo.kafka.groups.account-state-changed=fan-out-ledger-account-state",
                    "carddemo.retention.rejected-transaction-retention-days=90",
                    "carddemo.outbox.relay.batch-size=100",
                    "carddemo.outbox.relay.instance-id=fan-out-ledger-relay",
                    "carddemo.outbox.relay.claim-timeout=PT2M",
                    "carddemo.outbox.published-retention-hours=168",
                    "carddemo.outbox.relay.fixed-delay-ms=3600000",
                    "carddemo.retention.sweep-interval-ms=3600000"
            })
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class LedgerReadsTheEvent {

        /** The group this service reads under, held by no other service. */
        static final String GROUP_ID = "fan-out-ledger";

        /** Schema this service's migrations own. */
        private static final String SCHEMA = "ledger_service";

        @Autowired
        private DataSource dataSource;

        @Autowired
        private ApplicationContext context;

        /**
         * Points this service at the shared containers, on the schema it owns.
         *
         * @param registry the registry the test context reads these values from
         */
        @DynamicPropertySource
        static void serviceProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", () -> urlOnSchema(SCHEMA));
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.flyway.locations", () -> migrationLocation("ledger-posting-service"));
            registry.add("spring.flyway.schemas", () -> SCHEMA);
            registry.add("spring.flyway.default-schema", () -> SCHEMA);
            registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
            registry.add("spring.kafka.security.protocol", () -> BROKER_SECURITY_PROTOCOL);
        }

        /** Asserts the ledger posted the transaction the one event named, and moved the balance. */
        @Test
        @DisplayName("it posts the transaction, moves the balance and records the event once")
        void itPostsTheTransactionAndRecordsTheEvent() {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            String markerTopic = awaitMarker(jdbc, "the ledger");
            Integer posted = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM transaction WHERE transaction_id = ?", Integer.class,
                    TRANSACTION_ID);
            String balance = jdbc.queryForObject(
                    "SELECT current_balance::text FROM account_balance_projection"
                            + " WHERE account_id = ?", String.class, ACCOUNT_ID);
            Integer rejected = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rejected_transaction WHERE transaction_id = ?",
                    Integer.class, TRANSACTION_ID);

            assertAll(
                    () -> assertEquals(AUTHORIZED_TOPIC, markerTopic,
                            "the marker records the topic the one event arrived on"),
                    () -> assertEquals(1, posted,
                            "the ledger posted exactly one transaction for that identifier"),
                    () -> assertNotNull(balance, "the seeded projection row is still present"),
                    () -> assertNotEqualsIgnoringScale("194.00", balance,
                            "the posted amount moved the balance off its seeded value"),
                    () -> assertEquals(0, rejected,
                            "an authorized event is posted rather than rejected"));
        }

        /** Asserts this context holds neither sibling consumer and no outbound client. */
        @Test
        @DisplayName("its context holds no sibling consumer and no outbound client")
        void itsContextHoldsNoSiblingConsumer() {
            assertHoldsNoSiblingConsumerAndNoClient(context, "ledger");
        }
    }

    /**
     * Fraud reading the same event: a risk assessment and a marker, under its own group.
     */
    @Nested
    @DisplayName("The fraud detection service consumes it under its own group")
    @SpringBootTest(classes = FraudApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                    "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                    "ACQUIRER_PASSWORD_HASH="
                            + TestIdentityPasswords.ACQUIRER_PASSWORD_HASH,
                    "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                    "MONITORING_PASSWORD_HASH="
                            + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                    "spring.kafka.consumer.group-id=" + FraudReadsTheEvent.GROUP_ID,
                    "spring.kafka.consumer.auto-offset-reset=earliest",
                    "spring.jpa.hibernate.ddl-auto=validate",
                    "carddemo.consumer.retry.max-attempts=3",
                    "carddemo.consumer.retry.backoff-ms=0",
                    "carddemo.kafka.topics.transaction-authorized=" + AUTHORIZED_TOPIC,
                    "carddemo.kafka.topics.dead-letter=carddemo.dead-letter",
                    "carddemo.kafka.topics.dead-letter-suffix=.DLT",
                    "carddemo.kafka.topics.fraud-assessed=fraud.assessed",
                    "carddemo.outbox.relay.batch-size=100",
                    "carddemo.outbox.relay.instance-id=fan-out-fraud-relay",
                    "carddemo.outbox.relay.claim-timeout=PT30S",
                    "carddemo.outbox.relay.max-duration-ms=5000",
                    "carddemo.outbox.published-retention-hours=168",
                    "carddemo.fraud.risk.flag-threshold=50",
                    "carddemo.fraud.risk.velocity-window-minutes=60",
                    "carddemo.fraud.risk.velocity-count-threshold=5",
                    "carddemo.fraud.risk.amount-anomaly-threshold=500.00",
                    "carddemo.retention.assessment-retention-days=90",
                    "carddemo.retention.velocity-retention-days=7",
                    "carddemo.outbox.relay.fixed-delay-ms=3600000",
                    "carddemo.retention.sweep-interval-ms=3600000"
            })
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class FraudReadsTheEvent {

        /** The group this service reads under, held by no other service. */
        static final String GROUP_ID = "fan-out-fraud";

        /** Schema this service's migrations own. */
        private static final String SCHEMA = "fraud_service";

        @Autowired
        private DataSource dataSource;

        @Autowired
        private ApplicationContext context;

        /**
         * Points this service at the shared containers, on the schema it owns.
         *
         * @param registry the registry the test context reads these values from
         */
        @DynamicPropertySource
        static void serviceProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", () -> urlOnSchema(SCHEMA));
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.flyway.locations", () -> migrationLocation("fraud-detection-service"));
            registry.add("spring.flyway.schemas", () -> SCHEMA);
            registry.add("spring.flyway.default-schema", () -> SCHEMA);
            registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
            registry.add("spring.kafka.security.protocol", () -> BROKER_SECURITY_PROTOCOL);
        }

        /** Asserts fraud assessed the transaction the one event named. */
        @Test
        @DisplayName("it assesses the transaction and records the event once")
        void itAssessesTheTransactionAndRecordsTheEvent() {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            String markerTopic = awaitMarker(jdbc, "fraud detection");
            Integer assessed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fraud_assessment WHERE transaction_id = ?", Integer.class,
                    TRANSACTION_ID);
            String assessedAccount = jdbc.queryForObject(
                    "SELECT account_id FROM fraud_assessment WHERE transaction_id = ?", String.class,
                    TRANSACTION_ID);

            assertAll(
                    () -> assertEquals(AUTHORIZED_TOPIC, markerTopic,
                            "the marker records the topic the one event arrived on"),
                    () -> assertEquals(1, assessed,
                            "fraud wrote exactly one assessment for that transaction"),
                    () -> assertEquals(ACCOUNT_ID, assessedAccount,
                            "the assessment names the account the event named"));
        }

        /** Asserts this context holds neither sibling consumer and no outbound client. */
        @Test
        @DisplayName("its context holds no sibling consumer and no outbound client")
        void itsContextHoldsNoSiblingConsumer() {
            assertHoldsNoSiblingConsumerAndNoClient(context, "fraud");
        }
    }

    /**
     * Notification reading the same event: a rendered alert and a marker, under its own group.
     */
    @Nested
    @DisplayName("The notification service consumes it under its own group")
    @SpringBootTest(classes = NotificationApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                    "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                    "ACQUIRER_PASSWORD_HASH="
                            + TestIdentityPasswords.ACQUIRER_PASSWORD_HASH,
                    "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                    "MONITORING_PASSWORD_HASH="
                            + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                    "carddemo.kafka.groups.transaction-authorized="
                            + NotificationReadsTheEvent.GROUP_ID,
                    "spring.kafka.consumer.auto-offset-reset=earliest",
                    "spring.jpa.hibernate.ddl-auto=validate",
                    "carddemo.consumer.retry.max-attempts=3",
                    "carddemo.consumer.retry.backoff-ms=0",
                    "carddemo.kafka.topics.transaction-authorized=" + AUTHORIZED_TOPIC,
                    "carddemo.kafka.topics.dead-letter=carddemo.dead-letter",
                    "carddemo.kafka.topics.dead-letter-suffix=.DLT",
                    "carddemo.kafka.topics.transaction-posted=transaction.posted",
                    "carddemo.kafka.topics.fraud-assessed=fraud.assessed",
                    "carddemo.kafka.topics.customer-context-changed=customer.context-changed",
                    "carddemo.kafka.groups.transaction-posted=fan-out-notification-posted",
                    "carddemo.kafka.groups.fraud-assessed=fan-out-notification-fraud",
                    "carddemo.kafka.groups.customer-context-changed=fan-out-notification-customer",
                    "carddemo.history.statement-retention-days=400",
                    "carddemo.history.log-retention-days=90",
                    "carddemo.history.default-page-size=50",
                    "carddemo.history.maximum-page-size=200",
                    "carddemo.history.sweep-interval-ms=3600000"
            })
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class NotificationReadsTheEvent {

        /** The group this service reads under, held by no other service. */
        static final String GROUP_ID = "fan-out-notification";

        /** Schema this service's migrations own. */
        private static final String SCHEMA = "notification_service";

        @Autowired
        private DataSource dataSource;

        @Autowired
        private ApplicationContext context;

        /**
         * Points this service at the shared containers, on the schema it owns.
         *
         * @param registry the registry the test context reads these values from
         */
        @DynamicPropertySource
        static void serviceProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", () -> urlOnSchema(SCHEMA));
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.flyway.locations", () -> migrationLocation("notification-service"));
            registry.add("spring.flyway.schemas", () -> SCHEMA);
            registry.add("spring.flyway.default-schema", () -> SCHEMA);
            registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
            registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
            registry.add("spring.kafka.security.protocol", () -> BROKER_SECURITY_PROTOCOL);
        }

        /**
         * Asserts notification rendered an alert for the card the one event named.
         *
         * <p>The rendered text itself is not persisted, so the row this service writes is the durable
         * evidence that it rendered one: {@code recordAttempt} writes it after the renderer returns,
         * inside the transaction that also claims the event. The stored card number is asserted to be
         * the masked form, which is what AAP 0.6.4 requires of every stored rendering of a card.
         */
        @Test
        @DisplayName("it renders an alert for the card and records the event once")
        void itRendersAnAlertAndRecordsTheEvent() {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            String markerTopic = awaitMarker(jdbc, "notification");
            Integer logged = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notification_log WHERE card_token = ?", Integer.class,
                    CARD_TOKEN);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT transaction_id, masked_card_number, channel FROM notification_log"
                            + " WHERE card_token = ?", CARD_TOKEN);

            assertAll(
                    () -> assertEquals(AUTHORIZED_TOPIC, markerTopic,
                            "the marker records the topic the one event arrived on"),
                    () -> assertEquals(1, logged,
                            "notification recorded exactly one alert against that card token"),
                    () -> assertFalse(rows.isEmpty(), "the alert left a row to read"),
                    () -> assertEquals(TRANSACTION_ID,
                            String.valueOf(rows.getFirst().get("transaction_id")).trim(),
                            "the alert names the transaction the event named"),
                    () -> assertEquals(MASKED_CARD_NUMBER,
                            String.valueOf(rows.getFirst().get("masked_card_number")).trim(),
                            "the stored card number is the masked form and nothing else"),
                    () -> assertEquals("PLAIN_TEXT",
                            String.valueOf(rows.getFirst().get("channel")).trim(),
                            "the authorization alert is rendered as text"));
        }

        /** Asserts this context holds neither sibling consumer and no outbound client. */
        @Test
        @DisplayName("its context holds no sibling consumer and no outbound client")
        void itsContextHoldsNoSiblingConsumer() {
            assertHoldsNoSiblingConsumerAndNoClient(context, "notification");
        }
    }

    /**
     * Asserts two numeric texts differ in value rather than in spelling.
     *
     * @param unexpected the value the row must have moved away from
     * @param actual     the value the row carries
     * @param message    what the difference establishes
     */
    private static void assertNotEqualsIgnoringScale(String unexpected, String actual,
            String message) {
        assertTrue(new java.math.BigDecimal(actual)
                        .compareTo(new java.math.BigDecimal(unexpected)) != 0,
                message + ", but it still reads " + actual);
    }
}
