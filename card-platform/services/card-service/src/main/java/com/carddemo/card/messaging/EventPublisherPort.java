package com.carddemo.card.messaging;

/**
 * The card service publishes every event through this interface.
 *
 * <p>ADDITIVE. No COBOL program and no copybook defines this contract. The locator below is a
 * reference, not an ancestor.
 *
 * <p>Source reference: the {@code WIRTE-JOBSUB-TDQ} paragraph at
 * {@code app/cbl/CORPT00C.cbl:L515-L523} writes one record to a Customer Information Control
 * System (CICS) transient data queue.
 *
 * <p>{@code com.carddemo.card.outbox.OutboxRelay} is the only caller of {@code publish} in this
 * service. The card row and the outbox row commit in one local transaction, and the relay
 * publishes afterwards in a separate transaction. Request handling publishes nothing.
 *
 * <p>A failed publish throws an unchecked exception and leaves the outbox row unpublished for the
 * next tick.
 *
 * <p>A different event bus needs one new implementation of this interface and no other change.
 *
 * <p>Rationale for this addition lives in {@code card-platform/docs/decision-log.md}.
 */
public interface EventPublisherPort {

    /**
     * Publishes one event to one topic.
     *
     * @param topic   the destination topic name, which the caller reads from configuration
     * @param key     the eleven-digit account identifier, matching {@code XREF-ACCT-ID PIC 9(11)}
     *                at {@code app/cpy/CVACT03Y.cpy:L7}. Callers pass all eleven characters,
     *                leading zeros included.
     * @param payload the event body, already serialized as JavaScript Object Notation (JSON) and
     *                taken from the {@code payload} column of {@code outbox_event}
     */
    void publish(String topic, String key, String payload);
}
