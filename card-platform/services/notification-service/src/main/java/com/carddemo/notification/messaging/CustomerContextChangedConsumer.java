package com.carddemo.notification.messaging;

import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.repository.CardholderContextRepository;
import com.carddemo.notification.entity.ProcessedEventEntity;
import com.carddemo.notification.repository.ProcessedEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Reads one {@code CustomerContextChanged} event and applies it to the account-keyed cardholder
 * projection {@code messaging/TransactionPostedConsumer} renders an alert from.
 *
 * <p>{@code 5000-CREATE-STATEMENT} at {@code app/cbl/CBSTM03A.CBL:L458-L504} read the ten
 * cardholder fields from the customer record directly, through the copybook
 * {@code app/cbl/CBSTM03A.CBL:L55} copies, because one program held every dataset. The account
 * service owns the customer record here, so those ten fields reach this service on an event and
 * {@code entity/CardholderContextEntity} holds them.
 *
 * <p>The payload arrives as a checked tree rather than as a record.
 * {@code com.carddemo.events.serde.JsonSchemaValidatingDeserializer} holds the document
 * {@code schemas/customer-context-changed-v1.json} and names no class for this event type, since
 * the record belongs to the account service that publishes it. The tree this method receives has
 * already satisfied that document, so every property {@link #applyOneEvent} reads is present and
 * carries its declared form.
 *
 * <p>{@code repository/CardholderContextRepository#applyContextChange} carries the staleness
 * comparison in the statement, so an event older than the stored row writes nothing. A reordered
 * delivery therefore cannot move a row backwards.
 *
 * <p>ADDITIVE in full. No Common Business Oriented Language (COBOL) program consumes an event, and
 * none detects a duplicate delivery: the transaction write at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} meets a duplicate key on a replayed feed and ends the run
 * at L577.
 *
 * <p>For the path one message takes from publish through consume to the dead-letter topic, read
 * {@code card-platform/docs/event-flow.md}. The choices behind this listener sit in
 * {@code card-platform/docs/decision-log.md}.
 */
@Component
public class CustomerContextChangedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a cardholder value. */
    private static final Logger LOG =
            LoggerFactory.getLogger(CustomerContextChangedConsumer.class);

    /**
     * Failure classifier the dead-letter metadata carries, four characters wide.
     *
     * <p>{@code MOVE 999 TO ABCODE} at {@code app/cbl/CBTRN02C.cbl:L710} supplies the digits, and
     * {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22} supplies the width.
     */
    private static final String ABEND_CODE = "0999";

    /**
     * Identity of this listener in the dead-letter metadata, eight characters wide, from
     * {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    private static final String CULPRIT = "notif-cc";

    /**
     * Detail the dead-letter metadata carries on every failure.
     *
     * <p>The text names one field by its JavaScript Object Notation (JSON) pointer and holds no
     * field value. One local transaction covers the projection row and the marker, so a failure on
     * either leaves both unwritten.
     */
    private static final String NOTHING_WRITTEN =
            "no cardholder-context row or marker written for /accountId";

    /** Envelope property carrying the identifier the publishing service assigned. */
    private static final String EVENT_ID = "eventId";

    /** Envelope property carrying when the change occurred at its source. */
    private static final String OCCURRED_AT = "occurredAt";

    /** Payload property carrying the account the ten cardholder fields belong to. */
    private static final String ACCOUNT_ID = "accountId";

    /** The ten payload properties this listener reads, in the column order of the projection. */
    private static final String FIRST_NAME = "firstName";
    private static final String MIDDLE_NAME = "middleName";
    private static final String LAST_NAME = "lastName";
    private static final String ADDRESS_LINE_1 = "addressLine1";
    private static final String ADDRESS_LINE_2 = "addressLine2";
    private static final String ADDRESS_LINE_3 = "addressLine3";
    private static final String STATE_CODE = "stateCode";
    private static final String COUNTRY_CODE = "countryCode";
    private static final String ZIP_CODE = "zipCode";
    private static final String FICO_SCORE = "ficoScore";

    /** Reads and writes the account-keyed cardholder projection. */
    private final CardholderContextRepository cardholderContexts;

    /** Claims one event identifier, so a second delivery of it changes nothing. */
    private final ProcessedEventRepository processedEvents;

    /** Runs the claim and the projection write inside one local transaction. */
    private final TransactionTemplate transactionTemplate;

    /** Counts events read and duplicates skipped, times one delivery, and counts a failure. */
    private final NotificationMetrics metrics;

    /**
     * Takes the two repositories, the transaction runner and the meters.
     *
     * @param cardholderContexts store of the account-keyed cardholder projection
     * @param processedEvents    store of duplicate-delivery markers
     * @param transactionTemplate runner of the one local transaction this listener opens
     * @param metrics            the meter holder {@code config/ObservabilityConfig} registers
     * @throws NullPointerException if any argument is null
     */
    public CustomerContextChangedConsumer(CardholderContextRepository cardholderContexts,
            ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate,
            NotificationMetrics metrics) {
        this.cardholderContexts =
                Objects.requireNonNull(cardholderContexts, "cardholderContexts is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
    }

    /**
     * Reads one delivery, applies it inside one local transaction, and acknowledges.
     *
     * <p>The topic and the consumer group both resolve from configuration, so neither name appears
     * here as text.
     *
     * <p>A failure leaves the offset uncommitted and travels to the listener container, which
     * decides between another delivery attempt and the dead-letter topic. The acknowledgement below
     * is unreachable on that path, so a repeat delivery follows and the claim keeps it harmless.
     *
     * @param event          the checked tree this delivery carries
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @throws NullPointerException if {@code event} or {@code acknowledgment} is null
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.customer-context-changed}",
            groupId = "${carddemo.kafka.groups.customer-context-changed}")
    public void onCustomerContextChanged(JsonNode event, Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        UUID eventId = eventId(event);
        metrics.eventsConsumed(NotificationMetrics.EVENT_CUSTOMER_CONTEXT_CHANGED).increment();
        long startedAt = System.nanoTime();
        try {
            transactionTemplate
                    .executeWithoutResult(status -> applyOneEvent(event, eventId, consumedTopic));
        } catch (RuntimeException failure) {
            reportFailure(eventId, failure);
            throw failure;
        } finally {
            metrics.processingLatency(NotificationMetrics.EVENT_CUSTOMER_CONTEXT_CHANGED)
                    .record(Duration.ofNanos(System.nanoTime() - startedAt));
        }

        acknowledgment.acknowledge();
    }

    /**
     * Applies one event inside the open transaction, in a fixed order.
     *
     * <p>The claim runs first. An event already claimed leaves the projection untouched, and the
     * claim commits with the projection write it guards.
     *
     * <p>The write reports zero rows when the stored row carries a {@code source_occurred_at} at
     * least as recent as the arriving one. That is an ordinary outcome for a reordered delivery, so
     * it raises nothing and the claim still stands: the event has been handled and will not be
     * applied again.
     *
     * @param event         the checked tree this delivery carries
     * @param eventId       the identifier the publishing service assigned
     * @param consumedTopic the topic the delivery arrived on, recorded on the marker
     */
    private void applyOneEvent(JsonNode event, UUID eventId, String consumedTopic) {
        Instant now = Instant.now();
        if (processedEvents.claimEvent(eventId, now, consumedTopicOrSentinel(consumedTopic))
                == ProcessedEventRepository.ALREADY_CLAIMED) {
            metrics.duplicatesSkipped().increment();
            LOG.debug("Event {} carries a marker already, so this delivery writes nothing.",
                    eventId);
            return;
        }

        int rowsWritten = cardholderContexts.applyContextChange(
                text(event, ACCOUNT_ID),
                text(event, FIRST_NAME),
                text(event, MIDDLE_NAME),
                text(event, LAST_NAME),
                text(event, ADDRESS_LINE_1),
                text(event, ADDRESS_LINE_2),
                text(event, ADDRESS_LINE_3),
                text(event, STATE_CODE),
                text(event, COUNTRY_CODE),
                text(event, ZIP_CODE),
                text(event, FICO_SCORE),
                occurredAt(event),
                now);

        if (rowsWritten == CardholderContextRepository.NO_ROW_WRITTEN) {
            LOG.debug("Event {} carries a change no later than the stored one, so the projection"
                    + " keeps the row it holds.", eventId);
        }
    }

    /**
     * Reads the identifier the publishing service assigned.
     *
     * @param event the checked tree
     * @return the identifier
     * @throws IllegalArgumentException when the property is absent or is not a valid identifier
     */
    private static UUID eventId(JsonNode event) {
        try {
            return UUID.fromString(text(event, EVENT_ID));
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("/" + EVENT_ID
                    + " does not carry an event identifier this service reads.", malformed);
        }
    }

    /**
     * Reads when the change occurred at its source.
     *
     * @param event the checked tree
     * @return the instant the envelope carries
     * @throws IllegalArgumentException when the property is absent or is not an instant
     */
    private static Instant occurredAt(JsonNode event) {
        String value = text(event, OCCURRED_AT);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException malformed) {
            throw new IllegalArgumentException("/" + OCCURRED_AT
                    + " does not carry an instant this service reads.", malformed);
        }
    }

    /**
     * Reads one text property of the checked tree.
     *
     * <p>The document behind this event type declares every property below as required, so an
     * absent one means the tree reaching this method was not the tree the deserializer checked. The
     * message names the property by its JSON pointer and never its value.
     *
     * @param event the checked tree
     * @param property the property name
     * @return the property value
     * @throws IllegalArgumentException when the property is absent or carries no text
     */
    private static String text(JsonNode event, String property) {
        JsonNode value = event.path(property);
        if (!value.isString()) {
            throw new IllegalArgumentException(
                    "/" + property + " carries no text, and this event declares it required.");
        }
        return value.stringValue();
    }

    /**
     * Names the topic this delivery arrived on, for the half of the marker key that holds it.
     *
     * <p>The topic is half of {@code pk_processed_event}, so it holds no null. A delivery that
     * carried no topic header records {@link ProcessedEventEntity#NO_CONSUMED_TOPIC}, which states
     * that absence in a value no real topic name can equal.</p>
     *
     * @param consumedTopic the topic the delivery arrived on, possibly null or blank
     * @return the topic, or {@link ProcessedEventEntity#NO_CONSUMED_TOPIC} when it named none
     */
    private static String consumedTopicOrSentinel(String consumedTopic) {
        return consumedTopic == null || consumedTopic.isBlank()
                ? ProcessedEventEntity.NO_CONSUMED_TOPIC
                : consumedTopic;
    }

    /**
     * Counts one failure and reports its four metadata fields, leaving the throw to the caller.
     *
     * <p>The four fields carry the layout of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}. {@code ABEND-REASON} names the exception type and
     * {@code ABEND-MSG} names one field by its JSON pointer, so no field value and no payload
     * reaches a log line. Retry counting and routing to the dead-letter topic belong to the
     * listener container in {@code com.carddemo.notification.config}.
     *
     * <p>The level is {@code WARN} because this line reports one attempt and a retry may still
     * succeed. The terminal outcome is reported once, at {@code ERROR}, by the recoverer in
     * {@code config/KafkaConsumerConfig} when the attempts are spent, and that line is the one an
     * alerting rule should watch. Reporting each attempt at {@code ERROR} put three of them on a
     * record that recovered on the third try, which made a transient fault indistinguishable from a
     * permanent one.
     *
     * @param eventId the identifier of the event that failed
     * @param failure the fault this delivery raised
     */
    private void reportFailure(UUID eventId, RuntimeException failure) {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(ABEND_CODE, CULPRIT,
                failure.getClass().getSimpleName(), NOTHING_WRITTEN);
        metrics.failures(failureKind(failure)).increment();

        LOG.atWarn()
                .addKeyValue("abendCode", metadata.abendCode())
                .addKeyValue("abendCulprit", metadata.culprit())
                .addKeyValue("abendReason", metadata.reason())
                .addKeyValue("abendMessage", metadata.message())
                .log("Event {} was not applied, and this delivery stays unacknowledged.", eventId);
    }

    /**
     * Names the failure kind one fault counts under.
     *
     * <p>A database fault counts as a persistence failure and every other fault as a schema
     * validation failure, since a tree this listener cannot read is a tree that did not carry what
     * its document declares. {@code ObservabilityConfig.NotificationMetrics#isPersistenceFault}
     * decides the first case for the whole service, and it names a transaction fault as well as a
     * data-access fault: a paused or unreachable database raises
     * {@code CannotCreateTransactionException} from the connection pool, which is the former.
     * {@code config/ObservabilityConfig} registers both series.
     *
     * @param failure the fault this delivery raised
     * @return the tag value the failure counter carries
     */
    private static String failureKind(RuntimeException failure) {
        return NotificationMetrics.isPersistenceFault(failure)
                ? NotificationMetrics.FAILURE_PERSISTENCE
                : NotificationMetrics.FAILURE_SCHEMA_VALIDATION;
    }
}
