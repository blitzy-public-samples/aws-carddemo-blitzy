package com.carddemo.notification.messaging;

import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.CardholderContextReader;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.domain.NotificationService.CardholderDetails;
import com.carddemo.notification.entity.ProcessedEventEntity;
import com.carddemo.notification.repository.ProcessedEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads one risk assessment and renders the cardholder alert a flagged transaction produces.
 *
 * <p>ADDITIVE in full. {@code app/cbl/CBSTM03A.CBL} carries no fraud concept, and no Common
 * Business Oriented Language (COBOL) program in CardDemo scores a transaction. The cardholder
 * fields the alert reports are the fields {@code 5000-CREATE-STATEMENT} assembles at
 * {@code app/cbl/CBSTM03A.CBL:L458-L504}.
 *
 * <p>This listener reads an event the fraud service publishes without knowing of this service, and
 * calls no other service.
 *
 * <p>One topic carries both assessment outcomes. {@link FraudFlagged} and {@link FraudCleared}
 * travel together on the assessment topic, and {@link #onFraudAssessed} routes on the concrete type
 * the deserializer built from the envelope {@code eventType} field. A payload parameter narrowed to
 * one of the two records sends every delivery of the other to the dead-letter topic. A cleared
 * assessment produces no alert and still claims its event, so a redelivery of it stays harmless.
 *
 * <p>Correlation is durable. The alert names the cardholder of the account the assessment carries,
 * read from {@code cardholder_context}, which {@code messaging/CustomerContextChangedConsumer}
 * fills. Nothing about the assessment is held in memory between two deliveries. An account the
 * projection holds no row for fails the delivery, so the gap reaches the dead-letter topic in place
 * of an alert carrying no name and no address.
 *
 * <p>No rendered-alert row is written here. {@code notification_log} keys a row by {@code id},
 * and its {@code card_token} and {@code masked_card_number} columns are {@code NOT NULL}: the
 * first is what {@code ix_notification_log_card_token} reads one card's rendered history by, and
 * an assessment carries neither value.
 *
 * <p>For the path one message takes from publish through consume to the dead-letter topic, read
 * {@code card-platform/docs/event-flow.md}. The choices behind this listener sit in
 * {@code card-platform/docs/decision-log.md}.
 */
@Component
public class FraudFlaggedConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(FraudFlaggedConsumer.class);

    /**
     * Failure classifier the dead-letter metadata carries, four characters wide.
     *
     * <p>{@code MOVE 999 TO ABCODE} at {@code app/cbl/CBTRN02C.cbl:L710} supplies the digits, and
     * {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22} supplies the width.
     */
    private static final String ABEND_CODE = "0999";

    /**
     * Failure classifier a payload outside the two assessment outcomes carries, four characters
     * wide, from {@code ABEND-CODE PIC X(4)} at {@code app/cpy/CSMSG02Y.cpy:L22}.
     */
    private static final String CONTRACT_CODE = "TYPE";

    /**
     * Identity of this listener in the dead-letter metadata, eight characters wide, from
     * {@code ABEND-CULPRIT PIC X(8)} at {@code app/cpy/CSMSG02Y.cpy:L24}.
     */
    private static final String CULPRIT = "notif-fa";

    /**
     * Detail the dead-letter metadata carries on every failure.
     *
     * <p>The text names one field by its JavaScript Object Notation (JSON) pointer and holds no
     * field value. One local transaction covers the claim and the alert, so a failure on either
     * leaves both unwritten.
     */
    private static final String NOTHING_WRITTEN =
            "no alert rendered or marker written for /transactionId";

    /**
     * Detail the dead-letter metadata carries for a payload this listener does not read.
     *
     * <p>The text names the envelope field the deserializer resolved the payload type from, and it
     * holds no field value.
     */
    private static final String UNREADABLE_PAYLOAD =
            "/eventType names a payload outside the two assessment outcomes";

    /**
     * Reason the dead-letter metadata carries for a record refused on its key.
     *
     * <p>Fifty characters is the width {@link DeadLetterMetadata#REASON_MAX_LENGTH} declares, and
     * this text is shorter. It names which three values disagreed and none of their values.
     */
    private static final String KEY_DISAGREEMENT = "KeyAggregateAccountDisagreement";

    /**
     * Detail the dead-letter metadata carries for a record refused on its key.
     *
     * <p>The text names the two envelope fields and the key by their contract names, and it holds no
     * field value, so no account identifier reaches the diagnostic through it.
     */
    private static final String KEY_MISMATCH_DETAIL =
            "the key, /aggregateId and /accountId must carry one value";

    /** Reads the account-keyed cardholder fields one alert reports. */
    private final CardholderContextReader cardholderContextReader;

    /** Claims one event identifier, so a second delivery of it changes nothing. */
    private final ProcessedEventRepository processedEvents;

    /** Renders the cardholder alert. */
    private final NotificationService notificationService;

    /** Runs the claim and the render inside one local transaction. */
    private final TransactionTemplate transactionTemplate;

    /** Counts events read and duplicates skipped, times one delivery, and counts a failure. */
    private final NotificationMetrics metrics;

    /**
     * Takes the projection reader, the marker store, the domain service, the transaction runner and
     * the meters.
     *
     * @param cardholderContextReader reader of the account-keyed cardholder projection
     * @param processedEvents         store of duplicate-delivery markers
     * @param notificationService     renderer of the cardholder alert
     * @param transactionTemplate     runner of the one local transaction this listener opens
     * @param metrics                 the meter holder {@code config/ObservabilityConfig} registers
     * @throws NullPointerException if any argument is null
     */
    public FraudFlaggedConsumer(CardholderContextReader cardholderContextReader,
            ProcessedEventRepository processedEvents, NotificationService notificationService,
            TransactionTemplate transactionTemplate, NotificationMetrics metrics) {
        this.cardholderContextReader = Objects.requireNonNull(cardholderContextReader,
                "cardholderContextReader is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.notificationService =
                Objects.requireNonNull(notificationService, "notificationService is required");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics is required");
    }

    /**
     * Reads one delivery of either assessment outcome, applies it inside one local transaction, and
     * acknowledges.
     *
     * <p>The topic and the consumer group both resolve from configuration, so neither name appears
     * here as text. The payload parameter is {@link Record}, which binds the event the deserializer
     * built and leaves the transport record to the listener adapter. Both assessment outcomes are
     * records, and this method refuses any other payload.
     *
     * <p>A failure leaves the offset uncommitted and travels to the listener container, which
     * decides between another delivery attempt and the dead-letter topic. The acknowledgement below
     * is unreachable on that path, so a repeat delivery follows and the claim keeps it harmless.
     *
     * @param event          the validated event this delivery carries, flagged or cleared
     * @param messageKey     the key the record arrived under, which must name the account the
     *                       payload names
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @throws NullPointerException if {@code event} or {@code acknowledgment} is null
     * @throws IllegalArgumentException if the payload is neither assessment outcome, or the key
     *                                  names another account
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.fraud-assessed}",
            groupId = "${carddemo.kafka.groups.fraud-assessed}")
    public void onFraudAssessed(Record event,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey,
            Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        String eventType = recordedEventType(event);
        metrics.eventsConsumed(eventType).increment();
        long startedAt = System.nanoTime();
        try {
            if (event instanceof FraudFlagged flagged) {
                requireKeyNamesAggregate(messageKey, flagged.aggregateId(), flagged.accountId());
                applyFlagged(flagged, consumedTopic);
            } else if (event instanceof FraudCleared cleared) {
                requireKeyNamesAggregate(messageKey, cleared.aggregateId(), cleared.accountId());
                applyCleared(cleared, consumedTopic);
            } else {
                throw refusePayload(event);
            }
        } finally {
            metrics.processingLatency(eventType)
                    .record(Duration.ofNanos(System.nanoTime() - startedAt));
        }

        acknowledgment.acknowledge();
    }

    /**
     * Names the series a delivery is measured on.
     *
     * <p>A payload outside the two assessment outcomes resolves to
     * {@link NotificationMetrics#UNKNOWN}, which is already a declared value of the
     * {@code eventType} tag, so a refused payload is measured rather than unmeasured and no new tag
     * value is introduced.
     *
     * @param event the payload this delivery carried
     * @return the {@code eventType} tag value
     */
    private static String recordedEventType(Record event) {
        if (event instanceof FraudFlagged) {
            return NotificationMetrics.EVENT_FRAUD_FLAGGED;
        }
        if (event instanceof FraudCleared) {
            return NotificationMetrics.EVENT_FRAUD_CLEARED;
        }
        return NotificationMetrics.UNKNOWN;
    }

    /**
     * Claims one flagged assessment, renders its alert, and times the delivery.
     *
     * @param event         the flagged assessment
     * @param consumedTopic the topic the delivery arrived on
     */
    private void applyFlagged(FraudFlagged event, String consumedTopic) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (claimed(event.eventId(), consumedTopic)) {
                    CardholderDetails cardholder = cardholderDetails(event.accountId());
                    // One alert per format, as app/cbl/CBSTM03A.CBL writes one statement per output
                    // file: FD-STMTFILE-REC PIC X(80) at :L45 and FD-HTMLFILE-REC PIC X(100) at
                    // :L47, both written in the same run.
                    for (RenderedFormat format : RenderedFormat.values()) {
                        notificationService.renderFraudAlert(event.transactionId(),
                                event.accountId(), event.riskScore(), event.triggeredRules(),
                                cardholder, format);
                    }
                }
            });
        } catch (RuntimeException failure) {
            reportFailure(event.eventId(), failure);
            throw failure;
        }
    }

    /**
     * Claims one cleared assessment and renders nothing.
     *
     * <p>A cleared assessment tells a cardholder nothing, so no alert follows it. The claim still
     * commits, which is what makes a redelivery of it harmless.
     *
     * @param event         the cleared assessment
     * @param consumedTopic the topic the delivery arrived on
     */
    private void applyCleared(FraudCleared event, String consumedTopic) {
        try {
            transactionTemplate
                    .executeWithoutResult(status -> claimed(event.eventId(), consumedTopic));
        } catch (RuntimeException failure) {
            reportFailure(event.eventId(), failure);
            throw failure;
        }
    }

    /**
     * Refuses a record whose key does not name the aggregate its payload names.
     *
     * <p>Kafka orders records inside one partition and nowhere else, and the key chooses the
     * partition. AAP 0.3.1 makes the account identifier the key of every event for exactly
     * that reason, and the document behind this event states the rule outright: the Kafka message
     * key, {@code aggregateId} and {@code accountId} all carry one value, so account
     * identity has a single source. Schema validation checks the shape of each of the three and not
     * their agreement, so a producer with write access to this topic could place one
     * account's payload on another's partition and pass every check before this one.
     *
     * <p>The two transaction listeners of this service already make this check. It belongs on this
     * stream for a reason of its own: the account this payload names is the account whose
     * cardholder details the alert is addressed with, so an assessment applied under the wrong key
     * would render one cardholder a fraud alert about another cardholder's transaction, and record
     * having done so. Nothing sends that alert, so the disclosure would be to whatever reads the
     * rendered text rather than to the cardholder; the check refuses it either way. Both outcomes
     * on this topic are checked, because both carry an account and only one of them renders
     * anything.
     *
     * <p>All three values are compared rather than the key against one of them. A key that matches
     * {@code aggregateId} while {@code accountId} names something else would route correctly and
     * write to the wrong row, which is the same defect one field further in.
     *
     * <p>The refusal is an {@link IllegalArgumentException} raised before anything is claimed or
     * written, so nothing is applied, the delivery is retried, and a spent record reaches the
     * sanitized dead-letter route of {@code config/KafkaConsumerConfig}. Neither message names the
     * key, the aggregate or the account, so no identifier reaches a log line through them. The
     * refusal is counted by {@link #refuseKey(String)}, so a rejected delivery is visible as a
     * reading rather than only as a log line.
     *
     * @param messageKey  the key the record arrived under, possibly {@code null}
     * @param aggregateId the aggregate the envelope names
     * @param accountId   the account the payload names
     * @throws IllegalArgumentException when the key is absent or the three do not agree
     */
    private void requireKeyNamesAggregate(String messageKey, String aggregateId, String accountId) {
        if (messageKey == null || messageKey.isBlank()) {
            throw refuseKey("this record carries no message key, so the"
                    + " partition it arrived on is not the one that orders its account");
        }
        if (!messageKey.equals(aggregateId) || !messageKey.equals(accountId)) {
            throw refuseKey("the message key, the aggregate and the"
                    + " account this payload names do not agree, so the partition this"
                    + " record arrived on is not the one that orders that account");
        }
    }

    /**
     * Counts one key refusal, reports its four metadata fields, and builds the refusal to throw.
     *
     * <p>ADDITIVE, on the same layout {@link #refusePayload(Record)} carries, and counted on the
     * same {@code schema_validation} value of the {@code failure.kind} tag. Both refusals reject a
     * record for disagreeing with the document behind the event rather than for a fault in this
     * service, and the document states the key rule as plainly as it states a field type. A refusal
     * that counted nothing left a rejected delivery indistinguishable from one that never arrived,
     * which is the state an observability review found.
     *
     * <p>The level is {@code WARN} because this line reports one attempt. The refusal repeats until
     * the attempts are spent, and the terminal outcome is reported once at {@code ERROR} by the
     * recoverer in {@code config/KafkaConsumerConfig}.
     *
     * <p>Neither the message nor the metadata names the key, the aggregate or the account, so no
     * identifier reaches a log line through them.
     *
     * @param explanation what disagreed, naming no value
     * @return the refusal the caller throws, which leaves the offset uncommitted
     */
    private IllegalArgumentException refuseKey(String explanation) {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(CONTRACT_CODE, CULPRIT,
                KEY_DISAGREEMENT, KEY_MISMATCH_DETAIL);
        metrics.failures(NotificationMetrics.FAILURE_SCHEMA_VALIDATION).increment();

        LOG.atWarn()
                .addKeyValue("abendCode", metadata.abendCode())
                .addKeyValue("abendCulprit", metadata.culprit())
                .addKeyValue("abendReason", metadata.reason())
                .addKeyValue("abendMessage", metadata.message())
                .log("A record on the assessment topic was refused on its key, and this delivery"
                        + " stays unacknowledged.");

        return new IllegalArgumentException(explanation);
    }

    /**
     * Claims one event identifier inside the open transaction, and reports whether this delivery
     * took it.
     *
     * <p>ADDITIVE. The source carries no duplicate detection: a replayed feed drives
     * {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579} into a
     * duplicate-key condition and on to its abend routine. One statement claims the event here, so
     * no delivery reads the marker table and then writes it. The claim and whatever follows it
     * commit together or not at all.
     *
     * @param eventId       the identifier the fraud service assigned
     * @param consumedTopic the topic the delivery arrived on, recorded on the marker
     * @return {@code true} when this delivery took the event, and {@code false} when the marker was
     *         already held
     */
    private boolean claimed(UUID eventId, String consumedTopic) {
        if (processedEvents.claimEvent(eventId, Instant.now(), consumedTopicOrSentinel(consumedTopic))
                == ProcessedEventRepository.ALREADY_CLAIMED) {
            metrics.duplicatesSkipped().increment();
            LOG.debug("Event {} carries a marker already, so this delivery writes nothing.",
                    eventId);
            return false;
        }
        return true;
    }

    /**
     * Reads the ten cardholder fields one account carries, refusing when the projection holds none.
     *
     * <p>{@link CardholderContextReader#require(String)} raises a fault for an account the
     * projection holds no row for, which leaves the offset uncommitted and has the delivery taken
     * again. {@code messaging/CustomerContextChangedConsumer} fills that projection.
     *
     * @param accountId the account the assessment names
     * @return the cardholder fields the projection holds for the account
     */
    private CardholderDetails cardholderDetails(String accountId) {
        return cardholderContextReader.require(accountId);
    }

    /**
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
     * <p>ADDITIVE. The four fields carry the layout of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}, a copybook the statement program never copies.
     * {@code ABEND-REASON} names the exception type and {@code ABEND-MSG} names one field by its
     * JSON pointer, so no field value and no payload reaches a log line. Retry counting and routing
     * to the dead-letter topic belong to the listener container in
     * {@code com.carddemo.notification.config}.
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
     * Counts one contract failure, reports its four metadata fields, and builds the refusal the
     * caller throws.
     *
     * <p>ADDITIVE, on the same layout {@link #reportFailure(UUID, RuntimeException)} carries.
     * {@code ABEND-REASON} names the record type the deserializer built and {@code ABEND-MSG} names
     * one envelope field by its JSON pointer, so no field value reaches a log line. A payload
     * outside the two assessment outcomes repeats on every attempt, and the listener container
     * routes it to the dead-letter topic once its attempts run out.
     *
     * <p>The level is {@code WARN} because this line reports one attempt, as every per-attempt line
     * of this platform does. This refusal will repeat until the attempts are spent, and the terminal
     * outcome is reported once at {@code ERROR} by the recoverer in
     * {@code config/KafkaConsumerConfig}. Reporting the attempt at {@code ERROR} as well counted the
     * same record three times at the level an alerting rule watches.
     *
     * @param event the payload this delivery carried
     * @return the refusal the caller throws, which leaves the offset uncommitted
     */
    private IllegalArgumentException refusePayload(Record event) {
        String received = event.getClass().getSimpleName();
        DeadLetterMetadata metadata =
                DeadLetterMetadata.of(CONTRACT_CODE, CULPRIT, received, UNREADABLE_PAYLOAD);
        metrics.failures(NotificationMetrics.FAILURE_SCHEMA_VALIDATION).increment();

        LOG.atWarn()
                .addKeyValue("abendCode", metadata.abendCode())
                .addKeyValue("abendCulprit", metadata.culprit())
                .addKeyValue("abendReason", metadata.reason())
                .addKeyValue("abendMessage", metadata.message())
                .log("A payload of type {} arrived on the assessment topic, and this delivery stays"
                        + " unacknowledged.", received);

        return new IllegalArgumentException("The record on this topic carries a " + received
                + ", and this listener reads a flagged or a cleared assessment.");
    }

    /**
     * @param failure the fault this delivery raised
     * @return the tag value the failure counter carries
     */
    private static String failureKind(RuntimeException failure) {
        return NotificationMetrics.isPersistenceFault(failure)
                ? NotificationMetrics.FAILURE_PERSISTENCE
                : NotificationMetrics.FAILURE_RENDERING;
    }
}
