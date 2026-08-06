package com.carddemo.notification.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.NewTopic;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the notification service against a real database while no broker answers, and asserts the
 * application context still reaches an active state.
 *
 * <p>One PostgreSQL container carries the schema. Flyway applies {@code db/migration} inside that
 * fresh container, and Hibernate then checks its Jakarta Persistence (JPA) mapping against the
 * migrated Data Definition Language (DDL) under {@code ddl-auto: validate}. The broker address
 * below routes nowhere, so no assertion here can pass by finding a broker.
 *
 * <p>Three absences let the context start, and each one is asserted.
 * {@code spring.kafka.admin.fail-fast} is set nowhere, {@link KafkaConsumerConfig} declares no
 * topic, and no listener container treats a missing topic as fatal. Remove any one of the three and
 * start-up aborts the moment the broker cannot be reached.
 *
 * <p>Provenance. Alert content comes from {@code 01 STATEMENT-LINES} at
 * {@code app/cbl/CBSTM03A.CBL:L85-L159}. The route this service takes on an infrastructure fault
 * replaces the four-line abend paragraph at {@code app/cbl/CBTRN02C.cbl:L707-L711}, which performs
 * no cleanup and is reached from more than twenty call sites. Shape only, no logic.
 *
 * <p>Scope. The sibling {@code NotificationApplicationTest} starts no context and leaves this proof
 * here, and every other test of this module builds a partial context. Meters belong to
 * {@code ObservabilityConfigTest}. Delivery-attempt arithmetic and the dead-letter topic route
 * belong to {@code KafkaConsumerConfigTest}. The readiness rules belong to
 * {@code ReadinessHealthConfigTest}, and column shapes to the {@code entity} test package.
 *
 * <p>Pinned here: Java 25, Spring Boot 4.1.0, JUnit Jupiter 6.0.3, Testcontainers 2.0.5,
 * Awaitility 4.3.0, and the image {@code postgres:18.4}.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "KAFKA_SASL_PASSWORD=a-generated-broker-value-for-this-test",
                "ADMIN_PASSWORD_HASH={noop}a-generated-admin-value-for-this-test",
                "USER_PASSWORD_HASH={noop}a-generated-user-value-for-this-test",
                "MONITORING_PASSWORD_HASH={noop}a-generated-monitoring-value-for-this-test",
                "management.server.port=${server.port}"
        })
@Testcontainers
@DisplayName("Notification service start-up while no broker answers")
class BrokerUnreachableStartupIT {

    /** Image tag of the database container, pinned to the platform version. */
    private static final String POSTGRES_IMAGE = "postgres:18.4";

    /** Database name, login name and password of the container, one value for all three. */
    private static final String CONTAINER_CREDENTIAL = "carddemo";

    /** Schema Flyway migrates and the persistence layer places every entity in. */
    private static final String SERVICE_SCHEMA = "notification_service";

    /** Table Flyway records applied migrations in, under {@link #SERVICE_SCHEMA}. */
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    /**
     * Host the broker client is pointed at. RFC 5737 reserves {@code 192.0.2.0/24} for
     * documentation, and no host on that block is routable. The address is numeric, so the client
     * builds and its consumer thread starts, and every connection attempt then times out.
     */
    private static final String UNREACHABLE_BROKER_HOST = "192.0.2.1";

    /** Port paired with {@link #UNREACHABLE_BROKER_HOST}. */
    private static final int UNREACHABLE_BROKER_PORT = 9092;

    /** Value {@link #BOOTSTRAP_SERVERS_PROPERTY} resolves to. */
    private static final String UNREACHABLE_BROKER =
            UNREACHABLE_BROKER_HOST + ":" + UNREACHABLE_BROKER_PORT;

    /** Bound on the one connection attempt this class makes, in milliseconds. */
    private static final int CONNECT_TIMEOUT_MS = 250;

    /** Bound on the wait for every listener container to report running. */
    private static final Duration LISTENERS_RUNNING_TIMEOUT = Duration.ofSeconds(20);

    /** Interval between two polls of that wait. */
    private static final Duration LISTENERS_RUNNING_POLL = Duration.ofMillis(200);

    private static final String BOOTSTRAP_SERVERS_PROPERTY = "spring.kafka.bootstrap-servers";
    private static final String ADMIN_FAIL_FAST_PROPERTY = "spring.kafka.admin.fail-fast";
    private static final String DDL_AUTO_PROPERTY = "spring.jpa.hibernate.ddl-auto";
    private static final String FLYWAY_CREATE_SCHEMAS_PROPERTY = "spring.flyway.create-schemas";
    private static final String DATASOURCE_URL_PROPERTY = "spring.datasource.url";
    private static final String DATASOURCE_USERNAME_PROPERTY = "spring.datasource.username";
    private static final String DATASOURCE_PASSWORD_PROPERTY = "spring.datasource.password";

    /** Prefix every topic key shares. */
    private static final String TOPIC_PREFIX = "carddemo.kafka.topics.";

    /** Prefix every consumer-group key shares. */
    private static final String GROUP_PREFIX = "carddemo.kafka.groups.";

    /** Name of the health contributor that asks the cluster, and of the one that reads listeners. */
    private static final String KAFKA_HEALTH_COMPONENT = "kafka";
    private static final String LISTENERS_HEALTH_COMPONENT = "listeners";

    /** Health group that carries no external dependency. */
    private static final String LIVENESS_HEALTH_GROUP = "liveness";

    /** Value {@link #DDL_AUTO_PROPERTY} resolves to. */
    private static final String SCHEMA_VALIDATION = "validate";

    /**
     * The six topic keys the shipped configuration carries, each with the value it resolves to.
     * Four name a consumed topic, one names the shared dead-letter topic, and one names the suffix a
     * source-specific dead-letter topic takes.
     */
    private static final Map<String, String> EXPECTED_TOPICS = topicExpectations();

    /** One consumer group per listener, so no listener moves another's offsets. */
    private static final Map<String, String> EXPECTED_GROUPS = groupExpectations();

    /**
     * The topic each consumer group reads, one pair per listener. The pairing places
     * {@code TransactionAuthorizedConsumer}, {@code TransactionPostedConsumer},
     * {@code FraudFlaggedConsumer} and {@code CustomerContextChangedConsumer} on one topic each.
     */
    private static final Map<String, String> EXPECTED_GROUP_TOPICS = Map.of(
            "notification-authorized", "transaction.authorized",
            "notification-posted", "transaction.posted",
            "notification-fraud", "fraud.assessed",
            "notification-customer", "customer.context-changed");

    /**
     * Keys no shipped file carries. {@code FraudFlagged} and {@code FraudCleared} both travel on
     * {@code fraud.assessed}, told apart by the envelope event type. A key naming either verdict
     * would name a topic that does not exist.
     */
    private static final List<String> ABSENT_TOPIC_KEYS =
            List.of(TOPIC_PREFIX + "fraud-flagged", TOPIC_PREFIX + "fraud-cleared");

    /** Event types the consumed-event counter carries one series for. */
    private static final List<String> CONSUMED_EVENT_TYPES = List.of(
            ObservabilityConfig.NotificationMetrics.EVENT_TRANSACTION_AUTHORIZED,
            ObservabilityConfig.NotificationMetrics.EVENT_TRANSACTION_POSTED,
            ObservabilityConfig.NotificationMetrics.EVENT_FRAUD_FLAGGED,
            ObservabilityConfig.NotificationMetrics.EVENT_FRAUD_CLEARED,
            ObservabilityConfig.NotificationMetrics.EVENT_CUSTOMER_CONTEXT_CHANGED,
            ObservabilityConfig.NotificationMetrics.UNKNOWN);

    /**
     * The database this context migrates and validates against. No broker container joins it, and
     * that absence is what this class asserts the consequences of.
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName(CONTAINER_CREDENTIAL)
            .withUsername(CONTAINER_CREDENTIAL)
            .withPassword(CONTAINER_CREDENTIAL);

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private ObservabilityConfig.NotificationMetrics metrics;

    @Autowired
    private DataSource dataSource;

    /**
     * Points the datasource at the container and the broker client at an address that routes
     * nowhere. Nothing else is overridden, so every Kafka, Flyway and persistence setting the
     * shipped {@code application.yml} carries is the one exercised.
     *
     * @param registrar registry the test context supplies for late-resolved values
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registrar) {
        registrar.add(DATASOURCE_URL_PROPERTY, BrokerUnreachableStartupIT::jdbcUrlOnServiceSchema);
        registrar.add(DATASOURCE_USERNAME_PROPERTY, POSTGRES::getUsername);
        registrar.add(DATASOURCE_PASSWORD_PROPERTY, POSTGRES::getPassword);
        registrar.add(BOOTSTRAP_SERVERS_PROPERTY, () -> UNREACHABLE_BROKER);
    }

    /**
     * Returns the container address with the service schema named on it.
     *
     * @return a connection string carrying {@code currentSchema=notification_service}
     */
    private static String jdbcUrlOnServiceSchema() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SERVICE_SCHEMA;
    }

    @Test
    @DisplayName("the context becomes active and running with no broker answering")
    void contextStartsWithoutABroker() {
        assertAll("started context",
                () -> assertTrue(context.isActive(),
                        "the application context is active"),
                () -> assertTrue(context.isRunning(),
                        "the application context is running"),
                () -> assertNotNull(context.getBean(KafkaConsumerConfig.class),
                        "the consume-side configuration is part of the started context"));
    }

    @Test
    @DisplayName("the broker address routes nowhere, so no assertion here found a broker")
    void brokerAddressIsUnreachable() {
        assertEquals(UNREACHABLE_BROKER, environment.getProperty(BOOTSTRAP_SERVERS_PROPERTY),
                BOOTSTRAP_SERVERS_PROPERTY + " names the address this class supplied");

        assertThrows(IOException.class, () -> {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(
                        UNREACHABLE_BROKER_HOST, UNREACHABLE_BROKER_PORT), CONNECT_TIMEOUT_MS);
            }
        }, "a connection to " + UNREACHABLE_BROKER + " fails inside "
                + CONNECT_TIMEOUT_MS + " milliseconds");
    }

    @Test
    @DisplayName("no start-up admin check and no topic declaration exist to reach a broker")
    void nothingDeclaredContactsTheBrokerAtStartUp() {
        assertAll("absences that let start-up finish",
                () -> assertFalse(environment.containsProperty(ADMIN_FAIL_FAST_PROPERTY),
                        ADMIN_FAIL_FAST_PROPERTY + " resolves nowhere, so the administrator "
                                + "verifies no connection while the context refreshes"),
                () -> assertEquals(Set.of(),
                        context.getBeansOfType(NewTopic.class).keySet(),
                        "no topic is declared, so no topic is created at start-up"),
                () -> assertEquals(Set.of(),
                        context.getBeansOfType(KafkaAdmin.NewTopics.class).keySet(),
                        "no grouped topic declaration slips past the single-topic assertion"));
    }

    @Test
    @DisplayName("liveness stays up while the broker contributor reports down")
    void livenessIsUpAndTheBrokerContributorIsDown() {
        awaitRunningContainers();
        HealthDescriptor liveness = healthEndpoint.healthForPath(LIVENESS_HEALTH_GROUP);
        Map<String, HealthDescriptor> components = healthComponents();

        assertAll("health while the broker is away",
                () -> assertNotNull(liveness, LIVENESS_HEALTH_GROUP + " answers"),
                () -> assertEquals(Status.UP, liveness.getStatus(),
                        LIVENESS_HEALTH_GROUP + " carries no external dependency and stays up"),
                () -> assertEquals(Status.DOWN, statusOf(components, KAFKA_HEALTH_COMPONENT),
                        KAFKA_HEALTH_COMPONENT + " asks the cluster and reports the outage"),
                () -> assertEquals(Status.UP, statusOf(components, LISTENERS_HEALTH_COMPONENT),
                        LISTENERS_HEALTH_COMPONENT + " reports every registered container running"));
    }

    @Test
    @DisplayName("four listener containers register and run under four distinct groups")
    void everyListenerRegistersAndRuns() {
        Collection<MessageListenerContainer> containers = awaitRunningContainers();
        Map<String, MessageListenerContainer> byGroup = new LinkedHashMap<>();
        for (MessageListenerContainer container : containers) {
            byGroup.put(container.getGroupId(), container);
        }

        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertEquals(EXPECTED_GROUPS.size(), containers.size(),
                "one listener container is registered per declared consumer group"));
        checks.add(() -> assertEquals(new TreeSet<>(EXPECTED_GROUPS.values()),
                new TreeSet<>(byGroup.keySet()),
                "the registered groups are the declared groups, all distinct"));
        for (MessageListenerContainer container : containers) {
            checks.add(() -> assertTrue(container.isRunning(), "container "
                    + container.getListenerId() + " runs while the broker is away"));
            checks.add(() -> assertEquals(1, topicsOf(container).length,
                    "container " + container.getListenerId() + " reads one topic"));
            checks.add(() -> assertEquals(
                    EXPECTED_GROUP_TOPICS.get(container.getGroupId()), topicsOf(container)[0],
                    "group " + container.getGroupId() + " reads the topic paired with it"));
        }
        assertAll("registered listener containers", checks);
    }

    @Test
    @DisplayName("every container acknowledges on its own writes and tolerates a missing topic")
    void everyContainerAcknowledgesManuallyAndToleratesAMissingTopic() {
        Collection<MessageListenerContainer> containers = awaitRunningContainers();

        List<Executable> checks = new ArrayList<>();
        for (MessageListenerContainer container : containers) {
            ContainerProperties properties = container.getContainerProperties();
            checks.add(() -> assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE,
                    properties.getAckMode(), "container " + container.getListenerId()
                            + " commits the offset at the acknowledgement its listener makes"));
            checks.add(() -> assertFalse(properties.isMissingTopicsFatal(),
                    "container " + container.getListenerId() + " starts without asking a broker "
                            + "whether its topic exists"));
        }
        assertAll("container properties resolved from the shipped configuration", checks);
    }

    @Test
    @DisplayName("no listener ran, so every consumed-event series stays at zero")
    void noListenerConsumedAnything() {
        List<Executable> checks = new ArrayList<>();
        for (String eventType : CONSUMED_EVENT_TYPES) {
            checks.add(() -> assertEquals(0.0d, metrics.eventsConsumed(eventType).count(), 0.0d,
                    "no " + eventType + " record reached a listener"));
        }
        assertAll("consumed-event counters", checks);
    }

    @Test
    @DisplayName("six topic keys and four group keys resolve, and no invented key resolves")
    void streamNamesResolveToTheirShippedValues() {
        List<Executable> checks = new ArrayList<>();
        EXPECTED_TOPICS.forEach((key, value) -> checks.add(() ->
                assertEquals(value, environment.getProperty(key), key + " resolves")));
        EXPECTED_GROUPS.forEach((key, value) -> checks.add(() ->
                assertEquals(value, environment.getProperty(key), key + " resolves")));
        for (String absent : ABSENT_TOPIC_KEYS) {
            checks.add(() -> assertFalse(environment.containsProperty(absent),
                    absent + " names a topic that does not exist"));
        }
        assertAll("stream names", checks);
    }

    @Test
    @DisplayName("Flyway migrated the service schema and Hibernate validated against it")
    void flywayMigratedTheSchemaAndHibernateValidatedIt() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer schemas = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
                Integer.class, SERVICE_SCHEMA);
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM " + SERVICE_SCHEMA + "." + FLYWAY_HISTORY_TABLE
                        + " WHERE success = true", Integer.class);

        assertAll("migrated database",
                () -> assertEquals(Integer.valueOf(1), schemas,
                        "Flyway created the schema " + SERVICE_SCHEMA + " inside a fresh container"),
                () -> assertNotNull(applied, FLYWAY_HISTORY_TABLE + " answers"),
                () -> assertTrue(applied > 0,
                        FLYWAY_HISTORY_TABLE + " records at least one applied migration"),
                () -> assertEquals(SCHEMA_VALIDATION, environment.getProperty(DDL_AUTO_PROPERTY),
                        DDL_AUTO_PROPERTY + " checks the mapping and writes no schema"),
                () -> assertEquals("true", environment.getProperty(FLYWAY_CREATE_SCHEMAS_PROPERTY),
                        FLYWAY_CREATE_SCHEMAS_PROPERTY
                                + " creates the schema, so this test needs no database setup"));
    }

    /**
     * Waits for every registered listener container to report running, then returns them.
     *
     * <p>A container launches its consumer thread and retries the connection on its own schedule,
     * so the first poll can arrive before the last container has reported.
     *
     * @return the registered containers, each running
     */
    private Collection<MessageListenerContainer> awaitRunningContainers() {
        Awaitility.await("every registered listener container reports running")
                .atMost(LISTENERS_RUNNING_TIMEOUT)
                .pollInterval(LISTENERS_RUNNING_POLL)
                .until(() -> {
                    Collection<MessageListenerContainer> current =
                            listenerRegistry.getListenerContainers();
                    return current.size() == EXPECTED_GROUPS.size()
                            && current.stream().allMatch(MessageListenerContainer::isRunning);
                });
        return listenerRegistry.getListenerContainers();
    }

    /**
     * Returns the component map of the default health group.
     *
     * @return one descriptor per registered contributor
     */
    private Map<String, HealthDescriptor> healthComponents() {
        HealthDescriptor overall = healthEndpoint.health();
        CompositeHealthDescriptor composite = assertInstanceOf(CompositeHealthDescriptor.class,
                overall, "the health endpoint answers with a component map");
        return composite.getComponents();
    }

    /**
     * Returns the status one named contributor reported.
     *
     * @param components the component map read from the health endpoint
     * @param name       name of the contributor
     * @return the reported status
     */
    private static Status statusOf(Map<String, HealthDescriptor> components, String name) {
        HealthDescriptor descriptor = components.get(name);
        assertNotNull(descriptor, name + " is a registered health contributor");
        return descriptor.getStatus();
    }

    /**
     * Returns the topics one container subscribes to.
     *
     * @param container a registered listener container
     * @return the configured topic names
     */
    private static String[] topicsOf(MessageListenerContainer container) {
        String[] topics = container.getContainerProperties().getTopics();
        assertNotNull(topics, "container " + container.getListenerId() + " names its topics");
        return topics;
    }

    /**
     * Builds the topic keys and the values the shipped configuration resolves them to.
     *
     * @return six keys, each mapped to its value
     */
    private static Map<String, String> topicExpectations() {
        Map<String, String> topics = new LinkedHashMap<>();
        topics.put(TOPIC_PREFIX + "transaction-authorized", "transaction.authorized");
        topics.put(TOPIC_PREFIX + "transaction-posted", "transaction.posted");
        topics.put(TOPIC_PREFIX + "fraud-assessed", "fraud.assessed");
        topics.put(TOPIC_PREFIX + "customer-context-changed", "customer.context-changed");
        topics.put(TOPIC_PREFIX + "dead-letter", "carddemo.dead-letter");
        topics.put(TOPIC_PREFIX + "dead-letter-suffix", ".DLT");
        return Map.copyOf(topics);
    }

    /**
     * Builds the consumer-group keys and the values the shipped configuration resolves them to.
     *
     * @return four keys, each mapped to its value
     */
    private static Map<String, String> groupExpectations() {
        Map<String, String> groups = new LinkedHashMap<>();
        groups.put(GROUP_PREFIX + "transaction-authorized", "notification-authorized");
        groups.put(GROUP_PREFIX + "transaction-posted", "notification-posted");
        groups.put(GROUP_PREFIX + "fraud-assessed", "notification-fraud");
        groups.put(GROUP_PREFIX + "customer-context-changed", "notification-customer");
        return Map.copyOf(groups);
    }
}
