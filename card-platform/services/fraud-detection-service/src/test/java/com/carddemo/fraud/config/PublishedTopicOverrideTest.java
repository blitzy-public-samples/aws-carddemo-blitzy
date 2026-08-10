package com.carddemo.fraud.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.carddemo.events.DeadLetterEnvelope;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.events.serde.EventContracts;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;

/**
 * Holds the producer factory to registering one serializer topic override per event type it can
 * publish.
 *
 * <p>{@link JsonSchemaValidatingSerializer} refuses an event whose type is not bound to the topic it
 * was handed, and it knows only each type's default name until an override names the deployed one.
 * Three event types travel through this service's one template: the two assessment outcomes from
 * {@code outbox/OutboxRelay#dispatch}, and {@link DeadLetterEnvelope} from
 * {@code outbox/OutboxRelay#closeUnpublishableRow}.
 *
 * <p>A missing override fails only where that topic was renamed, which is why it survived review: the
 * shipped defaults match, so nothing breaks until a deployment sets {@code TOPIC_DEAD_LETTER}. Then
 * every dead letter is refused, and the outbox row that could not be published cannot be closed
 * either — each sweep retries it and fails identically for as long as the service runs. The tests
 * below therefore configure renamed topics, which is the only condition that shows the fault.
 */
@DisplayName("The producer factory registers a topic override for every event type it publishes")
class PublishedTopicOverrideTest {

    /** A deployed assessment topic name that is not the default. */
    private static final String RENAMED_ASSESSED = "carddemo.fraud.assessed.v2";

    /** A deployed dead-letter topic name that is not the default. */
    private static final String RENAMED_DEAD_LETTER = "carddemo.poison.v2";

    /** The account every event below belongs to. */
    private static final String ACCOUNT_ID = "00000000011";

    /** The transaction every event below carries. */
    private static final String TRANSACTION_ID = "0000000000683580";

    @Nested
    @DisplayName("The registered override set")
    class OverrideSet {

        @Test
        @DisplayName("It names every event type this service can publish")
        void itNamesEveryEventTypeThisServiceCanPublish() {
            Map<String, Object> settings = producerSettings(RENAMED_ASSESSED, RENAMED_DEAD_LETTER);

            assertThat(overrides(settings))
                    .as("the two assessment outcomes and the dead-letter envelope all travel "
                            + "through this one factory")
                    .containsOnlyKeys(FraudFlagged.EVENT_TYPE, FraudCleared.EVENT_TYPE,
                            EventContracts.DEAD_LETTER);
        }

        @Test
        @DisplayName("It points each event type at the topic that deployment configured")
        void itPointsEachEventTypeAtTheConfiguredTopic() {
            Map<String, String> overrides =
                    overrides(producerSettings(RENAMED_ASSESSED, RENAMED_DEAD_LETTER));

            assertThat(overrides)
                    .containsEntry(FraudFlagged.EVENT_TYPE, RENAMED_ASSESSED)
                    .containsEntry(FraudCleared.EVENT_TYPE, RENAMED_ASSESSED)
                    .containsEntry(EventContracts.DEAD_LETTER, RENAMED_DEAD_LETTER);
        }
    }

    @Nested
    @DisplayName("A serializer configured from those settings")
    class ConfiguredSerializer {

        @Test
        @DisplayName("It accepts a dead letter on the renamed dead-letter topic")
        void itAcceptsADeadLetterOnTheRenamedTopic() {
            JsonSchemaValidatingSerializer<Object> serializer =
                    serializerFor(RENAMED_ASSESSED, RENAMED_DEAD_LETTER);

            assertThat(serializer.serialize(RENAMED_DEAD_LETTER, deadLetter()))
                    .as("without the override this is the send that fails, and it fails for ever")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("It accepts a flagged assessment on the renamed assessment topic")
        void itAcceptsAFlaggedAssessmentOnTheRenamedTopic() {
            JsonSchemaValidatingSerializer<Object> serializer =
                    serializerFor(RENAMED_ASSESSED, RENAMED_DEAD_LETTER);

            assertThat(serializer.serialize(RENAMED_ASSESSED, flagged())).isNotEmpty();
        }

        @Test
        @DisplayName("It still refuses an event sent to a topic it is not bound to")
        void itStillRefusesAMisroutedEvent() {
            JsonSchemaValidatingSerializer<Object> serializer =
                    serializerFor(RENAMED_ASSESSED, RENAMED_DEAD_LETTER);

            assertThatExceptionOfType(SerializationException.class)
                    .as("an override widens the accepted names for one type; it does not stop the "
                            + "check")
                    .isThrownBy(() -> serializer.serialize(RENAMED_DEAD_LETTER, flagged()));
        }
    }

    /** Builds the producer settings this service's factory would install for the given topics. */
    private static Map<String, Object> producerSettings(String assessedTopic, String deadLetterTopic) {
        return new KafkaProducerConfig("kafka:29092", assessedTopic, deadLetterTopic)
                .fraudEventProducerFactory(new KafkaProperties())
                .getConfigurationProperties();
    }

    /** Reads the topic overrides out of one settings map, keyed by event type. */
    private static Map<String, String> overrides(Map<String, Object> settings) {
        return settings.entrySet().stream()
                .filter(entry -> entry.getKey()
                        .startsWith(JsonSchemaValidatingSerializer.TOPIC_OVERRIDE_PREFIX))
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey()
                                .substring(JsonSchemaValidatingSerializer.TOPIC_OVERRIDE_PREFIX
                                        .length()),
                        entry -> String.valueOf(entry.getValue())));
    }

    /** Builds a serializer configured exactly as the factory configures the one it installs. */
    private static JsonSchemaValidatingSerializer<Object> serializerFor(String assessedTopic,
            String deadLetterTopic) {
        JsonSchemaValidatingSerializer<Object> serializer = new JsonSchemaValidatingSerializer<>();
        serializer.configure(producerSettings(assessedTopic, deadLetterTopic), false);
        return serializer;
    }

    /** One dead-letter envelope, shaped as the relay builds it. */
    private static DeadLetterEnvelope deadLetter() {
        return DeadLetterEnvelope.fromFailure(ACCOUNT_ID, "0999",
                new IllegalStateException("refused"), "CONTRACT",
                "The event contract refused this payload", "fraud.assessed", 0, 0L, TRANSACTION_ID,
                FraudFlagged.EVENT_TYPE, 1);
    }

    /** One flagged assessment, shaped as the relay reads it back out of the outbox. */
    private static FraudFlagged flagged() {
        EventEnvelope stamped = EventEnvelope.of(FraudFlagged.EVENT_TYPE, ACCOUNT_ID);
        return new FraudFlagged(stamped.eventId(), stamped.eventType(), stamped.schemaVersion(),
                stamped.occurredAt(), stamped.aggregateId(), TRANSACTION_ID, 60,
                List.of(FraudFlagged.VELOCITY_RULE), Instant.parse("2022-06-10T19:27:53Z"),
                ACCOUNT_ID);
    }
}
