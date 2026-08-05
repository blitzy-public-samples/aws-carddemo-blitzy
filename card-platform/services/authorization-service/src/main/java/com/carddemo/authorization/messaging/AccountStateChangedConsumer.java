package com.carddemo.authorization.messaging;

import com.carddemo.authorization.entity.ProcessedEventEntity;
import com.carddemo.authorization.repository.AccountCreditSnapshotRepository;
import com.carddemo.authorization.repository.ProcessedEventRepository;

import java.time.Clock;
import java.time.Instant;
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
 * <p>Decisions: {@code card-platform/docs/decision-log.md}. Event paths:
 * {@code card-platform/docs/event-flow.md}.
 */
@Component
public class AccountStateChangedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a monetary value. */
    private static final Logger LOG = LoggerFactory.getLogger(AccountStateChangedConsumer.class);

    /** Store of the credit projection the decline rules read. */
    private final AccountCreditSnapshotRepository snapshots;

    /** Store of the duplicate-delivery markers. */
    private final ProcessedEventRepository processedEvents;

    /** Opens the one transaction the apply and its marker commit in. */
    private final TransactionTemplate transactionTemplate;

    /** Supplies the observation moment a freshness check later reads. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the two stores and the transaction boundary.
     *
     * @param snapshots       store of the credit projection
     * @param processedEvents store of the duplicate-delivery markers
     * @param transactionTemplate    opens the one transaction per delivery
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountStateChangedConsumer(AccountCreditSnapshotRepository snapshots,
            ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
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
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @throws NullPointerException     if {@code acknowledgment} is {@code null}
     * @throws IllegalArgumentException if the payload is a tombstone
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.account-state-changed}",
            groupId = "${carddemo.kafka.groups.account-state-changed}")
    public void onAccountStateChanged(JsonNode message, Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {

        Objects.requireNonNull(acknowledgment, "acknowledgment is required");
        if (message == null) {
            throw new IllegalArgumentException("account.state-changed carries no tombstone, and a "
                    + "record with no payload cannot name the account it changed");
        }

        AccountStateChanged event = AccountStateChanged.from(message);
        try {
            transactionTemplate.executeWithoutResult(status -> applyOneEvent(event, consumedTopic));
        } catch (DataIntegrityViolationException integrityFailure) {
            if (!isCommittedDuplicate(event.eventId())) {
                throw integrityFailure;
            }
            LOG.debug("Event {} gained a marker from a delivery running alongside this one, so this"
                    + " one applied nothing.", event.eventId());
        }

        acknowledgment.acknowledge();
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
        if (processedEvents.existsById(event.eventId())) {
            LOG.debug("Event {} already carries a marker, so this delivery applied nothing.",
                    event.eventId());
            return;
        }

        int applied = snapshots.applyStateChange(event.accountId(), event.creditLimit(),
                event.expirationDate(), event.currentCycleCredit(), event.currentCycleDebit(),
                event.eventId(), event.occurredAt(), clock.instant());

        if (applied == 0) {
            LOG.info("Event {} for account {} was not applied, because a newer change is already"
                    + " recorded on the row.", event.eventId(), event.accountId());
        }
        processedEvents.save(marker(event.eventId(), consumedTopic));
    }

    /**
     * Builds the marker this delivery records, naming the topic it arrived on.
     *
     * @param eventId       the identifier of the applied event
     * @param consumedTopic the topic the delivery arrived on
     * @return the marker to store
     */
    private ProcessedEventEntity marker(UUID eventId, String consumedTopic) {
        ProcessedEventEntity marker = new ProcessedEventEntity(eventId, clock.instant());
        marker.setConsumedTopic(
                consumedTopic == null || consumedTopic.isBlank() ? null : consumedTopic);
        return marker;
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
     * @param eventId the identifier of the event this delivery carries
     * @return {@code true} only when a committed marker was observed
     */
    private boolean isCommittedDuplicate(UUID eventId) {
        try {
            return processedEvents.existsById(eventId);
        } catch (DataAccessException unreadable) {
            LOG.warn("Whether event {} already carries a marker could not be established after a {},"
                            + " so this delivery stays unacknowledged.", eventId,
                    unreadable.getClass().getSimpleName());
            return false;
        }
    }
}
