package com.carddemo.notification.messaging;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.notification.config.ObservabilityConfig.NotificationMetrics;
import com.carddemo.notification.domain.CardholderContextReader;
import com.carddemo.notification.domain.NotificationRenderer.RenderedFormat;
import com.carddemo.notification.domain.NotificationService;
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
 * Renders a cardholder alert the moment a transaction is authorized.
 *
 * <p>This listener is the third independent consumer of {@code transaction.authorized}, beside the
 * ledger and the fraud detector. It reads that topic and nothing else, calls no other service, and
 * cannot delay the authorization response, which was returned before the relay published the event.
 *
 * <p>{@code messaging/TransactionPostedConsumer} renders a second alert for the balance change. The
 * two carry different event identifiers, so neither suppresses nor duplicates the other. Neither is
 * sent: this service reaches no mail, message, webhook or push gateway, so it renders each alert and
 * records that it rendered it, and every {@code notification_log} row carries
 * {@link com.carddemo.notification.entity.NotificationLogEntity#RENDERED_NOT_SENT}.
 *
 * <p>Design decisions:
 * {@code card-platform/docs/decision-log.md}.
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
     * <p>A delivery at contract version {@link EventEnvelope#SCHEMA_VERSION} carries no card token,
     * and this service is keyed on that token. Such a delivery renders nothing, is counted on
     * {@code carddemo.notification.events.unapplied}, is reported once, and is acknowledged.
     * {@link #carriesNoCardIdentity(TransactionAuthorized)} states why that is the handling and why
     * nothing is invented in its place.
     *
     * @throws NullPointerException     if {@code event} or {@code acknowledgment} is null
     * @throws IllegalArgumentException if the message key does not name the aggregate the payload
     *                                  names
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

            if (carriesNoCardIdentity(event)) {
                recordUnappliedVersion(event.eventId(), event.schemaVersion());
            } else {
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
            }
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
        if (processedEvents.claimEvent(eventId, Instant.now(), consumedTopicOrSentinel(consumedTopic))
                == ProcessedEventRepository.ALREADY_CLAIMED) {
            metrics.duplicatesSkipped().increment();
            LOG.debug("Event {} carries a marker already, so this delivery renders nothing.",
                    eventId);
            return false;
        }
        return true;
    }

    /**
     * Reports whether this delivery carries a contract version that names no card.
     *
     * <p>{@code notification_log} is keyed by card token, and the token is the only card identifier
     * this service holds: it stores no card number, masked or otherwise, as an identity. Version
     * {@link TransactionAuthorized#CARD_TOKEN_SCHEMA_VERSION} carries the token and version
     * {@link EventEnvelope#SCHEMA_VERSION} declares no such property at all, so a version 1 event
     * names no card for an alert to be recorded against.
     *
     * <p><strong>Nothing is invented in its place, and nothing is refused either.</strong> The token
     * is the hexadecimal rendering of a digest over the whole card number, and the masked number a
     * version 1 event does carry has discarded twelve of the sixteen digits that derivation reads, so
     * no token can be recovered from it. Deriving one from the account identifier, the transaction
     * identifier or the masked form would key a row on a value no card resolves to, and every later
     * read of that row would report an alert for a card that never had one.
     *
     * <p>Refusing it was the previous handling and it was worse. A version 1 event is governed, valid
     * against its own schema document, and accepted by the deserializer; refusing it in the listener
     * spent three delivery attempts and put it on the dead-letter topic, which reports a valid event
     * as a poison record. Consumer groups start at the earliest offset, so a group added to a topic
     * that retains version 1 records met that route on every one of them, and the backward
     * compatibility this platform's versioning exists to provide did not hold in practice.
     *
     * @param event the authorization this delivery carries
     * @return {@code true} when the event carries no card token
     */
    private static boolean carriesNoCardIdentity(TransactionAuthorized event) {
        String cardToken = event.cardToken();
        return cardToken == null || cardToken.isBlank();
    }

    /**
     * Counts and reports one governed delivery this listener deliberately applied nothing for.
     *
     * <p>No marker is written. {@code processed_event} guards side effects, and there are none to
     * guard: a marker would assert that this event had been applied. A redelivery is therefore counted
     * here again, which is truthful, because the delivery did happen again.
     *
     * <p>Reported at {@code WARN} rather than {@code INFO}, because a stream of these means a producer
     * is publishing an older contract version than this service can act on, which is an operational
     * fact rather than a routine one. The line names the event identifier and the version, and no
     * field of the payload.
     *
     * @param eventId       the identifier the authorization service assigned
     * @param schemaVersion the contract version the delivery reported
     */
    private void recordUnappliedVersion(UUID eventId, int schemaVersion) {
        metrics.eventsUnapplied(NotificationMetrics.EVENT_TRANSACTION_AUTHORIZED).increment();
        LOG.warn("Event {} reports contract version {} and carries no card token, so this service"
                + " renders nothing for it and applies nothing. Version {} carries the token every"
                + " row here is keyed on.", eventId, schemaVersion,
                TransactionAuthorized.CARD_TOKEN_SCHEMA_VERSION);
    }

    /**
     * Reads the card token the alert is recorded against.
     *
     * <p>Reached only for a delivery {@link #carriesNoCardIdentity(TransactionAuthorized)} answered
     * false for, so the value is present. The check remains because this method's contract is a
     * non-null token and a caller that stopped asking the question first would otherwise pass one
     * silently.
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
     * {@code ABEND-MSG} names one field by its JSON pointer, so no field value and no payload reaches
     * a log line.
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
