package com.carddemo.equivalence;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.events.serde.EventWireBounds;
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
 * Holds every consumer and every relay of the platform to five delivery guarantees, three about
 * correctness and two about the throughput a service gives up to reach it.
 *
 * <p>Each correctness property is one a service can hold while a peer does not, which is the shape
 * of defect a reader catches once and a test has to catch from then on. A dead-lettered record
 * whose offset stays uncommitted is read again after a rebalance, so one refused record becomes two
 * dead letters. A record whose key does not name the aggregate its payload names arrives on a
 * partition that does not order that aggregate, so applying it writes to a row whose ordering
 * guarantee was never held. And a duplicate-suppression marker swept while the record it suppresses
 * is still readable suppresses nothing, which is what a marker horizon equal to broker log
 * retention produces.
 *
 * <p>The two throughput properties are the other half of the same subject: what the platform gives
 * up to hold per-account order. A relay that publishes one row at a time holds every account behind
 * whichever account it is publishing, and a listener container running one thread reads every
 * partition of its topic in sequence. Neither is required by the ordering guarantee, because the
 * account identifier is the message key and Kafka assigns one partition to one consumer, so both
 * are serialization the guarantee did not ask for.
 *
 * <p>Every assertion reads a shipped source or a shipped configuration file. No application
 * context, no database and no broker takes part, so a service that drifts from its peers fails the
 * unit-test phase rather than waiting to be noticed by a reader.
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
     * consumer added without an entry here fails
     * {@link MessageKeyGuard#everyListenerSourceIsListed()}.
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

    /** Broker log retention the shipped files carry, in hours. */
    private static final long BROKER_RETENTION_HOURS = 168L;

    /** The setting that carried the withdrawn marker horizon, asserted absent below. */
    private static final String WITHDRAWN_MARKER_SETTING = "PROCESSED_EVENT_RETENTION_HOURS";

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

    /**
     * A duplicate-suppression claim outlasts every window its record can return through, by never
     * being removed.
     *
     * <p>A horizon of 720 hours checked at start-up against twice the 168-hour broker log retention
     * bounds only how long the broker can redeliver a record. Every effect a claim guards outlives
     * that: a posted transaction, a category balance, a cycle accumulator, a read-model row. An
     * archived, restored or deliberately replayed record arriving after such a horizon is new to
     * the guard.
     *
     * <p>The assertions below therefore hold the absence rather than the margin. No service reads a
     * marker horizon, no properties record binds one, and no deployment artifact carries the setting
     * that named it.
     */
    @Nested
    @DisplayName("A duplicate-suppression claim is permanent")
    class MarkerHorizon {

        /** Asserts no service reads a marker horizon, because none exists to read. */
        @Test
        @DisplayName("no service reads a marker horizon from its configuration")
        void noServiceReadsAMarkerHorizon() {
            for (String module : SERVICES) {
                assertThat(applicationConfigurationOf(module))
                        .as("%s must bind no marker horizon", module)
                        .doesNotContain("${" + WITHDRAWN_MARKER_SETTING + ":")
                        .doesNotContain("marker-retention-hours");
            }
        }

        /** Asserts no properties record binds the withdrawn pair or the margin between them. */
        @Test
        @DisplayName("no properties record binds a marker horizon or the margin it was checked by")
        void noPropertiesRecordBindsAMarkerHorizon() {
            for (String module : SERVICES) {
                String properties = propertiesSourceOf(module);
                assertThat(properties)
                        .as("%s must declare no marker horizon component", module)
                        .doesNotContain("markerRetentionHours")
                        .doesNotContain("brokerRetentionHours")
                        .doesNotContain("MINIMUM_RETENTION_MARGIN");
            }
        }

        /**
         * Asserts every service's own migration states the claim is permanent, in the catalogue.
         *
         * <p>An operator reads the catalogue rather than the Java source, so the property has to be
         * stated where they read. The comment says {@code purge_key=none}, which is what
         * {@link RetentionSweepContractTest} reads to tell a table nothing expires from one something
         * does.
         */
        @Test
        @DisplayName("every schema declares the claim permanent in its catalogue comment")
        void everySchemaDeclaresTheClaimPermanent() {
            for (String module : SERVICES) {
                assertThat(migrationTextOf(module))
                        .as("%s must declare processed_event permanent in its catalogue", module)
                        .contains("retention=permanent; purge_key=none");
            }
        }

        /** Asserts no deployment artifact carries the withdrawn setting. */
        @Test
        @DisplayName("no shipped file carries the withdrawn marker setting")
        void noShippedFileCarriesTheWithdrawnMarkerSetting() {
            String example = read(platformDirectory().resolve(".env.example"));
            String compose = read(platformDirectory().resolve("docker-compose.yml"));
            String configMap = read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml"));

            assertThat(example.lines().toList())
                    .as("the example may not offer a horizon to set")
                    .noneMatch(line -> line.startsWith(WITHDRAWN_MARKER_SETTING + "="));
            assertThat(compose)
                    .as("no container may receive a horizon")
                    .doesNotContain(WITHDRAWN_MARKER_SETTING + ":");
            assertThat(configMap)
                    .as("the ConfigMap may not declare a horizon")
                    .doesNotContain(WITHDRAWN_MARKER_SETTING + ":");
            assertThat(example.lines().toList())
                    .as("broker retention stays, because the broker still applies it")
                    .contains("KAFKA_LOG_RETENTION_HOURS=" + BROKER_RETENTION_HOURS);
            assertThat(configMap)
                    .contains("KAFKA_LOG_RETENTION_HOURS: \"" + BROKER_RETENTION_HOURS + "\"");
        }

        /**
         * Asserts the broker figure reaches the broker and no service.
         *
         * <p>It reached all six services while each of them compared its marker horizon against it.
         * No service reads it now, and a value handed to a container that binds nothing is a value a
         * reader can set with no effect.
         */
        @Test
        @DisplayName("the broker retention reaches the broker alone")
        void theBrokerRetentionReachesTheBrokerAlone() {
            String compose = read(platformDirectory().resolve("docker-compose.yml"));
            String shared = compose.substring(compose.indexOf("x-service-environment:"),
                    compose.indexOf("services:"));

            assertThat(shared)
                    .as("no service binds broker retention, so the shared block may not carry it")
                    .doesNotContain("KAFKA_LOG_RETENTION_HOURS");
            assertThat(compose)
                    .as("the broker itself still applies the figure")
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
     * <p>Ordering and throughput pull in opposite directions here, and the partition is what
     * settles them. Kafka assigns one partition to exactly one consumer of a group, so a listener
     * container running one thread per partition never splits a partition and never reorders one
     * account: the account identifier is the message key on every event this platform publishes.
     * What it does remove is the serialization of accounts that have nothing to do with each other.
     * Leaving the framework default in place, or declaring one thread as a properties record can,
     * has three partitions of authorized transactions scored, posted and rendered one record at a
     * time.
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
     * Every wire ceiling of the transport sits above the envelope the application governs.
     *
     * <p>The application refuses an event above {@link EventWireBounds#MAX_EVENT_BYTES} before its
     * outbox row is written. A transport ceiling left at a client default sits orders of magnitude
     * above that, so a record this platform would never publish is one the transport would happily
     * carry. Hostname verification left to a client default is the same exposure, because a future
     * release could change it without any deployment noticing.
     *
     * <p>The five figures form a ladder, each above the one below it. The envelope leaves room for
     * record overhead in the producer request, the producer request leaves room for compression and
     * batching at the broker, the broker record fits inside a partition fetch, and a partition fetch
     * fits inside a whole fetch. The assertions below read all five out of the shipped files and
     * hold the ladder monotonic, so moving one figure without the others fails this phase.
     */
    @Nested
    @DisplayName("Every wire ceiling is a rung above the governed envelope")
    class WireCeilings {

        /** The producer ceiling every publishing service ships, in bytes. */
        private static final int PRODUCER_REQUEST_BYTES = 16384;

        /** The broker ceiling both deployment descriptions ship, in bytes. */
        private static final int BROKER_MESSAGE_BYTES = 32768;

        /** The per-partition fetch ceiling every service ships, in bytes. */
        private static final int PARTITION_FETCH_BYTES = 65536;

        /** The whole-fetch ceiling every service ships, in bytes. */
        private static final int FETCH_BYTES = 262144;

        /** The property that keeps hostname verification on when the transport is SASL_SSL. */
        private static final String HOSTNAME_VERIFICATION =
                "ssl.endpoint.identification.algorithm: https";

        /** The broker setting both deployment descriptions carry as a literal. */
        private static final String BROKER_SETTING = "KAFKA_MESSAGE_MAX_BYTES";

        /**
         * Asserts the five figures are strictly increasing, starting at the governed envelope.
         *
         * <p>Read as one assertion rather than five constants compared by eye. A rung equal to the
         * one below it leaves no room for the overhead that layer adds, and a rung below it refuses
         * a message the layer beneath already accepted, which is a failure arriving one hop later
         * than the check that could have explained it.
         */
        @Test
        @DisplayName("the five figures increase from the envelope outward")
        void theFiveFiguresIncreaseFromTheEnvelopeOutward() {
            assertThat(List.of(EventWireBounds.MAX_EVENT_BYTES, PRODUCER_REQUEST_BYTES,
                            BROKER_MESSAGE_BYTES, PARTITION_FETCH_BYTES, FETCH_BYTES))
                    .as("the ladder from the governed envelope to the whole fetch")
                    .isSorted()
                    .doesNotHaveDuplicates();
        }

        /**
         * Asserts each publishing service declares the producer ceiling, and the sixth declares none.
         *
         * <p>The notification service publishes nothing, so it builds no producer configuration and
         * a ceiling in its file would be a setting nothing reads.
         */
        @Test
        @DisplayName("every publishing service bounds its producer request")
        void everyPublishingServiceBoundsItsProducerRequest() {
            for (String module : SERVICES) {
                String declaration = "max.request.size: " + PRODUCER_REQUEST_BYTES;

                if (OUTBOX_SERVICES.contains(module)) {
                    assertThat(applicationConfigurationOf(module))
                            .as("%s publishes, so it bounds the request it sends", module)
                            .contains(declaration);
                } else {
                    assertThat(applicationConfigurationOf(module))
                            .as("%s publishes nothing, so a producer ceiling would bound nothing",
                                    module)
                            .doesNotContain("max.request.size");
                }
            }
        }

        /**
         * Asserts every service bounds both fetch sizes, including the one that consumes nothing.
         *
         * <p>The card service registers no listener and still ships the pair, because its file
         * already carries the acknowledgement mode a listener added later would need and a ceiling
         * absent from one file of six is the drift this class exists to refuse.
         */
        @Test
        @DisplayName("every service bounds a partition fetch and a whole fetch")
        void everyServiceBoundsAPartitionFetchAndAWholeFetch() {
            for (String module : SERVICES) {
                assertThat(applicationConfigurationOf(module))
                        .as("the shipped consumer ceilings of %s", module)
                        .contains("max.partition.fetch.bytes: " + PARTITION_FETCH_BYTES)
                        .contains("fetch.max.bytes: " + FETCH_BYTES);
            }
        }

        /**
         * Asserts hostname verification is stated in all six files rather than inherited.
         *
         * <p>The value equals the client default today. Stating it is what stops a future client
         * release changing that default from silently dropping the check from a deployment using
         * SASL_SSL, and it puts the property where this test can read it.
         */
        @Test
        @DisplayName("every service states hostname verification rather than inheriting it")
        void everyServiceStatesHostnameVerification() {
            for (String module : SERVICES) {
                assertThat(applicationConfigurationOf(module))
                        .as("%s must state hostname verification, because a default that changes "
                                + "beneath a deployment changes it silently", module)
                        .contains(HOSTNAME_VERIFICATION);
            }
        }

        /**
         * Asserts both deployment descriptions carry the broker ceiling as a literal.
         *
         * <p>A ConfigMap key would let one deployment raise the broker ceiling while the four
         * application ceilings stayed where they are, which is the disagreement the ladder exists to
         * prevent. The figure belongs to the ladder, so it is written where the broker is declared.
         */
        @Test
        @DisplayName("both deployment descriptions carry the broker ceiling as a literal")
        void bothDeploymentDescriptionsCarryTheBrokerCeilingAsALiteral() {
            String compose = read(platformDirectory().resolve("docker-compose.yml"));
            String broker = read(platformDirectory().resolve("deploy/k8s/10-kafka.yaml"));

            assertThat(compose)
                    .as("the composition broker ceiling")
                    .contains(BROKER_SETTING + ": " + BROKER_MESSAGE_BYTES)
                    .doesNotContain(BROKER_SETTING + ": ${");
            assertThat(broker)
                    .as("the cluster broker ceiling")
                    .contains("- name: " + BROKER_SETTING)
                    .contains("value: \"" + BROKER_MESSAGE_BYTES + "\"");
            assertThat(read(platformDirectory().resolve("deploy/k8s/30-configmap.yaml")))
                    .as("the broker ceiling is derived from a constant in the code, so it is not a "
                            + "value a deployment chooses")
                    .doesNotContain(BROKER_SETTING);
            assertThat(read(platformDirectory().resolve(".env.example")))
                    .as("the broker ceiling is not an environment setting either")
                    .doesNotContain(BROKER_SETTING);
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
     * Reads every migration of one module as one text, so a later comment is visible with the first.
     *
     * @param module the service module directory name
     * @return the concatenated migration text of that module
     */
    private static String migrationTextOf(String module) {
        Path migrations = platformDirectory().resolve("services").resolve(module)
                .resolve("src/main/resources/db/migration");
        StringBuilder joined = new StringBuilder();
        try (var files = Files.list(migrations)) {
            for (Path file : files.sorted().toList()) {
                joined.append(read(file)).append('\n');
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + migrations, unreadable);
        }
        return joined.toString();
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
