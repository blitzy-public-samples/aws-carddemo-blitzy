package com.carddemo.fraud.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.carddemo.events.serde.JsonSchemaValidatingDeserializer;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;
import com.carddemo.fraud.FraudServiceDatabase;
import com.carddemo.fraud.TestIdentityPasswords;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Asserts the effective configuration of the fraud detection service while no broker answers.
 *
 * <p>The service is net new; no COBOL ancestor exists among the Common Business Oriented Language
 * programs of the source. One PostgreSQL container carries the schema, the broker client points at a
 * closed port, and the four credential values below stand in for variables the shipped file leaves
 * undefined. Every assertion reads a bean, a meter or a resolved property from the started context.
 * The shipped text belongs to {@code ShippedConfigurationContractTest} and the physical schema to
 * {@code EntitySchemaValidationIT}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "KAFKA_SASL_PASSWORD=not-a-real-broker-password",
                "ADMIN_PASSWORD_HASH=" + TestIdentityPasswords.ADMIN_PASSWORD_HASH,
                "USER_PASSWORD_HASH=" + TestIdentityPasswords.USER_PASSWORD_HASH,
                "MONITORING_PASSWORD_HASH=" + TestIdentityPasswords.MONITORING_PASSWORD_HASH,
                "management.server.port=${server.port}"
        })
@DisplayName("Effective configuration of the fraud detection service with no broker reachable")
class ConfigurationInvariantsIT {

    /** Host and port the broker client is pointed at, where nothing listens. */
    private static final String UNREACHABLE_BROKER = "localhost:1";

    /** Schema Flyway migrates and the persistence layer places every entity in. */
    private static final String SERVICE_SCHEMA = "fraud_service";

    /** Monitoring login name the shipped file defaults to. */
    private static final String MONITORING_USERNAME = "monitor01";

    /** Monitoring password this class supplies, matching the inert hash above. */
    private static final String MONITORING_PASSWORD = TestIdentityPasswords.MONITORING_PASSWORD;

    private static final String BOOTSTRAP_SERVERS_PROPERTY = "spring.kafka.bootstrap-servers";
    private static final String ADMIN_FAIL_FAST_PROPERTY = "spring.kafka.admin.fail-fast";
    private static final String ACK_MODE_PROPERTY = "spring.kafka.listener.ack-mode";
    private static final String CONSUMER_KEY_DESERIALIZER_PROPERTY =
            "spring.kafka.consumer.key-deserializer";
    private static final String CONSUMER_VALUE_DESERIALIZER_PROPERTY =
            "spring.kafka.consumer.value-deserializer";
    private static final String CONSUMER_DELEGATE_PROPERTY =
            "spring.kafka.consumer.properties.spring.deserializer.value.delegate.class";
    private static final String EXPOSURE_INCLUDE_PROPERTY =
            "management.endpoints.web.exposure.include";
    private static final String DDL_AUTO_PROPERTY = "spring.jpa.hibernate.ddl-auto";
    private static final String DEFAULT_SCHEMA_PROPERTY =
            "spring.jpa.properties.hibernate.default_schema";
    private static final String KEYWORD_QUOTING_PROPERTY =
            "spring.jpa.properties.hibernate.auto_quote_keyword";
    private static final String FLYWAY_CREATE_SCHEMAS_PROPERTY = "spring.flyway.create-schemas";
    private static final String SERVER_PORT_PROPERTY = "server.port";

    /** Value {@link #DDL_AUTO_PROPERTY} resolves to. */
    private static final String SCHEMA_VALIDATION = "validate";

    /** Values {@link #DDL_AUTO_PROPERTY} must not resolve to. */
    private static final List<String> WRITING_DDL_AUTO_VALUES =
            List.of("update", "create", "create-drop", "none");

    /** Consumer group the listener of this service reads under. */
    private static final String CONSUMER_GROUP = "fraud-detection";

    /** Offset a new consumer group starts from. */
    private static final String OFFSET_RESET_EARLIEST = "earliest";

    /** Acknowledgement setting every service of this platform carries. */
    private static final String ACKS_FROM_ALL_REPLICAS = "all";

    /** Requests one producer may leave unacknowledged while production stays idempotent. */
    private static final String IN_FLIGHT_REQUESTS = "5";

    /** Bean name of the health contributor that reaches the broker. */
    private static final String KAFKA_HEALTH_INDICATOR = "kafkaHealthIndicator";

    /** Package every class this service declares sits under. */
    private static final String FRAUD_PACKAGE = "com.carddemo.fraud";

    private static final String METER_PREFIX = "carddemo.fraud";
    private static final String METER_EVENTS_CONSUMED = "carddemo.fraud.events.consumed";
    private static final String METER_ASSESSMENTS_PRODUCED = "carddemo.fraud.assessments.produced";
    private static final String METER_PROCESSING_LATENCY = "carddemo.fraud.processing.latency";
    private static final String METER_FAILURES = "carddemo.fraud.failures";
    private static final String METER_DEAD_LETTERS = "carddemo.fraud.dead.letters";
    private static final String METER_OUTBOX_ABANDONED = "carddemo.fraud.outbox.abandoned";
    private static final String METER_EVENTS_PUBLISHED = "carddemo.fraud.events.published";

    /**
     * Deliveries the idempotency guard refused as already processed.
     *
     * <p>A replay is acknowledged, writes no row and raises no failure, so it moves no other
     * series here and would be invisible without this one.
     */
    private static final String METER_DUPLICATES_SKIPPED = "carddemo.fraud.duplicates.skipped";

    /**
     * The two meters the request-surface filters register.
     *
     * <p>{@code config/RequestRateCeilingFilter} registers the throttle counter under five
     * {@code stage} tags, and {@code config/CrossSiteRequestFilter} registers one counter with no
     * tag of its own. Both register in their constructors, so both exist before the first refusal
     * and a dashboard reads zero rather than reading nothing at all.
     */
    private static final String METER_THROTTLED = "carddemo.fraud.requests.throttled";
    private static final String METER_CROSS_SITE_REFUSED =
            "carddemo.fraud.requests.cross.site.refused";

    /** Rows due for a publish attempt now, being the backlog this service has not yet published. */
    private static final String METER_OUTBOX_DUE = "carddemo.fraud.outbox.due";

    /** Seconds the longest-waiting due outbox row has waited, zero when none is due. */
    private static final String METER_OUTBOX_OLDEST_DUE_AGE =
            "carddemo.fraud.outbox.oldest.due.age";

    /** The twelve meter names this service registers. */
    private static final List<String> FRAUD_METER_NAMES = List.of(METER_EVENTS_CONSUMED,
            METER_ASSESSMENTS_PRODUCED, METER_PROCESSING_LATENCY, METER_FAILURES,
            METER_DEAD_LETTERS, METER_EVENTS_PUBLISHED, METER_OUTBOX_ABANDONED,
            METER_DUPLICATES_SKIPPED, METER_THROTTLED, METER_CROSS_SITE_REFUSED,
            METER_OUTBOX_DUE, METER_OUTBOX_OLDEST_DUE_AGE);

    /** The complete set of tag keys a meter of this service may carry. */
    private static final Set<String> ALLOWED_TAG_KEYS = Set.of("service", "outcome", "stage");

    /** Endpoint names the shipped exposure list holds, in order. */
    private static final List<String> EXPOSED_ENDPOINTS = List.of("health", "metrics", "prometheus");

    private static final String HEALTH_PATH = "/actuator/health";
    private static final String LIVENESS_PATH = "/actuator/health/liveness";
    private static final String READINESS_PATH = "/actuator/health/readiness";
    private static final String METRICS_PATH = "/actuator/metrics";
    private static final String PROMETHEUS_PATH = "/actuator/prometheus";

    /** Actuator paths the exposure list omits, which no caller reaches. */
    private static final List<String> UNEXPOSED_PATHS = List.of("/actuator/info", "/actuator/env",
            "/actuator/beans", "/actuator/loggers");

    private static final int STATUS_OK = 200;
    private static final int STATUS_UNAUTHORIZED = 401;
    private static final int STATUS_FORBIDDEN = 403;
    private static final int STATUS_SERVICE_UNAVAILABLE = 503;

    /** Rendered form of an aggregate health verdict of UP. */
    private static final String STATUS_UP_BODY = "\"status\":\"UP\"";

    /** Table names the migrated schema holds, none of which an actuator body names. */
    private static final List<String> TABLE_NAMES =
            List.of("fraud_assessment", "velocity_window", "processed_event", "outbox_event");

    /** Structured Query Language (SQL) fragments no actuator body carries. */
    private static final List<String> SQL_FRAGMENTS =
            List.of("select ", "insert into", "delete from", "create table", "alter table");

    /** Return types no bean method of this service declares. */
    private static final List<Class<?>> FORBIDDEN_BEAN_TYPES = List.of(KafkaAdmin.class,
            NewTopic.class, MeterRegistry.class, DataSource.class, EntityManagerFactory.class,
            PlatformTransactionManager.class, Flyway.class);

    /** Pattern an account identifier as a tag value matches. */
    private static final Pattern ACCOUNT_IDENTIFIER = Pattern.compile("^[0-9]{11}$");

    /** Pattern a card number or an equally long run of digits matches. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("[0-9]{12,}");

    /** Pattern a Universally Unique Identifier (UUID) matches. */
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * The one container the module fork runs, which this class reads a login from.
     *
     * <p>{@link FraudServiceDatabase} owns it and hands this class a database of its own inside
     * it. Nothing here starts or stops a container.
     */
    private static final PostgreSQLContainer POSTGRES = FraudServiceDatabase.container();

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private WebApplicationContext webContext;

    /**
     * Points the datasource at the container and the broker client at a port with no listener.
     *
     * @param registrar registry the Spring test context supplies
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registrar) {
        registrar.add("spring.datasource.url", ConfigurationInvariantsIT::jdbcUrlOnServiceSchema);
        registrar.add("spring.datasource.username", POSTGRES::getUsername);
        registrar.add("spring.datasource.password", POSTGRES::getPassword);
        registrar.add(BOOTSTRAP_SERVERS_PROPERTY, () -> UNREACHABLE_BROKER);
    }

    /** Returns the URL of this class's own database, with the service schema on the search path. */
    private static String jdbcUrlOnServiceSchema() {
        return FraudServiceDatabase.urlFor(ConfigurationInvariantsIT.class);
    }

    @Test
    @DisplayName("the context stays active while the broker address names a port with no listener")
    void theContextStaysActiveWithNoBrokerReachable() {
        assertAll(
                () -> assertNotNull(context, "the application context is injected"),
                () -> assertInstanceOf(ConfigurableApplicationContext.class, context,
                        "the injected context reports its own lifecycle"),
                () -> assertTrue(((ConfigurableApplicationContext) context).isActive(),
                        "the application context is active"),
                () -> assertEquals(UNREACHABLE_BROKER,
                        environment.getProperty(BOOTSTRAP_SERVERS_PROPERTY),
                        BOOTSTRAP_SERVERS_PROPERTY));
    }

    /**
     * Asserts health answers an unauthenticated caller, liveness reports UP, readiness reports DOWN,
     * and the broker contributor is the only one reporting DOWN.
     */
    @Test
    @DisplayName("health answers with no credential and the broker contributor alone reports DOWN")
    void healthAnswersWithNoCredentialAndTheBrokerContributorAloneReportsDown() throws Exception {
        MockHttpServletResponse root = anonymousGet(HEALTH_PATH);
        MockHttpServletResponse liveness = anonymousGet(LIVENESS_PATH);
        MockHttpServletResponse readiness = anonymousGet(READINESS_PATH);
        String livenessBody = liveness.getContentAsString();
        List<String> reportingDown = contributorsReporting(Status.DOWN);
        List<String> reportingUp = contributorsReporting(Status.UP);

        assertAll(
                () -> assertNotEquals(STATUS_UNAUTHORIZED, root.getStatus(),
                        () -> HEALTH_PATH + " demanded a credential: " + root.getStatus()),
                () -> assertNotEquals(STATUS_FORBIDDEN, root.getStatus(),
                        () -> HEALTH_PATH + " refused an unauthenticated caller: "
                                + root.getStatus()),
                () -> assertEquals(STATUS_OK, liveness.getStatus(),
                        () -> LIVENESS_PATH + " answered " + liveness.getStatus()),
                () -> assertTrue(livenessBody.contains(STATUS_UP_BODY),
                        () -> LIVENESS_PATH + " reported " + livenessBody),
                () -> assertEquals(STATUS_SERVICE_UNAVAILABLE, readiness.getStatus(),
                        () -> READINESS_PATH + " answered " + readiness.getStatus()),
                () -> assertEquals(List.of(KAFKA_HEALTH_INDICATOR), reportingDown,
                        () -> "contributors reporting DOWN: " + reportingDown),
                () -> assertFalse(reportingUp.isEmpty(),
                        () -> "contributors reporting UP: " + reportingUp));
    }

    @Test
    @DisplayName(ADMIN_FAIL_FAST_PROPERTY + " resolves to no value in the started context")
    void theAdminFailFastPropertyResolvesToNoValue() {
        assertNull(environment.getProperty(ADMIN_FAIL_FAST_PROPERTY),
                ADMIN_FAIL_FAST_PROPERTY + " resolves to no value");
    }

    /**
     * Asserts one listener container factory answers by type, its effective acknowledgement mode is
     * {@code MANUAL_IMMEDIATE}, and the shipped property string names the same mode.
     *
     * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
     */
    @Test
    @DisplayName("one listener container factory answers by type and acknowledges immediately")
    void theOneListenerContainerFactoryAcknowledgesImmediately() {
        String[] factoryNames =
                context.getBeanNamesForType(ConcurrentKafkaListenerContainerFactory.class);
        ContainerProperties.AckMode effective = context
                .getBean(ConcurrentKafkaListenerContainerFactory.class)
                .getContainerProperties()
                .getAckMode();
        String shipped = environment.getProperty(ACK_MODE_PROPERTY);
        String shippedAsMode = shipped == null ? null : shipped.toUpperCase(Locale.ROOT);

        assertAll(
                () -> assertEquals(1, factoryNames.length,
                        () -> "listener container factory beans: " + Arrays.toString(factoryNames)),
                () -> assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE, effective,
                        () -> "effective acknowledgement mode " + effective
                                + " against shipped property string " + shipped),
                () -> assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE.name(),
                        shippedAsMode,
                        () -> "effective acknowledgement mode " + effective
                                + " against shipped property string " + shipped));
    }

    /**
     * Asserts the consumer factory holds deserializer instances and carries no deserializer
     * class-name entry, while the shipped file names three such classes.
     */
    @Test
    @DisplayName("the consumer factory holds deserializer instances and no class-name entry")
    void theConsumerFactoryHoldsDeserializerInstancesAndNoClassNameEntry() throws Exception {
        ConsumerFactory<?, ?> factory = context.getBean(ConsumerFactory.class);
        Deserializer<?> value = factory.getValueDeserializer();
        Map<String, Object> settings = factory.getConfigurationProperties();
        Object delegate = delegateOf(assertInstanceOf(ErrorHandlingDeserializer.class, value,
                "the configured value deserializer instance"));

        assertAll(
                () -> assertInstanceOf(JsonSchemaValidatingDeserializer.class, delegate,
                        () -> "the wrapped delegate instance is " + delegate.getClass().getName()
                                + " against shipped property string "
                                + environment.getProperty(CONSUMER_DELEGATE_PROPERTY)),
                () -> assertInstanceOf(StringDeserializer.class, factory.getKeyDeserializer(),
                        "the configured key deserializer instance"),
                () -> assertFalse(
                        settings.containsKey(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG),
                        () -> "the factory carries "
                                + ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG
                                + " against shipped property string "
                                + environment.getProperty(CONSUMER_VALUE_DESERIALIZER_PROPERTY)),
                () -> assertFalse(settings.containsKey(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG),
                        () -> "the factory carries "
                                + ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG
                                + " against shipped property string "
                                + environment.getProperty(CONSUMER_KEY_DESERIALIZER_PROPERTY)),
                () -> assertFalse(
                        settings.containsKey(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS),
                        () -> "the factory carries "
                                + ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS
                                + " against shipped property string "
                                + environment.getProperty(CONSUMER_DELEGATE_PROPERTY)),
                () -> assertEquals(ErrorHandlingDeserializer.class.getName(),
                        environment.getProperty(CONSUMER_VALUE_DESERIALIZER_PROPERTY),
                        CONSUMER_VALUE_DESERIALIZER_PROPERTY),
                () -> assertEquals(StringDeserializer.class.getName(),
                        environment.getProperty(CONSUMER_KEY_DESERIALIZER_PROPERTY),
                        CONSUMER_KEY_DESERIALIZER_PROPERTY),
                () -> assertEquals(JsonSchemaValidatingDeserializer.class.getName(),
                        environment.getProperty(CONSUMER_DELEGATE_PROPERTY),
                        CONSUMER_DELEGATE_PROPERTY));
    }

    @Test
    @DisplayName("the consumer factory commits no offset automatically and starts at the earliest")
    void theConsumerFactoryCommitsNoOffsetAutomatically() {
        ConsumerFactory<?, ?> factory = context.getBean(ConsumerFactory.class);
        Map<String, Object> settings = factory.getConfigurationProperties();

        assertAll(
                () -> assertEquals("false",
                        text(settings.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)),
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG),
                () -> assertEquals(OFFSET_RESET_EARLIEST,
                        text(settings.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG),
                () -> assertEquals(CONSUMER_GROUP, text(settings.get(ConsumerConfig.GROUP_ID_CONFIG)),
                        ConsumerConfig.GROUP_ID_CONFIG));
    }

    @Test
    @DisplayName("the producer factory validates on publish, produces idempotently and waits for "
            + "every replica")
    void theProducerFactoryRequiresIdempotenceAndEveryReplica() {
        String[] factoryNames = context.getBeanNamesForType(ProducerFactory.class);
        ProducerFactory<?, ?> factory = context.getBean(ProducerFactory.class);
        Map<String, Object> settings = factory.getConfigurationProperties();

        assertAll(
                () -> assertEquals(1, factoryNames.length,
                        () -> "producer factory beans: " + Arrays.toString(factoryNames)),
                () -> assertInstanceOf(JsonSchemaValidatingSerializer.class,
                        factory.getValueSerializer(), "the configured value serializer instance"),
                () -> assertEquals("true",
                        text(settings.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)),
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG),
                () -> assertEquals(ACKS_FROM_ALL_REPLICAS,
                        text(settings.get(ProducerConfig.ACKS_CONFIG)), ProducerConfig.ACKS_CONFIG),
                () -> assertEquals(IN_FLIGHT_REQUESTS,
                        text(settings.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)),
                        ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
    }

    @Test
    @DisplayName("no topic provisioning, transaction manager or start-up runner bean exists")
    void noTopicProvisioningTransactionManagerOrRunnerBeanExists() {
        assertAll(
                () -> assertEquals(0, context.getBeanNamesForType(NewTopic.class).length,
                        () -> "NewTopic beans: " + names(NewTopic.class)),
                () -> assertEquals(0,
                        context.getBeanNamesForType(KafkaTransactionManager.class).length,
                        () -> "KafkaTransactionManager beans: "
                                + names(KafkaTransactionManager.class)),
                () -> assertEquals(0, context.getBeanNamesForType(CommandLineRunner.class).length,
                        () -> "CommandLineRunner beans: " + names(CommandLineRunner.class)),
                () -> assertEquals(0, context.getBeanNamesForType(ApplicationRunner.class).length,
                        () -> "ApplicationRunner beans: " + names(ApplicationRunner.class)));
    }

    /**
     * Asserts no bean method the fraud configuration classes declare returns a topic administration,
     * meter registry, datasource, entity manager factory, transaction manager or migration type.
     */
    @Test
    @DisplayName("no fraud bean method returns an infrastructure type the framework supplies")
    void noFraudBeanMethodReturnsAnInfrastructureType() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> declaring : fraudConfigurationClasses()) {
            for (Method method : declaring.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Bean.class)) {
                    continue;
                }
                for (Class<?> forbidden : FORBIDDEN_BEAN_TYPES) {
                    if (forbidden.isAssignableFrom(method.getReturnType())) {
                        offenders.add(declaring.getSimpleName() + "." + method.getName()
                                + " returns " + forbidden.getName());
                    }
                }
            }
        }

        assertAll(
                () -> assertFalse(fraudConfigurationClasses().isEmpty(),
                        "the scan found configuration classes to read"),
                () -> assertEquals(List.of(), offenders,
                        () -> "bean methods returning an infrastructure type: " + offenders));
    }

    /**
     * Asserts the exposure list holds the three shipped names, the two exposed endpoints answer a
     * monitoring identity, and every other actuator path is refused.
     */
    @Test
    @DisplayName("the exposure list holds three names and every other actuator path is refused")
    void theExposureListHoldsThreeNamesAndEveryOtherPathIsRefused() throws Exception {
        String include = environment.getProperty(EXPOSURE_INCLUDE_PROPERTY);
        List<String> names = commaSeparated(include);
        MockMvc client = client();

        assertAll(
                () -> assertEquals(EXPOSED_ENDPOINTS, names, EXPOSURE_INCLUDE_PROPERTY),
                () -> assertFalse(names.contains("*"),
                        () -> "the exposure list carries a wildcard: " + include),
                () -> assertEquals(STATUS_OK, monitoringGet(client, METRICS_PATH).getStatus(),
                        METRICS_PATH + " answers a monitoring identity"),
                () -> assertEquals(STATUS_OK, monitoringGet(client, PROMETHEUS_PATH).getStatus(),
                        PROMETHEUS_PATH + " answers a monitoring identity"),
                () -> assertEquals(STATUS_UNAUTHORIZED, anonymousGet(METRICS_PATH).getStatus(),
                        METRICS_PATH + " demands a credential"),
                () -> assertEquals(STATUS_UNAUTHORIZED, anonymousGet(PROMETHEUS_PATH).getStatus(),
                        PROMETHEUS_PATH + " demands a credential"),
                () -> assertAll(UNEXPOSED_PATHS.stream().map(path -> (Executable) () -> assertAll(
                                () -> assertEquals(STATUS_FORBIDDEN,
                                        monitoringGet(client, path).getStatus(),
                                        path + " is refused a monitoring identity"),
                                () -> assertEquals(STATUS_UNAUTHORIZED,
                                        anonymousGet(path).getStatus(),
                                        path + " demands a credential")))));
    }

    /**
     * Asserts no actuator body names a table, the service schema or a Structured Query Language
     * fragment. Each table name is matched at its word boundaries.
     */
    @Test
    @DisplayName("no actuator body names a table, the service schema or a query fragment")
    void noActuatorBodyNamesATableTheSchemaOrAQueryFragment() throws Exception {
        MockMvc client = client();
        Map<String, String> bodies = Map.of(
                HEALTH_PATH, anonymousGet(HEALTH_PATH).getContentAsString(),
                METRICS_PATH, monitoringGet(client, METRICS_PATH).getContentAsString(),
                PROMETHEUS_PATH, monitoringGet(client, PROMETHEUS_PATH).getContentAsString());
        List<String> leaks = new ArrayList<>();
        bodies.forEach((path, body) -> {
            String lower = body.toLowerCase(Locale.ROOT);
            for (String table : TABLE_NAMES) {
                if (Pattern.compile("(?<![a-z0-9_])" + table + "(?![a-z0-9_])")
                        .matcher(lower).find()) {
                    leaks.add(path + " names table " + table);
                }
            }
            if (lower.contains(SERVICE_SCHEMA + ".")) {
                leaks.add(path + " names the schema prefix " + SERVICE_SCHEMA);
            }
            for (String fragment : SQL_FRAGMENTS) {
                if (lower.contains(fragment)) {
                    leaks.add(path + " carries the fragment " + fragment.trim());
                }
            }
        });

        assertEquals(List.of(), leaks, () -> "actuator bodies leaking storage detail: " + leaks);
    }

    @Test
    @DisplayName("one auto-configured Prometheus meter registry serves this service")
    void oneAutoConfiguredPrometheusRegistryServesThisService() {
        String[] registryNames = context.getBeanNamesForType(MeterRegistry.class);

        assertAll(
                () -> assertEquals(1, registryNames.length,
                        () -> "meter registry beans: " + Arrays.toString(registryNames)),
                () -> assertTrue(holdsPrometheusRegistry(registry),
                        () -> "the injected registry is " + registry.getClass().getName()));
    }

    /**
     * Asserts the ten fraud meter names register before the first message arrives, and that each
     * one reports zero.
     *
     * <p>{@code carddemo.fraud.events.published} counts the assessment events the broker
     * acknowledged, {@code carddemo.fraud.duplicates.skipped} counts a delivery the idempotency
     * guard refused, and the two request-surface counters count refusals. All ten are registered as
     * the context starts, so a dashboard reads zero from a service that has published nothing and
     * refused nothing rather than finding no series at all.
     */
    @Test
    @DisplayName("the ten fraud meter names register before the first message and report zero")
    void theTenFraudMeterNamesRegisterBeforeTheFirstMessageAndReportZero() {
        Collection<Counter> counters = new ArrayList<>();
        counters.addAll(registry.find(METER_EVENTS_CONSUMED).counters());
        counters.addAll(registry.find(METER_ASSESSMENTS_PRODUCED).counters());
        counters.addAll(registry.find(METER_FAILURES).counters());
        counters.addAll(registry.find(METER_DEAD_LETTERS).counters());
        counters.addAll(registry.find(METER_OUTBOX_ABANDONED).counters());
        counters.addAll(registry.find(METER_EVENTS_PUBLISHED).counters());
        counters.addAll(registry.find(METER_DUPLICATES_SKIPPED).counters());
        counters.addAll(registry.find(METER_THROTTLED).counters());
        counters.addAll(registry.find(METER_CROSS_SITE_REFUSED).counters());
        Collection<Timer> timers = registry.find(METER_PROCESSING_LATENCY).timers();

        assertAll(
                () -> assertEquals(FRAUD_METER_NAMES.size(), fraudMeterNames().size(),
                        () -> "registered fraud meter names: " + fraudMeterNames()),
                () -> assertEquals(new LinkedHashSet<>(FRAUD_METER_NAMES), fraudMeterNames(),
                        () -> "registered fraud meter names: " + fraudMeterNames()),
                () -> assertFalse(counters.isEmpty(), "the nine counter names are registered"),
                () -> assertFalse(timers.isEmpty(), "the timer name is registered"),
                () -> assertAll(counters.stream().map(counter -> (Executable) () ->
                        assertEquals(0.0d, counter.count(),
                                () -> counter.getId() + " reports " + counter.count()))),
                () -> assertAll(timers.stream().map(timer -> (Executable) () ->
                        assertEquals(0L, timer.count(),
                                () -> timer.getId() + " reports " + timer.count()))));
    }

    @Test
    @DisplayName("the assessments counter and the failure counter are two meters of distinct names")
    void theAssessmentsCounterAndTheFailureCounterAreTwoMetersOfDistinctNames() {
        Collection<Counter> produced = registry.find(METER_ASSESSMENTS_PRODUCED).counters();
        Collection<Counter> failures = registry.find(METER_FAILURES).counters();

        assertAll(
                () -> assertNotEquals(METER_ASSESSMENTS_PRODUCED, METER_FAILURES,
                        "the two counter names differ"),
                () -> assertFalse(produced.isEmpty(), METER_ASSESSMENTS_PRODUCED + " is registered"),
                () -> assertFalse(failures.isEmpty(), METER_FAILURES + " is registered"),
                () -> assertTrue(produced.stream().noneMatch(counter ->
                                METER_FAILURES.equals(counter.getId().getName())),
                        "no assessments counter answers to the failure name"),
                () -> assertTrue(failures.stream().noneMatch(counter ->
                                METER_ASSESSMENTS_PRODUCED.equals(counter.getId().getName())),
                        "no failure counter answers to the assessments name"));
    }

    /**
     * Asserts every fraud meter tag key falls in the allowed set and no tag value carries an
     * identifier.
     */
    @Test
    @DisplayName("every fraud meter tag key is allowed and no tag value carries an identifier")
    void everyFraudMeterTagKeyIsAllowedAndNoTagValueCarriesAnIdentifier() {
        List<String> unknownKeys = new ArrayList<>();
        List<String> identifierValues = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            Meter.Id id = meter.getId();
            if (!id.getName().startsWith(METER_PREFIX)) {
                continue;
            }
            for (Tag tag : id.getTags()) {
                if (!ALLOWED_TAG_KEYS.contains(tag.getKey())) {
                    unknownKeys.add(id.getName() + " tagged " + tag.getKey());
                }
                if (carriesIdentifier(tag.getValue())) {
                    identifierValues.add(id.getName() + " tag " + tag.getKey());
                }
            }
        }

        assertAll(
                () -> assertEquals(List.of(), unknownKeys,
                        () -> "tag keys outside " + ALLOWED_TAG_KEYS + ": " + unknownKeys),
                () -> assertEquals(List.of(), identifierValues,
                        () -> "tag values shaped like an identifier: " + identifierValues));
    }

    @Test
    @DisplayName("the Prometheus body renders the nine fraud meter names with underscores")
    void thePrometheusBodyRendersTheNineFraudMeterNames() throws Exception {
        String body = monitoringGet(client(), PROMETHEUS_PATH).getContentAsString();

        assertAll(FRAUD_METER_NAMES.stream().map(name -> (Executable) () -> {
                    String rendered = name.replace('.', '_');
                    assertTrue(body.contains(rendered),
                            () -> PROMETHEUS_PATH + " renders no series named " + rendered);
                }));
    }

    /**
     * Asserts the persistence, migration and port settings the started context resolved, including
     * the keyword quoting property the shipped file leaves unset.
     */
    @Test
    @DisplayName("the persistence, migration and port settings resolve from the started context")
    void thePersistenceMigrationAndPortSettingsResolveFromTheStartedContext() {
        String ddlAuto = environment.getProperty(DDL_AUTO_PROPERTY);

        assertAll(
                () -> assertEquals(SCHEMA_VALIDATION, ddlAuto, DDL_AUTO_PROPERTY),
                () -> assertFalse(WRITING_DDL_AUTO_VALUES.contains(ddlAuto),
                        () -> DDL_AUTO_PROPERTY + " resolved to " + ddlAuto),
                () -> assertEquals(SERVICE_SCHEMA, environment.getProperty(DEFAULT_SCHEMA_PROPERTY),
                        DEFAULT_SCHEMA_PROPERTY),
                () -> assertNull(environment.getProperty(KEYWORD_QUOTING_PROPERTY),
                        KEYWORD_QUOTING_PROPERTY + " resolves to no value"),
                () -> assertEquals("true",
                        environment.getProperty(FLYWAY_CREATE_SCHEMAS_PROPERTY),
                        FLYWAY_CREATE_SCHEMAS_PROPERTY),
                () -> assertEquals("8080", environment.getProperty(SERVER_PORT_PROPERTY),
                        SERVER_PORT_PROPERTY));
    }

    /** Builds a client over the started dispatcher with the security chain installed. */
    private MockMvc client() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    /** Returns the response to an unauthenticated read of {@code path}. */
    private MockHttpServletResponse anonymousGet(String path) throws Exception {
        return client().perform(get(path)).andReturn().getResponse();
    }

    /** Returns the response to a read of {@code path} carrying the monitoring identity. */
    private static MockHttpServletResponse monitoringGet(MockMvc client, String path)
            throws Exception {
        String credential = MONITORING_USERNAME + ":" + MONITORING_PASSWORD;
        String header = "Basic " + Base64.getEncoder()
                .encodeToString(credential.getBytes(StandardCharsets.UTF_8));
        return client.perform(get(path).header("Authorization", header)).andReturn().getResponse();
    }

    /** Returns the names of the health contributor beans reporting {@code status}. */
    private List<String> contributorsReporting(Status status) {
        List<String> reporting = new ArrayList<>();
        for (String name : context.getBeanNamesForType(HealthIndicator.class)) {
            if (status.equals(context.getBean(name, HealthIndicator.class).health().getStatus())) {
                reporting.add(name);
            }
        }
        return reporting;
    }

    /** Returns the configuration classes this service declares, with any proxy unwrapped. */
    private List<Class<?>> fraudConfigurationClasses() {
        List<Class<?>> declared = new ArrayList<>();
        for (String name : context.getBeanNamesForAnnotation(Configuration.class)) {
            Class<?> beanType = context.getType(name);
            if (beanType == null) {
                continue;
            }
            Class<?> userType = ClassUtils.getUserClass(beanType);
            if (userType.getName().startsWith(FRAUD_PACKAGE)) {
                declared.add(userType);
            }
        }
        return declared;
    }

    /** Returns the distinct meter names this service registered under its own prefix. */
    private Set<String> fraudMeterNames() {
        Set<String> registered = new LinkedHashSet<>();
        for (Meter meter : registry.getMeters()) {
            String name = meter.getId().getName();
            if (name.startsWith(METER_PREFIX)) {
                registered.add(name);
            }
        }
        return registered;
    }

    /** Returns the instance an {@link ErrorHandlingDeserializer} wraps. */
    private static Object delegateOf(ErrorHandlingDeserializer<?> wrapper) throws Exception {
        Field field = ErrorHandlingDeserializer.class.getDeclaredField("delegate");
        field.setAccessible(true);
        return field.get(wrapper);
    }

    /** Reports whether {@code candidate} is a Prometheus registry or a composite holding one. */
    private static boolean holdsPrometheusRegistry(MeterRegistry candidate) {
        if (candidate instanceof PrometheusMeterRegistry) {
            return true;
        }
        return candidate instanceof CompositeMeterRegistry composite
                && composite.getRegistries().stream()
                        .anyMatch(ConfigurationInvariantsIT::holdsPrometheusRegistry);
    }

    /** Reports whether {@code value} is shaped like an identifier of this platform. */
    private static boolean carriesIdentifier(String value) {
        if (value == null) {
            return false;
        }
        if (ACCOUNT_IDENTIFIER.matcher(value).matches()
                || LONG_DIGIT_RUN.matcher(value).find()
                || UUID_SHAPE.matcher(value).matches()) {
            return true;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException notAnIdentifier) {
            return false;
        }
    }

    /** Returns the names of every bean of {@code type}, for a failure message. */
    private String names(Class<?> type) {
        return Arrays.toString(context.getBeanNamesForType(type));
    }

    /** Returns {@code value} as text, which compares a boxed setting and a string setting alike. */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Splits a comma-separated property value, trimming each name. */
    private static List<String> commaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(name -> !name.isEmpty())
                .toList();
    }
}
