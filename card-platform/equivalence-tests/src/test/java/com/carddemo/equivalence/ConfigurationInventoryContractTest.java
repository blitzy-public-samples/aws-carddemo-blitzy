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

    /**
     * The sweep budget every service ships, from {@code carddemo.outbox.relay.max-duration-ms}.
     *
     * <p>Used where a deployment artifact overrides a send component and leaves the budget to the
     * service's own configuration.
     */
    private static final int SHIPPED_RELAY_BUDGET_MS = 5_000;

    /**
     * The value every service ships for each of the two send components that make up one send window,
     * {@code max.block.ms} and {@code delivery.timeout.ms}.
     */
    private static final int SHIPPED_SEND_COMPONENT_MS = 2_000;

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

    /**
     * Asserts every relay's instance identifier falls back to the hostname, never to a literal.
     *
     * <p><b>What the identifier is for.</b> A relay writes it into {@code outbox_event.claimed_by} when
     * it claims a row, and {@code recoverStrandedClaims} uses it to tell a claim a crashed process left
     * behind from one a running process still holds. That distinction only works while the value differs
     * per replica.
     *
     * <p><b>The defect.</b> The fraud service defaulted to the literal {@code fraud-relay-1}. Two
     * replicas then claimed rows under one identity, so recovery could take a row another running
     * instance was still publishing and publish it a second time. The literal also read as an instance
     * number that nothing ever incremented, which invites a reader to assume the platform allocates
     * them.
     *
     * <p><b>Why the fallback is the hostname.</b> A container's {@code HOSTNAME} is its Pod name under
     * Kubernetes and its container id under Compose, both already distinct per replica, so the value
     * costs no configuration and cannot collide. {@code deploy/k8s/30-configmap.yaml} deliberately
     * declares no key for it, because even an empty key would be a defined variable and would win over
     * the hostname.
     *
     * <p>The assertion covers all five producing services rather than the one that was wrong, which is
     * what stops the sixth from repeating it.
     */
    @Test
    @DisplayName("every relay instance identifier falls back to the hostname, not a literal")
    void everyRelayInstanceIdentifierFallsBackToTheHostname() {
        List<String> divergences = new ArrayList<>();

        for (String service : OUTBOX_RETENTION_SERVICES) {
            String configuration = read(platformDirectory().resolve("services").resolve(service)
                    .resolve("src/main/resources/application.yml"));
            Matcher declared = Pattern
                    .compile("instance-id:\\s*\"?\\$\\{OUTBOX_RELAY_INSTANCE_ID:([^}\"]*)")
                    .matcher(configuration);

            if (!declared.find()) {
                divergences.add(service + " declares no carddemo.outbox.relay.instance-id fallback,"
                        + " so a deployment that sets no variable leaves every replica claiming"
                        + " rows under one empty identity");
                continue;
            }
            if (!declared.group(1).startsWith("${HOSTNAME:")) {
                divergences.add(service + " falls back to the literal '" + declared.group(1)
                        + "' rather than to ${HOSTNAME:...}, so two replicas claim rows under one"
                        + " identity and stranded-claim recovery cannot tell them apart");
            }
        }

        assertEquals(List.of(), divergences,
                "a relay identity shared by two replicas turns claim recovery into a second"
                        + " publish: " + divergences);
    }

    /**
     * Asserts no deployment artifact widens a producer send past the relay sweep budget.
     *
     * <p><b>The defect this stands over.</b> Every producing service pins
     * {@code delivery.timeout.ms}, {@code request.timeout.ms} and {@code max.block.ms} at 2000 in its
     * own {@code application.yml}, so one send resolves in at most
     * {@code max.block.ms + delivery.timeout.ms} = 4000 milliseconds, inside the 5000-millisecond
     * {@code carddemo.outbox.relay.max-duration-ms} that bounds the whole sweep. The card service
     * block of {@code docker-compose.yml} overrode all three with 120000, 30000 and 5000, and an
     * environment variable outranks {@code application.yml}. The effective window became 125 seconds
     * under a 5-second deadline: the relay stopped waiting while the producer kept trying, so a row
     * could be published by a send the relay had abandoned and republished by the next sweep.
     *
     * <p><b>Why this is asserted over the deployment files rather than over the properties.</b> The
     * per-service property tests already hold the relationship inside {@code application.yml}, and
     * that is exactly what the defect went around. What was missing was any assertion over the
     * <em>effective</em> value, so a Compose or Kubernetes override could restate one of the three at
     * any size and nothing failed.
     *
     * <p>An override is not forbidden outright. It is required to keep the invariant, so a deployment
     * that genuinely needs a wider send window raises the deadline with it and this test passes.
     */
    @Test
    @DisplayName("no Compose or Kubernetes override widens a send past the relay sweep budget")
    void noDeploymentOverrideWidensASendPastTheRelaySweepBudget() {
        Path platform = platformDirectory();
        List<String> divergences = new ArrayList<>();

        for (String artifact : List.of("docker-compose.yml", "deploy/k8s/30-configmap.yaml",
                "deploy/k8s/40-authorization-service.yaml", "deploy/k8s/41-ledger-posting-service.yaml",
                "deploy/k8s/42-fraud-detection-service.yaml", "deploy/k8s/44-account-service.yaml",
                "deploy/k8s/45-card-service.yaml")) {

            String text = read(platform.resolve(artifact));
            Integer maxBlock = declaredMilliseconds(text, "SPRING_KAFKA_PRODUCER_PROPERTIES_MAX_BLOCK_MS");
            Integer delivery =
                    declaredMilliseconds(text, "SPRING_KAFKA_PRODUCER_PROPERTIES_DELIVERY_TIMEOUT_MS");
            Integer budget = declaredMilliseconds(text, "OUTBOX_RELAY_MAX_DURATION_MS");

            if (maxBlock == null && delivery == null) {
                continue;
            }
            int effectiveBudget = budget == null ? SHIPPED_RELAY_BUDGET_MS : budget;
            int window = (maxBlock == null ? SHIPPED_SEND_COMPONENT_MS : maxBlock)
                    + (delivery == null ? SHIPPED_SEND_COMPONENT_MS : delivery);

            if (window >= effectiveBudget) {
                divergences.add(artifact + " lets one send take up to " + window
                        + "ms while the relay sweep is bounded at " + effectiveBudget
                        + "ms, so the relay gives up on a send the producer is still making");
            }
        }

        assertEquals(List.of(), divergences,
                "a producer window wider than the sweep budget publishes a row the relay stopped"
                        + " waiting for, and the next sweep publishes it again: " + divergences);
    }

    /**
     * Reads one millisecond setting out of a deployment file, taking the literal a key is set to.
     *
     * <p>A value written as a Compose substitution keeps its default, {@code ${NAME:-5000}}, which is
     * what the artifact ships. A key the file does not set at all reads {@code null}, meaning the
     * service's own {@code application.yml} decides.
     *
     * @param text the artifact contents
     * @param key  the environment variable name
     * @return the shipped millisecond value, or {@code null} when the artifact sets none
     */
    private static Integer declaredMilliseconds(String text, String key) {
        Matcher setting = Pattern.compile(Pattern.quote(key)
                + "\\s*:\\s*\"?(?:\\$\\{[A-Z0-9_]+:-?)?(\\d+)").matcher(text);
        return setting.find() ? Integer.valueOf(setting.group(1)) : null;
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