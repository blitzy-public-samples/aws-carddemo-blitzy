package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds every consumer of the platform to the three delivery guarantees a security review found
 * unevenly applied.
 *
 * <p>Each of the three was a property some services had and others did not, which is the shape of
 * finding that a review catches once and a test has to catch from then on. A dead-lettered record
 * whose offset stays uncommitted is read again after a rebalance, so one refused record becomes two
 * dead letters: four services committed the recovered offset and the fifth did not. A record whose
 * key does not name the aggregate its payload names arrived on a partition that does not order that
 * aggregate, so applying it writes to a row whose ordering guarantee was never held: three
 * consumers
 * refused such a record and five did not. And a duplicate-suppression marker swept while the record
 * it suppresses is still readable suppresses nothing, which the shipped configuration allowed by
 * setting the marker horizon equal to broker log retention.
 *
 * <p>Every assertion reads a shipped source or a shipped configuration file. No application
 * context,
 * no database and no broker takes part, so a service that drifts from its peers fails the unit-test
 * phase rather than being noticed in a review.
 */
@DisplayName("Kafka delivery guarantees across the six services")
class KafkaDeliveryGuaranteeContractTest {

    /** The six service modules, in the order the plan lists them. */
    private static final List<String> SERVICES = List.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "notification-service",
            "account-service",
            "card-service");

    /** The Java package each module owns, keyed by module. */
    private static final Map<String, String> PACKAGES = Map.of(
            "authorization-service", "authorization",
            "ledger-posting-service", "ledger",
            "fraud-detection-service", "fraud",
            "notification-service", "notification",
            "account-service", "account",
            "card-service", "card");

    /** The five modules that build a consumer configuration. The card service consumes nothing. */
    private static final List<String> CONSUMING_SERVICES = List.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "notification-service",
            "account-service");

    /**
     * Every listener method of the platform, as module and source file.
     *
     * <p>Listed rather than discovered, because the point of the list is that it is complete: a
     * consumer added without an entry here fails {@link #everyListenerSourceIsListed()}.
     */
    private static final Map<String, List<String>> LISTENERS = Map.of(
            "authorization-service", List.of(
                    "AccountStateChangedConsumer.java",
                    "CardUpdatedConsumer.java"),
            "ledger-posting-service", List.of(
                    "TransactionAuthorizedConsumer.java",
                    "TransactionDeclinedConsumer.java",
                    "AccountStateChangedConsumer.java"),
            "fraud-detection-service", List.of(
                    "TransactionAuthorizedConsumer.java"),
            "notification-service", List.of(
                    "TransactionAuthorizedConsumer.java",
                    "TransactionPostedConsumer.java",
                    "FraudFlaggedConsumer.java",
                    "CustomerContextChangedConsumer.java"),
            "account-service", List.of(
                    "TransactionPostedConsumer.java"));

    /** Marker horizon every shipped file carries, in hours. */
    private static final long MARKER_RETENTION_HOURS = 720L;

    /** Broker log retention every shipped file carries, in hours. */
    private static final long BROKER_RETENTION_HOURS = 168L;

    /** The smallest multiple of broker retention a marker horizon may be. */
    private static final long MINIMUM_RETENTION_MARGIN = 2L;

    /** A dead-lettered record has to have its offset committed, or it is read again. */
    @Nested
    @DisplayName("A recovered record commits its offset")
    class RecoveredOffsets {

        /**
         * Asserts every consumer configuration makes its dead-letter route terminal.
         *
         * <p>The authorization service was the one that did not. Its listeners acknowledge by hand,
         * so nothing acknowledged a record its listener never accepted, and the next start-up or
         * partition assignment read the same record and published a second envelope for it.
         */
        @Test
        @DisplayName("every consumer configuration sets commitRecovered")
        void everyConsumerConfigurationSetsCommitRecovered() {
            for (String module : CONSUMING_SERVICES) {
                assertThat(consumerConfigurationOf(module))
                        .as("%s must commit the offset of a record its route has published, or one"
                                + " refused record produces two dead letters", module)
                        .contains("setCommitRecovered(true)");
            }
        }

        /**
         * Asserts every consumer acknowledges by hand, which is what makes the setting necessary.
         *
         * <p>The framework applies {@code setCommitRecovered} only under
         * {@code MANUAL_IMMEDIATE}; under {@code MANUAL} it reports the setting as ignored and
         * commits nothing. Asserting the mode alongside the setting keeps the pair honest.
         */
        @Test
        @DisplayName("every consuming service names manual_immediate acknowledgement")
        void everyConsumingServiceNamesManualImmediateAcknowledgement() {
            for (String module : CONSUMING_SERVICES) {
                assertThat(applicationConfigurationOf(module))
                        .as("%s applies the recovered-offset commit only under this mode", module)
                        .contains("ack-mode: manual_immediate");
            }
        }
    }

    /** A record's key has to name the aggregate its payload names. */
    @Nested
    @DisplayName("The message key names the aggregate")
    class MessageKeyGuard {

        /**
         * Asserts every listener source reads the key the record arrived under.
         *
         * <p>Five of the ten did not. A record whose key names another aggregate arrived on a
         * partition that does not order that aggregate, and a record with no key at all was
         * partitioned at random, which is the same defect without the evidence.
         *
         * <p>Two ways of reading it are accepted, because the platform already uses both and
         * neither
         * is wrong: a listener binding the payload alone reads the key from
         * {@code KafkaHeaders.RECEIVED_KEY}, and a listener binding the whole {@code
         * ConsumerRecord}
         * reads it from the record. Requiring one spelling would rewrite a compliant listener no
         * finding was raised against.
         */
        @Test
        @DisplayName("every listener reads the key the record arrived under")
        void everyListenerReadsTheKeyTheRecordArrivedUnder() {
            for (Map.Entry<String, List<String>> module : LISTENERS.entrySet()) {
                for (String listener : module.getValue()) {
                    String source = listenerSourceOf(module.getKey(), listener);
                    assertThat(source.contains("KafkaHeaders.RECEIVED_KEY")
                            || source.contains("consumerRecord.key()"))
                            .as("%s/%s must read the key the record arrived under",
                                    module.getKey(), listener)
                            .isTrue();
                }
            }
        }

        /**
         * Asserts every listener source refuses a key that names something else.
         *
         * <p>Reading the key without comparing it reads the value and acts anyway, so the
         * comparison
         * is what the guarantee rests on. Three spellings are accepted for the same reason as
         * above:
         * two families of listener carry a named helper, and one compares inline. What every one of
         * them has to do is compare the key against the aggregate.
         */
        @Test
        @DisplayName("every listener refuses a key naming another aggregate")
        void everyListenerRefusesAKeyNamingAnotherAggregate() {
            for (Map.Entry<String, List<String>> module : LISTENERS.entrySet()) {
                for (String listener : module.getValue()) {
                    String source = listenerSourceOf(module.getKey(), listener);
                    assertThat(source.contains("requireKeyNamesAggregate")
                            || source.contains("requireKeyNamesPayloadAggregate")
                            || source.contains("messageKey.equals(event.aggregateId())"))
                            .as("%s/%s reads the key and must compare it, or the partition it"
                                    + " arrived on is not the one that orders its aggregate",
                                    module.getKey(), listener)
                            .isTrue();
                }
            }
        }

        /**
         * Asserts a listener reading a checked tree compares all three identity values.
         *
         * <p>A listener binding a record of {@code libs/event-contracts} inherits the agreement:
         * every one of those records refuses a payload whose {@code accountId} differs from its
         * {@code aggregateId}, so comparing the key against either compares it against both. A
         * listener reading a {@code JsonNode} has no such record, because the payload belongs to
         * another service and no service module may depend on another. Those four therefore compare
         * the key, the envelope's aggregate and the payload's account: a key agreeing with the
         * envelope while the payload names something else would route correctly and write to the
         * wrong row.
         */
        @Test
        @DisplayName("a listener reading a checked tree compares the key, the aggregate and the"
                + " account")
        void aListenerReadingACheckedTreeComparesAllThree() {
            for (Map.Entry<String, List<String>> module : LISTENERS.entrySet()) {
                for (String listener : module.getValue()) {
                    String source = listenerSourceOf(module.getKey(), listener);
                    boolean readsATree = source.contains("JsonNode message")
                            || source.contains("JsonNode event");
                    if (!readsATree) {
                        continue;
                    }
                    assertThat(source)
                            .as("%s/%s reads a tree, so nothing else checks that the envelope and"
                                    + " the payload name one account", module.getKey(), listener)
                            .contains("!messageKey.equals(aggregateId) || !messageKey"
                                    + ".equals(accountId)");
                }
            }
        }

        /**
         * Asserts no refusal message names an identifier.
         *
         * <p>A refusal reaches a log line through the container's error handler, so a message
         * carrying the key or the account would put an identifier into whatever retains logs. The
         * refusals are fixed text, and this reads them back to prove it.
         */
        @Test
        @DisplayName("no refusal message interpolates a value")
        void noRefusalMessageInterpolatesAValue() {
            Pattern refusal = Pattern.compile(
                    "throw new IllegalArgumentException\\(([^;]*?)\\);", Pattern.DOTALL);
            List<String> offenders = new ArrayList<>();
            for (Map.Entry<String, List<String>> module : LISTENERS.entrySet()) {
                for (String listener : module.getValue()) {
                    String source = listenerSourceOf(module.getKey(), listener);
                    Matcher raised = refusal.matcher(source);
                    while (raised.find()) {
                        String argument = raised.group(1);
                        if (!argument.contains("partition") && !argument.contains("aggregate")
                                && !argument.contains("message key")) {
                            continue;
                        }
                        if (argument.contains("messageKey") || argument.contains("accountId")
                                || argument.contains("aggregateId +")) {
                            offenders.add(module.getKey() + "/" + listener);
                        }
                    }
                }
            }
            assertThat(offenders)
                    .as("a refusal that names the value it refused puts that value in a log line")
                    .isEmpty();
        }

        /** Asserts the listener inventory above is the whole inventory. */
        @Test
        @DisplayName("every listener source of the platform is listed here")
        void everyListenerSourceIsListed() {
            for (String module : SERVICES) {
                List<String> found = new ArrayList<>();
                Path messaging = platformDirectory().resolve("services").resolve(module)
                        .resolve("src/main/java/com/carddemo").resolve(PACKAGES.get(module))
                        .resolve("messaging");
                if (!Files.isDirectory(messaging)) {
                    continue;
                }
                try (var listed = Files.list(messaging)) {
                    listed.filter(Files::isRegularFile)
                            .filter(file -> read(file).contains("@KafkaListener"))
                            .forEach(file -> found.add(file.getFileName().toString()));
                } catch (IOException unreadable) {
                    throw new UncheckedIOException("cannot list " + messaging, unreadable);
                }
                assertThat(found)
                        .as("%s carries a listener this contract does not hold to the key guard",
                                module)
                        .containsExactlyInAnyOrderElementsOf(
                                LISTENERS.getOrDefault(module, List.of()));
            }
        }
    }

    /** A marker has to outlast every window its record can return through. */
    @Nested
    @DisplayName("A duplicate-suppression marker outlasts broker retention")
    class MarkerHorizon {

        /**
         * Asserts the shipped pair clears the margin the properties records enforce.
         *
         * <p>The two were equal, which made the guarantee an equality rather than a margin: segment
         * cleanup is not instant, a restored backup can carry an older record, and an operator
         * resetting a group replays whatever the log holds. Any one left a record readable after
         * its
         * marker was swept, and for {@code account-posted} that applies one transaction amount to a
         * balance and a cycle accumulator twice.
         */
        @Test
        @DisplayName("the shipped marker horizon is at least twice the shipped broker retention")
        void theShippedMarkerHorizonClearsTheMargin() {
            assertThat(MARKER_RETENTION_HOURS)
                    .as("the shipped pair has to satisfy the rule the services enforce")
                    .isGreaterThanOrEqualTo(BROKER_RETENTION_HOURS * MINIMUM_RETENTION_MARGIN);
        }

        /** Asserts every service reads both halves of the relationship it enforces. */
        @Test
        @DisplayName("every service reads both retention values with the shipped defaults")
        void everyServiceReadsBothRetentionValues() {
            for (String module : SERVICES) {
                String application = applicationConfigurationOf(module);
                assertThat(application)
                        .as("%s must read the marker horizon", module)
                        .contains("${PROCESSED_EVENT_RETENTION_HOURS:" + MARKER_RETENTION_HOURS
                                + "}");
                assertThat(application)
                        .as("%s must read the broker retention it has to outlast", module)
                        .contains("${KAFKA_LOG_RETENTION_HOURS:" + BROKER_RETENTION_HOURS + "}");
            }
        }

        /** Asserts every properties record enforces the relationship rather than documenting it. */
        @Test
        @DisplayName("every properties record refuses a horizon under the margin at start-up")
        void everyPropertiesRecordRefusesAHorizonUnderTheMargin() {
            for (String module : SERVICES) {
                String properties = propertiesSourceOf(module);
                assertThat(properties)
                        .as("%s must declare the margin as a constant rather than a literal",
                                module)
                        .contains("MINIMUM_RETENTION_MARGIN = " + MINIMUM_RETENTION_MARGIN + "L");
                assertThat(properties)
                        .as("%s must refuse a marker horizon under the margin", module)
                        .contains("throw new IllegalArgumentException(");
                assertThat(properties)
                        .as("%s must compare the marker horizon against broker retention", module)
                        .contains("brokerRetentionHours * MINIMUM_RETENTION_MARGIN");
            }
        }

        /** Asserts the three shipped files carry one pair of values rather than three. */
        @Test
        @DisplayName("the dotenv example, the composition and the ConfigMap carry the same pair")
        void theShippedFilesCarryTheSamePair() {
            String example = read(platformDirectory().resolve(".env.example"));
            String compose = read(platformDirectory().resolve("docker-compose.yml"));
            String configMap = read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml"));

            assertThat(example.lines().toList())
                    .contains("PROCESSED_EVENT_RETENTION_HOURS=" + MARKER_RETENTION_HOURS,
                            "KAFKA_LOG_RETENTION_HOURS=" + BROKER_RETENTION_HOURS);
            assertThat(compose)
                    .contains("PROCESSED_EVENT_RETENTION_HOURS:"
                            + " ${PROCESSED_EVENT_RETENTION_HOURS:-" + MARKER_RETENTION_HOURS + "}")
                    .contains("KAFKA_LOG_RETENTION_HOURS: ${KAFKA_LOG_RETENTION_HOURS:-"
                            + BROKER_RETENTION_HOURS + "}");
            assertThat(configMap)
                    .contains("PROCESSED_EVENT_RETENTION_HOURS: \"" + MARKER_RETENTION_HOURS + "\"")
                    .contains("KAFKA_LOG_RETENTION_HOURS: \"" + BROKER_RETENTION_HOURS + "\"");
        }

        /**
         * Asserts the composition hands the broker figure to every service, not to the broker
         * alone.
         *
         * <p>One value describes the broker, so two copies of it can disagree and the disagreement
         * is exactly what the start-up check exists to catch. It therefore lives in the shared
         * service block, where all six containers inherit it.
         */
        @Test
        @DisplayName("the broker retention reaches every service through the shared block")
        void theBrokerRetentionReachesEveryServiceThroughTheSharedBlock() {
            String compose = read(platformDirectory().resolve("docker-compose.yml"));
            String shared = compose.substring(compose.indexOf("x-service-environment:"),
                    compose.indexOf("services:"));

            assertThat(shared)
                    .as("one value reaches all six services, so no two can disagree")
                    .contains("KAFKA_LOG_RETENTION_HOURS: ${KAFKA_LOG_RETENTION_HOURS:-"
                            + BROKER_RETENTION_HOURS + "}");
        }
    }

    /**
     * Reads the consumer configuration of one module.
     *
     * @param module the service module directory name
     * @return the source text
     */
    private static String consumerConfigurationOf(String module) {
        return read(platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/java/com/carddemo").resolve(PACKAGES.get(module))
                .resolve("config/KafkaConsumerConfig.java"));
    }

    /**
     * Reads the properties record of one module.
     *
     * @param module the service module directory name
     * @return the source text
     */
    private static String propertiesSourceOf(String module) {
        String owner = PACKAGES.get(module);
        String type = Character.toUpperCase(owner.charAt(0)) + owner.substring(1) + "Properties";
        return read(platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/java/com/carddemo").resolve(owner)
                .resolve("config").resolve(type + ".java"));
    }

    /**
     * Reads one listener source of one module.
     *
     * @param module   the service module directory name
     * @param listener the file name of the listener
     * @return the source text
     */
    private static String listenerSourceOf(String module, String listener) {
        return read(platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/java/com/carddemo").resolve(PACKAGES.get(module))
                .resolve("messaging").resolve(listener));
    }

    /**
     * Reads the shipped application configuration of one module.
     *
     * @param module the service module directory name
     * @return the file text
     */
    private static String applicationConfigurationOf(String module) {
        return read(platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/resources/application.yml"));
    }

    /** @return the {@code card-platform} directory of this checkout */
    private static Path platformDirectory() {
        return CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent()
                .resolve("card-platform");
    }

    /**
     * Reads one file as text.
     *
     * @param file the file to read
     * @return its content
     */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
    }
}
