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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds every consumer and every relay of the platform to the delivery guarantees a review found
 * unevenly applied: three from a security review, and two more from a performance review.
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
 * <p>The two the performance review added are the other half of the same subject: what the platform
 * gives up to hold per-account order. A relay that publishes one row at a time holds every account
 * behind whichever account it is publishing, and a listener container running one thread reads every
 * partition of its topic in sequence. Neither is required by the ordering guarantee, because the
 * account identifier is the message key and Kafka assigns one partition to one consumer, so both
 * were serialization the guarantee did not ask for.
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

    /**
     * The five modules that own an {@code outbox_event} table and relay it.
     *
     * <p>The notification service publishes nothing, so it holds no outbox and no relay.
     */
    private static final List<String> OUTBOX_SERVICES = List.of(
            "authorization-service",
            "ledger-posting-service",
            "fraud-detection-service",
            "account-service",
            "card-service");

    /** The partial index every outbox-owning module ships for the account-head claim. */
    private static final String AGGREGATE_HEAD_INDEX = "ix_outbox_event_aggregate_head";

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
     * Per-account ordering survives a relay that publishes several accounts at once.
     *
     * <p>Each relay used to hold one transaction around a whole sweep and to return at the first
     * refused row. The transaction held a database connection and every claimed row lock for the sum
     * of that sweep's broker waits, and the return meant one unreachable partition stopped
     * publication for every account, not just the account it belonged to. Both are gone, and both
     * were removed on the strength of one property: the claim answers with the due head row of each
     * aggregate, so a sweep's rows name distinct accounts and a later event of one account is not
     * claimable while that account's earlier row is unpublished.
     *
     * <p>That property is what the ordering guarantee now rests on, so it is asserted for every
     * relay rather than for the one whose test happened to cover it. The account identifier is the
     * message key, so an account whose second event overtook its first would be applied out of order
     * by every consumer of the partition it keys.
     */
    @Nested
    @DisplayName("Per-account ordering through the outbox")
    class PerAccountOrdering {

        /** Sends one relay keeps in flight, which is also its producer's in-flight window. */
        private static final int SENDS_IN_FLIGHT = 5;

        @Test
        @DisplayName("every relay claims the head row of each account and never two of one account")
        void everyRelayClaimsTheHeadRowOfEachAccount() {
            for (String module : OUTBOX_SERVICES) {
                assertThat(outboxRepositorySourceOf(module))
                        .as("the claim query of %s", module)
                        .contains("AND NOT EXISTS (")
                        .contains("WHERE preceding.aggregateId = row.aggregateId")
                        .contains("preceding.createdAt < row.createdAt")
                        .contains("AND preceding.eventId < row.eventId");
            }
        }

        @Test
        @DisplayName("every relay ships the partial index that claim reads")
        void everyRelayShipsThePartialIndexThatClaimReads() {
            for (String module : OUTBOX_SERVICES) {
                String migration = migrationDeclaring(module, AGGREGATE_HEAD_INDEX);

                assertThat(migration)
                        .as("the aggregate-head index of %s", module)
                        .contains("ON outbox_event (aggregate_id, created_at, event_id)")
                        .contains("WHERE relay_state IN ('PENDING', 'CLAIMED')");
            }
        }

        @Test
        @DisplayName("no relay waits for a broker inside a transaction")
        void noRelayWaitsForABrokerInsideATransaction() {
            for (String module : OUTBOX_SERVICES) {
                String relay = relaySourceOf(module);

                assertThat(relay)
                        .as("the sweep of %s claims in one transaction and settles in another",
                                module)
                        .contains("claimBatch(")
                        .contains("dispatch(")
                        .contains("settle(");
                assertThat(relay)
                        .as("the sweep of %s wraps no whole pass in one transaction", module)
                        .doesNotContain("transactionTemplate.execute(status -> sweepOnce")
                        .doesNotContain("transactionTemplate.execute(status -> runOnePass");
            }
        }

        @Test
        @DisplayName("every relay bounds its in-flight sends to the producer's own window")
        void everyRelayBoundsItsInFlightSendsToTheProducerWindow() {
            for (String module : OUTBOX_SERVICES) {
                assertThat(relaySourceOf(module))
                        .as("the in-flight bound of %s", module)
                        .contains("MAX_SENDS_IN_FLIGHT = " + SENDS_IN_FLIGHT)
                        .contains("Math.min(this.batchSize, MAX_SENDS_IN_FLIGHT)");
                assertThat(applicationConfigurationOf(module))
                        .as("the producer window of %s, which the bound above matches", module)
                        .contains("max.in.flight.requests.per.connection: " + SENDS_IN_FLIGHT);
            }
        }
    }

    /**
     * A consumer reads a partition at a time, and unrelated partitions are read beside each other.
     *
     * <p>Ordering and throughput pull in opposite directions here, and the partition is what settles
     * them. Kafka assigns one partition to exactly one consumer of a group, so a listener container
     * running one thread per partition never splits a partition and never reorders one account: the
     * account identifier is the message key on every event this platform publishes. What it does
     * remove is the serialization of accounts that have nothing to do with each other. Every one of
     * these five services read its topics on a single thread before this contract existed — four by
     * leaving the framework default in place and the fraud service by declaring it — so three
     * partitions of authorized transactions were scored, posted and rendered one record at a time.
     */
    @Nested
    @DisplayName("Consumer throughput is bounded by partitions, not by one thread")
    class ConsumerThroughput {

        /** The declaration each shipped file carries, unquoted and quoted. */
        private static final String CONCURRENCY_KEY = "concurrency: ";

        /** The override name the shipped placeholder reads, which Spring also binds by itself. */
        private static final String CONCURRENCY_OVERRIDE = "SPRING_KAFKA_LISTENER_CONCURRENCY";

        /** The variable both deployment artifacts carry the partition count under. */
        private static final String PARTITION_COUNT_VARIABLE = "KAFKA_TOPIC_PARTITIONS";

        /**
         * Asserts the shipped concurrency of all six services equals the documented partition count.
         *
         * <p>Equality is the assertion in both directions. Below the count, partitions share a
         * thread and the service reads its topics more slowly than the broker offers them. Above it,
         * the group assignor has nothing to give the surplus threads, so the extra consumers sit in
         * the group, take part in every rebalance and read nothing. The card service is included
         * even though it registers no listener, because its shipped file already declares the
         * acknowledgement mode for a listener added later and the same reasoning applies to this.
         */
        @Test
        @DisplayName("every service reads one thread per partition, from the documented count")
        void everyServiceReadsOneThreadPerPartition() {
            String declaration = CONCURRENCY_KEY + "${" + CONCURRENCY_OVERRIDE + ":"
                    + documentedPartitionCount() + "}";

            for (String module : SERVICES) {
                // Two of the six quote every scalar and four quote none, and a quoted placeholder
                // binds to the same value, so the quotes are taken out before matching.
                assertThat(applicationConfigurationOf(module).replace("\"", ""))
                        .as("the shipped listener concurrency of %s", module)
                        .contains(declaration);
            }
        }

        /**
         * Asserts the partition count is the same number in the dotenv example and in the ConfigMap.
         *
         * <p>The concurrency above is read against this number, so the number has to mean one thing.
         * The composition passes it to the topic-creating container from the dotenv file, and the
         * cluster path passes it from the ConfigMap, and a service reads its concurrency from
         * neither: it ships the value. Two artifacts disagreeing would leave one path with idle
         * consumers and the other with shared partitions, and nothing would report it.
         */
        @Test
        @DisplayName("the dotenv example and the ConfigMap declare the same partition count")
        void theDotenvExampleAndTheConfigMapDeclareTheSamePartitionCount() {
            String configMap = read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml"));

            assertThat(configMap)
                    .as("the ConfigMap partition count")
                    .contains(PARTITION_COUNT_VARIABLE + ": \"" + documentedPartitionCount() + "\"");
        }

        /**
         * Reads the partition count out of {@code .env.example}.
         *
         * @return the documented partition count of every topic
         */
        private int documentedPartitionCount() {
            Matcher declared = Pattern
                    .compile("(?m)^" + PARTITION_COUNT_VARIABLE + "=(\\d+)$")
                    .matcher(read(platformDirectory().resolve(".env.example")));

            assertThat(declared.find())
                    .as("%s must be documented in .env.example", PARTITION_COUNT_VARIABLE)
                    .isTrue();
            return Integer.parseInt(declared.group(1));
        }
    }

    /**
     * Reads the outbox repository of one module.
     *
     * @param module the service module directory name
     * @return the source text
     */
    private static String outboxRepositorySourceOf(String module) {
        return read(platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/java/com/carddemo").resolve(PACKAGES.get(module))
                .resolve("repository/OutboxEventRepository.java"));
    }

    /**
     * Reads the outbox relay of one module.
     *
     * @param module the service module directory name
     * @return the source text
     */
    private static String relaySourceOf(String module) {
        return read(platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/java/com/carddemo").resolve(PACKAGES.get(module))
                .resolve("outbox/OutboxRelay.java"));
    }

    /**
     * Reads the one shipped migration of a module that creates the named index.
     *
     * @param module the service module directory name
     * @param index  the index name the migration creates
     * @return the text of that migration
     */
    private static String migrationDeclaring(String module, String index) {
        Path migrations = platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/resources/db/migration");
        try (Stream<Path> files = Files.list(migrations)) {
            List<Path> declaring = files
                    .filter(file -> file.getFileName().toString().endsWith(".sql"))
                    .filter(file -> read(file).contains("CREATE INDEX " + index))
                    .toList();

            assertThat(declaring)
                    .as("migrations of %s creating %s", module, index)
                    .hasSize(1);
            return read(declaring.getFirst());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + migrations, unreadable);
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
     * Holds the two dead-letter wire forms of this platform to the topics that carry them.
     *
     * <p>Two forms exist, and a document that claimed one was the shape of every dead-letter topic
     * sent a reader building tooling for the wrong parser. A consumer that exhausts its delivery
     * attempts on a named topic writes the fixed-width rendering of {@code app/cpy/CSMSG02Y.cpy} to
     * {@code <source>.DLT}; a relay that exhausts its publish attempts, and a consumer whose refused
     * record names no source topic, writes the governed {@code schemas/dead-letter-v1.json} envelope
     * to the shared fallback topic. Which service writes which is a delivery property rather than a
     * preference, so it is measured here. The definition a reader needs is in
     * {@code card-platform/docs/event-flow.md}.
     */
    @Nested
    @DisplayName("dead-letter wire forms")
    class DeadLetterWireForms {

        /** The modules whose consumer route writes the fixed-width diagnostic. */
        private static final List<String> FIXED_WIDTH_CONSUMERS = List.of(
                "ledger-posting-service", "fraud-detection-service", "notification-service");

        /** The modules whose consumer route writes the governed envelope. */
        private static final List<String> GOVERNED_ENVELOPE_CONSUMERS =
                List.of("authorization-service", "account-service");

        @Test
        @DisplayName("each consumer route writes exactly one of the two forms, and the same one every time")
        void eachConsumerRouteWritesExactlyOneOfTheTwoForms() {
            for (String module : CONSUMING_SERVICES) {
                String configuration = read(platformDirectory().resolve("services").resolve(module)
                        .resolve("src/main/java/com/carddemo").resolve(PACKAGES.get(module))
                        .resolve("config/KafkaConsumerConfig.java"));

                boolean fixedWidth = configuration.contains(".toFixedWidthRecord()");
                boolean envelope = configuration.contains(".toEnvelope(");

                assertThat(fixedWidth)
                        .as(module + " writes the fixed-width diagnostic on its consumer route,"
                                + " which docs/event-flow.md fixes for "
                                + FIXED_WIDTH_CONSUMERS)
                        .isEqualTo(FIXED_WIDTH_CONSUMERS.contains(module));
                assertThat(envelope)
                        .as(module + " writes the governed dead-letter envelope on its consumer"
                                + " route, which docs/event-flow.md fixes for "
                                + GOVERNED_ENVELOPE_CONSUMERS)
                        .isEqualTo(GOVERNED_ENVELOPE_CONSUMERS.contains(module));
                assertThat(fixedWidth ^ envelope)
                        .as(module + " must write one dead-letter form and not both, so tooling"
                                + " reading its topic needs one parser")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a fixed-width route sends to the source topic plus a suffix and never to the shared topic")
        void aFixedWidthRouteSendsToTheSourceTopicPlusASuffix() {
            for (String module : FIXED_WIDTH_CONSUMERS) {
                String configuration = read(platformDirectory().resolve("services").resolve(module)
                        .resolve("src/main/java/com/carddemo").resolve(PACKAGES.get(module))
                        .resolve("config/KafkaConsumerConfig.java"));

                assertThat(configuration)
                        .as(module + " must resolve its dead-letter destination by appending the"
                                + " suffix to the source topic, so the diagnostic of one topic"
                                + " stays on that topic's own dead-letter topic")
                        .contains("sourceTopic + suffix");
            }
        }

        @Test
        @DisplayName("the governed envelope document declares the shared topic and points at the other form")
        void theGovernedEnvelopeDocumentDeclaresTheSharedTopicAndPointsAtTheOtherForm() {
            String document = read(platformDirectory().resolve(
                    "libs/event-contracts/src/main/resources/schemas/dead-letter-v1.json"));

            assertThat(document)
                    .as("dead-letter-v1.json must name the topic it governs rather than claiming"
                            + " every dead-letter topic carries it")
                    .contains("shared fallback dead-letter topic, carddemo.dead-letter");
            assertThat(document)
                    .as("dead-letter-v1.json must name the other form and where it is defined, so a"
                            + " reader generating tooling from it knows the fixed-width topics exist")
                    .contains("fixed-width diagnostic to <source>.DLT")
                    .contains("card-platform/docs/event-flow.md");
        }

        @Test
        @DisplayName("the fixed-width form is 134 characters, from the four abend fields")
        void theFixedWidthFormIsOneHundredAndThirtyFourCharacters() {
            for (String module : FIXED_WIDTH_CONSUMERS) {
                String metadata = read(platformDirectory().resolve("services").resolve(module)
                        .resolve("src/main/java/com/carddemo").resolve(PACKAGES.get(module))
                        .resolve("messaging/DeadLetterMetadata.java"));

                assertThat(metadata)
                        .as(module + " must render the four fields of app/cpy/CSMSG02Y.cpy at their"
                                + " declared widths, which is the layout docs/event-flow.md states")
                        .contains("ABEND_CODE_MAX_LENGTH = 4")
                        .contains("CULPRIT_MAX_LENGTH = 8")
                        .contains("REASON_MAX_LENGTH = 50")
                        .contains("MESSAGE_MAX_LENGTH = 72");
            }
        }

        @Test
        @DisplayName("docs/event-flow.md defines both dead-letter forms")
        void theEventFlowGuideDefinesBothDeadLetterForms() {
            String guide = read(platformDirectory().resolve("docs/event-flow.md"));

            assertThat(guide)
                    .as("the guide is where dead-letter-v1.json sends a reader for the other form,"
                            + " so it has to define the fixed-width layout and its widths")
                    .contains("134")
                    .contains("<source>.DLT");
            for (String module : FIXED_WIDTH_CONSUMERS) {
                assertThat(guide)
                        .as("the guide must name " + module + " among the services writing the"
                                + " fixed-width form")
                        .contains(module);
            }
        }
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
