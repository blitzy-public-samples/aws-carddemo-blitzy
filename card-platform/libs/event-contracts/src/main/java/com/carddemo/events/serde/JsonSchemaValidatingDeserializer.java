package com.carddemo.events.serde;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.carddemo.events.EventEnvelope;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
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

import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;

/**
 * Reads one event from Kafka bytes and checks those bytes against the event's schema before it
 * builds a record. A malformed message never becomes a domain object.
 *
 * <p>ADDITIVE IN FULL. No COBOL program and no copybook in this repository defines this class. The
 * reasoning behind the choices this class implements sits in
 * {@code card-platform/docs/decision-log.md} (planned).
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
 * <p>A failure names the schema, counts the violations and lists each failing property as a JSON
 * pointer. No message carries the value that failed, so a full Primary Account Number (PAN) cannot
 * reach a log or a dead-letter record through a failure. This class writes no log line, records no
 * metric, retries nothing and publishes nothing. It throws
 * {@link SerializationException}, and the consumer of each service turns that into a route to the
 * {@code carddemo.dead-letter} topic. For the path each event travels from publish to consume, read
 * {@code card-platform/docs/event-flow.md} (planned).
 *
 * <p>A declined event is ordinary traffic and deserializes like any other. Nothing here treats a
 * decline as a failure.
 *
 * <p>Versions: Java 25, {@code jackson-databind 3.1.4}, {@code json-schema-validator 3.0.6} for
 * JSON Schema Draft 2020-12, and {@code kafka-clients 4.2.1} for the {@link Deserializer}
 * interface. A module descriptor that omits {@code <java.version>25</java.version>} compiles at
 * release 17 with no warning.
 *
 * <p>An instance holds no mutable state, so consumer threads may share one.
 *
 * @param <T> the event this deserializer builds, one of the five records in
 *            {@code com.carddemo.events}
 */
public final class JsonSchemaValidatingDeserializer<T> implements Deserializer<T> {

    /** The property that selects the schema and the record class. */
    private static final String EVENT_TYPE_PROPERTY = "eventType";

    /**
     * The classpath resource holding the schema document of each governed pair of event type and
     * contract version, read from {@link EventSchemas#SCHEMA_DOCUMENTS}.
     *
     * <p>Reading the one table this package shares with {@link JsonSchemaValidatingSerializer} is
     * what keeps the set of event types that may be published and the set that may be consumed from
     * drifting apart. {@code TransactionDeclined} is the one event type with two documents.
     */
    private static final Map<EventSchemas.SchemaKey, String> SCHEMA_DOCUMENTS =
            EventSchemas.SCHEMA_DOCUMENTS;

    /**
     * The record each event type builds. Both sides of each pair are literals.
     *
     * <p>Five of the seven registered event types appear here. {@code AccountStateChanged} and
     * {@code CardUpdated} are records of the account service and the card service, so this module
     * carries their documents and validates against them but names no class for them. A consumer of
     * either passes that record class to {@link #JsonSchemaValidatingDeserializer(Class)}, or asks
     * for {@link JsonNode} and reads the checked tree.
     */
    private static final Map<String, Class<?>> RECORD_TYPES = EventSchemas.RECORD_TYPES;

    /** Reads the {@code eventType} and then the record. Configured once, in a constructor. */
    private final ObjectMapper mapper;

    /** One compiled schema per event type, compiled once and shared across calls. */
    private final Map<EventSchemas.SchemaKey, Schema> schemasByKey;

    /**
     * The one event this instance accepts, or {@code null} when it accepts all five.
     *
     * <p>A consumer subscribed to one event names its record class, and a message carrying another
     * event type then fails instead of returning a value the caller cannot use.
     *
     * <p>{@link JsonNode} names no single event. An instance built for it accepts all five and
     * returns the checked tree instead of a record, which is what a consumer configured through
     * {@code spring.deserializer.value.delegate.class} reads and what a test asserting the wire form
     * reads.
     */
    private final Class<T> expectedType;

    /**
     * Builds a deserializer that accepts all five event types. Kafka calls this constructor by
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
     * @param expectedType the record class this instance builds, one of the five in
     *                     {@code com.carddemo.events}, or {@link JsonNode} to accept all five and
     *                     return the checked tree
     * @throws NullPointerException     when {@code expectedType} is {@code null}
     * @throws IllegalArgumentException when {@code expectedType} is neither one of the five records
     *                                  nor {@link JsonNode}
     * @throws IllegalStateException    when a schema document is missing from the classpath
     */
    public JsonSchemaValidatingDeserializer(Class<T> expectedType) {
        this(defaultMapper(), SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12),
                Objects.requireNonNull(expectedType, "expectedType must be present"));
    }

    /**
     * Builds a deserializer from a supplied mapper and registry, and compiles every governed
     * schema. A
     * test calls this constructor to substitute a mapper. Reading the schema documents here stops a
     * consumer with a packaging fault at startup and not at its first message.
     *
     * @param mapper         the Jackson 3 mapper that reads each event
     * @param schemaRegistry the registry that reads JSON Schema Draft 2020-12
     * @param expectedType   the one record class this instance builds, {@link JsonNode} to return
     *                       the checked tree, or {@code null} to accept all five as records
     * @throws NullPointerException     when {@code mapper} or {@code schemaRegistry} is
     *                                  {@code null}
     * @throws IllegalArgumentException when {@code expectedType} is present and is neither one of
     *                                  the five records nor {@link JsonNode}
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
                    + new java.util.TreeSet<>(EventSchemas.governedEventTypes()) + ", and "
                    + JsonNode.class.getName() + " reads any of them as a tree.");
        }
        this.expectedType = expectedType;

        Map<EventSchemas.SchemaKey, Schema> compiled = new LinkedHashMap<>();
        for (Map.Entry<EventSchemas.SchemaKey, String> document : SCHEMA_DOCUMENTS.entrySet()) {
            compiled.put(document.getKey(), compile(schemaRegistry, document.getValue()));
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
     * @throws SerializationException when the bytes are not JSON, when {@code eventType} selects no
     *                                schema, when the event type is not the one this instance
     *                                expects, when the JSON breaks its schema, or when the checked
     *                                JSON cannot build its record
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
        // The event type and the contract version together select the document. An event
        // published under an older contract stays readable under the document it was published
        // under, and a version this module ships no document for is refused rather than checked
        // against another version's document.
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
        // document and validates against it but names no class to build. A caller that asked for no
        // particular type receives the checked tree, which is the whole event and nothing more.
        boolean treeResult = expectedType == null && recordType == null;

        List<Error> violations;
        try {
            violations = schema.validate(json, InputFormat.JSON);
        } catch (JacksonException cause) {
            throw new SerializationException("Checking " + eventType + " against "
                    + EventSchemas.resourceFor(eventType, schemaVersion)
                    + " could not read the JSON.", cause);
        }
        if (!violations.isEmpty()) {
            throw new SerializationException(describe(eventType, schemaVersion, violations));
        }

        Class<?> boundType = treeForm || treeResult ? JsonNode.class
                : expectedType != null ? expectedType : recordType;
        try {
            return cast(mapper.readValue(json, boundType));
        } catch (JacksonException | IllegalArgumentException cause) {
            throw new SerializationException("The checked JSON of " + eventType
                    + " could not build a " + boundType.getSimpleName() + ".", cause);
        }
    }

    /**
     * The mapper the two short constructors build. An unknown property fails, which agrees with
     * the {@code additionalProperties} of {@code false} every schema document sets. A monetary
     * property arrives as a decimal string and reads through
     * {@link java.math.BigDecimal#BigDecimal(String)}, so the two fractional digits survive and no
     * binary floating point enters. No setting reads a quoted number as a number, so
     * {@code schemaVersion} and {@code riskScore} must arrive as JSON integers.
     */
    private static JsonMapper defaultMapper() {
        // The parser reads under the platform's wire bounds, which cap nesting depth, string
        // length, property-name length and token count while reading rather than after, so a
        // record built to exhaust a parser is refused as it is read.
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(EventWireBounds.streamReadConstraints())
                .build();

        // Binding tolerates a property the record does not declare, and the schema does not.
        // Every shipped document closes its top-level property set and declares one bounded
        // extensions object, so the only undeclared property that reaches binding is that object.
        // Refusing it here would refuse exactly the enriched event it exists to let through, and
        // would put the compatibility guarantee back where it started.
        return JsonMapper.builder(factory)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Compiles one schema document read from the classpath of this class. Nothing is read from a
     * network location or from an absolute path on disk, and no {@code $id} is dereferenced.
     *
     * @throws IllegalStateException when the classpath holds no such resource, or reading it fails
     */
    private static Schema compile(SchemaRegistry schemaRegistry, String resource) {
        try (InputStream document = JsonSchemaValidatingDeserializer.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (document == null) {
                throw new IllegalStateException(
                        "Classpath resource " + resource + " is missing from this module.");
            }
            return schemaRegistry.getSchema(document, InputFormat.JSON);
        } catch (IOException cause) {
            throw new IllegalStateException(
                    "Classpath resource " + resource + " could not be read.", cause);
        }
    }

    /**
     * Shortens an event type for a failure message.
     *
     * <p>The type is a value from the record, so a message that echoed it whole would carry
     * whatever a sender put there into a log line. Thirty-two characters name every governed type
     * and bound what an ungoverned one can write.
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
     * <p>The permissive decode of {@code new String(bytes, UTF_8)} replaces a malformed sequence
     * with a replacement character, which turns bytes that are not an event into a document that
     * parses. Reporting the fault instead refuses the record before it is parsed.
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
     * <p>The five records share no supertype, and {@code eventType} has already selected the class
     * the mapper built, so the narrowing holds. An instance built for {@link JsonNode} asked the
     * mapper for that type, so the narrowing holds there too.
     */
    @SuppressWarnings("unchecked")
    private T cast(Object event) {
        return (T) event;
    }
}
