package com.carddemo.ledger.outbox;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionPosted;
import com.carddemo.events.correlation.EventCorrelation;
import com.carddemo.events.serde.PublishGate;
import com.carddemo.ledger.entity.OutboxEventEntity;
import com.carddemo.ledger.repository.OutboxEventRepository;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts one row into {@code outbox_event} for one ledger event, in the transaction its caller
 * already opened.
 *
 * <p>No CardDemo program stores an event. The one asynchronous handoff in the source
 * writes a Job Control Language (JCL) record to a Customer Information Control System (CICS)
 * transient data queue at {@code app/cbl/CORPT00C.cbl:L517-L518}. The internal reader picks that
 * record up and submits the batch job it holds. The write is the only one of its kind in the 28
 * programs of {@code app/cbl/}.
 *
 * <p>The posting rows, the processed-event marker and this row commit together or fail together.
 * {@code app/cbl/CBTRN02C.cbl:L440-L442} performs three updates in fixed order with no rollback,
 * and all eight file definitions in {@code app/csd/CARDDEMO.CSD} carry {@code RECOVERY(NONE)} and
 * {@code JOURNAL(NO)}. {@code app/cbl/COACTUPC.cbl} rewrites two files in one unit of work, at
 * L4066 and L4086, under those same attributes.
 *
 * <p>This writer opens no transaction of its own and publishes nothing.
 * {@code outbox/OutboxRelay} reads the rows it inserts.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository outboxEvents;

    /** Stamps {@code created_at}, in Coordinated Universal Time. */
    private final Clock clock = Clock.systemUTC();

    /**
     * @param outboxEvents store of unpublished events
     * @throws NullPointerException if {@code outboxEvents} is {@code null}
     */
    public OutboxWriter(OutboxEventRepository outboxEvents) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must be present");
    }

    /**
     * Writes one event as an unpublished outbox row.
     *
     * <p>{@code domain/PostingService} is the one caller, and {@link TransactionPosted} is the one
     * event this service publishes. A decline is not published here: the authorization service is
     * the sole writer of the decision under AAP 0.1.1, and this service consumes the decline it
     * published rather than republishing it. A decline is nonetheless ordinary traffic, which is why
     * {@code app/cbl/CBTRN02C.cbl:L229-L230} moves 4 into the return code rather than abending once
     * the reject count rises above zero.
     *
     * <p>The row takes its identifiers from the event itself, so the row key and the
     * {@code eventId} a consumer deduplicates on hold one value, and {@code aggregate_id} takes the
     * message key the relay publishes under, from {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}.
     *
     * <p>The payload is one flat JSON object carrying the five envelope properties beside the
     * payload properties, so no {@code envelope} key reaches the row. {@link PublishGate} writes and
     * checks it: the class must name a registered event type, that type must belong on its topic,
     * neither sensitive-data screen may refuse the written JSON, the document its contract version
     * selects must accept it, it must fit the platform byte ceiling, and that version must be one a
     * producer may still write. The row then stores exactly the checked text and the relay publishes
     * those bytes unchanged. A malformed or sensitive-bearing event therefore fails while the
     * caller's transaction can still roll back. A failure message holds JSON pointers, broken
     * keywords and property names only, so no card number and no account identifier reaches a log
     * through it.
     *
     * @param event the event to enqueue, a {@link TransactionPosted}
     * @return the row saved, carrying the event identifier the relay publishes under
     * @throws NullPointerException     if {@code event} is {@code null}
     * @throws IllegalArgumentException if {@code event} is not the event type this service
     *                                  publishes, if that type has no contract, if the written
     *                                  payload breaks the contract its type names, or if the version
     *                                  it declares is retained rather than published
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEventEntity write(Object event) {
        EventEnvelope envelope = envelopeOf(event);
        String eventType = envelope.eventType();
        String payload = PublishGate.checkedJsonOf(recordOf(event));

        return outboxEvents.save(correlated(new OutboxEventEntity(envelope.eventId(), eventType,
                envelope.aggregateId(), payload, clock.instant())));
    }

    /**
     * Stamps one row with the two correlation identifiers the writing thread is working under.
     *
     * <p>ADDITIVE. The values come from the ambient scope
     * {@code config/CorrelationContextFilter} or the listener opened, rather than from a parameter,
     * so no domain method between that scope and this writer carries an identifier it does not
     * otherwise use.
     *
     * <p>A row written outside any scope starts its own trace: it adopts its own event identifier
     * as the correlation identifier, so every published record carries one and a reader can always
     * join a record to what followed it. Causation stays absent on such a row, because nothing
     * caused it, and an absent causation contributes no record header when
     * {@code outbox/OutboxRelay} publishes the row.
     *
     * @param row the row about to be saved
     * @return the same row, stamped
     */
    private static OutboxEventEntity correlated(OutboxEventEntity row) {
        row.recordCorrelation(
                EventCorrelation.currentCorrelationId().orElseGet(row::getEventId),
                EventCorrelation.currentEventId().orElse(null));
        return row;
    }

    /**
     * Reads one ledger event as the record {@link PublishGate} takes.
     *
     * <p>The gate resolves the event type from the class rather than from a property of the written
     * JSON, so it takes a record and not an arbitrary object. {@link #envelopeOf(Object)} has
     * already refused every class this service does not publish by the time this method is reached,
     * and this method states the remaining condition rather than assuming it.
     *
     * @param event the event to read
     * @return the same event, as a record
     * @throws NullPointerException     if {@code event} is {@code null}
     * @throws IllegalArgumentException if {@code event} is not a record
     */
    private static Record recordOf(Object event) {
        Objects.requireNonNull(event, "event must be present");

        if (event instanceof Record record) {
            return record;
        }
        throw new IllegalArgumentException("the ledger posting service publishes "
                + TransactionPosted.EVENT_TYPE + " alone, and " + event.getClass().getName()
                + " is not a record at all");
    }

    /**
     * Reads the five envelope values off one ledger event.
     *
     * <p>Each record carries those five values inline and returns them through its own
     * {@code envelope()} accessor, so the row and the payload cannot disagree.
     *
     * @param event the event to read
     * @return the envelope the event carries
     * @throws NullPointerException     if {@code event} is {@code null}
     * @throws IllegalArgumentException if {@code event} is not the event type this service
     *                                  publishes
     */
    private static EventEnvelope envelopeOf(Object event) {
        Objects.requireNonNull(event, "event must be present");

        return switch (event) {
            case TransactionPosted posted -> posted.envelope();
            default -> throw new IllegalArgumentException("the ledger posting service publishes "
                    + TransactionPosted.EVENT_TYPE + " alone, and " + event.getClass().getName()
                    + " is not it");
        };
    }
}
