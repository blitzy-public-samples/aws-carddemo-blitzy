package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the environment-variable inventory shared by dotenv, Compose, Kubernetes, and Spring.
 * Finding m1 identified six undocumented application overrides and one documented dead key.
 * Each assertion reads the shipped files, so a renamed or orphaned key fails the unit-test phase.
 */
@DisplayName("Configuration inventory across .env, Compose, Kubernetes, and application YAML")
class ConfigurationInventoryContractTest {

    private static final Map<String, String> EXPECTED_DEFAULTS = Map.of(
            "OUTBOX_PUBLISHED_RETENTION_HOURS", "168",
            // 720 rather than 168 since a security review found the marker horizon equal to broker
            // log retention. A record still readable after its marker was swept is applied twice, so
            // every service now refuses a horizon under twice KAFKA_LOG_RETENTION_HOURS at start-up.
            "PROCESSED_EVENT_RETENTION_HOURS", "720",
            "STATEMENT_RETENTION_DAYS", "400",
            "NOTIFICATION_LOG_RETENTION_DAYS", "90",
            "MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED", "true");

    private static final Map<String, Integer> APPLICATION_REFERENCE_COUNTS = Map.of(
            "OUTBOX_PUBLISHED_RETENTION_HOURS", 5,
            "PROCESSED_EVENT_RETENTION_HOURS", 6,
            "STATEMENT_RETENTION_DAYS", 1,
            "NOTIFICATION_LOG_RETENTION_DAYS", 1);

    private static final Map<String, Integer> COMPOSE_ENVIRONMENT_COUNTS = Map.of(
            "OUTBOX_PUBLISHED_RETENTION_HOURS", 5,
            "PROCESSED_EVENT_RETENTION_HOURS", 6,
            "STATEMENT_RETENTION_DAYS", 1,
            "NOTIFICATION_LOG_RETENTION_DAYS", 1,
            "MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED", 1);

    private static final Set<String> OUTBOX_RETENTION_SERVICES = Set.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "account-service",
            "card-service");

    private static final Set<String> MARKER_RETENTION_SERVICES = Set.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "notification-service",
            "account-service",
            "card-service");

    private static final List<String> APPLICATION_SERVICES = List.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "notification-service",
            "account-service",
            "card-service");

    @Test
    void theExampleEnvironmentDeclaresEverySupportedOverrideWithItsShippedDefault() {
        Map<String, String> dotenv = dotenvValues(platformDirectory().resolve(".env.example"));

        for (Map.Entry<String, String> expected : EXPECTED_DEFAULTS.entrySet()) {
            assertEquals(expected.getValue(), dotenv.get(expected.getKey()),
                    expected.getKey() + " must be documented with its shipped default");
        }
    }

    @Test
    void everyApplicationPlaceholderMatchesTheDocumentedDefault() {
        String applications = applicationConfiguration();

        for (Map.Entry<String, Integer> expected : APPLICATION_REFERENCE_COUNTS.entrySet()) {
            String placeholder = "${" + expected.getKey() + ":"
                    + EXPECTED_DEFAULTS.get(expected.getKey()) + "}";
            assertEquals(expected.getValue().intValue(), count(applications, placeholder),
                    expected.getKey() + " must keep the documented Spring placeholder count");
        }
    }

    @Test
    void composePassesEachOverrideOnlyToServicesThatReadIt() {
        String compose = read(platformDirectory().resolve("docker-compose.yml"));

        for (Map.Entry<String, Integer> expected : COMPOSE_ENVIRONMENT_COUNTS.entrySet()) {
            assertEquals(expected.getValue().intValue(),
                    count(compose, expected.getKey() + ": ${" + expected.getKey() + ":-"
                            + EXPECTED_DEFAULTS.get(expected.getKey()) + "}"),
                    expected.getKey() + " must have the expected Compose environment count");
        }

        for (String service : APPLICATION_SERVICES) {
            String block = serviceBlock(compose, service);
            assertEquals(OUTBOX_RETENTION_SERVICES.contains(service),
                    block.contains("OUTBOX_PUBLISHED_RETENTION_HOURS:"),
                    service + " outbox-retention mapping must match application.yml");
            assertEquals(MARKER_RETENTION_SERVICES.contains(service),
                    block.contains("PROCESSED_EVENT_RETENTION_HOURS:"),
                    service + " marker-retention mapping must match application.yml");
        }

        String notification = serviceBlock(compose, "notification-service");
        for (String key : Set.of("STATEMENT_RETENTION_DAYS",
                "NOTIFICATION_LOG_RETENTION_DAYS")) {
            assertTrue(notification.contains(key + ":"),
                    "notification-service must receive " + key);
        }
    }

    @Test
    void everyServiceInheritsTheHealthProbeBinding() {
        String compose = read(platformDirectory().resolve("docker-compose.yml"));

        assertTrue(compose.contains(
                        "MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "
                                + "${MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED:-true}"),
                "the shared service environment must bind the documented health-probe key");
        assertEquals(APPLICATION_SERVICES.size(), count(compose, "<<: *service-environment"),
                "all six service containers must inherit the shared environment");
    }

    /**
     * The composition has to grant and wire the topic the account service reads.
     *
     * <p>Three things have to agree, and a mismatch in any one is quiet. The access control matrix
     * must carry a consumer entry, or the Kafka authorizer refuses every delivery. The container must
     * receive the topic name and the group name, or the listener joins the wrong stream. And the
     * group must be the account service's own: two services in one group split the partitions, so
     * each would apply roughly half the postings and neither would raise anything.
     *
     * <p>The retry settings are asserted here too, because the shipped configuration binds them and a
     * container that never receives them takes each record once. That turns a transient database
     * failure into a dead-lettered posting, which is a balance the account record never receives.
     */
    @Test
    void composeGrantsAndWiresTheTopicTheAccountServiceReads() {
        String compose = read(platformDirectory().resolve("docker-compose.yml"));

        assertTrue(compose.contains(
                        "grant_consumer \"$${ACCOUNT_KAFKA_USER}\" \"$${TOPIC_TRANSACTION_POSTED}\""),
                "the access control matrix must let the account service read transaction.posted, or "
                        + "the authorizer refuses every delivery");
        assertTrue(compose.contains("\"$${GROUP_ACCOUNT_POSTED}\" || return 1"),
                "the consumer entry must name the group the account listener joins");
        assertTrue(compose.contains(
                        "grant_producer \"$${ACCOUNT_KAFKA_USER}\" \"$${TOPIC_DEAD_LETTER}\""),
                "a record the account listener cannot apply has to reach the shared dead-letter "
                        + "topic");

        String account = serviceBlock(compose, "account-service");
        assertTrue(account.contains(
                        "TOPIC_TRANSACTION_POSTED: ${TOPIC_TRANSACTION_POSTED:-transaction.posted}"),
                "the account container must receive the topic it reads");
        assertTrue(account.contains(
                        "SPRING_KAFKA_CONSUMER_GROUP_ID: ${GROUP_ACCOUNT_POSTED:-account-posted}"),
                "the account container must receive the group its listener joins");
        assertTrue(account.contains("CONSUMER_MAX_RETRY_ATTEMPTS:")
                        && account.contains("CONSUMER_RETRY_BACKOFF_MS:"),
                "the account container must receive the retry policy, or one transient failure "
                        + "dead-letters a posting");

        String notification = serviceBlock(compose, "notification-service");
        assertTrue(notification.contains("GROUP_NOTIFICATION_POSTED:"),
                "the notification service must keep its own group on the same topic, or the two "
                        + "consumers split the partitions between them");
        assertFalse(notification.contains("GROUP_ACCOUNT_POSTED:"),
                "no other container may join the account group");
    }

    @Test
    void theKubernetesConfigMapCarriesTheSameSevenStringDefaults() {
        String configMap =
                read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml"));

        for (Map.Entry<String, String> expected : EXPECTED_DEFAULTS.entrySet()) {
            Pattern quotedEntry = Pattern.compile("(?m)^  "
                    + Pattern.quote(expected.getKey()) + ": \""
                    + Pattern.quote(expected.getValue()) + "\"$");
            assertTrue(quotedEntry.matcher(configMap).find(),
                    expected.getKey() + " must be a quoted ConfigMap string");
        }
    }

    @Test
    void noReviewedOverrideRemainsUndocumentedOrDead() {
        Map<String, String> dotenv = dotenvValues(platformDirectory().resolve(".env.example"));
        String compose = read(platformDirectory().resolve("docker-compose.yml"));
        String applications = applicationConfiguration();

        for (String key : EXPECTED_DEFAULTS.keySet()) {
            assertTrue(dotenv.containsKey(key), key + " must exist in .env.example");
            assertTrue(compose.contains(key + ":"), key + " must reach a container");
            if (!key.equals("MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED")) {
                assertTrue(applications.contains("${" + key + ":"),
                        key + " must remain an application override");
            }
        }
        assertFalse(applications.contains("${MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED:"),
                "Spring Boot binds the management key directly from the container environment");
    }

    /**
     * Holds the one unmanaged pin this build carries, and the source of structured log output.
     *
     * <p>A pin no module declares governs nothing: {@code dependencyManagement} has no coordinate to
     * manage, the library never reaches a classpath, and a reader is left believing a dependency is
     * present that is absent. {@code net.logstash.logback:logstash-logback-encoder} was such a pin
     * and is withdrawn, so this test fails if it returns without a module that declares it.
     *
     * <p>The other half of the assertion is what replaced it. Structured JSON output comes from the
     * framework, selected by one property per service, and no service ships a Logback configuration
     * file. A configuration file appearing beside that property would mean two mechanisms competed
     * for the same output, and the file would win silently.
     */
    @Test
    @DisplayName("one unmanaged pin, and structured logging taken from the framework")
    void theBuildPinsOnlyTheUnmanagedArtifactItDeclares() {
        List<Path> poms = new ArrayList<>();
        poms.add(platformDirectory().resolve("pom.xml"));
        poms.add(platformDirectory().resolve("libs/event-contracts/pom.xml"));
        poms.add(platformDirectory().resolve("libs/cobol-compat/pom.xml"));
        poms.add(platformDirectory().resolve("equivalence-tests/pom.xml"));
        for (String service : APPLICATION_SERVICES) {
            poms.add(platformDirectory().resolve("services").resolve(service).resolve("pom.xml"));
        }

        for (Path pom : poms) {
            assertFalse(read(pom).contains("<artifactId>logstash-logback-encoder</artifactId>"),
                    pom.getFileName() + " under " + pom.getParent().getFileName()
                            + " declares an encoder no version pins; pin it in the aggregator "
                            + "first, and record the decision");
        }
        assertTrue(read(poms.get(0))
                        .contains("<artifactId>json-schema-validator</artifactId>"),
                "the aggregator has to keep pinning the one artifact the bill of materials does "
                        + "not manage");

        for (String service : APPLICATION_SERVICES) {
            Path resources = platformDirectory().resolve("services")
                    .resolve(service)
                    .resolve("src/main/resources");
            assertTrue(read(resources.resolve("application.yml"))
                            .contains("console: logstash"),
                    service + " has to select the Logstash format the framework implements");
            for (String file : List.of("logback.xml", "logback-spring.xml")) {
                assertFalse(Files.exists(resources.resolve(file)),
                        service + " ships " + file + ", which takes over the output the "
                                + "logging.structured.format.console property configures");
            }
        }
    }

    private static Map<String, String> dotenvValues(Path file) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : read(file).lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            assertTrue(separator > 0, "dotenv entry must use KEY=value syntax: " + line);
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            assertFalse(values.containsKey(key), key + " must be declared once");
            values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private static String applicationConfiguration() {
        StringBuilder combined = new StringBuilder();
        for (String service : APPLICATION_SERVICES) {
            Path file = platformDirectory().resolve("services")
                    .resolve(service)
                    .resolve("src/main/resources/application.yml");
            combined.append(read(file)).append('\n');
        }
        return combined.toString();
    }

    private static String serviceBlock(String compose, String service) {
        Pattern blockPattern = Pattern.compile(
                "(?ms)^  " + Pattern.quote(service) + ":\\n(.*?)(?=^  [a-z0-9-]+:\\n|\\z)");
        Matcher matcher = blockPattern.matcher(compose);
        assertTrue(matcher.find(), "docker-compose.yml must declare " + service);
        return matcher.group(1);
    }

    private static int count(String text, String token) {
        int occurrences = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            occurrences++;
            offset += token.length();
        }
        return occurrences;
    }

    private static Path platformDirectory() {
        return CardDemoFixtureLoader.fixtureDirectory()
                .getParent()
                .getParent()
                .getParent()
                .resolve("card-platform");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}