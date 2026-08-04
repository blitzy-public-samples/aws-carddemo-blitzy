package com.carddemo.events.serde;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.TransactionPosted;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;

/**
 * The one table that maps an {@code eventType} to the schema document governing it, shared by the
 * serializer and the deserializer of this package.
 *
 * <p>ADDITIVE IN FULL. No COBOL program and no copybook defines this class.
 *
 * <p>Every event this platform publishes passes through one of the two serde classes, and both read
 * this table, so a single gate decides which documents exist and what each one is called. A new
 * event type is added by adding a schema document beside the others and one entry here; nothing else
 * in the platform learns about event types by any other route. The account and card mutation events
 * are in the table for that reason: the records themselves belong to the services that own those
 * aggregates, and their contracts are validated here alongside the five core events rather than
 * beside a {@code StringSerializer}.
 *
 * <p>The dead-letter envelope is in the table too, so the one contract every dead-letter topic
 * carries is validated by the same gate as the events themselves, and a handler reading several
 * dead-letter topics validates rather than trusts.
 *
 * <p>{@link #RECORD_TYPES} maps the event types whose records live in this module. An event
 * type present in {@link #SCHEMA_RESOURCES} but absent from {@link #RECORD_TYPES} is one this module
 * validates but does not bind, because the record belongs to the service that owns the aggregate.
 * That service supplies its own record class to
 * {@link JsonSchemaValidatingDeserializer#JsonSchemaValidatingDeserializer(Map)}.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned). Event paths:
 * {@code card-platform/docs/event-flow.md} (planned).
 */
public final class EventSchemas {

    /** The property that selects a schema, and the routing discriminator each event carries. */
    public static final String EVENT_TYPE_PROPERTY = "eventType";

    /** The property carrying the contract version, checked before any payload field is read. */
    public static final String SCHEMA_VERSION_PROPERTY = "schemaVersion";

    /** The property carrying the idempotency key, named in a failure message but never a payload. */
    public static final String EVENT_ID_PROPERTY = "eventId";

    /**
     * One event type at one contract version, and the key that selects a schema document.
     *
     * <p>An event type is not enough on its own. {@code TransactionDeclined} ships two documents: a
     * decline whose account the card cross-reference resolved, and a decline where it did not. A
     * gate that selected on the event type alone would validate one against the other's document and
     * report a violation that names the wrong contract.
     *
     * @param eventType     the routing discriminator each event carries
     * @param schemaVersion the contract version each event carries
     */
    public record SchemaKey(String eventType, int schemaVersion) {

        /**
         * Rejects a key naming no event type.
         *
         * @throws NullPointerException     when {@code eventType} is {@code null}
         * @throws IllegalArgumentException when {@code eventType} is blank
         */
        public SchemaKey {
            Objects.requireNonNull(eventType, "eventType must be present");
            if (eventType.isBlank()) {
                throw new IllegalArgumentException("eventType must name an event type");
            }
        }
    }

    /**
     * The classpath resource holding every governed pair of event type and contract version. Both
     * sides of each entry are literals, so renaming either side breaks the build instead of the wire
     * form.
     *
     * <p>The five core events travel between the authorization, ledger, fraud and notification
     * services. The two mutation events travel from the account and card services, whose records
     * live in those modules and whose contracts are governed here.
     *
     * <p>{@code TransactionDeclined} is the one event type with two versions. Version 1 governs a
     * decline whose account the cross-reference resolved. Version 2 governs reject reason
     * {@code 0100}, which {@code app/cbl/CBTRN02C.cbl:L385-L387} assigns when the keyed read of the
     * cross-reference file misses; that document declares no account identifier, because none exists
     * that the platform established itself. Both are compiled and both remain readable, which is
     * what lets a consumer of resolved declines carry on unchanged.
     */
    public static final Map<SchemaKey, String> SCHEMA_DOCUMENTS = Map.ofEntries(
            Map.entry(new SchemaKey("TransactionAuthorized", 1),
                    "schemas/transaction-authorized-v1.json"),
            Map.entry(new SchemaKey("TransactionDeclined", 1),
                    "schemas/transaction-declined-v1.json"),
            Map.entry(new SchemaKey("TransactionDeclined", 2),
                    "schemas/transaction-declined-v2.json"),
            Map.entry(new SchemaKey("TransactionPosted", 1),
                    "schemas/transaction-posted-v1.json"),
            Map.entry(new SchemaKey("FraudFlagged", 1), "schemas/fraud-flagged-v1.json"),
            Map.entry(new SchemaKey("FraudCleared", 1), "schemas/fraud-cleared-v1.json"),
            Map.entry(new SchemaKey("AccountStateChanged", 1),
                    "schemas/account-state-changed-v1.json"),
            Map.entry(new SchemaKey("CardUpdated", 1), "schemas/card-updated-v1.json"),
            Map.entry(new SchemaKey(DeadLetterEnvelope.EVENT_TYPE, 1),
                    DeadLetterEnvelope.SCHEMA_RESOURCE));

    /**
     * The newest document of each event type, derived from {@link #SCHEMA_DOCUMENTS} rather than
     * typed a second time, so the two cannot drift apart.
     *
     * <p>This is the table to read when the question is which contract a producer publishes today.
     * It is not the table a gate validates against: a gate reads the version the event itself
     * carries, because an older event stays valid under the document it was published under.
     */
    public static final Map<String, String> SCHEMA_RESOURCES = newestDocumentPerEventType();

    /**
     * The record class each event type of this module binds to, used by the deserializer.
     *
     * <p>A class reaches this map as a class literal and never as a name read from configuration or
     * from a message, so no document can choose the type it is bound to.
     */
    public static final Map<String, Class<?>> RECORD_TYPES = Map.of(
            "TransactionAuthorized", TransactionAuthorized.class,
            "TransactionDeclined", TransactionDeclined.class,
            "TransactionPosted", TransactionPosted.class,
            "FraudFlagged", FraudFlagged.class,
            "FraudCleared", FraudCleared.class,
            DeadLetterEnvelope.EVENT_TYPE, DeadLetterEnvelope.class);

    /** Nothing constructs this class. */
    private EventSchemas() {
        throw new AssertionError("EventSchemas holds one table and is never instantiated");
    }

    /**
     * Returns the event types this module governs.
     *
     * @return the key set of {@link #SCHEMA_RESOURCES}, unmodifiable
     */
    public static Set<String> governedEventTypes() {
        return SCHEMA_RESOURCES.keySet();
    }

    /**
     * Returns the contract versions this module governs for one event type, in ascending order.
     *
     * <p>A gate names this set when it refuses an event carrying a version no document describes,
     * which tells an operator what the platform does read rather than only what it does not.
     *
     * @param eventType the routing discriminator to look up
     * @return the versions governed, ascending, and empty when the event type is not governed
     */
    public static List<Integer> governedVersions(String eventType) {
        return SCHEMA_DOCUMENTS.keySet().stream()
                .filter(key -> key.eventType().equals(eventType))
                .map(SchemaKey::schemaVersion)
                .sorted()
                .toList();
    }

    /**
     * Returns the document governing one pair of event type and contract version.
     *
     * @param eventType     the routing discriminator the event carries
     * @param schemaVersion the contract version the event carries
     * @return the classpath resource, or {@code null} when the pair is not governed
     */
    public static String resourceFor(String eventType, int schemaVersion) {
        return SCHEMA_DOCUMENTS.get(new SchemaKey(eventType, schemaVersion));
    }

    /**
     * Reduces {@link #SCHEMA_DOCUMENTS} to the highest version of each event type.
     *
     * @return one document per event type, unmodifiable
     */
    private static Map<String, String> newestDocumentPerEventType() {
        Map<String, Integer> newest = new HashMap<>();
        for (SchemaKey key : SCHEMA_DOCUMENTS.keySet()) {
            newest.merge(key.eventType(), key.schemaVersion(), Math::max);
        }
        Map<String, String> resources = new HashMap<>();
        newest.forEach((eventType, version) ->
                resources.put(eventType, SCHEMA_DOCUMENTS.get(new SchemaKey(eventType, version))));
        return Map.copyOf(resources);
    }

    /**
     * Compiles one schema document read from the classpath of this class.
     *
     * <p>Nothing is read from a network location or from an absolute path on disk, and no
     * {@code $id} is dereferenced, so compiling a document reaches no resource this module does not
     * ship.
     *
     * @param schemaRegistry the registry that reads JSON Schema Draft 2020-12
     * @param resource       the classpath resource holding the document
     * @return the compiled schema
     * @throws IllegalStateException when the classpath holds no such resource, or reading it fails
     */
    public static Schema compile(SchemaRegistry schemaRegistry, String resource) {
        try (InputStream document = EventSchemas.class.getClassLoader()
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
}
