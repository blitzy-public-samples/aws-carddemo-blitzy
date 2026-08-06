package com.carddemo.notification.messaging;

import com.carddemo.events.TransactionAuthorized;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.CardholderContextReader;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationService;
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
 * Renders a cardholder alert the moment a transaction is authorized.
 *
 * <p>WHY THIS LISTENER EXISTS. AAP 0.1.1 and 0.8.3 require that authorizing a transaction produces
 * one event which at least three independent services then consume. The ledger and the fraud detector
 * read {@code transaction.authorized} directly; this service read only events those two derive from
 * it, so the authorization event had two direct consumers and not three. Reading it here makes the
 * third, and it makes the alert immediate: a cardholder learns of a transaction when it is authorized
 * rather than when the ledger has finished posting it.
 *
 * <p>WHAT INDEPENDENCE MEANS HERE. This listener reads the topic the authorization service publishes
 * and nothing else. It calls no other service, and no module of this service depends on the ledger or
 * the fraud detector, which AAP 0.4.2 makes a property of the build rather than of review: no service
 * module lists another service module as a dependency, so an import of one would not compile. It also
 * cannot delay the authorization response, because the response was returned before the outbox relay
 * published the event this listener reads.
 *
 * <p>WHY THIS ALERT AND THE POSTED ALERT ARE BOTH SENT. They report different facts. An authorization
 * is a decision about whether a transaction may proceed, and it establishes no balance. A posting is
 * the balance changing. {@code messaging/TransactionPostedConsumer} reports the second, and the two
 * alerts carry different event identifiers, so neither suppresses nor duplicates the other.
 *
 * <p>Idempotency is ADDITIVE, as it is for every listener here. The source has no duplicate detection
 * at all: a replayed feed drives the posting program at {@code app/cbl/CBTRN02C.cbl:L562-L579} into a
 * duplicate-key condition and straight to its abend routine.
 * {@link ProcessedEventRepository#claimEvent} takes the event identifier in the same transaction as
 * the alert it guards, so a redelivery renders nothing and writes nothing.
 *
 * <p>A failure leaves the offset uncommitted and travels to the listener container, which decides
 * between another delivery attempt and the source-specific dead-letter topic. Retry counting and
 * dead-letter routing belong to {@code config/KafkaConsumerConfig}.
 *
 * <p>No log line here carries a card number, an amount or a cardholder field.
 */
@Component
public class TransactionAuthorizedConsumer {

    /** Writes the diagnostic lines this class emits, none naming a cardholder or an amount. */
    private static final Logger LOG =
            LoggerFactory.getLogger(TransactionAuthorizedConsumer.class);

    /** {@code ABEND-CODE} of {@code 01 ABEND-DATA} at {@code app/cpy/CSMSG02Y.cpy:L21-L29}. */
    private static final String ABEND_CODE = "NTFY";

    /** {@code ABEND-CULPRIT}, naming the job this listener stands in for. */
    private static final String CULPRIT = "CREASTMT";

    /** {@code ABEND-MSG} for a delivery that rendered nothing. */
    private static final String NOTHING_WRITTEN = "/no alert rendered";

    /** Reads the ten cardholder fields, refusing when the projection holds none. */
    private final CardholderContextReader cardholderContextReader;

    /** Claims one event identifier, so a redelivery renders nothing. */
    private final ProcessedEventRepository processedEvents;

    /** Renders the cardholder alert. */
    private final NotificationService notificationService;

    /** Opens the one local transaction this listener runs its work in. */
    private final TransactionTemplate transactionTemplate;

    /** Counters and timers this listener records. */
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
    public TransactionAuthorizedConsumer(CardholderContextReader cardholderContextReader,
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
     * Reads one authorization, renders its alert inside one local transaction, and acknowledges.
     *
     * <p>The topic and the consumer group both resolve from configuration, so neither name appears
     * here as text. The group is this service's own, so this listener reads every partition of the
     * topic independently of the ledger's and the fraud detector's groups, and none of the three
     * affects another's offsets.
     *
     * @param event          the validated authorization this delivery carries
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param messageKey     the key the record arrived under, which must name the aggregate
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @throws NullPointerException     if {@code event} or {@code acknowledgment} is null
     * @throws IllegalArgumentException if the event carries no card token, or if the message key does
     *                                  not name the aggregate the payload names
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.transaction-authorized}",
            groupId = "${carddemo.kafka.groups.transaction-authorized}")
    public void onTransactionAuthorized(TransactionAuthorized event, Acknowledgment acknowledgment,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {
        Objects.requireNonNull(event, "event is required");
        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        metrics.eventsConsumed(NotificationMetrics.EVENT_TRANSACTION_AUTHORIZED).increment();
        long startedAt = System.nanoTime();
        try {
            requireKeyNamesAggregate(messageKey, event);
            String cardToken = requireCardToken(event);

            transactionTemplate.executeWithoutResult(status -> {
                if (claimed(event.eventId(), consumedTopic)) {
                    notificationService.renderAuthorizationAlert(cardToken,
                            event.maskedCardNumber(), event.transactionId(), event.accountId(),
                            event.description(), event.amount(),
                            cardholderContextReader.require(event.accountId()),
                            RenderedFormat.PLAIN_TEXT);
                }
            });
        } catch (RuntimeException failure) {
            reportFailure(event.eventId(), failure);
            throw failure;
        } finally {
            metrics.processingLatency(NotificationMetrics.EVENT_TRANSACTION_AUTHORIZED)
                    .record(Duration.ofNanos(System.nanoTime() - startedAt));
        }

        acknowledgment.acknowledge();
    }

    /**
     * Claims one event identifier inside the open transaction, reporting whether this delivery took
     * it.
     *
     * <p>One statement claims the event, so no delivery reads the marker table and then writes it.
     * The claim and the alert it guards commit together or not at all.
     *
     * @param eventId       the identifier the authorization service assigned
     * @param consumedTopic the topic the delivery arrived on, recorded on the marker
     * @return {@code true} when this delivery took the event, and {@code false} when the marker was
     *         already held
     */
    private boolean claimed(UUID eventId, String consumedTopic) {
        if (processedEvents.claimEvent(eventId, Instant.now(), topicOrNull(consumedTopic))
                == ProcessedEventRepository.ALREADY_CLAIMED) {
            metrics.duplicatesSkipped().increment();
            LOG.debug("Event {} carries a marker already, so this delivery renders nothing.",
                    eventId);
            return false;
        }
        return true;
    }

    /**
     * Reads the card token the alert is recorded against, refusing an event that carries none.
     *
     * <p>{@code notification_log} is keyed by card token, and the token is the only card identifier
     * this service holds: it stores no card number, masked or otherwise, as an identity. An event
     * without one names no card to record the attempt against, so it is refused rather than recorded
     * against a placeholder.
     *
     * @param event the authorization this delivery carries
     * @return the card token
     * @throws IllegalArgumentException when the event carries no card token
     */
    private static String requireCardToken(TransactionAuthorized event) {
        String cardToken = event.cardToken();
        if (cardToken == null || cardToken.isBlank()) {
            throw new IllegalArgumentException("/cardToken carries no value, so this event names no"
                    + " card to record an alert against.");
        }
        return cardToken;
    }

    /**
     * Refuses a record whose key does not name the aggregate its payload names.
     *
     * <p>Kafka orders records within one partition, and the key chooses the partition. AAP 0.3.1
     * makes the account identifier the key of every event for this reason, so a record whose key
     * names a different aggregate arrived on a partition that does not order it. A delivery with no
     * key at all is refused for the same reason.
     *
     * @param messageKey the key the record arrived under
     * @param event      the authorization this delivery carries
     * @throws IllegalArgumentException when the key is absent or names another aggregate
     */
    private static void requireKeyNamesAggregate(String messageKey, TransactionAuthorized event) {
        if (messageKey == null || messageKey.isBlank()) {
            throw new IllegalArgumentException("This record carries no message key, so the partition"
                    + " it arrived on is not the one that orders its aggregate.");
        }
        if (!messageKey.equals(event.aggregateId())) {
            throw new IllegalArgumentException(
                    "The message key does not name the aggregate this payload names, so the"
                            + " partition this record arrived on is not the one that orders it.");
        }
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
     * {@code ABEND-MSG} names one field by its JSON pointer, so no field value and no payload reaches
     * a log line.
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
                .log("Event {} rendered no alert, and this delivery stays unacknowledged.", eventId);
    }

    /**
     * Names the failure kind one fault records against.
     *
     * <p>A database fault counts as a persistence failure and every other fault as a rendering
     * failure. {@code ObservabilityConfig.NotificationMetrics#isPersistenceFault} decides the first
     * case for the whole service, and it names a transaction fault as well as a data-access fault: a
     * paused or unreachable database raises {@code CannotCreateTransactionException} from the
     * connection pool, which is the former.
     *
     * @param failure the fault this delivery raised
     * @return the tag value {@code config/ObservabilityConfig} registers a series for
     */
    private static String failureKind(RuntimeException failure) {
        return NotificationMetrics.isPersistenceFault(failure)
                ? NotificationMetrics.FAILURE_PERSISTENCE
                : NotificationMetrics.FAILURE_RENDERING;
    }
}
