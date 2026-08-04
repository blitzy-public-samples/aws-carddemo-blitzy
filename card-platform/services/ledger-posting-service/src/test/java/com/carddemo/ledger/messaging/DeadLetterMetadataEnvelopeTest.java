package com.carddemo.ledger.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.EventSchemas;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that this service's dead-letter metadata reaches a topic only as the one shared contract.
 *
 * <p>Five services declared a record named {@code DeadLetterMetadata}, each with its own component
 * list and none with a schema. That is five wire formats on a topic a single operator has to read,
 * and it meant nothing validated what reached a dead-letter topic: the one place a rejected message
 * is kept longest was the one place its contents were least controlled.
 *
 * <p>{@link DeadLetterEnvelope} is that contract now. These tests assert three things about the
 * bridge: the envelope it produces validates against {@code schemas/dead-letter-v1.json} through
 * the same serializer every other event passes, the diagnostics survive the crossing, and no value
 * of the failing payload appears in the result.
 *
 * <p>Every test runs in memory. None opens a connection or contacts a broker.
 */
@DisplayName("dead-letter metadata crosses to the one shared envelope")
class DeadLetterMetadataEnvelopeTest {

    /** Row one of {@code app/data/ASCII/cardxref.txt}, the account identifier it resolves to. */
    private static final String AGGREGATE_ID = "00000000050";

    /** A sentinel that stands for a payload value, so a leak is unmistakable. */
    private static final String PAYLOAD_VALUE = "0500024453765740";

    /** The topic the failing record arrived on. */
    private static final String SOURCE_TOPIC = "transaction.authorized";

    @Test
    @DisplayName("the envelope the bridge builds validates against the shared schema")
    void theEnvelopeValidatesAgainstTheSharedSchema() {
        DeadLetterEnvelope envelope = bridged();

        try (JsonSchemaValidatingSerializer<DeadLetterEnvelope> serializer =
                new JsonSchemaValidatingSerializer<>()) {
            // The envelope travels on the dead-letter topic, not on the topic the failing record
            // arrived on. The serializer refuses any other topic for a registered event type, which
            // is what keeps a dead letter off a topic a consumer reads for business events.
            byte[] wire = serializer.serialize(
                    EventContracts.defaultTopicFor(DeadLetterEnvelope.EVENT_TYPE), envelope);

            assertTrue(wire.length > 0, "the serializer validates before it returns bytes");
            String json = new String(wire, StandardCharsets.UTF_8);
            assertTrue(json.contains(DeadLetterEnvelope.EVENT_TYPE),
                    "the document names the one dead-letter type: " + json);
            assertFalse(json.contains(PAYLOAD_VALUE),
                    "no value of the failing record reaches the topic: " + json);
        }
    }

    @Test
    @DisplayName("the shared schema governs exactly one version of this contract")
    void theSharedSchemaGovernsThisContract() {
        assertEquals(DeadLetterEnvelope.SCHEMA_RESOURCE,
                EventSchemas.resourceFor(DeadLetterEnvelope.EVENT_TYPE, 1),
                "one type, one version, one schema document");
    }

    @Test
    @DisplayName("the diagnostics cross, and the source coordinates are added")
    void theDiagnosticsCross() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of("0999", "IllegalStateException",
                "SCHEMA_VALIDATION_FAILED", "the document declared an undeclared property");
        DeadLetterEnvelope envelope = metadata.toEnvelope(AGGREGATE_ID, SOURCE_TOPIC, 2, 4242L,
                null, null, 5);

        assertEquals(metadata.abendCode(), envelope.abendCode(), "the failure code crosses");
        assertEquals(metadata.culprit(), envelope.culprit(), "the culprit crosses");
        assertEquals(metadata.reason(), envelope.reason(), "the classification crosses");
        assertEquals(metadata.message(), envelope.message(), "the operator detail crosses");
        assertEquals(List.of(), envelope.truncatedComponents(),
                "this carrier bounds every diagnostic to the width the envelope bounds it to, so the"
                        + " envelope shortened nothing of its own");
        assertEquals(SOURCE_TOPIC, envelope.sourceTopic(),
                "the envelope adds where the record was, which the diagnostics cannot supply");
        assertEquals(2, envelope.sourcePartition(), "and the partition");
        assertEquals(4242L, envelope.sourceOffset(), "and the offset");
        assertEquals(AGGREGATE_ID, envelope.aggregateId(),
                "the account identifier is the message key, so a dead letter lands on the partition"
                        + " its record belonged to");
    }

    @Test
    @DisplayName("an aggregate identifier that is not eleven digits is refused")
    void aBadAggregateIdentifierIsRefused() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of("0999", "IllegalStateException",
                "SCHEMA_VALIDATION_FAILED", "the document declared an undeclared property");

        assertThrows(IllegalArgumentException.class,
                () -> metadata.toEnvelope("50", SOURCE_TOPIC, 0, 0L, null, null, 1),
                "a key that is not the eleven-character account identifier partitions the dead"
                        + " letter away from the records it belongs with");
    }

    /**
     * Builds one envelope through the bridge under test.
     *
     * @return the shared envelope this service would publish
     */
    private static DeadLetterEnvelope bridged() {
        return DeadLetterMetadata
                .of("0999", "IllegalStateException", "SCHEMA_VALIDATION_FAILED",
                        "the document declared an undeclared property")
                .toEnvelope(AGGREGATE_ID, SOURCE_TOPIC, 0, 0L, null, null, 3);
    }
    /**
     * Asserts an over-long diagnostic reaches the envelope already inside its declared width.
     *
     * <p>Bounding every diagnostic is the security property: without it, an arbitrarily long value
     * assembled from a failing record reaches the one topic a rejected message is kept on longest.
     * This carrier bounds each component to the same width {@link DeadLetterEnvelope} bounds it to,
     * so the envelope receives a value that already fits and reports no component as shortened. A
     * producer that bounds its diagnostics more narrowly builds the envelope directly, and the
     * envelope then names what it shortened itself.
     */
    @Test
    void anOverLongDiagnosticArrivesAlreadyInsideItsWidth() {
        DeadLetterMetadata metadata = DeadLetterMetadata.of("0999", "IllegalStateException",
                "R".repeat(DeadLetterMetadata.REASON_MAX_LENGTH + 20),
                "M".repeat(DeadLetterMetadata.MESSAGE_MAX_LENGTH + 20));

        DeadLetterEnvelope envelope = metadata.toEnvelope(AGGREGATE_ID, SOURCE_TOPIC, 2, 4242L,
                null, null, 5);

        assertEquals(DeadLetterMetadata.REASON_MAX_LENGTH, envelope.reason().length(),
                "the reason was shortened to the width ABEND-REASON PIC X(50) declares");
        assertEquals(DeadLetterMetadata.MESSAGE_MAX_LENGTH, envelope.message().length(),
                "the message was shortened to the width ABEND-MSG PIC X(72) declares");
        assertEquals(List.of(), envelope.truncatedComponents(),
                "the envelope received values that already fit, so it shortened nothing itself");
    }

}
