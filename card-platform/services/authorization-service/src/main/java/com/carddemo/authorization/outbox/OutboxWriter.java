package com.carddemo.authorization.outbox;

import com.carddemo.authorization.entity.OutboxEventEntity;
import com.carddemo.authorization.repository.OutboxEventRepository;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.TransactionAuthorized;
import com.carddemo.events.TransactionDeclined;
import com.carddemo.events.correlation.EventCorrelation;
import com.carddemo.events.serde.EventContracts;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one event into {@code outbox_event}, in the transaction that already holds the decision.
 *
 * <p>No CardDemo program stores an event. The source holds one asynchronous handoff:
 * paragraph {@code WIRTE-JOBSUB-TDQ} at {@code app/cbl/CORPT00C.cbl:L515-L523} writes a record to a
 * Customer Information Control System (CICS) transient data queue, and a separate job reads that
 * record later. This class plays the write half of the handoff and {@link OutboxRelay} plays the
 * pick-up half.
 *
 * <p>The decision and the row commit together, or neither commits. Both write operations carry
 * {@code @Transactional(propagation = MANDATORY)}, so each joins the transaction
 * {@code domain/AuthorizationService} opened around the decision and neither can start one: a call
 * arriving with no transaction in progress is refused rather than committing an outbox row on its own,
 * which would leave an event with no decision behind it. The source offers no atomicity to reproduce.
 * {@code app/cbl/CBTRN02C.cbl:L440-L442} runs three writes under no condition and
 * {@code :L444} is the exit, and all eight file definitions of {@code app/csd/CARDDEMO.CSD} carry
 * {@code JOURNAL(NO)} at {@code :L7} and {@code RECOVERY(NONE)} at {@code :L9}.
 *
 * <p>That atomicity has no source ancestor, and neither does the idempotency the primary key of the
 * table gives: {@code event_id} is that key, so one event yields one row.
 *
 * <p>Every decided call writes exactly one row. An approval writes one {@link TransactionAuthorized}
 * and a decline writes one {@link TransactionDeclined}, never both and never two of either. A call
 * whose card resolved to no account writes its decline too, keyed on the account the caller
 * declared, which is the subject that decision applies to.
 *
 * <p>Each payload is one flat JavaScript Object Notation (JSON) object. The five envelope
 * properties sit beside the payload properties, so no nested key reaches the row:
 * {@code transaction-authorized-v1.json} names nineteen required properties, and the three governed
 * transaction-declined documents name the account-keyed form, the account-less form released for
 * reject code {@code 0100}, and the detail-bearing form every decline publishes under.
 *
 * <p>Every payload is measured against the document its event type and contract version select,
 * before the row is saved. A payload the publish gate refuses therefore never reaches the table,
 * where it would stall the relay. A rejection message holds JSON pointers and broken keywords, so no
 * card number and no account identifier reaches a log through it.
 *
 * <p>This class publishes nothing, holds no broker type, and starts no thread. It allocates no
 * transaction identifier either: {@code domain/TransactionIdentifierSource} allocates the sixteen
 * characters of {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} from the database
 * sequence {@code transaction_id_seq}, which replaces the browse-backwards allocation at
 * {@code app/cbl/COTRN02C.cbl:L444-L449} and {@code app/cbl/COBIL00C.cbl:L212-L217}.
 *
 * <p>This class writes no {@code processed_event} marker. That marker guards an event a consumer
 * receives, and this class sits on the produce side of the service, so it has no inbound delivery to
 * guard. The service does register consumers: {@code messaging/AccountStateChangedConsumer} and
 * {@code messaging/CardUpdatedConsumer} keep {@code account_credit_snapshot} and {@code card_xref}
 * current, and each of the two writes its marker into the same transaction as the replica row it
 * applies, acknowledging the delivery only after that transaction commits.
 */
@Component
public class OutboxWriter {

    /** Stores the rows {@link OutboxRelay} later reads. */
    private final OutboxEventRepository outboxEvents;

    /**
     * Writes one event record to text. Jackson 3, matching the platform.
     *
     * <p>The mapper belongs to this class. A payload is therefore shaped by the contract of its own
     * event, and by no setting of the web layer.
     */
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * Stamps {@code created_at}, in Coordinated Universal Time.
     *
     * <p>{@link OutboxRelay} sweeps rows oldest first, so one zone governs every row.
     */
    private final Clock clock = Clock.systemUTC();

    /**
     * Takes the store this writer saves through.
     *
     * @param outboxEvents store of unpublished events
     * @throws NullPointerException when {@code outboxEvents} is {@code null}
     */
    public OutboxWriter(OutboxEventRepository outboxEvents) {
        this.outboxEvents = Objects.requireNonNull(outboxEvents, "outboxEvents must be present");
    }

    /**
     * Writes one approval event as an unpublished row.
     *
     * <p>Version 1 of the contract declares nineteen properties, of which five are the envelope
     * and fourteen the payload. Twelve of the payload properties come from the twelve field moves
     * of paragraph {@code 2000-POST-TRANSACTION} at {@code app/cbl/CBTRN02C.cbl:L425-L436}, the
     * thirteenth is the account identifier the cross-reference read at
     * {@code app/cbl/CBTRN02C.cbl:L382-L383} resolved, and the fourteenth is the currency. Version 2
     * adds one payload property, the card token a consumer correlates a card on.
     *
     * <p>The masked card number and the currency have no COBOL ancestor. The source masks nothing:
     * the card number occupies its full sixteen characters unprotected on the card detail map at
     * {@code app/bms/COCRDSL.bms:L96} with {@code LENGTH=16} at {@code :L99}.
     *
     * @param event the approval event, carrying its own envelope and its masked card number
     * @return the row saved, keyed on the event identifier {@link OutboxRelay} publishes under
     * @throws NullPointerException     when {@code event} is {@code null}
     * @throws IllegalArgumentException when the written payload breaks the document its contract
     *                                  version selects
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEventEntity writeAuthorized(TransactionAuthorized event) {
        Objects.requireNonNull(event, "event must be present");

        return write(event.envelope(), event);
    }

    /**
     * Writes one decline event as an unpublished row.
     *
     * <p>A decline is ordinary traffic. {@code app/cbl/CBTRN02C.cbl:L229-L230} moves 4 into the
     * return code once the reject count rises above zero, and the batch job then ends normally.
     *
     * <p>The event carries the reject code and its text as one value of {@code DeclineReason}. The
     * four values are assigned at {@code app/cbl/CBTRN02C.cbl:L385-L387}, {@code :L397-L399},
     * {@code :L410-L412} and {@code :L417-L419}, and their widths come from
     * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code :L181} and
     * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at {@code :L182}. Those two fields are the
     * eighty-byte trailer of the reject record at {@code :L176-L178}.
     *
     * <p>The three-hundred-and-fifty-byte reject payload the source writes at
     * {@code app/cbl/CBTRN02C.cbl:L446-L465} stays out of this row. The ledger posting service owns
     * it, in its own {@code rejected_transaction} table.
     *
     * <p>TWO VERSIONS REACH THIS METHOD, AND THE REJECT CODE SELECTS WHICH. Version 1 keys on the
     * eleven-digit account identifier the cross-reference resolved, and reasons {@code 0101},
     * {@code 0102} and {@code 0103} are the three that reach it, because each fires after that read
     * succeeded.
     *
     * <p>Reject code {@code 0100} reaches version 2. It is assigned at
     * {@code app/cbl/CBTRN02C.cbl:L385}, inside the {@code INVALID KEY} branch of the cross-reference
     * read at {@code :L383}, and the short-circuit at {@code :L376-L378} stops the account read from
     * running, so no account identifier exists to key an event on.
     * {@code schemas/transaction-declined-v2.json} carries no {@code accountId} and keys on the
     * sixteen-character transaction identifier instead, which {@code aggregate_id} holds as readily as
     * eleven digits. {@code domain/AuthorizationService} writes it in the transaction that recorded
     * the decision, so one authorization call writes exactly one outbox row whichever outcome it
     * reached, which is what AAP transformation rule T4 requires.
     *
     * @param event the decline event, carrying its own envelope, its reject code and its masked card
     *              number
     * @return the row saved, keyed on the event identifier {@link OutboxRelay} publishes under
     * @throws NullPointerException     when {@code event} is {@code null}
     * @throws IllegalArgumentException when the event carries neither key form, or when the written
     *                                  payload breaks the document its contract version selects
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEventEntity writeDeclined(TransactionDeclined event) {
        Objects.requireNonNull(event, "event must be present");

        return write(event.envelope(), event);
    }

    /**
     * Writes and checks one payload, then saves the single row that carries it.
     *
     * <p>The row takes its identifiers from the envelope the event itself carries. The primary key of
     * the row, the message key the relay publishes under, and the values inside the payload therefore
     * hold one account and one event identifier.
     *
     * <p>The order of the three steps is the guarantee. The account key is read first, the payload is
     * written and measured second, and the row is saved last. Every rejection therefore leaves the
     * caller's transaction able to roll back with nothing stored.
     *
     * @param envelope the five envelope values of the event
     * @param event    the event record to store
     * @return the row saved
     * @throws IllegalArgumentException when the envelope carries neither key form, when the written
     *                                  payload breaks the document its contract version selects, or
     *                                  when that version is retained rather than published
     */
    private OutboxEventEntity write(EventEnvelope envelope, Record event) {
        String eventType = envelope.eventType();
        String messageKey = messageKeyOf(envelope);
        String payload = jsonMapper.writeValueAsString(event);

        List<String> violations = EventContracts.publishViolationsOf(eventType, payload);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(
                    EventContracts.describeViolations(eventType, violations));
        }

        return outboxEvents.save(correlated(new OutboxEventEntity(envelope.eventId(), eventType,
                messageKey, payload, clock.instant())));
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
     * Reads the message key the row records, and the relay publishes under.
     *
     * <p>One form reaches a row this method writes: eleven decimal digits, the account identifier of
     * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. The key travels as text and
     * nothing pads, so the leading zeros of an identifier belong to the value and survive: account
     * seven renders as eleven characters ending in seven. Keying every event of one account on that
     * value keeps the events of that account on one partition and in publish order, which is what the
     * ledger needs to apply them to a balance in the order they were decided.
     *
     * <p>Every decision this service records names an account, reject code {@code 0100} of
     * {@code app/cbl/CBTRN02C.cbl:L385-L387} included: the cross-reference read resolves the subject
     * where it can, the caller declares it where that read misses, and a call that establishes
     * neither is refused before a decision by
     * {@code domain/AuthorizationService.CardNumberNotFoundInCrossReferenceException}. So there is one
     * key form to write.
     *
     * <p>{@link EventEnvelope#AGGREGATE_KEY_PATTERN} still admits a second form, the sixteen-character
     * transaction identifier, because a record published under
     * {@code schemas/transaction-declined-v2.json} before that redesign is still on its topic and a
     * consumer still reads it. Reading it is what retention buys; writing it is what this method
     * refuses. The CHECK constraint {@code ck_outbox_event_aggregate_id} was narrowed to the account
     * form by migration {@code V19} and carries {@code NOT VALID}, so it holds every row written from
     * that migration forward and leaves the older rows the record they are.
     *
     * @param envelope the five envelope values of the event
     * @return the message key: eleven decimal digits
     * @throws IllegalArgumentException when the envelope carries another form. The message names the
     *                                  pattern and the length, and never the value
     */
    private static String messageKeyOf(EventEnvelope envelope) {
        if (!envelope.carriesAccountKey()) {
            throw new IllegalArgumentException("aggregateId must match "
                    + EventEnvelope.AGGREGATE_ID_PATTERN + ", the account key form column"
                    + " aggregate_id records for every row this service writes, and the supplied"
                    + " envelope holds " + envelope.aggregateId().length() + " characters");
        }
        return envelope.aggregateId();
    }
}
