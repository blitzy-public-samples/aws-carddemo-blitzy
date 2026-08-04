package com.carddemo.fraud.outbox;

import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.fraud.config.ObservabilityConfig.FraudMeters;
import com.carddemo.fraud.entity.OutboxEventEntity;
import com.carddemo.fraud.messaging.DeadLetterMetadata;
import com.carddemo.fraud.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes the {@code outbox_event} rows {@link OutboxWriter} stored, then marks each one it sent.
 *
 * <p>ADDITIVE IN FULL: net new; no COBOL ancestor. The one ancestor construct of the outbox pattern
 * is the Customer Information Control System (CICS) transient data queue write at
 * {@code app/cbl/CORPT00C.cbl:L517-L523}. One program writes a Job Control Language (JCL) record
 * there and a separate job reads it later. Shape only, no logic.
 *
 * <p>One tick reads a batch of unpublished rows in creation order. The relay reads each stored
 * payload back into its record type, sends that record to the topic {@code fraud.assessed}, and
 * marks the row published. The tick runs in a transaction of its own. The assessment row and the
 * event row already committed inside the listener's transaction, which this class never joins.
 *
 * <p>Every message is keyed on the eleven-digit account identifier the row holds as
 * {@code aggregate_id}, from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
 * (shape only, no logic). The key selects the partition, so the events of one account stay in
 * publish order. The row's {@code event_type} selects the record type, and the topic name never
 * does: {@code FraudFlagged} and {@code FraudCleared} travel together on the one topic.
 *
 * <p>A send the broker does not accept leaves its row unpublished, and the next tick takes that row
 * again. A row naming a type this service does not publish reaches the dead-letter topic. So does a
 * row whose payload no longer satisfies its event contract. Both are then marked published, so no
 * later tick takes either one again.
 *
 * <p>Two facts a reader needs. {@code FraudApplication} carries {@code @EnableScheduling}, and
 * without it this application starts, reports healthy, and publishes nothing. The wire form is
 * flat: the five envelope properties sit beside the payload properties in one JavaScript Object
 * Notation (JSON) object. A written {@link FraudFlagged} holds ten properties, and a written
 * {@link FraudCleared} holds eight.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@Component
public class OutboxRelay {

    /** Diagnostic output of this class. */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** The failure code every dead letter of this relay carries. */
    private static final String ABEND_CODE = "0999";

    /** The component every dead letter of this relay names. */
    private static final String CULPRIT = "RELAY";

    /** Classification a dead letter carries for a type this service never publishes. */
    private static final String UNKNOWN_TYPE_REASON =
            "event type is not one this service publishes";

    /** Failing pointer a dead letter carries for a row naming such a type. */
    private static final String UNKNOWN_TYPE_MESSAGE = "failing pointer /eventType";

    /** Classification a dead letter carries for a payload its own event contract refuses. */
    private static final String CONTRACT_REASON = "payload does not satisfy its event contract";

    /** Failing pointer a dead letter carries for such a payload. */
    private static final String CONTRACT_MESSAGE = "failing pointer / at the document root";

    /**
     * The event type a dead letter may report, which is the pattern
     * {@code schemas/dead-letter-v1.json} sets on its {@code failedEventType} property.
     */
    private static final Pattern REPORTABLE_EVENT_TYPE =
            Pattern.compile("^[A-Za-z][A-Za-z0-9]{0,63}$");

    /** Partition every dead letter reports. A row of {@code outbox_event} arrives from no topic. */
    private static final int NO_SOURCE_PARTITION = 0;

    /** Offset every dead letter reports, on the same footing as the partition above. */
    private static final long NO_SOURCE_OFFSET = 0L;

    /** Reads unpublished rows and stores the flag each published row carries. */
    private final OutboxEventRepository outboxEvents;

    /**
     * Sends one event record under one message key. {@code config/KafkaProducerConfig} builds this
     * template, and its value serializer takes a registered event record and no other value.
     */
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** Counts one failed row, under {@code carddemo.fraud.failures} with stage publish. */
    private final FraudMeters meters;

    /** Reads one stored payload back into its record type. Jackson 3, matching the platform. */
    private final ObjectMapper objectMapper;

    /**
     * The two event types this service publishes, each mapped to the record its payload reads into.
     * The map is the type guard: a row naming any other type never reaches a topic.
     */
    private final Map<String, Class<?>> recordTypesByEventType;

    /** The topic both assessment outcomes travel on. */
    private final String fraudAssessedTopic;

    /** The topic a row no tick can publish travels on. */
    private final String deadLetterTopic;

    /** Rows one tick reads. */
    private final int batchSize;

    /**
     * Takes the store, the template and the meters, reads the three configured values, and builds
     * the one mapper this relay reads payloads with.
     *
     * <p>Each of the three property keys carries a default, so this service runs before a
     * deployment sets any of them.
     *
     * @param outboxEvents       store of unpublished events
     * @param kafkaTemplate      the template both topics are reached through
     * @param meters             the recording surface of this service
     * @param batchSize          rows one tick reads, from {@code carddemo.outbox.relay.batch-size}
     * @param fraudAssessedTopic topic both assessment outcomes travel on, from
     *                           {@code carddemo.kafka.topics.fraud-assessed}
     * @param deadLetterTopic    topic an unpublishable row travels on, from
     *                           {@code carddemo.kafka.topics.dead-letter}
     * @throws NullPointerException     if the store, the template or the meters is null
     * @throws IllegalArgumentException if the batch size is below one, or if either topic name
     *                                  resolves to no usable value
     */
    public OutboxRelay(OutboxEventRepository outboxEvents,
            @Qualifier("fraudEventKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
            FraudMeters meters,
            @Value("${carddemo.outbox.relay.batch-size:100}") int batchSize,
            @Value("${carddemo.kafka.topics.fraud-assessed:fraud.assessed}")
                    String fraudAssessedTopic,
            @Value("${carddemo.kafka.topics.dead-letter:carddemo.dead-letter}")
                    String deadLetterTopic) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must be present");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must be present");
        this.meters = Objects.requireNonNull(meters, "meters must be present");
        this.batchSize = requireBatchSize(batchSize);
        this.fraudAssessedTopic =
                requireTopic(fraudAssessedTopic, "carddemo.kafka.topics.fraud-assessed");
        this.deadLetterTopic = requireTopic(deadLetterTopic, "carddemo.kafka.topics.dead-letter");
        this.objectMapper = eventMapper();
        this.recordTypesByEventType = Map.of(FraudFlagged.EVENT_TYPE, FraudFlagged.class,
                FraudCleared.EVENT_TYPE, FraudCleared.class);
    }

    /**
     * Publishes one batch of unpublished rows, oldest first, and marks every row it closes.
     *
     * <p>Each row is handled on its own, so a row the broker refuses does not stop the rows behind
     * it. A row is marked only after its send returns, so no row is marked for a message the broker
     * never acknowledged. An abandoned row is left alone, and it is the one terminal state an
     * unpublished batch still holds.
     *
     * <p>The delay is measured from the end of one tick to the start of the next, so a slow batch
     * cannot overlap itself. An empty batch writes no log line.
     */
    @Scheduled(fixedDelayString = "${carddemo.outbox.relay.fixed-delay-ms:500}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEventEntity> pending =
                outboxEvents.findByPublishedFalseOrderByCreatedAtAsc(PageRequest.ofSize(batchSize));
        if (pending.isEmpty()) {
            return;
        }

        int published = 0;
        int failed = 0;
        for (OutboxEventEntity row : pending) {
            if (row.isTerminal()) {
                continue;
            }
            Class<?> recordType = recordTypesByEventType.get(row.getEventType());
            if (recordType == null) {
                failed++;
                meters.recordPublishFailure();
                routeToDeadLetter(row, DeadLetterMetadata.of(ABEND_CODE, CULPRIT,
                        UNKNOWN_TYPE_REASON, UNKNOWN_TYPE_MESSAGE));
                continue;
            }
            try {
                publishAndMark(row, recordType);
                published++;
            } catch (RuntimeException failure) {
                failed++;
                meters.recordPublishFailure();
                onFailedRow(row, failure);
            }
        }
        log.info("Outbox relay published {} rows this tick and failed {}", published, failed);
    }

    /**
     * Reads one row back into its record type, sends it, and marks the row published.
     *
     * <p>The stored text is read without reshaping, so the writer's document and the broker's
     * document are one flat object. A send that returns while the mark fails leaves the row
     * unpublished, and the next tick sends it again.
     *
     * @param row        the unpublished row
     * @param recordType the record its payload reads into
     * @throws SerializationException if the event does not satisfy its schema document
     * @throws JacksonException       if the payload does not read into {@code recordType}
     */
    private void publishAndMark(OutboxEventEntity row, Class<?> recordType) {
        Object event = objectMapper.readValue(row.getPayload(), recordType);

        kafkaTemplate.send(fraudAssessedTopic, row.getAggregateId(), event).join();
        row.markPublished(Instant.now());
        outboxEvents.save(row);
    }

    /**
     * Routes one failed row to the dead-letter topic, or leaves it for the next tick.
     *
     * <p>A failure the event contract raised is permanent, and the row travels to the dead-letter
     * topic. Every other failure is a broker or a network fault, and the row stays unpublished for
     * the next tick. The log line names the event type and the failure type. It carries no payload,
     * no message key and no event value.
     *
     * @param row     the row whose send failed
     * @param failure the failure the send raised
     */
    private void onFailedRow(OutboxEventEntity row, RuntimeException failure) {
        Throwable cause = rootCause(failure);
        if (isPermanent(failure)) {
            routeToDeadLetter(row, DeadLetterMetadata.fromFailure(ABEND_CODE, cause,
                    CONTRACT_REASON, CONTRACT_MESSAGE));
            return;
        }
        log.warn("An outbox row of type {} stays unpublished after {}, and the next tick takes it "
                + "again", row.getEventType(), cause.getClass().getSimpleName());
    }

    /**
     * Sends one dead letter for a row no tick can publish, then closes that row.
     *
     * <p>The dead letter carries the four diagnostic values, the topic the row was bound for, the
     * event identifier and the attempt this tick made. It carries no property of the failing
     * payload. Its message key is the account identifier of the row, so a dead letter lands on the
     * partition of the account it concerns.
     *
     * <p>The row is marked published once the send returns, which takes it out of every later
     * batch. A send that does not return leaves the row unpublished, and the next tick attempts the
     * row and this dead letter again.
     *
     * @param row      the row this relay cannot publish
     * @param metadata the four diagnostic values, each already held to its own width
     */
    private void routeToDeadLetter(OutboxEventEntity row, DeadLetterMetadata metadata) {
        try {
            Object deadLetter = metadata.toEnvelope(row.getAggregateId(), fraudAssessedTopic,
                    NO_SOURCE_PARTITION, NO_SOURCE_OFFSET, row.getEventId().toString(),
                    reportableEventType(row.getEventType()), row.getAttemptCount() + 1);

            kafkaTemplate.send(deadLetterTopic, row.getAggregateId(), deadLetter).join();
        } catch (RuntimeException undelivered) {
            log.error("The dead letter for an outbox row of type {} did not reach topic {} after "
                    + "{}, and the row stays unpublished", row.getEventType(), deadLetterTopic,
                    rootCause(undelivered).getClass().getSimpleName());
            return;
        }
        row.markPublished(Instant.now());
        outboxEvents.save(row);
        log.error("An outbox row of type {} reached topic {} and takes no further attempt",
                row.getEventType(), deadLetterTopic);
    }

    /**
     * Reports whether one failure is permanent.
     *
     * <p>The serializer raises {@link SerializationException} for an event its schema document
     * refuses, and the mapper raises {@link JacksonException} for a payload that does not read into
     * its record. Neither improves on a later attempt, and a broker or a network fault carries
     * neither type.
     *
     * <p>The walk follows the cause chain, since a resolved send wraps its cause, and it ends on a
     * chain that names itself as its own cause.
     *
     * @param failure the failure the send raised
     * @return true when a later attempt fails the same way
     */
    private static boolean isPermanent(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SerializationException || cause instanceof JacksonException) {
                return true;
            }
            Throwable next = cause.getCause();
            cause = next == cause ? null : next;
        }
        return false;
    }

    /**
     * Returns the deepest cause of one failure.
     *
     * <p>A resolved send wraps its cause, so the outermost type names the completion and not the
     * fault. The value returned here is the type a log line reports and the type a dead letter
     * carries as its culprit. The walk ends on a chain that names itself as its own cause.
     *
     * @param failure the failure the send raised
     * @return the deepest cause, and {@code failure} itself when it wraps none
     */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Returns the event type a dead letter may report, or null.
     *
     * <p>A stored type outside {@link #REPORTABLE_EVENT_TYPE} travels as null, which the
     * {@code failedEventType} property of {@code schemas/dead-letter-v1.json} accepts. Carrying
     * such a type fails the dead letter on its own document.
     *
     * @param eventType the type the row holds
     * @return {@code eventType} when the dead-letter document takes it, and null otherwise
     */
    private static String reportableEventType(String eventType) {
        if (eventType == null || !REPORTABLE_EVENT_TYPE.matcher(eventType).matches()) {
            return null;
        }
        return eventType;
    }

    /**
     * Checks the configured batch size and returns it.
     *
     * @param batchSize rows one tick reads
     * @return {@code batchSize}
     * @throws IllegalArgumentException if {@code batchSize} is below one
     */
    private static int requireBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "carddemo.outbox.relay.batch-size holds at least one row and resolved to "
                            + batchSize);
        }
        return batchSize;
    }

    /**
     * Checks one configured topic name and returns it trimmed. No failure message holds the value.
     *
     * @param topic        the resolved property value
     * @param propertyName the property that supplied it, named in a failure
     * @return {@code topic}, trimmed
     * @throws IllegalArgumentException if {@code topic} is null or blank
     */
    private static String requireTopic(String topic, String propertyName) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                    "Property " + propertyName + " resolved to no usable topic name.");
        }
        return topic.trim();
    }

    /**
     * Builds the one mapper this relay holds.
     *
     * <p>The setting that fails on an unknown property is enabled, matching the
     * {@code additionalProperties} of {@code false} both fraud schema documents set and the mapper
     * {@link OutboxWriter} writes with. No setting quotes an ordinary number, so
     * {@code schemaVersion} and {@code riskScore} read back as integers. No setting reads a date as
     * a number, so {@code occurredAt} and {@code assessedAt} read back from ISO-8601 text.
     *
     * @return the mapper, built once for each instance of this class
     */
    private static ObjectMapper eventMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
