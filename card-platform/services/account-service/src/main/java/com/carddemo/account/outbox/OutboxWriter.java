package com.carddemo.account.outbox;

import com.carddemo.account.entity.OutboxEventEntity;
import com.carddemo.account.messaging.AccountStateChanged;
import com.carddemo.account.messaging.CustomerContextChanged;
import com.carddemo.account.repository.OutboxEventRepository;
import com.carddemo.events.correlation.EventCorrelation;
import com.carddemo.events.serde.PublishGate;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one {@code outbox_event} row inside the transaction its caller opened, and publishes
 * nothing.
 *
 * <p>No COBOL ancestor. The one ancestor construct is the Transient Data Queue write of the
 * Customer Information Control System (CICS) at {@code app/cbl/CORPT00C.cbl:L515-L523}. One program
 * writes the record there, and a separate job collects it later.
 *
 * <p>{@code app/cbl/COACTUPC.cbl} rewrites two files in one unit of work. The account rewrite at
 * {@code app/cbl/COACTUPC.cbl:L4066} fails with no rollback at {@code
 * app/cbl/COACTUPC.cbl:L4076-L4081}. The customer rewrite at {@code app/cbl/COACTUPC.cbl:L4086}
 * reaches {@code SYNCPOINT ROLLBACK} at {@code app/cbl/COACTUPC.cbl:L4099-L4101}. All eight file
 * definitions in {@code app/csd/CARDDEMO.CSD} carry {@code RECOVERY(NONE)} and {@code JOURNAL(NO)}.
 *
 * <p>The account row, the customer row and this row commit together.
 * {@link #write(AccountStateChanged)} joins the transaction its caller opened, with
 * {@link Propagation#MANDATORY}, and opens none of its own.
 *
 * <p>Both write methods measure the serialized event against its governed schema document before
 * they save it, so the earliest boundary an event crosses is also the first one that checks it. A
 * payload the document refuses fails the caller's transaction, which leaves neither the business
 * state nor the event row stored. Validating only at publish time would move that refusal to the
 * relay, hours after the state it accompanied had committed.
 */
@Component
public class OutboxWriter {

    /**
     * Widest {@code payload} this writer stores, counted in octets.
     *
     * <p>The check constraint {@code ck_outbox_event_payload_bytes} in
     * {@code src/main/resources/db/migration/V1__schema.sql} holds the column to
     * {@value #PAYLOAD_MAX_BYTES} octets. PostgreSQL counts that column in UTF-8, and this writer
     * measures the same encoding.
     */
    public static final int PAYLOAD_MAX_BYTES = 8192;

    /** Stores the row this writer inserts, through the {@code save} it inherits. */
    private final OutboxEventRepository outboxEventRepository;

    /**
     * Takes the repository this writer saves through.
     *
     * <p>No mapper arrives with it. {@link PublishGate} serializes every event this class stores, so
     * the text a row holds is the text a gate checked; a mapper of this class's own would write bytes
     * no gate had seen. {@code config/KafkaProducerConfig} still declares
     * {@code accountEventObjectMapper}, which {@code outbox/OutboxRelay} reads a stored payload back
     * with.
     *
     * @param outboxEventRepository store of unpublished events
     * @throws NullPointerException when {@code outboxEventRepository} is {@code null}
     */
    public OutboxWriter(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = Objects.requireNonNull(outboxEventRepository,
                "outboxEventRepository must be present");
    }

    /**
     * Writes one account state change as an unpublished {@code outbox_event} row.
     *
     * <p>The row carries the five
     * {@link com.carddemo.events.EventEnvelope EventEnvelope} values the event already holds. Its
     * primary key is the event identifier a consumer deduplicates on. A second write of one event
     * fails on that key. On insert {@code published} is {@code false} and {@code published_at} is
     * null.
     *
     * <p>{@link Propagation#MANDATORY} joins the transaction its caller opened and starts none. A
     * call with no transaction open fails, and a failure anywhere in that caller's unit of work
     * leaves no row.
     *
     * <p>The aggregate identifier is the account identifier: eleven digits held as text, which
     * keeps a leading zero. Its width comes from {@code XREF-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT03Y.cpy:L7}.
     *
     * <p>The canonical constructor of {@link AccountStateChanged} has already checked all eleven
     * components, and the constructor of {@link OutboxEventEntity} checks every column value this
     * method supplies. No message thrown here holds a payload, an account identifier or a monetary
     * value.
     *
     * @param event the event to store
     * @throws NullPointerException                when {@code event} is {@code null}
     * @throws IllegalArgumentException            when the serialized event breaks its contract
     *                                             document, or exceeds
     *                                             {@value #PAYLOAD_MAX_BYTES} octets
     * @throws tools.jackson.core.JacksonException when the event cannot be written as text
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(AccountStateChanged event) {
        Objects.requireNonNull(event, "event must be present");

        String payload = writeAndCheck(event.eventType(), event.eventId(), event);

        outboxEventRepository.save(correlated(new OutboxEventEntity(event.eventId(),
                event.eventType(), payload, event.aggregateId(), event.occurredAt())));
    }

    /**
     * Writes one {@code customer.context-changed} event into the outbox, in the caller's
     * transaction.
     *
     * <p>The name differs from {@link #write(AccountStateChanged)} on purpose: two overloads named
     * {@code write} would make a mock verification on a null argument ambiguous.
     *
     * <p>The customer detail travels on its own contract rather than on the account event, so a
     * consumer that only maintains balances never reads a cardholder name. The row joins the same
     * transaction as the two record rewrites, so the event and the state commit together.
     *
     * @param event the event to store
     * @throws NullPointerException     when {@code event} is absent
     * @throws IllegalArgumentException when the serialized payload breaks its contract document or
     *                                  exceeds the column width
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeCustomerContext(CustomerContextChanged event) {
        Objects.requireNonNull(event, "event must be present");

        String payload = writeAndCheck(event.eventType(), event.eventId(), event);

        outboxEventRepository.save(correlated(new OutboxEventEntity(event.eventId(),
                event.eventType(), payload, event.aggregateId(), event.occurredAt())));
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
     * Writes one event to text and measures that text against its contract document and its column.
     *
     * <p>Both checks run before the row is saved, and both throw. The caller's transaction is the
     * one that rewrote the account record and the customer record, so a payload the contract refuses
     * rolls that rewrite back and leaves neither the state nor the event stored, rather than leaving an
     * event no consumer can deserialize beside committed business state. Rationale and the alternatives
     * weighed: {@code card-platform/docs/decision-log.md}.
     *
     * <p>{@link PublishGate} is the one publish-side boundary of this platform, and it is what this
     * method crosses: the class must name a registered event type, that type must belong on its
     * topic, neither sensitive-data screen may refuse the written JSON, the document its contract
     * version selects must accept it, it must fit the platform byte ceiling, and that version must be
     * one a producer may still write. The row then stores exactly the text the gate checked and the
     * relay publishes those bytes unchanged, so nothing a gate has not seen reaches a topic. The
     * account service publishes two types and this method serves both, which is why the type and the
     * identifier arrive as arguments rather than being read from a particular record.
     *
     * <p>The screens are the substantive change a security review asked for. A cardholder name or an
     * address line is narrative text an administrator supplies, and until the gate ran on this path a
     * Primary Account Number written into one of them committed with the account rewrite and reached
     * the notification read model.
     *
     * <p>A refusal message holds JavaScript Object Notation pointers, broken keyword names and the
     * event identifier. None of those is a customer value, so no account identifier, cardholder
     * name, Social Security number or monetary amount reaches a log through a refusal.
     *
     * @param eventType the governed event type this writer publishes, named in a refusal
     * @param eventId   the identifier of the event being written, named in a refusal
     * @param event     the event to write, a record of this service
     * @return the event as JSON text, checked against its document and its column width
     * @throws IllegalArgumentException when the written payload breaks its contract document, when
     *                                  the version it declares is retained rather than published, or
     *                                  when it exceeds {@value #PAYLOAD_MAX_BYTES} octets of UTF-8
     */
    private String writeAndCheck(String eventType, UUID eventId, Record event) {
        String payload;
        try {
            payload = PublishGate.checkedJsonOf(event);
        } catch (IllegalArgumentException refused) {
            throw new IllegalArgumentException("event " + eventId + " breaks its contract: "
                    + refused.getMessage(), refused);
        }

        int octets = payload.getBytes(StandardCharsets.UTF_8).length;
        if (octets > PAYLOAD_MAX_BYTES) {
            throw new IllegalArgumentException("event " + eventId + " serializes to " + octets
                    + " octets and the payload column holds " + PAYLOAD_MAX_BYTES);
        }
        return payload;
    }
}
