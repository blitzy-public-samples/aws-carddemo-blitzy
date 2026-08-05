package com.carddemo.events.serde;

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

import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks one already-serialized event against the schema its own {@code eventType} names.
 *
 * <p>No COBOL program and no copybook defines this class.
 *
 * <p>This is the gate for a publisher that holds an event as text rather than as an object. The
 * transactional outbox produces exactly that: the {@code payload} column holds one serialized event,
 * and the relay publishes those bytes unchanged. {@link JsonSchemaValidatingSerializer} cannot help
 * there, because handing it a {@link String} would write a JSON string literal rather than the object
 * the string already holds.
 *
 * <p>Before this class existed, a service in that position loaded schema documents itself and derived
 * a document name by lower-casing its {@code eventType}. Two gates then decided what a valid event
 * was, they could disagree, and neither the platform size ceiling nor the governed-type list applied
 * to the second one. This class removes the second gate: it reads
 * {@link EventSchemas#SCHEMA_RESOURCES}, the same table both serde classes read, and applies the same
 * {@link EventWireBounds} limits.
 *
 * <p>The checks run in the same order the consume side uses, each before the work it protects: the
 * byte ceiling, then the parse under the platform's parser limits, then the governed event type, then
 * the contract version, and only then the schema itself.
 *
 * <p>No failure message carries a value from the event. A message names the event type, the schema
 * document, the count of violations and the JSON pointer and broken keyword of each failing property.
 *
 * <p>One instance compiles every governed schema once and holds no mutable state afterwards, so
 * threads may share one. {@link #shared()} returns the instance a service normally uses.
 */
public final class EventJsonValidator {

    /** The instance a service uses, compiled once when this class is first touched. */
    private static final EventJsonValidator SHARED = new EventJsonValidator();

    /** One compiled schema per governed pair of event type and contract version. */
    private final Map<EventSchemas.SchemaKey, Schema> schemasByKey;

    /** Reads the text under {@link EventWireBounds#streamReadConstraints()}. */
    private final JsonMapper mapper;

    /**
     * Compiles every schema {@link EventSchemas#SCHEMA_DOCUMENTS} governs, one per pair of event
     * type and contract version.
     *
     * @throws IllegalStateException when a schema document is missing from the classpath
     */
    public EventJsonValidator() {
        SchemaRegistry registry =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

        Map<EventSchemas.SchemaKey, Schema> compiled = new LinkedHashMap<>();
        for (Map.Entry<EventSchemas.SchemaKey, String> document
                : EventSchemas.SCHEMA_DOCUMENTS.entrySet()) {
            compiled.put(document.getKey(),
                    EventSchemas.compile(registry, document.getValue()));
        }
        this.schemasByKey = Map.copyOf(compiled);
        this.mapper = JsonMapper.builder(JsonFactory.builder()
                        .streamReadConstraints(EventWireBounds.streamReadConstraints())
                        .build())
                .build();
    }

    /**
     * Returns the shared instance, which has every governed schema already compiled.
     *
     * @return one instance for the whole process
     */
    public static EventJsonValidator shared() {
        return SHARED;
    }

    /**
     * Checks one serialized event and returns it parsed.
     *
     * <p>The returned tree lets a caller read the envelope without parsing the text a second time. A
     * caller that only needs the check may discard it.
     *
     * @param json one event serialized as JavaScript Object Notation
     * @return the parsed event
     * @throws NullPointerException     when {@code json} is {@code null}
     * @throws IllegalArgumentException when the text exceeds
     *                                 {@link EventWireBounds#MAX_EVENT_BYTES}, is not one readable
     *                                 JSON object inside the platform's parser limits, names no
     *                                 governed event type, carries another contract version, or
     *                                 breaks its schema
     */
    public JsonNode validate(String json) {
        Objects.requireNonNull(json, "json holds one serialized event");

        if (json.isBlank()) {
            throw new IllegalArgumentException(
                    "json holds one serialized event and is blank");
        }
        // A character count is a lower bound on the byte count for UTF-8, so an oversized text is
        // refused here and the exact byte count is checked again where the bytes are produced.
        if (json.length() > EventWireBounds.MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("An event of " + json.length()
                    + " characters exceeds the " + EventWireBounds.MAX_EVENT_BYTES
                    + " byte ceiling one event of this platform may occupy.");
        }

        JsonNode event;
        try {
            event = mapper.readTree(json);
        } catch (JacksonException cause) {
            throw new IllegalArgumentException("An event of " + json.length()
                    + " characters is not JSON this platform reads inside its parser limits.",
                    cause);
        }
        if (!event.isObject()) {
            throw new IllegalArgumentException("An event is one JSON object carrying the five"
                    + " envelope properties beside its payload properties.");
        }

        String eventType = event.path(EventSchemas.EVENT_TYPE_PROPERTY).stringValue("");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("An event carries "
                    + EventSchemas.EVENT_TYPE_PROPERTY
                    + ", which names its schema document, and this one carries none.");
        }
        if (!EventSchemas.governedEventTypes().contains(eventType)) {
            throw new IllegalArgumentException("Event type \"" + shorten(eventType)
                    + "\" is not one com.carddemo:event-contracts governs, so no schema document"
                    + " describes it. The governed types are " + EventSchemas.governedEventTypes()
                    + ".");
        }

        // The version selects the document. An event stays valid under the contract it was published
        // under, so an older version is read rather than refused, and a version no document
        // describes is refused rather than validated against the wrong contract.
        int schemaVersion = event.path(EventSchemas.SCHEMA_VERSION_PROPERTY).asInt(0);
        Schema schema = schemasByKey.get(new EventSchemas.SchemaKey(eventType, schemaVersion));
        if (schema == null) {
            throw new IllegalArgumentException(eventType + " carries "
                    + EventSchemas.SCHEMA_VERSION_PROPERTY + " " + schemaVersion
                    + " and this platform governs version"
                    + (EventSchemas.governedVersions(eventType).size() == 1 ? " " : "s ")
                    + EventSchemas.governedVersions(eventType) + " of it.");
        }

        List<Error> violations = schema.validate(json, InputFormat.JSON);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(describe(eventType, schemaVersion, violations));
        }
        return event;
    }

    /**
     * The failure text: the event type, the schema document, the count of violations, and the JSON
     * pointer and broken keyword of each failing property. No value from the event appears in it.
     */
    private static String describe(String eventType, int schemaVersion, List<Error> violations) {
        String properties = violations.stream()
                .map(violation -> pointer(violation) + " (" + violation.getKeyword() + ")")
                .collect(Collectors.joining(", "));
        return eventType + " breaks " + EventSchemas.resourceFor(eventType, schemaVersion) + " on "
                + violations.size() + (violations.size() == 1 ? " property: " : " properties: ")
                + properties;
    }

    /** The JSON pointer of the property one violation reports. */
    private static String pointer(Error violation) {
        String location = violation.getInstanceLocation().toString();
        String property = violation.getProperty();
        if (property == null || property.isBlank()) {
            return location.isEmpty() ? "/" : location;
        }
        return location + "/" + property;
    }

    /**
     * Cuts an over-long event type, so an attempt to write a log line through the failure message
     * costs nothing.
     */
    private static String shorten(String eventType) {
        int limit = 32;
        return eventType.length() <= limit ? eventType : eventType.substring(0, limit) + "...";
    }
}
