package com.carddemo.account.messaging;

/**
 * The account service publishes every event through this interface.
 *
 * <p>ADDITIVE. This interface has no COBOL ancestor. The CardDemo source carries no event
 * bus and no publish abstraction.
 *
 * <p>Source reference: the {@code WIRTE-JOBSUB-TDQ} paragraph at
 * {@code app/cbl/CORPT00C.cbl:L515-L523} writes one record to a Customer Information Control System
 * (CICS) transient data queue. That is the one asynchronous handoff in the source, and it is a
 * reference rather than an ancestor.
 *
 * <p>A caller publishes after the local transaction that recorded the change has committed, so
 * request handling publishes nothing.
 *
 * <p>A failed publish throws an unchecked exception.
 *
 * <p>A different event bus needs one new implementation of this interface and no other change.
 */
public interface EventPublisherPort {

    /**
     * Pattern every {@code aggregateId} argument matches: exactly eleven decimal digits, the width
     * of {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}. The same pattern
     * constrains {@code aggregateId} in every event schema under
     * {@code card-platform/libs/event-contracts/src/main/resources/schemas}.
     */
    String AGGREGATE_ID_PATTERN = "^[0-9]{11}$";

    /**
     * Publishes one event to one topic, keyed on the aggregate the event belongs to.
     *
     * <p>A broker orders messages within a partition only, and the message key selects the
     * partition, so the events of one account stay in order. The implementation derives that key
     * from {@code aggregateId} and takes no separate key argument, so the key and the payload
     * cannot name different accounts. It checks {@code aggregateId} against
     * {@link #AGGREGATE_ID_PATTERN} and rejects any other value.
     *
     * <p>Every implementation binds the payload to {@code topic} before it sends. It reads
     * {@code eventType} from the payload envelope, looks that type up in
     * {@code com.carddemo.events.serde.EventContracts}, and rejects the call when the type does not
     * belong on {@code topic}. The event type therefore has one source, the payload, and a caller
     * cannot route an account state change onto a transaction topic.
     *
     * <p>Every implementation also validates the payload against the versioned schema document that
     * event type names, and rejects a payload that fails it.
     *
     * <p>A rejection message holds a JSON pointer, a broken keyword, an event type, a topic name or
     * a length, and never a value read from the payload. A caller that logs a rejection therefore
     * records no account identifier, no customer name and no Social Security number.
     *
     * @param topic       the destination topic name, which the caller reads from configuration
     * @param aggregateId the eleven-digit account identifier the event belongs to, and the value
     *                    the payload carries in its own {@code aggregateId} field. Callers pass all
     *                    eleven characters, leading zeros included.
     * @param payload     the event body, already serialized as JavaScript Object Notation (JSON)
     *                    and taken from the {@code payload} column of {@code outbox_event}
     * @throws IllegalArgumentException when an argument is absent, when {@code aggregateId} misses
     *         {@link #AGGREGATE_ID_PATTERN}, when the payload declares an event type that does not
     *         belong on {@code topic}, or when the payload fails the document that type names
     */
    void publish(String topic, String aggregateId, String payload);
}
