package com.carddemo.authorization.messaging;

import com.carddemo.events.serde.EventContracts;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka-backed {@link EventPublisherPort} implementation, and the only Kafka type this service
 * imports outside its configuration.
 *
 * <p>No COBOL program declares an event bus. The nearest source construct is the transient
 * data queue write at {@code app/cbl/CORPT00C.cbl:L515-L523}.
 *
 * <p>Three checks run before any event leaves this service, and each one closes a way a correct
 * decision could still reach the wrong consumer. The event type the payload declares must belong on
 * the supplied topic, so an approval cannot reach the decline topic. The message key must equal the
 * payload aggregate and every non-null account identifier the payload carries, so an event cannot
 * land on one aggregate's partition while naming another. The payload must satisfy the versioned
 * schema document its event type names.
 *
 * <p>{@link EventContracts} performs the binding and the validation, so this service and every other
 * publisher on the platform read one registry and one set of documents.
 *
 * <p>Every rejection message holds a JSON pointer, a broken keyword, an event type, a topic name or a
 * length. None holds a value read from the payload, so a full Primary Account Number (PAN) or an
 * account identifier cannot reach a log through a failed publish.
 *
 * <p>Another event bus needs one more implementation of {@link EventPublisherPort} and no other
 * change.
 */
@Component
public class KafkaEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** Envelope field carrying the account identifier and the Kafka message key. */
    private static final String AGGREGATE_ID = "aggregateId";

    /** Payload field carrying the account identifier. */
    private static final String ACCOUNT_ID = "accountId";

    /** Envelope field naming the event, and with it the schema document and the topic. */
    private static final String EVENT_TYPE = "eventType";

    /** Compiled once from {@link EventPublisherPort#MESSAGE_KEY_PATTERN}. */
    private static final Pattern MESSAGE_KEY_MATCHER = Pattern.compile(MESSAGE_KEY_PATTERN);

    /** Sends every event to the broker. */
    private final KafkaTemplate<String, String> kafkaTemplate;

    /** Each event type this service publishes, mapped to the topic this deployment configures. */
    private final Map<String, String> configuredTopics;

    /**
     * Longest one send waits for the broker, from
     * {@code carddemo.outbox.relay.publish-timeout}.
     */
    private final Duration publishTimeout;

    /** Reads the envelope of an already-written payload. */
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /**
     * Takes the producer template and the two configured topic names.
     *
     * @param kafkaTemplate   the template that sends every event to the broker
     * @param authorizedTopic topic configured for the approval event, from
     *                        {@code carddemo.kafka.topics.transaction-authorized}
     * @param declinedTopic   topic configured for the decline event, from
     *                        {@code carddemo.kafka.topics.transaction-declined}
     * @param publishTimeout  longest one send waits for the broker, from
     *                        {@code carddemo.outbox.relay.publish-timeout}
     */
    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
            @Value("${carddemo.kafka.topics.transaction-authorized:}") String authorizedTopic,
            @Value("${carddemo.kafka.topics.transaction-declined:}") String declinedTopic,
            @Value("${carddemo.outbox.relay.publish-timeout}") Duration publishTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.configuredTopics = Map.of(EventContracts.TRANSACTION_AUTHORIZED, authorizedTopic,
                EventContracts.TRANSACTION_DECLINED, declinedTopic);
        this.publishTimeout =
                Objects.requireNonNull(publishTimeout, "publishTimeout must be present");
    }

    /**
     * Sends {@code payload} to {@code topic} unchanged, keyed on {@code aggregateId}, and waits for
     * the broker acknowledgement.
     *
     * <p>The log record names the event type, the topic and the payload length. It carries no message
     * key, no account identifier and no part of the payload.
     *
     * @throws IllegalArgumentException when an argument is absent, when {@code aggregateId} misses
     *         {@link #MESSAGE_KEY_PATTERN}, when the payload is not one JSON object, when the event
     *         type it declares does not belong on {@code topic}, when {@code aggregateId} differs
     *         from the payload aggregate or a non-null account identifier it carries, or when the
     *         payload fails the document its event type names
     */
    @Override
    public void publish(String topic, String aggregateId, String payload) {
        if (topic == null || payload == null) {
            throw new IllegalArgumentException("topic and payload are both required");
        }
        if (aggregateId == null || !MESSAGE_KEY_MATCHER.matcher(aggregateId).matches()) {
            throw new IllegalArgumentException("aggregateId must match " + MESSAGE_KEY_PATTERN
                    + " and the supplied value "
                    + (aggregateId == null ? "is null"
                            : "holds " + aggregateId.length() + " characters"));
        }

        JsonNode event = readEnvelope(payload);
        String eventType = requireBoundToTopic(event, topic);
        requireSingleAggregateIdentity(aggregateId, event);
        requireValidAgainstSchema(eventType, payload);

        log.debug("Publishing authorization event {} to topic {}, payload length {}", eventType,
                topic, payload.length());
        sendAndWait(topic, aggregateId, payload);
    }

    /**
     * Sends one payload and waits no longer than the configured publish timeout for the broker.
     *
     * <p>An unbounded wait holds this thread, and with it the relay sweep that called it, for as long
     * as the broker is unreachable, and every later event of every account waits behind it. The
     * bounded wait turns that condition into one thrown failure the caller records against the row.
     *
     * <p>The thrown message names the topic and the bound and reads no field of the payload, so a
     * caller that logs it records no card number and no account identifier.
     *
     * @param topic       the destination topic
     * @param aggregateId the message key
     * @param payload     the event text
     * @throws KafkaException when the broker does not acknowledge inside the bound, when the send
     *         fails, or when the waiting thread is interrupted
     */
    private void sendAndWait(String topic, String aggregateId, String payload) {
        try {
            kafkaTemplate.send(topic, aggregateId, payload)
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException lapsed) {
            throw new KafkaException("the broker did not acknowledge a send to topic " + topic
                    + " within " + publishTimeout, lapsed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new KafkaException("the wait on a send to topic " + topic + " was interrupted",
                    interrupted);
        } catch (ExecutionException failed) {
            throw new KafkaException("a send to topic " + topic + " failed", failed.getCause());
        }
    }

    /**
     * Reads the payload as one JSON object.
     *
     * <p>The failure text reports the length of the text that would not parse and no part of it, so a
     * malformed payload carrying a card number cannot be logged through this path.
     *
     * @param payload the written event the relay supplied
     * @return the parsed event
     * @throws IllegalArgumentException when the payload is blank, unparseable, or not an object
     */
    private JsonNode readEnvelope(String payload) {
        if (payload.isBlank()) {
            throw new IllegalArgumentException("payload holds one written event and is not blank");
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
     * Reads the event type from the envelope and checks that the type belongs on the supplied topic.
     *
     * <p>This service publishes two event types on two topics. Without this check an approval could
     * reach the decline topic, where the ledger and the fraud services would never see it and any
     * consumer of the decline topic would fail to read it as a decline.
     *
     * <p>The event type comes from the payload and never from a separate argument, so a caller cannot
     * name one type while publishing another.
     *
     * @param event the parsed event
     * @param topic the destination topic the relay read from configuration
     * @return the event type the envelope declares
     * @throws IllegalArgumentException when the envelope declares no registered event type, or when
     *         that type does not belong on {@code topic}
     */
    private String requireBoundToTopic(JsonNode event, String topic) {
        String eventType = event.path(EVENT_TYPE).asString("");
        if (!EventContracts.isRegistered(eventType)) {
            throw new IllegalArgumentException("the envelope declares the event type '" + eventType
                    + "', which no contract registers, and " + EventContracts.eventTypes()
                    + " are the registered types");
        }
        if (!EventContracts.isBoundToTopic(eventType, topic, configuredTopics.get(eventType))) {
            throw new IllegalArgumentException(eventType + " belongs on the topic "
                    + EventContracts.defaultTopicFor(eventType) + " and the supplied topic reads "
                    + topic);
        }
        return eventType;
    }

    /**
     * Checks that the message key and the aggregate identifier hold one value.
     *
     * <p>Every document declares {@code aggregateId}. A document that also declares a non-null
     * {@code accountId} must agree with it. The unresolved-card decline is the one governed
     * exception: schema version 2 declares a transaction-key aggregate and omits
     * {@code accountId}, because the cross-reference lookup resolved none.
     *
     * <p>No message below names a value. The identifier is the value under check, and a caller that
     * logs the failure would otherwise record it.
     *
     * @param key   the Kafka message key
     * @param event the parsed event
     * @throws IllegalArgumentException when the identifiers do not agree
     */
    private static void requireSingleAggregateIdentity(String key, JsonNode event) {
        if (!key.equals(event.path(AGGREGATE_ID).asString(""))) {
            throw new IllegalArgumentException("the Kafka message key and " + AGGREGATE_ID
                    + " carry one aggregate identifier, and they differ");
        }
        JsonNode accountId = event.path(ACCOUNT_ID);
        if (!accountId.isMissingNode() && !accountId.isNull()
                && !key.equals(accountId.asString(""))) {
            throw new IllegalArgumentException("the Kafka message key and " + ACCOUNT_ID
                    + " carry one account identifier, and they differ");
        }
    }

    /**
     * Validates the payload against the versioned schema document its event type names.
     *
     * <p>{@link EventContracts#violationsOf(String, String)} returns one entry per failure, and each
     * entry holds a JSON pointer and the broken keyword and nothing else.
     *
     * @param eventType the registered event type the envelope declares
     * @param payload   the written event, validated as received
     * @throws IllegalArgumentException when the payload fails the document that type names
     */
    private static void requireValidAgainstSchema(String eventType, String payload) {
        List<String> violations = EventContracts.violationsOf(eventType, payload);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    EventContracts.describeViolations(eventType, violations));
        }
    }
}
