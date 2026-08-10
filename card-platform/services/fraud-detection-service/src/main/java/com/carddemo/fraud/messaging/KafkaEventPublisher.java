package com.carddemo.fraud.messaging;

import com.carddemo.events.correlation.EventCorrelation;
import org.apache.kafka.clients.producer.ProducerRecord;
import static com.carddemo.fraud.messaging.EventPublisherPort.AGGREGATE_ID_PATTERN;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The Kafka adapter behind {@link EventPublisherPort}, and the only class in this service that holds a
 * broker client.
 *
 * <p>Before this class existed, {@code outbox/OutboxRelay} injected the
 * {@code fraudEventKafkaTemplate} and called {@code send} on it, so the relay named Kafka in its own
 * signature. Every other producing service on this platform already published through a port with an
 * adapter behind it, and this one did not, which made the broker a compile-time dependency of the
 * relay rather than a configuration decision. Substituting a managed event service is now one new
 * implementation of {@link EventPublisherPort} and no change to the relay.
 *
 * <p><b>What this class checks, and what it deliberately leaves to the serializer.</b> It checks the
 * message key, because that is the one property the relay supplies and the payload also carries: an
 * eleven-digit account identifier, so Kafka partitions on the account and every assessment of one
 * account stays in publish order. It does not write or validate the payload. The producer
 * {@code config/KafkaProducerConfig} builds carries {@code JsonSchemaValidatingSerializer}, which
 * serializes the record, validates it against the schema document its event type names, and refuses an
 * event sent to a topic its type is not bound to — with one topic override registered per event type,
 * the shared dead-letter envelope included. Repeating any of that here would mean two places to keep
 * in step and a second serialization of the same record.
 *
 * <p>It does check the two producer reliability settings this platform pins, at construction, for the
 * same reason its siblings do: {@code acks=all} waits for every in-sync replica and
 * {@code enable.idempotence=true} stops an internal retry writing one assessment twice. A producer
 * assembled without them fails start-up here rather than at the first lost or duplicated event.
 *
 * <p><b>What this class does not do.</b> It does not wait. A send that was started reports on the stage
 * this method returns, and {@code outbox/OutboxRelay} waits on that stage under the budget its sweep
 * has left, cancelling what it gave up on. A per-send wait here would make the sweep bound behave as a
 * per-send ceiling, which is the defect the sweep budget already documents.
 *
 * <p>The log line names the topic and the event class. It carries no message key, no account identifier
 * and no property of the payload.
 *
 * <p>Thread-safe: {@link KafkaTemplate} is, and this class holds no mutable state.
 */
@Component
public class KafkaEventPublisher implements EventPublisherPort {

    /** Compiled once from {@link EventPublisherPort#AGGREGATE_ID_PATTERN}. */
    private static final Pattern AGGREGATE_ID_MATCHER = Pattern.compile(AGGREGATE_ID_PATTERN);

    /** Acknowledgement from every in-sync replica, which every producer on this platform pins. */
    private static final String REQUIRED_ACKS = "all";

    /** The one logger of this class. */
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** Sends every event of this service to the broker. */
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Takes the configured producer template and checks the two settings this platform pins.
     *
     * @param kafkaTemplate the template {@code config/KafkaProducerConfig} builds, carrying the
     *                      validating serializer and one topic override per event type
     * @throws NullPointerException when {@code kafkaTemplate} is absent
     * @throws IllegalStateException when the producer pins neither acknowledgement from every in-sync
     *                               replica nor idempotent production
     */
    public KafkaEventPublisher(
            @Qualifier("fraudEventKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must be present");
        requireReliableProducer(kafkaTemplate.getProducerFactory().getConfigurationProperties());
    }

    @Override
    public CompletionStage<Void> publish(String topic, String aggregateId, Object event) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic names the destination and is not blank");
        }
        if (event == null) {
            throw new IllegalArgumentException("event holds one record and is not null");
        }
        if (aggregateId == null || !AGGREGATE_ID_MATCHER.matcher(aggregateId).matches()) {
            throw new IllegalArgumentException("aggregateId must match " + AGGREGATE_ID_PATTERN
                    + " and the supplied value " + (aggregateId == null ? "is null"
                            : "holds " + aggregateId.length() + " characters"));
        }
        LOG.debug("Publishing a {} to topic {}", event.getClass().getSimpleName(), topic);
        return kafkaTemplate.send(correlatedRecord(topic, aggregateId, event))
                .thenApply(acknowledged -> null);
    }

    /**
     * Builds the record one send carries, attaching the two correlation identifiers of the row.
     *
     * <p>ADDITIVE. The identifiers travel as record headers rather than as payload properties,
     * because AAP 0.3.1 fixes the event envelope at five properties and every schema document closes
     * its top-level property set. The values come from the ambient scope the relay opened for the
     * row, so a header is present exactly when the row recorded one.
     *
     * @param topic       the destination topic
     * @param aggregateId the message key
     * @param payload     the value to send
     * @param <V>         the value type of the template this record is sent through
     * @return the record to send, carrying no header for an identifier the row did not record
     */
    private static <V> ProducerRecord<String, V> correlatedRecord(String topic,
            String aggregateId, V payload) {
        return new ProducerRecord<>(topic, null, aggregateId, payload,
                EventCorrelation.headersFor(
                        EventCorrelation.currentCorrelationId().orElse(null),
                        EventCorrelation.currentCausationId().orElse(null)));
    }

    /**
     * Checks the two producer reliability settings this platform pins.
     *
     * <p>An unset value counts as absent, so a producer relying on a client default fails here instead
     * of at the first lost or duplicated event. The client default acknowledges on the leader alone and
     * retries without an idempotence guarantee, and either one turns a broker hiccup into a lost or
     * doubled assessment.
     *
     * @param configuration the producer configuration the template was built with
     * @throws IllegalStateException naming every setting that is absent or wrong
     */
    private static void requireReliableProducer(Map<String, Object> configuration) {
        Map<String, String> problems = new LinkedHashMap<>();

        Object acks = configuration.get(ProducerConfig.ACKS_CONFIG);
        String acksText = acks == null ? null : String.valueOf(acks);
        if (!REQUIRED_ACKS.equals(acksText) && !"-1".equals(acksText)) {
            problems.put(ProducerConfig.ACKS_CONFIG, "reads " + acksText + " and must read all");
        }

        Object idempotence = configuration.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
        if (!Boolean.parseBoolean(String.valueOf(idempotence))) {
            problems.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                    "reads " + idempotence + " and must read true");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "The fraud detection service producer misses the platform reliability settings: "
                            + problems + ". config/KafkaProducerConfig pins both, and "
                            + "card-platform/.env.example carries the values every producer holds.");
        }
    }
}
