package com.carddemo.events.serde;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Writes one event as JavaScript Object Notation (JSON) bytes and checks those bytes, the event type
 * and the destination topic before returning them. A malformed or misrouted event never reaches a
 * Kafka topic.
 *
 * <p>No COBOL program and no copybook in this repository defines this class.
 *
 * <p>Three checks run on every call, in this order.
 *
 * <ol>
 * <li>The event must be one of the five records of {@code com.carddemo.events}. The class of the
 * argument selects the event type. The five records of this module resolve through
 * {@link #EVENT_TYPES_BY_CLASS}. A mutation record of a service resolves through its own simple
 * name, which {@link EventContracts#isRegistered(String)} must recognise. An arbitrary object or
 * map whose JSON happens to carry a supported {@code eventType} is therefore rejected before
 * anything is written.</li>
 * <li>{@link EventContracts} must bind that event type to the topic the caller named. A producer
 * that sends an approval to the declined topic therefore fails here rather than at a consumer.
 * {@code FraudFlagged} and {@code FraudCleared} both bind to {@code fraud.assessed}, which is the
 * one topic two event types share. A deployment that renames a topic passes that name through
 * {@link #TOPIC_OVERRIDE_PREFIX} in the producer properties.</li>
 * <li>The written JSON must carry no property {@link SensitiveEventProperties} forbids. A card
 * number, a verification value or a government identifier therefore cannot travel, even where a
 * schema would tolerate an undeclared property.</li>
 * <li>The written JSON must satisfy the schema document of that event type at the contract
 * version the event itself declares, which {@link EventContracts#violationsOf(String, String)}
 * selects and checks. {@code TransactionDeclined} publishes two contracts, and validating one
 * against the other's document would report a violation naming the wrong contract.</li>
 * <li>The finished document must fit {@link EventWireBounds#MAX_EVENT_BYTES}, which is also the
 * width of the {@code payload} column of every {@code outbox_event} table, so an event that
 * serializes fits the row that carries it.</li>
 * </ol>
 *
 * <p>The wire form is flat. The five envelope properties sit beside the payload properties in one
 * JSON object. An event nested under an {@code envelope} key therefore fails its document on the
 * {@code required} array. {@code aggregateId} holds the eleven-digit account identifier from
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} and travels as text, so a
 * leading zero survives. Each record holds its own {@code accountId} equal to that value, checked in
 * its canonical constructor, because JSON Schema Draft 2020-12 declares no keyword comparing one
 * property to another.
 *
 * <p>A failure names the public event type, the schema document, the count of violations and each
 * failing property as a JSON pointer with the keyword it broke. No message carries a value from the
 * event and no message names an implementation class, so a full Primary Account Number (PAN) cannot
 * reach a log through a failure. This class writes no log line and records no metric.
 *
 * <p>Two bounds apply beside the schema. The parser that reads the {@code eventType} back runs
 * under {@link EventWireBounds#streamReadConstraints()}. The finished document is refused when it
 * exceeds {@link EventWireBounds#MAX_EVENT_BYTES}, the width of the {@code payload} column of
 * every {@code outbox_event} table. An event that serializes therefore fits the row that carries
 * it. Every shipped schema closes its property set, so an undeclared field cannot ride along
 * inside a known event type.
 *
 * <p>Versions: Java 25, {@code jackson-databind 3.1.5}, {@code json-schema-validator 3.0.6} for
 * JSON Schema Draft 2020-12, and {@code kafka-clients 4.2.1} for the {@link Serializer} interface.
 * A module descriptor that omits {@code <java.version>25</java.version>} compiles at release 17
 * with no warning.
 *
 * <p>An instance holds no mutable state after {@link #configure(Map, boolean)} returns, so producer
 * threads may share one. This class is the publish side, and
 * {@link JsonSchemaValidatingDeserializer} is the consume side, so one payload is measured against
 * one document at both ends.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param <T> the event this serializer writes. Either one of the five records in
 *            {@code com.carddemo.events}, or a mutation event record of the service that owns the
 *            aggregate. Its {@code eventType} names a schema in {@link EventSchemas}
 */
public final class JsonSchemaValidatingSerializer<T> implements Serializer<T> {

    /**
     * Prefix of the producer property that renames the topic of one event type.
     *
     * <p>A property named {@code carddemo.event.topic.TransactionAuthorized} states which topic this
     * deployment publishes that event to. The default topic of the event type stays accepted, so a
     * deployment that keeps the defaults configures nothing.
     */
    public static final String TOPIC_OVERRIDE_PREFIX = "carddemo.event.topic.";

    /** The property that names the event type inside the written JSON. */
    private static final String EVENT_TYPE_PROPERTY = "eventType";

    /**
     * The concrete event classes this serializer accepts, each mapped to its event type.
     *
     * <p>The map is the type guard. A class absent from it is refused, so no map, no
     * loosely-typed holder and no unrelated record can present itself as a platform event.
     */
    private static final Map<Class<?>, String> EVENT_TYPES_BY_CLASS = Map.of(
            TransactionAuthorized.class, EventContracts.TRANSACTION_AUTHORIZED,
            TransactionDeclined.class, EventContracts.TRANSACTION_DECLINED,
            TransactionPosted.class, EventContracts.TRANSACTION_POSTED,
            FraudFlagged.class, EventContracts.FRAUD_FLAGGED,
            FraudCleared.class, EventContracts.FRAUD_CLEARED,
            DeadLetterEnvelope.class, EventContracts.DEAD_LETTER);

    /** Writes the JSON and reads back its {@code eventType}. Configured once, in a constructor. */
    private final ObjectMapper mapper;

    /** Each event type mapped to the topic this deployment publishes it to, or empty. */
    private Map<String, String> configuredTopics = Map.of();

    /**
     * Builds a serializer with a mapper of its own. Kafka calls this constructor by reflection when
     * a producer names this class in its {@code value.serializer} property.
     */
    public JsonSchemaValidatingSerializer() {
        this(defaultMapper());
    }

    /**
     * Builds a serializer from a supplied mapper. A test calls this constructor to substitute one.
     *
     * @param mapper the Jackson 3 mapper that writes each event
     * @throws NullPointerException when {@code mapper} is {@code null}
     */
    public JsonSchemaValidatingSerializer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must be present");

    }

    /**
     * Reads the topic each event type is configured for, so a renamed topic still validates.
     *
     * <p>Kafka calls this method once, before the first publish. A property named
     * {@link #TOPIC_OVERRIDE_PREFIX} followed by an event type supplies that topic. Every other
     * property is ignored.
     *
     * @param configs the producer properties
     * @param isKey   {@code true} when this instance serializes keys. This serializer writes values,
     *                and the flag changes nothing
     */
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        if (configs == null) {
            return;
        }

        Map<String, String> named = new LinkedHashMap<>();
        for (String eventType : EventContracts.eventTypes()) {
            Object configured = configs.get(TOPIC_OVERRIDE_PREFIX + eventType);
            if (configured != null && !String.valueOf(configured).isBlank()) {
                named.put(eventType, String.valueOf(configured).trim());
            }
        }
        this.configuredTopics = Map.copyOf(named);
    }

    /**
     * Writes one event as JSON bytes and checks the event type, the topic and the bytes.
     *
     * <p>A {@code null} event returns {@code null}, which is Kafka's tombstone contract. The
     * returned bytes are the bytes of the text that was checked, in
     * {@link StandardCharsets#UTF_8} and never in the charset of the host.
     *
     * @param topic the topic the record is bound for, checked against the event type
     * @param data  the event to write, or {@code null}
     * @return the checked JSON as UTF-8 bytes, or {@code null} when {@code data} is {@code null}
     * @throws SerializationException on any of five conditions. The argument is not a registered
     *                                event record. The event type does not belong on {@code topic}.
     *                                The mapper cannot write the event. The written JSON names
     *                                another event type. The JSON breaks its schema
     */
    @Override
    public byte[] serialize(String topic, T data) {
        if (data == null) {
            return null;
        }

        String eventType = EVENT_TYPES_BY_CLASS.get(data.getClass());
        if (eventType == null && EventContracts.isRegistered(data.getClass().getSimpleName())) {
            // A mutation event is a record of the service that publishes it, so this module holds
            // its document but names no class for it. The class name is the event type.
            eventType = data.getClass().getSimpleName();
        }
        if (eventType == null) {
            throw new SerializationException("This serializer writes only a record whose simple "
                    + "name names a registered event type. The registered types are "
                    + EventContracts.eventTypes() + ", and the supplied value names none of them.");
        }
        if (!EventContracts.isBoundToTopic(eventType, topic, configuredTopics.get(eventType))) {
            throw new SerializationException(eventType + " belongs on topic "
                    + EventContracts.defaultTopicFor(eventType) + " and the supplied topic is \""
                    + topic + "\". Rename a topic through the producer property "
                    + TOPIC_OVERRIDE_PREFIX + eventType + ".");
        }

        String json;
        JsonNode document;
        String written;
        int schemaVersion;
        try {
            json = mapper.writeValueAsString(data);
            document = mapper.readTree(json);
            written = document.path(EVENT_TYPE_PROPERTY).stringValue("");
            schemaVersion = document.path(EventSchemas.SCHEMA_VERSION_PROPERTY)
                    .asInt(EventEnvelope.SCHEMA_VERSION);
        } catch (JacksonException cause) {
            throw new SerializationException("Writing " + eventType + " as JSON failed.", cause);
        }
        if (!eventType.equals(written)) {
            throw new SerializationException("The JSON written for " + eventType + " names the "
                    + "event type \"" + written + "\", so the record and its envelope disagree.");
        }

        // One tree, three readers. The envelope check above, the two screens below and the schema
        // check after them all read the same bytes, and parsing them once is the only way a screen
        // and the document can never disagree about what was published.
        JsonNode screened = document;
        String forbidden = SensitiveEventProperties.firstForbiddenProperty(screened);
        if (forbidden != null) {
            throw new SerializationException("The JSON written for " + eventType
                    + " carries the property \"" + forbidden
                    + "\", which no event may carry. See SensitiveEventProperties.");
        }

        String sensitive = SensitiveEventProperties.firstSensitiveValue(screened);
        if (sensitive != null) {
            throw new SerializationException("The JSON written for " + eventType
                    + " carries a card number or a government identifier in the free-text property "
                    + "\"" + sensitive + "\". No event may carry either. "
                    + "See SensitiveEventProperties.");
        }

        List<String> violations;
        try {
            violations = EventContracts.violationsOf(eventType, document);
        } catch (IllegalStateException ungoverned) {
            // The pair of event type and contract version selects the document, and this event
            // names a pair no document describes. Refusing it is the point: checking version 2 of
            // a decline against the version 1 document would report the wrong contract.
            throw new SerializationException(eventType + " carries "
                    + EventSchemas.SCHEMA_VERSION_PROPERTY + " " + schemaVersion
                    + ", which this module governs no document for. The governed version"
                    + (EventSchemas.governedVersions(eventType).size() == 1 ? " is " : "s are ")
                    + EventSchemas.governedVersions(eventType) + ".", ungoverned);
        }
        if (!violations.isEmpty()) {
            throw new SerializationException(
                    EventContracts.describeViolations(eventType, violations));
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > EventWireBounds.MAX_EVENT_BYTES) {
            throw new SerializationException(eventType + " serializes to " + bytes.length
                    + " bytes, which exceeds the " + EventWireBounds.MAX_EVENT_BYTES
                    + " byte ceiling one event of this platform may occupy. The event was not"
                    + " published. No value from it appears in this message.");
        }
        return bytes;
    }

    /**
     * The event types this serializer accepts, sorted so a failure message reads the same each time.
     *
     * @return the five event types, comma separated
     */
    private static String supportedEventTypes() {
        return EVENT_TYPES_BY_CLASS.values().stream().sorted().reduce((left, right)
                -> left + ", " + right).orElse("");
    }

    /**
     * The mapper the no-argument constructor builds. Dates are written as text, so
     * {@code occurredAt} and {@code assessedAt} carry an ISO-8601 string and never a numeric epoch.
     * No setting quotes an ordinary number, so {@code schemaVersion} and {@code riskScore} stay
     * integers.
     *
     * @return the configured mapper
     */
    private static JsonMapper defaultMapper() {
        SimpleModule plainDecimals = new SimpleModule("carddemo-plain-decimal-strings");
        plainDecimals.addSerializer(BigDecimal.class, new PlainDecimalStringSerializer());

        return JsonMapper.builder(JsonFactory.builder()
                        .streamReadConstraints(EventWireBounds.streamReadConstraints())
                        .build())
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .addModule(plainDecimals)
                .build();
    }

    /**

     * Writes a fixed-point amount as a JSON string through {@link BigDecimal#toPlainString()}, so
     * no exponent reaches the wire. Each monetary property of the schema documents is a string:
     * {@code amount} allows nine digits before the point, from
     * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}, and {@code newBalance}
     * allows ten.
     */
    private static final class PlainDecimalStringSerializer extends ValueSerializer<BigDecimal> {

        @Override
        public void serialize(BigDecimal value, JsonGenerator generator,
                SerializationContext context) {
            generator.writeString(value.toPlainString());
        }
    }
}
