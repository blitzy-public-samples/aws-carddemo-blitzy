package com.carddemo.authorization.messaging;

/**
 * Publishes one serialized event payload to one topic on the event bus.
 *
 * <p>ADDITIVE. This interface has no COBOL ancestor. No program, copybook, or job in the CardDemo
 * source declares an event bus or a publish abstraction.
 *
 * <p>A different event bus needs one more implementation of this interface.
 *
 * <p>Rationale for this addition lives in {@code card-platform/docs/decision-log.md}.
 */
public interface EventPublisherPort {

    /**
     * Publishes one payload to one topic under one message key.
     *
     * <p>A broker keeps message order inside a single partition, and the key selects the partition.
     * Keying on the account identifier keeps the events of one account in order.
     *
     * <p>An implementation reports a failed publish with an unchecked exception.
     *
     * @param topic   the destination topic name, which the caller reads from configuration
     * @param key     the account identifier, declared {@code XREF-ACCT-ID PIC 9(11)} at
     *                {@code app/cpy/CVACT03Y.cpy:L7}. Eleven digits, and a {@code String} keeps
     *                the leading zeros
     * @param payload one event, serialized as JavaScript Object Notation (JSON) before the call
     */
    void publish(String topic, String key, String payload);
}
