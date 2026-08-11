package com.carddemo.events.serde;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.carddemo.events.EventEnvelope;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads one event from Kafka bytes and checks those bytes against the event's schema before it
 * builds a record. A malformed message never becomes a domain object.
 *
 * <p>No COBOL program and no copybook in this repository defines this class.
 *
 * <p>Schema selection reads the {@code eventType} property of the inbound JavaScript Object
 * Notation (JSON) document, and the same property selects the record class to build. A topic name
 * selects nothing: the {@code fraud.assessed} topic carries {@code FraudFlagged} and
 * {@code FraudCleared} together, so only {@code eventType} tells the two apart. The five event
 * types are {@code TransactionAuthorized}, {@code TransactionDeclined}, {@code TransactionPosted},
 * {@code FraudFlagged} and {@code FraudCleared}.
 *
 * <p>The wire form is flat. The five envelope properties sit beside the payload properties in one
 * JSON object, and every schema document sets {@code additionalProperties} to {@code false}. An
 * event nested under an {@code envelope} key therefore fails its document twice: the five envelope
 * properties are missing from the top level, and {@code envelope} is a property no document
 * declares. {@code aggregateId} holds the eleven-digit account identifier from
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} and travels as text, so a
 * leading zero survives.
 *
 * <p>Two properties carry a 26-character COBOL timestamp as plain text, and neither is ISO-8601.
 * {@code authorizedAt} on {@code TransactionAuthorized} holds {@code TRAN-ORIG-TS} from
 * {@code app/cpy/CVTRA05Y.cpy:L16}, spelled {@code YYYY-MM-DD HH:MM:SS.ffffff}. {@code postedAt} on
 * {@code TransactionPosted} holds {@code TRAN-PROC-TS} from {@code app/cpy/CVTRA05Y.cpy:L17},
 * spelled {@code YYYY-MM-DD-HH.MM.SS.NN0000}. Both bind to {@link String}, and this class neither
 * parses nor reformats them.
 *
 * <p>A failure names the schema, counts the violations and lists each failing property as a JSON
 * pointer. No message carries the value that failed, so a full Primary Account Number (PAN) cannot
 * reach a log or a dead-letter record through a failure. This class writes no log line, records no
 * metric, retries nothing and publishes nothing. It throws {@link SerializationException}, and the
 * consumer of each service turns that into a route to the {@code carddemo.dead-letter} topic.
 *
 * <p>A declined event is ordinary traffic and deserializes like any other. Nothing here treats a
 * decline as a failure.
 *
 * <p>Versions: Java 25, {@code jackson-databind 3.1.5}, {@code json-schema-validator 3.0.6} for
 * JSON Schema Draft 2020-12, and {@code kafka-clients 4.2.1} for the {@link Deserializer}
 * interface. A module descriptor that omits {@code <java.version>25</java.version>} compiles at
 * release 17 with no warning.
 *
 * <p>An instance holds no mutable state, so consumer threads may share one.
 *
 * @param <T> the event this deserializer builds: one of the six records of
 *            {@code com.carddemo.events} that {@link EventSchemas#RECORD_TYPES} names, a mutation
 *            event record of the service that owns the aggregate, or {@link JsonNode} to receive
 *            the checked tree
 */
public final class JsonSchemaValidatingDeserializer<T> implements Deserializer<T> {

    /** The property that selects the schema and the record class. */
    private static final String EVENT_TYPE_PROPERTY = "eventType";

    /**
     * The classpath resource holding the schema document of each governed pair of event type and
     * contract version, read from {@link EventSchemas#SCHEMA_DOCUMENTS}.
     *
     * <p>This table is the one {@link JsonSchemaValidatingSerializer} reads, so the set of event
     * types that may be published equals the set that may be consumed. Which types carry more than
     * one document is read from the table itself through {@link EventSchemas#governedVersions},
     * never from a figure written here, because a figure in a comment cannot move when a version
     * ships.
     */
    private static final Map<EventSchemas.SchemaKey, String> SCHEMA_DOCUMENTS =
            EventSchemas.SCHEMA_DOCUMENTS;

    /**
     * The record each event type builds. Both sides of each pair are literals.
     *
     * <p>The event types whose records live in this module appear here, together with
     * {@code DeadLetterEnvelope}. {@code AccountStateChanged}, {@code CustomerContextChanged} and
     * {@code CardUpdated} are records of the account service and the card service, so this module
     * carries their documents and validates against them but names no class for them. A consumer of
     * any of the three passes that record class to
     * {@link #JsonSchemaValidatingDeserializer(Class)}, or asks for {@link JsonNode} and reads the
     * checked tree. {@link EventSchemas#RECORD_TYPES} is the table itself, so a type added there is
     * covered here without a figure in this comment moving.
     */
    private static final Map<String, Class<?>> RECORD_TYPES = EventSchemas.RECORD_TYPES;

    /** Reads the {@code eventType} and then the record. Configured once, in a constructor. */
    private final ObjectMapper mapper;

    /** One compiled schema per event type, compiled once and shared across calls. */
    private final Map<EventSchemas.SchemaKey, Schema> schemasByKey;

    /**
     * The one event this instance accepts, or {@code null} when it accepts every governed type.
     *
     * <p>A consumer subscribed to one event names its record class, and a message carrying another
     * event type then fails instead of returning a value the caller cannot use.
     *
     * <p>{@link JsonNode} names no single event. An instance built for it accepts every governed
     * type and returns the checked tree instead of a record. A consumer configured through
     * {@code spring.deserializer.value.delegate.class} reads that tree, and so does a test
     * asserting the wire form.
     */
    private final Class<T> expectedType;

    /**
     * Builds a deserializer that accepts every governed event type. Kafka calls this constructor by
     * reflection when a consumer names this class in its {@code value.deserializer} property.
     *
     * @throws IllegalStateException when a schema document is missing from the classpath
     */
    public JsonSchemaValidatingDeserializer() {
        this(defaultMapper(), SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12),
                null);
    }

    /**
     * Builds a deserializer that accepts one event type.
     *
     * <p>A consumer of the {@code fraud.assessed} topic uses this constructor to read one of the
     * two events that topic carries.
     *
     * @param expectedType the record class this instance builds, one whose simple name
     *                     {@link EventSchemas#governedEventTypes()} holds, or {@link JsonNode} to
     *                     accept every governed type and return the checked tree
     * @throws NullPointerException     when {@code expectedType} is {@code null}
     * @throws IllegalArgumentException when {@code expectedType} names no governed event type and
     *                                  is not {@link JsonNode}
     * @throws IllegalStateException    when a schema document is missing from the classpath
     */
    public JsonSchemaValidatingDeserializer(Class<T> expectedType) {
        this(defaultMapper(), SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12),
                Objects.requireNonNull(expectedType, "expectedType must be present"));
    }

    /**
     * Builds a deserializer from a supplied mapper and registry, and compiles every governed
     * schema. A test calls this constructor to substitute a mapper. Reading the schema documents
     * here stops a consumer with a packaging fault at startup and not at its first message.
     *
     * @param mapper         the Jackson 3 mapper that reads each event
     * @param schemaRegistry the registry that reads JSON Schema Draft 2020-12
     * @param expectedType   the one record class this instance builds, {@link JsonNode} to return
     *                       the checked tree, or {@code null} to accept every governed type
     * @throws NullPointerException     when {@code mapper} or {@code schemaRegistry} is
     *                                  {@code null}
     * @throws IllegalArgumentException when {@code expectedType} is present, names no governed
     *                                  event type and is not {@link JsonNode}
     * @throws IllegalStateException    when a schema document is missing from the classpath
     */
    public JsonSchemaValidatingDeserializer(ObjectMapper mapper, SchemaRegistry schemaRegistry,
            Class<T> expectedType) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must be present");
        Objects.requireNonNull(schemaRegistry, "schemaRegistry must be present");

        if (expectedType != null && !JsonNode.class.equals(expectedType)
                && !EventSchemas.governedEventTypes().contains(expectedType.getSimpleName())) {
            throw new IllegalArgumentException(expectedType.getName()
                    + " names no registered event type. The registered types are "
                    + new TreeSet<>(EventSchemas.governedEventTypes()) + ", and "
                    + JsonNode.class.getName() + " reads any of them as a tree.");
        }
        this.expectedType = expectedType;

        Map<EventSchemas.SchemaKey, Schema> compiled = new LinkedHashMap<>();
        for (Map.Entry<EventSchemas.SchemaKey, String> document : SCHEMA_DOCUMENTS.entrySet()) {
            compiled.put(document.getKey(),
                    EventSchemas.compile(schemaRegistry, document.getValue()));
        }
        this.schemasByKey = Map.copyOf(compiled);
    }

    /**
     * Checks inbound bytes against the event's schema and then builds the record.
     *
     * <p>{@code null} bytes return {@code null}, which is Kafka's tombstone contract. An empty
     * array is not {@code null}: it cannot parse, so it fails. The bytes are read as
     * {@link StandardCharsets#UTF_8} and never in the charset of the host. The check runs before
     * the record is built, so a schema violation is reported ahead of any binding failure.
     *
     * @param topic the topic the record arrived on. Schema selection ignores it
     * @param data  the event as UTF-8 bytes, or {@code null}
     * @return the event, or {@code null} when {@code data} is {@code null}
     * @throws SerializationException on any of five faults. The bytes are not JSON this platform
     *                                reads. {@code eventType} selects no schema. The event type is
     *                                not the one this instance expects. The JSON breaks its schema.
     *                                The checked JSON cannot build its record
     */
    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }

        if (data.length > EventWireBounds.MAX_EVENT_BYTES) {
            throw new SerializationException("A record of " + data.length + " bytes from topic "
                    + topic + " exceeds the " + EventWireBounds.MAX_EVENT_BYTES
                    + " byte ceiling one event of this platform may occupy, so it was refused"
                    + " before it was parsed.");
        }

        String json = decodeStrictUtf8(topic, data);
        JsonNode tree;
        try {
            tree = mapper.readTree(json);
        } catch (JacksonException cause) {
            throw new SerializationException("Reading " + data.length
                    + " bytes from topic " + topic + " as JSON failed.", cause);
        }
        if (!tree.isObject()) {
            throw new SerializationException("The JSON read from topic " + topic
                    + " is not a JSON object, so it carries no envelope and no schema selects it.");
        }
        String eventType = tree.path(EVENT_TYPE_PROPERTY).stringValue("");

        if (eventType.isBlank()) {
            throw new SerializationException("The JSON read from topic " + topic
                    + " carries no eventType property, so no schema selects it.");
        }
        // The event type and the contract version together select the document, so an event
        // published under an older contract is read under that contract. A version this module
        // ships no document for is refused.
        int schemaVersion = tree.path(EventSchemas.SCHEMA_VERSION_PROPERTY)
                .asInt(EventEnvelope.SCHEMA_VERSION);
        Schema schema = schemasByKey.get(new EventSchemas.SchemaKey(eventType, schemaVersion));
        Class<?> recordType = RECORD_TYPES.get(eventType);
        if (!EventSchemas.governedEventTypes().contains(eventType)) {
            throw new SerializationException("Event type \"" + describeEventType(eventType)
                    + "\" has no schema in this module.");
        }
        if (schema == null) {
            throw new SerializationException(eventType + " carries "
                    + EventSchemas.SCHEMA_VERSION_PROPERTY + " " + schemaVersion
                    + ", and this module governs version"
                    + (EventSchemas.governedVersions(eventType).size() == 1 ? " " : "s ")
                    + EventSchemas.governedVersions(eventType)
                    + " of it, so the record was refused before it was validated.");
        }
        boolean treeForm = JsonNode.class.equals(expectedType);
        if (expectedType != null && !treeForm
                && !expectedType.getSimpleName().equals(eventType)) {
            throw new SerializationException("This deserializer builds "
                    + expectedType.getSimpleName() + " and the message carries " + eventType + ".");
        }
        // A mutation event is a record of the service that publishes it, so this module holds its
        // document but names no class to build. A caller that named no type receives the checked
        // tree.
        boolean treeResult = expectedType == null && recordType == null;

        // The tree read above is what the document governs. Handing the text to the validator
        // instead would parse the same bytes a second time, and a record this deserializer already
        // parsed cannot parse differently the second time.
        List<Error> violations;
        try {
            violations = schema.validate(tree);
        } catch (JacksonException cause) {
            throw new SerializationException("Checking " + eventType + " against "
                    + EventSchemas.resourceFor(eventType, schemaVersion)
                    + " could not read the JSON.", cause);
        }
        if (!violations.isEmpty()) {
            throw new SerializationException(describe(eventType, schemaVersion, violations));
        }

        // The schema closes the top-level property set, and the two screens below cover what a
        // pattern cannot: the extensions object is open by design, and a free-text property is
        // bounded by length rather than by shape. A record either screen refuses reaches the
        // dead-letter route and no consumer.
        String forbidden = SensitiveEventProperties.firstForbiddenProperty(tree);
        if (forbidden != null) {
            throw new SerializationException("The JSON read from topic " + topic + " carries the "
                    + "property \"" + forbidden + "\", which no event may carry. "
                    + "See SensitiveEventProperties.");
        }
        String sensitive = SensitiveEventProperties.firstSensitiveValue(tree);
        if (sensitive != null) {
            throw new SerializationException("The JSON read from topic " + topic + " carries a card "
                    + "number or a government identifier in the free-text property \"" + sensitive
                    + "\". No event may carry either. See SensitiveEventProperties.");
        }

        Class<?> boundType = treeForm || treeResult ? JsonNode.class
                : expectedType != null ? expectedType : recordType;
        if (JsonNode.class.equals(boundType)) {
            // The caller asked for the tree, and the checked tree is the tree. Reading the text
            // again to answer with an equal one would parse the record a third time.
            return cast(tree);
        }
        try {
            return cast(mapper.treeToValue(tree, boundType));
        } catch (JacksonException | IllegalArgumentException cause) {
            throw new SerializationException("The checked JSON of " + eventType
                    + " could not build a " + boundType.getSimpleName() + ".", cause);
        }
    }

    /**
     * The mapper the two short constructors build. Binding accepts a property the record does not
     * declare, and each schema document closes the property set instead. A monetary property arrives
     * as a decimal string and reads through {@link java.math.BigDecimal#BigDecimal(String)}, so the
     * two fractional digits survive and no binary floating point enters. No setting reads a quoted
     * number as a number, so {@code schemaVersion} and {@code riskScore} must arrive as JSON
     * integers.
     */
    private static JsonMapper defaultMapper() {
        // The parser caps nesting depth, string length, property-name length and token count as it
        // reads, so an oversized record is refused during the read and not after it.
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(EventWireBounds.streamReadConstraints())
                .build();

        // The schema closes the property set, not the binding. Every shipped document sets
        // additionalProperties to false and declares one bounded extensions object, which no record
        // declares, so that object is the only undeclared property binding ever sees.
        return JsonMapper.builder(factory)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Shortens an event type for a failure message.
     *
     * <p>The value arrives from the record, and thirty-two characters name every governed type in
     * full. A longer value is cut, so a failure message carries a bounded amount of what a sender
     * wrote.
     *
     * @param eventType the value the record carried
     * @return the value, or its first thirty-two characters followed by an ellipsis
     */
    private static String describeEventType(String eventType) {
        int limit = 32;
        return eventType.length() <= limit ? eventType : eventType.substring(0, limit) + "...";
    }

    /**
     * Decodes the record bytes as strict UTF-8.
     *
     * <p>The decoder reports a malformed sequence instead of substituting a replacement character,
     * so bytes that are not UTF-8 are refused before they are parsed.
     *
     * @param topic the topic the record arrived on, named in the failure
     * @param data  the record bytes
     * @return the decoded text
     * @throws SerializationException when the bytes are not well-formed UTF-8
     */
    private static String decodeStrictUtf8(String topic, byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException cause) {
            throw new SerializationException("A record of " + data.length + " bytes from topic "
                    + topic + " is not well-formed UTF-8, so it was refused before it was parsed.",
                    cause);
        }
    }

    /**
     * The failure text: the event type, the schema document, the count of violations, and the JSON
     * pointer and broken keyword of each failing property. No value from the event appears in it.
     */
    private static String describe(String eventType, int schemaVersion,
            List<Error> violations) {
        String properties = violations.stream()
                .map(violation -> pointer(violation) + " (" + violation.getKeyword() + ")")
                .collect(Collectors.joining(", "));
        return eventType + " breaks " + EventSchemas.resourceFor(eventType, schemaVersion) + " on "
                + violations.size() + (violations.size() == 1 ? " property: " : " properties: ")
                + properties;
    }

    /**
     * The JSON pointer of the property one violation reports. A missing property is reported
     * against the object that should hold it, with the property name beside it, so the two are
     * joined here. Any other violation already points at the property. A result reads
     * {@code /maskedCardNumber} at the top level and {@code /triggeredRules/1} inside an array.
     */
    private static String pointer(Error violation) {
        String location = violation.getInstanceLocation().toString();
        String property = violation.getProperty();
        if (property == null || property.isBlank()) {
            return location.isEmpty() ? "/" : location;
        }
        return location + "/" + property;
    }

    /**
     * Narrows one built record to the type this deserializer returns.
     *
     * <p>The event records share no supertype. {@code eventType} has already selected the class the
     * mapper built, and an instance built for {@link JsonNode} asked the mapper for that type, so
     * the narrowing holds in both cases.
     */
    @SuppressWarnings("unchecked")
    private T cast(Object event) {
        return (T) event;
    }
}
