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

    private static final Map<String, String> EXPECTED_DEFAULTS = Map.ofEntries(
            Map.entry("OUTBOX_PUBLISHED_RETENTION_HOURS", "168"),
            Map.entry("STATEMENT_RETENTION_DAYS", "400"),
            Map.entry("NOTIFICATION_LOG_RETENTION_DAYS", "90"),
            // The four below were bindable in application.yml and named by no deployment artifact,
            // so the shipped default was the only value any of them could ever hold. Each is now
            // declared in .env.example, passed by Compose to exactly the containers that read it,
            // and carried once by the Kubernetes ConfigMap.
            Map.entry("API_MAX_REQUEST_BODY_BYTES", "65536"),
            Map.entry("OUTBOX_RELAY_PUBLISH_TIMEOUT", "PT10S"),
            Map.entry("DECISION_RETENTION_DAYS", "365"),
            Map.entry("WRITE_LOCK_WAIT_MS", "3000"),
            // PT2M in all five relays. The fraud service shipped PT30S, so the documented default
            // described four of them, and only a run that set no variable ever saw the difference.
            Map.entry("OUTBOX_RELAY_CLAIM_TIMEOUT", "PT2M"),
            Map.entry("MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED", "true"));

    private static final Map<String, Integer> APPLICATION_REFERENCE_COUNTS = Map.ofEntries(
            Map.entry("OUTBOX_PUBLISHED_RETENTION_HOURS", 5),
            Map.entry("STATEMENT_RETENTION_DAYS", 1),
            Map.entry("NOTIFICATION_LOG_RETENTION_DAYS", 1),
            Map.entry("API_MAX_REQUEST_BODY_BYTES", 3),
            Map.entry("OUTBOX_RELAY_PUBLISH_TIMEOUT", 3),
            Map.entry("DECISION_RETENTION_DAYS", 1),
            Map.entry("WRITE_LOCK_WAIT_MS", 2),
            Map.entry("OUTBOX_RELAY_CLAIM_TIMEOUT", 5));

    private static final Map<String, Integer> COMPOSE_ENVIRONMENT_COUNTS = Map.ofEntries(
            Map.entry("OUTBOX_PUBLISHED_RETENTION_HOURS", 5),
            Map.entry("STATEMENT_RETENTION_DAYS", 1),
            Map.entry("NOTIFICATION_LOG_RETENTION_DAYS", 1),
            Map.entry("API_MAX_REQUEST_BODY_BYTES", 3),
            Map.entry("OUTBOX_RELAY_PUBLISH_TIMEOUT", 3),
            Map.entry("DECISION_RETENTION_DAYS", 1),
            Map.entry("WRITE_LOCK_WAIT_MS", 2),
            Map.entry("OUTBOX_RELAY_CLAIM_TIMEOUT", 5),
            Map.entry("MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED", 1));

    /**
     * The overrides a service reads from {@code application.yml} rather than from the framework, and
     * which therefore have to reach exactly the containers that bind them.
     *
     * <p>Used by {@link #composeCarriesEachCustomOverrideToExactlyTheServicesThatBindIt()}, which
     * derives the expected service set from the shipped {@code application.yml} files instead of
     * restating it, so a service that starts or stops binding one of these moves the expectation with
     * it. {@code MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED} is excluded because Spring Boot binds it
     * directly from the environment and no {@code application.yml} names it.
     */
    private static final List<String> CUSTOM_OVERRIDES = List.of(
            "OUTBOX_PUBLISHED_RETENTION_HOURS",
            "STATEMENT_RETENTION_DAYS",
            "NOTIFICATION_LOG_RETENTION_DAYS",
            "API_MAX_REQUEST_BODY_BYTES",
            "OUTBOX_RELAY_PUBLISH_TIMEOUT",
            "DECISION_RETENTION_DAYS",
            "WRITE_LOCK_WAIT_MS",
            "OUTBOX_RELAY_CLAIM_TIMEOUT");

    /**
     * Keys this platform retired, which no deployment artifact may declare again.
     *
     * <p>{@code GROUP_AUTHORIZATION} named the authorization service's framework-level default
     * consumer group. No listener joined it, the broker's access-control matrix granted it nothing,
     * and the container was never given it, so a reader who scoped the group names per stack set one
     * value that did nothing. {@code OUTBOX_RELAY_INSTANCE_ID} is the opposite failure: it is a real
     * override, and it has to stay unset in a container so each relay falls back to its own hostname.
     * Declaring it in the example invited an operator to give every replica one identity, which is
     * what turns stranded-claim recovery into a second publish.
     *
     * <p>{@code PROCESSED_EVENT_RETENTION_HOURS} is the third, and it is retired rather than
     * renamed. It would set a horizon after which a duplicate-delivery claim is deleted, and every
     * effect a claim guards outlives such a horizon: a posted balance, a category balance, a cycle
     * accumulator, a read-model row. A claim is permanent, so the key would name a behaviour no
     * service has. Retiring it is what stops a deployment reintroducing the horizon by setting a
     * variable.
     */
    private static final List<String> RETIRED_KEYS = List.of(
            "GROUP_AUTHORIZATION",
            "OUTBOX_RELAY_INSTANCE_ID",
            "PROCESSED_EVENT_RETENTION_HOURS");

    private static final Set<String> OUTBOX_RETENTION_SERVICES = Set.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
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
            assertFalse(block.contains("PROCESSED_EVENT_RETENTION_HOURS:"),
                    service + " must receive no marker horizon: a claim is permanent, so the key "
                            + "would name a behaviour the service does not have");
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
    void theKubernetesConfigMapCarriesEveryDocumentedDefaultAsAQuotedString() {
        String configMap =
                read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml"));

        for (Map.Entry<String, String> expected : EXPECTED_DEFAULTS.entrySet()) {
            Pattern quotedEntry = Pattern.compile("(?m)^  "
                    + Pattern.quote(expected.getKey()) + ": \""
                    + Pattern.quote(expected.getValue()) + "\"$");
            assertTrue(quotedEntry.matcher(configMap).find(),
                    expected.getKey() + " must be a quoted ConfigMap string");
            assertEquals(1, count(configMap, "\n  " + expected.getKey() + ": "),
                    expected.getKey() + " must be declared once in the ConfigMap; a second entry "
                            + "would make editing the first change nothing");
        }
    }

    /**
     * Asserts each custom override reaches exactly the containers that bind it, and no others.
     *
     * <p><b>The defect this stands over.</b> {@code API_MAX_REQUEST_BODY_BYTES},
     * {@code OUTBOX_RELAY_PUBLISH_TIMEOUT} and {@code DECISION_RETENTION_DAYS} were bindable in
     * {@code application.yml} and named by no deployment artifact at all. The shipped default was
     * therefore the only value any of them could hold: {@code .env.example} documented no name to set,
     * Compose passed none, and the Kubernetes ConfigMap declared none. A reader who found the
     * placeholder in {@code application.yml} and set the variable in {@code .env} changed nothing,
     * because Compose hands each container an explicit key list rather than the whole file.
     *
     * <p><b>The expectation is derived rather than listed.</b> The service set comes from the
     * shipped {@code application.yml} files, so a service that starts binding one of these keys, or
     * stops, moves the expectation with it. A hand-kept list is the thing that went stale here in the
     * first place.
     *
     * <p>Both directions matter. A key missing from a container that reads it is an override that
     * silently does nothing. A key handed to a container that reads it is documentation of a setting
     * that container does not have, and it reads as though the service honours it.
     */
    @Test
    @DisplayName("every custom override reaches exactly the containers whose application.yml binds it")
    void composeCarriesEachCustomOverrideToExactlyTheServicesThatBindIt() {
        String compose = read(platformDirectory().resolve("docker-compose.yml"));
        List<String> divergences = new ArrayList<>();

        for (String key : CUSTOM_OVERRIDES) {
            for (String service : APPLICATION_SERVICES) {
                boolean bound = read(platformDirectory().resolve("services").resolve(service)
                        .resolve("src/main/resources/application.yml"))
                        .contains("${" + key + ":");
                boolean passed = serviceBlock(compose, service).contains(key + ":");

                if (bound && !passed) {
                    divergences.add(service + " binds " + key + " and the Compose block passes it "
                            + "nothing, so setting the name in .env changes nothing");
                }
                if (passed && !bound) {
                    divergences.add(service + " receives " + key + " and reads it nowhere, which "
                            + "documents a setting that container does not have");
                }
            }
        }

        assertEquals(List.of(), divergences,
                "an override reaches the containers that bind it and no others: " + divergences);
    }

    /**
     * Asserts no deployment artifact declares a key this platform retired.
     *
     * <p>Two names are covered, and they failed in opposite directions. {@code GROUP_AUTHORIZATION}
     * reached nothing: no listener joined the group, no broker entry granted it, and the container was
     * never given it, so it was one value out of eleven that a reader scoping group names per stack
     * set for no effect. {@code OUTBOX_RELAY_INSTANCE_ID} is a live override that has to stay unset in
     * a container, because a relay falls back to its hostname and a hostname is already distinct per
     * replica; a declared value — even an empty one — is a defined variable that wins over the
     * hostname and leaves every replica claiming rows under one identity.
     *
     * <p>The relay identifier therefore remains an {@code application.yml} placeholder, which
     * {@link #everyRelayInstanceIdentifierFallsBackToTheHostname()} asserts, and is absent from every
     * artifact a deployment hands to a container.
     */
    @Test
    @DisplayName("no retired key returns to .env.example, Compose or the ConfigMap")
    void noRetiredKeyReturnsToADeploymentArtifact() {
        Path platform = platformDirectory();
        Map<String, String> dotenv = dotenvValues(platform.resolve(".env.example"));
        String compose = read(platform.resolve("docker-compose.yml"));
        String configMap = read(platform.resolve("deploy/k8s/30-configmap.yaml"));
        List<String> divergences = new ArrayList<>();

        for (String key : RETIRED_KEYS) {
            if (dotenv.containsKey(key)) {
                divergences.add(".env.example declares " + key + " as a settable assignment");
            }
            // The boundary matters: GROUP_AUTHORIZATION is a prefix of the two active group names.
            Pattern setting = Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
                    + "(?![A-Z0-9_])\\s*:");
            if (setting.matcher(compose).find()) {
                divergences.add("docker-compose.yml sets " + key + " on a container");
            }
            if (setting.matcher(configMap).find()) {
                divergences.add("30-configmap.yaml declares " + key);
            }
        }

        String applications = applicationConfiguration();
        assertFalse(Pattern.compile("\\$\\{GROUP_AUTHORIZATION(?![A-Z0-9_])")
                        .matcher(applications).find(),
                "no application.yml may read GROUP_AUTHORIZATION; the authorization service's "
                        + "framework-level group falls back to a literal and is overridden by "
                        + "SPRING_KAFKA_CONSUMER_GROUP_ID, as the other five are");
        assertTrue(applications.contains("${OUTBOX_RELAY_INSTANCE_ID:"),
                "the relay identifier stays an application override, settable for a relay run as a "
                        + "direct process outside a container");

        assertEquals(List.of(), divergences,
                "a retired key a deployment declares is either dead configuration or a value that "
                        + "defeats the fallback it replaced: " + divergences);
    }

    /**
     * Asserts the inventory counts the deployment files state match the inventory they carry.
     *
     * <p>Each number was measured wrong. The ConfigMap said seven topics where fourteen exist, named
     * four dead-letter topics where six do, and counted ten consumer groups where eleven listeners
     * run. The Secret template said seventeen Secrets where eighteen are declared, and four hashes in
     * the shared identity Secret where three sit there and the fourth sits in a Secret only the
     * authorization service pulls. A count stated in a comment is what a reader trusts instead of
     * counting, so a wrong one is worse than none.
     *
     * <p>Each assertion reads the file it describes, so the number and the thing it counts cannot
     * drift apart again.
     */
    @Test
    @DisplayName("stated inventory counts match the inventory the deployment files declare")
    void statedInventoryCountsMatchTheDeclaredInventory() {
        Path k8s = platformDirectory().resolve("deploy/k8s");
        String configMap = read(k8s.resolve("30-configmap.yaml"));
        String secrets = read(k8s.resolve("31-secret.example.yaml"));

        assertEquals(7, count(configMap, "\n  TOPIC_") - count(configMap, "\n  TOPIC_DEAD_LETTER"),
                "the ConfigMap names the seven event topics; the six source dead-letter topics and "
                        + "the shared fallback complete the fourteen 10-kafka.yaml creates");
        assertTrue(configMap.contains("fourteen topics"),
                "the ConfigMap has to state the whole inventory rather than the seven names it "
                        + "carries, because a reader counts the comment and not the topics");
        for (String deadLetter : List.of("transaction.authorized.DLT", "transaction.declined.DLT",
                "transaction.posted.DLT", "fraud.assessed.DLT", "account.state-changed.DLT",
                "customer.context-changed.DLT")) {
            assertTrue(configMap.contains(deadLetter),
                    "the ConfigMap must name " + deadLetter + "; naming four of the six left two "
                            + "streams a reader would not look for");
        }
        assertEquals(11, count(configMap, "\n  GROUP_"),
                "eleven listeners run, one consumer group each");
        assertTrue(configMap.contains("Eleven groups, one per active listener"),
                "the stated group count has to be the eleven the file declares");

        assertEquals(18, count(secrets, "\nkind: Secret"),
                "eighteen Secrets are declared");
        assertTrue(secrets.contains("EIGHTEEN Secrets"),
                "the Secret template header has to state the number of Secrets it declares");
        assertTrue(secrets.contains("The THREE request-identity password hashes"),
                "three hashes sit in the shared identity Secret; the acquirer hash sits in a Secret "
                        + "only 40-authorization-service.yaml pulls");
        assertEquals(3, count(secrets, "_PASSWORD_HASH: \"{bcrypt}")
                        - count(secrets, "ACQUIRER_PASSWORD_HASH: \"{bcrypt}"),
                "the shared identity Secret carries the administrator, cardholder and monitoring "
                        + "hashes and no fourth");
        assertEquals(1, count(secrets, "ACQUIRER_PASSWORD_HASH: \"{bcrypt}"),
                "the acquirer hash is declared once, in carddemo-authorization-identity-secret");
    }

    /**
     * Asserts the assignment count the documentation states is the count {@code .env.example} carries.
     *
     * <p>{@code scripts/generate-env.sh} ends a run by reporting how many assignments the file
     * declares, and two documents quote that number to tell a reader what a complete file looks like.
     * The number is what a reader compares their own output against, so a stale one reads as a failed
     * reconciliation on a file that is in fact complete. Both have quoted a stale count twice: once
     * from before three keys were added and two retired, and once from before the three card-token
     * rotation settings arrived.
     */
    @Test
    @DisplayName("the documented assignment count is the count .env.example declares")
    void theDocumentedAssignmentCountMatchesTheExampleFile() {
        Path platform = platformDirectory();
        int declared = dotenvValues(platform.resolve(".env.example")).size();

        assertEquals(136, declared,
                "the example file's assignment count changed; raise it here and in both documents "
                        + "that quote it, or the reader compares their run against a stale number");
        assertTrue(read(platform.resolve("docs/onboarding.md"))
                        .contains("declares all " + declared + " assignments"),
                "docs/onboarding.md quotes the count a completed run reports");
        assertTrue(read(platform.resolve("docs/decision-log.md"))
                        .contains("a file of " + declared + " assignments"),
                "docs/decision-log.md quotes the same count in the reconciliation decision");
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
     * <p><b>The fallback is the hostname.</b> A container's {@code HOSTNAME} is its Pod name under
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
     * Asserts every relay timing a service binds is refused when it is zero or negative.
     *
     * <p><b>What {@code @NotNull} does not cover.</b> A {@code Duration} component annotated
     * {@code @NotNull} rejects an absent value and nothing else, so {@code PT0S} and a negative
     * duration both bind cleanly. Neither is a configuration a service can work under.
     * {@code claimTimeout} at zero strands every claim the moment it is taken, so one sweep recovers
     * the batch the previous sweep is still publishing — the double publish the claim exists to
     * prevent. {@code publishTimeout} at zero fails every send the instant it is issued, so each row
     * stays unpublished and each sweep claims it again.
     *
     * <p><b>The two defects this stands over.</b> The fraud service bound {@code claimTimeout} with no
     * check at all, while the other four refused it, so one variable set to zero switched the
     * guarantee off in that service alone. The authorization and card services bound
     * {@code publishTimeout} twice: once as this record component and once as a constructor
     * {@code @Value} in the publisher, and the {@code @Value} is the one the publisher read. A second
     * binding meets no constraint the record declares, which is why the publisher is built from the
     * record now and reads no placeholder of its own.
     *
     * <p>The assertion is written over the sources of every service rather than over the two that were
     * wrong, so the guard is what a new relay inherits.
     */
    @Test
    @DisplayName("every relay refuses a zero or negative claim and publish timeout")
    void everyRelayRefusesANonPositiveTiming() {
        List<String> divergences = new ArrayList<>();

        for (String service : OUTBOX_RETENTION_SERVICES) {
            String properties = read(propertiesSource(service));

            for (String timing : List.of("claimTimeout", "publishTimeout")) {
                boolean bound = properties.contains("Duration " + timing);
                boolean refused = properties.contains(timing + " != null")
                        && properties.contains(timing + ".isZero()")
                        && properties.contains(timing + ".isNegative()");

                if (bound && !refused) {
                    divergences.add(service + " binds " + timing + " and refuses neither zero nor a"
                            + " negative duration, so the value starts the service and fails at"
                            + " runtime instead");
                }
            }
            if (!properties.contains("Duration claimTimeout")) {
                divergences.add(service + " runs a relay and binds no claim timeout at all");
            }
        }

        // A publisher that reads the property itself bypasses the record that validates it.
        for (String service : List.of("authorization-service", "account-service", "card-service")) {
            for (Path source : javaSources(platformDirectory().resolve("services").resolve(service)
                    .resolve("src/main/java"))) {
                String text = read(source);
                if (text.contains("@Value(\"${carddemo.outbox.relay.publish-timeout")) {
                    divergences.add(service + "/" + source.getFileName()
                            + " reads carddemo.outbox.relay.publish-timeout through @Value, which is"
                            + " a second binding of a validated property and meets none of its"
                            + " constraints");
                }
            }
        }

        assertEquals(List.of(), divergences,
                "a relay timing that binds without being refused is a guarantee one variable can"
                        + " switch off: " + divergences);
    }

    /**
     * Asserts every relay-owning service ships the same claim horizon.
     *
     * <p>The fraud service shipped {@code PT30S} while the other four shipped {@code PT2M}, and
     * {@code .env.example}, {@code docker-compose.yml} and {@code deploy/k8s/30-configmap.yaml} all
     * carry two minutes. The divergence was invisible in every deployed path, because all three of
     * those set the variable, and it appeared only where nothing set it — a test, or the service
     * started directly. A default that describes only four of five services is worse than no default:
     * a reader who measures recovery on one relay carries the wrong number to the next.
     */
    @Test
    @DisplayName("every relay ships the same claim horizon, and the deployment files carry it")
    void everyRelayShipsTheSameClaimHorizon() {
        Path platform = platformDirectory();
        String expected = EXPECTED_DEFAULTS.get("OUTBOX_RELAY_CLAIM_TIMEOUT");
        List<String> divergences = new ArrayList<>();

        for (String service : OUTBOX_RETENTION_SERVICES) {
            String configuration = read(platform.resolve("services").resolve(service)
                    .resolve("src/main/resources/application.yml"));
            Matcher declared = Pattern
                    .compile("claim-timeout:\\s*\"?\\$\\{OUTBOX_RELAY_CLAIM_TIMEOUT:([^}\"]*)")
                    .matcher(configuration);

            if (!declared.find()) {
                divergences.add(service + " declares no claim-timeout placeholder");
            } else if (!expected.equals(declared.group(1))) {
                divergences.add(service + " ships " + declared.group(1) + " rather than " + expected);
            }
        }

        assertEquals(List.of(), divergences,
                "one claim horizon across five relays: " + divergences);
        assertEquals(expected,
                dotenvValues(platform.resolve(".env.example")).get("OUTBOX_RELAY_CLAIM_TIMEOUT"),
                ".env.example documents the horizon every relay ships");
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
     * <p><b>The assertion reads the deployment files rather than the properties.</b> The
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

    /**
     * Locates the one {@code @ConfigurationProperties} record of a service.
     *
     * @param service the service directory name
     * @return the path of its {@code *Properties.java}
     */
    private static Path propertiesSource(String service) {
        Path config = platformDirectory().resolve("services").resolve(service)
                .resolve("src/main/java/com/carddemo")
                .resolve(SERVICE_PACKAGES.get(service))
                .resolve("config");
        List<Path> candidates = javaSources(config).stream()
                .filter(path -> path.getFileName().toString().endsWith("Properties.java"))
                .toList();
        assertEquals(1, candidates.size(),
                service + " must own exactly one bound properties record, found " + candidates);
        return candidates.get(0);
    }

    /** The package segment each service occupies under {@code com.carddemo}. */
    private static final Map<String, String> SERVICE_PACKAGES = Map.of(
            "authorization-service", "authorization",
            "ledger-posting-service", "ledger",
            "fraud-detection-service", "fraud",
            "notification-service", "notification",
            "account-service", "account",
            "card-service", "card");

    /**
     * Lists every Java source under a directory, in a stable order.
     *
     * @param root the directory to walk
     * @return the sources found, empty when the directory is absent
     */
    private static List<Path> javaSources(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot walk " + root, unreadable);
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