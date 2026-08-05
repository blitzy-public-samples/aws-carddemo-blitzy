package com.carddemo.authorization.messaging;

import com.carddemo.authorization.entity.ProcessedEventEntity;
import com.carddemo.authorization.repository.CardCrossReferenceRepository;
import com.carddemo.authorization.repository.ProcessedEventRepository;

import java.time.Clock;
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
 * changes, and no authorization can be misrouted by it. The alternative would be to leave the
 * cross-reference unable to establish its own freshness at all, which is the condition the request path
 * refuses to authorize in.
 *
 * <p>The refresh and the marker commit together, and the offset is committed only once that transaction
 * has. The statement carries the same newer-wins guard the upsert does.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}. Event paths:
 * {@code card-platform/docs/event-flow.md}.
 */
@Component
public class CardUpdatedConsumer {

    /** Writes the diagnostic lines this class emits, none carrying a card number. */
    private static final Logger LOG = LoggerFactory.getLogger(CardUpdatedConsumer.class);

    /** Store of the cross-reference, the first hop of every authorization. */
    private final CardCrossReferenceRepository crossReferences;

    /** Store of the duplicate-delivery markers. */
    private final ProcessedEventRepository processedEvents;

    /** Opens the one transaction the refresh and its marker commit in. */
    private final TransactionTemplate transactionTemplate;

    /** Supplies the observation moment a freshness check later reads. */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the two stores and the transaction boundary.
     *
     * @param crossReferences store of the cross-reference
     * @param processedEvents store of the duplicate-delivery markers
     * @param transactionTemplate    opens the one transaction per delivery
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardUpdatedConsumer(CardCrossReferenceRepository crossReferences,
            ProcessedEventRepository processedEvents, TransactionTemplate transactionTemplate) {
        this.crossReferences =
                Objects.requireNonNull(crossReferences, "crossReferences is required");
        this.processedEvents =
                Objects.requireNonNull(processedEvents, "processedEvents is required");
        this.transactionTemplate =
                Objects.requireNonNull(transactionTemplate, "transactionTemplate is required");
    }

    /**
     * Refreshes the observation on one account's matching cross-reference rows, then acknowledges.
     *
     * <p>A {@code null} payload is Kafka's tombstone. This stream produces none, and one is refused
     * rather than ignored: a record with no payload names neither an account nor a card, so there is
     * nothing it could be applied to and accepting it would hide a producer defect.
     *
     * @param message        the schema-checked message tree, or {@code null} for a tombstone
     * @param acknowledgment the offset commit, invoked once the transaction has committed
     * @param consumedTopic  the topic the delivery arrived on, recorded on the marker
     * @throws NullPointerException     if {@code acknowledgment} is {@code null}
     * @throws IllegalArgumentException if the payload is a tombstone
     */
    @KafkaListener(topics = "${carddemo.kafka.topics.card-updated}",
            groupId = "${carddemo.kafka.groups.card-updated}")
    public void onCardUpdated(JsonNode message, Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String consumedTopic) {

        Objects.requireNonNull(acknowledgment, "acknowledgment is required");
        if (message == null) {
            throw new IllegalArgumentException("card.updated carries no tombstone, and a record with"
                    + " no payload cannot name the card it changed");
        }

        CardUpdated event = CardUpdated.from(message);
        try {
            transactionTemplate.executeWithoutResult(status -> applyOneEvent(event, consumedTopic));
        } catch (DataIntegrityViolationException integrityFailure) {
            if (!isCommittedDuplicate(event.eventId())) {
                throw integrityFailure;
            }
            LOG.debug("Event {} gained a marker from a delivery running alongside this one, so this"
                    + " one refreshed nothing.", event.eventId());
        }

        acknowledgment.acknowledge();
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
        if (processedEvents.existsById(event.eventId())) {
            LOG.debug("Event {} already carries a marker, so this delivery refreshed nothing.",
                    event.eventId());
            return;
        }

        int refreshed = crossReferences.refreshObservation(event.accountId(),
                event.visibleDigitsSuffix(), event.eventId(), event.occurredAt(), clock.instant());

        if (refreshed == 0) {
            LOG.info("Event {} for account {} refreshed no cross-reference row, so this replica"
                            + " holds no card of that account ending in the visible digits, or a"
                            + " newer change is already recorded.", event.eventId(),
                    event.accountId());
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
