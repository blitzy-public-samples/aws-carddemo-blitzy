package com.carddemo.authorization.messaging;

import com.carddemo.authorization.config.ObservabilityConfig.ReplicaMeters;
import com.carddemo.authorization.domain.ReplicaGapLog;
import com.carddemo.authorization.entity.ProcessedEventEntity;
import com.carddemo.authorization.entity.ProcessedEventEntity.ProcessedEventId;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
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
 * Keeps {@code account_credit_snapshot} current from the account service's state-change stream.
 *
 * <p>ADDITIVE IN FULL. {@code app/cbl/CBTRN02C.cbl:L395} issues a keyed read against the account
 * dataset itself, so the source has no replica and nothing to keep current. This listener exists
 * because the credit-limit rule at {@code app/cbl/CBTRN02C.cbl:L403-L413} and the expiry rule at
 * {@code app/cbl/CBTRN02C.cbl:L414-L420} read a copy of that record, and a copy nothing updates keeps
 * answering with whatever it last knew. Both rules would then compute an answer from obsolete numbers
 * and raise nothing while doing it.
 *
 * <p>This listener is not on the authorization response path. It runs in its own consumer group, and
 * a request never waits for it. What the request path does is refuse to authorize against a row this
 * listener has not touched recently enough, which is
 * {@code AuthorizationService.requireFreshReplicaData}.
 *
 * <p>The apply and the marker commit together. Both happen inside one local transaction, and the
 * offset is committed only once that transaction has, so a crash between them replays the delivery and
 * the marker makes the replay harmless. The upsert carries its own newer-wins guard, so an
 * out-of-order redelivery is discarded rather than applied backwards.
 *
 * <p>Three series report this stream, and each is read per delivery rather than per applied change:
 * {@code carddemo.authorization.events.consumed}, {@code carddemo.authorization.duplicates.skipped}
 * and {@code carddemo.authorization.processing.latency}, all tagged
 * {@code eventType=AccountStateChanged}. The consumed count rises as the delivery arrives, so a stream
 * that stopped arriving is a count that stopped rising rather than a silence, and the timer records
 * every delivery including one that failed. A delivery this service gives up on is counted again by
 * {@code config/KafkaConsumerConfig} as a fault of the replica stage.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}. Event paths:
 * {@code card-platform/docs/event-flow.md}.
 */
@Component
public class AccountStateChangedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a monetary value. */
    private static final Logger LOG = LoggerFactory.getLogger(AccountStateChangedConsumer.class);

    /** Envelope property naming the aggregate, which the Kafka message key must equal. */
    private static final String AGGREGATE_ID = "aggregateId";

    /** Store of the credit projection the decline rules read. */
    private final AccountCreditSnapshotRepository snapshots;

    /** Store of the duplicate-delivery markers. */
    private final ProcessedEventRepository processedEvents;

    /** Opens the one transaction the apply and its marker commit in. */
    private final TransactionTemplate transactionTemplate;

    /** The three consume-side series of this stream. */
    private final ReplicaMeters meters;

    /**
     * Opens and closes the record of accounts this stream owes a change.
     *
     * <p>A delivery that cannot be applied leaves the account it names behind, and lag alone cannot
     * report that: the offset advances once the record's diagnostic is away, so the stream reads as
     * caught up while one account's copy is missing a change. The gap is what makes
     * {@code domain/AuthorizationService} refuse that one account rather than every account or none.
     */
    private final ReplicaGapLog replicaGaps;

    /** Supplies the observation moment recorded on the row this delivery applies. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the two stores, the transaction boundary, the recording surface and the gap log.
     *
     * @param snapshots       store of the credit projection
     * @param processedEvents store of the duplicate-delivery markers
     * @param transactionTemplate    opens the one transaction per delivery
     * @param meters          the consume-side series of this stream
     * @param replicaGaps     opens and closes the record of accounts this stream owes a change
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountStateChangedConsumer(AccountCreditSnapshotRepository snapshots,
            ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate,
            ReplicaMeters meters, ReplicaGapLog replicaGaps) {
        this.replicaGaps = Objects.requireNonNull(replicaGaps, "replicaGaps is required");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
        this.meters = Objects.requireNonNull(meters, "meters is required");
    }

    /**
     * Applies one account state change to the replica, then acknowledges.
     *
     * <p>The payload arrives as a schema-checked tree rather than as a bound record.
     * {@code libs/event-contracts} holds the document for this event and names no class to build,
     * because the record belongs to the account service and no service module may depend on another.
     * {@link AccountStateChanged#from(JsonNode)} reads the checked tree into the components this
     * service replicates.
     *
     * <p>A {@code null} payload is Kafka's tombstone. This stream produces none: an account is never
     * deleted by a state change, and the replica has no row to remove. One is refused rather than
     * ignored, because silently accepting a message this contract cannot produce would hide a
     * producer defect.
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
    @KafkaListener(topics = "${carddemo.kafka.topics.account-state-changed}",
            groupId = "${carddemo.kafka.groups.account-state-changed}")
    public void onAccountStateChanged(JsonNode message,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String messageKey,
            Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {

        Objects.requireNonNull(acknowledgment, "acknowledgment is required");

        meters.recordEventConsumed(AccountStateChanged.EVENT_TYPE);
        long startedAt = System.nanoTime();
        try {
            if (message == null) {
                throw new IllegalArgumentException("account.state-changed carries no tombstone, and a"
                        + " record with no payload cannot name the account it changed");
            }

            AccountStateChanged event = AccountStateChanged.from(message);
            requireKeyNamesAggregate(messageKey, aggregateIdOf(message),
                    event.accountId());
            try {
                transactionTemplate
                        .executeWithoutResult(status -> applyOneEvent(event, consumedTopic));
            } catch (DataIntegrityViolationException integrityFailure) {
                if (!isCommittedDuplicate(event.eventId(), consumedTopic)) {
                    throw integrityFailure;
                }
                meters.recordDuplicateSkipped(AccountStateChanged.EVENT_TYPE);
                LOG.debug("Event {} gained a marker from a delivery running alongside this one, so"
                        + " this one applied nothing.", event.eventId());
            }
        } catch (RuntimeException failure) {
            // The gap is committed in its own transaction, so this delivery's rollback leaves it
            // standing. The failure then propagates, so the container still retries and still
            // dead-letters: nothing about the existing error handling changes here.
            replicaGaps.recordFailure(messageKey, consumedTopic, failure);
            throw failure;
        } finally {
            meters.recordProcessingLatency(AccountStateChanged.EVENT_TYPE,
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
     * <p>This service authorizes against the row this listener maintains. A change applied under
     * the wrong key would move a credit limit or an expiry onto another account's snapshot, and the
     * four decline rules of {@code app/cbl/CBTRN02C.cbl:L380-L420} would then decide that account's
     * transactions from a value that never belonged to it.
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
     * <p>{@link AccountStateChanged} reads the components this service replicates and the
     * envelope's
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
     * Applies one event inside the open transaction, marker first.
     *
     * <p>The marker is read before the upsert runs and written after it. Reading first skips the work
     * of a redelivery, and writing after keeps the marker and the row in one commit: a marker written
     * ahead of a failing upsert would suppress the redelivery that would have applied it.
     *
     * @param event         the validated event this delivery carries
     * @param consumedTopic the topic the delivery arrived on
     */
    private void applyOneEvent(AccountStateChanged event, String consumedTopic) {
        if (processedEvents.existsById(markerKey(event.eventId(), consumedTopic))) {
            meters.recordDuplicateSkipped(AccountStateChanged.EVENT_TYPE);
            LOG.debug("Event {} already carries a marker for the topic it arrived on, so this"
                    + " delivery applied nothing.", event.eventId());
            return;
        }

        int applied = snapshots.applyStateChange(event.accountId(), event.creditLimit(),
                event.expirationDate(), event.currentCycleCredit(), event.currentCycleDebit(),
                event.eventId(), event.occurredAt(), clock.instant());

        // The copy and the record of its gap move together. A gap opened by an earlier failed
        // delivery is closed here, inside the transaction that applies the change, so no decision can
        // read a row whose gap was cleared by a write that then rolled back.
        replicaGaps.clear(event.accountId());

        if (applied == 0) {
            // The event identifier is the correlation value and the account identifier is not.
            // A reader who needs the account reads the event from the topic, where access is
            // controlled; a log line that named it would put an account identifier into every
            // centralized log this deployment feeds and into whatever retains them.
            LOG.info("Event {} was not applied, because a newer change is already recorded on the"
                    + " row it names.", event.eventId());
        }
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
     * <p>The read runs after this delivery's transaction rolled back, so it observes the committed
     * state rather than this delivery's discarded writes. A fault on the read itself answers
     * {@code false}, which leaves the delivery unacknowledged and arriving again; the marker check at
     * the head of {@link #applyOneEvent} makes that arrival harmless.
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
