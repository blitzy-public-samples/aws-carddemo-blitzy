package com.carddemo.events.serde;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.EventEnvelope;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one registry that binds each event type to its schema document and to the topic it travels
 * on, and the one place a payload is checked against that document.
 *
 * <p>No COBOL program and no copybook in this repository declares an event bus, a topic or a
 * schema.
 *
 * <p>Seven event types are registered, which is every event the platform publishes at runtime:
 * {@code TransactionAuthorized}, {@code TransactionDeclined} and {@code TransactionPosted} for the
 * transaction path, {@code FraudFlagged} and {@code FraudCleared} for the risk path, and
 * {@code AccountStateChanged} and {@code CardUpdated} for the two supporting services. A
 * service that publishes an event outside this set has no contract, and
 * {@link #schemaResourceFor(String)} rejects it.
 *
 * <p>{@link #defaultTopicFor(String)} answers which topic one event type belongs on.
 * {@code FraudFlagged} and {@code FraudCleared} share {@code fraud.assessed}, so a consumer of that
 * topic reads {@code eventType} to learn which payload arrived. A deployment may rename a topic
 * through its own configuration, and {@link #isBoundToTopic(String, String)} accepts a supplied
 * override beside the default.
 *
 * <p>{@link #violationsOf(String, String)} validates a serialized payload and returns one entry per
 * failure. Each entry holds a JSON pointer and the broken keyword and nothing else. No entry carries
 * a value from the payload, so a full Primary Account Number (PAN), a card verification value or an
 * account identifier cannot reach a log through a validation failure.
 *
 * <p>{@link #publishViolationsOf(String, String)} is the form a producer holding TEXT calls, which is
 * a relay re-checking an outbox row it is about to publish. It reports what
 * {@link #violationsOf(String, String)} reports, one entry per sensitive-data screen the payload
 * fails, one entry when the payload is wider than {@link EventWireBounds#MAX_EVENT_BYTES}, and one
 * further entry when the version the payload declares is retained rather than published, which
 * {@link ReleasedContracts} decides from {@code contracts/released-contracts.json}. The consume path
 * applies no posture gate, so a record written under a retained document stays readable.
 *
 * <p>A producer holding a RECORD calls {@link PublishGate#checkedJsonOf(Record)} instead, which
 * writes the text through {@link JsonSchemaValidatingSerializer} and adds the class and topic checks
 * this class cannot make from text alone. That is the mandatory boundary before an outbox row is
 * stored. This method is the same set of checks over text a caller already holds, so neither path is
 * the weaker one.
 *
 * <p>{@link #violationsOf(String, JsonNode)} takes the payload already parsed and checks the same
 * documents against it. A caller that has read the envelope, or screened the payload for a property
 * no event may carry, holds that tree already, and the two serde classes of this module both do.
 * Both forms select the document by event type and declared version and report failures the same
 * way; they differ only in who parsed the payload.
 *
 * <p>Every document is read from the classpath of this class. Nothing is read from a network
 * location or from an absolute path on disk, and no {@code $id} is dereferenced. A document compiles
 * once and is then shared, so an instance holds no mutable state a caller can observe and service
 * threads may call every method here.
 *
 * <p>Versions in use: Java 25, {@code json-schema-validator 3.0.6} for JSON Schema Draft 2020-12.
 */
public final class EventContracts {

    /** The event type the authorization service publishes when it approves a transaction. */
    public static final String TRANSACTION_AUTHORIZED = "TransactionAuthorized";

    /** The event type the authorization service publishes when it rejects a transaction. */
    public static final String TRANSACTION_DECLINED = "TransactionDeclined";

    /** The event type the ledger posting service publishes after it applies a posting. */
    public static final String TRANSACTION_POSTED = "TransactionPosted";

    /** The event type the fraud detection service publishes when its rules flag a transaction. */
    public static final String FRAUD_FLAGGED = "FraudFlagged";

    /** The event type the fraud detection service publishes when its rules clear a transaction. */
    public static final String FRAUD_CLEARED = "FraudCleared";

    /** The event type the account service publishes when an account changes state. */
    public static final String ACCOUNT_STATE_CHANGED = "AccountStateChanged";

    /**
     * The event type the account service publishes when it rewrites the customer record.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:L4086} rewrites the customer record beside the account rewrite
     * at {@code app/cbl/COACTUPC.cbl:L4066}. The customer half travels on its own topic, so a
     * consumer that needs credit values alone receives no cardholder name, address or credit score.
     */
    public static final String CUSTOMER_CONTEXT_CHANGED = "CustomerContextChanged";

    /** The event type the card service publishes when a card update commits. */
    public static final String CARD_UPDATED = "CardUpdated";

    /**
     * The routing discriminator of the one shape every dead-letter topic carries.
     *
     * <p>{@code schemas/dead-letter-v1.json} governs it, and it travels on the one shared
     * dead-letter topic rather than a per-topic one, which is what every service binds through
     * {@code carddemo.kafka.topics.dead-letter}.
     */
    public static final String DEAD_LETTER = DeadLetterEnvelope.EVENT_TYPE;

    /**
     * Each event type mapped to the classpath resource holding its schema document. Both sides of
     * each pair are literals, so renaming either side breaks the build instead of the wire form.
     */
    private static final Map<String, String> SCHEMA_RESOURCES = EventSchemas.SCHEMA_RESOURCES;

    /**
     * Each event type mapped to the topic it travels on by default, matching the topic names
     * {@code card-platform/.env.example} declares and {@code card-platform/docker-compose.yml}
     * creates.
     */
    private static final Map<String, String> DEFAULT_TOPICS = Map.ofEntries(
            Map.entry(TRANSACTION_AUTHORIZED, "transaction.authorized"),
            Map.entry(TRANSACTION_DECLINED, "transaction.declined"),
            Map.entry(TRANSACTION_POSTED, "transaction.posted"),
            Map.entry(FRAUD_FLAGGED, "fraud.assessed"),
            Map.entry(FRAUD_CLEARED, "fraud.assessed"),
            Map.entry(ACCOUNT_STATE_CHANGED, "account.state-changed"),
            Map.entry(CUSTOMER_CONTEXT_CHANGED, "customer.context-changed"),
            Map.entry(CARD_UPDATED, "card.updated"),
            Map.entry(DEAD_LETTER, "carddemo.dead-letter"));

    /** The registry that reads JSON Schema Draft 2020-12, the dialect every document declares. */
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** One compiled schema per event type. A document is read from the classpath once. */
    private static final Map<String, Schema> COMPILED = new ConcurrentHashMap<>();

    /**
     * The one reader this class parses text with.
     *
     * <p>A mapper is expensive to build and safe to share: it holds a configuration and a cache of
     * the types it has bound, and no per-call state. One built for every call to read one property
     * discarded that cache each time, which is what the reader of a hot publish path pays for.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** No instance is needed: every member is static. */
    private EventContracts() {
    }

    /**
     * The event types this module holds a contract for.
     *
     * <p>The set is read from the registry rather than restated here, so it grows with the schema
     * documents on the classpath. It holds the transaction and fraud events, the state-change events
     * and the dead-letter envelope. A type with more than one governed version contributes one entry,
     * and {@code FraudFlagged} and {@code FraudCleared} contribute one entry each while sharing a
     * topic, so the size of this set is smaller than the number of documents behind it. Read the
     * figures from {@link EventSchemas#governedEventTypes()} and
     * {@link EventSchemas#governedVersions(String)} rather than from this comment.
     *
     * @return every registered event type
     */
    public static Set<String> eventTypes() {
        return SCHEMA_RESOURCES.keySet();
    }

    /**
     * Reports whether one event type is registered.
     *
     * @param eventType the routing discriminator to look up; may be {@code null}
     * @return {@code true} when this module holds a schema document for it
     */
    public static boolean isRegistered(String eventType) {
        return eventType != null && SCHEMA_RESOURCES.containsKey(eventType);
    }

    /**
     * The classpath resource holding the schema document of one event type.
     *
     * @param eventType the routing discriminator
     * @return the resource path, such as {@code schemas/transaction-authorized-v1.json}
     * @throws IllegalArgumentException when {@code eventType} is not registered
     */
    public static String schemaResourceFor(String eventType) {
        return SCHEMA_RESOURCES.get(requireRegistered(eventType));
    }

    /**
     * The topic one event type travels on by default.
     *
     * @param eventType the routing discriminator
     * @return the default topic name
     * @throws IllegalArgumentException when {@code eventType} is not registered
     */
    public static String defaultTopicFor(String eventType) {
        return DEFAULT_TOPICS.get(requireRegistered(eventType));
    }

    /**
     * Reports whether one event type belongs on one topic.
     *
     * <p>The default topic of the event type always matches. A deployment that renames a topic
     * passes the configured name it publishes to, and that name matches as well, so a rename stays
     * a configuration change. Any other name does not match, which is how an event bound for the
     * wrong topic is caught before the send.
     *
     * @param eventType      the routing discriminator
     * @param topic          the topic the caller is about to publish to
     * @param configuredName the topic name this deployment configured for {@code eventType}, or
     *                       {@code null} when the deployment keeps the default
     * @return {@code true} when {@code topic} is the default topic or the configured one
     * @throws IllegalArgumentException when {@code eventType} is not registered
     */
    public static boolean isBoundToTopic(String eventType, String topic, String configuredName) {
        String expected = defaultTopicFor(eventType);

        if (topic == null) {
            return false;
        }
        return topic.equals(expected) || topic.equals(configuredName);
    }

    /**
     * Reports whether one event type belongs on one topic, with no configured override.
     *
     * @param eventType the routing discriminator
     * @param topic     the topic the caller is about to publish to
     * @return {@code true} when {@code topic} is the default topic of {@code eventType}
     * @throws IllegalArgumentException when {@code eventType} is not registered
     */
    public static boolean isBoundToTopic(String eventType, String topic) {
        return isBoundToTopic(eventType, topic, null);
    }

    /**
     * Validates one serialized payload against the schema document of its event type.
     *
     * <p>The text is parsed once here and the tree is what the document is checked against, so a
     * caller that holds no tree pays one parse and never two. A caller that already parsed the
     * payload passes the tree to {@link #violationsOf(String, JsonNode)} and pays none.
     *
     * @param eventType the routing discriminator naming the document
     * @param json      the serialized payload, validated exactly as supplied
     * @return one entry per failure, empty when the payload validates. Each entry holds a JSON
     *         pointer and the broken keyword and carries no value from the payload
     * @throws IllegalArgumentException when {@code eventType} is not registered
     * @throws IllegalStateException    when the classpath holds no such document
     */
    public static List<String> violationsOf(String eventType, String json) {
        Objects.requireNonNull(json, "json must be present");
        requireRegistered(eventType);

        JsonNode document;
        try {
            document = MAPPER.readTree(json);
        } catch (RuntimeException unreadable) {
            // Text that is not JSON at all cannot be checked against a document that describes
            // JSON. The validator answers the same way for the same input, so the report a caller
            // receives is the validator's and not this method's reading of the failure.
            List<String> pointers = new ArrayList<>();
            Schema schema = COMPILED.computeIfAbsent(
                    schemaResourceFor(eventType, EventEnvelope.SCHEMA_VERSION),
                    EventContracts::compile);
            for (Error violation : schema.validate(json, InputFormat.JSON)) {
                pointers.add(describe(violation));
            }
            return List.copyOf(pointers);
        }
        return violationsOf(eventType, document);
    }

    /**
     * Validates one parsed payload against the schema document of its event type.
     *
     * <p>This is the form the two serde classes call. Each of them has already parsed the payload
     * to read its envelope and to screen it for a forbidden or sensitive property, and the checked
     * tree is the same tree the document governs, so parsing it again to validate it would read the
     * same bytes a third time and could not read them differently.
     *
     * @param eventType the routing discriminator naming the document
     * @param document  the parsed payload, validated exactly as supplied
     * @return one entry per failure, empty when the payload validates. Each entry holds a JSON
     *         pointer and the broken keyword and carries no value from the payload
     * @throws IllegalArgumentException when {@code eventType} is not registered
     * @throws IllegalStateException    when the classpath holds no such document
     */
    public static List<String> violationsOf(String eventType, JsonNode document) {
        Objects.requireNonNull(document, "document must be present");
        requireRegistered(eventType);

        Schema schema = COMPILED.computeIfAbsent(schemaResourceFor(eventType, versionOf(document)),
                EventContracts::compile);
        List<String> pointers = new ArrayList<>();
        for (Error violation : schema.validate(document)) {
            pointers.add(describe(violation));
        }
        return List.copyOf(pointers);
    }

    /**
     * Applies every publish-side check to one payload a caller already holds as text.
     *
     * <p>Six checks run. The document its event type and declared version select must accept the
     * payload, which {@link #violationsOf(String, String)} reports. Neither
     * {@link SensitiveEventProperties} screen may refuse it. It may carry no {@code extensions}
     * object, because no record of this platform declares one and a consumer nonetheless keeps
     * reading one. It must fit {@link EventWireBounds#MAX_EVENT_BYTES}. And the version it declares
     * must be one a producer may still write, which {@link ReleasedContracts} decides.
     *
     * <p>The two screens and the ceiling used to be absent here, and that absence is why this method
     * exists in this form. An outbox writer wrote its own text, measured it against the document
     * alone and stored it, so a card number in a free-text property committed with the business state
     * and reached a topic, where the consume side refused it. The produce and consume contracts now
     * name the same refusals whichever path a producer takes.
     *
     * <p>{@link PublishGate#checkedJsonOf(Record)} is the boundary a producer holding a RECORD
     * crosses, and it adds the two checks text cannot carry: the class must name a registered event
     * type, and that type must belong on the topic. A caller holding text has already lost both
     * facts, so this method reports what remains measurable.
     *
     * <p>A retained document stays readable on the consume side, and
     * {@code serde/JsonSchemaValidatingDeserializer} applies no posture gate for that reason. What
     * the posture check refuses is a producer writing under a document a consumer has already moved
     * past, which is how one event type was downgraded to a version whose successor was already on a
     * topic.
     *
     * @param eventType the routing discriminator naming the document
     * @param json      the serialized payload about to be published, validated exactly as supplied
     * @return one entry per failure, empty when the payload passes every check. No entry carries a
     *         value from the payload
     * @throws IllegalArgumentException when {@code eventType} is not registered
     * @throws IllegalStateException    when the classpath holds no such document
     */
    public static List<String> publishViolationsOf(String eventType, String json) {
        List<String> violations = new ArrayList<>(violationsOf(eventType, json));
        violations.addAll(screenViolationsOf(json));
        violations.addAll(byteCeilingViolationsOf(eventType, json));
        violations.addAll(postureViolationsOf(eventType, declaredVersionOf(json)));
        return List.copyOf(violations);
    }

    /**
     * Reports the two sensitive-data screens against one serialized payload.
     *
     * <p>These are the screens {@link JsonSchemaValidatingDeserializer} applies to every record it
     * reads, and {@link PublishGate} applies to every event before it is stored. This method is what
     * keeps them on the third path as well: a producer holding text rather than a record, which is
     * {@code messaging/KafkaEventPublisher} re-checking an outbox row it is about to publish.
     *
     * <p>One screen here has no consume-side counterpart, deliberately.
     * {@link SensitiveEventProperties#firstExtensionProperty(JsonNode)} refuses a payload carrying an
     * {@code extensions} object, because no record of this platform declares one, so a producer
     * carrying it built its JSON outside its own record. The consume side accepts that subtree,
     * because a record a later contract version enriched arrives through it.
     *
     * <p>A schema cannot do this work. Every document closes its property set, so an undeclared
     * property is already refused, but a declared free-text property accepts any text its pattern
     * admits, and {@code description} admits a card number as readily as a sentence.
     *
     * @param json the serialized payload
     * @return one entry per screen that refuses the payload, empty when neither does. Each entry
     *         names the offending property and never its value
     */
    private static List<String> screenViolationsOf(String json) {
        JsonNode document;
        try {
            document = MAPPER.readTree(json);
        } catch (RuntimeException unreadable) {
            // Text that is not JSON carries no property to screen, and violationsOf has already
            // reported it against the document its event type names.
            return List.of();
        }

        List<String> violations = new ArrayList<>();
        String forbidden = SensitiveEventProperties.firstForbiddenProperty(document);
        if (forbidden != null) {
            violations.add("property \"" + forbidden + "\": no event may carry it."
                    + " See SensitiveEventProperties.");
        }
        String extension = SensitiveEventProperties.firstExtensionProperty(document);
        if (extension != null) {
            violations.add("property \"" + extension + "\": no record of this platform declares it,"
                    + " so a payload carrying it was built outside its own record. The subtree is"
                    + " open on the consume side, where an enriched record from a later version"
                    + " arrives, and closed here. See SensitiveEventProperties.");
        }
        String sensitive = SensitiveEventProperties.firstSensitiveValue(document);
        if (sensitive != null) {
            violations.add("property \"" + sensitive + "\": the value carries a card number, a"
                    + " government identifier or a card verification value, and no event may carry"
                    + " any of them. See SensitiveEventProperties.");
        }
        return violations;
    }

    /**
     * Reports the platform byte ceiling against one serialized payload.
     *
     * <p>{@link EventWireBounds#MAX_EVENT_BYTES} is the width one event may occupy on a topic and in
     * an outbox row, and it is measured in UTF-8 octets rather than in characters, because a
     * multi-byte character occupies more of a Kafka record than of a Java string.
     *
     * @param eventType the routing discriminator, named in the entry
     * @param json      the serialized payload
     * @return one entry when the payload is wider than the ceiling, empty otherwise. The entry
     *         states the two widths and no value from the payload
     */
    private static List<String> byteCeilingViolationsOf(String eventType, String json) {
        int octets = json.getBytes(StandardCharsets.UTF_8).length;
        if (octets <= EventWireBounds.MAX_EVENT_BYTES) {
            return List.of();
        }
        return List.of(eventType + " serializes to " + octets + " bytes, which exceeds the "
                + EventWireBounds.MAX_EVENT_BYTES + " byte ceiling one event of this platform may"
                + " occupy. No value from it appears in this message.");
    }

    /**
     * Checks one outgoing contract version against the posture the released baseline records for it.
     *
     * <p>This is the posture half of {@link #publishViolationsOf(String, String)} on its own, for a
     * producer path that has already validated its payload against the document and would otherwise
     * validate it twice. The card service writer is in that position, because
     * {@code CardUpdated#toValidatedJson()} serializes through the validating serializer.
     *
     * @param eventType     the routing discriminator naming the document
     * @param schemaVersion the contract version the outgoing payload declares
     * @return one entry when that version is released but retained, empty when it is published
     * @throws IllegalArgumentException when {@code eventType} is not registered
     */
    public static List<String> postureViolationsOf(String eventType, int schemaVersion) {
        requireRegistered(eventType);
        if (ReleasedContracts.isPublished(eventType, schemaVersion)) {
            return List.of();
        }
        return List.of(EventSchemas.SCHEMA_VERSION_PROPERTY + " " + schemaVersion + ": posture "
                + ReleasedContracts.postureOf(eventType, schemaVersion) + " in "
                + ReleasedContracts.RESOURCE + ", so no producer may write it. Published"
                + (ReleasedContracts.publishedVersions(eventType).size() == 1
                        ? " version is " : " versions are ")
                + ReleasedContracts.publishedVersions(eventType));
    }

    /**
     * The contract version one serialized payload declares.
     *
     * <p>Text that is not JSON, or JSON carrying no readable version, reads as
     * {@link EventEnvelope#SCHEMA_VERSION}. {@link #violationsOf(String, String)} has already
     * reported that payload against its document, so this method reports no failure of its own.
     *
     * <p>Package-private rather than private, because {@link PublishGate} reads the version off the
     * text the serializer has just checked and the posture gate takes that version as its argument.
     *
     * @param json the serialized payload
     * @return the version the payload declares, or {@link EventEnvelope#SCHEMA_VERSION}
     */
    static int declaredVersionOf(String json) {
        try {
            return versionOf(MAPPER.readTree(json));
        } catch (RuntimeException unreadable) {
            return EventEnvelope.SCHEMA_VERSION;
        }
    }

    /**
     * The contract version one parsed payload carries.
     *
     * <p>Several event types ship more than one document, which
     * {@link EventSchemas#governedVersions(String)} reports, so the version is part of what selects
     * one. A payload that carries no readable version reads as
     * {@link EventEnvelope#SCHEMA_VERSION}, which is the first version every event type ships, and
     * the schema then reports the missing property itself rather than this method guessing at it.
     *
     * @param document the parsed payload
     * @return the version the payload declares, or {@link EventEnvelope#SCHEMA_VERSION}
     */
    private static int versionOf(JsonNode document) {
        try {
            return document.path(EventSchemas.SCHEMA_VERSION_PROPERTY)
                    .asInt(EventEnvelope.SCHEMA_VERSION);
        } catch (RuntimeException unreadable) {
            return EventEnvelope.SCHEMA_VERSION;
        }
    }

    /**
     * The document governing one event type at one contract version.
     *
     * @param eventType     the routing discriminator naming the document
     * @param schemaVersion the contract version the payload declares
     * @return the classpath resource of that document
     * @throws IllegalStateException when this module governs no such pair
     */
    private static String schemaResourceFor(String eventType, int schemaVersion) {
        String resource = EventSchemas.SCHEMA_DOCUMENTS
                .get(new EventSchemas.SchemaKey(eventType, schemaVersion));
        if (resource == null) {
            throw new IllegalStateException(eventType + " carries "
                    + EventSchemas.SCHEMA_VERSION_PROPERTY + " " + schemaVersion
                    + " and this module governs version"
                    + (EventSchemas.governedVersions(eventType).size() == 1 ? " " : "s ")
                    + EventSchemas.governedVersions(eventType) + " of it");
        }
        return resource;
    }

    /**
     * Joins the reported violations into one line for a failure message.
     *
     * @param eventType  the routing discriminator naming the document
     * @param violations the entries {@link #violationsOf(String, String)} returned
     * @return the event type, the document, the count and each pointer with its broken keyword
     */
    public static String describeViolations(String eventType, List<String> violations) {
        return eventType + " breaks " + schemaResourceFor(eventType) + " on " + violations.size()
                + (violations.size() == 1 ? " property: " : " properties: ")
                + String.join(", ", violations);
    }

    /**
     * Renders one violation as a JSON pointer and the keyword it broke.
     *
     * <p>The rendered text names where the failure happened and which rule it broke, and never what
     * the value was. A missing property is reported against the object that should hold it, with
     * the property name beside it, so the two are joined here. A result reads
     * {@code /maskedCardNumber (pattern)} at the top level and {@code /triggeredRules/1 (enum)}
     * inside an array.
     *
     * @param violation the reported violation
     * @return the pointer and the broken keyword
     */
    private static String describe(Error violation) {
        String location = violation.getInstanceLocation().toString();
        String property = violation.getProperty();
        String pointer;

        if (property == null || property.isBlank()) {
            pointer = location.isEmpty() ? "/" : location;
        } else {
            pointer = location + "/" + property;
        }
        return pointer + " (" + violation.getKeyword() + ")";
    }

    /**
     * Requires an event type to be registered.
     *
     * @param eventType the routing discriminator to check
     * @return {@code eventType}, unchanged
     * @throws IllegalArgumentException when it is absent or not registered
     */
    private static String requireRegistered(String eventType) {
        if (!isRegistered(eventType)) {
            throw new IllegalArgumentException("event type \"" + eventType
                    + "\" has no contract in this module, which registers "
                    + String.join(", ", orderedEventTypes()));
        }
        return eventType;
    }

    /**
     * The registered event types, sorted so a failure message and a listing read alike.
     *
     * @return the event types in ascending order
     */
    private static List<String> orderedEventTypes() {
        List<String> names = new ArrayList<>(SCHEMA_RESOURCES.keySet());

        names.sort(null);
        return names;
    }

    /**
     * Compiles the schema document of one event type, read from the classpath of this class.
     *
     * @param resource the classpath resource of the document to compile
     * @return the compiled schema
     * @throws IllegalStateException when the classpath holds no such document, or reading it fails
     */
    private static Schema compile(String resource) {
        try (InputStream document =
                EventContracts.class.getClassLoader().getResourceAsStream(resource)) {
            if (document == null) {
                throw new IllegalStateException(
                        "classpath resource " + resource + " is missing from this module");
            }
            return REGISTRY.getSchema(document, InputFormat.JSON);
        } catch (IOException cause) {
            throw new IllegalStateException("classpath resource " + resource
                    + " could not be read", cause);
        }
    }

    /**
     * Every registered event type mapped to its default topic.
     *
     * @return an unmodifiable copy, in event-type order
     */
    public static Map<String, String> defaultTopics() {
        Map<String, String> ordered = new LinkedHashMap<>();

        for (String eventType : orderedEventTypes()) {
            ordered.put(eventType, DEFAULT_TOPICS.get(eventType));
        }
        return Map.copyOf(ordered);
    }
}
