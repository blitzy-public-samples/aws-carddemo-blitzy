package com.carddemo.card.messaging;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka-backed {@link EventPublisherPort} implementation. This class holds the only Kafka type the
 * card service imports.
 *
 * <p>ADDITIVE. No COBOL program defines this class. The nearest source construct is the
 * {@code WIRTE-JOBSUB-TDQ} paragraph at {@code app/cbl/CORPT00C.cbl:L515-L523}, which hands one
 * record to a Customer Information Control System (CICS) transient data queue.
 *
 * <p>The card row and the outbox row commit in one local transaction, and the outbox relay calls
 * this class afterwards in a separate transaction, never from inside request handling.
 *
 * <p>Three checks run before any event leaves this service. Construction rejects a producer that
 * does not pin acknowledgement from every in-sync replica, idempotent production, a safe in-flight
 * limit and bounded timeouts. Each publish rejects a message whose key differs from the payload's
 * {@code aggregateId}, or from its {@code accountId} where the document declares one. Each publish
 * also validates the payload against the versioned JSON Schema document that its {@code eventType}
 * and {@code schemaVersion} name, loaded from {@code com.carddemo:event-contracts} on the
 * classpath.
 *
 * <p>Another event bus needs one more implementation of {@link EventPublisherPort}, and a new
 * consumer of a card event needs no change in this package.
 */
@Component
public class KafkaEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** Envelope field that carries the account identifier and the Kafka message key. */
    private static final String AGGREGATE_ID = "aggregateId";

    /** Payload field that carries the account identifier. */
    private static final String ACCOUNT_ID = "accountId";

    /** Envelope field that names the event, and with it the schema document. */
    private static final String EVENT_TYPE = "eventType";

    /** Envelope field that names the schema version, and with it the document suffix. */
    private static final String SCHEMA_VERSION = "schemaVersion";

    /** Classpath directory {@code com.carddemo:event-contracts} ships its schema documents in. */
    private static final String SCHEMA_DIRECTORY = "schemas/";

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

    /** Draft 2020-12 registry, the dialect every schema document in this platform declares. */
    private final SchemaRegistry schemaRegistry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** One parsed schema per document name. A document is read from the classpath once. */
    private final Map<String, Schema> schemasByDocumentName = new ConcurrentHashMap<>();

    /**
     * Takes the producer template Spring Boot builds from the {@code spring.kafka.producer}
     * properties and checks the reliability settings that template carries.
     *
     * @param kafkaTemplate the template that sends every card event to the broker
     * @throws IllegalStateException when the producer does not pin acknowledgement from every
     *         in-sync replica, idempotent production, an in-flight limit of at most
     *         {@value #MAX_IN_FLIGHT_LIMIT}, or a bounded delivery, request and block timeout
     */
    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        requireReliableProducer(kafkaTemplate.getProducerFactory().getConfigurationProperties());
    }

    /**
     * Sends {@code payload} to {@code topic} unchanged and waits for the broker acknowledgement. A
     * broker failure arrives as an unchecked {@code java.util.concurrent.CompletionException}.
     *
     * <p>The message key is {@code aggregateId} itself, so Kafka partitions on the account and
     * every event for one account stays in order.
     *
     * <p>The log record names the event type, the topic and the payload length. It carries no
     * message key, no account identifier and no part of the payload.
     *
     * @throws IllegalArgumentException when {@code topic} or {@code payload} is null, when
     *         {@code aggregateId} is not eleven decimal digits, when the payload is not one JSON
     *         object, when {@code aggregateId} differs from the payload's {@code aggregateId} or
     *         from its {@code accountId} where the document declares one, or when the payload
     *         fails the schema its envelope names
     */
    @Override
    public void publish(String topic, String aggregateId, String payload) {
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
        requireSingleAccountIdentity(aggregateId, event);
        requireValidAgainstSchema(event, payload);
        log.debug("Publishing card event {} to topic {}, payload length {}",
                event.path(EVENT_TYPE).asString(""), topic, payload.length());
        kafkaTemplate.send(topic, aggregateId, payload).join();
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
            throw new IllegalArgumentException(
                    "payload is not JavaScript Object Notation: " + parseFailure.getMessage(),
                    parseFailure);
        }
        if (!event.isObject()) {
            throw new IllegalArgumentException("payload holds one JSON object per event");
        }
        return event;
    }

    /**
     * Checks that the message key and every account identifier the payload carries hold one value.
     *
     * <p>Without this check an event can reach the partition of one account while it names another,
     * which reorders that account's events and misdirects every consumer that maps the identifier
     * onto its own account column.
     *
     * <p>{@code aggregateId} is the single source of account identity and every document declares
     * it. A document that also declares the payload field {@code accountId} must agree with it; a
     * document that single-sources the identifier carries no such field, and its absence is not a
     * disagreement.
     *
     * <p>No message below names a value. The account identifier is the value under check, and a
     * caller that logs the failure would otherwise record it.
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
     * Validates the payload against the versioned schema document its envelope names.
     *
     * @param event   the parsed event, read for {@code eventType} and {@code schemaVersion}
     * @param payload the serialized event, validated as received
     * @throws IllegalArgumentException when the envelope names no document, when the classpath
     *         carries no such document, or when the payload fails it
     */
    private void requireValidAgainstSchema(JsonNode event, String payload) {
        String documentName = schemaDocumentName(event);
        Schema schema = schemasByDocumentName.computeIfAbsent(documentName, this::loadSchema);
        List<Error> errors = schema.validate(payload, InputFormat.JSON);
        if (!errors.isEmpty()) {
            List<String> pointers = errors.stream()
                    .map(error -> error.getInstanceLocation() + " " + error.getMessage())
                    .toList();
            throw new IllegalArgumentException(
                    "The event fails " + documentName + ": " + pointers);
        }
    }

    /**
     * Names the schema document for one event. {@code CardStateChanged} at version 1 names
     * {@code card-state-changed-v1.json}.
     *
     * @param event the parsed event
     * @return the document name, without the classpath directory
     * @throws IllegalArgumentException when the envelope carries no event type or no version
     */
    private static String schemaDocumentName(JsonNode event) {
        String eventType = event.path(EVENT_TYPE).asString("");
        JsonNode version = event.path(SCHEMA_VERSION);
        if (eventType.isEmpty() || !version.isNumber()) {
            throw new IllegalArgumentException("The envelope carries " + EVENT_TYPE + " and "
                    + SCHEMA_VERSION + ", which name the schema document. " + EVENT_TYPE
                    + " reads '" + eventType + "' and " + SCHEMA_VERSION + " reads "
                    + version.asString("") + ".");
        }
        StringBuilder kebab = new StringBuilder(eventType.length() + 8);
        for (int index = 0; index < eventType.length(); index++) {
            char character = eventType.charAt(index);
            if (Character.isUpperCase(character) && index > 0) {
                kebab.append('-');
            }
            kebab.append(Character.toLowerCase(character));
        }
        return kebab.append("-v").append(version.intValue()).append(".json").toString();
    }

    /**
     * Reads one schema document from the classpath.
     *
     * @param documentName the document name, without the classpath directory
     * @return the parsed schema
     * @throws IllegalArgumentException when the classpath carries no such document
     */
    private Schema loadSchema(String documentName) {
        String resource = SCHEMA_DIRECTORY + documentName;
        try (InputStream document =
                KafkaEventPublisher.class.getClassLoader().getResourceAsStream(resource)) {
            if (document == null) {
                throw new IllegalArgumentException("The classpath carries no " + resource
                        + ". com.carddemo:event-contracts ships every schema document.");
            }
            return schemaRegistry.getSchema(document, InputFormat.JSON);
        } catch (java.io.IOException readFailure) {
            throw new IllegalArgumentException("Reading " + resource + " failed", readFailure);
        }
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
