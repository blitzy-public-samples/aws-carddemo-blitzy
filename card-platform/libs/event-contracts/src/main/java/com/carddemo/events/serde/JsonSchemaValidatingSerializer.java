package com.carddemo.events.serde;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Writes one event as JavaScript Object Notation (JSON) bytes and checks those bytes against the
 * event's schema before returning them. A malformed event never reaches a Kafka topic.
 *
 * <p>ADDITIVE IN FULL. No COBOL program and no copybook in this repository defines this class. The
 * reasoning behind the choices this class implements sits in
 * {@code card-platform/docs/decision-log.md}.
 *
 * <p>Schema selection reads the {@code eventType} property of the JSON the mapper just wrote. The
 * five event types are {@code TransactionAuthorized}, {@code TransactionDeclined},
 * {@code TransactionPosted}, {@code FraudFlagged} and {@code FraudCleared}. A topic name selects
 * nothing: the {@code fraud.assessed} topic carries FraudFlagged and FraudCleared together.
 *
 * <p>The wire form is flat. The five envelope properties sit beside the payload properties in one
 * JSON object. An event nested under an {@code envelope} key therefore fails all five schema
 * documents on the {@code required} array. {@code aggregateId} holds the eleven-digit account
 * identifier from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} and travels as
 * text, so a leading zero survives.
 *
 * <p>A failure names the schema, counts the violations and lists each failing property as a JSON
 * pointer. No message carries the value that failed, so a full Primary Account Number (PAN) cannot
 * reach a log through a failure. This class writes no log line and records no metric.
 *
 * <p>Versions: Java 25, {@code jackson-databind 3.1.4}, {@code json-schema-validator 3.0.6} for
 * JSON Schema Draft 2020-12, and {@code kafka-clients 4.2.1} for the {@link Serializer} interface.
 * A module descriptor that omits {@code <java.version>25</java.version>} compiles at release 17
 * with no warning.
 *
 * <p>An instance holds no mutable state, so producer threads may share one. For the path each event
 * travels from publish to consume, read {@code card-platform/docs/event-flow.md}.
 *
 * @param <T> the event this serializer writes, one of the five records in
 *            {@code com.carddemo.events}
 */
public final class JsonSchemaValidatingSerializer<T> implements Serializer<T> {

    /** The property that selects the schema, and the routing discriminator each event carries. */
    private static final String EVENT_TYPE_PROPERTY = "eventType";

    /**
     * The classpath resource holding each event type's schema document. Both sides of each pair are
     * literals, so renaming either side breaks the build instead of the wire form.
     */
    private static final Map<String, String> SCHEMA_RESOURCES = Map.of(
            "TransactionAuthorized", "schemas/transaction-authorized-v1.json",
            "TransactionDeclined", "schemas/transaction-declined-v1.json",
            "TransactionPosted", "schemas/transaction-posted-v1.json",
            "FraudFlagged", "schemas/fraud-flagged-v1.json",
            "FraudCleared", "schemas/fraud-cleared-v1.json");

    /** Writes the JSON and reads back its {@code eventType}. Configured once, in a constructor. */
    private final ObjectMapper mapper;

    /** One compiled schema per event type, compiled once and shared across calls. */
    private final Map<String, Schema> schemasByEventType;

    /**
     * Builds a serializer with a mapper and a schema registry of its own. Kafka calls this
     * constructor by reflection when a producer names this class in its {@code value.serializer}
     * property.
     *
     * @throws IllegalStateException when a schema document is missing from the classpath
     */
    public JsonSchemaValidatingSerializer() {
        this(defaultMapper(),
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12));
    }

    /**
     * Builds a serializer from a supplied mapper and registry, and compiles all five schemas. A
     * test calls this constructor to substitute a mapper. Reading the schema documents here stops a
     * producer with a packaging fault at startup and not at its first publish.
     *
     * @param mapper         the Jackson 3 mapper that writes each event
     * @param schemaRegistry the registry that reads JSON Schema Draft 2020-12
     * @throws NullPointerException  when either argument is {@code null}
     * @throws IllegalStateException when a schema document is missing from the classpath
     */
    public JsonSchemaValidatingSerializer(ObjectMapper mapper, SchemaRegistry schemaRegistry) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must be present");
        Objects.requireNonNull(schemaRegistry, "schemaRegistry must be present");

        Map<String, Schema> compiled = new LinkedHashMap<>();
        for (Map.Entry<String, String> resource : SCHEMA_RESOURCES.entrySet()) {
            compiled.put(resource.getKey(), compile(schemaRegistry, resource.getValue()));
        }
        this.schemasByEventType = Map.copyOf(compiled);
    }

    /**
     * Writes one event as JSON bytes and checks them against the event's schema.
     *
     * <p>A {@code null} event returns {@code null}, which is Kafka's tombstone contract. The
     * returned bytes are the bytes of the text that was checked, in
     * {@link StandardCharsets#UTF_8} and never in the charset of the host.
     *
     * @param topic the topic the record is bound for. Schema selection ignores it
     * @param data  the event to write, or {@code null}
     * @return the checked JSON as UTF-8 bytes, or {@code null} when {@code data} is {@code null}
     * @throws SerializationException when the mapper cannot write the event, when
     *                                {@code eventType} selects no schema, or when the JSON breaks
     *                                its schema
     */
    @Override
    public byte[] serialize(String topic, T data) {
        if (data == null) {
            return null;
        }

        String json;
        String eventType;
        try {
            json = mapper.writeValueAsString(data);
            eventType = mapper.readTree(json).path(EVENT_TYPE_PROPERTY).stringValue("");
        } catch (JacksonException cause) {
            throw new SerializationException(
                    "Writing " + data.getClass().getName() + " as JSON failed.", cause);
        }

        if (eventType.isBlank()) {
            throw new SerializationException("The JSON written for " + data.getClass().getName()
                    + " carries no eventType property, so no schema selects it.");
        }
        Schema schema = schemasByEventType.get(eventType);
        if (schema == null) {
            throw new SerializationException(
                    "Event type \"" + eventType + "\" has no schema in this module.");
        }

        List<Error> violations;
        try {
            violations = schema.validate(json, InputFormat.JSON);
        } catch (JacksonException cause) {
            throw new SerializationException("Checking " + eventType + " against "
                    + SCHEMA_RESOURCES.get(eventType) + " could not read the JSON.", cause);
        }
        if (!violations.isEmpty()) {
            throw new SerializationException(describe(eventType, violations));
        }
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The mapper the no-argument constructor builds. Dates are written as text, so
     * {@code occurredAt} and {@code assessedAt} carry an ISO-8601 string and never a numeric epoch.
     * No setting quotes an ordinary number, so {@code schemaVersion} and {@code riskScore} stay
     * integers.
     */
    private static JsonMapper defaultMapper() {
        SimpleModule plainDecimals = new SimpleModule("carddemo-plain-decimal-strings");
        plainDecimals.addSerializer(BigDecimal.class, new PlainDecimalStringSerializer());

        return JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .addModule(plainDecimals)
                .build();
    }

    /**
     * Compiles one schema document read from the classpath of this class. Nothing is read from a
     * network location or from an absolute path on disk, and no {@code $id} is dereferenced.
     *
     * @throws IllegalStateException when the classpath holds no such resource, or reading it fails
     */
    private static Schema compile(SchemaRegistry schemaRegistry, String resource) {
        try (InputStream document = JsonSchemaValidatingSerializer.class.getClassLoader()
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
     * The failure text: the event type, the schema document, the count of violations, and the JSON
     * pointer and broken keyword of each failing property. No value from the event appears in it.
     */
    private static String describe(String eventType, List<Error> violations) {
        String properties = violations.stream()
                .map(violation -> pointer(violation) + " (" + violation.getKeyword() + ")")
                .collect(Collectors.joining(", "));
        return eventType + " breaks " + SCHEMA_RESOURCES.get(eventType) + " on "
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
     * Writes a fixed-point amount as a JSON string through {@link BigDecimal#toPlainString()}, so
     * no exponent reaches the wire. Each monetary property of the five schema documents is a
     * string: {@code amount} allows nine digits before the point, from
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
