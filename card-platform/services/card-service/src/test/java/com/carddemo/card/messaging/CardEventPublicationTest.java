package com.carddemo.card.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.card.CardApplication;
import com.carddemo.card.api.CardController;
import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardUpdateResponse.UpdateOutcome;
import com.carddemo.card.config.KafkaProducerConfig;
import com.carddemo.card.domain.CardUpdateService;
import com.carddemo.card.entity.CardEntity;
import com.carddemo.card.entity.OutboxEventEntity;
import com.carddemo.card.outbox.OutboxRelay;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.repository.OutboxEventRepository;
import com.carddemo.cobol.PanMasker;
import com.carddemo.events.EventEnvelope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the card service over its own Representational State Transfer (REST) routes and reads
 * what reaches the broker.
 *
 * <p>Four properties reach this class. A card mutation publishes exactly one {@code CardUpdated}
 * and a card read publishes none. The eleven-digit account identifier is the broker message key.
 * The published card number is masked while the lookup runs on the full Primary Account Number
 * (PAN). The card verification value reaches no outbound shape.
 *
 * <p>The fourth property is why the class exists. {@code CARD-CVV-CD PIC 9(03)} at
 * {@code app/cpy/CVACT02Y.cpy:L7} is stored in the clear, and
 * {@code app/cbl/COCRDUPC.cbl:L1503} compares it first in the six-field change check, so the
 * column has to hold it. A comment claiming the value never leaves proves nothing. The assertions
 * below read the serialized text and look for the three digits.
 *
 * <p>The publish and consume paths are drawn in {@code card-platform/docs/event-flow.md}.
 *
 * <p>How this class runs. One PostgreSQL 18.4 container and one Apache Kafka 4.2.1 container serve
 * the whole class, on the image tags {@code card-platform/docker-compose.yml} also names.
 * {@code src/main/resources/application.yml} sits on the test classpath and carries every other
 * setting, so Flyway creates schema {@code card_service}, applies {@code V1__schema.sql} and loads
 * the fifty rows of {@code V2__seed.sql}. {@link DynamicPropertySource} points the datasource and
 * the broker address at the two containers. Scheduling stays on, so the real relay sweep publishes
 * every row these tests commit.
 *
 * <p>Run this class from {@code card-platform/} with
 * {@code mvn -o -B -pl services/card-service -am test}. A container runtime is the only thing to
 * prepare: no broker, no topic and no fixture edit is needed beforehand.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        classes = CardApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_USERNAME=" + CardEventPublicationTest.ADMIN_NAME,
                "ADMIN_PASSWORD_HASH={noop}" + CardEventPublicationTest.ADMIN_SECRET,
                "USER_PASSWORD_HASH={noop}not-a-real-user-password",
                "MONITORING_PASSWORD_HASH={noop}not-a-real-monitoring-password"
        })
@Testcontainers
@DisplayName("Card event publication: one event per mutation, keyed, masked, and free of the card"
        + " verification value")
class CardEventPublicationTest {

    /**
     * Login name of the administrator identity every request below presents.
     *
     * <p>{@code config/SecurityConfig} grants {@code ROLE_ADMIN} every ownership check, so one
     * identity reaches the card list, the card read and the card update.
     */
    static final String ADMIN_NAME = "admin001";

    /**
     * Password of that identity, in the clear because the configured value carries the
     * {@code noop} prefix.
     *
     * <p>The value matches no provider credential pattern and authenticates against this test
     * context alone.
     */
    static final String ADMIN_SECRET = "not-a-real-admin-password";

    /** The image tag {@code card-platform/docker-compose.yml} also names for the database. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** The image tag {@code card-platform/docker-compose.yml} also names for the broker. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";

    /**
     * Database name, login name and password of the container, one value for all three.
     * {@code card-platform/.env.example} declares the same value.
     */
    private static final String POSTGRES_CREDENTIAL = "carddemo";

    /**
     * Schema Flyway creates, from {@code spring.flyway.schemas} and
     * {@code spring.jpa.properties.hibernate.default_schema} in
     * {@code src/main/resources/application.yml}.
     */
    private static final String MIGRATED_SCHEMA = "card_service";

    /**
     * Transport the container broker speaks.
     *
     * <p>{@code src/main/resources/application.yml} defaults to a Simple Authentication and
     * Security Layer (SASL) transport, which the shipped compose broker requires and a container
     * broker does not offer.
     */
    private static final String CONTAINER_SECURITY_PROTOCOL = "PLAINTEXT";

    /** Partitions the card topic carries here, as {@code card-platform/docker-compose.yml} sets. */
    private static final int TOPIC_PARTITIONS = 3;

    /** Replicas the card topic carries on a single-broker container. */
    private static final short TOPIC_REPLICAS = 1;

    /**
     * Card number of the first row of {@code app/data/ASCII/carddata.txt}, sixteen digits as
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} holds them.
     */
    private static final String SEEDED_CARD_NUMBER = "0500024453765740";

    /**
     * Account identifier of that row, eleven digits with eight leading zeros.
     *
     * <p>{@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6} declares the field and
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} is the declared width
     * authority. The eight zeros belong to the value.
     */
    private static final String SEEDED_ACCOUNT_ID = "00000000050";

    /**
     * Card verification value of that row, from {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}.
     *
     * <p>Every assertion naming these three digits is scoped to this one row.
     * {@code app/data/ASCII/carddata.txt} holds the same digits inside the card number of its
     * forty-seventh row, so an unscoped search over many rows would pass or fail for the wrong
     * reason.
     */
    private static final String SEEDED_VERIFICATION_VALUE = "747";

    /** Embossed cardholder name of that row, before the padding the column adds. */
    private static final String SEEDED_EMBOSSED_NAME = "Aniya Von";

    /** Expiry year of that row, from {@code CARD-EXPIRAION-DATE PIC X(10)}. */
    private static final String SEEDED_EXPIRY_YEAR = "2023";

    /** Expiry month of that row. */
    private static final String SEEDED_EXPIRY_MONTH = "03";

    /** Day of the month of that row, which the card update path carries forward unchanged. */
    private static final String SEEDED_EXPIRY_DAY = "09";

    /** Expiry date of that row as the column stores it. */
    private static final LocalDate SEEDED_EXPIRATION_DATE = LocalDate.of(2023, 3, 9);

    /**
     * Active status of that row, from {@code CARD-ACTIVE-STATUS PIC X(01)} at
     * {@code app/cpy/CVACT02Y.cpy:L10}.
     *
     * <p>The event carries the status as a value, and no assertion below reads it as a gate.
     * {@code app/jcl/POSTTRAN.jcl} allocates six datasets and none of them is the card master file,
     * so no source program tests the status before it posts a transaction.
     */
    private static final String SEEDED_ACTIVE_STATUS = "Y";

    /** Characters the embossed name column holds, from {@code CARD-EMBOSSED-NAME PIC X(50)}. */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /** Embossed cardholder name a committed update writes, in the letters the edit admits. */
    private static final String UPDATED_EMBOSSED_NAME = "ANIYA VONN";

    /**
     * The seeded card number with twelve mask characters ahead of its last four digits.
     *
     * <p>{@code com.carddemo.cobol.PanMasker} derives the value, and the assertions below compare
     * against this literal so a change in the masker fails a test rather than passing silently.
     */
    private static final String MASKED_CARD_NUMBER = "************5740";

    /** A partly masked value: four mask characters and twelve digits, which no event may carry. */
    private static final String PARTLY_MASKED_CARD_NUMBER = "****024453765740";

    /** A value one mask character short of the form the contract fixes. */
    private static final String UNDER_MASKED_CARD_NUMBER = "***********5740";

    /**
     * The nine property names one card event carries at the top level of its object.
     *
     * <p>Five come from {@link EventEnvelope} and four carry the card. The same nine are the
     * {@code required} array of {@code schemas/card-updated-v2.json}, which
     * {@code com.carddemo:event-contracts} ships.
     */
    private static final Set<String> WIRE_PROPERTIES = Set.of(
            "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId",
            "accountId", "maskedCardNumber", "expirationDate", "activeStatus");

    /** Nesting key a wrapped envelope would produce, which the closed property set refuses. */
    private static final String NESTING_KEY = "envelope";

    /** Longest wait for one event to travel from a committed row to the broker. */
    private static final Duration EVENT_ARRIVES_WITHIN = Duration.ofSeconds(30);

    /** Window a negative assertion watches before it concludes that nothing was published. */
    private static final Duration QUIET_PERIOD = Duration.ofSeconds(3);

    /** Longest one broker poll blocks. */
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(250);

    /** Longest one request waits for the service. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** Reads a payload back as a tree. Jackson 3 writes it and Jackson 3 reads it. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Logger name the log assertion attaches its recorder to. */
    private static final String SERVICE_LOGGER = "com.carddemo";

    /** Turns to {@code true} once the card topic exists on the container broker. */
    private static volatile boolean topicPrepared;

    /**
     * The one database container every test in this class shares.
     *
     * <p>The class name comes from {@code org.testcontainers.postgresql}, the package
     * Testcontainers 2.0.5 ships it in.
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(POSTGRES_CREDENTIAL)
            .withUsername(POSTGRES_CREDENTIAL)
            .withPassword(POSTGRES_CREDENTIAL);

    /**
     * The one broker container every test in this class shares.
     *
     * <p>The class name comes from {@code org.testcontainers.kafka}, and the image runs in
     * KRaft mode with no ZooKeeper.
     */
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE);

    /**
     * Points the datasource and the broker address at the two running containers.
     *
     * <p>Five properties leave here, each as a supplier the context resolves at refresh. No line
     * creates the schema, because {@code spring.flyway.create-schemas} does that.
     *
     * @param registry the registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CardEventPublicationTest::migratedSchemaUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.security.protocol", () -> CONTAINER_SECURITY_PROTOCOL);
    }

    /**
     * Returns the container connection string with the migrated schema on the search path.
     *
     * <p>Testcontainers appends one query parameter of its own, so the separator is {@code &}
     * whenever a {@code ?} is present and {@code ?} otherwise.
     *
     * @return the uniform resource locator whose search path holds {@value #MIGRATED_SCHEMA}
     */
    private static String migratedSchemaUrl() {
        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "currentSchema=" + MIGRATED_SCHEMA;
    }

    /** Port the embedded web server took, which the request helpers below build on. */
    @LocalServerPort
    private int servicePort;

    /**
     * Topic name a card update travels on, read from {@code carddemo.kafka.topics.card-updated}.
     *
     * <p>No test below names a topic as a literal. A deployment that renames the topic renames it
     * here too.
     */
    @Value("${carddemo.kafka.topics.card-updated}")
    private String cardUpdatedTopic;

    /**
     * The shared dead-letter topic, read from {@code carddemo.kafka.topics.dead-letter}.
     *
     * <p>A card update never travels on it. The relay publishes one diagnostic there for a row it
     * abandoned, so no card event these tests commit should reach it.
     */
    @Value("${carddemo.kafka.topics.dead-letter}")
    private String deadLetterTopic;

    /** The producer configuration, which reports the topic name it bound. */
    @Autowired
    private KafkaProducerConfig producerConfig;

    /** The publisher the relay sends through, taken from the context to name its implementation. */
    @Autowired
    private EventPublisherPort publisher;

    /** The card update path, which owns the transaction the outbox writer joins. */
    @Autowired
    private CardUpdateService cardUpdateService;

    /** The sweep that publishes committed rows, invoked directly where a test needs one now. */
    @Autowired
    private OutboxRelay outboxRelay;

    /** Reads and stores card rows. */
    @Autowired
    private CardRepository cards;

    /** Reads and removes event rows. */
    @Autowired
    private OutboxEventRepository outboxEvents;

    /** Opens the transactions this class starts and commits. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Reads the stored card verification value, which no accessor of {@code entity/CardEntity}
     * exposes.
     *
     * <p>The column exists because {@code app/cpy/CVACT02Y.cpy:L7} declares the field. Reading it
     * straight from the migrated schema is what makes the absence assertions non-vacuous.
     */
    @Autowired
    private JdbcTemplate jdbc;

    /** Sends every request. Built once per test, and closed with the test. */
    private HttpClient httpClient;

    /** Reads the card topic. Built once per test, positioned at the end of every partition. */
    private KafkaConsumer<String, String> cardEvents;

    /**
     * Restores the seeded card, empties the outbox, and positions a reader at the topic end.
     *
     * <p>The order matters. Emptying the outbox first leaves the relay nothing to publish, and
     * seeking to the end afterwards drops anything an earlier test left on the topic. A test that
     * then counts records counts only its own.
     */
    @BeforeEach
    void restoreTheSeededCardAndPositionTheReader() {
        prepareTopics();
        emptyTheOutbox();
        restoreTheSeededCard();

        httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
        cardEvents = readerPositionedAtTheEndOf(cardUpdatedTopic);
    }

    /**
     * Closes the reader and the client, empties the outbox, and restores the seeded card.
     *
     * <p>The fifty rows of {@code V2__seed.sql} stand unchanged for the next class.
     */
    @AfterEach
    void closeTheReaderAndRestoreTheSeededCard() {
        if (cardEvents != null) {
            cardEvents.close();
            cardEvents = null;
        }
        if (httpClient != null) {
            httpClient.close();
            httpClient = null;
        }
        emptyTheOutbox();
        restoreTheSeededCard();
    }

    /**
     * Creates the card topic and the dead-letter topic on the container broker, once per class.
     *
     * <p>A broker that auto-creates a topic on first produce would leave a reader watching a topic
     * that does not exist yet, so both names are created up front. A second call finds them present
     * and returns.
     */
    private void prepareTopics() {
        if (topicPrepared) {
            return;
        }
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            List<NewTopic> wanted = List.of(
                    new NewTopic(cardUpdatedTopic, TOPIC_PARTITIONS, TOPIC_REPLICAS),
                    new NewTopic(deadLetterTopic, TOPIC_PARTITIONS, TOPIC_REPLICAS));
            admin.createTopics(wanted).all().get();
        } catch (ExecutionException alreadyThere) {
            if (!(alreadyThere.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("the card topics could not be created",
                        alreadyThere);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("topic creation was interrupted", interrupted);
        }
        topicPrepared = true;
    }

    /**
     * Builds a reader assigned to every partition of one topic and seeks it to the end.
     *
     * <p>Assignment positions the reader the moment it returns, with no group rebalance to wait
     * for. The group name carries a fresh identifier, so parallel clones of this repository do not
     * share a committed offset.
     *
     * <p>{@code seekToEnd} records the intent and resolves the offset later, so the loop below asks
     * for the position of every assigned partition. Each answer forces that resolution now, and a
     * record published after this method returns therefore reaches the reader.
     *
     * @param topic the topic to read
     * @return a reader that sees records published after it returns, and none published before
     */
    private static KafkaConsumer<String, String> readerPositionedAtTheEndOf(String topic) {
        Map<String, Object> settings = new HashMap<>();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        settings.put(ConsumerConfig.GROUP_ID_CONFIG,
                "card-event-publication-" + UUID.randomUUID());
        settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        KafkaConsumer<String, String> reader = new KafkaConsumer<>(settings);
        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo partition : reader.partitionsFor(topic)) {
            partitions.add(new TopicPartition(partition.topic(), partition.partition()));
        }
        reader.assign(partitions);
        reader.seekToEnd(partitions);
        for (TopicPartition partition : partitions) {
            reader.position(partition);
        }
        return reader;
    }

    /**
     * Waits until the expected number of records has arrived, then answers with them.
     *
     * <p>Awaitility owns the wait. {@code KafkaConsumer.poll} blocks for at most
     * {@link #POLL_TIMEOUT}, so the loop needs no sleep of its own.
     *
     * @param expected how many records to wait for
     * @return the records that arrived, in the order the reader saw them
     */
    private List<ConsumerRecord<String, String>> awaitRecords(int expected) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        Awaitility.await("card events reaching the topic")
                .atMost(EVENT_ARRIVES_WITHIN)
                .pollInterval(POLL_TIMEOUT)
                .until(() -> {
                    cardEvents.poll(POLL_TIMEOUT).forEach(collected::add);
                    return collected.size() >= expected;
                });
        return collected;
    }

    /**
     * Reads the topic for a bounded window and answers with whatever arrived.
     *
     * <p>A negative assertion needs a window rather than one poll: a single poll that finds nothing
     * proves nothing about a publish still in flight. Each poll blocks for at most
     * {@link #POLL_TIMEOUT}, so the window passes without a sleep.
     *
     * @param window how long to watch
     * @return every record seen inside the window, which is empty when nothing was published
     */
    private List<ConsumerRecord<String, String>> recordsDuring(Duration window) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            cardEvents.poll(POLL_TIMEOUT).forEach(collected::add);
        }
        return collected;
    }

    /**
     * Waits for exactly one record and proves no second one follows it.
     *
     * @return the one record the topic carried
     */
    private ConsumerRecord<String, String> theOneRecordPublished() {
        List<ConsumerRecord<String, String>> arrived = awaitRecords(1);
        assertEquals(1, arrived.size(), "one card update publishes one event");
        List<ConsumerRecord<String, String>> afterwards = recordsDuring(QUIET_PERIOD);
        assertTrue(afterwards.isEmpty(),
                "no further event follows the one a single card update publishes");
        return arrived.getFirst();
    }

    /**
     * Runs one relay sweep on the calling thread, without waiting for the scheduled one.
     *
     * <p>A silent topic on its own could mean the scheduled sweep has not run yet. Running the
     * sweep first, then finding the topic still silent, means the relay found no row to publish.
     */
    private void sweepTheOutboxNow() {
        outboxRelay.publishPendingEvents();
    }

    /** Removes every event row, so the relay has nothing left from an earlier test. */
    private void emptyTheOutbox() {
        Awaitility.await("the outbox emptying")
                .atMost(EVENT_ARRIVES_WITHIN)
                .pollInterval(POLL_TIMEOUT)
                .until(() -> {
                    runInNewTransaction(() -> outboxEvents.deleteAll());
                    return inNewTransaction(outboxEvents::count) == 0L;
                });
    }

    /**
     * Writes the fixture values back onto the seeded card row.
     *
     * <p>Field values come from the first row of {@code app/data/ASCII/carddata.txt}, so each test
     * starts from the state {@code V2__seed.sql} loaded and a name change is a real change.
     */
    private void restoreTheSeededCard() {
        inNewTransaction(() -> {
            CardEntity card = cards.findByCardNumber(SEEDED_CARD_NUMBER).orElseThrow(
                    () -> new IllegalStateException("V2__seed.sql loads the card this class reads"));
            card.applyUpdate(padded(SEEDED_EMBOSSED_NAME), SEEDED_EXPIRATION_DATE,
                    SEEDED_ACTIVE_STATUS);
            return cards.save(card);
        });
    }

    /**
     * Runs one unit of work in a transaction of its own and commits it.
     *
     * @param work what to run
     * @param <T>  what the work answers with
     * @return whatever the work answered
     */
    private <T> T inNewTransaction(Supplier<T> work) {
        return newTransaction().execute(status -> work.get());
    }

    /**
     * Runs one unit of work in a transaction of its own, commits it, and answers with nothing.
     *
     * @param work what to run
     */
    private void runInNewTransaction(Runnable work) {
        newTransaction().executeWithoutResult(status -> work.run());
    }

    /**
     * Opens a transaction of its own, joining none.
     *
     * @return a template that starts a fresh transaction on every call
     */
    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Pads one value on the right to the width of {@code CARD-EMBOSSED-NAME PIC X(50)}.
     *
     * @param value the value to pad
     * @return the value at exactly {@value #EMBOSSED_NAME_WIDTH} characters
     */
    private static String padded(String value) {
        return value.length() >= EMBOSSED_NAME_WIDTH ? value.substring(0, EMBOSSED_NAME_WIDTH)
                : value + " ".repeat(EMBOSSED_NAME_WIDTH - value.length());
    }

    /**
     * Lists the cards of one account over the real route and answers with the response text.
     *
     * <p>{@code GET /cards} carries the account in a query parameter, and
     * {@code config/SecurityConfig} reads that parameter to decide ownership. Account
     * {@value #SEEDED_ACCOUNT_ID} holds exactly one card in
     * {@code app/data/ASCII/carddata.txt}, so the page below carries one row.
     *
     * @param accountId the eleven-digit account whose cards to list
     * @return the status and the body the service answered with
     */
    private HttpResponse<String> listCardsOfAccount(String accountId) {
        return send(authorized(CardController.BASE_PATH + "?"
                + CardController.ACCOUNT_ID_PARAMETER + "=" + accountId).GET().build());
    }

    /**
     * Reads one card over the real route, naming the full Primary Account Number in the body.
     *
     * <p>{@code POST /cards/detail} takes the sixteen characters the read keys on.
     * {@code app/bms/COCRDSL.bms:L99} declares the source screen field at the same sixteen, and no
     * source program masks the value.
     *
     * @param accountId  the eleven-digit account the card belongs to
     * @param cardNumber the full sixteen-digit card number
     * @return the status and the body the service answered with
     */
    private HttpResponse<String> readCard(String accountId, String cardNumber) {
        String body = "{\"accountId\":\"" + accountId + "\",\"cardNumber\":\"" + cardNumber + "\"}";
        return send(authorized(CardController.DETAIL_ROUTE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    /**
     * Updates one card over the real route, naming the full Primary Account Number in the body.
     *
     * @param cardNumber   the full sixteen-digit card number
     * @param embossedName the cardholder name to write
     * @param expiryYear   the four-character expiry year
     * @param expiryMonth  the two-character expiry month
     * @param expiryDay    the two-character day, which the update path takes from the stored row
     * @param activeStatus the one-character active status
     * @return the status and the body the service answered with
     */
    private HttpResponse<String> updateCard(String cardNumber, String embossedName,
            String expiryYear, String expiryMonth, String expiryDay, String activeStatus) {
        String body = "{\"cardNumber\":\"" + cardNumber
                + "\",\"embossedName\":\"" + embossedName
                + "\",\"expiryYear\":\"" + expiryYear
                + "\",\"expiryMonth\":\"" + expiryMonth
                + "\",\"expiryDay\":\"" + expiryDay
                + "\",\"activeStatus\":\"" + activeStatus + "\"}";
        return send(authorized(CardController.BASE_PATH)
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
    }

    /**
     * Renames the seeded card, which is the one change every mutation test makes.
     *
     * @return the status and the body the service answered with
     */
    private HttpResponse<String> renameTheSeededCard() {
        return updateCard(SEEDED_CARD_NUMBER, UPDATED_EMBOSSED_NAME, SEEDED_EXPIRY_YEAR,
                SEEDED_EXPIRY_MONTH, SEEDED_EXPIRY_DAY, SEEDED_ACTIVE_STATUS);
    }

    /**
     * Builds a request carrying the administrator credential and a JavaScript Object Notation
     * (JSON) content type.
     *
     * @param pathAndQuery the path below the service root, with any query string
     * @return a builder the caller finishes with a method and a body
     */
    private HttpRequest.Builder authorized(String pathAndQuery) {
        String credential = Base64.getEncoder().encodeToString(
                (ADMIN_NAME + ":" + ADMIN_SECRET).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + servicePort + pathAndQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Basic " + credential)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    /**
     * Sends one request and answers with the status and the body as text.
     *
     * <p>The body arrives as text. Every assertion about what an answer must not carry reads the
     * serialized form, so a property added later cannot slip past a field-by-field check.
     *
     * @param request the request to send
     * @return the response, with its body as text
     */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException failed) {
            throw new IllegalStateException("the request did not reach the service", failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the request was interrupted", interrupted);
        }
    }

    /**
     * Parses one payload and answers with its top-level property names.
     *
     * @param payload the event text as the broker carried it
     * @return the property names, in the order the document declares them
     */
    private static Set<String> topLevelPropertiesOf(String payload) {
        JsonNode event = MAPPER.readTree(payload);
        Set<String> names = new LinkedHashSet<>();
        event.propertyNames().forEach(names::add);
        return names;
    }

    /**
     * Builds one valid card event and lets a caller replace the masked card number.
     *
     * @param maskedCardNumber the value to place in the masked component
     * @return an event carrying that value and valid values everywhere else
     */
    private static CardUpdated eventCarrying(String maskedCardNumber) {
        return new CardUpdated(UUID.randomUUID(), CardUpdated.EVENT_TYPE,
                CardUpdated.SCHEMA_VERSION, Instant.parse("2024-01-01T00:00:00Z"),
                SEEDED_ACCOUNT_ID, maskedCardNumber, SEEDED_ACCOUNT_ID,
                SEEDED_EXPIRATION_DATE.toString(), SEEDED_ACTIVE_STATUS);
    }

    /**
     * The wire form one card event takes, read from the broker rather than from a serializer.
     *
     * <p>{@code schemas/card-updated-v2.json} closes its property set, so a wrapped envelope would
     * be refused before it reached a topic. The assertions below read the record the broker
     * carried, which is the only form a consumer ever sees.
     */
    @Nested
    @DisplayName("The wire form: nine flat properties, one number, no nesting key")
    class TheWireForm {

        /**
         * One card event carries exactly the nine named properties at its top level.
         *
         * <p>Set equality rather than containment, so a tenth property fails the assertion. Five
         * names come from {@link EventEnvelope} and four carry the card, whose widths come from
         * {@code app/cpy/CVACT02Y.cpy:L5-L10}.
         */
        @Test
        @DisplayName("nine top-level properties, and no envelope nesting key")
        void oneEventCarriesNineTopLevelPropertiesAndNoNestingKey() {
            assertEquals(200, renameTheSeededCard().statusCode(),
                    "the seeded card accepts a rename");
            Set<String> names = topLevelPropertiesOf(theOneRecordPublished().value());

            assertAll(
                    () -> assertEquals(WIRE_PROPERTIES, names,
                            "the event carries the nine properties its contract requires"),
                    () -> assertFalse(names.contains(NESTING_KEY),
                            "the five envelope fields sit beside the card fields, not below a key"));
        }

        /**
         * The envelope names the card event type and the contract version the schema fixes.
         *
         * <p>{@code eventType} routes the record and {@code schemaVersion} tells a consumer which
         * document describes it, so a consumer added later reads both without parsing the payload.
         */
        @Test
        @DisplayName("eventType and schemaVersion carry the values the contract fixes")
        void theEnvelopeNamesTheCardEventTypeAndItsContractVersion() {
            assertEquals(200, renameTheSeededCard().statusCode(),
                    "the seeded card accepts a rename");
            JsonNode event = MAPPER.readTree(theOneRecordPublished().value());

            assertAll(
                    () -> assertEquals(CardUpdated.EVENT_TYPE, event.get("eventType").stringValue(),
                            "the routing discriminator names the card event"),
                    () -> assertEquals(CardUpdated.SCHEMA_VERSION,
                            event.get("schemaVersion").intValue(),
                            "the contract version is the one the shipped document fixes"),
                    () -> assertTrue(event.get("aggregateId").stringValue()
                                    .matches(EventEnvelope.AGGREGATE_ID_PATTERN),
                            "the aggregate identifier is eleven decimal digits"),
                    () -> assertEquals(event.get("eventId").stringValue(),
                            UUID.fromString(event.get("eventId").stringValue()).toString(),
                            "the event identifier a consumer deduplicates on is a canonical"
                                    + " universally unique identifier"));
        }

        /**
         * The contract version is the only number, and no money property exists.
         *
         * <p>The card record carries no monetary field, so every other value travels as text.
         * Where this platform does carry an amount, it writes the amount as a decimal string.
         */
        @Test
        @DisplayName("schemaVersion is the only number, and no amount property exists")
        void theContractVersionIsTheOnlyNumberTheEventCarries() {
            assertEquals(200, renameTheSeededCard().statusCode(),
                    "the seeded card accepts a rename");
            JsonNode event = MAPPER.readTree(theOneRecordPublished().value());

            List<String> numeric = new ArrayList<>();
            List<String> monetary = new ArrayList<>();
            for (Map.Entry<String, JsonNode> property : event.properties()) {
                if (property.getValue().isNumber()) {
                    numeric.add(property.getKey());
                }
                String lowered = property.getKey().toLowerCase(Locale.ROOT);
                if (lowered.contains("amount") || lowered.contains("balance")
                        || lowered.contains("total")) {
                    monetary.add(property.getKey());
                }
            }

            assertAll(
                    () -> assertEquals(List.of("schemaVersion"), numeric,
                            "the contract version is the only value that travels as a number"),
                    () -> assertTrue(monetary.isEmpty(),
                            "a card event names no amount, no balance and no total"));
        }
    }

    /**
     * The broker message key, and where its eleven characters come from.
     *
     * <p>{@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} is the declared width
     * authority, and {@code CC-ACCT-ID PIC X(11)} at {@code app/cpy/CVCRD01Y.cpy:L34-L36} is the
     * text-primary working-storage field the target column follows. Keying on the account holds
     * every event of one account on one partition and in store order.
     */
    @Nested
    @DisplayName("The message key is the eleven-digit account identifier")
    class TheMessageKey {

        /** The key is the account identifier the payload also carries, twice. */
        @Test
        @DisplayName("the key equals the aggregate identifier and the payload account identifier")
        void theKeyEqualsTheAccountIdentifierThePayloadCarries() {
            assertEquals(200, renameTheSeededCard().statusCode(),
                    "the seeded card accepts a rename");
            ConsumerRecord<String, String> published = theOneRecordPublished();
            JsonNode event = MAPPER.readTree(published.value());

            assertAll(
                    () -> assertEquals(SEEDED_ACCOUNT_ID, published.key(),
                            "the key is the account identifier of the seeded card"),
                    () -> assertTrue(published.key().matches(EventEnvelope.AGGREGATE_ID_PATTERN),
                            "the key is eleven decimal digits, leading zeros included"),
                    () -> assertEquals(published.key(), event.get("aggregateId").stringValue(),
                            "the key and the aggregate identifier are one value"),
                    () -> assertEquals(published.key(), event.get("accountId").stringValue(),
                            "the key and the payload account identifier are one value"));
        }

        /** The key is not the card number, and carries none of its digits. */
        @Test
        @DisplayName("the key is not the card number")
        void theKeyIsNotTheCardNumber() {
            assertEquals(200, renameTheSeededCard().statusCode(),
                    "the seeded card accepts a rename");
            ConsumerRecord<String, String> published = theOneRecordPublished();

            assertAll(
                    () -> assertFalse(SEEDED_CARD_NUMBER.equals(published.key()),
                            "a card number as the key would spread one account over partitions"),
                    () -> assertFalse(published.key().contains(SEEDED_CARD_NUMBER),
                            "the key holds no part of the Primary Account Number"),
                    () -> assertEquals(cardUpdatedTopic, published.topic(),
                            "the record reached the topic configuration names"));
        }
    }

    /**
     * Masking, and the order that makes it work.
     *
     * <p>No CardDemo program masks a card number. {@code app/bms/COCRDSL.bms:L99} declares the card
     * detail field at {@code LENGTH=16} and leaves it unprotected, so all sixteen digits render on
     * the terminal. Masking is an addition, and it happens after the lookup.
     */
    @Nested
    @DisplayName("Masking happens after the lookup, never before it")
    class Masking {

        /**
         * The full card number resolves the row while every answer carries the masked form.
         *
         * <p>The card table keys on all sixteen characters, so a masked key matches no row.
         */
        @Test
        @DisplayName("the full Primary Account Number resolves the card, and answers carry the mask")
        void theFullCardNumberResolvesTheCardWhileAnswersCarryTheMask() {
            HttpResponse<String> updated = renameTheSeededCard();
            assertEquals(200, updated.statusCode(),
                    "the full Primary Account Number reached the repository and found the row");
            assertEquals(UpdateOutcome.UPDATED.name(),
                    MAPPER.readTree(updated.body()).get("outcome").stringValue(),
                    "the row the full card number resolved was rewritten");

            JsonNode published = MAPPER.readTree(theOneRecordPublished().value());
            HttpResponse<String> read = readCard(SEEDED_ACCOUNT_ID, SEEDED_CARD_NUMBER);

            assertAll(
                    () -> assertEquals(MASKED_CARD_NUMBER,
                            published.get("maskedCardNumber").stringValue(),
                            "the payload carries twelve mask characters and the last four digits"),
                    () -> assertFalse(published.get("maskedCardNumber").stringValue()
                                    .equals(SEEDED_CARD_NUMBER),
                            "the payload carries no full Primary Account Number"),
                    () -> assertEquals(200, read.statusCode(),
                            "the read route also resolves the card on all sixteen characters"),
                    () -> assertTrue(read.body().contains(MASKED_CARD_NUMBER),
                            "the read answer carries the masked form"),
                    () -> assertFalse(read.body().contains(SEEDED_CARD_NUMBER),
                            "the read answer carries no full Primary Account Number"));
        }

        /**
         * The masker produces the one form the canonical constructor accepts.
         *
         * <p>{@code PanMasker} is final, carries a private constructor and holds only static
         * members, so the call below is static and no bean is injected.
         */
        @Test
        @DisplayName("the masker produces the form the canonical constructor accepts")
        void theMaskerProducesTheFormTheCanonicalConstructorAccepts() {
            String masked = PanMasker.maskCardNumber(SEEDED_CARD_NUMBER);

            assertAll(
                    () -> assertEquals(MASKED_CARD_NUMBER, masked,
                            "the seeded card masks to its last four digits"),
                    () -> assertTrue(masked.matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                            "the masked value matches the pattern the contract fixes"),
                    () -> assertEquals(CardUpdated.MASKED_CARD_NUMBER_LENGTH, masked.length(),
                            "masking preserves the width of CARD-NUM PIC X(16)"),
                    () -> assertEquals(masked, eventCarrying(masked).maskedCardNumber(),
                            "the constructor accepts the value the masker produced"));
        }

        /** The canonical constructor refuses a full number, a partly masked one and a short one. */
        @Test
        @DisplayName("the canonical constructor refuses every value that is not fully masked")
        void theCanonicalConstructorRefusesEveryValueThatIsNotFullyMasked() {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> eventCarrying(SEEDED_CARD_NUMBER),
                            "a full sixteen-digit Primary Account Number is refused"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> eventCarrying(PARTLY_MASKED_CARD_NUMBER),
                            "a partly masked value is refused"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> eventCarrying(UNDER_MASKED_CARD_NUMBER),
                            "a value carrying eleven mask characters is refused"));
        }
    }

    /**
     * The card verification value, and the one row every assertion here is scoped to.
     *
     * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} is stored in the clear,
     * and {@code app/cbl/COCRDUPC.cbl:L1503} compares it first in the six-field change check. The
     * column holds the value and no outbound shape carries it.
     *
     * <p>Scope matters here. The three digits of the first row of
     * {@code app/data/ASCII/carddata.txt} also sit inside the card number of its forty-seventh row.
     * A search across many rows would therefore report a match the field under test never produced.
     * Account {@value #SEEDED_ACCOUNT_ID} holds exactly one card, so the list answer below carries
     * one row.
     */
    @Nested
    @DisplayName("The card verification value is stored and never emitted")
    class TheCardVerificationValue {

        /** Every serialized answer and the published payload are free of the three digits. */
        @Test
        @DisplayName("the stored value reaches no list, read, update or event payload")
        void theStoredValueReachesNoOutboundShape() {
            assertEquals(SEEDED_VERIFICATION_VALUE, storedVerificationValue(),
                    "the seeded row holds the three digits the assertions look for");

            HttpResponse<String> updated = renameTheSeededCard();
            assertEquals(200, updated.statusCode(), "the seeded card accepts a rename");
            String payload = theOneRecordPublished().value();
            HttpResponse<String> read = readCard(SEEDED_ACCOUNT_ID, SEEDED_CARD_NUMBER);
            HttpResponse<String> listed = listCardsOfAccount(SEEDED_ACCOUNT_ID);

            assertAll(
                    () -> assertEquals(200, read.statusCode(), "the read answered one card"),
                    () -> assertEquals(200, listed.statusCode(), "the list answered one page"),
                    () -> assertFalse(payload.contains(SEEDED_VERIFICATION_VALUE),
                            "the published payload holds no card verification value"),
                    () -> assertFalse(updated.body().contains(SEEDED_VERIFICATION_VALUE),
                            "the update answer holds no card verification value"),
                    () -> assertFalse(read.body().contains(SEEDED_VERIFICATION_VALUE),
                            "the read answer holds no card verification value"),
                    () -> assertFalse(listed.body().contains(SEEDED_VERIFICATION_VALUE),
                            "the one-row page holds no card verification value"));
        }

        /** No log line this service writes carries the three digits either. */
        @Test
        @DisplayName("the stored value reaches no structured log line")
        void theStoredValueReachesNoStructuredLogLine() {
            assertEquals(SEEDED_VERIFICATION_VALUE, storedVerificationValue(),
                    "the seeded row holds the three digits the assertions look for");

            ListAppender<ILoggingEvent> recorder = new ListAppender<>();
            Logger serviceLogger = (Logger) LoggerFactory.getLogger(SERVICE_LOGGER);
            recorder.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
            recorder.start();
            serviceLogger.addAppender(recorder);
            try {
                assertEquals(200, renameTheSeededCard().statusCode(),
                        "the seeded card accepts a rename");
                theOneRecordPublished();
                assertEquals(200, readCard(SEEDED_ACCOUNT_ID, SEEDED_CARD_NUMBER).statusCode(),
                        "the read answered one card");
                assertEquals(200, listCardsOfAccount(SEEDED_ACCOUNT_ID).statusCode(),
                        "the list answered one page");
            } finally {
                serviceLogger.detachAppender(recorder);
                recorder.stop();
            }

            List<String> lines = recorder.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .toList();
            List<String> leaking = lines.stream()
                    .filter(line -> withoutOpaqueIdentifiers(line)
                            .contains(SEEDED_VERIFICATION_VALUE))
                    .toList();

            assertAll(
                    () -> assertFalse(lines.isEmpty(),
                            "the three requests wrote log lines, so the search had text to read"),
                    () -> assertTrue(leaking.isEmpty(),
                            "no log line holds a card verification value, and these do: "
                                    + leaking));
        }

        /**
         * Removes the two opaque identifiers this service logs on purpose.
         *
         * <p>A card verification value is three decimal digits, and three digits land inside a
         * randomly generated identifier often. {@code outbox/OutboxWriter} logs the event
         * identifier, and one run of this method read
         * {@code Stored card event 034996bf-df74-4017-b255-115782747004 of type CardUpdated}, whose
         * last group ends in the three digits the seeded row holds. That is a coincidence of
         * randomness and not a leak, and left in the text it fails this assertion on roughly one
         * run in five.
         *
         * <p>Two shapes come out: the identifier as a Universally Unique Identifier (UUID), and the
         * sixty-four hexadecimal characters of a card token. Both are values this service is
         * documented to log and neither is a place a card verification value could hide, because a
         * leak reaches a log line as a field of the row rather than as a run of an identifier. A run
         * of decimal digits is left where it is, so a line carrying a whole record still fails.
         *
         * @param line one formatted log line
         * @return the line with those two shapes removed
         */
        private String withoutOpaqueIdentifiers(String line) {
            return line.replaceAll(
                            "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
                            "<event-id>")
                    .replaceAll("\\b[0-9a-f]{64}\\b", "<card-token>");
        }

        /**
         * Reads the stored card verification value straight from the migrated schema.
         *
         * @return the three digits column {@code card_verification_value} holds for the seeded card
         */
        private String storedVerificationValue() {
            String stored = jdbc.queryForObject("SELECT card_verification_value FROM "
                    + MIGRATED_SCHEMA + ".card WHERE card_number = ?", String.class,
                    SEEDED_CARD_NUMBER);
            return stored == null ? "" : stored.trim();
        }
    }

    /**
     * How many events one request produces.
     *
     * <p>The card service is a supporting service: it publishes on a state change and on nothing
     * else. {@code app/cbl/COCRDUPC.cbl:L1503-L1511} is where the source detects an unchanged
     * record and skips its rewrite, and the target skips the event for the same case.
     */
    @Nested
    @DisplayName("One event per state change, and none otherwise")
    class OneEventPerStateChange {

        /** A committed rename publishes exactly one event, counted rather than sampled. */
        @Test
        @DisplayName("one committed update publishes exactly one event")
        void oneCommittedUpdatePublishesExactlyOneEvent() {
            assertEquals(200, renameTheSeededCard().statusCode(),
                    "the seeded card accepts a rename");

            ConsumerRecord<String, String> published = theOneRecordPublished();
            assertEquals(CardUpdated.EVENT_TYPE,
                    MAPPER.readTree(published.value()).get("eventType").stringValue(),
                    "the one record is a card update event");
        }

        /** Listing cards changes nothing, so it publishes nothing. */
        @Test
        @DisplayName("a card list publishes nothing")
        void aCardListPublishesNothing() {
            assertEquals(200, listCardsOfAccount(SEEDED_ACCOUNT_ID).statusCode(),
                    "the list answered one page");
            sweepTheOutboxNow();

            assertAll(
                    () -> assertEquals(0L, inNewTransaction(outboxEvents::count),
                            "a card list leaves no event row"),
                    () -> assertTrue(recordsDuring(QUIET_PERIOD).isEmpty(),
                            "a read of the card table is not a state change"));
        }

        /** Reading one card changes nothing, so it publishes nothing. */
        @Test
        @DisplayName("a card read publishes nothing")
        void aCardReadPublishesNothing() {
            assertEquals(200, readCard(SEEDED_ACCOUNT_ID, SEEDED_CARD_NUMBER).statusCode(),
                    "the read answered one card");
            sweepTheOutboxNow();

            assertAll(
                    () -> assertEquals(0L, inNewTransaction(outboxEvents::count),
                            "a card read leaves no event row"),
                    () -> assertTrue(recordsDuring(QUIET_PERIOD).isEmpty(),
                            "a read of one card row is not a state change"));
        }

        /** Submitting the stored values changes nothing, so it publishes nothing. */
        @Test
        @DisplayName("an update that changes nothing publishes nothing")
        void anUpdateThatChangesNothingPublishesNothing() {
            HttpResponse<String> answer = updateCard(SEEDED_CARD_NUMBER, SEEDED_EMBOSSED_NAME,
                    SEEDED_EXPIRY_YEAR, SEEDED_EXPIRY_MONTH, SEEDED_EXPIRY_DAY,
                    SEEDED_ACTIVE_STATUS);
            sweepTheOutboxNow();

            assertAll(
                    () -> assertEquals(UpdateOutcome.NO_CHANGE_DETECTED.name(),
                            MAPPER.readTree(answer.body()).get("outcome").stringValue(),
                            "the submitted values are the values already stored"),
                    () -> assertEquals(0L, inNewTransaction(outboxEvents::count),
                            "an unchanged card leaves no event row"),
                    () -> assertTrue(recordsDuring(QUIET_PERIOD).isEmpty(),
                            "an unchanged card publishes no event"));
        }
    }

    /**
     * Where the publish happens, and where it does not.
     *
     * <p>{@code outbox/OutboxWriter} joins the transaction {@code domain/CardUpdateService} opened
     * and writes one row. {@code outbox/OutboxRelay} publishes that row on its next sweep, which
     * {@code @EnableScheduling} on {@code CardApplication} drives. Request handling publishes
     * nothing, and the two tests below read that from the row and from the topic.
     */
    @Nested
    @DisplayName("Publication comes from the relay, after the transaction commits")
    class PublicationAfterCommit {

        /**
         * The row exists and stays unpublished while the update transaction is open.
         *
         * <p>The test opens the transaction itself, so the window is not a race. An uncommitted row
         * is invisible to the relay sweep, which reads in a transaction of its own, so the topic
         * stays silent until the commit.
         */
        @Test
        @DisplayName("the row stays unpublished until the update transaction commits")
        void theRowStaysUnpublishedUntilTheUpdateTransactionCommits() {
            List<UUID> written = new ArrayList<>();

            newTransaction().executeWithoutResult(status -> {
                CardUpdateResponse answer = cardUpdateService.updateCard(new CardUpdateRequest(
                        SEEDED_CARD_NUMBER, UPDATED_EMBOSSED_NAME, SEEDED_EXPIRY_YEAR,
                        SEEDED_EXPIRY_MONTH, SEEDED_EXPIRY_DAY, SEEDED_ACTIVE_STATUS));
                assertEquals(UpdateOutcome.UPDATED, answer.outcome(),
                        "the update inside the open transaction rewrote the row");

                List<OutboxEventEntity> rows = outboxEvents.findAll();
                assertEquals(1, rows.size(), "the update wrote one event row");
                assertFalse(rows.getFirst().isPublished(),
                        "the row the writer stored is unpublished");
                written.add(rows.getFirst().getEventId());

                assertTrue(recordsDuring(QUIET_PERIOD).isEmpty(),
                        "request handling published nothing while the transaction was open");
            });

            ConsumerRecord<String, String> published = awaitRecords(1).getFirst();
            UUID rowEventId = written.getFirst();

            assertAll(
                    () -> assertEquals(rowEventId.toString(),
                            MAPPER.readTree(published.value()).get("eventId").stringValue(),
                            "the record the relay published is the row the transaction committed"),
                    () -> assertEquals(SEEDED_ACCOUNT_ID, published.key(),
                            "the relay used the account identifier the row recorded as its key"),
                    () -> Awaitility.await("the relay marking the row published")
                            .atMost(EVENT_ARRIVES_WITHIN)
                            .pollInterval(POLL_TIMEOUT)
                            .until(() -> inNewTransaction(() -> outboxEvents.findById(rowEventId)
                                    .map(OutboxEventEntity::isPublished).orElse(false))));
        }

        /**
         * The producer configuration, the relay and the record agree on one topic name.
         *
         * <p>{@code config/KafkaProducerConfig} binds the name from
         * {@code carddemo.kafka.topics.card-updated}, so a deployment that renames the topic renames
         * it in one place. The publisher on the path is the Kafka-backed implementation, which is
         * what makes the record above a real broker record.
         */
        @Test
        @DisplayName("the configured topic name is the topic the record reached")
        void theConfiguredTopicNameIsTheTopicTheRecordReached() {
            assertEquals(200, renameTheSeededCard().statusCode(),
                    "the seeded card accepts a rename");
            ConsumerRecord<String, String> published = theOneRecordPublished();

            assertAll(
                    () -> assertEquals(cardUpdatedTopic, producerConfig.cardUpdatedTopic(),
                            "the producer configuration bound the configured topic name"),
                    () -> assertEquals(cardUpdatedTopic, published.topic(),
                            "the record reached that topic and no other"),
                    () -> assertFalse(deadLetterTopic.equals(published.topic()),
                            "a card update never travels on the dead-letter topic"),
                    () -> assertInstanceOf(KafkaEventPublisher.class, publisher,
                            "the relay publishes through the Kafka-backed port implementation"));
        }
    }

    /**
     * The four diagnostic components this package owns, from {@code 01 ABEND-DATA} in
     * {@code app/cpy/CSMSG02Y.cpy}.
     *
     * <p>{@link DeadLetterMetadata} is never persisted. The relay carries it on the one diagnostic
     * it publishes to the dead-letter topic for a row it abandoned. The two assertions below cover
     * the normalisation its canonical constructor performs.
     */
    @Nested
    @DisplayName("Dead-letter diagnostic components normalise their own widths")
    class DeadLetterDiagnostics {

        /** An absent component becomes the empty string, mirroring {@code VALUE SPACES}. */
        @Test
        @DisplayName("an absent component becomes the empty string")
        void anAbsentComponentBecomesTheEmptyString() {
            DeadLetterMetadata metadata = DeadLetterMetadata.of(null, null, null, null);

            assertAll(
                    () -> assertEquals("", metadata.abendCode(), "an absent abend code is empty"),
                    () -> assertEquals("", metadata.culprit(), "an absent culprit is empty"),
                    () -> assertEquals("", metadata.reason(), "an absent reason is empty"),
                    () -> assertEquals("", metadata.message(), "an absent message is empty"));
        }

        /** An over-long component is cut to the width the copybook declares. */
        @Test
        @DisplayName("an over-long component is cut to its declared maximum")
        void anOverLongComponentIsCutToItsDeclaredMaximum() {
            String tooWide = "9".repeat(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH + 6);
            DeadLetterMetadata metadata = DeadLetterMetadata.of(tooWide, "OUTBOX",
                    "a reason", DeadLetterMetadata.DEFAULT_MESSAGE);

            assertAll(
                    () -> assertEquals(DeadLetterMetadata.ABEND_CODE_MAX_LENGTH,
                            metadata.abendCode().length(),
                            "the abend code holds the four characters the copybook declares"),
                    () -> assertEquals(DeadLetterMetadata.DEFAULT_MESSAGE, metadata.message(),
                            "a message inside its width survives unchanged"));
        }
    }
}
