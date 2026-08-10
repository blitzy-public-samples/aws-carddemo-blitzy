package com.carddemo.card.messaging;

import com.carddemo.events.correlation.EventCorrelation;
import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.EventJsonValidator;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka-backed {@link EventPublisherPort} implementation. This class holds the only Kafka type the
 * card service imports.
 *
 * <p>No COBOL program defines this class. The nearest source construct is the
 * {@code WIRTE-JOBSUB-TDQ} paragraph at {@code app/cbl/CORPT00C.cbl:L515-L523}, which hands one
 * record to a Customer Information Control System (CICS) transient data queue.
 *
 * <p>{@code domain/CardUpdateService} and {@code outbox/OutboxWriter} commit the card row and the
 * outbox row in one local transaction. {@code outbox/OutboxRelay} calls this class afterwards, in a
 * transaction of its own, never from inside request handling.
 *
 * <p>Four checks run before any event leaves this service. Construction rejects a producer that
 * does not pin acknowledgement from every in-sync replica, idempotent production, a safe in-flight
 * limit and bounded timeouts. Each publish rejects a message whose event type does not belong on
 * the supplied topic. Each publish rejects a message whose key differs from the payload's
 * {@code aggregateId}, or from its {@code accountId} where the document declares one. Each publish
 * validates the payload through {@link EventJsonValidator}, the one gate every event of this
 * platform passes.
 *
 * <p>That gate is shared. It reads the same table
 * {@code JsonSchemaValidatingSerializer} and {@code JsonSchemaValidatingDeserializer} read. A card
 * event is therefore held to the same rules as every other event on the platform.
 *
 * <ul>
 *   <li>the governed event type list</li>
 *   <li>the contract version</li>
 *   <li>the size ceiling</li>
 *   <li>the parser limits</li>
 *   <li>the closed property set</li>
 * </ul>
 *
 * <p>This class previously loaded schema documents itself and derived a document name from the
 * event type. That was a second gate, able to disagree with the first, and it applied neither the
 * governed type list nor the size ceiling.
 *
 * <p>Every rejection message holds a JSON pointer, a broken keyword, an event type, a topic name
 * or a length. None holds a value read from the payload. A full Primary Account Number (PAN), a
 * card verification value and an account identifier therefore cannot reach a log through a failed
 * publish.
 *
 * <p>Another event bus needs one more implementation of {@link EventPublisherPort}, and a new
 * consumer of a card event needs no change in this package.
 *
 * <p>This class carries no stereotype and reads no property placeholder. {@code
 * config/KafkaProducerConfig} builds the one instance from the bound {@code CardProperties}, so every
 * value it holds has already met the constraints that record declares. A constructor {@code @Value}
 * would be a second binding of the same properties, and a second binding meets no constraint: that is
 * how a publish timeout of zero used to start the service.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public class KafkaEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** Envelope field that carries the account identifier and the Kafka message key. */
    private static final String AGGREGATE_ID = "aggregateId";

    /** Payload field that carries the account identifier. */
    private static final String ACCOUNT_ID = "accountId";

    /** Envelope field that names the event, and with it the schema document. */
    private static final String EVENT_TYPE = "eventType";

    /** Envelope field that names the contract version. */
    private static final String SCHEMA_VERSION = "schemaVersion";

    /** Acknowledgement setting the platform requires: every in-sync replica. */
    private static final String REQUIRED_ACKS = "all";

    /** Highest in-flight request count that keeps ordering under an idempotent producer. */
    private static final int MAX_IN_FLIGHT_LIMIT = 5;

    /** Producer settings that must carry a bounded millisecond value. */
    private static final List<String> REQUIRED_TIMEOUT_SETTINGS = List.of(
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
            ProducerConfig.MAX_BLOCK_MS_CONFIG);

    /** Compiled once from {@link EventPublisherPort#AGGREGATE_ID_PATTERN}. */
    private static final Pattern AGGREGATE_ID_MATCHER = Pattern.compile(AGGREGATE_ID_PATTERN);

    private final KafkaTemplate<String, String> kafkaTemplate;

    /** Reads the envelope of an already-serialized payload. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Each event type this service publishes, mapped to the topic this deployment configures.
     *
     * <p>Two entries, and the second is the one a review found missing. {@link EventContracts#CARD_UPDATED}
     * comes from {@code carddemo.kafka.topics.card-updated} and {@link EventContracts#DEAD_LETTER}
     * from {@code carddemo.kafka.topics.dead-letter}. {@link #requireBoundToTopic} refuses any event
     * type whose destination this map does not confirm, so a deployment that renamed the dead-letter
     * topic — which {@code docker-compose.yml} and {@code deploy/k8s/30-configmap.yaml} both allow —
     * had every terminal diagnostic refused before it was sent. That is the one record of an event the
     * relay gave up on, and losing it leaves an abandoned row that nothing on the broker accounts for.
     *
     * <p>A blank value leaves the registry default as the only accepted name for that type, which is
     * what an unset property means.
     */
    private final Map<String, String> configuredTopics;

    /**
     * The one gate every event of this platform passes on the way out.
     *
     * <p>{@code com.carddemo:event-contracts} owns it. Five things are therefore the same here as
     * in the shared serializer and deserializer.
     *
     * <ul>
     *   <li>the governed event type list</li>
     *   <li>the contract version</li>
     *   <li>the size ceiling</li>
     *   <li>the parser limits</li>
     *   <li>every closed property set</li>
     * </ul>
     *
     * <p>Nothing about a schema document is decided in this package.
     */
    private final EventJsonValidator eventValidator = EventJsonValidator.shared();

    /** How long one send waits for the broker before the attempt is reported as failed. */
    private final Duration publishTimeout;

    /**
     * Takes the producer template Spring Boot builds from the {@code spring.kafka.producer}
     * properties and checks the reliability settings that template carries.
     *
     * @param kafkaTemplate   the template that sends every card event to the broker
     * @param cardUpdatedTopic the topic name configured for the card update event, which
     *                        {@code application.yml} reads from
     *                        {@code carddemo.kafka.topics.card-updated}
     * @param deadLetterTopic the topic name configured for the terminal diagnostic of an abandoned
     *                        outbox row, from {@code carddemo.kafka.topics.dead-letter}
     * @param publishTimeout  how long one send waits for the broker, from
     *                        {@code carddemo.outbox.relay.publish-timeout}
     * @throws IllegalStateException when the producer does not pin acknowledgement from every
     *         in-sync replica, idempotent production, an in-flight limit of at most
     *         {@value #MAX_IN_FLIGHT_LIMIT}, or a bounded delivery, request and block timeout
     */
    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
            String cardUpdatedTopic,
            String deadLetterTopic,
            Duration publishTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.configuredTopics = Map.of(
                EventContracts.CARD_UPDATED, cardUpdatedTopic == null ? "" : cardUpdatedTopic,
                EventContracts.DEAD_LETTER, deadLetterTopic == null ? "" : deadLetterTopic);
        this.publishTimeout = publishTimeout;
        requireReliableProducer(kafkaTemplate.getProducerFactory().getConfigurationProperties());
    }

    /**
     * Sends {@code payload} to {@code topic} unchanged and answers the stage the broker
     * acknowledgement completes. This method does not wait: the caller decides how long to, and
     * {@code outbox/OutboxRelay} waits under its own whole-sweep deadline. A broker failure arrives
     * on that stage as an unchecked {@code java.util.concurrent.CompletionException}.
     *
     * <p>Every check below runs before any send is started, so a refused call publishes nothing and
     * throws rather than failing a stage.
     *
     * <p>The message key is {@code aggregateId} itself, so Kafka partitions on the account and
     * every event for one account stays in order.
     *
     * <p>The log record names the event type, the topic and the payload length. It carries no
     * message key, no account identifier and no part of the payload.
     *
     * @throws IllegalArgumentException on any of five conditions.
     *         {@code topic} or {@code payload} is null.
     *         {@code aggregateId} is not eleven decimal digits.
     *         The payload is not one JSON object.
     *         The payload declares an event type that does not belong on {@code topic}, or
     *         {@code aggregateId} differs from the payload's {@code aggregateId} or from its
     *         {@code accountId} where the document declares one.
     *         The shared gate refuses the payload
     * @return the stage the broker acknowledgement completes, failing when the broker refuses the
     *         send or does not answer inside {@code carddemo.outbox.relay.publish-timeout}
     */
    @Override
    public CompletionStage<Void> publish(String topic, String aggregateId, String payload) {
        if (topic == null || payload == null) {
            throw new IllegalArgumentException("topic and payload are both required");
        }
        if (aggregateId == null || !AGGREGATE_ID_MATCHER.matcher(aggregateId).matches()) {
            throw new IllegalArgumentException(
                    "aggregateId must match " + AGGREGATE_ID_PATTERN + " and the supplied value "
                            + (aggregateId == null ? "is null" : "holds " + aggregateId.length()
                                    + " characters"));
        }
        JsonNode event = readEnvelope(payload);
        String eventType = requireBoundToTopic(event, topic);
        requireSingleAccountIdentity(aggregateId, event);
        requireValidAgainstSchema(payload);
        log.debug("Publishing card event {} to topic {}, payload length {}",
                eventType, topic, payload.length());
        return send(topic, aggregateId, payload);
    }

    /**
     * Starts one send and answers the stage its acknowledgement completes.
     *
     * <p>This method does not block. It used to wait on the send here, and the wait was the defect:
     * the bound it applied was ten seconds while the producer was configured to keep trying for
     * two minutes, so a wait that ran out left a send the producer still held. Nothing cancelled
     * it and nothing observed it, the row stayed unpublished, and the next sweep published the
     * same event a second time. The two windows are now one budget, declared together in
     * {@code src/main/resources/application.yml}.
     *
     * <p>{@code orTimeout} fails the returned stage rather than the send, so the caller learns the
     * outcome without holding a thread. {@code outbox/OutboxRelay} waits on the stage under its own
     * whole-sweep deadline and cancels what it gave up on.
     *
     * @param topic       the destination topic
     * @param aggregateId the message key
     * @param payload     the event text
     * @return the stage the acknowledgement completes, failing when the broker refuses the send or
     *         does not answer inside {@code carddemo.outbox.relay.publish-timeout}
     */
    private CompletionStage<Void> send(String topic, String aggregateId, String payload) {
        return kafkaTemplate.send(correlatedRecord(topic, aggregateId, payload))
                .thenApply(acknowledged -> (Void) null)
                .orTimeout(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
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
     * Reads the event type from the envelope and checks that the type belongs on the supplied topic.
     *
     * <p>{@link EventContracts} holds the one registry that pairs an event type with its topic and
     * with its schema document. Without this check a card update could reach the transaction topic,
     * where every consumer would read it as a transaction and reject or mishandle it.
     *
     * <p>The event type comes from the payload and never from a separate argument, so a caller
     * cannot name one type while publishing another.
     *
     * @param event the parsed event
     * @param topic the destination topic the caller read from configuration
     * @return the event type the envelope declares
     * @throws IllegalArgumentException when the envelope declares no registered event type, or
     *         when that type does not belong on {@code topic}
     */
    private String requireBoundToTopic(JsonNode event, String topic) {
        String eventType = event.path(EVENT_TYPE).asString("");
        JsonNode version = event.path(SCHEMA_VERSION);
        if (!version.isNumber()) {
            throw new IllegalArgumentException("The envelope carries " + SCHEMA_VERSION
                    + " as a number, and the supplied event carries none.");
        }
        if (!EventContracts.isRegistered(eventType)) {
            throw new IllegalArgumentException("The envelope declares the event type '" + eventType
                    + "', which no contract registers. " + EventContracts.eventTypes()
                    + " are the registered types.");
        }
        if (!EventContracts.isBoundToTopic(eventType, topic, configuredTopics.get(eventType))) {
            throw new IllegalArgumentException(eventType + " belongs on the topic "
                    + EventContracts.defaultTopicFor(eventType) + " and the supplied topic reads "
                    + topic + ".");
        }
        return eventType;
    }

    /**
     * Checks the four producer reliability settings this platform pins.
     *
     * <p>An unset value counts as absent, so a producer that relies on a client default fails here
     * instead of at the first lost message.
     *
     * @param configuration the producer configuration the template was built with
     * @throws IllegalStateException naming every setting that is absent or wrong
     */
    private static void requireReliableProducer(Map<String, Object> configuration) {
        Map<String, String> problems = new LinkedHashMap<>();

        String acks = text(configuration.get(ProducerConfig.ACKS_CONFIG));
        if (!REQUIRED_ACKS.equals(acks) && !"-1".equals(acks)) {
            problems.put(ProducerConfig.ACKS_CONFIG, "reads " + acks + " and must read all");
        }

        String idempotence = text(configuration.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        if (!Boolean.parseBoolean(idempotence)) {
            problems.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                    "reads " + idempotence + " and must read true");
        }

        Integer inFlight = number(configuration.get(
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
        if (inFlight == null || inFlight < 1 || inFlight > MAX_IN_FLIGHT_LIMIT) {
            problems.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,
                    "reads " + inFlight + " and must read 1 through " + MAX_IN_FLIGHT_LIMIT);
        }

        for (String setting : REQUIRED_TIMEOUT_SETTINGS) {
            Integer millis = number(configuration.get(setting));
            if (millis == null || millis <= 0) {
                problems.put(setting, "reads " + millis + " and must read a positive millisecond "
                        + "count");
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "The card service producer misses the platform reliability settings: "
                            + problems + ". docker-compose.yml and "
                            + "card-platform/.env.example carry the values every producer pins.");
        }
    }

    /**
     * Reads the payload as one JSON object.
     *
     * @param payload the serialized event the outbox relay supplied
     * @return the parsed event
     * @throws IllegalArgumentException when the payload is absent, unparseable, or not an object
     */
    private JsonNode readEnvelope(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload holds one serialized event and is not blank");
        }
        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (RuntimeException parseFailure) {
            throw new IllegalArgumentException("payload holds one JavaScript Object Notation (JSON) "
                    + "object and the supplied text of " + payload.length()
                    + " characters does not parse");
        }
        if (!event.isObject()) {
            throw new IllegalArgumentException("payload holds one JSON object per event");
        }
        return event;
    }

    /**
     * Checks that the message key and every account identifier the payload carries hold one value.
     *
     * <p>The Kafka message key must equal {@code aggregateId}.
     *
     * <p>{@code aggregateId} is the single source of account identity and every document declares
     * it. A document that also declares the payload field {@code accountId} must agree with it. A
     * document that single-sources the identifier carries no such field, and its absence is not a
     * disagreement.
     *
     * <p>No message here names a value.
     *
     * @param key   the Kafka message key the caller supplied
     * @param event the parsed event
     * @throws IllegalArgumentException when the identifiers do not agree
     */
    private static void requireSingleAccountIdentity(String key, JsonNode event) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException(
                    "key holds the account identifier and is neither null nor empty");
        }
        if (!key.equals(event.path(AGGREGATE_ID).asString(""))) {
            throw new IllegalArgumentException("The Kafka message key and " + AGGREGATE_ID
                    + " carry one account identifier, and they differ.");
        }
        JsonNode accountId = event.path(ACCOUNT_ID);
        if (!accountId.isMissingNode() && !key.equals(accountId.asString(""))) {
            throw new IllegalArgumentException("The Kafka message key and " + ACCOUNT_ID
                    + " carry one account identifier, and they differ.");
        }
    }

    /**
     * Validates the payload through the shared gate {@code com.carddemo:event-contracts} owns.
     *
     * <p>The shared validator selects the schema from the event type. It refuses a type the
     * platform does not govern and refuses another contract version. It applies the platform size
     * ceiling and the platform parser limits. It reports a failure by JSON pointer and broken
     * keyword, with no value from the event in the message. This class adds nothing to that and
     * reimplements none of it.
     *
     * @param payload the serialized event, validated as received
     * @throws IllegalArgumentException when the event names no governed type, carries another
     *         contract version, exceeds the platform ceiling, or breaks its schema
     */
    private void requireValidAgainstSchema(String payload) {
        eventValidator.validate(payload);
    }

    /**
     * Renders one configuration value as text.
     *
     * @param value the configured value, possibly null
     * @return the value as text, or null
     */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Reads one configuration value as a whole number.
     *
     * @param value the configured value, possibly null and possibly text
     * @return the value as an {@code Integer}, or null when it is absent or not numeric
     */
    private static Integer number(Object value) {
        if (value instanceof Number configured) {
            return configured.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
