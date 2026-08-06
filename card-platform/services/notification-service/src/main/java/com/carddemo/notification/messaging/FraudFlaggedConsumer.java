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
 * <p>This listener is the second independent reader of a second event, and it reads an event that
 * already travels: the fraud service publishes it and knows nothing of this service. Adding this
 * class needed no change to the fraud service, to the ledger service or to the authorization
 * service.
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
 * <p>No delivery-attempt row is written here. {@code notification_log} keys a row by card token and
 * masked card number, and an assessment carries neither.
 *
 * <p>For the path one message takes from publish through consume to the dead-letter topic, read
 * {@code card-platform/docs/event-flow.md}. The choices behind this listener sit in
 * {@code card-platform/docs/decision-log.md}.
 */
@Component
public class FraudFlaggedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a cardholder value. */
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
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @throws NullPointerException if {@code event} or {@code acknowledgment} is null
     * @throws IllegalArgumentException if the payload is neither assessment outcome
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.fraud-assessed}",
            groupId = "${carddemo.kafka.groups.fraud-assessed}")
    public void onFraudAssessed(Record event, Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        if (event instanceof FraudFlagged flagged) {
            applyFlagged(flagged, consumedTopic);
        } else if (event instanceof FraudCleared cleared) {
            applyCleared(cleared, consumedTopic);
        } else {
            throw refusePayload(event);
        }

        acknowledgment.acknowledge();
    }

    /**
     * Claims one flagged assessment, renders its alert, and times the delivery.
     *
     * @param event         the flagged assessment
     * @param consumedTopic the topic the delivery arrived on
     */
    private void applyFlagged(FraudFlagged event, String consumedTopic) {
        metrics.eventsConsumed(NotificationMetrics.EVENT_FRAUD_FLAGGED).increment();
        long startedAt = System.nanoTime();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (claimed(event.eventId(), consumedTopic)) {
                    notificationService.renderFraudAlert(event.transactionId(), event.accountId(),
                            event.riskScore(), event.triggeredRules(),
                            cardholderDetails(event.accountId()), RenderedFormat.PLAIN_TEXT);
                }
            });
        } catch (RuntimeException failure) {
            reportFailure(event.eventId(), failure);
            throw failure;
        } finally {
            metrics.processingLatency(NotificationMetrics.EVENT_FRAUD_FLAGGED)
                    .record(Duration.ofNanos(System.nanoTime() - startedAt));
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
        metrics.eventsConsumed(NotificationMetrics.EVENT_FRAUD_CLEARED).increment();
        long startedAt = System.nanoTime();
        try {
            transactionTemplate
                    .executeWithoutResult(status -> claimed(event.eventId(), consumedTopic));
        } catch (RuntimeException failure) {
            reportFailure(event.eventId(), failure);
            throw failure;
        } finally {
            metrics.processingLatency(NotificationMetrics.EVENT_FRAUD_CLEARED)
                    .record(Duration.ofNanos(System.nanoTime() - startedAt));
        }
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
     * <p>ADDITIVE. The four fields carry the layout of {@code 01 ABEND-DATA} at
     * {@code app/cpy/CSMSG02Y.cpy:L21-L29}, a copybook the statement program never copies.
     * {@code ABEND-REASON} names the exception type and {@code ABEND-MSG} names one field by its
     * JSON pointer, so no field value and no payload reaches a log line. Retry counting and routing
     * to the dead-letter topic belong to the listener container in
     * {@code com.carddemo.notification.config}.
     *
     * @param eventId the identifier of the event that failed
     * @param failure the fault this delivery raised
     */
    private void reportFailure(UUID eventId, RuntimeException failure) {
        DeadLetterMetadata metadata = DeadLetterMetadata.of(ABEND_CODE, CULPRIT,
                failure.getClass().getSimpleName(), NOTHING_WRITTEN);
        metrics.failures(failureKind(failure)).increment();

        LOG.atError()
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
     * @param event the payload this delivery carried
     * @return the refusal the caller throws, which leaves the offset uncommitted
     */
    private IllegalArgumentException refusePayload(Record event) {
        String received = event.getClass().getSimpleName();
        DeadLetterMetadata metadata =
                DeadLetterMetadata.of(CONTRACT_CODE, CULPRIT, received, UNREADABLE_PAYLOAD);
        metrics.failures(NotificationMetrics.FAILURE_SCHEMA_VALIDATION).increment();

        LOG.atError()
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
     * Names the failure kind one fault counts under.
     *
     * <p>A database fault counts as a persistence failure and every other fault as a rendering
     * failure. {@code ObservabilityConfig.NotificationMetrics#isPersistenceFault} decides the first
     * case for the whole service, and it names a transaction fault as well as a data-access fault:
     * an unreachable database raises {@code CannotCreateTransactionException} from the connection
     * pool. {@code config/ObservabilityConfig} registers both series.
     *
     * @param failure the fault this delivery raised
     * @return the tag value the failure counter carries
     */
    private static String failureKind(RuntimeException failure) {
        return NotificationMetrics.isPersistenceFault(failure)
                ? NotificationMetrics.FAILURE_PERSISTENCE
                : NotificationMetrics.FAILURE_RENDERING;
    }
}
