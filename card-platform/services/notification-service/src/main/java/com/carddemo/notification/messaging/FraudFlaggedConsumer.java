package com.carddemo.notification.messaging;

import com.carddemo.events.FraudCleared;
import com.carddemo.events.FraudFlagged;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationService;
import com.carddemo.notification.domain.NotificationService.CardholderDetails;
import com.carddemo.notification.entity.CardholderContextEntity;
import com.carddemo.notification.repository.CardholderContextRepository;
import com.carddemo.notification.repository.ProcessedEventRepository;
import java.time.Duration;
import java.time.Instant;
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

/**
 * Reads one risk assessment and renders the cardholder alert a flagged transaction produces.
 *
 * <p>ADDITIVE in full. {@code app/cbl/CBSTM03A.CBL} carries no fraud concept, and no Common Business
 * Oriented Language (COBOL) program in CardDemo scores a transaction. The cardholder fields the
 * alert reports are the fields {@code 5000-CREATE-STATEMENT} assembles at
 * {@code app/cbl/CBSTM03A.CBL:L458-L504}.
 *
 * <p>This listener is the second independent reader of a second event, and it reads an event that
 * already travels: the fraud service publishes it and knows nothing of this service. Adding this
 * class needed no change to the fraud service, to the ledger service or to the authorization
 * service.
 *
 * <p>One topic carries both assessment outcomes. {@link FraudFlagged} and {@link FraudCleared} both
 * travel on {@code fraud.assessed} and {@link #onFraudAssessed} routes on the concrete type the
 * deserializer built from the envelope {@code eventType} field. A cleared assessment produces no
 * alert and still claims its event, so a redelivery of it stays harmless.
 *
 * <p>Correlation is durable. The alert names the cardholder of the account the assessment carries,
 * read from {@code cardholder_context}, which {@code messaging/CustomerContextChangedConsumer}
 * fills. Nothing about the assessment is held in memory between two deliveries, and an account with
 * no row there renders as spaces, the state {@code INITIALIZE STATEMENT-LINES} at
 * {@code app/cbl/CBSTM03A.CBL:L459} leaves.
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

    /** Reads the account-keyed cardholder fields one alert reports. */
    private final CardholderContextRepository cardholderContexts;

    /** Claims one event identifier, so a second delivery of it changes nothing. */
    private final ProcessedEventRepository processedEvents;

    /** Renders the cardholder alert. */
    private final NotificationService notificationService;

    /** Runs the claim and the render inside one local transaction. */
    private final TransactionTemplate transactionTemplate;

    /** Counts events read and duplicates skipped, times one delivery, and counts a failure. */
    private final NotificationMetrics metrics;

    /**
     * Takes the two repositories, the domain service, the transaction runner and the meters.
     *
     * @param cardholderContexts  store of the account-keyed cardholder projection
     * @param processedEvents     store of duplicate-delivery markers
     * @param notificationService renderer of the cardholder alert
     * @param transactionTemplate runner of the one local transaction this listener opens
     * @param metrics             the meter holder {@code config/ObservabilityConfig} registers
     * @throws NullPointerException if any argument is null
     */
    public FraudFlaggedConsumer(CardholderContextRepository cardholderContexts,
            ProcessedEventRepository processedEvents, NotificationService notificationService,
            TransactionTemplate transactionTemplate, NotificationMetrics metrics) {
        this.cardholderContexts =
                Objects.requireNonNull(cardholderContexts, "cardholderContexts is required");
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
     * here as text. The payload type is the record the deserializer built, and this method accepts
     * the two the shared topic carries. Any other payload is refused, which routes the record to the
     * dead-letter topic rather than acknowledging an event no listener understood.
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
    // The payload parameter is java.lang.Record and not Object on purpose. An Object
    // parameter matches the ConsumerRecord the container supplies before payload
    // resolution runs, so a valid assessment reaches the default branch and is
    // dead-lettered. Record matches only the deserialized event.
    @KafkaListener(topics = "${carddemo.kafka.topics.fraud-assessed}",
            groupId = "${carddemo.kafka.groups.fraud-assessed}")
    public void onFraudAssessed(Record event, Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        switch (event) {
            case FraudFlagged flagged -> applyFlagged(flagged, consumedTopic);
            case FraudCleared cleared -> applyCleared(cleared, consumedTopic);
            default -> throw new IllegalArgumentException("The record on this topic carries a "
                    + event.getClass().getSimpleName()
                    + ", and this listener reads a flagged or a cleared assessment.");
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
     * <p>One statement claims the event, so no delivery reads the marker table and then writes it.
     * The claim and whatever follows it commit together or not at all.
     *
     * @param eventId       the identifier the fraud service assigned
     * @param consumedTopic the topic the delivery arrived on, recorded on the marker
     * @return {@code true} when this delivery took the event, and {@code false} when the marker was
     *         already held
     */
    private boolean claimed(UUID eventId, String consumedTopic) {
        if (processedEvents.claimEvent(eventId, Instant.now(), topicOrNull(consumedTopic))
                == ProcessedEventRepository.ALREADY_CLAIMED) {
            metrics.duplicatesSkipped().increment();
            LOG.debug("Event {} carries a marker already, so this delivery writes nothing.",
                    eventId);
            return false;
        }
        return true;
    }

    /**
     * Reads the ten cardholder fields one account carries, or blank fields when none is held.
     *
     * @param accountId the account the assessment names
     * @return the cardholder fields, blank when the projection holds no row for the account
     */
    private CardholderDetails cardholderDetails(String accountId) {
        return cardholderContexts.findById(accountId)
                .map(FraudFlaggedConsumer::detailsOf)
                .orElseGet(CardholderDetails::blank);
    }

    /**
     * Maps one projection row onto the ten fields an alert reports.
     *
     * <p>The order matches {@code app/cbl/CBSTM03A.CBL:L462-L485}: the name, the three address
     * lines, the state and country codes, the mail code and the credit score.
     *
     * @param context one row of the account-keyed cardholder projection
     * @return the ten fields, each at the width its source field declares
     */
    private static CardholderDetails detailsOf(CardholderContextEntity context) {
        return new CardholderDetails(context.getFirstName(), context.getMiddleName(),
                context.getLastName(), context.getAddressLine1(), context.getAddressLine2(),
                context.getAddressLine3(), context.getStateCode(), context.getCountryCode(),
                context.getZipCode(), context.getFicoScore());
    }

    /**
     * Normalises the consumed topic for the marker column, which holds null for none.
     *
     * @param consumedTopic the topic the delivery arrived on, possibly null or blank
     * @return the topic, or null when the delivery named none
     */
    private static String topicOrNull(String consumedTopic) {
        return consumedTopic == null || consumedTopic.isBlank() ? null : consumedTopic;
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
     * Names the failure kind one fault counts under.
     *
     * <p>A data-access fault counts as a persistence failure and every other fault as a rendering
     * failure. {@code config/ObservabilityConfig} registers both series.
     *
     * @param failure the fault this delivery raised
     * @return the tag value the failure counter carries
     */
    private static String failureKind(RuntimeException failure) {
        return failure instanceof DataAccessException
                ? NotificationMetrics.FAILURE_PERSISTENCE
                : NotificationMetrics.FAILURE_RENDERING;
    }
}
