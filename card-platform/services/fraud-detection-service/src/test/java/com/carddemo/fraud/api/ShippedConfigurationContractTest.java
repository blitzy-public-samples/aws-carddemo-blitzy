package com.carddemo.fraud.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Contract tests over this service's shipped configuration: {@code src/main/resources/application.yml},
 * the schema migration beside it, and the module directory listing. Net new; no COBOL ancestor.
 *
 * <p>The YAML file is read twice, once as characters for the absence sweeps and the placeholder scan,
 * once as nested maps for the key and value assertions.
 *
 * <p>Assertions cover present keys, absent keys, and the default every placeholder carries. No Spring
 * context starts, no database connects, no broker is reached, and every file operation is a read.
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

    /** The one placeholder in the shipped configuration that carries no default. */
    private static final String CREDENTIAL_PLACEHOLDER = "${POSTGRES_PASSWORD}";

    /** Schema name every schema placeholder defaults to. */
    private static final String DEFAULT_SCHEMA = "fraud_service";

    /** Resolved default form of the datasource connection string. */
    private static final String DEFAULT_DATASOURCE_URL =
            "jdbc:postgresql://postgres:5432/carddemo_fraud?currentSchema=" + DEFAULT_SCHEMA;

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

    /** Connection pool keys absent from {@code spring.datasource}. */
    private static final List<String> CONNECTION_POOL_KEYS = List.of("hikari", "tomcat", "dbcp2");

    /** Log levels no more verbose than informational. */
    private static final List<String> NON_VERBOSE_LEVELS = List.of("OFF", "ERROR", "WARN", "INFO");

    /** Logger names that print statements and bind parameters. */
    private static final List<String> HIBERNATE_LOGGERS =
            List.of("org.hibernate.SQL", "org.hibernate.orm.jdbc.bind");

    /** Logger name prefix the two Hibernate logger names share. */
    private static final String HIBERNATE_LOGGER_PREFIX = "org.hibernate";

    /** Actuator endpoint identifiers the shipped default exposes, in order. */
    private static final List<String> EXPOSED_ENDPOINTS = List.of("health", "metrics", "prometheus");

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

    /** Reads both shipped files and resolves the base directory of this module. */
    @BeforeAll
    static void readShippedConfiguration() {
        rawYaml = readClasspathResource(APPLICATION_YAML);
        foldedYaml = rawYaml.toLowerCase(Locale.ROOT);
        yamlTree = parseYaml(rawYaml);
        rawMigration = readClasspathResource(SCHEMA_MIGRATION);
        moduleBase = resolveModuleBase();
    }

    /** Resolves the directory under the module base and asserts it does not exist. */
    @Test
    @DisplayName("src/test/resources is absent from this module")
    void moduleDeclaresNoTestResourceDirectory() {
        Path testResources = moduleBase.resolve(TEST_RESOURCES);
        assertFalse(Files.exists(testResources),
                () -> "test resource directory present at " + testResources.toAbsolutePath());
    }

    /** Walks every regular file under {@code src} and asserts no name matches a Logback configuration. */
    @Test
    @DisplayName("no Logback configuration file exists under src")
    void moduleDeclaresNoLogbackConfigurationFile() {
        List<Path> found = filesUnder(moduleBase.resolve(SOURCE_ROOT)).stream()
                .filter(path -> LOGBACK_FILE_NAMES.contains(fileName(path)))
                .toList();
        assertEquals(List.of(), found, () -> "Logback configuration files present: " + found);
    }

    /**
     * Walks the main resource directory and asserts no file name pairs the profile prefix with a
     * configuration file extension.
     */
    @Test
    @DisplayName("no application-* profile variant exists under src/main/resources")
    void mainResourcesDeclareNoProfileVariantFile() {
        List<Path> found = filesUnder(moduleBase.resolve(MAIN_RESOURCES)).stream()
                .filter(path -> fileName(path).startsWith(PROFILE_VARIANT_PREFIX))
                .filter(path -> PROFILE_VARIANT_SUFFIXES.stream().anyMatch(fileName(path)::endsWith))
                .toList();
        assertEquals(List.of(), found, () -> "profile variant files present: " + found);
    }

    /**
     * Asserts no line opens a second document, and that no profile selection key appears in the text or
     * in the parsed tree.
     */
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

    /** Asserts three spellings are absent from the text and the parsed tree carries no admin block. */
    @Test
    @DisplayName("spring.kafka.admin.fail-fast is absent in every spelling")
    void shippedYamlSetsNoKafkaAdminFailFast() {
        assertAll(
                () -> assertEquals(List.of(), fragmentsPresent(FAIL_FAST_SPELLINGS),
                        () -> "fail-fast text present: " + fragmentsPresent(FAIL_FAST_SPELLINGS)),
                () -> assertNull(valueAt("spring", "kafka", "admin"), "spring.kafka.admin is set"));
    }

    /** Asserts both spellings of the quoting property, and every dialect key, are absent. */
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

    /** Asserts the migration location key is absent and that schema cleaning is not switched on. */
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

    /** Asserts the classpath carries no second migration beside {@code V1__schema.sql}. */
    @Test
    @DisplayName("V2__seed.sql is absent from the classpath, and the absence is the contract")
    void migrationFolderShipsNoSeedMigration() {
        assertNull(getClass().getResource(SEED_MIGRATION),
                () -> "seed migration present at " + SEED_MIGRATION);
    }

    /** Asserts none of the six host ports the composition publishes, and no port key, appears in the text. */
    @Test
    @DisplayName("application.yml names none of the six published host ports")
    void shippedYamlNamesNoPublishedHostPort() {
        assertAll(
                () -> assertEquals(List.of(), fragmentsPresent(PUBLISHED_HOST_PORTS),
                        () -> "published host ports present: " + fragmentsPresent(PUBLISHED_HOST_PORTS)),
                () -> assertFalse(foldedYaml.contains("fraud_port"), "a FRAUD_PORT key is set"));
    }

    /** Asserts each {@code server.error.include-*} key is absent or set to a value that hides detail. */
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
     * Asserts every placeholder span in the shipped file carries a default separator at depth one, apart
     * from the one credential placeholder. The datasource connection string nests four placeholders
     * inside a fifth, and the scan covers all five.
     */
    @Test
    @DisplayName("every placeholder carries a default at depth one, apart from the credential placeholder")
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
                () -> assertEquals(List.of(CREDENTIAL_PLACEHOLDER), withoutDefault,
                        () -> "placeholders carrying no default: " + withoutDefault),
                () -> assertEquals(DATASOURCE_URL_PLACEHOLDER_SPANS, connectionStringSpans.size(),
                        () -> "spring.datasource.url placeholder spans: " + connectionStringSpans),
                () -> assertEquals(List.of(), nestedWithoutDefault,
                        () -> "spring.datasource.url placeholders carrying no default: "
                                + nestedWithoutDefault));
    }

    /**
     * Asserts the datasource and schema placeholders resolve to their defaults with no environment
     * variable set. The password resolves to the credential placeholder itself.
     */
    @Test
    @DisplayName("the datasource and schema placeholders resolve to their defaults")
    void datasourceDefaultsResolveWithNoEnvironmentVariableSet() {
        String connectionString = resolvedAt("spring", "datasource", "url");
        assertAll(
                () -> assertEquals(DEFAULT_DATASOURCE_URL, connectionString, "spring.datasource.url"),
                () -> assertFalse(connectionString.contains("${"),
                        () -> "spring.datasource.url keeps a placeholder: " + connectionString),
                () -> assertEquals("carddemo", resolvedAt("spring", "datasource", "username"),
                        "spring.datasource.username"),
                () -> assertEquals(CREDENTIAL_PLACEHOLDER, resolvedAt("spring", "datasource", "password"),
                        "spring.datasource.password"),
                () -> assertEquals(DEFAULT_SCHEMA, resolvedAt("spring", "flyway", "schemas"),
                        "spring.flyway.schemas"),
                () -> assertEquals(DEFAULT_SCHEMA, resolvedAt("spring", "flyway", "default-schema"),
                        "spring.flyway.default-schema"),
                () -> assertEquals(DEFAULT_SCHEMA,
                        resolvedAt("spring", "jpa", "properties", "hibernate", "default_schema"),
                        "spring.jpa.properties.hibernate.default_schema"));
    }

    /** Asserts the broker address and the consumer group resolve to their defaults. */
    @Test
    @DisplayName("the Kafka placeholders resolve to their defaults")
    void kafkaDefaultsResolveWithNoEnvironmentVariableSet() {
        assertAll(
                () -> assertEquals("kafka:29092", resolvedAt("spring", "kafka", "bootstrap-servers"),
                        "spring.kafka.bootstrap-servers"),
                () -> assertEquals("fraud-detection", resolvedAt("spring", "kafka", "consumer", "group-id"),
                        "spring.kafka.consumer.group-id"));
    }

    /** Asserts the port the container listens on and the application name the service reports. */
    @Test
    @DisplayName("server.port is 8080 under the application name fraud-detection-service")
    void serverListensOnTheContainerPort() {
        assertAll(
                () -> assertEquals("8080", textAt("server", "port"), "server.port"),
                () -> assertEquals("fraud-detection-service", textAt("spring", "application", "name"),
                        "spring.application.name"));
    }

    /**
     * Asserts {@code spring.jpa.hibernate.ddl-auto} is {@code validate} and none of the four rejected
     * values. The persistence layer opens no view and prints no statement.
     */
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

    /** Asserts the driver class name and the absence of every connection pool key. */
    @Test
    @DisplayName("spring.datasource names the PostgreSQL driver and no connection pool")
    void datasourceNamesTheDriverAndNoConnectionPool() {
        List<String> poolKeys = CONNECTION_POOL_KEYS.stream()
                .filter(key -> valueAt("spring", "datasource", key) != null)
                .toList();
        assertAll(
                () -> assertEquals("org.postgresql.Driver",
                        textAt("spring", "datasource", "driver-class-name"),
                        "spring.datasource.driver-class-name"),
                () -> assertEquals(List.of(), poolKeys, () -> "connection pool keys present: " + poolKeys));
    }

    /** Asserts the four Flyway switches that place schema creation and migration checking in Flyway. */
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

    /**
     * Asserts the migration issues no schema creation, sets no search path, and names every created
     * object without a schema prefix. Comments are stripped before the statements are read.
     */
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
                () -> assertFalse(folded.contains("fraud."), "the migration carries a schema prefix"),
                () -> assertFalse(objectNames.isEmpty(), "the migration creates no object"),
                () -> assertEquals(List.of(), qualified, () -> "qualified object names: " + qualified));
    }

    /** Asserts the consumer switches off automatic commit and names its offset reset and key reader. */
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

    /**
     * Asserts the producer waits for every in-sync replica, produces idempotently, caps in-flight
     * requests at five, and carries the three shipped timeouts and the two serializers.
     */
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
                () -> assertEquals("120000",
                        textAt("spring", "kafka", "producer", "properties", "delivery.timeout.ms"),
                        "delivery.timeout.ms"),
                () -> assertEquals("30000",
                        textAt("spring", "kafka", "producer", "properties", "request.timeout.ms"),
                        "request.timeout.ms"),
                () -> assertEquals("5000",
                        textAt("spring", "kafka", "producer", "properties", "max.block.ms"),
                        "max.block.ms"),
                () -> assertEquals("org.apache.kafka.common.serialization.StringSerializer",
                        textAt("spring", "kafka", "producer", "key-serializer"),
                        "spring.kafka.producer.key-serializer"),
                () -> assertEquals("com.carddemo.events.serde.JsonSchemaValidatingSerializer",
                        textAt("spring", "kafka", "producer", "value-serializer"),
                        "spring.kafka.producer.value-serializer"));
    }

    /**
     * Asserts the shipped acknowledgement mode string and the listener concurrency.
     * {@code ConfigurationInvariantsIT} asserts the effective runtime mode.
     * Record: {@code card-platform/docs/decision-log.md}.
     */
    @Test
    @DisplayName("spring.kafka.listener.ack-mode ships as manual_immediate; "
            + "ConfigurationInvariantsIT asserts the effective mode")
    void listenerAcknowledgementModeIsTheShippedString() {
        assertAll(
                () -> assertEquals("manual_immediate",
                        textAt("spring", "kafka", "listener", "ack-mode"),
                        "spring.kafka.listener.ack-mode"),
                () -> assertEquals("1", textAt("spring", "kafka", "listener", "concurrency"),
                        "spring.kafka.listener.concurrency"));
    }

    /**
     * Asserts the three names under the plural key {@code carddemo.kafka.topics}, each resolved to its
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
                () -> assertEquals("transaction.authorized.DLT",
                        resolvedAt("carddemo", "kafka", "topics", "dead-letter"),
                        "carddemo.kafka.topics.dead-letter"));
    }

    /** Asserts the relay delay and batch size, and the consumer retry attempt count and backoff. */
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
     * Asserts the exposure list resolves to three endpoint names in order and carries no wildcard and no
     * fourth name. Metric exposition is switched on.
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

    /** Asserts the console log format and the two shipped level defaults. */
    @Test
    @DisplayName("logging ships structured console output with INFO defaults")
    void logConfigurationCarriesItsShippedDefaults() {
        assertAll(
                () -> assertEquals("logstash", textAt("logging", "structured", "format", "console"),
                        "logging.structured.format.console"),
                () -> assertEquals("INFO", resolvedAt("logging", "level", "root"), "logging.level.root"),
                () -> assertEquals("INFO", resolvedAt("logging", "level", "com.carddemo"),
                        "logging.level.com.carddemo"));
    }

    /**
     * Asserts the statement logger and the bind parameter logger are absent from the shipped levels or
     * set no lower than informational. No other Hibernate logger is verbose either.
     */
    @Test
    @DisplayName("org.hibernate.SQL and org.hibernate.orm.jdbc.bind are absent or no more verbose than "
            + "INFO; CardDataExposureTest reads the runtime logger context")
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

    /** Reads a classpath resource as text, failing with the resource path when it is absent. */
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

    /** Parses YAML text into nested maps, leaving every placeholder unresolved. */
    private static Map<String, Object> parseYaml(String text) {
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        if (!(loaded instanceof Map<?, ?> mapping)) {
            throw new AssertionError(APPLICATION_YAML + " does not parse to a mapping");
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        mapping.forEach((key, value) -> tree.put(String.valueOf(key), value));
        return tree;
    }

    /** Resolves the module base directory, failing with the resolved path when the marker is absent. */
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

    /** Lists every regular file under a directory, returning an empty list when the directory is absent. */
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

    /** Returns a path's file name folded to lower case. */
    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
    }

    /** Returns the fragments of the given list that occur in the shipped configuration text. */
    private static List<String> fragmentsPresent(List<String> fragments) {
        return fragments.stream().filter(foldedYaml::contains).toList();
    }

    /** Returns the value at a key path in the parsed tree, or {@code null} when a segment is absent. */
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

    /** Returns the value at a key path as text, or {@code null} when a segment is absent. */
    private static String textAt(String... path) {
        Object value = valueAt(path);
        return value == null ? null : String.valueOf(value);
    }

    /** Returns the value at a key path with every placeholder replaced by its default. */
    private static String resolvedAt(String... path) {
        return resolveDefaults(textAt(path));
    }

    /** Returns a comma separated value as a list of stripped names, empty when the value is absent. */
    private static List<String> commaSeparated(String value) {
        if (value == null) {
            return List.of();
        }
        return Stream.of(value.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    /** Returns every placeholder span in the text, nested spans included. */
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

    /** Returns the index of the brace that closes the placeholder starting at the given index. */
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

    /** Returns the index of the default separator at depth one of a span, or -1 when the span has none. */
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

    /**
     * Replaces every placeholder in a value with its depth one default, applying the same replacement to
     * a nested default. A placeholder carrying no default stays as it is.
     */
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

    /** Returns the migration text with every block comment and line comment removed. */
    private static String sqlWithoutComments(String sql) {
        String withoutBlocks = SQL_BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        return withoutBlocks.lines()
                .map(line -> SQL_LINE_COMMENT.matcher(line).replaceAll(""))
                .collect(Collectors.joining("\n"));
    }

    /** Returns every table name, index name and index target the given statements create. */
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

    /** Returns the shipped logger levels, each resolved to its default. */
    private static Map<String, String> loggingLevels() {
        Map<String, String> levels = new LinkedHashMap<>();
        if (valueAt("logging", "level") instanceof Map<?, ?> mapping) {
            mapping.forEach((logger, level) ->
                    levels.put(String.valueOf(logger), resolveDefaults(String.valueOf(level))));
        }
        return levels;
    }

    /** Reports whether a level is more verbose than informational. */
    private static boolean isVerbose(String level) {
        return level != null && !NON_VERBOSE_LEVELS.contains(level.toUpperCase(Locale.ROOT));
    }
}
