package com.carddemo.authorization.messaging;

import com.carddemo.authorization.config.ObservabilityConfig.ReplicaMeters;
import com.carddemo.authorization.domain.ReplicaGapLog;
import com.carddemo.authorization.entity.ProcessedEventEntity;
import com.carddemo.authorization.entity.ProcessedEventEntity.ProcessedEventId;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.ProcessedEventRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;

/**
 * Keeps the observation on {@code card_xref} current from the card service's update stream.
 *
 * <p>ADDITIVE IN FULL. {@code app/cbl/CBTRN02C.cbl:L382} issues a keyed read against the
 * cross-reference dataset itself, so the source has no copy and nothing to refresh.
 *
 * <p>What this listener can do is narrower than the account one, and the reason is the contract rather
 * than the code. A {@code CardUpdated} message carries a masked card number, because no full card
 * number travels on a topic in this platform. {@code card_xref} is keyed by the full sixteen-character
 * number, so the message cannot name a key: it can neither create a row nor move one card's mapping to
 * another account. It does carry the account identifier, so it does establish that the service owning
 * the card has just written that account's card data, which is what the observation column records.
 *
 * <p>This listener therefore refreshes the observation on that account's matching rows and writes no
 * mapping field. Where two cards of one account share their last four digits, both rows are refreshed.
 * That is a real imprecision and it is bounded: both rows already name this account, no mapping value
 * changes, and no authorization can be misrouted by it. Rationale and the alternatives weighed:
 * {@code card-platform/docs/decision-log.md}.
 *
 * <p>The refresh and the marker commit together, and the offset is committed only once that transaction
 * has. The statement carries the same newer-wins guard the upsert does.
 *
 * <p>Three series report this stream, and each is read per delivery rather than per refreshed row:
 * {@code carddemo.authorization.events.consumed}, {@code carddemo.authorization.duplicates.skipped}
 * and {@code carddemo.authorization.processing.latency}, all tagged {@code eventType=CardUpdated}. The
 * consumed count rises as the delivery arrives, so a stream that stopped arriving is a count that
 * stopped rising rather than a silence, and the timer records every delivery including one that failed.
 * A delivery this service gives up on is counted again by {@code config/KafkaConsumerConfig} as a fault
 * of the replica stage.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}. Event paths:
 * {@code card-platform/docs/event-flow.md}.
 */
@Component
public class CardUpdatedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a card number. */
    private static final Logger LOG = LoggerFactory.getLogger(CardUpdatedConsumer.class);

    /** Envelope property naming the aggregate, which the Kafka message key must equal. */
    private static final String AGGREGATE_ID = "aggregateId";

    /** Store of the cross-reference, the first hop of every authorization. */
    private final CardCrossReferenceRepository crossReferences;

    /** Store of the duplicate-delivery markers. */
    private final ProcessedEventRepository processedEvents;

    /** Opens the one transaction the refresh and its marker commit in. */
    private final TransactionTemplate transactionTemplate;

    /** The three consume-side series of this stream. */
    private final ReplicaMeters meters;

    /**
     * Opens and closes the record of accounts this stream owes a change.
     *
     * <p>A delivery that cannot be applied leaves the account it names behind, and lag alone cannot
     * report that: the offset advances once the record's diagnostic is away, so the stream reads as
     * caught up while one account's cards are missing a change. The gap is what makes
     * {@code domain/AuthorizationService} refuse that one account rather than every account or none.
     */
    private final ReplicaGapLog replicaGaps;

    /** Supplies the observation moment recorded on the rows this delivery refreshes. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the two stores, the transaction boundary, the recording surface and the gap log.
     *
     * @param crossReferences store of the cross-reference
     * @param processedEvents store of the duplicate-delivery markers
     * @param transactionTemplate    opens the one transaction per delivery
     * @param meters          the consume-side series of this stream
     * @param replicaGaps     opens and closes the record of accounts this stream owes a change
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardUpdatedConsumer(CardCrossReferenceRepository crossReferences,
            ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate,
            ReplicaMeters meters, ReplicaGapLog replicaGaps) {
        this.replicaGaps = Objects.requireNonNull(replicaGaps, "replicaGaps is required");
        this.crossReferences =
                Objects.requireNonNull(crossReferences, "crossReferences is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
        this.meters = Objects.requireNonNull(meters, "meters is required");
    }

    /**
     * Refreshes the observation on one account's matching cross-reference rows, then acknowledges.
     *
     * <p>A {@code null} payload is Kafka's tombstone. This stream produces none, and one is refused
     * rather than ignored: a record with no payload names neither an account nor a card, so there is
     * nothing it could be applied to and accepting it would hide a producer defect.
     *
     * @param message        the schema-checked message tree, or {@code null} for a tombstone
     * @param messageKey     the key the record arrived under, which must name the account the
     *                       payload names
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @throws NullPointerException     if {@code acknowledgment} is {@code null}
     * @throws IllegalArgumentException if the payload is a tombstone, or the key names another
     *                                  account
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.card-updated}",
            groupId = "${carddemo.kafka.groups.card-updated}")
    public void onCardUpdated(JsonNode message,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey,
            Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {

        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        meters.recordEventConsumed(CardUpdated.EVENT_TYPE);
        long startedAt = System.nanoTime();
        try {
            if (message == null) {
                throw new IllegalArgumentException("card.updated carries no tombstone, and a record"
                        + " with no payload cannot name the card it changed");
            }

            CardUpdated event = CardUpdated.from(message);
            requireKeyNamesAggregate(messageKey, aggregateIdOf(message),
                    event.accountId());
            try {
                transactionTemplate
                        .executeWithoutResult(status -> applyOneEvent(event, consumedTopic));
            } catch (DataIntegrityViolationException integrityFailure) {
                if (!isCommittedDuplicate(event.eventId(), consumedTopic)) {
                    throw integrityFailure;
                }
                meters.recordDuplicateSkipped(CardUpdated.EVENT_TYPE);
                LOG.debug("Event {} gained a marker from a delivery running alongside this one, so"
                        + " this one refreshed nothing.", event.eventId());
            }
        } catch (RuntimeException failure) {
            // The gap is committed in its own transaction, so this delivery's rollback leaves it
            // standing. The failure then propagates, so the container still retries and still
            // dead-letters: nothing about the existing error handling changes here.
            replicaGaps.recordFailure(messageKey, consumedTopic, failure);
            throw failure;
        } finally {
            meters.recordProcessingLatency(CardUpdated.EVENT_TYPE,
                    Duration.ofNanos(System.nanoTime() - startedAt));
        }

        acknowledgment.acknowledge();
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
     * <p>The refresh selects rows by account, so a change applied under the wrong key would move
     * one account's card observation onto another's rows. The route that reads those rows is the
     * one that resolves a presented card at {@code app/cbl/CBTRN02C.cbl:L385-L387}.
     *
     * <p>All three values are compared rather than the key against one of them. A key that matches
     * {@code aggregateId} while {@code accountId} names something else would route correctly and
     * write to the wrong row, which is the same defect one field further in.
     *
     * <p>The refusal is an {@link IllegalArgumentException} raised before anything is claimed or
     * written, so nothing is applied, the delivery is retried, and a spent record reaches the
     * sanitized dead-letter route of {@code config/KafkaConsumerConfig}. Neither message names the
     * key, the aggregate or the account, so no identifier reaches a log line through them.
     *
     * @param messageKey  the key the record arrived under, possibly {@code null}
     * @param aggregateId the aggregate the envelope names
     * @param accountId   the account the payload names
     * @throws IllegalArgumentException when the key is absent or the three do not agree
     */
    private static void requireKeyNamesAggregate(String messageKey, String aggregateId,
            String accountId) {
        if (messageKey == null || messageKey.isBlank()) {
            throw new IllegalArgumentException("this record carries no message key, so the"
                    + " partition it arrived on is not the one that orders its account");
        }
        if (!messageKey.equals(aggregateId) || !messageKey.equals(accountId)) {
            throw new IllegalArgumentException("the message key, the aggregate and the"
                    + " account this payload names do not agree, so the partition this"
                    + " record arrived on is not the one that orders that account");
        }
    }

    /**
     * Reads the aggregate the envelope names, straight from the checked tree.
     *
     * <p>{@link CardUpdated} reads the components this service replicates and the envelope's
     * aggregate is not one of them, so it is read here rather than widened into that record.
     *
     * @param message the checked tree
     * @return the aggregate the envelope names, or {@code null} when it carries no text
     */
    private static String aggregateIdOf(JsonNode message) {
        JsonNode aggregate = message.path(AGGREGATE_ID);
        return aggregate.isString() ? aggregate.stringValue() : null;
    }

    /**
     * Refreshes inside the open transaction, marker first.
     *
     * <p>A refresh that matches no row is reported and is not a failure. The cross-reference is loaded
     * from {@code app/data/ASCII/cardxref.txt} and holds a row per card the demo knows about, so a
     * card update naming an account with no such row describes a card this replica has never held. The
     * request path already treats an absent row as reject reason
     * {@code 0100} per {@code app/cbl/CBTRN02C.cbl:L385-L387}, so there is nothing for this listener to
     * repair and nothing to retry.
     *
     * @param event         the validated event this delivery carries
     * @param consumedTopic the topic the delivery arrived on
     */
    private void applyOneEvent(CardUpdated event, String consumedTopic) {
        if (processedEvents.existsById(markerKey(event.eventId(), consumedTopic))) {
            meters.recordDuplicateSkipped(CardUpdated.EVENT_TYPE);
            LOG.debug("Event {} already carries a marker for the topic it arrived on, so this"
                    + " delivery refreshed nothing.", event.eventId());
            return;
        }

        int refreshed = crossReferences.refreshObservation(event.accountId(),
                event.visibleDigitsSuffix(), event.eventId(), event.occurredAt(), clock.instant());

        if (refreshed == 0) {
            // Named by event identifier alone. The account identifier and the visible digits both
            // describe the cardholder, and a line that carried either would put them into every
            // centralized log this deployment feeds. The event on the topic carries both, behind
            // the access controls the topic already has.
            LOG.info("Event {} refreshed no cross-reference row, so this replica holds no card of"
                    + " the account it names ending in the digits it names, or a newer change is"
                    + " already recorded.", event.eventId());
        }

        // The copy and the record of its gap move together. A gap opened by an earlier failed
        // delivery is closed here, inside the transaction that applies the change, so no decision can
        // read rows whose gap was cleared by a write that then rolled back.
        replicaGaps.clear(event.accountId());
        processedEvents.save(marker(event.eventId(), consumedTopic));
    }

    /**
     * Builds the marker this delivery records, keyed on the event and the topic it arrived on.
     *
     * <p>A delivery that reached this listener with no topic header records
     * {@link ProcessedEventEntity#NO_CONSUMED_TOPIC} rather than nothing, because the topic is half
     * of the key and a key column holds no null.
     * {@code src/main/resources/db/migration/V6__processed_event_topic_key.sql} declares the same
     * sentinel and carries the reasoning.</p>
     *
     * @param eventId       the identifier of the applied event
     * @param consumedTopic the topic the delivery arrived on, possibly absent
     * @return the marker to store
     */
    private ProcessedEventEntity marker(UUID eventId, String consumedTopic) {
        return new ProcessedEventEntity(eventId, clock.instant(), recordedTopic(consumedTopic));
    }

    /**
     * Returns the topic a marker records for this delivery.
     *
     * @param consumedTopic the {@code RECEIVED_TOPIC} header, possibly absent or blank
     * @return the header when it names a topic, and
     *         {@link ProcessedEventEntity#NO_CONSUMED_TOPIC} when it does not
     */
    private static String recordedTopic(String consumedTopic) {
        return consumedTopic == null || consumedTopic.isBlank()
                ? ProcessedEventEntity.NO_CONSUMED_TOPIC
                : consumedTopic;
    }

    /**
     * Builds the key this delivery claims, which is its event and the topic it arrived on.
     *
     * @param eventId       the identifier of the event this delivery carries
     * @param consumedTopic the topic the delivery arrived on, possibly absent
     * @return the whole marker key
     */
    private static ProcessedEventId markerKey(UUID eventId, String consumedTopic) {
        return new ProcessedEventId(eventId, recordedTopic(consumedTopic));
    }

    /**
     * Reports whether {@code eventId} carries a committed marker, which is the one condition under
     * which a rolled-back delivery may acknowledge.
     *
     * <p>The read names the topic as well as the event. A marker written by a delivery on another
     * topic is not this delivery's, so answering on the identifier alone would acknowledge a record
     * whose own work never committed.
     *
     * @param eventId       the identifier of the event this delivery carries
     * @param consumedTopic the topic the delivery arrived on
     * @return {@code true} only when a committed marker was observed
     */
    private boolean isCommittedDuplicate(UUID eventId, String consumedTopic) {
        try {
            return processedEvents.existsById(markerKey(eventId, consumedTopic));
        } catch (DataAccessException unreadable) {
            LOG.warn("Whether event {} already carries a marker could not be established after a {},"
                            + " so this delivery stays unacknowledged.", eventId,
                    unreadable.getClass().getSimpleName());
            return false;
        }
    }
}
