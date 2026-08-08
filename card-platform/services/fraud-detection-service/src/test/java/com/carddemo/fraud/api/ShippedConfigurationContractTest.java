package com.carddemo.fraud.api;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.fraud.config.FraudProperties;
import com.carddemo.fraud.domain.RiskRule;
import com.carddemo.fraud.domain.RiskScoringService;
import com.carddemo.fraud.repository.VelocityWindowRepository;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract tests over this service's shipped configuration: {@code src/main/resources/application.yml},
 * the schema migration beside it, and the module directory listing. Net new; no COBOL ancestor.
 *
 * <p>The YAML file is read twice, once as characters for the absence sweeps and the placeholder scan,
 * once as nested maps for the key and value assertions.
 *
 * <p>Assertions cover present keys, absent keys, and the default every placeholder carries. Three
 * tests go further and assert the configuration Spring Boot binds from the same file. A key the
 * file spells in a form Spring or the Kafka client does not accept therefore fails, rather than
 * passing a text comparison. Those three start a context holding one configuration-properties bean
 * and nothing else.
 *
 * <p>No database connects, no broker is reached, and every file operation is a read.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Shipped configuration of the fraud detection service")
final class ShippedConfigurationContractTest {

    /** Classpath location of the shipped configuration file. */
    private static final String APPLICATION_YAML = "/application.yml";

    /** Classpath location of the shipped schema migration. */
    private static final String SCHEMA_MIGRATION = "/db/migration/V1__schema.sql";

    /** Classpath location this module ships no resource for. */
    private static final String SEED_MIGRATION = "/db/migration/V2__seed.sql";

    /** Module relative path of the main resource directory. */
    private static final String MAIN_RESOURCES = "src/main/resources";

    /** Module relative path of the test resource directory. */
    private static final String TEST_RESOURCES = "src/test/resources";

    /** Module relative path of the source directory. */
    private static final String SOURCE_ROOT = "src";

    /** The datasource credential placeholder, which carries no default. */
    private static final String CREDENTIAL_PLACEHOLDER = "${POSTGRES_PASSWORD}";

    /**
     * The broker credential placeholder, which carries no default either. This service
     * authenticates to the broker as its own Simple Authentication and Security Layer identity.
     * Which topics and groups that identity is admitted to is declared in
     * {@code card-platform/docker-compose.yml} and {@code card-platform/deploy/k8s/10-kafka.yaml},
     * not here: this service consumes {@code transaction.authorized} and publishes the fraud and
     * dead-letter topics its own configuration names.
     */
    private static final String BROKER_CREDENTIAL_PLACEHOLDER = "${KAFKA_SASL_PASSWORD}";

    /**
     * Every placeholder in the shipped configuration that carries no default, in the order the file
     * declares them. Each one is a credential, and a credential must not carry a default. A default
     * password or password hash is a credential this repository would publish, and an unset
     * variable stopping start-up is the intended outcome. The three hashes belong to the
     * identities {@code config/SecurityConfig} maps, and {@code card-platform/.env.example}
     * documents how to generate them.
     */
    private static final List<String> PLACEHOLDERS_WITHOUT_DEFAULT = List.of(
            CREDENTIAL_PLACEHOLDER,
            BROKER_CREDENTIAL_PLACEHOLDER,
            "${ADMIN_PASSWORD_HASH}",
            "${USER_PASSWORD_HASH}",
            "${MONITORING_PASSWORD_HASH}");

    /** Schema name every schema placeholder defaults to, inside this service's own database. */
    private static final String DEFAULT_SCHEMA = "fraud_service";

    /**
     * Database login this service signs on as by default. It is this service's own login and not the
     * superuser: one shared login would make the private schema this service owns a convention
     * rather than a control.
     */
    private static final String DEFAULT_DATASOURCE_LOGIN = "carddemo_fraud_svc";

    /** Consumer group the shipped placeholder defaults to. */
    private static final String DEFAULT_CONSUMER_GROUP = "fraud-detection";

    /**
     * Bootstrap server the shipped placeholder defaults to.
     *
     * <p>The internal listener docker-compose.yml publishes, which is the one context a shipped
     * default can be correct in. A cluster overrides it to kafka:9092 and a run on the host to
     * localhost:9092.
     */
    private static final String DEFAULT_BOOTSTRAP_SERVERS = "kafka:29092";

    /**
     * Serializer that writes one of the registered event records and refuses every other value.
     *
     * <p>The shipped file must not name it as the producer value serializer. What this service
     * publishes is the text its {@code outbox_event} payload column holds, so the value reaching the
     * template is a {@code String}, and this serializer answers a {@code String} with a
     * {@code SerializationException}. Naming it would fail every publish while the service still
     * reported itself healthy, which is why {@link #producerRequiresEveryReplicaAndIdempotence()}
     * asserts its absence rather than trusting a comment. The schema gate runs at write time,
     * before the outbox row commits, where a rejected event can still roll back its transaction.
     */
    private static final String RECORD_ONLY_SERIALIZER =
            "com.carddemo.events.serde.JsonSchemaValidatingSerializer";

    /** Deserializer the error-handling wrapper delegates to, which validates on consume. */
    private static final String VALIDATING_DESERIALIZER =
            "com.carddemo.events.serde.JsonSchemaValidatingDeserializer";

    /** Key by which {@code ErrorHandlingDeserializer} reads the deserializer it wraps. */
    private static final String DELEGATE_DESERIALIZER_KEY =
            "spring.deserializer.value.delegate.class";

    /** Prefix of the one built property name that carries no Kafka client configuration name. */
    private static final String SPRING_PROPERTY_PREFIX = "spring.";

    /**
     * Loads the shipped {@code application.yml} into a context holding one
     * configuration-properties bean. The initializer reads the file and starts nothing else, so
     * Spring Boot binds exactly what the file declares and no client connects anywhere.
     */
    private static final ApplicationContextRunner KAFKA_RUNNER = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(KafkaPropertiesBinding.class);

    /**
     * Resolved default form of the datasource connection string. Each service reaches one private
     * database and, inside it, one private schema, so PostgreSQL runs no query across two services.
     * {@code sslmode=require} is part of the default rather than an override. A driver that fell
     * back to cleartext would carry every account identifier, balance and assessment across the
     * wire in the clear.
     */
    private static final String DEFAULT_DATASOURCE_URL =
            "jdbc:postgresql://postgres:5432/carddemo_fraud?currentSchema=" + DEFAULT_SCHEMA
                    + "&sslmode=require";

    /** Placeholder spans the datasource connection string declares, the outer span and four nested. */
    private static final int DATASOURCE_URL_PLACEHOLDER_SPANS = 5;

    /** Host ports the composition publishes onto the six service containers. */
    private static final List<String> PUBLISHED_HOST_PORTS =
            List.of("8081", "8082", "8083", "8084", "8085", "8086");

    /** File names a Logback configuration takes. */
    private static final List<String> LOGBACK_FILE_NAMES = List.of("logback.xml", "logback-spring.xml");

    /** File name extensions a Spring profile variant takes. */
    private static final List<String> PROFILE_VARIANT_SUFFIXES = List.of(".yml", ".yaml", ".properties");

    /** File name prefix a Spring profile variant takes. */
    private static final String PROFILE_VARIANT_PREFIX = "application-";

    /** Spellings of the Kafka admin fail-fast property. */
    private static final List<String> FAIL_FAST_SPELLINGS = List.of("fail-fast", "failfast", "fail_fast");

    /** Spellings of the Hibernate keyword quoting property. */
    private static final List<String> AUTO_QUOTE_SPELLINGS =
            List.of("auto_quote_keyword", "auto-quote-keyword");

    /** Keys that select a YAML document or a profile. */
    private static final List<String> PROFILE_TEXT_FRAGMENTS =
            List.of("spring.profiles", "on-profile", "config.import", "activate");

    /** Keys under {@code server.error} that widen an error response. */
    private static final List<String> ERROR_DETAIL_KEYS = List.of(
            "include-stacktrace", "include-message", "include-binding-errors", "include-exception");

    /** Values that widen an error response. */
    private static final List<String> REVEALING_VALUES = List.of("always", "on-param", "on_param", "true");

    /** Values {@code spring.jpa.hibernate.ddl-auto} does not carry here. */
    private static final List<String> REJECTED_DDL_AUTO_VALUES =
            List.of("update", "create", "create-drop", "none");

    /** Alternative connection-pool implementations absent from {@code spring.datasource}. */
    private static final List<String> ALTERNATIVE_CONNECTION_POOL_KEYS =
            List.of("tomcat", "dbcp2");

    /** Log levels no more verbose than informational. */
    private static final List<String> NON_VERBOSE_LEVELS = List.of("OFF", "ERROR", "WARN", "INFO");

    /** Logger names that print statements and bind parameters. */
    private static final List<String> HIBERNATE_LOGGERS =
            List.of("org.hibernate.SQL", "org.hibernate.orm.jdbc.bind");

    /** Logger name prefix the two Hibernate logger names share. */
    private static final String HIBERNATE_LOGGER_PREFIX = "org.hibernate";

    /** Actuator endpoint identifiers the shipped default exposes, in order. */
    private static final List<String> EXPOSED_ENDPOINTS =
            List.of("health", "metrics", "prometheus");

    /** One hundred rows, the shipped default of {@code carddemo.outbox.relay.batch-size}. */
    private static final String DEFAULT_RELAY_BATCH_SIZE = String.valueOf(10 * 10);

    /** One thousand milliseconds, the shipped default of {@code carddemo.consumer.retry.backoff-ms}. */
    private static final String DEFAULT_RETRY_BACKOFF_MILLIS =
            String.valueOf(Duration.ofSeconds(1L).toMillis());

    /** Matches a block comment in Structured Query Language (SQL) text. */
    private static final Pattern SQL_BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** Matches a line comment in SQL text. */
    private static final Pattern SQL_LINE_COMMENT = Pattern.compile("--.*$");

    /** Matches a table creation and captures the object name. */
    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([^\\s(]+)");

    /** Matches an index creation and captures the index name and the table it targets. */
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?i)create\\s+(?:unique\\s+)?index\\s+(?:if\\s+not\\s+exists\\s+)?([^\\s(]+)\\s+on\\s+([^\\s(]+)");

    /** Matches a schema creation. */
    private static final Pattern CREATE_SCHEMA = Pattern.compile("(?i)create\\s+schema");

    /** The shipped configuration file, character for character. */
    private static String rawYaml;

    /** The shipped configuration file folded to lower case. */
    private static String foldedYaml;

    /** The shipped configuration file parsed into nested maps. */
    private static Map<String, Object> yamlTree;

    /** The shipped schema migration, character for character. */
    private static String rawMigration;

    /** Base directory of this module. */
    private static Path moduleBase;

    @BeforeAll
    static void readShippedConfiguration() {
        rawYaml = readClasspathResource(APPLICATION_YAML);
        foldedYaml = rawYaml.toLowerCase(Locale.ROOT);
        yamlTree = parseYaml(rawYaml);
        rawMigration = readClasspathResource(SCHEMA_MIGRATION);
        moduleBase = resolveModuleBase();
    }

    @Test
    @DisplayName("src/test/resources is absent from this module")
    void moduleDeclaresNoTestResourceDirectory() {
        Path testResources = moduleBase.resolve(TEST_RESOURCES);
        assertFalse(Files.exists(testResources),
                () -> "test resource directory present at " + testResources.toAbsolutePath());
    }

    @Test
    @DisplayName("no Logback configuration file exists under src")
    void moduleDeclaresNoLogbackConfigurationFile() {
        List<Path> found = filesUnder(moduleBase.resolve(SOURCE_ROOT)).stream()
                .filter(path -> LOGBACK_FILE_NAMES.contains(fileName(path)))
                .toList();
        assertEquals(List.of(), found, () -> "Logback configuration files present: " + found);
    }

    @Test
    @DisplayName("no application-* profile variant exists under src/main/resources")
    void mainResourcesDeclareNoProfileVariantFile() {
        List<Path> found = filesUnder(moduleBase.resolve(MAIN_RESOURCES)).stream()
                .filter(path -> fileName(path).startsWith(PROFILE_VARIANT_PREFIX))
                .filter(path -> PROFILE_VARIANT_SUFFIXES.stream().anyMatch(fileName(path)::endsWith))
                .toList();
        assertEquals(List.of(), found, () -> "profile variant files present: " + found);
    }

    @Test
    @DisplayName("application.yml is one document and names no profile")
    void shippedYamlIsOneDocumentWithNoProfileKey() {
        List<String> separators = rawYaml.lines()
                .map(String::strip)
                .filter(line -> line.equals("---") || line.startsWith("--- "))
                .toList();
        assertAll(
                () -> assertEquals(List.of(), separators,
                        () -> "document separators present: " + separators),
                () -> assertEquals(List.of(), fragmentsPresent(PROFILE_TEXT_FRAGMENTS),
                        () -> "profile text present: " + fragmentsPresent(PROFILE_TEXT_FRAGMENTS)),
                () -> assertNull(valueAt("spring", "profiles"), "spring.profiles is set"),
                () -> assertNull(valueAt("spring", "config"), "spring.config is set"));
    }

    @Test
    @DisplayName("Kafka admin startup stays non-failing while readiness has a bounded timeout")
    void kafkaAdminStartupStaysNonFailingWhileReadinessHasABoundedTimeout() {
        assertAll(
                () -> assertEquals(List.of(), fragmentsPresent(FAIL_FAST_SPELLINGS),
                        () -> "fail-fast text present: " + fragmentsPresent(FAIL_FAST_SPELLINGS)),
                () -> assertNull(valueAt("spring", "kafka", "admin", "fail-fast"),
                        "spring.kafka.admin.fail-fast is set"),
                () -> assertEquals("3s",
                        resolvedAt("spring", "kafka", "admin", "operation-timeout"),
                        "spring.kafka.admin.operation-timeout"));
    }

    @Test
    @DisplayName("hibernate.auto_quote_keyword and every dialect key are absent")
    void shippedYamlSetsNoKeywordQuotingAndNoDialect() {
        assertAll(
                () -> assertEquals(List.of(), fragmentsPresent(AUTO_QUOTE_SPELLINGS),
                        () -> "keyword quoting text present: " + fragmentsPresent(AUTO_QUOTE_SPELLINGS)),
                () -> assertNull(
                        valueAt("spring", "jpa", "properties", "hibernate", "auto_quote_keyword"),
                        "hibernate.auto_quote_keyword is set"),
                () -> assertFalse(foldedYaml.contains("dialect"), "a dialect key is set"));
    }

    @Test
    @DisplayName("spring.flyway.locations is absent and clean-disabled is not false")
    void shippedYamlLeavesFlywayLocationsAndCleaningAlone() {
        assertAll(
                () -> assertFalse(foldedYaml.contains("locations"), "spring.flyway.locations is set"),
                () -> assertNull(valueAt("spring", "flyway", "locations"),
                        "spring.flyway.locations is set"),
                () -> assertFalse(foldedYaml.contains("clean-disabled"),
                        "spring.flyway.clean-disabled is set"),
                () -> assertNotEquals(Boolean.FALSE, valueAt("spring", "flyway", "clean-disabled"),
                        "schema cleaning is enabled"));
    }

    @Test
    @DisplayName("V2__seed.sql is absent from the classpath, and the absence is the contract")
    void migrationFolderShipsNoSeedMigration() {
        assertNull(getClass().getResource(SEED_MIGRATION),
                () -> "seed migration present at " + SEED_MIGRATION);
    }

    @Test
    @DisplayName("the bound shipped flag threshold clears 49 and flags 50 and 51")
    void shippedFlagThresholdControlsTheRealScorer() {
        KAFKA_RUNNER.run(context -> {
            assertThat(context).hasNotFailed();
            FraudProperties properties = context.getBean(FraudProperties.class);
            assertThat(properties.fraud().risk().flagThreshold()).isEqualTo(50);

            for (int score : List.of(49, 50, 51)) {
                VelocityWindowRepository repository = mock(VelocityWindowRepository.class);
                when(repository.addAuthorization(any(), any(), any(), any())).thenReturn(1);
                RiskScoringService scorer = new RiskScoringService(
                        List.of(fixedContribution(score)), repository, properties);

                RiskScoringService.RiskAssessment assessment =
                        scorer.assess(authorizedForThresholdTest());

                assertEquals(score >= 50, assessment.flagged(), "score " + score);
            }
        });
    }

    @Test
    @DisplayName("application.yml names none of the six published host ports")
    void shippedYamlNamesNoPublishedHostPort() {
        assertAll(
                () -> assertEquals(List.of(), fragmentsPresent(PUBLISHED_HOST_PORTS),
                        () -> "published host ports present: " + fragmentsPresent(PUBLISHED_HOST_PORTS)),
                () -> assertFalse(foldedYaml.contains("fraud_port"), "a FRAUD_PORT key is set"));
    }

    @Test
    @DisplayName("no server.error.include-* key reveals error detail")
    void shippedYamlRevealsNoErrorDetail() {
        List<String> revealing = ERROR_DETAIL_KEYS.stream()
                .filter(key -> {
                    String value = resolvedAt("server", "error", key);
                    return value != null && REVEALING_VALUES.contains(value.toLowerCase(Locale.ROOT));
                })
                .toList();
        assertEquals(List.of(), revealing, () -> "server.error keys revealing detail: " + revealing);
    }

    /**
     * Every placeholder span in the shipped file carries a default separator at depth one,
     * apart from the credential placeholders. The datasource connection string nests four placeholders
     * inside a fifth, and the scan covers all five.
     */
    @Test
    @DisplayName("every placeholder carries a default at depth one, apart from the credentials")
    void everyPlaceholderCarriesADefault() {
        List<String> spans = placeholderSpans(rawYaml);
        List<String> withoutDefault = spans.stream()
                .filter(span -> depthOneColonIndex(span) < 0)
                .distinct()
                .toList();
        List<String> connectionStringSpans = placeholderSpans(textAt("spring", "datasource", "url"));
        List<String> nestedWithoutDefault = connectionStringSpans.stream()
                .filter(span -> depthOneColonIndex(span) < 0)
                .toList();
        assertAll(
                () -> assertFalse(spans.isEmpty(), "the shipped file declares no placeholder"),
                () -> assertEquals(PLACEHOLDERS_WITHOUT_DEFAULT, withoutDefault,
                        () -> "placeholders carrying no default: " + withoutDefault),
                () -> assertEquals(DATASOURCE_URL_PLACEHOLDER_SPANS, connectionStringSpans.size(),
                        () -> "spring.datasource.url placeholder spans: " + connectionStringSpans),
                () -> assertEquals(List.of(), nestedWithoutDefault,
                        () -> "spring.datasource.url placeholders carrying no default: "
                                + nestedWithoutDefault));
    }

    /**
     * The datasource and schema placeholders resolve to their defaults with no environment
     * variable set. Each service reaches its own database and its own schema inside it.
     */
    @Test
    @DisplayName("the datasource and schema placeholders resolve to their defaults")
    void datasourceDefaultsResolveWithNoEnvironmentVariableSet() {
        String connectionString = resolvedAt("spring", "datasource", "url");
        assertAll(
                () -> assertEquals(DEFAULT_DATASOURCE_URL, connectionString, "spring.datasource.url"),
                () -> assertFalse(connectionString.contains("${"),
                        () -> "spring.datasource.url keeps a placeholder: " + connectionString),
                () -> assertEquals(DEFAULT_DATASOURCE_LOGIN,
                        resolvedAt("spring", "datasource", "username"),
                        "spring.datasource.username"),
                () -> assertEquals("${POSTGRES_PASSWORD}",
                        resolvedAt("spring", "datasource", "password"),
                        "spring.datasource.password carries no default, so an unset password stops "
                                + "start-up rather than signing on under a published one"),
                () -> assertEquals(DEFAULT_SCHEMA, resolvedAt("spring", "flyway", "schemas"),
                        "spring.flyway.schemas"),
                () -> assertEquals(DEFAULT_SCHEMA, resolvedAt("spring", "flyway", "default-schema"),
                        "spring.flyway.default-schema"),
                () -> assertEquals(DEFAULT_SCHEMA,
                        resolvedAt("spring", "jpa", "properties", "hibernate", "default_schema"),
                        "spring.jpa.properties.hibernate.default_schema"));
    }

    @Test
    @DisplayName("the Kafka placeholders resolve to their defaults")
    void kafkaDefaultsResolveWithNoEnvironmentVariableSet() {
        assertAll(
                () -> assertEquals(DEFAULT_BOOTSTRAP_SERVERS,
                        resolvedAt("spring", "kafka", "bootstrap-servers"),
                        "spring.kafka.bootstrap-servers"),
                () -> assertEquals("fraud-detection", resolvedAt("spring", "kafka", "consumer", "group-id"),
                        "spring.kafka.consumer.group-id"));
    }

    @Test
    @DisplayName("server.port is 8080 under the application name fraud-detection-service")
    void serverListensOnTheContainerPort() {
        assertAll(
                () -> assertEquals("8080", textAt("server", "port"), "server.port"),
                () -> assertEquals("fraud-detection-service", textAt("spring", "application", "name"),
                        "spring.application.name"));
    }

    @Test
    @DisplayName("spring.jpa.hibernate.ddl-auto is validate")
    void jpaValidatesTheMigratedSchema() {
        String ddlAuto = textAt("spring", "jpa", "hibernate", "ddl-auto");
        assertAll(
                () -> assertEquals("validate", ddlAuto, "spring.jpa.hibernate.ddl-auto"),
                () -> assertFalse(REJECTED_DDL_AUTO_VALUES.contains(ddlAuto),
                        () -> "spring.jpa.hibernate.ddl-auto is " + ddlAuto),
                () -> assertEquals(Boolean.FALSE, valueAt("spring", "jpa", "open-in-view"),
                        "spring.jpa.open-in-view"),
                () -> assertEquals(Boolean.FALSE, valueAt("spring", "jpa", "show-sql"),
                        "spring.jpa.show-sql"));
    }

    @Test
    @DisplayName("spring.datasource names PostgreSQL and bounds Hikari readiness waits")
    void datasourceNamesPostgresqlAndBoundsHikariReadinessWaits() {
        List<String> alternativePoolKeys = ALTERNATIVE_CONNECTION_POOL_KEYS.stream()
                .filter(key -> valueAt("spring", "datasource", key) != null)
                .toList();
        assertAll(
                () -> assertEquals("org.postgresql.Driver",
                        textAt("spring", "datasource", "driver-class-name"),
                        "spring.datasource.driver-class-name"),
                () -> assertEquals("3000",
                        resolvedAt("spring", "datasource", "hikari", "connection-timeout"),
                        "spring.datasource.hikari.connection-timeout"),
                () -> assertEquals("2000",
                        resolvedAt("spring", "datasource", "hikari", "validation-timeout"),
                        "spring.datasource.hikari.validation-timeout"),
                () -> assertEquals(List.of(), alternativePoolKeys,
                        () -> "alternative connection pool keys present: " + alternativePoolKeys));
    }

    @Test
    @DisplayName("spring.flyway creates the schema and validates every migration")
    void flywayOwnsSchemaCreation() {
        assertAll(
                () -> assertEquals(Boolean.TRUE, valueAt("spring", "flyway", "enabled"),
                        "spring.flyway.enabled"),
                () -> assertEquals(Boolean.TRUE, valueAt("spring", "flyway", "create-schemas"),
                        "spring.flyway.create-schemas"),
                () -> assertEquals(Boolean.FALSE, valueAt("spring", "flyway", "baseline-on-migrate"),
                        "spring.flyway.baseline-on-migrate"),
                () -> assertEquals(Boolean.TRUE, valueAt("spring", "flyway", "validate-on-migrate"),
                        "spring.flyway.validate-on-migrate"));
    }

    @Test
    @DisplayName("V1__schema.sql creates no schema, sets no search path and qualifies no object name")
    void migrationLeavesSchemaCreationToFlyway() {
        String statements = sqlWithoutComments(rawMigration);
        String folded = statements.toLowerCase(Locale.ROOT);
        List<String> objectNames = objectNamesIn(statements);
        List<String> qualified = objectNames.stream().filter(name -> name.contains(".")).toList();
        assertAll(
                () -> assertFalse(CREATE_SCHEMA.matcher(statements).find(),
                        "the migration creates a schema"),
                () -> assertFalse(folded.contains("search_path"), "the migration sets a search path"),
                () -> assertFalse(folded.contains(DEFAULT_SCHEMA + "."),
                        "the migration carries a schema prefix"),
                () -> assertFalse(objectNames.isEmpty(), "the migration creates no object"),
                () -> assertEquals(List.of(), qualified, () -> "qualified object names: " + qualified));
    }

    @Test
    @DisplayName("spring.kafka.consumer commits no offset automatically")
    void consumerCommitsNoOffsetAutomatically() {
        assertAll(
                () -> assertEquals(Boolean.FALSE,
                        valueAt("spring", "kafka", "consumer", "enable-auto-commit"),
                        "spring.kafka.consumer.enable-auto-commit"),
                () -> assertEquals("earliest",
                        resolvedAt("spring", "kafka", "consumer", "auto-offset-reset"),
                        "spring.kafka.consumer.auto-offset-reset"),
                () -> assertEquals("org.apache.kafka.common.serialization.StringDeserializer",
                        textAt("spring", "kafka", "consumer", "key-deserializer"),
                        "spring.kafka.consumer.key-deserializer"),
                () -> assertEquals("300000",
                        textAt("spring", "kafka", "consumer", "properties", "max.poll.interval.ms"),
                        "max.poll.interval.ms"));
    }

    @Test
    @DisplayName("one producer send resolves inside the relay pass that issued it")
    void oneSendResolvesInsideTheRelayPassThatIssuedIt() {
        long maxBlock = millisecondsAt("spring", "kafka", "producer", "properties", "max.block.ms");
        long deliveryTimeout =
                millisecondsAt("spring", "kafka", "producer", "properties", "delivery.timeout.ms");
        long requestTimeout =
                millisecondsAt("spring", "kafka", "producer", "properties", "request.timeout.ms");
        long linger = millisecondsAt("spring", "kafka", "producer", "properties", "linger.ms");
        long passBudget = millisecondsAt("carddemo", "outbox", "relay", "max-duration-ms");
        long oneSend = maxBlock + deliveryTimeout;

        assertAll("the producer window against the relay pass budget",
                () -> assertTrue(oneSend < passBudget,
                        () -> "max.block.ms + delivery.timeout.ms is " + oneSend
                                + " and carddemo.outbox.relay.max-duration-ms is " + passBudget
                                + ". A send the relay abandons can still be delivered, and the "
                                + "relay's retry then publishes a second copy of the same event"),
                () -> assertTrue(deliveryTimeout >= linger + requestTimeout,
                        () -> "delivery.timeout.ms is " + deliveryTimeout
                                + " and linger.ms plus request.timeout.ms is "
                                + (linger + requestTimeout)
                                + ". The producer refuses that combination at construction"));
    }

    @Test
    @DisplayName("spring.kafka.producer requires every replica and idempotent production")
    void producerRequiresEveryReplicaAndIdempotence() {
        assertAll(
                () -> assertEquals("all", textAt("spring", "kafka", "producer", "acks"),
                        "spring.kafka.producer.acks"),
                () -> assertEquals(Boolean.TRUE,
                        valueAt("spring", "kafka", "producer", "properties", "enable.idempotence"),
                        "enable.idempotence"),
                () -> assertEquals("5",
                        textAt("spring", "kafka", "producer", "properties",
                                "max.in.flight.requests.per.connection"),
                        "max.in.flight.requests.per.connection"),
                () -> assertEquals("2000",
                        textAt("spring", "kafka", "producer", "properties", "delivery.timeout.ms"),
                        "delivery.timeout.ms"),
                () -> assertEquals("2000",
                        textAt("spring", "kafka", "producer", "properties", "request.timeout.ms"),
                        "request.timeout.ms"),
                () -> assertEquals("0",
                        textAt("spring", "kafka", "producer", "properties", "linger.ms"),
                        "linger.ms"),
                () -> assertEquals("2000",
                        textAt("spring", "kafka", "producer", "properties", "max.block.ms"),
                        "max.block.ms"),
                () -> assertEquals(StringSerializer.class.getName(),
                        textAt("spring", "kafka", "producer", "key-serializer"),
                        "spring.kafka.producer.key-serializer"),
                () -> assertEquals(StringSerializer.class.getName(),
                        textAt("spring", "kafka", "producer", "value-serializer"),
                        "spring.kafka.producer.value-serializer"),
                () -> assertNotEquals(RECORD_ONLY_SERIALIZER,
                        textAt("spring", "kafka", "producer", "value-serializer"),
                        "spring.kafka.producer.value-serializer must take the text the outbox "
                                + "payload column holds. " + RECORD_ONLY_SERIALIZER
                                + " refuses a String, so it fails every publish"));
    }

    /**
     * The shipped acknowledgement mode string and the listener concurrency.
     * {@link #springBootBindsTheShippedKafkaConfiguration()} asserts the effective values the
     * listener container and both clients receive.
     */
    @Test
    @DisplayName("spring.kafka.listener.ack-mode ships as manual_immediate")
    void listenerAcknowledgementModeIsTheShippedString() {
        assertAll(
                () -> assertEquals("manual_immediate",
                        textAt("spring", "kafka", "listener", "ack-mode"),
                        "spring.kafka.listener.ack-mode"),
                () -> assertEquals("1", textAt("spring", "kafka", "listener", "concurrency"),
                        "spring.kafka.listener.concurrency"));
    }

    /**
     * The configuration Spring Boot binds from the shipped file, rather than the characters
     * the file holds. Spring resolves every placeholder and binds each value to the type
     * {@code KafkaProperties} declares. A misspelled structured key therefore binds nothing, and a
     * value outside an enumeration or a class that cannot be loaded fails the bind.
     *
     * <p>Four settings carry a guarantee this plan claims. Automatic commit is off and the
     * acknowledgement mode is manual and immediate, which is what makes the idempotency marker
     * meaningful. Production is idempotent and acknowledged by every in-sync replica, which is what
     * keeps a retried send from duplicating a record.</p>
     */
    @Test
    @DisplayName("Spring Boot binds the shipped file to the effective listener, consumer and "
            + "producer settings")
    void springBootBindsTheShippedKafkaConfiguration() {
        KAFKA_RUNNER.run(context -> {
            assertThat(context).hasNotFailed();
            KafkaProperties bound = context.getBean(KafkaProperties.class);

            assertAll("effective Kafka configuration",
                    () -> assertEquals(ContainerProperties.AckMode.MANUAL_IMMEDIATE,
                            bound.getListener().getAckMode(), "effective listener ack mode"),
                    () -> assertEquals(1, bound.getListener().getConcurrency(),
                            "effective listener concurrency"),
                    () -> assertEquals(Boolean.FALSE, bound.getConsumer().getEnableAutoCommit(),
                            "effective consumer automatic commit"),
                    () -> assertEquals(DEFAULT_CONSUMER_GROUP, bound.getConsumer().getGroupId(),
                            "effective consumer group, with the placeholder resolved by Spring"),
                    () -> assertEquals("earliest", bound.getConsumer().getAutoOffsetReset(),
                            "effective consumer offset reset"),
                    () -> assertEquals(StringDeserializer.class,
                            bound.getConsumer().getKeyDeserializer(),
                            "effective consumer key deserializer"),
                    () -> assertEquals(ErrorHandlingDeserializer.class,
                            bound.getConsumer().getValueDeserializer(),
                            "effective consumer value deserializer"),
                    () -> assertEquals("all", bound.getProducer().getAcks(),
                            "effective producer acknowledgement"),
                    () -> assertEquals(StringSerializer.class,
                            bound.getProducer().getKeySerializer(),
                            "effective producer key serializer"),
                    () -> assertEquals(StringSerializer.class,
                            bound.getProducer().getValueSerializer(),
                            "effective producer value serializer"),
                    () -> assertEquals(List.of(DEFAULT_BOOTSTRAP_SERVERS),
                            bound.getBootstrapServers(),
                            "effective bootstrap servers, with the placeholder resolved by Spring"));
        });
    }

    /**
     * The maps the two clients actually receive. {@code buildConsumerProperties} and
     * {@code buildProducerProperties} are the methods Spring Boot auto-configuration calls, so a
     * property the shipped file declares under the wrong parent reaches neither map.
     */
    @Test
    @DisplayName("The client property maps Spring Boot builds carry the shipped settings")
    void theBuiltClientPropertyMapsCarryTheShippedSettings() {
        KAFKA_RUNNER.run(context -> {
            KafkaProperties bound = context.getBean(KafkaProperties.class);
            Map<String, Object> consumer = bound.buildConsumerProperties();
            Map<String, Object> producer = bound.buildProducerProperties();

            assertAll("built client property maps",
                    () -> assertEquals(Boolean.FALSE,
                            consumer.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG),
                            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG),
                    () -> assertEquals(DEFAULT_CONSUMER_GROUP,
                            consumer.get(ConsumerConfig.GROUP_ID_CONFIG),
                            ConsumerConfig.GROUP_ID_CONFIG),
                    () -> assertEquals("earliest",
                            consumer.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG),
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG),
                    () -> assertEquals(ErrorHandlingDeserializer.class,
                            consumer.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG),
                            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG),
                    () -> assertEquals(VALIDATING_DESERIALIZER,
                            consumer.get(DELEGATE_DESERIALIZER_KEY), DELEGATE_DESERIALIZER_KEY),
                    () -> assertEquals("300000",
                            consumer.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG),
                            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG),
                    () -> assertEquals("all", producer.get(ProducerConfig.ACKS_CONFIG),
                            ProducerConfig.ACKS_CONFIG),
                    () -> assertEquals("true",
                            producer.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG),
                            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG),
                    () -> assertEquals("5",
                            producer.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION),
                            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION),
                    () -> assertEquals("2000",
                            producer.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG),
                            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG),
                    () -> assertEquals("2000",
                            producer.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG),
                            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG),
                    () -> assertEquals("0", producer.get(ProducerConfig.LINGER_MS_CONFIG),
                            ProducerConfig.LINGER_MS_CONFIG),
                    () -> assertEquals("2000", producer.get(ProducerConfig.MAX_BLOCK_MS_CONFIG),
                            ProducerConfig.MAX_BLOCK_MS_CONFIG),
                    () -> assertEquals(StringSerializer.class,
                            producer.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG),
                            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG),
                    () -> assertEquals(StringSerializer.class,
                            producer.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG),
                            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        });
    }

    /**
     * Every key the two built maps carry is a configuration name its own client
     * declares. A property the shipped file misspells under {@code properties} reaches the client as
     * an unknown name, which the client logs and ignores, so this test is the one that catches it.
     *
     * <p>Spring contributes the delegate key of {@code ErrorHandlingDeserializer}, which carries no
     * Kafka name. Every key of that form opens with {@code spring.}.</p>
     */
    @Test
    @DisplayName("Every built client property name is one the client declares")
    void everyBuiltClientPropertyNameIsOneTheClientDeclares() {
        KAFKA_RUNNER.run(context -> {
            KafkaProperties bound = context.getBean(KafkaProperties.class);

            List<String> unknownConsumerNames = unknownNames(
                    bound.buildConsumerProperties().keySet(), ConsumerConfig.configNames());
            List<String> unknownProducerNames = unknownNames(
                    bound.buildProducerProperties().keySet(), ProducerConfig.configNames());

            assertEquals(List.of(), unknownConsumerNames,
                    "consumer property names no Kafka consumer declares");
            assertEquals(List.of(), unknownProducerNames,
                    "producer property names no Kafka producer declares");
        });
    }

    /**
     * The three names under the plural key {@code carddemo.kafka.topics}, each resolved to its
     * shipped default. The third names the dead-letter topic.
     */
    @Test
    @DisplayName("carddemo.kafka.topics names three topics with their defaults")
    void topicNamesCarryTheirShippedDefaults() {
        assertAll(
                () -> assertEquals("transaction.authorized",
                        resolvedAt("carddemo", "kafka", "topics", "transaction-authorized"),
                        "carddemo.kafka.topics.transaction-authorized"),
                () -> assertEquals("fraud.assessed",
                        resolvedAt("carddemo", "kafka", "topics", "fraud-assessed"),
                        "carddemo.kafka.topics.fraud-assessed"),
                () -> assertEquals("carddemo.dead-letter",
                        resolvedAt("carddemo", "kafka", "topics", "dead-letter"),
                        "carddemo.kafka.topics.dead-letter"));
    }

    @Test
    @DisplayName("carddemo.outbox.relay and carddemo.consumer.retry carry their defaults")
    void relayAndRetryCarryTheirShippedDefaults() {
        assertAll(
                () -> assertEquals("500", resolvedAt("carddemo", "outbox", "relay", "fixed-delay-ms"),
                        "carddemo.outbox.relay.fixed-delay-ms"),
                () -> assertEquals(DEFAULT_RELAY_BATCH_SIZE,
                        resolvedAt("carddemo", "outbox", "relay", "batch-size"),
                        "carddemo.outbox.relay.batch-size"),
                () -> assertEquals("3", resolvedAt("carddemo", "consumer", "retry", "max-attempts"),
                        "carddemo.consumer.retry.max-attempts"),
                () -> assertEquals(DEFAULT_RETRY_BACKOFF_MILLIS,
                        resolvedAt("carddemo", "consumer", "retry", "backoff-ms"),
                        "carddemo.consumer.retry.backoff-ms"));
    }

    /**
     * The exposure list resolves to four endpoint names in order and carries no wildcard and no
     * fifth name. Metric exposition is switched on.
     */
    @Test
    @DisplayName("management exposes three endpoints and no wildcard")
    void actuatorExposesThreeEndpointsAndNoWildcard() {
        String include = resolvedAt("management", "endpoints", "web", "exposure", "include");
        List<String> names = commaSeparated(include);
        assertAll(
                () -> assertEquals(EXPOSED_ENDPOINTS, names,
                        "management.endpoints.web.exposure.include"),
                () -> assertFalse(names.contains("*"),
                        () -> "the exposure list carries a wildcard: " + include),
                () -> assertEquals(Boolean.TRUE,
                        valueAt("management", "prometheus", "metrics", "export", "enabled"),
                        "management.prometheus.metrics.export.enabled"));
    }

    @Test
    @DisplayName("logging ships structured console output with its shipped level defaults")
    void logConfigurationCarriesItsShippedDefaults() {
        assertAll(
                () -> assertEquals("logstash", textAt("logging", "structured", "format", "console"),
                        "logging.structured.format.console"),
                () -> assertEquals("INFO", resolvedAt("logging", "level", "root"), "logging.level.root"),
                // INFO rather than DEBUG. A default is what an operator gets who starts this
                // service directly, and a DEBUG default writes identifiers into every log a
                // deployment collects. The variable is still there to raise for one run.
                () -> assertEquals("INFO", resolvedAt("logging", "level", "com.carddemo"),
                        "logging.level.com.carddemo"));
    }

    @Test
    @DisplayName("org.hibernate.SQL and org.hibernate.orm.jdbc.bind are absent or no more verbose "
            + "than INFO")
    void hibernateStatementAndBindLoggersAreNotVerbose() {
        Map<String, String> levels = loggingLevels();
        List<String> verboseNamed = HIBERNATE_LOGGERS.stream()
                .filter(logger -> isVerbose(levels.get(logger)))
                .toList();
        List<String> verbosePrefixed = levels.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(HIBERNATE_LOGGER_PREFIX))
                .filter(entry -> isVerbose(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        assertAll(
                () -> assertEquals(List.of(), verboseNamed,
                        () -> "verbose logger levels: " + verboseNamed),
                () -> assertEquals(List.of(), verbosePrefixed,
                        () -> "verbose logger levels: " + verbosePrefixed));
    }

    private static String readClasspathResource(String resource) {
        try (InputStream stream = ShippedConfigurationContractTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("classpath resource absent: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("cannot read classpath resource: " + resource, failure);
        }
    }

    private static Map<String, Object> parseYaml(String text) {
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        if (!(loaded instanceof Map<?, ?> mapping)) {
            throw new AssertionError(APPLICATION_YAML + " does not parse to a mapping");
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        mapping.forEach((key, value) -> tree.put(String.valueOf(key), value));
        return tree;
    }

    private static Path resolveModuleBase() {
        String declared = System.getProperty("basedir");
        String fallback = System.getProperty("user.dir", ".");
        Path base = Path.of(declared == null || declared.isBlank() ? fallback : declared);
        Path marker = base.resolve(MAIN_RESOURCES).resolve("application.yml");
        if (!Files.isRegularFile(marker)) {
            throw new AssertionError("resolved module base " + base.toAbsolutePath()
                    + " carries no " + MAIN_RESOURCES + "/application.yml");
        }
        return base;
    }

    private static List<Path> filesUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (IOException failure) {
            throw new AssertionError("cannot read directory " + root.toAbsolutePath(), failure);
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
    }

    private static List<String> fragmentsPresent(List<String> fragments) {
        return fragments.stream().filter(foldedYaml::contains).toList();
    }

    private static Object valueAt(String... path) {
        Object current = yamlTree;
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> mapping)) {
                return null;
            }
            current = mapping.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static String textAt(String... path) {
        Object value = valueAt(path);
        return value == null ? null : String.valueOf(value);
    }

    private static String resolvedAt(String... path) {
        return resolveDefaults(textAt(path));
    }

    /**
     * Reads one millisecond setting, taking the default of an environment placeholder where the
     * shipped file carries one.
     *
     * @param path the key path in the shipped document
     * @return the value in milliseconds
     */
    private static long millisecondsAt(String... path) {
        String text = resolvedAt(path);
        assertNotNull(text, () -> String.join(".", path) + " is absent from the shipped file");
        return Long.parseLong(text.strip());
    }

    private static List<String> commaSeparated(String value) {
        if (value == null) {
            return List.of();
        }
        return Stream.of(value.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    private static List<String> placeholderSpans(String text) {
        List<String> spans = new ArrayList<>();
        if (text == null) {
            return spans;
        }
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf("${", cursor);
            if (start < 0) {
                break;
            }
            int end = matchingCloseBrace(text, start);
            if (end < 0) {
                break;
            }
            spans.add(text.substring(start, end + 1));
            cursor = start + 2;
        }
        return spans;
    }

    private static int matchingCloseBrace(String text, int start) {
        int depth = 0;
        for (int index = start + 1; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static int depthOneColonIndex(String span) {
        int depth = 0;
        for (int index = 2; index < span.length() - 1; index++) {
            char character = span.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
            } else if (character == ':' && depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String resolveDefaults(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder resolved = new StringBuilder();
        int cursor = 0;
        while (cursor < value.length()) {
            int start = value.indexOf("${", cursor);
            if (start < 0) {
                resolved.append(value, cursor, value.length());
                return resolved.toString();
            }
            resolved.append(value, cursor, start);
            int end = matchingCloseBrace(value, start);
            if (end < 0) {
                resolved.append(value, start, value.length());
                return resolved.toString();
            }
            String span = value.substring(start, end + 1);
            int separator = depthOneColonIndex(span);
            if (separator < 0) {
                resolved.append(span);
            } else {
                resolved.append(resolveDefaults(span.substring(separator + 1, span.length() - 1)));
            }
            cursor = end + 1;
        }
        return resolved.toString();
    }

    private static String sqlWithoutComments(String sql) {
        String withoutBlocks = SQL_BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        return withoutBlocks.lines()
                .map(line -> SQL_LINE_COMMENT.matcher(line).replaceAll(""))
                .collect(Collectors.joining("\n"));
    }

    private static List<String> objectNamesIn(String statements) {
        List<String> names = new ArrayList<>();
        Matcher tables = CREATE_TABLE.matcher(statements);
        while (tables.find()) {
            names.add(tables.group(1));
        }
        Matcher indexes = CREATE_INDEX.matcher(statements);
        while (indexes.find()) {
            names.add(indexes.group(1));
            names.add(indexes.group(2));
        }
        return names;
    }

    private static Map<String, String> loggingLevels() {
        Map<String, String> levels = new LinkedHashMap<>();
        if (valueAt("logging", "level") instanceof Map<?, ?> mapping) {
            mapping.forEach((logger, level) ->
                    levels.put(String.valueOf(logger), resolveDefaults(String.valueOf(level))));
        }
        return levels;
    }

    private static boolean isVerbose(String level) {
        return level != null && !NON_VERBOSE_LEVELS.contains(level.toUpperCase(Locale.ROOT));
    }

    private static RiskRule fixedContribution(int score) {
        return new RiskRule() {
            @Override
            public Contribution evaluate(TransactionAuthorized event) {
                return Contribution.triggeredWith(score);
            }

            @Override
            public String ruleId() {
                return "VELOCITY";
            }
        };
    }

    private static TransactionAuthorized authorizedForThresholdTest() {
        return TransactionAuthorized.of("00000000007", "THRESHOLD-CASE01", "01", "0003",
                "POS TERM", "Threshold boundary", new BigDecimal("10.00"), "800000000",
                "Demo Merchant", "Demo City", "72112", "************0001",
                "2bf90b0da1627234a5d993f0fcaab0f2a3640f8a033bf69969de2fb60b83fa8d", "2026-08-04 12:00:00.000000");
    }

    /**
     * @param builtNames    the keys of one built property map
     * @param declaredNames the configuration names the owning client declares
     * @return the names the client does not declare
     */
    private static List<String> unknownNames(Set<String> builtNames, Set<String> declaredNames) {
        List<String> unknown = new ArrayList<>();
        for (String name : builtNames) {
            if (!declaredNames.contains(name) && !name.startsWith(SPRING_PROPERTY_PREFIX)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    /**
     * Binds {@code spring.kafka} through the same configuration-properties machinery the
     * auto-configuration uses, and declares no other bean.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({KafkaProperties.class, FraudProperties.class})
    static class KafkaPropertiesBinding {
    }
}
