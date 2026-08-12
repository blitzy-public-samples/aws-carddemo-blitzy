package com.carddemo.authorization.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.domain.ReplicaSynchronization;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Measures the replica verdict one listener state at a time.
 *
 * <p><b>The defect this class stands over.</b> The shipped rule measured replica currency as the age
 * of the row a decision read, against a ceiling of one day. Both producers publish on a state change
 * and on nothing else, so the observation stamp of a card nobody touches recedes for ever while the
 * copy stays exactly correct. A day after seeding, every ordinary authorization refused, and no
 * amount of correct operation cleared it because nothing was wrong. The rule refused the quiet case
 * rather than the failing one, and that is what {@code KafkaReplicaSynchronization} replaces.
 *
 * <p><b>What replaced it.</b> The question is asked of the stream instead: is what this copy holds
 * what the owner published? A consumer that is running, holds partitions and reports no backlog is a
 * consumer with nothing waiting on it, whatever the age of the rows it wrote. The two assertions that
 * carry the fix are {@link StreamsWithNothingWaiting#aQuietStreamThatHasFetchedNothingIsUsable()} and
 * {@link StreamsWithNothingWaiting#aStreamMeasuredEmptyIsUsable()}: an idle topic must be usable
 * indefinitely, and it must be usable whether or not a lag sample has ever been published.
 *
 * <p><b>What still refuses.</b> Every condition under which this process cannot show that what the
 * owners published has been applied: a topic no listener subscribes to, a container that is not
 * running, a running container the group has assigned nothing to, a measured backlog over the
 * ceiling, and the window before the registry exists. Each is fail-closed on purpose, and each clears
 * itself as soon as the condition does, which is the difference between this rule and the one it
 * replaces.
 *
 * <p>No broker takes part. Every value here comes from a stubbed listener container, because that is
 * exactly where the production class reads from: {@link MessageListenerContainer#isRunning()},
 * {@link MessageListenerContainer#getAssignedPartitions()} and
 * {@link MessageListenerContainer#metrics()} are already-held state, so a verdict costs no request
 * and is affordable on the decision path.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("The replica verdict: about the stream, never about the age of a row")
class KafkaReplicaSynchronizationTest {

    /** The topic whose records maintain {@code account_credit_snapshot}. */
    private static final String ACCOUNT_TOPIC = "carddemo.account.state-changed";

    /** The topic whose records maintain {@code card_xref}. */
    private static final String CARD_TOPIC = "carddemo.card.updated";

    /** A topic name no replica listener declares, used to prove matching is by topic. */
    private static final String UNRELATED_TOPIC = "carddemo.transaction.authorized";

    /** How many records a stream may have waiting and still be authorized against. */
    private static final long LAG_CEILING = 0L;

    /** A ceiling that admits a small backlog, used by the boundary assertions. */
    private static final long TOLERANT_CEILING = 5L;

    /** The partition each running container below reports as assigned. */
    private static final TopicPartition ASSIGNED = new TopicPartition(ACCOUNT_TOPIC, 0);

    /** A backlog larger than either ceiling here admits. */
    private static final long LARGE_BACKLOG = 41L;

    @Nested
    @DisplayName("Streams with nothing waiting on them")
    class StreamsWithNothingWaiting {

        /**
         * Asserts a running, assigned consumer that has published no lag sample is usable.
         *
         * <p>This is the regression assertion for the defect. A platform whose card file nobody has
         * updated is a consumer that has fetched nothing, and a consumer that has fetched nothing
         * publishes no {@code records-lag-max} value at all. Refusing until a first sample arrived
         * would reproduce the outage in a form that never recovered, because an idle topic never
         * produces one.
         */
        @Test
        void aQuietStreamThatHasFetchedNothingIsUsable() {
            ReplicaSynchronization.Verdict verdict = verdictOf(LAG_CEILING,
                    runningContainer(ACCOUNT_TOPIC, noMetrics()),
                    runningContainer(CARD_TOPIC, noMetrics()));

            assertTrue(verdict.usable(),
                    "a consumer holding its assignment with nothing to fetch is caught up");
            assertEquals(ReplicaSynchronization.Verdict.SYNCHRONIZED, verdict.reason(),
                    "and a usable verdict names no fault");
            assertEquals(ReplicaSynchronization.Verdict.UNKNOWN_LAG, verdict.observedLag(),
                    "an unmeasured backlog is reported as unmeasured rather than as zero, so a"
                            + " health document does not claim a measurement it never took");
        }

        /**
         * Asserts a consumer that measured an empty backlog is usable, and reports the measurement.
         */
        @Test
        void aStreamMeasuredEmptyIsUsable() {
            ReplicaSynchronization.Verdict verdict = verdictOf(LAG_CEILING,
                    runningContainer(ACCOUNT_TOPIC, lagOf(0.0d)),
                    runningContainer(CARD_TOPIC, lagOf(0.0d)));

            assertTrue(verdict.usable(), "zero records waiting is a caught-up stream");
            assertEquals(0L, verdict.observedLag(), "and the measurement is reported");
        }

        /** Asserts a backlog at the ceiling is admitted, because the ceiling is inclusive. */
        @Test
        void aBacklogAtTheCeilingIsAdmitted() {
            ReplicaSynchronization.Verdict verdict = verdictOf(TOLERANT_CEILING,
                    runningContainer(ACCOUNT_TOPIC, lagOf((double) TOLERANT_CEILING)),
                    runningContainer(CARD_TOPIC, lagOf(0.0d)));

            assertTrue(verdict.usable(),
                    "carddemo.replica.lag-ceiling names how many records may be waiting, so the"
                            + " value itself is admitted");
            assertEquals(TOLERANT_CEILING, verdict.observedLag(),
                    "the highest measurement across both streams is the one reported");
        }

        /**
         * Asserts a metric named for lag under another group is not read as lag.
         *
         * <p>A consumer publishes many measurements, and matching on the name alone would read a
         * value from an unrelated group as a backlog.
         */
        @Test
        void aMetricUnderAnotherGroupIsNotReadAsLag() {
            Map<MetricName, Metric> foreign = new LinkedHashMap<>();
            put(foreign, KafkaReplicaSynchronization.RECORDS_LAG_MAX, "consumer-metrics",
                    (double) LARGE_BACKLOG);

            ReplicaSynchronization.Verdict verdict = verdictOf(LAG_CEILING,
                    runningContainer(ACCOUNT_TOPIC, foreign),
                    runningContainer(CARD_TOPIC, noMetrics()));

            assertTrue(verdict.usable(),
                    "only consumer-fetch-manager-metrics carries the fetch backlog");
        }

        /**
         * Asserts a non-finite sample is not read as a backlog.
         *
         * <p>A consumer with an assignment and no completed fetch publishes the metric with a value
         * of {@code NaN}, which compares false against every ceiling and would otherwise be cast to
         * a nonsense whole number.
         */
        @Test
        void aNonFiniteSampleIsNotReadAsABacklog() {
            ReplicaSynchronization.Verdict verdict = verdictOf(LAG_CEILING,
                    runningContainer(ACCOUNT_TOPIC, lagOf(Double.NaN)),
                    runningContainer(CARD_TOPIC, lagOf(Double.NEGATIVE_INFINITY)));

            assertTrue(verdict.usable(), "an absent measurement is not a backlog");
            assertEquals(ReplicaSynchronization.Verdict.UNKNOWN_LAG, verdict.observedLag(),
                    "and it is reported as unmeasured");
        }
    }

    @Nested
    @DisplayName("Streams this process cannot vouch for")
    class StreamsThisProcessCannotVouchFor {

        /** Asserts a measured backlog over the ceiling refuses, naming the measurement. */
        @Test
        void aMeasuredBacklogOverTheCeilingRefuses() {
            ReplicaSynchronization.Verdict verdict = verdictOf(TOLERANT_CEILING,
                    runningContainer(ACCOUNT_TOPIC, lagOf((double) LARGE_BACKLOG)),
                    runningContainer(CARD_TOPIC, lagOf(0.0d)));

            assertFalse(verdict.usable(),
                    "records published and not yet applied are changes this copy is missing");
            assertEquals(KafkaReplicaSynchronization.BEHIND, verdict.reason());
            assertEquals(LARGE_BACKLOG, verdict.observedLag(),
                    "the measurement travels with the refusal, so an operator reads how far behind");
        }

        /**
         * Asserts a fractional sample is rounded away from zero before it is compared.
         *
         * <p>{@code records-lag-max} is a floating-point maximum, so a single waiting record can be
         * reported as a value just over zero. Truncating it would admit a backlog at a ceiling of
         * zero.
         */
        @Test
        void aFractionalSampleIsRoundedUpBeforeComparison() {
            ReplicaSynchronization.Verdict verdict = verdictOf(LAG_CEILING,
                    runningContainer(ACCOUNT_TOPIC, lagOf(0.25d)),
                    runningContainer(CARD_TOPIC, noMetrics()));

            assertFalse(verdict.usable(), "a quarter of a record waiting is a record waiting");
            assertEquals(1L, verdict.observedLag());
        }

        /** Asserts a stopped container refuses, because nothing applies what arrives while it is. */
        @Test
        void aStoppedContainerRefuses() {
            ReplicaSynchronization.Verdict verdict = verdictOf(LAG_CEILING,
                    runningContainer(ACCOUNT_TOPIC, lagOf(0.0d)),
                    stoppedContainer(CARD_TOPIC));

            assertFalse(verdict.usable(),
                    "a container that is not running applies nothing published while it is not");
            assertEquals(KafkaReplicaSynchronization.NOT_RUNNING, verdict.reason());
            assertEquals(ReplicaSynchronization.Verdict.UNKNOWN_LAG, verdict.observedLag(),
                    "a stopped consumer measures nothing, so no measurement is claimed");
        }

        @Test
        void aRunningContainerWithNoAssignmentRefuses() {
            ReplicaSynchronization.Verdict verdict = verdictOf(LAG_CEILING,
                    runningContainer(ACCOUNT_TOPIC, lagOf(0.0d)),
                    unassignedContainer(CARD_TOPIC));

            assertFalse(verdict.usable(),
                    "a member the group has assigned nothing to reads nothing, which is what a"
                            + " rebalance looks like from inside");
            assertEquals(KafkaReplicaSynchronization.NO_ASSIGNMENT, verdict.reason());
        }

        /** Asserts a null assignment is treated as no assignment rather than as an approval. */
        @Test
        void aNullAssignmentRefuses() {
            MessageListenerContainer container = mock(MessageListenerContainer.class);
            when(container.getContainerProperties())
                    .thenReturn(new ContainerProperties(CARD_TOPIC));
            when(container.isRunning()).thenReturn(true);
            when(container.getAssignedPartitions()).thenReturn(null);

            ReplicaSynchronization.Verdict verdict = verdictOf(LAG_CEILING,
                    runningContainer(ACCOUNT_TOPIC, lagOf(0.0d)), container);

            assertFalse(verdict.usable(), "an absent assignment is not an empty backlog");
            assertEquals(KafkaReplicaSynchronization.NO_ASSIGNMENT, verdict.reason());
        }

        /** Asserts a replica topic no listener declares refuses, however current its table looks. */
        @Test
        void aTopicNoListenerSubscribesToRefuses() {
            ReplicaSynchronization.Verdict verdict = verdictOf(LAG_CEILING,
                    runningContainer(ACCOUNT_TOPIC, lagOf(0.0d)),
                    runningContainer(UNRELATED_TOPIC, lagOf(0.0d)));

            assertFalse(verdict.usable(),
                    "a topic nothing subscribes to is a table nothing maintains, so matching is by"
                            + " the topic a container declares and not by how many containers exist");
            assertEquals(KafkaReplicaSynchronization.NO_LISTENER, verdict.reason());
        }

        /** Asserts the window before the registry exists refuses rather than assuming currency. */
        @Test
        void anUnavailableRegistryRefuses() {
            ReplicaSynchronization.Verdict verdict =
                    new KafkaReplicaSynchronization(absentRegistry(), properties(LAG_CEILING))
                            .verdict();

            assertFalse(verdict.usable(),
                    "no registry means no listener has been asked, which is not the same as no"
                            + " backlog");
            assertEquals(KafkaReplicaSynchronization.REGISTRY_UNAVAILABLE, verdict.reason());
        }
    }

    /**
     * Builds the subject over a registry holding the given containers and reads one verdict.
     *
     * @param lagCeiling how many records a stream may have waiting
     * @param containers the listener containers the registry reports, in order
     * @return the verdict the subject answers
     */
    private static ReplicaSynchronization.Verdict verdictOf(long lagCeiling,
            MessageListenerContainer... containers) {

        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getListenerContainers()).thenReturn(List.of(containers));
        return new KafkaReplicaSynchronization(registryOf(registry), properties(lagCeiling))
                .verdict();
    }

    /**
     * Wraps one registry in the provider shape the constructor consumes.
     *
     * @param registry the registry to report as available
     * @return a provider answering {@code registry}
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<KafkaListenerEndpointRegistry> registryOf(
            KafkaListenerEndpointRegistry registry) {

        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    /**
     * @return a provider reporting no registry, which is the state during context start-up
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<KafkaListenerEndpointRegistry> absentRegistry() {
        ObjectProvider<KafkaListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    /**
     * Supplies the two topic names and the lag ceiling the subject reads.
     *
     * @param lagCeiling how many records a stream may have waiting
     * @return configuration carrying the replica block and the consumed topic names
     */
    private static AuthorizationProperties properties(long lagCeiling) {
        AuthorizationProperties properties = mock(AuthorizationProperties.class);
        AuthorizationProperties.Kafka.Topics topics = new AuthorizationProperties.Kafka.Topics(
                "carddemo.transaction.authorized", "carddemo.transaction.declined", ACCOUNT_TOPIC,
                CARD_TOPIC, "carddemo.dead-letter");
        AuthorizationProperties.Kafka.Groups groups = new AuthorizationProperties.Kafka.Groups(
                "authorization-account-state", "authorization-card-updated");
        when(properties.kafka()).thenReturn(
                new AuthorizationProperties.Kafka(topics, groups));
        when(properties.replica())
                .thenReturn(new AuthorizationProperties.Replica(lagCeiling));
        return properties;
    }

    /**
     * Builds a container that is running, holds one partition, and reports the given measurements.
     *
     * @param topic   the topic it declares
     * @param metrics the consumer measurements it publishes
     * @return the stubbed container
     */
    private static MessageListenerContainer runningContainer(String topic,
            Map<MetricName, Metric> metrics) {

        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(new ContainerProperties(topic));
        when(container.isRunning()).thenReturn(true);
        when(container.getAssignedPartitions()).thenReturn(Set.of(ASSIGNED));
        when(container.metrics()).thenReturn(
                Map.<String, Map<MetricName, ? extends Metric>>of("consumer-0", metrics));
        return container;
    }

    /**
     * Builds a container that is not running.
     *
     * @param topic the topic it declares
     * @return the stubbed container
     */
    private static MessageListenerContainer stoppedContainer(String topic) {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(new ContainerProperties(topic));
        when(container.isRunning()).thenReturn(false);
        return container;
    }

    /**
     * Builds a container that is running and holds no partition.
     *
     * @param topic the topic it declares
     * @return the stubbed container
     */
    private static MessageListenerContainer unassignedContainer(String topic) {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getContainerProperties()).thenReturn(new ContainerProperties(topic));
        when(container.isRunning()).thenReturn(true);
        when(container.getAssignedPartitions()).thenReturn(Set.of());
        return container;
    }

    /**
     * @return the measurements of a consumer that has completed no fetch
     */
    private static Map<MetricName, Metric> noMetrics() {
        return Map.of();
    }

    /**
     * Builds the one measurement the subject reads, under the group it reads it from.
     *
     * @param value the reported maximum record lag
     * @return a single-entry measurement map
     */
    private static Map<MetricName, Metric> lagOf(double value) {
        Map<MetricName, Metric> metrics = new LinkedHashMap<>();
        put(metrics, KafkaReplicaSynchronization.RECORDS_LAG_MAX,
                KafkaReplicaSynchronization.FETCH_MANAGER_GROUP, value);
        return metrics;
    }

    /**
     * Adds one stubbed measurement to a map.
     *
     * @param metrics the map to add to
     * @param name    the measurement name
     * @param group   the measurement group
     * @param value   the reported value
     */
    private static void put(Map<MetricName, Metric> metrics, String name, String group,
            double value) {

        MetricName metricName =
                new MetricName(name, group, "stubbed for this test", Map.<String, String>of());
        Metric metric = mock(Metric.class);
        when(metric.metricValue()).thenReturn(value);
        metrics.put(metricName, metric);
    }
}
