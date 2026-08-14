package com.carddemo.events.serde;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The posture of every schema document this module has released, read from
 * {@value #RESOURCE}.
 *
 * <p>No COBOL program declares a schema, so this class has no source ancestor. It exists because a
 * released document outlives the producer that wrote it: a record already on a topic stays readable
 * for as long as the topic holds it, and a document removed to tidy the registry takes that
 * readability with it.
 *
 * <p>Two postures are recorded, and {@value #RESOURCE} defines both.
 *
 * <ul>
 *   <li>{@link #PUBLISHED} — a producer on this platform writes records under this document.</li>
 *   <li>{@link #RETAINED} — no producer writes it, and a consumer still reads it. The document and
 *       its record type stay so a record written before the producer moved up stays readable.</li>
 * </ul>
 *
 * <p>{@link #isPublished(String, int)} is the gate {@link EventContracts#publishViolationsOf(String,
 * String)} applies on the publish path, so a producer downgraded to an older document fails at the
 * write rather than on a consumer that had already moved on. The deserialize path applies no such
 * gate, which is what keeps a retained record readable.
 *
 * <p>The baseline is read once, from the classpath of this class, and never from a network location
 * or an absolute path on disk. An instance holds no mutable state a caller can observe, so service
 * threads may call every method here.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
public final class ReleasedContracts {

    /** The classpath resource holding the released-contract baseline. */
    public static final String RESOURCE = "contracts/released-contracts.json";

    /** Posture of a document a producer on this platform writes records under. */
    public static final String PUBLISHED = "PUBLISHED";

    /** Posture of a released document no producer writes and every consumer still reads. */
    public static final String RETAINED = "RETAINED";

    /** The array of contract entries in {@value #RESOURCE}. */
    private static final String CONTRACTS_PROPERTY = "contracts";

    /** The event-type property of one contract entry. */
    private static final String EVENT_TYPE_PROPERTY = "eventType";

    /** The contract-version property of one contract entry. */
    private static final String SCHEMA_VERSION_PROPERTY = "schemaVersion";

    /** The posture property of one contract entry. */
    private static final String POSTURE_PROPERTY = "posture";

    /**
     * Every released pair of event type and contract version, mapped to its posture.
     *
     * <p>Read once at class initialization. A pair absent from this map has never been released,
     * which {@link #isPublished(String, int)} treats as unpublishable.
     */
    private static final Map<EventSchemas.SchemaKey, String> POSTURES = readPostures();

    /** Nothing constructs this class. */
    private ReleasedContracts() {
        throw new AssertionError("ReleasedContracts holds one table and is never instantiated");
    }

    /**
     * Returns the posture the baseline records for one released document.
     *
     * @param eventType     the routing discriminator to look up
     * @param schemaVersion the contract version to look up
     * @return {@link #PUBLISHED}, {@link #RETAINED}, or {@code null} when the pair is not recorded
     */
    public static String postureOf(String eventType, int schemaVersion) {
        return POSTURES.get(new EventSchemas.SchemaKey(eventType, schemaVersion));
    }

    /**
     * Reports whether a producer may write records under one released document.
     *
     * @param eventType     the routing discriminator the payload carries
     * @param schemaVersion the contract version the payload declares
     * @return {@code true} only when the baseline records the pair as {@link #PUBLISHED}
     */
    public static boolean isPublished(String eventType, int schemaVersion) {
        return PUBLISHED.equals(postureOf(eventType, schemaVersion));
    }

    /**
     * Returns the versions of one event type a producer may write, in ascending order.
     *
     * <p>A gate names this list when it refuses a payload, so an operator reads which version the
     * platform does publish rather than only which one it refused.
     *
     * @param eventType the routing discriminator to look up
     * @return the published versions, ascending, and empty when the event type has none
     */
    public static List<Integer> publishedVersions(String eventType) {
        return POSTURES.entrySet().stream()
                .filter(entry -> entry.getKey().eventType().equals(eventType))
                .filter(entry -> PUBLISHED.equals(entry.getValue()))
                .map(entry -> entry.getKey().schemaVersion())
                .sorted()
                .toList();
    }

    /**
     * Returns the versions of one event type a consumer reads and no producer writes, ascending.
     *
     * @param eventType the routing discriminator to look up
     * @return the retained versions, ascending, and empty when the event type has none
     */
    public static List<Integer> retainedVersions(String eventType) {
        return POSTURES.entrySet().stream()
                .filter(entry -> entry.getKey().eventType().equals(eventType))
                .filter(entry -> RETAINED.equals(entry.getValue()))
                .map(entry -> entry.getKey().schemaVersion())
                .sorted()
                .toList();
    }

    /**
     * Returns every released pair of event type and contract version, mapped to its posture.
     *
     * @return the whole baseline, unmodifiable
     */
    public static Map<EventSchemas.SchemaKey, String> postures() {
        return POSTURES;
    }

    /**
     * Reads {@value #RESOURCE} into the posture table.
     *
     * <p>An entry naming a posture the baseline does not define is refused here rather than treated
     * as unpublishable, because a misspelt posture would otherwise silently stop a producer.
     *
     * @return one entry per released document, unmodifiable
     * @throws IllegalStateException when the resource is missing, unreadable, holds no contract
     *                               array, or names a posture other than {@link #PUBLISHED} or
     *                               {@link #RETAINED}
     */
    private static Map<EventSchemas.SchemaKey, String> readPostures() {
        JsonNode baseline;
        try (InputStream document =
                ReleasedContracts.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (document == null) {
                throw new IllegalStateException(
                        "Classpath resource " + RESOURCE + " is missing from this module.");
            }
            baseline = JsonMapper.builder().build().readTree(document);
        } catch (IOException cause) {
            throw new IllegalStateException(
                    "Classpath resource " + RESOURCE + " could not be read.", cause);
        }

        JsonNode contracts = baseline.path(CONTRACTS_PROPERTY);
        if (!contracts.isArray() || contracts.isEmpty()) {
            throw new IllegalStateException("Classpath resource " + RESOURCE + " must hold a"
                    + " non-empty " + CONTRACTS_PROPERTY + " array.");
        }

        Map<EventSchemas.SchemaKey, String> postures = new HashMap<>();
        List<String> refused = new ArrayList<>();
        for (JsonNode contract : contracts) {
            String eventType = contract.path(EVENT_TYPE_PROPERTY).asString(null);
            int schemaVersion = contract.path(SCHEMA_VERSION_PROPERTY).asInt(0);
            String posture = contract.path(POSTURE_PROPERTY).asString(null);
            if (eventType == null || schemaVersion == 0
                    || !(PUBLISHED.equals(posture) || RETAINED.equals(posture))) {
                refused.add(eventType + " version " + schemaVersion + " posture " + posture);
                continue;
            }
            postures.put(new EventSchemas.SchemaKey(eventType, schemaVersion), posture);
        }
        if (!refused.isEmpty()) {
            throw new IllegalStateException("Classpath resource " + RESOURCE + " holds "
                    + refused.size() + " entries that name no event type, no version, or a posture"
                    + " other than " + PUBLISHED + " or " + RETAINED + ": " + refused);
        }
        return Map.copyOf(Objects.requireNonNull(postures));
    }
}
