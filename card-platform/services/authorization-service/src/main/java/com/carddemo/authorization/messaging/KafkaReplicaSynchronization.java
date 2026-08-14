package com.carddemo.authorization.messaging;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.carddemo.authorization.config.AuthorizationProperties;
import com.carddemo.authorization.domain.ReplicaSynchronization;

import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Reports replica synchronization from the two listeners that maintain the replicas.
 *
 * <p>Three properties are read, in this order, and each answers a different way a replica falls
 * behind its producer.
 *
 * <ol>
 *   <li><strong>A listener exists for each replica topic.</strong> A topic nothing subscribes to is
 *       a table nothing maintains, however recent its rows look.</li>
 *   <li><strong>That listener is running and holds partitions.</strong> A stopped container, or a
 *       running one the group has not assigned anything to, applies nothing that is published while
 *       it is in that state.</li>
 *   <li><strong>The lag it reports is within the configured ceiling.</strong> {@code records-lag-max}
 *       is the consumer's own measurement of how far behind the end of its partitions it was at its
 *       last fetch, so a caught-up consumer of a quiet topic reports zero and a consumer with
 *       records waiting reports how many.</li>
 * </ol>
 *
 * <p>The order matters because the third measurement is the only one that can be absent. A consumer
 * that has held its assignment since start-up and has fetched nothing publishes no lag sample, and a
 * platform whose card file nobody has updated is exactly that consumer. Refusing every decision until
 * a first sample arrived would reproduce the defect this class replaces, in a form that never
 * recovers: an idle topic produces no sample at all. An absent sample is therefore read as what it
 * is, a stream with nothing waiting on it, and the two liveness properties above are what stand
 * behind that reading. A sample that is present is compared, so a consumer with a backlog is refused
 * on the measurement rather than on a guess.
 *
 * <p>What this class cannot see is a record that was delivered, could not be applied, and had its
 * offset advanced once its diagnostic was away. Lag returns to zero for that stream while one
 * account's copy is missing a change, which is why
 * {@code com.carddemo.authorization.domain.ReplicaGapLog} records the account and
 * {@code domain/AuthorizationService} consults both.
 *
 * <p>No broker request is issued here. Every value comes from the listener container this process
 * already runs, so {@link #verdict()} costs a map lookup and may be called on the request path.
 *
 * <p>Thread-safe: the registry and the containers are thread-safe, and this class holds no mutable
 * state of its own.
 */
@Component
public class KafkaReplicaSynchronization implements ReplicaSynchronization {

    /** The consumer metric carrying how far behind its partitions a consumer last was. */
    static final String RECORDS_LAG_MAX = "records-lag-max";

    /** The metric group the consumer publishes fetch measurements under. */
    static final String FETCH_MANAGER_GROUP = "consumer-fetch-manager-metrics";

    /** Reported when no listener subscribes to one of the replica topics. */
    static final String NO_LISTENER = "replica-listener-missing";

    /** Reported when a replica listener exists and is not running. */
    static final String NOT_RUNNING = "replica-listener-stopped";

    /** Reported when a running replica listener holds no partition. */
    static final String NO_ASSIGNMENT = "replica-partitions-unassigned";

    /** Reported when a replica listener measures more lag than the ceiling admits. */
    static final String BEHIND = "replica-stream-behind";

    /** Reported before the listener registry is available, which is only during start-up. */
    static final String REGISTRY_UNAVAILABLE = "replica-registry-unavailable";

    /** Provider of the registry, which is absent until the context has finished starting. */
    private final ObjectProvider<KafkaListenerEndpointRegistry> registries;

    /** The two topics whose records maintain this service's replica tables. */
    private final List<String> replicaTopics;

    /** How many records a replica stream may have waiting and still be authorized against. */
    private final long lagCeiling;

    /**
     * Takes the listener registry and the replica stream policy.
     *
     * @param registries provider of the listener registry, absent until the context has started
     * @param properties the bound {@code carddemo} block, read for the two consumed topic names and
     *                   the lag ceiling
     * @throws NullPointerException when either argument, or a value this class reads from
     *                              {@code properties}, is absent
     */
    public KafkaReplicaSynchronization(ObjectProvider<KafkaListenerEndpointRegistry> registries,
            AuthorizationProperties properties) {
        this.registries = Objects.requireNonNull(registries, "registries must be present");

        AuthorizationProperties.Kafka.Topics topics =
                Objects.requireNonNull(properties, "properties must be present").kafka().topics();
        this.replicaTopics = List.of(
                Objects.requireNonNull(topics.accountStateChanged(),
                        "carddemo.kafka.topics.account-state-changed must be present"),
                Objects.requireNonNull(topics.cardUpdated(),
                        "carddemo.kafka.topics.card-updated must be present"));
        this.lagCeiling = properties.replica().lagCeiling();
    }

    @Override
    public Verdict verdict() {
        KafkaListenerEndpointRegistry registry = registries.getIfAvailable();
        if (registry == null) {
            return Verdict.behind(REGISTRY_UNAVAILABLE, Verdict.UNKNOWN_LAG);
        }

        long highestLag = Verdict.UNKNOWN_LAG;
        for (String topic : replicaTopics) {
            MessageListenerContainer container = containerFor(registry, topic);
            if (container == null) {
                return Verdict.behind(NO_LISTENER, Verdict.UNKNOWN_LAG);
            }
            if (!container.isRunning()) {
                return Verdict.behind(NOT_RUNNING, Verdict.UNKNOWN_LAG);
            }
            Collection<TopicPartition> assigned = container.getAssignedPartitions();
            if (assigned == null || assigned.isEmpty()) {
                return Verdict.behind(NO_ASSIGNMENT, Verdict.UNKNOWN_LAG);
            }

            long lag = measuredLag(container);
            if (lag > lagCeiling) {
                return Verdict.behind(BEHIND, lag);
            }
            highestLag = Math.max(highestLag, lag);
        }
        return Verdict.synchronizedAt(highestLag);
    }

    /**
     * Finds the container that subscribes to one topic.
     *
     * <p>Containers are matched by the topic they declare rather than by a listener identifier,
     * because a listener identifier is a name this class would have to keep in step with two
     * annotations and a topic name is the thing that actually defines which replica a listener
     * maintains.
     *
     * @param registry the listener registry
     * @param topic    the topic a replica listener must subscribe to
     * @return the container subscribing to {@code topic}, or {@code null} when none does
     */
    private static MessageListenerContainer containerFor(KafkaListenerEndpointRegistry registry,
            String topic) {

        for (MessageListenerContainer container : registry.getListenerContainers()) {
            String[] topics = container.getContainerProperties().getTopics();
            if (topics == null) {
                continue;
            }
            for (String declared : topics) {
                if (topic.equals(declared)) {
                    return container;
                }
            }
        }
        return null;
    }

    /**
     * Reads the highest record lag one container's consumers reported at their last fetch.
     *
     * <p>A consumer that has fetched nothing publishes no finite value, and that is reported as
     * {@link Verdict#UNKNOWN_LAG} rather than as a lag of zero, so a caller can tell a measured
     * empty backlog from an unmeasured one. Both are treated as synchronized by {@link #verdict()},
     * for the reason this class documents, and only the measured form appears in a health document.
     *
     * @param container the running container of one replica listener
     * @return the highest finite lag its consumers reported, or {@link Verdict#UNKNOWN_LAG}
     */
    private static long measuredLag(MessageListenerContainer container) {
        long highest = Verdict.UNKNOWN_LAG;

        for (Map<MetricName, ? extends Metric> perConsumer : container.metrics().values()) {
            for (Map.Entry<MetricName, ? extends Metric> metric : perConsumer.entrySet()) {
                MetricName name = metric.getKey();
                if (!RECORDS_LAG_MAX.equals(name.name())
                        || !FETCH_MANAGER_GROUP.equals(name.group())) {
                    continue;
                }
                Object value = metric.getValue().metricValue();
                if (value instanceof Double measured && Double.isFinite(measured)) {
                    highest = Math.max(highest, (long) Math.ceil(measured));
                }
            }
        }
        return highest;
    }
}
