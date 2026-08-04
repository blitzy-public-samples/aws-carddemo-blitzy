package com.carddemo.authorization.messaging;

/**
 * Publishes one serialized event payload to one topic on the event bus.
 *
 * <p>ADDITIVE. This interface has no COBOL ancestor. No program, copybook, or job in the CardDemo
 * source declares an event bus or a publish abstraction.
 *
 * <p>A different event bus needs one more implementation of this interface.
 */
public interface EventPublisherPort {

    /**
     * Pattern every {@code aggregateId} argument matches: exactly eleven decimal digits, the width
     * of {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. The same pattern
     * constrains {@code aggregateId} in every event schema under
     * {@code card-platform/libs/event-contracts/src/main/resources/schemas}.
     */
    String AGGREGATE_ID_PATTERN = "^[0-9]{11}$";

    /**
     * Publishes one payload to one topic, keyed on the aggregate the payload belongs to.
     *
     * <p>A broker keeps message order inside a single partition, and the message key selects the
     * partition. The implementation derives that key from {@code aggregateId} and takes no
     * separate key argument, so the key and the payload cannot name different accounts.
     *
     * <p>An implementation checks {@code aggregateId} against {@link #AGGREGATE_ID_PATTERN} and
     * rejects any other value. An implementation reports a failed publish with an unchecked
     * exception.
     *
     * <p>Every implementation binds the payload to {@code topic} before it sends. It reads
     * {@code eventType} from the payload envelope, looks that type up in
     * {@code com.carddemo.events.serde.EventContracts}, and rejects the call when the type does not
     * belong on {@code topic}. The event type therefore has one source, the payload, and neither an
     * approval nor a decline can reach the topic the other travels on.
     *
     * <p>Every implementation also validates the payload against the versioned schema document that
     * event type names, and rejects a payload that fails it.
     *
     * <p>A rejection message holds a JSON pointer, a broken keyword, an event type, a topic name or
     * a length, and never a value read from the payload. A caller that logs a rejection therefore
     * records no Primary Account Number (PAN) and no account identifier.
     *
     * @param topic       the destination topic name, which the caller reads from configuration
     * @param aggregateId the account identifier the event belongs to, and the value the payload
     *                    carries in its own {@code aggregateId} field. Eleven digits, and a
     *                    {@code String} keeps the leading zeros
     * @param payload     one event, serialized as JavaScript Object Notation (JSON) before the
     *                    call
     * @throws IllegalArgumentException when an argument is absent, when {@code aggregateId} misses
     *         {@link #AGGREGATE_ID_PATTERN}, when the payload declares an event type that does not
     *         belong on {@code topic}, or when the payload fails the document that type names
     */
    void publish(String topic, String aggregateId, String payload);
}
